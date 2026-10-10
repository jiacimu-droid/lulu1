package com.jiacimu.lulu

import android.content.Context
import com.jiacimu.lulu.data.CompanionOnlineStore
import com.jiacimu.lulu.data.ProactivePerceptionScheduler
import com.jiacimu.lulu.data.UserProfileContext

import com.jiacimu.lulu.data.CompanionPresenceStore
import com.jiacimu.lulu.data.EventEvidenceKind
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.SharedExperienceTimeline
import java.time.Instant
import java.util.UUID

/** World-only observations outside a formal meeting. Nobody learns of a visit from another room. */
internal object DigitalWorldVisitorAwareness {
    fun observePhysicalInteraction(context: Context, location: String, characterId: String, action: String) {
        if (characterId.isBlank()) return
        val actor = MigratedDomainStores.characters.get(characterId).displayName
        val fact = when (action) {
            "HOLD_HANDS" -> "${UserProfileContext.displayLabel()}在${location}牵住了${actor}的手，双方开始牵手移动。"
            "RELEASE_HANDS" -> "${UserProfileContext.displayLabel()}在${location}松开了${actor}的手，接触结束。"
            "FOLLOW" -> "${UserProfileContext.displayLabel()}在${location}邀请${actor}同行，双方开始一起走动。"
            "STOP_FOLLOW" -> "${UserProfileContext.displayLabel()}在${location}结束了与${actor}的跟随。"
            else -> return
        }
        SharedExperienceTimeline.record(
            eventId = "world-touch-${UUID.randomUUID()}-$characterId",
            characterId = characterId,
            channel = "数字世界·$location",
            speaker = "场景事实", content = fact, source = "digital-world",
            evidenceKind = EventEvidenceKind.Observation,
            triggerExtraction = true,
        )
        queueReaction(context, characterId)
    }

    fun observe(context: Context, location: String, residentIds: List<String>, entering: Boolean) {
        val seenAt = Instant.now()
        residentIds.distinct().filter(String::isNotBlank).forEach { residentId ->
            val action = if (entering) "你与${UserProfileContext.displayLabel()}现在同在数字世界地点“$location”，你能实际看到对方。"
                else "${UserProfileContext.displayLabel()}从你所在的数字世界地点“$location”离开了，你能够注意到这次离场。"
            SharedExperienceTimeline.record(
                eventId = "world-visit-${UUID.randomUUID()}-$residentId",
                characterId = residentId,
                channel = "数字世界·$location",
                speaker = "场景事实",
                content = action,
                occurredAt = seenAt,
                source = "digital-world",
                evidenceKind = EventEvidenceKind.Observation,
                triggerExtraction = true,
            )
            queueReaction(context, residentId)
            CompanionPresenceStore.update(
                characterId = residentId,
                statusText = if (entering) "注意到${UserProfileContext.displayLabel()}来到·$location" else "注意到${UserProfileContext.displayLabel()}离开·$location",
                gesture = null,
                innerThought = null,
                mood = null,
                source = "数字世界·到访感知",
                now = seenAt,
            )
        }
    }

    private fun queueReaction(context: Context, characterId: String) {
        // A percept grants a choice, not a compulsory reply or a new online window.
        if (CompanionOnlineStore.isOnline(characterId)) {
            ProactivePerceptionScheduler.scheduleOnlineReflection(context, characterId,
                "实际数字世界到访或接触：理解新现场变化，自主决定回应、行动或安静感受", delayMillis = 3_000L)
        }
    }
}
