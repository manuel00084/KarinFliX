package com.karin.streamtv.player

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.Listener
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.karin.streamtv.R
import com.karin.streamtv.util.AudioEffectsManager
import com.karin.streamtv.util.PlaylistQueue

class ExoPlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var wasPlayingBeforePause = true

    private var audioFx: AudioEffectsManager? = null

    // Cached effect instances for live slider updates
    private var depixelEffect: DepixelBoostEffect? = null
    private var detailBoostEffect: DetailBoostEffect? = null
    private var cinematicEffect: CinematicBoostEffect? = null
    private var hdrBoostEffect: HDRBoostEffect? = null
    private var colorsBoostEffect: ColorsBoostEffect? = null
    private var fsrEffect: FSRSuperEffect? = null
    private var dogEffect: DogSharpenEffect? = null
    private var motionX2Effect: MotionX2BoostEffect? = null

    private val prefs by lazy {
        getSharedPreferences(ExoPlayerSettingsHelper.PREFS_NAME, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exo_player)
        playerView = findViewById(R.id.player_view)
        playerView.findViewById<ImageButton>(R.id.btn_settings)?.setOnClickListener {
            ExoPlayerSettingsHelper.showAdvancedDialog(
                activity = this,
                prefs = prefs,
                player = player,
                onEffectsChanged = { applyVideoEffects(it) },
                onStrengthChanged = { effectType, strength ->
                    when (effectType) {
                        "depixel" -> depixelEffect?.updateStrength(strength)
                        "detailboost" -> detailBoostEffect?.updateStrength(strength)
                        "cine" -> cinematicEffect?.updateMaster(strength)
                        "hdr" -> hdrBoostEffect?.updateStrength(strength)
                        "colors" -> colorsBoostEffect?.updateStrength(strength)
                        "fsr" -> fsrEffect?.updateSharpness(strength)
                        "fsr_quality" -> fsrEffect?.updateQuality(FsrQuality.values().getOrNull(strength.toInt()) ?: FsrQuality.EQUILIBRADO)
                        "dog" -> dogEffect?.updateStrength(strength)
                        "motionx2" -> motionX2Effect?.updateStrength(strength)
                        "motionx2_mode" -> motionX2Effect?.updateMode(MotionX2Mode.values().getOrNull(strength.toInt()) ?: MotionX2Mode.HYBRID)
                    }
                },
            )
        }
        audioFx = AudioEffectsManager(this)
        playerView.findViewById<ImageButton>(R.id.btn_dsp)?.setOnClickListener {
            audioFx?.let { fx -> ExoPlayerSettingsHelper.showFxDialog(activity = this, fx = fx) }
        }
        playerView.findViewById<ImageButton>(R.id.btn_close)?.setOnClickListener {
            finish()
        }

        val url = intent.getStringExtra("video_url") ?: intent.data?.toString()
        Log.d("ExoPlayerActivity", "Video URL: $url")
        if (url.isNullOrBlank()) {
            Log.e("ExoPlayerActivity", "Empty video URL")
            Toast.makeText(this, "Sin video para reproducir", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // Extras que antes se ignoraban: título, referer, playlist y mega.
        val videoTitle = intent.getStringExtra("video_title").orEmpty()
        if (videoTitle.isNotBlank()) title = videoTitle
        val referer = intent.getStringExtra("referer").orEmpty()
        if (intent.hasExtra("mega_key")) {
            Log.w("ExoPlayerActivity", "Mega cifrado aún no soportado, se reproduce URL directa si existe")
            Toast.makeText(this, "Mega cifrado no soportado aún", Toast.LENGTH_LONG).show()
        }
        val uri = if (url.startsWith("content:") || url.contains("://")) {
            Uri.parse(url)
        } else {
            Uri.fromFile(java.io.File(url))
        }

        val httpFactory = DefaultHttpDataSource.Factory().apply {
            setUserAgent("Mozilla/5.0 (Linux; Android 11; KarinFLiX TV) ExoPlayer")
            if (referer.isNotBlank()) setDefaultRequestProperties(mapOf("Referer" to referer))
        }
        // DefaultDataSource delega por esquema: file/content/assets van a su fuente
        // local y http/https al httpFactory con headers. Solo-HTTP rompía video local.
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)
        val mediaItem = MediaItem.Builder().setUri(uri).apply {
            if (videoTitle.isNotBlank()) {
                setMediaMetadata(MediaMetadata.Builder().setTitle(videoTitle).build())
            }
        }.build()

        setupPlaylistButtons()
        player = ExoPlayer.Builder(this, CodecSelectorFactory.renderersFactory(this))
            .setMediaSourceFactory(mediaSourceFactory)
            .build().also {
            it.setMediaItem(mediaItem)
            applyVideoEffects(it)
            it.addListener(object : Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e("ExoPlayerActivity", "Playback error: ${error.message}", error)
                    val msg = when (error.errorCode) {
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                            "Sin conexión o el servidor no responde"
                        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                            "Archivo no encontrado"
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                        PlaybackException.ERROR_CODE_DECODING_FAILED ->
                            "Este video no se puede decodificar en este equipo"
                        else -> "No se pudo reproducir el video (${error.errorCode})"
                    }
                    Toast.makeText(this@ExoPlayerActivity, msg, Toast.LENGTH_LONG).show()
                    finish()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        audioFx?.attachToSession(it.audioSessionId)
                    }
                }

                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    audioFx?.attachToSession(audioSessionId)
                }
            })
            it.prepare()
            it.playWhenReady = true
            playerView.player = it
        }
    }

    /** Anterior/siguiente cambian de capítulo cuando hay playlist. */
    private fun setupPlaylistButtons() {
        val playlist = PlaylistQueue.fromJson(intent.getStringExtra("playlist_json"))
        if (playlist.isEmpty()) return
        val index = intent.getIntExtra("playlist_index", 0)
        val siteName = intent.getStringExtra("site_name").orEmpty()
        playerView.findViewById<ImageButton>(androidx.media3.ui.R.id.exo_prev)?.apply {
            isEnabled = index > 0
            setOnClickListener {
                startActivity(PlaylistQueue.buildIntent(this@ExoPlayerActivity, playlist, index - 1, siteName))
                finish()
            }
        }
        playerView.findViewById<ImageButton>(androidx.media3.ui.R.id.exo_next)?.apply {
            isEnabled = index < playlist.size - 1
            setOnClickListener {
                startActivity(PlaylistQueue.buildIntent(this@ExoPlayerActivity, playlist, index + 1, siteName))
                finish()
            }
        }
    }

    private fun prefsFloat(key: String, def: Int) = prefs.getInt(key, def) / 100f

    private fun clearCachedEffects() {
        depixelEffect = null
        detailBoostEffect = null
        cinematicEffect = null
        hdrBoostEffect = null
        colorsBoostEffect = null
        fsrEffect = null
        dogEffect = null
        motionX2Effect = null
    }

    private fun applyVideoEffects(exoPlayer: ExoPlayer) {
        // Limpia la caché: si no, los sliders actualizan efectos viejos fuera de la cadena.
        clearCachedEffects()
        // Demo: cada efecto conserva la mitad izquierda intacta y al final
        // solo se pinta la línea. Sin copias entre cuadros: no se desincroniza.
        val effects = mutableListOf<Effect>()
        val demoEnabled = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_DEMO_EN, false)

        addChainEffects(effects, demoEnabled)

        if (demoEnabled) effects.add(DemoLineEffect())
        exoPlayer.setVideoEffects(effects)
    }

    private fun addChainEffects(effects: MutableList<Effect>, demoEnabled: Boolean = false) {
        val isLowEnd = isLowEndDevice()
        val upscalerMode = prefs.getInt(ExoPlayerSettingsHelper.KEY_UPSCALER_MODE, 0)
        when (upscalerMode) {
            ExoPlayerSettingsHelper.MODE_BILINEAR -> effects.add(BilinearSamplerEffect())
            ExoPlayerSettingsHelper.MODE_BICUBIC -> effects.add(BicubicSamplerEffect(demoEnabled))
            ExoPlayerSettingsHelper.MODE_FSR -> {
                val sharpness = prefsFloat(ExoPlayerSettingsHelper.KEY_FSR_SHARPNESS, 60).coerceIn(0f, 1f)
                val qPref = prefs.getInt(ExoPlayerSettingsHelper.KEY_FSR_QUALITY, 1).coerceIn(0, 2)
                val fsrQ = if (isLowEnd) FsrQuality.RENDIMIENTO
                    else FsrQuality.values().getOrNull(qPref) ?: FsrQuality.EQUILIBRADO
                fsrEffect = FSRSuperEffect(sharpness, fsrQ, demoEnabled)
                effects.add(fsrEffect!!)
            }
            ExoPlayerSettingsHelper.MODE_DOG -> {
                dogEffect = DogSharpenEffect(prefsFloat(ExoPlayerSettingsHelper.KEY_DOG_STRENGTH, 50).coerceIn(0f, 1f), demoEnabled)
                effects.add(dogEffect!!)
            }
        }

        // Presupuesto de pases GL: cada filtro es 1 pase de pantalla completa.
        // El escalador pesado también consume: se reserva del cupo. El demo ya
        // no reserva nada (cada efecto parte su propia pasada, sin copias).
        val upscalerHeavy = if (upscalerMode == ExoPlayerSettingsHelper.MODE_FSR ||
            upscalerMode == ExoPlayerSettingsHelper.MODE_DOG) 1 else 0
        val maxHeavyEffects = ((if (isLowEnd) 3 else 5) - upscalerHeavy).coerceAtLeast(2)
        var heavyEffectCount = 0

        fun addHeavyEffect(label: String, effect: Effect) {
            if (heavyEffectCount < maxHeavyEffects) {
                effects.add(effect)
                heavyEffectCount++
            } else {
                Log.w("ExoPlayerActivity", "Efecto omitido por límite en este equipo: $label")
                Toast.makeText(
                    this@ExoPlayerActivity,
                    "$label omitido: apaga otro filtro o el demo para usarlo",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        // 1. Depixel: repara pixelado/ruido/artefactos primero.
        if (prefs.getBoolean(ExoPlayerSettingsHelper.KEY_DEPIXEL_EN, false)) {
            var strength = prefsFloat(ExoPlayerSettingsHelper.KEY_DEPIXEL_STRENGTH, 60)
            if (isLowEnd) strength = strength.coerceIn(0f, 0.8f)
            depixelEffect = DepixelBoostEffect(strength.coerceIn(0f, 1f), isLowEnd, demoEnabled)
            addHeavyEffect("Depixel", depixelEffect!!)
        }
        // 2. Detail Boost (incompatible con FSR, ya resuelto en el menú).
        if (prefs.getBoolean(ExoPlayerSettingsHelper.KEY_DETAIL_BOOST_EN, false) &&
            upscalerMode != ExoPlayerSettingsHelper.MODE_FSR
        ) {
            detailBoostEffect = DetailBoostEffect(prefsFloat(ExoPlayerSettingsHelper.KEY_DETAIL_BOOST_STRENGTH, 70).coerceIn(0f, 1f), isLowEnd, demoEnabled)
            addHeavyEffect("Detail", detailBoostEffect!!)
        }
        // 3. HDR Boost: expande el rango primero para que Cine remate encima.
        val hdrOn = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_HDR_EN, false)
        if (hdrOn) {
            hdrBoostEffect = HDRBoostEffect(prefsFloat(ExoPlayerSettingsHelper.KEY_HDR_STRENGTH, 60).coerceIn(0f, 1f), demoEnabled)
            addHeavyEffect("HDR", hdrBoostEffect!!)
        }
        // 4. Iluminación Cinemática: remate con volumen + tonos + viñeta.
        // Si HDR está prendido suaviza su curva para no pelearse con él.
        if (prefs.getBoolean(ExoPlayerSettingsHelper.KEY_CINE_EN, false)) {
            var master = prefsFloat(ExoPlayerSettingsHelper.KEY_CINE_STRENGTH, 60)
            if (isLowEnd) master = master.coerceIn(0f, 0.5f)
            cinematicEffect = CinematicBoostEffect(
                master.coerceIn(0f, 1f),
                prefsFloat(ExoPlayerSettingsHelper.KEY_CINE_AO, 35).coerceIn(0f, 1f),
                prefsFloat(ExoPlayerSettingsHelper.KEY_CINE_SAT, 30).coerceIn(0f, 1f),
                (0.7f + prefsFloat(ExoPlayerSettingsHelper.KEY_CINE_BRIGHT, 50) * 0.6f).coerceIn(0.7f, 1.3f),
                demoEnabled,
                hdrOn,
            )
            addHeavyEffect("Cine", cinematicEffect!!)
        }
        // 5. Colors Boost: remate de color (1 fetch, no entra al cupo).
        if (prefs.getBoolean(ExoPlayerSettingsHelper.KEY_COLORS_EN, false)) {
            colorsBoostEffect = ColorsBoostEffect(prefsFloat(ExoPlayerSettingsHelper.KEY_COLORS_STRENGTH, 60).coerceIn(0f, 1f), demoEnabled)
            effects.add(colorsBoostEffect!!)
        }
        // 6. MotionX2 Boost (en demo también participa: cada mitad usa su
        // mismo instante, la derecha mezcla con su propio historial).
        if (prefs.getBoolean(ExoPlayerSettingsHelper.KEY_MOTIONX2_EN, false)) {
            var modeIndex = prefs.getInt(ExoPlayerSettingsHelper.KEY_MOTIONX2_MODE, 0)
            var strength = prefsFloat(ExoPlayerSettingsHelper.KEY_MOTIONX2_STRENGTH, 50)
            if (isLowEnd) {
                modeIndex = MotionX2Mode.HYBRID.ordinal
                strength = strength.coerceIn(0f, 0.5f)
            }
            val mode = MotionX2Mode.values().getOrNull(modeIndex) ?: MotionX2Mode.HYBRID
            motionX2Effect = MotionX2BoostEffect(mode, strength.coerceIn(0f, 1f), demoEnabled)
            addHeavyEffect("MotionX2", motionX2Effect!!)
        }
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
        if (wasPlayingBeforePause) player?.playWhenReady = true
    }

    override fun onPause() {
        wasPlayingBeforePause = player?.playWhenReady == true
        player?.playWhenReady = false
        playerView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        playerView.player = null
        player?.release()
        player = null
        audioFx?.setListener(null)
        audioFx?.release()
        audioFx = null
        clearCachedEffects()
        super.onDestroy()
    }
}