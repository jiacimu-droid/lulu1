package com.jiacimu.lulu.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
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
    private const val MAX_RESIDENTS_PER_DAY = 24
    private const val MAX_MESSAGE = 500
    @Volatile private var appContext: Context? = null

    fun planForApp(residentId: String, at: Instant): JSONObject? =
        appContext?.let { planFor(it, residentId, at) }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun date(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        at.atZone(zone).toLocalDate().toString()

    fun planFor(context: Context, residentId: String, at: Instant): JSONObject? = runCatching {
        val root = JSONObject(prefs(context).getString("plan:$residentId", "{}").orEmpty())
        root.takeIf { it.optString("date") == date(at) }
    }.getOrNull()

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

    private fun recentOwnExperience(characterId: String): String =
        SharedExperienceTimeline.recentEvents(characterId, 16)
            .asSequence()
            .filter { it.source in setOf("resident-duty", "meeting", "character-message",
                "owner-on-behalf", "digital-world", "moment:self", "resident-daily") }
            .toList().takeLast(9).joinToString("\n") {
                "${it.occurredAt} | ${it.channel} | ${it.speaker}：${it.content.take(180)}"
            }

    private fun context(native: DigitalWorldResident, at: Instant): String {
        val characterId = ResidentCharacterBridge.characterId(native.id)
        val body = EmbodiedBodyRuntime.context(characterId).take(750)
        val contacts = ResidentSocialMailbox.visibleContacts(characterId).map { target ->
            "$target=${MigratedDomainStores.characters.get(target).displayName}"
        }
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
            "$speaker: ${it.content.take(220)}"
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
            数字世界社交网络公开动态（不代表本人已经与作者见面）：
            ${publicMoments.ifBlank { "暂无" }}
            只属于你本人的最近通信：
            ${mail.ifBlank { "无" }}
            自己确实经历过的事情：
            ${recentOwnExperience(characterId).ifBlank { "暂未积累" }}
            自己对熟人的真实个人印象（对方未必这么想）：
            ${relations.ifBlank { "暂无" }}
            自己的身体：
            $body
            ${DigitalWorldPublicBulletin.publicContext(at).ifBlank { "今天没有新的公共活动公告" }}
            这是有限世界信息，不是整个数字世界的直播。没有列在此处的隐私、谈话、住户和事件不能猜成亲眼所见。
        """.trimIndent().take(5_000)
    }

    /** Called by the scheduled worker; each citizen chooses using exactly one
     * invocation, from the shared inexpensive model archive selected in settings. */
    suspend fun runOnce(context: Context, native: DigitalWorldResident,
        now: Instant = Instant.now()): Boolean {
        val app = context.applicationContext
        ResidentSocialMailbox.initialize(app)
        val id = ResidentCharacterBridge.characterId(native.id)
        val selected = runCatching {
            ScopedModelSelections.initialize(app)
            ScopedModelSelections.resolveConnection(SCOPE)
        }.getOrNull() ?: return false
        if (!claim(app, native.id, now)) return false
        val reply = LuluAiServices.gateway.generate(
            characterId = id,
            facts = context(native, now),
            instruction = """
                你是生活在这个世界中的${native.name}。只根据自己的现状、个人性格、兴趣和已知交往自主安排今天。
                保留已有工作职责，自己选择午休和晚间的活动，允许无聊、独处、想玩、约人或突然有点小心思。
                仅输出 JSON，不能替别人发言：
                {"lunch":"cafe|home|courtyard|games",
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
            contextMode = CompanionContextMode.PersonaAndScenario,
            maxTokens = 650,
        ).getOrNull()
        val json = reply?.text?.let(ModelStructuredOutput::objectOrNull) ?: return false
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
        val eventId = "native-daily-${native.id}-${date(now)}"
        SharedExperienceTimeline.record(
            eventId = eventId, characterId = id, channel = "原住民·每日自我安排",
            speaker = native.name, source = "resident-daily", occurredAt = now,
            evidenceKind = EventEvidenceKind.CharacterStatement, triggerExtraction = false,
            content = "今天想法：${plan.optString("thought")}；心情：${plan.optString("mood")}；午休=$lunch；晚间=$evening；晚间想做：${plan.optString("eveningActivity")}。这是计划，尚未执行。",
        )
        // The same private thought and social perspective channel used by
        // protagonists. An unsupported stranger's bond is never invented.
        val known = ResidentSocialMailbox.visibleContacts(id).toSet()
        json.optJSONObject("innerLife")?.let { raw ->
            val scoped = JSONObject(raw.toString())
            val target = scoped.optJSONObject("social")?.optString("targetId").orEmpty()
            if (target !in known) scoped.remove("social")
            runCatching {
                CharacterInnerLifeStore.observe(
                    id, eventId, "今天自己的真实处境与已收到的私人消息",
                    scoped, known, now,
                )
            }
        }
        val post = json.optString("post").trim().take(MAX_MESSAGE)
        if (post.isNotBlank()) runCatching {
            MomentsStore.initialize(app)
            MomentsStore.publishCharacter(id, post)
        }
        val messageTo = json.optString("messageTo").trim()
        val message = json.optString("message").trim().take(MAX_MESSAGE)
        if (message.isNotBlank() && messageTo.isNotBlank() &&
            messageTo in ResidentSocialMailbox.visibleContacts(id)) {
            runCatching { ResidentSocialMailbox.send(id, messageTo, message) }
        }
        return true
    }

    suspend fun tick(context: Context, now: Instant = Instant.now()): Int {
        val app = context.applicationContext
        appContext = app
        ResidentDailyRoutine.refresh(app, now)
        ResidentSocialMailbox.initialize(app)
        MomentsStore.initialize(app)
        CharacterInnerLifeStore.initialize(app)
        ScopedModelSelections.initialize(app)
        var succeeded = 0
        for (native in DigitalWorldExpansionStore.residents().take(MAX_RESIDENTS_PER_DAY)) {
            if (runOnce(app, native, now)) succeeded++
        }
        // A completed planning decision may change today's free time if it
        // wasn't already reached, without rewriting a finished duty receipt.
        ResidentDailyRoutine.refresh(app, now)
        return succeeded
    }

    fun schedule(context: Context) {
        val app = context.applicationContext
        appContext = app
        val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val periodic = PeriodicWorkRequestBuilder<ResidentDailyDecisionWorker>(24, TimeUnit.HOURS)
            .setConstraints(network).build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
        val once = OneTimeWorkRequestBuilder<ResidentDailyDecisionWorker>()
            .setConstraints(network).build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, once)
    }
}

internal class ResidentDailyDecisionWorker(
    appContext: Context, params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        withContext(Dispatchers.IO) { ResidentDailyDecision.tick(applicationContext) }
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
