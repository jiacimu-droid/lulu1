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
import com.jiacimu.lulu.data.CommitmentTask
import com.jiacimu.lulu.data.CommitmentTaskStore
import com.jiacimu.lulu.data.CommitmentTaskStatus
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

    /**
     * Re-answering an already delivered chat turn is NOT another autonomous action.
     * Generate only a replacement expression, with its optional private afterthought.
     * Nothing here mutates the role's mental timeline; the UI commits that only after
     * the replacement messages have actually been persisted.
     */
    data class ReconsideredChatReply(
        val text: String,
        val innerThought: String = "",
        val innerThoughtBasis: JSONObject? = null,
        val innerLife: JSONObject? = null,
        val appraisal: JSONObject? = null,
        val reason: String = "",
        val mood: String? = null,
        val gesture: String? = null,
        val statusText: String? = null,
    )

    /** Commit optional mental changes only AFTER the replacement messages were saved. */
    fun commitReconsideredChatReply(
        characterId: String,
        evidenceId: String,
        reply: ReconsideredChatReply,
    ) {
        val now = Instant.now()
        val delta = com.jiacimu.lulu.data.PrivateStateDeltaEngine.evaluate(
            previous = com.jiacimu.lulu.data.CharacterInnerLifeStore.snapshot(characterId),
            proposal = reply.innerLife,
            appraisal = reply.appraisal,
            basis = reply.innerThoughtBasis,
            thought = reply.innerThought,
        )
        val groundedThought = com.jiacimu.lulu.data.CharacterHeartVoicePolicy.keepOrBlank(
            thought = reply.innerThought,
            outward = reply.text.replace(com.jiacimu.lulu.SemanticBubbleSeparator, " "),
            innerLife = reply.innerLife,
            basis = reply.innerThoughtBasis,
            delta = delta,
            hasFreshEvidence = true, // the user's real request to revise is an observed event
        )
        com.jiacimu.lulu.data.CharacterInnerLifeStore.recordInteractionAppraisal(
            characterId = characterId,
            conversationKey = "direct:user",
            evidenceId = evidenceId,
            appraisal = reply.appraisal,
            now = now,
        )
        if (delta.meaningful && reply.innerLife != null) {
            com.jiacimu.lulu.data.CharacterInnerLifeStore.observe(
                characterId, evidenceId, "用户请求重新考虑自己刚才的表达",
                reply.innerLife, emptySet(), now,
            )
        }
        // A pending concern is itself an enduring subjective decision, even if this
        // turn has no new emotion or publishable inner-voice text. Dropping it here
        // made a successful re-answer look emotionally resolved after five minutes.
        if (delta.meaningful || groundedThought.isNotBlank() ||
            com.jiacimu.lulu.data.CharacterOpenConcernRuntime.hasProposals(reply.appraisal)) {
            com.jiacimu.lulu.data.CharacterInnerLifeStore.recordCausalTransition(
                characterId = characterId, evidenceId = evidenceId, appraisal = reply.appraisal,
                innerLife = reply.innerLife, innerThoughtBasis = reply.innerThoughtBasis,
                selectedAction = "reply", innerThought = groundedThought,
                reason = reply.reason.ifBlank { "用户要求重写上一轮回复" }, now = now,
            )
            if (groundedThought.isNotBlank()) {
                com.jiacimu.lulu.data.CharacterInnerLifeStore.recordInnerVoice(
                    characterId, evidenceId, groundedThought, now, delta.fingerprint,
                )
            }
        }
        // Durable, bounded follow-up: the ordinary online timeout handles this
        // while the app stays alive; a persisted work request also survives
        // background process death. It only reopens the shared autonomous
        // decision, not a compulsory apology, message or new online window.
        if (com.jiacimu.lulu.data.CharacterInnerLifeStore.needsPostOnlineReflection(characterId, now)) {
            context?.let { app ->
                val onlineUntil = com.jiacimu.lulu.data.CompanionOnlineStore.states
                    .value[characterId]?.onlineUntil
                val untilOffline = onlineUntil?.let {
                    java.time.Duration.between(now, it).toMillis().coerceAtLeast(0L)
                } ?: 0L
                runCatching {
                    com.jiacimu.lulu.data.ProactivePerceptionScheduler.scheduleEmotionalAftercare(
                        app, characterId, delayMillis = untilOffline + 90_000L,
                    )
                }
            }
        }
        // No new inner voice is not an instruction to erase an earlier, still valid thought.
        // Avoid a blank status update creating a fictitious new moment.
        if (groundedThought.isNotBlank() || reply.statusText != null ||
            reply.gesture != null || reply.mood != null) {
            CompanionPresenceStore.update(
                characterId = characterId,
                statusText = reply.statusText,
                gesture = reply.gesture,
                innerThought = groundedThought.takeIf(String::isNotBlank),
                mood = reply.mood,
                source = "聊天重新回复",
                now = now,
                provenanceId = evidenceId,
                innerThoughtFingerprint = delta.fingerprint.takeIf { groundedThought.isNotBlank() },
            )
        }
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
        onTimedHangup: ((Int) -> Unit)? = null,
        silenceObservationId: String = "",
        turnContext: String = "",
        reconsiderationEvidenceId: String = "",
        userEvidenceIds: List<String> = emptyList(),
        phoneCallId: String = "",
        onReconsiderationDraft: ((ReconsideredChatReply) -> Unit)? = null,
        onLive2DAction: ((JSONObject) -> Unit)? = null,
    ): Result<ModelReply> {
        val callSilence = silenceObservationId.isNotBlank() && sceneContext.contains("电话")
        val rewriting = reconsiderationEvidenceId.isNotBlank()
        val appContext = context ?: return Result.failure(IllegalStateException("手机能力尚未初始化"))
        val externalCallResult = if (callSilence)
            com.jiacimu.lulu.data.SharedExperienceTimeline.eventsByIds(characterId, listOf(silenceObservationId))
                .firstOrNull { it.source == "tool-router" &&
                    it.evidenceKind == com.jiacimu.lulu.data.EventEvidenceKind.ToolResult }
        else null
        // QQ group rounds are dispatched by runGroupReplies: one character owns
        // each model decision, and the next character sees only persisted turns.
        // Do not hand the whole group to GroupEnsembleReplyEngine's cached ensemble.

        val connection = runCatching { LuluAiServices.connectionStore.resolveConnection(archiveId) }
            .getOrElse { return Result.failure(it) }
        val character = MigratedDomainStores.characters.get(characterId)
        // Factual integrity only. If the model wrongly puts its own missed
        // obligation on the user, ask the SAME role to reconsider its expression.
        // Never substitute a universal apology script or mutate stored persona.
        val repairUnfairSpeech: suspend (String) -> String? = { speech ->
            if (!com.jiacimu.lulu.data.CharacterAccountabilityContext
                    .containsUnfoundedBlame(userText, speech)) speech
            else {
                val redraft = LuluAiServices.gateway.generate(
                    characterId = characterId,
                    facts = buildString {
                        appendLine("此时场景：${sceneContext.take(180)}")
                        appendLine("用户本轮原话：${userText.take(850)}")
                        appendLine("刚才真实对话（只作关系与承诺核对，不把旧草稿当事实）：${history.takeLast(1_600)}")
                        appendLine("本角色刚才尚未发出的表达：${speech.take(900)}")
                        appendLine("已核实的责任上下文：")
                        appendLine(com.jiacimu.lulu.data.CharacterAccountabilityContext
                            .prompt(characterId).take(1800))
                    },
                    instruction = """
                        这是同一角色的原话在送达前进行事实核对，而不是新增用户消息。
                        原稿把没有证据的责任/动机强加给用户了。请依据刚才的事实，
                        由你自己重新判断想说什么，并用你原有的人格、关系、语气自然说出来。
                        保留有根据的不同意见和情绪，不重复无证据的指责。
                        角色应从本人已经成立的关系、先前的承诺和主人此次真实反馈出发理解，
                        不能把对方正常核查履约解释成故意挑衅、找茬或倒打一耙。
                        未履行或缺少实际回执时说清本人已知事实与不确定性，
                        不把用户没有回应等同于角色已经通知到位。
                        不规定必须道歉或必须怎样道歉；不得声称工具动作已经完成.
                        只输出本人自然口语的完整文本，不要 JSON、引号或舞台旁白。
                    """.trimIndent(),
                    source = "聊天发送前·责任事实核对",
                    title = title,
                    maxTokens = 420,
                    connectionOverride = connection,
                ).getOrNull()?.text?.trim().orEmpty()
                val natural = com.jiacimu.lulu.data.ModelStructuredOutput.completedReplyText(redraft)
                    ?: redraft.takeUnless {
                        it.startsWith("{") || it.startsWith("```")
                    }.orEmpty()
                natural.takeIf { it.isNotBlank() &&
                    !com.jiacimu.lulu.data.CharacterAccountabilityContext
                        .containsUnfoundedBlame(userText, it) }
            }
        }

        val previousPresence = CompanionPresenceStore.current(characterId)
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val interactionKey = if (sceneContext.contains("群聊《")) {
            val exactGroupId = turnContext.substringAfter("群聊ID=", "")
                .substringBefore("；").trim()
            "group:" + exactGroupId.ifBlank {
                sceneContext.substringAfter("群聊《").substringBefore("》")
            }
        } else "direct:user"
        val interactionContext = com.jiacimu.lulu.data.CharacterInnerLifeStore.interactionContext(characterId, interactionKey, now)
        // Real message IDs supplied by the chat transport outrank fuzzy
        // text matching. A synthetic hash is not a deletable user statement.
        val originalUserEvidence = if (userEvidenceIds.isEmpty()) emptyList() else
            com.jiacimu.lulu.data.SharedExperienceTimeline.eventsByIds(
                characterId, userEvidenceIds.takeLast(12),
            ).filter { it.evidenceKind == com.jiacimu.lulu.data.EventEvidenceKind.UserStatement }
        // Use actual role-owned deliveries in this exact conversation. User quotes,
        // other group members and unrelated private scenes cannot become our style cues.
        val currentSessionId = originalUserEvidence.lastOrNull()?.sessionId.orEmpty()
        val recentCharacterReplies = if (currentSessionId.isBlank()) emptyList() else
            com.jiacimu.lulu.data.ConversationTurnEconomy.recentOwnReplies(
                com.jiacimu.lulu.data.SharedExperienceTimeline.recentEvents(characterId, 80),
                characterId, currentSessionId, now,
            )
        val turnEconomyContext = com.jiacimu.lulu.data.ConversationTurnEconomy.context(
            userText, recentCharacterReplies,
        )
        val groundingEvidenceId = if (reconsiderationEvidenceId.isNotBlank()) reconsiderationEvidenceId
            else if (callSilence) silenceObservationId
            else if (originalUserEvidence.isNotEmpty()) originalUserEvidence.last().id
            else com.jiacimu.lulu.data.SharedExperienceTimeline.recentEvents(characterId, 40)
                .filter {
                    userText.isNotBlank() &&
                        it.evidenceKind == com.jiacimu.lulu.data.EventEvidenceKind.UserStatement &&
                        it.content.isNotBlank() &&
                        (userText.contains(it.content.trim().take(60)) ||
                            it.content.contains(userText.trim().take(60)))
                }
                .lastOrNull()?.id.orEmpty()
        val commitmentEvidence = originalUserEvidence.ifEmpty {
            if (groundingEvidenceId.isBlank()) emptyList() else
                com.jiacimu.lulu.data.SharedExperienceTimeline.eventsByIds(characterId, listOf(groundingEvidenceId))
        }
        val commitmentEvidenceIds = userEvidenceIds.ifEmpty { commitmentEvidence.map { it.id } }
        val commitmentSessionId = commitmentEvidence.lastOrNull()?.sessionId.orEmpty()
        if (groundingEvidenceId.isNotBlank() && !rewriting) {
            com.jiacimu.lulu.data.ConversationGroundingEngine.beforeTurn(
                characterId, interactionKey, groundingEvidenceId, userText,
            )
        }
        val groundingContext = com.jiacimu.lulu.data.ConversationGroundingEngine.context(
            characterId, interactionKey,
        )
        // Chat does not suspend the digital world. Its persisted hourly slot prevents
        // event rerolls from rapid messages or retries.
        val inWorldMoment = if (!rewriting && (!sceneContext.contains("电话") || callSilence) &&
            com.jiacimu.lulu.data.DigitalLifeProfileStore.isEnabled(characterId)) {
            com.jiacimu.lulu.data.DigitalWorldLifeEventStore.tick(
                appContext, characterId, now
            )
        } else null
        HealthRolePerception.initialize(appContext)
        if (!rewriting) HealthRolePerception.recordLatestSleep(characterId)
        val deviceContext = com.jiacimu.lulu.data.UserDevicePerception.context(appContext, characterId, now)
        val observedWorld = com.jiacimu.lulu.data.CharacterPerceptionContext.pending(appContext, characterId, now)
        val companionActionContext = CompanionActionRuntime.capabilityContext(appContext, characterId, includeWorldContext = false) + "\n" + com.jiacimu.lulu.data.CapabilityRegistry.context(appContext, characterId)
        val initiativeContext = com.jiacimu.lulu.data.CharacterInitiativeRuntime.context(characterId, userText)
        val onlineChatBubbleRule = if (sceneContext.contains("电话")) "" else """
            【即时通讯中的表达：先想说什么，再决定发多少】
            - 私聊或群聊是面对一个真实对象接话，不是写散文、做情绪分析或完成关系宣言。先接住这一轮真正新增的事情；对方只提出一个简单需求时，无须自行扩写一整段安慰、环境描写或联想。
            - 像微信里跟熟人聊天：先挑这一次最想让对方听到的一点情绪/态度/想法，通常一两句、几个自然气泡就够了。不是在交付任务，没必要解释来龙去脉、逐条回应所有暗含的问题。已经回应好就停，别为了显得体贴重复同一态度、再总结、补免责声明或说明后续安排。只有用户确实认真问了复杂问题时才展开。
            - 本轮真正要说出口的重点，和脑中想到的一大堆理由是两回事。可以有复杂的内心、愧疚、判断和后续打算，但不用把它们全部播报给对方。尤其工具失败时，说清结果和当下态度后停住；对方追问才解释细节。
            - 有审美不等于平时说话都像小说。幽默、修辞、暧昧、感叹、停顿都可以自然出现，但应贴合个人口吻与现场；不要突然借用无关的开发者、模型、后台或设备术语来营造奇怪的比喻。真正在讨论这些技术时可以正常提及。
            - 轻松时可以随性、跑题、开玩笑、改口或只回一部分，认真议事时可以充分解释；不按固定字数、气泡数或固定情绪比例表演。别把近期自己生成的长篇文风误认为稳定人格。
            - 不要把日常一句问候答成「我收到你的信息、理解你的需求、接下来可以帮你……」。先作真实对话里的回应，允许明显的个人主张、不同意、顺着旧话题开玩笑，或把自己确实经历过而还没讲的小事带进来；没有关联时不硬提。回想过去不等于那些事刚发生。
            - bubbles 中只放真的发给对方看的话；心情、心理活动与动作留在结构化状态里，不写舞台旁白。发送前自然收束：如果下一句只是在修饰上一句而不推进交流，可以不发。
            - 如果本人决定逗人开心、示好、开个玩笑、给一句亲昵回应，必须让对方真正收到一件有内容、有趣味或有温度的小互动，而不只是宣布“你说要我干嘛我都做”。可用语言、拟声、表情或自然的虚拟动作意象表达；绝不假装已经完成现实身体接触或数字世界操作。是否这么做、怎样做由关系和当下愿望决定，而非套用固定模板。
            - 标点、颜文字、emoji 和已经收藏的表情包都是个人表达的自然组成部分。认真、生气、得意、无语、撒娇、犯困时的节奏可以不同，但不规定频率、也不要每句都塞颜文字。没有合适的图就自然只发文字。
            - 先按普通文字聊天的自然节奏安排 bubbles：一个气泡通常只承担一个局部互动动作，完整短句/意群分别成泡。确定气泡以后，再逐条决定 delivery=text 或 voice；语音只是朗读该气泡原文，不允许因为想用语音就合并成一整段、跳过分泡，也不必为了发语音额外拆句。可以全部文字、全部语音或混合，按本人意愿决定。
            - 气泡边界只能通过 JSON 的 bubbles 数组表达，不要在任何正文里输出 BUBBLE、分隔符或解释控制协议。普通换行只是气泡内排版，不代表发送。
            - 若上下文有真实用户消息ID，确实想引用时在 text 开头用 ⟪QUOTE:消息ID⟫；只回应新消息且指代明确时不必引用。只允许引用明确给出的真实ID。
            - 只有本人真的很想长期留住某条用户消息时才在 text 开头用 ⟪FAVORITE:消息ID⟫。收藏不是点赞，不必为了展示能力频繁触发；ID同样必须真实。引用和收藏可以同时出现，也可以都不出现。
        """.trimIndent()
        val phoneSleepRule = if (sceneContext.contains("电话")) """
            【通话中的真实哄睡选择】根据对方整句原话、前后文及真实诉求，自主判断是否切换电话的陪睡状态。
            当对方确实想被哄着入睡、午休时自然陪睡，返回 sleepDecision="enable"；若明确想退出哄睡、恢复日常通话，返回 sleepDecision="disable"；其余不填或 keep。
            不以固定关键词认定：讨论别人睡眠、引用台词、否定句、简单说晚安不等于要求开启；她说“宝宝陪我睡会儿”“困了想听你哄我”也可理解为邀请。角色可以按性格回应，但答应陪睡时必须交出真实状态决定，不能只口头答应。已在哄睡状态且仍在哄睡，不要反复切换。
            sleepDecision 只是本通电话的真实控制字段，不是台词，不替代承诺叫醒的 commitmentChanges；有明确午睡截止时间应另行创建真实叫醒约定。
        """.trimIndent() else ""
        val currentPhoneMusic = if (phoneCallId.isNotBlank()) {
            // Fresh, read-only playback evidence before every role decision.
            // A manual pause during silence must invalidate an older music plan
            // even when the user has not spoken another sentence.
            com.jiacimu.lulu.data.DeferredCallMediaAction.observeBeforeUserTurn(
                appContext, characterId, phoneCallId)
            com.jiacimu.lulu.data.DeferredCallMediaAction.musicContext(characterId, phoneCallId)
        } else "未提供本次真实通话会话"
        val sharedReplyAndDeviceRule = if (!rewriting && (!callSilence || sceneContext.contains("电话"))) """
            【同一模型决定可以兼有回复与设备行为】
            一次决策可以同时决定你想怎么回答，以及是否调用真实媒体工具。
            若既想说话又想操作已授权能力，可以在一次决策中使用 action=reply 配合 steps=[{"tool":"真实能力名","args":{},"requiresPreviousSuccess":false},...]；按顺序执行多项实际动作，记录每一项结果，不限于音乐或音量。若希望保持安静，只操作设备，通话中可选 action=silent 加 steps。依赖前一步完成的动作写 requiresPreviousSuccess=true；尚在等待同意的屏幕请求不是已看见画面，需要真实回执才能继续基于画面作判断。
            若文字聊天想先回复再休眠，选择 action=reply，steps 最后填写 {"tool":"go_offline","args":{},"requiresPreviousSuccess":true}。必须等真实消息送达后下线；这不是结束电话。多步顺序由你自主决定。
            媒体操作还可选择 control_music、list_media_sessions；通话内获准使用闹钟能力时还可选 ring_in_call_wake（args.seconds=1到15，播放本地铃声，不会生成来电页面）。按你真正选定的顺序执行，不因任意固定动作数量上限丢掉决定；实际授权、设备执行能力及回执仍须逐项校验。
            private chat 仍用 speechIntent/dialogueMove；phone 直接返回 text。
            你也可以只说话、只操作、或者真的选择沉默，按自己的个性判断，不需填满字段。
            即将执行的动作不能预先说成已经完成。外部工具会真正执行、分别产生回执。
            音量“再高点”用 adjust+正的 deltaPercent，不要重复设定绝对值。
            不允许用 sideActions 绕过角色权限、访问其他人的资料或强迫用户接受操作。
        """.trimIndent() else ""
        val phoneMediaRule = if (sceneContext.contains("电话")) """
            【通话中的自然语言理解由你本人负责】
            本通电话实际观察到的音乐状态：$currentPhoneMusic
            如果本轮确实收到了用户的新话，结合原话、否定、转折、时间指向、此前经历与真正意图自行判断：
            mediaUnderstanding={"music":"unchanged|stop|play","wake":"unconfirmed|confirmed","reason":"你对这轮原话的具体理解"}。
            music=stop 表示对方现在希望停止或保持停止，不是她仅仅叙述过去曾经关过音乐。
            music=play 仅表示对方本轮新的确希望再次播放，不能因为历史上曾下令、谈论过音乐或你想旧曲重播而选择。
            music=unchanged 表示没有新音乐要求；默认为 unchanged。
            wake=confirmed 只在用户这轮确实确认已经醒来时填写；说话、翻身、没有回应、提问或否认自己醒了都不等于确认。
            没有真实新用户发言时这两个字段都省略：你仍然可以按自己的当前意愿选择获授权行动，但旧的用户播放意愿不会因你的定时感知而复活。
            此字段记录你理解出的意图，不直接替代动作：是否暂停/恢复音乐仍由已授权执行器验证，真实结果决定最终状态。
            【通话中的真实音乐安排】
            已确认的本通电话音乐状态：$currentPhoneMusic
            你可以选择让手机上真正存在的音乐播放器稍后播放音乐，同时与她保持通话。
            若你决定承担这项行动，在本次 reply JSON 加入字段
            "afterCallMedia":{"trigger":"in_call","delaySeconds":180,"command":"play","volumePercent":35}。
            trigger=in_call 表示【本通电话持续接通】且从你作出决定起计时；通话结束就取消。
            如果对方明确要挂断以后才播，改用 trigger=call_end，若不限定通话可用 now。
            时长根据当下真实要求与上下文判断，例如三分钟=180秒、半分钟=30秒。
            这不是固定音乐脚本。旧音乐命令已执行后绝不能在后续感知中机械重放。尤其对方自己暂停了音乐，就尊重她的最新选择，不能因旧约定、通话结束或说完一句话自动继续播放。你可以选择是否接受，不能只在台词里答应，
            必须给真实可执行的数据；成功与否还要以手机执行器回执为准。
            只有已授权的媒体会话能被控制，并不能虚构歌曲或播放器。
            音乐开始后电话仍可保持安静陪伴；你想说话时播放器暂时暂停。
            你可以在 reply JSON 选择 "musicAfterSpeech":"resume" 或 "stay_paused"，
            由你决定说完要不要继续播放；省略表示保持暂停，不会擅自续播。
            系统会对媒体音量执行额外限幅。
        """.trimIndent() else ""
        val live2dDecisionContext = if (sceneContext.contains("电话") &&
            phoneCallId.isNotBlank() && !rewriting) {
            com.jiacimu.lulu.Live2DVisualActionBus.capabilityNote(appContext, characterId, phoneCallId)
        } else ""
        val voicePerformanceRule = if (sceneContext.contains("电话"))
            com.jiacimu.lulu.VoicePerformance.phoneInstruction(appContext,
                sleepMode = sceneContext.contains("哄睡")) else ""
        val characterHangupRule = if (sceneContext.contains("电话") && onCharacterHangup != null) """
            【角色可以真的主动挂断】
            只有当前角色发自内心想结束这通电话时，才在 action=reply 的 JSON 中额外返回 "endCall":true。
            可以因为困倦、自然聊完、有已知的其他事情、吵架想冷静等选择离开；也可以舍不得挂。不要随机挂断、机械挂断、编造紧急事故或陌生人来电。
            text 应是确实要说的最后一句口语，可自然道别、说明想法或设立边界；结束语实际播放完毕后由电话系统真正挂断并记录。没有想挂断就省略该字段。
            不要把“我先挂了”当成完成挂断的证明，必须设置 endCall=true。只有发言才可申请结束，不输出无声终止的空 text。
            【稍后结束本次通话】如果本人明确答应用户“半小时后挂断当前电话”等要求，可在本轮 reply JSON 中额外填写 "hangupAfterSeconds":1800，表示从用户本轮说出请求时起1800秒后真正挂断【当前】通话；后续正常对话不重置计时。如果答应取消原来的定时挂断，填写0。没有新决定就省略。
            这是活跃电话会话内的计时控制，不是“明天打电话”的日程，绝不能通过 commitmentChanges、start_call、alarm_only 或 plan_personal_activity 来假装执行挂断。单独的每日监督、次日来电等约定应另建独立任务，不得把两个时间合并到同一个 goal/dueAt。若是精确钟点，根据提供的当前本地时间换算秒数；不要把当天凌晨错排到下一天的零点。尚未确认同意时不要设置；不能同时设置 endCall=true 和延时挂断。
        """.trimIndent() else ""
        // Group members must decide and speak within their own single model turn.
        // Private text chat retains its deliberate two-stage renderer.
        val isGroupChat = sceneContext.contains("群聊《")
        val separateExpression = !isGroupChat && CharacterDecisionProtocol.usesSeparateExpression(sceneContext)
        val expressionConversation = if (separateExpression && !rewriting && currentSessionId.isNotBlank() &&
            MigratedDomainStores.chat.conversations.value.any {
                it.id == currentSessionId && it.characterId == characterId && it.groupChat == null
            }) com.jiacimu.lulu.data.PrivateChatExpressionContext.conversation(
                characterId,
                MigratedDomainStores.chat.messages(currentSessionId).value.filter {
                    com.jiacimu.lulu.data.DigitalLifeProfileStore.allowsTimestamp(characterId, it.createdAt)
                },
                originalUserEvidence.mapTo(mutableSetOf()) { it.id }, userText,
            ) else emptyList()
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
        val decisionFormatRule = if (rewriting) """
            这是已有聊天的重新回复：不是新的用户消息。保留全部原本人格、记忆、判断、主观心声和表达两阶段架构，
            但只允许重新决定这轮该怎样用 reply 表达，不能选 tool 或 silent，不能重做已经执行的动作。
            决策阶段只输出一个尽快闭合的 JSON，必须有 action=reply、speechIntent 和 dialogueMove。
            speechIntent 是想传达的真实意思，不是已经说出口的话；表达层将负责分气泡。
            dialogueMove 可用正常聊天的 type/target/contentIntent/maxBubbles（1到3）。
            若真正产生新的未说出口想法，可选 innerThought，必须同时提供
            innerThoughtBasis={"focus":"具体触发点","change":"和上一刻有何不同","unsaidWhy":"为何没有说出口"}；
            如情绪、内在动机有可靠变化可选 innerLife、appraisal、mood。
            若上一轮真实发生了争执或对方明确受伤，应重新判断自己是否仍有未解决的个人心事，
            而不是因已经说过道歉就自动当作和好；如角色确实想继续修复，可在
            appraisal.pendingConcerns 中开放不同主题的可延续心事（每条有独立 topicKey、focus、whyItMatters、priority、hesitation、possibleNextStep），或续想相同 threadId；老的单条 pendingConcern 仍兼容。
            也允许多个不同甚至矛盾的念头保存在 innerLife.thoughts，行动由后续本人自主选择，
            不要把一种可能的修复方式写成必做规则、擅自认定用户已原谅或强制连发道歉。
            不能为了凑结构创造情绪、心声或未发生的事件，禁止重复执行工具。
        """.trimIndent() else if (callSilence) """
            这是电话中的真实观察，没有新增用户发言；观察可能是安静陪伴，也可能是此前工具刚返回的结果。
            依据本轮程序提供的观察内容，可选择 silent 或 reply；没有新增真实理由绝不可重复上一轮工具动作。若有新的叫醒进展或实际需要，允许 silent 搭配 sideActions 执行获授权的手机音乐、音量或通话内铃声；说话会与音乐争夺音频，纯音乐/铃声叫醒时优先选择 silent。
            安静陪伴返回 {"action":"silent","reason":"此刻选择安静的个人原因","statusText":"持续处境","gesture":"自己的动作","innerThought":"未说出口的念头","mood":"当前感受"}。
            真想说话才返回 {"action":"reply","text":"自然可朗读的口语"}；若只操作手机可选 {"action":"silent","reason":"我想先让音乐继续响一会儿","sideActions":[{"tool":"control_volume","args":{"action":"adjust","deltaPercent":5}}]}。每次先观察真实播放器状态，明确醒来才能视为叫醒目标达成；没有回应也不能当成一定未醒。不能把空回复、结构化状态或心声读出来。
        """.trimIndent() else if (separateExpression) """
            只返回完整 JSON，先自主选择 action=reply、tool 或 silent。选择 reply 时写 speechIntent 和 dialogueMove；speechIntent 是“具体想让对方知道/确认的内容与事实边界”，不是台词设计稿，不要提前写 text。
            想发一张真正的表情包时，仅从上面程序列出的已入库 stickerId 选填 stickerId；可选 stickerOnly=true 表示此刻只想独立发一张图、不说一句多余的话。单发图片仍用 action=reply；程序真实发送选中的图，不再强迫语言表达层配文字。若想文字加图片就不要填 stickerOnly；不能猜 ID，也不用每次都配一张。
            dialogueMove={"type":"acknowledge|answer|ask|backchannel|self_repair|other_initiated_repair|candidate_understanding|disagree|tease|reassure|topic_shift|defer|decline|close|share","repairFormat":"none|open|candidate","target":"当前局部互动目标","candidate":"只有候选修复时才写一个候选","confidence":0到1,"contentIntent":"本轮内容意图","maxBubbles":1到3}。
            speechIntent 只选这轮最想传达的核心态度或答案，确有需要再带一个具体细节；不用列出整段情绪、所有理由和比喻寓意。把次要推论、反复道歉、责任复盘和备选方案留在内心；已经能让对方明白就交还话轮，等她接话再决定往哪里聊。只有用户明确索要详细解释、复杂分析或存在不可省略的事实，才规划多点说明。尚未说尽不是待办，不为凑完内容安排自动补发或新挂心。
            speechIntent 写你此刻真正想告诉对方什么，而不是把对方情绪当成待修复的任务。她在说现实困难时，担忧本身可以成立；先分清她是在倾诉、讨论还是请求办法。不要仅因她有负面情绪，就默认目标是劝她休息、纠正她的想法或让她安心；角色自己想关心、表达不同看法或追问，也应针对她实际说的事情。
            如果内心有几种感受，只选择这一拍最愿意让对方感觉到的一种主导态度；可在原有 appraisal.expression 写 stance、subtext、device、restraint（都可省略）。subtext 是想留在话外的情绪，restraint 是主动不说破的部分；不要把它们写成示范台词、情绪清单或新承诺。简单闲聊可以没有这些字段，复杂问题也不因追求含蓄而漏答。\n            speechIntent 不要预先指定昵称、比喻、梗、反问句式或完整的表演台词，这些由表达层现场决定。但如果角色已经真心决定尝试一个具体而温和的社交小动作，可单独选填 socialAttempt="这次我打算怎样主动示好/逗她/靠近她"；这里写的是角色自选的行动意图，不是对方下达的任务。表达层应真的用文字、拟声或语言实现虚拟互动，不得虚构现实中已经碰到用户。
            回复例：{"action":"reply","speechIntent":"承认刚才理解偏了，让她补充真正意思","dialogueMove":{"type":"other_initiated_repair","repairFormat":"open","target":"刚才的误解","contentIntent":"做最小修复","maxBubbles":1},"reason":"先恢复共同理解"}
            沉默例：{"action":"silent","reason":"现在心情复杂不想回复","innerThought":"我得自己消化一下","innerThoughtBasis":{"focus":"用户刚才明确拒绝了邀约","change":"从期待转为需要自己消化失落","unsaidWhy":"现在不想把情绪压给用户"}}
            工具动作仍必须按下方的真实工具协议返回 tool 与 args；没有回执就不能假装成功。
            如果本轮行动是为了推进之前一件仍未解决的私人心事，可带 concernId=上下文里真实存在的 threadId；必须依据执行回执确认行动结果。没有对应心事就留空，不要随意创造。
            角色有自己的立场，可以反问、换话题或暂时不回复；被问到紧急、重要的事情时要考虑实际影响，不机械冷处理。
        """.trimIndent() else if (isGroupChat) """
            【群聊：一次调用只决定当前这一个角色】
            你只扮演当前 characterId 的一个真实成员；其他成员的话已经在群聊历史中，绝不代他们发言。
            可以选 action=silent（此刻不想说话）或 action=reply，直接写本人的 text，不需要额外表达调用。
            回复只返回自己的自然口语，可分一到几个短气泡；不要给其他人写台词或生成 NEXT/END 控制命令。
            如产生真实的学习、收藏或其他自主行动意愿，可选 action=tool 并附上受授权的能力及参数；执行成功与否必须等回执。
            没有真正变化的情绪、想法、关系和目标就不要反复输出相同状态。
            只输出一个完整 JSON，如 {"action":"reply","text":"我自己的群聊发言","reason":"想接话的简短原因"}；
            或 {"action":"silent","reason":"此刻为什么不想接话"}。
        """.trimIndent() else """
            这是电话的低延迟通道，普通交谈一般给可以直接念出的 text；若确实想不说话而操作已授权的设备，允许 silent 配合 sideActions，不能返回仅有 speechIntent 的空发言。
            {"action":"reply","text":"自然的电话口语","statusText":"持续状态","gesture":"神态","innerThought":"未说出的念头","mood":"心情"}
            【电话里可以当场收藏，无需先挂断或先回复另一次电话】
            你若真正决定把提供的某条准确用户消息保存在生活记录的「收藏」，可以在正常的电话回复里同时写
            {"action":"reply","text":"不预言执行结果的自然口语","phoneFavorite":{"messageId":"真实用户消息ID","reason":"自己为何想收藏这句原话"}}。
            注意 phoneFavorite 是本人这次真实选择的副动作，不只是内心想到；系统会在播报收藏结果前校验消息和原始证据、执行已有收藏工具，成功才说成功，失败会给出真实原因。
            如果根本不需要说话，也可直接选择 action=tool、tool=favorite_user_message 的旧方式。
            如果真的想收藏，可以选填 phoneFavorite；字段漏填时，只要你当下的真实心声或回复明确表达了收藏决定，程序也会尝试收藏刚刚真实收到的原话。格式不完整不应让整通电话回复失败。
            如果已经决定现在收藏，就直接行动而不是说“挂了以后再操作”；犹豫或暂不收藏时不要填写。
            如果决定以后再做，应把有真实目标和下一步的未完成意愿放进 appraisal.pendingConcern；心声、后续口头承诺或意图字段本身不会自动执行工具。明确决定在电话里稍后放歌时，必须使用上文 afterCallMedia，不要只留口头保证。
        """.trimIndent()
        val disputeNeedsReview = com.jiacimu.lulu.data.CharacterAccountabilityContext
            .let { it.isUnmetPromiseChallenge(userText) || it.isWakeExplanation(userText) }
        // This is deliberately AFTER message persistence, not a photo-picker blocker.
        val chatPhotos = if (!sceneContext.contains("电话") && userEvidenceIds.isNotEmpty())
            com.jiacimu.lulu.QqChatImageModelInput.prepare(appContext, characterId, userEvidenceIds)
        else com.jiacimu.lulu.QqPreparedChatPhotos(emptyList(), 0, 0)
        // Explicit requests are dispatched through the actual tool router. Do
        // not let a hallucinated model capability list veto a real app feature.
        val directXhsRequest = if (!rewriting && !sceneContext.contains("电话"))
            com.jiacimu.lulu.data.XhsExplicitReadIntent.parse(userText)
        else null
        val directXhsResult = directXhsRequest?.let { request ->
            val actualArgs = JSONObject()
            if (request.url.isNotBlank()) actualArgs.put("url", request.url)
            else actualArgs.put("keyword", request.keyword)
            com.jiacimu.lulu.data.ToolRouter.execute(
                appContext, characterId, "read_xiaohongshu", actualArgs,
                requestId = "direct-xhs-" + groundingEvidenceId.ifBlank {
                    userEvidenceIds.lastOrNull() ?: userText.hashCode().toString()
                },
                userRequested = true, causeEvidenceId = groundingEvidenceId,
            )
        }
        val planner = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = buildString {
                appendLine("当前时间：${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now.atZone(zone))}")
                appendLine("当前时区：${zone.id}")
                appendLine(com.jiacimu.lulu.data.CommitmentDecisionProtocol.context(
                    CommitmentTaskStore.active(characterId), now, zone))
                appendLine("【角色本轮可以选择的定时叫醒方式】真实来电start_call：" +
                    (if (MigratedDomainStores.characters.get(characterId).contactPolicy.proactiveCallsEnabled)
                        "已允许" else "此角色未开启主动来电"))
                appendLine("本机响铃alarm_only：可申请系统闹钟，准确登记和到点通知仍须程序回执；" +
                    "普通私聊send_private_message不能冒充叫醒熟睡的人。")
                commitmentEvidence.forEach { event ->
                    appendLine("本轮用户原话发生时间=${event.occurredAt.atZone(zone)}；内容=${event.content.take(500)}")
                }
                appendLine("当前真实互动场景：$sceneContext")
                inWorldMoment?.let { event ->
                    appendLine("【程序刚记录的本轮数字世界小事】${event.summary}")
                    appendLine("角色可按自身意愿回应、主动分享或不提；不能伪造后续结果。")
                }
                appendLine("【用户现实设备与状态｜属于用户，缺失数据不得猜测】\n$deviceContext")
                appendLine(com.jiacimu.lulu.data.CharacterPerceptionContext.render(observedWorld))
                appendLine(com.jiacimu.lulu.data.CharacterLifeFollowThroughStore.briefContext(characterId, now))
                appendLine(com.jiacimu.lulu.data.CharacterUnfinishedActionContext.context(appContext, characterId, now))
                if (history.isNotBlank()) appendLine("最近对话（这是已经发生完的连续过程，用来确定你此刻站在什么状态上）：\n$history")
                if (interactionContext.isNotBlank()) appendLine(interactionContext)
                // The gateway already injects this character's inner-life record
                // once in Full mode; do not duplicate the same motives and feelings.
                if (groundingContext.isNotBlank()) appendLine(groundingContext)
                if (initiativeContext.isNotBlank()) appendLine(initiativeContext)
                if (!sceneContext.contains("电话"))
                    appendLine(com.jiacimu.lulu.data.CharacterExpressionContinuity.guide())
                if (!sceneContext.contains("电话")) appendLine(com.jiacimu.lulu.StickerLibraryStore.prompt(appContext,
                    cues = userText,
                    recentConversation = history,
                    actorMood = previousPresence?.mood.orEmpty(),
                ))
                if (turnContext.isNotBlank()) {
                    appendLine("【系统提供的本轮交互元信息｜不是用户说的话，不得归因给用户】")
                    appendLine(turnContext.take(4_000))
                }
                previousPresence?.let { presence ->
                    appendLine("角色上一刻状态：${presence.statusText}；动作：${presence.gesture}；心情：${presence.mood}；没说出口：${presence.innerThought}")
                }
                appendLine("这一刻用户新增的消息：$userText")
                if (separateExpression) appendLine(com.jiacimu.lulu.data.TransientConversationStyle.context(
                    userText, history, includeExamples = false,
                ))
                if (turnEconomyContext.isNotBlank()) appendLine(turnEconomyContext)
                // The built-in Reality Window's Bangumi / Google News sources
                // are not a capability inventory for the independent Xiaohongshu
                // reader. Only an actual tool invocation can prove search outcome.
                if (userText.contains("小红书") || userText.contains("红薯") ||
                    userText.contains("xhs", ignoreCase = true) ||
                    history.takeLast(500).contains("小红书")) {
                    val xhsCapability = com.jiacimu.lulu.data.CapabilityRegistry.find("read_xiaohongshu")
                    val hasReader = xhsCapability != null
                    val permitted = xhsCapability != null &&
                        com.jiacimu.lulu.data.CapabilityRegistry.allows(
                            appContext, characterId, xhsCapability, false,
                        )
                    val currentlyAvailable = com.jiacimu.lulu.data.XhsReadingEngine.available(appContext)
                    appendLine("【程序核实的小红书能力状态｜与现实窗口来源列表无关】")
                    appendLine("独立 read_xiaohongshu 工具已注册=$hasReader；角色有只读授权=$permitted；当前前台浏览会话可尝试读取=$currentlyAvailable。")
                    appendLine("这三项仅表示能力/运行条件，不是已经搜索成功、拿到正文、页面网络正常或曾核对过后台。")
                    appendLine("不能因为现实窗口只列出 Bangumi/Google News 就断言小红书搜索没接入；也不能谎称刚去后台核实。")
                    appendLine("除非同轮提供真实工具回执，否则只能如实说明是否可尝试、仍未验证实际搜索结果。")
                }
                // Phone speech is persisted as an actual user message. Provide
                // real, role-visible IDs so the same existing favorite tool
                // can run during the call instead of becoming a deferred wish.
                if (sceneContext.contains("电话") && !callSilence && !rewriting)
                    appendLine(com.jiacimu.lulu.data.CharacterSocialActionOpportunities.prompt(characterId))
                if (chatPhotos.promptNote.isNotBlank()) appendLine(chatPhotos.promptNote)
                if (directXhsResult != null) {
                    appendLine("【本轮已由真实执行器尝试小红书读取｜不可重复执行】")
                    appendLine(directXhsResult.take(3_700))
                    appendLine("应根据实际 success/status 和正文证据回应用户。失败须承认，不能说没有注册这个工具，也不能把首页读到的另一篇说成搜索成功。此轮已调用，不要再次选择 read_xiaohongshu。")
                }
                // Existing obligations are already delivered by CharacterRuntime
                // in the gateway. Only the current-message challenge is local.
                appendLine(com.jiacimu.lulu.data.CharacterAccountabilityContext.challengeGuidance(userText))
            },
            instruction = if (sceneContext.contains("哄睡")) """
                【通话哄睡：单次完整长篇语音】你是当前角色，不是朗读系统或睡眠客服。
                严格基于本次对话、上一段真实已播的哄睡内容和今晚的偏好，
                写一段完整连贯、适合慢慢朗读的自然口语。不要复述刚才讲过的
                意象、许诺、句式、故事开头或同一个部位；故事推进新事件，
                身体/呼吸引导推进未做过的下一小步。
                以场景里给出的字数范围为主，不截成两三句就结束。
                轻柔而有个人性格，减少问句、催促、机械称呼和空泛保证；
                不需要对方回答，不假设她睡着了，不能把虚构故事当共同回忆。
                只输出一个紧凑 JSON：
                {"action":"reply","text":"可直接朗读的完整长段"}
                如果她明确想结束哄睡、恢复正常聊天，可额外返回 "sleepDecision":"disable"；由电话执行器切换真实声线，不要只口头说切换了。
                可选 sleepContinuation 只记录本段真的讲到的进展；
                如果附加字段影响正文完整性，优先只返回 action 和 text。
                禁止工具动作、隐藏心声、章节总结和解释性代码块。
                哄睡不等于必须一直占用麦克风连接：实时转写与发声可能持续产生费用。
                如果你本人结合已经陪伴的进度、对方真实要求和自己的心情，
                真想现在结束陪伴，可以用
                {"action":"reply","text":"自己自然说出的简短结束语","endCall":true}
                来作出挂断决定；这时不需要为了凑长篇再续讲。话真正播完后才会挂断。
                没有决定挂断就继续原有的完整哄睡，不必机械地到某一分钟说再见。
                程序会独立执行三十分钟安全挂断；未来的独立叫醒闹钟不要求通话持续接通。
            """.trimIndent() else """
                你正在以当前角色自己的立场做这轮决定，而不是担任无条件回答问题的助手。
                ${CharacterDecisionProtocol.runtimePrinciples}
                ${com.jiacimu.lulu.data.CharacterConversationAgency.instruction}
                ${com.jiacimu.lulu.data.CharacterExpressionContinuity.playfulGuide}
                ${if (userText.isNotBlank() && !sceneContext.contains("电话") && !isGroupChat) com.jiacimu.lulu.data.ConversationDominantImpulse.planningGuidance(userText, history) else ""}
                ${com.jiacimu.lulu.data.CharacterExpressionGuide.channelNote(
                    if (sceneContext.contains("电话")) com.jiacimu.lulu.data.CharacterSpeechChannel.PHONE
                    else if (sceneContext.contains("群聊《")) com.jiacimu.lulu.data.CharacterSpeechChannel.GROUP_CHAT
                    else com.jiacimu.lulu.data.CharacterSpeechChannel.PRIVATE_CHAT
                )}
                $decisionFormatRule
                ${if (!rewriting && !callSilence) com.jiacimu.lulu.data.CommitmentDecisionProtocol.instruction else ""}
                ${if (reconsiderationEvidenceId.isNotBlank()) "这是用户要求重新考虑角色刚才那一轮表达，用户没有新增聊天正文。可选 reply 或 silent；不得重做旧工具、发起电话、发圈或另行调度活动。只重新判断该怎样表达自己刚才想说的意思，不回答旧消息，不捏造新的用户请求。" else ""}
                $programDialogueConstraint
                $phoneConversationConstraint
                $live2dDecisionContext

                【主观生活与取舍】
                以已有性格、真实经历、关系和未完成愿望判断本轮刺激；可以快乐、好奇、不满、矛盾、犹豫、提出反问，也可以保持安静。不要统一成温柔安慰或每轮自我剖析。
                发生真实变化才填写 innerLife；不变时完全省略：
                innerLife={"emotion":{"feeling":"感受","cause":"真实触发","otherFeeling":"并存感受","impulse":"冲动","restraint":"克制","strength":1到4,"halfLifeMinutes":30到1440},
                "motives":[{"op":"start|revise|pause|resume|release|achieve","id":"已有ID","ref":"本轮新目标短引用","aim":"愿望","why":"原因","priority":1到3,"reason":"变化依据","nextStep":"可调整的一小步","waitingFor":"正在等的条件","successCondition":"实际怎样才算完成","stopCondition":"何时停止或暂缓","completionKind":"effect|execution，默认effect","reviewAfter":"未来ISO时间","outcomeEvidenceId":"达成依据的真实事件ID"}],
                "social":{"targetId":"user","interpretation":"对用户的主观看法","reason":"实际依据","dimensions":{"trust":"up|down|same","warmth":"up|down|same","ease":"up|down|same","friction":"up|down|same","boundarySafety":"up|down|same"}},
                "selfCorrection":{"realization":"新认识","nextTime":"打算修正的做法"},
                "thoughts":[{"thought":"未说出的想法","impulse":"冲动","hesitation":"顾虑"}]}。
                social.dimensions 只表示“这次真实互动让某个关系维度往哪边动了一点”，不是绝对好感分；没有明确证据就省略，单次变化不能定型整段关系。\n                心声与动作不是事实或承诺；冲突想法可以并存，后续需依据实际反馈改变。可选 afterglow={"feeling":"余波","impulse":"克制或行动冲动","holdHours":1到48}；
                【一套动机来源】新的持续愿望只在 innerLife.motives 用 op=start 写入；继续同一愿望需带已有 motiveId 并使用 revise/pause/resume/release/achieve，不能重复建档。旧的 intention 字段仅用于更新仍存在的历史兼容目标（必须有准确 id 和 disposition），不再创建另一套愿望。行动关联已有动机用 motiveId，本轮 start 的动机用相同 ref 的 motiveRef；旧历史目标确实尚在履行才用 intentionId。工具完成不等于愿望已达成；涉及对方状态或感受须用effect等待真实效果。仅完成明确动作可选execution，且须关联该目标实际成功的回执。achieve须依据successCondition和真实outcomeEvidenceId，用户未回复不能证明已经睡着。重要目标可影响在线取舍，reviewAfter可让自己届时醒来重新判断。若本轮既新建动机又决定稍后执行自己的活动，可给新动机 ref，并在 appraisal.ownActivity.motiveRef 填同一 ref；旧动机在 ownActivity.motiveId 填准确ID。等待条件和重新考虑时机只影响后续选择，不能直接触发工具。

                【已有真实手机能力】
                调用格式：{"action":"tool","tool":"能力名","args":{...}}。读取状态：get_battery、get_location、get_current_app、read_recent_notifications(args.limit)、
                read_screen（仅页面文字）；经主人授权的真正单帧视觉工具 capture_screen（args={}），发送可点击返回邀请 summon_lulu（args={}），准确授权状态见下方实时能力记录；闹钟：create_alarm(args.triggerAt=带时区ISO时间,args.label)、list_alarms、cancel_alarm(args.id)；
                音乐：list_media_sessions 查看已有播放器的标题、歌手、专辑和播放状态（只是元数据，不等于真正听过歌）；主人可在角色授权页主动分享音频给听觉模型，真实分析会作为有来源的观察进入你现有的统一角色上下文。你可形成自己的欣赏或不喜欢，不按模板固定感受；不能仅凭曲名编造听到的乐器或歌词。control_music(args.command=play|pause|next|previous,args.packageName=可选，填查询返回的包名)。只有通知读取权限和当前媒体会话可用时才能控制，不能假装已经播放某首未找到的歌曲；外部音乐 App 是否执行以结果为准。角色有媒体授权时，还可调用 control_volume(args.action=status|set|adjust|restore,args.targetPercent=0到100,args.deltaPercent=-50到50)；“再大一点”用adjust和正的deltaPercent，不是把set重复调用，只调媒体音量，耳机限制35%、其他设备65%，绝不改来电/勿扰；需要叫醒时由你真正决定是否播放、是否调整，不要按固定流程放歌。
                界面操作：screen_action(args.name=back|home|recents|notifications|quick_settings)、click_text(args.text)。
                位置只用工具返回的 readableAddress；定位老旧/模糊要说明，不能猜具体建筑。
                需要闹钟/操作/设备实时状态时调用工具，不假装完成；日期不明时追问，禁止自行猜时间。
                屏幕操作必须有用户明确授权，一次仅执行一个动作。当前已经在私聊/群聊时直接 reply，不要重复调用向当前会话发送同样的话。
                【小红书真实阅读能力】
                露露机已实现 read_xiaohongshu 工具，角色可以按关键词搜索、看首页推荐或按用户给的具体链接读帖。一轮工具调用只核实并打开一篇帖子，**这不是每天只能读一篇的限额**；后续有新的真实阅读理由时可以再次调用。不能声称应用没有该工具，也不能自创每天阅读数量限制。未实际调用时不得说“刚在后台检查过能力列表”。
                工具始终已注册，浏览器当前是否能访问网页是一次调用的执行结果，不是角色有无此能力。只读小红书搜索可以按自己的兴趣选择 action=tool, tool=read_xiaohongshu，args={"keyword":"自己想搜的内容"}；读用户指定原帖则 args={"url":"真实原帖官方链接"}。搜索网页失败后说清楚“这次网页没搜出来”就够，不要编造“我没有工具权限/系统没接入/我在后台查过权限”等没有实际回执的结论。
                ${if (com.jiacimu.lulu.data.XhsReadingEngine.available(appContext)) "当前存在可复用的阅读会话，实际搜索仍以搜索结果和笔记正文为准。" else "当前暂未确认可复用的阅读会话；角色仍有工具入口，可以按真实意图尝试一次，失败只依据工具回执说明具体原因，必要时请用户打开内置小红书页面。"}
                未获得正文/图片读取成功回执时，不能编造某篇帖子或图片的内容。用户给的链接只是用户分享了网址，不能据此说已经看过全文；如果分享卡片携带真实已提取正文，你可根据提供的原文回应，但尚未输入的图片、评论仍不能装作看过。对外部内容形成的是自己的感想，不是自己的亲身经历。
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
                $phoneSleepRule
                $phoneMediaRule
                $sharedReplyAndDeviceRule
                $characterHangupRule
            """.trimIndent(),
            source = if (sceneContext.contains("电话")) "电话回复" else "聊天工具规划",
            title = title,
            // Phone speech should be a natural turn, not an essay. Keep enough
            // headroom for the JSON state without paying for runaway monologues.
            maxTokens = when {
                sceneContext.contains("哄睡") && sceneContext.contains("长故事优先") -> 5_000
                sceneContext.contains("哄睡") -> 3_800
                rewriting -> 2_400
                separateExpression -> 1_450
                sceneContext.contains("电话") && userText.length < 280 -> 1_500
                else -> 2_400
            },
            // A complaint about unfulfilled responsibilities needs a checked complete response,
            // not an irreversible stream of premature accusations.
            streamResponse = !separateExpression && onReplyStream != null && !disputeNeedsReview,
            onStreamText = if (separateExpression || disputeNeedsReview) null else onReplyStream,
            imageDataUrls = chatPhotos.urls,
            connectionOverride = connection,
            memoryRequest = UnifiedMemoryRequest(
                currentInput = userText,
                sceneContext = sceneContext,
                recentContext = history,
                taskIntent = if (rewriting) "使用统一人格和两阶段表达链路重写上一轮，不执行工具" else "判断是直接回复还是执行一个真实手机工具",
            ),
        )
        currentCoroutineContext().ensureActive()
        if (planner.isFailure) return planner
        val plannedReply = planner.getOrThrow()
        val firstParsedPlan = parsePlan(plannedReply.text)
        // Same character/planner architecture; a malformed first JSON envelope gets
        // one bounded planner retry, not a separate personality-free response path.
        val plannerRepair = if (rewriting && (firstParsedPlan == null ||
            firstParsedPlan.action != "reply" ||
            (firstParsedPlan.speechIntent.isBlank() && firstParsedPlan.text.isBlank()))) {
            LuluAiServices.gateway.generate(
                characterId = characterId,
                facts = buildString {
                    appendLine("重新回复仍属于同一角色的同一轮对话。")
                    appendLine("原本需要回应的用户内容：${userText.takeLast(1_500)}")
                    appendLine("之前真实的交流：${history.takeLast(2_500)}")
                    appendLine("角色状态：${com.jiacimu.lulu.data.CharacterInnerLifeStore.compactContext(characterId, now)}")
                    appendLine("上一份不完整或错误的决策草稿只供参考：${plannedReply.text.take(1_000)}")
                },
                instruction = """
                    继续作为同一角色重新思考本轮表达，不更改已发生事实，不执行工具、不选择 silent。
                    只返回一个完整紧凑的 JSON 对象，采用与正常聊天相同的决策字段：
                    {"action":"reply","speechIntent":"这一轮具体想表达的意思",
                     "dialogueMove":{"type":"answer","target":"当前交流意图","contentIntent":"具体想表达什么","maxBubbles":2}}
                    只有确实发生新的私有心理变化时才可选 innerThought、innerThoughtBasis、innerLife、appraisal、mood。
                    若有 innerThought，basis 应提供 focus 以及 unsaidWhy/conflict/change，不重复对外说的话。
                    只规划思想和说话意图，下一阶段仍由正常聊天表达层生成气泡。
                """.trimIndent(),
                source = "聊天决策结构补全",
                title = title,
                maxTokens = 1_300,
                connectionOverride = connection,
                memoryRequest = UnifiedMemoryRequest(
                    currentInput = userText,
                    sceneContext = sceneContext,
                    recentContext = history,
                    taskIntent = "补全一次结构化聊天决策，保持原有角色人格和心理架构",
                ),
            ).getOrNull()?.text?.let(::parsePlan)
        } else null
        val parsedPlan = plannerRepair?.takeIf { it.action == "reply" } ?: firstParsedPlan ?: run {
            // The re-answer must not bypass character judgement and private-state planning.
            // Only ordinary legacy chat/phone may fall back directly to raw speech.
            if (rewriting) return Result.failure(IllegalStateException(
                "重新回复的角色决策结构仍不完整，未替换旧消息、也未写入心声"
            ))
            val fallback = com.jiacimu.lulu.data.ModelStructuredOutput.completedReplyText(plannedReply.text)
                ?: com.jiacimu.lulu.CallReplyStream.completeReplyText(plannedReply.text)
                ?: plannedReply.text.takeIf { value ->
                    value.isNotBlank() && !value.trimStart().startsWith("{") &&
                        !value.trimStart().startsWith("```")
                }
            // Incomplete JSON commands never run, but malformed optional
            // metadata should not fail an entire phone turn. One bounded
            // speech-only recovery uses the SAME configured character model.
            val recoveredPhoneSpeech = if (fallback.isNullOrBlank() &&
                sceneContext.contains("电话") && !callSilence) {
                LuluAiServices.gateway.generate(
                    characterId = characterId,
                    facts = "你仍在刚才的同一通电话。用户刚说：${userText.take(1000)}。之前模型返回的文字不完整：${plannedReply.text.take(600)}。",
                    instruction = "保持本人既有性格、称呼与感情，直接说自然口语，不要 JSON、代码块、字段名，也不要假装做过工具动作。",
                    source = "电话口语·格式失败后的柔性恢复",
                    title = title,
                    maxTokens = 380,
                    connectionOverride = connection,
                ).getOrNull()?.text?.trim()
                    ?.takeIf { it.isNotBlank() && !it.startsWith("{") &&
                        !it.startsWith("```") }
            } else null
            val visible = fallback?.takeIf(String::isNotBlank) ?: recoveredPhoneSpeech
            if (visible.isNullOrBlank()) {
                if (sceneContext.contains("电话")) return Result.success(plannedReply.copy(
                    text = "", disposition = CharacterDecisionProtocol.SILENT,
                ))
                return Result.failure(IllegalStateException("模型没有产生可核实的完整回复"))
            }
            if (sceneContext.contains("电话") &&
                com.jiacimu.lulu.PhoneSpokenContentBoundary.leaksInternalAnalysis(visible)) {
                if (callSilence) return Result.success(plannedReply.copy(
                    text = "", disposition = CharacterDecisionProtocol.SILENT))
                return Result.failure(IllegalStateException(
                    "角色把内部观察误写为口语，已拦截发声，请重试"))
            }
            val verifiedSpeech = repairUnfairSpeech(visible)
                ?: return Result.failure(IllegalStateException(
                    "本轮角色回复包含无事实依据的归责，重新表达仍未通过核验；原消息未丢失"
                ))
            return Result.success(plannedReply.copy(text = verifiedSpeech))
        }
        if (callSilence && parsedPlan.action !in setOf("reply", CharacterDecisionProtocol.SILENT))
            return Result.failure(IllegalStateException("通话沉默观察不执行外部工具动作"))
        var plan = parsedPlan.copy(innerThought =
            com.jiacimu.lulu.data.CharacterAccountabilityContext.guardUnfoundedInnerBlame(
                userText, parsedPlan.innerThought,
            ))
        // The character's own model interprets natural speech. Only an actual
        // new user turn can revise the previous music-stop boundary or confirm
        // the wake goal. Time-based silence and old chat history cannot.
        if (!rewriting && !callSilence && sceneContext.contains("电话") &&
            phoneCallId.isNotBlank() && userText.isNotBlank()) {
            val understanding = plan.mediaUnderstanding
            val musicChoice = com.jiacimu.lulu.data.CallMediaSemanticBoundary.musicChoice(
                understanding, userText.isNotBlank())
            if (musicChoice in setOf("stop", "play")) {
                com.jiacimu.lulu.data.DeferredCallMediaAction.applyUserMediaUnderstanding(
                    appContext, characterId, phoneCallId, musicChoice)
                if (musicChoice == "stop")
                    com.jiacimu.lulu.data.WakeMusicCoordinator.cancelPending(appContext, characterId)
            }
            if (com.jiacimu.lulu.data.CallMediaSemanticBoundary.confirmedAwake(
                    understanding, userText.isNotBlank())) {
                val actuallyCompleted = com.jiacimu.lulu.data.WakeGoalVerification.confirmFromModel(characterId)
                if (actuallyCompleted) {
                    com.jiacimu.lulu.data.DeferredCallMediaAction.onWakeConfirmed(
                        appContext, characterId, phoneCallId)
                    com.jiacimu.lulu.data.WakeMusicCoordinator.cancelPending(appContext, characterId)
                }
            }
        }
        // A phone media plan is a side action chosen in the SAME role turn.
        // It cannot be treated as achieved merely because the role said it.
        val mediaReceipt = if (!rewriting && !callSilence && sceneContext.contains("电话") &&
            plan.action == "reply" && plan.afterCallMedia != null && phoneCallId.isNotBlank()) {
            val sourceTurn = userEvidenceIds.lastOrNull()
                ?: "phone-${phoneCallId}-${now.toEpochMilli()}"
            val receipt = com.jiacimu.lulu.data.DeferredCallMediaAction.register(
                appContext, characterId, phoneCallId, sourceTurn, plan.afterCallMedia)
            runCatching {
                com.jiacimu.lulu.data.SharedExperienceTimeline.record(
                    eventId = "phone-media-decision:$characterId:$sourceTurn",
                    characterId = characterId, channel = "电话音乐安排",
                    speaker = "设备执行器", content = receipt, occurredAt = now,
                    source = "deferred-call-media", triggerExtraction = false,
                    evidenceKind = com.jiacimu.lulu.data.EventEvidenceKind.ToolResult)
            }
            if (receipt.startsWith("已登记")) {
                val trigger = plan.afterCallMedia.optString("trigger")
                if (trigger == "in_call" || trigger == "now") {
                    com.jiacimu.lulu.LuluVoiceCallSession.scheduleDeferredMedia(
                        characterId, phoneCallId, "$characterId:$phoneCallId:$sourceTurn",
                        plan.afterCallMedia.optInt("delaySeconds", 0))
                }
            }
            receipt
        } else ""
        val plannedSideActions = plan.sideActions
        val commitmentChanges = plan.commitmentChanges
        val commitmentReceipt = if (!rewriting && !callSilence && commitmentChanges != null) {
            runCatching {
                com.jiacimu.lulu.data.CommitmentDecisionRuntime.apply(
                    appContext, characterId,
                    "$commitmentSessionId:${commitmentEvidenceIds.lastOrNull() ?: now.toEpochMilli()}:$characterId",
                    commitmentEvidenceIds, commitmentChanges,
                )
            }.getOrElse { "约定保存/更新未成功：${it.message.orEmpty().take(180)}；不能声称已改好。" }
        } else ""
        // Common voice models sometimes complete speechIntent but omit the
        // optional final text field. Fix only the missing spoken surface instead
        // of treating a usable character decision as a failed phone request.
        if (sceneContext.contains("电话") && !callSilence &&
            plan.action == "reply" && plan.text.isBlank() &&
            plan.speechIntent.isNotBlank()) {
            val speech = LuluAiServices.gateway.generate(
                characterId = characterId,
                facts = "这是刚刚确定的本人电话表达意图：${plan.speechIntent.take(600)}。用户刚说：${userText.take(700)}。",
                instruction = "只返回简短自然口语，禁止 JSON 或状态字段；不需要重复解释或报备工具。",
                source = "电话已决定意图·补一句可听的台词",
                title = title,
                maxTokens = 300,
                connectionOverride = connection,
            ).getOrNull()?.text?.trim().orEmpty()
            if (speech.isNotBlank() && !speech.startsWith("{") &&
                !speech.startsWith("```")) plan = plan.copy(text = speech)
        }
        if (rewriting) {
            if (plan.action != "reply") return Result.failure(
                IllegalStateException("重新回复只能重新组织语言，不重做工具或沉默决定")
            )
            plan.appraisal?.remove("ownActivity")
        }
        // Important relational feedback should not lose all of its private follow-through
        // just because the model omitted one OPTIONAL JSON field. Ask the same model
        // for a bounded addendum to the SAME decision; this is not a new character,
        // an additional auto-apology, or a background action.
        if (separateExpression &&
            com.jiacimu.lulu.data.EmotionFollowThroughCoordinator.needsConcernAssessment(userText, plan.appraisal)) {
            val review = LuluAiServices.gateway.generate(
                characterId = characterId,
                facts = buildString {
                    appendLine("【现实互动】${userText.takeLast(2_000)}")
                    if (history.isNotBlank()) appendLine("【已发生的上下文】${history.takeLast(3_000)}")
                    appendLine("【原本的这轮角色判断】${plannedReply.text.take(2_000)}")
                    appendLine("【已有的私人心事】${com.jiacimu.lulu.data.CharacterOpenConcernRuntime.context(
                        com.jiacimu.lulu.data.CharacterInnerLifeStore.snapshot(characterId)
                            .optJSONArray("openConcerns"), now
                    )}")
                },
                instruction = """
                    你仍是同一个角色，继续刚才同一次私聊的内部判断，完全不生成对外台词。
                    对方明确表达了关系中实际的不愉快，但原决策漏掉了一个问题：
                    你自己是否还有未解决的事放在心上？这不是必须哄、必须原谅或必须追着对方联系的任务。
                    从自己的性格、真实经历、情绪与相互矛盾的想法出发，仔细判断。
                    如果有一件或数件真正不同主题的持续心事，在 appraisal.pendingConcerns 中分别写出
                    topicKey、status=open/revise、threadId(续想时)、focus、whyItMatters、hesitation、
                    possibleNextStep、priority(1到3)，最多四个不同主题，不能对同一问题换句话重复建档。
                    如果确实不想持续关注，给 appraisal.concernAssessment 写明自己不继续挂心的具体原因；
                    如果决定持续关注，也写这个选择的简短原因；不能凭空认定用户已经原谅。
                    可选 innerLife.thoughts 或 emotion 表示真正有变化的主观状态，不能凑数量。
                    只返回紧凑 JSON：{"appraisal":{"concernAssessment":"自己的决定与理由",
                    "pendingConcerns":[]},"innerLife":{}}。
                    没有要保存的心事就使用空数组；不执行工具、不发送新消息、不修改旧事实。
                """.trimIndent(),
                source = "聊天重大关系反馈·原决策复核",
                title = title,
                maxTokens = 1_200,
                connectionOverride = connection,
                memoryRequest = UnifiedMemoryRequest(
                    currentInput = userText,
                    sceneContext = sceneContext,
                    recentContext = history,
                    taskIntent = "同一轮决策补齐未解决心事的判断，不额外生成对外行动",
                ),
            ).getOrNull()?.text?.let(com.jiacimu.lulu.data.ModelStructuredOutput::objectOrNull)
            if (review != null) {
                val addendum = review.optJSONObject("appraisal")
                val revised = JSONObject(plan.appraisal?.toString() ?: "{}")
                if (addendum != null) {
                    for (key in listOf("concernAssessment", "pendingConcerns", "pendingConcern",
                        "possibleReadings", "tension", "uncertainty")) {
                        if (addendum.has(key)) revised.put(key, addendum.opt(key))
                    }
                }
                val extraLife = review.optJSONObject("innerLife")
                val sharedLife = JSONObject(plan.innerLife?.toString() ?: "{}")
                if (extraLife != null) {
                    for (key in listOf("emotion", "thoughts", "motives", "selfCorrection", "social")) {
                        if (!sharedLife.has(key) && extraLife.has(key)) sharedLife.put(key, extraLife.opt(key))
                    }
                }
                if (revised.length() > 0 || sharedLife.length() > 0) {
                    plan = plan.copy(
                        appraisal = revised.takeIf { it.length() > 0 },
                        innerLife = sharedLife.takeIf { it.length() > 0 },
                    )
                }
            }
        }
        if (separateExpression && plan.action == "reply" && plan.speechIntent.isBlank() && plan.text.isNotBlank()) {
            plan = plan.copy(speechIntent = plan.text)
        }
        // A specific phone wish that the role actually authored should not
        // evaporate as soon as the call finishes. Persist it in the SAME private
        // concern ledger, with a verified user message as its source, but never
        // manufacture an executed favorite from thought alone.
        val verifiedCallSource = sceneContext.contains("电话") &&
            groundingEvidenceId.isNotBlank() &&
            com.jiacimu.lulu.data.SharedExperienceTimeline.eventsByIds(
                characterId, listOf(groundingEvidenceId),
            ).any { it.evidenceKind == com.jiacimu.lulu.data.EventEvidenceKind.UserStatement }
        if (verifiedCallSource && !callSilence && !rewriting &&
            plan.action == "reply" &&
            !com.jiacimu.lulu.data.CharacterOpenConcernRuntime.hasProposals(plan.appraisal)) {
            val concern = com.jiacimu.lulu.data.CharacterDeferredFavoritePolicy.concern(
                plan.innerThought, plan.text, hasVerifiedUserMessage = true,
            )
            if (concern != null) {
                val appraisal = plan.appraisal?.let { JSONObject(it.toString()) } ?: JSONObject()
                appraisal.put("pendingConcern", concern)
                plan = plan.copy(appraisal = appraisal)
            }
        }
        // One character-authored reason protocol for text chat, phone and tools.
        // Speaking intent itself is a valid tiny everyday motive; innerThought is
        // optional and must never be fabricated to justify an outgoing action.
        if (sceneContext.contains("电话") && plan.action == "tool" &&
            plan.tool == "favorite_user_message" && !rewriting) {
            val requestedId = plan.args.optString("messageId").trim()
            val conversation = com.jiacimu.lulu.data.MigratedDomainStores.chat.conversations.value
                .firstOrNull { it.groupChat == null && it.characterId == characterId }
            val userMessage = conversation?.let { chat ->
                com.jiacimu.lulu.data.MigratedDomainStores.chat.messages(chat.id).value
                    .asReversed().firstOrNull { msg ->
                        msg.sender == com.jiacimu.lulu.data.LuluChatMessage.Sender.User &&
                            msg.status == com.jiacimu.lulu.data.LuluChatMessage.Status.Sent &&
                            msg.createdAt.isAfter(now.minusSeconds(300)) &&
                            (requestedId.isBlank() || msg.id == requestedId) &&
                            com.jiacimu.lulu.data.SharedExperienceTimeline.eventsByIds(
                                characterId, listOf(msg.id),
                            ).any { it.evidenceKind ==
                                com.jiacimu.lulu.data.EventEvidenceKind.UserStatement }
                    }
            }
            if (userMessage != null && requestedId.isBlank())
                plan.args.put("messageId", userMessage.id)
            // A genuinely chosen favorite keeps the role's own words,
            // however brief. Never fabricate four-character "motivations".
            if (plan.args.optString("reason").isBlank()) {
                val actualReason = plan.reason.trim().ifBlank { plan.innerThought.trim() }
                if (actualReason.isNotBlank()) plan.args.put("reason", actualReason.take(170))
            }
        }
        val choiceBasis = com.jiacimu.lulu.data.CharacterBehaviorMotivation.resolve(
            if (plan.action == "tool") plan.tool else plan.action,
            plan.reason.ifBlank {
                if (plan.tool == "favorite_user_message") plan.args.optString("reason")
                else ""
            }, plan.appraisal, plan.innerLife,
            speechIntent = plan.speechIntent,
        )
        // The character explicitly chose the tool in the same model turn.
        // Do not require a second prose explanation as permission to act.
        // Missing optional motivation remains missing in the trace; actual
        // capability permissions, input validation and tool results still gate
        // the real action. Never synthesize a motive from a future result.
        if (choiceBasis != null) plan = plan.copy(reason = choiceBasis.reason)
        val dialoguePlan = com.jiacimu.lulu.data.DialogueMoveEngine.resolve(
            plan.dialogueMove ?: plan.appraisal, plan.speechIntent, userText,
        )
        if (groundingEvidenceId.isNotBlank() && !rewriting) {
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
            // A tool-first choice can create an unspoken thought without
            // requiring an invented emotion or a spoken chat bubble.
            hasChosenToolAction = plan.action == "tool" ||
                (plannedSideActions?.length() ?: 0) > 0,
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
        if (!invalidBlame && !rewriting) {
            com.jiacimu.lulu.data.CharacterMotivationOwnership.updateLegacyIfPresent(characterId, plan.intention)
            val verifiedSources = originalUserEvidence.ifEmpty {
                com.jiacimu.lulu.data.SharedExperienceTimeline.recentEvents(characterId, 40)
                .filter { userText.isNotBlank() && it.evidenceKind == com.jiacimu.lulu.data.EventEvidenceKind.UserStatement &&
                    it.content.isNotBlank() && (userText.contains(it.content.trim().take(60)) ||
                        it.content.contains(userText.trim().take(60))) }.takeLast(12)
            }
            val userSources = if (callSilence) listOf(com.jiacimu.lulu.data.PerceptionStimulus(
                silenceObservationId,
                externalCallResult?.let { "本人此前查询刚收到的真实执行回执：${it.content.take(2_600)}" }
                    ?: "电话持续接通；本轮没有新增用户发言",
                if (externalCallResult == null) setOf("user") else emptySet())) else verifiedSources.map {
                com.jiacimu.lulu.data.PerceptionStimulus(it.id, it.content.take(260), setOf("user"))
            }.ifEmpty { if (userText.isBlank()) emptyList() else listOf(com.jiacimu.lulu.data.PerceptionStimulus(
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
                reconsiderationEvidenceId.isNotBlank() -> reconsiderationEvidenceId
                callSilence -> silenceObservationId
                verifiedSources.isNotEmpty() -> verifiedSources.last().id
                else -> ""
            }
            if (!callSilence && reconsiderationEvidenceId.isBlank()) {
                com.jiacimu.lulu.data.CharacterLearningLedger.observeInteraction(
                    characterId, plan.experienceLearning, plan.learningFeedback,
                    plan.learningChallenge, verifiedSources.map { it.id },
                )
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
            // The combined perception token is a *synthetic* multi-source ID.
            // Anchor enduring psychological changes to a REAL deletable user
            // message so the growth timeline can verify and retract them.
            val causalEvidenceId = interactionEvidenceId.ifBlank {
                observedSources.lastOrNull()?.evidenceId.orEmpty()
                    .ifBlank { userSources.lastOrNull()?.evidenceId.orEmpty() }
            }
            val canonicalInnerLife = com.jiacimu.lulu.data.CharacterMotivationOwnership.canonicalInnerLife(
                plan.intention, plan.innerLife,
            )
            com.jiacimu.lulu.data.CharacterExplorationContinuity.remember(
                appContext, characterId, plan.curiosity, plan.curiosities,
                (verifiedSources.map { it.id } + observedSources.map { it.evidenceId } + listOf(causalEvidenceId)).distinct(), now,
            )
            com.jiacimu.lulu.data.CharacterInnerLifeStore.observe(
                characterId, causalEvidenceId, combined?.description.orEmpty(),
                com.jiacimu.lulu.data.CharacterInnerLifeStore.withAfterglow(canonicalInnerLife, plan.afterglow, if (callSilence) sceneContext else userText),
                combined?.socialIds.orEmpty(), now,
            )
            plan = plan.copy(motiveId = com.jiacimu.lulu.data.CharacterMotiveProgress.resolveMotiveId(
                com.jiacimu.lulu.data.CharacterInnerLifeStore.snapshot(characterId).optJSONArray("motives"),
                plan.motiveId, plan.motiveRef, causalEvidenceId))
            com.jiacimu.lulu.data.CharacterInnerLifeStore.recordCausalTransition(
                characterId = characterId,
                evidenceId = causalEvidenceId,
                appraisal = plan.appraisal,
                innerLife = canonicalInnerLife,
                innerThoughtBasis = plan.innerThoughtBasis,
                selectedAction = plan.action,
                innerThought = plan.innerThought,
                reason = plan.reason,
                now = now,
                alternatives = plan.alternatives,
            )
            com.jiacimu.lulu.data.CharacterInnerLifeStore.recordInnerVoice(
                characterId, causalEvidenceId,
                plan.innerThought, now, privateDelta.fingerprint,
            )
        }
        // Persist this role's appraisal/motives BEFORE executing the chosen phone steps.
        // A new concern must exist before its own real receipt can be projected onto it.
        val sideDeviceReceipt = if (!rewriting && plannedSideActions != null &&
            sceneContext.contains("电话") &&
            (plan.action == "reply" || plan.action == CharacterDecisionProtocol.SILENT)) {
            val notes = mutableListOf<String>()
            var verifiedWakeAudio = false
            var raisedVolumeThisTurn = false
            val inputKey = userEvidenceIds.lastOrNull()
                ?: silenceObservationId.takeIf(String::isNotBlank)
                ?: "current-" + now.toEpochMilli()
            var previousStepCompleted = true
            for (i in 0 until plannedSideActions.length()) {
                val effect = plannedSideActions.optJSONObject(i) ?: continue
                val tool = effect.optString("tool").trim()
                if (com.jiacimu.lulu.data.CapabilityRegistry.find(tool) == null ||
                    (tool == "ring_in_call_wake" && !sceneContext.contains("电话"))) {
                    notes += "第${i + 1}步未执行：工具不可用"
                    previousStepCompleted = false
                    continue
                }
                if (effect.optBoolean("requiresPreviousSuccess", false) && !previousStepCompleted) {
                    notes += "第${i + 1}步未执行：上一步尚未完成"
                    continue
                }
                val effectArgs = effect.optJSONObject("args") ?: JSONObject()
                if (tool == "control_volume" &&
                    effectArgs.optString("action") == "adjust" &&
                    effectArgs.optInt("deltaPercent") > 0) {
                    if (raisedVolumeThisTurn) {
                        notes += "同一轮重复增大音量已跳过；需等待新的感知与真实反馈"
                        continue
                    }
                    raisedVolumeThisTurn = true
                }
                val result = runCatching {
                    JSONObject(com.jiacimu.lulu.data.ToolRouter.execute(
                        appContext, characterId, tool,
                        effectArgs,
                        requestId = "reply-side-" + inputKey + "-" + i,
                        userRequested = com.jiacimu.lulu.data.CharacterInitiativeRuntime
                            .isExplicitUserToolRequest(userText, tool),
                        intentionId = plan.intentionId,
                        learningApplication = effect.optJSONObject("learningApplication"),
                        causeEvidenceId = groundingEvidenceId,
                        reason = effect.optString("reason").ifBlank { plan.reason },
                        motiveId = effect.optString("motiveId"),
                        concernId = effect.optString("concernId"),
                        experienceId = effect.optString("experienceId"),
                        curiosity = effect.optJSONObject("curiosity")))
                }.getOrElse { error ->
                    JSONObject().put("success", false)
                        .put("error", error.message.orEmpty().ifBlank { "工具执行异常" })
                }
                previousStepCompleted = result.optBoolean("success") &&
                    result.optString("status") !in setOf("pending", "waiting_user", "requested")
                val command = result.optString("command")
                    .ifBlank { effectArgs.optString("command") }
                if (result.optBoolean("success") &&
                    (tool == "ring_in_call_wake" ||
                        (tool == "control_music" && command == "play"))) {
                    verifiedWakeAudio = true
                }
                val actionLabel = if (tool == "control_music") "/$command" else ""
                notes += tool + actionLabel + "：" +
                    (if (result.optBoolean("success")) "已执行" else "未完成") + "：" +
                    result.optString("summary").ifBlank {
                    result.optString("error").ifBlank {
                        result.optString("verification").ifBlank {
                            result.optString("status").ifBlank { "状态未核实" }
                        }
                    }
                }.take(170)
            }
            val details = notes.joinToString("；").take(420)
            if (verifiedWakeAudio) "wake_audio_confirmed=1；$details" else details
        } else ""
        if (plan.action == CharacterDecisionProtocol.SILENT && (separateExpression || callSilence || isGroupChat || sceneContext.contains("电话")) && !rewriting) {
            com.jiacimu.lulu.data.CharacterInnerLifeStore.recordDecision(
                characterId = characterId,
                decisionId = if (callSilence) "$silenceObservationId:silent" else "chat:silent:${now.toEpochMilli()}:${userText.hashCode()}",
                selectedAction = CharacterDecisionProtocol.SILENT,
                reason = plan.reason,
                chosenMotiveId = plan.motiveId,
                alternatives = plan.alternatives,
                outcome = if (sideDeviceReceipt.isNotBlank()) "本轮安静执行手机动作：$sideDeviceReceipt" else "自愿保持安静，没有执行额外动作",
                succeeded = false,
                now = now,
            )
            savePresence(
                characterId, plan, if (callSilence) "通话沉默感知" else "聊天沉默",
                preserveQuietThought = true, heartVoiceFingerprint = privateDelta.fingerprint,
                momentChanged = privateDelta.meaningful,
            )
            if (!rewriting && phoneCallId.isNotBlank()) {
                // A silent visual reply is a REAL chosen action, not a speech
                // cue that should wait for TTS which will never arrive.
                plan.live2d?.let { chosen -> runCatching {
                    onLive2DAction?.invoke(JSONObject(chosen.toString()).put("_executeNow", true))
                } }
            }
            if (!sceneContext.contains("电话") && !rewriting && plannedSideActions != null) {
                com.jiacimu.lulu.data.OrderedCharacterActions.execute(
                    appContext, characterId, plannedSideActions,
                    requestId = "chat-silent-${userEvidenceIds.lastOrNull() ?: now.toEpochMilli()}",
                    intentionId = plan.intentionId, userText = userText,
                    userEvidenceIds = userEvidenceIds.toSet(),
                )
            }
            return Result.success(plannedReply.copy(text = "",
                disposition = CharacterDecisionProtocol.SILENT,
                phoneMediaReceipt = sideDeviceReceipt.takeIf(String::isNotBlank)))
        }
        if (plan.action == "reply") {
            // Phone reply and a voluntary favorite can be one decision. A
            // second chat/phone round is not needed to store a real user quote.
            val inOrdinaryPhone = sceneContext.contains("电话") &&
                !callSilence && !rewriting && !sceneContext.contains("哄睡")
            // The actual, non-Agent phone uses the same user-message executor
            // as text chat. An extra field or perfect JSON is NOT a prerequisite
            // for an authentic wish to collect the newest user utterance.
            val spontaneousWish = listOf(plan.innerThought, plan.text,
                plan.appraisal?.optString("responseAim").orEmpty())
                .any(com.jiacimu.lulu.data.CharacterPhoneFavoriteIntent::decided)
            val chosenPhoneFavorite = if (inOrdinaryPhone &&
                (plan.phoneFavorite != null || spontaneousWish)) plan.phoneFavorite ?: JSONObject()
            else null
            val phoneFavoriteResult = chosenPhoneFavorite?.let { selected ->
                val requestedId = selected.optString("messageId").trim()
                val conversation = com.jiacimu.lulu.data.MigratedDomainStores.chat.conversations.value
                    .firstOrNull { it.groupChat == null && it.characterId == characterId }
                val candidate = conversation?.let { chat ->
                    com.jiacimu.lulu.data.MigratedDomainStores.chat.messages(chat.id).value
                        .asReversed().firstOrNull { msg ->
                            msg.sender == com.jiacimu.lulu.data.LuluChatMessage.Sender.User &&
                                msg.status == com.jiacimu.lulu.data.LuluChatMessage.Status.Sent &&
                                msg.createdAt.isAfter(now.minusSeconds(300)) &&
                                (userText.isBlank() ||
                                    msg.content.contains(userText.trim().take(60)) ||
                                    userText.contains(msg.content.trim().take(60))) &&
                                (requestedId.isBlank() || msg.id == requestedId) &&
                                com.jiacimu.lulu.data.SharedExperienceTimeline.eventsByIds(
                                    characterId, listOf(msg.id),
                                ).any { it.evidenceKind ==
                                    com.jiacimu.lulu.data.EventEvidenceKind.UserStatement }
                        }
                }
                // We never guess which historical message was meant if a
                // specific ID was requested but deleted or inaccessible.
                // With no supplied ID, the verified newest phone utterance wins.
                if (candidate == null) null else {
                    val reason = selected.optString("reason").trim().take(180)
                        .takeIf { it.length >= 4 }
                        ?: plan.innerThought.trim().take(120).takeIf { it.length >= 4 }
                        ?: "自己刚听到这句原话，想把它收藏起来以后再听"
                    val requestId = "phone-favorite-" + candidate.id
                    runCatching {
                        JSONObject(com.jiacimu.lulu.data.ToolRouter.execute(
                            appContext, characterId, "favorite_user_message",
                            JSONObject().put("messageId", candidate.id).put("reason", reason),
                            requestId = requestId,
                        ))
                    }.getOrElse { error ->
                        JSONObject().put("success", false)
                            .put("error", error.message.orEmpty().take(120))
                    }
                }
            }
            // Optional missing/malformed state must not crash a call. Only
            // speech that can be safely recovered is delivered, never raw JSON.
            val phoneSpoken = if (!separateExpression && plan.text.isBlank())
                com.jiacimu.lulu.data.ModelStructuredOutput.completedReplyText(plannedReply.text)
                    ?.trim().orEmpty() else plan.text
            if (!separateExpression && phoneSpoken.isBlank()) {
                return Result.success(plannedReply.copy(text = "", disposition =
                    com.jiacimu.lulu.data.CharacterDecisionProtocol.SILENT))
            }
            if (!separateExpression && phoneSpoken != plan.text)
                plan = plan.copy(text = phoneSpoken)
            // The phone remains single-pass. In text chat, only the expression model
            // renders the planner's intent; it must not re-decide actions.
            val imageOnly = !sceneContext.contains("电话") && plan.stickerOnly &&
                plan.stickerId.isNotBlank() &&
                com.jiacimu.lulu.StickerLibraryStore.byId(appContext, plan.stickerId) != null
            val expressed = if (separateExpression && plan.speechIntent.isNotBlank() && !imageOnly) {
                val expressionHandoff = com.jiacimu.lulu.data.CharacterDecisionProtocol.expressionContext(
                    plan.appraisal, plan.innerLife, plan.mood,
                    "", "",
                    includeAppraisalDetails = false,
                )
                val generated = LuluAiServices.gateway.generate(
                    characterId = characterId,
                    facts = buildString {
                        appendLine("真实聊天场景：$sceneContext")
                        if (expressionConversation.isEmpty() && history.isNotBlank()) appendLine("已发生的对话：\n$history")
                        if (turnContext.isNotBlank()) {
                            appendLine("【系统提供的本轮交互元信息｜不是用户说的话】")
                            appendLine(turnContext.take(4_000))
                        }
                        if (expressionConversation.isEmpty()) appendLine("用户刚才说：$userText")
                        if (commitmentReceipt.isNotBlank()) appendLine("【本轮约定真实回执】$commitmentReceipt")
                if (mediaReceipt.isNotBlank()) appendLine("【本轮音乐操作真实回执】$mediaReceipt")
                if (sideDeviceReceipt.isNotBlank()) appendLine("【本轮统一决策设备执行回执｜按实际状态说话，不能编造成功】$sideDeviceReceipt")
                        appendLine(expressionHandoff)
                        if (turnEconomyContext.isNotBlank()) appendLine(turnEconomyContext)
                        appendLine(com.jiacimu.lulu.data.DialogueMoveEngine.expressionConstraint(dialoguePlan))
                        appendLine(com.jiacimu.lulu.data.ConversationDominantImpulse.expressionRule)
                        appendLine(com.jiacimu.lulu.data.TransientConversationStyle.context(userText, history,
                            includeExamples = false))
                        appendLine("角色已决定的内容简报（保留核心立场与必要答案；旁支理由可不说，不是逐条发送清单）：${plan.speechIntent}")
                        if (plan.socialAttempt.isNotBlank()) {
                            appendLine("角色自己选中的具体小尝试：${plan.socialAttempt.take(200)}。这是虚拟聊天表达的意图，不是已发生的现实接触。")
                        }
                    },
                    instruction = """
                        这是你本人正在参与的私聊，接着对方刚说的这一句说话。
                        已确定的核心立场、必要答案和真实行动结果保持不变。你可以按自己的性格、情绪和关系，决定这次愿意说到哪里；背景里想到的理由不必全部讲出来。
                        辨清她是在陈述现实、吐槽、试探、开玩笑，还是确实求安慰与建议。譬如一句“不读书就没钱”未必是恐慌求助；可以认同、不同意、心疼、逗她一句或给出自己的具体想法，但不要擅自替她升级成全面心理危机。
                        若交接里有自己选的情绪潜台词，用自己的口语、节奏或一点留白承载它，不朗读情绪标签、不替这句台词写心理解说。可以用一句亲昵、调侃或克制的反应接住倾诉，也可以认真展开；不必每次给建议、升华关系或顺手自责。\n                        普通聊天只兑现本轮一个最在意的回应，必要时补一个细节；不要照着“深呼吸→代她分析压力→讲人生道理→保证陪伴”的心理咨询流程拼出六段同义安慰。可以有独立判断与轻微分歧，不必总比她懂得更多，也不必解释自己为什么温柔。
                        情绪表达落在真实措辞、语气、停顿或具体反应里，不用连续堆“石头、重担、绷紧的弦、长路、天塌”等修辞换词表达同一恐惧。只要一句已经说到心坎，就留给她下一轮接话。
                        有交接具体社交小尝试时，在话里自然做出那件小事；幽默、亲昵、颜文字和 emoji 都依本人习惯与现场选择。只有真实执行回执才能证明操作或现实接触发生。
                        独处小说另有专属文学表达层，不把小说里的心理旁白和剧情评价照搬进即时聊天。详细问题、求助步骤和完整创作则应充分回答。按完整意群自然分泡；所有气泡合起来才是本轮回复，心声留在心里。
                        只返回完整 JSON：{"action":"reply","bubbles":[{"text":"实际发出的聊天"},{"text":"更适合用声音表达的一句","delivery":"voice"}]}。delivery 默认为 text，只有本人确实想让对方听到这一句的语气时才选 voice；普通文字与语音可以自然混排，不强制音频或亲昵情节，音频会直接显示原文转写。语气原则：${com.jiacimu.lulu.VoicePerformance.direction}
                        声音表演请在 voice 气泡中选填 speechText：正文 text 保留完整、无标签的原话，speechText 只能在相同原话间按整段心情和真实转折自然插入少量英文方括号音频标签（如 [softly] [hesitant] [laughs]），而不是每句话随机堆叠；不要编造新的动作或念出标签。只有支持表演标签的 TTS 引擎才收到它们，字幕和记忆仍是干净的 text。引用或收藏仅在明确给出真实消息ID时使用 ⟪QUOTE:消息ID⟫ 或 ⟪FAVORITE:消息ID⟫；图片由程序发送。
                    """.trimIndent(),
                    source = "聊天表达渲染",
                    title = title,
                    maxTokens = when (com.jiacimu.lulu.data.TransientConversationStyle.analyze(userText, history).pace) {
                        com.jiacimu.lulu.data.TransientConversationStyle.Pace.BRIEF -> 600
                        com.jiacimu.lulu.data.TransientConversationStyle.Pace.NORMAL -> 950
                        com.jiacimu.lulu.data.TransientConversationStyle.Pace.EXPANSIVE -> 1_400
                    },
                    connectionOverride = connection,
                    conversation = expressionConversation,
                    memoryRequest = UnifiedMemoryRequest(
                        currentInput = userText,
                        sceneContext = sceneContext,
                        recentContext = history,
                        taskIntent = "按已确定的回复意图渲染语言，不再次决策",
                        actionContext = plan.speechIntent.take(220) + " " + plan.socialAttempt.take(100),
                    ),
                ).getOrElse { return Result.failure(it) }
                val structuredBubbles = if (rewriting)
                    com.jiacimu.lulu.data.ModelStructuredOutput.replyBubblesWithPartialState(generated.text)
                else com.jiacimu.lulu.data.ModelStructuredOutput.completedReplyBubbles(generated.text)
                val firstBubbles = structuredBubbles
                    ?: generated.text.trim().takeIf { it.isNotBlank() &&
                        !it.startsWith("{") && !it.startsWith("```") }?.let(::listOf)
                    ?: return Result.failure(IllegalStateException("表达模型没有返回完整的可发送正文"))
                val naturalness = com.jiacimu.lulu.data.ConversationNaturalnessGate.assess(
                    userText, firstBubbles, history,
                    recentCharacterReplies = recentCharacterReplies,
                    operationalEvidence = turnContext,
                )
                val rerendered = if (naturalness.needsRerender) {
                    LuluAiServices.gateway.generate(
                        characterId = characterId,
                        facts = buildString {
                            appendLine("真实聊天场景：${sceneContext}")
                            if (expressionConversation.isEmpty()) appendLine("用户刚才说：${userText}")
                            if (expressionConversation.isEmpty() && history.isNotBlank()) appendLine("已发生的对话：\n$history")
                            if (turnContext.isNotBlank()) appendLine("本轮程序提供的交互元信息：${turnContext.take(4_000)}")
                            if (commitmentReceipt.isNotBlank()) appendLine("【本轮约定真实回执】$commitmentReceipt")
                            appendLine(expressionHandoff)
                            if (turnEconomyContext.isNotBlank()) appendLine(turnEconomyContext)
                            appendLine(com.jiacimu.lulu.data.DialogueMoveEngine.expressionConstraint(dialoguePlan))
                        appendLine(com.jiacimu.lulu.data.ConversationDominantImpulse.expressionRule)
                            appendLine(com.jiacimu.lulu.data.TransientConversationStyle.context(userText, history,
                                includeExamples = false))
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
                            本轮只需说角色真正想说的重点。删去重复解释、繁琐铺垫、歉意三连和任务答复式收尾；保留真实立场、情绪与不可丢的事实。保留角色自选的情绪话外音，不把一句含蓄的真心话改写成解释它含义的长篇独白；也不要以压短为由漏掉必要答案。
                            已有的幽默、态度、颜文字或 emoji 如果仍贴合语境就保留，不要把消除客服腔误做成删掉全部情绪。
                            保持这个角色自己的词汇、节奏、幽默感、关系称呼与分寸；不要把“自然”理解成统一的短句网感，也不要故意加口头禅。
                            只返回完整 JSON：{"action":"reply","bubbles":[{"text":"重写后的自然聊天"}]}。确实更适合传达声音的一句可设 delivery="voice"，该气泡可选填 speechText（与 text 原话相同，仅在真实情绪转折处加少量英文音频标签），不在正文写标签；无须为了润色强行增加语音；不要改变角色本人已经选好的语气意图。
                            先按普通文字聊天的自然节奏确定每个完整意群的气泡，再为每个气泡选 text 或 voice；语音只是朗读这条气泡的原话，不要把原本会分开的文字气泡合并成长语音。规划阶段的互动动作数量不是可视气泡的数量。一个动作可用数个短气泡说清，不把没说完的词句拆开，也不为了凑数量发空话。
                        """.trimIndent(),
                        source = "聊天表达自然度修复",
                        title = title,
                        maxTokens = 1_000,
                        connectionOverride = connection,
                        conversation = expressionConversation,
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
                    com.jiacimu.lulu.data.ConversationNaturalnessGate.assess(userText, it, history,
                        recentCharacterReplies = recentCharacterReplies, operationalEvidence = turnContext)
                }
                val useRerender = rerendered != null && !rerenderedBubbles.isNullOrEmpty() &&
                    repairedNaturalness != null &&
                    com.jiacimu.lulu.data.ConversationNaturalnessGate.shouldUseRewrite(
                        userText, firstBubbles, rerenderedBubbles, naturalness, repairedNaturalness,
                    )
                val chosenResult = if (useRerender) rerendered!! else generated
                val chosenBubbles = if (useRerender) rerenderedBubbles!! else firstBubbles
                // dialoguePlan.maxBubbles is a count of *conversation moves*,
                // not a visual clamp. A single thought may need two complete
                // sentences in separate chat bubbles. Never discard the rest.
                val voiceChoices = com.jiacimu.lulu.data.ModelStructuredOutput.voiceBubbleTexts(chosenResult.text)
                val speechTracks = com.jiacimu.lulu.data.ModelStructuredOutput.objectOrNull(chosenResult.text)
                    ?.let(com.jiacimu.lulu.data.ModelStructuredOutput::bubbleSpeechTracks).orEmpty()
                // Carry the authored text bubbles and voice choices together. The
                // shared chat sender builds identical visual bubbles first, then
                // synthesizes audio only for the ones selected as voice.
                val spoken = (com.jiacimu.lulu.data.ModelStructuredOutput.authoredReplyBubbles(chosenResult.text)
                    ?: com.jiacimu.lulu.data.ModelStructuredOutput.readableReplyBubbles(chosenBubbles, maxBubbles = Int.MAX_VALUE))
                    .joinToString(com.jiacimu.lulu.SemanticBubbleSeparator)
                chosenResult.copy(
                    text = spoken,
                    voiceBubbleTexts = voiceChoices,
                    voiceBubbleSpeechTracks = speechTracks,
                    inputTokens = generated.inputTokens + (rerendered?.inputTokens ?: 0) + plannedReply.inputTokens,
                    outputTokens = generated.outputTokens + (rerendered?.outputTokens ?: 0) + plannedReply.outputTokens,
                    cachedTokens = generated.cachedTokens + (rerendered?.cachedTokens ?: 0) + plannedReply.cachedTokens,
                )
            } else plannedReply
            val naturalText = if (imageOnly) "" else if (separateExpression && plan.speechIntent.isNotBlank()) expressed.text
                else if (!separateExpression && phoneSpoken.isNotBlank())
                    phoneSpoken
                else proposedSpeech
            val verifiedSpeech = repairUnfairSpeech(naturalText)
                ?: return Result.failure(IllegalStateException(
                    "角色本轮表达与未履行责任的事实不符，已停止发送，不使用固定道歉模板"
                ))
            if (sceneContext.contains("电话") &&
                com.jiacimu.lulu.PhoneSpokenContentBoundary.leaksInternalAnalysis(verifiedSpeech)) {
                if (callSilence) return Result.success(expressed.copy(
                    text = "", disposition = CharacterDecisionProtocol.SILENT))
                return Result.failure(IllegalStateException(
                    "角色的回复混入内部感知分析，已停止播报并保留通话"))
            }
            val safeText = com.jiacimu.lulu.data.RelationshipExpressionGuard.apply(
                characterId, verifiedSpeech)
            // A sticker exists only if the user has imported and approved that
            // exact ID. Invalid guesses silently yield no image.
            val chosenSticker = if (!sceneContext.contains("电话"))
                plan.stickerId.takeIf(String::isNotBlank)?.let { com.jiacimu.lulu.StickerLibraryStore.byId(appContext, it) }
            else null
            if (plan.stickerOnly && !imageOnly) return Result.failure(
                IllegalStateException("角色选了只发图，但表情包已不存在或场景不支持图片"))
            if (safeText.isBlank() && chosenSticker == null) return Result.failure(
                IllegalStateException("角色决定回复但没有生成可发送内容"))
            val phoneFavoriteReceipt = phoneFavoriteResult?.let { actual ->
                if (actual.optBoolean("success")) "那句我收好了。"
                else "" // Failure stays in the real receipt, not a robotic spoken error.
            }.orEmpty()
            // Tool outcome is appended ONLY after the actual executor returned.
            // The spoken model text cannot independently prove an action ran.
            val deliveredSpeech = if (phoneFavoriteReceipt.isNotBlank()) {
                listOf(safeText, phoneFavoriteReceipt).filter(String::isNotBlank)
                    .joinToString(" ")
            } else safeText
            val sendText = if (chosenSticker == null) deliveredSpeech else listOf(
                deliveredSpeech, com.jiacimu.lulu.encodeQqChatImage(
                    chosenSticker.uri,
                    imageDescription = com.jiacimu.lulu.StickerLibraryStore.imageDescription(chosenSticker),
                    sticker = true,
                ),
            ).filter(String::isNotBlank).joinToString(com.jiacimu.lulu.SemanticBubbleSeparator)
            if (!invalidBlame && safeText == naturalText && !rewriting) {
                savePresence(
                    characterId, plan, if (callSilence) "通话沉默感知" else "聊天",
                    heartVoiceFingerprint = privateDelta.fingerprint,
                    momentChanged = privateDelta.meaningful,
                )
                com.jiacimu.lulu.data.CharacterLifeStore.recordAfterglow(characterId, if (callSilence) "电话中的安静陪伴观察" else "本轮用户消息：$userText", plan.afterglow)
            }
            if (!invalidBlame && !rewriting && phoneCallId.isNotBlank() &&
                safeText == naturalText) {
                plan.live2d?.let { chosen ->
                    runCatching { onLive2DAction?.invoke(chosen) }
                }
            }
            if (!invalidBlame && !rewriting && !plan.endCall &&
                sceneContext.contains("电话") && phoneCallId.isNotBlank() &&
                plan.hangupAfterSeconds in 0..86_400)
                onTimedHangup?.invoke(plan.hangupAfterSeconds)
            if (!invalidBlame && !rewriting && plan.endCall && sceneContext.contains("电话"))
                onCharacterHangup?.invoke()
            if (rewriting) onReconsiderationDraft?.invoke(ReconsideredChatReply(
                text = sendText,
                innerThought = plan.innerThought,
                innerThoughtBasis = plan.innerThoughtBasis,
                innerLife = plan.innerLife,
                appraisal = plan.appraisal,
                reason = plan.reason,
                mood = plan.mood.takeIf(String::isNotBlank),
                gesture = plan.gesture.takeIf(String::isNotBlank),
                statusText = plan.statusText.takeIf(String::isNotBlank),
            ))
            return Result.success(expressed.copy(text = sendText,
                deferredActions = if (!rewriting && !sceneContext.contains("电话"))
                    plan.sideActions?.toString() else null,
                bedtimeContinuation = plan.sleepContinuation?.toString(),
                sleepDecision = plan.sleepDecision.takeIf { sceneContext.contains("电话") && it in setOf("enable", "disable") },
                musicAfterSpeech = plan.musicAfterSpeech.takeIf { sceneContext.contains("电话") && it in setOf("resume", "stay_paused") },
                phoneMediaReceipt = mediaReceipt.takeIf(String::isNotBlank),
                interactionReceipt = if (rewriting || invalidBlame || safeText != naturalText) null else com.jiacimu.lulu.data.CharacterConversationAgency.receipt(
                    groundingEvidenceId.ifBlank { observedWorld.lastOrNull()?.id.orEmpty() },
                    plan.reason, plan.motiveId, plan.concernId, plan.curiosity, plan.experienceId,
                    plan.learningApplication, intentionId = plan.intentionId,
                )))
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
        val requestId = "reply-${lastUserEvent ?: java.util.UUID.randomUUID().toString()}"
        val toolResult = if (plan.tool == "read_xiaohongshu" && directXhsResult != null) {
            directXhsResult // The same real receipt: no second navigation/search.
        } else com.jiacimu.lulu.data.ToolRouter.execute(appContext, characterId, plan.tool, plan.args,
            requestId = requestId,
            userRequested = explicitUserToolRequest, intentionId = plan.intentionId,
            learningApplication = plan.learningApplication,
            causeEvidenceId = groundingEvidenceId, reason = plan.reason, curiosity = plan.curiosity)
        val actual = runCatching { JSONObject(toolResult) }.getOrNull()
        val actionReceipt = "tool-$characterId-$requestId"
        val executedSuccess = actual?.optBoolean("success") == true
        val executedSummary = actual?.optString("summary").orEmpty().ifBlank { toolResult.take(220) }
        com.jiacimu.lulu.data.CharacterInnerLifeStore.recordActionResult(
            characterId, plan.motiveId, actionReceipt, plan.tool, executedSuccess, executedSummary,
        )
        com.jiacimu.lulu.data.CharacterInnerLifeStore.recordConcernOutcome(
            characterId, plan.concernId, actionReceipt, plan.tool, executedSuccess, executedSummary,
        )
        val lifeAction = when (plan.tool) {
            "publish_moment" -> "moment"
            "send_private_message", "send_group_message" -> "message"
            "read_book" -> "reading"
            "play_solo_game" -> "solo_game"
            "digital_world_action" -> "digital_world"
            else -> ""
        }
        if (lifeAction.isNotBlank()) com.jiacimu.lulu.data.CharacterLifeFollowThroughStore.recordAction(
            characterId, plan.experienceId, lifeAction, executedSuccess, executedSummary,
            actual?.optString("evidenceId").orEmpty(),
        )
        if (!executedSuccess) com.jiacimu.lulu.data.CharacterCuriosityRuntime.recordFailure(
            characterId, plan.curiosity, plan.tool, executedSummary,
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
                appendLine("真实工具回执ID=$actionReceipt")
                appendLine(com.jiacimu.lulu.data.CharacterExplorationContinuity.context(characterId))
                if (commitmentReceipt.isNotBlank()) appendLine("【本轮约定真实回执】$commitmentReceipt")
            },
            instruction = """
                这是同一段连续经历中的下一刻：角色刚刚已经做完工具动作，现在只需要从动作后的新状态继续说话，不要把整段对话重新回答一遍。
                ${com.jiacimu.lulu.data.CharacterExpressionGuide.channelNote(com.jiacimu.lulu.data.CharacterSpeechChannel.TOOL_RESULT)}
                根据工具真实结果，以角色本人符合人设的方式自然接下去。成功时可以确认，失败时必须如实说明；除此之外只说此刻真正会新增的话。
                日常私聊里优先一两句真实的反应：比如“我试了，搜索又卡在首页了……烦。”就已经表达了结果和情绪。不要堆“经过我在后台确认、不是你的错、我的权限不足、让你担心了、下次我会……”之类未经证实的技术推论和连续检讨；只有用户追问细节时才解释流程。
                必须继续保持当前真实互动场景，电话里用自然口语，群聊里知道其他成员在场。
                对位置结果只能使用 readableAddress；地址为空、定位过旧或精度差时，必须明确说是大概位置，不得根据经纬度猜具体店铺、学校或建筑。
                只返回一个 JSON 对象，不要代码块；action 最先，紧接 text，不重复字段：
                {"action":"reply","text":"角色在动作之后自然接着说的话","statusText":"动作后的简短状态","gesture":"动作后的可见动作神态","innerThought":"动作后没说出口的第一人称心声，可为空","innerThoughtBasis":{"focus":"若有心声，真实触发点","change":"动作结果让内部状态新增/改变了什么","unsaidWhy":"为何没直接说"},"mood":"动作后的简短心情"}
                如果工具真实成功或失败使角色改变了一个想法、想继续尝试或意识到失误，可以选填 innerLife 的 emotion/motives/selfCorrection 字段；仅依据上面明确给出的工具结果，失败绝不能写成成功。
                ${com.jiacimu.lulu.data.CharacterExplorationContinuity.resultGuidance}
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
        // Ordinary tool follow-ups previously bypassed the text-chat naturalness
        // gate completely, so one failed search could produce three apologies and
        // a verbose incident report. Only re-render non-streamed text replies;
        // phone streams must not be silently replaced after audio was emitted.
        val toolReplyForDelivery = if (!sceneContext.contains("电话") &&
            onReplyStream == null && !disputeNeedsReview) {
            val draft = finalReply.getOrNull()
            val envelope = draft?.text?.let {
                com.jiacimu.lulu.data.ModelStructuredOutput.objectOrNull(it)
            }
            val draftSpeech = envelope?.optString("text").orEmpty().trim()
            val quality = com.jiacimu.lulu.data.ConversationNaturalnessGate.assess(
                userText, listOf(draftSpeech), history,
                recentCharacterReplies = recentCharacterReplies,
                operationalEvidence = "$turnContext\n$toolResult",
            )
            if (draft != null && envelope != null && draftSpeech.isNotBlank() &&
                quality.needsRerender) {
                val adjusted = LuluAiServices.gateway.generate(
                    characterId = characterId,
                    facts = buildString {
                        appendLine("这是刚执行完工具后同一角色的即时聊天，不是新的一轮决策。")
                        appendLine("用户问：${userText.take(900)}")
                        if (turnEconomyContext.isNotBlank()) appendLine(turnEconomyContext)
                        appendLine("角色刚执行的工具：${plan.tool}")
                        appendLine("真实工具结果（最高优先级，不得变更成功失败）：${toolResult.take(1800)}")
                        appendLine("刚刚写出的回复草稿：${draftSpeech.take(1800)}")
                        appendLine(com.jiacimu.lulu.data.CharacterExpressionContinuity.guide())
                    },
                    instruction = """
                        只把角色刚才工具结果后的回复改写成自己真实会发出的自然聊天。
                        ${quality.repairInstruction()}
                        留下真正的执行结果、重要事实和角色自己的情绪/态度；删掉解释三连、
                        冗余安慰、责任总结、未经证实的技术原因。对方没索要说明书，就说够即停。
                        保留事实的成功或失败、链接、数值、否定、约定和关系分寸，不改变工具事实；
                        不增加新行动或承诺，也不能把别的笔记说成已搜索到的目标。
                        只返回完整 JSON：{"action":"reply","text":"改写后真实发出的聊天正文"}。
                        情绪自然、用词依照这个角色的已有习惯，不要生成统一的客服温柔腔。
                    """.trimIndent(),
                    source = "工具结果·聊天自然表达修复",
                    title = title,
                    maxTokens = 700,
                    connectionOverride = connection,
                ).getOrNull()
                val revisedSpeech = adjusted?.text?.let {
                    com.jiacimu.lulu.data.ModelStructuredOutput.objectOrNull(it)
                        ?.optString("text")
                }.orEmpty().trim()
                val revisedQuality = com.jiacimu.lulu.data.ConversationNaturalnessGate.assess(
                    userText, listOf(revisedSpeech), history,
                    recentCharacterReplies = recentCharacterReplies,
                    operationalEvidence = "$turnContext\n$toolResult",
                )
                if (revisedSpeech.isNotBlank() &&
                    com.jiacimu.lulu.data.ConversationNaturalnessGate.shouldUseRewrite(
                        userText, listOf(draftSpeech), listOf(revisedSpeech), quality, revisedQuality,
                    )) {
                    val revisedEnvelope = JSONObject(envelope.toString()).put("text", revisedSpeech)
                    Result.success(draft.copy(
                        text = revisedEnvelope.toString(),
                        inputTokens = draft.inputTokens + (adjusted?.inputTokens ?: 0),
                        outputTokens = draft.outputTokens + (adjusted?.outputTokens ?: 0),
                        cachedTokens = draft.cachedTokens + (adjusted?.cachedTokens ?: 0),
                    ))
                } else finalReply
            } else finalReply
        } else finalReply
        return toolReplyForDelivery.mapCatching { result ->
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
                    // The observed receipt is the new stimulus for this role's
                    // own reaction, never proof of a model-authored success.
                    hasChosenToolAction = true,
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
            val checkedResultText = repairUnfairSpeech(naturalText)
                ?: throw IllegalStateException(
                    "工具完成后回复包含无依据的归责，重新表达仍未通过核验；不代写道歉"
                )
            if (sceneContext.contains("电话") &&
                com.jiacimu.lulu.PhoneSpokenContentBoundary.leaksInternalAnalysis(checkedResultText))
                throw IllegalStateException("工具结果混入内部分析，未向通话播放")
            if (finalPlan != null && checkedResultText == naturalText) {
                savePresence(
                    characterId, finalPlan, "聊天·工具",
                    heartVoiceFingerprint = toolResultPrivateDelta?.fingerprint.orEmpty(),
                    momentChanged = toolResultPrivateDelta?.meaningful == true,
                )
                com.jiacimu.lulu.data.CharacterLifeStore.recordAfterglow(characterId,
                    "本轮用户消息：${userText.take(120)}；工具真实结果：${toolResult.take(120)}", finalPlan.afterglow, evidenceId = actionReceipt)
                runCatching { com.jiacimu.lulu.data.CharacterExplorationContinuity.returned(appContext, characterId,
                    com.jiacimu.lulu.data.ModelStructuredOutput.objectOrNull(result.text)?.apply {
                        put("innerThought", finalPlan.innerThought)
                        put("innerLife", com.jiacimu.lulu.data.CharacterInnerLifeStore.withAfterglow(
                            finalPlan.innerLife, finalPlan.afterglow, toolResult))
                    },
                    listOf(actionReceipt, actual?.optString("evidenceId").orEmpty()).filter(String::isNotBlank)) }
                    .onFailure { android.util.Log.w("CharacterExploration", "Tool-result continuity was not saved", it) }
                if (!finalPlan.endCall && sceneContext.contains("电话") &&
                    phoneCallId.isNotBlank() && finalPlan.hangupAfterSeconds in 0..86_400)
                    onTimedHangup?.invoke(finalPlan.hangupAfterSeconds)
                if (finalPlan.endCall && checkedResultText.isNotBlank() && sceneContext.contains("电话"))
                    onCharacterHangup?.invoke()
            }
            val displayedText = if (!sceneContext.contains("电话")) {
                com.jiacimu.lulu.data.ModelStructuredOutput.readableReplyBubbles(
                    listOf(checkedResultText), maxBubbles = 8,
                ).joinToString(com.jiacimu.lulu.SemanticBubbleSeparator)
            } else checkedResultText
            result.copy(
                text = displayedText,
                sleepDecision = plan.sleepDecision.takeIf {
                    sceneContext.contains("电话") && it in setOf("enable", "disable")
                },
                musicAfterSpeech = plan.musicAfterSpeech.takeIf {
                    sceneContext.contains("电话") && it in setOf("resume", "stay_paused")
                },
                inputTokens = result.inputTokens + plannedReply.inputTokens,
                outputTokens = result.outputTokens + plannedReply.outputTokens,
                cachedTokens = result.cachedTokens + plannedReply.cachedTokens,
            )
        }
    }

    internal suspend fun executeDeferredReplyActions(
        characterId: String,
        steps: String?,
        requestId: String,
        userEvidenceIds: Set<String>,
        userText: String,
    ): com.jiacimu.lulu.data.OrderedCharacterActionReport? {
        val app = context ?: return null
        val parsed = runCatching { org.json.JSONArray(steps ?: return null) }.getOrNull()
            ?: return null
        return com.jiacimu.lulu.data.OrderedCharacterActions.execute(
            app, characterId, parsed, requestId = requestId,
            userEvidenceIds = userEvidenceIds, userText = userText,
        )
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
                    commitmentTaskId = "tool-task-tool-$characterId-$requestId",
                ).getOrThrow()
                CommitmentTaskStore.initialize(context)
                CommitmentTaskStore.save(
                    CommitmentTask(
                        id = "tool-task-${alarm.id}",
                        characterId = characterId,
                        goal = "本机闹钟：${alarm.label}",
                        status = CommitmentTaskStatus.Scheduled,
                        dueAt = alarm.triggerAt,
                        timezone = ZoneId.systemDefault().id,
                        nextCheckAt = alarm.triggerAt,
                        linkedAlarmId = alarm.id,
                        deliveryAction = "alarm_only",
                        completionCondition = "本机闹钟到期触发；不可冒充角色打电话或发消息",
                        lastActionResult = if (LuluAlarmSystem.canScheduleExact())
                            "本机精确闹钟已登记，待触发" else "非精确闹钟已登记，可能延迟",
                    ),
                )
                JSONObject().put("success", LuluAlarmSystem.canScheduleExact()).put("status", if (LuluAlarmSystem.canScheduleExact()) "succeeded" else "waiting_permission").put("verification", if (LuluAlarmSystem.canScheduleExact()) "AlarmManager已接受本地精确调度，注册记录已持久化" else "已保存本机非精确提醒，但无法保证准点；请在露露机角色授权页开启精确闹钟权限，无须反复进入系统时钟确认").put("systemClock", false).put("id", alarm.id).put("triggerAt", alarm.triggerAt.toString()).put("label", alarm.label).toString()
            }
            "list_alarms" -> {
                val alarms = LuluAlarmSystem.list().filter { it.characterId == characterId }
                JSONObject().put("success", true).put("count", alarms.size).put(
                    "alarms",
                    alarms.joinToString("\n") { alarm -> "${alarm.id} | ${alarm.triggerAt.atZone(ZoneId.systemDefault())} | ${alarm.label}" },
                ).toString()
            }
            "list_media_sessions" -> LuluMediaPlayback.sessions(context)
            "control_music" -> {
                val phone = com.jiacimu.lulu.LuluVoiceCallSession.state.value
                val requested = args.optString("command").trim().lowercase()
                // A device-level stop by the user cannot be undone by the
                // character replaying the previous conversation's old PLAY.
                if (phone.connected && phone.characterId == characterId &&
                    requested in setOf("play", "resume", "start", "播放", "继续播放") &&
                    !com.jiacimu.lulu.data.DeferredCallMediaAction.mayStartMusic(
                        characterId, phone.callExperienceId)) {
                    return@runCatching JSONObject().put("success", false)
                        .put("status", "waiting_user")
                        .put("error", "用户已停止本通电话的音乐；需要新的明确播放请求，不能根据旧指令自动续播")
                        .toString()
                }
                val result = LuluMediaPlayback.control(
                    context, args.optString("command"), args.optString("packageName"))
                if (phone.connected && phone.characterId == characterId &&
                    phone.callExperienceId.isNotBlank()) {
                    runCatching {
                        com.jiacimu.lulu.data.DeferredCallMediaAction.notePhoneMediaControl(
                            characterId, phone.callExperienceId, args.optString("command"),
                            JSONObject(result))
                    }
                }
                result
            }
            "ring_in_call_wake" -> {
                val phone = com.jiacimu.lulu.LuluVoiceCallSession.state.value
                check(phone.connected && phone.characterId == characterId &&
                    phone.callExperienceId.isNotBlank()) {
                    "只有与此角色真实通话中才能播放通话内叫醒铃声"
                }
                // Ringing is a separate, short native action; it does not
                // force a voice reply or dial a second incoming call.
                val seconds = args.optInt("seconds", 6).coerceIn(1, 15)
                val started = com.jiacimu.lulu.LuluCallRingtone.startInCallWake(
                    context, "role-wake-$requestId", seconds * 1_000L)
                JSONObject().put("success", started)
                    .put("status", if (started) "succeeded" else "failed")
                    .put("summary", if (started)
                        "已在当前通话播放本地叫醒铃声，约${seconds}秒后自动停止"
                        else "铃声未能播放；检查手机静音、勿扰模式和来电铃声设置")
                    .toString()
            }
            "control_volume" -> LuluMediaVolume.control(
                context, characterId, args.optString("action", "status"),
                args.optInt("targetPercent", 0), args.optInt("deltaPercent", 0))
            "cancel_alarm" -> {
                val id = args.optString("id")
                require(LuluAlarmSystem.list().any { it.id == id && it.characterId == characterId }) { "不能取消其他角色的闹钟或不存在的闹钟" }
                val cancelled = LuluAlarmSystem.cancel(id)
                if (cancelled) {
                    CommitmentTaskStore.snapshot(characterId)
                        .filter { it.linkedAlarmId == id }
                        .forEach { CommitmentTaskStore.cancel(it.id, "角色主动取消本机闹钟") }
                }
                JSONObject().put("success", cancelled).put("id", id).toString()
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
            // Legacy models sometimes output the tool name as action directly.
            // Normalize this one known, locally verified action instead of
            // rejecting the entire telephone response for a wrapper mismatch.
            val legacyFavorite = json.optString("action") == "favorite_user_message"
            val action = if (legacyFavorite) "tool"
                else CharacterDecisionProtocol.chatAction(json) ?: return null
            val bubbleValues = com.jiacimu.lulu.data.ModelStructuredOutput.completedReplyBubbles(raw)
            val spoken = if (json.optJSONArray("bubbles") != null && !bubbleValues.isNullOrEmpty())
                bubbleValues.joinToString(com.jiacimu.lulu.SemanticBubbleSeparator)
            else com.jiacimu.lulu.data.ModelStructuredOutput.completedReplyText(raw).orEmpty()
            ToolPlan(
                action = action,
                text = if (action == "reply") spoken else json.optString("text"),
                tool = if (legacyFavorite) "favorite_user_message" else json.optString("tool"),
                args = json.optJSONObject("args") ?: if (legacyFavorite) JSONObject()
                    .put("messageId", json.optString("messageId"))
                    .put("reason", json.optString("reason")) else JSONObject(),
                statusText = json.optString("statusText").ifBlank { json.optString("status") },
                gesture = json.optString("gesture").ifBlank { json.optString("actionDescription") },
                live2d = json.optJSONObject("live2d"),
                innerThought = json.optString("innerThought").ifBlank { json.optString("inner_voice") },
                innerThoughtBasis = json.optJSONObject("innerThoughtBasis"),
                mood = json.optString("mood"),
                afterglow = json.optJSONObject("afterglow"),
                intention = json.optJSONObject("intention"),
                innerLife = json.optJSONObject("innerLife"),
                commitmentChanges = json.optJSONArray("commitmentChanges"),
                motiveId = json.optString("motiveId"),
                motiveRef = json.optString("motiveRef"),
                intentionId = json.optString("intentionId").trim().take(70),
                endCall = json.optBoolean("endCall", false),
                hangupAfterSeconds = json.optInt("hangupAfterSeconds", -1),
                sleepDecision = json.optString("sleepDecision").trim().lowercase(),
                afterCallMedia = json.optJSONObject("afterCallMedia"),
                sideActions = com.jiacimu.lulu.data.CharacterThoughtActionHandoff.actions(json),
                mediaUnderstanding = json.optJSONObject("mediaUnderstanding"),
                musicAfterSpeech = json.optString("musicAfterSpeech").trim().lowercase(),
                phoneFavorite = json.optJSONObject("phoneFavorite"),
                speechIntent = CharacterDecisionProtocol.speechIntent(json),
                socialAttempt = json.optString("socialAttempt").trim().take(220),
                stickerId = json.optString("stickerId").trim().take(90),
                stickerOnly = json.optBoolean("stickerOnly", false),
                concernId = json.optString("concernId").trim().take(100),
                reason = json.optString("reason"),
                alternatives = json.optJSONArray("alternatives"),
                appraisal = json.optJSONObject("appraisal"),
                dialogueMove = json.optJSONObject("dialogueMove"),
                sleepContinuation = json.optJSONObject("sleepContinuation"),
                curiosity = json.optJSONObject("curiosity"),
                curiosities = json.optJSONArray("curiosities"),
                learningApplication = json.optJSONObject("learningApplication"),
                experienceLearning = json.optJSONObject("experienceLearning"),
                learningFeedback = json.optJSONObject("learningFeedback"),
                learningChallenge = json.optJSONObject("learningChallenge"),
                experienceId = json.optString("experienceId").trim().take(180),
            )
        }.getOrNull()
    }

    private fun savePresence(
        characterId: String,
        plan: ToolPlan,
        source: String,
        preserveQuietThought: Boolean = false,
        heartVoiceFingerprint: String = "",
        momentChanged: Boolean = true,
    ) {
        CompanionPresenceStore.update(
            characterId = characterId,
            statusText = plan.statusText,
            gesture = plan.gesture,
            innerThought = if (preserveQuietThought) plan.innerThought.takeIf(String::isNotBlank) else plan.innerThought,
            mood = plan.mood,
            source = source,
            innerThoughtFingerprint = heartVoiceFingerprint,
            momentReflection = if (momentChanged || plan.appraisal?.optJSONObject("spontaneousBid") != null ||
                plan.appraisal?.optJSONObject("ownActivity") != null ||
                (plan.appraisal?.optJSONArray("privateIdeas")?.length() ?: 0) > 0)
                com.jiacimu.lulu.data.CharacterMomentSnapshot.reflection(
                plan.innerLife, plan.innerThoughtBasis, plan.appraisal,
                outward = plan.text.ifBlank { plan.speechIntent },
                innerThought = plan.innerThought,
            ) else "",
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
    val live2d: JSONObject? = null,
    val innerThoughtBasis: JSONObject? = null,
    val mood: String,
    val intention: JSONObject? = null,
    val afterglow: JSONObject? = null,
    val innerLife: JSONObject? = null,
    val commitmentChanges: org.json.JSONArray? = null,
    val motiveId: String = "",
    val motiveRef: String = "",
    val intentionId: String = "",
    val endCall: Boolean = false,
    val hangupAfterSeconds: Int = -1,
    val sleepDecision: String = "",
    val afterCallMedia: JSONObject? = null,
    val sideActions: org.json.JSONArray? = null,
    val mediaUnderstanding: JSONObject? = null,
    val musicAfterSpeech: String = "",
    val phoneFavorite: JSONObject? = null,
    val speechIntent: String = "",
    val socialAttempt: String = "",
    val stickerId: String = "",
    val stickerOnly: Boolean = false,
    val concernId: String = "",
    val reason: String = "",
    val appraisal: JSONObject? = null,
    val dialogueMove: JSONObject? = null,
    val curiosity: JSONObject? = null,
    val curiosities: org.json.JSONArray? = null,
    val learningApplication: JSONObject? = null,
    val experienceLearning: JSONObject? = null,
    val learningFeedback: JSONObject? = null,
    val learningChallenge: JSONObject? = null,
    val sleepContinuation: JSONObject? = null,
    val experienceId: String = "",
    val alternatives: org.json.JSONArray? = null,
)
