package com.karin.streamtv.karinlink

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.karin.streamtv.R
import com.karin.streamtv.karinlink.queue.PlaybackQueue
import com.karin.streamtv.karinlink.queue.QueueAction
import com.karin.streamtv.karinlink.queue.QueueHub
import com.karin.streamtv.karinlink.queue.QueueItem
import com.karin.streamtv.util.enableTvFocus
import com.karin.streamtv.util.onActionKey

/**
 * Cola de reproducción, pensada para el mando de la TV.
 *
 * Se pinta desde [QueueHub] en lugar de tener su propia copia: lo que se ve
 * aquí es exactamente lo que va a sonar, y se refresca sola cuando la cola
 * cambia (incluido desde otro mando), en vez de dar la sensación de que los
 * botones "a veces funcionan".
 */
class KarinLinkQueueActivity : FragmentActivity() {

    private lateinit var tvNow: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var list: LinearLayout
    private lateinit var tvTitle: TextView

    private val hubListener = QueueHub.Listener { queue -> runOnUiThread { render(queue) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_karinlink_queue)
        enableTvFocus()

        tvNow = findViewById(R.id.tv_queue_now)
        tvEmpty = findViewById(R.id.tv_queue_empty)
        list = findViewById(R.id.queue_list)
        tvTitle = findViewById(R.id.tv_queue_title)

        findViewById<View>(R.id.btn_queue_close).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_queue_close).onActionKey {
            findViewById<View>(R.id.btn_queue_close).performClick()
        }
        findViewById<View>(R.id.btn_queue_skip).setOnClickListener { skip() }
        findViewById<View>(R.id.btn_queue_skip).onActionKey {
            findViewById<View>(R.id.btn_queue_skip).performClick()
        }
        findViewById<View>(R.id.btn_queue_clear).setOnClickListener { clear() }
        findViewById<View>(R.id.btn_queue_clear).onActionKey {
            findViewById<View>(R.id.btn_queue_clear).performClick()
        }
    }

    override fun onStart() {
        super.onStart()
        QueueHub.addListener(hubListener)
        render(QueueHub.current())
    }

    override fun onStop() {
        // Fuera de la pantalla, esta pantalla deja de escuchar: si se queda
        // registrada, cada cambio de cola acabaría hablando con una Activity
        // destruida.
        QueueHub.removeListener(hubListener)
        super.onStop()
    }

    private fun render(queue: PlaybackQueue) {
        val current = queue.current
        val waiting = queue.snapshot()

        tvNow.text = current?.let { "Sonando: ${it.title}" } ?: "No hay nada sonando"
        tvNow.setTextColor(if (current == null) hint() else bright())

        tvEmpty.visibility = if (waiting.isEmpty()) View.VISIBLE else View.GONE
        tvTitle.text = "Cola de reproducción (${waiting.size})"

        list.removeAllViews()
        waiting.forEachIndexed { index, item -> list.addView(row(index, item)) }
    }

    private fun row(position: Int, item: QueueItem): View {
        val text = "${position + 1}. ${item.title}"
        val view = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            background = getDrawable(R.drawable.selector_card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            this.text = text
            setTextColor(bright())
            textSize = 15f
            isFocusable = true
            isFocusableInTouchMode = true
            contentDescription = text
        }

        // Aceptar quita el elemento. Atrás no lo hace, porque onActionKey solo
        // reacciona a "seleccionar": en un mando no hay forma de pulsar sin
        // querer por un botón de más, y borrar media cola sería lo contrario de
        // lo que se espera.
        view.setOnClickListener {
            QueueHub.remove(item.id)
            Toast.makeText(this, "Quitado de la cola", Toast.LENGTH_SHORT).show()
        }
        view.onActionKey {
            view.performClick()
        }
        return view
    }

    private fun skip() {
        if (QueueHub.skip() !is QueueAction.Start) {
            Toast.makeText(this, "No hay nada más en la cola", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clear() {
        // Se pregunta antes de vaciar: una cola con cosas pero sin nada sonando
        // también se vacía, y en ese caso no hay reproductor que parar.
        val hadSomething = QueueHub.current().size > 0
        QueueHub.clear()
        if (hadSomething) Toast.makeText(this, "Cola vaciada", Toast.LENGTH_SHORT).show()
    }

    private fun bright() = color(R.color.text_primary)
    private fun hint() = color(R.color.text_secondary)

    /**
     * Los colores vienen de los recursos, como en el resto de pantallas de
     * KARIN Link.
     *
     * Se hardcodeaba negro en modo claro y, sobre las tarjetas oscuras, el texto
     * desaparecía justo en los equipos que no están en modo noche. Un mando no
     * se usa a oscuras: ahí es donde más falta hace verse.
     */
    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
