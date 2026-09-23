package com.karin.streamtv.enhancer

import android.content.SharedPreferences
import com.karin.streamtv.player.ExoPlayerSettingsHelper
import com.karin.streamtv.player.SuperResolutionEffect

/**
 * Puente entre prefs y RestoreBoostEffect: una sola intensidad maestra
 * deriva las 3 etapas internas (limpieza, reconstrucción, detalle).
 *
 * La atenuación gruesa Depixel→Detail por sliders de la cadena vieja ya no
 * existe: dentro del pase, el afilado se frena por píxel según cuánto se
 * suavizó ese mismo píxel (smoothK). Solo se conserva la atenuación frente
 * al upscaler (efecto distinto que ya afila por su cuenta).
 *
 * Migración legacy (una vez): si no hay claves nuevas pero sí alguna vieja
 * activa, el master = promedio de las intensidades activas.
 */
object RestoreBoostController {

    data class Stages(
        val depixel: Float,
        val retro: Float,
        val detail: Float,
    ) {
        val anyOn: Boolean get() = depixel > 0f || retro > 0f || detail > 0f
    }

    /** Master 0..1 + enabled, migrando prefs legacy si hace falta. */
    fun masterAndEnabled(prefs: SharedPreferences): Pair<Float, Boolean> {
        if (prefs.contains(ExoPlayerSettingsHelper.KEY_RESTORE_EN)) {
            val en = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_RESTORE_EN, false)
            val m = prefs.getInt(ExoPlayerSettingsHelper.KEY_RESTORE_STRENGTH, 60) / 100f
            return m.coerceIn(0f, 1f) to en
        }
        val parts = mutableListOf<Float>()
        if (prefs.getBoolean(ExoPlayerSettingsHelper.KEY_DEPIXEL_EN, false)) {
            parts += prefs.getInt(ExoPlayerSettingsHelper.KEY_DEPIXEL_STRENGTH, 60) / 100f
        }
        if (prefs.getBoolean(ExoPlayerSettingsHelper.KEY_RETRO_EN, false)) {
            parts += prefs.getInt(ExoPlayerSettingsHelper.KEY_RETRO_STRENGTH, 60) / 100f
        }
        if (prefs.getBoolean(ExoPlayerSettingsHelper.KEY_DETAIL_BOOST_EN, false)) {
            parts += prefs.getInt(ExoPlayerSettingsHelper.KEY_DETAIL_BOOST_STRENGTH, 70) / 100f
        }
        val en = parts.isNotEmpty()
        val m = if (en) (parts.sum() / parts.size).coerceIn(0f, 1f) else 0.6f
        prefs.edit()
            .putBoolean(ExoPlayerSettingsHelper.KEY_RESTORE_EN, en)
            .putInt(ExoPlayerSettingsHelper.KEY_RESTORE_STRENGTH, (m * 100).toInt())
            .apply()
        return m to en
    }

    /**
     * Deriva las 3 etapas desde el master. En gama baja, limpieza y
     * reconstrucción se topan a 0.8 (igual que antes); el detalle se atenúa
     * solo si el upscaler ya afila (FSR/Anime4K duplicarían halos).
     */
    fun stagesFor(
        master: Float,
        upscalerOn: Boolean,
        upscalerMode: Int,
        lowEnd: Boolean,
    ): Stages {
        val m = master.coerceIn(0f, 1f)
        var dep = m
        var ret = m
        var det = m
        if (lowEnd) {
            dep = dep.coerceIn(0f, 0.8f)
            ret = ret.coerceIn(0f, 0.8f)
        }
        if (upscalerOn) {
            if (upscalerMode == SuperResolutionEffect.MODE_FSR) det *= 0.55f
            if (upscalerMode == SuperResolutionEffect.MODE_ANIME4K) det *= 0.5f
            if (upscalerMode == SuperResolutionEffect.MODE_KARIN) det *= 0.55f
        }
        return Stages(
            depixel = dep.coerceIn(0f, 1f),
            retro = ret.coerceIn(0f, 1f),
            detail = det.coerceIn(0f, 1f),
        )
    }

    /**
     * Etapas efectivas para la cadena: modo vinculado (derivan del master)
     * o personalizado (cada etapa lee su pref; el usuario manda, sin factor
     * upscaler). Siempre con topes de gama baja.
     */
    fun stagesFromPrefs(
        prefs: SharedPreferences,
        upscalerOn: Boolean,
        upscalerMode: Int,
        lowEnd: Boolean,
    ): Stages {
        val (master, _) = masterAndEnabled(prefs)
        val custom = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_RESTORE_CUSTOM, false)
        var dep: Float
        var ret: Float
        var det: Float
        if (custom) {
            dep = prefs.getInt(ExoPlayerSettingsHelper.KEY_DEPIXEL_STRENGTH, 60) / 100f
            ret = prefs.getInt(ExoPlayerSettingsHelper.KEY_RETRO_STRENGTH, 60) / 100f
            det = prefs.getInt(ExoPlayerSettingsHelper.KEY_DETAIL_BOOST_STRENGTH, 70) / 100f
        } else {
            val s = stagesFor(master, upscalerOn, upscalerMode, lowEnd = false)
            dep = s.depixel
            ret = s.retro
            det = s.detail
        }
        if (lowEnd) {
            dep = dep.coerceIn(0f, 0.8f)
            ret = ret.coerceIn(0f, 0.8f)
        }
        return Stages(
            depixel = dep.coerceIn(0f, 1f),
            retro = ret.coerceIn(0f, 1f),
            detail = det.coerceIn(0f, 1f),
        )
    }
}
