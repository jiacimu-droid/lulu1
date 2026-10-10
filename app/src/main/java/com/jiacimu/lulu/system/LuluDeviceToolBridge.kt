package com.jiacimu.lulu.system

import android.Manifest
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelReply
import com.jiacimu.lulu.data.CompanionPresenceStore
import com.jiacimu.lulu.data.CompanionActionRuntime
import com.jiacimu.lulu.data.CharacterDecisionProtocol
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.UnifiedMemoryRequest
import com.jiacimu.lulu.health.HealthRolePerception
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object LuluDeviceToolBridge {
    private var context: Context? = null

    fun initialize(appContext: Context) {
        context = appContext.applicationContext
        LuluAlarmSystem.initialize(appContext)
    }

    suspend fun respond(
        characterId: String,
        history: String,
        userText: String,
        title: String,
        archiveId: String? = null,
        sceneContext: String = "正在和用户进行文字聊天。",
        onReplyStream: ((String) -> Unit)? = null,
        onCharacterHangup: (() -> Unit)? = null,
        silenceObservationId: String = "",
        turnContext: String = "",
    ): Result<ModelReply> {
        val callSilence = silenceObservationId.isNotBlank() && sceneContext.contains("电话")
        val appContext = context ?: return Result.failure(IllegalStateException("手机能力尚未初始化"))
        GroupEnsembleReplyEngine.respondIfApplicable(
            context = appContext,
            characterId = characterId,
            history = history,
            userText = userText,
            title = title,
            archiveId = archiveId,
            sceneContext = sceneContext,
        )?.let { return it }

        val connection = runCatching { LuluAiServices.connectionStore.resolveConnection(archiveId) }
            .getOrElse { return Result.failure(it) }
        val character = MigratedDomainStores.characters.get(characterId)
        val previousPresence = CompanionPresenceStore.current(characterId)
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val interactionKey = "direct:user"
        val interactionContext = com.jiacimu.lulu.data.CharacterInnerLifeStore.interactionContext(characterId, interactionKey, now)
        val groundingEvidenceId = if (callSilence) silenceObservationId else
            com.jiacimu.lulu.data.SharedExperienceTimeline.recentEvents(characterId, 40)
                .filter {
                    userText.isNotBlank() &&
                        it.evidenceKind == com.jiacimu.lulu.data.EventEvidenceKind.UserStatement &&
                        it.content.isNotBlank() &&
                        (userText.contains(it.content.trim().take(60)) ||
                            it.content.contains(userText.trim().take(60)))
                }
                .lastOrNull()?.id.orEmpty()
        if (groundingEvidenceId.isNotBlank()) {
            com.jiacimu.lulu.data.ConversationGroundingEngine.beforeTurn(
                characterId, interactionKey, groundingEvidenceId, userText,
            )
        }
        val groundingContext = com.jiacimu.lulu.data.ConversationGroundingEngine.context(
            characterId, interactionKey,
        )
        // Chat does not suspend the digital world. Its persisted hourly slot prevents
        // event rerolls from rapid messages or retries.
        val inWorldMoment = if ((!sceneContext.contains("电话") || callSilence) &&
            com.jiacimu.lulu.data.DigitalLifeProfileStore.isEnabled(characterId)) {
            com.jiacimu.lulu.data.DigitalWorldLifeEventStore.tick(
                appContext, characterId, now
            )
        } else null
        HealthRolePerception.initialize(appContext)
        HealthRolePerception.recordLatestSleep(characterId)
        val deviceContext = com.jiacimu.lulu.data.UserDevicePerception.context(appContext, characterId, now)
        val observedWorld = com.jiacimu.lulu.data.CharacterPerceptionContext.pending(appContext, characterId, now)
        val companionActionContext = CompanionActionRuntime.capabilityContext(appContext, characterId, includeWorldContext = false) + "\n" + com.jiacimu.lulu.data.CapabilityRegistry.context(appContext, characterId)
        val initiativeContext = com.jiacimu.lulu.data.CharacterInitiativeRuntime.context(characterId, userText)
        val continuityContext = com.jiacimu.lulu.data.CharacterContinuityRuntime.context(characterId, userText, now)
        val onlineChatBubbleRule = if (sceneContext.contains("电话")) "" else """
            【即时通讯中的表达：先想说什么，再决定发多少】
            - 私聊或群聊是面对一个真实对象接话，不是写散文、做情绪分析或完成关系宣言。先接住这一轮真正新增的事情；对方只提出一个简单需求时，无须自行扩写一整段安慰、环境描写或联想。
            - 角色可以很健谈，也可以只说一两个字；关键是每段新增内容都有此刻真正想表达的意思。已经回应好、没有新的意图时直接停，别为了显得贴心又重复同一态度、重新总结、填充比喻或凭空扩展场景。
            - 有审美不等于平时说话都像小说。幽默、修辞、暧昧、感叹、停顿都可以自然出现，但应贴合个人口吻与现场；不要突然借用无关的开发者、模型、后台或设备术语来营造奇怪的比喻。真正在讨论这些技术时可以正常提及。
            - 轻松时可以随性、跑题、开玩笑、改口或只回一部分，认真议事时可以充分解释；不按固定字数、气泡数或固定情绪比例表演。别把近期自己生成的长篇文风误认为稳定人格。
            - bubbles 中只放真的发给对方看的话；心情、心理活动与动作留在结构化状态里，不写舞台旁白。发送前自然收束：如果下一句只是在修饰上一句而不推进交流，可以不发。
            - 如果本人决定逗人开心、示好、开个玩笑、给一句亲昵回应，必须让对方真正收到一件有内容、有趣味或有温度的小互动，而不只是宣布“你说要我干嘛我都做”。可用语言、拟声、表情或自然的虚拟动作意象表达；绝不假装已经完成现实身体接触或数字世界操作。是否这么做、怎样做由关系和当下愿望决定，而非套用固定模板。
            - 标点、颜文字、emoji 和已经收藏的表情包都是个人表达的自然组成部分。认真、生气、得意、无语、撒娇、犯困时的节奏可以不同，但不规定频率、也不要每句都塞颜文字。没有合适的图就自然只发文字。
            - 一个气泡通常只承担一个局部互动动作。气泡边界只能通过 JSON 的 bubbles 数组表达，不要在任何正文里输出 BUBBLE、分隔符或解释控制协议。普通换行只是气泡内排版，不代表发送。
            - 若上下文有真实用户消息ID，确实想引用时在 text 开头用 ⟪QUOTE:消息ID⟫；只回应新消息且指代明确时不必引用。只允许引用明确给出的真实ID。
            - 只有本人真的很想长期留住某条用户消息时才在 text 开头用 ⟪FAVORITE:消息ID⟫。收藏不是点赞，不必为了展示能力频繁触发；ID同样必须真实。引用和收藏可以同时出现，也可以都不出现。
        """.trimIndent()
        val voicePerformanceRule = if (sceneContext.contains("电话"))
            com.jiacimu.lulu.VoicePerformance.phoneInstruction(appContext,
                sleepMode = sceneContext.contains("哄睡")) else ""
        val characterHangupRule = if (sceneContext.contains("电话") && onCharacterHangup != null) """
            【角色可以真的主动挂断】
            只有当前角色发自内心想结束这通电话时，才在 action=reply 的 JSON 中额外返回 "endCall":true。
            可以因为困倦、自然聊完、有已知的其他事情、吵架想冷静等选择离开；也可以舍不得挂。不要随机挂断、机械挂断、编造紧急事故或陌生人来电。
            text 应是确实要说的最后一句口语，可自然道别、说明想法或设立边界；结束语实际播放完毕后由电话系统真正挂断并记录。没有想挂断就省略该字段。
            不要把“我先挂了”当成完成挂断的证明，必须设置 endCall=true。只有发言才可申请结束，不输出无声终止的空 text。
        """.trimIndent() else ""
        val separateExpression = CharacterDecisionProtocol.usesSeparateExpression(sceneContext)
        val programDialogueConstraint = if (separateExpression)
            com.jiacimu.lulu.data.DialogueMoveEngine.plannerConstraint(userText) else ""
        val phoneConversationConstraint = if (!separateExpression && userText.isNotBlank()) buildString {
            appendLine(com.jiacimu.lulu.data.TransientConversationStyle.context(userText, history))
            if (com.jiacimu.lulu.data.DialogueMoveEngine.userInitiatesRepair(userText)) {
                appendLine("【电话中的理解修复】对方正在指出你刚才没听懂。先局部修正理解，不要把这一拍变成长篇道歉、多个猜测、撒娇或连续追问；没有高把握时只做最小澄清。")
            }
            val naturalnessCue = com.jiacimu.lulu.data.CharacterInitiativeRuntime.detect(userText)
            if (naturalnessCue != null) {
                appendLine("对方已经显露出可能值得回应的需要，不要用“那我该做什么/你想让我做什么”把自己的判断责任原样退回去；按本人性格先作一个自然的小判断或回应。")
            }
        }.trim() else ""
        val decisionFormatRule = if (callSilence) """
            这是电话中没有新增用户发言的自主观察，可选择 silent 或 reply；不允许工具动作。
            安静陪伴返回 {"action":"silent","reason":"此刻选择安静的个人原因","statusText":"持续处境","gesture":"自己的动作","innerThought":"未说出口的念头","mood":"当前感受"}。
            真想说话才返回 {"action":"reply","text":"自然可朗读的口语"}，不能把空回复、结构化状态或心声读出来。
        """.trimIndent() else if (separateExpression) """
            只返回完整 JSON，先自主选择 action=reply、tool 或 silent。选择 reply 时写 speechIntent 和 dialogueMove；speechIntent 是“具体想让对方知道/确认的内容与事实边界”，不是台词设计稿，不要提前写 text。
            想发一张真正的表情包时，仅从上面程序列出的已入库 stickerId 选填 stickerId；可以只发表情，但那时也要把 action 选为 reply 并在 speechIntent 中说明这一张表情想表达的感觉。不准猜 ID，也不用每次都配一张。
            dialogueMove={"type":"acknowledge|answer|ask|backchannel|self_repair|other_initiated_repair|candidate_understanding|disagree|tease|reassure|topic_shift|defer|decline|close|share","repairFormat":"none|open|candidate","target":"当前局部互动目标","candidate":"只有候选修复时才写一个候选","confidence":0到1,"contentIntent":"本轮内容意图","maxBubbles":1到3}。
            speechIntent 不要预先指定昵称、比喻、梗、反问句式或完整的表演台词，这些由表达层现场决定。但如果角色已经真心决定尝试一个具体而温和的社交小动作，可单独选填 socialAttempt="这次我打算怎样主动示好/逗她/靠近她"；这里写的是角色自选的行动意图，不是对方下达的任务。表达层应真的用文字、拟声或语言实现虚拟互动，不得虚构现实中已经碰到用户。
            回复例：{"action":"reply","speechIntent":"承认刚才理解偏了，让她补充真正意思","dialogueMove":{"type":"other_initiated_repair","repairFormat":"open","target":"刚才的误解","contentIntent":"做最小修复","maxBubbles":1},"reason":"先恢复共同理解"}
            沉默例：{"action":"silent","reason":"现在心情复杂不想回复","innerThought":"我得自己消化一下","innerThoughtBasis":{"focus":"用户刚才明确拒绝了邀约","change":"从期待转为需要自己消化失落","unsaidWhy":"现在不想把情绪压给用户"}}
            工具动作仍必须按下方的真实工具协议返回 tool 与 args；没有回执就不能假装成功。
            角色有自己的立场，可以反问、换话题或暂时不回复；被问到紧急、重要的事情时要考虑实际影响，不机械冷处理。
        """.trimIndent() else """
            这是电话的低延迟通道，必须给可以直接念出的 text，不能返回 silent 或仅有 speechIntent 的空发言。
            {"action":"reply","text":"自然的电话口语","statusText":"持续状态","gesture":"神态","innerThought":"未说出的念头","mood":"心情"}
        """.trimIndent()
        val disputeNeedsReview = com.jiacimu.lulu.data.CharacterAccountabilityContext.isUnmetPromiseChallenge(userText)
        val planner = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = buildString {
                appendLine("当前时间：${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now.atZone(zone))}")
                appendLine("当前时区：${zone.id}")
                appendLine("当前真实互动场景：$sceneContext")
                inWorldMoment?.let { event ->
                    appendLine("【程序刚记录的本轮数字世界小事】${event.summary}")
                    appendLine("角色可按自身意愿回应、主动分享或不提；不能伪造后续结果。")
                }
                appendLine("【用户现实设备与状态｜属于用户，缺失数据不得猜测】\n$deviceContext")
                appendLine(com.jiacimu.lulu.data.CharacterPerceptionContext.render(observedWorld))
                if (history.isNotBlank()) appendLine("最近对话（这是已经发生完的连续过程，用来确定你此刻站在什么状态上）：\n$history")
                if (continuityContext.isNotBlank()) appendLine(continuityContext)
                if (interactionContext.isNotBlank()) appendLine(interactionContext)
                appendLine(com.jiacimu.lulu.data.CharacterInnerLifeStore.compactContext(characterId, now))
                if (groundingContext.isNotBlank()) appendLine(groundingContext)
                if (initiativeContext.isNotBlank()) appendLine(initiativeContext)
                if (!sceneContext.contains("电话")) appendLine(com.jiacimu.lulu.StickerLibraryStore.prompt(appContext))
                if (turnContext.isNotBlank()) {
                    appendLine("【系统提供的本轮交互元信息｜不是用户说的话，不得归因给用户】")
                    appendLine(turnContext.take(4_000))
                }
                previousPresence?.let { presence ->
                    appendLine("角色上一刻状态：${presence.statusText}；动作：${presence.gesture}；心情：${presence.mood}；没说出口：${presence.innerThought}")
                }
                appendLine("这一刻用户新增的消息：$userText")
                appendLine(com.jiacimu.lulu.data.CharacterAccountabilityContext.prompt(characterId, now))
                appendLine(com.jiacimu.lulu.data.CharacterAccountabilityContext.challengeGuidance(userText))
            },
            instruction = """
                你正在以当前角色自己的立场做这轮决定，而不是担任无条件回答问题的助手。
                ${CharacterDecisionProtocol.principles}
                $decisionFormatRule
                $programDialogueConstraint
                $phoneConversationConstraint

                【主观生活与取舍】
                以已有性格、真实经历、关系和未完成愿望判断本轮刺激；可以快乐、好奇、不满、矛盾、犹豫、提出反问，也可以保持安静。不要统一成温柔安慰或每轮自我剖析。
                发生真实变化才填写 innerLife；不变时完全省略：
                innerLife={"emotion":{"feeling":"感受","cause":"真实触发","otherFeeling":"并存感受","impulse":"冲动","restraint":"克制","strength":1到4,"halfLifeMinutes":30到1440},
                "motives":[{"op":"start|revise|pause|resume|release","id":"已有ID","aim":"愿望","why":"原因","priority":1到3,"reason":"变化依据"}],
                "social":{"targetId":"user","interpretation":"对用户的主观看法","reason":"实际依据","dimensions":{"trust":"up|down|same","warmth":"up|down|same","ease":"up|down|same","friction":"up|down|same","boundarySafety":"up|down|same"}},
                "selfCorrection":{"realization":"新认识","nextTime":"打算修正的做法"},
                "thoughts":[{"thought":"未说出的想法","impulse":"冲动","hesitation":"顾虑"}]}。
                social.dimensions 只表示“这次真实互动让某个关系维度往哪边动了一点”，不是绝对好感分；没有明确证据就省略，单次变化不能定型整段关系。\n                心声与动作不是事实或承诺；冲突想法可以并存，后续需依据实际反馈改变。可选 afterglow={"feeling":"余波","impulse":"克制或行动冲动","holdHours":1到48}；
                可选 intention={"aim":"持续目标","motive":"个人原因"}；调整/放下时用现存 id、disposition=update|release、reason，不要无凭据重建。若真实动作推进了旧愿望可写 motiveId，结果只由执行回执确认。

                【已有真实手机能力】
                调用格式：{"action":"tool","tool":"能力名","args":{...}}。读取状态：get_battery、get_location、get_current_app、read_recent_notifications(args.limit)、
                read_screen；闹钟：create_alarm(args.triggerAt=带时区ISO时间,args.label)、list_alarms、cancel_alarm(args.id)；
                界面操作：screen_action(args.name=back|home|recents|notifications|quick_settings)、click_text(args.text)。
                位置只用工具返回的 readableAddress；定位老旧/模糊要说明，不能猜具体建筑。
                需要闹钟/操作/设备实时状态时调用工具，不假装完成；日期不明时追问，禁止自行猜时间。
                屏幕操作必须有用户明确授权，一次仅执行一个动作。当前已经在私聊/群聊时直接 reply，不要重复调用向当前会话发送同样的话。
                $companionActionContext

                【生活、社交与责任】
                角色可按自己的动机主动来电、邀约、发朋友圈、写日记、阅读、游戏、修改私人备注或网名；拒绝、暂缓或不打扰也合理。不为展示工具而机械执行。真实动作先执行再描述结果；失败不能冒充成功。
                用户表达疲惫、难受、开心、无聊或想靠近时，先把它当作“可能值得主动回应的需要线索”，不是命令。
                颜文字、emoji、标点、停顿和经过用户挑选入库的表情包可以独立传达你的个人感受、幽默和态度。让表达贴合你自己的习惯和这轮语境，不做固定频率的自动贴图，不把每一句都写成客服话术。若程序提供了主动性机会，优先结合已验证方法、关系习惯和真实记忆自主选一个最自然的小动作；没有可靠方法也可以只做低风险的小回应。除非真的缺少执行所必需的信息，不要反问“那我该做什么/你想让我怎么办”。
                【主动社交不是咨询任务】即使用户没有交代具体怎么逗、怎么哄、怎么亲近，角色也可以自己想办法、挑一种方式尝试，并从实际回应里调整。选中具体做法后要真正通过本场景的 reply/已授权工具付诸实施，而不是长篇描述自己“愿意做”；一个低风险、合时宜的小尝试比空泛的万能承诺更有人味。若用户已明确不愿被打扰或需要休息，则可不打扰并继续自己的生活。
                用户没新发言也可能构成有意味的互动空白；先考虑多个解释和自己的情绪，再决定是否开口。把真实的犹豫和克制存在内在状态，不向用户直播心理分析，也不让没有证据的担忧升级为事实。
                跨到其他群/私聊发送的内容必须进入对应真实会话；群聊不泄露私聊内容。
                用户明确要求一项已有能力时可以拒绝；只要答应现在做，就必须实际调用对应动作。数字世界家具的增改删须走 digital_world_action。
                用户健康/睡眠资料属于用户本人，最新真实同步优先；过期时说明不确定，不要求用户重复填已同步的数据。
                睡眠奖励仅在当前角色有 grant_sleep_reward 授权时使用；未实际发放不能说已到账。
                新的长期约定必须明确必要的时间、条件与权限。自己说“我会提醒/叫醒”会留下责任并需要执行，不把心愿当承诺，也不拿日记或普通聊天冒充真正来电。
                关系随经历缓慢改变，可继续介意、牵挂、好奇，不能凭一句好话就剧烈升温，也不能把他人的真实意图当已知事实。
                状态字段只在本轮确实更新时填写：gesture 为可见动作，statusText 为持续处境，mood 为当前感受，innerThought 为未出口的个人念头；不复制 text、工具结果或互相复述。若填写 innerThought，必须同时填写 innerThoughtBasis={"focus":"真正触发它的具体刺激/矛盾","change":"相比上一刻新增或改变了什么","conflict":"可选内部冲突","unsaidWhy":"为什么没有说出口"}；focus 不能为空，change/conflict/unsaidWhy 至少一项非空，否则程序会把心声清空。
                ${com.jiacimu.lulu.data.spontaneousInnerVoiceGuide}
                ${if (separateExpression) "" else onlineChatBubbleRule}
                $voicePerformanceRule
                $characterHangupRule
            """.trimIndent(),
            source = if (sceneContext.contains("电话")) "电话回复" else "聊天工具规划",
            title = title,
            // Phone speech should be a natural turn, not an essay. Keep enough
            // headroom for the JSON state without paying for runaway monologues.
            maxTokens = when {
                separateExpression -> 1_450
                sceneContext.contains("电话") && userText.length < 280 -> 1_500
                else -> 2_400
            },
            // A complaint about unfulfilled responsibilities needs a checked complete response,
            // not an irreversible stream of premature accusations.
            streamResponse = !separateExpression && onReplyStream != null && !disputeNeedsReview,
            onStreamText = if (separateExpression || disputeNeedsReview) null else onReplyStream,
            connectionOverride = connection,
            memoryRequest = UnifiedMemoryRequest(
                currentInput = userText,
                sceneContext = sceneContext,
                recentContext = history,
                taskIntent = "判断是直接回复还是执行一个真实手机工具",
            ),
        )
        currentCoroutineContext().ensureActive()
        if (planner.isFailure) return planner
        val plannedReply = planner.getOrThrow()
        val parsedPlan = parsePlan(plannedReply.text) ?: run {
            val fallback = com.jiacimu.lulu.data.ModelStructuredOutput.completedReplyText(plannedReply.text)
                ?: com.jiacimu.lulu.CallReplyStream.completeReplyText(plannedReply.text)
                ?: plannedReply.text.takeIf { value ->
                    value.isNotBlank() && !value.trimStart().startsWith("{") &&
                        !value.trimStart().startsWith("```")
                }
            // Incomplete JSON commands cannot run. But actual natural-language text
            // should not disappear just because the model skipped the requested envelope.
            if (fallback.isNullOrBlank()) return Result.failure(
                IllegalStateException("模型返回了不完整的结构化内容，未执行任何动作；可重新回复")
            )
            return Result.success(plannedReply.copy(
                text = com.jiacimu.lulu.data.CharacterAccountabilityContext.guardUnfairBlame(userText, fallback),
            ))
        }
        if (callSilence && parsedPlan.action !in setOf("reply", CharacterDecisionProtocol.SILENT))
            return Result.failure(IllegalStateException("通话沉默观察不执行外部工具动作"))
        var plan = parsedPlan.copy(innerThought =
            com.jiacimu.lulu.data.CharacterAccountabilityContext.guardUnfoundedInnerBlame(
                userText, parsedPlan.innerThought,
            ))
        val programRepair = separateExpression &&
            com.jiacimu.lulu.data.DialogueMoveEngine.userInitiatesRepair(userText) &&
            !com.jiacimu.lulu.data.DialogueMoveEngine.correctionSuppliesDirection(userText)
        if (separateExpression && plan.action == "reply" && plan.speechIntent.isBlank() && plan.text.isNotBlank()) {
            plan = plan.copy(speechIntent = plan.text)
        }
        if (programRepair && plan.action != "reply") {
            plan = plan.copy(
                action = "reply",
                speechIntent = "承认刚才理解有误，进行最小必要修复，让对方继续说明真正意思",
                reason = "程序识别到用户在纠正上一轮理解，优先恢复共同语境",
            )
        }
        val dialoguePlan = com.jiacimu.lulu.data.DialogueMoveEngine.resolve(
            plan.dialogueMove ?: plan.appraisal, plan.speechIntent, userText,
        )
        if (groundingEvidenceId.isNotBlank()) {
            com.jiacimu.lulu.data.ConversationGroundingEngine.afterDecision(
                characterId, interactionKey, groundingEvidenceId, dialoguePlan,
            )
        }
        val privateStateBefore = com.jiacimu.lulu.data.CharacterInnerLifeStore.snapshot(characterId)
        val privateDelta = com.jiacimu.lulu.data.PrivateStateDeltaEngine.evaluate(
            previous = privateStateBefore,
            proposal = plan.innerLife,
            appraisal = plan.appraisal,
            basis = plan.innerThoughtBasis,
            thought = plan.innerThought,
        )
        // Do not treat a decision envelope as spoken content. In particular,
        // a phone model returning only a brief must never read JSON aloud.
        val proposedSpeech = if (plan.action == "reply") plan.text else ""
        val checkedText = if (proposedSpeech.isNotBlank())
            com.jiacimu.lulu.data.CharacterAccountabilityContext.guardUnfairBlame(
                userText, proposedSpeech,
            ) else ""
        // A corrected visible sentence must not leave abusive hidden state behind.
        val invalidBlame = proposedSpeech.isNotBlank() && checkedText != proposedSpeech
        if (!invalidBlame) {
            com.jiacimu.lulu.data.CharacterLifeStore.consider(characterId, plan.intention)
            val verifiedSources = com.jiacimu.lulu.data.SharedExperienceTimeline.recentEvents(characterId, 40)
                .filter { userText.isNotBlank() && it.evidenceKind == com.jiacimu.lulu.data.EventEvidenceKind.UserStatement &&
                    it.content.isNotBlank() && (userText.contains(it.content.trim().take(60)) ||
                        it.content.contains(userText.trim().take(60))) }.takeLast(12)
            val userSources = if (callSilence) listOf(com.jiacimu.lulu.data.PerceptionStimulus(
                silenceObservationId, "电话持续接通；本轮没有新增用户发言", setOf("user"))) else verifiedSources.map {
                com.jiacimu.lulu.data.PerceptionStimulus(it.id, it.content.take(260), setOf("user"))
            }.ifEmpty { listOf(com.jiacimu.lulu.data.PerceptionStimulus(
                "chat:${now.toEpochMilli()}:${userText.hashCode()}", userText, setOf("user"))) }
            val perceptionInput = com.jiacimu.lulu.data.CharacterPerceptionContext.integrate(
                context = appContext,
                characterId = characterId,
                observed = observedWorld,
                direct = userSources,
                claimDirect = false,
            )
            val observedSources = perceptionInput.freshStimuli.filterNot {
                fresh -> userSources.any { it.evidenceId == fresh.evidenceId }
            }
            val combined = perceptionInput.combined
            val interactionEvidenceId = when {
                callSilence -> silenceObservationId
                verifiedSources.isNotEmpty() -> verifiedSources.last().id
                else -> ""
            }
            com.jiacimu.lulu.data.CharacterInnerLifeStore.recordInteractionAppraisal(
                characterId = characterId,
                conversationKey = interactionKey,
                evidenceId = interactionEvidenceId,
                appraisal = plan.appraisal,
                now = now,
            )
            // Heart voice is a sparse private residue, not a mandatory second answer.
            // A normal user message is fresh evidence; phone silence alone is not.
            plan = plan.copy(innerThought = com.jiacimu.lulu.data.CharacterHeartVoicePolicy.keepOrBlank(
                thought = plan.innerThought,
                outward = plan.speechIntent.ifBlank { plan.text },
                innerLife = plan.innerLife,
                basis = plan.innerThoughtBasis,
                delta = privateDelta,
                hasFreshEvidence = (!callSilence && userText.isNotBlank()) || observedSources.isNotEmpty(),
            ))
            val causalEvidenceId = combined?.evidenceId.orEmpty().ifBlank { interactionEvidenceId }
            com.jiacimu.lulu.data.CharacterInnerLifeStore.recordCausalTransition(
                characterId = characterId,
                evidenceId = causalEvidenceId,
                appraisal = plan.appraisal,
                innerLife = plan.innerLife,
                innerThoughtBasis = plan.innerThoughtBasis,
                selectedAction = plan.action,
                innerThought = plan.innerThought,
                reason = plan.reason,
                now = now,
                alternatives = plan.alternatives,
            )
            com.jiacimu.lulu.data.CharacterInnerLifeStore.observe(
                characterId, combined?.evidenceId.orEmpty(), combined?.description.orEmpty(),
                com.jiacimu.lulu.data.CharacterInnerLifeStore.withAfterglow(plan.innerLife, plan.afterglow, if (callSilence) sceneContext else userText),
                combined?.socialIds.orEmpty(), now,
            )
            com.jiacimu.lulu.data.CharacterInnerLifeStore.recordInnerVoice(
                characterId, combined?.evidenceId.orEmpty(),
                plan.innerThought, now, privateDelta.fingerprint,
            )
        }
        if (plan.action == CharacterDecisionProtocol.SILENT && (separateExpression || callSilence)) {
            com.jiacimu.lulu.data.CharacterInnerLifeStore.recordDecision(
                characterId = characterId,
                decisionId = if (callSilence) "$silenceObservationId:silent" else "chat:silent:${now.toEpochMilli()}:${userText.hashCode()}",
                selectedAction = CharacterDecisionProtocol.SILENT,
                reason = plan.reason,
                chosenMotiveId = plan.motiveId,
                alternatives = plan.alternatives,
                outcome = "暂时不发送消息，没有执行任何外部动作",
                succeeded = false,
                now = now,
            )
            savePresence(
                characterId, plan, if (callSilence) "通话沉默感知" else "聊天沉默",
                preserveQuietThought = true, heartVoiceFingerprint = privateDelta.fingerprint,
            )
            return Result.success(plannedReply.copy(text = "", disposition = CharacterDecisionProtocol.SILENT))
        }
        if (plan.action == "reply") {
            if (!separateExpression && plan.text.isBlank()) return Result.failure(
                IllegalStateException("电话模型没有返回可朗读的正文，不能把决策 JSON 当作语音")
            )
            // The phone remains single-pass. In text chat, only the expression model
            // renders the planner's intent; it must not re-decide actions.
            val expressed = if (separateExpression && plan.speechIntent.isNotBlank()) {
                val generated = LuluAiServices.gateway.generate(
                    characterId = characterId,
                    facts = buildString {
                        appendLine("真实聊天场景：$sceneContext")
                        if (history.isNotBlank()) appendLine("已发生的对话：\n$history")
                        if (turnContext.isNotBlank()) {
                            appendLine("【系统提供的本轮交互元信息｜不是用户说的话】")
                            appendLine(turnContext.take(4_000))
                        }
                        appendLine("用户刚才说：$userText")
                        appendLine(com.jiacimu.lulu.data.CharacterDecisionProtocol.expressionContext(
                            plan.appraisal, plan.innerLife, plan.mood,
                            com.jiacimu.lulu.data.CharacterInnerLifeStore.compactContext(characterId, now),
                            com.jiacimu.lulu.data.CharacterLifeStore.compactContext(characterId),
                        ))
                        appendLine(com.jiacimu.lulu.data.CharacterInnerLifeStore.interactionContext(characterId, interactionKey, now))
                        appendLine(com.jiacimu.lulu.data.DialogueMoveEngine.expressionConstraint(dialoguePlan))
                        appendLine(com.jiacimu.lulu.data.TransientConversationStyle.context(userText, history))
                        appendLine("角色已决定的内容简报（不能改事实、立场或改作其他行动；不要照抄成台词）：${plan.speechIntent}")
                        if (plan.socialAttempt.isNotBlank()) {
                            appendLine("角色自己选中的具体小尝试：${plan.socialAttempt.take(200)}。这是虚拟聊天表达的意图，不是已发生的现实接触。")
                        }
                    },
                    instruction = """
                        你是当前角色的语言表达层，不是新的决策者；只把已经决定的内容变成符合本人性格、关系边界和当前语境的自然聊天。
                        不要展示决策协议、状态标签、内心独白，也不要选择工具或宣称尚未完成的事情已经完成。
                        只返回完整 JSON：{"action":"reply","bubbles":[{"text":"真正发送的自然语言"}]}。需要第二个局部互动动作时才增加第二项；不要返回任何 BUBBLE/NEXT/END 等控制字符串。
                        你可以在程序已经确定的 dialogueMove 范围内决定自然措辞、停顿、嘴硬或认真程度，也可以自然选择稳定昵称；不能把一个 repair 扩写成道歉+猜测+撒娇+追问的组合任务，不要把内容简报里的抽象词逐字翻译成客服式句子。
                        先接住用户真正新增的意思，再按这个角色平时会说话的方式说出来。不要为了“有个性”临时发明无关称呼、刑罚/职位/游戏化比喻或夸张设定；除非当前对话和角色既有习惯确实支持。
                        说法可以自然、个性化和口语化，但不能更改想表达的核心意思。
                        如果交接了「角色自己选中的具体小尝试」，就实际在发送的气泡中自然做出这件小事：一句玩笑、一个拟声或隔空的虚拟互动都可以依本人性格呈现；不要把实际尝试退化为“那你想让我做什么”“我都愿意”，也不要编造未执行的现实动作。没交接时不凭空制造亲昵举动。
                        可以按本人语气自然加入恰当的标点、颜文字或 emoji，但不要为了显得活泼而给每句都追加相同表情。模型已选中的图片表情由程序另外发送，不要在正文里编造图片标签、路径或表情ID。
                        $onlineChatBubbleRule
                    """.trimIndent(),
                    source = "聊天表达渲染",
                    title = title,
                    maxTokens = 1_400,
                    connectionOverride = connection,
                    memoryRequest = UnifiedMemoryRequest(
                        currentInput = userText,
                        sceneContext = sceneContext,
                        recentContext = history,
                        taskIntent = "按已确定的回复意图渲染语言，不再次决策",
                    ),
                ).getOrElse { return Result.failure(it) }
                val structuredBubbles = com.jiacimu.lulu.data.ModelStructuredOutput.completedReplyBubbles(generated.text)
                val firstBubbles = structuredBubbles
                    ?: generated.text.trim().takeIf { it.isNotBlank() &&
                        !it.startsWith("{") && !it.startsWith("```") }?.let(::listOf)
                    ?: return Result.failure(IllegalStateException("表达模型没有返回完整的可发送正文"))
                val naturalness = com.jiacimu.lulu.data.ConversationNaturalnessGate.assess(
                    userText, firstBubbles, history,
                )
                val rerendered = if (naturalness.needsRerender) {
                    LuluAiServices.gateway.generate(
                        characterId = characterId,
                        facts = buildString {
                            appendLine("真实聊天场景：${sceneContext}")
                            appendLine("用户刚才说：${userText}")
                            appendLine(com.jiacimu.lulu.data.DialogueMoveEngine.expressionConstraint(dialoguePlan))
                            appendLine(com.jiacimu.lulu.data.TransientConversationStyle.context(userText, history))
                            appendLine("已经确定的内容意图：${plan.speechIntent}")
                            if (plan.socialAttempt.isNotBlank()) {
                                appendLine("不能丢失角色自选的小尝试：${plan.socialAttempt.take(180)}；必须实际表达，不能空口承诺。")
                            }
                            appendLine("第一版表达草稿（只用于改措辞，不把它当新事实）：")
                            firstBubbles.forEach { appendLine("- $it") }
                        },
                        instruction = """
                            你仍然只是当前角色的语言表达层，不重新决策。
                            ${naturalness.repairInstruction()}
                            保持这个角色自己的词汇、节奏、幽默感、关系称呼与分寸；不要把“自然”理解成统一的短句网感，也不要故意加口头禅。
                            只返回完整 JSON：{"action":"reply","bubbles":[{"text":"重写后的自然聊天"}]}。
                            每个气泡必须对应一个完整局部互动动作，语法没结束的尾巴不能单独成气泡；最多 ${dialoguePlan.maxBubbles} 个气泡。
                        """.trimIndent(),
                        source = "聊天表达自然度修复",
                        title = title,
                        maxTokens = 1_000,
                        connectionOverride = connection,
                        memoryRequest = UnifiedMemoryRequest(
                            currentInput = userText,
                            sceneContext = sceneContext,
                            recentContext = history,
                            taskIntent = "只修正表达层人机感，不改变已决定内容",
                        ),
                    ).getOrNull()
                } else null
                val rerenderedBubbles = rerendered?.let {
                    com.jiacimu.lulu.data.ModelStructuredOutput.completedReplyBubbles(it.text)
                }
                val repairedNaturalness = rerenderedBubbles?.let {
                    com.jiacimu.lulu.data.ConversationNaturalnessGate.assess(userText, it, history)
                }
                val useRerender = rerendered != null && !rerenderedBubbles.isNullOrEmpty() &&
                    repairedNaturalness != null && repairedNaturalness.score < naturalness.score &&
                    com.jiacimu.lulu.data.ConversationNaturalnessGate.preservesSurfaceIntent(
                        firstBubbles, rerenderedBubbles,
                    )
                val chosenResult = if (useRerender) rerendered!! else generated
                val chosenBubbles = if (useRerender) rerenderedBubbles!! else firstBubbles
                val spoken = chosenBubbles.take(dialoguePlan.maxBubbles)
                    .joinToString(com.jiacimu.lulu.SemanticBubbleSeparator)
                chosenResult.copy(
                    text = spoken,
                    inputTokens = generated.inputTokens + (rerendered?.inputTokens ?: 0) + plannedReply.inputTokens,
                    outputTokens = generated.outputTokens + (rerendered?.outputTokens ?: 0) + plannedReply.outputTokens,
                    cachedTokens = generated.cachedTokens + (rerendered?.cachedTokens ?: 0) + plannedReply.cachedTokens,
                )
            } else plannedReply
            val naturalText = if (separateExpression && plan.speechIntent.isNotBlank()) expressed.text else checkedText
            val safeText = com.jiacimu.lulu.data.CharacterAccountabilityContext.guardUnfairBlame(userText, naturalText)
            // A sticker exists only if the user has imported and approved that
            // exact ID. Invalid guesses silently yield no image.
            val chosenSticker = if (!sceneContext.contains("电话"))
                plan.stickerId.takeIf(String::isNotBlank)?.let { com.jiacimu.lulu.StickerLibraryStore.byId(appContext, it) }
            else null
            if (safeText.isBlank() && chosenSticker == null) return Result.failure(
                IllegalStateException("角色决定回复但没有生成可发送内容"))
            val sendText = if (chosenSticker == null) safeText else listOf(
                safeText, com.jiacimu.lulu.encodeQqChatImage(
                    chosenSticker.uri,
                    imageDescription = chosenSticker.name,
                    sticker = true,
                ),
            ).filter(String::isNotBlank).joinToString(com.jiacimu.lulu.SemanticBubbleSeparator)
            if (!invalidBlame && safeText == naturalText) {
                savePresence(
                    characterId, plan, if (callSilence) "通话沉默感知" else "聊天",
                    heartVoiceFingerprint = privateDelta.fingerprint,
                )
                com.jiacimu.lulu.data.CharacterLifeStore.recordAfterglow(characterId, if (callSilence) "电话中的安静陪伴观察" else "本轮用户消息：$userText", plan.afterglow)
            }
            if (!invalidBlame && plan.endCall && sceneContext.contains("电话"))
                onCharacterHangup?.invoke()
            return Result.success(expressed.copy(text = sendText))
        }
        if (plan.action == "tool" && plan.tool.isNotBlank()) {
            com.jiacimu.lulu.data.CharacterLifeStore.recordAfterglow(characterId, "本轮用户消息：$userText", plan.afterglow)
        }
        if (plan.action != "tool" || plan.tool.isBlank()) return Result.failure(
            IllegalStateException("决策没有有效的可执行动作，未发送消息也未调用工具")
        )

        val lastUserEvent = com.jiacimu.lulu.data.SharedExperienceTimeline.recentEvents(characterId, 20)
            .lastOrNull { it.evidenceKind == com.jiacimu.lulu.data.EventEvidenceKind.UserStatement }?.id
        val explicitUserToolRequest = com.jiacimu.lulu.data.CharacterInitiativeRuntime
            .isExplicitUserToolRequest(userText, plan.tool)
        val toolResult = com.jiacimu.lulu.data.ToolRouter.execute(appContext, characterId, plan.tool, plan.args,
            requestId = "reply-${lastUserEvent ?: java.util.UUID.randomUUID().toString()}",
            userRequested = explicitUserToolRequest)
        val actual = runCatching { JSONObject(toolResult) }.getOrNull()
        com.jiacimu.lulu.data.CharacterInnerLifeStore.recordActionResult(
            characterId, plan.motiveId, "tool:${lastUserEvent ?: now.toEpochMilli()}:${plan.tool}",
            plan.tool, actual?.optBoolean("success") == true,
            actual?.optString("summary").orEmpty().ifBlank { toolResult.take(220) },
        )
        val finalReply = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = buildString {
                appendLine("当前真实互动场景：$sceneContext")
                if (history.isNotBlank()) appendLine("最近对话（已经真实发生，用来确定动作前的状态）：\n$history")
                appendLine("这一刻用户新增的消息：$userText")
                appendLine("角色刚才决定做的动作：${plan.tool}")
                if (plan.statusText.isNotBlank() || plan.gesture.isNotBlank() || plan.mood.isNotBlank() || plan.innerThought.isNotBlank()) {
                    appendLine("动作前一瞬的角色状态：${plan.statusText}；动作：${plan.gesture}；心情：${plan.mood}；没说出口：${plan.innerThought}")
                }
                appendLine("这个动作刚刚产生的真实新结果：$toolResult")
            },
            instruction = """
                这是同一段连续经历中的下一刻：角色刚刚已经做完工具动作，现在只需要从动作后的新状态继续说话，不要把整段对话重新回答一遍。
                根据工具真实结果，以角色本人符合人设的方式自然接下去。成功时可以确认，失败时必须如实说明；除此之外只说此刻真正会新增的话。
                必须继续保持当前真实互动场景，电话里用自然口语，群聊里知道其他成员在场。
                对位置结果只能使用 readableAddress；地址为空、定位过旧或精度差时，必须明确说是大概位置，不得根据经纬度猜具体店铺、学校或建筑。
                只返回一个 JSON 对象，不要代码块；action 最先，紧接 text，不重复字段：
                {"action":"reply","text":"角色在动作之后自然接着说的话","statusText":"动作后的简短状态","gesture":"动作后的可见动作神态","innerThought":"动作后没说出口的第一人称心声，可为空","innerThoughtBasis":{"focus":"若有心声，真实触发点","change":"动作结果让内部状态新增/改变了什么","unsaidWhy":"为何没直接说"},"mood":"动作后的简短心情"}
                如果工具真实成功或失败使角色改变了一个想法、想继续尝试或意识到失误，可以选填 innerLife 的 emotion/motives/selfCorrection 字段；仅依据上面明确给出的工具结果，失败绝不能写成成功。
                若工具成功或失败真的引发新的情绪，可额外填写 afterglow:{"feeling":"第一拍心声","impulse":"尚未执行的冲动","holdHours":1到48的整数}；不是必须填写。
                ${com.jiacimu.lulu.data.spontaneousInnerVoiceGuide}
                不要解释内部工具协议。innerThought 不是工具决策理由，gesture 不得编造未发生的工具结果或现实场景。
                $onlineChatBubbleRule
                $voicePerformanceRule
                $characterHangupRule
            """.trimIndent(),
            source = "聊天工具结果",
            title = title,
            maxTokens = if (sceneContext.contains("电话")) 1_800 else 1_200,
            streamResponse = onReplyStream != null && !disputeNeedsReview,
            onStreamText = if (disputeNeedsReview) null else onReplyStream,
            connectionOverride = connection,
        )
        return finalReply.map { result ->
            val toolResultPrivateStateBefore = com.jiacimu.lulu.data.CharacterInnerLifeStore.snapshot(characterId)
            var toolResultPrivateDelta: com.jiacimu.lulu.data.PrivateStateDelta? = null
            val finalPlan = parsePlan(result.text)?.let { parsed ->
                val guarded = com.jiacimu.lulu.data.CharacterAccountabilityContext.guardUnfoundedInnerBlame(
                    userText, parsed.innerThought,
                )
                toolResultPrivateDelta = com.jiacimu.lulu.data.PrivateStateDeltaEngine.evaluate(
                    previous = toolResultPrivateStateBefore,
                    proposal = parsed.innerLife,
                    appraisal = parsed.appraisal,
                    basis = parsed.innerThoughtBasis,
                    thought = guarded,
                )
                parsed.copy(innerThought = com.jiacimu.lulu.data.CharacterHeartVoicePolicy.keepOrBlank(
                    thought = guarded,
                    outward = parsed.text,
                    innerLife = parsed.innerLife,
                    basis = parsed.innerThoughtBasis,
                    delta = toolResultPrivateDelta,
                    hasFreshEvidence = true,
                ))
            }
            val naturalText = finalPlan?.text?.ifBlank { result.text }
                ?: com.jiacimu.lulu.CallReplyStream.completeReplyText(result.text) ?: result.text
            val checkedResultText = com.jiacimu.lulu.data.CharacterAccountabilityContext.guardUnfairBlame(
                userText, naturalText,
            )
            if (finalPlan != null && checkedResultText == naturalText) {
                savePresence(
                    characterId, finalPlan, "聊天·工具",
                    heartVoiceFingerprint = toolResultPrivateDelta?.fingerprint.orEmpty(),
                )
                com.jiacimu.lulu.data.CharacterLifeStore.recordAfterglow(characterId,
                    "本轮用户消息：${userText.take(120)}；工具真实结果：${toolResult.take(120)}", finalPlan.afterglow)
                com.jiacimu.lulu.data.CharacterInnerLifeStore.observe(
                    characterId, "tool-result:${now.toEpochMilli()}:${plan.tool}",
                    toolResult, com.jiacimu.lulu.data.CharacterInnerLifeStore.withAfterglow(finalPlan.innerLife, finalPlan.afterglow, toolResult), emptySet(),
                )
                com.jiacimu.lulu.data.CharacterInnerLifeStore.recordInnerVoice(
                    characterId, "tool-result:${now.toEpochMilli()}:${plan.tool}",
                    finalPlan.innerThought,
                    causeFingerprint = toolResultPrivateDelta?.fingerprint.orEmpty(),
                )
                if (finalPlan.endCall && checkedResultText.isNotBlank() && sceneContext.contains("电话"))
                    onCharacterHangup?.invoke()
            }
            result.copy(
                text = checkedResultText,
                inputTokens = result.inputTokens + plannedReply.inputTokens,
                outputTokens = result.outputTokens + plannedReply.outputTokens,
                cachedTokens = result.cachedTokens + plannedReply.cachedTokens,
            )
        }
    }

    suspend fun executeRegistered(context: Context, characterId: String, tool: String, args: JSONObject, requestId: String): String = runCatching {
        val characterName = MigratedDomainStores.characters.get(characterId).displayName
        when (tool.trim().lowercase()) {
            "get_battery" -> battery(context)
            "get_location" -> location(context)
            "get_current_app" -> currentApp(context)
            "read_recent_notifications" -> notifications(args.optInt("limit", 10))
            "create_alarm" -> {
                val trigger = Instant.parse(args.optString("triggerAt"))
                val alarm = LuluAlarmSystem.create(
                    characterId = characterId,
                    characterName = characterName,
                    triggerAt = trigger,
                    label = args.optString("label").ifBlank { "${characterName}提醒你" },
                    id = "tool-$characterId-$requestId",
                ).getOrThrow()
                JSONObject().put("success", LuluAlarmSystem.canScheduleExact()).put("status", if (LuluAlarmSystem.canScheduleExact()) "succeeded" else "waiting_user").put("verification", if (LuluAlarmSystem.canScheduleExact()) "AlarmManager已接受本地精确调度，注册记录已持久化" else "已请求系统时钟创建，尚无法读取系统时钟结果，请核实").put("systemClock", !LuluAlarmSystem.canScheduleExact()).put("id", alarm.id).put("triggerAt", alarm.triggerAt.toString()).put("label", alarm.label).toString()
            }
            "list_alarms" -> {
                val alarms = LuluAlarmSystem.list().filter { it.characterId == characterId }
                JSONObject().put("success", true).put("count", alarms.size).put(
                    "alarms",
                    alarms.joinToString("\n") { alarm -> "${alarm.id} | ${alarm.triggerAt.atZone(ZoneId.systemDefault())} | ${alarm.label}" },
                ).toString()
            }
            "cancel_alarm" -> {
                val id = args.optString("id")
                require(LuluAlarmSystem.list().any { it.id == id && it.characterId == characterId }) { "不能取消其他角色的闹钟或不存在的闹钟" }
                JSONObject().put("success", LuluAlarmSystem.cancel(id)).put("id", id).toString()
            }
            "screen_action" -> {
                val action = when (args.optString("name").lowercase()) {
                    "back" -> LuluScreenAction.Back
                    "home" -> LuluScreenAction.Home
                    "recents" -> LuluScreenAction.Recents
                    "notifications" -> LuluScreenAction.Notifications
                    "quick_settings" -> LuluScreenAction.QuickSettings
                    else -> error("未知屏幕动作")
                }
                val accepted = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { LuluAccessibilityService.perform(action) }
                kotlinx.coroutines.delay(500)
                val observed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { LuluAccessibilityService.observe() }
                JSONObject().put("success", false).put("status", "waiting_user").put("accepted", accepted).put("action", action.name)
                    .put("visibleText", observed.visibleText).put("verification", "系统动作请求已返回，需依据新屏幕核实目标；不等同任务成功").toString()
            }
            "click_text" -> {
                val text = args.optString("text")
                val accepted = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { LuluAccessibilityService.clickFirstText(text) }
                kotlinx.coroutines.delay(500)
                val observed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { LuluAccessibilityService.observe() }
                JSONObject().put("success", false).put("status", "waiting_user").put("accepted", accepted).put("text", text)
                    .put("visibleText", observed.visibleText).put("verification", "点击请求已返回；未指定验证条件，不能宣称目标完成").toString()
            }
            "screen_sequence" -> VerifiedScreenSequence.execute(args)
            "read_screen" -> {
                val value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { LuluAccessibilityService.observe() }
                check(value.connected) { "尚未开启屏幕感知与控制权限" }
                JSONObject().put("success", true).put("packageName", value.packageName).put("windowTitle", value.windowTitle)
                    .put("visibleText", value.visibleText.take(6_000)).put("capturedAt", value.capturedAt?.toString().orEmpty()).toString()
            }
            else -> CompanionActionRuntime.execute(context, characterId, tool, args).asJson()
        }
    }.getOrElse { error ->
        JSONObject().put("success", false).put("error", error.message ?: error::class.java.simpleName).toString()
    }

    private fun battery(context: Context): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return JSONObject().put("success", percent >= 0).put("percent", percent).put("charging", charging).toString()
    }

    private suspend fun location(context: Context): String {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            "尚未授予精确位置权限"
        }
        val best = LuluLocationProvider.freshLocation(context)
            ?: error("暂时没有可用定位，请确认系统定位已开启后再试")

        val ageMillis = (System.currentTimeMillis() - best.time).coerceAtLeast(0L)
        val stale = ageMillis > 10 * 60_000L
        val address = runCatching {
            if (!Geocoder.isPresent()) return@runCatching null
            Geocoder(context, Locale.getDefault())
                .getFromLocation(best.latitude, best.longitude, 1)
                ?.firstOrNull()
        }.getOrNull()
        val readableAddress = address?.let { value ->
            listOfNotNull(
                value.subLocality,
                value.locality,
                value.adminArea,
                value.countryName,
            ).map(String::trim).filter(String::isNotBlank).distinct().joinToString("，")
        }.orEmpty()
        val confidence = when {
            stale -> "stale"
            best.accuracy <= 30f -> "high"
            best.accuracy <= 100f -> "medium"
            else -> "low"
        }
        val reliabilityNote = buildString {
            when (confidence) {
                "high" -> append("GPS 精度较高")
                "medium" -> append("只能判断大致街区范围")
                "low" -> append("定位精度较低，只能判断城区范围")
                else -> append("位置数据已经过旧，不应当作当前位置")
            }
            if (readableAddress.isBlank()) append("；系统未返回可靠行政区地址")
            else append("；地址仅保留行政区层级，已忽略容易误报的具体建筑名称")
        }

        return JSONObject()
            .put("success", true)
            .put("readableAddress", readableAddress)
            .put("latitude", best.latitude)
            .put("longitude", best.longitude)
            .put("accuracyMeters", best.accuracy)
            .put("provider", best.provider)
            .put("capturedAt", Instant.ofEpochMilli(best.time).toString())
            .put("ageSeconds", ageMillis / 1000L)
            .put("stale", stale)
            .put("confidence", confidence)
            .put("addressGranularity", "administrative_area")
            .put("note", reliabilityNote)
            .toString()
    }

    private fun currentApp(context: Context): String {
        val accessibility = LuluAccessibilityService.state.value
        if (accessibility.connected && accessibility.packageName.isNotBlank()) {
            return JSONObject().put("success", true).put("packageName", accessibility.packageName).put("source", "accessibility")
                .put("capturedAt", accessibility.capturedAt?.toString().orEmpty()).toString()
        }
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val events = usage.queryEvents(end - 15 * 60_000L, end)
        val event = UsageEvents.Event()
        var packageName = ""
        var latest = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.timeStamp >= latest) {
                packageName = event.packageName.orEmpty()
                latest = event.timeStamp
            }
        }
        check(packageName.isNotBlank()) { "尚未获得应用使用情况权限，或近期没有前台应用记录" }
        return JSONObject().put("success", true).put("packageName", packageName).put("source", "usage_stats")
            .put("capturedAt", Instant.ofEpochMilli(latest).toString()).toString()
    }

    private fun notifications(limit: Int): String {
        check(LuluNotificationListenerService.isConnected.value) { "尚未开启通知读取权限" }
        val values = LuluNotificationListenerService.notifications.value.take(limit.coerceIn(1, 30))
        return JSONObject().put("success", true).put("count", values.size).put(
            "notifications",
            values.joinToString("\n") { value -> "${value.postedAt} | ${value.packageName} | ${value.title} | ${value.text}" },
        ).toString()
    }

    private fun parsePlan(raw: String): ToolPlan? {
        return runCatching {
            val json = com.jiacimu.lulu.data.ModelStructuredOutput.objectOrNull(raw) ?: return null
            val action = CharacterDecisionProtocol.chatAction(json) ?: return null
            val bubbleValues = com.jiacimu.lulu.data.ModelStructuredOutput.completedReplyBubbles(raw)
            val spoken = if (json.optJSONArray("bubbles") != null && !bubbleValues.isNullOrEmpty())
                bubbleValues.joinToString(com.jiacimu.lulu.SemanticBubbleSeparator)
            else com.jiacimu.lulu.data.ModelStructuredOutput.completedReplyText(raw).orEmpty()
            ToolPlan(
                action = action,
                text = if (action == "reply") spoken else json.optString("text"),
                tool = json.optString("tool"),
                args = json.optJSONObject("args") ?: JSONObject(),
                statusText = json.optString("statusText").ifBlank { json.optString("status") },
                gesture = json.optString("gesture").ifBlank { json.optString("actionDescription") },
                innerThought = json.optString("innerThought").ifBlank { json.optString("inner_voice") },
                innerThoughtBasis = json.optJSONObject("innerThoughtBasis"),
                mood = json.optString("mood"),
                afterglow = json.optJSONObject("afterglow"),
                intention = json.optJSONObject("intention"),
                innerLife = json.optJSONObject("innerLife"),
                motiveId = json.optString("motiveId"),
                endCall = json.optBoolean("endCall", false),
                speechIntent = CharacterDecisionProtocol.speechIntent(json),
                socialAttempt = json.optString("socialAttempt").trim().take(220),
                stickerId = json.optString("stickerId").trim().take(90),
                reason = json.optString("reason"),
                alternatives = json.optJSONArray("alternatives"),
                appraisal = json.optJSONObject("appraisal"),
                dialogueMove = json.optJSONObject("dialogueMove"),
            )
        }.getOrNull()
    }

    private fun savePresence(
        characterId: String,
        plan: ToolPlan,
        source: String,
        preserveQuietThought: Boolean = false,
        heartVoiceFingerprint: String = "",
    ) {
        CompanionPresenceStore.update(
            characterId = characterId,
            statusText = plan.statusText,
            gesture = plan.gesture,
            innerThought = if (preserveQuietThought) plan.innerThought.takeIf(String::isNotBlank) else plan.innerThought,
            mood = plan.mood,
            source = source,
            innerThoughtFingerprint = heartVoiceFingerprint,
        )
    }
}

private data class ToolPlan(
    val action: String,
    val text: String,
    val tool: String,
    val args: JSONObject,
    val statusText: String,
    val gesture: String,
    val innerThought: String,
    val innerThoughtBasis: JSONObject? = null,
    val mood: String,
    val intention: JSONObject? = null,
    val afterglow: JSONObject? = null,
    val innerLife: JSONObject? = null,
    val motiveId: String = "",
    val endCall: Boolean = false,
    val speechIntent: String = "",
    val socialAttempt: String = "",
    val stickerId: String = "",
    val reason: String = "",
    val appraisal: JSONObject? = null,
    val dialogueMove: JSONObject? = null,
    val alternatives: org.json.JSONArray? = null,
)
