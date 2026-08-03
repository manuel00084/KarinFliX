package com.karin.streamtv.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.karin.streamtv.R
import com.karin.streamtv.model.Episode
import com.karin.streamtv.model.PlaylistItem
import com.karin.streamtv.model.VideoSource
import com.karin.streamtv.scraper.DynamicParser
import com.karin.streamtv.scraper.ScraperRegistry
import com.karin.streamtv.scraper.ScrapingEngine
import com.karin.streamtv.scraper.ServerExtractor
import com.karin.streamtv.util.DeviceUtils
import com.karin.streamtv.util.DiskImageCache
import com.karin.streamtv.util.GamepadHelper
import com.karin.streamtv.util.PlaylistQueue
import com.karin.streamtv.util.ServerHelper
import com.karin.streamtv.util.onActionKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SeriesDetailActivity : AppCompatActivity() {

    private lateinit var ivCover: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvDescriptionLabel: TextView
    private lateinit var tvDescription: TextView
    private lateinit var tvEpisodesLabel: TextView
    private lateinit var rvEpisodes: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var loadingOverlay: android.widget.FrameLayout
    private lateinit var tvLoadingText: TextView

    private var seriesUrl: String = ""
    private var seriesTitle: String = ""
    private var pendingQueue: List<PlaylistItem>? = null
    private var selectedCount = 0
    private lateinit var btnPlaylist: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series_detail)

        seriesUrl = intent.getStringExtra("series_url") ?: ""
        seriesTitle = intent.getStringExtra("series_title") ?: ""
        siteName = intent.getStringExtra("site_name") ?: ""

        ivCover = findViewById(R.id.iv_cover)
        tvTitle = findViewById(R.id.tv_series_title)
        tvMeta = findViewById(R.id.tv_series_meta)
        tvDescriptionLabel = findViewById(R.id.tv_description_label)
        tvDescription = findViewById(R.id.tv_description)
        tvEpisodesLabel = findViewById(R.id.tv_episodes_label)
        rvEpisodes = findViewById(R.id.rv_episodes)
        tvEmpty = findViewById(R.id.tv_empty)
        loadingOverlay = findViewById(R.id.loading_overlay)
        tvLoadingText = findViewById(R.id.tv_loading_text)

        findViewById<TextView>(R.id.btn_home).setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            finish()
        }
        findViewById<TextView>(R.id.btn_home).onActionKey {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            finish()
        }

        findViewById<TextView>(R.id.btn_directory).setOnClickListener {
            val intent = Intent(this, SiteBrowserActivity::class.java)
            startActivity(intent)
        }
        findViewById<TextView>(R.id.btn_directory).onActionKey {
            val intent = Intent(this, SiteBrowserActivity::class.java)
            startActivity(intent)
        }

        findViewById<TextView>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.btn_settings).onActionKey {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnPlaylist = findViewById(R.id.btn_playlist)
        btnPlaylist.setOnClickListener { onPlaylistButtonClick() }
        btnPlaylist.onActionKey { onPlaylistButtonClick() }

        val isTv = DeviceUtils.isTvDevice(this)
        rvEpisodes.layoutManager = GridLayoutManager(this, if (isTv) 4 else 3)
        if (isTv) {
            // Configurar foco para TV
            rvEpisodes.isFocusable = true
            rvEpisodes.isFocusableInTouchMode = true
            rvEpisodes.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

            // DPAD_UP desde grid -> barra superior
            rvEpisodes.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    val firstVisible = (rvEpisodes.layoutManager as GridLayoutManager).findFirstVisibleItemPosition()
                    if (firstVisible == 0) {
                        findViewById<TextView>(R.id.btn_settings).requestFocus()
                        return@setOnKeyListener true
                    }
                }
                false
            }

            // Navegación DPAD entre barra superior y grid + horizontal en barra
            val topBarButtons = listOf(
                findViewById<TextView>(R.id.btn_home),
                findViewById<TextView>(R.id.btn_directory),
                findViewById<TextView>(R.id.btn_settings),
                btnPlaylist
            )
            topBarButtons.forEachIndexed { index, btn ->
                btn.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                rvEpisodes.requestFocus()
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (index > 0) topBarButtons[index - 1].requestFocus()
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                if (index < topBarButtons.lastIndex) topBarButtons[index + 1].requestFocus()
                                true
                            }
                            else -> false
                        }
                    } else false
                }
            }
        }

        loadSeries()
    }

    private fun loadSeries() {
        showLoading("Cargando serie...")
        lifecycleScope.launch {
            try {
                Log.d("SeriesDetail", "Loading series: $seriesUrl (site=$siteName)")
                val page = withContext(Dispatchers.IO) {
                    val doc = ScrapingEngine.fetch(seriesUrl, siteName, "${siteName}::series::${seriesUrl.hashCode()}")
                    Log.d("SeriesDetail", "Fetch result: doc=${doc != null}, bodyLen=${doc?.body()?.html()?.length ?: 0}")
                    if (doc != null) {
                        val result = DynamicParser.parseSeriesPage(doc, seriesUrl, siteName)
                        Log.d("SeriesDetail", "Parsed: title='${result.title}', episodes=${result.episodes.size}, cover='${result.coverUrl.take(60)}'")
                        if (result.episodes.isEmpty()) {
                            val verLinks = doc.select("a[href*='/ver/']")
                            Log.d("SeriesDetail", "Fallback: found ${verLinks.size} a[href*='/ver/'] links")
                            verLinks.forEach { Log.d("SeriesDetail", "  link: ${it.attr("abs:href")} -> ${it.text().trim().take(50)}") }
                        }
                        result
                    } else null
                }
                loadingOverlay.visibility = android.view.View.GONE

                if (page == null) {
                    Toast.makeText(this@SeriesDetailActivity, "Error al cargar la serie", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }

                displaySeries(page)
            } catch (e: Exception) {
                Log.e("SeriesDetail", "load error: ${e.message}", e)
                loadingOverlay.visibility = android.view.View.GONE
                Toast.makeText(this@SeriesDetailActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun displaySeries(page: DynamicParser.SeriesPage) {
        val resolvedTitle = page.title.ifBlank { seriesTitle }
        tvTitle.text = resolvedTitle

        val metaParts = mutableListOf<String>()
        if (page.type.isNotBlank()) metaParts.add(page.type)
        if (page.status.isNotBlank()) metaParts.add(page.status)
        tvMeta.text = metaParts.joinToString(" • ")

        if (page.coverUrl.isNotBlank()) {
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    DiskImageCache.loadFromNetwork(page.coverUrl, 800, 600)
                }
                if (bmp != null) ivCover.setImageBitmap(bmp)
            }
        }

        if (page.description.isNotBlank()) {
            tvDescriptionLabel.visibility = android.view.View.VISIBLE
            tvDescription.visibility = android.view.View.VISIBLE
            tvDescription.text = page.description
        }

        if (page.episodes.isNotEmpty()) {
            tvEpisodesLabel.visibility = android.view.View.VISIBLE
            rvEpisodes.visibility = android.view.View.VISIBLE
            rvEpisodes.adapter = EpisodeAdapter(page.episodes, seriesUrl) { episode ->
                openEpisode(episode)
            }.apply {
                onSelectionCountChanged = { count ->
                    selectedCount = count
                    updatePlaylistButton()
                }
            }
            if (DeviceUtils.isTvDevice(this)) rvEpisodes.post { rvEpisodes.requestFocus() }
        } else {
            tvEmpty.visibility = android.view.View.VISIBLE
        }
    }

    private var siteName: String = ""
    private var currentEpisodeUrl: String = ""

    private fun openEpisode(episode: Episode) {
        showLoading("Extrayendo servidores de video...")
        currentEpisodeUrl = episode.url
        lifecycleScope.launch {
            try {
                val scraper = ScraperRegistry.getScraper(siteName)
                val servers = withContext(Dispatchers.IO) {
                    scraper?.extractServers(episode.url) ?: ServerExtractor.extractServers(episode.url, siteName)
                }
                loadingOverlay.visibility = android.view.View.GONE

                if (servers.isEmpty()) {
                    openEpisodeDirect(episode)
                    return@launch
                }

                showServerSelectionDialog(servers, episode.title, episode.url)
            } catch (e: Exception) {
                loadingOverlay.visibility = android.view.View.GONE
                openEpisodeDirect(episode)
            }
        }
    }

    private fun openEpisodeDirect(episode: Episode) {
        val intent = Intent(this, EmbedWebViewActivity::class.java).apply {
            putExtra("embed_url", episode.url)
            putExtra("video_title", episode.title)
        }
        attachPlaylist(intent)
        startActivity(intent)
    }

    private fun showServerSelectionDialog(servers: List<VideoSource>, title: String, episodeUrl: String) {
        val sorted = servers.sortedByDescending { it.speedRating }

        val view = layoutInflater.inflate(R.layout.dialog_servers, null)
        val listView = view.findViewById<android.widget.ListView>(R.id.lv_servers)
        val tvCount = view.findViewById<TextView>(R.id.tv_server_count)
        val btnAddToQueue = view.findViewById<TextView>(R.id.btn_add_to_queue)
        val tvQueueCount = view.findViewById<TextView>(R.id.btn_queue_count)
        tvCount.text = "${sorted.size} servidores"

        fun updateQueueBadge() {
            val qSize = com.karin.streamtv.util.VideoQueue.size()
            if (qSize > 0) {
                tvQueueCount.visibility = android.view.View.VISIBLE
                tvQueueCount.text = "Cola: $qSize"
            } else {
                tvQueueCount.visibility = android.view.View.GONE
            }
        }
        updateQueueBadge()

        listView.adapter = ServerAdapter(sorted, title)

        btnAddToQueue.setOnClickListener {
            val best = sorted.firstOrNull()
            val embedUrl = best?.serverUrl ?: episodeUrl
            val item = com.karin.streamtv.model.PlaylistItem(
                title = title,
                url = episodeUrl,
                embedUrl = embedUrl,
                serverName = best?.name ?: siteName,
                episodeNumber = ServerHelper.extractEpisodeNumber(title)
            )
            com.karin.streamtv.util.VideoQueue.add(item)
            updateQueueBadge()
            Toast.makeText(this, "Agregado a la cola (${com.karin.streamtv.util.VideoQueue.size()})", Toast.LENGTH_SHORT).show()
        }
        btnAddToQueue.onActionKey { btnAddToQueue.performClick() }

        val dialog = android.app.AlertDialog.Builder(this, R.style.DialogTheme)
            .setView(view)
            .setNegativeButton("Cancelar", null)
            .create()

        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )

        com.karin.streamtv.util.TvDialogHelper.makeListTvReady(dialog, listView, this)

        dialog.show()

        listView.setOnItemClickListener { _, _, which, _ ->
            dialog.dismiss()
            val server = sorted[which]
            openEmbedWebView(server, title, sorted)
        }
    }

    private fun openEmbedWebView(server: VideoSource, title: String, allServers: List<VideoSource> = emptyList()) {
        val isTabServer = server.serverUrl.contains("?server=")
        val intent = Intent(this, EmbedWebViewActivity::class.java).apply {
            if (isTabServer) {
                val baseUrl = server.serverUrl.substringBefore("?server=")
                val srvName = server.serverUrl.substringAfter("?server=")
                putExtra("embed_url", baseUrl)
                putExtra("server_name", srvName)
            } else {
                putExtra("embed_url", server.serverUrl)
            }
            putExtra("video_title", title)
            putExtra("episode_url", currentEpisodeUrl)
            putExtra("episode_number", ServerHelper.extractEpisodeNumber(title))
            if (allServers.isNotEmpty()) {
                putExtra("all_server_urls", allServers.map { it.serverUrl }.toTypedArray())
                putExtra("all_server_names", allServers.map { it.name }.toTypedArray())
                putExtra("current_server_index", allServers.indexOfFirst { it.serverUrl == server.serverUrl }.coerceAtLeast(0))
            }
        }
        attachPlaylist(intent)
        startActivity(intent)
    }

    private fun onPlaylistButtonClick() {
        val adapter = rvEpisodes.adapter as? EpisodeAdapter ?: return
        if (!adapter.isSelectionMode()) {
            adapter.setSelectionMode(true)
            updatePlaylistButton()
            Toast.makeText(this, "Selecciona los episodios y pulsa ▶ para reproducir", Toast.LENGTH_SHORT).show()
        } else if (adapter.selectedCount() > 0) {
            startPlaylist()
        } else {
            adapter.setSelectionMode(false)
            updatePlaylistButton()
        }
    }

    private fun updatePlaylistButton() {
        val adapter = rvEpisodes.adapter as? EpisodeAdapter ?: return
        btnPlaylist.text = when {
            !adapter.isSelectionMode() -> "📋"
            selectedCount > 0 -> "▶ $selectedCount"
            else -> "📋 ✕"
        }
    }

    private fun startPlaylist() {
        val adapter = rvEpisodes.adapter as? EpisodeAdapter ?: return
        val selected = adapter.getSelectedEpisodes()
        if (selected.isEmpty()) return
        adapter.setSelectionMode(false)
        updatePlaylistButton()
        showLoading("Preparando lista de reproducción...")
        lifecycleScope.launch {
            val queue = withContext(Dispatchers.IO) { buildQueue(selected) }
            loadingOverlay.visibility = android.view.View.GONE
            if (queue.isEmpty()) {
                Toast.makeText(this@SeriesDetailActivity, "No se pudieron resolver los episodios", Toast.LENGTH_LONG).show()
                return@launch
            }
            pendingQueue = queue
            val first = queue.first()
            openEpisode(Episode(title = first.title, url = first.url))
        }
    }

    private suspend fun buildQueue(episodes: List<Episode>): List<PlaylistItem> {
        val scraper = ScraperRegistry.getScraper(siteName)
        return episodes.mapNotNull { ep ->
            try {
                val servers = scraper?.extractServers(ep.url) ?: ServerExtractor.extractServers(ep.url, siteName)
                val best = servers.maxByOrNull { it.speedRating }
                if (best != null) {
                    PlaylistItem(
                        title = ep.title,
                        url = ep.url,
                        embedUrl = if (best.serverUrl.contains("?server=")) best.serverUrl.substringBefore("?server=") else best.serverUrl,
                        serverName = if (best.serverUrl.contains("?server=")) best.serverUrl.substringAfter("?server=") else "",
                        episodeNumber = ServerHelper.extractEpisodeNumber(ep.title)
                    )
                } else {
                    PlaylistItem(
                        title = ep.title,
                        url = ep.url,
                        embedUrl = ep.url,
                        episodeNumber = ServerHelper.extractEpisodeNumber(ep.title)
                    )
                }
            } catch (e: Exception) {
                Log.e("SeriesDetail", "buildQueue error: ${e.message}")
                null
            }
        }
    }

    private fun attachPlaylist(intent: Intent) {
        val queue = pendingQueue ?: return
        intent.putExtra("playlist_json", PlaylistQueue.toJson(queue))
        intent.putExtra("playlist_index", 0)
        pendingQueue = null
    }

    private inner class ServerAdapter(
        private val servers: List<VideoSource>,
        private val title: String
    ) : android.widget.BaseAdapter() {

        override fun getCount(): Int = servers.size
        override fun getItem(position: Int): Any = servers[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
            val row = convertView ?: layoutInflater.inflate(R.layout.item_server, parent, false)

            val server = servers[position]
            val tvName = row.findViewById<android.widget.TextView>(R.id.tv_server_name)
            val tvStars = row.findViewById<android.widget.TextView>(R.id.tv_server_stars)
            val tvRes = row.findViewById<android.widget.TextView>(R.id.tv_res_badge)
            val btnFb = row.findViewById<android.view.View>(R.id.btn_share_fb)
            val btnWa = row.findViewById<android.view.View>(R.id.btn_share_wa)
            val btnExt = row.findViewById<android.widget.TextView>(R.id.btn_play_external)

            val stars = "\u2605".repeat(server.speedRating.coerceIn(1, 5))
            val fastTag = if (server.speedRating >= 4) " \u26A1" else ""
            tvName.text = "${server.name}$fastTag"
            tvStars.text = stars
            tvRes.visibility = if (server.supportsResolutionChange) android.view.View.VISIBLE else android.view.View.GONE

            val tvPlayer = row.findViewById<android.widget.TextView>(R.id.tv_player_badge)
            val usesHttp = com.karin.streamtv.scraper.ServerDirectResolver.usesHttpResolver(server.serverUrl)
            tvPlayer.visibility = android.view.View.VISIBLE
            tvPlayer.text = if (usesHttp) "⚡ ExoPlayer" else "🌐 WebView"
            tvPlayer.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 6f
                setColor(if (usesHttp) android.graphics.Color.parseColor("#7C3AED") else android.graphics.Color.parseColor("#475569"))
            }

            row.setOnClickListener { openEmbedWebView(server, title, servers) }

            btnFb.setOnClickListener {
                ServerHelper.shareUrl(this@SeriesDetailActivity, server.serverUrl, server.name, "com.facebook.katana")
            }
            btnWa.setOnClickListener {
                ServerHelper.shareUrl(this@SeriesDetailActivity, server.serverUrl, server.name, "com.whatsapp")
            }
            btnExt.setOnClickListener {
                val isTabServer = server.serverUrl.contains("?server=")
                val intent = Intent(this@SeriesDetailActivity, EmbedWebViewActivity::class.java).apply {
                    if (isTabServer) {
                        val baseUrl = server.serverUrl.substringBefore("?server=")
                        val srvName = server.serverUrl.substringAfter("?server=")
                        putExtra("embed_url", baseUrl)
                        putExtra("server_name", srvName)
                    } else {
                        putExtra("embed_url", server.serverUrl)
                    }
                    putExtra("video_title", title)
                    putExtra("episode_url", currentEpisodeUrl)
                    putExtra("episode_number", ServerHelper.extractEpisodeNumber(title))
                    putExtra("open_external", true)
                }
                startActivity(intent)
            }

            if (DeviceUtils.isTvDevice(this@SeriesDetailActivity)) {
                btnFb.isFocusable = false
                btnWa.isFocusable = false
                btnExt.isFocusable = false
            }

            return row
        }
    }

    private fun showLoading(text: String) {
        tvLoadingText.text = text
        loadingOverlay.visibility = android.view.View.VISIBLE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val mapped = GamepadHelper.mapGamepadToDpad(keyCode)
        if (mapped != keyCode) return onKeyDown(mapped, event)
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
