package com.karin.streamtv.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo


import android.widget.EditText
import android.widget.LinearLayout

import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.util.Log
import com.karin.streamtv.R
import com.karin.streamtv.model.Episode
import com.karin.streamtv.model.EpisodeNavigation
import com.karin.streamtv.model.SiteMenuItem
import com.karin.streamtv.scraper.DynamicParser
import com.karin.streamtv.scraper.MenuParser
import com.karin.streamtv.scraper.ScrapingEngine
import com.karin.streamtv.scraper.ScraperRegistry
import com.karin.streamtv.scraper.ServerExtractor
import com.karin.streamtv.util.DeviceUtils
import com.karin.streamtv.model.VideoSource
import com.karin.streamtv.util.ServerHelper
import com.karin.streamtv.util.onActionKey
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SiteBrowserActivity : AppCompatActivity() {

    private lateinit var rvEpisodes: RecyclerView
    private lateinit var etSearch: EditText
    private lateinit var btnSearch: TextView
    private lateinit var btnVoice: TextView
    private lateinit var loadingOverlay: android.widget.FrameLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvLoadingText: TextView
    private lateinit var btnCancelLoading: TextView
    private lateinit var btnHome: TextView
    private lateinit var btnMovies: TextView
    private lateinit var btnSeries: TextView
    private lateinit var btnDorama: TextView
    private lateinit var btnSettings: TextView
    // true = el botón actúa como el antiguo Directorio/Catálogo (sitios
    // sin sección de películas, ej. JKAnime/LatAnime).
    private var moviesButtonOpensCatalog = false
    // Sección actual (MOVIES/SERIES/ANIME/DORAMA) o null en portada,
    // directorio y búsqueda. Define qué filtros ofrece la barra.
    private var currentSection: com.karin.streamtv.model.MenuSection? = null
    // Sección pedida por intent (p. ej. desde el detalle de serie).
    private var pendingSection: com.karin.streamtv.model.MenuSection? = null
    private lateinit var btnNextPage: TextView
    private lateinit var btnPrevPage: TextView
    private lateinit var filterBar: View
    private lateinit var filterChipsContainer: LinearLayout
    private lateinit var btnFilterClear: TextView
    private val selectedFilters = LinkedHashMap<String, String>()
    private var activeDims: List<FilterDim> = emptyList()
    private lateinit var paginationBar: View
    private lateinit var tvPageNumber: TextView

    // Spinners de filtro (compatibilidad código legacy)
    private var spinnerOrder: android.widget.Spinner? = null
    private var spinnerYear: android.widget.Spinner? = null
    private var spinnerGenre: android.widget.Spinner? = null
    private var spinnerDemo: android.widget.Spinner? = null
    private var spinnerType: android.widget.Spinner? = null
    private var spinnerStatus: android.widget.Spinner? = null
    private var spinnerSeason: android.widget.Spinner? = null
    private var spinnerLetter: android.widget.Spinner? = null
    private var spinnerCategory: android.widget.Spinner? = null

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
    private var gridIsCatalog: Boolean = false
    private var currentPageNum: Int = 1
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_site_browser)


        siteName = intent.getStringExtra("site_name") ?: ""
        siteUrl = intent.getStringExtra("site_url") ?: ""

        rvEpisodes = findViewById(R.id.rv_episodes)
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

        findViewById<android.view.View>(R.id.iv_header_logo).setOnClickListener {
            goToMainPage()
        }
        findViewById<android.view.View>(R.id.iv_header_logo).onActionKey {
            goToMainPage()
        }

        btnMovies = findViewById(R.id.btn_movies)
        btnMovies.setOnClickListener { onMoviesButton() }
        btnMovies.onActionKey { onMoviesButton() }

        btnSeries = findViewById(R.id.btn_series)
        btnSeries.setOnClickListener { openSection(com.karin.streamtv.model.MenuSection.SERIES, "Series") }
        btnSeries.onActionKey { openSection(com.karin.streamtv.model.MenuSection.SERIES, "Series") }

        btnDorama = findViewById(R.id.btn_dorama)
        btnDorama.setOnClickListener { openSection(com.karin.streamtv.model.MenuSection.DORAMA, "Doramas") }
        btnDorama.onActionKey { openSection(com.karin.streamtv.model.MenuSection.DORAMA, "Doramas") }

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
        filterChipsContainer = findViewById(R.id.filter_chips_container)
        btnFilterClear = findViewById(R.id.btn_filter_clear)
        paginationBar = findViewById(R.id.pagination_bar)
        tvPageNumber = findViewById(R.id.tv_page_number)

        initDynamicFilterBar()

        val isTv = DeviceUtils.isTvDevice(this)
        rvEpisodes.layoutManager = GridLayoutManager(this, 3)
        if (isTv) {
            etSearch.isFocusableInTouchMode = false
            // Configurar foco para TV
            rvEpisodes.isFocusable = true
            rvEpisodes.isFocusableInTouchMode = true
            rvEpisodes.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            // Solicitar foco inicial al RecyclerView cuando se carguen los datos
            rvEpisodes.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    rvEpisodes.post {
                        val firstChild = rvEpisodes.getChildAt(0)
                        firstChild?.requestFocus()
                    }
                }
            }
            // DPAD_UP desde grid -> barra superior (último botón enfocado)
            rvEpisodes.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    val firstVisible = (rvEpisodes.layoutManager as GridLayoutManager).findFirstVisibleItemPosition()
                    if (firstVisible == 0) {
                        btnSettings.requestFocus()
                        return@setOnKeyListener true
                    }
                }
                false
            }
            // Navegación DPAD entre barra superior y grid + horizontal en barra
            val topBarButtons: List<View> = listOf(findViewById(R.id.iv_header_logo), btnHome, btnMovies, btnSeries, btnDorama, btnSettings)
            topBarButtons.forEachIndexed { index, btn ->
                btn.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                rvEpisodes.requestFocus()
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (index > 0) {
                                    var prev = index - 1
                                    while (prev > 0 && topBarButtons[prev].visibility != View.VISIBLE) prev--
                                    topBarButtons[prev].requestFocus()
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                if (index < topBarButtons.lastIndex) {
                                    var next = index + 1
                                    while (next < topBarButtons.lastIndex && topBarButtons[next].visibility != View.VISIBLE) next++
                                    topBarButtons[next].requestFocus()
                                }
                                true
                            }
                            else -> false
                        }
                    } else false
                }
            }
        }

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

        findViewById<android.view.View>(R.id.btn_share)?.setOnClickListener {
            shareCurrentSite()
        }
        findViewById<android.view.View>(R.id.btn_karinlink)?.setOnClickListener {
            startActivity(Intent(this, com.karin.streamtv.karinlink.KarinLinkActivity::class.java))
        }

        ScrapingEngine.onMetrics = { metrics ->
            Log.d("SiteBrowser", "[${metrics.site}] ${metrics.url.takeLast(40)} " +
                    "cached=${metrics.cached} attempts=${metrics.attempts} " +
                    "duration=${metrics.durationMs}ms success=${metrics.success}")
        }

        val autoPlayUrl = intent.getStringExtra("autoplay_url")
        val autoPlayTitle = intent.getStringExtra("autoplay_title")
        val seriesUrl = intent.getStringExtra("series_url")
        pendingSection = intent.getStringExtra("open_section")?.let { raw ->
            try { com.karin.streamtv.model.MenuSection.valueOf(raw) } catch (_: Exception) { null }
        }
        when {
            !seriesUrl.isNullOrBlank() -> loadSeriesPage(seriesUrl)
            !autoPlayUrl.isNullOrBlank() -> {
                val ep = Episode(autoPlayTitle ?: "Episodio", autoPlayUrl, siteName = siteName)
                loadHomepageAndAutoPlay(ep)
            }
            else -> loadHomepage()
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
                    // Misma clave que getLatestEpisodes() (GenericScraper.fetchDocument usa
                    // "<name>::<url.hashCode>") para que colisionen en caché y no se
                    // descargue la homepage dos veces en paralelo.
                    val doc = ScrapingEngine.fetch(siteUrl, siteName, "${siteName}::${siteUrl.hashCode()}", forceFresh = false)
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

                gridIsCatalog = false
                setEpisodesAdapter(episodes) { episode ->
                    openEpisodeOrDetail(episode)
                }
                // TV: solicitar foco en grid tras cargar datos
                if (DeviceUtils.isTvDevice(this@SiteBrowserActivity)) {
                    rvEpisodes.post { rvEpisodes.requestFocus() }
                }
                tvEmpty.visibility = android.view.View.GONE
                // Botones de sección solo con icono (sin letrero): solo los que
                // el sitio ofrece (URL directa conocida o entrada de menú).
                // Películas, si no existe, actúa como el antiguo
                // Directorio/Catálogo (JKAnime, LatAnime...).
                val hasMoviesSection = supportsSection(com.karin.streamtv.model.MenuSection.MOVIES)
                if (hasMoviesSection) {
                    moviesButtonOpensCatalog = false
                    btnMovies.text = "🎬 Películas"
                } else {
                    moviesButtonOpensCatalog = true
                    btnMovies.text = "📚 Catálogo"
                }
                val hasSeriesSection = supportsSection(com.karin.streamtv.model.MenuSection.SERIES)
                btnSeries.visibility = if (hasSeriesSection) View.VISIBLE else View.GONE
                btnSeries.text = "📺 Series"
                val hasDoramaSection = supportsSection(com.karin.streamtv.model.MenuSection.DORAMA)
                btnDorama.visibility = if (hasDoramaSection) View.VISIBLE else View.GONE
                btnDorama.text = "🏮 Doramas"
                // En portada no hay sección: filtros por defecto del sitio.
                // PeliPops muestra Género + Año aquí (en secciones no hay barra).
                currentSection = null
                filterBar.visibility =
                    if (siteName.equals("PeliPops", ignoreCase = true)) View.VISIBLE else View.GONE
                refreshFilterBar()
                // Apertura directa de sección pedida por intent (detalle).
                pendingSection?.let { section ->
                    pendingSection = null
                    val label = when (section) {
                        com.karin.streamtv.model.MenuSection.MOVIES -> "Películas"
                        com.karin.streamtv.model.MenuSection.SERIES -> "Series"
                        com.karin.streamtv.model.MenuSection.ANIME -> "Anime"
                        com.karin.streamtv.model.MenuSection.DORAMA -> "Doramas"
                        else -> section.name
                    }
                    openSection(section, label)
                }
            } catch (e: Exception) {
                Log.e("SiteBrowser", "loadHomepage error: ${e.message}", e)
                loadingOverlay.visibility = android.view.View.GONE
                tvEmpty.visibility = android.view.View.VISIBLE
                tvEmpty.text = "Error al cargar episodios"
            }
        }
    }

    private fun loadSeriesPage(seriesUrl: String) {
        hideSearchBar()
        filterBar.visibility = View.GONE
        paginationBar.visibility = View.GONE
        currentPageNum = 1
        showingSearchResults = false
        lastSearchQuery = ""
        tvEmpty.visibility = android.view.View.GONE
        showLoading("Cargando capítulos...")
        nextPageUrl = null
        prevPageUrl = null
        btnNextPage.visibility = android.view.View.GONE
        btnPrevPage.visibility = android.view.View.GONE

        lifecycleScope.launch {
            try {
                val (episodes, doc) = withContext(Dispatchers.IO) {
                    val doc = ScrapingEngine.fetch(seriesUrl, siteName, "${siteName}::serie::${seriesUrl.takeLast(80)}")
                    val eps = if (doc != null) {
                        val links = DynamicParser.parseEpisodeLinks(doc, siteName)
                        if (links.isNotEmpty()) links else DynamicParser.parseDynamic(doc, siteName)
                    } else emptyList()
                    Pair(eps, doc)
                }

                loadingOverlay.visibility = android.view.View.GONE

                currentPageUrl = seriesUrl

                if (episodes.isEmpty()) {
                    tvEmpty.visibility = android.view.View.VISIBLE
                    tvEmpty.text = "No se pudieron cargar los capítulos de la serie"
                    return@launch
                }

                currentEpisodes = ArrayList(episodes)
                gridIsCatalog = false
                setEpisodesAdapter(episodes) { episode ->
                    openEpisode(episode)
                }
                if (DeviceUtils.isTvDevice(this@SiteBrowserActivity)) rvEpisodes.post { rvEpisodes.requestFocus() }
                tvEmpty.visibility = android.view.View.GONE
            } catch (e: Exception) {
                Log.e("SiteBrowser", "loadSeriesPage error: ${e.message}", e)
                loadingOverlay.visibility = android.view.View.GONE
                tvEmpty.visibility = android.view.View.VISIBLE
                tvEmpty.text = "Error al cargar los capítulos"
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

gridIsCatalog = false
                setEpisodesAdapter(episodes) { episode ->
                    openEpisodeOrDetail(episode)
                }
                if (DeviceUtils.isTvDevice(this@SiteBrowserActivity)) rvEpisodes.post { rvEpisodes.requestFocus() }
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
                setEpisodesAdapter(currentEpisodes) { episode ->
                    openGridItem(episode)
                }
                if (DeviceUtils.isTvDevice(this@SiteBrowserActivity)) rvEpisodes.post { rvEpisodes.requestFocus() }
                rvEpisodes.scrollToPosition(0)

                currentPageNum++
                // Se conserva el título de la sección (no se pisa con Directorio).

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
                setEpisodesAdapter(currentEpisodes) { episode ->
                    openGridItem(episode)
                }
                if (DeviceUtils.isTvDevice(this@SiteBrowserActivity)) rvEpisodes.post { rvEpisodes.requestFocus() }
                rvEpisodes.scrollToPosition(0)

                currentPageNum = (currentPageNum - 1).coerceAtLeast(1)
                // Se conserva el título de la sección (no se pisa con Directorio).

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

    // Abre una sección del sitio (Películas/Series/Anime/Doramas) con la misma
    // ruta del directorio: cuadrícula -> detalle.
    // Fichas de serie van al detalle; capítulos al reproductor.
    // (Regla del navegador para no tocar scrapers ajenos.)
    private fun openEpisodeOrDetail(episode: Episode) {
        if (isSeriesDetailUrl(episode.url)) {
            val intent = Intent(this@SiteBrowserActivity, SeriesDetailActivity::class.java).apply {
                putExtra("series_url", episode.url)
                putExtra("series_title", episode.title)
                putExtra("site_name", siteName)
            }
            startActivity(intent)
        } else {
            openEpisode(episode)
        }
    }

    // ¿La URL es ficha de serie (detalle con capítulos) y no capítulo reproducible?
    private fun isSeriesDetailUrl(url: String): Boolean {
        if (siteName.equals("JKAnime", ignoreCase = true)) return isJkSeriesUrl(url)
        // LaCartoons: /serie/{id}; el capítulo es /serie/capitulo/{id}?t={temporada}.
        if (siteName.equals("LaCartoons", ignoreCase = true)) {
            val path = try { java.net.URI(url).path?.trim('/') } catch (_: Exception) { null }
                ?: return false
            return Regex("""^serie/\d+$""").matches(path)
        }
        return false
    }

    // Elemento del grid según el modo actual: en el catálogo/directorio las
    // tarjetas son series (abren el detalle con capítulos); en el resto son
    // episodios reproducibles.
    private fun openGridItem(episode: Episode) {
        if (gridIsCatalog) {
            val intent = Intent(this@SiteBrowserActivity, SeriesDetailActivity::class.java).apply {
                putExtra("series_url", episode.url)
                putExtra("series_title", episode.title)
                putExtra("site_name", siteName)
            }
            startActivity(intent)
        } else {
            openEpisodeOrDetail(episode)
        }
    }

    // Ficha JKAnime: un solo segmento (/slug/). Con número (/slug/123) es capítulo.
    private fun isJkSeriesUrl(url: String): Boolean {
        val path = try { java.net.URI(url).path?.trim('/') } catch (_: Exception) { null }
            ?: return false
        return path.isNotBlank() && !path.contains("/")
    }

    private fun openSection(section: com.karin.streamtv.model.MenuSection, title: String) {
        // 1) URL directa conocida (no depende del menú cargado).
        // 2) Sección del menú del sitio. 3) aviso.
        val direct = com.karin.streamtv.util.SiteSections.directUrl(siteName, section)
        val url = if (!direct.isNullOrBlank()) direct
            else menuItems.firstOrNull { it.section == section }?.url
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "Sección no encontrada en $siteName", Toast.LENGTH_SHORT).show()
            return
        }
        currentSection = section
        refreshFilterBar()
        loadDirectoryContent(url)
    }

    // ¿El sitio ofrece esta sección? Url directa conocida que no sea la
    // propia portada, o una entrada en el menú del sitio. Misma regla para
    // Películas, Series, Anime y Doramas.
    private fun supportsSection(section: com.karin.streamtv.model.MenuSection): Boolean {
        return com.karin.streamtv.util.SiteSections.supported(
            siteName,
            section,
            siteUrl,
            menuItems.any { it.section == section }
        )
    }

    // Catálogo/directorio clásico: entrada DIRECTORY del menú o URL conocida
    // por sitio (defaultDirUrl). Es la vía de exploración de los sitios de
    // anime/donghua/doramas, que no tienen sección de películas.
    private fun openDirectory() {
        currentSection = null
        refreshFilterBar()
        loadDirectoryContent(null)
    }

    private fun onMoviesButton() {
        if (moviesButtonOpensCatalog) openDirectory()
        else openSection(com.karin.streamtv.model.MenuSection.MOVIES, "Películas")
    }

    private fun updatePaginationBar() {
        val hasPages = nextPageUrl != null || prevPageUrl != null
        paginationBar.visibility = if (hasPages) View.VISIBLE else View.GONE
        tvPageNumber.text = "Página $currentPageNum"
        btnPrevPage.visibility = if (prevPageUrl != null) View.VISIBLE else View.GONE
        btnNextPage.visibility = if (nextPageUrl != null) View.VISIBLE else View.GONE
    }

    // region --- Barra de filtros dinámica ---

    private data class FilterDim(
        val key: String,
        val label: String,
        val options: List<String>,
        val slugMap: Map<String, String> = emptyMap(),
        val valueFromLabel: (String) -> String = { it }
    )

    private val genericGenres = listOf(
        "Acción", "Aventura", "Carreras", "Ciencia Ficción", "Comedia", "Cyberpunk",
        "Deportes", "Drama", "Ecchi", "Escolares", "Fantasía", "Gore", "Harem",
        "Horror", "Josei", "Lucha", "Magia", "Mecha", "Militar", "Misterio",
        "Música", "Parodias", "Psicológico", "Seinen", "Shojo", "Shonen",
        "Sobrenatural", "Vampiros", "Yaoi", "Yuri", "Latino", "Espacial",
        "Histórico", "Samurai", "Artes Marciales", "Demonios", "Romance",
        "Dementia", "Policía", "Castellano", "Donghua", "Blu-ray", "Isekai", "Suspenso"
    )

    private val genericCategories = listOf(
        "Anime", "Ova", "Película", "Especial", "Corto", "Ona", "Donghua",
        "Sin Censura", "Preestreno", "Latino", "Castellano", "Live Action",
        "Cartoon", "Catalán"
    )

    private fun initDynamicFilterBar() {
        activeDims = buildFilterDims()
        renderFilterChips()
        btnFilterClear.setOnClickListener { clearFilters() }
        btnFilterClear.onActionKey { clearFilters() }
    }

    // Géneros reales de PeliPops (/generos/<slug>), con su nombre visible.
    private val pelipopsMovieGenres = listOf(
        "Acción" to "accion",
        "Animación" to "animacion",
        "Aventura" to "aventura",
        "Bélica" to "belica",
        "Ciencia Ficción" to "ciencia-ficcion",
        "Comedia" to "comedia",
        "Crimen" to "crimen",
        "Documental" to "documental",
        "Doramas" to "dorama",
        "Drama" to "drama",
        "Familia" to "familia",
        "Fantasía" to "fantasia",
        "Guerra" to "guerra",
        "Historia" to "historia",
        "Misterio" to "misterio",
        "Romance" to "romance",
        "Suspense" to "suspense",
        "Terror" to "terror",
        "Western" to "western"
    )

    private fun isPelipopsHome(): Boolean =
        siteName.equals("PeliPops", ignoreCase = true) && currentSection == null

    private fun buildFilterDims(): List<FilterDim> {
        // LaCartoons: una sola dimensión real, las categorías del sitio (/?Categoria_id=<id>).
        if (siteName.equals("LaCartoons", ignoreCase = true)) {
            return listOf(
                FilterDim("Categoria_id", "Categoría", lcCategorySlugs.map { it.first }, slugMap = lcCategorySlugs.toMap())
            )
        }
        // PeliPops: Género + Año solo en la portada; en secciones
        // (Películas, Series, Anime, Doramas) no hay barra de filtros.
        if (siteName.equals("PeliPops", ignoreCase = true)) {
            if (!isPelipopsHome()) return emptyList()
            return listOf(
                FilterDim("genero", "Género", pelipopsMovieGenres.map { it.first }, slugMap = pelipopsMovieGenres.toMap()),
                FilterDim("fecha", "Año", (2026 downTo 2007).map { it.toString() })
            )
        }
        return if (siteName.equals("JKAnime", ignoreCase = true)) {            listOf(
                FilterDim("filtro", "Ordenar", jkOrderSlugs.map { it.first }, slugMap = jkOrderSlugs.toMap()),
                FilterDim("fecha", "Año", (2026 downTo 1981).map { it.toString() }),
                FilterDim("genero", "Género", jkGenreSlugs.map { it.first }, slugMap = jkGenreSlugs.toMap()),
                FilterDim("demografia", "Demografía", jkDemoSlugs.map { it.first }, slugMap = jkDemoSlugs.toMap()),
                FilterDim("tipo", "Tipo", jkTypeSlugs.map { it.first }, slugMap = jkTypeSlugs.toMap()),
                FilterDim("estado", "Estado", jkStatusSlugs.map { it.first }, slugMap = jkStatusSlugs.toMap()),
                FilterDim("temporada", "Temporada", jkSeasonSlugs.map { it.first }, slugMap = jkSeasonSlugs.toMap()),
                FilterDim("letra", "Letra", ('A'..'Z').map { it.toString() }),
                FilterDim("categoria", "Categoría", jkCategorySlugs.map { it.first }, slugMap = jkCategorySlugs.toMap())
            )
        } else {
            listOf(
                FilterDim("fecha", "Año", (2026 downTo 1990).map { it.toString() }),
                FilterDim("genero", "Género", genericGenres, valueFromLabel = { slugify(it) }),
                FilterDim("letra", "Letra", listOf("0-9") + ('A'..'Z').map { it.toString() }, valueFromLabel = { if (it == "0-9") "09" else it }),
                FilterDim("categoria", "Categoría", genericCategories)
            )
        }
    }

    private fun slugify(value: String): String =
        value.lowercase()
            .replace("ñ", "n").replace("ó", "o").replace("é", "e")
            .replace("á", "a").replace("í", "i").replace("ú", "u").replace("ü", "u")
            .replace(" ", "-")

    private fun renderFilterChips() {
        filterChipsContainer.removeAllViews()
        // LaCartoons: las categorías van directas en la barra (sin desplegable).
        if (siteName.equals("LaCartoons", ignoreCase = true)) {
            val dim = activeDims.firstOrNull { it.key == "Categoria_id" }
            if (dim != null) {
                val current = selectedFilters[dim.key]
                for (option in dim.options) {
                    val isSel = current == option
                    val chip = TextView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            dp(34)
                        ).apply { marginEnd = dp(4) }
                        text = if (isSel) "✓ $option" else option
                        setPadding(dp(10), 0, dp(10), 0)
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setTextColor(if (isSel) ContextCompat.getColor(this@SiteBrowserActivity, R.color.accent) else ContextCompat.getColor(this@SiteBrowserActivity, R.color.text_primary))
                        textSize = 13f
                        setBackgroundResource(R.drawable.bg_spinner)
                        isFocusable = true
                        setOnClickListener {
                            if (selectedFilters[dim.key] == option) selectedFilters.remove(dim.key)
                            else selectedFilters[dim.key] = option
                            renderFilterChips()
                            applyFilters()
                        }
                        setOnKeyListener { _, keyCode, event ->
                            if (event.action == KeyEvent.ACTION_DOWN) {
                                when (keyCode) {
                                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { performClick(); true }
                                    KeyEvent.KEYCODE_DPAD_DOWN -> { rvEpisodes.requestFocus(); true }
                                    else -> false
                                }
                            } else false
                        }
                    }
                    filterChipsContainer.addView(chip)
                }
                btnFilterClear.visibility = if (selectedFilters.isNotEmpty()) View.VISIBLE else View.GONE
                return
            }
        }
        for (dim in activeDims) {
            val selected = selectedFilters[dim.key]
            val chip = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(34)
                ).apply { marginEnd = dp(4) }
                text = if (selected != null) "✓ ${dim.label}: $selected" else "${dim.label} ▾"
                setPadding(dp(10), 0, dp(10), 0)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setTextColor(if (selected != null) ContextCompat.getColor(this@SiteBrowserActivity, R.color.accent) else ContextCompat.getColor(this@SiteBrowserActivity, R.color.text_primary))
                textSize = 13f
                setBackgroundResource(R.drawable.bg_spinner)
                isFocusable = true
                setOnClickListener { showFilterPicker(dim) }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { performClick(); true }
                            KeyEvent.KEYCODE_DPAD_DOWN -> { rvEpisodes.requestFocus(); true }
                            else -> false
                        }
                    } else false
                }
            }
            filterChipsContainer.addView(chip)
        }
        btnFilterClear.visibility = if (selectedFilters.isNotEmpty()) View.VISIBLE else View.GONE
    }

    // Reconstruye la barra según la sección actual y limpia selecciones
    // que ya no aplican (ej. filtros de otra sección).
    private fun refreshFilterBar() {
        selectedFilters.clear()
        activeDims = buildFilterDims()
        renderFilterChips()
    }

    private fun showFilterPicker(dim: FilterDim) {
        val options = dim.options
        val current = selectedFilters[dim.key]
        val checked = current?.let { options.indexOf(it) } ?: -1
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(dim.label)
            .setSingleChoiceItems(options.toTypedArray(), checked) { d, which ->
                selectedFilters[dim.key] = options[which]
                // PeliPops/portada: género y año se excluyen (el sitio no
                // tiene URL que los combine: /generos/<slug> o /year/<aaaa>).
                if (isPelipopsHome() && dim.key == "genero") selectedFilters.remove("fecha")
                if (isPelipopsHome() && dim.key == "fecha") selectedFilters.remove("genero")
                renderFilterChips()
                d.dismiss()
                applyFilters()
            }
            .setNegativeButton("Quitar filtro") { d, _ ->
                selectedFilters.remove(dim.key)
                renderFilterChips()
                d.dismiss()
                applyFilters()
            }
            .setNeutralButton("Cancelar", null)
            .show()
    }

    private fun clearFilters() {
        selectedFilters.clear()
        renderFilterChips()
        applyFilters()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    // endregion

    // Filtros de la portada de PeliPops: género y año son excluyentes porque
    // el sitio no tiene URL que los combine. Carga la página correspondiente
    // con la misma tubería del directorio (cuadrícula + paginación) y oculta
    // la barra: las secciones no llevan filtros.
    private fun applyPelipopsHomeFilters() {
        val genreLabel = selectedFilters["genero"]
        val genreSlug = genreLabel?.let { pelipopsMovieGenres.toMap()[it] }.orEmpty()
        val year = selectedFilters["fecha"].orEmpty()
        val home = com.karin.streamtv.util.SiteSections.directUrl(siteName, com.karin.streamtv.model.MenuSection.MOVIES)
            ?.let { it.substringBefore("/peliculas") }
            .orEmpty().ifBlank { siteUrl }.trimEnd('/')
        val (url, title) = when {
            genreSlug.isNotBlank() -> "$home/generos/$genreSlug" to "$siteName · $genreLabel"
            year.isNotBlank() -> "$home/year/$year" to "$siteName · $year"
            else -> "$home/peliculas" to "$siteName - Películas"
        }
        loadDirectoryContent(url)
        filterBar.visibility = View.GONE
    }

    /** Aplica los filtros dinámicos actuales y ejecuta la búsqueda en el directorio. */
    private fun applyFilters() {
        // PeliPops/portada: el sitio filtra por páginas (/generos/<slug>,
        // /year/<aaaa>), no por query params.
        if (isPelipopsHome()) {
            applyPelipopsHomeFilters()
            return
        }
        val params = activeDims.mapNotNull { dim ->
            val label = selectedFilters[dim.key] ?: return@mapNotNull null
            val value = dim.slugMap[label] ?: dim.valueFromLabel(label)
            if (value.isBlank()) return@mapNotNull null
            "${dim.key}=${java.net.URLEncoder.encode(value, "UTF-8")}"
        }

        val baseUrl = if (siteName.equals("MundoDonghua", ignoreCase = true)) {
            defaultDirUrl()
        } else {
            menuItems.firstOrNull { it.section == com.karin.streamtv.model.MenuSection.DIRECTORY }?.url
                ?: defaultDirUrl()
        }
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
                gridIsCatalog = true

                setEpisodesAdapter(seriesList) { episode ->
                    val intent = Intent(this@SiteBrowserActivity, SeriesDetailActivity::class.java).apply {
                        putExtra("series_url", episode.url)
                        putExtra("series_title", episode.title)
                        putExtra("site_name", siteName)
                    }
                    startActivity(intent)
                }
                if (DeviceUtils.isTvDevice(this@SiteBrowserActivity)) rvEpisodes.post { rvEpisodes.requestFocus() }
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

    // Opciones de filtro del directorio de JKAnime: (etiqueta visible, slug del parámetro).
    private val jkOrderSlugs = listOf(
        "Por Fecha" to "",
        "Por Nombre" to "nombre",
        "Por Popularidad" to "popularidad"
    )

    private val jkGenreSlugs = listOf(
        "Acción" to "accion", "Aventura" to "aventura", "Autos" to "autos",
        "Comedia" to "comedia", "Dementia" to "dementia", "Demonios" to "demonios",
        "Misterio" to "misterio", "Drama" to "drama", "Ecchi" to "ecchi",
        "Fantasía" to "fantasia", "Juegos" to "juegos", "Hentai" to "hentai",
        "Histórico" to "historico", "Terror" to "terror", "Niños" to "nios",
        "Magia" to "magia", "Artes Marciales" to "artes-marciales", "Mecha" to "mecha",
        "Música" to "musica", "Parodia" to "parodia", "Samurái" to "samurai",
        "Romance" to "romance", "Escolares" to "colegial", "Ciencia Ficción" to "sci-fi",
        "Shoujo" to "shoujo", "Shoujo Ai" to "shoujo-ai", "Shounen" to "shounen",
        "Shounen Ai" to "shounen-ai", "Espacial" to "space", "Deportes" to "deportes",
        "Super Poderes" to "super-poderes", "Vampiros" to "vampiros", "Yaoi" to "yaoi",
        "Yuri" to "yuri", "Harem" to "harem", "Cosas de la Vida" to "cosas-de-la-vida",
        "Sobrenatural" to "sobrenatural", "Militar" to "militar", "Policial" to "policial",
        "Psicológico" to "psicologico", "Thriller" to "thriller", "Seinen" to "seinen",
        "Josei" to "josei", "Latino" to "latino", "Isekai" to "isekai"
    )

    private val jkDemoSlugs = listOf(
        "Niños" to "nios", "Shoujo" to "shoujo", "Shounen" to "shounen",
        "Seinen" to "seinen", "Josei" to "josei"
    )

    private val jkTypeSlugs = listOf(
        "Animes" to "animes", "Películas" to "peliculas", "Especiales" to "especiales",
        "OVAs" to "ovas", "ONAs" to "onas"
    )

    private val jkStatusSlugs = listOf(
        "En emisión" to "emision", "Finalizado" to "finalizados", "Por Estrenar" to "estrenos"
    )

    private val jkSeasonSlugs = listOf(
        "Invierno" to "invierno", "Primavera" to "primavera",
        "Verano" to "verano", "Otoño" to "otoño"
    )

    private val jkCategorySlugs = listOf(
        "Donghua" to "donghua", "Latino" to "latino"
    )

    // Categorías reales de LaCartoons (/?Categoria_id=<id>).
    private val lcCategorySlugs = listOf(
        "Nickelodeon" to "1", "Cartoon Network" to "2", "Fox Kids" to "3",
        "Hanna Barbera" to "4", "Disney" to "5", "Warner Channel" to "6",
        "Marvel" to "7", "Otros" to "8"
    )

    private fun loadDirectoryContent(dirUrl: String? = null) {
        showLoading("Cargando directorio...")
        hideSearchBar()
        // PeliPops: las secciones no llevan barra de filtros (solo la portada).
        filterBar.visibility =
            if (siteName.equals("PeliPops", ignoreCase = true)) View.GONE else View.VISIBLE
        showingSearchResults = false
        currentPageNum = 1
        tvEmpty.visibility = android.view.View.GONE
        nextPageUrl = null
        prevPageUrl = null
        updatePaginationBar()

        val url = dirUrl ?: if (siteName.equals("MundoDonghua", ignoreCase = true)) {
            defaultDirUrl()
        } else {
            menuItems.firstOrNull { it.section == com.karin.streamtv.model.MenuSection.DIRECTORY }?.url
                ?: defaultDirUrl()
        }

        lifecycleScope.launch {
            try {
                val (seriesList, doc) = withContext(Dispatchers.IO) {
                    val doc = ScrapingEngine.fetch(url, siteName, "${siteName}::dir::${url.hashCode()}")
                    val eps = if (doc != null) {
                        DynamicParser.parseDynamic(doc, siteName, 1)
                    } else emptyList()
                    Pair(eps, doc)
                }

                loadingOverlay.visibility = android.view.View.GONE

                if (seriesList.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = "No se pudo extraer el directorio de $siteName"
                    return@launch
                }

                currentEpisodes = ArrayList(seriesList)
                currentPageUrl = url
                gridIsCatalog = true

                setEpisodesAdapter(seriesList) { episode ->
                    val intent = Intent(this@SiteBrowserActivity, SeriesDetailActivity::class.java).apply {
                        putExtra("series_url", episode.url)
                        putExtra("series_title", episode.title)
                        putExtra("site_name", siteName)
                    }
                    startActivity(intent)
                }
                if (DeviceUtils.isTvDevice(this@SiteBrowserActivity)) rvEpisodes.post { rvEpisodes.requestFocus() }
                rvEpisodes.scrollToPosition(0)

                if (doc != null) {
                    nextPageUrl = DynamicParser.findNextPageUrl(doc, url)
                    // En la primera página el buscador devuelve null y el
                    // botón Atrás queda oculto; en las siguientes aparece.
                    prevPageUrl = DynamicParser.findPrevPageUrl(doc, url)
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
        "jkanime" -> "https://jkanime.net/directorio"
        "mundodonghua" -> "https://www.mundodonghua.com/lista-donghuas"
        "lacartoons" -> "https://www.lacartoons.com/"
        "doramasyt" -> "https://www.doramasyt.com"
        else -> "$siteUrl/animes"
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

    private fun goToMainPage() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
        finish()
    }

    private var currentEpisodeUrl: String = ""
    private var allEpisodes: List<Episode> = emptyList()
    private var currentEpisodeIndex: Int = -1
    private var currentNavigation: EpisodeNavigation = EpisodeNavigation()
    private var episodeExtractionInProgress: Boolean = false

    private fun openEpisode(episode: Episode) {
        Log.d("SiteBrowser", "openEpisode called: url=${episode.url}, title=${episode.title}")
        if (episodeExtractionInProgress) {
            Log.d("SiteBrowser", "Extraction already in progress, ignoring duplicate openEpisode")
            return
        }
        episodeExtractionInProgress = true
        currentEpisodeUrl = episode.url
        currentEpisodeIndex = allEpisodes.indexOfFirst { it.url == episode.url }
        showLoading("Extrayendo servidores de video...")
        lifecycleScope.launch {
            try {
                Log.d("SiteBrowser", "Starting server extraction for: ${episode.url}")
                val scraper = ScraperRegistry.getScraper(siteName)
                val (servers, nav) = kotlinx.coroutines.coroutineScope {
                    val serversDeferred = async(Dispatchers.IO) {
                        kotlinx.coroutines.withTimeoutOrNull(25_000) {
                            scraper?.extractServers(episode.url) ?: ServerExtractor.extractServers(episode.url, siteName)
                        }
                    }
                    val navDeferred = async(Dispatchers.IO) {
                        try {
                            kotlinx.coroutines.withTimeoutOrNull(10_000) {
                                scraper?.scrapeEpisodeNavigation(episode.url)
                                    ?: ServerExtractor.extractEpisodeNavigation(episode.url, siteName)
                            }
                        } catch (e: Exception) {
                            Log.w("SiteBrowser", "Navigation scrape failed: ${e.message}")
                            null
                        }
                    }
                    Pair(serversDeferred.await() ?: emptyList(), navDeferred.await() ?: EpisodeNavigation())
                }
                currentNavigation = nav
                Log.d("SiteBrowser", "Server extraction complete: found ${servers.size} servers for ${episode.url}")
                servers.forEach { Log.d("SiteBrowser", "  Server: ${it.name} -> ${it.serverUrl}") }
                Log.d("SiteBrowser", "Navigation: prev=${currentNavigation.prevUrl}, next=${currentNavigation.nextUrl}")
                loadingOverlay.visibility = android.view.View.GONE

                if (servers.isEmpty()) {
                    Log.d("SiteBrowser", "No servers found, showing error instead of opening website")
                    loadingOverlay.visibility = android.view.View.GONE
                    episodeExtractionInProgress = false
                    Toast.makeText(
                        this@SiteBrowserActivity,
                        "No se encontraron servidores de video para este episodio",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                episodeExtractionInProgress = false
                showServerSelectionDialog(servers, episode.title, episode.url)
            } catch (e: Exception) {
                Log.e("SiteBrowser", "Error extracting servers: ${e.message}", e)
                loadingOverlay.visibility = android.view.View.GONE
                episodeExtractionInProgress = false
                Toast.makeText(
                    this@SiteBrowserActivity,
                    "Error al extraer servidores de video",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showServerSelectionDialog(servers: List<VideoSource>, title: String, episodeUrl: String) {
        val sorted = servers.sortedByDescending { it.speedRating }

        val view = layoutInflater.inflate(R.layout.dialog_servers, null)
        val listView = view.findViewById<android.widget.ListView>(R.id.lv_servers)
        val tvDialogTitle = view.findViewById<TextView>(R.id.tv_dialog_title)
        val btnPrevEpisode = view.findViewById<TextView>(R.id.btn_prev_episode)
        val btnNextEpisode = view.findViewById<TextView>(R.id.btn_next_episode)
        val btnEpisodeList = view.findViewById<TextView>(R.id.btn_episode_list)
        tvDialogTitle.text = title

        val resolutionLabels = java.util.concurrent.ConcurrentHashMap<String, String>()
        listView.adapter = ServerAdapter(sorted, title, resolutionLabels)

        var dialog: android.app.AlertDialog? = null

        dialog = android.app.AlertDialog.Builder(this, R.style.DialogTheme)
            .setView(view)
            .setNegativeButton("Cancelar", null)
            .create()

        // Botones de navegación de episodios (scrapeados de la página del episodio)
        val nav = currentNavigation
        if (nav.prevUrl != null) {
            btnPrevEpisode.visibility = android.view.View.VISIBLE
            btnPrevEpisode.setOnClickListener {
                dialog?.dismiss()
                openEpisode(Episode(
                    title = com.karin.streamtv.util.ServerHelper.titleFromEpisodeUrl(nav.prevUrl),
                    url = nav.prevUrl,
                    siteName = siteName
                ))
            }
        }

        if (nav.nextUrl != null) {
            btnNextEpisode.visibility = android.view.View.VISIBLE
            btnNextEpisode.setOnClickListener {
                dialog?.dismiss()
                openEpisode(Episode(
                    title = com.karin.streamtv.util.ServerHelper.titleFromEpisodeUrl(nav.nextUrl),
                    url = nav.nextUrl,
                    siteName = siteName
                ))
            }
        }

        if (nav.listUrl != null) {
            btnEpisodeList.setOnClickListener {
                dialog?.dismiss()
                val intent = Intent(this, SiteBrowserActivity::class.java).apply {
                    putExtra("series_url", nav.listUrl)
                    putExtra("site_name", siteName)
                }
                startActivity(intent)
            }
        }

        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )

        com.karin.streamtv.util.TvDialogHelper.makeDialogTvReady(dialog, listView, this, btnPrevEpisode, btnEpisodeList, btnNextEpisode)

        dialog.show()

        detectServerResolutions(sorted, view as ViewGroup, listView.adapter as android.widget.BaseAdapter, resolutionLabels)

        listView.setOnItemClickListener { _, _, which, _ ->
            dialog.dismiss()
            val server = sorted[which]
            openEmbedWebView(server, title, sorted)
        }
    }

    private fun detectServerResolutions(
        servers: List<VideoSource>,
        container: ViewGroup,
        adapter: android.widget.BaseAdapter,
        labels: java.util.concurrent.ConcurrentHashMap<String, String>
    ) {
        val semaphore = java.util.concurrent.Semaphore(3)
        lifecycleScope.launch {
            servers.forEach { server ->
                launch(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        val label = com.karin.streamtv.scraper.ServerResolutionDetector.detect(server, container, siteName)
                        if (label != null) {
                            labels[server.serverUrl] = label
                            runOnUiThread { adapter.notifyDataSetChanged() }
                        }
                    } catch (e: Exception) {
                        Log.w("SiteBrowser", "res detection failed: ${e.message}")
                    } finally {
                        semaphore.release()
                    }
                }
            }
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
            putExtra("episode_number", ServerHelper.extractEpisodeNumber(title))
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
            putExtra("episode_number", ServerHelper.extractEpisodeNumber(server.name))
            putExtra("open_external", true)
        }
        startActivity(intent)
    }

    private inner class ServerAdapter(
        private val servers: List<VideoSource>,
        private val title: String,
        private val resolutionLabels: java.util.concurrent.ConcurrentHashMap<String, String>
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
            val btnExt = row.findViewById<android.widget.TextView>(R.id.btn_play_external)

            val stars = "\u2605".repeat(server.speedRating.coerceIn(1, 5))
            val fastTag = if (server.speedRating >= 4) " \u26A1" else ""
            tvName.text = "${position + 1}. ${server.name}$fastTag"
            tvStars.text = stars
            val detected = resolutionLabels[server.serverUrl]
            if (detected != null) {
                tvRes.visibility = android.view.View.VISIBLE
                tvRes.text = detected
            } else if (server.supportsResolutionChange) {
                tvRes.visibility = android.view.View.VISIBLE
                tvRes.text = "HD"
            } else {
                tvRes.visibility = android.view.View.GONE
            }

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

            btnExt.setOnClickListener { openExternalPlayer(server) }

            if (DeviceUtils.isTvDevice(this@SiteBrowserActivity)) {
                btnExt.isFocusable = false
            }

            return row
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
        // Al volver del player el progreso pudo cambiar; refresca los badges.
        (rvEpisodes.adapter as? EpisodeAdapter)?.invalidateProgressCache()
    }

    private fun setEpisodesAdapter(list: List<Episode>, onEpisodeClick: (Episode) -> Unit) {
        allEpisodes = list
        (rvEpisodes.adapter as? EpisodeAdapter)?.destroy()
        rvEpisodes.adapter = EpisodeAdapter(list, siteUrl, onEpisodeClick = onEpisodeClick)
    }

    override fun onDestroy() {
        ScrapingEngine.onMetrics = null
        handler.removeCallbacksAndMessages(null)
        (rvEpisodes.adapter as? EpisodeAdapter)?.destroy()
        super.onDestroy()
    }
}
