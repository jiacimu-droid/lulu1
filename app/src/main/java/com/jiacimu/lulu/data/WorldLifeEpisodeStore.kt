package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A character's PRIVATE, narrated leisure moment.
 *
 * Its only objective world fact is the already-committed execution receipt.
 * Descriptive prose and unspoken thoughts are subjective, NOT extra map moves,
 * acquired objects, meetings or witnessed residents. Opening a moment is free:
 * this store does not call the model.
 */
internal data class WorldLifeEpisode(
    val id: String,
    val characterId: String,
    val occurredAt: Instant,
    val location: String,
    val title: String,
    val scene: String,
    val actualAction: String,
    val evidenceId: String,
)

internal object WorldLifeEpisodeStore {
    private const val PREFS = "lulu_world_life_episodes_v1"
    private const val MAX_PER_CHARACTER = 80
    private var prefs: android.content.SharedPreferences? = null
    private val mutable = MutableStateFlow<Map<String, List<WorldLifeEpisode>>>(emptyMap())
    val state = mutable.asStateFlow()

    @Synchronized fun initialize(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = store
        mutable.value = store.all.mapNotNull { (id, raw) ->
            (raw as? String)?.let { id to decode(it) }
        }.toMap()
    }

    fun episodes(characterId: String): List<WorldLifeEpisode> =
        mutable.value[characterId].orEmpty()

    fun find(id: String): WorldLifeEpisode? = mutable.value.values
        .asSequence().flatMap(List<WorldLifeEpisode>::asSequence)
        .firstOrNull { it.id == id }

    /** Called only after the world executor has committed its actual action. */
    @Synchronized fun record(
        characterId: String,
        location: String,
        title: String,
        prose: String,
        actualAction: String,
        evidenceId: String,
        now: Instant = Instant.now(),
    ): WorldLifeEpisode? {
        if (prefs == null || characterId.isBlank() || evidenceId.isBlank()) return null
        val scene = prose.trim().replace(Regex("\\n{4,}"), "\n\n").take(1_800)
        if (scene.length < 100 || actualAction.isBlank()) return null
        val prior = episodes(characterId)
        if (prior.any { it.evidenceId == evidenceId }) return prior.first { it.evidenceId == evidenceId }
        val episode = WorldLifeEpisode(
            id = UUID.randomUUID().toString(), characterId = characterId,
            occurredAt = now, location = location.take(100),
            title = title.trim().take(70).ifBlank { "一个人的片刻" },
            scene = scene, actualAction = actualAction.take(280),
            evidenceId = evidenceId,
        )
        val next = (prior + episode).takeLast(MAX_PER_CHARACTER)
        check(prefs?.edit()?.putString(characterId, encode(next))?.commit() == true) {
            "角色个人生活片段尚未保存"
        }
        mutable.value = mutable.value + (characterId to next)
        return episode
    }

    fun recentContext(characterId: String, now: Instant = Instant.now()): String {
        val episode = episodes(characterId).lastOrNull() ?: return ""
        val elapsed = java.time.Duration.between(episode.occurredAt, now).toMinutes()
        if (elapsed !in 0..35) return ""
        return "最近已经有过一段完整的独处经历（约${elapsed}分钟前）：" +
            "${episode.title}；真实动作：${episode.actualAction}。这是过去的活动而不是新事件。" +
            "不要为了维持在线连续重演坐下、散步、换地点；若当下没有新的明确兴趣或事情，休息或休眠都是自然的。"
    }

    @Synchronized fun delete(id: String): Boolean {
        val found = find(id) ?: return false
        val next = episodes(found.characterId).filterNot { it.id == id }
        if (prefs?.edit()?.putString(found.characterId, encode(next))?.commit() != true) return false
        mutable.value = mutable.value + (found.characterId to next)
        return true
    }

    @Synchronized fun clear(characterId: String) {
        prefs?.edit()?.remove(characterId)?.commit()
        mutable.value = mutable.value - characterId
    }

    private fun encode(entries: List<WorldLifeEpisode>): String = JSONArray().apply {
        entries.forEach { e ->
            put(JSONObject().put("id", e.id).put("characterId", e.characterId)
                .put("occurredAt", e.occurredAt.toString())
                .put("location", e.location).put("title", e.title)
                .put("scene", e.scene).put("actualAction", e.actualAction)
                .put("evidenceId", e.evidenceId))
        }
    }.toString()

    private fun decode(raw: String): List<WorldLifeEpisode> = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            val e = array.optJSONObject(i) ?: return@mapNotNull null
            val time = runCatching { Instant.parse(e.optString("occurredAt")) }.getOrNull()
                ?: return@mapNotNull null
            val id = e.optString("id")
            val characterId = e.optString("characterId")
            if (id.isBlank() || characterId.isBlank()) return@mapNotNull null
            WorldLifeEpisode(id, characterId, time, e.optString("location"),
                e.optString("title"), e.optString("scene"),
                e.optString("actualAction"), e.optString("evidenceId"))
        }.takeLast(MAX_PER_CHARACTER)
    }.getOrDefault(emptyList())
}
