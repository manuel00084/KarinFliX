package com.karin.streamtv.player

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import android.graphics.PixelFormat
import android.view.SurfaceHolder
import android.view.SurfaceView

import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.karin.streamtv.R
import com.karin.streamtv.util.AutoPlayManager
import com.karin.streamtv.util.DeviceUtils
import com.karin.streamtv.util.EpisodeProgress
import com.karin.streamtv.util.GamepadHelper
import com.karin.streamtv.util.onActionKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class ExoPlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var processor: Media3SixtyFpsProcessor? = null
    private var trackSelector: DefaultTrackSelector? = null
    private lateinit var playerContainer: FrameLayout
    private lateinit var loadingContainer: View
    private lateinit var loadingText: TextView
    private lateinit var topBar: View
    private lateinit var tvVideoTitle: TextView
    private lateinit var tvEpisodeInfo: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var fpsBadge: TextView
    private lateinit var tvQueueBadge: TextView
    private lateinit var playStateOverlay: TextView
    private lateinit var controllerPanel: View
    private lateinit var centerControls: View
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnRewind: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnPrev: TextView
    private lateinit var btnNext: TextView
    private lateinit var tvPosition: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvRemaining: TextView
    private lateinit var gestureIndicator: View
    private lateinit var tvGestureIcon: TextView
    private lateinit var tvGestureValue: TextView
    private lateinit var btnQuality: TextView
    private lateinit var btnSpeed: TextView
    private lateinit var btnMore: TextView
    private lateinit var btnServer: TextView
    private lateinit var btnFullscreen: ImageButton
    private lateinit var btnLock: ImageButton
    private lateinit var btnUnlock: ImageButton
    private lateinit var btnVolume: ImageButton
    private lateinit var btnAudioPreset: TextView
    private lateinit var btnInterp: TextView
    private lateinit var btnVideoProfile: TextView
    private lateinit var btnUpscaler: TextView
    private lateinit var btnInfo: TextView
    private var seekDragging = false
    private var selectedHeight = -1
    private var gestureStartY = 0f
    private var startBrightness = 0.5f
    private var startVolume = 1.0f
    private var gestureSide = 0
    private var gestureDetector: GestureDetector? = null
    private val controllerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var audioRefreshCounter = 0
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            audioRefreshCounter++
            if (audioRefreshCounter >= 4) {
                audioRefreshCounter = 0
                com.karin.streamtv.player.dsp.AudioEnhanceConfig.refreshPlaybackVolume()
            }
            controllerHandler.postDelayed(this, 500)
        }
    }
    private val hideController = Runnable {
        topBar.visibility = View.GONE
        controllerPanel.visibility = View.GONE
        centerControls.visibility = View.GONE
    }
    private var animeId: String = ""
    private var episodeNumber: Int = 0
    private var videoTitle: String = ""
    private var serverName: String = ""
    private var lastCpuTimeMs = 0L
    private var lastCpuTicks = 0L
    private var currentEpisodeUrl: String = ""
    private var autoPlayTriggered: Boolean = false
    private var useEnhancedMode = false
    private var referer: String = ""
    private var contentKind: com.karin.streamtv.player.dsp.AudioEnhanceConfig.ContentType =
        com.karin.streamtv.player.dsp.AudioEnhanceConfig.ContentType.NEUTRAL
    private var fallbackTriggered = false
    private var isPlainFallback = false
    private var playlist: List<com.karin.streamtv.model.PlaylistItem> = emptyList()
    private var playlistIndex: Int = 0
    private var currentVideoUrl: String = ""
    private var currentMegaResolved: com.karin.streamtv.scraper.ServerDirectResolver.ResolvedVideo? = null
    private var pendingResumeMs = -1L
    private var openResumeMs = -1L
    private var openResumeHandled = false
    private var allServerUrls: Array<String> = emptyArray()
    private var allServerNames: Array<String> = emptyArray()
    private var currentServerIndex: Int = 0
    private var serverFailoverTriggered = false
    private val fallbackHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Auto-reproducir al abrir: lo gobierna el toggle "PlayNow" de Ajustes. */
    private fun autoPlayEnabled(): Boolean =
        com.karin.streamtv.util.AppPreferences.isPlayNowEnabled()

    private var retryCount = 0
    private var isNetworkBack = true
    private var wasInterrupted = false
    private var wasPlayingBeforePause = true
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val rebufferWatchdog = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.playbackState == Player.STATE_BUFFERING && isNetworkBack) {
                Log.w(TAG, "Stalled in buffering > 15s - forcing recovery")
                forceReconnect("buffering-watchdog")
            }
        }
    }

    private lateinit var glSurface: android.opengl.GLSurfaceView

    private val irPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->        if (uri == null) return@registerForActivityResult
        try {
            val name = com.karin.streamtv.player.dsp.WavIr.displayName(contentResolver, uri)
            val bytes = contentResolver.openInputStream(uri)?.readBytes()
            if (bytes != null && com.karin.streamtv.player.dsp.AudioEnhanceConfig.setUserIr(name, bytes)) {
                com.karin.streamtv.player.dsp.AudioEnhanceConfig.setIrPreset(
                    com.karin.streamtv.player.dsp.AudioEnhanceConfig.IrPreset.USER
                )
                android.widget.Toast.makeText(this, "IR cargado: $name", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(this, "WAV no soportado (PCM 16/24/32 o float 32)", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (t: Throwable) {
            android.widget.Toast.makeText(this, "Error al leer el archivo", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (DeviceUtils.isTvDevice(this)) {
            startActivity(
                android.content.Intent(this, com.karin.streamtv.ui.tv.TvExoPlayerActivity::class.java)
                    .putExtras(intent)
            )
            finish()
            return
        }

        setContentView(R.layout.activity_exo_player)

        playerContainer = findViewById(R.id.player_container)
        loadingContainer = findViewById(R.id.loading_container)
        loadingText = findViewById(R.id.tv_loading)
        topBar = findViewById(R.id.top_bar)
        tvVideoTitle = findViewById(R.id.tv_video_title)
        tvEpisodeInfo = findViewById(R.id.tv_episode_info)
        btnBack = findViewById(R.id.btn_back)
        fpsBadge = findViewById(R.id.tv_fps_badge)
        tvQueueBadge = findViewById(R.id.tv_queue_badge)
        playStateOverlay = findViewById(R.id.tv_play_state)
        controllerPanel = findViewById(R.id.controller_panel)
        centerControls = findViewById(R.id.center_controls)
        seekBar = findViewById(R.id.seek_bar)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnRewind = findViewById(R.id.btn_rewind)
        btnForward = findViewById(R.id.btn_forward)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)
        tvPosition = findViewById(R.id.tv_position)
        tvDuration = findViewById(R.id.tv_duration)
        tvRemaining = findViewById(R.id.tv_remaining)
        gestureIndicator = findViewById(R.id.gesture_indicator)
        tvGestureIcon = findViewById(R.id.tv_gesture_icon)
        tvGestureValue = findViewById(R.id.tv_gesture_value)
        btnQuality = findViewById(R.id.btn_quality)
        btnVolume = findViewById(R.id.btn_volume)
        btnAudioPreset = findViewById(R.id.btn_audio_preset)
        btnSpeed = findViewById(R.id.btn_speed)
        btnMore = findViewById(R.id.btn_more)
        btnServer = findViewById(R.id.btn_server)
        btnFullscreen = findViewById(R.id.btn_fullscreen)
        btnLock = findViewById(R.id.btn_lock)
        btnUnlock = findViewById(R.id.btn_unlock)
        btnInterp = findViewById(R.id.btn_interp)
        btnVideoProfile = findViewById(R.id.btn_video_profile)
        btnUpscaler = findViewById(R.id.btn_upscaler)
        btnInfo = findViewById(R.id.btn_info)

        trackSelector = TrackSelectorFactory.create(this)

        // "Reproductor de video del sistema": si está OFF y abrimos un video externo
        // (ACTION_VIEW video/*), delegamos al reproductor por defecto del dispositivo.
        if (intent.action == Intent.ACTION_VIEW &&
            !com.karin.streamtv.util.AppPreferences.isVideoPlayerModeEnabled()
        ) {
            val data = intent.data
            if (data != null) {
                val shareable = com.karin.streamtv.util.ShareFileUri.shareableUri(this, data)
                val type = contentResolver.getType(shareable) ?: "video/*"
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndTypeAndNormalize(shareable, type))
                } catch (_: Exception) {
                    // Si no hay otra app que atienda, ignoramos y salimos silenciosamente.
                }
            }
            finish()
            return
        }

        val externalUri: Uri? = if (intent.action == Intent.ACTION_VIEW) intent.data else null
        val videoUrl = intent.getStringExtra("video_url") ?: externalUri?.toString()
        val embedUrl = intent.getStringExtra("embed_url")
        serverName = intent.getStringExtra("server_name") ?: ""
        val episodeUrl = intent.getStringExtra("episode_url") ?: ""
        val epNum = intent.getIntExtra("episode_number", 0)

        playlist = com.karin.streamtv.util.PlaylistQueue.fromJson(intent.getStringExtra("playlist_json"))
        playlistIndex = intent.getIntExtra("playlist_index", 0)

        currentEpisodeUrl = episodeUrl
        if (episodeUrl.isNotBlank()) {
            animeId = EpisodeProgress.generateAnimeId(episodeUrl)
            episodeNumber = epNum
            // Auto-resume: recordar el minuto donde se dejó el video (dato ya guardado).
            if (animeId.isNotBlank() && episodeNumber > 0) {
                openResumeMs = EpisodeProgress.getLastPosition(animeId, episodeNumber)
            }
        }

        allServerUrls = intent.getStringArrayExtra("all_server_urls") ?: emptyArray()
        allServerNames = intent.getStringArrayExtra("all_server_names") ?: emptyArray()
        currentServerIndex = intent.getIntExtra("current_server_index", 0)

        videoTitle = intent.getStringExtra("video_title") ?: ""
        tvVideoTitle.text = videoTitle
        if (episodeNumber > 0) {
            tvEpisodeInfo.text = "Ep. $episodeNumber"
            tvEpisodeInfo.visibility = View.VISIBLE
        }

        contentKind = intent.getStringExtra("content_type")?.let { name ->
            com.karin.streamtv.player.dsp.AudioEnhanceConfig.ContentType.entries.firstOrNull { it.name == name }
        } ?: com.karin.streamtv.player.dsp.AudioEnhanceConfig.detectContentType(
            intent.getStringExtra("site_name") ?: "",
            videoUrl ?: embedUrl
        )
        com.karin.streamtv.player.dsp.AudioEnhanceConfig.setContentType(contentKind)

        referer = intent.getStringExtra("referer")
            ?: embedUrl
            ?: ""
        if (referer.startsWith("http://")) referer = "https://" + referer.substringAfter("http://")

        useEnhancedMode = com.karin.streamtv.util.DeviceProfile.get(this).let { profile ->
            // En gama baja el pipeline GL de mejora/interpolación es muy pesado:
            // la APTA se adapta apagándolo automáticamente (auto-adaptación),
            // salvo que el usuario lo fuerza explícitamente.
            if (!profile.supportsInterpolation && !profile.supportsGlEnhance) return@let false
            VideoEnhanceConfig.isEnabled() || VideoEnhanceConfig.isInterpolationEnabled() || VideoEnhanceConfig.isGlQualityMode()
        }

        val dbgExtra = intent.getIntExtra("debug_mode", -1)
        if (dbgExtra >= 0) VideoEnhanceConfig.setDebugMode(dbgExtra)

        val megaKeyB64 = intent.getStringExtra("mega_key")
        if (videoUrl != null && videoUrl.isNotBlank() && megaKeyB64 != null) {
            hideLoading()
            val resolved = com.karin.streamtv.scraper.ServerDirectResolver.ResolvedVideo(
                url = videoUrl,
                referer = referer,
                extraHeaders = mapOf("Referer" to if (referer.startsWith("http")) referer else "https://$referer"),
                needsMegaDecrypt = true,
                megaKey = android.util.Base64.decode(megaKeyB64, android.util.Base64.NO_WRAP),
                megaCtrStart = intent.getLongExtra("mega_ctr", 0L)
            )
            playVideoMega(resolved)
        } else if (videoUrl != null && videoUrl.isNotBlank()) {
            hideLoading()
            playVideo(videoUrl)
        } else if (embedUrl != null && embedUrl.isNotBlank()) {
            showLoading("Extrayendo video...")
            extractAndPlay(embedUrl, serverName)
        } else {
            Toast.makeText(this, "URL de video no disponible", Toast.LENGTH_SHORT).show()
            finish()
        }

        btnBack.setOnClickListener { finish() }
        btnBack.onActionKey { finish() }
        setupGestureControls()
        btnPlayPause.setOnClickListener { togglePlayPause() }
        btnRewind.setOnClickListener { seekRelative(-10000) }
        btnForward.setOnClickListener { seekRelative(10000) }
        if (playlist.isEmpty()) {
            btnPrev.visibility = View.GONE
            btnNext.visibility = View.GONE
        } else {
            btnPrev.setOnClickListener { skipToPlaylist(-1) }
            btnNext.setOnClickListener { skipToPlaylist(1) }
        }
        updateAudioPresetButton()
        btnSpeed.text = speedLabel(com.karin.streamtv.util.AppPreferences.getPlayerSpeed())
        btnSpeed.setOnClickListener { showSpeedDialog() }
        btnMore.setOnClickListener { showMoreDialog() }
        if (allServerUrls.size > 1) {
            btnServer.visibility = View.VISIBLE
            btnServer.text = "🖥 ${allServerNames.getOrElse(currentServerIndex) { "Servidor" }}"
            btnServer.setOnClickListener { showServerPickerDialog() }
        }
        btnFullscreen.setOnClickListener { toggleFullscreen() }
        btnLock.setOnClickListener { lockControls() }
        btnUnlock.setOnClickListener { unlockControls() }
        btnQuality.setOnClickListener { showQualityDialog() }
        btnVolume.setOnClickListener { showVolumeDialog() }
        btnAudioPreset.setOnClickListener { showDspDialog() }
        btnVideoProfile.setOnClickListener { showVideoProfileDialog() }
        updateVideoProfileButton()
        btnInterp.setOnClickListener { showInterpolationDialog() }
        updateInterpButton()
        btnUpscaler.setOnClickListener { showUpscalerDialog() }
        btnInfo.setOnClickListener { showInfoDialog() }
        updateUpscalerButton()
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) { seekDragging = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekDragging = false
                player?.seekTo(seekBar?.progress?.toLong() ?: 0L)
            }
        })
        controllerHandler.post(progressRunnable)
        registerNetworkMonitor()
    }

    private fun registerNetworkMonitor() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    isNetworkBack = true
                    if (wasInterrupted) {
                        wasInterrupted = false
                        Log.i(TAG, "Network available - attempting resume")
                        forceReconnect("network-available")
                    }
                }
            }
            override fun onLost(network: Network) {
                runOnUiThread {
                    isNetworkBack = false
                    wasInterrupted = true
                    Log.w(TAG, "Network lost - pausing playback")
                    player?.pause()
                    showLoading("Sin conexión - esperando red...")
                    reconnectHandler.removeCallbacks(rebufferWatchdog)
                }
            }
        }
        networkCallback = callback
        cm.registerDefaultNetworkCallback(callback)
    }

    private fun setupGestureControls() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                if (isLocked) return false
                gestureStartY = e.y
                val wl = window.attributes
                startBrightness = if (wl.screenBrightness >= 0) wl.screenBrightness else 0.5f
                startVolume = player?.volume ?: com.karin.streamtv.util.AppPreferences.getPlayerVolume()
                gestureSide = if (e.x < playerContainer.width / 2f) 0 else 1
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val h = playerContainer.height.coerceAtLeast(1)
                val ratio = (gestureStartY - e2.y) / h
                if (gestureSide == 0) {
                    val v = (startBrightness + ratio).coerceIn(0.01f, 1f)
                    setScreenBrightness(v)
                    showGestureIndicator("\u2600\ufe0f Brillo", "${(v * 100).toInt()}%")
                } else {
                    val target = (startVolume + ratio).coerceIn(0.0f, 3.0f)
                    player?.volume = target
                    com.karin.streamtv.player.dsp.AudioEnhanceConfig.setAppVolume(target)
                    com.karin.streamtv.util.AppPreferences.setPlayerVolume(target)
                    showGestureIndicator("\ud83d\udd0a Volumen", "${(target * 100).toInt()}%")
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val x = e.x
                val w = playerContainer.width.coerceAtLeast(1)
                if (x < w / 3f) {
                    seekRelative(-10000)
                } else if (x > w * 2f / 3f) {
                    seekRelative(10000)
                } else {
                    togglePlayPause()
                }
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                showController()
                return true
            }
        })
        playerContainer.setOnTouchListener { _, event -> gestureDetector?.onTouchEvent(event) ?: false }
    }

    private val hideGestureIndicator = Runnable { gestureIndicator.visibility = View.GONE }

    private fun showGestureIndicator(icon: String, value: String) {
        tvGestureIcon.text = icon
        tvGestureValue.text = value
        gestureIndicator.visibility = View.VISIBLE
        gestureIndicator.removeCallbacks(hideGestureIndicator)
        gestureIndicator.postDelayed(hideGestureIndicator, 900)
    }

    private fun setScreenBrightness(v: Float) {
        try {
            val wl = window.attributes
            wl.screenBrightness = v
            window.attributes = wl
        } catch (_: Exception) {}
    }

    private var isFullscreen = false

    private var isLocked = false

    private fun lockControls() {
        isLocked = true
        topBar.visibility = View.GONE
        controllerPanel.visibility = View.GONE
        centerControls.visibility = View.GONE
        btnUnlock.visibility = View.VISIBLE
        controllerHandler.removeCallbacks(hideController)
    }

    private fun unlockControls() {
        isLocked = false
        btnUnlock.visibility = View.GONE
        showController()
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        applyFullscreenState()
        showController()
    }

    private fun applyFullscreenState() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (isFullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            btnFullscreen.setImageResource(R.drawable.ic_fullscreen_exit)
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            btnFullscreen.setImageResource(R.drawable.ic_fullscreen_enter)
        }
    }

    private fun showController() {
        topBar.visibility = View.VISIBLE
        controllerPanel.visibility = View.VISIBLE
        centerControls.visibility = View.VISIBLE
        updateProgress()
        controllerHandler.removeCallbacks(hideController)
        controllerHandler.postDelayed(hideController, 4000)
    }

    private fun togglePlayPause() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        playStateOverlay.text = if (p.playWhenReady) "\u25b6" else "\u275a\u275a"
        playStateOverlay.textSize = 30f
        playStateOverlay.visibility = View.VISIBLE
        playStateOverlay.removeCallbacks(hidePlayState)
        playStateOverlay.postDelayed(hidePlayState, 800)
        updateProgress()
        showController()
    }

    private fun updateProgress() {
        val p = player ?: return
        if (controllerPanel.visibility != View.VISIBLE) return
        val dur = p.duration.coerceAtLeast(0)
        val pos = p.currentPosition.coerceAtLeast(0)
        if (dur > 0) {
            seekBar.max = dur.toInt().coerceAtLeast(1)
            if (!seekDragging) seekBar.progress = pos.toInt()
            seekBar.secondaryProgress = p.bufferedPosition.toInt().coerceIn(0, seekBar.max)
            tvPosition.text = formatTime(pos)
            tvDuration.text = formatTime(dur)
            tvRemaining.text = "-" + formatTime((dur - pos).coerceAtLeast(0))
        }
        btnPlayPause.setImageResource(
            if (p.playWhenReady && p.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    private fun seekRelative(ms: Long) {
        val p = player ?: return
        val newPos = (p.currentPosition + ms).coerceIn(0, p.duration.coerceAtLeast(0))
        p.seekTo(newPos)
        showController()
    }

    private fun showQualityDialog() {
        val p = player ?: return
        val heights = LinkedHashSet<Int>()
        p.currentTracks.groups.forEach { g ->
            if (g.type == C.TRACK_TYPE_VIDEO) {
                for (i in 0 until g.length) {
                    val f = g.getTrackFormat(i)
                    if (f.height > 0) heights.add(f.height)
                }
            }
        }
        val sorted = heights.sortedDescending()
        val items = ArrayList<String>()
        items.add("Auto")
        sorted.forEach { items.add("${it}p") }
        val selectedIdx = if (selectedHeight > 0) sorted.indexOf(selectedHeight) + 1 else 0
        AlertDialog.Builder(this)
            .setTitle("Calidad")
            .setSingleChoiceItems(items.toTypedArray(), selectedIdx) { _, which ->
                if (which == 0) {
                    selectedHeight = -1
                    trackSelector?.setParameters(
                        trackSelector!!.buildUponParameters()
                            .setMaxVideoSize(C.LENGTH_UNSET, C.LENGTH_UNSET)
                    )
                } else {
                    val h = sorted[which - 1]
                    selectedHeight = h
                    trackSelector?.setParameters(
                        trackSelector!!.buildUponParameters()
                            .setMaxVideoSize(C.LENGTH_UNSET, h)
                    )
                }
                btnQuality.text = items[which]
                showController()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private val hidePlayState = Runnable { playStateOverlay.visibility = View.GONE }

    private fun showVolumeDialog() {
        val p = player ?: return
        val seek = android.widget.SeekBar(this)
        seek.max = 300
        seek.progress = (p.volume * 100).toInt().coerceIn(10, 300)
        val profilesLink = android.widget.TextView(this).apply {
            text = "Perfiles de audio (DSP)"
            textSize = 14f
            setTextColor(0xFF4FC3F7.toInt())
            setPadding(0, 20, 0, 0)
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(seek)
            addView(profilesLink)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Volumen (100% = normal, hasta 300% para videos bajos)")
            .setView(container)
            .setPositiveButton("Aceptar", null)
            .setOnDismissListener {
                p.volume = seek.progress / 100f
                com.karin.streamtv.player.dsp.AudioEnhanceConfig.setAppVolume(p.volume)
                com.karin.streamtv.util.AppPreferences.setPlayerVolume(p.volume)
            }
            .create()
        profilesLink.setOnClickListener {
            dlg.dismiss()
            showDspDialog()
        }
        dlg.show()
    }

    private fun showDspDialog() {
        com.karin.streamtv.player.dsp.AudioDspUi.showPresetDialog(this,
            onAdvanced = {
                com.karin.streamtv.player.dsp.AudioDspUi.showAdvanced(this) {
                    irPicker.launch(arrayOf("audio/*", "application/octet-stream"))
                }
            },
            onChanged = {
                updateAudioPresetButton()
                showController()
            }
        )
    }

    private fun showAudioPresetDialog() {
        val presets = com.karin.streamtv.player.dsp.AudioEnhanceConfig.Preset.entries
        val current = com.karin.streamtv.player.dsp.AudioEnhanceConfig.preset()
        val labels = presets.map { it.label }.toTypedArray()
        val selectedIdx = presets.indexOf(current).coerceAtLeast(0)

        android.app.AlertDialog.Builder(this)
            .setTitle("Perfil de audio")
            .setSingleChoiceItems(labels, selectedIdx) { _, which ->
                val preset = presets[which]
                com.karin.streamtv.player.dsp.AudioEnhanceConfig.applyParams(
                    com.karin.streamtv.player.dsp.AudioEnhanceConfig.Params().withPreset(preset)
                )
                updateAudioPresetButton()
                showController()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun updateAudioPresetButton() {
        val preset = com.karin.streamtv.player.dsp.AudioEnhanceConfig.preset()
        val enabled = com.karin.streamtv.player.dsp.AudioEnhanceConfig.isEnabled()
        val auto = com.karin.streamtv.player.dsp.AudioEnhanceConfig.isAutoDevice()
        btnAudioPreset.text = when {
            !enabled || preset == com.karin.streamtv.player.dsp.AudioEnhanceConfig.Preset.OFF ->
                "Perfil: OFF"
            auto -> {
                val contentLabel = com.karin.streamtv.player.dsp.AudioEnhanceConfig.getContentType()
                "Perfil: Auto${if (contentLabel != com.karin.streamtv.player.dsp.AudioEnhanceConfig.ContentType.NEUTRAL) " · ${contentLabel.label}" else ""}"
            }
            else -> "Perfil: ${preset.label}"
        }
    }

    private fun showMoreDialog() {
        val options = listOf(
            "🎛 Perfil de audio (DSP)",
            "✨ Enhancement"
        )
        AlertDialog.Builder(this)
            .setTitle("Opciones avanzadas")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showDspDialog()
                    1 -> showVideoProfileDialog()
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showServerPickerDialog() {
        if (allServerUrls.size <= 1) return
        val names = allServerNames.takeIf { it.isEmpty().not() } ?: allServerUrls
        val labels = allServerUrls.mapIndexed { i, _ ->
            when {
                i < names.size && names[i].isNotBlank() -> names[i]
                else -> "Servidor ${i + 1}"
            }
        }.toTypedArray()
        val currentIdx = currentServerIndex.coerceIn(0, allServerUrls.size - 1)
        AlertDialog.Builder(this)
            .setTitle("Seleccionar servidor")
            .setSingleChoiceItems(labels, currentIdx) { _, which ->
                if (which != currentServerIndex) {
                    switchToServer(which)
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Cambiar manualmente a otro servidor relanzando la cadena de extracción. */
    private fun switchToServer(index: Int) {
        val name = allServerNames.getOrElse(index) { "Servidor ${index + 1}" }
        Log.w(TAG, "Manual server switch -> $name: ${allServerUrls[index].takeLast(60)}")
        Toast.makeText(this, "Cambiando a: $name", Toast.LENGTH_SHORT).show()
        serverFailoverTriggered = true
        val intent = Intent(this, com.karin.streamtv.ui.EmbedWebViewActivity::class.java).apply {
            putExtra("embed_url", allServerUrls[index])
            putExtra("server_name", name)
            putExtra("video_title", videoTitle)
            putExtra("episode_url", currentEpisodeUrl)
            putExtra("episode_number", episodeNumber)
            putExtra("site_name", intent.getStringExtra("site_name") ?: "")
            putExtra("all_server_urls", allServerUrls)
            putExtra("all_server_names", allServerNames)
            putExtra("current_server_index", index)
            if (playlist.isNotEmpty()) {
                putExtra("playlist_json", com.karin.streamtv.util.PlaylistQueue.toJson(playlist))
                putExtra("playlist_index", playlistIndex)
            }
        }
        startActivity(intent)
        finish()
    }

    private fun showSpeedDialog() {
        val p = player ?: return
        val speeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val labels = speeds.map { speedLabel(it) }
        val current = com.karin.streamtv.util.AppPreferences.getPlayerSpeed()
        val selectedIdx = speeds.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }.coerceAtLeast(3)
        AlertDialog.Builder(this)
            .setTitle("Velocidad de reproducción")
            .setSingleChoiceItems(labels.toTypedArray(), selectedIdx) { _, which ->
                val s = speeds[which]
                p.setPlaybackParameters(androidx.media3.common.PlaybackParameters(s, s))
                com.karin.streamtv.util.AppPreferences.setPlayerSpeed(s)
                btnSpeed.text = speedLabel(s)
                showController()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun speedLabel(s: Float): String {
        return when {
            s == 1.0f -> "1×"
            s % 1.0f == 0f -> "${s.toInt()}×"
            else -> "$s×"
        }
    }

    private fun applySavedSpeed() {
        val p = player ?: return
        val s = com.karin.streamtv.util.AppPreferences.getPlayerSpeed()
        p.setPlaybackParameters(androidx.media3.common.PlaybackParameters(s, s))
        btnSpeed.text = speedLabel(s)
    }

    private fun showVideoProfileDialog() {
        VideoEnhanceUi.showAdvanced(this) {
            updateVideoProfileButton()
            showController()
        }
    }

    private fun updateVideoProfileButton() {
        btnVideoProfile.text = "Enhancement: ON"
    }

    private fun skipToPlaylist(delta: Int) {
        if (playlist.isEmpty()) {
            Toast.makeText(this, "No hay lista de reproducción activa", Toast.LENGTH_SHORT).show()
            return
        }
        val target = playlistIndex + delta
        if (target < 0 || target >= playlist.size) {
            Toast.makeText(this, if (delta > 0) "Fin de la lista" else "Inicio de la lista", Toast.LENGTH_SHORT).show()
            return
        }
        autoPlayTriggered = false
        val siteName = intent.getStringExtra("site_name") ?: ""
        startActivity(com.karin.streamtv.util.PlaylistQueue.buildIntent(this, playlist, target, siteName))
        finish()
    }

    private fun showInterpolationDialog() {
        val labels = arrayOf("Apagado") + VideoEnhanceConfig.InterpolationMode.entries.map { it.label }
        val selectedIdx = if (!VideoEnhanceConfig.isInterpolationEnabled()) 0
                          else VideoEnhanceConfig.interpolationMode().ordinal + 1
        AlertDialog.Builder(this)
            .setTitle("MotionX2 60p")
            .setSingleChoiceItems(labels, selectedIdx) { _, which ->
if (which == 0) {
                    VideoEnhanceConfig.setInterpolationEnabled(false)
                } else {
                    val mode = VideoEnhanceConfig.InterpolationMode.entries[which - 1]
                    VideoEnhanceConfig.setInterpolationMode(mode)
                    VideoEnhanceConfig.setInterpolationEnabled(true)
                }
                updateInterpButton()
                if (VideoEnhanceConfig.isInterpolationEnabled() && !useEnhancedMode) {
                    useEnhancedMode = true
                    restartWithEnhanced()
                }
                showController()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun updateInterpButton() {
        val enabled = VideoEnhanceConfig.isInterpolationEnabled()
        val mode = VideoEnhanceConfig.interpolationMode()
        btnInterp.text = if (!enabled) "MotionX2 60p: OFF"
                         else "MotionX2 60p: ${mode.label}"
    }

    private fun restartWithEnhanced() {
        val p = player
        pendingResumeMs = if (p != null && p.duration > 0) p.currentPosition else -1
        p?.release()
        player = null
        processor?.release()
        processor = null
        playerContainer.removeAllViews()
        if (currentMegaResolved != null) {
            playVideoMega(currentMegaResolved!!)
        } else if (currentVideoUrl.isNotBlank()) {
            playVideo(currentVideoUrl)
        } else {
            pendingResumeMs = -1
            Toast.makeText(this, "MotionX2 60p se aplicará al próximo video", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyPendingResume(exoPlayer: androidx.media3.exoplayer.ExoPlayer) {
        if (pendingResumeMs > 0) {
            exoPlayer.seekTo(pendingResumeMs)
            pendingResumeMs = -1
        }
    }

    /** Auto-resume: ofrece continuar desde el minuto guardado (una sola vez por apertura). */
    private fun maybeOfferResume(exoPlayer: androidx.media3.exoplayer.ExoPlayer) {
        if (openResumeHandled || openResumeMs <= 0) return
        openResumeHandled = true
        val duration = exoPlayer.duration
        val resumePos = openResumeMs
        openResumeMs = -1
        // Sin duración conocida o ya visto casi completo: no interrumpir.
        if (duration <= 0 || resumePos <= 0 || resumePos >= duration * 0.9) return
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Reanudar reproducción")
            .setMessage("Continuar desde ${formatTime(resumePos)}?")
            .setPositiveButton("Reanudar") { _, _ -> exoPlayer.seekTo(resumePos) }
            .setNegativeButton("Desde el inicio") { _, _ -> exoPlayer.seekTo(0) }
            .show()
    }

    private fun showUpscalerDialog() {
        val modes = VideoEnhanceConfig.mainUpscalers
        val labels = modes.map { it.label }.toTypedArray()
        val current = VideoEnhanceConfig.getUpscalerMode()
        val selectedIdx = modes.indexOfFirst { it == current }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Escalado de Video")
            .setSingleChoiceItems(labels, selectedIdx) { _, which ->
                VideoEnhanceConfig.setUpscalerMode(modes[which])
                updateUpscalerButton()
                showController()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun updateUpscalerButton() {
        val mode = VideoEnhanceConfig.getUpscalerMode()
        btnUpscaler.text = "Escala: ${mode.label}"
    }

    private fun showInfoDialog() {
        val p = player
        val r = processor?.renderer
        val cfg = com.karin.streamtv.player.VideoEnhanceConfig

        // Datos de ExoPlayer (formato, codec, resolución, bitrate)
        var videoFormat: androidx.media3.common.Format? = null
        var audioFormat: androidx.media3.common.Format? = null
        try {
            p?.currentTracks?.groups?.forEach { group ->
                for (i in 0 until group.length) {
                    if (!group.isTrackSupported(i)) continue
                    val f = group.getTrackFormat(i)
                    if (f.sampleMimeType?.startsWith("video/") == true && videoFormat == null) videoFormat = f
                    if (f.sampleMimeType?.startsWith("audio/") == true && audioFormat == null) audioFormat = f
                }
            }
        } catch (_: Throwable) {}

        val vf = videoFormat
        val codecName = try {
            p?.videoFormat?.codecs ?: vf?.codecs ?: "—"
        } catch (_: Throwable) { "—" }
        val mime = vf?.sampleMimeType ?: "—"
        val isLocal = isLocalUrl(currentVideoUrl)
        val serverLabel = if (isLocal) "Video local (dispositivo)" else serverNameOrUrl()
        val enhanced = useEnhancedMode && processor != null
        val interpOn = r?.interpolationActive == true
        val upscaler = cfg.getUpscalerMode()

        val sb = StringBuilder()
        sb.appendLine("🎬 ${if (videoTitle.isNotBlank()) videoTitle else "(sin título)"}")
        if (episodeNumber > 0) sb.appendLine("   Episodio $episodeNumber")
        sb.appendLine()

        // Fuente
        sb.appendLine("🌐 Fuente: $serverLabel")
        if (!isLocal && serverName.isNotBlank()) sb.appendLine("   Servidor: $serverName")
        sb.appendLine()

        // Video
        val inW = r?.videoInputWidth() ?: 0
        val inH = r?.videoInputHeight() ?: 0
        val outW = viewWidth()
        val outH = viewHeight()
        sb.appendLine("📹 VIDEO")
        sb.appendLine("   Formato: ${formatShort(mime)}")
        sb.appendLine("   Códec: ${codecName.ifBlank { "—" }}")
        sb.appendLine("   Resolución entrada: ${if (inW > 0) "${inW}×${inH}" else vf?.let { "${it.width}×${it.height}" } ?: "—"}")
        sb.appendLine("   Resolución salida: ${if (outW > 0) "${outW}×${outH}" else "—"}")
        val bitrate = vf?.bitrate ?: -1
        sb.appendLine("   Bitrate video: ${if (bitrate > 0) formatBytes(bitrate) + "/s" else "—"}")
        sb.appendLine()

        // Audio
        sb.appendLine("🔊 AUDIO")
        val aBitrate = audioFormat?.bitrate ?: -1
        sb.appendLine("   Códec: ${audioFormat?.codecs?.ifBlank { "—" } ?: "—"}")
        sb.appendLine("   Bitrate: ${if (aBitrate > 0) formatBytes(aBitrate) + "/s" else "—"}")
        sb.appendLine()

        // FPS
        sb.appendLine("⏱ FPS")
        sb.appendLine("   Entrada (fuente): ${"%.1f".format(r?.sourceFps ?: 0f)} fps")
        sb.appendLine("   Salida (render): ${"%.1f".format(r?.outputFps ?: 0f)} fps")
        sb.appendLine("   Tiempo frame: ${"%.2f".format(r?.frameMs ?: 0f)} ms")
        sb.appendLine("   Interpolación: ${if (interpOn) "ACTIVA" else "inactiva"}")
        sb.appendLine("   Frames caídos: ${r?.droppedFrames ?: 0}")
        sb.appendLine()

        // Recursos
        val mem = runtimeMemInfo()
        sb.appendLine("💾 RECURSOS")
        sb.appendLine("   RAM (app): ${mem.first} MB  (heap ${mem.second}/${mem.third} MB)")
        sb.appendLine("   CPU: ${cpuUsageString()}")
        sb.appendLine("   VRAM: ${vramInfo(r)}")
        sb.appendLine()

        // Pipeline
        sb.appendLine("⚙️ PIPELINE")
        sb.appendLine("   Motor: ${if (enhanced) "Media3 + GL 60fps" else "ExoPlayer estándar"}")
        sb.appendLine("   Upscaler: ${if (enhanced) upscaler.label else "—"}")
        sb.appendLine("   Calidad: ${r?.qualityLabel ?: cfg.qualityLabel()}")
        sb.appendLine("   FrameMs target: 16.6 ms")

        android.app.AlertDialog.Builder(this)
            .setTitle("Análisis de reproducción")
            .setMessage(sb.toString())
            .setPositiveButton("Actualizar", { _, _ -> showInfoDialog() })
            .setNegativeButton("Cerrar", null)
            .setOnDismissListener(null)
            .show()
    }

    private fun formatShort(mime: String): String = when {
        mime.contains("av1") -> "AV1"
        mime.contains("vp9") -> "VP9"
        mime.contains("vp8") -> "VP8"
        mime.contains("hevc") || mime.contains("h265") -> "HEVC (H.265)"
        mime.contains("avc") || mime.contains("h264") -> "H.264 (AVC)"
        mime.contains("mp4") -> "MP4"
        mime.contains("webm") -> "WebM"
        mime.contains("mpeg") -> "MPEG"
        mime.contains("mp3") -> "MP3"
        mime.contains("aac") -> "AAC"
        mime.contains("opus") -> "Opus"
        mime.contains("flac") -> "FLAC"
        mime.contains("ac3") -> "AC-3"
        mime.contains("eac3") -> "E-AC-3"
        mime.contains("dts") -> "DTS"
        else -> mime.ifBlank { "—" }
    }

    private fun formatBytes(bits: Int): String {
        val b = bits / 8L
        return when {
            b >= 1_000_000 -> "%.1f MB".format(b / 1_000_000f)
            b >= 1_000 -> "%.1f kB".format(b / 1_000f)
            else -> "$b B"
        }
    }

    private fun viewWidth(): Int = processor?.renderer?.viewWidth() ?: 0

    private fun viewHeight(): Int = processor?.renderer?.viewHeight() ?: 0

    private fun serverNameOrUrl(): String {
        val raw = currentVideoUrl
        if (raw.isBlank()) return "—"
        return try {
            android.net.Uri.parse(raw).host?.removePrefix("www.") ?: raw
        } catch (_: Throwable) { raw }
    }

    private fun runtimeMemInfo(): Triple<String, Long, Long> {
        val rt = Runtime.getRuntime()
        val total = rt.totalMemory() / (1024 * 1024)
        val free = rt.freeMemory() / (1024 * 1024)
        val used = total - free
        val max = rt.maxMemory() / (1024 * 1024)
        return Triple("$used", used, max)
    }

    private fun cpuUsageString(): String {
        return try {
            // CPU usada por la app vía /proc/self/stat (user+system ticks)
            val stat = java.io.File("/proc/self/stat").readText()
            val fields = stat.split(" ")
            // fields 14 (utime) y 15 (stime) - índices 13 y 14 en array de 1-based
            val utime = fields.getOrNull(13)?.toLongOrNull() ?: 0L
            val stime = fields.getOrNull(14)?.toLongOrNull() ?: 0L
            val now = System.currentTimeMillis()
            val dtMs = (now - lastCpuTimeMs).coerceAtLeast(1L)
            val dTicks = ((utime + stime) - lastCpuTicks).coerceAtLeast(0L)
            lastCpuTimeMs = now
            lastCpuTicks = utime + stime
            val pct = dTicks * 100f / dtMs
            "%.1f%%".format(pct.coerceAtMost(100f))
        } catch (_: Throwable) { "—" }
    }

    private fun vramInfo(r: com.karin.streamtv.player.Media3SixtyFpsProcessor.InterpolationRenderer?): String {
        if (r == null) return "— (no GL)"
        return "~${r.approxVramMb()} MB"
    }

    private fun applySavedVolume() {
        val p = player ?: return
        val v = com.karin.streamtv.util.AppPreferences.getPlayerVolume()
        p.volume = v
        com.karin.streamtv.player.dsp.AudioEnhanceConfig.setAppVolume(v)
    }

    private fun showLoading(text: String) {
        loadingText.text = text
        loadingContainer.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        loadingContainer.visibility = View.GONE
    }

    private fun extractAndPlay(embedUrl: String, serverName: String) {
        lifecycleScope.launch {
            try {
                val resolved = com.karin.streamtv.scraper.ServerDirectResolver.resolve(embedUrl, referer)
                hideLoading()
                if (resolved != null) {
                    Log.i(TAG, "HTTP-resolved video, launching playback")
                    if (resolved.needsMegaDecrypt) {
                        playVideoMega(resolved)
                    } else {
                        playVideo(resolved.url)
                    }
                    return@launch
                }
                val extractor = VideoExtractorHelper(playerContainer)
                try {
                    val url = withContext(Dispatchers.Main) {
                        extractor.extractSuspend(embedUrl, serverName, referer)
                    }
                    hideLoading()
                    if (!url.isNullOrBlank()) {
                        playVideo(url)
                    } else {
                        if (!tryServerFailover("extracción fallida")) {
                            Toast.makeText(this@ExoPlayerActivity, "No se pudo extraer el video", Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                } finally {
                    extractor.destroy()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Extraction error: ${e.message}", e)
                hideLoading()
                if (!tryServerFailover("extracción fallida: ${e.message}")) {
                    Toast.makeText(this@ExoPlayerActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun playVideoMega(resolved: com.karin.streamtv.scraper.ServerDirectResolver.ResolvedVideo) {
        currentMegaResolved = resolved
        currentVideoUrl = resolved.url
        val key = resolved.megaKey ?: run {
            Toast.makeText(this, "MEGA key no disponible", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val megaFactory = androidx.media3.datasource.DataSource.Factory {
            com.karin.streamtv.player.MegaDecryptingDataSource(
                key,
                resolved.megaCtrStart,
                VideoDataSource.create(this, referer)
            )
        }
        if (useEnhancedMode && !isPlainFallback &&
            contentKind != com.karin.streamtv.player.dsp.AudioEnhanceConfig.ContentType.MUSIC
        ) {
            playWithEnhancedPipeline(resolved.url, megaFactory)
            return
        }
        val loadControl = RamAwareLoadControl.create(this)

        val exoPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(this, CodecSelectorFactory.renderersFactory(this))
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector!!)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(megaFactory)
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(android.os.PowerManager.PARTIAL_WAKE_LOCK)
            .build()
        player = exoPlayer
        applySavedVolume()
        applySavedSpeed()

        val playerView = androidx.media3.ui.PlayerView(this).apply {
            useController = false
            keepScreenOn = true
            player = exoPlayer
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        (playerView.getVideoSurfaceView() as? SurfaceView)?.holder?.setFormat(PixelFormat.RGBA_8888)
        exoPlayer.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
        playerContainer.addView(playerView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        exoPlayer.setMediaItem(MediaItem.fromUri(resolved.url))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = autoPlayEnabled()
        applyPendingResume(exoPlayer)

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        hideLoading()
                        retryCount = 0
                        reconnectHandler.removeCallbacks(rebufferWatchdog)
                        maybeOfferResume(exoPlayer)
                    }
                    Player.STATE_BUFFERING -> {
                        showLoading(if (isNetworkBack) "Cargando..." else "Sin conexión...")
                        reconnectHandler.removeCallbacks(rebufferWatchdog)
                        reconnectHandler.postDelayed(rebufferWatchdog, 15000)
                    }
                    Player.STATE_ENDED -> onVideoEnded()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "MEGA Playback error: ${error.message}")
                handlePlaybackError(error)
            }
        })
    }

    private fun isLocalUrl(url: String): Boolean =
        url.startsWith("content://") || url.startsWith("file://")

    private fun playVideo(url: String) {
        currentVideoUrl = url
        if (useEnhancedMode && !isPlainFallback &&
            contentKind != com.karin.streamtv.player.dsp.AudioEnhanceConfig.ContentType.MUSIC
        ) {
            playWithEnhancedPipeline(
                url,
                if (isLocalUrl(url)) androidx.media3.datasource.DefaultDataSource.Factory(this) else null
            )
        } else {
            playStandard(url)
        }
    }

    private fun playWithEnhancedPipeline(url: String, dataSourceFactory: androidx.media3.datasource.DataSource.Factory? = null) {
        glSurface = android.opengl.GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
        }

        playerContainer.addView(glSurface, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        processor = Media3SixtyFpsProcessor(this, glSurface, referer)
        processor!!.setupGlPipeline()
        processor!!.renderer?.setDebugModeValue(VideoEnhanceConfig.getDebugMode())
        processor!!.onGlFailure = {
            runOnUiThread { triggerFallback(url) }
        }

        val exoPlayer = processor!!.createPlayer(trackSelector, dataSourceFactory)
        player = exoPlayer
        applySavedVolume()
        applySavedSpeed()

        processor!!.connectPlayer(exoPlayer)
        processor!!.play(url)
        applyPendingResume(exoPlayer)
        startFpsPolling()

        fallbackHandler.postDelayed({
            if (player != null && processor?.isPipelineReady() != true && !fallbackTriggered) {
                Log.w(TAG, "GL pipeline not ready, falling back to standard playback")
                triggerFallback(url)
            }
        }, 5000)

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        hideLoading()
                        retryCount = 0
                        reconnectHandler.removeCallbacks(rebufferWatchdog)
                        maybeOfferResume(exoPlayer)
                        Log.i(TAG, "Player STATE_READY - 60fps pipeline active")
                    }
                    Player.STATE_BUFFERING -> {
                        showLoading(if (isNetworkBack) "Cargando..." else "Sin conexión...")
                        reconnectHandler.removeCallbacks(rebufferWatchdog)
                        reconnectHandler.postDelayed(rebufferWatchdog, 15000)
                    }
                    Player.STATE_ENDED -> onVideoEnded()
                }
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                processor?.renderer?.setVideoSize(videoSize.width, videoSize.height)
                Log.i(TAG, "Video size: ${videoSize.width}x${videoSize.height}")
                // Android TV fix: force GL buffer to panel-native resolution
                val (w, h) = TvSurfaceCompat.idealSurfaceSize(this@ExoPlayerActivity, videoSize.width, videoSize.height)
                TvSurfaceCompat.forceGlSurfaceSize(glSurface, w, h)
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback error: ${error.message}")
                handlePlaybackError(error)
            }
        })
    }

    private fun triggerFallback(url: String) {
        if (fallbackTriggered) return
        fallbackTriggered = true
        useEnhancedMode = false
        Log.w(TAG, "Falling back to standard playback")
        fpsBadge.visibility = View.GONE
        processor?.release()
        processor = null
        player?.release()
        player = null
        playerContainer.removeAllViews()
        isPlainFallback = true
        playStandard(url)
    }

    private fun startFpsPolling() {
        val poll = object : Runnable {
            override fun run() {
                val r = processor?.renderer
                if (r != null) {
                     if (r.pipelineReady && r.interpolationActive) {
                        fpsBadge.visibility = View.VISIBLE
                        fpsBadge.text = "Salida ${r.outputFps.toInt()} fps · ${r.frameMs}ms · Drop ${r.droppedFrames}"
                    } else if (r.pipelineReady && com.karin.streamtv.player.VideoEnhanceConfig.isGlQualityMode() && !com.karin.streamtv.player.VideoEnhanceConfig.isEnabled() && !com.karin.streamtv.player.VideoEnhanceConfig.isInterpolationEnabled()) {
                        fpsBadge.visibility = View.VISIBLE
                        fpsBadge.text = "Calidad GL: activa"
                    } else {
                        fpsBadge.visibility = View.GONE
                    }
                    val p = player
                    if (p != null && p.isPlaying && p.playbackState == Player.STATE_READY) {
                        val lastNs = r.lastRenderedFrameNs
                        if (lastNs > 0) {
                            val stallMs = (System.nanoTime() - lastNs) / 1_000_000
                            if (stallMs > 2500) {
                                processor?.resyncSurface()
                                r.markResync()
                            }
                        }
                    }
                    fallbackHandler.postDelayed(this, 1500)
                } else {
                    fpsBadge.visibility = View.GONE
                }
            }
        }
        fallbackHandler.postDelayed(poll, 1500)
    }

    private fun playStandard(url: String) {
        currentVideoUrl = url
        if (useEnhancedMode && !isPlainFallback) {
            playWithEnhancedPipeline(
                url,
                if (isLocalUrl(url)) androidx.media3.datasource.DefaultDataSource.Factory(this) else null
            )
            return
        }
        if (url.startsWith("content://")) {
            try {
                contentResolver.takePersistableUriPermission(
                    android.net.Uri.parse(url),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
        }
        val loadControl = RamAwareLoadControl.create(this)

        val exoPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(this, CodecSelectorFactory.renderersFactory(this))
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector!!)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(
                        if (isLocalUrl(url)) {
                            androidx.media3.datasource.DefaultDataSource.Factory(this)
                        } else {
                            VideoDataSource.factory(this, referer)
                        }
                    )
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(android.os.PowerManager.PARTIAL_WAKE_LOCK)
            .build()
        player = exoPlayer
        applySavedVolume()
        applySavedSpeed()

        val playerView = androidx.media3.ui.PlayerView(this).apply {
            useController = false
            keepScreenOn = true
            player = exoPlayer
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        (playerView.getVideoSurfaceView() as? SurfaceView)?.holder?.setFormat(PixelFormat.RGBA_8888)
        exoPlayer.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
        playerContainer.addView(playerView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = autoPlayEnabled()
        applyPendingResume(exoPlayer)

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        hideLoading()
                        retryCount = 0
                        reconnectHandler.removeCallbacks(rebufferWatchdog)
                        maybeOfferResume(exoPlayer)
                    }
                    Player.STATE_BUFFERING -> {
                        showLoading(if (isNetworkBack) "Cargando..." else "Sin conexión...")
                        reconnectHandler.removeCallbacks(rebufferWatchdog)
                        reconnectHandler.postDelayed(rebufferWatchdog, 15000)
                    }
                    Player.STATE_ENDED -> onVideoEnded()
                }
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                // Android TV fix: force the hardware SurfaceView buffer to panel-native resolution
                val (w, h) = TvSurfaceCompat.idealSurfaceSize(this@ExoPlayerActivity, videoSize.width, videoSize.height)
                TvSurfaceCompat.forcePlayerViewSurface(playerView, w, h)
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback error: ${error.message}")
                handlePlaybackError(error)
            }
        })
    }

    /**
     * Failover de servidores: si el enlace actual falla, abrir el siguiente servidor
     * en la lista (EmbedWebViewActivity se encarga de extraer y seguir la cadena).
     * Respeta el toggle "Fallback de servidores" de Ajustes.
     */
    private fun tryServerFailover(reason: String): Boolean {
        if (serverFailoverTriggered) return false
        if (!com.karin.streamtv.util.AppPreferences.isServerFallbackEnabled()) return false
        if (allServerUrls.isEmpty() || currentServerIndex + 1 >= allServerUrls.size) return false
        val nextIndex = currentServerIndex + 1
        val nextUrl = allServerUrls[nextIndex]
        val nextName = allServerNames.getOrElse(nextIndex) { "" }
        serverFailoverTriggered = true
        Log.w(TAG, "Server failover ($reason) -> $nextName: ${nextUrl.takeLast(60)}")
        Toast.makeText(this, "Servidor no disponible ($reason). Probando: $nextName", Toast.LENGTH_SHORT).show()
        val intent = Intent(this, com.karin.streamtv.ui.EmbedWebViewActivity::class.java).apply {
            putExtra("embed_url", nextUrl)
            putExtra("server_name", nextName)
            putExtra("video_title", videoTitle)
            putExtra("episode_url", currentEpisodeUrl)
            putExtra("episode_number", episodeNumber)
            putExtra("site_name", intent.getStringExtra("site_name") ?: "")
            putExtra("all_server_urls", allServerUrls)
            putExtra("all_server_names", allServerNames)
            putExtra("current_server_index", nextIndex)
            if (playlist.isNotEmpty()) {
                putExtra("playlist_json", com.karin.streamtv.util.PlaylistQueue.toJson(playlist))
                putExtra("playlist_index", playlistIndex)
            }
        }
        startActivity(intent)
        finish()
        return true
    }

    private fun handlePlaybackError(error: PlaybackException) {
        Log.e(TAG, "handlePlaybackError: ${error.errorCodeName} - ${error.message}")
        val transient =
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        // En gama baja los fallos de decoder suelen deberse a falta de RAM/heap
        // nativo; bajar la resolución máxima antes de rendirse evita cerrar la app.
        val decoderIssue =
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
        // HTTP duro (404/403/410/451): el enlace del servidor está roto, no vale la
        // pena reintentarlo → saltar al siguiente servidor si hay.
        val hardHttp =
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS &&
            error.message?.lowercase()?.let { m ->
                listOf("404", "403", "410", "451", "not found", "forbidden", "gone").any { m.contains(it) }
            } ?: false
        if (hardHttp && tryServerFailover("HTTP ${error.message?.substringBefore('\n')?.take(50)}")) return
        if (!transient && !decoderIssue) {
            if (!tryServerFailover("enlace no soportado")) {
                Toast.makeText(this@ExoPlayerActivity, "Error: ${error.message}", Toast.LENGTH_LONG).show()
                finish()
            }
            return
        }
        if (decoderIssue && selectedHeight > 0) {
            Log.w(TAG, "Decoder error - lowering max video height from ${selectedHeight}p")
            selectedHeight /= 2
            trackSelector?.setParameters(
                trackSelector!!.buildUponParameters()
                    .setMaxVideoSize(C.LENGTH_UNSET, selectedHeight)
            )
            retryCount = 0
            reconnectVideo("decoder-lower-resolution", 1000L)
            return
        }
        if (retryCount >= 3) {
            if (!tryServerFailover("reintentos agotados")) {
                Toast.makeText(this@ExoPlayerActivity, "Error: ${error.message}", Toast.LENGTH_LONG).show()
                finish()
            }
            return
        }
        retryCount++
        val delay = 1500L * retryCount
        showLoading("Reconectando... (intento $retryCount/3)")
        reconnectVideo("retry", delay)
    }

    /**
     * Reconexión robusta: tras un error el player está en ERROR/IDLE y un simple
     * seekTo+prepare es no-op. Se re-crea el MediaItem (fuerza re-apertura del
     * stream), se busca la posición y se respeta el estado de reproducción previo.
     */
    private fun reconnectVideo(reason: String, delayMs: Long) {
        val url = currentVideoUrl
        if (url.isBlank()) {
            finish()
            return
        }
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectHandler.postDelayed({
            val live = player ?: return@postDelayed
            val wasPlaying = live.playWhenReady
            val pos = live.currentPosition.coerceAtLeast(0)
            try {
                Log.d(TAG, "Reconnect($reason) at $pos")
                live.setMediaItem(MediaItem.fromUri(url))
                live.seekTo(pos)
                live.prepare()
                live.playWhenReady = wasPlaying
            } catch (e: Exception) {
                Log.e(TAG, "Reconnect prepare failed: ${e.message}")
                finish()
            }
        }, delayMs)
    }

    private fun forceReconnect(reason: String) {
        Log.w(TAG, "Reconnect triggered by: $reason")
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED || p.playbackState == Player.STATE_IDLE) return
        reconnectVideo(reason, 1200L)
    }

    private fun onVideoEnded() {
        if (animeId.isNotBlank() && episodeNumber > 0) {
            EpisodeProgress.markWatched(animeId, episodeNumber)
        }
        if (autoPlayTriggered || !AutoPlayManager.isAutoPlayEnabled()) return
        autoPlayTriggered = true

        val nextFromQueue = com.karin.streamtv.util.VideoQueue.peek()
        if (nextFromQueue != null) {
            com.karin.streamtv.util.VideoQueue.poll()
            val siteName = intent.getStringExtra("site_name") ?: ""
            AutoPlayManager.startCountdown(object : AutoPlayManager.AutoPlayCallback {
                override fun onCountdownTick(sec: Int) {
                    showLoading("Siguiente: ${nextFromQueue.title} en ${sec}s")
                }
                override fun onCountdownFinish() {
                    val intent = Intent(this@ExoPlayerActivity, com.karin.streamtv.ui.SiteBrowserActivity::class.java).apply {
                        putExtra("autoplay_url", nextFromQueue.embedUrl)
                        putExtra("autoplay_title", nextFromQueue.title)
                        putExtra("site_name", nextFromQueue.serverName)
                    }
                    startActivity(intent)
                    finish()
                }
                override fun onAutoPlayCancelled() {}
            })
            return
        }

        if (playlist.isNotEmpty()) {
            val nextIndex = playlistIndex + 1
            if (nextIndex >= playlist.size) return
            val next = playlist[nextIndex]
            val siteName = intent.getStringExtra("site_name") ?: ""
            AutoPlayManager.startCountdown(object : AutoPlayManager.AutoPlayCallback {
                override fun onCountdownTick(sec: Int) {
                    showLoading("Siguiente: ${next.title} en ${sec}s")
                }
                override fun onCountdownFinish() {
                    startActivity(com.karin.streamtv.util.PlaylistQueue.buildIntent(this@ExoPlayerActivity, playlist, nextIndex, siteName))
                    finish()
                }
                override fun onAutoPlayCancelled() {}
            })
            return
        }

        if (currentEpisodeUrl.isNotBlank() && episodeNumber > 0) {
            val nextUrl = AutoPlayManager.findNextEpisodeUrl(currentEpisodeUrl, episodeNumber)
            if (nextUrl != null) {
                AutoPlayManager.startCountdown(object : AutoPlayManager.AutoPlayCallback {
                    override fun onCountdownTick(sec: Int) {
                        showLoading("Siguiente episodio en ${sec}s")
                    }
                    override fun onCountdownFinish() {
                        val intent = android.content.Intent(
                            this@ExoPlayerActivity,
                            com.karin.streamtv.ui.SiteBrowserActivity::class.java
                        ).apply {
                            putExtra("autoplay_url", nextUrl)
                            putExtra("autoplay_title", "Episodio ${episodeNumber + 1}")
                            putExtra("site_name", intent.getStringExtra("site_name") ?: "")
                        }
                        startActivity(intent)
                        finish()
                    }
                    override fun onAutoPlayCancelled() {}
                })
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val mapped = GamepadHelper.mapGamepadToDpad(keyCode)
        if (mapped != keyCode) return onKeyDown(mapped, event)
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
            || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
            || keyCode == KeyEvent.KEYCODE_SPACE
            || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            togglePlayPause()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        if (useEnhancedMode && ::glSurface.isInitialized) glSurface.onResume()
        updateQueueBadge()
        // Restaurar el estado de reproducción previo (respetando pausa manual y PlayNow).
        player?.playWhenReady = wasPlayingBeforePause
    }

    private fun updateQueueBadge() {
        val qSize = com.karin.streamtv.util.VideoQueue.size()
        if (qSize > 0) {
            tvQueueBadge.visibility = View.VISIBLE
            tvQueueBadge.text = "Cola: $qSize"
        } else {
            tvQueueBadge.visibility = View.GONE
        }
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
        wasPlayingBeforePause = player?.playWhenReady == true
        player?.pause()
        if (useEnhancedMode && ::glSurface.isInitialized) glSurface.onPause()
    }

    override fun onDestroy() {
        saveProgress()
        fallbackHandler.removeCallbacksAndMessages(null)
        controllerHandler.removeCallbacksAndMessages(null)
        reconnectHandler.removeCallbacksAndMessages(null)
        networkCallback?.let { cb ->
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
        }
        networkCallback = null
        val proc = processor
        processor = null
        trackSelector = null
        proc?.release() ?: player?.release()
        player = null
        super.onDestroy()
    }

    private fun saveProgress() {
        if (animeId.isNotBlank() && episodeNumber > 0) {
            val p = player ?: return
            val pos = p.currentPosition.coerceAtLeast(0)
            val dur = p.duration.coerceAtLeast(0)
            if (dur > 0) {
                EpisodeProgress.saveLastPosition(animeId, episodeNumber, pos, dur)
                EpisodeProgress.setLastWatchedEpisode(animeId, episodeNumber)
            }
        }
    }

    companion object {
        private const val TAG = "ExoPlayerActivity"
    }
}
