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
import com.jiacimu.lulu.system.LuluLocationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit

internal enum class RealityTopicState { DISCOVERED, FOLLOWING, DROPPED }
internal enum class RealityExposureState { GLIMPSED, READ }
internal enum class RealitySourceTrust { STRUCTURED_SOURCE, AGGREGATED_REPORT }

internal data class RealityTopic(
    val id: String,
    val label: String,
    val query: String,
    val state: RealityTopicState,
    val discoveredAt: Instant,
    val followedAt: Instant? = null,
)

internal data class RealityWindowEvent(
    val id: String,
    val topicId: String,
    val topicLabel: String,
    val title: String,
    val summary: String,
    val sourceName: String,
    val sourceUrl: String,
    val publishedAt: Instant,
    val fetchedAt: Instant,
    val trust: RealitySourceTrust,
    val criticalForUser: Boolean = false,
    val distanceKm: Int? = null,
)

internal data class RealityRefreshResult(
    val criticalEvents: List<RealityWindowEvent> = emptyList(),
)

/** Program-owned, multi-source real-world information window for digital residents. */
internal object RealityWorldWindowRuntime {
    private const val PREFS_NAME = "lulu_reality_world_window_v1"
    private const val KEY_EVENTS = "events_v1"
    private const val MAX_EVENTS = 520
    private const val PERIODIC_WORK = "lulu-reality-window-periodic"
    private const val IMMEDIATE_WORK = "lulu-reality-window-immediate"
    private const val TOPIC_WORLD = "system-world"
    private const val TOPIC_WEATHER = "system-weather"
    private const val TOPIC_EARTHQUAKE = "system-earthquake"

    private val lock = Any()
    private var prefs: android.content.SharedPreferences? = null
    private var appContext: Context? = null
    private var events: List<RealityWindowEvent> = emptyList()

    private val systemTopics = mapOf(
        TOPIC_WORLD to RealityTopic(TOPIC_WORLD, "世界大事", "", RealityTopicState.DISCOVERED, Instant.EPOCH),
        TOPIC_WEATHER to RealityTopic(TOPIC_WEATHER, "用户现实所在地天气", "", RealityTopicState.DISCOVERED, Instant.EPOCH),
        TOPIC_EARTHQUAKE to RealityTopic(TOPIC_EARTHQUAKE, "地震与现实安全", "", RealityTopicState.DISCOVERED, Instant.EPOCH),
    )

    @Synchronized
    fun initialize(context: Context) {
        val application = context.applicationContext
        appContext = application
        if (prefs == null) {
            prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            events = decodeEvents(prefs?.getString(KEY_EVENTS, null))
        }
        val request = PeriodicWorkRequestBuilder<RealityWindowWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(application).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun contextFor(characterId: String, now: Instant = Instant.now()): String {
        if (characterId.isBlank()) return ""
        val topics = topicsFor(characterId)
        val followed = topics.filter { it.state == RealityTopicState.FOLLOWING }
        val discovered = topics.filter { it.state == RealityTopicState.DISCOVERED }
        val visibleTopicIds = topics.filter { it.state != RealityTopicState.DROPPED }
            .mapTo(mutableSetOf()) { it.id }
            .apply { add(TOPIC_WORLD); add(TOPIC_WEATHER); add(TOPIC_EARTHQUAKE) }
        val exposure = exposureFor(characterId)

        val fresh = synchronized(lock) {
            events.asSequence()
                .filter { it.topicId in visibleTopicIds }
                .filter { Duration.between(it.publishedAt, now).abs() <= Duration.ofDays(7) }
                .sortedWith(
                    compareByDescending<RealityWindowEvent> { event ->
                        when {
                            event.criticalForUser -> 120
                            followed.any { it.id == event.topicId } -> 80
                            discovered.any { it.id == event.topicId } -> 62
                            event.topicId == TOPIC_WEATHER -> 58
                            event.topicId == TOPIC_EARTHQUAKE -> 54
                            else -> 35
                        } + when (exposure[event.id]?.first) {
                            RealityExposureState.READ -> -24
                            RealityExposureState.GLIMPSED -> -4
                            null -> 10
                        }
                    }.thenByDescending(RealityWindowEvent::publishedAt),
                )
                .distinctBy { normalizeTitle(it.title) }
                .take(10)
                .toList()
        }
        if (fresh.isNotEmpty()) markGlimpsed(characterId, fresh.map { it.id }, now)
        val updatedExposure = exposureFor(characterId)
        val recentlyRead = synchronized(lock) {
            events.asSequence()
                .filter { updatedExposure[it.id]?.first == RealityExposureState.READ }
                .sortedByDescending { updatedExposure[it.id]?.second ?: Instant.EPOCH }
                .take(5)
                .toList()
        }

        return buildString {
            appendLine("【现实世界窗口｜多来源、角色自主关注】")
            appendLine("- 这是数字生命通往用户现实世界的程序信息层，不是手机通知。资讯来自网络来源并保存为带来源的外部记录；角色不能凭常识或想象创造新闻。")
            appendLine("- 当前来源可包括：Google News RSS / GDELT 新闻索引 / Bangumi / AniList / Steam News / Open-Meteo / USGS。不同圈子走不同来源，Google News 只是兜底。")
            appendLine("- 圈子归角色本人：用户不替角色分配兴趣。角色可依据人设、记忆、好奇心、朋友分享和既有兴趣，自主探索、关注、退订，也可以长期不看某类内容。")
            appendLine("- 标题被看到不等于正文已读。只有 reality_open 成功后，来源摘要才成为该角色真正读过的信息。数据库条目只代表知道作品存在，不代表已经看过/玩过。")
            appendLine("- 外部报道证明的是‘某来源这样报道/记录’，不是角色或用户亲历。天气是模型数据；地震距离由设备本地计算。")
            appendLine("公共入口：topicId=$TOPIC_WORLD=世界大事；topicId=$TOPIC_WEATHER=现实所在地天气；topicId=$TOPIC_EARTHQUAKE=地震与安全。公共入口不等于角色长期关注。")
            if (followed.isNotEmpty()) {
                appendLine("角色自己正在关注的圈子：")
                followed.take(12).forEach { topic ->
                    val hints = providerHints(topic.id)
                    append("- topicId=${topic.id}；${topic.label}")
                    if (hints.isNotEmpty()) append("；已发现对口来源=${hints.joinToString("/")}")
                    appendLine()
                }
            } else appendLine("角色自己正在关注的圈子：暂无。")
            if (discovered.isNotEmpty()) {
                appendLine("角色最近自己探索过、还没决定长期关注的圈子：")
                discovered.take(10).forEach { appendLine("- topicId=${it.id}；${it.label}") }
            }
            if (fresh.isNotEmpty()) {
                appendLine("窗口当前可见的来源条目（本轮只证明扫到了标题；未必点开）：")
                fresh.forEach { event ->
                    val marker = if (updatedExposure[event.id]?.first == RealityExposureState.READ) "已读" else "仅标题"
                    val distance = event.distanceKm?.let { "；距用户设备最近位置约${it}km" }.orEmpty()
                    appendLine("- eventId=${event.id}；topicId=${event.topicId}；$marker；来源=${event.sourceName}；时间=${event.publishedAt}；标题=${event.title}$distance")
                }
            } else appendLine("窗口暂时没有成功抓取到的新条目；这只代表当前来源没有可用结果，不代表现实世界没有相关信息。")
            if (recentlyRead.isNotEmpty()) {
                appendLine("角色最近真正点开过的资讯/条目摘要：")
                recentlyRead.forEach { appendLine("- [${it.sourceName}] ${it.title}：${it.summary.take(480)}") }
            }
            appendLine("可执行现实窗口动作（通过 digital_world + worldAction=use_location + activityId；不受当前数字地点限制）：")
            appendLine("- reality_explore:<关键词或圈子>：角色自主探索，例如‘恋爱动画’‘冰之城墙’‘成都’‘某个游戏’‘某位明星’，程序会尝试通用新闻与对口专业源。")
            appendLine("- reality_follow:<topicId>：关注真实存在的 topicId。")
            appendLine("- reality_unfollow:<topicId>：退订角色当前真实关注/探索过的 topicId。")
            appendLine("- reality_open:<eventId>：点开真实存在的 eventId，程序才返回并记录来源摘要。")
        }.trim()
    }

    fun executeActivity(characterId: String, activityId: String, now: Instant = Instant.now()): String {
        checkNotNull(prefs) { "现实世界窗口尚未初始化" }
        val character = MigratedDomainStores.characters.get(characterId)
        val raw = activityId.trim()
        val summary = when {
            raw.startsWith("reality_explore:") -> {
                val query = cleanQuery(raw.substringAfter(':'))
                require(query.isNotBlank()) { "探索现实圈子需要一个明确关键词" }
                val topic = ensureDiscoveredTopic(characterId, query, now)
                enqueueImmediateRefresh()
                "${character.displayName}自己决定探索现实圈子“${topic.label}”；程序会尝试新闻索引以及可能匹配的动漫/漫画/游戏专业来源。当前只是开始探索，不等于已经关注或读过任何结果。"
            }
            raw.startsWith("reality_follow:") -> {
                val topicId = raw.substringAfter(':').trim()
                val topic = resolveTopic(characterId, topicId) ?: error("没有找到这个现实圈子")
                require(topicId !in systemTopics.keys) { "公共现实入口不需要关注；角色可直接浏览其中的真实条目" }
                val saved = topic.copy(
                    state = RealityTopicState.FOLLOWING,
                    followedAt = topic.followedAt ?: now,
                    discoveredAt = if (topic.discoveredAt == Instant.EPOCH) now else topic.discoveredAt,
                )
                upsertTopic(characterId, saved)
                enqueueImmediateRefresh()
                "${character.displayName}自己决定长期关注现实圈子“${saved.label}”。"
            }
            raw.startsWith("reality_unfollow:") -> {
                val topicId = raw.substringAfter(':').trim()
                require(topicId !in systemTopics.keys) { "公共现实入口不能退订；它们只是可见入口，不代表角色兴趣" }
                val topic = resolveTopic(characterId, topicId) ?: error("没有找到这个现实圈子")
                require(topicsFor(characterId).any { it.id == topicId && it.state != RealityTopicState.DROPPED }) { "角色目前没有关注或探索这个圈子" }
                upsertTopic(characterId, topic.copy(state = RealityTopicState.DROPPED))
                "${character.displayName}自己决定不再关注现实圈子“${topic.label}”。"
            }
            raw.startsWith("reality_open:") -> {
                val eventId = raw.substringAfter(':').trim()
                val event = synchronized(lock) { events.firstOrNull { it.id == eventId } }
                    ?: error("这条现实资讯不存在或已经过期")
                require(isVisibleTo(characterId, event)) { "这条资讯不在角色当前可见的现实窗口里" }
                markRead(characterId, event.id, now)
                val trust = when (event.trust) {
                    RealitySourceTrust.STRUCTURED_SOURCE -> "结构化来源记录"
                    RealitySourceTrust.AGGREGATED_REPORT -> "新闻聚合/索引记录"
                }
                val distance = event.distanceKm?.let { "；距用户设备最近位置约${it}km" }.orEmpty()
                val content = buildString {
                    append("角色实际点开现实世界窗口中的一条来源记录。来源=${event.sourceName}；类型=$trust；来源时间=${event.publishedAt}；标题=${event.title}")
                    append(distance)
                    append("；来源摘要=${event.summary.take(1_400)}")
                    if (event.sourceUrl.isNotBlank()) append("；来源链接=${event.sourceUrl}")
                    append("。这只证明角色阅读了该来源返回的信息；不能据此声称角色或用户亲历，也不能把数据库条目写成已经观看/游玩。")
                }
                SharedExperienceTimeline.record(
                    eventId = "reality-read-${event.id}-$characterId-${now.toEpochMilli()}",
                    characterId = characterId,
                    channel = "现实世界窗口·已读",
                    speaker = character.displayName,
                    content = content,
                    occurredAt = now,
                )
                return "已查看现实来源｜${event.sourceName}｜${event.title}：${event.summary.take(650)}$distance"
            }
            else -> error("未知现实世界窗口动作")
        }
        SharedExperienceTimeline.record(
            eventId = "reality-action-${stableId(characterId, "$raw:${now.toEpochMilli()}")}",
            characterId = characterId,
            channel = when {
                raw.startsWith("reality_explore:") -> "现实世界窗口·探索"
                raw.startsWith("reality_follow:") -> "现实世界窗口·关注"
                else -> "现实世界窗口·退订"
            },
            speaker = character.displayName,
            content = summary,
            occurredAt = now,
        )
        return summary
    }

    suspend fun refresh(context: Context, now: Instant = Instant.now()): RealityRefreshResult = withContext(Dispatchers.IO) {
        initialize(context)
        val p = checkNotNull(prefs)
        val fetched = mutableListOf<RealityWindowEvent>()
        if (refreshDue("refresh:$TOPIC_WORLD", now, Duration.ofMinutes(90))) {
            runCatching { RealityWorldSourceAdapters.fetchWorldNews(TOPIC_WORLD, "世界大事", now) }.getOrNull()?.let(fetched::addAll)
            markRefresh("refresh:$TOPIC_WORLD", now)
        }
        val queryTopics = allRoleTopics()
            .filter { it.state != RealityTopicState.DROPPED && it.query.isNotBlank() }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<RealityTopic> { it.state == RealityTopicState.FOLLOWING }.thenByDescending { it.discoveredAt })
            .take(18)
        queryTopics.forEach { topic ->
            if (!refreshDue("refresh:${topic.id}", now, Duration.ofHours(2))) return@forEach
            val hints = providerHints(topic.id)
            val lastProbe = p.getLong("provider_probe:${topic.id}", 0L).takeIf { it > 0L }?.let(Instant::ofEpochMilli)
            val probeSpecialized = lastProbe == null || Duration.between(lastProbe, now).abs() >= Duration.ofHours(24)
            val batch = runCatching {
                RealityWorldSourceAdapters.fetchTopic(topic, hints, probeSpecialized, now)
            }.getOrNull()
            if (batch != null) {
                fetched += batch.events
                if (batch.providerHints != hints) saveProviderHints(topic.id, batch.providerHints)
            }
            if (probeSpecialized) p.edit().putLong("provider_probe:${topic.id}", now.toEpochMilli()).apply()
            markRefresh("refresh:${topic.id}", now)
        }
        val weatherDue = refreshDue("refresh:$TOPIC_WEATHER", now, Duration.ofMinutes(55))
        val quakeDue = refreshDue("refresh:$TOPIC_EARTHQUAKE", now, Duration.ofMinutes(25))
        val deviceLocation = if (weatherDue || quakeDue) runCatching { LuluLocationProvider.freshLocation(context.applicationContext) }.getOrNull() else null
        if (weatherDue) {
            runCatching { RealityWorldSourceAdapters.fetchWeather(TOPIC_WEATHER, "用户现实所在地天气", now, deviceLocation) }.getOrNull()?.let(fetched::addAll)
            markRefresh("refresh:$TOPIC_WEATHER", now)
        }
        if (quakeDue) {
            runCatching { RealityWorldSourceAdapters.fetchEarthquakes(TOPIC_EARTHQUAKE, "地震与现实安全", now, deviceLocation) }.getOrNull()?.let(fetched::addAll)
            markRefresh("refresh:$TOPIC_EARTHQUAKE", now)
        }
        if (fetched.isNotEmpty()) upsertEvents(fetched, now)
        RealityRefreshResult(
            criticalEvents = fetched.filter { it.criticalForUser && Duration.between(it.publishedAt, now).abs() <= Duration.ofHours(3) }.distinctBy { it.id },
        )
    }

    fun claimCriticalWake(eventId: String): Boolean {
        val p = prefs ?: return false
        synchronized(lock) {
            val key = "critical_wake:$eventId"
            if (p.getBoolean(key, false)) return false
            return p.edit().putBoolean(key, true).commit()
        }
    }

    private fun refreshDue(key: String, now: Instant, interval: Duration): Boolean {
        val last = prefs?.getLong(key, 0L)?.takeIf { it > 0L }?.let(Instant::ofEpochMilli) ?: return true
        return Duration.between(last, now).abs() >= interval
    }

    private fun markRefresh(key: String, now: Instant) { prefs?.edit()?.putLong(key, now.toEpochMilli())?.apply() }

    private fun enqueueImmediateRefresh() {
        val context = appContext ?: return
        val request = OneTimeWorkRequestBuilder<RealityWindowWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    private fun resolveTopic(characterId: String, topicId: String): RealityTopic? =
        topicsFor(characterId).firstOrNull { it.id == topicId } ?: systemTopics[topicId]

    private fun ensureDiscoveredTopic(characterId: String, query: String, now: Instant): RealityTopic {
        val id = "topic-${stableId("query", query.lowercase(Locale.ROOT))}"
        val current = topicsFor(characterId).firstOrNull { it.id == id }
        if (current != null && current.state != RealityTopicState.DROPPED) return current
        val topic = RealityTopic(id, query, query, RealityTopicState.DISCOVERED, now)
        upsertTopic(characterId, topic)
        prefs?.edit()?.remove("refresh:$id")?.apply()
        return topic
    }

    private fun upsertTopic(characterId: String, topic: RealityTopic) {
        val topics = topicsFor(characterId).filterNot { it.id == topic.id } + topic
        saveTopics(characterId, topics.sortedBy { it.discoveredAt }.takeLast(70))
    }

    private fun allRoleTopics(): List<RealityTopic> = MigratedDomainStores.characters.settings.value.keys.flatMap(::topicsFor)

    private fun topicsFor(characterId: String): List<RealityTopic> {
        val array = runCatching { JSONArray(prefs?.getString("topics:$characterId", "[]").orEmpty()) }.getOrDefault(JSONArray())
        return buildList {
            for (i in 0 until array.length()) {
                val json = array.optJSONObject(i) ?: continue
                val id = json.optString("id").trim()
                val label = json.optString("label").trim()
                if (id.isBlank() || label.isBlank()) continue
                add(
                    RealityTopic(
                        id = id,
                        label = label,
                        query = json.optString("query").trim(),
                        state = runCatching { RealityTopicState.valueOf(json.optString("state")) }.getOrDefault(RealityTopicState.DISCOVERED),
                        discoveredAt = json.optString("discoveredAt").toInstantOrNull() ?: Instant.EPOCH,
                        followedAt = json.optString("followedAt").toInstantOrNull(),
                    ),
                )
            }
        }
    }

    private fun saveTopics(characterId: String, topics: List<RealityTopic>) {
        val array = JSONArray()
        topics.forEach { topic ->
            array.put(JSONObject().put("id", topic.id).put("label", topic.label).put("query", topic.query)
                .put("state", topic.state.name).put("discoveredAt", topic.discoveredAt.toString()).put("followedAt", topic.followedAt?.toString().orEmpty()))
        }
        check(prefs?.edit()?.putString("topics:$characterId", array.toString())?.commit() == true) { "现实圈子状态保存失败" }
    }

    private fun providerHints(topicId: String): Set<String> = prefs?.getStringSet("provider_hints:$topicId", emptySet())?.toSet().orEmpty()
    private fun saveProviderHints(topicId: String, hints: Set<String>) { prefs?.edit()?.putStringSet("provider_hints:$topicId", hints.toSet())?.apply() }

    private fun exposureFor(characterId: String): Map<String, Pair<RealityExposureState, Instant>> {
        val json = runCatching { JSONObject(prefs?.getString("exposure:$characterId", "{}").orEmpty()) }.getOrDefault(JSONObject())
        return buildMap {
            json.keys().forEach { eventId ->
                val item = json.optJSONObject(eventId) ?: return@forEach
                put(
                    eventId,
                    runCatching { RealityExposureState.valueOf(item.optString("state")) }.getOrDefault(RealityExposureState.GLIMPSED) to
                        (item.optString("at").toInstantOrNull() ?: Instant.EPOCH),
                )
            }
        }
    }

    private fun markGlimpsed(characterId: String, eventIds: List<String>, now: Instant) {
        if (eventIds.isEmpty()) return
        synchronized(lock) {
            val current = JSONObject(prefs?.getString("exposure:$characterId", "{}").orEmpty())
            eventIds.forEach { eventId ->
                if (current.optJSONObject(eventId)?.optString("state") == RealityExposureState.READ.name) return@forEach
                current.put(eventId, JSONObject().put("state", RealityExposureState.GLIMPSED.name).put("at", now.toString()))
            }
            trimExposure(current)
            prefs?.edit()?.putString("exposure:$characterId", current.toString())?.apply()
        }
    }

    private fun markRead(characterId: String, eventId: String, now: Instant) {
        synchronized(lock) {
            val current = JSONObject(prefs?.getString("exposure:$characterId", "{}").orEmpty())
            current.put(eventId, JSONObject().put("state", RealityExposureState.READ.name).put("at", now.toString()))
            trimExposure(current)
            check(prefs?.edit()?.putString("exposure:$characterId", current.toString())?.commit() == true) { "资讯阅读状态保存失败" }
        }
    }

    private fun trimExposure(json: JSONObject) {
        if (json.length() <= 650) return
        val keep = json.keys().asSequence().mapNotNull { id ->
            val item = json.optJSONObject(id) ?: return@mapNotNull null
            id to (item.optString("at").toInstantOrNull() ?: Instant.EPOCH)
        }.sortedByDescending { it.second }.take(520).map { it.first }.toSet()
        json.keys().asSequence().toList().filterNot { it in keep }.forEach(json::remove)
    }

    private fun isVisibleTo(characterId: String, event: RealityWindowEvent): Boolean =
        event.topicId in systemTopics.keys || topicsFor(characterId).any { it.id == event.topicId && it.state != RealityTopicState.DROPPED }

    private fun upsertEvents(incoming: List<RealityWindowEvent>, now: Instant) {
        synchronized(lock) {
            val merged = (events + incoming).associateBy(RealityWindowEvent::id).values
                .filter { Duration.between(it.publishedAt, now).abs() <= Duration.ofDays(10) }
                .sortedBy(RealityWindowEvent::publishedAt).takeLast(MAX_EVENTS)
            events = merged
            val encoded = JSONArray()
            merged.forEach { event ->
                encoded.put(JSONObject().put("id", event.id).put("topicId", event.topicId).put("topicLabel", event.topicLabel)
                    .put("title", event.title).put("summary", event.summary).put("sourceName", event.sourceName)
                    .put("sourceUrl", event.sourceUrl).put("publishedAt", event.publishedAt.toString()).put("fetchedAt", event.fetchedAt.toString())
                    .put("trust", event.trust.name).put("criticalForUser", event.criticalForUser).put("distanceKm", event.distanceKm ?: JSONObject.NULL))
            }
            check(prefs?.edit()?.putString(KEY_EVENTS, encoded.toString())?.commit() == true) { "现实资讯保存失败" }
        }
    }

    private fun decodeEvents(raw: String?): List<RealityWindowEvent> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val json = array.optJSONObject(i) ?: continue
                val id = json.optString("id").trim()
                val title = json.optString("title").trim()
                if (id.isBlank() || title.isBlank()) continue
                add(
                    RealityWindowEvent(
                        id = id,
                        topicId = json.optString("topicId"),
                        topicLabel = json.optString("topicLabel"),
                        title = title,
                        summary = json.optString("summary"),
                        sourceName = json.optString("sourceName").ifBlank { "外部来源" },
                        sourceUrl = json.optString("sourceUrl"),
                        publishedAt = json.optString("publishedAt").toInstantOrNull() ?: Instant.EPOCH,
                        fetchedAt = json.optString("fetchedAt").toInstantOrNull() ?: Instant.EPOCH,
                        trust = runCatching { RealitySourceTrust.valueOf(json.optString("trust")) }.getOrDefault(RealitySourceTrust.AGGREGATED_REPORT),
                        criticalForUser = json.optBoolean("criticalForUser", false),
                        distanceKm = if (json.isNull("distanceKm")) null else json.optInt("distanceKm"),
                    ),
                )
            }
        }
    }

    private fun cleanQuery(value: String): String = value
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(80)

    private fun normalizeTitle(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").replace(Regex("\\s+"), " ").trim()

    private fun stableId(namespace: String, value: String): String {
        val seed = "$namespace::$value"
        return seed.hashCode().toUInt().toString(16) + seed.reversed().hashCode().toUInt().toString(16)
    }

    private fun String.toInstantOrNull(): Instant? = takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() }
}

class RealityWindowWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        val now = Instant.now()
        val refresh = RealityWorldWindowRuntime.refresh(applicationContext, now)
        val critical = refresh.criticalEvents.sortedByDescending(RealityWindowEvent::publishedAt)
            .firstOrNull { RealityWorldWindowRuntime.claimCriticalWake(it.id) }
        if (critical != null) {
            ProactivePerceptionRuntime.runDueCycle(
                context = applicationContext,
                trigger = "现实世界安全窗口出现高相关事件：${critical.title}；来源=${critical.sourceName}。角色只获得这一真实来源信息，是否联系用户由角色自己决定。",
                force = true,
                now = now,
            )
        }
        Result.success()
    }.getOrElse { Result.retry() }
}
