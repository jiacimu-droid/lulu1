package com.jiacimu.lulu.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.wangzan101.mika.core.adb.AdbHosts
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Android adbd often listens on the phone's WLAN address, not 127.0.0.1.
 * Never resolve DNS or connect to another device. AdbHosts validates the
 * chosen IP against THIS phone's active interfaces again at socket creation.
 */
internal object LuluLocalAdbAddress {
    const val LOOPBACK = "127.0.0.1"

    fun candidateHosts(preferred: String?, wifi: List<String>, remembered: String?): List<String> =
        (listOfNotNull(preferred?.trim()?.takeIf(String::isNotEmpty)) +
            wifi + listOfNotNull(remembered?.trim()?.takeIf(String::isNotEmpty)) +
            LOOPBACK).distinct()

    fun wifiIps(context: Context): List<String> {
        val cmAddresses = runCatching {
            val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            cm.allNetworks.asSequence()
                .filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                .flatMap {
                    cm.getLinkProperties(it)?.linkAddresses?.asSequence()
                        ?.map { address -> address.address } ?: emptySequence()
                }
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                .map { it.hostAddress.orEmpty() }.toList()
        }.getOrDefault(emptyList())
        val interfaceAddresses = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && (it.name.startsWith("wlan") ||
                    it.name.startsWith("swlan") || it.name.startsWith("wifi")) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                .map { it.hostAddress.orEmpty() }
        }.getOrDefault(emptyList())
        return (cmAddresses + interfaceAddresses).distinct()
            .filter(String::isNotBlank).filter { AdbHosts.ownAddress(it) != null }
    }

    fun validatedCandidates(context: Context, preferred: String?, remembered: String?): List<String> {
        val value = preferred?.trim()?.takeIf(String::isNotEmpty)
        if (value != null) {
            require(AdbHosts.ownAddress(value) != null) {
                "请输入系统配对弹窗中的本机 Wi-Fi IP；不允许连接其他设备地址"
            }
        }
        return candidateHosts(value, wifiIps(context), remembered)
            .filter { AdbHosts.ownAddress(it) != null }
    }
}
