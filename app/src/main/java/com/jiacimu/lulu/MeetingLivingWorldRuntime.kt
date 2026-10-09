package com.jiacimu.lulu

import com.jiacimu.lulu.data.*
import java.time.Instant
import java.util.UUID

/**
 * Observations made by the actual digital-world UI. No language model may
 * invent an entrance, a departure, a touch or an answer from the user.
 *
 * All events use the same meeting session and shared character timeline as
 * existing private/group chat. Deleting a meeting deletes the "meeting-"
 * provenance prefix too.
 */
internal object MeetingLivingWorldRuntime {
    private val visibleSessions = mutableSetOf<String>()
    data class PendingTouch(val id: String, val characterId: String, val fact: String)
    private val pendingTouches = mutableMapOf<String, List<PendingTouch>>()

    @Synchronized
    fun nextTouch(sessionId: String): PendingTouch? = pendingTouches[sessionId]?.firstOrNull()

    @Synchronized
    fun acknowledgeTouch(sessionId: String, eventId: String) {
        val remaining = pendingTouches[sessionId].orEmpty().filterNot { it.id == eventId }
        if (remaining.isEmpty()) pendingTouches.remove(sessionId)
        else pendingTouches[sessionId] = remaining
    }

    @Synchronized
    fun entered(session: MeetingSession): Boolean {
        if (session.endedAt != null || !visibleSessions.add(session.id)) return false
        recordObservation(session, "主人进入了见面场景“${session.location}”，现在实际在场。", "arrival")
        session.participantIds.forEach { characterId ->
            CompanionPresenceStore.update(characterId,
                statusText = "主人已来到见面场景·${session.location}",
                gesture = null, innerThought = null, mood = null,
                source = "见面·实际到场")
        }
        return true
    }

    @Synchronized
    fun departed(session: MeetingSession): Boolean {
        if (!visibleSessions.remove(session.id)) return false
        val current = DigitalWorldStore.state.value.meetings.firstOrNull { it.id == session.id }
        if (current?.endedAt == null) {
            recordObservation(session, "主人离开了见面场景“${session.location}”，目前不在场。离开不等于主动结束你们的关系或忘记见面。", "departure")
            session.participantIds.forEach { characterId ->
                CompanionPresenceStore.update(characterId,
                    statusText = "主人刚离开见面场景·${session.location}",
                    gesture = null, innerThought = null, mood = null,
                    source = "见面·实际离场")
            }
        }
        pendingTouches.remove(session.id)
        return true
    }

    @Synchronized
    fun isPresent(sessionId: String): Boolean = sessionId in visibleSessions

    /** Returns a witnessed fact for the existing character-response model. */
    fun physicalAction(session: MeetingSession, characterId: String, action: String): String? {
        if (session.endedAt != null || characterId !in session.participantIds || !isPresent(session.id)) return null
        val name = MigratedDomainStores.characters.get(characterId).displayName
        val fact = when (action) {
            "HOLD_HANDS" -> "主人在“${session.location}”主动牵住了${name}的手，双方现在以真实数字身体保持牵手，并可以继续一起移动。"
            "RELEASE_HANDS" -> "主人在“${session.location}”松开了${name}的手，牵手已实际结束。"
            "FOLLOW" -> "主人邀请${name}在“${session.location}”同行，场景已进入跟随移动状态。"
            "STOP_FOLLOW" -> "主人在“${session.location}”结束了与${name}的同行跟随，双方恢复自由移动。"
            else -> return null
        }
        val old = MeetingExperienceStore.sceneFor(session)
        val updated = old.copy(participants = old.participants.map { participant ->
            if (participant.participantId != characterId) participant
            else when (action) {
                "HOLD_HANDS" -> participant.copy(
                    contact = (participant.contact.filterNot { it == "handholding:user" } + "handholding:user"),
                    explorationMode = "FOLLOW_USER",
                )
                "RELEASE_HANDS" -> participant.copy(
                    contact = participant.contact.filterNot { it == "handholding:user" },
                )
                "FOLLOW" -> participant.copy(explorationMode = "FOLLOW_USER")
                "STOP_FOLLOW" -> participant.copy(
                    contact = participant.contact.filterNot { it == "handholding:user" },
                    explorationMode = "STAY",
                )
                else -> participant
            }
        }, updatedAt = Instant.now())
        MeetingExperienceStore.updateScene(session.id, updated)
        // A witnessed action is persisted immediately, even if model generation
        // fails. It is not a synthetic dialogue turn written on behalf of user.
        val now = Instant.now()
        val turn = MeetingTurn(
            id = UUID.randomUUID().toString(),
            speakerId = null,
            speakerName = UserProfileContext.displayLabel(),
            sceneText = fact,
            dialogue = "",
            occurredAt = now,
            segments = listOf(MeetingSegment(MeetingSegmentType.ACTION, fact)),
        )
        DigitalWorldStore.appendMeetingTurn(session.id, turn)
        recordObservation(session, fact, "physical-${turn.id}")
        synchronized(this) {
            pendingTouches[session.id] = (pendingTouches[session.id].orEmpty() +
                PendingTouch(turn.id, characterId, fact)).takeLast(12)
        }
        return fact
    }

    private fun recordObservation(session: MeetingSession, fact: String, suffix: String) {
        val now = Instant.now()
        session.participantIds.forEach { id ->
            SharedExperienceTimeline.record(
                eventId = "meeting-${session.id}-living-${suffix}-${UUID.randomUUID()}-viewer-$id",
                characterId = id,
                channel = "数字世界见面·${session.location}",
                speaker = "场景事实",
                content = fact,
                occurredAt = now,
                sessionId = session.id,
                source = "meeting",
                evidenceKind = EventEvidenceKind.Observation,
                triggerExtraction = true,
            )
        }
    }
}
