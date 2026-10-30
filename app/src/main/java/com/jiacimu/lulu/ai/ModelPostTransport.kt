package com.jiacimu.lulu.ai

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/** One chargeable POST, with readable 4xx bodies and no transport replay. */
internal object ModelPostTransport {
    internal enum class Phase { CONNECT, WRITE_BODY, READ_RESPONSE }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    internal fun <T> execute(
        url: String,
        headers: Map<String, String>,
        body: String,
        readTimeoutMillis: Int,
        preflight: (String) -> Unit = { ModelLocalRouteHealth.probeBeforePost(it) },
        read: (Response) -> T,
    ): T {
        preflight(url)
        var phase = Phase.CONNECT
        val bytes = body.toByteArray(Charsets.UTF_8)
        val requestBody = object : RequestBody() {
            override fun contentType() = "application/json; charset=utf-8".toMediaType()
            override fun contentLength() = bytes.size.toLong()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                phase = Phase.WRITE_BODY
                sink.write(bytes)
                sink.flush()
                phase = Phase.READ_RESPONSE
            }
        }
        val request = Request.Builder().url(url).post(requestBody).apply {
            headers.forEach { (key, value) -> header(key, value) }
        }.build()
        try {
            // newBuilder shares pools/dispatcher, but each request keeps its
            // own read budget. 401/403 bodies remain readable without auth replay.
            val transport = client.newBuilder()
                .readTimeout(readTimeoutMillis.coerceIn(30_000, 300_000).toLong(), TimeUnit.MILLISECONDS)
                .build()
            return transport.newCall(request).execute().use { response ->
                phase = Phase.READ_RESPONSE
                read(response)
            }
        } catch (error: IOException) {
            throw ModelPostTransportException(phase, error)
        }
    }
}

internal class ModelPostTransportException(
    val phase: ModelPostTransport.Phase,
    cause: IOException,
) : IOException(cause.message ?: cause.javaClass.simpleName, cause) {
    val diagnostic: String get() = when (phase) {
        ModelPostTransport.Phase.CONNECT ->
            "；连接建立失败，请求正文尚未发送；本次传输未重放"
        ModelPostTransport.Phase.WRITE_BODY ->
            "；发送请求正文时连接中断，服务端接收及生成结果未知；本次传输未重放"
        ModelPostTransport.Phase.READ_RESPONSE ->
            "；请求正文已发送，等待响应时连接中断或超时，服务端生成结果未知；本次传输未重放"
    }
}
