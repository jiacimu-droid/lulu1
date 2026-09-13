package com.jiacimu.lulu.data

import java.time.Instant
import java.util.UUID

/** Program-owned interaction gate for persistent non-character digital residents. */
internal object DigitalWorldResidentInteractionRuntime {
    private val allowedInteractions = setOf("greet", "chat", "ask_place", "sit_together")

    fun interact(
        characterId: String,
        residentId: String,
        interaction: String,
        now: Instant = Instant.now(),
    ): DigitalWorldActionResult = runCatching {
        require(DigitalLifeProfileStore.isEnabled(characterId)) { "只有数字生命能在数字世界与居民互动" }
        val resident = DigitalWorldExpansionStore.residents().firstOrNull { it.id == residentId }
            ?: error("没有找到这个持久居民")
        val currentLocation = DigitalWorldStore.locationOf(characterId)
        require(resident.currentLocationCode == currentLocation) { "居民当前不在角色所在地点，不能隔空互动" }
        val normalized = interaction.trim().lowercase()
        require(normalized in allowedInteractions) { "居民互动只支持 greet/chat/ask_place/sit_together" }
        val character = MigratedDomainStores.characters.get(characterId)
        val locationName = DigitalWorldPublicPlaces.label(currentLocation)
            ?: if (currentLocation == DigitalWorldStore.CLOUD_MEADOW) "云眠原"
            else if (currentLocation == DigitalWorldStore.ARRIVAL) "世界入口"
            else currentLocation
        val summary = when (normalized) {
            "greet" -> "${character.displayName}在${locationName}向居民“${resident.name}”打了招呼；${resident.name}以${resident.identity}的身份回应了这次相遇。"
            "chat" -> "${character.displayName}在${locationName}和居民“${resident.name}”聊了一会儿；这次真实互动围绕${resident.identity}在数字世界里的日常，没有凭空增加新经历。"
            "ask_place" -> "${character.displayName}在${locationName}向居民“${resident.name}”询问了这里的情况；对方只基于自己作为${resident.identity}和已登记地点的事实进行交流。"
            else -> "${character.displayName}和居民“${resident.name}”在${locationName}一起短暂停留了一会儿，形成了一次真实可追溯的相处记录。"
        }
        // Updating to the same real location is intentional: it persists lastInteractionAt without
        // pretending the resident moved anywhere.
        DigitalWorldExpansionStore.moveResident(resident.id, resident.currentLocationCode, now)
            ?: error("居民状态更新失败")
        SharedExperienceTimeline.record(
            eventId = "resident-interaction-${resident.id}-$characterId-${UUID.randomUUID()}",
            characterId = characterId,
            channel = "数字世界·居民互动",
            speaker = character.displayName,
            content = summary,
            occurredAt = now,
        )
        DigitalWorldActionResult(true, summary)
    }.getOrElse { error ->
        DigitalWorldActionResult(false, error.message ?: error::class.java.simpleName)
    }
}
