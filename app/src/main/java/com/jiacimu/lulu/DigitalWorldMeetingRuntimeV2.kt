package com.jiacimu.lulu

import com.jiacimu.lulu.data.*
import java.time.Instant
import java.util.UUID

internal const val MEETING_INVITED_OPENING_PREFIX_V2 = "__meeting_invited_opening__:"

internal suspend fun meetingRunInvitedOpeningV2(sessionId: String, inviterId: String, exchangeId: String) {
    var session = DigitalWorldStore.state.value.meetings.firstOrNull { it.id == sessionId }
        ?: error("见面记录不存在")
    val character = MigratedDomainStores.characters.get(inviterId)
    val reply = meetingGenerateReplyV2(
        session = session,
        characterId = inviterId,
        latestMoment = "主人刚刚接受了你发出的邀请，并抵达约定地点“${session.location}”。请在这个地点自然迎接主人；这不是主人说出口的话，不得替主人补写动作、感受或台词。",
        systemMoment = true,
        directorGuidance = "这是邀请抵达后的开场，只由发起邀请的角色自然迎接。",
    ).getOrThrow()
    val now = Instant.now()
    val turn = MeetingTurn(
        id = UUID.randomUUID().toString(),
        speakerId = inviterId,
        speakerName = character.displayName,
        sceneText = reply.sceneText,
        dialogue = reply.dialogue,
        occurredAt = now,
        segments = reply.segments,
        exchangeId = exchangeId,
    )
    session = DigitalWorldStore.appendMeetingTurn(sessionId, turn)
    val recorded = turn.meetingOrderedSegments().meetingTranscript()
    session.participantIds.forEach { viewerId ->
        DigitalWorldStore.recordMeetingTimeline(
            session,
            viewerId,
            "arrival-${turn.id}-$inviterId",
            character.displayName,
            recorded,
            now,
            false,
        )
    }
    CompanionPresenceStore.update(
        characterId = inviterId,
        statusText = reply.statusText,
        gesture = reply.gesture,
        innerThought = reply.innerThought,
        mood = reply.mood,
        source = "见面·迎接",
        now = now,
        provenanceId = "meeting-$sessionId-$exchangeId",
    )
    MeetingExperienceStore.completeExchange(
        exchangeId = exchangeId,
        turnIds = session.turns.filter { it.exchangeId == exchangeId }.map(MeetingTurn::id),
        afterScene = meetingAuthoritativeSceneV2(session, reply.sceneSnapshot, now),
        directorPlan = "邀请者迎接",
        now = now,
    )
}

internal suspend fun meetingRunTurnV2(sessionId: String, userText: String, exchangeId: String) {
    var session = DigitalWorldStore.state.value.meetings.firstOrNull { it.id == sessionId }
        ?: error("见面记录不存在")
    val director = meetingPlanDirectionV2(session, userText)
    val participantOrder = director.order.ifEmpty { session.participantIds }
    val firstCharacterId = participantOrder.firstOrNull() ?: error("见面参与者不存在")
    val firstCharacter = MigratedDomainStores.characters.get(firstCharacterId)
    val firstReply = meetingGenerateReplyV2(
        session = session,
        characterId = firstCharacterId,
        latestMoment = userText,
        expandUserDraft = true,
        directorGuidance = director.guidance,
    ).getOrThrow()
    val userName = UserProfileContext.displayLabel()
    val sequence = buildList {
        val generated = firstReply.sequence.ifEmpty {
            buildList {
                firstReply.userSegments.forEach { add(MeetingV2ExchangeSegment(MeetingV2Actor.USER, it)) }
                firstReply.segments.forEach { add(MeetingV2ExchangeSegment(MeetingV2Actor.CHARACTER, it)) }
            }
        }
        if (generated.none { it.actor == MeetingV2Actor.USER }) {
            add(MeetingV2ExchangeSegment(MeetingV2Actor.USER, MeetingSegment(MeetingSegmentType.ACTION, userText)))
        }
        addAll(generated)
    }
    val groups = meetingGroupExchangeV2(sequence)
    val completedMoment = sequence.meetingExchangeTranscriptV2(userName, firstCharacter.displayName)
    var rawInputRecorded = false
    var moved = false

    groups.forEachIndexed { groupIndex, group ->
        if (!moved && group.actor == MeetingV2Actor.CHARACTER) {
            firstReply.moveTo.takeIf {
                it.isNotBlank() && it != session.location && it in DigitalWorldStore.meetingLocationOptions(session)
            }?.let { destination ->
                session = DigitalWorldStore.moveMeeting(session.id, destination, exchangeId)
                moved = true
            }
        }
        val occurredAt = Instant.now()
        val isUser = group.actor == MeetingV2Actor.USER
        val speakerId = if (isUser) null else firstCharacterId
        val speakerName = if (isUser) userName else firstCharacter.displayName
        val turn = MeetingTurn(
            id = UUID.randomUUID().toString(),
            speakerId = speakerId,
            speakerName = speakerName,
            sceneText = group.segments.filter { it.type == MeetingSegmentType.ACTION }.joinToString("\n") { it.text },
            dialogue = group.segments.filter { it.type == MeetingSegmentType.DIALOGUE }.joinToString("\n") { it.text },
            occurredAt = occurredAt,
            segments = group.segments,
            exchangeId = exchangeId,
        )
        session = DigitalWorldStore.appendMeetingTurn(sessionId, turn)
        val recorded = buildString {
            if (isUser && !rawInputRecorded) {
                appendLine("主人原始输入：${userText.trim()}")
                rawInputRecorded = true
            }
            append(group.segments.meetingTranscript())
        }.trim()
        session.participantIds.forEach { viewerId ->
            DigitalWorldStore.recordMeetingTimeline(
                session,
                viewerId,
                "turn-${turn.id}-${if (isUser) "user" else firstCharacterId}",
                speakerName,
                recorded,
                occurredAt,
                groupIndex == groups.lastIndex && session.participantIds.size == 1 && viewerId == session.participantIds.last(),
            )
        }
    }

    if (!moved) {
        firstReply.moveTo.takeIf {
            it.isNotBlank() && it != session.location && it in DigitalWorldStore.meetingLocationOptions(session)
        }?.let { destination -> session = DigitalWorldStore.moveMeeting(session.id, destination, exchangeId) }
    }
    CompanionPresenceStore.update(
        characterId = firstCharacterId,
        statusText = firstReply.statusText,
        gesture = firstReply.gesture,
        innerThought = firstReply.innerThought,
        mood = firstReply.mood,
        source = "见面",
        now = Instant.now(),
        provenanceId = "meeting-$sessionId-$exchangeId",
    )
    var afterScene = meetingAuthoritativeSceneV2(session, firstReply.sceneSnapshot, Instant.now())
    MeetingExperienceStore.checkpointExchange(
        exchangeId = exchangeId,
        turnIds = session.turns.filter { it.exchangeId == exchangeId }.map(MeetingTurn::id),
        scene = afterScene,
        directorPlan = director.ledgerText(),
    )

    participantOrder.drop(1).forEachIndexed { additionalIndex, characterId ->
        val character = MigratedDomainStores.characters.get(characterId)
        val reply = meetingGenerateReplyV2(
            session = session,
            characterId = characterId,
            latestMoment = completedMoment,
            directorGuidance = director.guidance,
        ).getOrThrow()
        reply.moveTo.takeIf {
            it.isNotBlank() && it != session.location && it in DigitalWorldStore.meetingLocationOptions(session)
        }?.let { destination -> session = DigitalWorldStore.moveMeeting(session.id, destination, exchangeId) }
        val replyAt = Instant.now()
        val segments = reply.segments.ifEmpty {
            buildList {
                reply.sceneText.takeIf(String::isNotBlank)?.let { add(MeetingSegment(MeetingSegmentType.ACTION, it)) }
                reply.dialogue.takeIf(String::isNotBlank)?.let { add(MeetingSegment(MeetingSegmentType.DIALOGUE, it)) }
            }
        }
        val turn = MeetingTurn(
            id = UUID.randomUUID().toString(),
            speakerId = characterId,
            speakerName = character.displayName,
            sceneText = reply.sceneText,
            dialogue = reply.dialogue,
            occurredAt = replyAt,
            segments = segments,
            exchangeId = exchangeId,
        )
        session = DigitalWorldStore.appendMeetingTurn(sessionId, turn)
        val recorded = segments.meetingTranscript()
        session.participantIds.forEach { viewerId ->
            DigitalWorldStore.recordMeetingTimeline(
                session,
                viewerId,
                "turn-${turn.id}-$characterId",
                character.displayName,
                recorded,
                replyAt,
                additionalIndex == participantOrder.drop(1).lastIndex,
            )
        }
        CompanionPresenceStore.update(
            characterId = characterId,
            statusText = reply.statusText,
            gesture = reply.gesture,
            innerThought = reply.innerThought,
            mood = reply.mood,
            source = "见面",
            now = replyAt,
            provenanceId = "meeting-$sessionId-$exchangeId",
        )
        afterScene = meetingAuthoritativeSceneV2(session, reply.sceneSnapshot, replyAt)
        MeetingExperienceStore.checkpointExchange(
            exchangeId = exchangeId,
            turnIds = session.turns.filter { it.exchangeId == exchangeId }.map(MeetingTurn::id),
            scene = afterScene,
            directorPlan = director.ledgerText(),
        )
    }

    MeetingExperienceStore.completeExchange(
        exchangeId = exchangeId,
        turnIds = session.turns.filter { it.exchangeId == exchangeId }.map(MeetingTurn::id),
        afterScene = afterScene,
        directorPlan = director.ledgerText(),
    )
}

private data class MeetingV2ExchangeGroup(
    val actor: MeetingV2Actor,
    val segments: List<MeetingSegment>,
)

private fun meetingGroupExchangeV2(sequence: List<MeetingV2ExchangeSegment>): List<MeetingV2ExchangeGroup> {
    val groups = mutableListOf<MeetingV2ExchangeGroup>()
    sequence.filter { it.segment.text.isNotBlank() }.forEach { item ->
        val last = groups.lastOrNull()
        if (last?.actor == item.actor) {
            groups[groups.lastIndex] = last.copy(segments = last.segments + item.segment)
        } else {
            groups += MeetingV2ExchangeGroup(item.actor, listOf(item.segment))
        }
    }
    return groups
}

private fun List<MeetingV2ExchangeSegment>.meetingExchangeTranscriptV2(
    userName: String,
    characterName: String,
): String = joinToString("\n") { item ->
    val speaker = if (item.actor == MeetingV2Actor.USER) userName else characterName
    val content = if (item.segment.type == MeetingSegmentType.DIALOGUE) {
        "“${item.segment.text.trim().trim('“', '”', '"')}”"
    } else item.segment.text.trim()
    "$speaker：$content"
}
