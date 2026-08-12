package com.jiacimu.lulu.games

import android.content.Context
import android.util.Base64
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.data.MemoryModelRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.ln
import kotlin.math.sqrt

/** Plot-only memory. It never reads or writes the per-character memory repository. */
internal data class ApocalypsePlotMemoryCardV5(
    val id: String,
    val saveId: String,
    val scene: Int,
    val worldTime: String,
    val location: String,
    val characterIds: List<String>,
    val eventTypes: List<String>,
    val importance: Double,
    val content: String,
    val embeddingSignature: String = "",
    val embeddingDimension: Int = 0,
    val embedding: FloatArray = FloatArray(0),
    val createdAt: Long = System.currentTimeMillis(),
)

internal class ApocalypsePlotMemoryStoreV5(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(saveId: String): List<ApocalypsePlotMemoryCardV5> = synchronized(LOCK) {
        decodeCards(prefs.getString(key(saveId), null)).filter { it.saveId == saveId }
    }

    /** Gives upgraded V5 saves useful recall immediately, without another chat-model extraction. */
    fun bootstrapFromSave(save: ApocalypseV3Save) {
        synchronized(LOCK) {
            val existing = decodeCards(prefs.getString(key(save.id), null))
            val knownScenes = existing.mapTo(mutableSetOf()) { it.scene }
            val imported = save.log.mapNotNull { line ->
                val scene = Regex("第(\\d+)幕").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                if (scene in knownScenes) return@mapNotNull null
                ApocalypsePlotMemoryCardV5(
                    id = "legacy_scene_${scene}_${UUID.randomUUID()}",
                    saveId = save.id,
                    scene = scene,
                    worldTime = "旧剧情记录",
                    location = "",
                    characterIds = emptyList(),
                    eventTypes = listOf("正史回填"),
                    importance = 0.55,
                    content = line.take(900),
                    createdAt = save.updatedAt,
                )
            }
            if (imported.isNotEmpty()) {
                persist(save.id, (existing + imported).sortedBy { it.scene }.takeLast(MAX_CARDS))
            }
        }
    }

    fun recordScene(
        saveBefore: ApocalypseV3Save,
        saveAfter: ApocalypseV3Save,
        action: String,
        outcome: ApocalypseSceneOutcomeV5,
    ) {
        val summary = outcome.continuitySummary
            .ifBlank { outcome.actionOutcome }
            .ifBlank { compactApocalypseSceneExcerptV5(saveAfter.narration, 180) }
            .trim()
        val presentIds = if (saveAfter.director.presentCharacterStateKnown) {
            saveAfter.director.presentCharacterIds
        } else {
            saveAfter.partyIds
        }
        val content = buildString {
            append("第${saveAfter.scene}幕｜")
            append(apocalypseDayLabelV5(saveAfter.director.dayIndex))
            append(' ')
            append(apocalypseClockLabelV5(saveAfter.director.clockMinutes))
            append("｜${saveAfter.director.location}｜玩家行动：${action.take(180)}｜正史：")
            append(summary.take(420))
            if (outcome.actionOutcome.isNotBlank() && !summary.contains(outcome.actionOutcome)) {
                append("｜结果：${outcome.actionOutcome.take(220)}")
            }
        }
        val card = ApocalypsePlotMemoryCardV5(
            id = "scene_${saveAfter.scene}_${UUID.randomUUID()}",
            saveId = saveAfter.id,
            scene = saveAfter.scene,
            worldTime = "${apocalypseDayLabelV5(saveAfter.director.dayIndex)} ${apocalypseClockLabelV5(saveAfter.director.clockMinutes)}",
            location = saveAfter.director.location,
            characterIds = (presentIds + outcome.respondedCharacterIds).distinct().take(12),
            eventTypes = plotEventTypes(action, outcome, saveBefore, saveAfter),
            importance = plotImportance(outcome, saveBefore, saveAfter),
            content = content.take(900),
        )
        synchronized(LOCK) {
            val cards = decodeCards(prefs.getString(key(saveAfter.id), null))
                .filterNot { it.scene == saveAfter.scene }
                .plus(card)
                .sortedBy { it.scene }
                .takeLast(MAX_CARDS)
            persist(saveAfter.id, cards)
        }
    }

    /** Manual edits are intentionally supported: plot memory is user-auditable canon, not a black box. */
    fun updateCard(
        saveId: String,
        cardId: String,
        content: String,
        worldTime: String,
        location: String,
        eventTypes: List<String>,
        importance: Double,
    ): Boolean = synchronized(LOCK) {
        val cards = decodeCards(prefs.getString(key(saveId), null))
        val index = cards.indexOfFirst { it.id == cardId && it.saveId == saveId }
        if (index < 0) return@synchronized false
        val next = cards.toMutableList()
        next[index] = next[index].copy(
            content = content.trim().take(900),
            worldTime = worldTime.trim().take(80),
            location = location.trim().take(100),
            eventTypes = eventTypes.map(String::trim).filter(String::isNotBlank).distinct().take(6),
            importance = importance.coerceIn(0.0, 1.0),
            // Editing text invalidates the old vector; the UI/background refresh will rebuild it.
            embeddingSignature = "",
            embeddingDimension = 0,
            embedding = FloatArray(0),
        )
        persist(saveId, next)
        true
    }

    fun deleteCard(saveId: String, cardId: String): Boolean = synchronized(LOCK) {
        val cards = decodeCards(prefs.getString(key(saveId), null))
        val next = cards.filterNot { it.id == cardId && it.saveId == saveId }
        if (next.size == cards.size) return@synchronized false
        persist(saveId, next)
        true
    }

    fun invalidateEmbeddings(saveId: String) {
        if (saveId.isBlank()) return
        synchronized(LOCK) {
            val cards = decodeCards(prefs.getString(key(saveId), null))
            if (cards.isEmpty()) return@synchronized
            persist(
                saveId,
                cards.map {
                    it.copy(
                        embeddingSignature = "",
                        embeddingDimension = 0,
                        embedding = FloatArray(0),
                    )
                },
            )
        }
    }

    fun updateEmbeddings(saveId: String, updates: Map<String, EmbeddedPlotMemoryV5>) {
        if (updates.isEmpty()) return
        synchronized(LOCK) {
            val cards = decodeCards(prefs.getString(key(saveId), null))
            val updated = cards.map { card ->
                updates[card.id]?.let { value ->
                    card.copy(
                        embeddingSignature = value.signature,
                        embeddingDimension = value.vector.size,
                        embedding = value.vector,
                    )
                } ?: card
            }
            persist(saveId, updated)
        }
    }

    fun trimAfterScene(saveId: String, scene: Int) {
        synchronized(LOCK) {
            persist(saveId, decodeCards(prefs.getString(key(saveId), null)).filter { it.scene <= scene })
        }
    }

    fun clear(saveId: String) {
        if (saveId.isBlank()) return
        synchronized(LOCK) { prefs.edit().remove(key(saveId)).apply() }
    }

    private fun persist(saveId: String, cards: List<ApocalypsePlotMemoryCardV5>) {
        if (cards.isEmpty()) {
            prefs.edit().remove(key(saveId)).apply()
        } else {
            prefs.edit().putString(key(saveId), JSONArray().apply { cards.forEach { put(encodeCard(it)) } }.toString()).apply()
        }
    }

    private fun key(saveId: String): String = "plot_$saveId"

    private companion object {
        const val PREFS_NAME = "apocalypse_plot_memory_v5"
        const val MAX_CARDS = 320
        val LOCK = Any()
    }
}

internal data class EmbeddedPlotMemoryV5(val signature: String, val vector: FloatArray)

internal object ApocalypsePlotMemoryRuntimeV5 {
    private val activeRefreshes = mutableSetOf<String>()

    suspend fun recall(
        context: Context,
        save: ApocalypseV3Save,
        action: String,
        limit: Int = 5,
    ): String {
        val store = ApocalypsePlotMemoryStoreV5(context)
        store.bootstrapFromSave(save)
        val cards = store.load(save.id)
            // The immediately preceding scene is already supplied in full by the continuity pack.
            .filter { it.scene < save.scene }
        if (cards.isEmpty()) return ""
        val query = buildString {
            append(action)
            append("｜地点=${save.director.location}｜目标=${save.director.sceneGoal}")
            append("｜在场=${save.director.presentCharacterIds.joinToString()}")
        }
        val queryTerms = plotTerms(query)
        val lexical = cards
            .map { card -> card to plotLexicalScore(card, queryTerms, save.scene, save.director.location) }
            .sortedByDescending { it.second }
        val lexicalRelevant = lexical.filter { (card, score) -> score >= 1.35 || card.importance >= 0.85 }

        val connection = MemoryModelRuntime.embeddingConnection().takeIf { MemoryModelRuntime.vectorEnabled() }
        val signature = connection?.let(::embeddingSignature).orEmpty()
        val embeddedCards = if (signature.isBlank()) emptyList() else cards.filter {
            it.embeddingSignature == signature && it.embedding.isNotEmpty() && it.embeddingDimension == it.embedding.size
        }
        // No artificial 1–2 second cutoff here. The configured model/network owns its real timeout.
        // This keeps semantic recall from silently degrading to lexical recall on a merely slow endpoint.
        val queryVector = if (connection == null || embeddedCards.isEmpty()) null else {
            LuluAiServices.gateway.embed(connection, listOf(query)).getOrNull()?.singleOrNull()
        }
        var candidates = if (queryVector != null) {
            val lexicalById = lexical.associate { it.first.id to it.second }
            cards.sortedByDescending { card ->
                val vectorScore = if (card.embeddingSignature == signature) cosine(queryVector, card.embedding) else -1.0
                lexicalById.getValue(card.id) + vectorScore.coerceAtLeast(0.0) * 4.5
            }
        } else {
            lexicalRelevant.map { it.first }
        }.take(18)

        val rerankConnection = MemoryModelRuntime.rerankConnection().takeIf {
            MemoryModelRuntime.rerankEnabled() && candidates.isNotEmpty()
        }
        if (rerankConnection != null) {
            val snapshot = candidates
            val order = LuluAiServices.gateway.rerank(rerankConnection, query, snapshot.map { it.content }).getOrNull()
            if (!order.isNullOrEmpty()) {
                candidates = order.mapNotNull(snapshot::getOrNull) + snapshot.filterIndexed { index, _ -> index !in order }
            }
        }
        val recalled = candidates.distinctBy { it.id }.take(limit.coerceIn(1, 8))
        return if (recalled.isEmpty()) "" else buildString {
            appendLine("与本幕相关的本存档旧剧情（只可按记录使用；当前硬状态永远优先）：")
            recalled.forEach { card -> appendLine("- [第${card.scene}幕｜${card.worldTime}｜${card.location}] ${card.content}") }
        }.trim()
    }

    suspend fun refreshEmbeddings(context: Context, saveId: String) {
        val shouldRun = synchronized(activeRefreshes) { activeRefreshes.add(saveId) }
        if (!shouldRun) return
        try {
            val connection = MemoryModelRuntime.embeddingConnection().takeIf { MemoryModelRuntime.vectorEnabled() }
                ?: return
            val signature = embeddingSignature(connection)
            val store = ApocalypsePlotMemoryStoreV5(context)
            // Background rebuilding is no longer capped to 96 cards per pass. Process every stale
            // plot card in bounded API batches so old saves can fully catch up by themselves.
            val pending = store.load(saveId)
                .filter { it.embeddingSignature != signature || it.embedding.isEmpty() }
                .sortedWith(compareByDescending<ApocalypsePlotMemoryCardV5> { it.importance }.thenByDescending { it.scene })
            pending.chunked(EMBED_BATCH_SIZE).forEach { batch ->
                val vectors = LuluAiServices.gateway.embed(connection, batch.map { it.content }).getOrNull()
                    ?.takeIf { it.size == batch.size }
                    ?: return
                store.updateEmbeddings(
                    saveId,
                    batch.zip(vectors).associate { (card, vector) ->
                        card.id to EmbeddedPlotMemoryV5(signature, vector)
                    },
                )
            }
        } finally {
            synchronized(activeRefreshes) { activeRefreshes.remove(saveId) }
        }
    }

    private const val EMBED_BATCH_SIZE = 32
}

private fun plotEventTypes(
    action: String,
    outcome: ApocalypseSceneOutcomeV5,
    before: ApocalypseV3Save,
    after: ApocalypseV3Save,
): List<String> = buildList {
    val text = "$action ${outcome.actionOutcome}"
    if (listOf("买", "采购", "交易", "付款").any(text::contains)) add("交易")
    if (listOf("吃", "喝", "做饭", "休息", "睡").any(text::contains)) add("生存日常")
    if (listOf("打", "战斗", "丧尸", "感染者", "受伤").any(text::contains)) add("战斗")
    if (listOf("问", "说", "告诉", "谈", "争执").any(text::contains)) add("关系互动")
    if (listOf("调查", "线索", "发现", "秘密", "真相").any(text::contains)) add("调查")
    if (before.director.location != after.director.location) add("移动")
    if (before.stats != after.stats) add("状态变化")
    if (outcome.storyThreadUpdates.isNotEmpty() || outcome.foreshadowPatches.isNotEmpty()) add("剧情线推进")
    if (isEmpty()) add("连续事件")
}.distinct().take(6)

private fun plotImportance(outcome: ApocalypseSceneOutcomeV5, before: ApocalypseV3Save, after: ApocalypseV3Save): Double {
    var value = 0.48
    if (outcome.castUpdates.isNotEmpty()) value += 0.12
    if (outcome.storyThreadUpdates.isNotEmpty()) value += 0.14
    if (outcome.foreshadowPatches.isNotEmpty()) value += 0.14
    if (before.director.location != after.director.location) value += 0.06
    if (kotlin.math.abs(after.stats.health - before.stats.health) >= 15) value += 0.06
    return value.coerceIn(0.35, 1.0)
}

private fun plotLexicalScore(
    card: ApocalypsePlotMemoryCardV5,
    queryTerms: Set<String>,
    currentScene: Int,
    currentLocation: String,
): Double {
    val cardTerms = plotTerms(
        card.content + " " + card.eventTypes.joinToString(" ") + " " + card.characterIds.joinToString(" "),
    )
    val overlap = if (queryTerms.isEmpty() || cardTerms.isEmpty()) 0.0 else {
        queryTerms.intersect(cardTerms).size.toDouble() / queryTerms.size.coerceAtLeast(1)
    }
    val exact = queryTerms.count { it.length >= 2 && card.content.contains(it, ignoreCase = true) }.coerceAtMost(5)
    val age = (currentScene - card.scene).coerceAtLeast(1).toDouble()
    val recency = 1.0 / (1.0 + ln(1.0 + age))
    val location = if (currentLocation.isNotBlank() && card.location == currentLocation) 0.75 else 0.0
    return overlap * 7.0 + exact * 0.45 + card.importance + recency + location
}

private fun plotTerms(text: String): Set<String> {
    val normalized = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    if (normalized.isBlank()) return emptySet()
    return buildSet {
        normalized.split(Regex("\\s+")).forEach { token ->
            if (token.length >= 2) add(token)
            if (token.any { it.code > 127 }) {
                token.windowed(2, 1, false).forEach(::add)
                token.windowed(3, 1, false).forEach(::add)
            }
        }
    }
}

private fun cosine(left: FloatArray, right: FloatArray): Double {
    if (left.isEmpty() || left.size != right.size) return -1.0
    var dot = 0.0
    var leftNorm = 0.0
    var rightNorm = 0.0
    for (index in left.indices) {
        dot += left[index] * right[index]
        leftNorm += left[index] * left[index]
        rightNorm += right[index] * right[index]
    }
    return if (leftNorm == 0.0 || rightNorm == 0.0) -1.0 else dot / sqrt(leftNorm * rightNorm)
}

private fun embeddingSignature(connection: com.jiacimu.lulu.ai.ModelConnection): String =
    "${connection.baseUrl.trimEnd('/')}|${connection.model}"

private fun encodeCard(value: ApocalypsePlotMemoryCardV5): JSONObject = JSONObject()
    .put("id", value.id)
    .put("saveId", value.saveId)
    .put("scene", value.scene)
    .put("worldTime", value.worldTime)
    .put("location", value.location)
    .put("characterIds", JSONArray(value.characterIds))
    .put("eventTypes", JSONArray(value.eventTypes))
    .put("importance", value.importance)
    .put("content", value.content)
    .put("embeddingSignature", value.embeddingSignature)
    .put("embeddingDimension", value.embeddingDimension)
    .put("embedding", encodeVector(value.embedding))
    .put("createdAt", value.createdAt)

private fun decodeCards(raw: String?): List<ApocalypsePlotMemoryCardV5> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val vector = decodeVector(item.optString("embedding"))
                add(
                    ApocalypsePlotMemoryCardV5(
                        id = item.optString("id"),
                        saveId = item.optString("saveId"),
                        scene = item.optInt("scene"),
                        worldTime = item.optString("worldTime"),
                        location = item.optString("location"),
                        characterIds = item.optJSONArray("characterIds").stringList(),
                        eventTypes = item.optJSONArray("eventTypes").stringList(),
                        importance = item.optDouble("importance", 0.5).coerceIn(0.0, 1.0),
                        content = item.optString("content"),
                        embeddingSignature = item.optString("embeddingSignature"),
                        embeddingDimension = item.optInt("embeddingDimension", vector.size),
                        embedding = vector,
                        createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}

private fun JSONArray?.stringList(): List<String> = buildList {
    val array = this@stringList ?: return@buildList
    for (index in 0 until array.length()) array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
}

private fun encodeVector(vector: FloatArray): String {
    if (vector.isEmpty()) return ""
    val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    vector.forEach { value -> buffer.putFloat(value) }
    return Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
}

private fun decodeVector(raw: String): FloatArray {
    if (raw.isBlank()) return FloatArray(0)
    return runCatching {
        val bytes = Base64.decode(raw, Base64.NO_WRAP)
        require(bytes.size % Float.SIZE_BYTES == 0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.float }
    }.getOrDefault(FloatArray(0))
}
