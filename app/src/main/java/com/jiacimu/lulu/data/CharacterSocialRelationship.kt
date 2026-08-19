package com.jiacimu.lulu.data

import java.time.Instant

data class CharacterSocialRelationshipSnapshot(
    val firstCharacterId: String,
    val secondCharacterId: String,
    val commonGroupNames: List<String>,
    val sharedMeetingCount: Int,
    val lastMetAt: Instant?,
    val stage: String,
) {
    val knowsEachOther: Boolean
        get() = commonGroupNames.isNotEmpty() || sharedMeetingCount > 0

    fun shortContext(): String = buildString {
        append(stage)
        val sources = buildList {
            if (commonGroupNames.isNotEmpty()) {
                add("共同群聊：${commonGroupNames.take(3).joinToString("、")}")
            }
            if (sharedMeetingCount > 0) {
                add("真实见面${sharedMeetingCount}次")
            }
        }
        if (sources.isNotEmpty()) append("；").append(sources.joinToString("；"))
        lastMetAt?.let { append("；最近见面=$it") }
    }
}

/**
 * Social familiarity is derived from real history instead of a hidden affection score.
 * A common group already means the characters know each other; completed shared meetings add
 * lived experience and gradually change how familiar their future interactions may feel.
 */
object CharacterSocialRelationship {
    fun snapshot(firstCharacterId: String, secondCharacterId: String): CharacterSocialRelationshipSnapshot {
        val first = firstCharacterId.trim()
        val second = secondCharacterId.trim()
        if (first.isBlank() || second.isBlank() || first == second) {
            return CharacterSocialRelationshipSnapshot(first, second, emptyList(), 0, null, "尚未认识")
        }

        val commonGroups = MigratedDomainStores.chat.conversations.value
            .asSequence()
            .mapNotNull { it.groupChat }
            .filter { group ->
                val ids = group.members.map(LuluGroupMember::characterId)
                first in ids && second in ids
            }
            .map { it.name.trim().ifBlank { "共同群聊" } }
            .distinct()
            .toList()

        val meetings = DigitalWorldStore.state.value.meetings
            .asSequence()
            .filter { meeting ->
                meeting.endedAt != null &&
                    meeting.turns.isNotEmpty() &&
                    first in meeting.participantIds &&
                    second in meeting.participantIds
            }
            .sortedBy { it.endedAt ?: it.startedAt }
            .toList()
        val meetingCount = meetings.size
        val lastMetAt = meetings.lastOrNull()?.let { it.endedAt ?: it.startedAt }

        val stage = when {
            meetingCount >= 6 -> "经常来往"
            meetingCount >= 3 -> "已经熟悉"
            meetingCount >= 1 && commonGroups.isNotEmpty() -> "认识且见过面"
            meetingCount >= 1 -> "已经见过面"
            commonGroups.isNotEmpty() -> "共同群聊认识"
            else -> "尚未认识"
        }
        return CharacterSocialRelationshipSnapshot(
            firstCharacterId = first,
            secondCharacterId = second,
            commonGroupNames = commonGroups,
            sharedMeetingCount = meetingCount,
            lastMetAt = lastMetAt,
            stage = stage,
        )
    }

    fun knowsEachOther(firstCharacterId: String, secondCharacterId: String): Boolean =
        snapshot(firstCharacterId, secondCharacterId).knowsEachOther

    fun contextForGroup(participantIds: List<String>): String {
        val ids = participantIds.map(String::trim).filter(String::isNotBlank).distinct()
        if (ids.size < 2) return "暂无角色间社会关系历史。"
        val lines = buildList {
            for (firstIndex in 0 until ids.lastIndex) {
                for (secondIndex in firstIndex + 1 until ids.size) {
                    val first = ids[firstIndex]
                    val second = ids[secondIndex]
                    val relation = snapshot(first, second)
                    val firstName = runCatching { MigratedDomainStores.characters.get(first).displayName }.getOrDefault(first)
                    val secondName = runCatching { MigratedDomainStores.characters.get(second).displayName }.getOrDefault(second)
                    add("- $firstName ↔ $secondName：${relation.shortContext()}")
                }
            }
        }
        return buildString {
            appendLine("【角色间已有社会关系｜由共同群聊和真实见面历史推导，不是好感度】")
            lines.forEach(::appendLine)
            append("关系阶段只代表认识与来往经验，不自动代表喜欢、亲密、恋爱或敌意。")
        }
    }
}
