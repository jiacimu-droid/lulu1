package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import java.time.Instant

/**
 * Deletes one character's history without deleting the character profile or its settings.
 * Raw timeline deletion is the final source-of-truth cleanup so derived memory cannot survive it.
 */
object CharacterRecordReset {
    suspend fun clearAll(characterId: String, now: Instant = Instant.now()) {
        val cleanId = characterId.trim()
        if (cleanId.isBlank()) return

        MigratedDomainStores.chat.conversations.value
            .filter { it.characterId == cleanId && it.groupChat == null }
            .forEach { conversation ->
                MigratedDomainStores.chat.clearConversationMessages(conversation.id)
            }

        // Remove sources before derived stores, so a late extraction cannot revive old history.
        SharedExperienceTimeline.deleteCharacterEvents(cleanId)
        MemoryExtractionJobStore.clearCharacter(cleanId)
        CommitmentTaskStore.clearCharacter(cleanId)
        ProactiveIncomingCallStore.pending.value?.takeIf { it.characterId == cleanId }?.let {
            ProactiveIncomingCallStore.clear(it)
        }
        val meetingIds = DigitalWorldStore.state.value.meetings
            .filter { cleanId in it.participantIds }.map { it.id }.toSet()
        MeetingExperienceStore.clearCharacterHistory(cleanId, meetingIds)
        MomentsStore.clearCharacterData(cleanId)
        DigitalWorldStore.clearCharacter(cleanId)

        LuluRepositories.lexicon.snapshot(cleanId)
            .map { it.id }
            .forEach { id -> LuluRepositories.lexicon.delete(id) }

        LuluRepositories.memory.clearCharacterHistory(cleanId)
        com.jiacimu.lulu.study.ReadingReflectionStore.clearCharacter(cleanId)

        CharacterDevelopmentStore.clearCharacter(cleanId)
        CharacterLifeStore.clearHistory(cleanId)
        CharacterInnerLifeStore.clear(cleanId)
        CompanionPresenceStore.clearCharacter(cleanId)
        CompanionOnlineStore.resetCharacter(cleanId, now)

        DigitalLifeProfileStore.restartOrigin(cleanId, MigratedDomainStores.characters.get(cleanId).displayName, now)
    }
}
