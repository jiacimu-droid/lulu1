package com.jiacimu.lulu

import com.jiacimu.lulu.data.CompanionPresenceStore
import com.jiacimu.lulu.data.EventEvidenceKind
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.SharedExperienceTimeline
import java.time.Instant
import java.util.UUID

/** World-only observations outside a formal meeting. Nobody learns of a visit from another room. */
internal object DigitalWorldVisitorAwareness {
    fun observePhysicalInteraction(location: String, characterId: String, action: String) {
        if (characterId.isBlank()) return
        val actor = MigratedDomainStores.characters.get(characterId).displayName
        val fact = when (action) {
            "HOLD_HANDS" -> "主人在$location牵住了${actor}的手，双方开始牵手移动。"
            "RELEASE_HANDS" -> "主人在$location松开了${actor}的手，接触结束。"
            "FOLLOW" -> "主人在$location邀请${actor}同行，双方开始一起走动。"
            "STOP_FOLLOW" -> "主人在$location结束了与${actor}的跟随。"
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
    }

    fun observe(location: String, residentIds: List<String>, entering: Boolean) {
        val seenAt = Instant.now()
        residentIds.distinct().filter(String::isNotBlank).forEach { residentId ->
            val action = if (entering) "主人来到你当前所在的数字世界地点“$location”，你可以看到主人到场。"
                else "主人从你所在的数字世界地点“$location”离开了，你能够注意到这次离场。"
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
            CompanionPresenceStore.update(
                characterId = residentId,
                statusText = if (entering) "注意到主人来到·$location" else "注意到主人离开·$location",
                gesture = null,
                innerThought = null,
                mood = null,
                source = "数字世界·到访感知",
                now = seenAt,
            )
        }
    }
}
