package com.karin.streamtv.ui.tv

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
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
import com.karin.streamtv.scraper.ScrapingEngine
import com.karin.streamtv.util.AppPreferences
import com.karin.streamtv.util.SiteManager
import com.karin.streamtv.util.WatchHistory
import com.karin.streamtv.ui.SiteBrowserActivity

class TvMainActivity : FragmentActivity() {

    private lateinit var siteManager: SiteManager
    private lateinit var rowsAdapter: ArrayObjectAdapter

    private val brandColors = mapOf(
        "JKAnime" to Color.parseColor("#1E88E5"),
        "LatAnime" to Color.parseColor("#43A047"),
        "DoramasYT" to Color.parseColor("#00BCD4"),
        "MundoDonghua" to Color.parseColor("#8E24AA"),
        "RetroTVE" to Color.parseColor("#FF9800"),
        "LaCartoons" to Color.parseColor("#E91E63"),
        "FrikiSeries" to Color.parseColor("#1B5E20"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_main)

        ScrapingEngine.init(this)
        AppPreferences.init(this)
        siteManager = SiteManager(this)

        val browseFragment = supportFragmentManager
            .findFragmentById(R.id.browse_container) as BrowseSupportFragment

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
    }

    override fun onResume() {
        super.onResume()
        loadRows()
    }

    private fun loadRows() {
        rowsAdapter.clear()

        val sites = siteManager.getSites()
        if (sites.isNotEmpty()) {
            val sitePresenter = TvSiteCardPresenter(brandColors)
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
