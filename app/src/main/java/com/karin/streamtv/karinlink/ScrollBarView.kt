package com.karin.streamtv.karinlink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Barra vertical de scroll para el control remoto.
 *
 * Arrastra el dedo en vertical: envía deltas de scroll al equipo controlado
 * (rueda del mouse). Muestra el pulgar donde está el dedo mientras se toca.
 */
class ScrollBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var onScroll: (dy: Float) -> Unit = {}

    private var thumbY = 0.5f
    private var lastY = 0f
    private var tracking = false

    private val track = Paint().apply { color = Color.parseColor("#22000000"); style = Paint.Style.FILL }
    private val thumb = Paint().apply { color = Color.parseColor("#6C63FF"); style = Paint.Style.FILL }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = "Barra de scroll vertical"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastY = event.y
                thumbY = (event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                tracking = true
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return true
                val h = height.coerceAtLeast(1)
                val dy = (event.y - lastY) / h
                lastY = event.y
                thumbY = (event.y / h).coerceIn(0f, 1f)
                if (abs(dy) > 0.001f) onScroll(dy)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracking = false
                thumbY = 0.5f
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), track)
        val cx = width / 2f
        // Marca central de referencia.
        canvas.drawLine(cx - 14f, height / 2f, cx + 14f, height / 2f, thumb)
        // Pulgar.
        val y = thumbY * height
        canvas.drawRoundRect(cx - 12f, y - 40f, cx + 12f, y + 40f, 12f, 12f, thumb)
    }
}
