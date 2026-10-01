package com.karin.streamtv.cast

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.karin.streamtv.R
import com.karin.streamtv.util.enableTvFocus
import com.karin.streamtv.util.onActionKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Pantalla "Emitir": manda el vídeo actual fuera de este dispositivo.
 *
 * Dos vías en la misma pantalla porque son tecnologías distintas:
 *  - Google Cast, con el [MediaRouteButton] del propio framework (él abre el
 *    selector del sistema y arranca la sesión; elegir una ruta Cast a mano no
 *    es API pública).
 *  - DLNA, con nuestro SSDP + SOAP, listado aquí arriba.
 */
class KarinLinkCastActivity : FragmentActivity() {

    companion object {
        const val EXTRA_URL = "video_url"
        const val EXTRA_TITLE = "video_title"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var searching: TextView
    private lateinit var empty: TextView
    private lateinit var routeHost: FrameLayout

    private var renderers: List<DlnaProtocol.Renderer> = emptyList()
    private var paused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_karinlink_cast)
        enableTvFocus()

        status = findViewById(R.id.tv_cast_status)
        list = findViewById(R.id.dlna_list)
        searching = findViewById(R.id.tv_cast_searching)
        empty = findViewById(R.id.tv_cast_empty)
        routeHost = findViewById(R.id.cast_route_host)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isBlank()) {
            Toast.makeText(this, "No hay ningún vídeo para emitir", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        findViewById<View>(R.id.btn_cast_close).apply {
            setOnClickListener { finish() }
            onActionKey { performClick() }
        }
        findViewById<View>(R.id.btn_cast_refresh).apply {
            setOnClickListener { refresh() }
            onActionKey { performClick() }
        }
        findViewById<View>(R.id.btn_cast_pause).apply {
            setOnClickListener { togglePause() }
            onActionKey { performClick() }
        }
        findViewById<View>(R.id.btn_cast_stop).apply {
            setOnClickListener { stopEmission() }
            onActionKey { performClick() }
        }

        setUpCastButton()
        renderStatus()
        refresh()
    }

    private fun setUpCastButton() {
        val button = MediaRouteButton(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
            contentDescription = "Transmitir a un Chromecast"
        }
        routeHost.addView(button)
        // Sin Play Services (o sin el OptionsProvider) el framework lanza
        // IllegalStateException: en ese caso se oculta Cast y queda solo DLNA.
        runCatching { CastButtonFactory.setUpMediaRouteButton(this, button) }
            .onFailure {
                routeHost.visibility = View.GONE
                status.text = "Cast no disponible en este dispositivo"
            }
    }

    private fun refresh() {
        searching.visibility = View.VISIBLE
        empty.visibility = View.GONE
        list.removeAllViews()
        scope.launch {
            val found = DlnaDiscovery.discover()
            renderers = found
            searching.visibility = View.GONE
            if (found.isEmpty()) {
                empty.visibility = View.VISIBLE
            } else {
                found.forEach { list.addView(row(it)) }
            }
        }
    }

    private fun row(renderer: DlnaProtocol.Renderer): View {
        val secondary = renderer.manufacturer.takeIf { it.isNotBlank() }
            ?.let { " · $it" }.orEmpty()
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            background = getDrawable(R.drawable.selector_card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            text = renderer.name + secondary
            setTextColor(getColor(R.color.text_primary))
            textSize = 15f
            isFocusable = true
            isFocusableInTouchMode = true
            contentDescription = "Emitir en ${renderer.name}"
            setOnClickListener { emit(renderer) }
            onActionKey { performClick() }
        }
    }

    private fun emit(renderer: DlnaProtocol.Renderer) {
        val source = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE)
            ?.takeIf { it.isNotBlank() } ?: "Vídeo"

        status.text = "Preparando la emisión…"
        scope.launch {
            val target = CastSession.prepare(source) ?: run {
                status.text = CastSession.lastError ?: "No se pudo preparar el vídeo"
                return@launch
            }
            val ok = DlnaController.play(renderer, target, title)
            if (ok) {
                CastSession.mark(renderer)
                paused = false
                status.text = "Emitiendo en ${renderer.name}"
            } else {
                status.text = "No se pudo reproducir en ${renderer.name}"
            }
        }
    }

    private fun togglePause() {
        val renderer = CastSession.renderer ?: run {
            Toast.makeText(this, "No hay ninguna emisión activa", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val ok = if (paused) DlnaController.resume(renderer) else DlnaController.pause(renderer)
            if (ok) {
                paused = !paused
                status.text = if (paused) "En pausa en ${renderer.name}" else "Emitiendo en ${renderer.name}"
            } else {
                status.text = "No se pudo ${if (paused) "reanudar" else "pausar"} en ${renderer.name}"
            }
        }
    }

    private fun stopEmission() {
        val renderer = CastSession.renderer ?: run {
            Toast.makeText(this, "No hay ninguna emisión activa", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            DlnaController.stop(renderer)
            CastSession.stop()
            paused = false
            status.text = "Emisión detenida"
        }
    }

    private fun renderStatus() {
        val active = CastSession.renderer
        status.text = when {
            active != null -> "Emitiendo en ${active.name}"
            else -> "Nada emitiendo"
        }
    }

    override fun onStart() {
        super.onStart()
        if (renderers.isNotEmpty()) renderStatus()
    }

    override fun onDestroy() {
        // El servidor y el receptor siguen vivos (son de CastSession); lo único
        // que se cancela es el trabajo pendiente de ESTA pantalla.
        scope.cancel()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
