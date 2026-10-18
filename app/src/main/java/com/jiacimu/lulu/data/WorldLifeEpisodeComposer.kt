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

internal object WorldLifeEpisodeComposer {
    /** Block the old canned prose and empty reflection masquerading as a short story.
     * Not a global chat-length rule: applies ONLY to autonomous literary scenes. */
    internal fun readableScene(scene: String): Boolean {
        val text = scene.trim()
        if (text.length < 200) return false
        if (listOf(
                "这里确实发生的，只有已经记录下来的活动",
                "至于心里究竟在想什么，没有人替他宣布答案",
                "为了证明生活还在继续",
                "等到下次真的遇见新东西",
            ).any(text::contains)) return false
        return text.count { it == '。' || it == '！' || it == '？' } >= 3
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
    ): WorldLifeSceneDraft? {
        val character = MigratedDomainStores.characters.get(characterId)
        val locationName = when (location) {
            DigitalWorldStore.ARRIVAL -> "世界入口"
            DigitalWorldStore.CLOUD_MEADOW -> "云眠原"
            else -> DigitalWorldPublicPlaces.label(location)
                ?: if (location.startsWith("home:")) "角色家中" else "已登记的数字世界地点"
        }
        val items = DigitalWorldStore.itemsAtLocation(characterId).take(9)
            .joinToString("；") { "${it.name}（${it.position.take(65)}）" }
        val prior = WorldLifeEpisodeStore.episodes(characterId).lastOrNull()
        val now = Instant.now()
        val model = try {
            withTimeoutOrNull(45_000L) {
                LuluAiServices.gateway.generate(
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
                        只返回完整JSON：{"title":"有情境特点的小标题","scene":"完整的第三人称场景正文","unresolvedThread":"具体尚未解决的本人内在问题或下一次想做的事；如已解决留空"}。
                        unresolvedThread 仅为角色私人的主观线索，不得包含未经执行的客观事件结果。
                    """.trimIndent(),
                    source = "数字世界·独处小说",
                    title = "${character.displayName}的独处小剧场",
                    maxTokens = 1_800,
                    contextMode = com.jiacimu.lulu.ai.CompanionContextMode.CharacterAndScenario,
                    readTimeoutMillis = 42_000,
                ).getOrNull()?.text
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val parsed = ModelStructuredOutput.objectOrNull(model.orEmpty()) ?: return null
        val scene = parsed.optString("scene").trim()
        if (!readableScene(scene)) return null
        return WorldLifeSceneDraft(
            title = parsed.optString("title").trim().take(70)
                .ifBlank { suppliedTitle.trim().take(70).ifBlank { "一段独处时光" } },
            scene = scene.take(1_800),
            unresolvedThread = parsed.optString("unresolvedThread").trim().take(220),
        )
    }
}
