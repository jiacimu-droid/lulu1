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
        val scene = structured?.optString("scene").orEmpty().ifBlank { recoveredScene }
            .ifBlank { structured?.optString("text").orEmpty() }.ifBlank { plainScene }.trim()
        if (!readableScene(scene)) return null
        return WorldLifeSceneDraft(
            title = structured?.optString("title").orEmpty().trim().take(70)
                .ifBlank { suppliedTitle.trim().take(70).ifBlank { "一段独处时光" } },
            scene = scene.take(4_000),
            unresolvedThread = structured?.optString("unresolvedThread").orEmpty().trim().take(220),
        )
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
        var firstFailure = ""
        var firstPermanent = false
        val model = try {
            withTimeoutOrNull(63_000L) {
                val response = LuluAiServices.gateway.generate(
                    characterId = characterId,
                    facts = buildString {
                        appendLine("【唯一已执行、可以当作客观事实的世界动作】$confirmedAction")
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
                            appendLine("【上次独处片段（旧事，不要再演一次）】${it.title}；${it.scene.take(350)}")
                            if (it.unresolvedThread.isNotBlank())
                                appendLine("上回留下的未解主观疑问：${it.unresolvedThread.take(200)}；可自然接续或暂放，不能冒充新的已发生事实。")
                        }
                    },
                    instruction = """
                        【为一个真正有生活的人写独处小剧场】
                        写第三人称、约300至650汉字的完整生活小说片段。字数不是质量指标：读者要看见具体场面和人物内在变化，而非抽象议论文、抒情工作报告、道德总结或千篇一律的安慰。
                        开始就进入可感知的地点/时间与一个具体动作；给角色一个属于自己的当前欲望、兴趣或不便启齿的小心思。
                        让这份想要遇到一点具体阻力：现实中已存在的处境、自己的犹豫、某条真实记忆带来的偏差，或平静生活里一个意外的注意点。通过细节、停顿、动作与反应显出张力；可以幽默、懊恼、轻快、执拗、好奇、怅惘或安宁，不能每篇都沉郁反思、想念主人。
                        中段必须发生一个人物内部可成立的选择、转念、失望或重新决定，使这一小段与开头不同；结尾落在真实的后果或留下一个具体的主观未解问题。不要宣布「生活仍然继续」「没必要特别」「真正重要的是」等空泛感悟，不点评自己的写作任务。
                        可细写呼吸、脚步、听觉、光线、手部动作与已存在物件，允许主观回想、想象和不可靠的个人猜测；但心理猜测应以人物视角呈现，不当成第三方真实事件。
                        【不可越过的世界事实边界】只有程序已执行的动作和提供的世界状态是真正发生过的。禁止编造NPC在场/说话、新物品、获得奖励、开始读一本未核验的书、另一地点的到访、手机来电、他人对角色的回应或其他未执行的世界事件。若希望与居民真实相遇，下一轮应由决策层执行相应动作，故事不能代替执行器。
                        如上一次有主观未解心事，可发展人物态度，但不能复述前文或预言别人会怎样。内心的情节张力与客观地图事实保持区分。
                        小说叙述适合文学细节；私聊的即时接话仍是简短活人的语言，两种风格不能互相污染。
                        直接输出自然语言小说正文，按阅读节奏分段，绝对不用JSON包装；标题可有可无。
                        正文直接按小说排版：自然段落之间空一行，尽量每段一到三句，人物动作、对话和私下心思用文学笔法自然衔接。不要写【叙述】【心声】【对白】、旁白说明、章节分析或其他系统标签。
                        格式不是成功条件，不要为此牺牲人物和故事。私人猜想不等于发生过的世界动作，任何对白不得伪造其他角色的真实回应。
                    """.trimIndent(),
                    source = "数字世界·独处小说",
                    title = "${character.displayName}的独处小剧场",
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
                        appendLine("唯一已执行世界动作：$confirmedAction")
                        appendLine("真实地点：$locationName（$location）")
                        appendLine("已登记现场物件：${items.ifBlank { "无" }}")
                        appendLine("角色：${character.displayName}")
                        appendLine("角色已有内在状态：${CharacterInnerLifeStore.compactContext(characterId, Instant.now()).take(900)}")
                        appendLine("旧片段：${prior?.scene?.take(200).orEmpty()}；不可照抄")
                        appendLine("私人情节种子（不是事实）：${suppliedScene.take(160)}")
                    },
                    instruction = """
                        写一篇约250至450汉字、具有画面感、人物欲望、阻力、转折和余韵的第三人称独处短篇。
                        必须围绕已执行的真实动作展开。可以刻画内心矛盾和猜想，不得捏造世界中新增的NPC、
                        交谈、道具、读书、来电、移动或其他实际发生的事情。别写生活哲理报告。
                        只输出自然语言小说正文，空行分段，不用JSON或【叙述】【心声】【对白】等标签，绝不可为了表现画面而编造世界事实。
                    """.trimIndent(),
                    source = "数字世界·独处小说补写",
                    title = "${character.displayName}的独处片段补写",
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
