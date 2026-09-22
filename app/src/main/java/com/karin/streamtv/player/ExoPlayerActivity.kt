package com.karin.streamtv.player

import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import com.karin.streamtv.util.AppPreferences
import com.karin.streamtv.util.DeviceProfile
import com.karin.streamtv.util.PlaylistQueue

class ExoPlayerActivity : AppCompatActivity() {

    private companion object {
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 11; KarinFLiX TV) ExoPlayer"
    }

    private var player: ExoPlayer? = null
    private var dsp: AudioEnhanceProcessor? = null
    private lateinit var playerView: PlayerView
    private var wasPlayingBeforePause = true

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

    // Cached effect instances for live slider updates
    private var restoreEffect: RestoreBoostEffect? = null
    private var shaderEffect: GlEffect? = null
    private var karinLightBoostEffect: KarinLightBoostEffect? = null
    private var colorsBoostEffect: ColorsBoostEffect? = null
    private var superResolutionEffect: SuperResolutionEffect? = null
    private var superResRcasEffect: SuperResRcasEffect? = null
    private var motionX2Effect: MotionX2BoostEffect? = null
    private var karin3DEffect: Karin3DEffect? = null

    private val prefs by lazy {
        getSharedPreferences(ExoPlayerSettingsHelper.PREFS_NAME, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // La pantalla no se apaga mientras se ve video.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_exo_player)
        playerView = findViewById(R.id.player_view)
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
                    Log.e("ExoPlayerActivity", "Playback error: ${error.message}", error)
                    Toast.makeText(this@ExoPlayerActivity, playerErrorMessage(error), Toast.LENGTH_LONG).show()
                    finish()
                }

                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    // Tamaño decodificado real (entrada del pipeline).
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        osdInputW = videoSize.width
                        osdInputH = videoSize.height
                    }
                }
            })
            it.prepare()
            it.playWhenReady = true
            playerView.player = it
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
        pv.findViewById<ImageButton>(R.id.btn_shader)?.apply {
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
            else -> "No se pudo reproducir el video (${error.errorCode})"
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
                    ExoPlayerSettingsHelper.SHADER_PIXEL ->
                        (e as? PixelArtBoostEffect)?.updateStrength(strength)
                    ExoPlayerSettingsHelper.SHADER_FILM ->
                        (e as? FilmBoostEffect)?.updateStrength(strength)
                    ExoPlayerSettingsHelper.SHADER_RETRO ->
                        (e as? RetroAnimeBoostEffect)?.updateStrength(strength)
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
        return 1f
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
        // Demo: cada efecto conserva la mitad izquierda intacta y al final
        // solo se pinta la línea. Sin copias entre cuadros: no se desincroniza.
        val effects = mutableListOf<Effect>()
        val demoEnabled = prefs.getBoolean(ExoPlayerSettingsHelper.KEY_DEMO_EN, false)

        addChainEffects(effects, demoEnabled)

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

        // 4. Upscaler de calidad (FSR/Anime4K): reescala al final.
        // Al existir como efecto propio ocupa el presupuesto de pases; cuando
        // solo restaura la pasada half-res de Light Boost sería gratis, pero
        // hoy Light Boost corre a resolución completa y el upscaler va libre.
        if (upscalerOn) {
            val mode = upscalerMode
            val sharpness = prefsFloat(ExoPlayerSettingsHelper.KEY_UPSCALER_SHARP, 40)
                .coerceIn(0f, 1f)
            // Dos pases estilo madVR (el escalador y una pasada que afila el
            // resultado real). Solo en gama alta: el 2do pase corre a
            // resolucion de SALIDA (hasta 1080p = 4x pixeles) y en gama
            // media/baja tumba los fps.
            val fsrTwoPass = mode == SuperResolutionEffect.MODE_FSR && !isLowEnd && isHighEndDevice()
            val twoPass = fsrTwoPass
            val upscaleLabel = when (mode) {
                SuperResolutionEffect.MODE_ANIME4K -> "Anime4K"
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
                onConfigured = onScaled,
            )
            addHeavyEffect("Upscaler", superResolutionEffect!!)
            if (twoPass) {
                superResRcasEffect = SuperResRcasEffect(sharpness, demoEnabled, onFinal)
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
            val mode = MotionX2Mode.values().getOrNull(modeIndex) ?: MotionX2Mode.HYBRID
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
                    ExoPlayerSettingsHelper.SHADER_PIXEL -> PixelArtBoostEffect(shaderStrength, demoEnabled)
                    ExoPlayerSettingsHelper.SHADER_FILM -> FilmBoostEffect(shaderStrength, demoEnabled)
                    ExoPlayerSettingsHelper.SHADER_RETRO -> RetroAnimeBoostEffect(shaderStrength, demoEnabled)
                    else -> CrtBoostEffect(shaderStrength, demoEnabled)
                }
                addHeavyEffect("Shader:$label", shaderEffect!!)
            }
        }
        // 7. Tecnología 3D (botón lentes): reformatea la SALIDA al final de
        //    la cadena (tras Shader, antes de la línea Demo). 1 pase GL.
        //    Ver Karin3DController.compatWarnings(): B/N y CRT-polarizado son
        //    incompatibles reales; Upscaler/MotionX2/Light degradan el 3D
        //    (el diálogo ya avisa y aquí solo se construye la cadena).
        run {
            if (Karin3DController.isActive(prefs)) {
                val label = Karin3DController.chainLabel(prefs).ifBlank { "3D" }
                karin3DEffect = Karin3DEffect(
                    mode = Karin3DController.currentMode(prefs),
                    depth = Karin3DController.currentDepth(prefs),
                    swapEye = Karin3DController.isSwapEye(prefs),
                    stereoInput = Karin3DController.isStereoInput(prefs),
                    demoSplit = demoEnabled,
                    anaglyph = Karin3DController.anaglyphType(prefs),
                    inputKind = Karin3DController.inputKind(prefs),
                )
                addHeavyEffect(label, karin3DEffect!!)
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
        if (wasPlayingBeforePause) player?.playWhenReady = true
    }

    override fun onPause() {
        wasPlayingBeforePause = player?.playWhenReady == true
        player?.playWhenReady = false
        playerView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playerView.player = null
        player?.release()
        player = null
        clearCachedEffects()
        super.onDestroy()
    }
}