package com.karin.streamtv.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
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
    private lateinit var loadingText: TextView
    private lateinit var topBar: View
    private lateinit var tvVideoTitle: TextView
    private lateinit var tvEpisodeInfo: TextView
    private lateinit var btnBack: TextView
    private lateinit var fpsBadge: TextView
    private lateinit var tvQueueBadge: TextView
    private lateinit var playStateOverlay: TextView
    private lateinit var controllerPanel: View
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnPrev: TextView
    private lateinit var btnNext: TextView
    private lateinit var tvPosition: TextView
    private lateinit var tvDuration: TextView
    private lateinit var btnQuality: TextView
    private lateinit var btnVolume: ImageButton
    private lateinit var btnDsp: TextView
    private lateinit var btnInterp: TextView
    private lateinit var btnVideoProfile: TextView
    private lateinit var btnUpscaler: TextView
    private var seekDragging = false
    private var selectedHeight = -1
    private val controllerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            controllerHandler.postDelayed(this, 500)
        }
    }
    private val hideController = Runnable {
        topBar.visibility = View.GONE
        controllerPanel.visibility = View.GONE
        btnBack.visibility = View.GONE
    }
    private var animeId: String = ""
    private var episodeNumber: Int = 0
    private var currentEpisodeUrl: String = ""
    private var autoPlayTriggered: Boolean = false
    private var useEnhancedMode = false
    private var referer: String = ""
    private var fallbackTriggered = false
    private var isPlainFallback = false
    private var playlist: List<com.karin.streamtv.model.PlaylistItem> = emptyList()
    private var playlistIndex: Int = 0
    private var currentVideoUrl: String = ""
    private var currentMegaResolved: com.karin.streamtv.scraper.ServerDirectResolver.ResolvedVideo? = null
    private var pendingResumeMs = -1L
    private val fallbackHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private lateinit var glSurface: android.opengl.GLSurfaceView

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
        loadingText = findViewById(R.id.tv_loading)
        topBar = findViewById(R.id.top_bar)
        tvVideoTitle = findViewById(R.id.tv_video_title)
        tvEpisodeInfo = findViewById(R.id.tv_episode_info)
        btnBack = findViewById(R.id.btn_back)
        fpsBadge = findViewById(R.id.tv_fps_badge)
        tvQueueBadge = findViewById(R.id.tv_queue_badge)
        playStateOverlay = findViewById(R.id.tv_play_state)
        controllerPanel = findViewById(R.id.controller_panel)
        seekBar = findViewById(R.id.seek_bar)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)
        tvPosition = findViewById(R.id.tv_position)
        tvDuration = findViewById(R.id.tv_duration)
        btnQuality = findViewById(R.id.btn_quality)
        btnVolume = findViewById(R.id.btn_volume)
        btnDsp = findViewById(R.id.btn_dsp)
        btnInterp = findViewById(R.id.btn_interp)
        btnVideoProfile = findViewById(R.id.btn_video_profile)
        btnUpscaler = findViewById(R.id.btn_upscaler)

        trackSelector = DefaultTrackSelector(this)

        val externalUri: Uri? = if (intent.action == Intent.ACTION_VIEW) intent.data else null
        val videoUrl = intent.getStringExtra("video_url") ?: externalUri?.toString()
        val embedUrl = intent.getStringExtra("embed_url")
        val serverName = intent.getStringExtra("server_name") ?: ""
        val episodeUrl = intent.getStringExtra("episode_url") ?: ""
        val epNum = intent.getIntExtra("episode_number", 0)

        playlist = com.karin.streamtv.util.PlaylistQueue.fromJson(intent.getStringExtra("playlist_json"))
        playlistIndex = intent.getIntExtra("playlist_index", 0)

        currentEpisodeUrl = episodeUrl
        if (episodeUrl.isNotBlank()) {
            animeId = EpisodeProgress.generateAnimeId(episodeUrl)
            episodeNumber = epNum
        }

        val videoTitle = intent.getStringExtra("video_title") ?: ""
        tvVideoTitle.text = videoTitle
        if (episodeNumber > 0) {
            tvEpisodeInfo.text = "Ep. $episodeNumber"
            tvEpisodeInfo.visibility = View.VISIBLE
        }

        referer = intent.getStringExtra("referer")
            ?: embedUrl
            ?: ""
        if (referer.startsWith("http://")) referer = "https://" + referer.substringAfter("http://")

        useEnhancedMode = isEnhancementActive()

        val dbgExtra = intent.getIntExtra("debug_mode", -1)
        if (dbgExtra >= 0) VideoEnhanceConfig.setDebugMode(dbgExtra)

        val megaKeyB64 = intent.getStringExtra("mega_key")
        if (videoUrl != null && videoUrl.isNotBlank() && megaKeyB64 != null) {
            loadingText.visibility = View.GONE
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
            loadingText.visibility = View.GONE
            playVideo(videoUrl)
        } else if (embedUrl != null && embedUrl.isNotBlank()) {
            loadingText.visibility = View.VISIBLE
            loadingText.text = "Extrayendo video..."
            extractAndPlay(embedUrl, serverName)
        } else {
            Toast.makeText(this, "URL de video no disponible", Toast.LENGTH_SHORT).show()
            finish()
        }

        btnBack.setOnClickListener { finish() }
        btnBack.onActionKey { finish() }
        playerContainer.setOnClickListener { showController() }
        btnPlayPause.setOnClickListener { togglePlayPause() }
        if (playlist.isEmpty()) {
            btnPrev.visibility = View.GONE
            btnNext.visibility = View.GONE
        } else {
            btnPrev.setOnClickListener { skipToPlaylist(-1) }
            btnNext.setOnClickListener { skipToPlaylist(1) }
        }
        updateDspButton()
        btnQuality.setOnClickListener { showQualityDialog() }
        btnVolume.setOnClickListener { showVolumeDialog() }
        btnDsp.setOnClickListener { showDspDialog() }
        btnVideoProfile.setOnClickListener { showVideoProfileDialog() }
        updateVideoProfileButton()
        btnInterp.setOnClickListener { toggleInterpolation() }
        updateInterpButton()
        btnUpscaler.setOnClickListener { showUpscalerDialog() }
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
    }

    private fun showController() {
        topBar.visibility = View.VISIBLE
        controllerPanel.visibility = View.VISIBLE
        btnBack.visibility = View.GONE
        updateProgress()
        controllerHandler.removeCallbacks(hideController)
        controllerHandler.postDelayed(hideController, 4000)
    }

    private fun togglePlayPause() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        playStateOverlay.text = if (p.playWhenReady) "Reproduciendo" else "Pausa"
        playStateOverlay.visibility = View.VISIBLE
        playStateOverlay.removeCallbacks(hidePlayState)
        playStateOverlay.postDelayed(hidePlayState, 800)
        updateProgress()
        showController()
    }

    private fun updateProgress() {
        val p = player ?: return
        val dur = p.duration.coerceAtLeast(0)
        val pos = p.currentPosition.coerceAtLeast(0)
        if (dur > 0) {
            seekBar.max = dur.toInt().coerceAtLeast(1)
            if (!seekDragging) seekBar.progress = pos.toInt()
            tvPosition.text = formatTime(pos)
            tvDuration.text = formatTime(dur)
        }
        btnPlayPause.setImageResource(
            if (p.playWhenReady && p.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return String.format("%d:%02d", s / 60, s % 60)
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
        AlertDialog.Builder(this)
            .setTitle("Volumen (100% = normal, hasta 300% para videos bajos)")
            .setView(seek)
            .setPositiveButton("Aceptar", null)
            .setOnDismissListener {
                p.volume = seek.progress / 100f
                com.karin.streamtv.util.AppPreferences.setPlayerVolume(p.volume)
            }
            .show()
    }

    private fun showDspDialog() {
        val presets = com.karin.streamtv.player.dsp.AudioEnhanceConfig.Preset.entries
        val labels = ArrayList<String>()
        presets.forEach { labels.add(it.label) }
        val current = com.karin.streamtv.player.dsp.AudioEnhanceConfig.preset()
        val enabled = com.karin.streamtv.player.dsp.AudioEnhanceConfig.isEnabled()
        val selectedIdx = if (!enabled || current == com.karin.streamtv.player.dsp.AudioEnhanceConfig.Preset.OFF)
            presets.indexOf(com.karin.streamtv.player.dsp.AudioEnhanceConfig.Preset.OFF)
        else presets.indexOf(current)
        AlertDialog.Builder(this)
            .setTitle("Sonido (DSP)")
            .setSingleChoiceItems(labels.toTypedArray(), selectedIdx) { _, which ->
                val preset = presets[which]
                com.karin.streamtv.player.dsp.AudioEnhanceConfig.applyParams(
                    com.karin.streamtv.player.dsp.AudioEnhanceConfig.Params().withPreset(preset)
                )
                updateDspButton()
                showController()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun updateDspButton() {
        val preset = com.karin.streamtv.player.dsp.AudioEnhanceConfig.preset()
        val enabled = com.karin.streamtv.player.dsp.AudioEnhanceConfig.isEnabled()
        btnDsp.text = if (!enabled || preset == com.karin.streamtv.player.dsp.AudioEnhanceConfig.Preset.OFF)
            "Sonido: OFF"
        else "Sonido: ${preset.label}"
    }

    private fun showVideoProfileDialog() {
        val presets = VideoEnhanceConfig.Preset.entries
        val labels = ArrayList<String>()
        presets.forEach { labels.add(it.label) }
        val current = VideoEnhanceConfig.preset()
        val enabled = VideoEnhanceConfig.isEnabled()
        val selectedIdx = if (!enabled || current == VideoEnhanceConfig.Preset.OFF)
            presets.indexOf(VideoEnhanceConfig.Preset.OFF)
        else presets.indexOf(current)
        AlertDialog.Builder(this)
            .setTitle("Perfil de Video")
            .setSingleChoiceItems(labels.toTypedArray(), selectedIdx) { _, which ->
                VideoEnhanceConfig.applyPreset(presets[which])
                updateVideoProfileButton()
                syncEnhancementMode()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun updateVideoProfileButton() {
        val preset = VideoEnhanceConfig.preset()
        val enabled = VideoEnhanceConfig.isEnabled()
        btnVideoProfile.text = if (!enabled || preset == VideoEnhanceConfig.Preset.OFF)
            "Video: OFF"
        else "Video: ${preset.label}"
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

    private fun toggleInterpolation() {
        val enabling = !VideoEnhanceConfig.isInterpolationEnabled()
        VideoEnhanceConfig.setInterpolationEnabled(enabling)
        updateInterpButton()
        Toast.makeText(this, "MotionX2 60p: ${if (enabling) "Activado" else "Desactivado"}", Toast.LENGTH_SHORT).show()
        if (enabling && !useEnhancedMode) {
            useEnhancedMode = true
            restartWithEnhanced()
        } else if (!enabling && useEnhancedMode && !isEnhancementActive()) {
            switchToStandardPlayback()
        } else {
            showController()
        }
    }

    private fun updateInterpButton() {
        btnInterp.text = "MotionX2 60p: ${if (VideoEnhanceConfig.isInterpolationEnabled()) "ON" else "OFF"}"
    }

    private fun restartWithEnhanced() {
        isPlainFallback = false
        fallbackTriggered = false
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

    private fun isEnhancementActive(): Boolean =
        VideoEnhanceConfig.isEnabled() ||
            VideoEnhanceConfig.isInterpolationEnabled() ||
            VideoEnhanceConfig.getUpscalerMode() != VideoEnhanceConfig.UpscalerMode.OFF

    private fun syncEnhancementMode() {
        val active = isEnhancementActive()
        when {
            active && !useEnhancedMode -> {
                useEnhancedMode = true
                restartWithEnhanced()
            }
            !active && useEnhancedMode -> switchToStandardPlayback()
            else -> showController()
        }
    }

    private fun switchToStandardPlayback() {
        useEnhancedMode = false
        isPlainFallback = true
        Log.i(TAG, "Switching to native playback (sin pipeline GL)")
        fpsBadge.visibility = View.GONE
        processor?.release()
        processor = null
        player?.release()
        player = null
        playerContainer.removeAllViews()
        if (currentMegaResolved != null) {
            playVideoMega(currentMegaResolved!!)
        } else if (currentVideoUrl.isNotBlank()) {
            playVideo(currentVideoUrl)
        }
    }

    private fun applyPendingResume(exoPlayer: androidx.media3.exoplayer.ExoPlayer) {
        if (pendingResumeMs > 0) {
            exoPlayer.seekTo(pendingResumeMs)
            pendingResumeMs = -1
        }
    }

    private fun showUpscalerDialog() {
        val modes = VideoEnhanceConfig.UpscalerMode.entries
        val labels = modes.map { it.label }.toTypedArray()
        val current = VideoEnhanceConfig.getUpscalerMode()
        val selectedIdx = modes.indexOf(current)
        AlertDialog.Builder(this)
            .setTitle("Escalado de Video")
            .setSingleChoiceItems(labels, selectedIdx) { _, which ->
                VideoEnhanceConfig.setUpscalerMode(modes[which])
                updateUpscalerButton()
                syncEnhancementMode()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun updateUpscalerButton() {
        val mode = VideoEnhanceConfig.getUpscalerMode()
        btnUpscaler.text = "Escala: ${mode.label}"
    }

    private fun applySavedVolume() {
        val p = player ?: return
        val v = com.karin.streamtv.util.AppPreferences.getPlayerVolume()
        p.volume = v
    }

    private fun extractAndPlay(embedUrl: String, serverName: String) {
        lifecycleScope.launch {
            try {
                val resolved = com.karin.streamtv.scraper.ServerDirectResolver.resolve(embedUrl, referer)
                loadingText.visibility = View.GONE
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
                        extractor.extractSuspend(embedUrl, serverName)
                    }
                    loadingText.visibility = View.GONE
                    if (!url.isNullOrBlank()) {
                        playVideo(url)
                    } else {
                        Toast.makeText(this@ExoPlayerActivity, "No se pudo extraer el video", Toast.LENGTH_LONG).show()
                        finish()
                    }
                } finally {
                    extractor.destroy()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Extraction error: ${e.message}", e)
                loadingText.visibility = View.GONE
                Toast.makeText(this@ExoPlayerActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
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
            com.karin.streamtv.player.MegaDecryptingDataSource(key, resolved.megaCtrStart, resolved.extraHeaders)
        }
        if (useEnhancedMode && !isPlainFallback) {
            playWithEnhancedPipeline(resolved.url, megaFactory)
            return
        }
        val exoPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(this, com.karin.streamtv.player.dsp.DspRenderersFactory(this))
            .setTrackSelector(trackSelector!!)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(megaFactory)
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(android.os.PowerManager.PARTIAL_WAKE_LOCK)
            .build()
        player = exoPlayer
        applySavedVolume()

        val playerView = androidx.media3.ui.PlayerView(this).apply {
            useController = false
            keepScreenOn = true
            player = exoPlayer
        }
        playerContainer.addView(playerView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        exoPlayer.setMediaItem(MediaItem.fromUri(resolved.url))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        applyPendingResume(exoPlayer)

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> loadingText.visibility = View.GONE
                    Player.STATE_ENDED -> onVideoEnded()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "MEGA Playback error: ${error.message}")
                Toast.makeText(this@ExoPlayerActivity, "Error: ${error.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        })
    }

    private fun isLocalUrl(url: String): Boolean =
        url.startsWith("content://") || url.startsWith("file://")

    private fun playVideo(url: String) {
        currentVideoUrl = url
        if (useEnhancedMode && !isPlainFallback) {
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
                        loadingText.visibility = View.GONE
                        Log.i(TAG, "Player STATE_READY - 60fps pipeline active")
                    }
                    Player.STATE_ENDED -> onVideoEnded()
                }
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                processor?.renderer?.setVideoSize(videoSize.width, videoSize.height)
                Log.i(TAG, "Video size: ${videoSize.width}x${videoSize.height}")
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback error: ${error.message}")
                Toast.makeText(this@ExoPlayerActivity, "Error: ${error.message}", Toast.LENGTH_LONG).show()
                finish()
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
                        fpsBadge.text = "Salida ${r.outputFps.toInt()} fps · Fuente ${r.sourceFps.toInt()} · ${r.frameMs}ms · Mov ${(r.motionLevel * 100).toInt()} · Drop ${r.droppedFrames} · ${r.qualityLabel}"
                    } else {
                        fpsBadge.visibility = View.GONE
                    }
                    val p = player
                    if (p != null && p.isPlaying && p.playbackState == Player.STATE_READY) {
                        val lastNs = r.lastRenderedFrameNs
                        if (lastNs > 0) {
                            val stallMs = (System.nanoTime() - lastNs) / 1_000_000
                            if (stallMs > 2500) {
                                Log.w(TAG, "Video congelado ${stallMs}ms mientras el audio avanza - resincronizando")
                                processor?.resyncSurface()
                                r.markResync()
                            }
                        }
                    }
                    fallbackHandler.postDelayed(this, 1000)
                } else {
                    fpsBadge.visibility = View.GONE
                }
            }
        }
        fallbackHandler.postDelayed(poll, 1000)
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
        val exoPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(this, com.karin.streamtv.player.dsp.DspRenderersFactory(this))
            .setTrackSelector(trackSelector!!)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(
                        if (isLocalUrl(url)) {
                            androidx.media3.datasource.DefaultDataSource.Factory(this)
                        } else {
                            VideoDataSource.factory(referer)
                        }
                    )
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(android.os.PowerManager.PARTIAL_WAKE_LOCK)
            .build()
        player = exoPlayer
        applySavedVolume()

        val playerView = androidx.media3.ui.PlayerView(this).apply {
            useController = false
            keepScreenOn = true
            player = exoPlayer
        }
        playerContainer.addView(playerView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        applyPendingResume(exoPlayer)

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> loadingText.visibility = View.GONE
                    Player.STATE_ENDED -> onVideoEnded()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback error: ${error.message}")
                Toast.makeText(this@ExoPlayerActivity, "Error: ${error.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        })
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
                    loadingText.visibility = View.VISIBLE
                    loadingText.text = "Siguiente: ${nextFromQueue.title} en ${sec}s"
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
                    loadingText.visibility = View.VISIBLE
                    loadingText.text = "Siguiente: ${next.title} en ${sec}s"
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
                        loadingText.visibility = View.VISIBLE
                        loadingText.text = "Siguiente episodio en ${sec}s"
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
        player?.play()
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
        player?.pause()
        if (useEnhancedMode && ::glSurface.isInitialized) glSurface.onPause()
    }

    override fun onDestroy() {
        saveProgress()
        fallbackHandler.removeCallbacksAndMessages(null)
        controllerHandler.removeCallbacksAndMessages(null)
        processor?.release()
        processor = null
        trackSelector = null
        player?.release()
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
