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
    /** All verified facts behind this readable chapter; legacy prose has just one. */
    val sourceEvidenceIds: List<String> = listOf(evidenceId),
    val category: String = "outing",
)

internal object WorldLifeEpisodeStore {
    private const val PREFS = "lulu_world_life_episodes_v1"
    private const val DELETED_PREFIX = "__deleted_chapter_sources__:"
    private var prefs: android.content.SharedPreferences? = null
    private val mutable = MutableStateFlow<Map<String, List<WorldLifeEpisode>>>(emptyMap())
    val state = mutable.asStateFlow()

    @Synchronized fun initialize(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = store
        mutable.value = store.all.mapNotNull { (id, raw) ->
            if (id.startsWith(DELETED_PREFIX)) null
            else (raw as? String)?.let { id to decode(it) }
        }.toMap()
    }

    fun episodes(characterId: String): List<WorldLifeEpisode> =
        mutable.value[characterId].orEmpty()

    fun find(id: String): WorldLifeEpisode? = mutable.value.values
        .asSequence().flatMap { it.asSequence() }
        .firstOrNull { it.id == id }

    /** A deleted chapter is intentionally NOT regenerated from its still-valid
     * physical action if a delayed worker retries. Never delete that action. */
    private fun deletedSources(characterId: String): Set<String>? = runCatching {
        val raw = prefs?.getString(DELETED_PREFIX + characterId, "[]") ?: return@runCatching emptySet()
        val array = JSONArray(raw)
        (0 until array.length()).map { array.getString(it) }.toSet()
    }.getOrNull()

    /** A location outing is one chapter, even when successive perceptions
     * independently execute small, verified actions inside that same place. */
    fun recentAt(characterId: String, location: String, now: Instant = Instant.now()):
        WorldLifeEpisode? = episodes(characterId).lastOrNull()?.takeIf { episode ->
            episode.location == location && episode.category == "outing" &&
                java.time.Duration.between(episode.occurredAt, now).toMinutes() in 0..35
        }

    /** A queued scene can finish out of order. Check both time directions so
     * an older queued action cannot create a second chapter for the same outing. */
    fun nearbyAt(characterId: String, location: String, at: Instant): WorldLifeEpisode? =
        episodes(characterId).lastOrNull { episode ->
            episode.location == location && episode.category == "outing" &&
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
        sourceEvidenceIds: List<String> = listOf(evidenceId),
        category: String = "outing",
    ): WorldLifeEpisode? {
        if (prefs == null || characterId.isBlank() || evidenceId.isBlank()) return null
        val source = expectedSource ?: SharedExperienceTimeline.eventsByIds(characterId, listOf(evidenceId))
            .firstOrNull() ?: return null
        if (source.characterId != characterId || source.id != evidenceId) return null
        val links = (listOf(evidenceId) + sourceEvidenceIds).distinct().take(16)
        val currentSources = SharedExperienceTimeline.eventsByIds(characterId, links)
        if (currentSources.size != links.size || currentSources.any { it.characterId != characterId } ||
            currentSources.firstOrNull { it.id == evidenceId } != source) return null
        var saved: WorldLifeEpisode? = null
        // Always take the timeline monitor before this store, matching deleteEvent.
        // A model that returns after deletion, an edit, or a life reset cannot commit.
        SharedExperienceTimeline.commitIfEvidenceUnchanged(characterId, currentSources) {
            synchronized(this) {
                saved = recordLocked(characterId, location, title, prose, actualAction,
                    evidenceId, now, unresolvedThread, links, category)
            }
        }
        return saved
    }

    private fun recordLocked(characterId: String, location: String, title: String, prose: String,
        actualAction: String, evidenceId: String, now: Instant, unresolvedThread: String,
        sourceEvidenceIds: List<String>, category: String): WorldLifeEpisode? {
        val scene = prose.trim().replace(Regex("\\n{4,}"), "\n\n").take(4_000)
        // The composer already rejects blanks and provider errors. A second
        // arbitrary 100-character gate used to discard otherwise valid prose.
        if (scene.isBlank() || actualAction.isBlank()) return null
        val deleted = deletedSources(characterId) ?: return null
        if (evidenceId in deleted) return null
        val prior = episodes(characterId)
        if (prior.any { it.evidenceId == evidenceId }) return prior.first { it.evidenceId == evidenceId }
        val episode = WorldLifeEpisode(
            id = UUID.randomUUID().toString(), characterId = characterId,
            occurredAt = now, location = location.take(100),
            title = title.trim().take(70).ifBlank { "一个人的片刻" },
            scene = scene, actualAction = actualAction.take(280),
            evidenceId = evidenceId, unresolvedThread = unresolvedThread.trim().take(220),
            sourceEvidenceIds = sourceEvidenceIds,
            category = category.takeIf { it in setOf("outing", "encounter", "incident") } ?: "outing",
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
        // A novel is a readable artifact, NOT a reliable source of new beliefs
        // about the user. Keeping its prose in model context used to repeat
        // unsupported personal interpretations across story, chat and phone.
        return (if (recentEnoughForContinuation)
            "刚才已有一次生活经历（约${elapsed}分钟前）："
        else "今天较早时已有一次生活经历（约${elapsed}分钟前）：") +
            "经执行器核实的动作：${episode.actualAction}。" +
            (if (recentEnoughForContinuation)
                "避免短时间内重复执行相同的日常动作。"
            else "保留实际行动的时间关系；不要把文学正文当成事实或新的用户画像。")
    }

    @Synchronized fun delete(id: String): Boolean {
        val found = find(id) ?: return false
        val deleted = deletedSources(found.characterId) ?: return false
        val next = episodes(found.characterId).filterNot { it.id == id }
        val tombstones = JSONArray().apply {
            (deleted + found.evidenceId).forEach(::put)
        }
        // Both changes in one atomic prefs commit: user intent is preserved
        // without deleting the source timeline event or unrelated chapters.
        if (prefs?.edit()?.putString(found.characterId, encode(next))
                ?.putString(DELETED_PREFIX + found.characterId, tombstones.toString())
                ?.commit() != true) return false
        mutable.value = mutable.value + (found.characterId to next)
        return true
    }

    /** Removing an originating world fact also removes its derived prose. */
    @Synchronized fun invalidateEvidence(evidenceId: String) {
        if (prefs == null || evidenceId.isBlank()) return
        mutable.value.forEach { (characterId, entries) ->
            val next = entries.filterNot { evidenceId in it.sourceEvidenceIds }
            if (next.size != entries.size &&
                prefs?.edit()?.putString(characterId, encode(next))?.commit() == true) {
                mutable.value = mutable.value + (characterId to next)
            }
        }
    }

    @Synchronized fun clear(characterId: String) {
        prefs?.edit()?.remove(characterId)?.remove(DELETED_PREFIX + characterId)?.commit()
        mutable.value = mutable.value - characterId
    }

    private fun encode(entries: List<WorldLifeEpisode>): String = JSONArray().apply {
        entries.forEach { e ->
            put(JSONObject().put("id", e.id).put("characterId", e.characterId)
                .put("occurredAt", e.occurredAt.toString())
                .put("location", e.location).put("title", e.title)
                .put("scene", e.scene).put("actualAction", e.actualAction)
                .put("evidenceId", e.evidenceId).put("unresolvedThread", e.unresolvedThread)
                .put("category", e.category).put("sourceEvidenceIds", JSONArray(e.sourceEvidenceIds)))
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
                e.optString("unresolvedThread"),
                e.optJSONArray("sourceEvidenceIds")?.let { links ->
                    (0 until links.length()).mapNotNull { n ->
                        links.optString(n).takeIf(String::isNotBlank)
                    }
                }?.takeIf(List<String>::isNotEmpty) ?: listOf(e.optString("evidenceId")),
                e.optString("category").ifBlank { "outing" })
        }.sortedBy { it.occurredAt }
    }.getOrDefault(emptyList())
}
