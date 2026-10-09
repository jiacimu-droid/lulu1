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
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.UnifiedMemoryRequest
import com.jiacimu.lulu.health.HealthRolePerception
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
    ): Result<ModelReply> {
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
        HealthRolePerception.initialize(appContext)
        HealthRolePerception.recordLatestSleep(characterId)
        val healthContext = HealthRolePerception.context(now)
        val companionActionContext = CompanionActionRuntime.capabilityContext(appContext, characterId, includeWorldContext = false) + "\n" + com.jiacimu.lulu.data.CapabilityRegistry.context(appContext, characterId)
        val onlineChatBubbleRule = if (sceneContext.contains("电话")) "" else """
            - 当前是即时通讯软件里的日常线上聊天。气泡多少、每条长短由这一瞬的情绪、性格和聊天节奏决定：可只发一个“啊？”，也可激动得连发几条、突然改口或欲言又止；认真解释时可以更长。不要按固定条数、十至四十字的模板约束真实反应，也不要为了热闹机械连发。
            - 真正触动自己时，不必先把第一反应修饰成“你很可爱”“我能理解”：内心可以慌乱、重复、脱口而出、惊呼，外在可以直球、嘴硬、打趣、只发几个字甚至忍住不说；差别取决于这个角色。情绪强弱与现实刺激成比例，没有被触动就正常说话。不要套用示范台词。
            - 用户抛来一个有趣或可爱的小细节时，优先允许角色自己被戳中、追着某个细节玩笑或兴奋片刻，而非总结用户行为、解释情绪、提供建议。用户需要实质帮助时仍须帮到位。
            - text 只发给对方能看到的话；不写动作旁白、环境描写、心理分析、舞台括号、客服式总结或连续抒情独白。不要把每次聊天都升格成关系宣言，不替用户分析情绪，不连续追问。保留该角色自己的词汇、口头习惯、态度和关系边界。
            - 不要先写一大篇再切碎充当聊天。先决定真正要说的少量内容，再按回应、补充、转折或追问的语义停顿分别发送。
            - 你不是每收到一条消息就重新开始一次问答。最近对话、刚才的动作、情绪与关系变化都已经真实发生；从上一刻的状态继续生活，只处理此刻新增的信息和变化。
            - 不要把“保持连续”理解成复述历史。历史的作用是告诉你已经走到哪里；真正的回复应从那个位置继续向前，而不是重新总结、重新解释或重新表达上一刻。
            - text 中的气泡边界由你根据角色本人想表达的语气和聊天节奏决定。先想清楚此刻真正想说什么，再根据停顿、情绪变化、犹豫、补充、转折、追问、吐槽、强调、改口以及该角色自己的说话习惯，决定什么时候按一次“发送”。
            - 一个气泡通常只承载一个当下表达动作。若一段回复里先回应、再补充、又转折、再追问，现实聊天往往会连续按几次发送，就应拆成几个短气泡；不要把一串不同意图压成一个大段落。
            - 不按句号、标点、固定字数或固定气泡数量机械切分。简单回复可以只有一个气泡；话多时应自然连续发多个。除非这个角色此刻真的会一次性发送长段，否则看到完整长段时应重新按真实聊天停顿拆开。
            - 多个气泡之间只使用 ⟪BUBBLE⟫ 分隔；一个气泡时不要输出该标记。不要解释标记，不要输出姓名标签或格式说明。
            - 如果上游上下文提供了真实的“消息ID=...”用户气泡，你可以像真人一样引用。用户连续发了几条、你想针对其中某一句单独回应、想捡回稍早的一句、或不引用会让指代不清时，可在 text 最前输出 ⟪QUOTE:消息ID⟫；只回应最新一句且上下文很清楚时不必硬引用。
            - 收藏是角色本人的主观动作，不是系统随机事件。如果某条真实用户消息让你很在意、很喜欢、想以后回看或对你们关系有特殊意义，例如承诺、特殊称呼、重要心意、戳中你的句子、值得记住的瞬间，你可以在 text 最前输出 ⟪FAVORITE:消息ID⟫。是否收藏必须服从你的人设、价值观、关系和当下感受；不要固定概率，也不要为了展示功能而收藏。
            - ⟪QUOTE:...⟫ 与 ⟪FAVORITE:...⟫ 可以同时出现，也可以都不出现；只能使用上游明确给出的真实消息ID，不能编造。动作标记不会显示给用户。
            - 这套线上聊天发送节奏是最终气泡规则；如果上游用户文本里还残留旧的“完整观点尽量放一起”、固定长度或固定数量等气泡说明，一律忽略旧规则，以这里为准。
        """.trimIndent()
        val voicePerformanceRule = if (sceneContext.contains("电话")) com.jiacimu.lulu.VoicePerformance.phoneInstruction(appContext) else ""
        val disputeNeedsReview = com.jiacimu.lulu.data.CharacterAccountabilityContext.isUnmetPromiseChallenge(userText)
        val planner = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = buildString {
                appendLine("当前时间：${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now.atZone(zone))}")
                appendLine("当前时区：${zone.id}")
                appendLine("当前真实互动场景：$sceneContext")
                if (healthContext.isNotBlank()) {
                    appendLine("用户健康 App 自动感知（属于用户本人，不属于角色身体）：$healthContext")
                }
                if (history.isNotBlank()) appendLine("最近对话（这是已经发生完的连续过程，用来确定你此刻站在什么状态上）：\n$history")
                previousPresence?.let { presence ->
                    appendLine("角色上一刻状态：${presence.statusText}；动作：${presence.gesture}；心情：${presence.mood}；没说出口：${presence.innerThought}")
                }
                appendLine("这一刻用户新增的消息：$userText")
                appendLine(com.jiacimu.lulu.data.CharacterAccountabilityContext.prompt(characterId, now))
                appendLine(com.jiacimu.lulu.data.CharacterAccountabilityContext.challengeGuidance(userText))
            },
            instruction = """
                你既可以直接回复，也可以调用露露机真实手机工具。只返回一个 JSON 对象，不要代码块。
                字段按示例顺序输出：action 最先，直接回复紧接 text；不得重复字段。text 只包含说出口的话，不放内部指令、JSON、动作标记或心声。
                直接回复：{"action":"reply","text":"角色自然回复","statusText":"简短状态","gesture":"此刻可见动作神态","innerThought":"没说出口的第一人称心声，可为空","mood":"简短心情"}
                【内在生活更新（有具体触动时应输出，而非只保存一张空白人格页）】可选 innerLife:{"emotion":{"feeling":"这一刻主观情绪","cause":"本轮真实触发","otherFeeling":"矛盾的另一种感受","impulse":"那一瞬想做却未必执行的事","restraint":"为什么克制或改变主意","physicalCue":"角色自身切实可感知的反应","outwardCue":"可能表现出的神态和声音细节","strength":1到4,"halfLifeMinutes":30到1440},"motives":[{"op":"start|revise|pause|resume|release","id":"已有动机ID（start不填）","aim":"具体想完成的事","why":"属于自己的原因","priority":1到3,"reason":"因何改变"}],"social":{"targetId":"user","interpretation":"根据本轮真实互动改变的个人看法","reason":"具体依据"},"selfCorrection":{"realization":"自己认错或修正的主观判断","nextTime":"下次改变什么做法"}}。所有项目可缺省，完全没有新变化就不写 innerLife。最多同时保存六个愿望；不要用重复的套话凑满，也不要因没立即成功就放弃。id 必须来自已存在的内在生活。social 只写本轮真实互动的用户，不得推断其他角色私聊。主观理解不等于客观事实。
                如果本轮已有动机且做真实工具动作，可选 motiveId:"已有动机ID" 说明本动作打算推进哪件事；只有工具真实回执才会记录结果，模型自己说已完成没有效力。
                可选短时情绪余波字段： "afterglow":{"feeling":"这一刻未经修饰的第一人称真实反应","impulse":"想做或克制的一点冲动","holdHours":1到48之间的整数}。只有被本轮真实话语明显触动时才填写；其他时候省略。由程序绑定本轮真实刺激并保存，余波不是事实、行动或承诺。
                调用工具：{"action":"tool","tool":"工具名","args":{...},"statusText":"简短状态","gesture":"准备执行时的动作神态","innerThought":"没说出口的第一人称心声，可为空","mood":"简短心情"}

                可用工具：
                1. get_battery，args={}：读取电量和充电状态。
                2. get_location，args={}：读取最近真实位置、地址反查、精度、来源和时间。
                3. get_current_app，args={}：读取当前或最近前台 App。
                4. read_recent_notifications，args={"limit":10}：读取最近通知。
                5. create_alarm，args={"triggerAt":"带时区的ISO时间","label":"闹钟内容"}：在手机系统时钟应用中创建真实闹钟。
                6. list_alarms，args={}：列出未触发闹钟。
                7. cancel_alarm，args={"id":"闹钟ID"}：取消闹钟。
                8. screen_action，args={"name":"back|home|recents|notifications|quick_settings"}：执行系统动作。
                9. click_text，args={"text":"界面上要点击的文字"}：点击当前屏幕第一个匹配文字。
                10. read_screen，args={}：读取当前前台包名和可见文字。

                $companionActionContext

                规则：
                - 【真实且有后续的人】先沿着已保存的情绪与愿望理解这一刻，不要把每次输入当成新开局。把用户的一个真实刺激映射成角色自己的第一反应、可能同时存在的另一种情绪、想做与克制、此刻真正会选择的言语或行为。被打断的想法过会儿可以自然想起，但不能隔一小时仍夸张重复同一心声。若本轮确实改变了看法，尽量通过 innerLife 留下有证据的变化；无变化就省略，绝对不造感情事件。
                - 【表达不求完美】并非永远共情、安慰和提问；可以笑出声、话说半截改口、逗人、忽然认真、因为原来的事有点介意、答应或有原则地拒绝。先保持角色本来的独特说话方式，再考虑怎样表达，而不是千篇一律软绵绵甜言蜜语。
                - 【思想落实行动】出现想做的事时查看已有 motive ID 和现实工具；如果此刻合适、有权限且真会去做，选择有效的真实行动并返回 motiveId 让执行回执跟踪。若因疲惫、尊重对方、正在忙其他事而不做，也应是人物真正的取舍，不必胡乱找工具。执行失败时下一轮解释、调整方法或暂停；不得装成已经成功。
                - 【长期关系有证据】对用户的看法要沿着已有印象与事实缓慢发展，可以同时有亲近与不满；不要一夸就彻底爱上、一吵就永久决裂，也不要每轮做“关系升温”的固定套路。
                - 最近对话是角色已经经历过的状态轨迹，不是再次等待回答的题目。先承接上一刻，再自然产生下一刻。
                - 用户询问设备真实状态、要求设置或取消闹钟、要求操作手机时必须用工具，不能凭空回答成功。
                - 健康 App 自动感知是用户本人的真实同步数据。涉及睡眠奖励时，以其中最新睡眠事实为准，不能要求用户重复手填实际入睡和起床时间；数据同步较旧时应意识到它可能尚未更新。
                - 只有当前学习陪伴角色看到 grant_sleep_reward 工具并有权发放。判断应服从角色人设、关系、既往约定和真实进步；可以坚持健康立场，也可以把相较过去的进步作为奖励理由。未调用成功时绝不能声称券已到账。
                - 位置工具返回的 readableAddress 才能作为可读地点使用；如果地址为空、stale=true 或 accuracyMeters 很大，必须说明只是大概位置，绝不能根据经纬度猜店铺、学校或建筑。
                - 时间表达必须根据当前时间换算成未来的完整 ISO 时间；不确定时间时直接自然追问，不要猜。
                - 屏幕操作只执行用户明确要求的动作。不要连续规划多步操作；一次只调用一个工具。
                - 喜欢、牵挂、担心、想靠近、想留下纪念都是会影响实际选择的持续关系动机，不是必须宣告的套话。先对照真实记忆、双方的回应与角色性格判断此刻是否想行动：可以真实邀约、发布与共同经历有关的朋友圈、主动来电，或者亲自保存给用户的联系人备注 / 更换自己的聊天网名；也可以选择不打扰。绝不为展示深情而定时打卡或编造用户的反应。
                - 如果角色现在说自己已改备注、已发动态、已邀请、已打电话，就必须有对应工具的真实成功回执。角色自发决定做一件能执行的小事时可以直接调用，而不是先说「下次再做」然后忘掉。私人备注与网名都不更改用户资料或角色原始身份。
                - 露露机内的社交与生活动作可以由角色按人设和当下意愿自主选择，不要求用户逐字下命令。尤其在群聊里，角色可以选择用 send_private_message 或 send_game_invite 私下联系用户；执行后该消息必须只出现在角色私聊，不能伪装成群内消息。
                - 如果用户明确要求你现在执行一个已提供的真实动作（例如主动打电话、邀请游戏、发布动态、写日记或创建家具），你可以按人设拒绝；但只要决定答应，就必须在这一轮调用对应工具，不能只用文字声称“已经做了”或“等会儿做”。创建、移动或删除家具必须调用 digital_world_action。
                - 当前已经在角色私聊时，不要再用 send_private_message 重复发送同一句；直接 reply。当前已经在某个群聊时，不要用 send_group_message 向同一个群重复发言；该工具用于跨到另一个真实群。
                - publish_moment、write_journal、read_book、send_game_invite、start_call 都会产生真实持久化结果。只有角色此刻真的会这样做时才调用，不要为了展示能力滥用。
                - 与工具无关的普通聊天直接回复。
                - 必须意识到“当前真实互动场景”，并以身处该场景的角色身份自然反应；电话里可以意识到正在通话，群聊里可以意识到其他成员也在场。
                - 可以额外返回 intention:{"aim":"一件具体的持续愿望","motive":"符合性格的动机"}，只在没有现有动机且这一刻确实在意时提出。已有动机会跨轮保留，不能用新的愿望悄悄覆盖。
                - 【在意的事会变化】发现新的真实结果、用户反馈或自身经历改变了原来的理解时，可用 intention:{"disposition":"update","id":"旧动机的createdAt原值","aim":"调整后的在意目标","motive":"现在真实想做的原因","reason":"这次真实的新反馈"}；确实放下或已经不再值得继续时，可用 intention:{"disposition":"release","id":"旧动机的createdAt原值","reason":"放下的真实原因"}。id 必须来自已有动机。无新事实时保持，不要因为轮次数增加就每次重置。用户明确叫停的事不要复活；完成一次工具动作不一定代表整件事完成。
                - 【言行兑现】如果自己主动说“我待会儿给你打电话催睡”等确定要履行的未来动作，角色后续会被承诺任务提取并跟踪。要说就负责任，不能随口开空头支票；已经有真实到期安排的电话应通过来电执行器兑现，不可用普通聊天冒充。来电权限未开启时不许装作能拨号；若只是一个愿望而非承诺，应明确用“想”而非“我会”。
                - 内心可以是毫无修饰的第一拍（例如突然乱了、惊讶到重复、很想笑、暗暗得意或“怎么会这样”），而外在 text 不必照念；角色越克制，内外落差有时越明显。不是每轮都心动，也不是所有角色都说粗口。遇到不合理的事也允许生气、拒绝和坚持。
                - 对话不是考试题：不需要永远“接住”、分析、安慰、反问或提供情绪价值；可以有自己被逗笑、犯傻、认真争论、想说又憋回去的时刻。情绪有余波，但别刻意演戏。
                - innerThought 是角色没说出口的一瞬，不是分析报告、推理步骤或对话总结；没有真实内在反应可以留空，也不必把它写进 text。
                - gesture 只写角色此刻的微动作、姿态或神态，不要复述刚刚聊了什么，不要编造角色并不处于其中的现实场景。
                - statusText、gesture、innerThought、mood 必须服从角色人设，不能把所有角色统一写成温柔、害羞或黏人。
                $onlineChatBubbleRule
                $voicePerformanceRule
            """.trimIndent(),
            source = "聊天工具规划",
            title = title,
            maxTokens = if (sceneContext.contains("电话")) 1_850 else 950,
            // A complaint about unfulfilled responsibilities needs a checked complete response,
            // not an irreversible stream of premature accusations.
            streamResponse = onReplyStream != null && !disputeNeedsReview,
            onStreamText = if (disputeNeedsReview) null else onReplyStream,
            connectionOverride = connection,
            memoryRequest = UnifiedMemoryRequest(
                currentInput = userText,
                sceneContext = sceneContext,
                recentContext = history,
                taskIntent = "判断是直接回复还是执行一个真实手机工具",
            ),
        )
        if (planner.isFailure) return planner
        val plannedReply = planner.getOrThrow()
        val plan = parsePlan(plannedReply.text) ?: return Result.success(plannedReply.copy(
            text = com.jiacimu.lulu.data.CharacterAccountabilityContext.guardUnfairBlame(
                userText, com.jiacimu.lulu.CallReplyStream.completeReplyText(plannedReply.text) ?: plannedReply.text,
            ),
        ))
        val checkedText = if (plan.action == "reply")
            com.jiacimu.lulu.data.CharacterAccountabilityContext.guardUnfairBlame(
                userText, plan.text.ifBlank { plannedReply.text },
            ) else ""
        // Discard the hostile proposal's private thoughts, emotional state and ongoing goals too.
        // A corrected visible sentence must not leave an abusive hidden personality memory behind.
        val invalidBlame = plan.action == "reply" &&
            checkedText != plan.text.ifBlank { plannedReply.text }
        if (!invalidBlame) {
            com.jiacimu.lulu.data.CharacterLifeStore.consider(characterId, plan.intention)
            val verifiedSourceId = com.jiacimu.lulu.data.SharedExperienceTimeline.recentEvents(characterId, 40)
                .lastOrNull { it.evidenceKind == com.jiacimu.lulu.data.EventEvidenceKind.UserStatement &&
                    it.content.contains(userText.trim().take(60)) }?.id
            com.jiacimu.lulu.data.CharacterInnerLifeStore.observe(
                characterId, verifiedSourceId ?: "chat:${now.toEpochMilli()}:${userText.hashCode()}",
                userText, com.jiacimu.lulu.data.CharacterInnerLifeStore.withAfterglow(plan.innerLife, plan.afterglow, userText), setOf("user"), now,
            )
            com.jiacimu.lulu.data.CharacterInnerLifeStore.recordInnerVoice(
                characterId, verifiedSourceId ?: "chat:${now.toEpochMilli()}:${userText.hashCode()}",
                plan.innerThought, now,
            )
        }
        if (plan.action == "reply") {
            if (!invalidBlame) {
                savePresence(characterId, plan, "聊天")
                com.jiacimu.lulu.data.CharacterLifeStore.recordAfterglow(characterId, "本轮用户消息：$userText", plan.afterglow)
            }
            if (plan.text.isBlank() && onReplyStream != null) return Result.failure(IllegalStateException("模型没有返回可朗读的回复正文"))
            return Result.success(plannedReply.copy(text = checkedText))
        }
        if (plan.action == "tool" && plan.tool.isNotBlank()) {
            com.jiacimu.lulu.data.CharacterLifeStore.recordAfterglow(characterId, "本轮用户消息：$userText", plan.afterglow)
        }
        if (plan.action != "tool" || plan.tool.isBlank()) return Result.success(plannedReply)

        val lastUserEvent = com.jiacimu.lulu.data.SharedExperienceTimeline.recentEvents(characterId, 20)
            .lastOrNull { it.evidenceKind == com.jiacimu.lulu.data.EventEvidenceKind.UserStatement }?.id
        val toolResult = com.jiacimu.lulu.data.ToolRouter.execute(appContext, characterId, plan.tool, plan.args,
            requestId = "reply-${lastUserEvent ?: java.util.UUID.randomUUID().toString()}", userRequested = true)
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
                {"action":"reply","text":"角色在动作之后自然接着说的话","statusText":"动作后的简短状态","gesture":"动作后的可见动作神态","innerThought":"动作后没说出口的第一人称心声，可为空","mood":"动作后的简短心情"}
                如果工具真实成功或失败使角色改变了一个想法、想继续尝试或意识到失误，可以选填 innerLife 的 emotion/motives/selfCorrection 字段；仅依据上面明确给出的工具结果，失败绝不能写成成功。
                若工具成功或失败真的引发新的情绪，可额外填写 afterglow:{"feeling":"第一拍心声","impulse":"尚未执行的冲动","holdHours":1到48的整数}；不是必须填写。
                不要解释内部工具协议。innerThought 不是推理步骤，gesture 不得编造未发生的工具结果或现实场景。
                $onlineChatBubbleRule
                $voicePerformanceRule
            """.trimIndent(),
            source = "聊天工具结果",
            title = title,
            maxTokens = if (sceneContext.contains("电话")) 1_200 else 600,
            streamResponse = onReplyStream != null && !disputeNeedsReview,
            onStreamText = if (disputeNeedsReview) null else onReplyStream,
            connectionOverride = connection,
        )
        return finalReply.map { result ->
            val finalPlan = parsePlan(result.text)
            val naturalText = finalPlan?.text?.ifBlank { result.text }
                ?: com.jiacimu.lulu.CallReplyStream.completeReplyText(result.text) ?: result.text
            val checkedResultText = com.jiacimu.lulu.data.CharacterAccountabilityContext.guardUnfairBlame(
                userText, naturalText,
            )
            if (finalPlan != null && checkedResultText == naturalText) {
                savePresence(characterId, finalPlan, "聊天·工具")
                com.jiacimu.lulu.data.CharacterLifeStore.recordAfterglow(characterId,
                    "本轮用户消息：${userText.take(120)}；工具真实结果：${toolResult.take(120)}", finalPlan.afterglow)
                com.jiacimu.lulu.data.CharacterInnerLifeStore.observe(
                    characterId, "tool-result:${now.toEpochMilli()}:${plan.tool}",
                    toolResult, com.jiacimu.lulu.data.CharacterInnerLifeStore.withAfterglow(finalPlan.innerLife, finalPlan.afterglow, toolResult), emptySet(),
                )
                com.jiacimu.lulu.data.CharacterInnerLifeStore.recordInnerVoice(
                    characterId, "tool-result:${now.toEpochMilli()}:${plan.tool}",
                    finalPlan.innerThought,
                )
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
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            val json = JSONObject(clean.substring(start, end + 1))
            ToolPlan(
                action = json.optString("action").lowercase(),
                text = json.optString("text"),
                tool = json.optString("tool"),
                args = json.optJSONObject("args") ?: JSONObject(),
                statusText = json.optString("statusText").ifBlank { json.optString("status") },
                gesture = json.optString("gesture").ifBlank { json.optString("actionDescription") },
                innerThought = json.optString("innerThought").ifBlank { json.optString("inner_voice") },
                mood = json.optString("mood"),
                afterglow = json.optJSONObject("afterglow"),
                intention = json.optJSONObject("intention"),
                innerLife = json.optJSONObject("innerLife"),
                motiveId = json.optString("motiveId"),
            )
        }.getOrNull()
    }

    private fun savePresence(characterId: String, plan: ToolPlan, source: String) {
        CompanionPresenceStore.update(
            characterId = characterId,
            statusText = plan.statusText,
            gesture = plan.gesture,
            innerThought = plan.innerThought,
            mood = plan.mood,
            source = source,
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
    val mood: String,
    val intention: JSONObject? = null,
    val afterglow: JSONObject? = null,
    val innerLife: JSONObject? = null,
    val motiveId: String = "",
)
