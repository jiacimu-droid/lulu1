package com.jiacimu.lulu.ai

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URL

/** Diagnostic-only view of configured local routes. Never block a model request.
 * VPN/proxy routing belongs to Android and OkHttp, not an independent TCP preflight.
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

    internal fun transportHint(
        url: String,
        selector: ProxySelector? = ProxySelector.getDefault(),
    ): String {
        val route = runCatching { selectedLocalRoute(URL(url), selector) }.getOrNull() ?: return ""
        return if (route.isProxy)
            "；系统代理配置包含本机地址 ${route.address.hostString}:${route.address.port}，仅在连接失败时请检查代理是否运行"
        else "；模型地址配置为本机接口 ${route.address.hostString}:${route.address.port}，请检查服务是否启动"
    }
}
