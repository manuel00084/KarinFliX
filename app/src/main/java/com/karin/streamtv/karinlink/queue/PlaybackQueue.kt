package com.karin.streamtv.karinlink.queue

/**
 * Una entrada de la cola de reproducción.
 *
 * Un mismo tipo sirve para lo que llega de otro equipo y para lo que se sube
 * como fichero, porque en ambos casos lo que se reproduce es una referencia a
 * un medio: un enlace directo, un embed, o un fichero que ya está en este
 * dispositivo. [localFile] distingue el último caso para poder borrarlo cuando
 * termine, que es lo único que hay que hacer distinto.
 */
data class QueueItem(
    /** Estable entre el móvil que envía y la TV que reproduce, para poder quitarlo. */
    val id: String,
    val title: String,
    /** Enlace directo, `content://` o ruta local. Es lo que va al reproductor. */
    val videoUrl: String,
    val embedUrl: String = "",
    val episodeUrl: String = "",
    val siteName: String = "",
    /** Si el fichero llegó subido desde otro equipo, se borra al terminar. */
    val localFile: Boolean = false,
) {
    /** Sin enlace directo no hay nada que abrir: un embed sí tiene su propio camino. */
    val isPlayable: Boolean get() = videoUrl.isNotBlank() || embedUrl.isNotBlank()
}

/**
 * Qué tiene que hacer el reproductor tras un cambio en la cola.
 *
 * La cola no toca Activities ni Intents: decide y lo dice, y quien la usa lo
 * ejecuta. Así toda la lógica que decide qué se ve después se testea en una JVM
 * normal, en vez de descubrir en un dispositivo que al terminar un capítulo la
 * cola se quedaba bloqueada.
 */
sealed class QueueAction {
    /** No hay que tocar el reproductor. */
    object None : QueueAction()

    /** Empezar a reproducir este elemento. */
    data class Start(val item: QueueItem) : QueueAction()

    /** Parar lo que sonaba y empezar este otro. */
    data class StopAndStart(val stopped: QueueItem, val next: QueueItem) : QueueAction()

    /** Parar lo que sonaba y no seguir: la cola se quedó vacía. */
    data class Stop(val item: QueueItem) : QueueAction()
}

/**
 * La cola de reproducción, como lista pura.
 *
 * Guarda los elementos en orden con el primero siendo el que suena o va a
 * sonar. Las dos operaciones que pide el usuario son distintas y por eso hay
 * dos métodos y no un `add` con banderas:
 *
 * - "Reproducir ahora": lo que suena se para y se sustituye. Lo pendiente se
 *   descarta, porque quien manda un vídeo de otra cosa no quiere que el anterior
 *   se reanude después.
 * - "Añadir a la cola": lo que suena sigue, y lo nuevo entra detrás. Es el caso
 *   de ver el último capítulo de una serie y añadir los primeros de otras dos.
 */
class PlaybackQueue(private val nextId: () -> String) {

    private val items = ArrayList<QueueItem>()

    /** Qué está sonando ahora mismo, o lo primero que empezará. */
    val current: QueueItem? get() = items.firstOrNull()

    /** Lo que viene después, en orden. */
    val upcoming: List<QueueItem> get() = items.drop(1)

    val size: Int get() = items.size

    val isEmpty: Boolean get() = items.isEmpty()

    /**
     * Si algo está sonando ahora mismo.
     *
 * No es lo mismo que [current]: una cola recién llenada tiene un primero que
     * todavía no ha empezado. Esa diferencia decide si hace falta parar el
     * reproductor antes de arrancar el siguiente.
     */
    var isPlaying: Boolean = false
        private set

    fun snapshot(): List<QueueItem> = ArrayList(items)

    /** Sustituye todo lo que hubiera por este elemento y lo pone a sonar. */
    fun playNow(item: QueueItem): QueueAction {
        val stopped = current.takeIf { isPlaying }
        items.clear()
        items.add(item)
        isPlaying = true
        return stopped?.let { QueueAction.StopAndStart(it, item) } ?: QueueAction.Start(item)
    }

    /**
     * Añade detrás de lo que ya está.
     *
     * Si la cola estaba vacía, este elemento pasa a ser el primero y hay que
     * arrancarlo; si no, se queda esperando su turno sin molestar al que suena.
     */
    fun add(item: QueueItem): QueueAction {
        val wasEmpty = items.isEmpty()
        items.add(item)
        if (!wasEmpty) return QueueAction.None

        isPlaying = true
        return QueueAction.Start(item)
    }

    /** Crea el elemento con un id asignado y lo añade. */
    fun enqueue(
        title: String,
        videoUrl: String,
        embedUrl: String = "",
        episodeUrl: String = "",
        siteName: String = "",
        localFile: Boolean = false,
    ): QueueAction = add(
        QueueItem(
            id = nextId(),
            title = title,
            videoUrl = videoUrl,
            embedUrl = embedUrl,
            episodeUrl = episodeUrl,
            siteName = siteName,
            localFile = localFile,
        )
    )

    /**
     * El elemento actual terminó: se va y entra el siguiente.
     *
     * Es la transición que hace que la lista sea una cola de verdad, y la que no
     * se puede simular sin un reproductor delante.
     *
     * Devuelve [QueueAction.Start] y no [QueueAction.StopAndStart] a propósito:
     * el elemento ya terminó solo, así que no hay nada que parar. Ver
     * [skip] para el caso contrario.
     */
    fun completeCurrent(): QueueAction {
        val finished = current
        if (finished == null) {
            isPlaying = false
            return QueueAction.None
        }
        items.removeAt(0)
        val next = items.firstOrNull()
        return if (next == null) {
            isPlaying = false
            QueueAction.Stop(finished)
        } else {
            isPlaying = true
            QueueAction.Start(next)
        }
    }

    /**
     * Salta el elemento actual sin esperar a que termine.
     *
     * A diferencia de [completeCurrent], aquí sí hay algo que parar: el vídeo
     * seguiría sonando por debajo del siguiente si quien lo abre no recibiera la
     * orden explícita.
     */
    fun skip(): QueueAction {
        val playing = current
        if (playing == null) {
            isPlaying = false
            return QueueAction.None
        }
        items.removeAt(0)
        val next = items.firstOrNull()
        return if (next == null) {
            isPlaying = false
            QueueAction.Stop(playing)
        } else {
            isPlaying = true
            QueueAction.StopAndStart(playing, next)
        }
    }

    /**
     * Quita un elemento por id.
     *
     * Quitar el que está sonando implica pararlo, y entonces el siguiente entra
     * en su lugar. Quitar uno de los que faltan no molesta al que suena.
     */
    fun remove(id: String): QueueAction {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return QueueAction.None

        val wasCurrent = index == 0
        val removed = items.removeAt(index)
        if (!wasCurrent) return QueueAction.None

        val next = items.firstOrNull()
        if (next == null) {
            isPlaying = false
            return QueueAction.Stop(removed)
        }
        isPlaying = true
        return QueueAction.StopAndStart(removed, next)
    }

    /** Vacía la cola. Lo que estaba sonando se para si lo estaba. */
    fun clear(): QueueAction {
        val stopped = current.takeIf { isPlaying }
        items.clear()
        isPlaying = false
        return stopped?.let { QueueAction.Stop(it) } ?: QueueAction.None
    }

    /**
     * Reconstruye la cola desde una descripción recibida de otro equipo.
     *
     * Solo para el caso de que la TV pregunte al móvil qué tiene en cola; la
     * lista que manda la TV es la que vale.
     */
    fun replaceAll(with: List<QueueItem>) {
        items.clear()
        items.addAll(with)
        isPlaying = items.isNotEmpty()
    }
}
