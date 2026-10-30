package com.jiacimu.lulu.ai

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.URI
import java.net.URL

/**
 * A configured local API server / system HTTP proxy is not a model provider.
 * If its TCP listener is absent, fail BEFORE writing the chargeable model body.
 * This never bypasses a VPN/proxy, changes the configured API URL, or retries.
 */
internal object ModelLocalRouteHealth {
    private val loopback = setOf("127.0.0.1", "localhost", "::1", "[::1]")

    internal data class LocalRoute(val address: InetSocketAddress, val isProxy: Boolean)

    internal fun selectedLocalRoute(
        url: URL,
        selector: ProxySelector? = ProxySelector.getDefault(),
    ): LocalRoute? {
        // The URL itself may be a deliberately configured same-phone gateway.
        if (url.host.lowercase() in loopback) {
            return LocalRoute(InetSocketAddress(url.host, url.port.takeIf { it > 0 }
                ?: url.defaultPort.takeIf { it > 0 } ?: 80), false)
        }
        val selected = runCatching { selector?.select(url.toURI()) }
            .getOrNull()?.firstOrNull() ?: Proxy.NO_PROXY
        if (selected.type() != Proxy.Type.HTTP) return null
        val socket = selected.address() as? InetSocketAddress ?: return null
        if (socket.hostString.lowercase() !in loopback) return null
        return LocalRoute(socket, true)
    }

    internal fun probeBeforePost(
        url: String,
        timeoutMillis: Int = 1_200,
        selector: ProxySelector? = ProxySelector.getDefault(),
        connect: (InetSocketAddress, Int) -> Unit = { address, timeout ->
            Socket().use { socket -> socket.connect(address, timeout) }
        },
    ) {
        val route = selectedLocalRoute(URL(url), selector) ?: return
        try {
            connect(route.address, timeoutMillis)
        } catch (error: IOException) {
            val routeName = if (route.isProxy) "系统本机网络代理" else "本机模型接口"
            // This error is raised before the POST request is opened.
            throw ModelLocalRouteUnavailableException(
                "$routeName ${route.address.hostString}:${route.address.port} 无法连接。" +
                    "本轮模型请求尚未发送，不会由客户端重复计费。请检查本机代理/VPN是否运行、" +
                    "端口是否正确；若该端口由VPN提供，请检查其后台运行状态。",
                error,
            )
        }
    }

    internal fun transportHint(url: String): String {
        val route = runCatching { selectedLocalRoute(URL(url)) }.getOrNull() ?: return ""
        return if (route.isProxy)
            "；网络经系统本机代理 ${route.address.hostString}:${route.address.port}，请检查其运行状态"
        else "；目标是本机接口 ${route.address.hostString}:${route.address.port}，请检查服务是否启动"
    }
}

internal class ModelLocalRouteUnavailableException(
    message: String,
    cause: Throwable,
) : IOException(message, cause)
