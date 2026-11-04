package com.jiacimu.lulu.data

/**
 * Delivery-backed, one-hop group social stimulus. This class never chooses
 * anyone's words or decides they must speak; it only verifies which existing
 * group members really witnessed a newly delivered role-authored bubble.
 */
internal object GroupSocialOpportunity {
    const val WAKE_TRIGGER = "数字人物群聊新消息"

    fun recipients(
        group: LuluGroupChat, senderId: String, knownRoles: Set<String>,
        deliveredMessageId: String,
    ): List<String> {
        if (!group.allowCharacterConversation || deliveredMessageId.isBlank()) return emptyList()
        val members = group.members.map(LuluGroupMember::characterId).distinct()
            .filter { it != senderId && it in knownRoles }
        if (members.isEmpty()) return emptyList()
        // maxAutoReplies already belongs to the owner's actual group settings.
        // Rotate opportunity allocation so small configured budgets do not
        // permanently exclude the same member; this is NOT a speaker choice.
        val start = (deliveredMessageId.hashCode().toLong() and 0x7fffffff).rem(members.size).toInt()
        return (members.drop(start) + members.take(start))
            .take(group.maxAutoReplies.coerceIn(1, 8))
    }

    fun witnessed(characterId: String, evidenceId: String): Boolean {
        val suffix = ":group:$characterId"
        if (!evidenceId.endsWith(suffix)) return false
        val messageId = evidenceId.removeSuffix(suffix)
        val event = SharedExperienceTimeline.eventsByIds(characterId, listOf(evidenceId))
            .singleOrNull { it.revision == 1L && it.source == "message" &&
                it.evidenceKind == EventEvidenceKind.CharacterStatement } ?: return false
        val group = MigratedDomainStores.chat.conversations.value.firstOrNull {
            it.id == event.sessionId && it.groupChat?.members
                ?.any { member -> member.characterId == characterId } == true
        } ?: return false
        val delivered = MigratedDomainStores.chat.messages(group.id).value.firstOrNull {
            it.id == messageId && it.sender == LuluChatMessage.Sender.Character &&
                it.status == LuluChatMessage.Status.Sent && it.authorCharacterId != characterId &&
                !it.onBehalfByOwner
        } ?: return false
        return group.groupChat!!.members.any { it.characterId == delivered.authorCharacterId }
    }
}
