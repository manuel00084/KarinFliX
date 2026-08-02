package com.karin.streamtv.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.karin.streamtv.R
import com.karin.streamtv.model.VideoServer
import com.karin.streamtv.model.VideoSource
import com.karin.streamtv.share.ShareManager
import com.karin.streamtv.util.AppPreferences
import com.karin.streamtv.karinlink.KarinLinkManager
import com.karin.streamtv.karinlink.DiscoveryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.karin.streamtv.util.onActionKey

class SourcePickerDialog(
    context: Context,
    private val sources: List<VideoSource>,
    private val episodeTitle: String = "",
    private val episodeUrl: String = "",
    private val siteName: String = "",
    private val onSourceSelected: (VideoSource) -> Unit
) : Dialog(context) {

    private var karinLinkManager: KarinLinkManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = context.getDrawable(R.drawable.bg_card)
        }

        val title = TextView(context).apply {
            text = "📋 Seleccionar Fuente"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(0, 0, 0, dp(8))
        }
        container.addView(title)

        val subtitle = TextView(context).apply {
            text = "${sources.size} servidores • ⭐ = recomendado • 🟢 = resolución variable"
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 11f
            setPadding(0, 0, 0, dp(12))
        }
        container.addView(subtitle)

        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
        }

        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val sortedSources = sources.sortedByDescending { source ->
            val server = VideoServer.detectServer(source.serverUrl)
            server.priority
        }

        sortedSources.forEachIndexed { index, source ->
            val item = createSourceItem(source, index)
            listContainer.addView(item)
        }

        if (episodeUrl.isNotBlank()) {
            listContainer.addView(createDivider())
            listContainer.addView(createShareSection())
        }

        scrollView.addView(listContainer)
        container.addView(scrollView)

        setContentView(container)

        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_BACK -> {
                        dismiss()
                        true
                    }
                    else -> false
                }
            } else false
        }
    }

    private fun createDivider(): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(12)
            }
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        }
    }

    private fun createShareSection(): LinearLayout {
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }

        val shareTitle = TextView(context).apply {
            text = "📤 Compartir episodio"
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        }
        section.addView(shareTitle)

        val shareRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        shareRow.addView(createShareButton("💬 WhatsApp", "#25D366") {
            val data = ShareManager.ShareData(
                title = "KarinFLiX",
                episodeTitle = episodeTitle,
                episodeUrl = episodeUrl,
                siteName = siteName
            )
            ShareManager.shareToWhatsApp(context, data)
        })

        shareRow.addView(createShareButton("📘 Facebook", "#1877F2") {
            val data = ShareManager.ShareData(
                title = "KarinFLiX",
                episodeTitle = episodeTitle,
                episodeUrl = episodeUrl,
                siteName = siteName
            )
            ShareManager.shareToFacebook(context, data)
        })

        val karinLinkEnabled = AppPreferences.isKarinLinkEnabled()
        if (karinLinkEnabled) {
            shareRow.addView(createShareButton("🔗 KARIN Link", "#FFEB3B") {
                showKarinLinkDevicePicker()
            })
        }

        section.addView(shareRow)

        return section
    }

    private fun createShareButton(text: String, bgColor: String, onClick: () -> Unit): View {
        val bg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor(bgColor))
        }
        return TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = bg
            isFocusable = true
            isFocusableInTouchMode = true
            val lp = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginEnd = dp(6)
            }
            layoutParams = lp
            setOnFocusChangeListener { v, hasFocus ->
                v.alpha = if (hasFocus) 1.0f else 0.8f
                bg.setStroke(if (hasFocus) dp(2) else 0, if (hasFocus) Color.WHITE else Color.TRANSPARENT)
            }
            setOnClickListener { onClick() }
            onActionKey { onClick() }
        }
    }

    private fun showKarinLinkDevicePicker() {
        try {
            val manager = KarinLinkManager(context)
            manager.start()
            karinLinkManager = manager
        } catch (e: Exception) {
            Toast.makeText(context, "Error al iniciar KARIN Link: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        val deviceDialog = Dialog(context)
        deviceDialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        deviceDialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = context.getDrawable(R.drawable.bg_card)
        }

        container.addView(TextView(context).apply {
            text = "🔗 KARIN Link - Dispositivos"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 0, 0, dp(8))
        })

        val statusText = TextView(context).apply {
            text = "Buscando dispositivos en la red..."
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        }
        container.addView(statusText)

        val lanTitle = TextView(context).apply {
            text = "📡 Red Local (LAN)"
            setTextColor(Color.parseColor("#6C63FF"))
            textSize = 14f
            setPadding(0, 0, 0, dp(6))
        }
        container.addView(lanTitle)

        val lanContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        container.addView(lanContainer)

        val lanPlaceholder = TextView(context).apply {
            text = "Buscando dispositivos..."
            setTextColor(Color.parseColor("#6B7280"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(12))
        }
        lanContainer.addView(lanPlaceholder)

        val internetTitle = TextView(context).apply {
            text = "🌐 Internet (WAN)"
            setTextColor(Color.parseColor("#22C55E"))
            textSize = 14f
            setPadding(0, 0, 0, dp(6))
        }
        container.addView(internetTitle)

        val internetContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        container.addView(internetContainer)

        internetContainer.addView(TextView(context).apply {
            text = "Conecta una sala para compartir por internet"
            setTextColor(Color.parseColor("#6B7280"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        })

        val shareBtn = TextView(context).apply {
            text = "📤 Compartir enlace de sala"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#FF9800"))
            }
            isFocusable = true
            isFocusableInTouchMode = true
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
            layoutParams = lp
            setOnClickListener {
                val data = ShareManager.ShareData(
                    title = "KarinFLiX",
                    episodeTitle = episodeTitle,
                    episodeUrl = episodeUrl,
                    siteName = siteName
                )
                ShareManager.shareGeneric(context, data)
            }
            onActionKey {
                val data = ShareManager.ShareData(
                    title = "KarinFLiX",
                    episodeTitle = episodeTitle,
                    episodeUrl = episodeUrl,
                    siteName = siteName
                )
                ShareManager.shareGeneric(context, data)
            }
        }
        container.addView(shareBtn)

        val closeBtn = TextView(context).apply {
            text = "Cerrar"
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
            isFocusable = true
            setOnClickListener {
                karinLinkManager?.stop()
                deviceDialog.dismiss()
            }
            onActionKey {
                karinLinkManager?.stop()
                deviceDialog.dismiss()
            }
        }
        container.addView(closeBtn)

        deviceDialog.setContentView(container)

        val manager = karinLinkManager ?: return
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        scope.launch {
            manager.discoveryManager.devices.collect { devices ->
                lanContainer.removeAllViews()
                if (devices.isEmpty()) {
                    lanContainer.addView(TextView(context).apply {
                        text = "No se encontraron dispositivos en la red local"
                        setTextColor(Color.parseColor("#6B7280"))
                        textSize = 12f
                        setPadding(0, dp(4), 0, 0)
                    })
                } else {
                    statusText.text = "${devices.size} dispositivo(s) encontrado(s)"
                    devices.forEach { device ->
                        lanContainer.addView(createDeviceItem(device))
                    }
                }
            }
        }

        scope.launch {
            manager.status.collect { status ->
                statusText.text = status
            }
        }

        deviceDialog.setOnDismissListener {
            karinLinkManager?.stop()
            scope.cancel()
        }

        deviceDialog.show()
    }

    private fun createDeviceItem(device: DiscoveryManager.DiscoveredDevice): View {
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#1AFFFFFF"))
            }
            isFocusable = true
            isFocusableInTouchMode = true
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(4)
            }
            layoutParams = lp
        }

        item.addView(TextView(context).apply {
            text = "📱 ${device.displayName}"
            setTextColor(Color.WHITE)
            textSize = 14f
        })

        item.addView(TextView(context).apply {
            text = "${device.host}:${device.port} • v${device.appVersion}"
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 11f
            setPadding(0, dp(2), 0, 0)
        })

        item.setOnClickListener {
            karinLinkManager?.connectToDevice(device)
            Toast.makeText(context, "Conectando a ${device.displayName}...", Toast.LENGTH_SHORT).show()
        }

        item.onActionKey { item.performClick() }

        return item
    }

    private fun createSourceItem(source: VideoSource, index: Int): View {
        val isBest = index == 0
        val server = VideoServer.detectServer(source.serverUrl)
        val displayRating = adjustRatingWithPing(server.speedRating, source.pingMs)

        val item = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = if (isBest) {
                context.getDrawable(R.drawable.bg_recommended)
            } else {
                context.getDrawable(R.drawable.bg_input)
            }
            isFocusable = true
            isFocusableInTouchMode = true
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = dp(8)
            layoutParams = params
        }

        if (isBest) {
            val starBadge = TextView(context).apply {
                text = "⭐"
                textSize = 18f
                setPadding(0, 0, dp(6), 0)
                gravity = Gravity.CENTER_VERTICAL
            }
            item.addView(starBadge)
        }

        val serverIcon = TextView(context).apply {
            text = getServerIcon(server)
            textSize = 20f
            setPadding(0, 0, dp(10), 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        item.addView(serverIcon)

        val infoContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = params
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val serverName = TextView(context).apply {
            text = source.name
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 0, dp(6), 0)
        }
        topRow.addView(serverName)

        if (isBest) {
            topRow.addView(makeBadge("⭐ Recomendado", "#22C55E"))
        }

        infoContainer.addView(topRow)

        val detailsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val serverDomain = TextView(context).apply {
            text = getServerDomain(source.serverUrl)
            setTextColor(Color.parseColor("#D1D5DB"))
            textSize = 10f
            setPadding(0, 0, dp(6), 0)
        }
        detailsRow.addView(serverDomain)

        val usesHttp = com.karin.streamtv.scraper.ServerDirectResolver.usesHttpResolver(source.serverUrl)
        detailsRow.addView(makeBadge(if (usesHttp) "⚡ ExoPlayer" else "🌐 WebView", if (usesHttp) "#7C3AED" else "#475569"))

        detailsRow.addView(makeBadge(getSpeedLabel(displayRating, source.pingMs), getSpeedBgColor(displayRating)))

        if (server.supportsResolution) {
            detailsRow.addView(makeBadge("🔄 Variable", "#059669"))
        } else {
            detailsRow.addView(makeBadge("🔒 Fija", "#6B7280"))
        }

        infoContainer.addView(detailsRow)

        item.addView(infoContainer)

        val arrow = TextView(context).apply {
            text = "›"
            setTextColor(Color.parseColor("#6C63FF"))
            textSize = 20f
            gravity = Gravity.CENTER_VERTICAL
        }
        item.addView(arrow)

        item.setOnFocusChangeListener { v, hasFocus ->
            v.alpha = if (hasFocus) 1.0f else 0.85f
            (v.background as? android.graphics.drawable.GradientDrawable)?.setStroke(
                if (hasFocus) dp(2) else 0,
                if (hasFocus) Color.parseColor("#6C63FF") else Color.TRANSPARENT
            )
        }

        var sourceConsumed = false
        item.setOnClickListener {
            if (sourceConsumed) return@setOnClickListener
            sourceConsumed = true
            onSourceSelected(source)
            dismiss()
        }

        item.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_BUTTON_A -> {
                        if (sourceConsumed) return@setOnKeyListener true
                        sourceConsumed = true
                        onSourceSelected(source)
                        dismiss()
                        true
                    }
                    else -> false
                }
            } else false
        }

        return item
    }

    private fun makeBadge(text: String, bgColor: String): TextView {
        val bg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(4).toFloat()
            setColor(Color.parseColor(bgColor))
        }
        return TextView(context).apply {
            this.text = text
            textSize = 9f
            setTextColor(Color.WHITE)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = bg
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(4)
            layoutParams = lp
        }
    }

    private fun getServerIcon(server: VideoServer): String {
        return when (server.speedRating) {
            5 -> "🚀"
            4 -> "⚡"
            3 -> "📡"
            else -> "🔗"
        }
    }

    private fun getSpeedLabel(rating: Int, pingMs: Long): String {
        val label = when (rating) {
            5 -> "🚀 Máxima"
            4 -> "⚡ Alta"
            3 -> "📡 Media"
            2 -> "🔗 Lenta"
            else -> "🔗 Muy lenta"
        }
        if (pingMs < 0) return label
        return "$label • ${formatPing(pingMs)}"
    }

    private fun formatPing(pingMs: Long): String {
        return if (pingMs < 1000) "${pingMs}ms" else "${pingMs / 1000}s"
    }

    private fun getSpeedBgColor(rating: Int): String {
        return when (rating) {
            5 -> "#22C55E"
            4 -> "#3B82F6"
            3 -> "#F59E0B"
            2 -> "#EF4444"
            else -> "#DC2626"
        }
    }

    private fun adjustRatingWithPing(rating: Int, pingMs: Long): Int {
        if (pingMs < 0) return rating
        return when {
            pingMs in 1..199 -> minOf(rating + 1, 5)
            pingMs in 200..499 -> rating
            pingMs in 500..1499 -> maxOf(rating - 1, 1)
            else -> maxOf(rating - 2, 1)
        }
    }

    private fun getServerDomain(url: String): String {
        return try {
            val host = java.net.URL(url).host
            host.removePrefix("www.")
        } catch (e: Exception) {
            url.take(40)
        }
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
