package com.karin.streamtv.player

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.Listener
import androidx.media3.effect.GlEffect
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.karin.streamtv.R
import com.karin.streamtv.enhancer.KarinLightBoostController
import com.karin.streamtv.enhancer.RestoreBoostController
import com.karin.streamtv.enhancer.gpu.KarinLightBoostEffect
import com.karin.streamtv.player.dsp.AudioDspUi
import com.karin.streamtv.player.dsp.AudioEnhanceConfig
import com.karin.streamtv.player.dsp.AudioEnhanceProcessor
import com.karin.streamtv.player.sixty.MotionX2GlesRenderer
import com.karin.streamtv.util.AppPreferences
import com.karin.streamtv.util.DeviceProfile
import com.karin.streamtv.util.PlaylistQueue

class ExoPlayerActivity : AppCompatActivity() {

    private companion object {
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 11; KarinFLiX TV) ExoPlayer"
        /** Snapshot de la última configuración de video que sí reprodujo. */
        const val LAST_GOOD_PREFS = "last_good_video_prefs"
        /** Error de procesado de video (GPU/efectos/formato) con fallback. */
        const val ERROR_GPU_EFFECTS = 7001
    }

    private var player: ExoPlayer? = null
    private var dsp: AudioEnhanceProcessor? = null
    private lateinit var playerView: PlayerView
    private var wasPlayingBeforePause = true
    // Render propio GLES2 de 60 fps (modos MotionX2 INTERP/REAL60): bypass total
    // del grafo de efectos de Media3. Nulo en el resto de modos.
    private var ownRenderActive = false
    private var glesRenderer: MotionX2GlesRenderer? = null

    private var currentVideoUrl: String? = null

    // Resolución REAL (no calculada) reportada por el configure() del pipeline
    // GL cuando Media3 arma/reaplica los efectos. Solo se usa en las estadísticas.
    private var osdInputW = 0
    private var osdInputH = 0
    private var osdOutputW = 0
    private var osdOutputH = 0
    private var osdLabel = ""

    // Estado REAL de la última cadena construida (lo que addChainEffects
    // encoló y lo que descartó por presupuesto). Se muestra en el OSD.
    private var chainActive = mutableListOf<String>()
    private var chainOmitted = mutableListOf<String>()
    private var chainMotionLabel = ""
    private var chainUpscalerLabel = ""
    // Fallback error 7001: pasos de recuperación ya intentados en este video
    // (0 = nada, 1 = config anterior restaurada, 2 = modo seguro) y bandera
    // de cadena vacía (runtime: no toca los ajustes del usuario).
    private var errorRecoverAttempt = 0
    private var forceNoEffects = false

    // Cached effect instances for live slider updates
    private var restoreEffect: RestoreBoostEffect? = null
    private var shaderEffect: GlEffect? = null
    private var karinLightBoostEffect: KarinLightBoostEffect? = null
    private var colorsBoostEffect: ColorsBoostEffect? = null
    private var superResolutionEffect: SuperResolutionEffect? = null
    private var superResRcasEffect: SuperResRcasEffect? = null
    // Lambda DRS del upscaler (outW/inW real del pase 1): la alimenta el
    // callback GL en runtime y la lee el pase 2 KarinSharp (uScaleFactor).
    private val karinScale = floatArrayOf(2f)
    private var motionX2Effect: MotionX2BoostEffect? = null
    private var karin3DEffect: Karin3DEffect? = null
    private var visionEffect: VisionAssistEffect? = null

    private val prefs by lazy {
        getSharedPreferences(ExoPlayerSettingsHelper.PREFS_NAME, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // La pantalla no se apaga mientras se ve video.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Modos MotionX2 de 60 fps reales (INTERP/REAL60): layout con TextureView
        // propio + PlayerView sin superficie. Sin gate de gama: el render propio
        // es más barato que el grafo (<=2 pases por vsync) y se honra el modo
        // pedido explícito. El resto de modos usa el layout original.
        val mxOrdinal = prefs.getInt(ExoPlayerSettingsHelper.KEY_MOTIONX2_MODE, 0)
        ownRenderActive = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_MOTIONX2_EN, false) &&
            MotionX2Mode.resolveStored(mxOrdinal).isRealFps()
        setContentView(
            if (ownRenderActive) R.layout.activity_exo_player_motionx2
            else R.layout.activity_exo_player,
        )
        playerView = findViewById(R.id.player_view)
        if (ownRenderActive) {
            // El shutter negro del PlayerView taparía nuestro video: transparente.
            playerView.setShutterBackgroundColor(Color.TRANSPARENT)
            glesRenderer = MotionX2GlesRenderer()
        }
        wirePlayerButtons(playerView)
        AudioEnhanceConfig.setAppVolume(AppPreferences.getPlayerVolume())

        val url = intent.getStringExtra("video_url") ?: intent.data?.toString()
        currentVideoUrl = url
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
        // UA por stream: lista M3U o proveedor puede exigir un user-agent propio.
        val userAgent = intent.getStringExtra("user_agent").orEmpty()
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
            setUserAgent(userAgent.ifBlank { DEFAULT_USER_AGENT })
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

        setupPlaylistButtons(playerView)
        // DSP completo (perfiles, EQ 10 bandas, IR, binaural, compresor...) como
        // AudioProcessor propio inyectado al AudioSink.
        val dsp = AudioEnhanceProcessor(this)
        this.dsp = dsp
        player = ExoPlayer.Builder(this, CodecSelectorFactory.renderersFactoryWithAudio(this, arrayOf(dsp)))
            .setMediaSourceFactory(mediaSourceFactory)
            .build().also {
            it.setMediaItem(mediaItem)
            // Aplica las preferencias de reproducción (speed/volumen) que existían
            // en AppPreferences pero nunca llegaban al reproductor.
            it.setPlaybackSpeed(AppPreferences.getPlayerSpeed())
            it.setVolume(AppPreferences.getPlayerVolume().coerceIn(0f, 1f))
            applyVideoEffects(it)
            it.addListener(object : Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // Fallback 7001: antes de rendirse, reintenta con la
                    // configuración anterior y luego sin efectos.
                    if (tryRecoverFromError(error)) return
                    Log.e("ExoPlayerActivity", "Playback error: ${error.message}", error)
                    Toast.makeText(this@ExoPlayerActivity, playerErrorMessage(error), Toast.LENGTH_LONG).show()
                    finish()
                }

                override fun onRenderedFirstFrame() {
                    // Este video sí reproduce: guarda su configuración como
                    // última buena (para volver a ella si un video futuro
                    // falla). En modo seguro no se guarda (no es config real).
                    if (!forceNoEffects) snapshotEffectPrefs()
                }

                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    // Tamaño decodificado real (entrada del pipeline).
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        osdInputW = videoSize.width
                        osdInputH = videoSize.height
                        glesRenderer?.setVideoSize(videoSize.width, videoSize.height)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    glesRenderer?.setPlaying(isPlaying)
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                        glesRenderer?.reset()
                    }
                }
            })
            it.prepare()
            it.playWhenReady = true
            playerView.player = it
            if (ownRenderActive) {
                val modeIdx = prefs.getInt(ExoPlayerSettingsHelper.KEY_MOTIONX2_MODE, 0)
                val mxMode = MotionX2Mode.resolveStored(modeIdx)
                val demo = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_DEMO_EN, false)
                val texView = findViewById<SurfaceView>(R.id.motionx2_surface)
                glesRenderer?.attach(
                    texView,
                    it,
                    mxMode,
                    demo,
                ) { ExoPlayerSettingsHelper.getAspectRatioMode(prefs) }
            }
        }
    }

    /** Misma botonera original en ambas rutas (PlayerView normal o solo-controles). */
    private fun wirePlayerButtons(pv: PlayerView) {
        pv.findViewById<ImageButton>(R.id.btn_ratio)?.setOnClickListener {
            val next = (ExoPlayerSettingsHelper.getAspectRatioMode(prefs) + 1) % ExoPlayerSettingsHelper.ASPECT_RATIO_MODES
            ExoPlayerSettingsHelper.setAspectRatioMode(prefs, next)
            applyAspectRatioMode(pv, next)
            Toast.makeText(this, "Proporción: ${ExoPlayerSettingsHelper.aspectRatioLabel(next)}", Toast.LENGTH_SHORT).show()
        }
        pv.findViewById<ImageButton>(R.id.btn_settings)?.setOnClickListener {
            // OSD con la cadena REAL (lo que de verdad se construyó, no lo que
            // pidió el usuario) + omitidos por presupuesto + no-ops del upscaler.
            showChainOsd()
            ExoPlayerSettingsHelper.showAdvancedDialog(
                activity = this,
                prefs = prefs,
                player = player,
                videoInputHeight = osdInputH,
                onEffectsChanged = { rebuildEffects(it) },
                onStrengthChanged = { effectType, strength -> liveRoute(effectType, strength) },
                onKarinChanged = { p ->
                    karinLightBoostEffect?.update(p)
                },
                onRestoreChanged = { d, r, t ->
                    restoreEffect?.updateStages(d, r, t)
                },
            )
        }
        // Botón lentes: TECNOLOGÍA 3D. Abre el diálogo 3D directo con
        // rebuildEffects/liveRoute (misma ruta que los ajustes avanzados).
        pv.findViewById<ImageButton>(R.id.btn_3d)?.apply {
            isEnabled = true
            isClickable = true
            isFocusable = true
            alpha = 1f
            contentDescription = "Modo 3D"
            setOnClickListener {
                ExoPlayerSettingsHelper.show3DDialog(
                    activity = this@ExoPlayerActivity,
                    prefs = prefs,
                    player = player,
                    onEffectsChanged = { rebuildEffects(it) },
                    onLiveDepth = { v -> liveRoute("td_depth", v) },
                )
            }
        }
        pv.findViewById<ImageButton>(R.id.btn_vision)?.setOnClickListener {
            // Botón de anteojos: Asistencia de visión y audición (visión a la
            // cadena GL del reproductor, audición al DSP; no vive dentro de las
            // Opciones Avanzadas de Video).
            VisionAssistHelper.showVisionDialog(
                activity = this@ExoPlayerActivity,
                prefs = prefs,
                player = player,
                onEffectsChanged = { rebuildEffects(it) },
                onLive = { cfg -> visionEffect?.update(cfg) },
            )
        }
        pv.findViewById<ImageButton>(R.id.btn_dsp)?.setOnClickListener {
            openSoundSettings()
        }
        pv.findViewById<ImageButton>(R.id.btn_stats)?.setOnClickListener {
            // Pasa el dato REAL del pipeline GL (si ya está configurado) +
            // la cadena real construida (activos/omitidos) para que las
            // estadísticas no mientan cuando la GPU omite un filtro.
            val realIn = if (osdInputW > 0) osdInputW to osdInputH else null
            val realOut = if (osdOutputW > 0) osdOutputW to osdOutputH else null
            VideoStatsHelper.showStatsDialog(
                activity = this,
                player = player,
                prefs = prefs,
                videoUrl = currentVideoUrl,
                realInput = realIn,
                realOutput = realOut,
                chainActive = chainActive.toList(),
                chainOmitted = chainOmitted.toList(),
                chainMotionLabel = chainMotionLabel.ifBlank { null },
                chainUpscalerLabel = (chainUpscalerLabel.ifBlank { osdLabel }).ifBlank { null },
                playbackSpeed = try { player?.playbackParameters?.speed ?: AppPreferences.getPlayerSpeed() } catch (_: Exception) { 1f },
                aspectLabel = ExoPlayerSettingsHelper.aspectRatioLabel(ExoPlayerSettingsHelper.getAspectRatioMode(prefs)),
                dsp = dsp,
            )
        }
        pv.findViewById<ImageButton>(R.id.btn_close)?.setOnClickListener {
            finish()
        }
        setupPlaylistButtons(pv)
    }

    /** Panel de sonido único: volumen + DSP + auriculares + avanzado. */
    private fun openSoundSettings() {
        AudioDspUi.showSoundDialog(
            this,
            onAdvanced = {
                AudioDspUi.showAdvanced(this)
            },
            player = player,
        )
    }

    /** Aplica el modo de relación de aspecto al frame interno del PlayerView. */
    private fun applyAspectRatioMode(pv: PlayerView, mode: Int) {
        val frame = pv.findViewById<androidx.media3.ui.AspectRatioFrameLayout>(
            androidx.media3.ui.R.id.exo_content_frame
        ) ?: return
        val h = ExoPlayerSettingsHelper
        frame.resizeMode = when (mode) {
            h.MODE_ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            h.MODE_STRETCH -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        when (mode) {
            h.MODE_4_3 -> frame.setAspectRatio(4f / 3f)
            h.MODE_16_9 -> frame.setAspectRatio(16f / 9f)
            h.MODE_2_35 -> frame.setAspectRatio(2.35f)
            else -> frame.setAspectRatio(0f)
        }
    }

    private fun playerErrorMessage(error: PlaybackException): String {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Sin conexión o el servidor no responde"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                "Archivo no encontrado"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED ->
                "Este video no se puede decodificar en este equipo"
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED ->
                "Error 7000 al iniciar el procesador de video (efectos o formato)"
            ERROR_GPU_EFFECTS ->
                "Error 7001 al procesar el video (ni la configuración anterior ni el modo seguro funcionaron)"
            // Códigos desconocidos (p. ej. 1103): se muestra el nombre oficial
            // de Media3 para identificar la familia exacta del fallo.
            else -> try {
                "No se pudo reproducir el video (${error.errorCode} · ${error.errorCodeName})"
            } catch (_: Exception) {
                "No se pudo reproducir el video (${error.errorCode})"
            }
        }
    }

    /** Anterior/siguiente cambian de capítulo cuando hay playlist. */
    private fun setupPlaylistButtons(pv: PlayerView) {
        val playlist = PlaylistQueue.fromJson(intent.getStringExtra("playlist_json"))
        if (playlist.isEmpty()) return
        val index = intent.getIntExtra("playlist_index", 0)
        val siteName = intent.getStringExtra("site_name").orEmpty()
        pv.findViewById<ImageButton>(androidx.media3.ui.R.id.exo_prev)?.apply {
            isEnabled = index > 0
            setOnClickListener {
                startActivity(PlaylistQueue.buildIntent(this@ExoPlayerActivity, playlist, index - 1, siteName))
                finish()
            }
        }
        pv.findViewById<ImageButton>(androidx.media3.ui.R.id.exo_next)?.apply {
            isEnabled = index < playlist.size - 1
            setOnClickListener {
                startActivity(PlaylistQueue.buildIntent(this@ExoPlayerActivity, playlist, index + 1, siteName))
                finish()
            }
        }
    }

    private fun prefsFloat(key: String, def: Int) = prefs.getInt(key, def) / 100f

    /** Reconstruye la cadena + OSD. Lo usan ajustes, lentes y diálogos. */
    private fun rebuildEffects(p: ExoPlayer) {
        // Si el cambio de modo cruza grafo<->render propio, el layout es otro:
        // se recrea la actividad (mismo intent/video) para cablear limpio.
        // Dentro del mismo camino, el render propio cambia de modo en vivo.
        val wantOwn = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_MOTIONX2_EN, false) &&
            MotionX2Mode.resolveStored(
                prefs.getInt(ExoPlayerSettingsHelper.KEY_MOTIONX2_MODE, 0),
            ).isRealFps()
        if (wantOwn != ownRenderActive) {
            recreate()
            return
        }
        if (wantOwn) {
            glesRenderer?.setMode(
                MotionX2Mode.resolveStored(
                    prefs.getInt(ExoPlayerSettingsHelper.KEY_MOTIONX2_MODE, 0),
                ),
            )
        }
        applyVideoEffects(p)
        showChainOsd()
    }

    /** Preview en vivo de sliders. Compartido por ajustes y botón lentes. */
    private fun liveRoute(effectType: String, strength: Float) {
        when (effectType) {
            "restore" -> {
                // Preview en vivo del master: deriva igual que la
                // cadena (el factor upscaler se relee de prefs).
                val s = strength.coerceIn(0f, 1f)
                restoreEffect?.updateStages(s, s, s * restoreDetailFactor())
            }
            "colors" -> karinLightBoostEffect?.updateColorStrength(strength)
            "shader" -> {
                val (t, _) = ExoPlayerSettingsHelper.shaderSelection(prefs)
                val e = shaderEffect
                when (t) {
                    ExoPlayerSettingsHelper.SHADER_CINE ->
                        (e as? CineBoostEffect)?.updateStrength(strength)
                    ExoPlayerSettingsHelper.SHADER_BW ->
                        (e as? BwBoostEffect)?.updateStrength(strength)
                    else -> (e as? CrtBoostEffect)?.updateStrength(strength)
                }
            }
            "upscaler_sharp" -> {
                superResolutionEffect?.updateSharpness(strength)
                superResRcasEffect?.updateSharpness(strength)
            }
            "td_depth" -> karin3DEffect?.updateDepth(strength)
        }
    }

    /** Factor del detalle en vivo: el upscaler ya afila por su cuenta. */
    private fun restoreDetailFactor(): Float {
        if (!prefs.getBoolean(ExoPlayerSettingsHelper.KEY_UPSCALER_EN, false)) return 1f
        val mode = prefs.getInt(ExoPlayerSettingsHelper.KEY_UPSCALER_MODE, SuperResolutionEffect.MODE_FSR)
        if (mode == SuperResolutionEffect.MODE_FSR) return 0.55f
        if (mode == SuperResolutionEffect.MODE_ANIME4K) return 0.5f
        if (mode == SuperResolutionEffect.MODE_KARIN) return 0.55f
        return 1f
    }

    /**
     * Fallback ante error de procesado (7001 y familia decodificador):
     * paso 1 = restaura la última configuración que sí funcionó,
     * paso 2 = modo seguro sin efectos (sin tocar ajustes del usuario).
     * Devuelve true si reintentó (no cerrar), false si ya no hay más pasos.
     */
    private fun tryRecoverFromError(error: PlaybackException): Boolean {
        val code = error.errorCode
        // Familia "procesado/decodificación": 7001 (frames/efectos GL),
        // 7000 (init del procesador) y 4001-4006 (decodificador). El 7001
        // literal se conserva porque el constant no existe en Media3 viejo.
        val effectsRelated = code == ERROR_GPU_EFFECTS ||
            code == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED ||
            code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            code == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
            code == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            code == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
            code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
            code == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED
        if (!effectsRelated) return false
        val p = player ?: return false
        if (errorRecoverAttempt == 0) {
            errorRecoverAttempt = 1
            if (restoreLastGoodIfDifferent()) {
                Log.w("ExoPlayerActivity", "Error $code: reintentando con configuración anterior")
                Toast.makeText(this, "Error $code: volviendo a la última configuración que funcionó…", Toast.LENGTH_LONG).show()
                retryPlayback(p)
                return true
            }
            // Sin snapshot distinto: cae directo al modo seguro.
        }
        if (errorRecoverAttempt <= 1) {
            errorRecoverAttempt = 2
            forceNoEffects = true
            Log.w("ExoPlayerActivity", "Error $code: reintentando en modo seguro (sin efectos)")
            Toast.makeText(this, "Error $code: reintentando sin efectos (modo seguro)…", Toast.LENGTH_LONG).show()
            retryPlayback(p)
            return true
        }
        return false
    }

    /** Re-prepara conservando la posición (si el error la conservó). */
    private fun retryPlayback(p: ExoPlayer) {
        val pos = try { p.currentPosition.coerceAtLeast(0L) } catch (_: Exception) { 0L }
        try {
            applyVideoEffects(p)
            p.seekTo(pos)
            p.prepare()
            p.playWhenReady = true
        } catch (e: Exception) {
            Log.e("ExoPlayerActivity", "Reintento fallido", e)
            Toast.makeText(this, playerErrorMessage(PlaybackException(null, e, 0)), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /** Guarda la configuración actual de video como última buena. */
    private fun snapshotEffectPrefs() {
        try {
            val ed = getSharedPreferences(LAST_GOOD_PREFS, MODE_PRIVATE).edit().clear()
            for ((k, v) in prefs.all) {
                when (v) {
                    is Boolean -> ed.putBoolean(k, v)
                    is Int -> ed.putInt(k, v)
                    is Long -> ed.putLong(k, v)
                    is Float -> ed.putFloat(k, v)
                    is String -> ed.putString(k, v)
                }
            }
            ed.apply()
        } catch (_: Exception) { }
    }

    /**
     * Restaura el snapshot solo si difiere del actual (si es igual,
     * reintentar sería repetir el mismo fallo). Tus ajustes de velocidad y
     * volumen viven en otro archivo y no se tocan.
     */
    private fun restoreLastGoodIfDifferent(): Boolean {
        return try {
            val bg = getSharedPreferences(LAST_GOOD_PREFS, MODE_PRIVATE)
            if (bg.all.isEmpty() || bg.all == prefs.all) return false
            val ed = prefs.edit().clear()
            for ((k, v) in bg.all) {
                when (v) {
                    is Boolean -> ed.putBoolean(k, v)
                    is Int -> ed.putInt(k, v)
                    is Long -> ed.putLong(k, v)
                    is Float -> ed.putFloat(k, v)
                    is String -> ed.putString(k, v)
                }
            }
            ed.apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun clearCachedEffects() {
        restoreEffect = null
        shaderEffect = null
        karinLightBoostEffect = null
        colorsBoostEffect = null
        superResolutionEffect = null
        superResRcasEffect = null
        motionX2Effect = null
        karin3DEffect = null
        visionEffect = null
    }

    private fun applyVideoEffects(exoPlayer: ExoPlayer) {
        // Limpia la caché: si no, los sliders actualizan efectos viejos fuera de la cadena.
        clearCachedEffects()
        // El OSD refleja el estado real del pipeline actual; se rellena cuando
        // Media3 llame configure() en cada efecto de la cadena.
        osdInputW = 0
        osdInputH = 0
        osdOutputW = 0
        osdOutputH = 0
        osdLabel = ""
        // El reporte del OSD se reconstruye con la cadena real de hoy.
        chainActive.clear()
        chainOmitted.clear()
        chainMotionLabel = ""
        chainUpscalerLabel = ""
        // Render propio GLES2 (modos INTERP/REAL60): el decodificador vuelca
        // directo a nuestro SurfaceTexture y el vsync dibuja los 60 fps. NO se
        // llama a setVideoEffects ni siquiera con lista vacía: una lista vacía
        // (no-nula) TAMBIÉN crea el PlaybackVideoGraphWrapper y su
        // FinalShaderWrapper descarta los cuadros ("Output surface and size
        // not set"). Sin llamar queda null = ruta directa del decodificador.
        // El resto de filtros de imagen se pausan en este modo (una salida).
        if (ownRenderActive) {
            val modeIdx = prefs.getInt(ExoPlayerSettingsHelper.KEY_MOTIONX2_MODE, 0)
            val mxMode = MotionX2Mode.resolveStored(modeIdx)
            chainMotionLabel = mxMode.label
            val ownKind = if (mxMode == MotionX2Mode.DOUBLING) "x2 real" else "60fps"
            chainActive.add("MotionX2 ${mxMode.label} (render propio $ownKind)")
            chainOmitted.add("Filtros de imagen (en pausa en este modo)")
            Log.d("ExoPlayerActivity", "Render propio GLES2 activo: $mxMode, grafo vacío")
            return
        }
        // Demo: cada efecto conserva la mitad izquierda intacta y al final
        // solo se pinta la línea. Sin copias entre cuadros: no se desincroniza.
        val effects = mutableListOf<Effect>()
        val demoEnabled = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_DEMO_EN, false)

        // Modo seguro (tras error 7001): cadena vacía, sin tocar ajustes.
        // La línea demo se conserva (es trivial y ayuda a diagnosticar).
        if (forceNoEffects) {
            chainActive.add("Modo seguro")
            Log.w("ExoPlayerActivity", "Modo seguro: sin efectos de imagen")
        } else {
            addChainEffects(effects, demoEnabled)
        }

        if (demoEnabled) {
            effects.add(DemoLineEffect())
            chainActive.add("Demo")
        }
        Log.d("ExoPlayerActivity", "Cadena de efectos: ${effects.joinToString { it.javaClass.simpleName }}")
        exoPlayer.setVideoEffects(effects)
    }

    /** OSD: muestra la cadena REAL construida, los omitidos por presupuesto y
     *  los avisos (p. ej. Upscaler sin efecto en contenido 1080p+). */
    private fun showChainOsd() {
        val lines = mutableListOf<String>()
        lines += "Cadena real: ${if (chainActive.isEmpty()) "(sin filtros de imagen)" else chainActive.joinToString(" › ")}"
        if (chainOmitted.isNotEmpty()) {
            lines += "Omitidos (límite GPU): ${chainOmitted.joinToString(", ")} — apaga otro filtro para usarlos"
        }
        val upscalerEn = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_UPSCALER_EN, false)
        if (upscalerEn && osdInputH >= 1080) {
            lines += "Upscaler: sin efecto en ≥1080p (solo aplica en SD/720p)"
        }
        Toast.makeText(this, lines.joinToString("\n"), Toast.LENGTH_LONG).show()
    }

    private fun addChainEffects(effects: MutableList<Effect>, demoEnabled: Boolean = false) {
        val isLowEnd = isLowEndDevice()
        val upscalerOn = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_UPSCALER_EN, false)
        val upscalerMode = prefs.getInt(ExoPlayerSettingsHelper.KEY_UPSCALER_MODE, SuperResolutionEffect.MODE_FSR)

        // Presupuesto de pases GL: cada filtro es 1 pase de pantalla completa.
        // Por potencia: gama alta 7, media 5, baja 3 (menos escalador pesado).
        // El demo ya no reserva nada (cada efecto parte su propia pasada).
        val upscalerHeavy = if (upscalerOn) 1 else 0
        val baseBudget = when {
            isLowEnd -> 3
            isHighEndDevice() -> 7
            else -> 5
        }
        val maxHeavyEffects = (baseBudget - upscalerHeavy).coerceAtLeast(2)
        var heavyEffectCount = 0

        fun addHeavyEffect(label: String, effect: Effect) {
            if (heavyEffectCount < maxHeavyEffects) {
                effects.add(effect)
                heavyEffectCount++
                chainActive.add(label)
            } else {
                chainOmitted.add(label)
                Log.w("ExoPlayerActivity", "Efecto omitido por límite en este equipo: $label")
                Toast.makeText(
                    this@ExoPlayerActivity,
                    "$label omitido: apaga otro filtro o el demo para usarlo",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        // 1. Restore Boost: limpieza + reconstrucción + detalle en UN pase.
        //    Una sola intensidad deriva las 3 etapas; dentro del pase, el
        //    afilado se frena por píxel donde se limpió (sin parches por
        //    sliders como antes).
        run {
            val (_, restoreEn) = RestoreBoostController.masterAndEnabled(prefs)
            if (restoreEn) {
                val stages = RestoreBoostController.stagesFromPrefs(prefs, upscalerOn, upscalerMode, isLowEnd)
                if (stages.anyOn) {
                    restoreEffect = RestoreBoostEffect(
                        stages.depixel, stages.retro, stages.detail,
                        isLowEnd, demoEnabled,
                    )
                    addHeavyEffect("Restore", restoreEffect!!)
                } else {
                    chainOmitted.add("Restore")
                }
            }
        }
        // 3. Karin Light Boost (estilo Splash): iluminación adaptativa por
        //    luminancia + función extra de color (ex-Colors Boost, misma
        //    pasada), modo Manual/Auto según preferencias.
        if (prefs.getBoolean(ExoPlayerSettingsHelper.KEY_CINE_EN, false) ||
            prefs.getBoolean(ExoPlayerSettingsHelper.KEY_COLORS_EN, false) ||
            prefs.getInt(ExoPlayerSettingsHelper.KEY_RANGE_MODE, 0) != 0
        ) {
            val karinParams = KarinLightBoostController.fromPrefs(prefs)
            karinLightBoostEffect = KarinLightBoostEffect(karinParams, demoEnabled)
            Log.d("ExoPlayerActivity", "Light Boost activo (auto=${karinParams.autoMode}, boost=${karinParams.prefValue}, color=${karinParams.colorStrength})")
            addHeavyEffect("Light+Color", karinLightBoostEffect!!)
        }

        // 4. Upscaler de calidad (KarinSuperRes/FSR/Anime4K): reescala al final.
        // Al existir como efecto propio ocupa el presupuesto de pases; cuando
        // solo restaura la pasada half-res de Light Boost sería gratis, pero
        // hoy Light Boost corre a resolución completa y el upscaler va libre.
        // Karin por gama: LOW = ECO (1 pase barato), MID = CRISP (1 pase
        // completo), HIGH = 2 pases (KarinEasu limpio + KarinSharp real).
        if (upscalerOn) {
            val mode = upscalerMode
            val sharpness = prefsFloat(ExoPlayerSettingsHelper.KEY_UPSCALER_SHARP, 40)
                .coerceIn(0f, 1f)
            // Dos pases estilo madVR (el escalador y una pasada que afila el
            // resultado real). Solo en gama alta: el 2do pase corre a
            // resolucion de SALIDA (hasta 1080p = 4x pixeles) y en gama
            // media/baja tumba los fps.
            val isHighTier = !isLowEnd && isHighEndDevice()
            val fsrTwoPass = mode == SuperResolutionEffect.MODE_FSR && isHighTier
            val karinTwoPass = mode == SuperResolutionEffect.MODE_KARIN && isHighTier
            val twoPass = fsrTwoPass || karinTwoPass
            val karinVariant =
                if (isLowEnd) SuperResolutionEffect.KARIN_ECO else SuperResolutionEffect.KARIN_CRISP
            val upscaleLabel = when (mode) {
                SuperResolutionEffect.MODE_ANIME4K -> "Anime4K"
                SuperResolutionEffect.MODE_KARIN ->
                    when {
                        karinTwoPass -> "Karin HiRes"
                        karinVariant == SuperResolutionEffect.KARIN_ECO -> "Karin ECO"
                        else -> "Karin"
                    }
                else -> if (fsrTwoPass) "FSR+RCAS" else "FSR"
            }
            chainUpscalerLabel = upscaleLabel
            // El callback corre en el hilo del pipeline GL: lo llevamos al main.
            // El upscaler guarda la ENTRADA real decodificada; el RCAS solo
            // actualiza la salida (la entrada ya la dejó el upscaler).
            val onScaled = { inW: Int, inH: Int, outW: Int, outH: Int ->
                runOnUiThread {
                    osdInputW = inW
                    osdInputH = inH
                    osdOutputW = outW
                    osdOutputH = outH
                    osdLabel = upscaleLabel
                }
                karinScale[0] = if (inW > 0) outW.toFloat() / inW else 2f
                superResRcasEffect?.updateScale(karinScale[0])
                Unit
            }
            val onFinal = { _: Int, _: Int, outW: Int, outH: Int ->
                runOnUiThread {
                    osdOutputW = outW
                    osdOutputH = outH
                    osdLabel = upscaleLabel
                }
            }
            superResolutionEffect = SuperResolutionEffect(
                mode, sharpness, restorePass = false, separateRcas = twoPass,
                karinVariant = karinVariant,
                onConfigured = onScaled,
            )
            addHeavyEffect("Upscaler", superResolutionEffect!!)
            if (twoPass) {
                superResRcasEffect = if (mode == SuperResolutionEffect.MODE_KARIN) {
                    SuperResRcasEffect(sharpness, demoEnabled, onFinal, casMode = true, upscaleRatio = karinScale[0])
                } else {
                    SuperResRcasEffect(sharpness, demoEnabled, onFinal)
                }
                // El RCAS corre a resolucion de salida (caro): entra al cupo.
                addHeavyEffect("RCAS", superResRcasEffect!!)
            }
        }
        // 5. MotionX2 Boost. Va DESPUÉS del upscaler para que su historial y la
        // mezcla temporal trabajen a resolución de pantalla final: así el
        // suavizado se ve como smoothing real y no se "re-escala" tras un FSR.
        // Solo se encola si el upscaler no agotó el cupo de pases GL.
        if (prefs.getBoolean(ExoPlayerSettingsHelper.KEY_MOTIONX2_EN, false)) {
            var modeIndex = prefs.getInt(ExoPlayerSettingsHelper.KEY_MOTIONX2_MODE, 0)
            var strength = 0.5f
            if (isLowEnd) {
                modeIndex = MotionX2Mode.HYBRID.ordinal
                strength = 0.25f
            }
            val mode = MotionX2Mode.resolveStored(modeIndex)
            chainMotionLabel = mode.label
            motionX2Effect = MotionX2BoostEffect(mode, strength.coerceIn(0f, 1f), demoEnabled)
            addHeavyEffect("MotionX2", motionX2Effect!!)
        }
        // 6. Shader (selector CRT/Cine/B-N): acabado final tras MotionX2.
        //    Un tipo a la vez, 1 fetch/px, entra al cupo de pesados.
        run {
            val (shaderType, shaderStrength) =
                ExoPlayerSettingsHelper.shaderSelection(prefs)
            if (shaderType != ExoPlayerSettingsHelper.SHADER_OFF && shaderStrength > 0f) {
                val label = ExoPlayerSettingsHelper.shaderTypeName(shaderType)
                shaderEffect = when (shaderType) {
                    ExoPlayerSettingsHelper.SHADER_CINE -> CineBoostEffect(shaderStrength, demoEnabled)
                    ExoPlayerSettingsHelper.SHADER_BW -> BwBoostEffect(shaderStrength, demoEnabled)
                    else -> CrtBoostEffect(shaderStrength, demoEnabled)
                }
                addHeavyEffect("Shader:$label", shaderEffect!!)
            }
        }
        // 6b. Asistencia (botón de anteojos): perfil de accesibilidad.
        //     Visión → UN pase GL tras el Shader y antes del 3D (el 3D
        //     reformatea la salida y no debe teñir el efecto visual).
        //     Audición → corre en el DSP de audio (no ocupa pases GL).
        //     EXENTA del presupuesto de pases: es accesibilidad (un solo pase
        //     barato) y nunca debe quedar "omitida" al activarla el usuario.
        run {
            val cfg = VisionAssistHelper.fromPrefs(prefs)
            if (cfg.isActive && cfg.hasVision) {
                visionEffect = VisionAssistEffect(cfg)
                effects.add(visionEffect!!)
                chainActive.add("Visión")
                Log.d("ExoPlayerActivity", "Visión activa (${VisionAssistHelper.needsLabel(cfg)}), fuera de cupo por accesibilidad")
            }
            if (cfg.hasAudSpeech || cfg.hasAudLoss) {
                chainActive.add("Audición")
                Log.d("ExoPlayerActivity", "Asistencia de audición activa (voz=${cfg.hasAudSpeech}, agudos=${cfg.hasAudLoss})")
            }
        }
        // 7. Tecnología 3D (botón lentes): reformatea la SALIDA al final de
        //    la cadena (tras Shader/Visión, antes de la línea Demo). 1 pase
        //    GL barato. EXENTO del presupuesto igual que Visión: es el
        //    reformateo final que el usuario pidió explícito y, al ir
        //    último, antes era el primero en caer en silencio ("lo activo
        //    y no pasa nada").
        //    Ver Karin3DController.compatWarnings(): B/N+anaglifo y
        //    MotionX2+Pulfrich son incompatibles reales; Upscaler/Light
        //    degradan según modo (el diálogo ya avisa).
        run {
            if (Karin3DController.isActive(prefs)) {
                karin3DEffect = Karin3DEffect(
                    mode = Karin3DController.currentMode(prefs),
                    depth = Karin3DController.currentDepth(prefs),
                    swapEye = Karin3DController.isSwapEye(prefs),
                    demoSplit = demoEnabled,
                    anaglyph = Karin3DController.anaglyphType(prefs),
                    inputKind = Karin3DController.inputKind(prefs),
                )
                effects.add(karin3DEffect!!)
                chainActive.add(Karin3DController.chainLabel(prefs))
                // Los avisos ⛔/⚠ se muestran en el diálogo 3D y al aplicar;
                // además se dejan en log para diagnóstico.
                Karin3DController.compatWarnings(prefs).forEach {
                    android.util.Log.w("ExoPlayerActivity", "3D compat: $it")
                }
            }
        }
    }

    private fun isLowEndDevice(): Boolean {
        // Fuente unica: DeviceProfile (RAM + cores + heap + benchmark CPU).
        // Fallback al chequeo anterior si el perfil aun no existe.
        try {
            if (DeviceProfile.get(this).tier == DeviceProfile.Tier.LOW) return true
        } catch (_: Throwable) { }
        // Manual override desde ajustes (modo low-end forzado).
        try {
            if (AppPreferences.getPrefs()?.getBoolean(AppPreferences.KEY_LOW_END, false) == true) return true
        } catch (_: Throwable) { }
        val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem < 4L * 1024 * 1024 * 1024 || activityManager.isLowRamDevice
    }

    private fun isHighEndDevice(): Boolean {
        try {
            val tier = DeviceProfile.get(this).tier
            if (tier == DeviceProfile.Tier.HIGH) return true
            if (tier == DeviceProfile.Tier.LOW) return false
        } catch (_: Throwable) { }
        val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        if (activityManager.isLowRamDevice) return false
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem >= 6L * 1024 * 1024 * 1024
    }



    // ── Control remoto Karin Link ──────────────────────────────

    /** Alterna reproducción/pausa. Devuelve false si no hay reproductor. */
    fun remoteTogglePlay(): Boolean {
        val p = player ?: return false
        p.playWhenReady = !p.playWhenReady
        return true
    }

    fun remoteSetPlaying(playing: Boolean): Boolean {
        val p = player ?: return false
        p.playWhenReady = playing
        return true
    }

    /** Salta [deltaMs] (negativo = atrás). Devuelve false si no hay reproductor. */
    fun remoteSeekBy(deltaMs: Long): Boolean {
        val p = player ?: return false
        val end = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0L, end))
        return true
    }

    override fun onResume() {
        super.onResume()
        playerView.onResume()
        glesRenderer?.onActivityResume()
        if (wasPlayingBeforePause) player?.playWhenReady = true
    }

    override fun onPause() {
        wasPlayingBeforePause = player?.playWhenReady == true
        player?.playWhenReady = false
        glesRenderer?.onActivityPause()
        playerView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        glesRenderer?.detach()
        glesRenderer = null
        playerView.player = null
        player?.release()
        player = null
        clearCachedEffects()
        super.onDestroy()
    }
}