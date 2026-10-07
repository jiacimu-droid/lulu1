package com.jiacimu.lulu.data

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A persisted outbox survives process death even when submission has not reached the server. */
object CloudTaskBridge {
    private var context: Context? = null
    private var prefs: android.content.SharedPreferences? = null

    fun initialize(context: Context) {
        this.context = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences("lulu_cloud_tasks", Context.MODE_PRIVATE)
        if (tasks().any { it.optString("status") !in setOf("succeeded", "failed", "cancelled") }) schedule()
    }

    fun configuration(): Pair<String, String> = prefs?.getString("url", "").orEmpty() to prefs?.getString("token", "").orEmpty()
    fun isConfigured(): Boolean = configuration().let { it.first.startsWith("https://") && it.second.isNotBlank() }

    fun configure(url: String, token: String) {
        val clean = url.trim().trimEnd('/')
        require(clean.isBlank() || URL(clean).protocol == "https") { "云端服务需要 HTTPS 地址" }
        check(prefs?.edit()?.putString("url", clean)?.putString("token", token.trim())?.commit() == true)
        schedule()
    }

    @Synchronized
    fun tasks(characterId: String? = null): List<JSONObject> {
        val array = runCatching { JSONArray(prefs?.getString("outbox", "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).map { array.getJSONObject(it) }.filter { characterId == null || it.optString("characterId") == characterId }
    }

    @Synchronized
    fun submit(characterId: String, kind: String, payload: JSONObject, requestId: String = UUID.randomUUID().toString()): JSONObject {
        require(isConfigured()) { "请在语音设置配置云端服务地址与访问凭证" }
        require(kind in setOf("research", "pptx", "docx") && characterId.isNotBlank())
        tasks().firstOrNull { it.optString("requestId") == requestId && it.optString("characterId") == characterId }?.let { return it }
        val job = JSONObject().put("characterId", characterId).put("requestId", requestId).put("kind", kind)
            .put("payload", payload).put("status", "waiting_network").put("createdAt", Instant.now().toString())
        save(tasks() + job)
        schedule()
        return job
    }

    @Synchronized
    private fun update(job: JSONObject) {
        save(tasks().map { if (it.optString("requestId") == job.optString("requestId") && it.optString("characterId") == job.optString("characterId")) job else it })
    }

    private fun save(jobs: List<JSONObject>) {
        check(prefs?.edit()?.putString("outbox", JSONArray(jobs).toString())?.commit() == true) { "云端任务保存失败" }
    }

    suspend fun request(path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val (url, token) = configuration()
        require(isConfigured()) { "云端服务未配置" }
        val connection = (URL(url + path).openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            connectTimeout = 15_000; readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            if (body != null) doOutput = true
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(code in 200..299) { runCatching { JSONObject(raw).optString("error") }.getOrDefault("云端 HTTP $code").ifBlank { "云端 HTTP $code" } }
            JSONObject(raw)
        } finally { connection.disconnect() }
    }

    suspend fun refresh(): Boolean {
        var unfinished = false
        for (job in tasks().filter { it.optString("status") !in setOf("succeeded", "failed", "cancelled") }) {
            try {
                val result = if (job.optString("id").isBlank()) request("/v1/tasks", job) else request("/v1/tasks/${job.getString("id")}")
                result.keys().forEach { key -> job.put(key, result.get(key)) }
                job.remove("connectionError")
                update(job)
                if (job.optString("status") in setOf("succeeded", "failed")) {
                    val characterId = job.getString("characterId")
                    SharedExperienceTimeline.record("cloud-${job.getString("requestId")}-$characterId", characterId,
                        "云端任务", "任务服务", job.toString(), taskId = job.getString("requestId"),
                        source = "cloud-service", evidenceKind = EventEvidenceKind.ToolResult)
                    CharacterDevelopmentRuntime.request(characterId)
                    ImportantEventBridge.wake(checkNotNull(context), characterId, "重要事件·云端任务完成")
                } else unfinished = true
            } catch (error: Exception) {
                job.put("connectionError", error.message.orEmpty().take(250))
                update(job)
                unfinished = true
            }
        }
        return unfinished
    }

    fun download(job: JSONObject, preview: Boolean = false): Long {
        check(job.optString("status") == "succeeded") { "任务尚未成功" }
        val app = checkNotNull(context)
        val file = job.getJSONObject("result").getString(if (preview) "preview" else "file")
        require(Regex("[A-Za-z0-9_.-]+").matches(file))
        val (url, token) = configuration()
        val request = DownloadManager.Request(android.net.Uri.parse("$url/v1/tasks/${job.getString("id")}/files/$file"))
            .addRequestHeader("Authorization", "Bearer $token")
            .setTitle("露露任务：$file")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Lulu/${job.getString("id")}-$file")
        return (app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }

    fun schedule() {
        val app = context ?: return
        val work = OneTimeWorkRequestBuilder<CloudTaskWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(app).enqueueUniqueWork("lulu-cloud-outbox", ExistingWorkPolicy.KEEP, work)
    }
}

class CloudTaskWorker(context: Context, parameters: WorkerParameters): CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        initializeBackgroundRuntime(applicationContext)
        CloudTaskBridge.initialize(applicationContext)
        return if (CloudTaskBridge.refresh()) Result.retry() else Result.success()
    }
}
