package com.jiacimu.lulu.data

import java.time.Instant

data class CharacterSocialRelationshipSnapshot(
    val firstCharacterId: String,
    val secondCharacterId: String,
    val commonGroupNames: List<String>,
    val sharedMeetingCount: Int,
    val lastMetAt: Instant?,
    val stage: String,
    val hasDirectContact: Boolean = false,
) {
    val knowsEachOther: Boolean
        get() = commonGroupNames.isNotEmpty() || sharedMeetingCount > 0 || hasDirectContact

    fun shortContext(): String = buildString {
        append(stage)
        val sources = buildList {
            if (commonGroupNames.isNotEmpty()) {
                add("共同群聊：${commonGroupNames.take(3).joinToString("、")}")
            }
            if (sharedMeetingCount > 0) {
                add("真实见面${sharedMeetingCount}次")
            }
            if (hasDirectContact) add("有真实私信来往")
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
                "具体偏爱与相处方式应来自与这个人确实经历过的细节、明确说过的偏好和对具体尝试的真实反馈；" +
                "可以记得一个具体瞬间并因此想主动做事，但不要泛称每个人都独一无二，也不要把猜测、单次讨好或故事情节写成对方的固定属性。" +
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
        val directContact = second in ResidentSocialMailbox.contactsOf(first)

        val stage = when {
            meetingCount > 0 && commonGroups.isNotEmpty() -> "共同群聊认识且真实见过面"
            meetingCount > 0 -> "真实见过面"
            commonGroups.isNotEmpty() -> "共同群聊认识"
            directContact -> "有真实私信来往"
            else -> "尚未认识"
        }
        return CharacterSocialRelationshipSnapshot(
            firstCharacterId = first,
            secondCharacterId = second,
            commonGroupNames = commonGroups,
            sharedMeetingCount = meetingCount,
            lastMetAt = lastMetAt,
            stage = stage,
            hasDirectContact = directContact,
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

    /** Classify an already recorded event, rather than letting the model
     * upgrade hearsay, public writing or its own intent into direct knowledge.
     * The original timeline owns provenance and withdrawal; no belief DB.
     */
    internal fun evidenceBoundary(event: SharedTimelineEvent): String = when {
        event.source in setOf("xiaohongshu-webview", "xiaohongshu-mcp") ||
            event.source.startsWith("reading:") ->
            "外部文字/作品：只证明看到这些内容，不证明现实中的人做过"
        event.evidenceKind == EventEvidenceKind.ToolResult ->
            "工具回执：只证明实际执行结果，不证明别人如何想"
        event.evidenceKind == EventEvidenceKind.UserStatement ->
            "对方原话：只证明本人听到其表达，不证明话中提到的第三人动机"
        event.evidenceKind == EventEvidenceKind.CharacterStatement ->
            "一方陈述/计划：只证明其表达，不证明描述已经发生"
        event.evidenceKind == EventEvidenceKind.Observation &&
            ResidentSocialMailbox.witnessedMessage(event) != null ->
            "收到的私信：只证明发信人如此表示，转述不能当亲眼见证"
        event.evidenceKind == EventEvidenceKind.Observation ->
            "本人在场所见：只证明所见行为，不证明他人心意"
        else -> "历史来源不明确：保持不确定，不可当成已证实事实"
    }

    /** No guessed provenance when a source was removed or is unavailable. */
    internal fun evidenceBoundary(characterId: String, evidenceId: String): String {
        if (evidenceId.isBlank()) return "缺少可核验来源：只是旧主观看法"
        val source = SharedExperienceTimeline.eventsByIds(characterId, listOf(evidenceId))
            .firstOrNull() ?: return "来源已不可核验：旧看法不可当作事实"
        return evidenceBoundary(source)
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
                    buildString {
                        append("- 我对$name：${view.impression}")
                        if (view.reason.isNotBlank()) append("；具体依据=${view.reason}")
                        append("；证据边界=${evidenceBoundary(characterId, view.evidenceId)}")
                        if (view.priorImpression.isNotBlank() &&
                            view.priorImpression != view.impression) {
                            append("；之前曾认为=${view.priorImpression}（这是旧看法，并非当前事实）")
                        }
                        if (view.trend.isNotBlank()) append("；${view.trend}")
                        append("。这是我单方面、可能改变的印象，不代表对方也这么看我。")
                    }
                }
            }.joinToString("\n")

    fun knowsEachOther(firstCharacterId: String, secondCharacterId: String): Boolean =
        snapshot(firstCharacterId, secondCharacterId).knowsEachOther

    /**
     * One line for a pair of the focal character's contacts, or null when
     * the two have no real connection at all. Only the connection itself
     * is reported (shared group, real meetings, direct mail contact):
     * never the names of groups the focal character is not in, never
     * either side's private impression of the other, and never a score.
     * Pure function of the snapshot so it can be tested without stores.
     */
    internal fun networkPairLine(
        firstName: String, secondName: String,
        relation: CharacterSocialRelationshipSnapshot,
    ): String? {
        if (!relation.knowsEachOther) return null
        val details = buildList {
            if (relation.commonGroupNames.isNotEmpty()) add("共同群聊认识")
            if (relation.sharedMeetingCount > 0) add("真实见面${relation.sharedMeetingCount}次")
            if (relation.hasDirectContact) add("有真实私信来往")
        }
        return "$firstName ↔ $secondName：" + relation.stage +
            if (details.isEmpty()) "" else "；" + details.joinToString("；")
    }

    private fun networkDisplayName(id: String): String = when {
        id == "user" -> "用户"
        id.startsWith("resident:") ->
            DigitalWorldExpansionStore.residents()
                .firstOrNull { "resident:${it.id}" == id }?.name ?: "原住民"
        else -> runCatching { MigratedDomainStores.characters.get(id).displayName }
            .getOrDefault("另一位角色")
    }

    /**
     * Relationship knowledge is NOT symmetric or omniscient. A focal character
     * may know two contacts, but that never grants access to their private DMs
     * or meetings at which the focal character was absent. Only connections
     * the focal character could witness (own group membership, attended
     * completed meeting) may enter the focal character's mental context.
     *
     * The global two-person snapshot remains useful for contact permission
     * checks and owner UI; it is deliberately NOT a private memory source.
     */
    internal fun witnessedNetworkSnapshot(
        viewerId: String, firstId: String, secondId: String,
    ): CharacterSocialRelationshipSnapshot {
        val blank = CharacterSocialRelationshipSnapshot(
            firstId, secondId, emptyList(), 0, null, "尚未见证二人关系", false)
        if (viewerId.isBlank() || firstId.isBlank() || secondId.isBlank() ||
            viewerId == firstId || viewerId == secondId || firstId == secondId) return blank
        val groups = MigratedDomainStores.chat.conversations.value.asSequence()
            .mapNotNull { it.groupChat }
            .filter { group ->
                val memberIds = group.members.map(LuluGroupMember::characterId)
                viewerId in memberIds && firstId in memberIds && secondId in memberIds
            }
            .map { it.name.trim().ifBlank { "共同群聊" } }.distinct().toList()
        val meetings = DigitalWorldStore.state.value.meetings.asSequence()
            .filter { event ->
                event.endedAt != null && event.turns.isNotEmpty() &&
                    viewerId in event.participantIds &&
                    firstId in event.participantIds && secondId in event.participantIds
            }.sortedBy { it.endedAt ?: it.startedAt }.toList()
        val stage = when {
            groups.isNotEmpty() && meetings.isNotEmpty() -> "本人同群且共同见过面"
            groups.isNotEmpty() -> "本人共同群聊可见"
            meetings.isNotEmpty() -> "本人见证的共同会面"
            else -> "尚未见证二人关系"
        }
        return CharacterSocialRelationshipSnapshot(
            firstId, secondId, groups, meetings.size,
            meetings.lastOrNull()?.let { it.endedAt ?: it.startedAt },
            stage, false, // Never reveal pairwise private mail to a third party.
        )
    }

    /**
     * Network view for private chat: how the focal character's own
     * contacts relate to EACH OTHER. The focal character's own bonds
     * already arrive from CharacterInnerLifeStore; what was missing is
     * the between-contacts structure (do my two friends know each other,
     * have they actually met), which group chats already get from
     * contextForGroup but private chat never did. Only stored facts are
     * used, unconnected pairs are skipped entirely so the section stays
     * silent instead of dumping "尚未认识" lines, and the list is capped
     * at 6 pairs. Returns "" when there is nothing connected to report.
     */
    fun networkContext(characterId: String, contactIds: Collection<String>): String {
        val contacts = contactIds.map(String::trim)
            .filter { it.isNotBlank() && it != characterId && it != "user" }.distinct()
        if (contacts.size < 2) return ""
        val lines = buildList {
            for (firstIndex in 0 until contacts.lastIndex) {
                for (secondIndex in firstIndex + 1 until contacts.size) {
                    val first = contacts[firstIndex]
                    val second = contacts[secondIndex]
                    networkPairLine(
                        networkDisplayName(first), networkDisplayName(second),
                        witnessedNetworkSnapshot(characterId, first, second),
                    )?.let(::add)
                    if (size >= 6) break
                }
                if (size >= 6) break
            }
        }
        if (lines.isEmpty()) return ""
        return buildString {
            appendLine("【身边人的关系网｜只看他们彼此之间有没有真实交集】")
            lines.forEach(::appendLine)
            append("这只是两人之间有没有真实交集，不是好感度，也不代表他们彼此怎么看对方；" +
                "想叫谁一起、托谁传话时，先看这里，不要假设两个人互相认识。")
        }.trim()
    }

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
                        add("  $firstName 对 $secondName 的看法：${view.impression}；根据=${view.reason}；来源=${evidenceBoundary(first, view.evidenceId)}")
                    }
                    perspective(second, first)?.let { view ->
                        add("  $secondName 对 $firstName 的看法：${view.impression}；根据=${view.reason}；来源=${evidenceBoundary(second, view.evidenceId)}")
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
