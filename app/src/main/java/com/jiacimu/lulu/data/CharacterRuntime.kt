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

    suspend fun memory(characterId: String, request: UnifiedMemoryRequest): UnifiedMemoryContext =
        UnifiedMemoryOrchestrator.assemble(characterId, request)

    fun personaConstraintSnapshot(characterId: String): String {
        val persona = MigratedDomainStores.characters.get(characterId).persona
        val profile = CharacterLifeStore.state(characterId).optJSONObject("profile") ?: return persona
        val constraints = profile.keys().asSequence().toList().sorted().mapNotNull { key ->
            profile.optString(key).trim().takeIf(String::isNotBlank)?.let { "$key=$it" }
        }.joinToString("\n")
        return if (constraints.isBlank()) persona else "$persona\n用户行为设定：\n$constraints"
    }

    fun developmentContext(characterId: String): String {
        val learned = CharacterDevelopmentStore.active(characterId)
        return buildString {
            appendLine(CompanionContactClock.context(characterId))
            appendLine(CharacterLifeStore.context(characterId, includeProfile = false))
            appendLine(CharacterInnerLifeStore.context(characterId))
            appendLine("基于真实事件逐渐形成的可变习惯与判断（不修改用户锁定的人设；新反馈优先，不代表意识已实现）：")
            learned.takeLast(16).forEach { r ->
                appendLine("- ${r.kind} ${r.slot} v${r.version}：${r.content}；可信度=${r.confidence}；依据=${r.evidence.keys.joinToString()}")
            }
        }.trim()
    }
}
