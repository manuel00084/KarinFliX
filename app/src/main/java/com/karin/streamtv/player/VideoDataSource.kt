package com.karin.streamtv.player

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Central place that builds the HTTP engine used for streaming.
 *
 * Uses Cronet (HTTP/2 + HTTP/3/QUIC, DNS-over-HTTPS) when a provider is
 * available, falling back to [DefaultHttpDataSource] otherwise so playback
 * never breaks on devices without a Cronet provider (e.g. TVs without GMS).
 */
object VideoDataSource {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // Timeouts generosos para streaming: los default de Media3 (8s) causan fallos
    // de buffer en redes lentas. Connect corto pero read largo para no interrumpir
    // un stream ya establecido.
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 45_000

    @Volatile
    private var cronetEngine: CronetEngine? = null

    @Volatile
    private var cronetInitialized = false

    private val cronetExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private fun getCronetEngine(context: Context): CronetEngine? {
        if (!cronetInitialized) {
            cronetInitialized = true
            cronetEngine = try {
                CronetProvider.getAllProviders(context)
                    .firstOrNull { it.isEnabled }
                    ?.createBuilder()
                    ?.setUserAgent(USER_AGENT)
                    ?.build()
            } catch (_: Throwable) {
                null
            }
        }
        return cronetEngine
    }

    private fun httpHeaders(referer: String): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        if (referer.isNotBlank()) {
            headers["Referer"] = if (referer.startsWith("http")) referer else "https://$referer"
        }
        return headers
    }

    /**
     * Factory that produces the streaming engine (Cronet or HTTP fallback) with
     * the given default request headers (e.g. Referer).
     */
    fun factory(context: Context, referer: String = ""): DataSource.Factory {
        val engine = getCronetEngine(context)
        val headers = httpHeaders(referer)
        if (engine != null) {
            return DataSource.Factory {
                CronetDataSource.Factory(engine, cronetExecutor)
                    .setDefaultRequestProperties(headers)
                    .createDataSource()
            }
        }
        return DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)
    }

    /**
     * Single DataSource instance, used as the upstream of [MegaDecryptingDataSource].
     */
    fun create(context: Context, referer: String = ""): DataSource =
        factory(context, referer).createDataSource()
}
