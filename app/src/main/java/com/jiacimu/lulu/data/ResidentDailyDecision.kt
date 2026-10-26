package com.jiacimu.lulu.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ScopedModelSelections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Duration
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Native people get a single bounded model decision per local day, not a
 * replica of the owner's device/screen/perception route. The model chooses
 * optional leisure, one social gesture and private mood; deterministic duty
 * slots continue to execute from confirmed local state with no API call.
 */
internal object ResidentDailyDecision {
    const val SCOPE = "resident_daily"
    private const val PREFS = "lulu_resident_daily_decisions_v1"
    private const val WORK_PERIODIC = "lulu-resident-daily-decision-v1"
    private const val WORK_NOW = "lulu-resident-daily-initial-v1"
    private const val WORK_FIXED = "lulu-resident-daily-at-local-time-v2"
    /** Shared local planning hour. WorkManager is durable but Android may defer its exact delivery. */
    internal val DAILY_PLANNING_TIME: LocalTime = LocalTime.of(8, 0)
    private const val MAX_AGENDA_ITEMS = 8
    private const val MAX_MESSAGE = 500
    @Volatile private var appContext: Context? = null

    fun planForApp(residentId: String, at: Instant): JSONObject? =
        appContext?.let { planFor(it, residentId, at) }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun date(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        at.atZone(zone).toLocalDate().toString()

    fun attemptedToday(context: Context, residentId: String, at: Instant = Instant.now()): Boolean =
        prefs(context).getString("attempt:$residentId", "") == date(at)

    fun planFor(context: Context, residentId: String, at: Instant): JSONObject? = runCatching {
        val root = JSONObject(prefs(context).getString("plan:$residentId", "{}").orEmpty())
        root.takeIf { it.optString("date") == date(at) }
    }.getOrNull()

    /** Planned actions use the same shared calendar / wake / receipt store as other characters. */
    internal fun agendaTime(at: Instant, clock: String, zone: ZoneId = ZoneId.systemDefault()): Instant? {
        val time = runCatching { LocalTime.parse(clock.trim()) }.getOrNull() ?: return null
        val target = at.atZone(zone).toLocalDate().atTime(time).atZone(zone).toInstant()
        return target.takeIf { it.isAfter(at.plusSeconds(90)) }
    }

    internal fun nextPlanningTime(now: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant {
        var next = now.atZone(zone).toLocalDate().atTime(DAILY_PLANNING_TIME).atZone(zone)
        if (!next.toInstant().isAfter(now)) next = next.plusDays(1)
        return next.toInstant()
    }

    private val residentAgendaActions = setOf(
        "send_character_message", "send_group_message", "publish_moment",
        "digital_world_action", "play_solo_game",
    )

    /** Preserve model-selected targets and destinations in the SAME args checked at execution.
     * A top-level agenda target otherwise exists only in plan metadata, leaving the
     * scheduled ToolRouter call with no recipient or a mismatched location.
     * This never grants permission: canBegin and ToolRouter revalidate at due time.
     */
    internal fun agendaArgs(action: String, row: JSONObject): JSONObject {
        val args = row.optJSONObject("activityArgs")?.let { JSONObject(it.toString()) } ?: JSONObject()
        args.remove("planId")
        if (action == "send_character_message") {
            val target = row.optString("targetCharacterId").trim()
                .ifBlank { args.optString("targetCharacterId").trim() }
            if (target.isNotBlank()) args.put("targetCharacterId", target)
        }
        if (action == "digital_world_action" &&
            args.optString("worldAction") == "visit_public_place") {
            val place = row.optString("locationCode").trim()
                .ifBlank { args.optString("locationCode").trim()
                    .ifBlank { args.optString("location").trim() } }
            if (place.isNotBlank()) args.put("locationCode", place)
        }
        return args
    }

    /** Model plans are proposed intentions, not actions. Registry and social facts own validity. */
    // The failure receipt uses a suspendable performance repository. Keep it
    // in the caller's Worker coroutine instead of launching detached logging.
    internal suspend fun saveAgenda(context: Context, native: DigitalWorldResident, proposal: JSONObject,
        at: Instant): List<RoleDayPlan> {
        val actor = ResidentCharacterBridge.characterId(native.id)
        val agenda = proposal.optJSONArray("agenda") ?: return emptyList()
        val known = ResidentSocialMailbox.visibleContacts(actor).toSet()
        val saved = mutableListOf<RoleDayPlan>()
        for (index in 0 until minOf(agenda.length(), MAX_AGENDA_ITEMS)) {
            val row = agenda.optJSONObject(index) ?: continue
            val action = CapabilityRegistry.scheduledActionName(row.optString("activityAction"))
            if (action !in residentAgendaActions) continue
            val dueAt = agendaTime(at, row.optString("at")) ?: continue
            val title = row.optString("title").trim().take(95)
            val intent = row.optString("intent").trim().take(270)
            if (title.length < 2 || intent.length < 3) continue
            // Keep target/location in the persisted tool arguments, not only in plan metadata.
            val params = agendaArgs(action, row)
            val target = params.optString("targetCharacterId").trim()
            if (action == "send_character_message" &&
                (target !in known || target == actor)) continue
            if (action == "send_group_message") {
                val group = params.optString("groupId")
                if (group.isBlank() || MigratedDomainStores.chat.conversations.value.none {
                    it.id == group && it.groupChat?.members?.any { member -> member.characterId == actor } == true
                }) continue
            }
            val location = params.optString("locationCode").ifBlank {
                row.optString("locationCode").ifBlank { params.optString("location") }
            }.takeIf { requested -> DigitalWorldPublicPlaces.all.any { it.code == requested } }.orEmpty()
            val worldAction = params.optString("worldAction")
            if (action == "digital_world_action" && (worldAction !in setOf(
                "visit_public_place", "interact_resident", "visit_resident_home",
                "use_location", "go_home"))) continue
            if (action == "digital_world_action" && worldAction == "visit_public_place" &&
                location.isBlank()) continue
            // An agenda is not permission to generate a future message with no content.
            // The due decision can revise it, but an empty draft must not enter a
            // seemingly executable schedule that will predictably fail at ToolRouter.
            if (action in setOf("send_character_message", "send_group_message", "publish_moment") &&
                params.optString("text").isBlank()) continue
            if (action == "digital_world_action" && worldAction == "interact_resident" &&
                DigitalWorldExpansionStore.residents().none { it.id == params.optString("itemId") }) continue
            val targetResident = if (action == "digital_world_action" &&
                worldAction == "interact_resident") params.optString("itemId") else ""
            val result = runCatching {
                RoleDayScheduleStore.arrange(context, actor, title, intent, dueAt, at,
                    activityAction = action, activityArgs = params,
                    targetCharacterId = if (action == "send_character_message") target else "",
                    targetResidentId = targetResident, locationCode = location,
                    selfInitiated = true)
            }.onFailure { error ->
                com.jiacimu.lulu.LuluRepositories.performance.recordError(
                    source = "数字世界居民日程", title = "${native.name}的活动没有排入日程",
                    message = error.message.orEmpty().take(180))
            }.getOrNull()
            if (result != null) saved += result
        }
        return saved
    }

    internal val wakeTools = setOf("like_moment", "comment_moment", "send_friend_request",
        "respond_friend_request", "send_character_message", "publish_moment",
        "send_group_message", "create_group_chat", "play_solo_game", "digital_world_action")

    /** Project the executable resident catalogue into BOTH regular and due decisions. */
    internal fun wakeToolContext(catalog: String): String = catalog.lineSequence().filter { line ->
        val name = line.trimStart().removePrefix("- ").substringBefore("，").substringBefore("：")
        name in wakeTools
    }.joinToString("\n")

    /** Side actions share the resident's wake decision and original model
     * connection. Real tools keep individual idempotent receipts. Side actions
     * are never allowed to inherit the due activity's planId.
     */
    internal suspend fun executeWakeSteps(context: Context, role: String,
        decision: JSONObject, turnId: String, initialCompleted: Boolean = true): Int {
        val original = decision.optJSONArray("steps") ?: return 0
        val steps = JSONArray()
        for (i in 0 until original.length()) {
            val row = original.optJSONObject(i)
            if (row == null) {
                steps.put(original.opt(i)) // preserve invalid position for the shared failure report
                continue
            }
            val step = AutonomousActionWireProtocol.normalizeStep(row)
            val args = step.optJSONObject("args") ?: JSONObject()
            args.remove("planId")
            args.remove("_replyEvidenceIds")
            step.put("args", args)
            steps.put(step)
        }
        val report = OrderedCharacterActions.execute(context, role, steps,
            requestId = turnId, initialCompleted = initialCompleted,
            autonomousLifeTurn = true, sceneAllowedTools = wakeTools,
            // Existing releases used these IDs; replay must recover the same
            // receipts instead of performing a second post/message/like.
            requestIdForStep = { index -> "$turnId-extra-$index" })
        if (report.completedCount < steps.length()) {
            com.jiacimu.lulu.LuluRepositories.performance.recordError(
                source = "居民手机与多步行动", title = "附加行动含未完成步骤",
                message = report.notes.joinToString("\n"))
        }
        return report.completedCount
    }

    /** Each due item receives ONE fresh, cheap role decision. A recorded action is not an effect. */
    suspend fun runDuePlan(context: Context, plan: RoleDayPlan, at: Instant = Instant.now()): Boolean {
        val app = context.applicationContext
        appContext = app
        val native = ResidentCharacterBridge.nativeResident(plan.characterId) ?: return false
        if (plan.status !in setOf("due", "planned") || at.isBefore(plan.dueAt)) return false
        initializeBackgroundRuntime(app)
        ResidentSocialMailbox.initialize(app)
        val selected = runCatching {
            ScopedModelSelections.resolveConnection(SCOPE)
        }.getOrNull() ?: return false
        val key = "execute:${plan.id}:${plan.dueAt}"
        // Failures remain visible as due; a stopped worker cannot silently buy another call.
        if (prefs(app).getBoolean(key, false)) return false
        if (!prefs(app).edit().putBoolean(key, true).commit()) return false
        CharacterPhoneInbox.initialize(app)
        val pending = ResidentSocialMailbox.pendingIncoming(plan.characterId)
        val unread = CharacterPhoneInbox.unreadMoments(plan.characterId)
        val proposedArgs = runCatching { JSONObject(plan.activityArgs) }.getOrDefault(JSONObject())
        val now = Instant.now()
        val response = LuluAiServices.gateway.generate(
            characterId = plan.characterId,
            facts = decisionFacts(native, now, pending, unread) + "\n" +
                "【确实已到期的个人日程】planId=${plan.id}；原想法=${plan.title}；动机=${plan.intent}；" +
                "时间=${plan.dueAt}；预选工具=${plan.activityAction}；预选参数=${proposedArgs}。" +
                "这是当初的计划，并不是已执行。原定见面对象/联系人可能已经离开或不再可联系，请重新查证。",
            instruction = """
                你是${native.name}。此刻你从休眠中醒来，先感知自己的地点、身体、到期活动和手机里的未读动态、聊天消息、好友申请，再选择行为。
                你本人预先安排的一项真实生活活动已经到点，它是当前优先要复议的原计划；看手机不等于完成、撤销或放弃它。
                根据本人意愿可以执行、改期、取消或等待，也可以在同一次决定额外选多个真实生活行动，形成独立回执。
                如果执行，只能选原定合法工具=${plan.activityAction}；从预选参数和当前事实给出真正可执行的 args。
                私信必须是真实认识的人，面对面互动必须实际同场；对方没有回答不算聊过，去过地点不等于已经见面。
                行动后由工具留下独立真实回执，禁止自行宣告结果或替另一人说话。
                一次决策可同时选择原计划和额外 steps，全部是你本人真实想做的，不必为活跃而点赞或加好友。
                原计划用 choice=execute|reschedule|cancel|wait，追加步骤用 steps=[{"tool":"like_moment|comment_moment|send_friend_request|respond_friend_request|send_character_message|publish_moment|send_group_message|create_group_chat|play_solo_game|digital_world_action","args":{}}]。
                如对手机的真实postId点赞评论，必须提供 postId，评论还要 text；添加真实认识的人需 targetCharacterId，同意申请需实际收到的 requestId 和 decision=accept|decline。
                每步可填写 requiresPreviousSuccess=true|false；true 仅在前一步有实际成功回执后才执行，false 为独立行动。首个附加步骤若选true，需原计划本轮已执行成功；改期、取消、等待不算原活动执行成功。每步可带原有 motiveId/concernId/experienceId，分别关联真实结果。
                不能在附加步骤传 planId 或冒充执行原计划；对方未同意好友前不许称已添加成功。
                只输出JSON：{"choice":"execute|reschedule|cancel|wait","args":{},"dueAt":"改期时新的ISO8601时间","reason":"本人理由","steps":[]}。
                "wait"只是让原日程保持未完成；原计划是否完成由其真实工具回执单独核验。
            """.trimIndent(),
            source = "原住民个人日程到点", title = "${native.name} · ${plan.title.take(48)}",
            connectionOverride = selected, contextMode = CompanionContextMode.CharacterAndScenario,
            maxTokens = 1_300,
        ).onFailure { error ->
            com.jiacimu.lulu.LuluRepositories.performance.recordError(
                source = "数字世界居民日程", title = "${native.name}的到点决策失败",
                message = error.message.orEmpty().take(180))
        }.getOrNull() ?: return false
        val choice = response.text.let(ModelStructuredOutput::objectOrNull) ?: return false
        val wanted = choice.optString("choice")
        if (wanted !in setOf("execute", "reschedule", "cancel", "wait")) return false
        val originalHandled = when (wanted) {
            "execute" -> {
                // When the model explicitly chooses to execute but leaves args
                // unchanged, reuse its own saved proposal; live ToolRouter still
                // rechecks location, recipient, authority and evidence.
                val args = choice.optJSONObject("args")?.takeIf { it.length() > 0 }
                    ?.let { JSONObject(it.toString()) } ?: JSONObject(proposedArgs.toString())
                args.remove("_replyEvidenceIds")
                args.put("planId", plan.id)
                // ToolRouter checks the SAME plan authority and records a durable
                // rejection. A failed primary must not discard independent steps.
                val receipt = JSONObject(ToolRouter.execute(app, plan.characterId,
                    plan.activityAction, args, requestId = "resident-plan-${plan.id}-${plan.dueAt.epochSecond}",
                    autonomousLifeTurn = true))
                receipt.optBoolean("success") &&
                    RoleDayScheduleStore.plans(app, plan.characterId).any {
                        it.id == plan.id && it.status == "completed"
                    }
            }
            "reschedule" -> {
                val replacement = runCatching { java.time.OffsetDateTime.parse(
                    choice.getString("dueAt")).toInstant() }.getOrNull() ?: return false
                runCatching { RoleDayScheduleStore.reschedule(app, plan.characterId, plan.id,
                    replacement, choice.optString("reason"), Instant.now()) }.isSuccess
            }
            "cancel" -> RoleDayScheduleStore.cancel(app, plan.characterId, plan.id,
                choice.optString("reason"), Instant.now())
            "wait" -> true // still due, never count as performed
            else -> false
        }
        executeWakeSteps(app, plan.characterId, choice,
            "native-wake-${plan.id}-${plan.dueAt.epochSecond}",
            initialCompleted = wanted == "execute" && originalHandled)
        check(CharacterPhoneInbox.acknowledgeObserved(plan.characterId, unread)) {
            "手机动态已参与感知但未读状态未保存"
        }
        pending.lastOrNull()?.let {
            check(ResidentSocialMailbox.acknowledgeIncoming(plan.characterId, it.id))
        }
        return originalHandled
    }

    /** Optional leisure choices are bounded by real public locations. */
    fun allowedLeisureLocation(value: String): String? = when (value) {
        "home", "cafe", "games", "courtyard" -> value
        else -> null
    }

    internal fun publicPlace(option: String, nativeHome: String): String = when (option) {
        "cafe" -> DigitalWorldPublicPlaces.CAFE
        "games" -> DigitalWorldPublicPlaces.GAME_HALL
        "courtyard" -> DigitalWorldPublicPlaces.COURTYARD
        else -> nativeHome
    }

    /** A paid request is claimed durably *before* network access. A crash,
     * timeout, or race cannot cause multiple chargeable calls in the day. */
    @Synchronized
    internal fun claim(context: Context, nativeId: String, at: Instant): Boolean {
        val key = "attempt:$nativeId"
        val p = prefs(context)
        val day = date(at)
        if (p.getString(key, "") == day) return false
        return p.edit().putString(key, day).commit()
    }

    /** A small real action still belongs to tomorrow's life. Only this resident's
     * own witnessed events or settled executor receipts can recur as evidence;
     * a plan / draft never becomes a completed experience.
     */
    internal fun ownLifeEvidence(characterId: String, history: List<SharedTimelineEvent>): List<SharedTimelineEvent> =
        history.asSequence()
            .filter { it.characterId == characterId }
            .filter { event ->
                when (event.source) {
                    "tool-router" -> event.evidenceKind == EventEvidenceKind.ToolResult &&
                        runCatching { JSONObject(event.content).optString("status") in
                            setOf("succeeded", "failed") }.getOrDefault(false)
                    "resident-duty", "meeting", "character-message", "owner-on-behalf",
                    "digital-world", "moment:self" -> true
                    else -> false // A daily plan, a hypothetical thought, or someone else's story isn't an outcome.
                }
            }
            .toList().takeLast(12)

    private fun recentOwnEvents(characterId: String): List<SharedTimelineEvent> =
        ownLifeEvidence(characterId, SharedExperienceTimeline.recentEvents(characterId, 120))

    internal fun lifeEvidenceLine(event: SharedTimelineEvent): String {
        if (event.source == "tool-router") {
            val receipt = runCatching { JSONObject(event.content) }.getOrNull()
                ?: return ""
            val result = runCatching { JSONObject(receipt.optString("result")) }.getOrNull()
            val detail = result?.let { value ->
                value.optString("summary").ifBlank { value.optString("error") }
            }.orEmpty().trim().take(180)
            return "${event.occurredAt} | 实际工具回执 | ${receipt.optString("tool")} " +
                "${receipt.optString("status")}：${detail.ifBlank { "未提供结果说明" }}"
        }
        return "${event.occurredAt} | ${event.channel} | ${event.speaker}：${event.content.take(180)}"
    }

    private fun recentOwnExperience(characterId: String): String =
        recentOwnEvents(characterId).map(::lifeEvidenceLine)
            .filter(String::isNotBlank).joinToString("\n")

    private fun context(native: DigitalWorldResident, at: Instant,
        pending: List<ResidentDirectMessage>): String {
        val characterId = ResidentCharacterBridge.characterId(native.id)
        val body = EmbodiedBodyRuntime.context(characterId).take(750)
        val contacts = ResidentSocialMailbox.visibleContacts(characterId).map { target ->
            "$target=${MigratedDomainStores.characters.get(target).displayName}"
        }
        val groups = MigratedDomainStores.chat.conversations.value.filter {
            it.groupChat?.members?.any { member -> member.characterId == characterId } == true
        }.take(6).joinToString("、") { "${it.id}=${it.title}" }
        val relations = CharacterSocialRelationship.perspectivesFor(characterId,
            ResidentSocialMailbox.visibleContacts(characterId))
        val publicMoments = MomentFriendAccess.visiblePosts(characterId, MomentsStore.posts.value)
            .asSequence()
            .filter { it.authorType == MomentAuthorType.Character &&
                it.authorCharacterId != characterId &&
                it.authorCharacterId?.let(DigitalLifeProfileStore::isEnabled) == true }
            .take(8)
            .joinToString("\n") {
                val author = it.authorCharacterId.orEmpty()
                "${MigratedDomainStores.characters.get(author).displayName}：${it.content.take(200)}"
            }
        val mail = ResidentSocialMailbox.inbox(characterId, 8).joinToString("\n") {
            val speaker = MigratedDomainStores.characters.get(it.fromId).displayName
            val recipient = MigratedDomainStores.characters.get(it.toId).displayName
            "$speaker → $recipient: ${it.content.take(220)}"
        }
        val witnesses = DigitalWorldExpansionStore.residentsAt(native.currentLocationCode)
            .filter { it.id != native.id }.take(8)
            .joinToString("、") { "${it.name} (resident:${it.id})" }
        val otherRoles = MigratedDomainStores.characters.settings.value.values.filter {
            it.characterId != characterId && !it.characterId.startsWith("resident:") &&
                DigitalLifeProfileStore.isEnabled(it.characterId) &&
                DigitalWorldStore.locationOf(it.characterId) == native.currentLocationCode
        }.take(6).joinToString("、") { "${it.displayName} (${it.characterId})" }
        return """
            【这是你本人所在的世界，而非用户手机】${native.name}，身份=${native.identity}。
            今天=${date(at)}，现居=${native.homeLocationCode}，所在地点=${native.currentLocationCode}，当前职责=${native.dutyActivity}。
            原住民只亲自感知自己所在地点和过去真实接触的人；你看不到现实世界里的用户手机、屏幕、私聊，也不能推知其他角色的私人心声。
            实际在场的原住民：${witnesses.ifBlank { "未遇见" }}。
            实际在场的其他数字人物：${otherRoles.ifBlank { "无" }}。
            认识或真实相遇后才可私信的对象：${contacts.ifEmpty { listOf("暂无") }.joinToString("、")}。
            本人确实加入的群聊 groupId：${groups.ifBlank { "暂无" }}。
            数字世界好友动态（仅已确认好友可见；不代表本人已经与作者见面）：
            ${publicMoments.ifBlank { "暂无" }}
            只属于你本人的最近通信：
            ${mail.ifBlank { "无" }}
            ${CharacterEverydaySocialContext.render(characterId, at, pending)}
            自己确实经历过的事情与工具结果（失败也是真实经历；计划未执行不算）：
            ${recentOwnExperience(characterId).ifBlank { "暂未积累" }}
            自己对熟人的真实个人印象（对方未必这么想）：
            ${relations.ifBlank { "暂无" }}
            自己的身体：
            $body
            ${DigitalWorldPublicBulletin.publicContext(at).ifBlank { "今天没有新的公共活动公告" }}
            这是有限世界信息，不是整个数字世界的直播。没有列在此处的隐私、谈话、住户和事件不能猜成亲眼所见。
        """.trimIndent()
    }

    /** Same role-owned state and local recall as other adapters. No extra
     * embedding, rerank or paid psychological request for a daily plan. */
    internal suspend fun decisionFacts(native: DigitalWorldResident, at: Instant,
        pending: List<ResidentDirectMessage> = ResidentSocialMailbox.pendingIncoming(
            ResidentCharacterBridge.characterId(native.id)),
        unread: List<MomentPost> = CharacterPhoneInbox.unreadMoments(
            ResidentCharacterBridge.characterId(native.id))): String {
        val id = ResidentCharacterBridge.characterId(native.id)
        val facts = context(native, at, pending) + "\n" +
            CharacterPhoneInbox.modelContext(id, unread) + "\n" +
            CharacterPhoneInbox.requestContext(id) + "\n" +
            appContext?.let { wakeToolContext(CompanionActionRuntime.capabilityContext(it, id)) }.orEmpty()
        val memory = CharacterRuntime.actionMemory(id, UnifiedMemoryRequest(
            currentInput = pending.joinToString("\n") { it.content },
            sceneContext = "原住民自己的日常生活与熟人来往",
            recentContext = facts,
            taskIntent = "根据本人经历、心情与未尽念头安排今天，不补造他人经历",
        ))
        return listOf(facts,
            appContext?.let { RoleDayScheduleStore.plannedContext(it, id, at) }.orEmpty(),
            CharacterRuntime.personalityRuntimeContext(id, compact = true,
                query = facts, includeActiveCommitments = false),
            // CharacterAndScenario delivers core memories through its central
            // channel; do not inject the same core a second time in facts.
            memory.copy(coreMemories = emptyList()).compactPromptSection())
            .filter(String::isNotBlank).joinToString("\n\n")
    }

    /** Called by the scheduled worker; each citizen chooses using exactly one
     * invocation, from the shared inexpensive model archive selected in settings. */
    suspend fun runOnce(context: Context, native: DigitalWorldResident,
        now: Instant = Instant.now()): Boolean {
        val app = context.applicationContext
        appContext = app
        ResidentCharacterBridge.sync(app, now)
        ResidentSocialMailbox.initialize(app)
        CharacterPhoneInbox.initialize(app)
        CharacterInnerLifeStore.initialize(app)
        val id = ResidentCharacterBridge.characterId(native.id)
        val selected = runCatching {
            ScopedModelSelections.initialize(app)
            ScopedModelSelections.resolveConnection(SCOPE)
        }.getOrNull() ?: return false
        if (!claim(app, native.id, now)) return false
        val pending = ResidentSocialMailbox.pendingIncoming(id)
        val unread = CharacterPhoneInbox.unreadMoments(id)
        val reply = LuluAiServices.gateway.generate(
            characterId = id,
            facts = decisionFacts(native, now, pending, unread),
            instruction = """
                你是生活在这个世界中的${native.name}。只根据自己的现状、个人性格、兴趣和已知交往自主安排今天。
                保留已有工作职责，自己选择午休和晚间的活动，允许无聊、独处、想玩、约人或突然有点小心思。
                这也是每天规划时一次真实的手机感知：你知道这会儿哪些好友新发了朋友圈，有什么未读私信与好友申请。可以在同一轮模型决定日程，并自己选择处理或忽略它们。
                注意昨天和之前实际发生的小事：某次工具失败、没送出去的信、一次真实互动、一次具体的快乐或失落，都可能让你今天换个办法、继续关注或决定放下。但回执只能说明执行成功/失败，不能替别人确认喜欢、收到或原谅；已写计划不等于亲历，不为显得热闹而重复安排。
                更重要的是：你活在有人际交往的真实小世界里。想见熟人、与另一位居民聊共同话题、约朋友玩、回复真实来信、参与群聊、组织自己认识的好友建群或结识新朋友，都可以成为具体行程；不是每天只有喝水和独处。
                有真实已确认的好友时，可以选择当下 steps 工具 create_group_chat，args={"groupName":"自己想的名称","memberIds":["邀请的真实好友角色ID"]} 组建没有用户自动参与的朋友圈小群；创建并不代表群友回应。确认有合适共同话题再建，已有适合群时优先继续旧群。
                steps 按本人选择的先后执行；每步可填写 requiresPreviousSuccess=true|false，true 依赖前一步实际完成，false 可独立继续；没有前置动作的首步可直接执行。可带原有 motiveId/concernId/experienceId，让每个回执只关联自己的事项。
                你可以决定今天没有行程，也可根据生活需要自然安排多项。每项独立到点醒来、再判断是否执行；不要为了填满日程编造友谊或假设对方一定赴约。
                agenda 中每项需包含 at=今天当地HH:mm未来时刻、title=短活动、intent=本人的真实动机、activityAction、activityArgs。
                真实发私信/群聊/动态时，在 activityArgs.text 写你本人准备说的内容；发私信需同时写真实 targetCharacterId，不留空。访问公共地点时 activityArgs.worldAction=visit_public_place 且 locationCode 要与日程地点一致。日程写下不是对方收到消息，计划到点仍需你重新选择并得到工具回执。
                可用：send_character_message（只限上文已认识的真实ID，targetCharacterId 与 args.targetCharacterId 一致）；digital_world_action（visit_public_place，args.worldAction 与 locationCode 用真实公共地点码；或已同场时 interact_resident，args.itemId 为真实居民ID）；publish_moment；play_solo_game；本人实际入群才可 send_group_message。
                去公共地点是去找接触机会，不保证见到目标；要真正交流须执行当下可证实的互动或私信。人际对象可能后来改期或拒绝。
                仅输出 JSON，不能替别人发言：
                {"agenda":[{"at":"HH:mm","title":"计划中的一件具体活动","intent":"本人为什么想做","activityAction":"send_character_message|digital_world_action|publish_moment|play_solo_game|send_group_message","targetCharacterId":"真实认识的ID或留空","locationCode":"公共地点码或留空","activityArgs":{}}],
                 "lunch":"cafe|home|courtyard|games",
                 "evening":"cafe|home|courtyard|games",
                 "eveningActivity":"一件自己愿意做的短活动",
                 "mood":"今天本人真实的心情，可为空",
                 "thought":"自己内心一闪而过的念头，可为空",
                 "post":"如果今天真的想发朋友圈，填自己的短文；否则留空",
                 "messageTo":"上文列出的确实认识的角色ID，否则为空",
                 "message":"给这位角色真正想说的私信，否则为空",
                 "steps":[{"tool":"like_moment|comment_moment|send_friend_request|respond_friend_request|send_character_message|publish_moment|send_group_message|play_solo_game|digital_world_action","args":{}}],
                 "innerLife":{"emotion":{"feeling":"确实发生变化时的情绪","cause":"本人真实经历的原因"},"social":{"targetId":"确实认识的对象ID","interpretation":"本人对其个人看法","reason":"双方实际来往的依据","dimensions":{"trust":"up|down|same","warmth":"up|down|same","ease":"up|down|same","friction":"up|down|same","boundarySafety":"up|down|same"}}}}}
                不能为了活跃指标强制发帖/发消息；不能凭空接触不在身边的人或替别人决定是否喜欢你。
                发布只是你的选择，不代表对方一定读过；关系随各自真实来往慢慢改变，不能宣告双方已相爱。
                用户只允许数字世界内社交与行动，禁止现实设备感知、联网、购买和自主联系现实用户。
            """.trimIndent(),
            source = "数字世界原住民每日规划",
            title = "${native.name}的一天",
            connectionOverride = selected,
            contextMode = CompanionContextMode.CharacterAndScenario,
            maxTokens = 1_900,
        ).onFailure { error ->
            com.jiacimu.lulu.LuluRepositories.performance.recordError(
                source = "数字世界居民日程", title = "${native.name}今天规划失败",
                message = error.message.orEmpty().take(180))
        }.getOrNull()
        val json = reply?.text?.let(ModelStructuredOutput::objectOrNull)
        if (json == null) {
            if (reply != null) com.jiacimu.lulu.LuluRepositories.performance.recordError(
                source = "数字世界居民日程", title = "${native.name}规划格式不完整",
                message = "模型未返回可解析的当天日程 JSON，原消息未当作真实安排")
            return false
        }
        val lunch = allowedLeisureLocation(json.optString("lunch")) ?: "cafe"
        val evening = allowedLeisureLocation(json.optString("evening")) ?: "home"
        val plan = JSONObject()
            .put("date", date(now))
            .put("lunch", lunch)
            .put("evening", evening)
            .put("eveningActivity", json.optString("eveningActivity").trim().take(95))
            .put("mood", json.optString("mood").trim().take(140))
            .put("thought", json.optString("thought").trim().take(250))
        check(prefs(app).edit().putString("plan:${native.id}", plan.toString()).commit()) {
            "原住民日程没有保存"
        }
        val agenda = saveAgenda(app, native, json, now)
        val eventId = "native-daily-${native.id}-${date(now)}"
        SharedExperienceTimeline.record(
            eventId = eventId, characterId = id, channel = "原住民·每日自我安排",
            speaker = native.name, source = "resident-daily", occurredAt = now,
            evidenceKind = EventEvidenceKind.CharacterStatement, triggerExtraction = false,
            content = "本人选定的当天安排：午休=$lunch；晚间=$evening；晚间想做：${plan.optString("eveningActivity")}。" +
                "已排入真实日程的自主活动=${agenda.joinToString("；") { "${it.dueAt} ${it.title}" }}。计划均尚未执行。",
        )
        // The same private thought and social perspective channel used by
        // protagonists. An unsupported stranger's bond is never invented.
        val known = ResidentSocialMailbox.visibleContacts(id).toSet()
        val incomingEvents = SharedExperienceTimeline.eventsByIds(id,
            pending.map { ResidentSocialMailbox.evidenceId(it, id) })
        val scopedInnerLife = json.optJSONObject("innerLife")?.let { JSONObject(it.toString()) }
        var mentalEvidenceId = eventId
        scopedInnerLife?.let { scoped ->
            val target = scoped.optJSONObject("social")?.optString("targetId").orEmpty()
            val encounter = (incomingEvents + recentOwnEvents(id))
                .filter { it.evidenceKind == EventEvidenceKind.Observation && !it.occurredAt.isAfter(now) }
                .map(CharacterPerceptionContext::stimulus)
                .lastOrNull { target in it.socialIds }
            // Merely knowing a name is not a new encounter. Use an actual
            // supplied letter/meeting as provenance, so withdrawal works.
            if (target !in known || encounter == null) scoped.remove("social")
            if (scoped.has("social") && encounter != null) mentalEvidenceId = encounter.evidenceId
            CharacterInnerLifeStore.observe(
                id, mentalEvidenceId,
                encounter?.description ?: "今天自己的真实处境与安排",
                scoped, encounter?.socialIds.orEmpty(), now,
            )
        }
        CharacterInnerLifeStore.recordInnerVoice(id, mentalEvidenceId, plan.optString("thought"), now)
        CharacterInnerLifeStore.recordCausalTransition(id, mentalEvidenceId,
            appraisal = null, innerLife = scopedInnerLife, innerThoughtBasis = null,
            selectedAction = "plan_day", innerThought = plan.optString("thought"),
            reason = "本人作出的当天安排；尚未执行的活动不能算亲历", now = now)
        val post = json.optString("post").trim().take(MAX_MESSAGE)
        if (post.isNotBlank()) {
            MomentsStore.initialize(app)
            ToolRouter.execute(app, id, "publish_moment", JSONObject().put("text", post),
                requestId = "$eventId-post")
        }
        val messageTo = json.optString("messageTo").trim()
        val message = json.optString("message").trim().take(MAX_MESSAGE)
        if (message.isNotBlank() && messageTo.isNotBlank()) {
            ToolRouter.execute(app, id, "send_character_message",
                JSONObject().put("targetCharacterId", messageTo).put("text", message),
                requestId = "$eventId-message")
        }
        executeWakeSteps(app, id, json, "native-daily-${native.id}-${date(now)}")
        check(CharacterPhoneInbox.acknowledgeObserved(id, unread)) {
            "已感知的手机未读动态尚未记录"
        }
        pending.lastOrNull()?.let { check(ResidentSocialMailbox.acknowledgeIncoming(id, it.id)) {
            "居民来信处理进度没有保存"
        } }
        return true
    }

    suspend fun tick(context: Context, now: Instant = Instant.now()): Int {
        val app = context.applicationContext
        // WorkManager can start a cold process without MigrationActivity.
        // Restore the same repositories and model archive before any claims.
        initializeBackgroundRuntime(app)
        appContext = app
        ResidentDailyRoutine.refresh(app, now)
        ResidentSocialMailbox.initialize(app)
        MomentsStore.initialize(app)
        CharacterInnerLifeStore.initialize(app)
        ScopedModelSelections.initialize(app)
        // Every registered resident gets a chance; the old .take(24) left later
        // residents permanently unscheduled. Bound concurrent remote requests instead.
        val semaphore = Semaphore(3)
        val succeeded = coroutineScope {
            DigitalWorldExpansionStore.residents().map { native ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            runOnce(app, native, now)
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (error: Exception) {
                            com.jiacimu.lulu.LuluRepositories.performance.recordError(
                                source = "数字世界居民日程",
                                title = "${native.name}的每日安排未完成",
                                message = error.message.orEmpty().take(180))
                            false
                        }
                    }
                }
            }.awaitAll().count { it }
        }
        // A completed planning decision may change today's free time if it
        // wasn't already reached, without rewriting a finished duty receipt.
        ResidentDailyRoutine.refresh(app, now)
        return succeeded
    }

    fun schedule(context: Context) {
        val app = context.applicationContext
        appContext = app
        ScopedModelSelections.initialize(app)
        DigitalWorldExpansionStore.initialize(app)
        val manager = WorkManager.getInstance(app)
        // Migrate away from a rolling 24-hour period: local 08:00 is a stable calendar time.
        manager.cancelUniqueWork(WORK_PERIODIC)
        manager.cancelUniqueWork(WORK_NOW)
        val now = Instant.now()
        val next = nextPlanningTime(now)
        val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<ResidentDailyDecisionWorker>()
            .setConstraints(network)
            .setInitialDelay(Duration.between(now, next).toMillis().coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
            .build()
        manager.enqueueUniqueWork("${WORK_FIXED}-${date(next)}", ExistingWorkPolicy.KEEP, request)
        // If the app first opens after planning time, seed today's missing agendas once.
        // Claim() still owns each citizen's paid daily opportunity.
        if (now.atZone(ZoneId.systemDefault()).toLocalTime() >= DAILY_PLANNING_TIME &&
            runCatching { ScopedModelSelections.selectedArchiveId(SCOPE) }.getOrNull() != null &&
            DigitalWorldExpansionStore.residents().any {
                prefs(app).getString("attempt:${it.id}", "") != date(now)
            }) {
            manager.enqueueUniqueWork("${WORK_FIXED}-catchup-${date(now)}",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ResidentDailyDecisionWorker>()
                    .setConstraints(network).build())
        }
    }
}

internal class ResidentDailyDecisionWorker(
    appContext: Context, params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        withContext(Dispatchers.IO) { ResidentDailyDecision.tick(applicationContext) }
        ResidentDailyDecision.schedule(applicationContext)
        Result.success()
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        com.jiacimu.lulu.LuluRepositories.performance.recordError(
            source = "数字世界居民日程", title = "原住民每日计划未执行完毕",
            message = error.message.orEmpty().take(180))
        Result.failure()
    }
}
