package com.karin.streamtv.cast

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Control remoto de un receptor DLNA (funciones DMC del dispositivo).
 *
 * SOAP sobre HTTP contra el controlURL de AVTransport: SetAVTransportURI con
 * los metadatos DIDL-Lite y después Play. Sin librerías UPnP.
 */
object DlnaController {

    private const val TAG = "DlnaController"
    private const val XML_MEDIA_TYPE = "text/xml; charset=utf-8"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    /** Carga [url] en el receptor y lo pone en marcha. */
    suspend fun play(
        renderer: DlnaProtocol.Renderer,
        url: String,
        title: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val metadata = DlnaProtocol.didlLite(
            uri = url,
            title = title,
            mimeType = DlnaProtocol.mimeTypeFor(url),
        )
        val loaded = soap(renderer, "SetAVTransportURI", DlnaProtocol.setAvTransportXml(url, metadata))
        val started = soap(renderer, "Play", DlnaProtocol.playXml())
        Log.d(TAG, "play ${renderer.name}: set=$loaded start=$started")
        loaded && started
    }

    suspend fun pause(renderer: DlnaProtocol.Renderer): Boolean = withContext(Dispatchers.IO) {
        soap(renderer, "Pause", DlnaProtocol.pauseXml())
    }

    suspend fun resume(renderer: DlnaProtocol.Renderer): Boolean = withContext(Dispatchers.IO) {
        soap(renderer, "Play", DlnaProtocol.playXml())
    }

    suspend fun stop(renderer: DlnaProtocol.Renderer): Boolean = withContext(Dispatchers.IO) {
        soap(renderer, "Stop", DlnaProtocol.stopXml())
    }

    /** Estado de transporte actual (PLAYING, PAUSED_PLAYBACK, STOPPED...). */
    suspend fun state(renderer: DlnaProtocol.Renderer): String? = withContext(Dispatchers.IO) {
        try {
            val response = post(renderer, DlnaProtocol.getTransportInfoXml(), "GetTransportInfo")
            if (!response.isSuccess) return@withContext null
            DlnaProtocol.parseTransportState(response.body.orEmpty())
        } catch (e: Exception) {
            Log.d(TAG, "state ${renderer.name}: ${e.message}")
            null
        }
    }

    private suspend fun soap(
        renderer: DlnaProtocol.Renderer,
        action: String,
        envelope: String,
    ): Boolean = try {
        post(renderer, envelope, action).isSuccess
    } catch (e: Exception) {
        Log.w(TAG, "$action -> ${renderer.name} failed: ${e.message}")
        false
    }

    private data class SoapResult(val isSuccess: Boolean, val body: String?)

    private fun post(
        renderer: DlnaProtocol.Renderer,
        envelope: String,
        action: String,
    ): SoapResult {
        val request = Request.Builder()
            .url(renderer.controlUrl)
            .header("Content-Type", XML_MEDIA_TYPE)
            .header("SOAPAction", DlnaProtocol.soapAction(renderer.serviceType, action))
            .post(envelope.toRequestBody(XML_MEDIA_TYPE.toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            return SoapResult(response.isSuccessful, body)
        }
    }
}
