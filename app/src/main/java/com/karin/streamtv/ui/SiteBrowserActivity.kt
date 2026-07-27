package com.karin.streamtv.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.util.Log
import com.karin.streamtv.R
import com.karin.streamtv.model.Episode
import com.karin.streamtv.model.SiteMenuItem
import com.karin.streamtv.model.VideoServer
import com.karin.streamtv.model.VideoSource
import com.karin.streamtv.player.PlayerActivity
import com.karin.streamtv.scraper.DynamicParser
import com.karin.streamtv.scraper.MenuParser
import com.karin.streamtv.scraper.ScrapingEngine
import com.karin.streamtv.scraper.ScraperRegistry
import com.karin.streamtv.util.AppPreferences
import com.karin.streamtv.util.DeviceUtils
import com.karin.streamtv.util.EpisodeProgress
import com.karin.streamtv.util.ExtractionLogger
import com.karin.streamtv.util.VideoExtractor
import com.karin.streamtv.util.WebViewExtractor
import com.karin.streamtv.util.onActionKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SiteBrowserActivity : AppCompatActivity() {

    private lateinit var rvEpisodes: RecyclerView
    private lateinit var rvMenu: RecyclerView
    private lateinit var tvTitle: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnSearch: TextView
    private lateinit var loadingOverlay: android.widget.FrameLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvLoadingText: TextView
    private lateinit var btnCancelLoading: TextView
    private lateinit var btnNextPage: TextView
    private lateinit var btnPrevPage: TextView

    private var siteName: String = ""
    private var siteUrl: String = ""
    private var menuItems: List<SiteMenuItem> = emptyList()
    private var showingSearchResults = false
    private var lastSearchQuery = ""
    private var currentEpisodes: ArrayList<Episode> = arrayListOf()
    private var currentSeriesName: String = ""
    private var currentPageUrl: String = ""
    private var nextPageUrl: String? = null
    private var prevPageUrl: String? = null
    private var isLoadingPage: Boolean = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_site_browser)

        WebViewExtractor.init(this)

        siteName = intent.getStringExtra("site_name") ?: ""
        siteUrl = intent.getStringExtra("site_url") ?: ""

        rvEpisodes = findViewById(R.id.rv_episodes)
        rvMenu = findViewById(R.id.rv_menu)
        tvTitle = findViewById(R.id.tv_site_title)
        etSearch = findViewById(R.id.et_search)
        btnSearch = findViewById(R.id.btn_search)
        loadingOverlay = findViewById(R.id.loading_overlay)
        tvEmpty = findViewById(R.id.tv_empty)
        tvLoadingText = findViewById(R.id.tv_loading_text)
        btnCancelLoading = findViewById(R.id.btn_cancel_loading)
        btnCancelLoading.setOnClickListener { cancelExtractionAndOpenWebView() }
        btnCancelLoading.onActionKey { cancelExtractionAndOpenWebView() }

        btnNextPage = findViewById(R.id.btn_next_page)
        btnNextPage.setOnClickListener { loadNextPage() }
        btnNextPage.onActionKey { loadNextPage() }

        btnPrevPage = findViewById(R.id.btn_prev_page)
        btnPrevPage.setOnClickListener { loadPrevPage() }
        btnPrevPage.onActionKey { loadPrevPage() }

        tvTitle.text = siteName

        val isTv = DeviceUtils.isTvDevice(this)
        rvEpisodes.layoutManager = LinearLayoutManager(this)
        rvEpisodes.setHasFixedSize(true)
        if (isTv) {
            etSearch.isFocusableInTouchMode = false
        }

        rvMenu.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        btnSearch.setOnClickListener { performSearch() }
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else false
        }

        findViewById<android.view.View>(R.id.btn_voice).setOnClickListener {
            com.karin.streamtv.util.VoiceSearchHelper.startVoiceSearch(this)
        }

        findViewById<android.view.View>(R.id.btn_share).setOnClickListener {
            shareCurrentSite()
        }
        findViewById<android.view.View>(R.id.btn_karinlink).setOnClickListener {
            startActivity(Intent(this, com.karin.streamtv.karinlink.KarinLinkActivity::class.java))
        }

        ScrapingEngine.onMetrics = { metrics ->
            Log.d("SiteBrowser", "[${metrics.site}] ${metrics.url.takeLast(40)} " +
                    "cached=${metrics.cached} attempts=${metrics.attempts} " +
                    "duration=${metrics.durationMs}ms success=${metrics.success}")
        }

        val autoPlayUrl = intent.getStringExtra("autoplay_url")
        val autoPlayTitle = intent.getStringExtra("autoplay_title")
        if (!autoPlayUrl.isNullOrBlank()) {
            val ep = Episode(autoPlayTitle ?: "Episodio", autoPlayUrl, siteName = siteName)
            loadHomepageAndAutoPlay(ep)
        } else {
            loadHomepage()
        }
    }

    private fun loadHomepage() {
        showingSearchResults = false
        lastSearchQuery = ""
        etSearch.text.clear()
        showLoading("Cargando episodios...")
        nextPageUrl = null
        prevPageUrl = null
        btnNextPage.visibility = android.view.View.GONE
        btnPrevPage.visibility = android.view.View.GONE

        lifecycleScope.launch {
            try {
                val episodesDeferred = async(Dispatchers.IO) {
                    val scraper = ScraperRegistry.getScraper(siteName)
                    if (scraper != null) scraper.getLatestEpisodes() else emptyList()
                }

                val menuDeferred = async(Dispatchers.IO) {
                    val doc = ScrapingEngine.fetch(siteUrl, siteName, "${siteName}::home", forceFresh = false)
                    if (doc != null) Pair(MenuParser.extractMenu(doc), doc) else Pair(emptyList(), null)
                }

                val episodes = episodesDeferred.await()
                val (extractedMenu, homeDoc) = menuDeferred.await()

                loadingOverlay.visibility = android.view.View.GONE

                currentEpisodes = ArrayList(episodes)
                currentPageUrl = siteUrl
                menuItems = extractedMenu
                if (extractedMenu.isNotEmpty()) {
                    rvMenu.visibility = android.view.View.VISIBLE
                    rvMenu.adapter = MenuAdapter(extractedMenu) { item ->
                        onMenuItemClick(item)
                    }
                }

                if (homeDoc != null) {
                    nextPageUrl = DynamicParser.findNextPageUrl(homeDoc, siteUrl)
                    btnNextPage.visibility = if (nextPageUrl != null) android.view.View.VISIBLE else android.view.View.GONE
                    prevPageUrl = null
                    btnPrevPage.visibility = android.view.View.GONE
                }

                if (episodes.isEmpty()) {
                    tvEmpty.visibility = android.view.View.VISIBLE
                    tvEmpty.text = "No se pudieron cargar episodios de $siteName"
                    return@launch
                }

                rvEpisodes.adapter = EpisodeAdapter(episodes, siteUrl) { episode ->
                    openEpisode(episode)
                }
            } catch (e: Exception) {
                Log.e("SiteBrowser", "loadHomepage error: ${e.message}", e)
                loadingOverlay.visibility = android.view.View.GONE
                tvEmpty.visibility = android.view.View.VISIBLE
                tvEmpty.text = "Error al cargar episodios"
            }
        }
    }

    private fun loadHomepageAndAutoPlay(autoPlayEpisode: Episode) {
        showLoading("Cargando siguiente episodio...")

        lifecycleScope.launch {
            try {
                val episodes = withContext(Dispatchers.IO) {
                    val scraper = ScraperRegistry.getScraper(siteName)
                    if (scraper != null) scraper.getLatestEpisodes() else emptyList()
                }

                currentEpisodes = ArrayList(episodes)
                loadingOverlay.visibility = android.view.View.GONE

                openEpisode(autoPlayEpisode)
            } catch (e: Exception) {
                Log.e("SiteBrowser", "loadHomepageAndAutoPlay error: ${e.message}", e)
                loadingOverlay.visibility = android.view.View.GONE
                tvEmpty.visibility = android.view.View.VISIBLE
                tvEmpty.text = "Error al cargar episodios"
            }
        }
    }

    private fun performSearch() {
        val query = etSearch.text.toString().trim()
        if (query.isEmpty()) {
            // Empty query = back to homepage
            tvTitle.text = siteName
            loadHomepage()
            return
        }
        if (query == lastSearchQuery && showingSearchResults) return // already showing
        lastSearchQuery = query
        showingSearchResults = true
        tvTitle.text = "$siteName - Buscar: $query"
        showLoading("Buscando...")
        tvEmpty.visibility = android.view.View.GONE

        lifecycleScope.launch {
            try {
                val episodes = withContext(Dispatchers.IO) {
                    val scraper = ScraperRegistry.getScraper(siteName)
                    if (scraper != null) scraper.search(query) else emptyList()
                }

                loadingOverlay.visibility = android.view.View.GONE

                if (episodes.isEmpty()) {
                    tvEmpty.visibility = android.view.View.VISIBLE
                    tvEmpty.text = "Sin resultados para '$query'"
                    return@launch
                }

                rvEpisodes.adapter = EpisodeAdapter(episodes, siteUrl) { episode ->
                    openEpisode(episode)
                }
            } catch (e: Exception) {
                Log.e("SiteBrowser", "performSearch error: ${e.message}", e)
                loadingOverlay.visibility = android.view.View.GONE
                tvEmpty.visibility = android.view.View.VISIBLE
                tvEmpty.text = "Error al buscar"
            }
        }
    }

    private fun onMenuItemClick(item: SiteMenuItem) {
        if (item.section == com.karin.streamtv.model.MenuSection.SCHEDULE &&
            (item.name.contains("calendario", ignoreCase = true) ||
             item.url.contains("calendario", ignoreCase = true))) {
            startActivity(Intent(this, CalendarActivity::class.java))
            return
        }

        showingSearchResults = false
        lastSearchQuery = ""
        etSearch.text.clear()
        showLoading("Cargando ${item.name}...")
        tvEmpty.visibility = android.view.View.GONE

        lifecycleScope.launch {
            try {
                val (episodes, doc) = withContext(Dispatchers.IO) {
                    val doc = ScrapingEngine.fetch(item.url, siteName, "${siteName}::${item.name}")
                    val eps = if (doc != null) DynamicParser.parseDynamic(doc, siteName) else emptyList()
                    Pair(eps, doc)
                }

                loadingOverlay.visibility = android.view.View.GONE

                if (episodes.isEmpty()) {
                    tvEmpty.visibility = android.view.View.VISIBLE
                    tvEmpty.text = "No hay contenido en '${item.name}'"
                    return@launch
                }

                tvTitle.text = "${siteName} - ${item.name}"
                currentSeriesName = item.name
                currentPageUrl = item.url
                currentEpisodes = ArrayList(episodes)
                rvEpisodes.adapter = EpisodeAdapter(episodes, siteUrl) { episode ->
                    openEpisode(episode)
                }

                if (doc != null) {
                    nextPageUrl = DynamicParser.findNextPageUrl(doc, item.url)
                    btnNextPage.visibility = if (nextPageUrl != null) android.view.View.VISIBLE else android.view.View.GONE
                    prevPageUrl = DynamicParser.findPrevPageUrl(doc, item.url)
                    btnPrevPage.visibility = if (prevPageUrl != null) android.view.View.VISIBLE else android.view.View.GONE
                }
            } catch (e: Exception) {
                Log.e("SiteBrowser", "onMenuItemClick error: ${e.message}", e)
                loadingOverlay.visibility = android.view.View.GONE
                tvEmpty.visibility = android.view.View.VISIBLE
                tvEmpty.text = "Error al cargar '${item.name}'"
            }
        }
    }

    private fun loadNextPage() {
        val url = nextPageUrl ?: return
        if (isLoadingPage) return
        isLoadingPage = true
        showLoading("Cargando siguiente pagina...")

        lifecycleScope.launch {
            try {
                val (newEpisodes, doc) = withContext(Dispatchers.IO) {
                    val doc = ScrapingEngine.fetch(url, siteName, "${siteName}::page::${url.takeLast(80)}")
                    val eps = if (doc != null) {
                        val links = DynamicParser.parseEpisodeLinks(doc, siteName)
                        if (links.isNotEmpty()) links else DynamicParser.parseDynamic(doc, siteName)
                    } else emptyList()
                    Pair(eps, doc)
                }

                loadingOverlay.visibility = android.view.View.GONE
                isLoadingPage = false

                if (newEpisodes.isEmpty()) {
                    Toast.makeText(this@SiteBrowserActivity, "No hay mas contenido", Toast.LENGTH_SHORT).show()
                    btnNextPage.visibility = android.view.View.GONE
                    return@launch
                }

                currentPageUrl = url
                currentEpisodes = ArrayList(newEpisodes)
                rvEpisodes.adapter = EpisodeAdapter(currentEpisodes, siteUrl) { episode ->
                    openEpisode(episode)
                }
                rvEpisodes.scrollToPosition(0)

                val pageNum = Regex("""[?&]p=(\d+)""").find(url)?.groupValues?.get(1) ?: "?"
                tvTitle.text = "${siteName} - Pagina $pageNum"

                if (doc != null) {
                    nextPageUrl = DynamicParser.findNextPageUrl(doc, url)
                    btnNextPage.visibility = if (nextPageUrl != null) android.view.View.VISIBLE else android.view.View.GONE
                    prevPageUrl = DynamicParser.findPrevPageUrl(doc, url)
                    btnPrevPage.visibility = if (prevPageUrl != null) android.view.View.VISIBLE else android.view.View.GONE
                }
            } catch (e: Exception) {
                Log.e("SiteBrowser", "loadNextPage error: ${e.message}", e)
                loadingOverlay.visibility = android.view.View.GONE
                isLoadingPage = false
                Toast.makeText(this@SiteBrowserActivity, "Error al cargar siguiente pagina", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadPrevPage() {
        val url = prevPageUrl ?: return
        if (isLoadingPage) return
        isLoadingPage = true
        showLoading("Cargando pagina anterior...")

        lifecycleScope.launch {
            try {
                val (newEpisodes, doc) = withContext(Dispatchers.IO) {
                    val doc = ScrapingEngine.fetch(url, siteName, "${siteName}::page::${url.takeLast(80)}")
                    val eps = if (doc != null) {
                        val links = DynamicParser.parseEpisodeLinks(doc, siteName)
                        if (links.isNotEmpty()) links else DynamicParser.parseDynamic(doc, siteName)
                    } else emptyList()
                    Pair(eps, doc)
                }

                loadingOverlay.visibility = android.view.View.GONE
                isLoadingPage = false

                if (newEpisodes.isEmpty()) {
                    Toast.makeText(this@SiteBrowserActivity, "No hay contenido", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                currentPageUrl = url
                currentEpisodes = ArrayList(newEpisodes)
                rvEpisodes.adapter = EpisodeAdapter(currentEpisodes, siteUrl) { episode ->
                    openEpisode(episode)
                }
                rvEpisodes.scrollToPosition(0)

                val pageNum = Regex("""[?&]p=(\d+)""").find(url)?.groupValues?.get(1) ?: "1"
                tvTitle.text = "${siteName} - Pagina $pageNum"

                if (doc != null) {
                    nextPageUrl = DynamicParser.findNextPageUrl(doc, url)
                    btnNextPage.visibility = if (nextPageUrl != null) android.view.View.VISIBLE else android.view.View.GONE

                    prevPageUrl = DynamicParser.findPrevPageUrl(doc, url)
                    btnPrevPage.visibility = if (prevPageUrl != null) android.view.View.VISIBLE else android.view.View.GONE
                }
            } catch (e: Exception) {
                Log.e("SiteBrowser", "loadPrevPage error: ${e.message}", e)
                loadingOverlay.visibility = android.view.View.GONE
                isLoadingPage = false
                Toast.makeText(this@SiteBrowserActivity, "Error al cargar pagina anterior", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showLoading(text: String) {
        tvLoadingText.text = text
        loadingOverlay.visibility = android.view.View.VISIBLE
        tvLoadingText.announceForAccessibility(text)
        val ivImage = findViewById<android.widget.ImageView>(R.id.iv_loading_image)
        if (ivImage != null) {
            val isExtracting = text.startsWith("Extrayendo")
            ivImage.visibility = if (isExtracting) android.view.View.VISIBLE else android.view.View.GONE
            tvLoadingText.visibility = if (isExtracting) android.view.View.GONE else android.view.View.VISIBLE
            findViewById<android.view.View>(R.id.progress_bar)?.visibility = if (isExtracting) android.view.View.GONE else android.view.View.VISIBLE
        }
    }

    private fun openEpisode(episode: Episode) {
        Log.d("SiteBrowser", "Opening: ${episode.title} | ${episode.url}")

        lifecycleScope.launch {
            try {
                showLoading("Cargando...")

                val pageDoc = withContext(Dispatchers.IO) {
                    try {
                        ScrapingEngine.fetch(
                            episode.url, siteName,
                            "${siteName}::page::${episode.url.takeLast(80)}",
                            forceFresh = false
                        )
                    } catch (e: Exception) {
                        Log.w("SiteBrowser", "Page fetch failed: ${e.message}")
                        null
                    }
                }

                if (pageDoc != null) {
                    if (!DynamicParser.isEpisodeUrl(episode.url)) {
                        val linkEpisodes = DynamicParser.parseEpisodeLinks(pageDoc, siteName)
                        if (linkEpisodes.isNotEmpty()) {
                            Log.d("SiteBrowser", "Series page detected via episode links: ${episode.title} → ${linkEpisodes.size} episodes")
                            loadingOverlay.visibility = android.view.View.GONE
                            tvTitle.text = episode.title
                            currentSeriesName = episode.title
                            currentEpisodes = ArrayList(linkEpisodes)
                            currentPageUrl = episode.url
                            nextPageUrl = DynamicParser.findNextPageUrl(pageDoc, episode.url)
                            btnNextPage.visibility = if (nextPageUrl != null) android.view.View.VISIBLE else android.view.View.GONE
                            prevPageUrl = null
                            btnPrevPage.visibility = android.view.View.GONE
                            rvEpisodes.adapter = EpisodeAdapter(linkEpisodes, siteUrl) { ep -> openEpisode(ep) }
                            return@launch
                        }
                    }

                    val cardEpisodes = DynamicParser.parseDynamic(pageDoc, siteName, minCards = 2)
                    if (cardEpisodes.isNotEmpty()) {
                        Log.d("SiteBrowser", "Series page detected via cards: ${episode.title} → ${cardEpisodes.size} episodes")
                        loadingOverlay.visibility = android.view.View.GONE
                        tvTitle.text = episode.title
                        currentSeriesName = episode.title
                        currentEpisodes = ArrayList(cardEpisodes)
                        currentPageUrl = episode.url
                        nextPageUrl = DynamicParser.findNextPageUrl(pageDoc, episode.url)
                        btnNextPage.visibility = if (nextPageUrl != null) android.view.View.VISIBLE else android.view.View.GONE
                        prevPageUrl = null
                        btnPrevPage.visibility = android.view.View.GONE
                        rvEpisodes.adapter = EpisodeAdapter(cardEpisodes, siteUrl) { ep -> openEpisode(ep) }
                        return@launch
                    }
                }

                showLoading("Extrayendo servidores de video...")
                val sources = withContext(Dispatchers.IO) {
                    ExtractionLogger.init(siteName, episode.url)
                    VideoExtractor.extractSourcesFromPage(episode.url, siteName)
                }

                if (sources.isEmpty()) {
                    loadingOverlay.visibility = android.view.View.GONE
                    Log.d("SiteBrowser", "No servers found, opening in WebView: ${episode.url.takeLast(80)}")
                    val intent = Intent(this@SiteBrowserActivity, EmbedWebViewActivity::class.java).apply {
                        putExtra("embed_url", episode.url)
                        putExtra("video_title", episode.title)
                    }
                    startActivity(intent)
                    return@launch
                }

                loadingOverlay.visibility = android.view.View.GONE
                showServerPicker(episode, sources)
            } catch (e: Exception) {
                Log.e("SiteBrowser", "Error: ${e.message}")
                loadingOverlay.visibility = android.view.View.GONE
                Toast.makeText(this@SiteBrowserActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showServerPicker(episode: Episode, sources: List<VideoSource>) {
        SourcePickerDialog(
            context = this@SiteBrowserActivity,
            sources = sources,
            episodeTitle = episode.title,
            episodeUrl = episode.url,
            siteName = siteName,
            onSourceSelected = { source -> playVideo(episode, source, sources) }
        ).show()
    }

    private var currentEpisodeForFallback: Episode? = null
    private var extractionCancelled: Boolean = false
    @Volatile private var isPlayingVideo: Boolean = false
    private var lastPickerEpisode: Episode? = null
    private var lastPickerSources: List<VideoSource> = emptyList()

    private var pendingWebViewFallback: Boolean = false
    private var pendingFallbackEpisode: Episode? = null
    private var pendingFallbackSource: VideoSource? = null
    private var pendingFallbackAllSources: List<VideoSource> = emptyList()

    private fun playVideo(episode: Episode, source: VideoSource, allSources: List<VideoSource> = listOf(source)) {
        if (isPlayingVideo) {
            Log.w("SiteBrowser", "playVideo already in progress, ignoring duplicate call for ${source.name}")
            return
        }
        isPlayingVideo = true

        val server = VideoServer.detectServer(source.serverUrl)
        if (server.webViewOnly) {
            Log.d("SiteBrowser", "WebView-only server: ${source.name} — opening WebView directly")
            loadingOverlay.visibility = android.view.View.GONE
            isPlayingVideo = false

            val isDoramasYtReproductor = source.serverUrl.contains("doramasyt.com/reproductor", ignoreCase = true)
            if (isDoramasYtReproductor) {
                lifecycleScope.launch {
                    try {
                        showLoading("Extrayendo enlace real de ${source.name}...")
                        val realEmbedUrl = withContext(Dispatchers.IO) {
                            extractEmbedFromDoramasYtReproductor(source.serverUrl)
                        }
                        loadingOverlay.visibility = android.view.View.GONE
                        val urlToLoad = realEmbedUrl ?: source.serverUrl
                        Log.d("SiteBrowser", "DoramasYT reproductor: ${if (realEmbedUrl != null) "extracted embed" else "fallback to reproductor URL"}")
                        val intent = Intent(this@SiteBrowserActivity, EmbedWebViewActivity::class.java).apply {
                            putExtra("embed_url", urlToLoad)
                            putExtra("video_title", episode.title)
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.e("SiteBrowser", "DoramasYT extraction error: ${e.message}", e)
                        loadingOverlay.visibility = android.view.View.GONE
                        Toast.makeText(this@SiteBrowserActivity, "Error al abrir servidor", Toast.LENGTH_SHORT).show()
                        showServerPicker(episode, allSources)
                    }
                }
                return
            }

            try {
                val intent = Intent(this@SiteBrowserActivity, EmbedWebViewActivity::class.java).apply {
                    putExtra("embed_url", source.serverUrl)
                    putExtra("video_title", episode.title)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("SiteBrowser", "Failed to open WebView: ${e.message}", e)
                Toast.makeText(this, "Error al abrir servidor", Toast.LENGTH_SHORT).show()
                showServerPicker(episode, allSources)
            }
            return
        }

        val remaining = allSources.filter { it.serverUrl != source.serverUrl }
        Log.d("SiteBrowser", "playVideo: ${source.name} | server=${source.serverUrl.takeLast(60)} | remaining=${remaining.size}/${allSources.size}")
        currentEpisodeForFallback = episode

        lifecycleScope.launch {
            try {
                showLoading("Extrayendo video de ${source.name}...")

                // Show "Abrir en WebView" button after 8 seconds
                btnCancelLoading.visibility = android.view.View.GONE
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({
                    if (loadingOverlay.visibility == android.view.View.VISIBLE) {
                        btnCancelLoading.visibility = android.view.View.VISIBLE
                    }
                }, 8000)

                extractionCancelled = false
                val directUrl = try {
                    withContext(Dispatchers.IO) {
                        val isMega = source.serverUrl.contains("mega.nz", ignoreCase = true) || source.serverUrl.contains("mega.co", ignoreCase = true)
                        kotlinx.coroutines.withTimeoutOrNull(if (isMega) 60_000L else 15_000L) {
                            VideoExtractor.extractDirectVideoUrl(source.serverUrl, this@SiteBrowserActivity)
                        }
                    }
                } catch (e: Throwable) {
                    Log.e("SiteBrowser", "Extraction crashed: ${e.message}", e)
                    null
                }

                btnCancelLoading.visibility = android.view.View.GONE

                if (extractionCancelled) {
                    Log.d("SiteBrowser", "Extraction cancelled by user")
                    loadingOverlay.visibility = android.view.View.GONE
                    isPlayingVideo = false
                    return@launch
                }

                // Save extraction log after attempt
                try {
                    withContext(Dispatchers.IO) {
                        val logPath = ExtractionLogger.save(this@SiteBrowserActivity)
                        if (logPath != null) Log.d("SiteBrowser", "Extraction log saved: $logPath")
                    }
                } catch (e: Exception) {
                    Log.e("SiteBrowser", "Failed to save extraction log: ${e.message}")
                }

                if (directUrl != null) {
                    Log.d("SiteBrowser", "SUCCESS: extracted URL from ${source.name}: ${directUrl.take(120)}")
                    pendingWebViewFallback = false
                    loadingOverlay.visibility = android.view.View.GONE
                    isPlayingVideo = false
                    val detectedType = when {
                        directUrl.contains(".m3u8") || directUrl.contains(".m3u") -> "M3U8"
                        directUrl.contains(".mpd") -> "DASH"
                        directUrl.contains(".mp4") || directUrl.contains(".webm") -> "MP4"
                        directUrl.startsWith("file://") -> "MP4"
                        else -> "MP4"
                    }
                    val epNum = episode.episodeNum.toIntOrNull() ?: extractEpisodeNumber(episode.title)
                    val animeId = EpisodeProgress.generateAnimeId(episode.url)
                    val seriesForAniSkip = currentSeriesName.ifBlank { episode.title }
                    val intent = Intent(this@SiteBrowserActivity, PlayerActivity::class.java).apply {
                        putExtra("video_url", directUrl)
                        putExtra("video_title", episode.title)
                        putExtra("video_type", detectedType)
                        putExtra("episode_url", episode.url)
                        putExtra("episode_number", epNum)
                        putExtra("anime_id", animeId)
                        putExtra("series_name", seriesForAniSkip)
                        putExtra("referer", source.serverUrl)
                        putExtra("site_name", siteName)
                        putExtra("site_url", siteUrl)
                        putExtra("current_index", currentEpisodes.indexOfFirst { it.url == episode.url })
                        putParcelableArrayListExtra("episode_list", ArrayList(currentEpisodes))
                    }
                    startActivity(intent)
                    return@launch
                }

                Log.w("SiteBrowser", "FAILED to extract from ${source.name} (${source.serverUrl.takeLast(50)})")
                Log.d("SiteBrowser", "ExoPlayer failed → trying WebView fallback for ${source.name}")
                pendingWebViewFallback = true
                pendingFallbackEpisode = episode
                pendingFallbackSource = source
                pendingFallbackAllSources = allSources
                loadingOverlay.visibility = android.view.View.GONE
                isPlayingVideo = false
                Toast.makeText(this@SiteBrowserActivity, "Extrayendo falló, abriendo en WebView...", Toast.LENGTH_SHORT).show()
                val webViewIntent = Intent(this@SiteBrowserActivity, EmbedWebViewActivity::class.java).apply {
                    putExtra("embed_url", source.serverUrl)
                    putExtra("video_title", episode.title)
                }
                startActivity(webViewIntent)
            } catch (e: Exception) {
                Log.e("SiteBrowser", "playVideo error: ${e.message}", e)
                loadingOverlay.visibility = android.view.View.GONE
                isPlayingVideo = false
                Toast.makeText(this@SiteBrowserActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                showServerPicker(episode, allSources)
            }
        }
    }

    private fun cancelExtractionAndOpenWebView() {
        extractionCancelled = true
        btnCancelLoading.visibility = android.view.View.GONE
        loadingOverlay.visibility = android.view.View.GONE
        val ep = currentEpisodeForFallback ?: return
        Toast.makeText(this, "Abriendo en WebView...", Toast.LENGTH_SHORT).show()
        val intent = Intent(this, EmbedWebViewActivity::class.java).apply {
            putExtra("embed_url", ep.url)
            putExtra("video_title", ep.title)
        }
        startActivity(intent)
    }

    private suspend fun extractEmbedFromDoramasYtReproductor(reproductorUrl: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val doc = ScrapingEngine.fetch(reproductorUrl, "SiteBrowser", reproductorUrl) ?: return@withContext null

                val iframeSrc = doc.selectFirst("iframe[src]")?.let { iframe ->
                    val src = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
                    if (src.isNotBlank() && src != reproductorUrl) src else null
                }

                if (iframeSrc != null) {
                    Log.d("SiteBrowser", "DoramasYT: extracted iframe from reproductor: ${iframeSrc.takeLast(80)}")
                    return@withContext iframeSrc
                }

                val dataPlayer = doc.selectFirst("[data-player]")?.attr("data-player") ?: ""
                val dataVideo = doc.selectFirst("[data-video]")?.attr("data-video") ?: ""
                val dataSrc = doc.selectFirst("[data-src]")?.attr("data-src") ?: ""
                val dataUrl = doc.selectFirst("[data-url]")?.attr("data-url") ?: ""

                val embeddedUrl = listOf(dataPlayer, dataVideo, dataSrc, dataUrl).firstOrNull { it.isNotBlank() }
                if (embeddedUrl != null) {
                    val resolved = try {
                        if (embeddedUrl.startsWith("http")) embeddedUrl
                        else java.net.URL(java.net.URL(reproductorUrl), embeddedUrl).toExternalForm()
                    } catch (_: Exception) { null }
                    if (resolved != null) {
                        Log.d("SiteBrowser", "DoramasYT: extracted data attr from reproductor: ${resolved.takeLast(80)}")
                        return@withContext resolved
                    }
                }

                val srcPattern = Regex("""(?:src|source|file)\s*[:=]\s*['"]?(https?://[^'"\s;]+)""", RegexOption.IGNORE_CASE)
                val match = srcPattern.find(doc.html())
                if (match != null) {
                    val url = match.groupValues[1]
                    Log.d("SiteBrowser", "DoramasYT: extracted URL from regex: ${url.takeLast(80)}")
                    return@withContext url
                }

                Log.d("SiteBrowser", "DoramasYT: no embed found in reproductor, using URL directly")
                null
            } catch (e: Exception) {
                Log.e("SiteBrowser", "DoramasYT reproductor extraction failed: ${e.message}")
                null
            }
        }
    }

    private fun shareCurrentSite() {
        val data = com.karin.streamtv.share.ShareManager.ShareData(
            title = siteName,
            episodeTitle = "Explora $siteName en KarinFLiX",
            episodeUrl = siteUrl,
            siteName = siteName
        )
        com.karin.streamtv.share.ShareManager.shareGeneric(this, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        com.karin.streamtv.util.VoiceSearchHelper.handleResult(requestCode, resultCode, data, etSearch)
        if (requestCode == com.karin.streamtv.util.VoiceSearchHelper.REQUEST_VOICE_SEARCH && etSearch.text.isNotBlank()) {
            performSearch()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val mapped = com.karin.streamtv.util.GamepadHelper.mapGamepadToDpad(keyCode)
        if (mapped != keyCode) {
            return onKeyDown(mapped, event)
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        if (!pendingWebViewFallback) return
        pendingWebViewFallback = false

        val episode = pendingFallbackEpisode ?: return
        val failedSource = pendingFallbackSource ?: return
        val allSources = pendingFallbackAllSources
        val remaining = allSources.filter { it.serverUrl != failedSource.serverUrl }

        Log.d("SiteBrowser", "WebView returned → fallback check: failed=${failedSource.name} remaining=${remaining.size}")

        if (AppPreferences.isServerFallbackEnabled() && remaining.isNotEmpty()) {
            Toast.makeText(this, "WebView no reprodujo, probando siguiente servidor...", Toast.LENGTH_SHORT).show()
            isPlayingVideo = false
            val next = remaining.first()
            playVideo(episode, next, allSources)
        } else if (remaining.isNotEmpty()) {
            Toast.makeText(this, "Elige otro servidor", Toast.LENGTH_SHORT).show()
            showServerPicker(episode, allSources)
        } else {
            Log.e("SiteBrowser", "ALL SERVERS + WEBVIEW FAILED for '${episode.title}' (${episode.url.takeLast(60)})")
            Toast.makeText(this, "Ningún servidor disponible", Toast.LENGTH_SHORT).show()
            showServerPicker(episode, allSources)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun extractEpisodeNumber(title: String): Int {
        val patterns = listOf(
            Regex("""(?i)(?:episodio|episode|capitulo|cap|ep\.?|#)\s*(\d+)"""),
            Regex("""(\d+)""")
        )
        for (pattern in patterns) {
            val match = pattern.find(title)
            if (match != null) {
                return match.groupValues[1].toIntOrNull() ?: 0
            }
        }
        return 0
    }
}
