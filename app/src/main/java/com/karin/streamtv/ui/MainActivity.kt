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
import com.karin.streamtv.scraper.ScraperRegistry
import com.karin.streamtv.util.AppPreferences
import com.karin.streamtv.util.CrashLogger
import com.karin.streamtv.util.DeviceUtils
import com.karin.streamtv.util.enableTvFocus
import com.karin.streamtv.util.onActionKey
import com.karin.streamtv.util.DiskImageCache
import com.karin.streamtv.util.SearchManager
import com.karin.streamtv.util.SiteBranding
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
    private val faviconJobs = mutableListOf<Job>()
    private val searchAdapter = SearchResultsAdapter { result ->
        onSearchResultClick(result)
    }
    private val historyAdapter = HistoryAdapter()
    private val continueAdapter = ContinueWatchingAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_main)

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
            enableTvFocusSafe()
            rowSites.columnCount = computeSiteColumns()

            rvSearchResults.layoutManager = LinearLayoutManager(this)
            rvSearchResults.adapter = searchAdapter
            // Listas navegables con mando: sin esto el D-pad no aterriza.
            rvSearchResults.isFocusable = true
            rvSearchResults.isFocusableInTouchMode = false
            rvSearchResults.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS

            btnSearch.setOnClickListener { performGlobalSearch() }

            val btnVoice = findViewById<TextView>(R.id.btn_voice_search)
            // Sin reconocedor de voz (TV sin mic/Google): se oculta en vez de
            // dejar un botón muerto con foco.
            if (!VoiceSearchHelper.isAvailable(this)) {
                btnVoice.visibility = android.view.View.GONE
            } else {
                btnVoice.setOnClickListener {
                    if (!VoiceSearchHelper.startVoiceSearch(this)) {
                        Toast.makeText(this, "Esta TV no tiene búsqueda por voz", Toast.LENGTH_SHORT).show()
                    }
                }
                btnVoice.onActionKey { btnVoice.performClick() }
            }

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
                    // scrollSites es ScrollView no focuseable: ir al primer
                    // site card, o a resultados si la búsqueda ya corrió.
                    val wentToResults = if (rvSearchResults.visibility == View.VISIBLE) {
                        rvSearchResults.requestFocus()
                    } else false
                    if (!wentToResults) {
                        val first = if (rowSites.childCount > 0) rowSites.getChildAt(0) else null
                        if (first != null) first.requestFocus() else scrollSites.requestFocus()
                    }
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
                if (!AppPreferences.isKarinLinkEnabled()) {
                    Toast.makeText(this, "KARIN Link apagado: actívalo en Ajustes", Toast.LENGTH_SHORT).show()
                } else {
                    startActivity(Intent(this, com.karin.streamtv.karinlink.KarinLinkActivity::class.java))
                }
            }
            btnKarinLink.onActionKey { btnKarinLink.performClick() }

            val btnFiles = findViewById<TextView>(R.id.btn_files)
            btnFiles.setOnClickListener { openVideoFilePicker() }
            btnFiles.onActionKey { btnFiles.performClick() }

            findViewById<TextView>(R.id.btn_temp_site)?.apply {
                setOnClickListener { showAddTempSiteDialog() }
                onActionKey { performClick() }
            }

            findViewById<TextView>(R.id.btn_credits).apply {
                setOnClickListener { startActivity(Intent(this@MainActivity, CreditsActivity::class.java)) }
                onActionKey { performClick() }
            }

            findViewById<TextView>(R.id.btn_terms).apply {
                setOnClickListener { startActivity(Intent(this@MainActivity, TermsAndConditionsActivity::class.java)) }
                onActionKey { performClick() }
            }

            findViewById<TextView>(R.id.btn_tutorial).apply {
                setOnClickListener { startActivity(Intent(this@MainActivity, TutorialActivity::class.java)) }
                onActionKey { performClick() }
            }

            historySection = findViewById(R.id.history_section)
            rvHistory = findViewById(R.id.rv_history)
            rvHistory.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            rvHistory.adapter = historyAdapter
            rvHistory.isFocusable = true
            rvHistory.isFocusableInTouchMode = false

            continueSection = findViewById(R.id.continue_section)
            dividerContinue = findViewById(R.id.divider_continue)
            rvContinue = findViewById(R.id.rv_continue)
            rvContinue.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            rvContinue.adapter = continueAdapter
            rvContinue.isFocusable = true
            rvContinue.isFocusableInTouchMode = false

            loadHistory()
            loadContinueWatching()
            maybeShowCrashReport()
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

    // Si la app se cerró la última vez (ej. solo en TV), muestra la causa
    // real en pantalla la próxima vez que abra, para poder reportarla.
    private fun maybeShowCrashReport() {
        val crash = CrashLogger.latestCrash(this) ?: return
        val density = resources.displayMetrics.density
        val traceView = TextView(this).apply {
            text = crash.lineSequence().take(22).joinToString("\n")
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(24, 24, 24, 24)
        }
        val scroll = ScrollView(this).apply {
            addView(traceView, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                (300 * density).toInt(),
            ))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("La app se cerró por un error la última vez")
            .setMessage("Esto ocurrió en este equipo. Cópialo o mándamelo:\n")
            .setView(scroll)
            .setNegativeButton("Borrar") { _, _ -> CrashLogger.clear(this) }
            .setPositiveButton("Listo", null)
            .create()
            .apply {
                // TV/D-pad: el foco no debe quedar detrás del diálogo.
                setOnShowListener {
                    getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.requestFocus()
                }
                show()
            }
    }

    override fun onResume() {
        super.onResume()
        if (!::etSearch.isInitialized) {
            finish()
            return
        }
        applyHighContrastIfNeeded()
        loadHistory()
        loadContinueWatching()
        if (etSearch.text.isNullOrBlank()) {
            if (sitesDirty || rowSites.childCount == 0) {
                sitesDirty = false
                renderSites()
            }
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
        if (requestCode == REQUEST_PICK_VIDEO && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            try {
                val mime = try { contentResolver.getType(uri) } catch (_: Exception) { null }
                val intent = Intent(this, com.karin.streamtv.player.ExoPlayerActivity::class.java).apply {
                    putExtra("video_url", uri.toString())
                    putExtra("referer", "")
                    putExtra("audio_only", mime?.startsWith("audio") == true)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "No se pudo abrir el archivo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openVideoFilePicker() {
        try {
            val intent = Intent(this, FileExplorerActivity::class.java)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir el explorador", Toast.LENGTH_SHORT).show()
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

    override fun onBackPressed() {
        if (::etSearch.isInitialized && etSearch.text.isNotBlank()) {
            etSearch.text.clear()
            showSites()
        } else {
            super.onBackPressed()
        }
    }

    // Sin este callback, denegar el micrófono dejaba el flujo de voz muerto
    // en silencio (el permiso se pide y nunca se responde).
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Toast.makeText(this, "Sin permiso de micrófono: usa el teclado", Toast.LENGTH_SHORT).show()
            } else {
                VoiceSearchHelper.startVoiceSearch(this)
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        logoCache.evictAll()
        // Solo en memoria crítica se sueltan vistas (se re-renderizan en onResume).
        rowSites.removeAllViews()
        sitesDirty = true
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // MODERATE no suelta vistas: antes dejaba la parrilla vacía con
        // búsqueda activa (pantalla en negro sin foco D-pad).
        logoCache.evictAll()
    }

    /** Marcada cuando onLowMemory soltó la parrilla: onResume la reconstruye. */
    private var sitesDirty = false

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
            // Con mando el foco quedaba en la barra de búsqueda: moverlo a
            // la lista para uso 100% con control remoto.
            if (isTvDevice) {
                rvSearchResults.post {
                    if (!rvSearchResults.requestFocus()) {
                        rvSearchResults.getChildAt(0)?.requestFocus()
                    }
                }
            }
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

    private fun enableTvFocusSafe() {
        try {
            enableTvFocus()
        } catch (_: Exception) { }
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

    private fun computeSiteColumns(): Int {
        val widthPx = resources.displayMetrics.widthPixels
        val density = resources.displayMetrics.density
        val widthDp = widthPx / density
        return when {
            widthDp >= 960 -> 8
            widthDp >= 720 -> 6
            widthDp >= 540 -> 4
            else -> 3
        }
    }

    private fun renderSites() {
        faviconJobs.forEach { it.cancel() }
        faviconJobs.clear()
        rowSites.removeAllViews()

        val sites = siteManager.getSites()

        if (sites.isEmpty()) {
            if (::tvSearchEmpty.isInitialized) {
                tvSearchEmpty.text = "No hay sitios configurados"
                tvSearchEmpty.visibility = View.VISIBLE
            }
            return
        }

        if (::tvSearchEmpty.isInitialized) {
            tvSearchEmpty.visibility = View.GONE
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
            val color = SiteBranding.brandColors[site.name] ?: Color.parseColor("#555555")
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

        val faviconUrl = SiteBranding.siteLogos[site.name]
        faviconJobs += lifecycleScope.launch {
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

        tvName.text = if (site.isTemporary) "⏳ ${site.name}" else site.name
        tvUrl.text = if (site.isTemporary) "${site.url} · temporal" else site.url
        // Marca visual tenue para temporales (TV y táctil).
        card.alpha = if (site.isTemporary) 0.92f else 1f

        card.setOnClickListener {
            openBrowser(site)
        }

        // Mantén pulsado (o MENU en TV) en un temporal: Guardar / Descartar.
        card.setOnLongClickListener {
            if (site.isTemporary) {
                showTempSiteOptions(site)
                true
            } else false
        }

        card.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER -> {
                        openBrowser(site)
                        true
                    }
                    KeyEvent.KEYCODE_MENU -> {
                        if (site.isTemporary) {
                            showTempSiteOptions(site)
                            true
                        } else false
                    }
                    else -> false
                }
            } else false
        }

        return card
    }

    private fun openBrowser(site: SiteConfig) {
        siteManager.touchLastVisited(site.id)
        if (site.isTemporary) {
            // El navegador genérico necesita un scraper aunque no haya parser propio.
            ScraperRegistry.registerTempSite(site.name, site.url)
        }
        val intent = Intent(this, SiteBrowserActivity::class.java).apply {
            putExtra("site_id", site.id)
            putExtra("site_name", site.name)
            putExtra("site_url", site.url)
            putExtra("is_temporary", site.isTemporary)
        }
        startActivity(intent)
    }

    // region --- Páginas temporales ---

    private fun showAddTempSiteDialog() {
        val ctx = this
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val etName = EditText(ctx).apply {
            hint = "Nombre (opcional, ej. MiPágina)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        val etUrl = EditText(ctx).apply {
            hint = "URL (ej. https://ejemplo.com)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val tvHint = TextView(ctx).apply {
            text = "⏳ Solo vive en memoria: se borra al cerrar la app. No se guarda en disco."
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            textSize = 12f
            setPadding(0, padding / 2, 0, 0)
        }
        container.addView(etName)
        container.addView(etUrl)
        container.addView(tvHint)

        val dlg = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("🌐 Añadir página temporal")
            .setView(container)
            .setPositiveButton("Abrir", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dlg.setOnShowListener {
            val btnOpen = dlg.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            btnOpen?.requestFocus()
            btnOpen?.setOnClickListener {
                val rawName = etName.text.toString()
                val rawUrl = etUrl.text.toString()
                val built = com.karin.streamtv.util.TempSiteHelper.buildTempSite(rawName, rawUrl)
                if (built == null) {
                    etUrl.error = "URL no válida"
                    etUrl.requestFocus()
                    return@setOnClickListener
                }
                val added = siteManager.addTemporarySite(built)
                if (added == null) {
                    Toast.makeText(ctx, "Esa página ya está en la lista", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                ScraperRegistry.registerTempSite(added.name, added.url)
                dlg.dismiss()
                renderSites()
                Toast.makeText(ctx, "⏳ '${added.name}' temporal: no se guardará", Toast.LENGTH_SHORT).show()
                openBrowser(added)
            }
            etUrl.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    btnOpen?.performClick()
                    true
                } else false
            }
        }
        dlg.show()
    }

    private fun showTempSiteOptions(site: SiteConfig) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⏳ ${site.name} (temporal)")
            .setMessage("${site.url}\n\nEsta página solo vive en memoria y se borra al cerrar la app.")
            .setPositiveButton("💾 Guardar") { _, _ ->
                val permanent = siteManager.promoteTemporary(site.id)
                if (permanent != null) {
                    renderSites()
                    Toast.makeText(this, "'${permanent.name}' guardada", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("🗑 Descartar") { _, _ ->
                siteManager.discardTemporary(site.id)
                ScraperRegistry.unregister(site.name)
                renderSites()
                Toast.makeText(this, "Página temporal descartada", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Cancelar", null)
            .create()
            .apply {
                setOnShowListener { getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.requestFocus() }
                show()
            }
    }

    // endregion

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
                    if (bmp != null && holder.bindingAdapterPosition == position) {
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
                    if (bmp != null && holder.bindingAdapterPosition == position) {
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

    override fun onDestroy() {
        searchAdapter.destroy()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_PICK_VIDEO = 4101
    }
}
