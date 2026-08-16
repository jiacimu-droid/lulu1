package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import kotlinx.coroutines.flow.first

/**
 * Deletes one character's history without deleting the character profile or its settings.
 * Raw timeline deletion is the final source-of-truth cleanup so derived memory cannot survive it.
 */
object CharacterRecordReset {
    suspend fun clearAll(characterId: String) {
        val cleanId = characterId.trim()
        if (cleanId.isBlank()) return

        MigratedDomainStores.chat.conversations.value
            .filter { it.characterId == cleanId && it.groupChat == null }
            .forEach { conversation ->
                MigratedDomainStores.chat.clearConversationMessages(conversation.id)
            }

        MomentsStore.clearCharacterData(cleanId)
        DigitalWorldStore.clearCharacter(cleanId)

        LuluRepositories.lexicon.snapshot(cleanId)
            .map { it.id }
            .forEach { id -> LuluRepositories.lexicon.delete(id) }

        LuluRepositories.memory.observeMemories(cleanId)
            .first()
            .map { it.id }
            .forEach { id -> LuluRepositories.memory.delete(id) }

        CompanionPresenceStore.clearCharacter(cleanId)

        SharedExperienceTimeline.all(cleanId)
            .map { it.id }
            .distinct()
            .forEach(SharedExperienceTimeline::deleteEvent)
    }
}
