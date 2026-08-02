package com.karin.streamtv.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.util.Log
import com.karin.streamtv.R
import com.karin.streamtv.model.Episode
import com.karin.streamtv.model.SiteMenuItem
import com.karin.streamtv.scraper.DynamicParser
import com.karin.streamtv.scraper.MenuParser
import com.karin.streamtv.scraper.ScrapingEngine
import com.karin.streamtv.scraper.ScraperRegistry
import com.karin.streamtv.scraper.ServerExtractor
import com.karin.streamtv.util.DeviceUtils
import com.karin.streamtv.model.VideoSource
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
    private lateinit var btnVoice: TextView
    private lateinit var loadingOverlay: android.widget.FrameLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvLoadingText: TextView
    private lateinit var btnCancelLoading: TextView
    private lateinit var btnHome: TextView
    private lateinit var btnDirectory: TextView
    private lateinit var btnSettings: TextView
    private lateinit var btnNextPage: TextView
    private lateinit var btnPrevPage: TextView
    private lateinit var filterBar: View
    private lateinit var spinnerYear: Spinner
    private lateinit var spinnerGenre: Spinner
    private lateinit var spinnerLetter: Spinner
    private lateinit var spinnerCategory: Spinner
    private lateinit var btnFilterApply: TextView
    private lateinit var paginationBar: View
    private lateinit var tvPageNumber: TextView

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
    private var currentPageNum: Int = 1
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_site_browser)


        siteName = intent.getStringExtra("site_name") ?: ""
        siteUrl = intent.getStringExtra("site_url") ?: ""

        rvEpisodes = findViewById(R.id.rv_episodes)
        rvMenu = findViewById(R.id.rv_menu)
        tvTitle = findViewById(R.id.tv_site_title)
        etSearch = findViewById(R.id.et_search)
        btnSearch = findViewById(R.id.btn_search)
        btnVoice = findViewById(R.id.btn_voice)
        loadingOverlay = findViewById(R.id.loading_overlay)
        tvEmpty = findViewById(R.id.tv_empty)
        tvLoadingText = findViewById(R.id.tv_loading_text)
        btnCancelLoading = findViewById(R.id.btn_cancel_loading)
        btnCancelLoading.visibility = android.view.View.GONE

        btnHome = findViewById(R.id.btn_home)
        btnHome.setOnClickListener { loadHomepage() }
        btnHome.onActionKey { loadHomepage() }

        btnDirectory = findViewById(R.id.btn_directory)
        btnDirectory.setOnClickListener { toggleMenu() }
        btnDirectory.onActionKey { toggleMenu() }

        btnSettings = findViewById(R.id.btn_settings)
        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnSettings.onActionKey { btnSettings.performClick() }

        btnNextPage = findViewById(R.id.btn_next_page)
        btnNextPage.setOnClickListener { loadNextPage() }
        btnNextPage.onActionKey { loadNextPage() }

        btnPrevPage = findViewById(R.id.btn_prev_page)
        btnPrevPage.setOnClickListener { loadPrevPage() }
        btnPrevPage.onActionKey { loadPrevPage() }

        filterBar = findViewById(R.id.filter_bar)
        spinnerYear = findViewById(R.id.spinner_year)
        spinnerGenre = findViewById(R.id.spinner_genre)
        spinnerLetter = findViewById(R.id.spinner_letter)
        spinnerCategory = findViewById(R.id.spinner_category)
        btnFilterApply = findViewById(R.id.btn_filter_apply)
        paginationBar = findViewById(R.id.pagination_bar)
        tvPageNumber = findViewById(R.id.tv_page_number)

        setupFilterSpinners()
        btnFilterApply.setOnClickListener { applyFilters() }
        btnFilterApply.onActionKey { applyFilters() }

        tvTitle.text = siteName

        val isTv = DeviceUtils.isTvDevice(this)
        rvEpisodes.layoutManager = GridLayoutManager(this, 3)
        rvEpisodes.setHasFixedSize(true)
        if (isTv) {
            etSearch.isFocusableInTouchMode = false
        }

        rvMenu.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        btnSearch.setOnClickListener { toggleSearchBar() }
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else false
        }
        etSearch.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                etSearch.clearFocus()
                rvEpisodes.requestFocus()
                true
            } else false
        }

        btnVoice.setOnClickListener {
            com.karin.streamtv.util.VoiceSearchHelper.startVoiceSearch(this)
        }
        btnVoice.onActionKey { btnVoice.performClick() }

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

    private fun hideSearchBar() {
        etSearch.visibility = android.view.View.GONE
        btnVoice.visibility = android.view.View.GONE
        etSearch.text.clear()
        etSearch.clearFocus()
    }

    private fun loadHomepage() {
        hideSearchBar()
        filterBar.visibility = View.GONE
        paginationBar.visibility = View.GONE
        currentPageNum = 1
        showingSearchResults = false
        lastSearchQuery = ""
        tvEmpty.visibility = android.view.View.GONE
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

                var episodes = episodesDeferred.await()
                val (extractedMenu, homeDoc) = menuDeferred.await()

                loadingOverlay.visibility = android.view.View.GONE

                currentPageUrl = siteUrl
                menuItems = extractedMenu

                if (homeDoc != null) {
                    nextPageUrl = DynamicParser.findNextPageUrl(homeDoc, siteUrl)
                    btnNextPage.visibility = if (nextPageUrl != null) android.view.View.VISIBLE else android.view.View.GONE
                    prevPageUrl = null
                    btnPrevPage.visibility = android.view.View.GONE

                    // Fallback: if scraper returned empty, try dynamic parsing
                    if (episodes.isEmpty()) {
                        episodes = DynamicParser.parseDynamic(homeDoc, siteName)
                        if (episodes.isEmpty()) {
                            episodes = DynamicParser.parseEpisodeLinks(homeDoc, siteName)
                        }
                    }
                }

                currentEpisodes = ArrayList(episodes)

                if (episodes.isEmpty()) {
                    tvEmpty.visibility = android.view.View.VISIBLE
                    tvEmpty.text = "No se pudieron cargar episodios de $siteName"
                    return@launch
                }

                rvEpisodes.adapter = EpisodeAdapter(episodes, siteUrl) { episode ->
                    openEpisode(episode)
                }
                tvEmpty.visibility = android.view.View.GONE
            } catch (e: Exception) {
                Log.e("SiteBrowser", "loadHomepage error: ${e.message}", e)
                loadingOverlay.visibility = android.view.View.GONE
                tvEmpty.visibility = android.view.View.VISIBLE
                tvEmpty.text = "Error al cargar episodios"
            }
        }
    }

    private fun loadHomepageAndAutoPlay(autoPlayEpisode: Episode) {
        hideSearchBar()
        tvEmpty.visibility = android.view.View.GONE
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
            loadHomepage()
            return
        }
        if (query == lastSearchQuery && showingSearchResults) return
        lastSearchQuery = query
        showingSearchResults = true
        filterBar.visibility = View.GONE
        paginationBar.visibility = View.GONE
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

    private fun loadNextPage() {
        hideSearchBar()
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
                    return@launch
                }

                currentPageUrl = url
                currentEpisodes = ArrayList(newEpisodes)
                rvEpisodes.adapter = EpisodeAdapter(currentEpisodes, siteUrl) { episode ->
                    openEpisode(episode)
                }
                rvEpisodes.scrollToPosition(0)

                currentPageNum++
                tvTitle.text = "$siteName - Directorio"

                if (doc != null) {
                    nextPageUrl = DynamicParser.findNextPageUrl(doc, url)
                    prevPageUrl = DynamicParser.findPrevPageUrl(doc, url)
                    updatePaginationBar()
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
        hideSearchBar()
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

                currentPageNum = (currentPageNum - 1).coerceAtLeast(1)
                tvTitle.text = "$siteName - Directorio"

                if (doc != null) {
                    nextPageUrl = DynamicParser.findNextPageUrl(doc, url)
                    prevPageUrl = DynamicParser.findPrevPageUrl(doc, url)
                    updatePaginationBar()
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

    private fun toggleMenu() {
        loadDirectoryContent()
    }

    private fun updatePaginationBar() {
        val hasPages = nextPageUrl != null || prevPageUrl != null
        paginationBar.visibility = if (hasPages) View.VISIBLE else View.GONE
        tvPageNumber.text = "Página $currentPageNum"
        btnPrevPage.visibility = if (prevPageUrl != null) View.VISIBLE else View.GONE
        btnNextPage.visibility = if (nextPageUrl != null) View.VISIBLE else View.GONE
    }

    private fun setupFilterSpinners() {
        val years = listOf("Año") + (2026 downTo 1990).map { it.toString() }
        spinnerYear.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, years)

        val genres = listOf("Género") + listOf(
            "Acción", "Aventura", "Carreras", "Ciencia Ficción", "Comedia", "Cyberpunk",
            "Deportes", "Drama", "Ecchi", "Escolares", "Fantasía", "Gore", "Harem",
            "Horror", "Josei", "Lucha", "Magia", "Mecha", "Militar", "Misterio",
            "Música", "Parodias", "Psicológico", "Seinen", "Shojo", "Shonen",
            "Sobrenatural", "Vampiros", "Yaoi", "Yuri", "Latino", "Espacial",
            "Histórico", "Samurai", "Artes Marciales", "Demonios", "Romance",
            "Dementia", "Policía", "Castellano", "Donghua", "Blu-ray", "Isekai", "Suspenso"
        )
        spinnerGenre.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, genres)

        val letters = listOf("Letra") + listOf("0-9") + ('A'..'Z').map { it.toString() }
        spinnerLetter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, letters)

        val categories = listOf("Categoría") + listOf(
            "Anime", "Ova", "Película", "Especial", "Corto", "Ona", "Donghua",
            "Sin Censura", "Preestreno", "Latino", "Castellano", "Live Action",
            "Cartoon", "Catalán"
        )
        spinnerCategory.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
    }

    private fun applyFilters() {
        val yearValue = if (spinnerYear.selectedItemPosition > 0) {
            (spinnerYear.selectedItem as String).lowercase().replace(" ", "-")
        } else ""
        val genreValue = if (spinnerGenre.selectedItemPosition > 0) {
            (spinnerGenre.selectedItem as String).lowercase()
                .replace(" ", "-").replace("ó", "o").replace("é", "e").replace("á", "a").replace("í", "i").replace("ú", "u")
        } else ""
        val letterValue = if (spinnerLetter.selectedItemPosition > 0) {
            val raw = spinnerLetter.selectedItem as String
            if (raw == "0-9") "09" else raw
        } else ""
        val categoryValue = if (spinnerCategory.selectedItemPosition > 0) {
            (spinnerCategory.selectedItem as String)
        } else ""

        val params = mutableListOf<String>()
        if (yearValue.isNotBlank() && yearValue != "año") params.add("fecha=$yearValue")
        if (genreValue.isNotBlank() && genreValue != "género") params.add("genero=$genreValue")
        if (letterValue.isNotBlank() && letterValue != "letra") params.add("letra=$letterValue")
        if (categoryValue.isNotBlank() && categoryValue != "categoría") params.add("categoria=$categoryValue")

        val baseUrl = menuItems.firstOrNull { it.section == com.karin.streamtv.model.MenuSection.DIRECTORY }?.url
            ?: defaultDirUrl()
        val url = if (params.isNotEmpty()) "$baseUrl?${params.joinToString("&")}" else baseUrl

        showLoading("Buscando...")
        tvEmpty.visibility = View.GONE
        hideSearchBar()

        lifecycleScope.launch {
            try {
                val (seriesList, doc) = withContext(Dispatchers.IO) {
                    val doc = ScrapingEngine.fetch(url, siteName, "${siteName}::filter::${url.takeLast(80)}")
                    val eps = if (doc != null) DynamicParser.parseDynamic(doc, siteName, 1) else emptyList()
                    Pair(eps, doc)
                }

                loadingOverlay.visibility = View.GONE

                if (seriesList.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = "Sin resultados con estos filtros"
                    return@launch
                }

                currentEpisodes = ArrayList(seriesList)
                currentPageUrl = url
                tvTitle.text = "$siteName - Filtros"
                tvTitle.visibility = View.VISIBLE

                rvEpisodes.adapter = EpisodeAdapter(seriesList, siteUrl) { episode ->
                    val intent = Intent(this@SiteBrowserActivity, SeriesDetailActivity::class.java).apply {
                        putExtra("series_url", episode.url)
                        putExtra("series_title", episode.title)
                        putExtra("site_name", siteName)
                    }
                    startActivity(intent)
                }
                rvEpisodes.scrollToPosition(0)

                if (doc != null) {
                    nextPageUrl = DynamicParser.findNextPageUrl(doc, url)
                    prevPageUrl = DynamicParser.findPrevPageUrl(doc, url)
                    currentPageNum = 1
                    updatePaginationBar()
                }
            } catch (e: Exception) {
                Log.e("SiteBrowser", "applyFilters error: ${e.message}", e)
                loadingOverlay.visibility = View.GONE
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "Error al buscar"
            }
        }
    }

    private fun loadDirectoryContent() {
        showLoading("Cargando directorio...")
        hideSearchBar()
        filterBar.visibility = View.VISIBLE
        showingSearchResults = false
        currentPageNum = 1
        tvEmpty.visibility = android.view.View.GONE
        nextPageUrl = null
        prevPageUrl = null
        updatePaginationBar()

        val dirUrl = menuItems.firstOrNull { it.section == com.karin.streamtv.model.MenuSection.DIRECTORY }?.url
            ?: defaultDirUrl()

        lifecycleScope.launch {
            try {
                val (seriesList, doc) = withContext(Dispatchers.IO) {
                    val doc = ScrapingEngine.fetch(dirUrl, siteName, "${siteName}::directorio")
                    val eps = if (doc != null) {
                        DynamicParser.parseDynamic(doc, siteName, 1)
                    } else emptyList()
                    Pair(eps, doc)
                }

                loadingOverlay.visibility = android.view.View.GONE

                if (seriesList.isEmpty()) {
                    val intent = Intent(this@SiteBrowserActivity, EmbedWebViewActivity::class.java).apply {
                        putExtra("embed_url", dirUrl)
                        putExtra("video_title", "Directorio - $siteName")
                    }
                    startActivity(intent)
                    return@launch
                }

                currentEpisodes = ArrayList(seriesList)
                currentPageUrl = dirUrl
                tvTitle.text = "$siteName - Directorio"
                tvTitle.visibility = android.view.View.VISIBLE

                rvEpisodes.adapter = EpisodeAdapter(seriesList, siteUrl) { episode ->
                    val intent = Intent(this@SiteBrowserActivity, SeriesDetailActivity::class.java).apply {
                        putExtra("series_url", episode.url)
                        putExtra("series_title", episode.title)
                        putExtra("site_name", siteName)
                    }
                    startActivity(intent)
                }
                rvEpisodes.scrollToPosition(0)

                if (doc != null) {
                    nextPageUrl = DynamicParser.findNextPageUrl(doc, dirUrl)
                    prevPageUrl = null
                    currentPageNum = 1
                    updatePaginationBar()
                }
            } catch (e: Exception) {
                Log.e("SiteBrowser", "loadDirectoryContent error: ${e.message}", e)
                loadingOverlay.visibility = android.view.View.GONE
                tvEmpty.visibility = android.view.View.VISIBLE
                tvEmpty.text = "Error al cargar directorio"
            }
        }
    }

    private fun defaultDirUrl(): String = when (siteName.lowercase()) {
        "latanime" -> "https://latanime.org/animes"
        "jkanime" -> "https://jkanime.net/animes"
        "mundodonghua" -> "https://www.mundodonghua.com/listado/"
        "lacartoons" -> "https://lacartoons.com/lista/"
        "doramasyt" -> "https://www.doramasyt.com/directorio"
        else -> "$siteUrl/animes"
    }

    private fun onCategoryClick(item: SiteMenuItem) {
        hideSearchBar()
        filterBar.visibility = View.GONE
        paginationBar.visibility = View.GONE
        currentPageNum = 1
        showingSearchResults = false
        lastSearchQuery = ""
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
                Log.e("SiteBrowser", "Category error: ${e.message}", e)
                loadingOverlay.visibility = android.view.View.GONE
                tvEmpty.visibility = android.view.View.VISIBLE
                tvEmpty.text = "Error al cargar '${item.name}'"
            }
        }
    }

    private fun toggleSearchBar() {
        if (etSearch.visibility == android.view.View.VISIBLE) {
            hideSearchBar()
            btnSearch.requestFocus()
        } else {
            etSearch.visibility = android.view.View.VISIBLE
            btnVoice.visibility = android.view.View.VISIBLE
            etSearch.requestFocus()
        }
    }

    private var currentEpisodeUrl: String = ""

    private fun openEpisode(episode: Episode) {
        Log.d("SiteBrowser", "openEpisode called: url=${episode.url}, title=${episode.title}")
        currentEpisodeUrl = episode.url
        showLoading("Extrayendo servidores de video...")
        lifecycleScope.launch {
            try {
                Log.d("SiteBrowser", "Starting server extraction for: ${episode.url}")
                val servers = withContext(Dispatchers.IO) {
                    ServerExtractor.extractServers(episode.url, siteName)
                }
                Log.d("SiteBrowser", "Server extraction complete: found ${servers.size} servers for ${episode.url}")
                servers.forEach { Log.d("SiteBrowser", "  Server: ${it.name} -> ${it.serverUrl}") }
                loadingOverlay.visibility = android.view.View.GONE

                if (servers.isEmpty()) {
                    Log.d("SiteBrowser", "No servers found, going direct to WebView: ${episode.url}")
                    openEpisodeDirect(episode)
                    return@launch
                }

                showServerSelectionDialog(servers, episode.title, episode.url)
            } catch (e: Exception) {
                Log.e("SiteBrowser", "Error extracting servers: ${e.message}", e)
                loadingOverlay.visibility = android.view.View.GONE
                openEpisodeDirect(episode)
            }
        }
    }

    private fun openEpisodeDirect(episode: Episode) {
        val intent = Intent(this@SiteBrowserActivity, EmbedWebViewActivity::class.java).apply {
            putExtra("embed_url", episode.url)
            putExtra("video_title", episode.title)
        }
        startActivity(intent)
    }

    private fun showServerSelectionDialog(servers: List<VideoSource>, title: String, episodeUrl: String) {
        val sorted = servers.sortedByDescending { it.speedRating }

        val view = layoutInflater.inflate(R.layout.dialog_servers, null)
        val listView = view.findViewById<android.widget.ListView>(R.id.lv_servers)
        val tvCount = view.findViewById<TextView>(R.id.tv_server_count)
        tvCount.text = "${sorted.size} servidores"

        listView.adapter = ServerAdapter(sorted, title)

        val dialog = android.app.AlertDialog.Builder(this, R.style.DialogTheme)
            .setView(view)
            .setNegativeButton("Cancelar", null)
            .show()

        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )

        listView.setOnItemClickListener { _, _, which, _ ->
            dialog.dismiss()
            val server = sorted[which]
            openEmbedWebView(server, title, sorted)
        }
    }

    private fun openEmbedWebView(server: VideoSource, title: String, allServers: List<VideoSource> = emptyList()) {
        val isTabServer = server.serverUrl.contains("?server=")
        val intent = Intent(this@SiteBrowserActivity, EmbedWebViewActivity::class.java).apply {
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
            putExtra("episode_number", extractEpisodeNumber(title))
            if (allServers.isNotEmpty()) {
                val serverUrls = allServers.map { it.serverUrl }.toTypedArray()
                val serverNames = allServers.map { it.name }.toTypedArray()
                putExtra("all_server_urls", serverUrls)
                putExtra("all_server_names", serverNames)
                putExtra("current_server_index", allServers.indexOfFirst { it.serverUrl == server.serverUrl }.coerceAtLeast(0))
            }
        }
        startActivity(intent)
    }

    private fun openExternalPlayer(server: VideoSource) {
        val isTabServer = server.serverUrl.contains("?server=")
        val intent = Intent(this@SiteBrowserActivity, EmbedWebViewActivity::class.java).apply {
            if (isTabServer) {
                val baseUrl = server.serverUrl.substringBefore("?server=")
                val srvName = server.serverUrl.substringAfter("?server=")
                putExtra("embed_url", baseUrl)
                putExtra("server_name", srvName)
            } else {
                putExtra("embed_url", server.serverUrl)
            }
            putExtra("video_title", server.name)
            putExtra("episode_url", currentEpisodeUrl)
            putExtra("episode_number", extractEpisodeNumber(server.name))
            putExtra("open_external", true)
        }
        startActivity(intent)
    }

    private inner class ServerAdapter(
        private val servers: List<VideoSource>,
        private val title: String
    ) : android.widget.BaseAdapter() {

        override fun getCount(): Int = servers.size
        override fun getItem(position: Int): Any = servers[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
            val ctx = this@SiteBrowserActivity
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
                shareUrl(server.serverUrl, server.name, "com.facebook.katana")
            }
            btnWa.setOnClickListener {
                shareUrl(server.serverUrl, server.name, "com.whatsapp")
            }
            btnExt.setOnClickListener { openExternalPlayer(server) }

            return row
        }
    }

    private fun shareUrl(url: String, label: String, targetPackage: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "$label - $url")
                `package` = targetPackage
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "$label - $url")
                }
                startActivity(Intent.createChooser(intent, "Compartir"))
            } catch (_: Exception) {
                Toast.makeText(this, "App no disponible", Toast.LENGTH_SHORT).show()
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
            if (etSearch.visibility == android.view.View.VISIBLE) {
                toggleSearchBar()
                return true
            }
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
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
