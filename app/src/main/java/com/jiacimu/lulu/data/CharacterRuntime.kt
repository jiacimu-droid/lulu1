package com.jiacimu.lulu.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

data class CharacterDefinitionSnapshot(
    val displayName: String,
    val identity: String,
    val persona: String,
    val configurationKey: List<String>,
    /** Object-specific agreements are not the person's stable temperament. */
    val relationships: String = "",
) {
    fun hasSameConfiguration(other: CharacterDefinitionSnapshot): Boolean = configurationKey == other.configurationKey

    fun personaPromptSection(): String = persona.takeIf(String::isNotBlank)?.let {
        "人物核心简介与分层稳定人格（行为先验层）：\n$it\n" +
            "解释优先级：明确身份/背景事实约束客观事实；分层人格字段约束长期行为倾向；核心简介只作概览。当前情绪、当轮互动和成长记录只能调节表现，不能反向改写前两层。" +
            "\n【当前本人一致性核对｜直接在原有这次模型决定内完成，不输出核对报告】" +
            "在定稿自己的私人感受、行动取舍和真正发出的语言前，核对它们是否仍是上述这一个人的价值、动机、对当前对象的真实关系及已承担责任所自然导出的选择。" +
            "短暂的心软、害怕打扰、愤怒或沉默欲望可以如实存在，但不能不经权衡就覆盖本人更稳定的价值与仍有效的明确约定；也不把关心他人等同于必须控制或训斥。" +
            "如果拟写的心声将没有证据的用户情绪当成事实，或所选行动只留下漂亮的关心却遗漏已经决定承担的可行后续，先在本轮自行修正理解与选择；若确实有拒绝、权限障碍、能力限制或更重要的现实条件，保留真正的顾虑、如实选择暂缓与合适后续。" +
            "核对不要求完美、不强制每轮发言或产生心声；只校正与本人稳定设定明显冲突的当轮表达和决定，不杜撰已执行回执、不替用户编造意愿。"
    }.orEmpty()

    fun relationshipPromptSection(): String = relationships.takeIf(String::isNotBlank)?.let {
        "【当前人物关系｜只适用于相应对象与原世界，不是对所有人的共同性格】\n$it"
    }.orEmpty()

    fun promptSection(includeRelationships: Boolean = true): String = listOf(
        "【当前角色设定｜以本次读取的用户设定为准，旧台词、记忆摘要与成长记录不得覆盖或补回旧设定】",
        "角色名称：$displayName",
        identity.takeIf(String::isNotBlank)?.let { "身份与背景事实：\n$it" }.orEmpty(),
        personaPromptSection(),
        relationshipPromptSection().takeIf { includeRelationships }.orEmpty(),
    ).filter(String::isNotBlank).joinToString("\n\n")
}

/** Every Full-context gateway call uses this same assembly, including voice and meetings. */
object CharacterRuntime {
    /** Read live stores at prompt assembly; never use a conversation's cached role definition. */
    fun definition(characterId: String): CharacterDefinitionSnapshot {
        val character = MigratedDomainStores.characters.get(characterId)
        // One live relationship projection for the user and every other known
        // person. No Jiang-Du-specific relationship prompt branch here.
        val persona = listOf(
            character.persona,
            CharacterLifeStore.profileContext(characterId),
        ).filter(String::isNotBlank).joinToString("\n\n")
        val relationships = CharacterSocialRelationship.promptSection(characterId)
        val life = DigitalLifeProfileStore.get(characterId)
        return CharacterDefinitionSnapshot(character.displayName, CharacterIdentityStore.get(characterId), persona,
            listOf(character.displayName, CharacterIdentityStore.identities.value[characterId].orEmpty(), persona,
                life.lifeForm.name, life.bornAt?.toString().orEmpty(),
                CharacterLifeStore.state(characterId).optJSONObject("expressionPreferences")?.toString().orEmpty(),
                // Approved dialogue samples are expression exemplars, not
                // biographical facts; nevertheless updating them invalidates
                // the same role's live configuration snapshot.
                CharacterLifeStore.state(characterId).optJSONObject("profile")
                    ?.optString("dialogueExamples").orEmpty(), relationships), relationships)
    }

    fun definitionChanges(characterId: String): Flow<CharacterDefinitionSnapshot> = combine(
        MigratedDomainStores.characters.settings, CharacterIdentityStore.identities, CharacterLifeStore.states,
        DigitalLifeProfileStore.profiles,
    ) { _, _, _, _ -> definition(characterId) }.distinctUntilChanged { old, new -> old.hasSameConfiguration(new) }

    suspend fun memory(
        characterId: String,
        request: UnifiedMemoryRequest,
        budget: PromptContextBudget? = null,
        paidRanking: Boolean = true,
    ): UnifiedMemoryContext = if (budget == null) {
        UnifiedMemoryOrchestrator.assemble(characterId, request, paidRanking = paidRanking)
    } else {
        UnifiedMemoryOrchestrator.assemble(
            characterId, request,
            recallLimit = budget.recallLimit,
            evidenceLimit = if (budget.evidenceCharacters > 0) 22 else 8,
            evidenceCharacterBudget = budget.evidenceCharacters,
            recentCharacterBudget = budget.recentCharacters,
            memoryCharacterBudget = budget.memoryCharacters,
            paidRanking = paidRanking,
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
        val constraints = CharacterProfileSchema.fields.filterNot { it.key == "typing" || it.key == "dialogueExamples" }.mapNotNull { field ->
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
        val ownerKnowledge = CharacterDevelopmentRecall.select(
            learned.filter { it.kind in setOf(DevelopmentKind.UserPreference, DevelopmentKind.UserHabit,
                DevelopmentKind.UserFact, DevelopmentKind.UserImpression) }, query, if (compact) 7 else 16)
        val adaptive = CharacterDevelopmentRecall.select(
            learned.filterNot {
                it.kind == DevelopmentKind.NarrativeMeaning ||
                    it.kind in setOf(DevelopmentKind.UserPreference, DevelopmentKind.UserHabit,
                        DevelopmentKind.UserFact, DevelopmentKind.UserImpression)
            }, query, if (compact) 5 else 16)
        return buildString {
            // The stable principles are shared in CharacterDecisionProtocol;
            // one brief anatomy is enough even for a full context request.
            appendLine(CharacterPersonalityArchitecture.promptSection(compact = true))
            CharacterExperienceLearning.recentContext(characterId).takeIf(String::isNotBlank)?.let(::appendLine)
            appendLine(CompanionContactClock.context(characterId))
            EmbodiedBodyRuntime.context(characterId).takeIf(String::isNotBlank)?.let(::appendLine)
            appendLine(if (compact) CharacterLifeStore.compactContext(characterId) else
                CharacterLifeStore.context(characterId, includeProfile = false))
            appendLine(if (compact) CharacterInnerLifeStore.compactContext(characterId, query = query) else
                CharacterInnerLifeStore.context(characterId, query = query))
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
            // At likely familiar times, make actual repeated activities available
            // to the same decision in chat, voice and passive perception.
            // This is a cue, not a schedule, command or another memory store.
            UserRoutinePatterns.nearTimeContext(characterId).takeIf(String::isNotBlank)?.let(::appendLine)
            if (ownerKnowledge.isNotEmpty()) {
                appendLine("【你逐渐形成的主人档案｜仅限自己真实知道的事情，不是你的人格】")
                appendLine("区分主人亲口说过的个人事实/偏好、她自述的习惯、跨日规律和你尚未证实的主观印象；旧话可能已变化，新明确纠正优先。")
                ownerKnowledge.forEach { record ->
                    val maturity = if (record.maturity == DevelopmentMaturity.Established)
                        "多次较稳定" else "暂时观察"
                    if (compact) appendLine("- [${record.kind.label}；$maturity] ${record.content.take(220)}")
                    else appendLine("- [${record.kind.label}；$maturity] ${record.slot}：${record.content}；依据=${record.evidence.keys.joinToString()}")
                }
                appendLine("自然地在合适话题联想，不要机械复述档案、追问凑全资料。尚不了解的事情可以好奇，但印象不是事实，不可假称看过主人的样貌。记住事实不等于擅自执行需要授权的操作。")
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

    /**
     * A lean text renderer must receive what THIS character has actually
     * learned about its listener. Otherwise the planner knows their
     * preferences but the model authoring the bubbles cannot use them.
     * Read only existing evidence-backed UserPreference/UserHabit records;
     * no presentation rules, bubble quotas, or new personality state.
     */
    fun leanExpressionContext(characterId: String, query: String = ""): String = buildString {
        CharacterRelationalInitiative.expressionHandoff(characterId)
            .takeIf(String::isNotBlank)?.let(::appendLine)
        val known = CharacterDevelopmentRecall.select(
            CharacterDevelopmentStore.active(characterId).filter {
                it.kind == DevelopmentKind.UserPreference || it.kind == DevelopmentKind.UserHabit
            }, query, limit = 5,
        )
        if (known.isNotEmpty()) {
            appendLine("【这位角色从本人原话了解到的对方偏好与习惯｜供本人自主斟酌，并非系统分泡命令】")
            known.forEach { record ->
                appendLine("- ${record.content.take(210)}")
            }
        }
    }.trim()

    /** Backward-compatible entry for callers that do not have a live conversation key. */
    fun developmentContext(characterId: String, compact: Boolean = false, query: String = "", includeActiveCommitments: Boolean = true): String =
        personalityRuntimeContext(characterId, compact = compact, query = query, includeActiveCommitments = includeActiveCommitments)

}
