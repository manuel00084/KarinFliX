package com.karin.streamtv.enhancer

import android.content.SharedPreferences
import com.karin.streamtv.player.ExoPlayerSettingsHelper
import com.karin.streamtv.player.SuperResolutionEffect
import kotlin.math.pow

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
        val depth: Float = 0f,
    ) {
        val anyOn: Boolean get() = depixel > 0f || retro > 0f || detail > 0f || depth > 0f
    }

    /** Auto por fuente (KEY_RESTORE_AUTO, default true): SD limpia más, HD casi no toca. */
    fun autoFromPrefs(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(ExoPlayerSettingsHelper.KEY_RESTORE_AUTO, true)

    /**
     * Auto por fuente: escala el master según la altura real del video.
     * SD sucio necesita limpieza fuerte; HD limpio se empasta con lo mismo.
     * 0 = altura desconocida -> neutro. Solo Kotlin (una vez por video),
     * 0 pases/taps extra en GPU.
     */
    fun autoScale(videoHeight: Int): Float = when {
        videoHeight <= 0 -> 1f
        videoHeight <= 480 -> 1.2f
        videoHeight <= 540 -> 1.15f
        videoHeight <= 720 -> 1.1f
        videoHeight <= 1080 -> 0.85f
        else -> 0.6f
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
     * Deriva las 3 etapas desde el master con curvas perceptuales.
     *
     * El 1:1:1 lineal anterior ponía el detalle demasiado caliente en la
     * zona media (master 0.6 -> detail 0.6 = dientes en contornos) y la
     * limpieza demasiado tímida donde más ayuda. Ahora:
     *  - limpieza: sube rápido y satura (m^0.8): 0.6 -> ~0.67. Incluso
     *    intensidades bajas limpian bloques/ruido sin llegar a empastar.
 *  - reconstrucción: lineal suave ×0.9 (0.6 -> 0.54). El line-darken
 *    a 1.0 aplasta líneas finas; topado queda en zona segura. Incluye
 *    coherencia de píxel inspirada en CRT (0.35x de retro, solo luma,
 *    sin scanlines/máscara/glow) antes del detalle.
     *  - detalle: m^1.4 (0.6 -> ~0.49). El RCAS puro resultaba invisible;
     *    el detalle ahora es unsharp direccional con clamp estrecho (el filo
     *    se ve) y esta curva solo lo modera para no llegar al crudeza. Con el
     *    clamp fijo de 0.015 el exceso no crea dientes, asi que no hace falta
     *    frenar tan fuerte.
     * En gama baja, además del tope 0.8 en limpieza/reconstrucción, el
     * detalle se topa a 0.5 (protege batería y evita empastar en Mali
     * lentos). El detalle se atenúa solo si el upscaler ya afila
     * (FSR/Anime4K/Karin duplicarían halos).
     */
    fun stagesFor(
        master: Float,
        upscalerOn: Boolean,
        upscalerMode: Int,
        lowEnd: Boolean,
        videoHeight: Int = 0,
        auto: Boolean = true,
    ): Stages {
        var m = master.coerceIn(0f, 1f)
        // 1) Auto por fuente: SD sube, HD baja. Antes de las curvas para que
        // los topes de gama baja y el factor upscaler sigan mandando después.
        if (auto && videoHeight > 0) m = (m * autoScale(videoHeight)).coerceIn(0f, 1f)
        var dep = m.pow(0.8f)
        var ret = m * 0.9f
        var det = m.pow(1.4f)
        // Profundidad/pop: recomendada ~0.3 con master 0.6 (m*0.6). 0 fetches
        // extra (reusa el vecindario), solo ALU: segura en gama baja.
        var depth = m * 0.6f
        if (lowEnd) {
            dep = dep.coerceIn(0f, 0.8f)
            ret = ret.coerceIn(0f, 0.8f)
            det = det.coerceIn(0f, 0.5f)
            depth = depth.coerceIn(0f, 0.3f)
        }
        if (upscalerOn) {
            if (upscalerMode == SuperResolutionEffect.MODE_FSR) det *= 0.55f
            if (upscalerMode == SuperResolutionEffect.MODE_ANIME4K) det *= 0.5f
            if (upscalerMode == SuperResolutionEffect.MODE_KARIN) det *= 0.55f
            depth *= 0.7f
        }
        return Stages(
            depixel = dep.coerceIn(0f, 1f),
            retro = ret.coerceIn(0f, 1f),
            detail = det.coerceIn(0f, 1f),
            depth = depth.coerceIn(0f, 1f),
        )
    }

    /**
     * Etapas efectivas para la cadena: modo vinculado (curvas perceptuales
     * del master, con topes de gama baja y factor upscaler) o personalizado
     * (cada etapa lee su pref; el usuario manda, sin factor upscaler pero
     * con topes de gama baja). Los fallbacks del modo fino reflejan el
     * punto dulce nuevo (55/50/45 en vez del 60/60/70 caliente de antes).
     */
    fun stagesFromPrefs(
        prefs: SharedPreferences,
        upscalerOn: Boolean,
        upscalerMode: Int,
        lowEnd: Boolean,
        videoHeight: Int = 0,
    ): Stages {
        val (master, _) = masterAndEnabled(prefs)
        val custom = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_RESTORE_CUSTOM, false)
        var dep: Float
        var ret: Float
        var det: Float
        if (custom) {
            // Personalizado: el usuario manda por etapa (sin auto ni factor
            // upscaler), solo topes de gama baja abajo. Profundidad aparte.
            dep = prefs.getInt(ExoPlayerSettingsHelper.KEY_DEPIXEL_STRENGTH, 60) / 100f
            ret = prefs.getInt(ExoPlayerSettingsHelper.KEY_RETRO_STRENGTH, 55) / 100f
            det = prefs.getInt(ExoPlayerSettingsHelper.KEY_DETAIL_BOOST_STRENGTH, 55) / 100f
        } else {
            val s = stagesFor(
                master, upscalerOn, upscalerMode, lowEnd,
                videoHeight, autoFromPrefs(prefs),
            )
            dep = s.depixel
            ret = s.retro
            det = s.detail
        }
        // Profundidad: opción aparte (mismo pase, 0 costo). No la mueve el
        // master ni el modo fino: slider propio con default 30.
        var depth = prefs.getInt(ExoPlayerSettingsHelper.KEY_RESTORE_DEPTH, 30) / 100f
        if (lowEnd) {
            // El modo vinculado ya viene topado desde stagesFor; esto
            // protege además al personalizado en Mali lentos.
            dep = dep.coerceIn(0f, 0.8f)
            ret = ret.coerceIn(0f, 0.8f)
            det = det.coerceIn(0f, 0.5f)
            depth = depth.coerceIn(0f, 0.3f)
        }
        return Stages(
            depixel = dep.coerceIn(0f, 1f),
            retro = ret.coerceIn(0f, 1f),
            detail = det.coerceIn(0f, 1f),
            depth = depth.coerceIn(0f, 1f),
        )
    }
}
