package com.karin.streamtv.player

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.Listener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.karin.streamtv.R

class ExoPlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView

    // Cached effect instances for live slider updates
    private var lowBitrateEffect: LowBitrateBoostEffect? = null
    private var detailBoostEffect: DetailBoostEffect? = null
    private var lightBoostEffect: LightBoostEffect? = null
    private var fsrEffect: FSRSuperEffect? = null
    private var dogEffect: DogSharpenEffect? = null
    private var motionX2Effect: MotionX2BoostEffect? = null
    private var colorBoostEffect: ColorBoostEffect? = null

    private val prefs by lazy {
        getSharedPreferences(ExoPlayerSettingsHelper.PREFS_NAME, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exo_player)
        playerView = findViewById(R.id.player_view)
        findViewById<android.widget.ImageButton>(R.id.btn_settings).setOnClickListener {
            ExoPlayerSettingsHelper.showAdvancedDialog(
                activity = this,
                prefs = prefs,
                player = player,
                onEffectsChanged = { applyVideoEffects(it) },
                onStrengthChanged = { effectType, strength ->
                    when (effectType) {
                        "lowbitrate" -> lowBitrateEffect?.updateStrength(strength)
                        "detailboost" -> detailBoostEffect?.updateStrength(strength)
                        "lightboost" -> lightBoostEffect?.updateStrength(strength)
                        "fsr" -> fsrEffect?.updateSharpness(strength)
                        "dog" -> dogEffect?.updateStrength(strength)
                        "motionx2" -> motionX2Effect?.updateStrength(strength)
                        "motionx2_mode" -> motionX2Effect?.updateMode(MotionX2Mode.values().getOrNull(strength.toInt()) ?: MotionX2Mode.ADAPTIVE)
                        "motionx2_blend" -> motionX2Effect?.updateBlendFactor(strength)
                        "colorboost_sat" -> colorBoostEffect?.updateSaturation(strength)
                        "colorboost_vib" -> colorBoostEffect?.updateVibrance(strength)
                        "colorboost_hue" -> colorBoostEffect?.updateHueShift(strength)
                        "colorboost_col" -> colorBoostEffect?.updateColorfulness(strength)
                    }
                },
                onWarmthChanged = { warmth ->
                    lightBoostEffect?.updateWarmth(warmth)
                },
            )
        }
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE

        val url = intent.getStringExtra("video_url") ?: intent.data?.toString()
        Log.d("ExoPlayerActivity", "Video URL: $url")
        if (url.isNullOrBlank()) {
            Log.e("ExoPlayerActivity", "Empty video URL")
            finish()
            return
        }
        val uri = if (url.startsWith("content:") || url.contains("://")) {
            Uri.parse(url)
        } else {
            Uri.fromFile(java.io.File(url))
        }

        player = ExoPlayer.Builder(this, CodecSelectorFactory.renderersFactory(this)).build().also {
            it.setMediaItem(MediaItem.fromUri(uri))
            applyVideoEffects(it)
            it.addListener(object : Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e("ExoPlayerActivity", "Playback error: ${error.message}", error)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    Log.d("ExoPlayerActivity", "Playing state changed: $isPlaying")
                }
            })
            it.prepare()
            it.playWhenReady = true
            playerView.player = it
        }
    }

    private fun applyVideoEffects(exoPlayer: ExoPlayer) {
        val effects = mutableListOf<Effect>()

        val lowBitrateEnabled = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_LOW_BITRATE_EN, false)
        val lbS = if (lowBitrateEnabled) {
            prefs.getInt(ExoPlayerSettingsHelper.KEY_LOW_BITRATE_STRENGTH, 60) / 100f
        } else 0f

        val upscalerMode = prefs.getInt(ExoPlayerSettingsHelper.KEY_UPSCALER_MODE, 0)
        when (upscalerMode) {
            ExoPlayerSettingsHelper.MODE_BILINEAR -> effects.add(BilinearSamplerEffect())
            ExoPlayerSettingsHelper.MODE_BICUBIC -> effects.add(BicubicSamplerEffect())
            ExoPlayerSettingsHelper.MODE_FSR -> {
                val sharpness = prefs.getInt(ExoPlayerSettingsHelper.KEY_FSR_SHARPNESS, 60) / 100f
                fsrEffect = FSRSuperEffect(sharpness.coerceIn(0f, 1f))
                effects.add(fsrEffect!!)
            }
            ExoPlayerSettingsHelper.MODE_DOG -> {
                val dogStr = prefs.getInt(ExoPlayerSettingsHelper.KEY_DOG_STRENGTH, 50) / 100f
                dogEffect = DogSharpenEffect(dogStr.coerceIn(0f, 1f))
                effects.add(dogEffect!!)
            }
        }

        val demoEnabled = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_DEMO_EN, false)
        val detailEnabled = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_DETAIL_BOOST_EN, false)

        if (demoEnabled) {
            val detailS = prefs.getInt(ExoPlayerSettingsHelper.KEY_DETAIL_BOOST_STRENGTH, 70) / 100f
            val lightS = prefs.getInt(ExoPlayerSettingsHelper.KEY_LIGHT_BOOST_STRENGTH, 50) / 100f
            val lightDemoEnabled = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_LIGHT_BOOST_EN, false)
            val motionX2DemoEnabled = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_MOTIONX2_EN, false)
            val motionX2Mode = prefs.getInt(ExoPlayerSettingsHelper.KEY_MOTIONX2_MODE, 1)
            val motionX2Strength = prefs.getInt(ExoPlayerSettingsHelper.KEY_MOTIONX2_STRENGTH, 50) / 100f
            val motionX2Blend = prefs.getInt(ExoPlayerSettingsHelper.KEY_MOTIONX2_BLEND, 50) / 100f
            val colorBoostDemoEnabled = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_COLORBOOST_EN, false)
            val colorBoostSat = prefs.getInt(ExoPlayerSettingsHelper.KEY_COLORBOOST_SATURATION, 30) / 100f
            val colorBoostVib = prefs.getInt(ExoPlayerSettingsHelper.KEY_COLORBOOST_VIBRANCE, 20) / 100f
            val colorBoostHue = prefs.getInt(ExoPlayerSettingsHelper.KEY_COLORBOOST_HUE, 0) / 100f
            val colorBoostCol = prefs.getInt(ExoPlayerSettingsHelper.KEY_COLORBOOST_COLORFULNESS, 15) / 100f

            effects.add(
                DemoEffect(
                    detailS.coerceIn(0f, 1f),
                    if (lightDemoEnabled) lightS.coerceIn(0f, 1f) else 0f,
                    lbS.coerceIn(0f, 1f),
                    if (motionX2DemoEnabled) motionX2Strength.coerceIn(0f, 1f) else 0f,
                    motionX2Mode,
                    motionX2Blend.coerceIn(0f, 1f),
                    if (colorBoostDemoEnabled) colorBoostSat.coerceIn(0f, 1f) else 0f,
                    if (colorBoostDemoEnabled) colorBoostVib.coerceIn(0f, 1f) else 0f,
                    if (colorBoostDemoEnabled) colorBoostHue.coerceIn(-0.5f, 0.5f) else 0f,
                    if (colorBoostDemoEnabled) colorBoostCol.coerceIn(0f, 1f) else 0f,
                ),
            )
        } else {
            val isLowEnd = isLowEndDevice()
            val maxHeavyEffects = if (isLowEnd) 2 else 3
            var heavyEffectCount = 0

            fun addHeavyEffect(effect: Effect) {
                if (heavyEffectCount < maxHeavyEffects) {
                    effects.add(effect)
                    heavyEffectCount++
                }
            }

            // 1. Low Bitrate: reparar compresión primero
            if (lowBitrateEnabled) {
                lowBitrateEffect = LowBitrateBoostEffect(lbS.coerceIn(0f, 1f))
                addHeavyEffect(lowBitrateEffect!!)
            }
            // 2. Detail Boost: realzar sobre imagen corregida
            if (detailEnabled && upscalerMode != ExoPlayerSettingsHelper.MODE_FSR) {
                val detailS = prefs.getInt(ExoPlayerSettingsHelper.KEY_DETAIL_BOOST_STRENGTH, 70) / 100f
                detailBoostEffect = DetailBoostEffect(detailS.coerceIn(0f, 1f))
                addHeavyEffect(detailBoostEffect!!)
            }
            // 3. Light Boost: iluminación y color estilo Splash Player
            if (prefs.getBoolean(ExoPlayerSettingsHelper.KEY_LIGHT_BOOST_EN, false)) {
                val lightS = prefs.getInt(ExoPlayerSettingsHelper.KEY_LIGHT_BOOST_STRENGTH, 50) / 100f
                val warmth = (prefs.getInt(ExoPlayerSettingsHelper.KEY_LIGHT_BOOST_WARMTH, 50) / 50f) - 1f
                lightBoostEffect = LightBoostEffect(lightS.coerceIn(0f, 1f), warmth.coerceIn(-1f, 1f))
                addHeavyEffect(lightBoostEffect!!)
            }
            // 4. MotionX2 Boost: interpola frames para 2x FPS
            if (prefs.getBoolean(ExoPlayerSettingsHelper.KEY_MOTIONX2_EN, false)) {
                val lowEnd = isLowEndDevice()
                var modeIndex = prefs.getInt(ExoPlayerSettingsHelper.KEY_MOTIONX2_MODE, 1)
                var mStrength = prefs.getInt(ExoPlayerSettingsHelper.KEY_MOTIONX2_STRENGTH, 50) / 100f
                val mBlend = prefs.getInt(ExoPlayerSettingsHelper.KEY_MOTIONX2_BLEND, 50) / 100f
                if (lowEnd) {
                    // Equipos con pocos recursos: modo barato (FRAME_DUP) sin detección
                    // de bordes/vectores y con intensidad limitada para no saturar la GPU.
                    if (modeIndex >= 1) modeIndex = MotionX2Mode.FRAME_DUP.ordinal
                    mStrength = mStrength.coerceIn(0f, 0.5f)
                }
                val mode = MotionX2Mode.values().getOrNull(modeIndex) ?: MotionX2Mode.ADAPTIVE
                motionX2Effect = MotionX2BoostEffect(
                    mode,
                    mStrength.coerceIn(0f, 1f),
                    mBlend.coerceIn(0f, 1f),
                    lowEnd,
                )
                addHeavyEffect(motionX2Effect!!)
            }
            // 5. Color Boost: mejora colores más vivos y vibrantes
            if (prefs.getBoolean(ExoPlayerSettingsHelper.KEY_COLORBOOST_EN, false)) {
                val sat = prefs.getInt(ExoPlayerSettingsHelper.KEY_COLORBOOST_SATURATION, 30) / 100f
                val vib = prefs.getInt(ExoPlayerSettingsHelper.KEY_COLORBOOST_VIBRANCE, 20) / 100f
                val hue = prefs.getInt(ExoPlayerSettingsHelper.KEY_COLORBOOST_HUE, 0) / 100f
                val col = prefs.getInt(ExoPlayerSettingsHelper.KEY_COLORBOOST_COLORFULNESS, 15) / 100f
                colorBoostEffect = ColorBoostEffect(sat.coerceIn(0f, 1f), vib.coerceIn(0f, 1f), hue.coerceIn(-0.5f, 0.5f), col.coerceIn(0f, 1f))
                addHeavyEffect(colorBoostEffect!!)
            }
        }

        exoPlayer.setVideoEffects(effects)
    }

    private fun isLowEndDevice(): Boolean {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem < 4L * 1024 * 1024 * 1024 || activityManager.isLowRamDevice
    }

    override fun onResume() {
        super.onResume()
        playerView.onResume()
    }

    override fun onPause() {
        super.onPause()
        playerView.onPause()
    }

    override fun onDestroy() {
        playerView.player = null
        player?.release()
        player = null
        // Clear cached effects
        lowBitrateEffect = null
        detailBoostEffect = null
        lightBoostEffect = null
        fsrEffect = null
        dogEffect = null
        motionX2Effect = null
        colorBoostEffect = null
        super.onDestroy()
    }
}