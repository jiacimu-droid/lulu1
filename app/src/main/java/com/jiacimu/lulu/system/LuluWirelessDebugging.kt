package com.jiacimu.lulu.system

import android.content.Context
import android.os.Build
import com.wangzan101.mika.cap.phone.shell.adb.AndroidAdb
import com.wangzan101.mika.cap.phone.shell.adb.NsdAdbServiceFinder
import com.wangzan101.mika.core.adb.AdbConnectPort
import com.wangzan101.mika.core.adb.AdbConnection
import com.wangzan101.mika.core.adb.AdbPairingException
import com.wangzan101.mika.core.adb.AdbPairing
import com.wangzan101.mika.core.adb.LocalPortScan
import com.wangzan101.mika.core.adb.NioPortScanner
import com.wangzan101.mika.core.adb.PortCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local ADB transport only, adapted from Mika's pairing mechanism.
 * This is owner-only system setup, not an LLM-facing shell executor.
 *
 * Pairing port and connect port are DIFFERENT ephemeral ports. Never store the
 * six-digit code and never claim that pairing proves that adbd is still online.
 */
object LuluWirelessDebugging {
    private const val PREFS = "lulu_local_wireless_debug"
    private const val LAST_PORT = "last_connect_port"
    private const val LAST_LOCAL_IP = "last_local_ip"
    private const val USER_PAIRED = "paired_by_owner"
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasPriorPairing(context: Context): Boolean =
        prefs(context).getBoolean(USER_PAIRED, false)

    fun suggestedWifiHost(context: Context): String? =
        LuluLocalAdbAddress.wifiIps(context).firstOrNull()

    suspend fun pair(context: Context, pairingPort: Int, code: String, localIp: String? = null): String = withContext(Dispatchers.IO) {
        require(Build.VERSION.SDK_INT >= 30) { "无线调试配对需要 Android 11 及以上" }
        require(pairingPort in 1..65535) { "配对端口无效" }
        require(code.matches(Regex("\\d{6}"))) { "请输入系统显示的6位无线配对码" }
        val identity = AndroidAdb.pairingIdentity(context.applicationContext)
        val hosts = LuluLocalAdbAddress.validatedCandidates(context, localIp,
            prefs(context).getString(LAST_LOCAL_IP, null))
        val pairing = AdbPairing(AndroidAdb.tls(identity))
        var failedHost = ""
        for (host in hosts) {
            try {
                pairing.pair(host, pairingPort, code)
                check(prefs(context).edit().putString(LAST_LOCAL_IP, host)
                    .putBoolean(USER_PAIRED, true).commit()) {
                    "配对成功但本机授权标记未能保存"
                }
                return@withContext "已通过本机地址 $host 完成配对；接下来检查另一个连接端口"
            } catch (e: AdbPairingException) {
                // Incorrect one-time code and TLS errors must not be retried
                // as if they were a stale IP address.
                if (e.reason != AdbPairingException.Reason.CONNECT_FAILED) throw e
                failedHost = host
            }
        }
        error("无法连接无线配对端口 $pairingPort；已尝试本机 Wi-Fi 地址和回环地址。" +
            "请保持系统的六位码弹窗开启，确认端口来自本次配对弹窗。" +
            "最后一次尝试地址：$failedHost")
    }

    private fun portCache(context: Context): PortCache = object : PortCache {
        override fun load(): Int? = prefs(context).getInt(LAST_PORT, -1).takeIf { it in 1..65535 }
        override fun save(port: Int) {
            if (port in 1..65535) prefs(context).edit().putInt(LAST_PORT, port).apply()
        }
    }

    /**
     * Connection is established fresh and validated as uid 2000. Do not accept
     * unrelated TCP endpoints, remote IPs, or user/model-supplied shell strings.
     */
    /** Leaves an authenticated local ADB connection open for a single helper session. */
    suspend fun openShell(context: Context, manualConnectPort: Int? = null,
                          localIp: String? = null): AdbConnection =
        withContext(Dispatchers.IO) {
            require(Build.VERSION.SDK_INT >= 30) { "Android 11 以下不支持无线调试连接" }
            check(hasPriorPairing(context)) { "请先在露露机设置中配对无线调试" }
            val hosts = LuluLocalAdbAddress.validatedCandidates(context, localIp,
                prefs(context).getString(LAST_LOCAL_IP, null))
            val cache = portCache(context)
            val connect = AdbConnectPort(
                property = { null },
                finder = NsdAdbServiceFinder(context),
                cache = cache,
                scan = LocalPortScan(
                    scanner = NioPortScanner(),
                    hosts = { hosts },
                ),
                manual = { manualConnectPort?.takeIf { it in 1..65535 } },
                localHost = hosts.firstOrNull() ?: LuluLocalAdbAddress.LOOPBACK,
            )
            val tls = AndroidAdb.tls(AndroidAdb.identity(context.applicationContext))
            connect.connect { host, port ->
                val adb = AdbConnection.open(host, port, tls)
                try {
                    adb.exec("id").use { stream ->
                        check(stream.readText().contains("uid=2000(shell)")) {
                            "连接到的不是 Android shell 身份，拒绝操作"
                        }
                    }
                    prefs(context).edit().putString(LAST_LOCAL_IP, host).apply()
                    adb
                } catch (t: Throwable) {
                    adb.close()
                    throw t
                }
            }
        }

    suspend fun <T> withShell(
        context: Context,
        manualConnectPort: Int? = null,
        localIp: String? = null,
        block: (AdbConnection) -> T,
    ): T = withContext(Dispatchers.IO) {
        openShell(context, manualConnectPort, localIp).use(block)
    }

    suspend fun test(context: Context, port: Int? = null, localIp: String? = null): String =
        withShell(context, port, localIp) { "已连接本机 Android shell；后台屏幕尚需助手初始化" }

    fun forget(context: Context) {
        AndroidAdb.forget(context.applicationContext)
        check(prefs(context).edit().clear().commit()) { "撤销本地无线调试身份失败" }
        // Removing the app's key cannot revoke the key already trusted by adbd.
        // The user must also remove the pairing in Android's Developer Options.
    }
}
