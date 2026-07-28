package com.karin.streamtv.player

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.karin.streamtv.R
import com.karin.streamtv.model.Episode
import com.karin.streamtv.model.VideoType
import com.karin.streamtv.ui.SiteBrowserActivity
import com.karin.streamtv.util.AniSkipService
import com.karin.streamtv.util.AppPreferences
import com.karin.streamtv.util.AutoPlayManager
import com.karin.streamtv.util.EpisodeProgress
import com.karin.streamtv.util.AudioEffectsManager
import com.karin.streamtv.util.Http
import com.karin.streamtv.util.WatchHistory
import com.karin.streamtv.util.onActionKey
import kotlinx.coroutines.launch

class PlayerActivity : FragmentActivity() {

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 2
        private const val RETRY_BASE_DELAY_MS = 2000L
    }

    private lateinit var playerView: PlayerView
    private lateinit var playerLoading: LinearLayout
    private lateinit var autoPlayOverlay: LinearLayout
    private lateinit var tvAutoPlayCountdown: TextView
    private lateinit var tvAutoPlayEpisode: TextView
    private lateinit var btnAutoPlayCancel: TextView
    private lateinit var btnAutoPlayPlay: TextView
    private lateinit var skipOverlay: LinearLayout
    private lateinit var tvSkipLabel: TextView
    private var audioEffectsManager: AudioEffectsManager? = null
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null

    private var videoUrl: String = ""
    private var videoTitle: String = ""
    private var seriesName: String = ""
    private var videoType: String = "UNKNOWN"
    private var episodeNumber: Int = 0
    private var animeId: String = ""
    private var episodeUrl: String = ""
    private var lastPosition: Long = 0L
    private var referer: String = ""
    private var siteName: String = ""
    private var siteUrl: String = ""
    private var currentIndex: Int = -1
    private var episodeList: ArrayList<Episode> = arrayListOf()
    private var retryCount = 0
    private var qualityMode = 0

    private var skipOpening: AniSkipService.SkipInterval? = null
    private var skipEnding: AniSkipService.SkipInterval? = null
    private var skipButtonVisible = false
    private var lastSkipType: String = ""
    private val skipHandler = Handler(Looper.getMainLooper())
    private var skipCheckScheduled = false
    private val skipChecker = object : Runnable {
        override fun run() {
            checkSkipTimestamps()
            skipCheckScheduled = false
        }
    }

    private fun scheduleSkipCheck(delayMs: Long = 1000) {
        if (!skipCheckScheduled) {
            skipCheckScheduled = true
            skipHandler.postDelayed(skipChecker, delayMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.player_view)
        playerLoading = findViewById(R.id.player_loading)
        autoPlayOverlay = findViewById(R.id.autoplay_overlay)
        tvAutoPlayCountdown = findViewById(R.id.tv_autoplay_countdown)
        tvAutoPlayEpisode = findViewById(R.id.tv_autoplay_episode)
        btnAutoPlayCancel = findViewById(R.id.btn_autoplay_cancel)
        btnAutoPlayPlay = findViewById(R.id.btn_autoplay_play)
        skipOverlay = findViewById(R.id.skip_overlay)
        tvSkipLabel = findViewById(R.id.tv_skip_label)

        audioEffectsManager = AudioEffectsManager(this)

        videoUrl = intent.getStringExtra("video_url") ?: ""
        videoTitle = intent.getStringExtra("video_title") ?: ""
        seriesName = intent.getStringExtra("series_name") ?: ""
        videoType = intent.getStringExtra("video_type") ?: "UNKNOWN"
        episodeNumber = intent.getIntExtra("episode_number", 0)
        animeId = intent.getStringExtra("anime_id") ?: ""
        episodeUrl = intent.getStringExtra("episode_url") ?: ""
        referer = intent.getStringExtra("referer") ?: ""
        siteName = intent.getStringExtra("site_name") ?: ""
        siteUrl = intent.getStringExtra("site_url") ?: ""
        currentIndex = intent.getIntExtra("current_index", -1)
        @Suppress("DEPRECATION")
        episodeList = intent.getParcelableArrayListExtra<Episode>("episode_list") ?: arrayListOf()

        EpisodeProgress.init(this)

        if (episodeNumber > 0 && animeId.isNotEmpty()) {
            lastPosition = EpisodeProgress.getLastPosition(animeId, episodeNumber)
        }

        btnAutoPlayCancel.setOnClickListener {
            AutoPlayManager.cancelCountdown()
            autoPlayOverlay.visibility = View.GONE
        }
        btnAutoPlayCancel.onActionKey { btnAutoPlayCancel.performClick() }
        btnAutoPlayPlay.setOnClickListener {
            AutoPlayManager.cancelCountdown()
            autoPlayOverlay.visibility = View.GONE
            playNextEpisode()
        }
        btnAutoPlayPlay.onActionKey { btnAutoPlayPlay.performClick() }

        skipOverlay.setOnClickListener { performSkip() }
        skipOverlay.onActionKey { performSkip() }

        if (videoUrl.isEmpty()) {
            Toast.makeText(this, "URL de video no valida", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initializePlayer()
        fetchSkipTimes()
    }

    private fun fetchSkipTimes() {
        val skipOpeningPref = AppPreferences.isSkipOpeningEnabled()
        val skipEndingPref = AppPreferences.isSkipEndingEnabled()
        if (!skipOpeningPref && !skipEndingPref) return

        val titleForLookup = seriesName.ifBlank { videoTitle }.ifBlank { animeId }
        if (titleForLookup.isBlank()) return

        lifecycleScope.launch {
            try {
                val result = AniSkipService.getSkipTimes(titleForLookup, episodeNumber)
                if (skipOpeningPref) skipOpening = result.opening
                if (skipEndingPref) skipEnding = result.ending
                Log.d("PlayerActivity", "AniSkip: title='$titleForLookup' ep=$episodeNumber op=${skipOpening != null} ed=${skipEnding != null}")
            } catch (e: Exception) {
                Log.w("PlayerActivity", "AniSkip fetch failed: ${e.message}")
            }
        }
    }

    private fun applyFallbackSkipTimes(durationMs: Long) {
        if (durationMs <= 0 || durationMs < 120_000) return

        val skipOpeningPref = AppPreferences.isSkipOpeningEnabled()
        val skipEndingPref = AppPreferences.isSkipEndingEnabled()

        if (skipOpeningPref && skipOpening == null) {
            skipOpening = AniSkipService.SkipInterval(80.0, 110.0, "op")
            Log.d("PlayerActivity", "Using fallback opening skip: 80-110s")
        }
        if (skipEndingPref && skipEnding == null) {
            val endSec = durationMs.toDouble() / 1000.0
            val startSec = maxOf(0.0, endSec - 90.0)
            skipEnding = AniSkipService.SkipInterval(startSec, endSec, "ed")
            Log.d("PlayerActivity", "Using fallback ending skip: ${String.format("%.0f", startSec)}-${String.format("%.0f", endSec)}s")
        }
    }

    private fun checkSkipTimestamps() {
        val currentPosition = player?.currentPosition?.toDouble()?.div(1000.0) ?: return
        val duration = player?.duration?.toDouble()?.div(1000.0) ?: return
        if (duration <= 0) return

        val opening = skipOpening
        val ending = skipEnding
        var nextCheckMs = 2000L

        when {
            opening != null && currentPosition >= opening.startTime && currentPosition < opening.endTime -> {
                if (!skipButtonVisible || lastSkipType != "op") {
                    showSkipButton("Saltar Opening")
                    lastSkipType = "op"
                }
                nextCheckMs = 200
            }
            ending != null && currentPosition >= ending.startTime && currentPosition < ending.endTime -> {
                if (!skipButtonVisible || lastSkipType != "ed") {
                    showSkipButton("Saltar Ending")
                    lastSkipType = "ed"
                }
                nextCheckMs = 200
            }
            else -> {
                if (skipButtonVisible) hideSkipButton()
                if (opening != null && currentPosition < opening.startTime) {
                    nextCheckMs = ((opening.startTime - currentPosition - 1.0) * 1000).coerceIn(200.0, 5000.0).toLong()
                } else if (ending != null && currentPosition < ending.startTime) {
                    nextCheckMs = ((ending.startTime - currentPosition - 1.0) * 1000).coerceIn(200.0, 5000.0).toLong()
                }
            }
        }

        skipCheckScheduled = false
        scheduleSkipCheck(nextCheckMs)
    }

    private fun showSkipButton(label: String) {
        tvSkipLabel.text = label
        skipOverlay.visibility = View.VISIBLE
        skipButtonVisible = true
        skipOverlay.contentDescription = "Saltar $label"
        skipOverlay.announceForAccessibility("Boton disponible: Saltar $label")
        skipOverlay.requestFocus()
    }

    private fun hideSkipButton() {
        skipOverlay.visibility = View.GONE
        skipButtonVisible = false
        lastSkipType = ""
    }

    private fun performSkip() {
        val currentPosition = player?.currentPosition?.toDouble()?.div(1000.0) ?: return

        when (lastSkipType) {
            "op" -> {
                skipOpening?.let { skip ->
                    if (currentPosition >= skip.startTime && currentPosition < skip.endTime) {
                        player?.seekTo((skip.endTime * 1000).toLong())
                        hideSkipButton()
                    }
                }
            }
            "ed" -> {
                skipEnding?.let { skip ->
                    if (currentPosition >= skip.startTime && currentPosition < skip.endTime) {
                        player?.seekTo((skip.endTime * 1000).toLong())
                        hideSkipButton()
                    }
                }
            }
        }
    }

    private fun playNextEpisode() {
        if (episodeList.isEmpty() || currentIndex < 0) {
            val nextUrl = AutoPlayManager.findNextEpisodeUrl(episodeUrl, episodeNumber) ?: return
            val intent = Intent(this, SiteBrowserActivity::class.java).apply {
                putExtra("site_name", siteName)
                putExtra("site_url", siteUrl)
                putExtra("autoplay_url", nextUrl)
                putExtra("autoplay_title", "Episodio ${episodeNumber + 1}")
            }
            startActivity(intent)
            finish()
            return
        }

        val nextIndex = currentIndex + 1
        if (nextIndex >= episodeList.size) {
            Toast.makeText(this, "No hay mas episodios", Toast.LENGTH_SHORT).show()
            return
        }

        val nextEpisode = episodeList[nextIndex]
        val intent = Intent(this, SiteBrowserActivity::class.java).apply {
            putExtra("site_name", siteName)
            putExtra("site_url", siteUrl)
            putExtra("autoplay_url", nextEpisode.url)
            putExtra("autoplay_title", nextEpisode.title)
        }
        startActivity(intent)
        finish()
    }

    private fun showAutoPlayCountdown() {
        if (!AutoPlayManager.isAutoPlayEnabled()) return

        val hasNext = if (episodeList.isNotEmpty() && currentIndex >= 0) {
            currentIndex + 1 < episodeList.size
        } else {
            episodeNumber > 0 && episodeUrl.isNotEmpty() &&
                AutoPlayManager.findNextEpisodeUrl(episodeUrl, episodeNumber) != null
        }
        if (!hasNext) return

        autoPlayOverlay.visibility = View.VISIBLE
        val nextTitle = if (episodeList.isNotEmpty() && currentIndex >= 0 && currentIndex + 1 < episodeList.size) {
            episodeList[currentIndex + 1].title
        } else {
            "Episodio ${episodeNumber + 1}"
        }
        tvAutoPlayEpisode.text = nextTitle

        AutoPlayManager.startCountdown(object : AutoPlayManager.AutoPlayCallback {
            override fun onCountdownTick(secondsRemaining: Int) {
                tvAutoPlayCountdown.text = secondsRemaining.toString()
                if (secondsRemaining <= 3) {
                    autoPlayOverlay.announceForAccessibility("Reproduciendo siguiente episodio en $secondsRemaining")
                }
            }

            override fun onCountdownFinish() {
                autoPlayOverlay.visibility = View.GONE
                playNextEpisode()
            }

            override fun onAutoPlayCancelled() {
                autoPlayOverlay.visibility = View.GONE
            }
        })
    }

    private fun enterImmersiveMode() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun initializePlayer() {
        if (player != null) {
            releasePlayer()
        }

        val adaptiveTrackSelectionFactory = AdaptiveTrackSelection.Factory()
        trackSelector = DefaultTrackSelector(this, adaptiveTrackSelectionFactory).apply {
            val isLowRam = (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
            val params = buildUponParameters()
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setPreferredTextLanguage("es")
                .setSelectUndeterminedTextLanguage(true)
            if (isLowRam) {
                params.setMaxVideoSize(1280, 720)
                params.setMaxVideoBitrate(1_500_000)
            }
            setParameters(params)
        }

        val defaultHeaders = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9,es;q=0.8"
        )
        if (referer.isNotBlank()) {
            defaultHeaders["Referer"] = referer
        } else {
            try {
                val videoHost = java.net.URL(videoUrl).host
                defaultHeaders["Referer"] = "https://$videoHost/"
            } catch (_: Exception) {}
        }

        val okHttpClient = Http.client.newBuilder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setDefaultRequestProperties(defaultHeaders)

        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(this, httpDataSourceFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,  // minBufferMs — faster startup, still safe for most connections
                60_000,  // maxBufferMs — 60s ceiling prevents excessive memory use
                2_000,   // bufferForPlaybackMs — start playing with just 2s buffered
                5_000    // bufferForPlaybackAfterRebufferMs — 5s after rebuffer
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val bandwidthMeter = DefaultBandwidthMeter.Builder(this)
            .setResetOnNetworkTypeChange(true)
            .build()

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector!!)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setBandwidthMeter(bandwidthMeter)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
            )
            .build()
            .also { exoPlayer ->
                playerView.player = exoPlayer
                playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)

                adjustQualityForNetwork()

                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                playerLoading.visibility = View.VISIBLE
                            }
                            Player.STATE_READY -> {
                                playerLoading.visibility = View.GONE
                                retryCount = 0
                                if (lastPosition > 0 && exoPlayer.currentPosition < 1000) {
                                    exoPlayer.seekTo(lastPosition)
                                    lastPosition = 0L
                                }
                                val sessionId = exoPlayer.audioSessionId
                                if (sessionId > 0 && audioEffectsManager != null) {
                                    audioEffectsManager?.attachToSession(sessionId)
                                }
                                applyFallbackSkipTimes(exoPlayer.duration)
                                scheduleSkipCheck(500)
                            }
                            Player.STATE_ENDED -> {
                                skipHandler.removeCallbacksAndMessages(null)
                                skipCheckScheduled = false
                                hideSkipButton()
                                if (episodeNumber > 0 && animeId.isNotEmpty()) {
                                    EpisodeProgress.markWatched(animeId, episodeNumber)
                                    WatchHistory.addEntry(WatchHistory.HistoryEntry(
                                        animeId = animeId,
                                        episodeNumber = episodeNumber,
                                        title = videoTitle,
                                        siteName = siteName,
                                        thumbnailUrl = episodeUrl,
                                        episodeUrl = episodeUrl,
                                        positionMs = exoPlayer.duration,
                                        durationMs = exoPlayer.duration
                                    ))
                                }
                                showAutoPlayCountdown()
                            }
                            Player.STATE_IDLE -> {}
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            scheduleSkipCheck(500)
                        } else {
                            skipHandler.removeCallbacksAndMessages(null)
                            skipCheckScheduled = false
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        playerLoading.visibility = View.GONE
                        skipHandler.removeCallbacksAndMessages(null)
                        skipCheckScheduled = false
                        Log.e("PlayerActivity", "ExoPlayer error (attempt ${retryCount + 1}): ${error.message}")

                        if (retryCount < MAX_RETRY_ATTEMPTS) {
                            retryCount++
                            val delay = RETRY_BASE_DELAY_MS * retryCount
                            Log.d("PlayerActivity", "Retrying in ${delay}ms (attempt $retryCount/$MAX_RETRY_ATTEMPTS)")
                            Toast.makeText(
                                this@PlayerActivity,
                                "Reintentando... ($retryCount/$MAX_RETRY_ATTEMPTS)",
                                Toast.LENGTH_SHORT
                            ).show()
                            skipHandler.postDelayed({
                                if (!isFinishing && !isDestroyed && player != null && videoUrl.isNotEmpty()) {
                                    initializePlayer()
                                }
                            }, delay)
                        } else {
                            Toast.makeText(
                                this@PlayerActivity,
                                "Error al reproducir: ${error.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        Log.d("PlayerActivity", "Audio session changed: $audioSessionId")
                        audioEffectsManager?.attachToSession(audioSessionId)
                    }
                })

                val mediaItem = buildMediaItem()
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = AppPreferences.isPlayNowEnabled()
            }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun adjustQualityForNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val network = cm.activeNetwork ?: return
        val caps = cm.getNetworkCapabilities(network) ?: return

        val maxBitrate = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 8_000_000
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2_500_000
            else -> 1_500_000
        }

        trackSelector?.setParameters(
            trackSelector!!.buildUponParameters()
                .setMaxVideoBitrate(maxBitrate)
                .build()
        )
    }

    private fun buildMediaItem(): MediaItem {
        val uri = Uri.parse(videoUrl)
        val type = try { VideoType.valueOf(videoType) } catch (_: Exception) { VideoType.UNKNOWN }

        return when (type) {
            VideoType.M3U8 -> MediaItem.Builder().setUri(uri).setMimeType("application/x-mpegURL").build()
            VideoType.DASH -> MediaItem.Builder().setUri(uri).setMimeType("application/dash+xml").build()
            VideoType.MP4 -> MediaItem.Builder().setUri(uri).setMimeType("video/mp4").build()
            else -> MediaItem.fromUri(uri)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val mapped = com.karin.streamtv.util.GamepadHelper.mapGamepadToDpad(keyCode)
        if (mapped != keyCode) {
            return onKeyDown(mapped, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE -> {
                releasePlayer()
                finish()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                player?.let { it.playWhenReady = !it.playWhenReady }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                player?.let { it.seekTo(maxOf(0, it.currentPosition - 10000)) }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                player?.let { it.seekTo(minOf(it.duration, it.currentPosition + 10000)) }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (playerView.isControllerFullyVisible) {
                    cycleQuality()
                } else {
                    playerView.useController = true
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                playerView.resizeMode = when (playerView.resizeMode) {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT ->
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM ->
                        AspectRatioFrameLayout.RESIZE_MODE_FILL
                    AspectRatioFrameLayout.RESIZE_MODE_FILL ->
                        AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                    else ->
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                return true
            }

        }
        return super.onKeyDown(keyCode, event)
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun cycleQuality() {
        val ts = trackSelector ?: return
        qualityMode = (qualityMode + 1) % 3
        val label = when (qualityMode) {
            0 -> { ts.setParameters(ts.buildUponParameters().setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE).setMaxVideoBitrate(Int.MAX_VALUE).build()); "Auto" }
            1 -> { ts.setParameters(ts.buildUponParameters().setMaxVideoSize(1920, 1080).setMaxVideoBitrate(8_000_000).build()); "1080p max" }
            2 -> { ts.setParameters(ts.buildUponParameters().setMaxVideoSize(1280, 720).setMaxVideoBitrate(2_500_000).build()); "720p max" }
            else -> "Auto"
        }
        Toast.makeText(this, "Calidad: $label", Toast.LENGTH_SHORT).show()
    }

    private fun releasePlayer() {
        skipHandler.removeCallbacksAndMessages(null)
        skipCheckScheduled = false
        audioEffectsManager?.release()
        player?.release()
        player = null
        trackSelector = null
    }

    override fun onPause() {
        super.onPause()
        saveCurrentPosition()
        player?.playWhenReady = false
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = AppPreferences.isPlayNowEnabled()
        enterImmersiveMode()
    }

    override fun onDestroy() {
        saveCurrentPosition()
        releasePlayer()
        AutoPlayManager.cancelCountdown()
        skipHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun saveCurrentPosition() {
        if (episodeNumber > 0 && animeId.isNotEmpty()) {
            val currentPosition = player?.currentPosition ?: 0L
            val duration = player?.duration ?: 0L
            if (currentPosition > 0 && duration > 0) {
                EpisodeProgress.saveLastPosition(animeId, episodeNumber, currentPosition, duration)
                WatchHistory.addEntry(WatchHistory.HistoryEntry(
                    animeId = animeId,
                    episodeNumber = episodeNumber,
                    title = videoTitle,
                    siteName = siteName,
                    thumbnailUrl = episodeUrl,
                    episodeUrl = episodeUrl,
                    positionMs = currentPosition,
                    durationMs = duration
                ))
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            playerView.useController = false
            autoPlayOverlay.visibility = View.GONE
            skipOverlay.visibility = View.GONE
        } else {
            playerView.useController = true
            enterImmersiveMode()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true) {
            try {
                enterPictureInPictureMode()
            } catch (_: Exception) {}
        }
    }
}
