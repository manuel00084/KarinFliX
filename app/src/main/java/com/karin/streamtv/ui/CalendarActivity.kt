package com.karin.streamtv.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.karin.streamtv.R
import com.karin.streamtv.model.CalendarItem
import com.karin.streamtv.scraper.CalendarParser
import com.karin.streamtv.scraper.ScrapingEngine
import com.karin.streamtv.scraper.ScraperRegistry
import com.karin.streamtv.util.DeviceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CalendarActivity : AppCompatActivity() {

    private lateinit var rvDays: RecyclerView
    private lateinit var rvSeries: RecyclerView
    private lateinit var loadingOverlay: android.widget.FrameLayout
    private lateinit var tvEmpty: TextView

    private var allDays: List<CalendarParser.CalendarDay> = emptyList()
    private var dayNames: List<String> = emptyList()
    private var selectedDayIndex: Int = 0
    private var dayTabAdapter: DayTabAdapter? = null
    private var seriesAdapter: CalendarSeriesAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calendar)

        rvDays = findViewById(R.id.rv_days)
        rvSeries = findViewById(R.id.rv_series)
        loadingOverlay = findViewById(R.id.loading_overlay)
        tvEmpty = findViewById(R.id.tv_empty)

        val isTv = DeviceUtils.isTvDevice(this)

        findViewById<TextView>(R.id.btn_back).setOnClickListener { finish() }

        rvDays.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvSeries.layoutManager = if (isTv) {
            GridLayoutManager(this, 4)
        } else {
            GridLayoutManager(this, 2)
        }

        val autoDay = intent.getIntExtra("day_index", -1)
        loadCalendar(autoDay)
    }

    private fun loadCalendar(preselectDay: Int) {
        loadingOverlay.visibility = android.view.View.VISIBLE
        tvEmpty.visibility = android.view.View.GONE

        lifecycleScope.launch {
            try {
                val days = withContext(Dispatchers.IO) {
                    val doc = ScrapingEngine.fetch(
                        "https://latanime.org/calendario",
                        "LatAnime",
                        "LatAnime::calendario",
                        forceFresh = false
                    )
                    if (doc != null) CalendarParser.parse(doc) else emptyList()
                }

                loadingOverlay.visibility = android.view.View.GONE

                if (days.isEmpty()) {
                    tvEmpty.visibility = android.view.View.VISIBLE
                    return@launch
                }

                allDays = days
                dayNames = days.map { it.name }

                val defaultIndex = if (preselectDay in days.indices) preselectDay else {
                    val cal = java.util.Calendar.getInstance()
                    val dow = cal.get(java.util.Calendar.DAY_OF_WEEK)
                    when (dow) {
                        java.util.Calendar.MONDAY -> 0
                        java.util.Calendar.TUESDAY -> 1
                        java.util.Calendar.WEDNESDAY -> 2
                        java.util.Calendar.THURSDAY -> 3
                        java.util.Calendar.FRIDAY -> 4
                        java.util.Calendar.SATURDAY -> 5
                        java.util.Calendar.SUNDAY -> 6
                        else -> 0
                    }
                }

                selectedDayIndex = defaultIndex.coerceIn(0, days.size - 1)

                dayTabAdapter = DayTabAdapter(dayNames, selectedDayIndex) { index ->
                    selectedDayIndex = index
                    dayTabAdapter = DayTabAdapter(dayNames, selectedDayIndex) { idx -> selectDay(idx) }
                    rvDays.adapter = dayTabAdapter
                    showDay(index)
                }
                rvDays.adapter = dayTabAdapter

                showDay(selectedDayIndex)

                if (isTvDevice() && rvDays.childCount > selectedDayIndex) {
                    rvDays.getChildAt(selectedDayIndex)?.requestFocus()
                }
            } catch (e: Exception) {
                Log.e("CalendarActivity", "loadCalendar error: ${e.message}", e)
                loadingOverlay.visibility = android.view.View.GONE
                tvEmpty.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun selectDay(index: Int) {
        selectedDayIndex = index
        dayTabAdapter = DayTabAdapter(dayNames, selectedDayIndex) { idx -> selectDay(idx) }
        rvDays.adapter = dayTabAdapter
        showDay(index)
    }

    private fun showDay(index: Int) {
        if (index !in allDays.indices) return
        val day = allDays[index]

        if (day.items.isEmpty()) {
            tvEmpty.visibility = android.view.View.VISIBLE
            rvSeries.visibility = android.view.View.GONE
        } else {
            tvEmpty.visibility = android.view.View.GONE
            rvSeries.visibility = android.view.View.VISIBLE
            seriesAdapter = CalendarSeriesAdapter(day.items) { item ->
                openSeries(item)
            }
            rvSeries.adapter = seriesAdapter
            rvSeries.scrollToPosition(0)
        }

        rvDays.scrollToPosition(index)
    }

    private fun openSeries(item: CalendarItem) {
        val scraper = ScraperRegistry.getScraper("LatAnime")
        val siteUrl = scraper?.baseUrl ?: "https://latanime.org"
        val intent = Intent(this, SiteBrowserActivity::class.java).apply {
            putExtra("site_name", "LatAnime")
            putExtra("site_url", siteUrl)
            putExtra("autoplay_url", item.url)
            putExtra("autoplay_title", item.title)
        }
        startActivity(intent)
    }

    private fun isTvDevice() = DeviceUtils.isTvDevice(this)

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val mapped = com.karin.streamtv.util.GamepadHelper.mapGamepadToDpad(keyCode)
        if (mapped != keyCode) return onKeyDown(mapped, event)
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        seriesAdapter?.destroy()
        super.onDestroy()
    }
}
