package com.jiacimu.lulu.data

import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.CompanionContextMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.time.Instant

/** Incremental reflection is detached from real-time response and limited to one batch per hour. */
object CharacterDevelopmentRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var prefs: android.content.SharedPreferences? = null

    fun initialize(context: android.content.Context) {
        prefs = context.applicationContext.getSharedPreferences("lulu_development_reflection", android.content.Context.MODE_PRIVATE)
    }

    fun request(characterId: String) { scope.launch { mutex.withLock { reflect(characterId) } } }

    private suspend fun reflect(characterId: String) {
        val p = prefs ?: return
        val now = Instant.now().toEpochMilli()
        val persona = CharacterRuntime.personaConstraintSnapshot(characterId)
        val sameDefinition = p.getString("persona:$characterId", "") == persona
        if (sameDefinition && now - p.getLong("attempt:$characterId", 0) < 3_600_000) return
        val timeline = SharedExperienceTimeline.all(characterId)
        // Balance exposure SOURCES. Chat volume must never erase actual books,
        // games, world encounters and independently visited information sources.
        // These are real records, not invented offline history or quotas.
        val events = (timeline.filter { it.channel.startsWith("独自阅读") }.takeLast(10) +
            timeline.filter { it.channel.startsWith("独自游戏") }.takeLast(8) +
            timeline.filter { it.channel.startsWith("数字世界") }.takeLast(10) +
            timeline.filter { it.channel.startsWith("现实世界窗口") }.takeLast(8) +
            timeline.filter { it.isDevelopmentExposure() &&
                it.evidenceKind != EventEvidenceKind.UserStatement }.takeLast(10) +
            timeline.filter { it.evidenceKind == EventEvidenceKind.UserStatement }.takeLast(16) +
            timeline.filter { it.evidenceKind == EventEvidenceKind.CharacterStatement &&
                it.source !in setOf("journal:own", "moment:self") }.takeLast(16) +
            timeline.filter { it.evidenceKind == EventEvidenceKind.CharacterStatement &&
                it.source in setOf("journal:own", "moment:self") }.takeLast(8))
            .distinctBy { it.id }.sortedBy { it.occurredAt }
        if (events.count { it.isDevelopmentExposure() } < 3) return
        val fingerprint = persona + "\n" + events.joinToString("|") { "${it.id}:${it.revision}" }
        if (fingerprint == p.getString("batch:$characterId", "")) return
        p.edit().putLong("attempt:$characterId", now).putString("persona:$characterId", persona).commit()
        val reply = LuluAiServices.gateway.generate(characterId,
            facts = "锁定人设，不可改写：$persona\n当前已有成长：${CharacterRuntime.developmentContext(characterId)}\n" +
                events.joinToString("\n") {
                    "eventId=${it.id} revision=${it.revision} source=${it.source} channel=${it.channel} " +
                        "kind=${it.evidenceKind} ${it.evidenceContent.take(390)}"
                },
            instruction = """
                从角色真实经历中反思可改变的兴趣、偏好、习惯、判断，以及多次经历逐渐形成的叙事意义；绝不改写用户明确限定的人设字段。
                返回 JSON 数组，最多3项；没有可信变化就返回[]。每项含 disposition(upsert|retire)、slot(稳定主题键)、kind(Interest/Preference/Habit/ExpressionHabit/Judgment/SituationalPattern/NarrativeMeaning/RelationshipRoutine/VerifiedMethod)、content、evidenceIds、counterIds。upsert 表示形成/修订倾向；retire 只用于已有 slot 已被多次独立反例推翻，content 可沿用原描述，evidenceIds 可为空，但 counterIds 必须给出真实反例。
                【多来源的成长】可以分别从已实际阅读的小说和文本学习措辞与审美，从与真实群友的互动学习幽默、表达边界或相处方式，从独自游戏的成败中改变耐心和竞争心，从数字世界真正发生的偶遇、家具活动、事件结果形成处事习惯，从现实世界窗口实际看到并有来源的资料扩大知识与兴趣，也可以通过日记重新审视自己。每种来源只能影响它真实提供的方面。模型作者和书中人物的遭遇不是角色亲历，现实资讯也不代表角色到过那个地方。没有新可用经历时可以保持，不能因为几小时没上线就假装阅历增长。
                【成熟与阅历分开】数字生命从创建起可以拥有已设定的成熟判断、价值取向、边界感与成人情感能力，同时没有相应的亲历和发展出的语言习惯；这不意味着是儿童，也不能虚构童年、工作资历、社会身份。成长优先发生在具体文化接触、兴趣、感受、表达习惯、关系和自主选择上，不因学习新梗就把底层人格推翻。
                【模仿先于习惯】临时学对方拆气泡、语气词、打字方式是一种可能的社交试探，可表示亲近、玩笑甚至冒犯；一次模仿绝不是长期习惯。只有这个角色自己反复真实使用、得到真实反馈，且有多个不同来源/轮次的可信经历支持，才可以提出 ExpressionHabit。对方不喜欢某种模仿时不能继续把它当“可爱”强迫表演。
                【情境反应倾向，不是硬规则】若至少3个彼此独立的真实事件反复显示“在某类情境下，这个角色更容易出现某种理解/应对方式”，可提出 SituationalPattern。content 写成“当……时，通常更倾向……；但会受当时情绪、关系、目标和新事实影响”，不能写“遇到X必须Y”。不同情境可以表现出相反状态，这不叫人设崩坏；真正稳定的是跨时间可重复的条件性模式。一次吵架、一次撒娇、一次模型生成失误绝不能形成该模式。
                【个人表达与真实经历】标记为私人日记(journal:own)、自己朋友圈(moment:self)、CharacterStatement 的记录是角色确实写过或说过的话，可以证明当时有某种感受、判断或表达倾向，却绝不能单凭文字证明读了书、去了地点、玩了游戏或完成了补救。对成长的事实次数要求只能由 UserStatement/Observation/ToolResult/明确可核验的阅读及世界事件满足；日记和动态只是补充主观证据。即使作者写了十篇相同的心情，也不是十次实际经历。
                ExpressionHabit 是角色逐渐形成的个人语言习惯，而不是要求所有人模仿网感、统一说话风格。至少需要三段不同、可核实的实际交流/接触或执行经历，并有该角色在聊天/私人日记/朋友圈中真实表达的具体体现，且内容符合其原始人设；可以学会适合自己的语气词、标点节奏、幽默手法或熟悉的真实社交梗，也可以随着环境改变。不能因为用户使用了某个梗就默认角色会用，不从无来源的互联网杜撰当下热词。已存在相同主题的成长应沿用同一 slot。
                Interest 是角色自己的兴趣，不是用户的爱好：从真实阅读、游戏、探索、见面等实际接触，加上角色确实表达的感受，判断是否开始喜欢、加深、改变方向或逐渐失去兴趣。至少引用3次不同经历；不要因为角色说了一句喜欢就凭空创建兴趣，不假装体验过正文里的虚构情节。
                同一兴趣的变化沿用同一个slot。新的反复体验足以说明方向改变时，可以更新或淡化旧兴趣，counterIds保留旧依据；最新反例晚于支持证据时不更新。兴趣内容写清当前倾向与变化原因，不靠随机数或固定轮换。\n                NarrativeMeaning 是“这些真实经历逐渐对我意味着什么”的主观解释，不是新事实也不是人设改写。至少引用2个不同真实事件，最好跨不同时间点；可以形成对自己、关系、选择或未来的理解，但不能凭空补童年、职业履历、共同回忆或未发生的转折。单句漂亮话、一次情绪、一本小说角色的经历都不能直接成为叙事身份；有新反例时应更新原 slot 或暂不形成。
                长期习惯或方法也至少3次不同经历。VerifiedMethod 尤其严格：必须引用至少3条真实成功 ToolResult，并同时引用动作之后用户本人明确表示喜欢、开心、舒服、有用或希望下次继续的 UserStatement；“动作执行成功”只证明工具完成，不能单独推导“这样能让用户开心”。没有真实反馈就用 Preference/RelationshipRoutine 或保持未知，不要编造验证结论。形成后的倾向也不是永久标签：当后续出现反复、独立且更晚的反例时，对已有 slot 使用 disposition=retire，让程序按成熟度阈值决定是否撤回；不要因为一次反常表现就退役稳定倾向。所有ID必须来自输入；主观表达只证明感受，不证明行动成功。不强求每轮成长，经历不足时保持原状。
            """.trimIndent(),
            source = "增量成长反思", title = "角色成长", maxTokens = 1400,
            contextMode = CompanionContextMode.Isolated).getOrNull() ?: run {
                p.edit().putLong("attempt:$characterId", now - 3_300_000).commit()
                return
            }
        runCatching {
            val array = decodeMemoryResponseArray(reply.text)
            for (i in 0 until minOf(array.length(), 3)) {
                val item = array.getJSONObject(i)
                fun ids(key: String): List<String> {
                    val a = item.optJSONArray(key) ?: return emptyList()
                    return (0 until a.length()).map { a.getString(it) }
                }
                val slot = item.getString("slot")
                val disposition = item.optString("disposition", "upsert").lowercase()
                if (disposition == "retire") {
                    CharacterDevelopmentStore.retireSlotWithCounterEvidence(
                        characterId, slot, ids("counterIds"), persona,
                    )
                } else {
                    CharacterDevelopmentStore.applyProposal(characterId, slot,
                        DevelopmentKind.valueOf(item.getString("kind")), item.getString("content"),
                        ids("evidenceIds"), ids("counterIds"), persona)
                }
            }
            p.edit().putString("batch:$characterId", fingerprint).commit()
        }.onFailure { p.edit().putLong("attempt:$characterId", now - 3_300_000).commit() }
    }
}
