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
    /** A subjective, unresolved inner question for the NEXT chapter; not a world fact. */
    val unresolvedThread: String = "",
)

internal object WorldLifeEpisodeStore {
    private const val PREFS = "lulu_world_life_episodes_v1"
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
        .asSequence().flatMap { it.asSequence() }
        .firstOrNull { it.id == id }

    /** A location outing is one chapter, even when successive perceptions
     * independently execute small, verified actions inside that same place. */
    fun recentAt(characterId: String, location: String, now: Instant = Instant.now()):
        WorldLifeEpisode? = episodes(characterId).lastOrNull()?.takeIf { episode ->
            episode.location == location &&
                java.time.Duration.between(episode.occurredAt, now).toMinutes() in 0..35
        }

    /** A queued scene can finish out of order. Check both time directions so
     * an older queued action cannot create a second chapter for the same outing. */
    fun nearbyAt(characterId: String, location: String, at: Instant): WorldLifeEpisode? =
        episodes(characterId).lastOrNull { episode ->
            episode.location == location &&
                kotlin.math.abs(java.time.Duration.between(episode.occurredAt, at).toMinutes()) <= 35L
        }

    /** Called only after the world executor has committed its actual action. */
    fun record(
        characterId: String,
        location: String,
        title: String,
        prose: String,
        actualAction: String,
        evidenceId: String,
        now: Instant = Instant.now(),
        unresolvedThread: String = "",
        expectedSource: SharedTimelineEvent? = null,
    ): WorldLifeEpisode? {
        if (prefs == null || characterId.isBlank() || evidenceId.isBlank()) return null
        val source = expectedSource ?: SharedExperienceTimeline.eventsByIds(characterId, listOf(evidenceId))
            .firstOrNull() ?: return null
        if (source.characterId != characterId || source.id != evidenceId) return null
        var saved: WorldLifeEpisode? = null
        // Always take the timeline monitor before this store, matching deleteEvent.
        // A model that returns after deletion, an edit, or a life reset cannot commit.
        SharedExperienceTimeline.commitIfEvidenceUnchanged(characterId, listOf(source)) {
            synchronized(this) {
                saved = recordLocked(characterId, location, title, prose, actualAction,
                    evidenceId, now, unresolvedThread)
            }
        }
        return saved
    }

    private fun recordLocked(characterId: String, location: String, title: String, prose: String,
        actualAction: String, evidenceId: String, now: Instant, unresolvedThread: String): WorldLifeEpisode? {
        val scene = prose.trim().replace(Regex("\\n{4,}"), "\n\n").take(4_000)
        // The composer already rejects blanks and provider errors. A second
        // arbitrary 100-character gate used to discard otherwise valid prose.
        if (scene.isBlank() || actualAction.isBlank()) return null
        val prior = episodes(characterId)
        if (prior.any { it.evidenceId == evidenceId }) return prior.first { it.evidenceId == evidenceId }
        val episode = WorldLifeEpisode(
            id = UUID.randomUUID().toString(), characterId = characterId,
            occurredAt = now, location = location.take(100),
            title = title.trim().take(70).ifBlank { "一个人的片刻" },
            scene = scene, actualAction = actualAction.take(280),
            evidenceId = evidenceId, unresolvedThread = unresolvedThread.trim().take(220),
        )
        val next = (prior + episode).sortedBy { it.occurredAt }
        check(prefs?.edit()?.putString(characterId, encode(next))?.commit() == true) {
            "角色个人生活片段尚未保存"
        }
        mutable.value = mutable.value + (characterId to next)
        return episode
    }

    fun recentContext(characterId: String, now: Instant = Instant.now()): String {
        val episode = episodes(characterId).lastOrNull() ?: return ""
        val elapsed = java.time.Duration.between(episode.occurredAt, now).toMinutes()
        if (elapsed !in 0..1_440) return ""
        val recentEnoughForContinuation = elapsed <= 35
        return (if (recentEnoughForContinuation)
            "刚才已经经历了一段完整的独处（约${elapsed}分钟前）："
        else "今天较早时真实经历过一段独处（约${elapsed}分钟前；不可当作刚发生）：") +
            "${episode.title}；唯一核实的世界动作：${episode.actualAction}。" +
            "当时的主观叙事（不是新世界事实）：${episode.scene.take(260)}。" +
            episode.unresolvedThread.takeIf(String::isNotBlank)?.let {
                "人物仍有的主观疑问（不是已发生的客观事件）：${it}。"
            }.orEmpty() +
            (if (recentEnoughForContinuation)
                "同一地点的日常动作应接着这段时间继续，不要隔几分钟重复生成走路、坐下的小回执。"
            else "你可以因此产生自己的回味或新决定，但不要复演原剧情、把主观想象说成世界事实。")
    }

    @Synchronized fun delete(id: String): Boolean {
        val found = find(id) ?: return false
        val next = episodes(found.characterId).filterNot { it.id == id }
        if (prefs?.edit()?.putString(found.characterId, encode(next))?.commit() != true) return false
        mutable.value = mutable.value + (found.characterId to next)
        return true
    }

    /** Removing an originating world fact also removes its derived prose. */
    @Synchronized fun invalidateEvidence(evidenceId: String) {
        if (prefs == null || evidenceId.isBlank()) return
        mutable.value.forEach { (characterId, entries) ->
            val next = entries.filterNot { it.evidenceId == evidenceId }
            if (next.size != entries.size &&
                prefs?.edit()?.putString(characterId, encode(next))?.commit() == true) {
                mutable.value = mutable.value + (characterId to next)
            }
        }
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
                .put("evidenceId", e.evidenceId).put("unresolvedThread", e.unresolvedThread))
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
                e.optString("actualAction"), e.optString("evidenceId"),
                e.optString("unresolvedThread"))
        }.sortedBy { it.occurredAt }
    }.getOrDefault(emptyList())
}
