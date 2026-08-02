package com.karin.streamtv.player

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class ExoPlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var processor: Media3SixtyFpsProcessor? = null
    private var trackSelector: DefaultTrackSelector? = null
    private lateinit var playerContainer: FrameLayout
    private lateinit var loadingText: TextView
    private lateinit var btnBack: TextView
    private lateinit var fpsBadge: TextView
    private lateinit var playStateOverlay: TextView
    private lateinit var controllerPanel: View
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: ImageButton
    private lateinit var tvPosition: TextView
    private lateinit var tvDuration: TextView
    private lateinit var btnQuality: TextView
    private lateinit var btnVolume: TextView
    private lateinit var btnDsp: TextView
    private lateinit var btnDebug: TextView
    private lateinit var btnDownload: ImageButton
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
        btnBack = findViewById(R.id.btn_back)
        fpsBadge = findViewById(R.id.tv_fps_badge)
        playStateOverlay = findViewById(R.id.tv_play_state)
        controllerPanel = findViewById(R.id.controller_panel)
        seekBar = findViewById(R.id.seek_bar)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        tvPosition = findViewById(R.id.tv_position)
        tvDuration = findViewById(R.id.tv_duration)
        btnQuality = findViewById(R.id.btn_quality)
        btnVolume = findViewById(R.id.btn_volume)
        btnDsp = findViewById(R.id.btn_dsp)
        btnDebug = findViewById(R.id.btn_debug)
        btnDownload = findViewById(R.id.btn_download)

        trackSelector = DefaultTrackSelector(this)

        val videoUrl = intent.getStringExtra("video_url")
        val embedUrl = intent.getStringExtra("embed_url")
        val serverName = intent.getStringExtra("server_name") ?: ""
        val episodeUrl = intent.getStringExtra("episode_url") ?: ""
        val epNum = intent.getIntExtra("episode_number", 0)

        currentEpisodeUrl = episodeUrl
        if (episodeUrl.isNotBlank()) {
            animeId = EpisodeProgress.generateAnimeId(episodeUrl)
            episodeNumber = epNum
        }

        referer = intent.getStringExtra("referer")
            ?: embedUrl
            ?: ""
        if (referer.startsWith("http://")) referer = "https://" + referer.substringAfter("http://")

        useEnhancedMode = VideoEnhanceConfig.isEnabled() || VideoEnhanceConfig.isInterpolationEnabled()

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
        updateDspButton()
        btnQuality.setOnClickListener { showQualityDialog() }
        btnVolume.setOnClickListener { showVolumeDialog() }
        btnDsp.setOnClickListener { showDspDialog() }
        btnDebug.setOnClickListener { showDebugDialog() }
        btnDownload.setOnClickListener { startDownload() }
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
        controllerPanel.visibility = View.VISIBLE
        btnBack.visibility = View.VISIBLE
        updateProgress()
        controllerHandler.removeCallbacks(hideController)
        controllerHandler.postDelayed(hideController, 3000)
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
        seek.max = 100
        seek.progress = (p.volume * 100).toInt().coerceIn(0, 100)
        AlertDialog.Builder(this)
            .setTitle("Volumen")
            .setView(seek)
            .setPositiveButton("Aceptar", null)
            .setOnDismissListener {
                p.volume = seek.progress / 100f
                btnVolume.text = "Vol ${seek.progress}%"
            }
            .show()
    }

    private fun showDspDialog() {
        val presets = com.karin.streamtv.player.dsp.AudioEnhanceConfig.Preset.values()
        val labels = ArrayList<String>()
        labels.add("Sin efectos")
        presets.forEach { labels.add(it.label) }
        val current = com.karin.streamtv.player.dsp.AudioEnhanceConfig.preset()
        val enabled = com.karin.streamtv.player.dsp.AudioEnhanceConfig.isEnabled()
        val selectedIdx = if (!enabled) 0 else presets.indexOf(current) + 1
        AlertDialog.Builder(this)
            .setTitle("Sonido (DSP)")
            .setSingleChoiceItems(labels.toTypedArray(), selectedIdx) { _, which ->
                if (which == 0) {
                    com.karin.streamtv.player.dsp.AudioEnhanceConfig.setEnabled(false)
                } else {
                    val preset = presets[which - 1]
                    com.karin.streamtv.player.dsp.AudioEnhanceConfig.applyParams(
                        com.karin.streamtv.player.dsp.AudioEnhanceConfig.Params().withPreset(preset)
                    )
                }
                updateDspButton()
                showController()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun updateDspButton() {
        val enabled = com.karin.streamtv.player.dsp.AudioEnhanceConfig.isEnabled()
        btnDsp.text = if (!enabled) "Sonido: OFF" else "Sonido: ${com.karin.streamtv.player.dsp.AudioEnhanceConfig.preset().label}"
    }

    private fun showDebugDialog() {
        val labels = arrayOf("OFF", "PREV", "CURR", "UV", "FACTOR", "MOTION", "V0V1", "VISUAL")
        val current = VideoEnhanceConfig.getDebugMode()
        AlertDialog.Builder(this)
            .setTitle("Debug de interpolación")
            .setSingleChoiceItems(labels, current) { _, which ->
                VideoEnhanceConfig.setDebugMode(which)
                processor?.renderer?.setDebugModeValue(which)
                btnDebug.text = labels[which]
                showController()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun startDownload() {
        val url = getCurrentVideoUrl() ?: run {
            Toast.makeText(this, "No hay URL de video disponible", Toast.LENGTH_SHORT).show()
            return
        }

        val title = intent.getStringExtra("episode_title") ?: "video"
        val safeTitle = title.replace(Regex("[^\\w\\-. ]"), "_").take(100)
        val extension = if (url.contains(".m3u8")) ".mp4" else if (url.contains(".mp4")) ".mp4" else ".mp4"
        val fileName = "${safeTitle}${extension}"

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setDescription("Descargando $fileName")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "KarinFLiX/$fileName")
            setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            allowScanningByMediaScanner()
        }

        referer?.let { request.addRequestHeader("Referer", it) }

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        Toast.makeText(this, "Descarga iniciada: $fileName", Toast.LENGTH_LONG).show()

        // Optional: show notification with progress (could be enhanced with a foreground service)
        lifecycleScope.launch {
            var downloading = true
            while (downloading) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val bytesDownloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val totalBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        Toast.makeText(this@ExoPlayerActivity, "Descarga completada: $fileName", Toast.LENGTH_LONG).show()
                        downloading = false
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        Toast.makeText(this@ExoPlayerActivity, "Descarga fallida (código: $reason)", Toast.LENGTH_LONG).show()
                        downloading = false
                    }
                }
                cursor.close()
                if (downloading) delay(2000)
            }
        }
    }

    private fun getCurrentVideoUrl(): String? {
        return when {
            intent.getStringExtra("video_url")?.isNotBlank() == true -> intent.getStringExtra("video_url")
            player?.currentMediaItem?.mediaId?.isNotBlank() == true -> player?.currentMediaItem?.mediaId
            else -> null
        }
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
        val key = resolved.megaKey ?: run {
            Toast.makeText(this, "MEGA key no disponible", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val megaFactory = androidx.media3.datasource.DataSource.Factory {
            com.karin.streamtv.player.MegaDecryptingDataSource(key, resolved.megaCtrStart, resolved.extraHeaders)
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
        if (useEnhancedMode && !isLocalUrl(url)) {
            playWithEnhancedPipeline(url)
        } else {
            playStandard(url)
        }
    }

    private fun playWithEnhancedPipeline(url: String) {
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
        btnDebug.text = VideoEnhanceConfig.debugModeLabel(VideoEnhanceConfig.getDebugMode())
        processor!!.onGlFailure = {
            runOnUiThread { triggerFallback(url) }
        }

        val exoPlayer = processor!!.createPlayer(trackSelector)
        player = exoPlayer

        processor!!.connectPlayer(exoPlayer)
        processor!!.play(url)
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
        Log.w(TAG, "Falling back to standard playback")
        fpsBadge.visibility = View.GONE
        processor?.release()
        processor = null
        player?.release()
        player = null
        playerContainer.removeAllViews()
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
                    fallbackHandler.postDelayed(this, 1000)
                } else {
                    fpsBadge.visibility = View.GONE
                }
            }
        }
        fallbackHandler.postDelayed(poll, 1000)
    }

    private fun playStandard(url: String) {
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
        if (!autoPlayTriggered && AutoPlayManager.isAutoPlayEnabled()
            && currentEpisodeUrl.isNotBlank() && episodeNumber > 0) {
            autoPlayTriggered = true
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
        player?.play()
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
