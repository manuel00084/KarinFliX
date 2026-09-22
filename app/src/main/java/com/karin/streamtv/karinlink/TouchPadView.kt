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
 * Touch pad del control remoto.
 *
 * - 1 dedo: arrastra para mover el cursor (coordenadas normalizadas 0..1),
 *   toque rápido = clic.
 * - 2 dedos: arrastre vertical = scroll.
 *
 * Dibuja una cruz en la posición del cursor para referencia visual.
 */
class TouchPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var onMove: (x: Float, y: Float) -> Unit = { _, _ -> }
    var onTap: (x: Float, y: Float) -> Unit = { _, _ -> }
    var onScroll: (dx: Float, dy: Float, x: Float, y: Float) -> Unit = { _, _, _, _ -> }

    private var cx = 0.5f
    private var cy = 0.5f

    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var last2Y = 0f
    private var tracking = false
    private var twoFinger = false

    private val bg = Paint().apply { color = Color.parseColor("#22000000"); style = Paint.Style.FILL }
    private val line = Paint().apply {
        color = Color.parseColor("#6C63FF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val dot = Paint().apply { color = Color.parseColor("#6C63FF"); style = Paint.Style.FILL }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                lastX = event.x; lastY = event.y
                downT = System.currentTimeMillis()
                tracking = true
                twoFinger = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                twoFinger = true
                last2Y = event.getY(1)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return true
                if (twoFinger && event.pointerCount >= 2) {
                    val y2 = event.getY(1)
                    val dy = (y2 - last2Y) / height.coerceAtLeast(1)
                    last2Y = y2
                    if (abs(dy) > 0.002f) onScroll(0f, dy, cx, cy)
                } else if (event.pointerCount == 1) {
                    val dx = (event.x - lastX) / width.coerceAtLeast(1)
                    val dy = (event.y - lastY) / height.coerceAtLeast(1)
                    lastX = event.x; lastY = event.y
                    if (dx != 0f || dy != 0f) {
                        cx = (cx + dx * SENSITIVITY).coerceIn(0f, 1f)
                        cy = (cy + dy * SENSITIVITY).coerceIn(0f, 1f)
                        onMove(cx, cy)
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (tracking && !twoFinger && event.actionMasked == MotionEvent.ACTION_UP) {
                    val dt = System.currentTimeMillis() - downT
                    val dist = abs(event.x - downX) + abs(event.y - downY)
                    if (dt < 250 && dist < 24f) onTap(cx, cy)
                }
                tracking = false
                twoFinger = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val x = cx * width
        val y = cy * height
        val r = 28f
        canvas.drawLine(x - r, y, x + r, y, line)
        canvas.drawLine(x, y - r, x, y + r, line)
        canvas.drawCircle(x, y, 10f, dot)
    }

    private companion object {
        const val SENSITIVITY = 1.8f
    }
}
