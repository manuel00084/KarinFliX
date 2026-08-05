package com.karin.streamtv.ui.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.karin.streamtv.R
import com.karin.streamtv.player.CodecSelectorFactory
import com.karin.streamtv.player.Media3SixtyFpsProcessor
import com.karin.streamtv.player.MegaDecryptingDataSource
import com.karin.streamtv.player.TvSurfaceCompat
import com.karin.streamtv.player.VideoDataSource
import com.karin.streamtv.player.VideoEnhanceConfig
import com.karin.streamtv.player.VideoEnhanceUi
import com.karin.streamtv.player.ColorProfileUi
import com.karin.streamtv.player.VideoExtractorHelper
import com.karin.streamtv.scraper.ServerDirectResolver
import com.karin.streamtv.util.AutoPlayManager
import com.karin.streamtv.util.EpisodeProgress
import com.karin.streamtv.util.onActionKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class TvPlaybackFragment : Fragment() {

    private var player: ExoPlayer? = null
    private var processor: Media3SixtyFpsProcessor? = null
    private var trackSelector: DefaultTrackSelector? = null
    private lateinit var playerContainer: FrameLayout
    private lateinit var loadingText: TextView
    private lateinit var topBar: View
    private lateinit var tvVideoTitle: TextView
    private lateinit var tvEpisodeInfo: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var fpsBadge: TextView
    private lateinit var tvQueueBadge: TextView
    private lateinit var playStateOverlay: TextView
    private lateinit var controllerPanel: LinearLayout
    private lateinit var centerControls: View
    private lateinit var seekBar: SeekBar
    private lateinit var btnPrev: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnNext: TextView
    private lateinit var tvPosition: TextView
    private lateinit var tvDuration: TextView
    private lateinit var btnVolume: ImageButton
    private lateinit var btnDsp: TextView
    private lateinit var btnUpscaler: TextView
    private lateinit var btnInterp: TextView
    private lateinit var btnVideoProfile: TextView
    private lateinit var btnColorProfile: TextView
    private lateinit var btnQuality: TextView
    private lateinit var btnRewind: ImageButton
    private lateinit var btnForward: ImageButton
    private var seekDragging = false
    private var selectedHeight = -1
    private var playlist: List<com.karin.streamtv.model.PlaylistItem> = emptyList()
    private var playlistIndex = 0
    private val controllerHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            com.karin.streamtv.player.dsp.AudioEnhanceConfig.refreshPlaybackVolume()
            controllerHandler.postDelayed(this, 500)
        }
    }
    private val hideController = Runnable {
        controllerPanel.visibility = View.GONE
        btnBack.visibility = View.GONE
    }

    private val irPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val name = com.karin.streamtv.player.dsp.WavIr.displayName(requireContext().contentResolver, uri)
            val bytes = requireContext().contentResolver.openInputStream(uri)?.readBytes()
            if (bytes != null && com.karin.streamtv.player.dsp.AudioEnhanceConfig.setUserIr(name, bytes)) {
                com.karin.streamtv.player.dsp.AudioEnhanceConfig.setIrPreset(
                    com.karin.streamtv.player.dsp.AudioEnhanceConfig.IrPreset.USER
                )
                android.widget.Toast.makeText(requireContext(), "IR cargado: $name", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(requireContext(), "WAV no soportado (PCM 16/24/32 o float 32)", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (t: Throwable) {
            android.widget.Toast.makeText(requireContext(), "Error al leer el archivo", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    private val hidePlayState = Runnable { playStateOverlay.visibility = View.GONE }
    private lateinit var glSurface: android.opengl.GLSurfaceView
    private var glActive = false
    private var standardPlayerView: androidx.media3.ui.PlayerView? = null

    private var animeId = ""
    private var episodeNumber = 0
    private var currentEpisodeUrl = ""
    private var currentVideoUrl = ""
    private var autoPlayTriggered = false
    private var useEnhancedMode = false
    private var referer = ""
    private var fallbackTriggered = false
    private val fallbackHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        playlist = com.karin.streamtv.util.PlaylistQueue.fromJson(args?.getString("playlist_json"))
        playlistIndex = args?.getInt("playlist_index", 0) ?: 0

        referer = args?.getString("referer") ?: embedUrl ?: ""
        if (referer.startsWith("http://")) referer = "https://" + referer.substringAfter("http://")

        val dbgExtra = args?.getInt("debug_mode", -1) ?: -1
        if (dbgExtra >= 0) VideoEnhanceConfig.setDebugMode(dbgExtra)

        trackSelector = DefaultTrackSelector(requireContext())
        useEnhancedMode = VideoEnhanceConfig.isEnabled() || VideoEnhanceConfig.isInterpolationEnabled()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val content = inflater.inflate(R.layout.fragment_tv_playback, container, false)
        playerContainer = content.findViewById(R.id.tv_player_container)
        loadingText = content.findViewById(R.id.tv_loading)
        topBar = content.findViewById(R.id.top_bar)
        tvVideoTitle = content.findViewById(R.id.tv_video_title)
        tvEpisodeInfo = content.findViewById(R.id.tv_episode_info)
        btnBack = content.findViewById(R.id.btn_back)
        fpsBadge = content.findViewById(R.id.tv_fps_badge)
        tvQueueBadge = content.findViewById(R.id.tv_queue_badge)
        playStateOverlay = content.findViewById(R.id.tv_play_state)
        controllerPanel = content.findViewById(R.id.controller_panel)
        centerControls = content.findViewById(R.id.center_controls)
        seekBar = content.findViewById(R.id.seek_bar)
        btnPlayPause = content.findViewById(R.id.btn_play_pause)
        btnRewind = content.findViewById(R.id.btn_rewind)
        btnForward = content.findViewById(R.id.btn_forward)
        btnPrev = content.findViewById(R.id.btn_prev)
        btnNext = content.findViewById(R.id.btn_next)
        tvPosition = content.findViewById(R.id.tv_position)
        tvDuration = content.findViewById(R.id.tv_duration)
        btnVolume = content.findViewById(R.id.btn_volume)
        btnDsp = content.findViewById(R.id.btn_dsp)
        btnUpscaler = content.findViewById(R.id.btn_upscaler)
        btnInterp = content.findViewById(R.id.btn_interp)
        btnVideoProfile = content.findViewById(R.id.btn_video_profile)
        btnColorProfile = content.findViewById(R.id.btn_color_profile)
        btnQuality = content.findViewById(R.id.btn_quality)

        setupController()

        val videoTitle = arguments?.getString("video_title") ?: ""
        tvVideoTitle.text = videoTitle
        val epNum = arguments?.getInt("episode_number", 0) ?: 0
        if (epNum > 0) {
            tvEpisodeInfo.text = "Ep. $epNum"
            tvEpisodeInfo.visibility = View.VISIBLE
        }

        return content
    }

    private fun setupController() {
        btnBack.setOnClickListener { requireActivity().finish() }
        btnBack.onActionKey { requireActivity().finish() }
        playerContainer.setOnClickListener { showController() }
        playerContainer.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        toggleController()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        showController()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        hideController()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        seekRelative(-10000)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        seekRelative(10000)
                        true
                    }
                    else -> false
                }
            } else false
        }
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
        updateDspButton()
        btnQuality.setOnClickListener { showQualityDialog() }
        btnVolume.setOnClickListener { showVolumeDialog() }
        btnDsp.setOnClickListener { showDspDialog() }
        btnVideoProfile.setOnClickListener { showVideoProfileDialog() }
        updateVideoProfileButton()
        btnColorProfile.setOnClickListener { showColorProfileDialog() }
        updateColorProfileButton()
        btnInterp.setOnClickListener { showInterpolationDialog() }
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
        listOf(btnBack, btnPrev, btnPlayPause, btnNext, btnVolume, btnDsp, btnUpscaler, btnInterp, btnVideoProfile, btnQuality, btnRewind, btnForward)
            .forEach { btn ->
                btn.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        hideController()
                        true
                    } else false
                }
            }
        controllerHandler.post(progressRunnable)
    }

    fun showController() {
        topBar.visibility = View.VISIBLE
        controllerPanel.visibility = View.VISIBLE
        centerControls.visibility = View.VISIBLE
        updateProgress()
        btnPlayPause.requestFocus()
        controllerHandler.removeCallbacks(hideController)
        controllerHandler.postDelayed(hideController, 5000)
    }

    fun hideController() {
        topBar.visibility = View.GONE
        controllerPanel.visibility = View.GONE
        centerControls.visibility = View.GONE
        controllerHandler.removeCallbacks(hideController)
        playerContainer.requestFocus()
    }

    fun toggleController() {
        if (controllerPanel.visibility == View.VISIBLE) hideController() else showController()
    }

    fun isControllerVisible(): Boolean = controllerPanel.visibility == View.VISIBLE

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
        androidx.appcompat.app.AlertDialog.Builder(requireContext(), R.style.DialogTheme)
            .setTitle("Calidad")
            .setSingleChoiceItems(items.toTypedArray(), selectedIdx) { _, which ->
                val ts = trackSelector
                if (ts != null) {
                    if (which == 0) {
                        selectedHeight = -1
                        ts.setParameters(ts.buildUponParameters().setMaxVideoSize(C.LENGTH_UNSET, C.LENGTH_UNSET))
                    } else {
                        val h = sorted[which - 1]
                        selectedHeight = h
                        ts.setParameters(ts.buildUponParameters().setMaxVideoSize(h, C.LENGTH_UNSET))
                    }
                }
                updateQualityButton()
                showController()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun updateQualityButton() {
        btnQuality.text = if (selectedHeight > 0) "${selectedHeight}p" else "Auto"
    }

    private fun showVolumeDialog() {
        val p = player ?: return
        val seek = android.widget.SeekBar(requireContext())
        seek.max = 300
        seek.progress = (p.volume * 100).toInt().coerceIn(10, 300)
        val profilesLink = android.widget.TextView(requireContext()).apply {
            text = "Perfiles de audio (DSP)"
            textSize = 14f
            setTextColor(0xFF4FC3F7.toInt())
            setPadding(0, 20, 0, 0)
        }
        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(seek)
            addView(profilesLink)
        }
        val dlg = androidx.appcompat.app.AlertDialog.Builder(requireContext(), R.style.DialogTheme)
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
        com.karin.streamtv.player.dsp.AudioDspUi.showPresetDialog(requireContext(),
            onAdvanced = {
                com.karin.streamtv.player.dsp.AudioDspUi.showAdvanced(requireContext()) {
                    irPicker.launch(arrayOf("audio/*", "application/octet-stream"))
                }
            },
            onChanged = {
                updateDspButton()
                showController()
            }
        )
    }

    private fun updateDspButton() {
        val preset = com.karin.streamtv.player.dsp.AudioEnhanceConfig.preset()
        val enabled = com.karin.streamtv.player.dsp.AudioEnhanceConfig.isEnabled()
        val auto = com.karin.streamtv.player.dsp.AudioEnhanceConfig.isAutoDevice()
        btnDsp.text = when {
            !enabled || preset == com.karin.streamtv.player.dsp.AudioEnhanceConfig.Preset.OFF ->
                "Sonido: OFF"
            auto -> "Sonido: Auto (${com.karin.streamtv.player.dsp.AudioEnhanceConfig.outputDeviceLabel()})"
            else -> "Sonido: ${preset.label}"
        }
    }

    private fun showVideoProfileDialog() {
        VideoEnhanceUi.showAdvanced(requireContext()) {
            updateVideoProfileButton()
            if (VideoEnhanceConfig.isEnabled() && !useEnhancedMode) {
                useEnhancedMode = true
                restartWithEnhanced()
            } else {
                showController()
            }
        }
    }

    private fun updateVideoProfileButton() {
        btnVideoProfile.text = if (VideoEnhanceConfig.isEnabled()) "Enhancement: ON" else "Enhancement: OFF"
    }

    private fun showColorProfileDialog() {
        ColorProfileUi.show(requireContext()) {
            updateColorProfileButton()
            showController()
        }
    }

    private fun updateColorProfileButton() {
        val p = VideoEnhanceConfig.colorPreset()
        btnColorProfile.text = if (p == VideoEnhanceConfig.ColorPreset.OFF) "Color" else "Color: ${p.label}"
    }

    private fun skipToPlaylist(delta: Int) {
        if (playlist.isEmpty()) {
            Toast.makeText(requireContext(), "No hay lista de reproducción activa", Toast.LENGTH_SHORT).show()
            return
        }
        val target = playlistIndex + delta
        if (target < 0 || target >= playlist.size) {
            Toast.makeText(requireContext(), if (delta > 0) "Fin de la lista" else "Inicio de la lista", Toast.LENGTH_SHORT).show()
            return
        }
        autoPlayTriggered = false
        val siteName = arguments?.getString("site_name") ?: ""
        startActivity(com.karin.streamtv.util.PlaylistQueue.buildIntent(requireContext(), playlist, target, siteName))
        requireActivity().finish()
    }

    private fun showInterpolationDialog() {
        val labels = arrayOf("Apagado") + VideoEnhanceConfig.InterpolationMode.entries.map { it.label }
        val selected = if (!VideoEnhanceConfig.isInterpolationEnabled()) 0
                       else VideoEnhanceConfig.interpolationMode().ordinal + 1
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("MotionX2 60p")
            .setSingleChoiceItems(labels, selected) { _, which ->
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
        if (currentVideoUrl.isBlank()) {
            Toast.makeText(requireContext(), "MotionX2 60p se aplicará al próximo video", Toast.LENGTH_SHORT).show()
            return
        }
        player?.release()
        player = null
        processor?.release()
        processor = null
        playerContainer.removeAllViews()
        glActive = false
        fallbackTriggered = false
        fpsBadge.visibility = View.GONE
        playVideo(currentVideoUrl)
    }

    private fun showUpscalerDialog() {
        val modes = VideoEnhanceConfig.mainUpscalers
        val labels = modes.map { if (it == VideoEnhanceConfig.UpscalerMode.FSR) "FSR…" else it.label }.toTypedArray()
        val current = VideoEnhanceConfig.getUpscalerMode()
        val selectedIdx = if (VideoEnhanceConfig.isFsr(current)) modes.indexOf(VideoEnhanceConfig.UpscalerMode.FSR)
                          else modes.indexOfFirst { it.ordinal == current.ordinal }.coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(requireContext(), R.style.DialogTheme)
            .setTitle("Escalado de Video")
            .setSingleChoiceItems(labels, selectedIdx) { _, which ->
                val chosen = modes[which]
                if (chosen == VideoEnhanceConfig.UpscalerMode.FSR) {
                    showFsrQualityDialog()
                } else {
                    VideoEnhanceConfig.setUpscalerMode(chosen)
                    updateUpscalerButton()
                    showController()
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showFsrQualityDialog() {
        val modes = VideoEnhanceConfig.fsrQualities
        val labels = modes.map { it.label }.toTypedArray()
        val selectedIdx = modes.indexOf(VideoEnhanceConfig.currentFsr()).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(requireContext(), R.style.DialogTheme)
            .setTitle("FSR · Calidad")
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
        btnUpscaler.text = if (VideoEnhanceConfig.isFsr(mode)) "Escala: FSR · ${mode.label.removePrefix("FSR ")}"
                           else "Escala: ${mode.label}"
    }

    private fun applySavedVolume() {
        val p = player ?: return
        val v = com.karin.streamtv.util.AppPreferences.getPlayerVolume()
        p.volume = v
        com.karin.streamtv.player.dsp.AudioEnhanceConfig.setAppVolume(v)
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
        val exoPlayer = ExoPlayer.Builder(requireContext(), CodecSelectorFactory.renderersFactory(requireContext()))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(requireContext()).setDataSourceFactory(megaFactory)
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(PowerManager.PARTIAL_WAKE_LOCK)
            .build()
        player = exoPlayer
        applySavedVolume()

        addPlayerView(exoPlayer)
        exoPlayer.setMediaItem(MediaItem.fromUri(resolved.url))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        attachListener(exoPlayer)
    }

    private fun playVideo(url: String) {
        currentVideoUrl = url
        if (useEnhancedMode) {
            playWithEnhancedPipeline(url)
        } else {
            playStandard(url)
        }
    }

    private fun isLocalUrl(url: String): Boolean =
        url.startsWith("content://") || url.startsWith("file://")

    private fun playStandard(url: String) {
        val exoPlayer = ExoPlayer.Builder(requireContext(), CodecSelectorFactory.renderersFactory(requireContext()))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(requireContext()).setDataSourceFactory(
                    if (isLocalUrl(url)) androidx.media3.datasource.DefaultDataSource.Factory(requireContext())
                    else VideoDataSource.factory(referer)
                )
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(PowerManager.PARTIAL_WAKE_LOCK)
            .build()
        player = exoPlayer
        applySavedVolume()

        addPlayerView(exoPlayer)
        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        attachListener(exoPlayer)
    }

    private fun playWithEnhancedPipeline(url: String) {
        glSurface = android.opengl.GLSurfaceView(requireContext()).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            isFocusable = false
        }
        playerContainer.addView(glSurface, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        glActive = true

        processor = Media3SixtyFpsProcessor(requireContext(), glSurface, referer)
        processor!!.setupGlPipeline()
        processor!!.renderer?.setDebugModeValue(VideoEnhanceConfig.getDebugMode())
        processor!!.onGlFailure = { requireActivity().runOnUiThread { triggerGlFallback() } }

        val exoPlayer = processor!!.createPlayer(
            trackSelector = trackSelector,
            dataSourceFactory = if (isLocalUrl(url))
                androidx.media3.datasource.DefaultDataSource.Factory(requireContext())
            else null
        )
        player = exoPlayer
        applySavedVolume()

        processor!!.connectPlayer(exoPlayer)
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

    private fun addPlayerView(exoPlayer: ExoPlayer): androidx.media3.ui.PlayerView {
        val playerView = PlayerView(requireContext()).apply {
            useController = false
            keepScreenOn = true
            player = exoPlayer
        }
        playerContainer.addView(playerView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        standardPlayerView = playerView
        return playerView
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
                // Android TV fix: force the hardware SurfaceView buffer to panel-native resolution
                val ctx = requireContext()
                val (w, h) = TvSurfaceCompat.idealSurfaceSize(ctx, videoSize.width, videoSize.height)
                val stdView = standardPlayerView
                if (stdView != null) {
                    TvSurfaceCompat.forcePlayerViewSurface(stdView, w, h)
                } else if (::glSurface.isInitialized) {
                    TvSurfaceCompat.forceGlSurfaceSize(glSurface, w, h)
                }
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
        if (!autoPlayTriggered && AutoPlayManager.isAutoPlayEnabled()) {
            autoPlayTriggered = true

            val nextFromQueue = com.karin.streamtv.util.VideoQueue.peek()
            if (nextFromQueue != null) {
                com.karin.streamtv.util.VideoQueue.poll()
                AutoPlayManager.startCountdown(object : AutoPlayManager.AutoPlayCallback {
                    override fun onCountdownTick(sec: Int) {
                        loadingText.visibility = View.VISIBLE
                        loadingText.text = "Siguiente: ${nextFromQueue.title} en ${sec}s"
                    }
                    override fun onCountdownFinish() {
                        val intent = android.content.Intent(
                            requireContext(),
                            com.karin.streamtv.ui.SiteBrowserActivity::class.java
                        ).apply {
                            putExtra("autoplay_url", nextFromQueue.embedUrl)
                            putExtra("autoplay_title", nextFromQueue.title)
                            putExtra("site_name", nextFromQueue.serverName)
                        }
                        startActivity(intent)
                        requireActivity().finish()
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
        saveProgress()
        player?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        controllerHandler.removeCallbacksAndMessages(null)
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
