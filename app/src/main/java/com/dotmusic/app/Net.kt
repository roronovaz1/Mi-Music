package com.dotmusic.app

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit
import okhttp3.Request as OkRequest
import org.schabi.newpipe.extractor.downloader.Request as NpRequest

fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

object Net {
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val b = OkRequest.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { return it.body?.string().orEmpty() }
    }

    fun finalUrl(url: String): String = runCatching {
        client.newCall(OkRequest.Builder().url(url).header("User-Agent", UA).build()).execute()
            .use { it.request.url.toString() }
    }.getOrDefault(url)
}

class DownloaderImpl : Downloader() {
    override fun execute(request: NpRequest): Response {
        val rb = OkRequest.Builder().url(request.url()).header("User-Agent", Net.UA)
        request.headers().forEach { (k, v) ->
            rb.removeHeader(k)
            v.forEach { rb.addHeader(k, it) }
        }
        val data = request.dataToSend()
        val body = data?.toRequestBody()
        when (request.httpMethod()) {
            "GET" -> rb.get()
            "HEAD" -> rb.head()
            else -> rb.method(request.httpMethod(), body ?: ByteArray(0).toRequestBody())
        }
        Net.client.newCall(rb.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("Rate limited", request.url())
            return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }
}
