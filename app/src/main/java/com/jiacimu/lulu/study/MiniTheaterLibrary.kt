package com.jiacimu.lulu.study

import android.content.Context
import androidx.work.*
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ScopedModelSelections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** A whole one-shot small theater is a separate work, never a novel chapter. */
internal data class MiniTheaterItem(
    val id: String, val title: String, val prompt: String,
    val builtIn: Boolean = false, val sceneContext: String = "",
    val text: String = "", val status: String = "new",
    val error: String = "", val generationToken: String = "",
)

/** Single durable ownership for standalone theater templates, drafts and final text. */
internal class MiniTheaterLibrary private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("lulu_mini_theaters_v1", Context.MODE_PRIVATE)
    private val changes = MutableStateFlow(load())
    val items: StateFlow<List<MiniTheaterItem>> = changes

    init {
        val seeds = parse(app.assets.open("theater/wanxiang_mini_scenes.json")
            .bufferedReader().use { it.readText() })
        synchronized(this) {
            val known = changes.value.mapTo(mutableSetOf()) { it.title to it.prompt }
            val newItems = seeds.filter { known.add(it.title to it.prompt) }
                .map { it.copy(builtIn = true) }
            if (newItems.isNotEmpty()) save(changes.value + newItems)
        }
    }

    @Synchronized fun addSelected(candidates: List<MiniTheaterItem>): Int {
        val known = changes.value.mapTo(mutableSetOf()) { it.title to it.prompt }
        val inserted = candidates.filter { known.add(it.title to it.prompt) }
        if (inserted.isNotEmpty()) save(changes.value + inserted)
        return inserted.size
    }

    @Synchronized fun begin(id: String, background: String, rewrite: Boolean): String {
        val entry = items.value.firstOrNull { it.id == id } ?: error("小剧场已不存在")
        check(entry.status !in setOf("queued", "running")) { "这篇已经在生成中" }
        check(entry.status != "complete" || rewrite) { "已完结作品不可静默覆盖" }
        val token = UUID.randomUUID().toString()
        save(items.value.map {
            if (it.id == id) it.copy(sceneContext = background, generationToken = token,
                status = "queued", error = "", text = if (rewrite) "" else it.text)
            else it
        })
        return token
    }

    @Synchronized fun finish(id: String, token: String, status: String,
                             text: String? = null, error: String = ""): Boolean {
        val target = items.value.firstOrNull { it.id == id } ?: return false
        if (target.generationToken != token ||
            target.status !in setOf("queued", "running")) return false
        save(items.value.map {
            if (it.id == id) it.copy(status = status, text = text ?: it.text,
                error = error.take(300)) else it
        })
        return true
    }

    @Synchronized private fun save(all: List<MiniTheaterItem>) {
        val a = JSONArray()
        all.forEach {
            a.put(JSONObject().put("id", it.id).put("title", it.title)
                .put("prompt", it.prompt).put("builtIn", it.builtIn)
                .put("sceneContext", it.sceneContext).put("text", it.text)
                .put("status", it.status).put("error", it.error)
                .put("generationToken", it.generationToken))
        }
        check(prefs.edit().putString("items", a.toString()).commit()) { "小剧场保存失败" }
        changes.value = all
    }

    private fun load(): List<MiniTheaterItem> = runCatching {
        val a = JSONArray(prefs.getString("items", "[]"))
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("prompt").isBlank()) return@mapNotNull null
            MiniTheaterItem(o.optString("id"), o.optString("title"), o.optString("prompt"),
                o.optBoolean("builtIn"), o.optString("sceneContext"),
                o.optString("text"), o.optString("status", "new"),
                o.optString("error"), o.optString("generationToken"))
        }
    }.getOrDefault(emptyList())

    companion object {
        @Volatile private var holder: MiniTheaterLibrary? = null
        fun get(context: Context): MiniTheaterLibrary =
            holder ?: synchronized(this) {
                holder ?: MiniTheaterLibrary(context).also { holder = it }
            }

        private fun stableId(title: String, prompt: String): String =
            "mini-" + MessageDigest.getInstance("SHA-256")
                .digest((title + "\u0000" + prompt).toByteArray())
                .take(16).joinToString("") { "%02x".format(it) }

        /** Original Tavern preset or the user-converted world-book export. */
        fun parse(raw: String): List<MiniTheaterItem> {
            val o = JSONObject(raw.trim().removePrefix("\uFEFF"))
            val entries = o.optJSONArray("prompts") ?: o.optJSONArray("worldBooks")
                ?: o.optJSONArray("books") ?: o.optJSONArray("entries")
                ?: error("没有找到 prompts/worldBooks 数组")
            return (0 until entries.length()).mapNotNull { i ->
                val node = entries.optJSONObject(i) ?: return@mapNotNull null
                val title = node.optString("name").ifBlank {
                    node.optString("title").ifBlank { node.optString("comment") }
                }.removePrefix("🔸").removePrefix("🌙").trim()
                val prompt = node.optString("content")
                if (!title.contains("小剧场") || title.contains("规则") ||
                    prompt.isBlank()) return@mapNotNull null
                MiniTheaterItem(stableId(title, prompt), title, prompt)
            }.distinctBy { it.title to it.prompt }
        }
    }
}

/** Keep the request alive through UI navigation or process recreation. */
internal object MiniTheaterJobs {
    fun enqueue(context: Context, id: String, background: String, rewrite: Boolean = false) {
        val store = MiniTheaterLibrary.get(context)
        val token = store.begin(id, background, rewrite)
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "single-theater-$id", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<MiniTheaterWorker>()
                    .setConstraints(Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(workDataOf("id" to id, "token" to token)).build())
        } catch (e: Exception) {
            store.finish(id, token, "failed", error = e.message.orEmpty())
            throw e
        }
    }
}

internal class MiniTheaterWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id").orEmpty()
        val token = inputData.getString("token").orEmpty()
        val store = MiniTheaterLibrary.get(applicationContext)
        val snapshot = store.items.value.firstOrNull { it.id == id && it.generationToken == token }
            ?: return Result.success()
        if (!store.finish(id, token, "running")) return Result.success()
        return try {
            LuluAiServices.initialize(applicationContext)
            val originalDraft = snapshot.text
            val instructions = """
                你正在生成独立的单篇小剧场，不是长篇小说或系列章节。
                以下是用户收藏的原版写作要求，按原文字数、场景、形式来创作：
                ${snapshot.prompt}

                只创作这一篇，不制作故事大纲、人物成长弧、长期情节地图或后续章节。
                用户输入的前情是本篇可用的“本轮正文”来源。没有前情时自行构造
                独立场景，不能虚构已经发生过的真实聊天。
                {{user}}和{{char}}在此均视为虚构故事中的位置，不读取角色私人记忆。
                写足原要求，正文完成后在最后另起一行输出【小剧场已完】。
                不输出任何提示词解释。
            """.trimIndent() + if (originalDraft.isNotBlank()) """
                同一篇此前已写下前半部分。本轮只补写后文，不重演已写内容。
            """.trimIndent() else ""
            val facts = buildString {
                appendLine("标题：${snapshot.title}")
                appendLine("用户为这一篇提供的前情或特殊要求：${snapshot.sceneContext.ifBlank { "无" }}")
                if (originalDraft.isNotBlank())
                    appendLine("这篇已保存的正文末尾：\n${originalDraft.takeLast(10_000)}")
            }
            val generated = LuluAiServices.gateway.generate(
                characterId = "theater",
                facts = facts,
                instruction = instructions,
                source = "剧场·单篇小剧场",
                title = "${snapshot.title} · 单篇",
                contextMode = CompanionContextMode.Isolated,
                connectionOverride = ScopedModelSelections.resolveConnection(ScopedModelSelections.THEATER),
                maxTokens = null,
                readTimeoutMillis = 540_000,
            ).getOrThrow().text.trim()
            check(generated.isNotBlank()) { "没有生成正文" }
            val marker = "【小剧场已完】"
            val isComplete = generated.contains(marker)
            val full = (if (originalDraft.isBlank()) "" else "$" + "{originalDraft}\n") +
                generated.substringBefore(marker).trim()
            store.finish(id, token, if (isComplete) "complete" else "draft", text = full,
                error = if (isComplete) "" else "正文未完成，可以接着补写这一篇")
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            store.finish(id, token, "failed", error = e.message.orEmpty())
            Result.failure()
        }
    }
}
