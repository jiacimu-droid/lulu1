package com.jiacimu.lulu.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

/**
 * WebDAV 云备份：每天自动备份后，把同一份包 PUT 到用户自己的网盘
 * （坚果云、Nextcloud、群晖等支持 WebDAV 的都行），不用自建服务器。
 *
 * 为什么是 WebDAV 而不是某个固定云盘：用户的网盘各不相同，WebDAV 是
 * 它们共通的上传口，地址、账号、密码都由用户在备份页自己填。
 * 密码存在本机设置里（和已有的语音 API Key 同一级别），不发给别人。
 *
 * 上传走 OkHttp（App 已有依赖），不碰 Android 私有 API，单元测试
 * 可以直接对着本地服务器测；配置读写才需要 Context。
 * 不用 HttpURLConnection：建文件夹要发 MKCOL，这不在它白名单里的
 * 方法里，测试环境的 JDK 会直接拒掉（setRequestMethod 抛错），而
 * OkHttp 在测试环境和真机上都能发。第 43 批的测试失败就是这么查
 * 出来的：MKCOL 根本没发出去，上传在文件夹不存在时永远建不了。
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

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun putFile(url: String, username: String, password: String, file: File): Int {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader(username, password))
            .put(file.asRequestBody("application/zip".toMediaType()))
            .build()
        return client.newCall(request).execute().use { it.code }
    }

    private fun mkcol(url: String, username: String, password: String) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", authHeader(username, password))
                .method("MKCOL", null)
                .build()
            // 201 已建、405 已存在都算过；真失败由后面的 PUT 再报。
            client.newCall(request).execute().use { }
        } catch (ignored: IOException) {
            // 建文件夹失败不单独报错，交给 PUT 的结果说话。
        }
    }
}
