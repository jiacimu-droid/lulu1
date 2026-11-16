package com.jiacimu.lulu

import com.jiacimu.lulu.data.MeetingParticipantSceneState
import com.jiacimu.lulu.data.MeetingSceneSnapshot
import java.time.Instant

/** Reduces observed body actions, not story prompts or inferred feelings. */
internal object MeetingPhysicalStateReducer {
    fun apply(scene: MeetingSceneSnapshot, characterId: String, action: String, at: Instant): MeetingSceneSnapshot =
        scene.copy(participants = scene.participants.map { participant ->
            if (participant.participantId != characterId) participant
            else when (action) {
                "HOLD_HANDS" -> participant.copy(
                    contact = (participant.contact - "handholding:user" + "handholding:user"),
                    explorationMode = "FOLLOW_USER",
                )
                "RELEASE_HANDS" -> participant.copy(contact = participant.contact - "handholding:user")
                "FOLLOW" -> participant.copy(explorationMode = "FOLLOW_USER")
                "STOP_FOLLOW" -> participant.copy(
                    contact = participant.contact - "handholding:user",
                    explorationMode = "STAY",
                )
                else -> participant
            }
        }, updatedAt = at)

    /** Completes only an actually observed movement goal; narration alone cannot clear it. */
    fun reachedItem(scene: MeetingSceneSnapshot, characterId: String, itemId: String, itemName: String,
                    at: Instant): MeetingSceneSnapshot =
        scene.copy(participants = scene.participants.map { participant ->
            if (participant.participantId == characterId && participant.destinationItemId == itemId)
                participant.copy(destinationItemId = "", destinationActivityId = "", position = "在${itemName}旁")
            else participant
        }, updatedAt = at)

    fun userDeparted(scene: MeetingSceneSnapshot, at: Instant): MeetingSceneSnapshot =
        scene.copy(participants = scene.participants.map {
            if (it.participantId == "user") it.copy(contact = emptyList(), posture = "已离开当前场景")
            else it.copy(
                contact = it.contact - "handholding:user",
                explorationMode = if (it.explorationMode == "FOLLOW_USER") "STAY" else it.explorationMode,
                destinationItemId = "",
                destinationActivityId = "",
            )
        }, updatedAt = at)
}
