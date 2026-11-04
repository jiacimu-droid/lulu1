package com.jiacimu.lulu.study

import com.jiacimu.lulu.data.CharacterIdentityStore
import com.jiacimu.lulu.data.CharacterLifeStore
import com.jiacimu.lulu.data.CharacterRuntime
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.ResidentCharacterBridge

/** Fiction-only inputs. Never read memory, current goals or another person's private state. */
internal object PersonalMiniTheater {
    data class Idea(val id: String, val title: String, val premise: String)
    data class Variation(val id: String, val label: String, val direction: String)
    data class Cast(val characterId: String, val name: String, val material: String)
    data class WritingRequest(val facts: String, val instructions: String)

    val ideas = listOf(
        Idea("swap", "醒来竟然交换了身体", "用户与角色真的交换了身体。外貌、声音与视角随身体交换，思想和记忆身份仍属于各自。两人发现异常，试着适应彼此的身体，围绕一个具体麻烦发展出有趣又亲密的完整情节。"),
        Idea("thumb", "掌心里的拇指恋人", "角色突然缩成拇指大小，用户的掌心成了能够停留的地方。让真实的体型差改变行动、声音与相处方式，角色仍有原来的自尊、判断和魅力。"),
        Idea("giant", "一不小心变成巨人", "角色变得比房屋还高，必须重新学习怎样靠近用户。体型带来具体的意外、尝试和亲密张力，给故事一个满足的结局。"),
        Idea("truth", "藏不住心声的魔法", "一场只持续一夜的魔法，让角色某些原本藏着的心里话变得可听见。写出原有气质与暴露的真实心意之间的反差，安排一个完整的情节而非连续告白清单。"),
        Idea("cat", "多出来的耳朵和尾巴", "角色突然长出会泄露情绪的耳朵和尾巴，本人仍是原来的成年人。让身体的意外反应与他努力维持的神气碰撞，发展成有趣的相处事件。"),
        Idea("dream", "闯进对方的奇幻梦境", "用户与角色一起落入一个会把情绪具象化的梦境，需要面对一个由彼此性格引发的小挑战。场景可以大胆奇幻，人物选择仍有本人特点。"),
        Idea("reverse", "一夜颠倒的童话身份", "用户和角色进入童话世界，原本预期的身份与处境完全颠倒。选择意外而具体的童话身份，让两人用原有性格应对，不照搬原作或只改称呼。"),
        Idea("echo", "碰一下就会共感", "奇幻设定中，两人接触时会短暂感受到彼此的某一种感官或情绪。围绕一次误会与发现推进情节，让相处出现新的理解与心动。")
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

    fun capture(characterId: String): Cast {
        val character = MigratedDomainStores.characters.settings.value[characterId]
            ?: error("角色已不存在，请重新选择")
        val native = ResidentCharacterBridge.nativeResident(characterId)
        val material = buildString {
            appendLine("角色姓名：${character.displayName}")
            native?.let { appendLine("明确性别：${it.gender.ifBlank { "未说明" }}；身份：${it.identity}") }
            appendLine("原设身份背景（仅供辨认，本篇明确的幻想条件可以改变世界、职业和身体）：")
            appendLine(CharacterIdentityStore.identities.value[characterId].orEmpty())
            appendLine("本人稳定性格与思考方式：")
            appendLine(CharacterRuntime.personaConstraintSnapshot(characterId))
            CharacterLifeStore.state(characterId).optJSONObject("relationshipAgreements")
                ?.optJSONObject("user")?.let {
                    appendLine("与用户的原设关系：${it.optString("title")}；${it.optString("details")}")
                }
        }
        return Cast(characterId, character.displayName, material)
    }

    fun request(item: MiniTheaterItem): WritingRequest {
        val draft = if (item.rewriting) "" else item.text
        return WritingRequest(
            facts = buildString {
                appendLine("作品：${item.title}")
                appendLine("绑定角色：${item.characterName}（${item.characterId}）")
                appendLine(item.characterContext)
                appendLine("本篇幻想情节：${item.prompt}")
                appendLine("本篇性格侧面：${item.variationContext}")
                if (item.sceneContext.isNotBlank()) appendLine("用户补充：${item.sceneContext}")
                if (draft.isNotBlank()) appendLine("同一篇已经保存的正文（只续写）：\n$draft")
            },
            instructions = """
                为用户创作一篇“用户与绑定角色”的独立幻想恋爱番外，读起来有趣、心动、满足。
                这是虚构作品，不是角色本人发送的消息，不读取或编造双方已经真实发生过的聊天和共同经历。
                原设性格、价值、认知方式、明确性别与表达是人物辨认的基线；本篇奇幻前提可以真实改变故事里的世界、职业、大小或身体。
                身体互换必须真的互换身体，缩小必须影响具体行动，不能把大胆题材退化成换称呼或普通聊天。
                性格侧面放大只在本篇生效，保留其他面貌与核心辨识度，不把所有题材写成同一个男主或女主，不修改原角色。
                对话中以自然的你我交流；旁白可称用户或采用第二人称。姓名、称谓与性别以所给资料为准，身体互换时分清身体与意识身份。
                已成年的人物按成人的自主意愿相处；用户与角色有各自的主动和回应，亲密变化有具体缘由。
                一个完整场景或短篇，有环境、动作、对话与心理，情节大胆而因果连贯；不逐项展示人格标签，不写成解释性报告。
                约1000—1800字，充分展开一件事并收束；不要写大纲、后续预告或强制未完待续。若前文已有正文，只补后文，不重演开头。
                第一行写《篇名》，空行分段。末尾另起一行【小剧场已完】。只输出作品。
            """.trimIndent()
        )
    }
}
