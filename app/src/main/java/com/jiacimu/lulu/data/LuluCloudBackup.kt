package com.jiacimu.lulu.data

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/**
 * WebDAV 云备份：每天自动备份后，把同一份包 PUT 到用户自己的网盘
 * （坚果云、Nextcloud、群晖等支持 WebDAV 的都行），不用自建服务器。
 *
 * 为什么是 WebDAV 而不是某个固定云盘：用户的网盘各不相同，WebDAV 是
 * 它们共通的上传口，地址、账号、密码都由用户在备份页自己填。
 * 密码存在本机设置里（和已有的语音 API Key 同一级别），不发给别人。
 *
 * 上传本身是纯 java.net，不碰 Android API，单元测试可以直接对着
 * 本地 HTTP 服务器测；配置读写才需要 Context。
 */
object LuluCloudBackup {
    private const val PREFS = "lulu_cloud_backup"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SERVER = "server_url"
    private const val KEY_USER = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_DIR = "remote_dir"
    private const val KEY_LAST = "last_upload"

    data class Config(
        val enabled: Boolean = false,
        val serverUrl: String = "",
        val username: String = "",
        val password: String = "",
        val remoteDir: String = "lulu-backups",
    )

    fun loadConfig(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            serverUrl = prefs.getString(KEY_SERVER, "").orEmpty(),
            username = prefs.getString(KEY_USER, "").orEmpty(),
            password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
            remoteDir = prefs.getString(KEY_DIR, "lulu-backups").orEmpty().ifBlank { "lulu-backups" },
        )
    }

    fun saveConfig(context: Context, config: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_SERVER, config.serverUrl.trim())
            .putString(KEY_USER, config.username.trim())
            .putString(KEY_PASSWORD, config.password)
            .putString(KEY_DIR, config.remoteDir.trim().ifBlank { "lulu-backups" })
            .apply()
    }

    fun lastUploadText(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST, null)?.takeIf(String::isNotBlank)

    fun recordUpload(context: Context, text: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST, text).apply()
    }

    /**
     * 把 [file] 以 [fileName] 传进 WebDAV 的 [remoteDir] 文件夹。
     * 文件夹不存在时先 MKCOL 再重传一次；账号密码不对、地址不对都
     * 返回看得懂的原因，而不是抛一串英文异常。
     */
    fun upload(
        serverUrl: String,
        username: String,
        password: String,
        remoteDir: String,
        file: File,
        fileName: String,
    ): Result<Unit> = runCatching {
        val base = serverUrl.trim().trimEnd('/')
        require(base.isNotEmpty()) { "请先填写 WebDAV 服务器地址" }
        require(file.isFile) { "备份文件不见了" }
        val dir = remoteDir.trim().trim('/').ifBlank { "lulu-backups" }
        val dirUrl = base + "/" + dir.split('/').joinToString("/") { encodeSegment(it) }
        val fileUrl = dirUrl + "/" + encodeSegment(fileName)
        val first = putFile(fileUrl, username, password, file)
        if (first == 404 || first == 409) {
            // 文件夹不存在时先建，再传一次；建失败由第二次 PUT 再报错。
            mkcol(dirUrl, username, password)
            val retry = putFile(fileUrl, username, password, file)
            check(retry in 200..299) { httpMessage(retry) }
        } else {
            check(first in 200..299) { httpMessage(first) }
        }
    }

    private fun httpMessage(code: Int): String = when (code) {
        401 -> "用户名或密码不对（HTTP 401）"
        403 -> "服务器拒绝写入（HTTP 403）"
        404 -> "服务器地址或文件夹不对（HTTP 404）"
        else -> "上传失败（HTTP $code）"
    }

    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private fun authHeader(username: String, password: String): String {
        val token = Base64.getEncoder()
            .encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
        return "Basic $token"
    }

    private fun putFile(url: String, username: String, password: String, file: File): Int {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("Authorization", authHeader(username, password))
            connection.setRequestProperty("Content-Type", "application/zip")
            connection.setFixedLengthStreamingMode(file.length())
            FileInputStream(file).use { input ->
                connection.outputStream.use { out -> input.copyTo(out) }
            }
            return connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun mkcol(url: String, username: String, password: String) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "MKCOL"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", authHeader(username, password))
            // 201 已建、405 已存在都算过；真失败由后面的 PUT 再报。
            connection.responseCode
        } catch (ignored: IOException) {
            // 建文件夹失败不单独报错，交给 PUT 的结果说话。
        } finally {
            connection.disconnect()
        }
    }
}
