package com.jiacimu.lulu.study

import android.content.Context
import com.jiacimu.lulu.core.WorldBookEntry
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
    /** A rewrite keeps the last completed page until a replacement is committed. */
    val rewriting: Boolean = false,
    val characterId: String = "", val characterName: String = "",
    val characterContext: String = "", val variationContext: String = "",
    val worldMode: String = "original",
    /** Explicit entries frozen with each standalone work, independent of global activation. */
    val worldBookIds: List<String> = emptyList(), val worldBookContext: String = "",
    /** Frozen at enqueue so the worker never silently changes a paid model mid-job. */
    val generationArchiveId: String = "",
    /** A continuation is an append-only new scene, never a rewrite of an existing completed story. */
    val continuing: Boolean = false,
    val continuationDirection: String = "",
)

/** Keep a finished edition intact unless a complete new installment has arrived. */
internal object MiniTheaterContinuation {
    private const val endMarker = "【小剧场已完】"

    fun priorTextForPrompt(text: String): String = if (text.length <= 20_000) text else
        text.take(3_500) + "\n\n【已有正文中段较长，以下跳转至最近剧情；被省略的部分不得自行编造】\n\n" +
            text.takeLast(14_000)

    fun append(previous: String, generated: String): String {
        require(previous.isNotBlank()) { "没有已保存的正文，无法续写" }
        require(generated.contains(endMarker)) { "续写尚未完整完成，原文已保留" }
        var addition = generated.substringBefore(endMarker).trim()
        // A model may repeat the story title; keep the original one, not a second cover.
        val firstLine = addition.lineSequence().firstOrNull().orEmpty().trim()
        if (firstLine.startsWith("《") && firstLine.endsWith("》") && firstLine.length <= 100) {
            addition = addition.substringAfter('\n', "").trim()
        }
        require(addition.isNotBlank() && !previous.trimEnd().endsWith(addition)) {
            "没有得到新的续写正文，原文已保留"
        }
        return previous.trimEnd() + "\n\n" + addition
    }
}

/** Single durable ownership for standalone theater templates, drafts and final text. */
internal class MiniTheaterLibrary(context: Context, initialTemplates: List<MiniTheaterItem>? = null) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("lulu_mini_theaters_v1", Context.MODE_PRIVATE)
    private val changes = MutableStateFlow(load())
    val items: StateFlow<List<MiniTheaterItem>> = changes
    private val ideaChanges = MutableStateFlow(loadCreativeIdeas())
    val creativeIdeas: StateFlow<List<PersonalMiniTheater.Idea>> = ideaChanges

    init {
        val seeds = initialTemplates ?: parse(app.assets.open("theater/wanxiang_mini_scenes.json")
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

    @Synchronized fun addPersonal(cast: PersonalMiniTheater.Cast,
        idea: PersonalMiniTheater.Idea, variation: PersonalMiniTheater.Variation? = null,
        selectedWorldBookIds: Set<String> = emptySet(),
        availableWorldBooks: List<WorldBookEntry> = emptyList()): String {
        require(cast.characterId.isNotBlank() && cast.material.isNotBlank()) { "请先选择角色" }
        val selection = TheaterWorldBookContext.capture(selectedWorldBookIds, availableWorldBooks)
        require(selection.rules.map { it.id }.toSet() == selectedWorldBookIds) {
            "选中的世界书已被删除，请重新选择"
        }
        val item = MiniTheaterItem(id = "personal-" + UUID.randomUUID(),
            title = idea.title, prompt = idea.premise, characterId = cast.characterId,
            characterName = cast.name, characterContext = cast.material,
            variationContext = variation?.let { it.label + "：" + it.direction }.orEmpty(),
            worldMode = cast.worldMode,
            worldBookIds = selection.rules.map { it.id },
            worldBookContext = selection.standalonePromptText())
        save(items.value + item)
        return item.id
    }

    /** Persist AI batches separately from finished works; no model call on ordinary swipes. */
    @Synchronized fun addCreativeIdeas(candidates: List<PersonalMiniTheater.Idea>): Int {
        val previous = ideaChanges.value
        val usedTitles = (PersonalMiniTheater.ideas + previous).mapTo(mutableSetOf()) {
            it.title.trim().lowercase()
        }
        val usedPremises = (PersonalMiniTheater.ideas + previous)
            .mapTo(mutableSetOf()) { it.premise.trim().lowercase() }
        changes.value.filter { it.characterId.isNotBlank() }.forEach {
            usedTitles.add(it.title.trim().lowercase())
            usedPremises.add(it.prompt.trim().lowercase())
        }
        val fresh = candidates.filter { idea ->
            val title = idea.title.trim().lowercase()
            val premise = idea.premise.trim().lowercase()
            // Reject duplicates without consuming the other key. A rejected
            // premise must not prevent a later genuinely new candidate.
            if (title.isBlank() || premise.isBlank() ||
                title in usedTitles || premise in usedPremises) false
            else {
                usedTitles.add(title)
                usedPremises.add(premise)
                true
            }
        }
        if (fresh.isEmpty()) return 0
        val all = previous + fresh
        val encoded = JSONArray()
        all.forEach { encoded.put(JSONObject().put("id", it.id).put("title", it.title)
            .put("premise", it.premise).put("worldMode", it.recommendedWorldMode)) }
        check(prefs.edit().putString("creative_ideas", encoded.toString()).commit()) {
            "灵感保存失败"
        }
        ideaChanges.value = all
        return fresh.size
    }

    private fun loadCreativeIdeas(): List<PersonalMiniTheater.Idea> = runCatching {
        val array = JSONArray(prefs.getString("creative_ideas", "[]"))
        (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val title = obj.optString("title").trim()
            val premise = obj.optString("premise").trim()
            if (title.isBlank() || premise.isBlank()) null
            else PersonalMiniTheater.Idea(obj.optString("id", "ai-$i"), title, premise,
                obj.optString("worldMode").takeIf { it == "original" || it == "alternate" } ?: "original")
        }
    }.getOrDefault(emptyList())

    @Synchronized fun deletePersonal(id: String, characterId: String): Boolean {
        val entry = items.value.firstOrNull { it.id == id && it.characterId == characterId }
            ?: return false
        if (entry.characterId.isBlank()) return false
        save(items.value.filterNot { it.id == id })
        WorkManager.getInstance(app).cancelUniqueWork("single-theater-$id")
        return true
    }

    @Synchronized fun begin(id: String, background: String, rewrite: Boolean,
        modelArchiveId: String = "", continueStory: Boolean = false,
        continuationDirection: String = ""): String {
        val entry = items.value.firstOrNull { it.id == id } ?: error("小剧场已不存在")
        check(entry.status !in setOf("queued", "running")) { "这篇已经在生成中" }
        check(!rewrite || !continueStory) { "重新创作与续写不能同时进行" }
        check(!continueStory || (entry.text.isNotBlank() && !entry.rewriting)) {
            "只有已保存正文的作品才能续写；正在重写的旧版先完成或重试"
        }
        check(entry.status != "complete" || rewrite || continueStory) { "已完结作品不可静默覆盖" }
        val token = UUID.randomUUID().toString()
        save(items.value.map {
            if (it.id == id) it.copy(
                // The original scene setup belongs to the existing story. A new
                // installment has its own optional direction and must not replace it.
                sceneContext = if (continueStory) it.sceneContext else background,
                continuationDirection = if (continueStory) continuationDirection.trim().take(1000) else "",
                continuing = continueStory, generationToken = token,
                generationArchiveId = modelArchiveId,
                status = "queued", error = "", rewriting = rewrite || it.rewriting)
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
                rewriting = if (status == "complete") false else it.rewriting,
                continuing = if (status in setOf("queued", "running")) it.continuing else false,
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
                .put("generationToken", it.generationToken)
                .put("rewriting", it.rewriting).put("characterId", it.characterId)
                .put("characterName", it.characterName).put("characterContext", it.characterContext)
                .put("variationContext", it.variationContext).put("worldMode", it.worldMode)
                .put("worldBookIds", JSONArray(it.worldBookIds)).put("worldBookContext", it.worldBookContext)
                .put("generationArchiveId", it.generationArchiveId)
                .put("continuing", it.continuing)
                .put("continuationDirection", it.continuationDirection))
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
                o.optString("error"), o.optString("generationToken"),
                o.optBoolean("rewriting"), o.optString("characterId"), o.optString("characterName"),
                o.optString("characterContext"), o.optString("variationContext"),
                o.optString("worldMode", "original"),
                o.optJSONArray("worldBookIds")?.let { ids ->
                    (0 until ids.length()).mapNotNull { j -> ids.optString(j).takeIf(String::isNotBlank) }
                }.orEmpty(),
                o.optString("worldBookContext"),
                o.optString("generationArchiveId"),
                o.optBoolean("continuing"), o.optString("continuationDirection"))
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
    fun enqueue(context: Context, id: String, background: String, rewrite: Boolean = false,
        continueStory: Boolean = false, direction: String = "") {
        val store = MiniTheaterLibrary.get(context)
        if (rewrite || continueStory) {
            val item = store.items.value.firstOrNull { it.id == id } ?: error("小剧场已不存在")
            check(item.characterId.isBlank() || com.jiacimu.lulu.data.MigratedDomainStores.characters.settings.value.containsKey(item.characterId)) { "角色已移除，保留已保存的作品" }
        }
        LuluAiServices.initialize(context.applicationContext)
        ScopedModelSelections.initialize(context.applicationContext)
        val entry = store.items.value.firstOrNull { it.id == id } ?: error("小剧场已不存在")
        val archiveId = if (entry.characterId.isNotBlank())
            ScopedModelSelections.selectedPersonalTheaterArchiveId(entry.characterId)
        else ScopedModelSelections.selectedArchiveId(ScopedModelSelections.THEATER)
        require(!archiveId.isNullOrBlank()) { "请先为番外选择可用模型" }
        // The model used for a background paid request is frozen once queued.
        LuluAiServices.connectionStore.resolveConnection(archiveId)
        val token = store.begin(id, background, rewrite, modelArchiveId = archiveId,
            continueStory = continueStory, continuationDirection = direction)
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
        // A recovered personal work whose paid request may already have started
        // is not automatically bought a second time after process death.
        if (snapshot.characterId.isNotBlank() && snapshot.status == "running") {
            store.finish(id, token, "failed", error = "上次创作中断，未自动重复请求；可以手动重试")
            return Result.success()
        }
        if (!store.finish(id, token, "running")) return Result.success()
        return try {
            LuluAiServices.initialize(applicationContext)
            // On rewrite, retain the last complete page in storage until success,
            // but never feed it into the new creative prompt or append it.
            val originalDraft = if (snapshot.rewriting) "" else snapshot.text
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
                    appendLine("这篇已保存的正文（以前文为已发生事实）：\n${if (snapshot.continuing) MiniTheaterContinuation.priorTextForPrompt(originalDraft) else originalDraft.takeLast(10_000)}")
                if (snapshot.continuing) appendLine("本次续写方向（可自由发挥但不可推翻前文）：${snapshot.continuationDirection.ifBlank { "顺着既有剧情自然发展" }}")
            }
            val personal = if (snapshot.characterId.isNotBlank()) {
                if (snapshot.continuing) PersonalMiniTheater.continuationRequest(snapshot)
                else PersonalMiniTheater.request(snapshot)
            } else null
            val continuationInstructions = if (!snapshot.continuing) instructions else """
                续写同一部已保存的番外，不是重写、复述或另起独立短篇。保留全部已发生的剧情事实、关系进展与世界设定。
                先接上原文结尾，让新一段事件由人物自身选择与当前局面自然发展，不照着原始故事灵感重演开头，也不突然撤销原结局。
                用户的续写方向只是本次偏好，不得覆盖前文既定事实。约1000—1800字，注重场景、动作、对白、心理与连续因果。
                不重写标题，不重复原文，不解释创作过程，只写新正文；末尾独立一行【小剧场已完】。
            """.trimIndent()
            val generated = LuluAiServices.gateway.generate(
                characterId = "theater",
                facts = personal?.facts ?: facts,
                instruction = personal?.instructions ?: continuationInstructions,
                source = if (snapshot.continuing) "剧场·番外续写" else "剧场·单篇小剧场",
                title = "${snapshot.title} · ${if (snapshot.continuing) "续写" else "单篇"}",
                contextMode = CompanionContextMode.Isolated,
                connectionOverride = LuluAiServices.connectionStore.resolveConnection(
                    snapshot.generationArchiveId.takeIf(String::isNotBlank)
                        ?: if (snapshot.characterId.isNotBlank())
                            ScopedModelSelections.selectedPersonalTheaterArchiveId(snapshot.characterId)
                        else ScopedModelSelections.selectedArchiveId(ScopedModelSelections.THEATER)),
                maxTokens = if (personal == null) null else 4_500,
                readTimeoutMillis = 540_000,
            ).getOrThrow().text.trim()
            check(generated.isNotBlank()) { "没有生成正文" }
            val marker = "【小剧场已完】"
            val isComplete = generated.contains(marker)
            val full = if (snapshot.continuing && isComplete)
                MiniTheaterContinuation.append(originalDraft, generated)
            else (if (originalDraft.isBlank()) "" else originalDraft + "\n") +
                generated.substringBefore(marker).trim()
            if (snapshot.continuing && !isComplete) {
                store.finish(id, token, "failed",
                    error = "续写没有完整生成，原文已保留；可以重试续写")
            } else if (snapshot.rewriting && !isComplete) {
                // A partial replacement must never erase a finished edition.
                store.finish(id, token, "failed",
                    error = "新版本尚未完整写完，旧版正文已保留；可以重试")
            } else {
                store.finish(id, token, if (isComplete) "complete" else "draft", text = full,
                    error = if (isComplete) "" else "正文未完成，可以接着补写这一篇")
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            store.finish(id, token, "failed", error = e.message.orEmpty())
            Result.failure()
        }
    }
}
