package com.karin.streamtv.karinlink

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Mantiene el servidor Karin Link encendido a nivel de aplicación para que
 * el control remoto funcione sin tener abierta la pantalla de Karin Link
 * (p. ej. mientras se ve un capítulo).
 *
 * Se arranca en [com.karin.streamtv.KarinTVApplication.onCreate] y vive
 * mientras el proceso viva. Solo atiende comandos `remote.*`; el resto
 * (`sync`, etc.) lo siguen gestionando las actividades abiertas.
 */
object KarinLinkHost {

    private const val TAG = "KarinLinkHost"
    private const val PREFS_NAME = "karin_link"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"

    @Volatile var isRunning = false
        private set

    private var discovery: DiscoveryManager? = null
    private var appContext: Context? = null

    private val hostListener: (from: String?, type: String, data: JSONObject) -> Unit =
        { _, type, data ->
            when {
                RemoteProtocol.isRemote(type) -> {
                    appContext?.let { RemoteControlHub.handle(it, type, data) }
                }
                // Capítulo compartido para reproducir en este equipo.
                type == "sync" -> {
                    appContext?.let { handleSync(it, data) }
                }
            }
        }

    /**
     * Auto-reproduce un capítulo enviado por otro equipo, aunque la pantalla
     * de Karin Link no esté abierta. Si sí lo está, ella lo gestiona.
     */
    private fun handleSync(app: Context, data: JSONObject) {
        val embed = data.optString("embedUrl")
        if (embed.isBlank()) return
        val current = com.karin.streamtv.util.AppActivityHolder.current()
        if (current is KarinLinkActivity) return
        try {
            val intent = android.content.Intent(
                app,
                com.karin.streamtv.ui.EmbedWebViewActivity::class.java,
            ).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("embed_url", embed)
                putExtra("video_title", data.optString("episodeTitle", ""))
                putExtra("episode_url", data.optString("episodeUrl", ""))
                putExtra("episode_number", 0)
            }
            app.startActivity(intent)
            Log.i(TAG, "Auto-playing shared episode: ${data.optString("episodeTitle", "")}")
        } catch (e: Exception) {
            Log.w(TAG, "handleSync failed: ${e.message}")
        }
    }

    @Synchronized
    fun start(ctx: Context) {
        if (isRunning) return
        try {
            val app = ctx.applicationContext
            appContext = app
            val port = LinkServer.start(0)
            if (port <= 0) {
                Log.w(TAG, "LinkServer did not bind")
                return
            }
            LinkServer.addListener(hostListener)
            val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val deviceId = prefs.getString(KEY_DEVICE_ID, null)
                ?: java.util.UUID.randomUUID().toString().also {
                    prefs.edit().putString(KEY_DEVICE_ID, it).apply()
                }
            val deviceName = prefs.getString(KEY_DEVICE_NAME, null)
                ?: android.provider.Settings.Global.getString(app.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
                ?: "KarinFLiX-$deviceId"
            discovery = DiscoveryManager(app).also {
                it.registerService(port, deviceId, deviceName)
                it.startDiscovery()
            }
            isRunning = true
            Log.i(TAG, "Host started on port $port as $deviceName")
        } catch (e: Exception) {
            Log.w(TAG, "Host start failed: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        LinkServer.removeListener(hostListener)
        // No se detiene LinkServer aquí: otras pantallas pueden estar usándolo.
        try {
            discovery?.destroy()
        } catch (_: Exception) {
        }
        discovery = null
        Log.i(TAG, "Host stopped")
    }
}
