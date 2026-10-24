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
import kotlinx.coroutines.withContext
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
    private const val MAX_RESIDENTS_PER_DAY = 24
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

    /** Model plans are proposed intentions, not actions. Registry and social facts own validity. */
    internal fun saveAgenda(context: Context, native: DigitalWorldResident, proposal: JSONObject,
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
            val params = row.optJSONObject("activityArgs")?.let { JSONObject(it.toString()) } ?: JSONObject()
            // Never accept execution metadata, unverified destinations or arbitrary contacts from AI.
            params.remove("planId")
            val target = row.optString("targetCharacterId")
                .ifBlank { params.optString("targetCharacterId") }.trim()
            if (action == "send_character_message" &&
                (target !in known || target == actor)) continue
            if (action == "send_group_message") {
                val group = params.optString("groupId")
                if (group.isBlank() || MigratedDomainStores.chat.conversations.value.none {
                    it.id == group && it.groupChat?.members?.contains(actor) == true
                }) continue
            }
            val location = row.optString("locationCode").ifBlank {
                params.optString("locationCode").ifBlank { params.optString("location") }
            }.takeIf { requested -> DigitalWorldPublicPlaces.all.any { it.code == requested } }.orEmpty()
            val worldAction = params.optString("worldAction")
            if (action == "digital_world_action" && (worldAction !in setOf(
                "visit_public_place", "interact_resident", "visit_resident_home",
                "use_location", "go_home"))) continue
            if (action == "digital_world_action" && worldAction == "visit_public_place" &&
                location.isBlank()) continue
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

    /** Each due item receives ONE fresh, cheap role decision. A recorded action is not an effect. */
    suspend fun runDuePlan(context: Context, plan: RoleDayPlan, at: Instant = Instant.now()): Boolean {
        val app = context.applicationContext
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
        val proposedArgs = runCatching { JSONObject(plan.activityArgs) }.getOrDefault(JSONObject())
        val now = Instant.now()
        val response = LuluAiServices.gateway.generate(
            characterId = plan.characterId,
            facts = decisionFacts(native, now) + "\n" +
                "【确实已到期的个人日程】planId=${plan.id}；原想法=${plan.title}；动机=${plan.intent}；" +
                "时间=${plan.dueAt}；预选工具=${plan.activityAction}；预选参数=${proposedArgs}。" +
                "这是当初的计划，并不是已执行。原定见面对象/联系人可能已经离开或不再可联系，请重新查证。",
            instruction = """
                你是${native.name}。你本人预先安排的一项真实生活活动已经到点。
                只根据此刻真实世界状态和本人意愿，自由选择执行、改期或取消。不是为了打卡。
                如果执行，只能选原定合法工具=${plan.activityAction}；从预选参数和当前事实给出真正可执行的 args。
                私信必须是真实认识的人，面对面互动必须实际同场；对方没有回答不算聊过，去过地点不等于已经见面。
                行动后由工具留下独立真实回执，禁止自行宣告结果或替另一人说话。
                只输出 JSON，例如：
                {"choice":"execute|reschedule|cancel|wait","args":{},"dueAt":"改期时新的带偏移ISO8601时间","reason":"简短真实理由"}
                "wait" 只是保留尚未完成的日程，并不宣称执行成功。
            """.trimIndent(),
            source = "原住民个人日程到点", title = "${native.name} · ${plan.title.take(48)}",
            connectionOverride = selected, contextMode = CompanionContextMode.CharacterAndScenario,
            maxTokens = 700,
        ).getOrNull() ?: return false
        val choice = response.text.let(ModelStructuredOutput::objectOrNull) ?: return false
        return when (choice.optString("choice")) {
            "execute" -> {
                val args = choice.optJSONObject("args")?.let { JSONObject(it.toString()) } ?: JSONObject()
                args.remove("_replyEvidenceIds")
                args.put("planId", plan.id)
                // Validate against the SAME existing plan authority before the real tool.
                if (!RoleDayScheduleStore.canBegin(app, plan.characterId, plan.id,
                        plan.activityAction, args, Instant.now())) return false
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
            else -> false
        }
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

    private fun recentOwnEvents(characterId: String): List<SharedTimelineEvent> =
        SharedExperienceTimeline.recentEvents(characterId, 16)
            .asSequence()
            .filter { it.source in setOf("resident-duty", "meeting", "character-message",
                "owner-on-behalf", "digital-world", "moment:self", "resident-daily") }
            .toList().takeLast(9)

    private fun recentOwnExperience(characterId: String): String =
        recentOwnEvents(characterId).joinToString("\n") {
                "${it.occurredAt} | ${it.channel} | ${it.speaker}：${it.content.take(180)}"
            }

    private fun context(native: DigitalWorldResident, at: Instant,
        pending: List<ResidentDirectMessage>): String {
        val characterId = ResidentCharacterBridge.characterId(native.id)
        val body = EmbodiedBodyRuntime.context(characterId).take(750)
        val contacts = ResidentSocialMailbox.visibleContacts(characterId).map { target ->
            "$target=${MigratedDomainStores.characters.get(target).displayName}"
        }
        val groups = MigratedDomainStores.chat.conversations.value.filter {
            it.groupChat?.members?.contains(characterId) == true
        }.take(6).joinToString("、") { "${it.id}=${it.title}" }
        val relations = CharacterSocialRelationship.perspectivesFor(characterId,
            ResidentSocialMailbox.visibleContacts(characterId))
        val publicMoments = MomentsStore.posts.value.asSequence()
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
            数字世界社交网络公开动态（不代表本人已经与作者见面）：
            ${publicMoments.ifBlank { "暂无" }}
            只属于你本人的最近通信：
            ${mail.ifBlank { "无" }}
            ${CharacterEverydaySocialContext.render(characterId, at, pending)}
            自己确实经历过的事情：
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
            ResidentCharacterBridge.characterId(native.id))): String {
        val id = ResidentCharacterBridge.characterId(native.id)
        val facts = context(native, at, pending)
        val memory = CharacterRuntime.actionMemory(id, UnifiedMemoryRequest(
            currentInput = pending.joinToString("\n") { it.content },
            sceneContext = "原住民自己的日常生活与熟人来往",
            recentContext = facts,
            taskIntent = "根据本人经历、心情与未尽念头安排今天，不补造他人经历",
        ))
        return listOf(facts,
            RoleDayScheduleStore.plannedContext(appContext ?: return facts, id, at),
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
        ResidentCharacterBridge.sync(app, now)
        ResidentSocialMailbox.initialize(app)
        CharacterInnerLifeStore.initialize(app)
        val id = ResidentCharacterBridge.characterId(native.id)
        val selected = runCatching {
            ScopedModelSelections.initialize(app)
            ScopedModelSelections.resolveConnection(SCOPE)
        }.getOrNull() ?: return false
        if (!claim(app, native.id, now)) return false
        val pending = ResidentSocialMailbox.pendingIncoming(id)
        val reply = LuluAiServices.gateway.generate(
            characterId = id,
            facts = decisionFacts(native, now, pending),
            instruction = """
                你是生活在这个世界中的${native.name}。只根据自己的现状、个人性格、兴趣和已知交往自主安排今天。
                保留已有工作职责，自己选择午休和晚间的活动，允许无聊、独处、想玩、约人或突然有点小心思。
                更重要的是：你活在有人际交往的真实小世界里。想见熟人、与另一位居民聊共同话题、约朋友玩、回复真实来信、参与群聊或结识新朋友，都可以成为具体行程；不是每天只有喝水和独处。
                你可以决定今天没有行程，也可根据生活需要自然安排多项。每项独立到点醒来、再判断是否执行；不要为了填满日程编造友谊或假设对方一定赴约。
                agenda 中每项需包含 at=今天当地HH:mm未来时刻、title=短活动、intent=本人的真实动机、activityAction、activityArgs。
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
                 "innerLife":{"emotion":{"feeling":"确实发生变化时的情绪","cause":"本人真实经历的原因"},"social":{"targetId":"确实认识的对象ID","interpretation":"本人对其个人看法","reason":"双方实际来往的依据","dimensions":{"trust":"up|down|same","warmth":"up|down|same","ease":"up|down|same","friction":"up|down|same","boundarySafety":"up|down|same"}}}}}
                不能为了活跃指标强制发帖/发消息；不能凭空接触不在身边的人或替别人决定是否喜欢你。
                发布只是你的选择，不代表对方一定读过；关系随各自真实来往慢慢改变，不能宣告双方已相爱。
                用户只允许数字世界内社交与行动，禁止现实设备感知、联网、购买和自主联系现实用户。
            """.trimIndent(),
            source = "数字世界原住民每日规划",
            title = "${native.name}的一天",
            connectionOverride = selected,
            contextMode = CompanionContextMode.CharacterAndScenario,
            maxTokens = 1_500,
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
        var succeeded = 0
        for (native in DigitalWorldExpansionStore.residents().take(MAX_RESIDENTS_PER_DAY)) {
            try {
                if (runOnce(app, native, now)) succeeded++
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                com.jiacimu.lulu.LuluRepositories.performance.recordError(
                    source = "数字世界居民日程", title = "一位居民的每日安排未完成",
                    message = error.message.orEmpty().take(180))
            }
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
