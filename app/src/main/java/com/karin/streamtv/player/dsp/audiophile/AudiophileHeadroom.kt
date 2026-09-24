package com.karin.streamtv.player.dsp.audiophile

/**
 * Headroom manager: calcula el preamp (dB negativo) necesario para que los
 * boosts de EQ no empujen la señal al clip. Auto ON por defecto.
 * No destruye dinámica: solo baja la ganancia global cuando hay boost real.
 */
object AudiophileHeadroom {

    data class Result(val preampDb: Float, val autoHeadroom: Boolean, val eqMaxBoostDb: Float)

    fun compute(params: AudiophileConfig.Params): Result {
        val eqMax = if (params.eqEnabled) AudiophileEQ().maxBoostDb(params.bands) else 0f
        val preamp = if (params.autoHeadroom && eqMax > 0f) -eqMax else 0f
        return Result(preamp, params.autoHeadroom, eqMax)
    }

    /** Ganancia lineal del preamp. */
    fun preampGain(result: Result): Double = AudiophileConfig.dbToLin(result.preampDb)
}
