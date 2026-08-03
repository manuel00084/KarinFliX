package com.karin.streamtv.ui.tv

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import com.karin.streamtv.R
import com.karin.streamtv.model.SiteConfig
import com.karin.streamtv.model.Episode
import com.karin.streamtv.scraper.ScraperRegistry
import com.karin.streamtv.util.SiteBranding
import com.karin.streamtv.util.SiteManager
import com.karin.streamtv.util.WatchHistory
import com.karin.streamtv.ui.SiteBrowserActivity

class TvMainActivity : FragmentActivity() {

    private lateinit var siteManager: SiteManager
    private lateinit var rowsAdapter: ArrayObjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_tv_main)

            siteManager = SiteManager(this)

            val browseFragment = supportFragmentManager
                .findFragmentById(R.id.browse_container) as? BrowseSupportFragment

            if (browseFragment == null) {
                showError("BrowseSupportFragment is null")
                return
            }

            browseFragment.apply {
                title = "KarinFLiX"
                brandColor = ContextCompat.getColor(this@TvMainActivity, R.color.azul_rey)
                headersState = BrowseSupportFragment.HEADERS_ENABLED
                isHeadersTransitionOnBackEnabled = true

                rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
                adapter = rowsAdapter

                setOnItemViewClickedListener(ItemViewClickedListener())
                setOnSearchClickedListener {
                    startActivity(Intent(this@TvMainActivity, SiteBrowserActivity::class.java).apply {
                        putExtra("site_id", "search")
                        putExtra("site_name", "Buscar")
                        putExtra("site_url", "")
                    })
                }
            }

            loadRows()
        } catch (e: Exception) {
            Log.e("TvMainActivity", "FATAL onCreate: ${e.message}", e)
            showError("Crash: ${e.message}\n\n${e.stackTraceToString()}")
        }
    }

    private fun showError(msg: String) {
        Log.e("TvMainActivity", msg)
        val tv = TextView(this)
        tv.text = msg
        tv.setTextSize(12f)
        tv.setTextColor(Color.WHITE)
        tv.setBackgroundColor(Color.BLACK)
        tv.setPadding(24, 24, 24, 24)
        setContentView(tv)
    }

    override fun onResume() {
        super.onResume()
        loadRows()
    }

    private fun loadRows() {
        try {
            rowsAdapter.clear()

            val sites = siteManager.getSites()
            if (sites.isNotEmpty()) {
                val sitePresenter = TvSiteCardPresenter()
                val siteAdapter = ArrayObjectAdapter(sitePresenter)
                sites.forEach { siteAdapter.add(it) }
                rowsAdapter.add(ListRow(HeaderItem(0, "Sitios"), siteAdapter))
            }

            val continueEntries = WatchHistory.getContinueWatching(10)
            if (continueEntries.isNotEmpty()) {
                val continuePresenter = TvEpisodeCardPresenter()
                val continueAdapter = ArrayObjectAdapter(continuePresenter)
                continueEntries.forEach { entry ->
                    continueAdapter.add(Episode(
                        title = entry.title,
                        url = entry.episodeUrl,
                        thumbnailUrl = entry.thumbnailUrl,
                        siteName = entry.siteName,
                        episodeNum = "Ep. ${entry.episodeNumber}"
                    ))
                }
                rowsAdapter.add(ListRow(HeaderItem(1, "Continue viendo"), continueAdapter))
            }

            val recentHistory = WatchHistory.getRecentEntries(10)
            if (recentHistory.isNotEmpty()) {
                val historyPresenter = TvEpisodeCardPresenter()
                val historyAdapter = ArrayObjectAdapter(historyPresenter)
                recentHistory.forEach { entry ->
                    historyAdapter.add(Episode(
                        title = entry.title,
                        url = entry.episodeUrl,
                        thumbnailUrl = entry.thumbnailUrl,
                        siteName = entry.siteName,
                        episodeNum = "Ep. ${entry.episodeNumber}"
                    ))
                }
                rowsAdapter.add(ListRow(HeaderItem(2, "Historial"), historyAdapter))
            }
        } catch (e: Exception) {
            Log.e("TvMainActivity", "loadRows error: ${e.message}", e)
        }
    }

    private inner class ItemViewClickedListener : OnItemViewClickedListener {
        override fun onItemClicked(
            itemViewHolder: Presenter.ViewHolder?,
            item: Any?,
            rowViewHolder: RowPresenter.ViewHolder?,
            row: Row?
        ) {
            when (item) {
                is SiteConfig -> {
                    siteManager.touchLastVisited(item.id)
                    startActivity(Intent(this@TvMainActivity, SiteBrowserActivity::class.java).apply {
                        putExtra("site_id", item.id)
                        putExtra("site_name", item.name)
                        putExtra("site_url", item.url)
                    })
                }
                is Episode -> {
                    val scraper = com.karin.streamtv.scraper.ScraperRegistry.getScraper(item.siteName)
                    if (scraper != null) {
                        startActivity(Intent(this@TvMainActivity, SiteBrowserActivity::class.java).apply {
                            putExtra("site_id", item.siteName)
                            putExtra("site_name", item.siteName)
                            putExtra("site_url", scraper.baseUrl)
                            putExtra("autoplay_url", item.url)
                            putExtra("autoplay_title", item.title)
                        })
                    }
                }
            }
        }
    }
}
