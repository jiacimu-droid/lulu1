package com.jiacimu.lulu.data

/** The timeline owner is the witness, not necessarily the speaker.
 * Resolve group authorship from the existing canonical chat message/IDs;
 * names, nicknames and the words themselves never establish authorship. */
internal object TimelineStatementOwnership {
    private fun isGroup(event: SharedTimelineEvent): Boolean =
        event.channel.contains("群聊") || event.id.endsWith(":group:${event.characterId}")

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

    /** A spoken line in shared history is not necessarily a conversation with
     * the user: moments, journals, solo learning and resident encounters are
     * firsthand character statements with different audiences. */
    private fun isDirectUserExchange(event: SharedTimelineEvent): Boolean = when (event.source) {
        "message", "watch-together" -> true
        "meeting" -> event.sessionId.isNotBlank() &&
            DigitalWorldStore.state.value.meetings.any { session ->
                session.id == event.sessionId &&
                    session.participantIds.none { it.startsWith("resident:") }
            }
        // Older direct chat rows may lack a source but retain their channel.
        "legacy" -> event.channel == "私聊"
        else -> false
    }

    fun isOwnDialogueStatement(event: SharedTimelineEvent): Boolean =
        isOwnCharacterStatement(event) && (isGroup(event) || isDirectUserExchange(event))

    /** A group statement alone does not establish contact with the user.
     * Current group membership is mutable and cannot prove historical audience.
     * An actual user statement can anchor that contact without guessing who read a reply. */
    fun involvesUser(event: SharedTimelineEvent): Boolean {
        if (!isGroup(event)) return isDirectUserExchange(event)
        if (event.evidenceKind != EventEvidenceKind.UserStatement ||
            event.source != "message") return false
        // A witnessed group row is not proof of a user message on its own.
        // Resolve the original sent message, just as we do for character authorship.
        // This also rejects deleted/failed/rewritten or orphaned witness records.
        val (_, message) = original(event) ?: return false
        return message.sender == LuluChatMessage.Sender.User
    }
}
