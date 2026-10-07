package com.jiacimu.lulu.data

/** Every Full-context gateway call uses this same assembly, including voice and meetings. */
object CharacterRuntime {
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
            appendLine(CharacterLifeStore.context(characterId))
            appendLine("基于真实事件逐渐形成的可变习惯与判断（不修改用户锁定的人设；新反馈优先，不代表意识已实现）：")
            learned.takeLast(16).forEach { r ->
                appendLine("- ${r.kind} ${r.slot} v${r.version}：${r.content}；可信度=${r.confidence}；依据=${r.evidence.keys.joinToString()}")
            }
        }.trim()
    }
}
