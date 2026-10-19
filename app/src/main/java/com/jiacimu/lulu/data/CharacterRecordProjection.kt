package com.jiacimu.lulu.data

import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.core.MemoryEntry
import org.json.JSONObject
import java.time.Instant

/** Navigation and read models only. Originals keep their IDs, stores and deletion semantics. */
internal enum class CharacterRecordDestination {
    Memory, Timeline, Schedule, Diary, Favorites, Stories, Moments, LifeRecords;

    companion object {
        fun fromLegacy(section: String?, diaryTitle: String? = null,
                       favoriteId: String? = null): CharacterRecordDestination = when {
            !diaryTitle.isNullOrBlank() -> Diary
            !favoriteId.isNullOrBlank() -> Favorites
            else -> when (section?.lowercase()) {
                "diary", "日记" -> Diary
                "favorite", "favorites", "收藏" -> Favorites
                "小剧场", "story", "scene", "life-story" -> Stories
                "promise", "约定" -> Schedule
                "life", "concern", "生活近况", "学习", "学习记录" -> Memory
                "足迹", "历程", "audit", "life-audit", "经历" -> Timeline
                "moments", "朋友圈" -> Moments
                else -> LifeRecords
            }
        }
    }
}

internal data class CharacterRememberedItem(
    val id: String,
    val title: String,
    val content: String,
    val at: Instant,
    val sourceIds: List<String>,
    val memory: MemoryEntry? = null,
    val insight: CharacterLearningLedger.InsightRow? = null,
    val note: LexiconEntry? = null,
)

internal object CharacterRecordProjection {
    /** Learning and old life notes join the memory view without copying them into another store. */
    fun remembered(characterId: String, memories: List<MemoryEntry>,
                   insights: List<CharacterLearningLedger.InsightRow>,
                   notes: List<LexiconEntry>): List<CharacterRememberedItem> {
        val items = memories.filter { it.characterId == characterId &&
            MemoryValidityStore.isActive(it.id) && memoryHasLiveSources(it, characterId) }.map { memory ->
            CharacterRememberedItem("memory:${memory.id}", "记住的事", memory.content,
                memory.occurredAt ?: memory.createdAt, memory.sourceIds(), memory = memory)
        } + insights.filter { SharedExperienceTimeline.eventsByIds(characterId,
            listOf(it.sourceEvidenceId)).isNotEmpty() }.map { insight ->
            CharacterRememberedItem("understanding:${insight.sourceEvidenceId}", insight.topic,
                insight.understanding, insight.occurredAt, listOf(insight.sourceEvidenceId), insight = insight)
        } + notes.filter { it.characterId == characterId &&
            it.section in setOf(LexiconSection.Life, LexiconSection.Concern) && lexiconHasLiveSources(it) }
            .map { note -> CharacterRememberedItem("note:${note.id}", note.title, note.content,
                note.updatedAt, note.sourceEventIds, note = note) }
        // A second projection of the same original is not an independent memory/evidence count.
        return items.distinctBy { item ->
            if (item.sourceIds.isEmpty()) item.id
            else item.sourceIds.sorted().joinToString("|") + ":" + item.content.filterNot(Char::isWhitespace)
        }.sortedWith(compareByDescending<CharacterRememberedItem> { it.memory?.isCoreMemory() == true }
            .thenByDescending { it.at })
    }

    fun eventText(event: SharedTimelineEvent): String {
        val payload = runCatching { JSONObject(event.content) }.getOrNull() ?: return event.content
        val result = payload.optJSONObject("result") ?: runCatching {
            JSONObject(payload.optString("result"))
        }.getOrNull()
        return listOf(result?.optString("summary").orEmpty(), result?.optString("error").orEmpty(),
            payload.optString("summary"), payload.optString("text"), payload.optString("artifact"))
            .firstOrNull(String::isNotBlank) ?: "已保存一条执行记录"
    }

    fun actionLabel(action: String): String = when (action) {
        "silent" -> "暂时保持安静"
        "private_message", "send_private_message", "reply", "message" -> "发送消息"
        "group_message", "send_group_message" -> "在群里说话"
        "digital_world", "digital_world_action" -> "在数字世界活动"
        "read_book", "reading" -> "阅读"
        "publish_moment", "moment" -> "发布朋友圈"
        "write_journal", "journal" -> "写日记"
        "favorite_user_message" -> "收藏消息"
        "play_solo_game", "solo_game" -> "自己玩游戏"
        "read_xiaohongshu" -> "阅读小红书"
        "sticker", "kaomoji" -> "发送表情"
        "group_sticker", "group_kaomoji" -> "在群里发送表情"
        "game_invite" -> "邀请一起玩游戏"
        "world_invite" -> "邀请见面"
        "user_remark" -> "修改私人备注"
        "self_nickname" -> "修改自己的网名"
        "start_call", "call" -> "打电话"
        else -> "做出行动选择"
    }

    /** Only effects that the runtime currently accepts can be shown as effective changes. */
    fun influences(characterId: String, evidenceIds: Collection<String>): List<DevelopmentRecord> {
        if (evidenceIds.isEmpty()) return emptyList()
        val ids = evidenceIds.toSet()
        return CharacterDevelopmentStore.active(characterId).filter { row -> row.evidence.keys.any { it in ids } }
            .distinctBy { it.kind to it.slot }
    }

    data class DecisionMoment(val id: String, val at: Instant, val action: String,
        val reason: String, val result: String, val evidenceId: String)

    /** Decisions join state history, never become a second experience archive. */
    fun decisions(characterId: String, cutoff: Instant = Instant.now()): List<DecisionMoment> {
        val root = CharacterInnerLifeStore.snapshot(characterId)
        val array = root.optJSONArray("decisions") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val row = array.optJSONObject(index) ?: return@mapNotNull null
            val at = runCatching { Instant.parse(row.optString("at")) }.getOrNull() ?: return@mapNotNull null
            if (at.isAfter(cutoff)) return@mapNotNull null
            val evidence = row.optString("causalEvidenceId")
            if (evidence.isNotBlank() && SharedExperienceTimeline.eventsByIds(characterId,
                    listOf(evidence)).isEmpty()) return@mapNotNull null
            DecisionMoment(row.optString("id").ifBlank { "decision:$at:$index" }, at,
                actionLabel(row.optString("selected")), row.optString("reason"),
                row.optString("outcome"), evidence)
        }.distinctBy { it.id }.sortedByDescending { it.at }
    }
}
