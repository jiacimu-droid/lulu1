package com.jiacimu.lulu.data

import android.content.Context
import android.location.Location
import android.util.Xml
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
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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

/**
 * The digital residents' window into the user's real world.
 *
 * External data is program-owned. A role may choose what to explore/follow/open, but model prose
 * can never create a RealityWindowEvent. Headlines are only a glance at a source; opening an item
 * records the supplied source summary as "the role saw this report", never as the role or user
 * personally experiencing the reported event.
 */
internal object RealityWorldWindowRuntime {
    private const val PREFS_NAME = "lulu_reality_world_window_v1"
    private const val KEY_EVENTS = "events_v1"
    private const val MAX_EVENTS = 320
    private const val PERIODIC_WORK = "lulu-reality-window-periodic"
    private const val IMMEDIATE_WORK = "lulu-reality-window-immediate"
    private const val TOPIC_WORLD = "system-world"
    private const val TOPIC_EARTHQUAKE = "system-earthquake"
    private const val GOOGLE_TOP =
        "https://news.google.com/rss?hl=zh-CN&gl=CN&ceid=CN:zh-Hans"
    private const val GOOGLE_SEARCH =
        "https://news.google.com/rss/search?q=%s&hl=zh-CN&gl=CN&ceid=CN:zh-Hans"
    private const val USGS_45_DAY =
        "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/4.5_day.geojson"

    private val lock = Any()
    private var prefs: android.content.SharedPreferences? = null
    private var appContext: Context? = null
    private var events: List<RealityWindowEvent> = emptyList()

    private val worldTopic = RealityTopic(
        id = TOPIC_WORLD,
        label = "世界大事",
        query = "",
        state = RealityTopicState.DISCOVERED,
        discoveredAt = Instant.EPOCH,
    )
    private val earthquakeTopic = RealityTopic(
        id = TOPIC_EARTHQUAKE,
        label = "地震与现实安全",
        query = "",
        state = RealityTopicState.DISCOVERED,
        discoveredAt = Instant.EPOCH,
    )

    @Synchronized
    fun initialize(context: Context) {
        val application = context.applicationContext
        appContext = application
        if (prefs == null) {
            prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            events = decodeEvents(prefs?.getString(KEY_EVENTS, null))
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<RealityWindowWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
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
        val topicIds = topics.filter { it.state != RealityTopicState.DROPPED }.mapTo(mutableSetOf()) { it.id }
        topicIds += TOPIC_WORLD
        topicIds += TOPIC_EARTHQUAKE
        val exposure = exposureFor(characterId)

        val fresh = synchronized(lock) {
            events.asSequence()
                .filter { it.topicId in topicIds }
                .filter { Duration.between(it.publishedAt, now).abs() <= Duration.ofDays(7) }
                .sortedWith(
                    compareByDescending<RealityWindowEvent> { event ->
                        when {
                            event.criticalForUser -> 100
                            followed.any { it.id == event.topicId } -> 70
                            discovered.any { it.id == event.topicId } -> 55
                            event.topicId == TOPIC_EARTHQUAKE -> 45
                            else -> 30
                        } + if (exposure[event.id]?.first == RealityExposureState.READ) -18 else 8
                    }.thenByDescending(RealityWindowEvent::publishedAt),
                )
                .take(8)
                .toList()
        }
        if (fresh.isNotEmpty()) markGlimpsed(characterId, fresh.map { it.id }, now)
        val updatedExposure = exposureFor(characterId)

        val recentlyRead = synchronized(lock) {
            events.asSequence()
                .filter { updatedExposure[it.id]?.first == RealityExposureState.READ }
                .sortedByDescending { updatedExposure[it.id]?.second ?: Instant.EPOCH }
                .take(4)
                .toList()
        }

        return buildString {
            appendLine("【现实世界窗口｜外部来源与角色自主关注】")
            appendLine("- 这里是数字生命通往用户现实世界的信息窗口。外部资讯由程序抓取并保存；角色不能凭常识或想象创建新闻。")
            appendLine("- 圈子归角色本人：用户没有替角色分配兴趣。角色可按自己的人设、记忆、好奇心和已经接触过的东西自主探索、关注、退订，也可以长期不看某类内容。")
            appendLine("- 首页标题只代表角色扫到了来源标题；只有 reality_open 真正点开后，来源摘要才成为角色已读信息。即使已读，也应说“我看到某来源报道/提到……”；不能把报道写成自己或用户亲历。")
            appendLine("- 公共入口：topicId=$TOPIC_WORLD=世界大事；topicId=$TOPIC_EARTHQUAKE=地震与现实安全。公共入口不等于强制关注。")
            if (followed.isNotEmpty()) {
                appendLine("角色自己正在关注的圈子：")
                followed.forEach { appendLine("- topicId=${it.id}；${it.label}") }
            } else {
                appendLine("角色自己正在关注的圈子：暂无。")
            }
            if (discovered.isNotEmpty()) {
                appendLine("最近自己探索过、但还没决定长期关注的圈子：")
                discovered.take(8).forEach { appendLine("- topicId=${it.id}；${it.label}") }
            }
            if (fresh.isNotEmpty()) {
                appendLine("窗口首页现在可见的真实来源标题（角色本轮已扫到标题，未必点开）：")
                fresh.forEach { event ->
                    val state = updatedExposure[event.id]?.first
                    val marker = if (state == RealityExposureState.READ) "已读" else "仅标题"
                    val distance = event.distanceKm?.let { "；距用户设备最近位置约${it}km" }.orEmpty()
                    appendLine("- eventId=${event.id}；topicId=${event.topicId}；$marker；来源=${event.sourceName}；发布时间=${event.publishedAt}；标题=${event.title}$distance")
                }
            } else {
                appendLine("窗口首页暂时没有已抓取到的新标题；这不代表现实世界没有新闻，只代表当前信息源还没有可用结果。")
            }
            if (recentlyRead.isNotEmpty()) {
                appendLine("角色最近真正点开过的资讯摘要：")
                recentlyRead.forEach { event ->
                    appendLine("- [${event.sourceName}] ${event.title}：${event.summary.take(420)}")
                }
            }
            appendLine("可执行的现实窗口动作（都通过 digital_world + worldAction=use_location + activityId 执行；现实窗口是露露机系统能力，不受当前数字地点限制）：")
            appendLine("- activityId=reality_explore:<角色自己想了解的关键词或圈子>：主动探索，例如“恋爱动画”“成都”“某个游戏”“某位明星”。这是角色自己的选择，不是用户分配。")
            appendLine("- activityId=reality_follow:<topicId>：只关注上面真实存在的 topicId。")
            appendLine("- activityId=reality_unfollow:<topicId>：只退订角色当前真实关注/探索过的 topicId。")
            appendLine("- activityId=reality_open:<eventId>：只点开上面真实存在的 eventId，程序会返回并记录该来源已经抓取到的摘要。")
        }.trim()
    }

    fun executeActivity(
        characterId: String,
        activityId: String,
        now: Instant = Instant.now(),
    ): String {
        checkNotNull(prefs) { "现实世界窗口尚未初始化" }
        val character = MigratedDomainStores.characters.get(characterId)
        val raw = activityId.trim()
        val summary = when {
            raw.startsWith("reality_explore:") -> {
                val query = cleanQuery(raw.substringAfter(':'))
                require(query.isNotBlank()) { "探索现实圈子需要一个明确关键词" }
                val topic = ensureDiscoveredTopic(characterId, query, now)
                enqueueImmediateRefresh()
                "${character.displayName}主动把“${topic.label}”放进自己的现实世界探索列表；这还不是长期关注，真实资讯正在等待来源刷新。"
            }
            raw.startsWith("reality_follow:") -> {
                val topicId = raw.substringAfter(':').trim()
                val topic = resolveTopic(characterId, topicId) ?: error("没有找到这个现实圈子")
                val saved = topic.copy(
                    state = RealityTopicState.FOLLOWING,
                    followedAt = topic.followedAt ?: now,
                    discoveredAt = if (topic.discoveredAt == Instant.EPOCH) now else topic.discoveredAt,
                )
                upsertTopic(characterId, saved)
                enqueueImmediateRefresh()
                "${character.displayName}自己决定关注现实圈子“${saved.label}”。"
            }
            raw.startsWith("reality_unfollow:") -> {
                val topicId = raw.substringAfter(':').trim()
                val topic = resolveTopic(characterId, topicId) ?: error("没有找到这个现实圈子")
                require(topicsFor(characterId).any { it.id == topicId && it.state != RealityTopicState.DROPPED }) {
                    "角色目前没有关注或探索这个圈子"
                }
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
                    RealitySourceTrust.AGGREGATED_REPORT -> "外部媒体聚合报道"
                }
                val distance = event.distanceKm?.let { "；距用户设备最近位置约${it}km" }.orEmpty()
                val content = buildString {
                    append("角色实际点开现实世界窗口中的一条资讯摘要。")
                    append("来源=${event.sourceName}；类型=$trust；发布时间=${event.publishedAt}；标题=${event.title}")
                    append(distance)
                    append("；来源摘要=${event.summary.take(1_200)}")
                    if (event.sourceUrl.isNotBlank()) append("；来源链接=${event.sourceUrl}")
                    append("。这证明角色看到了该来源信息，不证明角色或用户亲历了报道中的事件。")
                }
                SharedExperienceTimeline.record(
                    eventId = "reality-read-${event.id}-$characterId-${now.toEpochMilli()}",
                    characterId = characterId,
                    channel = "现实世界窗口·资讯",
                    speaker = character.displayName,
                    content = content,
                    occurredAt = now,
                )
                return "已查看现实资讯摘要｜${event.sourceName}｜${event.title}：${event.summary.take(520)}$distance"
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

    suspend fun refresh(context: Context, now: Instant = Instant.now()): RealityRefreshResult =
        withContext(Dispatchers.IO) {
            initialize(context)
            val p = checkNotNull(prefs)
            val fetched = mutableListOf<RealityWindowEvent>()

            val lastWorld = p.getLong("refresh:$TOPIC_WORLD", 0L)
                .takeIf { it > 0L }?.let(Instant::ofEpochMilli)
            if (lastWorld == null || Duration.between(lastWorld, now).abs() >= Duration.ofHours(2)) {
                runCatching { fetchGoogleRss(TOPIC_WORLD, "世界大事", GOOGLE_TOP, now) }
                    .getOrNull()?.let { fetched += it }
                p.edit().putLong("refresh:$TOPIC_WORLD", now.toEpochMilli()).apply()
            }

            val queryTopics = allRoleTopics()
                .filter { it.state != RealityTopicState.DROPPED && it.query.isNotBlank() }
                .distinctBy { it.id }
                .take(16)
            queryTopics.forEach { topic ->
                val last = p.getLong("refresh:${topic.id}", 0L)
                    .takeIf { it > 0L }?.let(Instant::ofEpochMilli)
                if (last == null || Duration.between(last, now).abs() >= Duration.ofHours(2)) {
                    val url = GOOGLE_SEARCH.format(
                        URLEncoder.encode(topic.query, StandardCharsets.UTF_8.toString()),
                    )
                    runCatching { fetchGoogleRss(topic.id, topic.label, url, now) }
                        .getOrNull()?.let { fetched += it }
                    p.edit().putLong("refresh:${topic.id}", now.toEpochMilli()).apply()
                }
            }

            val lastQuake = p.getLong("refresh:$TOPIC_EARTHQUAKE", 0L)
                .takeIf { it > 0L }?.let(Instant::ofEpochMilli)
            if (lastQuake == null || Duration.between(lastQuake, now).abs() >= Duration.ofMinutes(25)) {
                val deviceLocation = runCatching { LuluLocationProvider.freshLocation(context.applicationContext) }.getOrNull()
                runCatching { fetchEarthquakes(now, deviceLocation) }
                    .getOrNull()?.let { fetched += it }
                p.edit().putLong("refresh:$TOPIC_EARTHQUAKE", now.toEpochMilli()).apply()
            }

            if (fetched.isNotEmpty()) upsertEvents(fetched, now)
            val critical = fetched.filter {
                it.criticalForUser && Duration.between(it.publishedAt, now).abs() <= Duration.ofHours(3)
            }
            RealityRefreshResult(criticalEvents = critical)
        }

    fun claimCriticalWake(eventId: String): Boolean {
        val p = prefs ?: return false
        synchronized(lock) {
            val key = "critical_wake:$eventId"
            if (p.getBoolean(key, false)) return false
            return p.edit().putBoolean(key, true).commit()
        }
    }

    private fun enqueueImmediateRefresh() {
        val context = appContext ?: return
        val request = OneTimeWorkRequestBuilder<RealityWindowWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun resolveTopic(characterId: String, topicId: String): RealityTopic? =
        topicsFor(characterId).firstOrNull { it.id == topicId } ?: when (topicId) {
            TOPIC_WORLD -> worldTopic
            TOPIC_EARTHQUAKE -> earthquakeTopic
            else -> null
        }

    private fun ensureDiscoveredTopic(characterId: String, query: String, now: Instant): RealityTopic {
        val id = "topic-${stableId("query", query.lowercase(Locale.ROOT))}"
        val current = topicsFor(characterId).firstOrNull { it.id == id }
        if (current != null && current.state != RealityTopicState.DROPPED) return current
        val topic = RealityTopic(
            id = id,
            label = query,
            query = query,
            state = RealityTopicState.DISCOVERED,
            discoveredAt = now,
        )
        upsertTopic(characterId, topic)
        return topic
    }

    private fun cleanQuery(value: String): String = value
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(80)

    private fun upsertTopic(characterId: String, topic: RealityTopic) {
        val topics = topicsFor(characterId).filterNot { it.id == topic.id } + topic
        saveTopics(characterId, topics.sortedBy { it.discoveredAt }.takeLast(60))
    }

    private fun allRoleTopics(): List<RealityTopic> =
        MigratedDomainStores.characters.settings.value.keys.flatMap(::topicsFor)

    private fun topicsFor(characterId: String): List<RealityTopic> {
        val array = runCatching {
            JSONArray(prefs?.getString("topics:$characterId", "[]").orEmpty())
        }.getOrDefault(JSONArray())
        return buildList {
            for (i in 0 until array.length()) {
                val json = array.optJSONObject(i) ?: continue
                val id = json.optString("id").trim()
                val label = json.optString("label").trim()
                if (id.isBlank() || label.isBlank()) continue
                val state = runCatching { RealityTopicState.valueOf(json.optString("state")) }
                    .getOrDefault(RealityTopicState.DISCOVERED)
                add(
                    RealityTopic(
                        id = id,
                        label = label,
                        query = json.optString("query").trim(),
                        state = state,
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
            array.put(
                JSONObject()
                    .put("id", topic.id)
                    .put("label", topic.label)
                    .put("query", topic.query)
                    .put("state", topic.state.name)
                    .put("discoveredAt", topic.discoveredAt.toString())
                    .put("followedAt", topic.followedAt?.toString().orEmpty()),
            )
        }
        check(prefs?.edit()?.putString("topics:$characterId", array.toString())?.commit() == true) {
            "现实圈子状态保存失败"
        }
    }

    private fun exposureFor(characterId: String): Map<String, Pair<RealityExposureState, Instant>> {
        val json = runCatching {
            JSONObject(prefs?.getString("exposure:$characterId", "{}").orEmpty())
        }.getOrDefault(JSONObject())
        return buildMap {
            json.keys().forEach { eventId ->
                val item = json.optJSONObject(eventId) ?: return@forEach
                val state = runCatching { RealityExposureState.valueOf(item.optString("state")) }
                    .getOrDefault(RealityExposureState.GLIMPSED)
                val at = item.optString("at").toInstantOrNull() ?: Instant.EPOCH
                put(eventId, state to at)
            }
        }
    }

    private fun markGlimpsed(characterId: String, eventIds: List<String>, now: Instant) {
        if (eventIds.isEmpty()) return
        synchronized(lock) {
            val current = JSONObject(prefs?.getString("exposure:$characterId", "{}").orEmpty())
            eventIds.forEach { eventId ->
                val previous = current.optJSONObject(eventId)
                if (previous?.optString("state") == RealityExposureState.READ.name) return@forEach
                current.put(
                    eventId,
                    JSONObject()
                        .put("state", RealityExposureState.GLIMPSED.name)
                        .put("at", now.toString()),
                )
            }
            prefs?.edit()?.putString("exposure:$characterId", current.toString())?.apply()
        }
    }

    private fun markRead(characterId: String, eventId: String, now: Instant) {
        synchronized(lock) {
            val current = JSONObject(prefs?.getString("exposure:$characterId", "{}").orEmpty())
            current.put(
                eventId,
                JSONObject()
                    .put("state", RealityExposureState.READ.name)
                    .put("at", now.toString()),
            )
            check(prefs?.edit()?.putString("exposure:$characterId", current.toString())?.commit() == true) {
                "资讯阅读状态保存失败"
            }
        }
    }

    private fun isVisibleTo(characterId: String, event: RealityWindowEvent): Boolean {
        if (event.topicId == TOPIC_WORLD || event.topicId == TOPIC_EARTHQUAKE) return true
        return topicsFor(characterId).any { it.id == event.topicId && it.state != RealityTopicState.DROPPED }
    }

    private fun upsertEvents(incoming: List<RealityWindowEvent>, now: Instant) {
        synchronized(lock) {
            val merged = (events + incoming)
                .associateBy(RealityWindowEvent::id)
                .values
                .filter { Duration.between(it.publishedAt, now).abs() <= Duration.ofDays(10) }
                .sortedBy(RealityWindowEvent::publishedAt)
                .takeLast(MAX_EVENTS)
            events = merged
            val encoded = JSONArray()
            merged.forEach { event ->
                encoded.put(
                    JSONObject()
                        .put("id", event.id)
                        .put("topicId", event.topicId)
                        .put("topicLabel", event.topicLabel)
                        .put("title", event.title)
                        .put("summary", event.summary)
                        .put("sourceName", event.sourceName)
                        .put("sourceUrl", event.sourceUrl)
                        .put("publishedAt", event.publishedAt.toString())
                        .put("fetchedAt", event.fetchedAt.toString())
                        .put("trust", event.trust.name)
                        .put("criticalForUser", event.criticalForUser)
                        .put("distanceKm", event.distanceKm ?: JSONObject.NULL),
                )
            }
            check(prefs?.edit()?.putString(KEY_EVENTS, encoded.toString())?.commit() == true) {
                "现实资讯保存失败"
            }
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
                        trust = runCatching { RealitySourceTrust.valueOf(json.optString("trust")) }
                            .getOrDefault(RealitySourceTrust.AGGREGATED_REPORT),
                        criticalForUser = json.optBoolean("criticalForUser", false),
                        distanceKm = if (json.isNull("distanceKm")) null else json.optInt("distanceKm"),
                    ),
                )
            }
        }
    }

    private fun fetchGoogleRss(
        topicId: String,
        topicLabel: String,
        url: String,
        now: Instant,
    ): List<RealityWindowEvent> {
        val xml = httpGet(url)
        val parser = Xml.newPullParser().apply { setInput(StringReader(xml)) }
        val result = mutableListOf<RealityWindowEvent>()
        var inItem = false
        var currentTag = ""
        var title = ""
        var link = ""
        var description = ""
        var pubDate = ""
        var guid = ""
        var source = ""

        fun addCurrent() {
            val cleanTitle = cleanExternalText(title).take(300)
            if (cleanTitle.isBlank()) return
            val published = parseRssDate(pubDate) ?: now
            val stable = guid.ifBlank { link.ifBlank { "$cleanTitle:$published" } }
            result += RealityWindowEvent(
                id = "gnews-${stableId(topicId, stable)}",
                topicId = topicId,
                topicLabel = topicLabel,
                title = cleanTitle,
                summary = cleanExternalText(description).take(1_000).ifBlank { cleanTitle },
                sourceName = cleanExternalText(source).ifBlank { "Google News 聚合" }.take(100),
                sourceUrl = link.trim().take(1_000),
                publishedAt = published,
                fetchedAt = now,
                trust = RealitySourceTrust.AGGREGATED_REPORT,
            )
        }

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name.orEmpty()
                    if (currentTag.equals("item", ignoreCase = true)) {
                        inItem = true
                        title = ""
                        link = ""
                        description = ""
                        pubDate = ""
                        guid = ""
                        source = ""
                    }
                }
                XmlPullParser.TEXT -> if (inItem) {
                    when (currentTag.lowercase(Locale.ROOT)) {
                        "title" -> title += parser.text
                        "link" -> link += parser.text
                        "description" -> description += parser.text
                        "pubdate" -> pubDate += parser.text
                        "guid" -> guid += parser.text
                        "source" -> source += parser.text
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("item", ignoreCase = true)) {
                        addCurrent()
                        inItem = false
                    }
                    currentTag = ""
                }
            }
            parser.next()
        }
        return result
            .distinctBy(RealityWindowEvent::id)
            .sortedByDescending(RealityWindowEvent::publishedAt)
            .take(24)
    }

    private fun fetchEarthquakes(now: Instant, deviceLocation: Location?): List<RealityWindowEvent> {
        val root = JSONObject(httpGet(USGS_45_DAY))
        val features = root.optJSONArray("features") ?: return emptyList()
        return buildList {
            for (i in 0 until features.length()) {
                val feature = features.optJSONObject(i) ?: continue
                val properties = feature.optJSONObject("properties") ?: continue
                val geometry = feature.optJSONObject("geometry")
                val coordinates = geometry?.optJSONArray("coordinates")
                val latitude = coordinates?.optDouble(1, Double.NaN) ?: Double.NaN
                val longitude = coordinates?.optDouble(0, Double.NaN) ?: Double.NaN
                val magnitude = properties.optDouble("mag", Double.NaN)
                if (magnitude.isNaN()) continue
                val distanceKm = if (
                    deviceLocation != null && !latitude.isNaN() && !longitude.isNaN()
                ) {
                    val out = FloatArray(1)
                    Location.distanceBetween(
                        deviceLocation.latitude,
                        deviceLocation.longitude,
                        latitude,
                        longitude,
                        out,
                    )
                    (out[0] / 1_000f).toInt().coerceAtLeast(0)
                } else null
                if (distanceKm == null && magnitude < 6.0) continue
                if (distanceKm != null && distanceKm > 1_500 && magnitude < 6.0) continue

                val eventTime = properties.optLong("time", 0L)
                    .takeIf { it > 0L }?.let(Instant::ofEpochMilli) ?: now
                val place = properties.optString("place").trim().ifBlank { "未命名地点" }
                val critical = when {
                    distanceKm == null -> false
                    distanceKm <= 500 && magnitude >= 4.5 -> true
                    distanceKm <= 1_000 && magnitude >= 5.5 -> true
                    distanceKm <= 1_500 && magnitude >= 6.0 -> true
                    else -> false
                }
                val id = feature.optString("id").trim().ifBlank {
                    stableId("quake", "$place:$eventTime:$magnitude")
                }
                val distanceText = distanceKm?.let { "；距用户设备最近位置约${it}km" }.orEmpty()
                val detail = buildString {
                    append("USGS 结构化地震目录记录：M")
                    append(String.format(Locale.US, "%.1f", magnitude))
                    append("，地点=$place，时间=$eventTime")
                    append(distanceText)
                    val alert = properties.optString("alert").trim()
                    if (alert.isNotBlank() && alert != "null") append("；alert=$alert")
                    append("。")
                }
                add(
                    RealityWindowEvent(
                        id = "usgs-$id",
                        topicId = TOPIC_EARTHQUAKE,
                        topicLabel = "地震与现实安全",
                        title = "M${String.format(Locale.US, "%.1f", magnitude)} $place",
                        summary = detail,
                        sourceName = "USGS Earthquake Catalog",
                        sourceUrl = properties.optString("url").trim(),
                        publishedAt = eventTime,
                        fetchedAt = now,
                        trust = RealitySourceTrust.STRUCTURED_SOURCE,
                        criticalForUser = critical,
                        distanceKm = distanceKm,
                    ),
                )
            }
        }.sortedByDescending(RealityWindowEvent::publishedAt).take(40)
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 16_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json, application/rss+xml, application/xml, text/xml, */*")
            setRequestProperty("User-Agent", "Lulu/1.0 RealityWindow Android")
        }
        return try {
            val code = connection.responseCode
            require(code in 200..299) { "现实资讯源请求失败 HTTP $code" }
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRssDate(value: String): Instant? = runCatching {
        ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
    }.getOrNull()

    private fun cleanExternalText(value: String): String = value
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun stableId(namespace: String, value: String): String {
        val seed = "$namespace::$value"
        val first = seed.hashCode().toUInt().toString(16)
        val second = seed.reversed().hashCode().toUInt().toString(16)
        return "$first$second"
    }

    private fun String.toInstantOrNull(): Instant? =
        takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() }
}

class RealityWindowWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        val now = Instant.now()
        val refresh = RealityWorldWindowRuntime.refresh(applicationContext, now)
        val critical = refresh.criticalEvents
            .sortedByDescending(RealityWindowEvent::publishedAt)
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
    }.getOrElse {
        Result.retry()
    }
}
