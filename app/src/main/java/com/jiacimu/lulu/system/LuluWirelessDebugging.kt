package com.jiacimu.lulu.system

import android.content.Context
import android.os.Build
import com.wangzan101.mika.cap.phone.shell.adb.AndroidAdb
import com.wangzan101.mika.cap.phone.shell.adb.NsdAdbServiceFinder
import com.wangzan101.mika.core.adb.AdbConnectPort
import com.wangzan101.mika.core.adb.AdbConnection
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
    private const val USER_PAIRED = "paired_by_owner"
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasPriorPairing(context: Context): Boolean =
        prefs(context).getBoolean(USER_PAIRED, false)

    suspend fun pair(context: Context, pairingPort: Int, code: String): String = withContext(Dispatchers.IO) {
        require(Build.VERSION.SDK_INT >= 30) { "无线调试配对需要 Android 11 及以上" }
        require(pairingPort in 1..65535) { "配对端口无效" }
        require(code.matches(Regex("\\d{6}"))) { "请输入系统显示的6位无线配对码" }
        val identity = AndroidAdb.pairingIdentity(context.applicationContext)
        val peer = AdbPairing(AndroidAdb.tls(identity)).pair("127.0.0.1", pairingPort, code)
        check(prefs(context).edit().putBoolean(USER_PAIRED, true).commit()) {
            "配对成功但本机授权标记未能保存"
        }
        "已与本机无线调试服务完成密钥配对；尚需连接并验证后台助手"
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
    suspend fun openShell(context: Context, manualConnectPort: Int? = null): AdbConnection =
        withContext(Dispatchers.IO) {
            require(Build.VERSION.SDK_INT >= 30) { "Android 11 以下不支持无线调试连接" }
            check(hasPriorPairing(context)) { "请先在露露机设置中配对无线调试" }
            val cache = portCache(context)
            val connect = AdbConnectPort(
                property = { null },
                finder = NsdAdbServiceFinder(context),
                cache = cache,
                scan = LocalPortScan(
                    scanner = NioPortScanner(),
                    hosts = { listOf("127.0.0.1") },
                ),
                manual = { manualConnectPort?.takeIf { it in 1..65535 } },
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
        block: (AdbConnection) -> T,
    ): T = withContext(Dispatchers.IO) {
        openShell(context, manualConnectPort).use(block)
    }

    suspend fun test(context: Context, port: Int? = null): String =
        withShell(context, port) { "已连接本机 Android shell；后台屏幕尚需助手初始化" }

    fun forget(context: Context) {
        AndroidAdb.forget(context.applicationContext)
        check(prefs(context).edit().clear().commit()) { "撤销本地无线调试身份失败" }
        // Removing the app's key cannot revoke the key already trusted by adbd.
        // The user must also remove the pairing in Android's Developer Options.
    }
}
