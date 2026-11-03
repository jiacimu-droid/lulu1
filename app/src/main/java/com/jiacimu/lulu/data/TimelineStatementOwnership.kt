package com.jiacimu.lulu.data

/** The timeline owner is the witness, not necessarily the speaker.
 * Resolve group authorship from the existing canonical chat message/IDs;
 * names, nicknames and the words themselves never establish authorship. */
internal object TimelineStatementOwnership {
    private fun isGroup(event: SharedTimelineEvent): Boolean =
        event.channel.startsWith("群聊") || event.id.endsWith(":group:${event.characterId}")

    private fun original(event: SharedTimelineEvent): Pair<LuluConversation, LuluChatMessage>? {
        if (event.sessionId.isBlank()) return null
        val conversation = MigratedDomainStores.chat.conversations.value
            .firstOrNull { it.id == event.sessionId } ?: return null
        val id = event.id.removeSuffix(":group:${event.characterId}")
        val message = MigratedDomainStores.chat.recordedMessage(conversation.id, id)
            ?.takeIf { it.status == LuluChatMessage.Status.Sent } ?: return null
        return conversation to message
    }

    fun isOwnCharacterStatement(event: SharedTimelineEvent): Boolean {
        if (event.evidenceKind != EventEvidenceKind.CharacterStatement ||
            event.source == "owner-on-behalf") return false
        // Existing direct/self records retain their established meaning.
        // A group witness requires positive source attribution, even for old
        // records whose original conversation is no longer available.
        if (!isGroup(event)) return true
        if (event.source != "message") return false
        val (conversation, message) = original(event) ?: return false
        return message.sender == LuluChatMessage.Sender.Character && !message.onBehalfByOwner &&
            (message.authorCharacterId ?: conversation.characterId) == event.characterId
    }

    /** A group statement alone does not establish contact with the user.
     * Current group membership is mutable and cannot prove historical audience.
     * An actual user statement can anchor that contact without guessing who read a reply. */
    fun involvesUser(event: SharedTimelineEvent): Boolean {
        if (!isGroup(event)) return true
        return event.evidenceKind == EventEvidenceKind.UserStatement && event.source == "message"
    }
}
