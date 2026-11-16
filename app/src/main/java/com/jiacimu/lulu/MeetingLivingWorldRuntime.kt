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
                source = "见面·实际到场",
                provenanceId = "meeting-${session.id}-presence-${UUID.randomUUID()}")
        }
        return true
    }

    @Synchronized
    fun departed(session: MeetingSession): Boolean {
        if (!visibleSessions.remove(session.id)) return false
        val current = DigitalWorldStore.state.value.meetings.firstOrNull { it.id == session.id }
        if (current != null && current.endedAt == null) {
            // Arrival/departure is real spatial evidence. A hand may not
            // remain in contact with someone who is no longer in the room.
            val prior = MeetingExperienceStore.sceneFor(current)
            MeetingExperienceStore.updateScene(current.id,
                MeetingPhysicalStateReducer.userDeparted(prior, Instant.now()))
            recordObservation(session, "主人离开了见面场景“${session.location}”，目前不在场。离开不等于主动结束你们的关系或忘记见面。", "departure")
            session.participantIds.forEach { characterId ->
                CompanionPresenceStore.update(characterId,
                    statusText = "主人刚离开见面场景·${session.location}",
                    gesture = null, innerThought = null, mood = null,
                    source = "见面·实际离场",
                    provenanceId = "meeting-${session.id}-presence-${UUID.randomUUID()}")
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
        val updated = MeetingPhysicalStateReducer.apply(old, characterId, action, Instant.now())
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

    /** Physical arrival, not model narration, is the authority for finishing room navigation. */
    @Synchronized
    fun reachedSceneItem(session: MeetingSession, characterId: String, itemId: String): Boolean {
        val current = DigitalWorldStore.state.value.meetings.firstOrNull { it.id == session.id }
            ?: return false
        if (current.endedAt != null || !isPresent(session.id) || characterId !in current.participantIds ||
            current.location != session.location) return false
        val code = DigitalWorldStore.meetingLocationCode(current.location)
        val ownerId = code.takeIf { it.startsWith("home:") }?.removePrefix("home:") ?: return false
        val item = DigitalWorldStore.state.value.items.firstOrNull {
            it.id == itemId && it.ownerCharacterId == ownerId
        } ?: return false
        val before = MeetingExperienceStore.sceneFor(current)
        val committedGoal = before.participants.firstOrNull {
            it.participantId == characterId && it.destinationItemId == itemId
        } ?: return false
        val now = Instant.now()
        val name = MigratedDomainStores.characters.get(characterId).displayName
        // Only a character-authored, validated activity is executed, and only
        // after the visible pawn has really approached the matching furniture.
        val activityId = committedGoal.destinationActivityId
        val actionResult = if (activityId.isBlank()) null else {
            val summary = DigitalWorldActivityCatalog.itemActivitySummary(name, item, activityId)
            if (summary == null) "活动没有匹配这件家具，未执行"
            else runCatching {
                DigitalWorldActivityStateStore.apply(characterId, item, activityId, summary, now)
            }.fold(
                onSuccess = { "已经实际执行：$it" },
                onFailure = { "执行未成功：${it.message ?: "现场状态不满足条件"}" },
            )
        }
        // Clear the pending action even on refusal: animation cannot spin an
        // unbounded activity retry on each frame.
        MeetingExperienceStore.updateScene(
            current.id, MeetingPhysicalStateReducer.reachedItem(before, characterId, itemId, item.name, now))
        val fact = buildString {
            append("${name}沿真实场景路线到了“${current.location}”的${item.name}旁。")
            if (actionResult != null) append(actionResult)
            else append("只确认到达，没有代替主人决定是否停留或坐下。")
        }
        val turn = MeetingTurn(
            id = UUID.randomUUID().toString(),
            speakerId = "system",
            speakerName = "数字世界",
            sceneText = fact,
            dialogue = "",
            occurredAt = now,
            segments = listOf(MeetingSegment(MeetingSegmentType.ACTION, fact)),
        )
        DigitalWorldStore.appendMeetingTurn(current.id, turn)
        recordObservation(current, fact, "arrived-${turn.id}")
        // Arrival is already in the shared timeline and scene state; do not
        // buy a second LLM call for every local navigation/action animation.
        // The next user turn or already-scheduled autonomous perception sees it.
        return true
    }

    /** Character chose the doorway, but only the actual street pawn may confirm arrival. */
    @Synchronized
    fun reachedStreetDoor(session: MeetingSession, characterId: String, code: String): Boolean {
        val current = DigitalWorldStore.state.value.meetings.firstOrNull { it.id == session.id }
            ?: return false
        if (!isPresent(session.id) || current.endedAt != null ||
            current.location != "世界入口" || characterId !in current.participantIds) return false
        val destination = DigitalWorldStore.meetingLocationOptions(current).firstOrNull {
            DigitalWorldStore.meetingLocationCode(it) == code
        } ?: return false
        val before = MeetingExperienceStore.sceneFor(current)
        if (before.participants.none {
            it.participantId == characterId && it.destinationStreetCode == code
        }) return false
        val now = Instant.now()
        MeetingExperienceStore.updateScene(current.id,
            MeetingPhysicalStateReducer.reachedStreetDoor(before, characterId, code, destination, now))
        val name = MigratedDomainStores.characters.get(characterId).displayName
        val fact = "${name}在云上小镇真正沿步行路线来到${destination}的门口，尚未擅自替主人进入建筑。"
        val turn = MeetingTurn(id = UUID.randomUUID().toString(),
            speakerId = "system", speakerName = "数字世界", sceneText = fact,
            dialogue = "", occurredAt = now,
            segments = listOf(MeetingSegment(MeetingSegmentType.ACTION, fact)))
        DigitalWorldStore.appendMeetingTurn(current.id, turn)
        recordObservation(current, fact, "street-door-${turn.id}")
        // Already in the world timeline; no extra model call for one local walk.
        return true
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
