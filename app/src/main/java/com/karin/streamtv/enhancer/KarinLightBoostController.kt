package com.karin.streamtv.enhancer

import android.content.SharedPreferences
import androidx.media3.common.C
import com.karin.streamtv.enhancer.parameters.KarinLightBoostParameters
import com.karin.streamtv.player.ExoPlayerSettingsHelper

/**
 * Puente entre las preferencias del usuario y KarinLightBoostParameters,
 * más detección SDR/HDR del contenido y de la pantalla.
 *
 * Lee el interruptor, el modo y la intensidad maestra, y deriva todas las
 * etapas con stagesFor(). Los modos de rango (contentHdr/displayHdr) los
 * pone quien crea el efecto (la Activity), que sí conoce el formato del
 * video y la pantalla.
 */
object KarinLightBoostController {

    const val MODE_MANUAL = 0
    const val MODE_AUTO = 1

    /** Contenido: transferencia Media3 -> 0 SDR, 1 PQ, 2 HLG. */
    fun detectContentHdr(colorTransfer: Int): Int {
        return when (colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> 1
            C.COLOR_TRANSFER_HLG -> 2
            else -> 0
        }
    }

    fun fromPrefs(prefs: SharedPreferences): KarinLightBoostParameters {
        val cineEn = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_CINE_EN, false)
        val colorsEn = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_COLORS_EN, false)
        val rangeMode = prefs.getInt(ExoPlayerSettingsHelper.KEY_RANGE_MODE, 0).coerceIn(0, 2)
        if (!cineEn && !colorsEn && rangeMode == 0) return KarinLightBoostParameters(enabled = false)

        // Función extra de color: switch independiente, puede ir con luz en 0.
        val colorStrength = if (colorsEn) {
            prefs.getInt(ExoPlayerSettingsHelper.KEY_COLORS_STRENGTH, 60) / 100f
        } else {
            0f
        }.coerceIn(0f, 1f)

        // Solo-luz apagada pero color/rango pedidos: luz neutra (boost 0 la
        // salta), el color y el rango siguen aplicando.
        if (!cineEn) {
            return KarinLightBoostParameters(
                enabled = true,
                prefValue = 0f,
                shadowBoost = 0f,
                blackLevel = 0f,
                whiteBoost = 0f,
                highlightControl = 0f,
                localContrast = 0f,
                gammaInv = 1f,
                saturation = 1f,
                vibrance = 0f,
                colorStrength = colorStrength,
                rangeMode = rangeMode,
            )
        }

        val autoMode = prefs.getInt(ExoPlayerSettingsHelper.KEY_CINE_MODE, MODE_MANUAL) == MODE_AUTO
        val master = prefs.getInt(ExoPlayerSettingsHelper.KEY_CINE_STRENGTH, 50) / 100f
        return stagesFor(master).copy(
            enabled = true,
            autoMode = autoMode,
            colorStrength = colorStrength,
            rangeMode = rangeMode,
        )
    }

    /**
     * Deriva todas las etapas desde una única intensidad maestra 0..1.
     * En 0.5 reproduce el punto medio anterior (Sombra/Negros/Blancos/Luces
     * ~50, Contraste ~45, Gamma ~1.0, Saturación ~1.03).
     */
    fun stagesFor(master: Float): KarinLightBoostParameters {
        val m = master.coerceIn(0f, 1f)
        return KarinLightBoostParameters(
            enabled = true,
            autoMode = false,
            prefValue = m,
            shadowBoost = 0.25f + 0.45f * m,
            blackLevel = 0.25f + 0.45f * m,
            whiteBoost = 0.25f + 0.45f * m,
            highlightControl = 0.25f + 0.45f * m,
            localContrast = 0.20f + 0.40f * m,
            // Gamma que sí aclara en medios: 0.95 a m=0.5 (antes 1.00 =
            // neutra, por eso a 50% no se veía más brillo), ~neutra en 1.0
            // donde el lift y el contraste ya hacen el trabajo.
            gammaInv = 1f / KarinLightBoostParameters.gammaFromSlider((10 + 30 * m).toInt()),
            saturation = KarinLightBoostParameters.satFromSlider((20 + 50 * m).toInt()),
            vibrance = 0.08f + 0.42f * m,
        )
    }
}
