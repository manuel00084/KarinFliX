package com.karin.streamtv.ui

import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.GridLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.karin.streamtv.R
import com.karin.streamtv.model.SiteConfig
import com.karin.streamtv.scraper.ScrapingEngine
import com.karin.streamtv.scraper.ScraperRegistry
import com.karin.streamtv.util.AppPreferences
import com.karin.streamtv.util.DeviceUtils
import com.karin.streamtv.util.onActionKey
import com.karin.streamtv.util.DiskImageCache
import com.karin.streamtv.util.SearchManager
import com.karin.streamtv.util.SiteManager
import com.karin.streamtv.util.VoiceSearchHelper
import com.karin.streamtv.util.WatchHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {

    private lateinit var siteManager: SiteManager
    private lateinit var rowSites: GridLayout
    private lateinit var scrollSites: ScrollView
    private lateinit var rvSearchResults: RecyclerView
    private lateinit var searchLoading: View
    private lateinit var tvSearchStatus: TextView
    private lateinit var tvSearchEmpty: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnSearch: TextView
    private lateinit var historySection: View
    private lateinit var rvHistory: RecyclerView
    private lateinit var continueSection: View
    private lateinit var dividerContinue: View
    private lateinit var rvContinue: RecyclerView
    private var isTvDevice = false
    private val logoCache = object : android.util.LruCache<String, Bitmap>(8) {
        override fun sizeOf(key: String, value: Bitmap) = 1
    }
    private var searchJob: Job? = null
    private val searchAdapter = SearchResultsAdapter { result ->
        onSearchResultClick(result)
    }
    private val historyAdapter = HistoryAdapter()
    private val continueAdapter = ContinueWatchingAdapter()

    private val brandColors = mapOf(
        "JKAnime" to Color.parseColor("#1E88E5"),
        "LatAnime" to Color.parseColor("#43A047"),
        "DoramasYT" to Color.parseColor("#00BCD4"),
        "MundoDonghua" to Color.parseColor("#8E24AA"),
        "RetroTVE" to Color.parseColor("#FF9800"),
        "LaCartoons" to Color.parseColor("#E91E63"),
        "FrikiSeries" to Color.parseColor("#1B5E20"),
    )

    private val SITE_LOGOS = mapOf(
        "JKAnime" to "https://cdn.jkdesa.com/assets3/css/img/jkanimenet.png?v=2.0.184",
        "LatAnime" to "https://latanime.org/img/logito.png",
        "DoramasYT" to "https://www.doramasyt.com/img/logo6.png?v=1718135438",
        "MundoDonghua" to "https://mundodonghua.com/images/favicon.png",
        "RetroTVE" to "https://retrotve.com/wp-content/uploads/2024/11/cropped-android-chrome-512x512-1-192x192.png",
        "FrikiSeries" to "https://www.frikiserie.com/assets/icon/favicon.png",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_main)

            AppPreferences.init(this)
            siteManager = SiteManager(this)
            rowSites = findViewById(R.id.row_sites)
            scrollSites = findViewById(R.id.scroll_sites)
            rvSearchResults = findViewById(R.id.rv_search_results)
            searchLoading = findViewById(R.id.search_loading)
            tvSearchStatus = findViewById(R.id.tv_search_status)
            tvSearchEmpty = findViewById(R.id.tv_search_empty)
            etSearch = findViewById(R.id.et_global_search)
            btnSearch = findViewById(R.id.btn_global_search)
            isTvDevice = DeviceUtils.isTvDevice(this)
            if (isTvDevice) {
                etSearch.isFocusableInTouchMode = false
            }
            ScrapingEngine.init(this)

            rvSearchResults.layoutManager = LinearLayoutManager(this)
            rvSearchResults.adapter = searchAdapter

            btnSearch.setOnClickListener { performGlobalSearch() }

            val btnVoice = findViewById<TextView>(R.id.btn_voice_search)
            btnVoice.setOnClickListener {
                VoiceSearchHelper.startVoiceSearch(this)
            }
            btnVoice.onActionKey { btnVoice.performClick() }

            etSearch.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    performGlobalSearch()
                    true
                } else false
            }
            etSearch.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                    performGlobalSearch()
                    true
                } else if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    etSearch.clearFocus()
                    scrollSites.requestFocus()
                    true
                } else false
            }

            val btnSettings = findViewById<TextView>(R.id.btn_settings)
            btnSettings.setOnClickListener {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            btnSettings.onActionKey { btnSettings.performClick() }

            val btnKarinLink = findViewById<TextView>(R.id.btn_karinlink_main)
            btnKarinLink.setOnClickListener {
                startActivity(Intent(this, com.karin.streamtv.karinlink.KarinLinkActivity::class.java))
            }
            btnKarinLink.onActionKey { btnKarinLink.performClick() }

            historySection = findViewById(R.id.history_section)
            rvHistory = findViewById(R.id.rv_history)
            rvHistory.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            rvHistory.adapter = historyAdapter

            continueSection = findViewById(R.id.continue_section)
            dividerContinue = findViewById(R.id.divider_continue)
            rvContinue = findViewById(R.id.rv_continue)
            rvContinue.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            rvContinue.adapter = continueAdapter

            loadHistory()
            loadContinueWatching()
        } catch (e: Exception) {
            Log.e("MainActivity", "FATAL onCreate: ${e.message}", e)
            try {
                val tv = android.widget.TextView(this)
                tv.text = "Error:\n${e.message}\n\n${e.stackTraceToString()}"
                tv.setTextSize(14f)
                tv.setPadding(32, 32, 32, 32)
                setContentView(tv)
            } catch (_: Exception) {
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyHighContrastIfNeeded()
        loadHistory()
        loadContinueWatching()
        if (etSearch.text.isNullOrBlank()) {
            renderSites()
        }
    }

    private fun applyHighContrastIfNeeded() {
        try {
            val am = getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return
            val method = am.javaClass.getMethod("isHighTextContrastEnabled")
            val enabled = method.invoke(am) as? Boolean ?: false
            if (enabled) {
                val tvStatus = findViewById<TextView>(R.id.tv_search_status)
                val tvEmpty = findViewById<TextView>(R.id.tv_search_empty)
                tvStatus?.setTextColor(Color.WHITE)
                tvEmpty?.setTextColor(Color.WHITE)
            }
        } catch (_: Exception) { }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        VoiceSearchHelper.handleResult(requestCode, resultCode, data, etSearch)
        if (requestCode == VoiceSearchHelper.REQUEST_VOICE_SEARCH && etSearch.text.isNotBlank()) {
            performGlobalSearch()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val mapped = com.karin.streamtv.util.GamepadHelper.mapGamepadToDpad(keyCode)
        if (mapped != keyCode) {
            return onKeyDown(mapped, event)
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (etSearch.text.isNotBlank()) {
                etSearch.text.clear()
                showSites()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        logoCache.evictAll()
        rowSites.removeAllViews()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) {
            logoCache.evictAll()
            rowSites.removeAllViews()
        }
    }

    private fun performGlobalSearch() {
        val query = etSearch.text.toString().trim()
        if (query.isBlank()) {
            showSites()
            return
        }

        searchJob?.cancel()
        searchLoading.visibility = View.VISIBLE
        tvSearchStatus.visibility = View.VISIBLE
        tvSearchStatus.text = "Buscando '$query' en ${ScraperRegistry.allSites.size} sitios..."
        tvSearchEmpty.visibility = View.GONE

        searchJob = lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                SearchManager.searchAll(query)
            }

            searchLoading.visibility = View.GONE

            if (results.isEmpty()) {
                tvSearchEmpty.visibility = View.VISIBLE
                tvSearchEmpty.text = "Sin resultados para '$query'"
                tvSearchEmpty.announceForAccessibility(tvSearchEmpty.text)
                rvSearchResults.visibility = View.GONE
                return@launch
            }

            val siteCount = results.map { it.site }.distinct().size
            tvSearchStatus.text = "${results.size} resultados de $siteCount sitio(s)"
            tvSearchStatus.announceForAccessibility(tvSearchStatus.text)

            scrollSites.visibility = View.GONE
            tvSearchEmpty.visibility = View.GONE
            rvSearchResults.visibility = View.VISIBLE
            searchAdapter.submitList(results)
        }
    }

    private fun showSites() {
        searchJob?.cancel()
        searchLoading.visibility = View.GONE
        tvSearchStatus.visibility = View.GONE
        tvSearchEmpty.visibility = View.GONE
        rvSearchResults.visibility = View.GONE
        scrollSites.visibility = View.VISIBLE
        renderSites()
    }

    private fun onSearchResultClick(result: SearchManager.SearchResult) {
        val scraper = ScraperRegistry.getScraper(result.site)
        if (scraper != null) {
            val intent = Intent(this, SiteBrowserActivity::class.java).apply {
                putExtra("site_id", result.site)
                putExtra("site_name", result.site)
                putExtra("site_url", scraper.baseUrl)
                putExtra("autoplay_url", result.url)
                putExtra("autoplay_title", result.title)
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Sitio no disponible", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderSites() {
        rowSites.removeAllViews()

        val sites = siteManager.getSites()

        if (sites.isEmpty()) {
            return
        }

        sites.forEachIndexed { index, site ->
            val cardView = createSiteCard(site, index)
            rowSites.addView(cardView)
        }

        if (isTvDevice && rowSites.childCount > 0) {
            rowSites.getChildAt(0)?.requestFocus()
        }
    }

    private fun createSiteCard(site: SiteConfig, @Suppress("UNUSED_PARAMETER") index: Int): View {
        val card = layoutInflater.inflate(R.layout.item_site_grid, rowSites, false)
        card.layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            height = GridLayout.LayoutParams.WRAP_CONTENT
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(6, 6, 6, 6)
        }

        val ivLogo = card.findViewById<ImageView>(R.id.iv_site_logo)
        val tvName = card.findViewById<TextView>(R.id.tv_site_name)
        val tvUrl = card.findViewById<TextView>(R.id.tv_site_url)

        val cachedBmp = logoCache.get(site.name)
        val density = resources.displayMetrics.density
        val plateW = (104 * density).toInt()
        val plateH = (36 * density).toInt()
        val bmp = if (cachedBmp != null) cachedBmp else {
            val iconText = site.icon.take(2).padEnd(2).take(2)
            val color = brandColors[site.name] ?: Color.parseColor("#555555")
            val size = resources.getDimensionPixelSize(R.dimen.card_icon_size)
            val newBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { b ->
                val c = Canvas(b)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                }
                c.drawCircle(size / 2f, size / 2f, size / 2f, p)
                p.color = Color.WHITE
                p.textSize = size * 0.38f
                p.textAlign = Paint.Align.CENTER
                p.typeface = Typeface.DEFAULT_BOLD
                c.drawText(iconText, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2f, p)
            }
            logoCache.put(site.name, newBmp)
            newBmp
        }
        ivLogo.setImageBitmap(DiskImageCache.renderLogoPlate(bmp, plateW, plateH))

        val faviconUrl = SITE_LOGOS[site.name]
        lifecycleScope.launch {
            val plate = withContext(Dispatchers.IO) {
                val host = runCatching { java.net.URI(site.url).host }.getOrNull()
                val logo = faviconUrl?.let { DiskImageCache.loadFromNetwork(it, 480, 160) }
                    ?: if (host != null) DiskImageCache.loadBestFavicon(DiskImageCache.faviconCandidates(host)) else null
                logo?.let { DiskImageCache.renderLogoPlate(it, plateW, plateH) }
            }
            if (plate != null) {
                ivLogo.setImageBitmap(plate)
            }
        }

        tvName.text = site.name
        tvUrl.text = site.url

        card.setOnClickListener {
            openBrowser(site)
        }

        card.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER -> {
                        openBrowser(site)
                        true
                    }
                    else -> false
                }
            } else false
        }

        return card
    }

    private fun openBrowser(site: SiteConfig) {
        siteManager.touchLastVisited(site.id)
        val intent = Intent(this, SiteBrowserActivity::class.java).apply {
            putExtra("site_id", site.id)
            putExtra("site_name", site.name)
            putExtra("site_url", site.url)
        }
        startActivity(intent)
    }

    private fun loadHistory() {
        val entries = WatchHistory.getRecentEntries(10)
        if (entries.isEmpty()) {
            historySection.visibility = View.GONE
        } else {
            historySection.visibility = View.VISIBLE
            historyAdapter.submitList(entries)
        }
    }

    private fun loadContinueWatching() {
        val entries = WatchHistory.getContinueWatching(6)
        if (entries.isEmpty()) {
            continueSection.visibility = View.GONE
            dividerContinue.visibility = View.GONE
        } else {
            continueSection.visibility = View.VISIBLE
            dividerContinue.visibility = View.VISIBLE
            continueAdapter.submitList(entries)
        }
    }

    inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.VH>() {

        private var items: List<WatchHistory.HistoryEntry> = emptyList()

        fun submitList(list: List<WatchHistory.HistoryEntry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_history_card, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = items[position]
            holder.tvTitle.text = entry.title
            holder.tvSite.text = entry.siteName

            if (entry.thumbnailUrl.isNotBlank()) {
                lifecycleScope.launch {
                    val bmp = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        DiskImageCache.loadFromNetwork(entry.thumbnailUrl, 300, 180)
                    }
                    if (bmp != null) {
                        holder.ivThumb.setImageBitmap(bmp)
                    }
                }
            }

            holder.itemView.setOnClickListener {
                try {
                    val scraper = ScraperRegistry.getScraper(entry.siteName)
                    if (scraper != null) {
                        val intent = Intent(this@MainActivity, SiteBrowserActivity::class.java).apply {
                            putExtra("site_id", entry.siteName)
                            putExtra("site_name", entry.siteName)
                            putExtra("site_url", scraper.baseUrl)
                        }
                        startActivity(intent)
                    }
                } catch (_: Exception) {}
            }

            holder.itemView.onActionKey { holder.itemView.performClick() }
        }

        override fun getItemCount() = items.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ivThumb: ImageView = v.findViewById(R.id.iv_history_thumb)
            val tvTitle: TextView = v.findViewById(R.id.tv_history_title)
            val tvSite: TextView = v.findViewById(R.id.tv_history_site)
        }
    }

    inner class ContinueWatchingAdapter : RecyclerView.Adapter<ContinueWatchingAdapter.VH>() {

        private var items: List<WatchHistory.HistoryEntry> = emptyList()

        fun submitList(list: List<WatchHistory.HistoryEntry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_continue_watching, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = items[position]
            holder.tvTitle.text = entry.title
            holder.tvSite.text = entry.siteName
            holder.tvEp.text = "Ep ${entry.episodeNumber}"

            val pct = if (entry.durationMs > 0) ((entry.positionMs * 100) / entry.durationMs).toInt().coerceIn(0, 100) else 0
            holder.progressBar.progress = pct

            if (entry.thumbnailUrl.isNotBlank()) {
                lifecycleScope.launch {
                    val bmp = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        DiskImageCache.loadFromNetwork(entry.thumbnailUrl, 400, 220)
                    }
                    if (bmp != null) {
                        holder.ivThumb.setImageBitmap(bmp)
                    }
                }
            }

            holder.itemView.setOnClickListener {
                try {
                    val scraper = ScraperRegistry.getScraper(entry.siteName)
                    if (scraper != null) {
                        val intent = Intent(this@MainActivity, SiteBrowserActivity::class.java).apply {
                            putExtra("site_id", entry.siteName)
                            putExtra("site_name", entry.siteName)
                            putExtra("site_url", scraper.baseUrl)
                            putExtra("autoplay_url", if (entry.episodeUrl.isNotBlank()) entry.episodeUrl else scraper.baseUrl)
                            putExtra("autoplay_title", entry.title)
                            putExtra("autoplay_anime_id", entry.animeId)
                            putExtra("autoplay_episode", entry.episodeNumber)
                        }
                        startActivity(intent)
                    }
                } catch (_: Exception) {}
            }

            holder.itemView.onActionKey { holder.itemView.performClick() }
        }

        override fun getItemCount() = items.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ivThumb: ImageView = v.findViewById(R.id.iv_continue_thumb)
            val tvTitle: TextView = v.findViewById(R.id.tv_continue_title)
            val tvSite: TextView = v.findViewById(R.id.tv_continue_site)
            val tvEp: TextView = v.findViewById(R.id.tv_continue_ep)
            val progressBar: android.widget.ProgressBar = v.findViewById(R.id.progress_bar_continue)
        }
    }
}
