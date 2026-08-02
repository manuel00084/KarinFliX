package com.karin.streamtv.player.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class BiquadFilter {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun configure(kind: Kind, fs: Int, f0: Float, gainDb: Float, q: Float) {
        val w0 = 2.0 * PI * f0 / fs
        val cw = cos(w0)
        val sw = sin(w0)
        when (kind) {
            Kind.PEAKING -> {
                val a = 10.0.pow(gainDb / 40.0)
                val alpha = sw / (2.0 * q)
                val a0 = 1.0 + alpha / a
                b0 = (1.0 + alpha * a) / a0
                b1 = (-2.0 * cw) / a0
                b2 = (1.0 - alpha * a) / a0
                a1 = (-2.0 * cw) / a0
                a2 = (1.0 - alpha / a) / a0
            }
            Kind.LOWSHELF -> {
                val a = 10.0.pow(gainDb / 40.0)
                val alpha = sw / 2.0 * sqrt((a + 1.0 / a) * (1.0 / q - 1.0) + 2.0)
                val ts = 2.0 * sqrt(a)
                val a0 = (a + 1.0) + (a - 1.0) * cw + ts * alpha
                b0 = a * ((a + 1.0) - (a - 1.0) * cw + ts * alpha) / a0
                b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cw) / a0
                b2 = a * ((a + 1.0) - (a - 1.0) * cw - ts * alpha) / a0
                a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cw) / a0
                a2 = ((a + 1.0) + (a - 1.0) * cw - ts * alpha) / a0
            }
            Kind.HIGHSHELF -> {
                val a = 10.0.pow(gainDb / 40.0)
                val alpha = sw / 2.0 * sqrt((a + 1.0 / a) * (1.0 / q - 1.0) + 2.0)
                val ts = 2.0 * sqrt(a)
                val a0 = (a + 1.0) - (a - 1.0) * cw + ts * alpha
                b0 = a * ((a + 1.0) + (a - 1.0) * cw + ts * alpha) / a0
                b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cw) / a0
                b2 = a * ((a + 1.0) + (a - 1.0) * cw - ts * alpha) / a0
                a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cw) / a0
                a2 = ((a + 1.0) - (a - 1.0) * cw - ts * alpha) / a0
            }
            Kind.LOWPASS -> {
                val alpha = sw / (2.0 * q)
                val a0 = 1.0 + alpha
                b0 = (1.0 - cw) / 2.0 / a0
                b1 = (1.0 - cw) / a0
                b2 = (1.0 - cw) / 2.0 / a0
                a1 = (-2.0 * cw) / a0
                a2 = (1.0 - alpha) / a0
            }
        }
    }

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }

    fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }

    enum class Kind { PEAKING, LOWSHELF, HIGHSHELF, LOWPASS }
}
