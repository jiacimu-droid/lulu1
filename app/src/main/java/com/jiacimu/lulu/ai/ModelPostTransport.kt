package com.jiacimu.lulu.ai

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/** One chargeable POST through the system's configured network route.
 * Never open a separate "health probe" socket or reject VPN/system proxies before OkHttp.
 * One-shot body and disabled redirects prevent silent paid replays. Only a failure
 * strictly before request-body write may be retried once (no billable body sent). */
internal object ModelPostTransport {
    internal enum class Phase { CONNECT, WRITE_BODY, READ_RESPONSE }
    // OkHttp defaults writeTimeout to 10s even if readTimeout is 3–5 min.
    // Large UTF-8/JSON posts can time out in the request-body flush itself;
    // allow a bounded slower upload without replaying an uncertain paid POST.
    internal const val REQUEST_WRITE_TIMEOUT_SECONDS = 60L
    internal const val PRE_BODY_CONNECT_RETRIES = 1
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(REQUEST_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** A fresh Call per attempt; never replay an upload or a response wait.
     * A proxy/VPN is part of the selected system route, not a reason to block it. */
    internal fun <T> execute(
        url: String,
        headers: Map<String, String>,
        body: String,
        readTimeoutMillis: Int,
        transportClient: OkHttpClient = client,
        read: (Response) -> T,
    ): T {
        val bytes = body.toByteArray(Charsets.UTF_8)
        var preBodyRetries = 0
        while (true) {
            var phase = Phase.CONNECT
            // A new one-shot RequestBody for each safe attempt. Once writeTo
            // begins, the provider's billing status is uncertain: do not replay.
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
                // newBuilder shares pools/dispatcher while each request has
                // its own read budget. Do not retry chargeable response failures.
                val transport = transportClient.newBuilder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(REQUEST_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .readTimeout(readTimeoutMillis.coerceIn(30_000, 300_000).toLong(), TimeUnit.MILLISECONDS)
                    .build()
                return transport.newCall(request).execute().use { response ->
                    phase = Phase.READ_RESPONSE
                    read(response)
                }
            } catch (error: IOException) {
                if (phase == Phase.CONNECT &&
                    preBodyRetries < PRE_BODY_CONNECT_RETRIES &&
                    !Thread.currentThread().isInterrupted &&
                    isRetryablePreBodyFailure(error)) {
                    preBodyRetries++
                    continue
                }
                throw ModelPostTransportException(phase, error, preBodyRetries)
            }
        }
    }

    /** Certificate/configuration errors and cancellation should not be retried.
     * This is transport classification only, never an independent network probe. */
    internal fun isRetryablePreBodyFailure(error: IOException): Boolean {
        if (error.message.equals("Canceled", ignoreCase = true)) return false
        return when (error) {
            is SSLException, is UnknownHostException, is ProtocolException -> false
            is ConnectException, is SocketTimeoutException, is SocketException, is EOFException -> true
            else -> error.message?.let {
                it.contains("connection closed", ignoreCase = true) ||
                    it.contains("connection shutdown", ignoreCase = true)
            } == true
        }
    }

}

internal class ModelPostTransportException(
    val phase: ModelPostTransport.Phase,
    cause: IOException,
    val preBodyRetries: Int = 0,
) : IOException(cause.message ?: cause.javaClass.simpleName, cause) {
    val diagnostic: String get() = when (phase) {
        ModelPostTransport.Phase.CONNECT ->
            if (preBodyRetries > 0)
                "；连接建立失败，请求正文尚未发送；已安全重连${preBodyRetries}次（每次均未发送正文），不再重试"
            else "；连接建立失败，请求正文尚未发送；未重试（非瞬时连接故障或重连不安全）"
        ModelPostTransport.Phase.WRITE_BODY ->
            "；发送请求正文时连接中断，服务端接收及生成结果未知；本次传输未重放"
        ModelPostTransport.Phase.READ_RESPONSE ->
            "；请求正文已发送，等待响应时连接中断或超时，服务端生成结果未知；本次传输未重放"
    }
}
