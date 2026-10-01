package com.karin.streamtv.player.dsp.smartlite

/**
 * Headroom manager: calcula el preamp (dB negativo) necesario para que los
 * boosts de EQ no empujen la señal al clip. Auto ON por defecto.
 * No destruye dinámica: solo baja la ganancia global cuando hay boost real.
 */
object SmartLiteHeadroom {

    data class Result(val preampDb: Float, val autoHeadroom: Boolean, val eqMaxBoostDb: Float)

    fun compute(params: SmartLiteConfig.Params): Result {
        val eqMax = if (params.eqEnabled) SmartLiteEQ().maxBoostDb(params.bands) else 0f
        val preamp = if (params.autoHeadroom && eqMax > 0f) -eqMax else 0f
        return Result(preamp, params.autoHeadroom, eqMax)
    }

    /** Ganancia lineal del preamp. */
    fun preampGain(result: Result): Double = SmartLiteConfig.dbToLin(result.preampDb)
}
