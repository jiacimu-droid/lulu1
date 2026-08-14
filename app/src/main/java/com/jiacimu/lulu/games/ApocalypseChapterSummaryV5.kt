package com.jiacimu.lulu.games

import android.content.Context
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Long-form plot compression that preserves chronology instead of replacing it.
 *
 * - Every 10 scenes: a chapter summary.
 * - Every 20 scenes: a stage overview spanning the two adjacent chapters.
 * - Unresolved questions and small observable details live in their own fields so they are not lost
 *   when the main chronology is compressed.
 *
 * This store is plot-only. It never enters companion/character memory.
 */
internal data class ApocalypseChapterSummaryV5(
    val id: String,
    val startScene: Int,
    val endScene: Int,
    val span: Int,
    val chronology: List<String>,
    val unresolved: List<String>,
    val subtleDetails: List<String>,
    val characterChanges: List<String>,
    val worldChanges: List<String>,
    val createdAt: Long = System.currentTimeMillis(),
)

internal class ApocalypseChapterSummaryStoreV5(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(saveId: String): List<ApocalypseChapterSummaryV5> = synchronized(LOCK) {
        if (saveId.isBlank()) return@synchronized emptyList()
        decodeSummaries(prefs.getString(key(saveId), null))
            .filter { it.endScene >= it.startScene && it.span in setOf(10, 20) }
            .sortedWith(compareBy<ApocalypseChapterSummaryV5> { it.endScene }.thenBy { it.span })
    }

    fun has(saveId: String, startScene: Int, endScene: Int, span: Int): Boolean =
        load(saveId).any { it.startScene == startScene && it.endScene == endScene && it.span == span }

    fun upsert(saveId: String, summary: ApocalypseChapterSummaryV5) = synchronized(LOCK) {
        val next = decodeSummaries(prefs.getString(key(saveId), null))
            .filterNot {
                it.startScene == summary.startScene &&
                    it.endScene == summary.endScene &&
                    it.span == summary.span
            }
            .plus(summary)
            .sortedWith(compareBy<ApocalypseChapterSummaryV5> { it.endScene }.thenBy { it.span })
            .takeLast(MAX_SUMMARIES)
        prefs.edit().putString(key(saveId), encodeSummaries(next).toString()).apply()
    }

    fun trimAfterScene(saveId: String, scene: Int) = synchronized(LOCK) {
        val kept = decodeSummaries(prefs.getString(key(saveId), null)).filter { it.endScene <= scene }
        if (kept.isEmpty()) {
            prefs.edit().remove(key(saveId)).apply()
        } else {
            prefs.edit().putString(key(saveId), encodeSummaries(kept).toString()).apply()
        }
    }

    fun clear(saveId: String) {
        if (saveId.isBlank()) return
        synchronized(LOCK) { prefs.edit().remove(key(saveId)).apply() }
    }

    /**
     * Director-facing compression. Twenty-scene overviews are the durable backbone; a completed
     * ten-scene chapter that is not yet covered by a twenty-scene overview is appended afterwards.
     * Keep the first two stages plus the newest ten stages on extremely long saves so origins remain
     * visible without letting resolved middle history dominate the prompt. Exact old details remain
     * available through semantic plot recall.
     */
    fun promptForDirector(save: ApocalypseV3Save): String {
        val summaries = load(save.id).filter { it.endScene <= save.scene }
        if (summaries.isEmpty()) return ""

        val stages = summaries.filter { it.span == 20 }.sortedBy { it.startScene }
        val selectedStages = if (stages.size <= 12) {
            stages
        } else {
            (stages.take(2) + stages.takeLast(10)).distinctBy { it.id }.sortedBy { it.startScene }
        }
        val coveredThrough = stages.maxOfOrNull { it.endScene } ?: 0
        val trailingChapter = summaries
            .filter { it.span == 10 && it.endScene > coveredThrough }
            .maxByOrNull { it.endScene }

        val selected = (selectedStages + listOfNotNull(trailingChapter))
            .distinctBy { "${it.startScene}-${it.endScene}-${it.span}" }
            .sortedBy { it.startScene }
        if (selected.isEmpty()) return ""

        return buildString {
            appendLine("【章节压缩档案｜只压缩旧幕，不改变正史先后】")
            appendLine("20幕阶段总览负责长期方向；尚未凑满20幕的最近10幕使用小章总结。当前结构化剧情线/伏笔账本若与旧总结的‘当时未解决’状态不同，以当前账本为准，绝不能把已解决事项重新开启。")
            selected.forEach { summary ->
                appendLine("\n[第${summary.startScene}—${summary.endScene}幕｜${if (summary.span == 20) "阶段总览" else "10幕小结"}]")
                if (summary.chronology.isNotEmpty()) {
                    appendLine("时间顺序：")
                    summary.chronology.forEach { appendLine("- ${it.take(260)}") }
                }
                if (summary.unresolved.isNotEmpty()) {
                    appendLine("当时仍待解释/待回收：")
                    summary.unresolved.forEach { appendLine("- ${it.take(220)}") }
                }
                if (summary.subtleDetails.isNotEmpty()) {
                    appendLine("容易被大事件淹没的小细节：")
                    summary.subtleDetails.forEach { appendLine("- ${it.take(220)}") }
                }
                if (summary.characterChanges.isNotEmpty()) {
                    appendLine("人物/关系变化：")
                    summary.characterChanges.forEach { appendLine("- ${it.take(220)}") }
                }
                if (summary.worldChanges.isNotEmpty()) {
                    appendLine("世界/地点变化：")
                    summary.worldChanges.forEach { appendLine("- ${it.take(220)}") }
                }
            }
        }.trim()
    }

    private fun key(saveId: String) = "summary_$saveId"

    private companion object {
        const val PREFS_NAME = "apocalypse_chapter_summary_v5"
        const val MAX_SUMMARIES = 180
        val LOCK = Any()
    }
}

internal object ApocalypseChapterSummaryRuntimeV5 {
    private const val SUMMARY_CHARACTER_ID = "__apocalypse_chapter_summary_v5__"
    private val mutex = Mutex()

    /**
     * Called before planning the scene after a milestone. The current milestone is worth waiting for:
     * it is exactly where a long story should consolidate before moving on.
     */
    suspend fun ensureCurrentMilestone(context: Context, save: ApocalypseV3Save) = mutex.withLock {
        if (save.scene < 10 || save.scene % 10 != 0) return@withLock
        val store = ApocalypseChapterSummaryStoreV5(context)
        if (save.scene % 20 == 0) {
            val start = save.scene - 19
            ensureTwentyBundle(context, save, store, start, save.scene)
        } else {
            val start = save.scene - 9
            ensureTen(context, save, store, start, save.scene)
        }
    }

    /**
     * Existing long saves may predate this feature. Backfill one old 20-scene bundle at a time in the
     * background; this avoids turning the first upgraded scene into six consecutive summary calls.
     */
    suspend fun backfillOneOlderBundle(context: Context, save: ApocalypseV3Save) = mutex.withLock {
        if (save.scene < 20) return@withLock
        val store = ApocalypseChapterSummaryStoreV5(context)
        val completedTwenty = (save.scene / 20) * 20
        val missing = generateSequence(completedTwenty) { previous ->
            (previous - 20).takeIf { it >= 20 }
        }.map { end -> (end - 19) to end }
            .firstOrNull { (start, end) ->
                !store.has(save.id, start, end, 20) ||
                    !store.has(save.id, start, start + 9, 10) ||
                    !store.has(save.id, start + 10, end, 10)
            } ?: return@withLock
        ensureTwentyBundle(context, save, store, missing.first, missing.second)
    }

    private suspend fun ensureTen(
        context: Context,
        save: ApocalypseV3Save,
        store: ApocalypseChapterSummaryStoreV5,
        start: Int,
        end: Int,
    ) {
        if (store.has(save.id, start, end, 10)) return
        val source = sourceForRange(context, save, start, end)
        if (source.isBlank()) return
        generateTenSummary(save, start, end, source)?.let { store.upsert(save.id, it) }
    }

    private suspend fun ensureTwentyBundle(
        context: Context,
        save: ApocalypseV3Save,
        store: ApocalypseChapterSummaryStoreV5,
        start: Int,
        end: Int,
    ) {
        val mid = start + 9
        val needFirst = !store.has(save.id, start, mid, 10)
        val needSecond = !store.has(save.id, mid + 1, end, 10)
        val needStage = !store.has(save.id, start, end, 20)
        if (!needFirst && !needSecond && !needStage) return

        val source = sourceForRange(context, save, start, end)
        if (source.isBlank()) return
        val bundle = generateTwentyBundle(save, start, end, source) ?: return
        if (needFirst) bundle.first?.let { store.upsert(save.id, it) }
        if (needSecond) bundle.second?.let { store.upsert(save.id, it) }
        if (needStage) bundle.stage?.let { store.upsert(save.id, it) }
    }

    private fun sourceForRange(context: Context, save: ApocalypseV3Save, start: Int, end: Int): String {
        val logByScene = save.log.mapNotNull { line ->
            val scene = Regex("第(\\d+)幕").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@mapNotNull null
            scene to line
        }.filter { (scene, _) -> scene in start..end }
            .associate { it.first to it.second }
            .toMutableMap()

        if ((start..end).any { it !in logByScene }) {
            val plotStore = ApocalypsePlotMemoryStoreV5(context)
            plotStore.bootstrapFromSave(save)
            plotStore.load(save.id)
                .filter { it.scene in start..end }
                .forEach { card -> logByScene.putIfAbsent(card.scene, card.content) }
        }

        return logByScene.entries
            .sortedBy { it.key }
            .joinToString("\n") { (scene, text) -> "第${scene}幕｜${text.take(850)}" }
    }

    private fun currentEndStateForPrompt(save: ApocalypseV3Save, endScene: Int): String {
        if (endScene != save.scene) return ""
        val director = save.director
        return buildString {
            appendLine("【第${endScene}幕结束时结构化状态｜只辅助判断哪些东西当时还没解决】")
            appendLine("剧情线：")
            director.storyThreads
                .filter { it.status == "active" || it.status == "dormant" }
                .take(12)
                .forEach { appendLine("- ${it.id}｜${it.title}｜${it.currentState}｜${it.status}") }
            appendLine("伏笔可见层：")
            director.foreshadowLedger
                .filter { it.stage != "paid_off" && it.stage != "abandoned" }
                .take(14)
                .forEach {
                    appendLine("- ${it.id}｜${it.title}｜stage=${it.stage}｜可见证据=${it.visibleEvidence.joinToString("；")}｜表层含义=${it.surfaceMeaning}")
                }
            appendLine("人物状态：")
            director.characterDossiers
                .filter { it.lastSeenScene <= endScene || it.lastAdvancedScene <= endScene }
                .takeLast(12)
                .forEach {
                    appendLine("- ${it.id}｜${it.name}｜${it.status}｜${it.currentLocation}｜${it.physicalState}｜${it.emotionalState}｜公开目标=${it.publicGoal}")
                }
        }
    }

    private suspend fun generateTenSummary(
        save: ApocalypseV3Save,
        start: Int,
        end: Int,
        source: String,
    ): ApocalypseChapterSummaryV5? {
        val facts = buildString {
            appendLine("这是《末世求生》同一存档第${start}—${end}幕，记录已经按幕号从早到晚排列。")
            appendLine("不要改变任何事件先后，不要补写没发生的事，不要猜幕后真相。")
            appendLine(source)
            append(currentEndStateForPrompt(save, end))
        }
        val instruction = """
            你是长篇互动小说的隐藏 continuity editor。把这10幕整理成一份可供未来导演长期使用的小章档案。
            只返回JSON，不要Markdown：
            {"chronology":["按发生顺序的3—7条阶段节点"],"unresolved":["到第${end}幕结束仍未解释/未兑现/未解决的问题、伏笔或异常"],"subtleDetails":["将来可能有意义但很容易被大事件淹没的具体小细节"],"characterChanges":["人物关系、态度、伤势、目标或位置的真实变化"],"worldChanges":["地点、社会、生态、资源环境等持久变化"]}
            chronology必须严格从早到晚，不得按重要性重排。
            unresolved和subtleDetails宁可保留具体可观察细节，也不要写空泛的“仍有谜团”。
            只根据输入；没有证据的幕后身份、阴谋、异能来源一律不能猜。
            已明确解决的事情不要放进unresolved。
            每个数组最多8条，每条尽量具体、简短、可追溯。
        """.trimIndent()
        val raw = LuluAiServices.gateway.generate(
            characterId = SUMMARY_CHARACTER_ID,
            facts = facts,
            instruction = instruction,
            source = "末世求生V5十幕总结",
            title = "末世求生 · 第${start}—${end}幕小结",
            temperature = 0.24,
            maxTokens = 1500,
            usage = ModelUsage.Game,
            contextMode = CompanionContextMode.PersonaAndScenario,
            streamResponse = false,
            readTimeoutMillis = 90_000,
        ).getOrNull()?.text.orEmpty()
        return parseSummary(raw, start, end, 10)
    }

    private data class TwentyBundle(
        val first: ApocalypseChapterSummaryV5?,
        val second: ApocalypseChapterSummaryV5?,
        val stage: ApocalypseChapterSummaryV5?,
    )

    private suspend fun generateTwentyBundle(
        save: ApocalypseV3Save,
        start: Int,
        end: Int,
        source: String,
    ): TwentyBundle? {
        val mid = start + 9
        val facts = buildString {
            appendLine("这是《末世求生》同一存档第${start}—${end}幕，所有记录已经按幕号从早到晚排列。")
            appendLine("需要同时产出两个10幕小结和一个20幕阶段总览。不要改变事件先后，不要猜幕后真相。")
            appendLine(source)
            append(currentEndStateForPrompt(save, end))
        }
        val instruction = """
            你是长篇互动小说的隐藏 continuity editor。只返回JSON，不要Markdown：
            {
              "first10":{"chronology":[],"unresolved":[],"subtleDetails":[],"characterChanges":[],"worldChanges":[]},
              "second10":{"chronology":[],"unresolved":[],"subtleDetails":[],"characterChanges":[],"worldChanges":[]},
              "stage20":{"chronology":[],"unresolved":[],"subtleDetails":[],"characterChanges":[],"worldChanges":[]}
            }
            first10只总结第${start}—${mid}幕；second10只总结第${mid + 1}—${end}幕；stage20总结第${start}—${end}幕的长期变化。
            三份chronology都必须严格按原始幕序从早到晚，绝不能按相关性或重要性重排。
            unresolved专门保留到各自范围末尾仍未解释、未兑现、未解决的伏笔/异常/承诺；subtleDetails保留容易遗忘却可能在以后有意义的具体小细节。
            stage20不要机械拼接两个10幕摘要，而要看20幕里人物、世界、冲突和谜团怎样发生阶段性变化。
            只根据输入；不得发明未发生事实或猜幕后真相。已明确解决的事情不要继续列为unresolved。
            每个数组最多8条，每条简短、具体、可追溯。
        """.trimIndent()
        val raw = LuluAiServices.gateway.generate(
            characterId = SUMMARY_CHARACTER_ID,
            facts = facts,
            instruction = instruction,
            source = "末世求生V5二十幕总结",
            title = "末世求生 · 第${start}—${end}幕阶段总结",
            temperature = 0.22,
            maxTokens = 2600,
            usage = ModelUsage.Game,
            contextMode = CompanionContextMode.PersonaAndScenario,
            streamResponse = false,
            readTimeoutMillis = 90_000,
        ).getOrNull()?.text.orEmpty()
        val json = extractJsonObject(raw) ?: return null
        return TwentyBundle(
            first = json.optJSONObject("first10")?.let { parseSummaryObject(it, start, mid, 10) },
            second = json.optJSONObject("second10")?.let { parseSummaryObject(it, mid + 1, end, 10) },
            stage = json.optJSONObject("stage20")?.let { parseSummaryObject(it, start, end, 20) },
        )
    }

    private fun parseSummary(raw: String, start: Int, end: Int, span: Int): ApocalypseChapterSummaryV5? =
        extractJsonObject(raw)?.let { parseSummaryObject(it, start, end, span) }

    private fun parseSummaryObject(
        json: JSONObject,
        start: Int,
        end: Int,
        span: Int,
    ): ApocalypseChapterSummaryV5? {
        fun list(name: String): List<String> {
            val array = json.optJSONArray(name) ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
                }
            }.distinct().take(8)
        }
        val chronology = list("chronology")
        val unresolved = list("unresolved")
        val subtle = list("subtleDetails")
        val characters = list("characterChanges")
        val world = list("worldChanges")
        if (chronology.isEmpty() && unresolved.isEmpty() && subtle.isEmpty() && characters.isEmpty() && world.isEmpty()) return null
        return ApocalypseChapterSummaryV5(
            id = "summary_${span}_${start}_${end}_${UUID.randomUUID()}",
            startScene = start,
            endScene = end,
            span = span,
            chronology = chronology,
            unresolved = unresolved,
            subtleDetails = subtle,
            characterChanges = characters,
            worldChanges = world,
        )
    }
}

private fun encodeSummaries(values: List<ApocalypseChapterSummaryV5>): JSONArray = JSONArray().apply {
    values.forEach { value ->
        put(
            JSONObject()
                .put("id", value.id)
                .put("startScene", value.startScene)
                .put("endScene", value.endScene)
                .put("span", value.span)
                .put("chronology", JSONArray(value.chronology))
                .put("unresolved", JSONArray(value.unresolved))
                .put("subtleDetails", JSONArray(value.subtleDetails))
                .put("characterChanges", JSONArray(value.characterChanges))
                .put("worldChanges", JSONArray(value.worldChanges))
                .put("createdAt", value.createdAt),
        )
    }
}

private fun decodeSummaries(raw: String?): List<ApocalypseChapterSummaryV5> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val start = json.optInt("startScene", 0)
                val end = json.optInt("endScene", 0)
                val span = json.optInt("span", 0)
                if (start <= 0 || end < start || span !in setOf(10, 20)) continue
                fun list(name: String): List<String> {
                    val values = json.optJSONArray(name) ?: return emptyList()
                    return buildList {
                        for (i in 0 until values.length()) {
                            values.optString(i).trim().takeIf(String::isNotBlank)?.let(::add)
                        }
                    }.distinct().take(8)
                }
                add(
                    ApocalypseChapterSummaryV5(
                        id = json.optString("id").ifBlank { "summary_${span}_${start}_${end}_${UUID.randomUUID()}" },
                        startScene = start,
                        endScene = end,
                        span = span,
                        chronology = list("chronology"),
                        unresolved = list("unresolved"),
                        subtleDetails = list("subtleDetails"),
                        characterChanges = list("characterChanges"),
                        worldChanges = list("worldChanges"),
                        createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}

private fun extractJsonObject(raw: String): JSONObject? {
    val clean = raw.trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
    val start = clean.indexOf('{')
    val end = clean.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching { JSONObject(clean.substring(start, end + 1)) }.getOrNull()
}
