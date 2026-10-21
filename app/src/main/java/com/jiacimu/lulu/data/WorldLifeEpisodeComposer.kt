package com.jiacimu.lulu.data

import com.jiacimu.lulu.ai.LuluAiServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/**
 * An authored private scene, anchored in a real executed action.
 * "Unresolved" is the character's subjective tension, never an extra NPC/item/world event.
 */
internal data class WorldLifeSceneDraft(
    val title: String,
    val scene: String,
    val unresolvedThread: String = "",
)

internal data class WorldLifeComposeOutcome(
    val draft: WorldLifeSceneDraft? = null,
    val failureReason: String = "",
    val retryable: Boolean = true,
)

internal object WorldLifeEpisodeComposer {
    // Release builds obfuscate exception class names ("r", "a", etc.).
    // Prefer stable HTTP / transport reasons. Detailed provider responses are
    // already logged by the shared model gateway, not leaked into chat.
    internal fun failureSummary(error: Throwable): String {
        val description = error.message.orEmpty()
        val http = Regex("""(?<!\d)(400|401|403|404|408|413|429|500|502|503|504)(?!\d)""")
            .find(description)?.value
        return when (http) {
            "400", "413" -> "模型接口拒绝请求（HTTP $http）"
            "401", "403" -> "模型接口鉴权或权限失败（HTTP $http）"
            "404" -> "模型接口或模型名称不存在（HTTP 404）"
            "429" -> "模型接口限流（HTTP 429）"
            "408", "500", "502", "503", "504" -> "模型服务暂时不可用（HTTP $http）"
            else -> when {
                description.contains("timeout", true) || description.contains("超时") ->
                    "模型响应超时"
                description.contains("连接", true) || description.contains("connection", true) ->
                    "模型网络连接失败"
                description.contains("模型", true) && description.contains("配置") ->
                    "模型配置不可用"
                else -> "模型请求异常（请查看模型请求日志）"
            }
        }
    }

    internal fun permanentFailure(error: Throwable): Boolean =
        Regex("""(?<!\d)(400|401|403|404|413)(?!\d)""").containsMatchIn(error.message.orEmpty())

    /** An independent literary heading is a FIRST line, not a planner's task title.
     * Old records are never modified; this only affects newly generated works.
     * Ignore arbitrary JSON-like inner fragments and never treat a mid-story
     * quotation as an article heading. */
    internal fun literaryHeading(raw: String): Pair<String, String>? {
        val clean = raw.trim()
        val first = clean.lineSequence().firstOrNull()?.trim().orEmpty()
        val matched = Regex("""^(?:《([^《》\n]{2,20})》|标题[：:]\s*([^\n]{2,20}))$""")
            .matchEntire(first) ?: return null
        val title = (matched.groupValues[1].ifBlank { matched.groupValues[2] })
            .trim().trim('。', '！', '？', '.', '!', '?', ' ')
        if (title.length !in 2..20 || Regex("^(?:独处|一段独处|独处小剧场|一个人的片刻|生活片段|无题)$")
                .matches(title)) return null
        val remaining = clean.substringAfter('\n', "").trim()
        return if (readableScene(remaining)) title to remaining else null
    }

    /** Publication is not a writing contest. In particular, word count, number
     * of periods, tone and optional metadata must never discard real prose.
     * Only reject blank/error/obviously unfinished non-scenes.
     */
    internal fun readableScene(scene: String): Boolean {
        val text = scene.trim()
        if (text.length < 28) return false
        if (text.startsWith("{") || text.startsWith("[" + "\"" + "scene")) return false
        if (listOf("这里确实发生的，只有已经记录下来的活动",
                "至于心里究竟在想什么，没有人替他宣布答案",
                "为了证明生活还在继续",
                "等到下次真的遇见新东西").any(text::contains)) return false
        if (Regex("""^(?:error|http\\s*[45]\\d\\d|请求失败|生成失败|模型调用失败)""",
                RegexOption.IGNORE_CASE).containsMatchIn(text)) return false
        return true
    }

    /**
     * A fully closed scene field is usable even if the model ran out of tokens
     * while writing optional metadata. Never accept an unfinished scene.
     * Some providers return prose instead of the requested JSON envelope.
     */
    internal fun parseModelScene(raw: String, suppliedTitle: String): WorldLifeSceneDraft? {
        val structured = ModelStructuredOutput.objectOrNull(raw)
        val completeSceneField = Regex(""""scene"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            .find(raw)?.groupValues?.getOrNull(1)
        val recoveredScene = completeSceneField?.let { encoded ->
            runCatching { org.json.JSONObject("""{"scene":"${encoded}"}""").optString("scene") }
                .getOrNull()
        }.orEmpty()
        val plain = raw.trim().removePrefix("```text").removePrefix("```markdown")
            .removePrefix("```").removeSuffix("```").trim()
        val plainScene = if (plain.startsWith("{") || plain.contains("\"scene\"")) "" else plain
        val prose = structured?.optString("scene").orEmpty().ifBlank { recoveredScene }
            .ifBlank { structured?.optString("text").orEmpty() }.ifBlank { plainScene }.trim()
        val heading = literaryHeading(prose)
        val scene = heading?.second ?: prose
        if (!readableScene(scene)) return null
        return WorldLifeSceneDraft(
            title = heading?.first
                ?: structured?.optString("title").orEmpty().trim().take(24)
                    .takeUnless { genericHeading(it) }
                ?: deriveSceneTitle(scene),
            scene = scene.take(4_000),
            unresolvedThread = structured?.optString("unresolvedThread").orEmpty().trim().take(220),
        )
    }

    private fun genericHeading(title: String): Boolean =
        title.isBlank() || Regex("^(?:一段独处时光|一个人的片刻|独处小剧场|独处片段|生活片段|无题|独处故事|独处)$")
            .matches(title.trim()) || title.contains("独处小剧场")

    /** Gentle deterministic fallback when providers omit the requested title.
     * Never invent locations, other people or their reactions. */
    private fun deriveSceneTitle(scene: String): String {
        val opening = scene.trim().lineSequence().firstOrNull().orEmpty()
            .replace(Regex("^[\\s　“”]+"), "")
            .substringBefore('。').substringBefore('！').substringBefore('？')
            .trim('，', ',', ' ', '　', '“', '”')
        return if (opening.length in 3..15) opening else if (opening.length > 15)
            opening.take(11).trimEnd('，', '、', '与', '和', '的', '在')
        else "光落下的时候"
    }

    /**
     * The autonomous planner provides only a motive / plot seed.
     * A separate literary pass AFTER the executor's receipt owns prose.
     * On model failure do not invent a fake "life goes on" story as a receipt.
     */
    suspend fun compose(
        characterId: String,
        location: String,
        confirmedAction: String,
        suppliedTitle: String,
        suppliedScene: String,
        witnessedItems: String? = null,
        verifiedEvents: List<SharedTimelineEvent> = emptyList(),
        category: String = "outing",
    ): WorldLifeComposeOutcome {
        val character = MigratedDomainStores.characters.get(characterId)
        val locationName = when (location) {
            DigitalWorldStore.ARRIVAL -> "世界入口"
            DigitalWorldStore.CLOUD_MEADOW -> "云眠原"
            else -> DigitalWorldPublicPlaces.label(location)
                ?: if (location.startsWith("home:")) "角色家中" else "已登记的数字世界地点"
        }
        val items = witnessedItems ?: DigitalWorldStore.itemsAtLocation(characterId).take(9)
            .joinToString("；") { "${it.name}（${it.position.take(65)}）" }
        val prior = WorldLifeEpisodeStore.episodes(characterId).lastOrNull()
        val now = Instant.now()
        val sourceFacts = verifiedEvents.sortedBy { it.occurredAt }.take(16)
            .joinToString("\n") { e ->
                "${e.occurredAt} · ${e.speaker}（${e.channel}，证据=${e.id}）：${e.content.take(850)}"
            }.take(6_600)
        val encounteredResidents = verifiedEvents.filter { it.sessionId.isNotBlank() }
            .mapNotNull { source ->
                DigitalWorldStore.state.value.meetings
                    .firstOrNull { it.id == source.sessionId }
                    ?.turns?.map { it.speakerId }?.filter { it.startsWith("resident:") }
            }.flatten().distinct()
        val actualSocialContext = if (encounteredResidents.isEmpty()) "" else
            CharacterSocialRelationship.perspectivesFor(characterId, encounteredResidents)
        val sceneScope = if (category == "encounter")
            "这是已真实发生的居民交往，不是独处。可写人物之间的已确认台词、动作反应及本人对这段相处的感受，不得新编对方后续行为、对方心声或未发生的关系结果。"
        else if (category == "incident")
            "这是一件世界中已实际处理的生活事件，强调本人如何应对及真实后果；没有记录的追加事件不能擅自发生。"
        else "这是本人自主选择的真实日常活动，可以写轻快、幽默、犹豫或安静，但不要空写风景与没有缘由的反思。"
        var firstFailure = ""
        var firstPermanent = false
        val model = try {
            withTimeoutOrNull(63_000L) {
                val response = LuluAiServices.gateway.generate(
                    characterId = characterId,
                    facts = buildString {
                        appendLine("【本篇已执行的世界行动与交往结果】$confirmedAction")
                        if (sourceFacts.isNotBlank()) appendLine("【已保存的原始事件与实际发言（只能依据这些事件描写）】\n$sourceFacts")
                        if (actualSocialContext.isNotBlank())
                            appendLine("【角色已知的实际关系印象（仅本人主观）】\n${actualSocialContext.take(1000)}")
                        appendLine("【当前真实地点】$locationName（$location）")
                        appendLine("【现场实际登记的物品】${items.ifBlank { "无可核实的已登记物品" }}")
                        appendLine("【角色】${character.displayName}；尊重他已有的个性、关系经历与语言习惯。")
                        appendLine("【当前心境与持续意愿（内部体验，不等于客观动作）】")
                        appendLine(CharacterInnerLifeStore.compactContext(characterId, now).take(1_500))
                        appendLine(CharacterLifeStore.compactContext(characterId).take(950))
                        if (suppliedTitle.isNotBlank() || suppliedScene.isNotBlank()) {
                            appendLine("【角色决策阶段的私人创作线索，未经执行；不是新事实】")
                            appendLine("题材倾向：${suppliedTitle.take(70)}；戏剧种子：${suppliedScene.take(240)}")
                        }
                        prior?.let {
                            // Never recycle a novel's unverified character portrayal:
                            // it is creative prose, not reliable knowledge of the user.
                            appendLine("【上次真实独处行动（用于场景时间连续性）】${it.actualAction.take(180)}")
                        }
                    },
                    instruction = """
                        【根据已真实发生的经历，创作生活小剧场】
                        $sceneScope
                        写第三人称、约300至650汉字的完整生活小说片段。字数不是质量指标：读者要看见具体场面和人物内在变化，而非抽象议论文、抒情工作报告、道德总结或千篇一律的安慰。
                        开始就进入可感知的地点/时间与一个具体动作；结合角色真实计划、已有关系与生活经历，表现本人此刻的期待、犹豫或兴趣。
                        有真实阻力、犹豫、期待落空或意外发现时，借细节、停顿、动作与反应写出张力；若本次并无冲突，就写角色正在享受、观察或探索的具体趣味，不要凭空强迫转折。可以幽默、懊恼、轻快、执拗、好奇、怅惘或安宁，不能每篇都沉郁反思、想念主人。
                        根据原始事实展现真正发生的交流、进展、行动选择或人物当时的反应。允许这次经历没有剧烈变化；不要为了故事好看虚构突发事件、强制转念或关系升温。结尾落在真实后果、一个微妙的新理解或仍未解决的个人疑问。不要宣布「生活仍然继续」「没必要特别」「真正重要的是」等空泛感悟，不点评自己的写作任务。
                        可细写呼吸、脚步、听觉、光线、手部动作与已存在物件，允许主观回想、想象和不可靠的个人猜测；但心理猜测应以人物视角呈现，不当成第三方真实事件。
                        【不可越过的世界事实边界】只有已提供的事件证据与世界状态才是客观发生的。只有已保存的NPC本人真实台词可被引用、转述或展开表情动作；不得写出NPC未记录的新台词、动作、邀约、物品、关系确认、另一地点的到访或用户的新行为。情绪、动作描写是文学呈现，不是新增事实；不能把想象的互动写入时间线。
                        写人物之间的相处时，别替居民宣布心理或把他们写成陪衬；人物之间有各自的目的和界限。已证实的冲突、玩笑、尴尬和细微反应可以有小说笔触。
                        如上一次有主观未解心事，可自然发展人物态度，但不能复述前文或预言别人会怎样。内心的情节张力与客观地图事实保持区分。
                        小说叙述适合文学细节；私聊的即时接话仍是简短活人的语言，两种风格不能互相污染。
                        第一行单独写一条与本篇实际场景和人物情绪贴切的散文式篇名，格式《篇名》，约4～12个汉字；不写“独处小剧场”“一个人的时光”这种栏目名，也不照搬任务名。空一行后写小说正文，绝对不用JSON包装。
                        正文直接按小说排版：自然段落之间空一行，尽量每段一到三句，人物动作、对话和私下心思用文学笔法自然衔接。不要写【叙述】【心声】【对白】、旁白说明、章节分析或其他系统标签。
                        格式不是成功条件，不要为此牺牲人物和故事。私人猜想不等于世界事实；只有上面已提交证据中存在的对话，才能作为对方实际说的话。
                    """.trimIndent(),
                    source = "数字世界·生活小剧场",
                    title = "${character.displayName}的生活小剧场",
                    maxTokens = 1_800,
                    contextMode = com.jiacimu.lulu.ai.CompanionContextMode.CharacterAndScenario,
                    readTimeoutMillis = 55_000,
                )
                response.exceptionOrNull()?.let { error ->
                    firstFailure = failureSummary(error)
                    firstPermanent = permanentFailure(error)
                }
                response.getOrNull()?.text
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            firstFailure = failureSummary(error)
            firstPermanent = permanentFailure(error)
            null
        }
        parseModelScene(model.orEmpty(), suppliedTitle)?.let { return WorldLifeComposeOutcome(draft = it) }
        if (firstPermanent) return WorldLifeComposeOutcome(
            failureReason = firstFailure, retryable = false)

        // Retry ONLY a failed generation, never an already committed world action.
        // Keep the retry short so autonomous perception is not blocked by a second long chapter.
        var retryFailure = ""
        var retryPermanent = false
        val retry = try {
            withTimeoutOrNull(36_000L) {
                val response = LuluAiServices.gateway.generate(
                    characterId = characterId,
                    facts = buildString {
                        appendLine("已执行世界动作：$confirmedAction")
                        if (sourceFacts.isNotBlank()) appendLine("真实事件与双向台词：\n${sourceFacts.take(4000)}")
                        if (actualSocialContext.isNotBlank()) appendLine("已知关系：${actualSocialContext.take(600)}")
                        appendLine("真实地点：$locationName（$location）")
                        appendLine("已登记现场物件：${items.ifBlank { "无" }}")
                        appendLine("角色：${character.displayName}")
                        appendLine("角色已有内在状态：${CharacterInnerLifeStore.compactContext(characterId, Instant.now()).take(900)}")
                        appendLine("上次核实的行动：${prior?.actualAction?.take(140).orEmpty()}；不要复演")
                        appendLine("私人情节种子（不是事实）：${suppliedScene.take(160)}")
                    },
                    instruction = """
                        $sceneScope
                        写一篇约250至450汉字、有画面感、真实生活细节和人物主体性的第三人称生活短篇。
                        必须围绕已保存的真实行动和居民交谈展开，已记录的NPC可按真实台词入场；不得编造额外的NPC到访、
                        对话、道具、读书、来电、移动或相处结果。允许细腻主观心理，但别写空泛人生哲理报告。
                        第一行《有画面感的散文篇名》，空一行后自然语言正文；不写JSON或【叙述】【心声】【对白】标签。标题应来自当前故事而非固定模板，不虚构世界事实。
                    """.trimIndent(),
                    source = "数字世界·生活小剧场补写",
                    title = "${character.displayName}的生活片段补写",
                    maxTokens = 1_250,
                    contextMode = com.jiacimu.lulu.ai.CompanionContextMode.CharacterAndScenario,
                    readTimeoutMillis = 30_000,
                )
                response.exceptionOrNull()?.let { error ->
                    retryFailure = failureSummary(error)
                    retryPermanent = permanentFailure(error)
                }
                response.getOrNull()?.text
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            retryFailure = failureSummary(error)
            retryPermanent = permanentFailure(error)
            null
        }
        parseModelScene(retry.orEmpty(), suppliedTitle)?.let { return WorldLifeComposeOutcome(draft = it) }
        return WorldLifeComposeOutcome(retryable = !retryPermanent, failureReason = when {
            model.isNullOrBlank() && retry.isNullOrBlank() && firstFailure.isBlank() && retryFailure.isBlank() -> "两次请求超时或空响应"
            retryFailure.isNotBlank() -> "补写请求失败：$retryFailure"
            firstFailure.isNotBlank() && retry.isNullOrBlank() -> "模型请求失败：$firstFailure"
            else -> "模型正文不完整或格式不合格"
        })
    }
}
