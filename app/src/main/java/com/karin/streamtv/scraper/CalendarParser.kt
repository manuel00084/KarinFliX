package com.karin.streamtv.scraper

import android.util.Log
import com.karin.streamtv.model.CalendarItem
import com.karin.streamtv.util.HtmlClean
import org.jsoup.nodes.Document

object CalendarParser {

    private const val TAG = "CalendarParser"

    data class CalendarDay(
        val name: String,
        val items: List<CalendarItem>
    )

    private val dayIds = listOf(
        "lunes", "martes", "miercoles", "jueves",
        "viernes", "sabado", "domingo", "otros"
    )

    private val dayNames = listOf(
        "Lunes", "Martes", "Miercoles", "Jueves",
        "Viernes", "Sabado", "Domingo", "Otros"
    )

    fun parse(doc: Document): List<CalendarDay> {
        val days = mutableListOf<CalendarDay>()

        for (i in dayIds.indices) {
            val paneId = "${dayIds[i]}-tap-pane"
            val pane = doc.selectFirst("#$paneId") ?: doc.selectFirst("[id*='${dayIds[i]}']") ?: continue

            val items = pane.select("li.col, li[class*=col]").mapNotNull { li ->
                try {
                    val link = li.selectFirst("a[href]") ?: return@mapNotNull null
                    val href = link.attr("abs:href").ifBlank {
                        HtmlClean.resolveUrl(link.baseUri(), link.attr("href"))
                    }.ifBlank { return@mapNotNull null }

                    val img = link.selectFirst("img")
                    val thumb = img?.let {
                        HtmlClean.resolveUrl(link.baseUri(), it.attr("data-src").ifBlank {
                            it.attr("data-lazy-src").ifBlank {
                                it.attr("abs:src").ifBlank { "" }
                            }
                        })
                    } ?: ""

                    val badge = li.selectFirst("span.badge")
                    val nextEp = badge?.text()?.trim() ?: ""

                    val rawTitle = li.selectFirst("h3")?.text()?.trim()
                        ?: link.attr("title").trim()
                        ?: img?.attr("alt")?.trim()
                        ?: ""
                    val title = HtmlClean.clean(rawTitle)

                    if (title.isBlank() || href.isBlank()) return@mapNotNull null

                    CalendarItem(title, href, thumb, nextEp)
                } catch (e: Exception) {
                    Log.w(TAG, "Parse item error: ${e.message}")
                    null
                }
            }

            days.add(CalendarDay(dayNames[i], items))
            Log.d(TAG, "Day '${dayNames[i]}': ${items.size} series")
        }

        return days
    }
}
