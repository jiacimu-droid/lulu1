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
 * Social acquaintance is derived from real history instead of a hidden affection score or a
 * numeric familiarity ladder. A common group already means the characters know each other;
 * completed meetings are factual shared experience, but the count never auto-upgrades intimacy.
 */
data class SocialPerspective(
    val fromId: String,
    val towardId: String,
    val impression: String,
    val reason: String,
    val priorImpression: String,
    val trend: String,
    val evidenceId: String,
    val updatedAt: String,
)

data class SocialRelationshipAgreement(
    val fromId: String,
    val towardId: String,
    val title: String,
    val details: String,
    val updatedAt: String,
)

object CharacterSocialRelationship {
    /** Explicit user-editable relationship agreement in the existing character
     * state. Dynamic, evidence-backed subjective views remain in bonds.
     */
    fun agreement(fromId: String, towardId: String): SocialRelationshipAgreement? {
        val item = CharacterLifeStore.state(fromId)
            .optJSONObject("relationshipAgreements")?.optJSONObject(towardId) ?: return null
        val title = item.optString("title").trim()
        val details = item.optString("details").trim()
        if (title.isBlank() && details.isBlank()) return null
        return SocialRelationshipAgreement(
            fromId, towardId, title, details, item.optString("updatedAt"))
    }

    fun setAgreement(fromId: String, towardId: String, title: String, details: String): Boolean =
        CharacterLifeStore.setRelationshipAgreement(fromId, towardId, title, details)

    fun promptSection(characterId: String): String {
        val items = CharacterLifeStore.state(characterId)
            .optJSONObject("relationshipAgreements") ?: return ""
        val keys = items.keys().asSequence().toList()
            .sortedWith(compareByDescending<String> { it == "user" }.thenBy { it }).take(8)
        val active = keys.mapNotNull { agreement(characterId, it) }
        if (active.isEmpty()) return ""
        return buildString {
            appendLine("【人物关系网络｜当前明确关系约定；具体相处可随证据与当事人调整】")
            active.forEach { bond ->
                val name = if (bond.towardId == "user") "用户" else
                    runCatching { MigratedDomainStores.characters.get(bond.towardId).displayName }
                        .getOrDefault(bond.towardId)
                appendLine("- 与$name：${bond.title}；${bond.details}")
            }
            append("关系中的身份、约定与界限以当前编辑的内容为准；" +
                "对他人的主观印象、暂时心情和信任趋势以真实经历为据，可改变，不能伪装成永远不变的事实。" +
                "你对某人的内心变化不能替对方决定其感受。")
        }
    }


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
            meetingCount > 0 && commonGroups.isNotEmpty() -> "共同群聊认识且真实见过面"
            meetingCount > 0 -> "真实见过面"
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

    /**
     * A -> B is a PRIVATE, evidence-backed view, never a shared affection value.
     * Each direction comes from its own character's inner-life bond ledger.
     */
    fun perspective(fromId: String, towardId: String): SocialPerspective? {
        val bond = CharacterInnerLifeStore.snapshot(fromId).optJSONObject("bonds")
            ?.optJSONObject(towardId) ?: return null
        val opinion = bond.optString("interpretation").trim()
        if (opinion.isBlank()) return null
        return SocialPerspective(
            fromId = fromId,
            towardId = towardId,
            impression = opinion.take(180),
            reason = bond.optString("reason").take(180),
            priorImpression = bond.optString("priorThought").take(120),
            trend = bond.optJSONObject("trends")?.let { trends ->
                listOf("trust" to "信任", "warmth" to "亲近", "ease" to "自在",
                    "friction" to "摩擦", "boundarySafety" to "边界感")
                    .mapNotNull { (key, title) ->
                        val value = trends.optString(key)
                        if (value.isBlank() || value == "same") null else "$title：$value"
                    }.joinToString("；")
            }.orEmpty(),
            evidenceId = bond.optString("evidenceId"),
            updatedAt = bond.optString("updatedAt"),
        )
    }

    fun perspectivesFor(characterId: String, otherIds: Collection<String>): String =
        otherIds.asSequence().filter { it != characterId }.distinct().take(12)
            .mapNotNull { target ->
                perspective(characterId, target)?.let { view ->
                    val name = when {
                        target == "user" -> "用户"
                        target.startsWith("resident:") ->
                            DigitalWorldExpansionStore.residents()
                                .firstOrNull { "resident:${it.id}" == target }?.name ?: "原住民"
                        else -> runCatching { MigratedDomainStores.characters.get(target).displayName }
                            .getOrDefault("另一位角色")
                    }
                    "- 我对$name：${view.impression}；依据=${view.reason}；${view.trend}"
                }
            }.joinToString("\n")

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
                    perspective(first, second)?.let { view ->
                        add("  $firstName 对 $secondName 的看法：${view.impression}；根据=${view.reason}")
                    }
                    perspective(second, first)?.let { view ->
                        add("  $secondName 对 $firstName 的看法：${view.impression}；根据=${view.reason}")
                    }
                }
            }
        }
        return buildString {
            appendLine("【角色间已有社会关系｜共同群聊与真实见面只是事实，不是好感度或升级条】")
            lines.forEach(::appendLine)
            append("见面次数没有自动升级阈值。是否变熟、亲近、疏远、喜欢、厌烦或维持原样，只能从实际相处内容、时间跨度、双方人设、记住彼此什么以及之后各自是否还想来往中自然形成。")
        }
    }
}
