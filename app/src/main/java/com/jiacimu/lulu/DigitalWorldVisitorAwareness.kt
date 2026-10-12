package com.jiacimu.lulu

import android.content.Context
import com.jiacimu.lulu.data.CompanionOnlineStore
import com.jiacimu.lulu.data.ProactivePerceptionScheduler
import com.jiacimu.lulu.data.UserProfileContext

import com.jiacimu.lulu.data.CompanionPresenceStore
import com.jiacimu.lulu.data.EventEvidenceKind
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.DigitalWorldArrivalAudience
import com.jiacimu.lulu.data.DigitalLifeProfileStore
import com.jiacimu.lulu.data.ProactivePerceptionPolicyStore
import com.jiacimu.lulu.data.LuluConversation
import java.time.Instant
import java.util.UUID

/** World-only observations outside a formal meeting. Nobody learns of a visit from another room. */
internal object DigitalWorldVisitorAwareness {
    fun observeAction(context: Context, location: String, residentIds: List<String>, action: String) {
        if (action.isBlank()) return
        residentIds.distinct().filter(String::isNotBlank).forEach { id ->
            SharedExperienceTimeline.record(
                eventId = "world-behavior-${UUID.randomUUID()}-$id", characterId = id,
                channel = "数字世界·$location", speaker = "场景事实",
                content = "${UserProfileContext.displayLabel()}在${location}：${action.take(300)}。这是可见行为，不代表已知她的想法。",
                source = "digital-world", evidenceKind = EventEvidenceKind.Observation,
                triggerExtraction = true,
            )
            queueReaction(context, id)
        }
    }

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


    /**
     * Arrival notifications are delivered to established contacts in public spaces.
     * Private homes deliver only to the home's owner, even when the owner is away.
     * Remote contacts learn about the arrival as a notification, NOT as an eyewitness.
     */
    fun locationArrival(context: Context, sceneCode: String, sceneLabel: String,
        localResidents: List<String>, entering: Boolean, includeLocals: Boolean = true) {
        if (sceneCode.startsWith("preview:") || sceneCode.startsWith("resident-home:")) return
        // A native resident's house is private. Do not broadcast entry to unrelated contacts.
        val localIds = localResidents.filter(String::isNotBlank).toSet()
        val linkedIds = MigratedDomainStores.chat.conversations.value.asSequence()
            .filter { it.groupChat == null && it.characterId.isNotBlank() }
            .map(LuluConversation::characterId)
            .filter { DigitalLifeProfileStore.isEnabled(it) }
            .toSet()
        val owner = sceneCode.takeIf { it.startsWith("home:") }?.removePrefix("home:")
        val recipients = DigitalWorldArrivalAudience.recipients(sceneCode, linkedIds,
            owner, localIds)
        val now = Instant.now()
        recipients.filter { includeLocals || it !in localIds }.forEach { id ->
            val isLocal = id in localIds
            val text = when {
                !entering && isLocal -> "${UserProfileContext.displayLabel()}刚离开了你所在的${sceneLabel}；你能注意到离场。"
                !entering -> "地点到访通知：${UserProfileContext.displayLabel()}离开了${sceneLabel}；这不表示你当时在场或亲眼看见。"
                isLocal -> "${UserProfileContext.displayLabel()}来到${sceneLabel}，你们现在同在现场，可以实际看见彼此。"
                owner != null -> "家园到访通知：${UserProfileContext.displayLabel()}来到你自己的家“${sceneLabel}”；只通知房主，不能据此假定你已在家里看见对方。"
                else -> "公共地点到访通知：与你建立过联系的${UserProfileContext.displayLabel()}来到${sceneLabel}；这是通知，不代表你在场或已看见。"
            }
            val evidenceId = "world-arrival-${UUID.randomUUID()}-$id"
            SharedExperienceTimeline.record(eventId = evidenceId, characterId = id,
                channel = "数字世界·到访通知", speaker = "地图事件",
                content = text, occurredAt = now, source = "digital-world-arrival",
                evidenceKind = EventEvidenceKind.Observation, triggerExtraction = true)
            CompanionPresenceStore.recordPerceptionAttempt(id, "地图通知 · ${if (entering) "到访" else "离场"} · $sceneLabel", now)
            if (CompanionOnlineStore.isOnline(id)) {
                queueReaction(context, id)
            } else if (entering && ProactivePerceptionPolicyStore.get(id).enabled) {
                ProactivePerceptionScheduler.scheduleWorldArrival(context, id, evidenceId)
            }
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
