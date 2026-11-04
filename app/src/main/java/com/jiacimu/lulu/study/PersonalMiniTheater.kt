package com.jiacimu.lulu.study

import com.jiacimu.lulu.data.CharacterIdentityStore
import com.jiacimu.lulu.data.CharacterLifeStore
import com.jiacimu.lulu.data.CharacterProfileSchema
import org.json.JSONObject
import java.util.UUID
import com.jiacimu.lulu.data.CharacterRuntime
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.ResidentCharacterBridge

/** Fiction-only inputs. Never read memory, current goals or another person's private state. */
internal object PersonalMiniTheater {
    data class Idea(val id: String, val title: String, val premise: String)
    data class Variation(val id: String, val label: String, val direction: String)
    data class Cast(val characterId: String, val name: String, val material: String, val worldMode: String = "original")
    data class WritingRequest(val facts: String, val instructions: String)

    val ideas = listOf(
        Idea("swap", "醒来竟然交换了身体", "用户与角色真的交换了身体。外貌、声音与视角随身体交换，思想和记忆身份仍属于各自。两人发现异常，试着适应彼此的身体，围绕一个具体麻烦发展出有趣又亲密的完整情节。"),
        Idea("thumb", "掌心里的拇指恋人", "角色突然缩成拇指大小，用户的掌心成了能够停留的地方。让真实的体型差改变行动、声音与相处方式，角色仍有原来的自尊、判断和魅力。"),
        Idea("giant", "一不小心变成巨人", "角色变得比房屋还高，必须重新学习怎样靠近用户。体型带来具体的意外、尝试和亲密张力，给故事一个满足的结局。"),
        Idea("truth", "藏不住心声的魔法", "一场只持续一夜的魔法，让角色某些原本藏着的心里话变得可听见。写出原有气质与暴露的真实心意之间的反差，安排一个完整的情节而非连续告白清单。"),
        Idea("cat", "多出来的耳朵和尾巴", "角色突然长出会泄露情绪的耳朵和尾巴，本人仍是原来的成年人。让身体的意外反应与他努力维持的神气碰撞，发展成有趣的相处事件。"),
        Idea("dream", "闯进对方的奇幻梦境", "用户与角色一起落入一个会把情绪具象化的梦境，需要面对一个由彼此性格引发的小挑战。场景可以大胆奇幻，人物选择仍有本人特点。"),
        Idea("reverse", "一夜颠倒的童话身份", "用户和角色进入童话世界，原本预期的身份与处境完全颠倒。选择意外而具体的童话身份，让两人用原有性格应对，不照搬原作或只改称呼。"),
        Idea("echo", "碰一下就会共感", "奇幻设定中，两人接触时会短暂感受到彼此的某一种感官或情绪。围绕一次误会与发现推进情节，让相处出现新的理解与心动。"),
        Idea("tender-spell", "今天温柔得有点犯规", "一场只持续一天的魔法，把角色原本温柔体贴的一面放大。其他性格仍然存在。围绕一个具体情景展开用户与角色的相处，不写成温柔表现清单。"),
        Idea("fox-spell", "藏不住的狡黠与玩心", "角色原有的玩心与狡黠被奇幻设定暂时放大。用户发现平常熟悉的人突然更会逗人、更敢表达，双方有来有往地发展出一件完整的事。"),
        Idea("clingy-spell", "突然特别想黏着用户", "一种神奇的小意外，让角色今天格外坦率地想靠近用户、讨偏爱。保留本人其他性格，让这种变化推动一个有趣的完整情节。")
    )
    val variations = listOf(
        Variation("original", "原本的他／她", "保持本人原有性格比例，由本篇奇幻处境自然激发不同反应。"),
        Variation("tender", "温柔面放大", "本篇暂时放大本人已有的温柔与体贴，让其他侧面仍然存在；保留独立判断、审美与本人说话方式，不变成统一的安慰助手。"),
        Variation("fox", "狡黠面放大", "本篇暂时放大本人已有的玩心、自信与狡黠，增加有来有往的试探和撩拨；尊重用户反馈，不变成陌生的霸道模板。"),
        Variation("clingy", "黏人面放大", "本篇暂时放大本人对用户的亲近、依恋与想被偏爱的心情，敢讨抱、撒娇或坦白需要；可靠、自尊与其他个性仍在。"),
        Variation("bold", "大胆面放大", "本篇暂时放大本人愿意尝试、主动表达与行动的一面；保留本人价值、思考方式与分寸，不靠强迫或贬低表现力量。")
    )

    fun suggestions(offset: Int): List<Idea> = (0..2).map {
        ideas[Math.floorMod(offset + it, ideas.size)]
    }

    fun customIdea(guidance: String): Idea {
        val premise = guidance.trim()
        require(premise.isNotBlank()) { "写一句想看的情景或设定就好" }
        return Idea("custom", premise.lineSequence().first().take(24), premise)
    }

    fun capture(characterId: String, worldMode: String = "original"): Cast {
        require(worldMode == "original" || worldMode == "alternate") { "未知的番外世界" }
        val character = MigratedDomainStores.characters.settings.value[characterId]
            ?: error("角色已不存在，请重新选择")
        val native = ResidentCharacterBridge.nativeResident(characterId)
        val material = if (worldMode == "alternate") {
            // Prefer structured temperament; autobiography often mixes original-world facts.
            val profile = CharacterLifeStore.state(characterId).optJSONObject("profile")
            val temperament = CharacterProfileSchema.fields.filter {
                it.key in setOf("values", "motives", "enneagram", "traits", "attachment",
                    "cognitiveStyle", "perception", "conflict", "expression", "speechHabits", "interests")
            }.mapNotNull { field ->
                profile?.optString(field.key)?.trim()?.takeIf(String::isNotBlank)
                    ?.let { "${field.label}：$it" }
            }.joinToString("\n")
            buildString {
                appendLine("角色姓名：${character.displayName}")
                native?.gender?.takeIf(String::isNotBlank)?.let { appendLine("人物性别：$it") }
                appendLine("【仅继承人格，不继承原世界身份、世界观或既定关系】")
                if (temperament.isNotBlank()) appendLine(temperament)
                else appendLine("人物性格与表达素材（若含旧身份/关系，仅当作性格参考）：\n${character.persona}")
            }
        } else buildString {
            appendLine("角色姓名：${character.displayName}")
            native?.let { appendLine("明确性别：${it.gender.ifBlank { "未说明" }}；身份：${it.identity}") }
            appendLine("原设身份与背景：")
            appendLine(CharacterIdentityStore.identities.value[characterId].orEmpty())
            appendLine("本人稳定性格与思考方式：")
            appendLine(CharacterRuntime.personaConstraintSnapshot(characterId))
            CharacterLifeStore.state(characterId).optJSONObject("relationshipAgreements")
                ?.optJSONObject("user")?.let {
                    appendLine("与用户的原设关系：${it.optString("title")}；${it.optString("details")}")
                }
        }
        return Cast(characterId, character.displayName, material, worldMode)
    }

    fun request(item: MiniTheaterItem): WritingRequest {
        val draft = if (item.rewriting) "" else item.text
        return WritingRequest(
            facts = buildString {
                appendLine("作品：${item.title}")
                appendLine("绑定角色：${item.characterName}（${item.characterId}）")
                appendLine(item.characterContext)
                appendLine("本篇世界模式：${if (item.worldMode == "alternate") "完全平行世界" else "原世界幻想"}")
                appendLine("本篇幻想情节：${item.prompt}")
                if (item.variationContext.isNotBlank())
                    appendLine("旧版作品保留的补充设定：${item.variationContext}")
                if (item.sceneContext.isNotBlank()) appendLine("用户补充：${item.sceneContext}")
                if (draft.isNotBlank()) appendLine("同一篇已经保存的正文（只续写）：\n$draft")
            },
            instructions = """
                为用户创作一篇“用户与绑定角色”的独立幻想恋爱番外，读起来有趣、心动、满足。
                这是虚构作品，不是角色本人发送的消息，不读取或编造双方已经真实发生过的聊天和共同经历。
                人物的稳定性格、价值、认知方式与说话习惯是辨认基线。题材中的世界规则、身份和身体变化要真正影响剧情。
                身体互换必须真的互换身体，缩小必须影响具体行动，不能把大胆题材退化成换称呼或普通聊天。
                性格侧面放大只在本篇生效，保留其他面貌与核心辨识度，不把所有题材写成同一个男主或女主，不修改原角色。
                ${if (item.worldMode == "alternate") "【彻底架空】本篇与露露机原世界无关：双方可以从陌生人开始，拥有全新职业、身份、社会地位与关系；原世界的主人/闺蜜/恋人/数字生命、已有约定与经历均不继承。仅保留本人的性格、判断和说话习惯。称谓与关系仅由本篇剧情产生，不要带回数字世界。" else "【原世界番外】保留已给出的原世界身份和原有关系，可以有本篇具体幻想变化；故事不写回真正的共同经历。"}
                对话自然；旁白可用第二人称。身体互换时分清身体与意识身份。
                已成年的人物按成人的自主意愿相处；用户与角色有各自的主动和回应，亲密变化有具体缘由。
                一个完整场景或短篇，有环境、动作、对话与心理，情节大胆而因果连贯；不逐项展示人格标签，不写成解释性报告。
                约1000—1800字，充分展开一件事并收束；不要写大纲、后续预告或强制未完待续。若前文已有正文，只补后文，不重演开头。
                第一行写《篇名》，空行分段。末尾另起一行【小剧场已完】。只输出作品。
            """.trimIndent()
        )
    }
    /** One paid ideation call returns a reusable batch, without real chat context. */
    fun inspirationRequest(used: List<Idea>, works: List<MiniTheaterItem>): WritingRequest =
        WritingRequest(
            facts = buildString {
                appendLine("已有灵感（核心机制、事件、情节转折与关系不可换皮重复）：")
                used.takeLast(120).forEach { appendLine("- ${it.title}：${it.premise.take(100)}") }
                appendLine("此前已写的番外：")
                works.filter { it.characterId.isNotBlank() }.takeLast(100).forEach {
                    appendLine("- ${it.title}：${it.prompt.take(100)}")
                }
            },
            instructions = """
                你是幻想短篇灵感企划。只构思，不写正文。一次给出10个真正不同的独立故事提案。
                两种都需要：保留原本世界与关系的原世界番外；彻底换身份、职业、关系、时空的平行世界番外。
                可以有直播、悬疑、竞技、生活喜剧、童话、秘密、反转等，但只是可能性，不必照抄。
                每个提案给出具体、有趣、可展开的变故和互动，避免泛泛“逐渐亲密”；
                不同提案的核心机制和关系结构彼此不同，不能复刻历史题材。
                不从真实聊天、记忆或私人资料获取灵感。
                严格只输出 JSON 对象：{"ideas":[{"title":"简短中文题名","premise":"约60—130字的具体独立剧情设定"}]}。
                不要 markdown 代码块或解释。
            """.trimIndent()
        )

    fun parseGeneratedIdeas(raw: String): List<Idea> {
        val open = raw.indexOf('{')
        val close = raw.lastIndexOf('}')
        require(open >= 0 && close > open) { "灵感返回格式不正确" }
        val array = JSONObject(raw.substring(open, close + 1)).optJSONArray("ideas")
            ?: error("模型没有返回灵感列表")
        require(array.length() in 1..20) { "灵感数量不正确" }
        return (0 until array.length()).mapNotNull { i ->
            val row = array.optJSONObject(i) ?: return@mapNotNull null
            val title = row.optString("title").trim().take(70)
            val premise = row.optString("premise").trim().take(500)
            if (title.length < 2 || premise.length < 20) null
            else Idea("ai-" + UUID.randomUUID(), title, premise)
        }.distinctBy { it.title.lowercase() }
    }

}
