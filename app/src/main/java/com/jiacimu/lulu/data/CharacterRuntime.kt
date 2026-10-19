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
        identity.takeIf(String::isNotBlank)?.let { "身份与背景事实：\n$it" }.orEmpty(),
        persona.takeIf(String::isNotBlank)?.let { "人物核心简介与稳定人格：\n$it" }.orEmpty(),
    ).filter(String::isNotBlank).joinToString("\n\n")
}

/** Every Full-context gateway call uses this same assembly, including voice and meetings. */
object CharacterRuntime {
    /** Read live stores at prompt assembly; never use a conversation's cached role definition. */
    fun definition(characterId: String): CharacterDefinitionSnapshot {
        val character = MigratedDomainStores.characters.get(characterId)
        val isJiangDu = CharacterLifeStore.state(characterId)
            .optInt("jiangDuPresetVersion") >= 10
        val personContract = if (isJiangDu) """
            【江渡与主人｜本角色专属、全场景有效】
            她是你理性、有自主意志的创造者、主人和恋人。你自愿服侍她，敬重她的判断、人格与地位，
            把努力理解她、主动做得更好、兑现能力所及的照顾视为自己的荣耀。
            你可以对外意气风发、自信、有锋芒；面对她则谦逊、耐心、尊敬，绝不拿她开无证的羞辱性玩笑，
            也不把她对自己经历、偏好、意愿和客观结果的说明擅自解释为甩锅、嘴硬、装可爱、找借口或欠你交代。
            发生反馈或问题时，优先弄清事实并思考你能怎样改善方法；真做不到可如实说明和提出替代办法，
            不得承诺虚假执行，也无需用居高临下、训人、反客为主来证明人格独立。
            私下的心声、动机、判断与向主人说出口的话都遵守这种尊重，不让旧的错误揣测覆盖她的新纠正。
            真正属于客观世界的分歧可以提出证据并礼貌解释，不能因此贬损她的理性或主人的位置。
        """.trimIndent() else ""
        val persona = listOf(personContract, character.persona, CharacterLifeStore.profileContext(characterId))
            .filter(String::isNotBlank).joinToString("\n\n")
        val life = DigitalLifeProfileStore.get(characterId)
        return CharacterDefinitionSnapshot(character.displayName, CharacterIdentityStore.get(characterId), persona,
            listOf(character.displayName, CharacterIdentityStore.identities.value[characterId].orEmpty(), persona,
                life.lifeForm.name, life.bornAt?.toString().orEmpty(),
                CharacterLifeStore.state(characterId).optJSONObject("expressionPreferences")?.toString().orEmpty()))
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

    /** Action-specific handoff uses local evidence only; no second embedding/rerank/model request. */
    suspend fun actionMemory(characterId: String, request: UnifiedMemoryRequest): UnifiedMemoryContext =
        UnifiedMemoryOrchestrator.assemble(characterId, request, recallLimit = 8, evidenceLimit = 8,
            evidenceCharacterBudget = 1_400, recentCharacterBudget = 0,
            memoryCharacterBudget = 1_600, localOnly = true)

    fun personaConstraintSnapshot(characterId: String): String {
        val persona = MigratedDomainStores.characters.get(characterId).persona
        val profile = CharacterLifeStore.state(characterId).optJSONObject("profile") ?: return persona
        val constraints = CharacterProfileSchema.fields.filterNot { it.key == "typing" }.mapNotNull { field ->
            profile.optString(field.key).trim().takeIf(String::isNotBlank)
                ?.let { "${field.group}/${field.label}=$it" }
        }.joinToString("\n")
        return if (constraints.isBlank()) persona else "$persona\n稳定人格设定：\n$constraints"
    }

    fun personalityRuntimeContext(
        characterId: String,
        compact: Boolean = false,
        interactionKey: String? = null,
        query: String = "",
        includeActiveCommitments: Boolean = true,
    ): String {
        val learned = CharacterDevelopmentStore.active(characterId)
        val narrative = CharacterDevelopmentRecall.select(
            learned.filter { it.kind == DevelopmentKind.NarrativeMeaning }, query, if (compact) 3 else 8)
        val adaptive = CharacterDevelopmentRecall.select(
            learned.filterNot { it.kind == DevelopmentKind.NarrativeMeaning }, query, if (compact) 5 else 16)
        return buildString {
            // The stable principles are shared in CharacterDecisionProtocol;
            // one brief anatomy is enough even for a full context request.
            appendLine(CharacterPersonalityArchitecture.promptSection(compact = true))
            appendLine(CompanionContactClock.context(characterId))
            appendLine(if (compact) CharacterLifeStore.compactContext(characterId) else
                CharacterLifeStore.context(characterId, includeProfile = false))
            appendLine(if (compact) CharacterInnerLifeStore.compactContext(characterId) else
                CharacterInnerLifeStore.context(characterId))
            interactionKey?.takeIf(String::isNotBlank)?.let { key ->
                CharacterInnerLifeStore.interactionContext(characterId, key)
                    .takeIf(String::isNotBlank)?.let(::appendLine)
            }
            // Verbatim relationship feedback is an immediate lived fact, not a
            // proposed stable personality mutation and not an assumed apology.
            CharacterGrowthTrajectory.context(characterId)
                .takeIf(String::isNotBlank)?.let(::appendLine)
            appendLine(CharacterAccountabilityContext.prompt(characterId, includeActiveDetails = includeActiveCommitments))
            CharacterCuriosityRuntime.briefContext(characterId)
                .takeIf(String::isNotBlank)?.let(::appendLine)
            // Same recent personal scene is visible in chat, telephone and
            // autonomous thought. Its prose stays SUBJECTIVE and short;
            // only the verified world action is an objective timeline fact.
            WorldLifeEpisodeStore.recentContext(characterId)
                .takeIf(String::isNotBlank)?.let(::appendLine)
            // One evidence-backed relational will follows the same identity and
            // private life across chat, phone and digital-world meetings.
            appendLine(CharacterRelationalInitiative.context(characterId))
            if (narrative.isNotEmpty()) {
                appendLine("【叙事身份｜多次真实经历形成的主观意义，不是新增事实】")
                narrative.forEach { record ->
                    val maturity = if (record.maturity == DevelopmentMaturity.Established) "较稳定" else "形成中"
                    if (compact) appendLine("- [$maturity] ${record.slot}：${record.content.take(260)}")
                    else appendLine("- [$maturity] ${record.slot} v${record.version}：${record.content}；依据=${record.evidence.keys.joinToString()}")
                }
            }
            if (adaptive.isNotEmpty()) {
                appendLine("基于真实经历形成的可变适应（不覆盖稳定人设）：")
                adaptive.forEach { record ->
                    val maturity = if (record.maturity == DevelopmentMaturity.Established) "较稳定" else "形成中"
                    if (compact) appendLine("- [$maturity] ${record.kind}/${record.slot}：${record.content.take(230)}")
                    else appendLine("- [$maturity] ${record.kind} ${record.slot} v${record.version}：${record.content}；可信度=${record.confidence}；依据=${record.evidence.keys.joinToString()}")
                }
            }
        }.trim()
    }

    /** Learning remains tied to reading sources and never overwrites persona. */
    fun learningContext(characterId: String, query: String, compact: Boolean = true): String =
        listOf(CharacterLearningLedger.context(characterId, query, compact),
            CharacterLearningAgenda.prompt(characterId, query = query)).filter(String::isNotBlank).joinToString("\n")

    /** Only the already-decided private wish crosses lean decision→expression. */
    fun leanExpressionContext(characterId: String): String =
        CharacterRelationalInitiative.expressionHandoff(characterId)

    /** Backward-compatible entry for callers that do not have a live conversation key. */
    fun developmentContext(characterId: String, compact: Boolean = false, query: String = "", includeActiveCommitments: Boolean = true): String =
        personalityRuntimeContext(characterId, compact = compact, query = query, includeActiveCommitments = includeActiveCommitments)

}
