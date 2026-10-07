package com.jiacimu.lulu.data

/** Every Full-context gateway call uses this same assembly, including voice and meetings. */
object CharacterRuntime {
    suspend fun memory(characterId: String, request: UnifiedMemoryRequest): UnifiedMemoryContext =
        UnifiedMemoryOrchestrator.assemble(characterId, request)

    fun developmentContext(characterId: String): String {
        val learned = CharacterDevelopmentStore.active(characterId)
        if (learned.isEmpty()) return ""
        return buildString {
            appendLine("基于真实事件逐渐形成的可变习惯与判断（不修改用户锁定的人设；新反馈优先，不代表意识已实现）：")
            learned.takeLast(16).forEach { r ->
                appendLine("- ${r.kind} ${r.slot} v${r.version}：${r.content}；可信度=${r.confidence}；依据=${r.evidence.keys.joinToString()}")
            }
        }.trim()
    }
}
