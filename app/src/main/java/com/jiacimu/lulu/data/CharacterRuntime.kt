package com.jiacimu.lulu.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

data class CharacterDefinitionSnapshot(
    val displayName: String,
    val identity: String,
    val persona: String,
    val configurationKey: List<String>,
) {
    fun hasSameConfiguration(other: CharacterDefinitionSnapshot): Boolean = configurationKey == other.configurationKey

    fun promptSection(): String = listOf(
        "【当前角色设定｜以本次读取的用户设定为准，旧台词、记忆摘要与成长记录不得覆盖或补回旧设定】",
        "角色名称：$displayName",
        identity.takeIf(String::isNotBlank)?.let { "角色身份：\n$it" }.orEmpty(),
        persona.takeIf(String::isNotBlank)?.let { "角色设定：\n$it" }.orEmpty(),
    ).filter(String::isNotBlank).joinToString("\n\n")
}

/** Every Full-context gateway call uses this same assembly, including voice and meetings. */
object CharacterRuntime {
    /** Read live stores at prompt assembly; never use a conversation's cached role definition. */
    fun definition(characterId: String): CharacterDefinitionSnapshot {
        val character = MigratedDomainStores.characters.get(characterId)
        val persona = listOf(character.persona, CharacterLifeStore.profileContext(characterId))
            .filter(String::isNotBlank).joinToString("\n\n")
        val life = DigitalLifeProfileStore.get(characterId)
        return CharacterDefinitionSnapshot(character.displayName, CharacterIdentityStore.get(characterId), persona,
            listOf(character.displayName, CharacterIdentityStore.identities.value[characterId].orEmpty(), persona,
                life.lifeForm.name, life.bornAt?.toString().orEmpty()))
    }

    fun definitionChanges(characterId: String): Flow<CharacterDefinitionSnapshot> = combine(
        MigratedDomainStores.characters.settings, CharacterIdentityStore.identities, CharacterLifeStore.states,
        DigitalLifeProfileStore.profiles,
    ) { _, _, _, _ -> definition(characterId) }.distinctUntilChanged { old, new -> old.hasSameConfiguration(new) }

    suspend fun memory(
        characterId: String,
        request: UnifiedMemoryRequest,
        budget: PromptContextBudget? = null,
    ): UnifiedMemoryContext = if (budget == null) {
        UnifiedMemoryOrchestrator.assemble(characterId, request)
    } else {
        UnifiedMemoryOrchestrator.assemble(
            characterId, request,
            recallLimit = budget.recallLimit,
            evidenceLimit = if (budget.evidenceCharacters > 0) 22 else 8,
            evidenceCharacterBudget = budget.evidenceCharacters,
            recentCharacterBudget = budget.recentCharacters,
            memoryCharacterBudget = budget.memoryCharacters,
        )
    }

    fun personaConstraintSnapshot(characterId: String): String {
        val persona = MigratedDomainStores.characters.get(characterId).persona
        val profile = CharacterLifeStore.state(characterId).optJSONObject("profile") ?: return persona
        val constraints = profile.keys().asSequence().toList().sorted().mapNotNull { key ->
            profile.optString(key).trim().takeIf(String::isNotBlank)?.let { "$key=$it" }
        }.joinToString("\n")
        return if (constraints.isBlank()) persona else "$persona\n用户行为设定：\n$constraints"
    }

    fun developmentContext(characterId: String, compact: Boolean = false): String {
        val learned = CharacterDevelopmentStore.active(characterId)
        val narrative = learned.filter { it.kind == DevelopmentKind.NarrativeMeaning }
        val adaptive = learned.filterNot { it.kind == DevelopmentKind.NarrativeMeaning }
        return buildString {
            appendLine(CharacterPersonalityArchitecture.promptSection(compact))
            appendLine(CompanionContactClock.context(characterId))
            appendLine(if (compact) CharacterLifeStore.compactContext(characterId) else
                CharacterLifeStore.context(characterId, includeProfile = false))
            appendLine(if (compact) CharacterInnerLifeStore.compactContext(characterId) else
                CharacterInnerLifeStore.context(characterId))
            appendLine(CharacterAccountabilityContext.prompt(characterId))
            if (narrative.isNotEmpty()) {
                appendLine("【叙事身份｜多次真实经历形成的主观意义，不是新增事实】")
                narrative.takeLast(if (compact) 3 else 8).forEach { record ->
                    if (compact) appendLine("- ${record.slot}：${record.content.take(260)}")
                    else appendLine("- ${record.slot} v${record.version}：${record.content}；依据=${record.evidence.keys.joinToString()}")
                }
            }
            if (adaptive.isNotEmpty()) {
                appendLine("基于真实经历形成的可变适应（不覆盖稳定人设）：")
                adaptive.takeLast(if (compact) 5 else 16).forEach { record ->
                    if (compact) appendLine("- ${record.kind}/${record.slot}：${record.content.take(230)}")
                    else appendLine("- ${record.kind} ${record.slot} v${record.version}：${record.content}；可信度=${record.confidence}；依据=${record.evidence.keys.joinToString()}")
                }
            }
        }.trim()
    }
}
