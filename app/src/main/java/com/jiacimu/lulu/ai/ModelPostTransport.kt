package com.jiacimu.lulu.ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One chargeable POST. No transparent replay after a broken connection. */
internal object ModelPostTransport {
    internal enum class Phase { CONNECT, WRITE_BODY, READ_RESPONSE }

    internal fun <T> execute(
        url: String,
        headers: Map<String, String>,
        body: String,
        readTimeoutMillis: Int,
        open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
        preflight: (String) -> Unit = { ModelLocalRouteHealth.probeBeforePost(it) },
        read: (HttpURLConnection) -> T,
    ): T {
        preflight(url)
        val connection = open(URL(url))
        var phase = Phase.CONNECT
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = readTimeoutMillis.coerceIn(30_000, 300_000)
            connection.doOutput = true
            // Fixed-length streaming disables HttpURLConnection's buffered POST
            // replay on auth/redirect or a stale socket. Never replay a paid body.
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            connection.connect()
            phase = Phase.WRITE_BODY
            connection.outputStream.use { it.write(bytes) }
            phase = Phase.READ_RESPONSE
            return read(connection)
        } catch (error: IOException) {
            throw ModelPostTransportException(phase, error)
        } finally {
            connection.disconnect()
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
