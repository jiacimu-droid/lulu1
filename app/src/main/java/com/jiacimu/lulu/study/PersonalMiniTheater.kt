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
    data class Idea(val id: String, val title: String, val premise: String,
        val recommendedWorldMode: String = "original")
    data class Variation(val id: String, val label: String, val direction: String)
    data class Cast(val characterId: String, val name: String, val material: String, val worldMode: String = "original")
    data class WritingRequest(val facts: String, val instructions: String)

    val ideas = listOf(
        Idea("swap", "醒来竟然交换了身体", "用户与角色真的交换了身体。外貌、声音与视角随身体交换，思想和记忆身份仍属于各自。清晨的第一个照面就不对劲，接下来的冒险与相处会因各自的选择而改变。"),
        Idea("thumb", "掌心里的拇指恋人", "角色突然缩成拇指大小，用户的掌心成了能够停留的地方。这个意外让原本习以为常的空间、动作和亲近方式变得全然不同；角色仍有自己的自尊、判断和魅力。"),
        Idea("giant", "一不小心变成巨人", "角色忽然变得比房屋还高，伸手便能碰到往日遥不可及的地方，却也发现寻常的相处突然变得困难。巨大的身体会真实改变周围的世界。"),
        Idea("truth", "藏不住心声的魔法", "一场只持续一夜的魔法，让角色某些原本藏着的心里话意外变得可听见。听到的未必是预期的答案，彼此会如何回应仍是未知。"),
        Idea("cat", "多出来的耳朵和尾巴", "角色突然长出会泄露情绪的耳朵和尾巴，本人仍是原来的成年人。身体比言语更早透露反应，这种意外会给彼此带来怎样的新发现？"),
        Idea("dream", "闯进对方的奇幻梦境", "用户与角色一起落入一个会把情绪具象化的梦境。梦里的景色不断回应他们没说出口的心情，却没有人知道出口在哪里。"),
        Idea("reverse", "兽世王城的最后一夜", "在兽人王城，你是被误认作预言祭司的外乡人，他是奉命看守你的禁卫。城里每个人都能听懂兽语，唯独一头逃走的幼兽只肯与你交流。它似乎知道一件谁也不愿提起的秘密。", "alternate"),
        Idea("echo", "碰一下就会共感", "奇幻世界里，两人接触时会短暂感受到彼此的某一种感官或情绪。一次无意的触碰，让熟悉的人忽然显得陌生，也可能带来全新的理解。"),
        Idea("tender-spell", "今天温柔得有点犯规", "一场只持续一天的魔法，让角色原本温柔体贴的一面格外明显，但他的其他性格仍然存在。寻常一天会因为这点微妙变化变成什么模样？"),
        Idea("fox-spell", "女尊朝堂的假婚约", "女尊王朝里，你是手握兵权的年轻女侯，他是与你订下婚约的朝臣。第一次共同出席宫宴，一封来历不明的密信悄悄打破了平静。", "alternate"),
        Idea("clingy-spell", "魔法学院的心声考试", "平行世界的魔法学院里，你与那位素来高冷的成年学长被分到同一场毕业试炼。考试让两人的魔力共享，也会在施法时听见彼此的念头。没人知道考核究竟想考验什么。", "alternate"),
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
            val temperamentEntries = CharacterProfileSchema.fields.filter {
                it.key in setOf("values", "motives", "enneagram", "traits", "attachment",
                    "cognitiveStyle", "perception", "conflict", "care", "respect", "social",
                    "expression", "speechHabits", "interests")
            }.mapNotNull { field ->
                profile?.optString(field.key)?.trim()?.takeIf(String::isNotBlank)
                    ?.let { "${field.label}：$it" }
            }
            val temperament = temperamentEntries.joinToString("\n")
            buildString {
                appendLine("角色姓名：${character.displayName}")
                native?.gender?.takeIf(String::isNotBlank)?.let { appendLine("人物性别：$it") }
                appendLine("【仅继承人格与亲密互动的行为倾向，不继承原世界身份、世界观或既定关系】")
                if (temperamentEntries.size < 3 && character.persona.isNotBlank()) {
                    // Few structured fields must not erase the user's authored core traits.
                    appendLine("人物性格概要（只提取个性，原身份与关系均不继承）：")
                    appendLine(character.persona)
                }
                if (temperament.isNotBlank()) {
                    appendLine("以下是人格与相处方式素材。凡提及原世界用户/主人/恋人/闺蜜或地点，只抽象出人物的意愿、反应和相处风格，不把这些具体关系、背景或称呼带进本篇：")
                    appendLine(temperament)
                }
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
                appendLine("本篇灵感（仅供开场与世界条件参考，不是剧情步骤表）：${item.prompt}")
                if (item.worldBookContext.isNotBlank()) {
                    appendLine(TheaterWorldBookContext.storyFactsFromSnapshot(item.worldBookContext))
                }
                if (item.variationContext.isNotBlank())
                    appendLine("旧版作品保留的补充设定：${item.variationContext}")
                if (item.sceneContext.isNotBlank()) appendLine("用户补充：${item.sceneContext}")
                if (draft.isNotBlank()) appendLine("同一篇已经保存的正文（只续写）：\n$draft")
            },
            instructions = """
                为用户创作一篇“用户与绑定角色”的独立番外短篇，重点是人物本身与这次故事的独特阅读体验。根据本篇灵感与用户要求选择主调，可以是心动、奇幻、冒险、悬疑、喜剧或温暖日常；不要强迫所有故事都走向告白、共同解谜或固定甜蜜结局。
                这是虚构作品，不是角色本人发送的消息，不读取或编造双方已经真实发生过的聊天和共同经历。
                人物的稳定性格、价值、认知方式与说话习惯是辨认基线。题材中的世界规则、身份和身体变化要真正影响剧情。
                【故事从灵感里自由生长】标题与灵感只给出世界条件、一个引人好奇的开场或矛盾，不是预先排好的剧情大纲。人物接下来做什么、如何化解或加深冲突、关系如何变化以及最终落点，都由你依据人物本身和现场因果独立创造。不要机械照着灵感里的例子逐步演，也不要将不同题材一律写成被困、合作、误会、告白的同一条线。相同灵感可以长出完全不同的作品；但必须兑现选中的世界规则与用户明确指定的设定。
                身体互换必须真的互换身体，缩小必须影响具体行动，不能把大胆题材退化成换称呼或普通聊天。
                性格侧面放大只在本篇生效，保留其他面貌与核心辨识度，不把所有题材写成同一个男主或女主，不修改原角色。
                【人物性格如何进入文学正文】
                人设里的形容、动物比喻、关系互动偏好，只是理解同一个人如何思考、行动和亲近他人的素材，不是人物的第二名字、固定身份或出场造型。
                例如“像小狐狸”表示狡黠、有主见、喜欢主动逗人；“像小狗”表示自愿信任、愿意亲近或偶尔交出主导。这些是可能的行为侧面，不是“撩人＝狐狸、示弱＝小狗”的剧情分类器。
                让性格在具体情境里通过本人做出的选择、停顿、对话、动作及回应自然显现；同一个人可以主动、柔软、矛盾、嘴硬或坦率，不需要解释他此刻切换了哪一种人格。
                没有明确动物化设定时，不要把人格比喻反复写成旁白的“狐狸/小狗”称号，也不要仅因性格侧面变化就凭空长出耳朵、尾巴或变成动物身体；真实的人类姿态、主动靠近、依偎、俯身或自愿臣服都可由当下互动自然发展。偶然的亲昵称呼或玩笑比喻可以存在，但不是固定口癖。
                如果本篇明确设有兽耳、尾巴、变形或兽人等幻想条件，则认真写出这些设定对身体和情节的真实影响；不要把确实的动物元素误当成需要删除的普通人格比喻。
                ${if (item.worldMode == "alternate") "【彻底架空】本篇与露露机原世界无关：双方可以从陌生人开始，拥有全新职业、身份、社会地位与关系；原世界的主人/闺蜜/恋人/数字生命、已有约定与经历均不继承。仅保留本人的性格、判断和说话习惯。若人格素材里提到原世界的主人、恋人或闺蜜等关系，仅保留由此体现的性格和亲密互动偏好（例如主导或顺从的倾向），不能把原称谓、对用户的具体承诺或原关系当成本篇事实。称谓与关系仅由本篇剧情产生，不要带回数字世界。" else "【原世界番外】保留已给出的原世界身份和原有关系，可以有本篇具体幻想变化；故事不写回真正的共同经历。"}
                对话自然；旁白可用第二人称。身体互换时分清身体与意识身份。
                已成年的人物按成人的自主意愿相处；用户与角色有各自的主动和回应，亲密变化有具体缘由。
                一个完整场景或短篇，有环境、动作、对话与心理，情节大胆而因果连贯；不逐项展示人格标签，不写成解释性报告。
                【手机阅读的叙事节奏】让段落跟随镜头、动作、人物反应与心理转折自然换气。通常一个旁白段落集中描写一个动作或一个连续感受，宜为手机上约1—3行（可参考约35—75个汉字），避免把多个动作、反应和心理推进挤成长篇整段；关键的一瞬可以独立成短段，必要的环境或高潮描写也允许更长。
                角色直接说出的对白通常独立成段，用引号明确标示，再以独立短段承接动作和细微反应。不要在一段长旁白中反复夹入多次对话，更不要逐句都套上“他说/你说”。分段为阅读与情绪服务，而不是硬按字数、逗号或每个句号切行；不要碎成诗行、流水账或气泡聊天。
                约1000—1800字，充分展开一件事并收束；不要写大纲、后续预告或强制未完待续。若前文已有正文，只补后文，不重演开头。
                第一行写《篇名》，正文用空行明确区分自然段与独立对白。末尾另起一行【小剧场已完】。只输出作品。
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
                你是一位擅长制造惊喜、情绪体验与戏剧转折的番外创意主编。只构思故事，不写正文。一次提出10个值得点开阅读的、真正不同的独立短篇提案。
                目标不是“生成十种设定”，而是让人看见每个题目就想知道：这两个人接下来会怎样？每条都要有可发生的事，而不只是世界标签、性格标签或角色扮演简介。
                【放开想象力】可彻底更换时代、宇宙、世界规则、物种、阶层、职业、权力结构和初识方式；也可以只改变一项能力、身体特征、身份秘密、一场具体事件或一段互动。
                例如魔法世界、兽人社会、女尊秩序、兽耳、读心术、冷淡的成年学长、童话、科幻、神怪、悬疑、赛博都市、日常喜剧，都只是开放可能性，绝不是十条要逐项打卡的菜单。尽管发明未列出的新玩法。
                【读者先于设定】先想本篇想带来的鲜明体验：心动、狡黠拉扯、甜蜜、好笑、惊险、反转、被照顾、并肩冒险、意外理解、怅然或满足。十条不必都恋爱升温，也不必都加魔法；一个没有超能力但互动精彩的故事同样合格。
                【让设定真正起作用】每条需要一个抓人的开场状况、能引发具体互动的机会，以及真正影响行动的世界规则、身份差异或悬念；它们是起点而非写好的步骤，让写作模型有发挥空间。不要只把普通聊天搬到王宫或魔法学院。
                【提案不是剧本】不替写作模型排好故事的步骤、必经冲突、情感发展、反转和结局。尤其不要十条都采用“发现秘密→共同解决→逐渐亲近”的套路。你负责提供值得探索的起点，真正的发展由正式写作时的角色行为自然决定。
                世界观、人物身份、特殊规则、关系处境、事件类型、情感节奏至少在多个维度拉开差异，避免十条都变成“被困—合作—发现秘密—逐渐亲近”；可以由冲突、误会、竞技、共同任务、愿望、救援、礼物、日常小事或一个戏剧场景直接驱动，不需要堆背景设定。
                【世界模式】为每条给出推荐 worldMode：original＝沿用原世界里的原身份和既定关系，只在本篇发生幻想事件；alternate＝完全架空，允许新的初识、身份、社会、关系和世界。尤其明确改了原身份或既定关系的提案，选 alternate。两种模式都欢迎，不设固定配额。
                人物可以在架空世界有全新身份，但稳定性格与独立判断仍需能在互动中辨认；不预设对方永远温柔、强势、撒娇或服从。用户与角色都能主动影响故事，不只等着一个人拯救另一个人。
                参考下面的已用提案和旧作品，不换皮重复旧核心机制、关键变故或关系结构。灵感完全虚构，不读取或编造真实聊天、记忆与私人资料。
                每条 premise 用65—105个汉字描绘有画面感的世界条件与开场悬念，别预写接下来的剧情路径、必然选择或结局。题名有辨识度，不要一律“某某的秘密”。十条总量控制在输出预算内。
                严格只输出 JSON 对象：{"ideas":[{"title":"简短而有画面感的中文题名","premise":"65—105字的具体故事提案","worldMode":"original 或 alternate"}]}。
                不要 markdown 代码块或解释。
            """.trimIndent()
        )

    /**
     * Models sometimes exhaust their output budget mid-array. A finished
     * top-level idea object is useful even if the surrounding array never
     * closed; never invent the unfinished final item or buy a retry.
     */
    fun parseGeneratedIdeas(raw: String): List<Idea> {
        val marker = Regex("\"ideas\"\\s*:\\s*\\[").find(raw)
            ?: throw IllegalArgumentException("模型没有返回灵感列表")
        val arrayStart = marker.range.last + 1
        val objects = mutableListOf<JSONObject>()
        var objectStart = -1
        var objectDepth = 0
        var insideString = false
        var escaped = false
        var arrayClosed = false
        var malformed = false

        for (i in arrayStart until raw.length) {
            val char = raw[i]
            if (insideString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> insideString = false
                }
                continue
            }
            when (char) {
                '"' -> insideString = true
                '{' -> {
                    if (objectDepth == 0) objectStart = i
                    objectDepth++
                }
                '}' -> {
                    if (objectDepth == 0) {
                        malformed = true
                        break
                    }
                    objectDepth--
                    if (objectDepth == 0) {
                        val value = runCatching {
                            JSONObject(raw.substring(objectStart, i + 1))
                        }.getOrNull()
                        if (value == null) {
                            malformed = true
                            break
                        }
                        objects += value
                        objectStart = -1
                    }
                }
                ']' -> if (objectDepth == 0) {
                    arrayClosed = true
                    break
                }
                ',' -> Unit
                else -> if (!char.isWhitespace() && objectDepth == 0) {
                    malformed = true
                    break
                }
            }
        }
        if (malformed || objects.isEmpty() || objects.size > 20 ||
            (arrayClosed && objectDepth != 0)) {
            throw IllegalArgumentException("这一批灵感格式不完整，请重新生成")
        }

        val ideas = objects.mapNotNull { row ->
            val title = row.optString("title").trim().take(70)
            val premise = row.optString("premise").trim().take(500)
            if (title.length < 2 || premise.length < 20) null
            else Idea("ai-" + UUID.randomUUID(), title, premise,
                row.optString("worldMode").takeIf { it == "original" || it == "alternate" } ?: "original")
        }.distinctBy { it.title.lowercase() }

        if (ideas.isEmpty()) throw IllegalArgumentException("这一批没有可用的新灵感")
        return ideas
    }

}
