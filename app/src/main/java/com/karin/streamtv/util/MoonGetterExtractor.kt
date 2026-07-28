package com.karin.streamtv.util

import android.util.Log
import com.ead.lib.moongetter.MoonGetter
import com.ead.lib.moongetter.client.MoonClient
import com.ead.lib.moongetter.client.models.Configuration
import com.ead.lib.moongetter.client.request.HttpMethod
import com.ead.lib.moongetter.client.request.Request as MoonRequest
import com.ead.lib.moongetter.client.response.Response
import com.ead.lib.moongetter.client.response.body.ResponseBody
import com.ead.lib.moongetter.client.response.url.Url
import com.ead.lib.moongetter.models.Server
import com.ead.lib.moongetter.models.builder.Engine
import com.ead.lib.moongetter.utils.Values
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType

data class ExtractedVideo(
    val url: String,
    val headers: Map<String, String> = emptyMap()
)

object MoonGetterExtractor {

    private const val TAG = "MoonGetterExtractor"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private class OkHttpMoonClient : MoonClient {
        private var configData: Configuration.Data = Configuration.Data()

        override fun initConfigurationData(configData: Configuration.Data) {
            this.configData = configData
        }

        override suspend fun <T> request(request: MoonRequest<T>): Response = withContext(Dispatchers.IO) {
            val url = buildString {
                append(request.url)
                val params = request.queryParams
                if (params.isNotEmpty()) {
                    val separator = if (request.url.contains("?")) "&" else "?"
                    append(separator)
                    append(params.entries.joinToString("&") { "${it.key}=${it.value}" })
                }
            }

            val builder = okhttp3.Request.Builder().url(url)

            builder.header("User-Agent", USER_AGENT)
            builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            builder.header("Accept-Language", "en-US,en;q=0.9,es;q=0.8")

            request.headers.forEach { (key, value) ->
                if (value.isNotBlank()) {
                    builder.header(key, value)
                }
            }

            when (request.method) {
                HttpMethod.GET -> builder.get()
                HttpMethod.POST -> {
                    val body = request.body
                    val serializer = request.serializer
                    if (body != null && serializer != null) {
                        @Suppress("UNCHECKED_CAST")
                        val json = kotlinx.serialization.json.Json.encodeToString(serializer as kotlinx.serialization.SerializationStrategy<Any>, body)
                        val mediaType = if (request.asFormUrlEncoded) {
                            "application/x-www-form-urlencoded".toMediaType()
                        } else {
                            "application/json".toMediaType()
                        }
                        builder.post(json.toRequestBody(mediaType))
                    } else {
                        builder.post(ByteArray(0).toRequestBody(null))
                    }
                }
            }

            val requestCall = Http.client.newBuilder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
                .newCall(builder.build())
            val httpResponse = requestCall.execute()

            val requestBody = try { httpResponse.body?.string() ?: "" } finally { httpResponse.close() }
            val responseUrl = httpResponse.request.url.toString()
            val responseHost = httpResponse.request.url.host
            val contentType = httpResponse.header("Content-Type")
            val statusCode = httpResponse.code

            ExtractionLogger.logHttp(
                method = request.method.name,
                url = url,
                status = statusCode,
                contentType = contentType,
                bodyLength = requestBody.length,
                bodyPreview = requestBody.take(300)
            )

            object : Response {
                override val statusCode: Int = statusCode
                override val headers: Map<String, String> = buildMap {
                    httpResponse.headers?.let { h ->
                        for (i in 0 until h.size) {
                            put(h.name(i), h.value(i))
                        }
                    }
                }
                override val body: ResponseBody = object : ResponseBody {
                    override fun asString(): String = requestBody
                }
                override val url: Url = object : Url {
                    override val toString: String = responseUrl
                    override val host: String = responseHost
                }
            }
        }
    }

    suspend fun extractVideo(embedUrl: String): ExtractedVideo? = withContext(Dispatchers.IO) {
        val result = withTimeoutOrNull(15_000L) {
            try {
                Log.d(TAG, "=== MoonGetter START ===")
                Log.d(TAG, "URL: $embedUrl")

                val okClient = OkHttpMoonClient()

                val serverBundleClass = Class.forName("com.ead.lib.moongetter.server.bundle.BundleFileKt")
                val getServerBundleMethod = serverBundleClass.getMethod("getServerBundle")
                @Suppress("UNCHECKED_CAST")
                val serverBundle = getServerBundleMethod.invoke(null) as Array<Server.Factory>
                Log.d(TAG, "Server bundle loaded: ${serverBundle.size} factories")
                val factoryNames = serverBundle.map { "${it.serverName}(${it.pattern})" }
                ExtractionLogger.logMoonGetterSummary(serverBundle.size, factoryNames)

                val engine = Engine.Builder().onCore(serverBundle).build()
                Log.d(TAG, "Engine built OK")

                try {
                    Values.targetUrl = embedUrl
                    Values.targetUrl2 = embedUrl
                    Log.d(TAG, "Set Values.targetUrl and targetUrl2 to $embedUrl")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set Values: ${e.message}")
                }

                val factoryBuilder = MoonGetter.Builder()
                    .setClient(okClient)
                    .setEngine(engine)
                Log.d(TAG, "FactoryBuilder created OK")

                val server = factoryBuilder.get(embedUrl)
                if (server == null) {
                    Log.w(TAG, "MoonGetter: NO matching server for $embedUrl")
                    ExtractionLogger.logMoonGetterMatch(false, null, null)
                    return@withTimeoutOrNull null
                }
                Log.d(TAG, "Matched server -> calling onExtract()")
                ExtractionLogger.logMoonGetterMatch(true, null, null)

                val videos = server.onExtract()
                Log.d(TAG, "onExtract returned ${videos.size} videos")
                ExtractionLogger.logMoonGetterOnExtractCall(
                    callNumber = 1,
                    urlState = server.url ?: "null",
                    urlChanged = false,
                    videosFound = videos.size
                )
                if (videos.isEmpty()) {
                    return@withTimeoutOrNull null
                }

                for ((i, v) in videos.withIndex()) {
                    Log.d(TAG, "  Video[$i]: quality=${v.quality} url=${v.request.url.take(120)}")
                    ExtractionLogger.logMoonGetterVideo(i, v.quality, v.request.url)
                }

                val video = videos.firstOrNull { it.quality?.contains("1080") == true }
                    ?: videos.firstOrNull { it.quality?.contains("720") == true }
                    ?: videos.first()

                val videoUrl = video.request.url
                val videoHeaders = video.request.headers ?: emptyMap()
                Log.d(TAG, "MoonGetter OK: $videoUrl (quality: ${video.quality})")
                if (videoUrl.isNotBlank()) ExtractedVideo(videoUrl, videoHeaders) else null
            } catch (e: Exception) {
                Log.e(TAG, "MoonGetter FAILED: ${e.message}", e)
                null
            }
        }
        if (result == null) {
            Log.w(TAG, "MoonGetter timed out or failed for $embedUrl")
        }
        result
    }

    suspend fun extractVideoUrl(embedUrl: String): String? = extractVideo(embedUrl)?.url

    private fun String.toRequestBody(mediaType: okhttp3.MediaType?) =
        okhttp3.RequestBody.create(mediaType, this)

    private fun ByteArray.toRequestBody(mediaType: okhttp3.MediaType?) =
        okhttp3.RequestBody.create(mediaType, this)
}
