package com.karin.streamtv.ui.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.leanback.app.PlaybackSupportFragment
import androidx.leanback.app.PlaybackSupportFragmentGlueHost
import androidx.leanback.media.PlaybackTransportControlGlue
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.leanback.LeanbackPlayerAdapter
import com.karin.streamtv.R
import com.karin.streamtv.player.Media3SixtyFpsProcessor
import com.karin.streamtv.player.MegaDecryptingDataSource
import com.karin.streamtv.player.VideoDataSource
import com.karin.streamtv.player.VideoEnhanceConfig
import com.karin.streamtv.player.VideoExtractorHelper
import com.karin.streamtv.scraper.ServerDirectResolver
import com.karin.streamtv.util.AutoPlayManager
import com.karin.streamtv.util.EpisodeProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class TvPlaybackFragment : PlaybackSupportFragment() {

    private var player: ExoPlayer? = null
    private var processor: Media3SixtyFpsProcessor? = null
    private var glue: PlaybackTransportControlGlue<LeanbackPlayerAdapter>? = null
    private lateinit var playerContainer: FrameLayout
    private lateinit var loadingText: TextView
    private lateinit var fpsBadge: TextView
    private lateinit var glSurface: android.opengl.GLSurfaceView
    private var glActive = false

    private var animeId = ""
    private var episodeNumber = 0
    private var currentEpisodeUrl = ""
    private var autoPlayTriggered = false
    private var useEnhancedMode = false
    private var referer = ""
    private var fallbackTriggered = false
    private val fallbackHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setBackgroundType(BG_NONE)

        val args = arguments
        val videoUrl = args?.getString("video_url")
        val embedUrl = args?.getString("embed_url")
        val episodeUrl = args?.getString("episode_url") ?: ""
        val epNum = args?.getInt("episode_number", 0) ?: 0

        currentEpisodeUrl = episodeUrl
        if (episodeUrl.isNotBlank()) {
            animeId = EpisodeProgress.generateAnimeId(episodeUrl)
            episodeNumber = epNum
        }

        referer = args?.getString("referer") ?: embedUrl ?: ""
        if (referer.startsWith("http://")) referer = "https://" + referer.substringAfter("http://")

        useEnhancedMode = VideoEnhanceConfig.isEnabled() || VideoEnhanceConfig.isInterpolationEnabled()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val root = super.onCreateView(inflater, container, savedInstanceState) as ViewGroup
        val content = inflater.inflate(R.layout.fragment_tv_playback, root, false)
        root.addView(content, 1, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        playerContainer = content.findViewById(R.id.tv_player_container)
        loadingText = content.findViewById(R.id.tv_loading)
        fpsBadge = content.findViewById(R.id.tv_fps_badge)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = arguments
        val videoUrl = args?.getString("video_url")
        val embedUrl = args?.getString("embed_url")
        val serverName = args?.getString("server_name") ?: ""
        val megaKeyB64 = args?.getString("mega_key")

        when {
            videoUrl != null && videoUrl.isNotBlank() && megaKeyB64 != null && args != null -> {
                loadingText.visibility = View.GONE
                val resolved = ServerDirectResolver.ResolvedVideo(
                    url = videoUrl,
                    referer = referer,
                    extraHeaders = mapOf("Referer" to if (referer.startsWith("http")) referer else "https://$referer"),
                    needsMegaDecrypt = true,
                    megaKey = android.util.Base64.decode(megaKeyB64, android.util.Base64.NO_WRAP),
                    megaCtrStart = args.getLong("mega_ctr", 0L)
                )
                playVideoMega(resolved)
            }
            videoUrl != null && videoUrl.isNotBlank() -> {
                loadingText.visibility = View.GONE
                playVideo(videoUrl)
            }
            embedUrl != null && embedUrl.isNotBlank() -> {
                loadingText.visibility = View.VISIBLE
                loadingText.text = "Extrayendo video..."
                extractAndPlay(embedUrl, serverName)
            }
            else -> {
                Toast.makeText(requireContext(), "URL de video no disponible", Toast.LENGTH_SHORT).show()
                requireActivity().finish()
            }
        }
    }

    private fun extractAndPlay(embedUrl: String, serverName: String) {
        lifecycleScope.launch {
            try {
                val resolved = ServerDirectResolver.resolve(embedUrl, referer)
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
                        Toast.makeText(requireContext(), "No se pudo extraer el video", Toast.LENGTH_LONG).show()
                        requireActivity().finish()
                    }
                } finally {
                    extractor.destroy()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Extraction error: ${e.message}", e)
                loadingText.visibility = View.GONE
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                requireActivity().finish()
            }
        }
    }

    private fun playVideoMega(resolved: ServerDirectResolver.ResolvedVideo) {
        val key = resolved.megaKey ?: run {
            Toast.makeText(requireContext(), "MEGA key no disponible", Toast.LENGTH_SHORT).show()
            requireActivity().finish()
            return
        }
        val megaFactory = DataSource.Factory {
            MegaDecryptingDataSource(key, resolved.megaCtrStart, resolved.extraHeaders)
        }
        val exoPlayer = ExoPlayer.Builder(requireContext())
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(requireContext()).setDataSourceFactory(megaFactory)
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(PowerManager.PARTIAL_WAKE_LOCK)
            .build()
        player = exoPlayer

        addPlayerView(exoPlayer)
        setupGlue(exoPlayer)
        exoPlayer.setMediaItem(MediaItem.fromUri(resolved.url))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        attachListener(exoPlayer)
    }

    private fun playVideo(url: String) {
        if (useEnhancedMode) {
            playWithEnhancedPipeline(url)
        } else {
            playStandard(url)
        }
    }

    private fun playStandard(url: String) {
        val exoPlayer = ExoPlayer.Builder(requireContext())
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(requireContext()).setDataSourceFactory(VideoDataSource.factory(referer))
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(PowerManager.PARTIAL_WAKE_LOCK)
            .build()
        player = exoPlayer

        addPlayerView(exoPlayer)
        setupGlue(exoPlayer)
        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        attachListener(exoPlayer)
    }

    private fun playWithEnhancedPipeline(url: String) {
        glSurface = android.opengl.GLSurfaceView(requireContext()).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
        }
        playerContainer.addView(glSurface, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        glActive = true

        processor = Media3SixtyFpsProcessor(requireContext(), glSurface, referer)
        processor!!.setupGlPipeline()
        processor!!.onGlFailure = { requireActivity().runOnUiThread { triggerGlFallback() } }

        val exoPlayer = processor!!.createPlayer()
        player = exoPlayer

        processor!!.connectPlayer(exoPlayer)
        setupGlue(exoPlayer)
        processor!!.play(url)
        attachListener(exoPlayer)
        startFpsPolling()

        fallbackHandler.postDelayed({
            if (player != null && processor?.isPipelineReady() != true && !fallbackTriggered) {
                Log.w(TAG, "GL pipeline not ready, falling back to standard surface")
                triggerGlFallback()
            }
        }, 5000)
    }

    private fun addPlayerView(exoPlayer: ExoPlayer) {
        val playerView = PlayerView(requireContext()).apply {
            useController = false
            keepScreenOn = true
            player = exoPlayer
        }
        playerContainer.addView(playerView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun triggerGlFallback() {
        if (fallbackTriggered) return
        fallbackTriggered = true
        Log.w(TAG, "GL pipeline failed, switching to standard surface")
        fpsBadge.visibility = View.GONE
        processor?.release()
        processor = null
        glActive = false
        playerContainer.removeAllViews()
        val exo = player
        if (exo != null) {
            addPlayerView(exo)
        }
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

    private fun setupGlue(exoPlayer: ExoPlayer) {
        val adapter = LeanbackPlayerAdapter(requireContext(), exoPlayer, 500)
        val g = PlaybackTransportControlGlue(requireContext(), adapter)
        g.title = arguments?.getString("video_title") ?: "KarinFLiX"
        g.isSeekEnabled = true
        glue = g
        g.setHost(PlaybackSupportFragmentGlueHost(this))
    }

    private fun attachListener(exoPlayer: ExoPlayer) {
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> loadingText.visibility = View.GONE
                    Player.STATE_ENDED -> onVideoEnded()
                }
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                processor?.renderer?.setVideoSize(videoSize.width, videoSize.height)
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback error: ${error.message}")
                Toast.makeText(requireContext(), "Error: ${error.message}", Toast.LENGTH_LONG).show()
                requireActivity().finish()
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
                            requireContext(),
                            com.karin.streamtv.ui.SiteBrowserActivity::class.java
                        ).apply {
                            putExtra("autoplay_url", nextUrl)
                            putExtra("autoplay_title", "Episodio ${episodeNumber + 1}")
                            putExtra("site_name", arguments?.getString("site_name") ?: "")
                        }
                        startActivity(intent)
                        requireActivity().finish()
                    }
                    override fun onAutoPlayCancelled() {}
                })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (useEnhancedMode && glActive && ::glSurface.isInitialized) glSurface.onResume()
    }

    override fun onStop() {
        super.onStop()
        if (useEnhancedMode && glActive && ::glSurface.isInitialized) glSurface.onPause()
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onPause() {
        saveProgress()
        player?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        fallbackHandler.removeCallbacksAndMessages(null)
        processor?.release()
        processor = null
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
        private const val TAG = "TvPlaybackFragment"
    }
}
