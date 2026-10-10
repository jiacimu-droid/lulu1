package com.jiacimu.lulu.system

import android.content.Context
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelReply
import com.jiacimu.lulu.data.CharacterRuntime
import com.jiacimu.lulu.data.CharacterAddressPreference
import com.jiacimu.lulu.data.CharacterDefinitionSnapshot
import com.jiacimu.lulu.data.CompanionActionRuntime
import com.jiacimu.lulu.data.CompanionPresenceStore
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.LuluConversation
import com.jiacimu.lulu.data.LuluGroupMember
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.UnifiedMemoryOrchestrator
import com.jiacimu.lulu.data.UnifiedMemoryRequest
import com.jiacimu.lulu.data.UserProfileContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Plans one natural group-chat continuation with one model request.
 *
 * Members choose whether to speak during each generated round. A group is not a roll call:
 * quiet personalities may listen while another member returns to the conversation.
 */
internal object GroupEnsembleReplyEngine {
    private const val BubbleSeparator = "⟪BUBBLE⟫"
    private const val EndMarker = "⟪END⟫"

    private data class PlannedTurn(
        val characterId: String,
        val replyTo: String,
        val intent: String,
        val bubbles: List<String>,
        val quoteMessageId: String?,
        val favoriteMessageId: String?,
        val recallBubbleNumber: Int?,
        val pokeUser: Boolean,
        val statusText: String,
        val gesture: String,
        val innerThought: String,
        val mood: String,
        val tool: String,
        val args: JSONObject,
        val afterglow: JSONObject? = null,
        val innerLife: JSONObject? = null,
        val motiveId: String = "",
    )

    private data class CachedPlan(
        val turns: MutableList<PlannedTurn>,
        val memberLabels: Map<String, String>,
        val definitions: Map<String, CharacterDefinitionSnapshot>,
        val emotionalAnchor: String,
        val witnessedSpeakers: Set<String>,
        val sourceUserMessageId: String,
    )

    private val lock = Any()
    private val cachedPlans = linkedMapOf<String, CachedPlan>()

    suspend fun respondIfApplicable(
        context: Context,
        characterId: String,
        history: String,
        userText: String,
        title: String,
        archiveId: String?,
        sceneContext: String,
    ): Result<ModelReply>? {
        val conversation = resolveGroupConversation(sceneContext) ?: return null
        val group = conversation.groupChat ?: return null
        val messages = MigratedDomainStores.chat.messages(conversation.id).value
        val latestUserMessage = messages.lastOrNull { it.sender == LuluChatMessage.Sender.User } ?: return null
        val channel = if (sceneContext.contains("电话")) "call" else "chat"
        val planKey = "${conversation.id}:${latestUserMessage.id}:$channel"

        // Later members in the same generated group round reuse the cached plan even though a
        // character message has already been appended. Resolve the cache before recomputing the
        // current unanswered user turn.
        takeCachedTurn(context, planKey, characterId)?.let { return Result.success(it) }

        val lastCharacterIndex = messages.indexOfLast { it.sender == LuluChatMessage.Sender.Character }
        val actionableUserMessages = messages
            .drop(lastCharacterIndex + 1)
            .filter { it.sender == LuluChatMessage.Sender.User }
        val validUserMessageIds = actionableUserMessages.mapTo(mutableSetOf(), LuluChatMessage::id)

        val settings = MigratedDomainStores.characters.settings.value
        val validMembers = group.members.filter { member -> member.characterId in settings }
        if (validMembers.size < 2) {
            return Result.success(ModelReply(text = "群里现在没有足够的成员能接话。$EndMarker"))
        }
        if (validMembers.size > 8) {
            return Result.success(ModelReply(text = "这个群目前超过了 8 个角色，暂时无法稳定编排这一轮。$EndMarker"))
        }

        val currentSpeakerId = characterId.takeIf { requested ->
            validMembers.any { it.characterId == requested }
        } ?: validMembers.first().characterId
        val memberLabels = validMembers.associate { member ->
            val character = settings.getValue(member.characterId)
            member.characterId to member.groupNickname.ifBlank { character.displayName }
        }
        val mentionedIds = validMembers.filter { member ->
            val label = memberLabels.getValue(member.characterId)
            val displayName = settings.getValue(member.characterId).displayName
            latestUserMessage.content.contains("@$label", ignoreCase = true) ||
                latestUserMessage.content.contains("@$displayName", ignoreCase = true)
        }.map(LuluGroupMember::characterId)

        val memberCount = validMembers.size
        val replyLimit = group.maxAutoReplies.coerceAtLeast(memberCount).coerceIn(memberCount, 8)
        val connection = runCatching { LuluAiServices.connectionStore.resolveConnection(archiveId) }
            .getOrElse { error ->
                return Result.success(fallbackReply(currentSpeakerId, memberLabels, error.message))
            }
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val isCall = channel == "call"
        val userProfileContext = UserProfileContext.promptSection()
        val ensembleMemoryRequest = UnifiedMemoryRequest(
            currentInput = latestUserMessage.content,
            sceneContext = sceneContext,
            recentContext = history.takeLast(6_000),
            taskIntent = "延续当前群聊并让每个成员按自己的经历自然接话",
        )
        val memberMemoryContexts = buildMap {
            validMembers.forEach { member ->
                put(
                    member.characterId,
                    UnifiedMemoryOrchestrator.assemble(
                        characterId = member.characterId,
                        request = ensembleMemoryRequest,
                        recallLimit = 8,
                        evidenceLimit = 6,
                        evidenceCharacterBudget = 2_800,
                        recentCharacterBudget = 3_200,
                    ),
                )
            }
        }

        val definitions = validMembers.associate { it.characterId to CharacterRuntime.definition(it.characterId) }
        val generated = LuluAiServices.gateway.generate(
            characterId = currentSpeakerId,
            facts = buildString {
                appendLine("当前时间：${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now.atZone(zone))}")
                appendLine("当前真实场景：$sceneContext")
                appendLine("群聊名称：${group.name}")
                appendLine("用户在群里的称呼：${group.userGroupNickname}")
                if (userProfileContext.isNotBlank()) {
                    appendLine("【用户自己设定的资料上下文｜所有成员只能按这里真实存在的内容理解用户】")
                    appendLine(userProfileContext)
                }
                appendLine("本轮界面最初显示 characterId=$currentSpeakerId（${memberLabels[currentSpeakerId]}）正在考虑是否接话。她可以保持沉默；如果她不说而另一位想说，turns 从真正想说的那位开始，程序会移交发言权。")
                appendLine("群里共有 $memberCount 个角色，但不是点名报数；除了当前首发者，其他人只在按自身性格真正想说时才发言，也允许一直旁听。")
                appendLine("本轮最多允许 $replyLimit 个角色回合，可以只说一两轮，也可以自然延展。一个人可以再插话，而别人此刻完全不必发言。")
                appendLine("真实群成员集合：${validMembers.joinToString(",") { it.characterId }}。它只决定谁有资格说话，不要求每个人一定说。")
                if (mentionedIds.isNotEmpty()) appendLine("用户明确点名了：${mentionedIds.joinToString(",")}。被点名角色可以更积极地接话，但其他成员仍可以选择不发言。")
                if (actionableUserMessages.isNotEmpty()) {
                    appendLine("\n【本轮用户尚未被回复的真实消息｜数量跟随用户实际发送，不按固定条数截取】")
                    actionableUserMessages.forEach { item -> appendLine("消息ID=${item.id}；内容=${item.content.take(320)}") }
                    appendLine("这些消息同时构成本轮引用/收藏的唯一候选。已经在更早轮次回复完的旧消息不在候选中。")
                } else {
                    appendLine("用户刚刚在群里说：${latestUserMessage.content}")
                }
                if (history.isNotBlank()) {
                    appendLine("\n【当前群聊的局部接话记录｜只用于接住眼前群话题，不是角色的全部记忆】")
                    appendLine(history.takeLast(14_000))
                    appendLine("群聊如果中断过很久，不能把上一次群聊末尾误当成刚刚发生；角色在间隔期间的私聊、电话、游戏和其他真实经历以其个人原始时间线为准。")
                }
                appendLine("\n【群成员身份与各自原始时间线｜每个人只能继承自己真正经历过的事】")
                validMembers.forEach { member ->
                    val character = settings.getValue(member.characterId)
                    val label = memberLabels.getValue(member.characterId)
                    // Group chat has no separate memory silo. Each member is recalled through the
                    // same semantic -> raw-evidence -> recent-timeline dispatcher as private chat.
                    val memoryContext = memberMemoryContexts[member.characterId]
                    val presence = CompanionPresenceStore.current(member.characterId)
                    appendLine("---")
                    appendLine("characterId=${member.characterId}")
                    appendLine("显示名=${character.displayName}；群内称呼=$label")
                    appendLine(CharacterAddressPreference.promptSection(member.characterId))
                    appendLine(definitions.getValue(member.characterId).promptSection())
                    appendLine(CharacterRuntime.developmentContext(member.characterId))
                    memoryContext?.compactPromptSection(characterBudget = 4_200)
                        ?.takeIf(String::isNotBlank)
                        ?.let { appendLine(it) }
                    presence?.let { appendLine("上一刻状态=${it.statusText}；动作=${it.gesture}；心情=${it.mood}；没说出口=${it.innerThought}") }
                    appendLine(
                        CompanionActionRuntime.capabilityContext(
                            context = context,
                            characterId = member.characterId,
                            allowSleepReward = false,
                        ),
                    )
                }
                appendLine("\n【调用来源】这是群聊界面的一轮自然延续。成员是否发言取决于自己是否有话想说；可以一人独说、两人互怼或多人接龙，顺序由内容驱动。")
            },
            instruction = """
                你是多人群聊的整体编排器。每个成员有独立立场和意愿，可以接话、插话或保持沉默；绝不按名单轮班。
                文字群聊如果这一刻所有成员确实都不想说话，可只返回 {"action":"silent","reason":"各人此刻不发言的真实原因","turns":[]}；不要为了填满消息硬编气泡。电话仍需真实可念出的语音回应。

                只返回一个 JSON 对象，不要代码块、分析、旁白或额外说明：
                {"turns":[{"characterId":"真实角色ID","replyTo":"user|group|另一个真实角色ID","intent":"简短意图","bubbles":["群里真正说出的气泡"],"tool":"可选的露露机内动作名或空字符串","args":{},"quoteMessageId":"真实用户消息ID或空字符串","favoriteMessageId":"角色真心想收藏的真实用户消息ID或空字符串","recallBubbleNumber":0,"pokeUser":false,"statusText":"简短状态","gesture":"该角色此刻的微动作神态","innerThought":"这个角色自己的心声，不强制简短，可为空","mood":"简短心情"}]}

                规则：
                1. turns 第一项是这一刻实际愿意发言的成员，可以不是界面最初等待的成员；程序负责转交发言权。所有人都不愿意发言时，文字群聊使用明确的 silent 决策，不得编造开场白。
                2. 除首发者外，群成员可按人设和现实关系选择发言或旁听；沉默不是掉线或冷漠。同一个角色有真实动机时可以再次出现。
                3. 发言顺序不绑定成员列表，不默认 A→B→C。可以 A 一人发几句、A→C→A，或 A→B→C→B；是否插话只由当前话题与人物动机决定。
                4. 一个人可以在其他人还没发言时补发一句；不必等待其他人表态，也不必替缺席发言者补台词。
                5. turns 最少一轮、最多安全上限；不为填满上限强行续聊，也不为凑齐人数生成无意义的“我也接一句”。
                6. 后续角色应真正接住已经发生的内容：赞同、质疑、反驳、追问、补充、插话、玩笑、岔开或改口；不要每个人都从头回答用户同一个问题。
                7. 每个角色必须严格保持自己的身份、语言习惯、关系边界、称呼和性格差异。不要把所有人统一写成温柔助手，也不要让一个角色替另一个角色发言。
                ${com.jiacimu.lulu.data.spontaneousInnerVoiceGuide}
                8. 气泡多少、长短由这个角色的情绪与口语节奏决定：可能短促惊呼、停顿、突然补发、重复、欲言又止，也可能完整讲清一件事。bubbles 是一次次真正按下“发送”的内容。不要硬套一至四条或十至四十字的规格，也不要为显得热闹机械刷屏。真正心动、好笑或生气时允许有未经润饰的语气；但不得把内心独白、动作旁白、客服总结直接塞进聊天气泡。
                9. quoteMessageId 只能从“本轮用户尚未被回复的真实消息”中选择。用户这轮只发一条，就只有这一条候选；连续发几条，就都可以按内容自然选择。不要回头引用更早轮次已经回答完的旧消息。
                10. favoriteMessageId 同样只能从本轮尚未被回复的用户消息中选择，并且要在回应这一轮时当场决定。不要在后续新话题中突然回来补收藏已经回复完的旧消息。
                11. recallBubbleNumber 默认 0。只有极少数角色刚说出口就后悔、说漏嘴或想装作没说过的时刻才填真实序号。
                12. pokeUser 默认 false。只有这个角色此刻真的会自然戳一下用户时才设为 true。
                13. 不要虚构用户当前身体、环境或正在做的事情。只能依据用户刚说的话、群聊局部记录、角色身份与设定、用户设定资料和角色自己的真实原始时间线互动。
                14. 最后一轮不需要总结，不需要“把话题交给主人”，自然停住就可以。
                ${if (isCall) com.jiacimu.lulu.VoicePerformance.phoneInstruction(context).replace("电话的 text", "电话的 bubbles 中每条字符串") else ""}
                15. ${if (isCall) "这是实时群聊电话，quoteMessageId、favoriteMessageId 留空，recallBubbleNumber=0，pokeUser=false；语言必须更口语化、适合直接念出。" else "这是文字群聊，可以自然使用连续短气泡、引用、角色主观收藏，以及非常偶发的撤回或戳一戳。"}
                16. statusText、gesture、innerThought、mood 属于当前角色本人。内心可以是冲动、慌乱、暗喜、无语、突然冒粗口，也可以平静；外在未必全说出来，不能变成系统分析。若本轮用户真实消息强烈触动了此角色，可选填 afterglow:{"feeling":"第一拍心声","impulse":"尚未实施的冲动","holdHours":1到48的整数}；无强烈刺激不填。
                16a. 如角色确实从本轮群话语或已发生的同伴发言中产生新情绪、想调整愿望、或改变对真正说过话的同伴的看法，可为该 turns 对象可选 innerLife:{"emotion":{"feeling":"私人感受","cause":"真实缘由","otherFeeling":"并存感受","strength":1到4},"motives":[{"op":"start|revise|pause|resume|release","id":"已有动机ID","aim":"具体愿望","why":"原因","reason":"改变依据"}],"social":{"targetId":"user或本群真实已发言的角色ID","interpretation":"本人的主观理解","reason":"实际对话依据"},"selfCorrection":{"realization":"反省","nextTime":"下次做法"}}，没有新依据可不填。仅根据自己真实见过的对话，不能把别人的私聊当证据。每个角色内在生活彼此隔离。
                16a-补充. 遇到真正触动角色的群聊争执、友情变化或困难取舍，可在自己的 innerLife 写 thoughts:[{"thought":"一个未说出口的念头","impulse":"想做什么","hesitation":"顾虑"},{"thought":"可以和前一个矛盾的念头","impulse":"另一种冲动","hesitation":"为什么犹豫"}]，通常2—4条，内容必须依据本人真实目睹的群消息；它们不是已执行的行动，也不能强制所有角色都产生同一种想法。日常简单对话不必硬填。
                16b. 若执行 tool 真正用于自己既有的一个愿望，可选 motiveId:"已有动机ID"；真实执行结果将归入该愿望，而文字声称成功不算。
                17. 每个角色还可以在自己这一回合自主执行一个真实露露机内动作。尤其用户在群里问“谁想玩”或某个角色想私下找用户时，可以填写 tool=send_game_invite 或 send_private_message；该动作会真实进入这个角色与用户的私聊，不能把私聊内容又写进群气泡。也可按角色意愿发布朋友圈、写日记、读真实正文、跨到另一个所在群聊、在允许时发起来电、邀请进入数字世界或创建家具。没有自然动机时 tool 留空，严禁为了展示功能每轮都调用。用户明确要求某角色立即执行可用动作时，该角色可以按人设拒绝；一旦答应就必须填写对应 tool，不能只在气泡里口头声称成功。
                18. 群聊不是独立记忆空间。每个角色只有自己的那条原始时间线：私聊、群聊、电话、游戏和共同事件都按真实时间写在其中。群聊局部记录只负责“此刻怎么接话”，不能覆盖或替代个人时间线。
                19. 如果这个群隔了很久才重新说话，而某个角色在间隔期间和用户发生过新的私聊/电话/游戏经历，那么这些更晚发生的个人经历才是这个角色更近的状态；不能因为重新打开群聊就把很久以前的群话题当作刚刚发生。
                20. 私聊知识严格按角色隔离。A 与用户私聊里发生的事情可以让 A 在群里记得，但除非后来真实在群里说出、转发或通过其他共同事件让 B 知道，否则 B 不能凭空知道 A 的私聊内容。
            """.trimIndent(),
            source = if (isCall) "群聊电话·全员自然讨论" else "群聊·全员自然讨论",
            title = title,
            maxTokens = (700 + replyLimit * 300).coerceIn(1_100, 3_200),
            connectionOverride = connection,
            memoryRequest = ensembleMemoryRequest,
        )

        val baseReply = generated.getOrElse { error -> return Result.success(fallbackReply(currentSpeakerId, memberLabels, error.message)) }
        if (!isCall && com.jiacimu.lulu.data.CharacterDecisionProtocol.groupIsExplicitlySilent(baseReply.text)) {
            val decision = com.jiacimu.lulu.data.ModelStructuredOutput.objectOrNull(baseReply.text)
            com.jiacimu.lulu.data.CharacterInnerLifeStore.recordDecision(
                characterId = currentSpeakerId,
                decisionId = "group:silent:$planKey",
                selectedAction = "silent",
                reason = decision?.optString("reason").orEmpty(),
                chosenMotiveId = decision?.optString("motiveId").orEmpty(),
                alternatives = decision?.optJSONArray("alternatives"),
                outcome = "群聊当前没有人发言，无外部动作",
                succeeded = false,
                now = now,
            )
            return Result.success(baseReply.copy(text = "", disposition = "silent"))
        }
        val parsed = parseTurns(
            raw = baseReply.text,
            validMembers = validMembers,
            memberLabels = memberLabels,
            replyLimit = replyLimit,
            validUserMessageIds = if (isCall) emptySet() else validUserMessageIds,
            allowMessageActions = !isCall,
        )
        val completed = selectNaturalTurns(
            parsed = parsed,
            currentSpeakerId = currentSpeakerId,
            replyLimit = replyLimit,
        )

        synchronized(lock) {
            cachedPlans[planKey] = CachedPlan(completed.toMutableList(), memberLabels, definitions,
                "本轮群聊用户真实发言：${actionableUserMessages.joinToString("；") { it.content.take(150) }.ifBlank { latestUserMessage.content.take(180) }}",
                messages.filter { it.sender == LuluChatMessage.Sender.Character && it.status == LuluChatMessage.Status.Sent }
                    .takeLast(24).mapNotNull { it.authorCharacterId }.toSet(),
                latestUserMessage.id)
            while (cachedPlans.size > 24) cachedPlans.remove(cachedPlans.keys.first())
        }
        // The UI picked a potential first speaker, not someone obliged to speak.
        // If she abstained, hand over to the first person who actually chose to talk
        // without attributing another character's message to her.
        if (completed.isNotEmpty() && completed.none { it.characterId == currentSpeakerId }) {
            val nextLabel = memberLabels[completed.first().characterId].orEmpty()
            if (nextLabel.isNotBlank()) return Result.success(
                baseReply.copy(text = "⟪NEXT:$nextLabel⟫", disposition = "silent")
            )
        }
        return Result.success(
            takeCachedTurn(context, planKey, currentSpeakerId, baseReply)
                ?: fallbackReply(currentSpeakerId, memberLabels, "群聊编排没有返回有效内容"),
        )
    }

    private fun resolveGroupConversation(sceneContext: String): LuluConversation? {
        val groupName = Regex("群聊《([^》]+)》").find(sceneContext)?.groupValues?.getOrNull(1)?.trim() ?: return null
        return MigratedDomainStores.chat.conversations.value
            .asSequence()
            .filter { conversation -> conversation.groupChat?.name == groupName }
            .maxByOrNull(LuluConversation::updatedAt)
    }

    private suspend fun takeCachedTurn(
        context: Context,
        planKey: String,
        requestedCharacterId: String,
        tokenSource: ModelReply? = null,
    ): ModelReply? {
        val served = synchronized(lock) {
            val cached = cachedPlans[planKey] ?: return@synchronized null
            if (cached.definitions.any { (id, definition) -> !CharacterRuntime.definition(id).hasSameConfiguration(definition) }) {
                cachedPlans.remove(planKey)
                return@synchronized null
            }
            val requestedIndex = cached.turns.indexOfFirst { it.characterId == requestedCharacterId }
            val index = if (requestedIndex >= 0) requestedIndex else 0
            val turn = cached.turns.removeAt(index)
            val next = cached.turns.firstOrNull()
            val nextLabel = next?.let { cached.memberLabels[it.characterId] }
            if (cached.turns.isEmpty()) cachedPlans.remove(planKey)
            ServedTurn(turn, nextLabel, cached.emotionalAnchor, cached.witnessedSpeakers, cached.sourceUserMessageId)
        } ?: return null

        com.jiacimu.lulu.data.CharacterLifeStore.recordAfterglow(
            served.turn.characterId, served.emotionalAnchor, served.turn.afterglow)
        com.jiacimu.lulu.data.CharacterInnerLifeStore.observe(
            served.turn.characterId, "${served.sourceUserMessageId}:group:${served.turn.characterId}",
            served.emotionalAnchor, com.jiacimu.lulu.data.CharacterInnerLifeStore.withAfterglow(served.turn.innerLife, served.turn.afterglow, served.emotionalAnchor),
            served.witnessedSpeakers.filterNot { it == served.turn.characterId }.toSet() + "user",
        )
        com.jiacimu.lulu.data.CharacterInnerLifeStore.recordInnerVoice(
            served.turn.characterId, "${served.sourceUserMessageId}:group:${served.turn.characterId}",
            served.turn.innerThought,
        )
        CompanionPresenceStore.update(
            characterId = served.turn.characterId,
            statusText = served.turn.statusText,
            gesture = served.turn.gesture,
            innerThought = served.turn.innerThought,
            mood = served.turn.mood,
            source = "群聊·全员自然讨论",
        )
        if (served.turn.tool.isNotBlank()) {
            val toolResult = CompanionActionRuntime.execute(
                context = context,
                characterId = served.turn.characterId,
                action = served.turn.tool,
                args = served.turn.args,
            )
            com.jiacimu.lulu.data.CharacterInnerLifeStore.recordActionResult(
                served.turn.characterId, served.turn.motiveId,
                "group-tool:${planKey}:${served.turn.tool}",
                served.turn.tool, toolResult.success, toolResult.summary,
            )
        }
        val marker = served.nextLabel?.let { "⟪NEXT:$it⟫" } ?: EndMarker
        val quote = served.turn.quoteMessageId?.let { "⟪QUOTE:$it⟫" }.orEmpty()
        val favorite = served.turn.favoriteMessageId?.let { "⟪FAVORITE:$it⟫" }.orEmpty()
        val recall = served.turn.recallBubbleNumber?.let { "⟪RECALL:$it⟫" }.orEmpty()
        val poke = if (served.turn.pokeUser) "⟪POKE_USER⟫" else ""
        val text = quote + favorite + recall + poke + served.turn.bubbles.joinToString(BubbleSeparator) + marker
        return ModelReply(
            text = text,
            inputTokens = tokenSource?.inputTokens ?: 0,
            outputTokens = tokenSource?.outputTokens ?: 0,
            cachedTokens = tokenSource?.cachedTokens ?: 0,
        )
    }

    private data class ServedTurn(val turn: PlannedTurn, val nextLabel: String?, val emotionalAnchor: String, val witnessedSpeakers: Set<String>, val sourceUserMessageId: String)

    private fun parseTurns(
        raw: String,
        validMembers: List<LuluGroupMember>,
        memberLabels: Map<String, String>,
        replyLimit: Int,
        validUserMessageIds: Set<String>,
        allowMessageActions: Boolean,
    ): List<PlannedTurn> {
        val displayNames = MigratedDomainStores.characters.settings.value.mapValues { it.value.displayName }
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        return runCatching {
            val json = JSONObject(cleaned.substring(start, end + 1))
            val array = json.optJSONArray("turns") ?: json.optJSONArray("messages") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    if (size >= replyLimit) break
                    val item = array.optJSONObject(index) ?: continue
                    val rawSpeaker = item.optString("characterId").ifBlank { item.optString("speaker") }.ifBlank { item.optString("name") }
                    val resolvedId = resolveSpeakerId(rawSpeaker, validMembers, memberLabels, displayNames) ?: continue
                    val rawBubbles = buildList {
                        val bubblesArray = item.optJSONArray("bubbles")
                        if (bubblesArray != null) {
                            for (bubbleIndex in 0 until bubblesArray.length()) add(bubblesArray.optString(bubbleIndex))
                        } else add(item.optString("text").ifBlank { item.optString("content") })
                    }
                    val bubbles = normalizeBubbles(rawBubbles)
                    if (bubbles.isEmpty()) continue
                    val requestedQuoteId = item.optString("quoteMessageId").trim()
                    val requestedFavoriteId = item.optString("favoriteMessageId").trim()
                    val requestedRecall = item.optInt("recallBubbleNumber", 0)
                    add(
                        PlannedTurn(
                            characterId = resolvedId,
                            replyTo = item.optString("replyTo").ifBlank { "group" }.take(100),
                            intent = item.optString("intent").take(80),
                            bubbles = bubbles,
                            quoteMessageId = requestedQuoteId.takeIf { allowMessageActions && it in validUserMessageIds },
                            favoriteMessageId = requestedFavoriteId.takeIf { allowMessageActions && it in validUserMessageIds },
                            recallBubbleNumber = requestedRecall.takeIf { allowMessageActions && it in 1..bubbles.size },
                            pokeUser = allowMessageActions && item.optBoolean("pokeUser", false),
                            statusText = item.optString("statusText").ifBlank { item.optString("status") }.take(80),
                            gesture = item.optString("gesture").ifBlank { item.optString("actionDescription") }.take(160),
                            innerThought = item.optString("innerThought").ifBlank { item.optString("inner_voice") }.take(220),
                            mood = item.optString("mood").take(60),
                            tool = item.optString("tool").trim().takeIf { requested ->
                                requested in setOf(
                                    "send_private_message", "send_group_message", "send_game_invite",
                                    "send_world_invite", "publish_moment", "write_journal", "read_book",
                                    "start_call", "digital_world_action",
                                )
                            }.orEmpty(),
                            args = item.optJSONObject("args") ?: JSONObject(),
                            afterglow = item.optJSONObject("afterglow"),
                            innerLife = item.optJSONObject("innerLife"),
                            motiveId = item.optString("motiveId"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun selectNaturalTurns(
        parsed: List<PlannedTurn>,
        currentSpeakerId: String,
        replyLimit: Int,
    ): List<PlannedTurn> {
        val result = parsed.take(replyLimit).toMutableList()
        // Never force the suggested first speaker to say something she never chose.
        // The caller will hand off the turn to the first willing speaker.
        val firstIndex = result.indexOfFirst { it.characterId == currentSpeakerId }
        if (firstIndex > 0) result.add(0, result.removeAt(firstIndex))
        return result.take(replyLimit)
    }

    private fun resolveSpeakerId(
        raw: String,
        validMembers: List<LuluGroupMember>,
        memberLabels: Map<String, String>,
        displayNames: Map<String, String>,
    ): String? {
        val clean = raw.trim()
        if (clean.isBlank()) return null
        return validMembers.firstOrNull { member ->
            val label = memberLabels[member.characterId].orEmpty()
            val displayName = displayNames[member.characterId].orEmpty()
            clean == member.characterId || clean.equals(label, ignoreCase = true) || clean.equals(displayName, ignoreCase = true)
        }?.characterId
    }

    private fun normalizeBubbles(values: List<String>): List<String> = values.flatMap { value ->
        value.replace("\r\n", "\n")
            .split(BubbleSeparator)
            .map { part -> part.lines().joinToString(" ") { line -> line.trim() }.trim().trim('"', '“', '”') }
    }.filter(String::isNotBlank)

    private fun fallbackReply(characterId: String, labels: Map<String, String>, reason: String?): ModelReply {
        val label = labels[characterId].orEmpty()
        val text = when {
            reason.isNullOrBlank() -> "我刚刚一下没接住……你再说一次？"
            label.isBlank() -> "我这边刚刚卡了一下，你再说一次？"
            else -> "$label 刚刚卡了一下……你再说一次？"
        }
        return ModelReply(text = text + EndMarker)
    }
}
