package com.karin.streamtv.karinlink

/**
 * Lógica pura del control remoto: límite de frames y diff de texto.
 *
 * Todo en JVM puro para poder unit testearlo sin Robolectric. La actividad
 * solo decide cuándo llamar; aquí no hay `android.*`.
 */

/**
 * Limita a un frame cada [intervalMs].
 *
 * El cursor, el scroll del touchpad y la barra comparten una sola instancia,
 * de modo que el presupuesto total es el mismo: el gesto que llegue antes se
 * lleva el frame y el otro espera 40 ms, que es tiempo de más para que se
 * note. Sin esto el scroll de dos dedos se enviaba a la tasa cruda del panel
 * táctil, entre 60 y 120 frames por segundo.
 */
class FrameThrottle(private val intervalMs: Long) {

    private var lastSentAt: Long? = null

    /** Devuelve true si [now] cabe en el presupuesto y consume el frame. */
    fun allow(now: Long = System.currentTimeMillis()): Boolean {
        val last = lastSentAt
        if (last != null && now - last < intervalMs) return false
        lastSentAt = now
        return true
    }

    fun reset() {
        lastSentAt = null
    }
}

/**
 * Qué mandar a la TV cuando cambia el campo de texto local.
 *
 * El receptor ([RemoteControlHub]) solo sabe insertar texto en el `EditText`
 * enfocado y teclear `DEL`, así que un cambio local se traduce en "borra N,
 * escribe esto". El orden importa: borrar antes de escribir para emular el
 * reemplazo de una selección.
 */
object TextEdit {

    /**
     * Ráfaga máxima de borrados por un solo cambio.
     *
     * Un "seleccionar todo y suprimir" sobre un texto largo mandaría un frame
     * por carácter; se acota para no inundar el WebSocket. Es un caso raro y
     * el campo propio se limpia con la bandera de sincronización de la
     * actividad, que ni siquiera llega aquí.
     */
    const val MAX_DELETE = 30

    sealed class Edit {
        /** Se teclearon estos caracteres, en este orden. */
        data class Typed(val text: String) : Edit()

        /** Hay que borrar estos caracteres con `KEYCODE_DEL`. */
        data class Deleted(val count: Int) : Edit()
    }

    /**
     * Traduce los parámetros de `TextWatcher.onTextChanged`.
     *
     * @param start inicio del tramo cambiado
     * @param before cuántos caracteres había (se reemplazan)
     * @param count cuántos caracteres hay ahora
     * @param text el contenido nuevo completo
     */
    fun diff(start: Int, before: Int, count: Int, text: CharSequence?): List<Edit> {
        val deletes = before.coerceAtLeast(0).coerceAtMost(MAX_DELETE)
        val inserted = if (text != null && start >= 0 && count > 0 &&
            start + count <= text.length
        ) {
            text.subSequence(start, start + count).toString()
        } else {
            ""
        }
        if (deletes == 0 && inserted.isEmpty()) return emptyList()
        return buildList {
            if (deletes > 0) add(Edit.Deleted(deletes))
            if (inserted.isNotEmpty()) add(Edit.Typed(inserted))
        }
    }
}
