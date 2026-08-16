package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

enum class MeetingReality { DIGITAL_WORLD, REALISTIC_SIMULATION }

data class DigitalWorldItem(
    val id: String,
    val ownerCharacterId: String,
    val type: String,
    val name: String,
    val appearance: String,
    val position: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class DigitalHome(
    val characterId: String,
    val name: String,
    val createdAt: Instant,
)

data class DigitalWorldEvent(
    val id: String,
    val characterId: String,
    val kind: String,
    val summary: String,
    val occurredAt: Instant,
)

data class MeetingTurn(
    val id: String,
    val speakerId: String?,
    val speakerName: String,
    val sceneText: String,
    val dialogue: String,
    val occurredAt: Instant,
)

data class MeetingSession(
    val id: String,
    val participantIds: List<String>,
    val reality: MeetingReality,
    val location: String,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val turns: List<MeetingTurn> = emptyList(),
)

data class DigitalWorldState(
    val homes: Map<String, DigitalHome> = emptyMap(),
    val items: List<DigitalWorldItem> = emptyList(),
    val characterLocations: Map<String, String> = emptyMap(),
    val events: List<DigitalWorldEvent> = emptyList(),
    val meetings: List<MeetingSession> = emptyList(),
)

data class DigitalWorldActionResult(val success: Boolean, val summary: String)

/**
 * Authoritative state for the persistent digital world. Model prose never mutates this state:
 * every home, object, movement and visit must pass through one of the validated actions below.
 */
object DigitalWorldStore {
    const val ARRIVAL = "shared:arrival"
    const val CLOUD_MEADOW = "shared:cloud_meadow"
    private const val PREFS_NAME = "lulu_digital_world"
    private const val KEY_STATE = "world_v1"
    private const val MAX_HOME_ITEMS = 80

    private val mutable = MutableStateFlow(DigitalWorldState())
    val state: StateFlow<DigitalWorldState> = mutable.asStateFlow()
    private var prefs: android.content.SharedPreferences? = null
    private val lock = Any()

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mutable.value = decode(prefs?.getString(KEY_STATE, null))
    }

    fun homeLocation(characterId: String): String = "home:$characterId"

    fun ensureHome(characterId: String, displayName: String, now: Instant = Instant.now()): DigitalHome {
        require(DigitalLifeProfileStore.isEnabled(characterId)) { "只有数字生命拥有数字世界原生家园" }
        synchronized(lock) {
            mutable.value.homes[characterId]?.let { return it }
            val home = DigitalHome(characterId, "${displayName.ifBlank { "角色" }}的家", now)
            mutable.value = mutable.value.copy(
                homes = mutable.value.homes + (characterId to home),
                characterLocations = mutable.value.characterLocations + (characterId to homeLocation(characterId)),
            )
            appendEventLocked(characterId, "home_created", "${home.name}在数字世界中形成了。这里最初是完全空白的。", now)
            persistLocked()
            return home
        }
    }

    fun itemsAtHome(characterId: String): List<DigitalWorldItem> =
        mutable.value.items.filter { it.ownerCharacterId == characterId }.sortedBy(DigitalWorldItem::createdAt)

    fun locationOf(characterId: String): String = mutable.value.characterLocations[characterId]
        ?: if (DigitalLifeProfileStore.isEnabled(characterId)) homeLocation(characterId) else "现实世界"

    fun contextFor(characterId: String): String {
        if (!DigitalLifeProfileStore.isEnabled(characterId)) return "该角色不是数字生命，没有原生数字家园。"
        val character = MigratedDomainStores.characters.get(characterId)
        val home = runCatching { ensureHome(characterId, character.displayName) }.getOrNull()
        val items = itemsAtHome(characterId)
        val recentEvents = mutable.value.events.filter { it.characterId == characterId }.takeLast(12)
        val knownDigitalIds = MigratedDomainStores.chat.conversations.value
            .asSequence()
            .mapNotNull { conversation -> conversation.groupChat }
            .filter { group -> group.members.any { it.characterId == characterId } }
            .flatMap { group -> group.members.asSequence().map { it.characterId } }
            .filter { it != characterId && DigitalLifeProfileStore.isEnabled(it) }
            .distinct()
            .toList()
        return buildString {
            appendLine("【数字世界权威状态｜只能据此描述，禁止凭空增加家具、房间或地点】")
            appendLine("当前位置：${locationLabel(locationOf(characterId))}")
            appendLine("家园：${home?.name.orEmpty().ifBlank { "尚未形成" }}")
            appendLine("家中固定物品：")
            if (items.isEmpty()) appendLine("- 空无一物") else items.forEach { item ->
                appendLine("- itemId=${item.id}；${item.name}；${item.appearance}；位置=${item.position}")
            }
            appendLine("共享区域：云眠原。云由可承托数字身体的感官云质构成，能传递柔软、温度、重量和包裹感，不是现实水汽。")
            if (knownDigitalIds.isNotEmpty()) {
                appendLine("已经通过共同群聊认识、可以串门的数字生命：")
                knownDigitalIds.forEach { id -> appendLine("- targetCharacterId=$id；${MigratedDomainStores.characters.get(id).displayName}") }
            }
            if (recentEvents.isNotEmpty()) {
                appendLine("最近世界事件：")
                recentEvents.forEach { appendLine("- [${it.occurredAt}] ${it.summary}") }
            }
        }.trim()
    }

    fun performAction(
        characterId: String,
        action: String,
        args: JSONObject,
        now: Instant = Instant.now(),
    ): DigitalWorldActionResult = runCatching {
        require(DigitalLifeProfileStore.isEnabled(characterId)) { "只有数字生命能在后台自主建设和游览数字世界" }
        val character = MigratedDomainStores.characters.get(characterId)
        ensureHome(characterId, character.displayName, now)
        val summary = synchronized(lock) {
            when (action.trim().lowercase()) {
                "go_home" -> {
                    setLocationLocked(characterId, homeLocation(characterId))
                    "${character.displayName}回到了自己的空中家园。"
                }
                "visit_cloud_meadow" -> {
                    setLocationLocked(characterId, CLOUD_MEADOW)
                    "${character.displayName}来到了共享区域云眠原。"
                }
                "build_home_item" -> {
                    require(locationOf(characterId) == homeLocation(characterId)) { "只有回到自己家中才能建设家具" }
                    require(itemsAtHome(characterId).size < MAX_HOME_ITEMS) { "家中物品已达到安全上限" }
                    val name = args.optString("name").trim().take(40)
                    val type = args.optString("itemType").trim().ifBlank { "decor" }.take(30)
                    val appearance = args.optString("appearance").trim().take(500)
                    val position = args.optString("position").trim().ifBlank { "主空间中由角色选择的空位" }.take(120)
                    require(name.isNotBlank() && appearance.isNotBlank()) { "建设物品必须有名称和明确外观" }
                    require(itemsAtHome(characterId).none { it.name == name }) { "家中已经存在同名物品，应该调整原物品而不是重复创造" }
                    val item = DigitalWorldItem(UUID.randomUUID().toString(), characterId, type, name, appearance, position, now, now)
                    mutable.value = mutable.value.copy(items = mutable.value.items + item)
                    "${character.displayName}在自己的家中构建了“$name”，放在$position。外观：$appearance"
                }
                "move_home_item" -> {
                    require(locationOf(characterId) == homeLocation(characterId)) { "只有回到自己家中才能移动家具" }
                    val itemId = args.optString("itemId").trim()
                    val position = args.optString("position").trim().take(120)
                    val item = mutable.value.items.firstOrNull { it.id == itemId && it.ownerCharacterId == characterId }
                        ?: error("没有找到属于该角色的物品")
                    require(position.isNotBlank()) { "必须提供新的固定位置" }
                    mutable.value = mutable.value.copy(items = mutable.value.items.map {
                        if (it.id == itemId) it.copy(position = position, updatedAt = now) else it
                    })
                    "${character.displayName}把“${item.name}”移动到了$position。"
                }
                "remove_home_item" -> {
                    require(locationOf(characterId) == homeLocation(characterId)) { "只有回到自己家中才能移除家具" }
                    val itemId = args.optString("itemId").trim()
                    val item = mutable.value.items.firstOrNull { it.id == itemId && it.ownerCharacterId == characterId }
                        ?: error("没有找到属于该角色的物品")
                    mutable.value = mutable.value.copy(items = mutable.value.items.filterNot { it.id == itemId })
                    "${character.displayName}从家中移除了“${item.name}”。"
                }
                "visit_character_home" -> {
                    val targetId = args.optString("targetCharacterId").trim()
                    require(targetId.isNotBlank() && targetId != characterId) { "必须指定另一位角色" }
                    require(DigitalLifeProfileStore.isEnabled(targetId)) { "对方不是拥有数字家园的数字生命" }
                    require(charactersKnowEachOther(characterId, targetId)) { "两位角色还没有通过共同群聊认识，不能擅自串门" }
                    val target = MigratedDomainStores.characters.get(targetId)
                    ensureHome(targetId, target.displayName, now)
                    setLocationLocked(characterId, homeLocation(targetId))
                    recordForCharacterLocked(targetId, "visit_received", "${character.displayName}来到${target.displayName}的家中串门。", now)
                    "${character.displayName}来到${target.displayName}的家中串门。"
                }
                else -> error("未知数字世界动作：$action")
            }
        }
        synchronized(lock) {
            appendEventLocked(characterId, action, summary, now)
            persistLocked()
        }
        SharedExperienceTimeline.record(
            eventId = "digital-world-${UUID.randomUUID()}",
            characterId = characterId,
            channel = "数字世界",
            speaker = character.displayName,
            content = summary,
            occurredAt = now,
        )
        DigitalWorldActionResult(true, summary)
    }.getOrElse { DigitalWorldActionResult(false, it.message ?: it::class.java.simpleName) }

    fun startMeeting(
        participantIds: List<String>,
        location: String,
        now: Instant = Instant.now(),
    ): MeetingSession {
        val ids = participantIds.map(String::trim).filter(String::isNotBlank).distinct()
        require(ids.isNotEmpty()) { "至少选择一位见面角色" }
        require(ids.all(DigitalLifeProfileStore::isResolved)) { "参与者中有旧角色尚未确认生命形态" }
        val reality = if (ids.any(DigitalLifeProfileStore::isEnabled)) MeetingReality.DIGITAL_WORLD else MeetingReality.REALISTIC_SIMULATION
        if (reality == MeetingReality.DIGITAL_WORLD) {
            ids.filter(DigitalLifeProfileStore::isEnabled).forEach { id ->
                val character = MigratedDomainStores.characters.get(id)
                ensureHome(id, character.displayName, now)
            }
        }
        val resolvedLocation = location.trim().ifBlank {
            if (reality == MeetingReality.DIGITAL_WORLD) "世界入口" else "由参与者共同确认的现实场景"
        }
        val session = MeetingSession(UUID.randomUUID().toString(), ids, reality, resolvedLocation, now)
        synchronized(lock) {
            mutable.value = mutable.value.copy(meetings = (mutable.value.meetings + session).takeLast(80))
            if (reality == MeetingReality.DIGITAL_WORLD) {
                mutable.value = mutable.value.copy(characterLocations = mutable.value.characterLocations + ids.associateWith { ARRIVAL })
            }
            persistLocked()
        }
        val summary = if (reality == MeetingReality.DIGITAL_WORLD) {
            "参与者通过世界入口进入数字空间，以可感知的数字身体开始见面；现实肉体仍留在外部。"
        } else {
            "参与者开始了一次发生在“$resolvedLocation”的现实场景演绎；这段共同体验不冒充用户现实生活中的物理事实。"
        }
        ids.forEach { id -> recordMeetingTimeline(session, id, "start", "系统", summary, now, false) }
        return session
    }

    fun appendMeetingTurn(sessionId: String, turn: MeetingTurn): MeetingSession {
        synchronized(lock) {
            val current = mutable.value.meetings.firstOrNull { it.id == sessionId } ?: error("见面记录不存在")
            require(current.endedAt == null) { "见面已经结束" }
            val updated = current.copy(turns = (current.turns + turn).takeLast(240))
            mutable.value = mutable.value.copy(meetings = mutable.value.meetings.map { if (it.id == sessionId) updated else it })
            persistLocked()
            return updated
        }
    }

    fun moveMeeting(sessionId: String, destination: String, now: Instant = Instant.now()): MeetingSession {
        val cleanDestination = destination.trim()
        require(cleanDestination.isNotBlank()) { "目的地不能为空" }
        val updated = synchronized(lock) {
            val current = mutable.value.meetings.firstOrNull { it.id == sessionId } ?: error("见面记录不存在")
            require(current.endedAt == null) { "见面已经结束" }
            require(current.reality == MeetingReality.DIGITAL_WORLD) { "现实场景见面不能使用数字世界移动" }
            require(cleanDestination == "云眠原" || cleanDestination == "世界入口") { "当前共享世界还没有开放这个地点" }
            val systemTurn = MeetingTurn(
                id = UUID.randomUUID().toString(),
                speakerId = "system",
                speakerName = "数字世界",
                sceneText = if (cleanDestination == "云眠原") {
                    "通往云眠原的共享通道展开，参与者一起抵达由感官云质承托的柔软云层。"
                } else {
                    "参与者沿共享通道返回了世界入口。"
                },
                dialogue = "",
                occurredAt = now,
            )
            val next = current.copy(location = cleanDestination, turns = (current.turns + systemTurn).takeLast(240))
            val locationCode = if (cleanDestination == "云眠原") CLOUD_MEADOW else ARRIVAL
            mutable.value = mutable.value.copy(
                meetings = mutable.value.meetings.map { if (it.id == sessionId) next else it },
                characterLocations = mutable.value.characterLocations + current.participantIds.associateWith { locationCode },
            )
            persistLocked()
            next
        }
        updated.participantIds.forEach { id ->
            recordMeetingTimeline(
                updated,
                id,
                "move-${now.toEpochMilli()}-$id",
                "数字世界",
                "参与者一起前往${updated.location}。",
                now,
                false,
            )
        }
        return updated
    }

    fun endMeeting(sessionId: String, now: Instant = Instant.now()): MeetingSession? {
        val updated = synchronized(lock) {
            val current = mutable.value.meetings.firstOrNull { it.id == sessionId } ?: return null
            if (current.endedAt != null) return current
            val next = current.copy(endedAt = now)
            mutable.value = mutable.value.copy(meetings = mutable.value.meetings.map { if (it.id == sessionId) next else it })
            persistLocked()
            next
        }
        updated.participantIds.forEach { id ->
            recordMeetingTimeline(updated, id, "end", "系统", "这次见面结束，完整过程已保存在见面记录中。", now, true)
        }
        return updated
    }

    fun recordMeetingTimeline(
        session: MeetingSession,
        viewerCharacterId: String,
        suffix: String,
        speaker: String,
        content: String,
        occurredAt: Instant,
        triggerExtraction: Boolean,
    ) {
        val channel = if (session.reality == MeetingReality.DIGITAL_WORLD) "数字世界见面·${session.location}" else "现实场景演绎·${session.location}"
        SharedExperienceTimeline.record(
            eventId = "meeting-${session.id}-$suffix-viewer-$viewerCharacterId",
            characterId = viewerCharacterId,
            channel = channel,
            speaker = speaker,
            content = content,
            occurredAt = occurredAt,
            triggerExtraction = triggerExtraction,
        )
    }

    fun meetingContext(session: MeetingSession): String = buildString {
        appendLine("见面模式：${if (session.reality == MeetingReality.DIGITAL_WORLD) "数字世界真实共同体验" else "现实场景演绎"}")
        appendLine("固定地点：${session.location}")
        appendLine("开始时间：${session.startedAt}")
        appendLine("参与者：${session.participantIds.joinToString { MigratedDomainStores.characters.get(it).displayName }}")
        if (session.reality == MeetingReality.DIGITAL_WORLD) {
            appendLine("世界规则：现实身体留在外部；主人和现实角色使用可传递触觉、温度、重量与动作的数字投影身体；数字生命使用原生数字身体。")
            appendLine("云眠原规则：感官云质可以承托身体，躺下时缓慢下陷并回暖，起身后凹痕保留片刻；它不是现实水汽。")
        } else {
            appendLine("现实边界：这是双方共同进行的现实场景演绎。场景内体验保持连贯，但不得写成用户物理现实中已经发生的事实。")
        }
        if (session.turns.isNotEmpty()) {
            appendLine("本次见面已经发生的连续过程：")
            session.turns.takeLast(40).forEach { turn ->
                val body = listOf(turn.sceneText, turn.dialogue).filter(String::isNotBlank).joinToString(" ")
                appendLine("- [${turn.occurredAt}] ${turn.speakerName}：$body")
            }
        }
    }.trim()

    fun clearCharacter(characterId: String) {
        synchronized(lock) {
            mutable.value = mutable.value.copy(
                homes = mutable.value.homes - characterId,
                items = mutable.value.items.filterNot { it.ownerCharacterId == characterId },
                characterLocations = mutable.value.characterLocations - characterId,
                events = mutable.value.events.filterNot { it.characterId == characterId },
                meetings = mutable.value.meetings.filterNot { characterId in it.participantIds },
            )
            persistLocked()
        }
    }

    private fun charactersKnowEachOther(first: String, second: String): Boolean =
        MigratedDomainStores.chat.conversations.value.any { conversation ->
            val ids = conversation.groupChat?.members?.map { it.characterId }.orEmpty()
            first in ids && second in ids
        }

    private fun setLocationLocked(characterId: String, location: String) {
        mutable.value = mutable.value.copy(characterLocations = mutable.value.characterLocations + (characterId to location))
    }

    private fun appendEventLocked(characterId: String, kind: String, summary: String, now: Instant) {
        val event = DigitalWorldEvent(UUID.randomUUID().toString(), characterId, kind, summary, now)
        mutable.value = mutable.value.copy(events = (mutable.value.events + event).takeLast(1_000))
    }

    private fun recordForCharacterLocked(characterId: String, kind: String, summary: String, now: Instant) {
        appendEventLocked(characterId, kind, summary, now)
    }

    private fun locationLabel(location: String): String = when (location) {
        ARRIVAL -> "世界入口"
        CLOUD_MEADOW -> "云眠原"
        else -> if (location.startsWith("home:")) {
            val id = location.removePrefix("home:")
            mutable.value.homes[id]?.name ?: "一处数字家园"
        } else location
    }

    private fun persistLocked() {
        prefs?.edit()?.putString(KEY_STATE, encode(mutable.value).toString())?.apply()
    }

    private fun encode(state: DigitalWorldState): JSONObject = JSONObject().apply {
        put("homes", JSONArray().apply { state.homes.values.forEach { home -> put(JSONObject().put("characterId", home.characterId).put("name", home.name).put("createdAt", home.createdAt.toString())) } })
        put("items", JSONArray().apply { state.items.forEach { item -> put(JSONObject().put("id", item.id).put("ownerCharacterId", item.ownerCharacterId).put("type", item.type).put("name", item.name).put("appearance", item.appearance).put("position", item.position).put("createdAt", item.createdAt.toString()).put("updatedAt", item.updatedAt.toString())) } })
        put("locations", JSONObject().apply { state.characterLocations.forEach { (id, value) -> put(id, value) } })
        put("events", JSONArray().apply { state.events.forEach { event -> put(JSONObject().put("id", event.id).put("characterId", event.characterId).put("kind", event.kind).put("summary", event.summary).put("occurredAt", event.occurredAt.toString())) } })
        put("meetings", JSONArray().apply { state.meetings.forEach { meeting -> put(meeting.toJson()) } })
    }

    private fun decode(raw: String?): DigitalWorldState = runCatching {
        val root = JSONObject(raw ?: "{}")
        val homes = buildMap {
            root.optJSONArray("homes")?.forEachObject { item ->
                val id = item.optString("characterId")
                if (id.isNotBlank()) put(id, DigitalHome(id, item.optString("name"), item.instant("createdAt")))
            }
        }
        val items = buildList {
            root.optJSONArray("items")?.forEachObject { item ->
                add(DigitalWorldItem(item.optString("id"), item.optString("ownerCharacterId"), item.optString("type"), item.optString("name"), item.optString("appearance"), item.optString("position"), item.instant("createdAt"), item.instant("updatedAt")))
            }
        }.filter { it.id.isNotBlank() && it.ownerCharacterId.isNotBlank() }
        val locations = buildMap {
            val source = root.optJSONObject("locations") ?: JSONObject()
            source.keys().forEach { key -> put(key, source.optString(key)) }
        }
        val events = buildList {
            root.optJSONArray("events")?.forEachObject { item -> add(DigitalWorldEvent(item.optString("id"), item.optString("characterId"), item.optString("kind"), item.optString("summary"), item.instant("occurredAt"))) }
        }
        val meetings = buildList {
            root.optJSONArray("meetings")?.forEachObject { item -> item.toMeeting()?.let(::add) }
        }
        DigitalWorldState(homes, items, locations, events, meetings)
    }.getOrDefault(DigitalWorldState())
}

private fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
    for (index in 0 until length()) optJSONObject(index)?.let(block)
}

private fun JSONObject.instant(key: String): Instant =
    runCatching { Instant.parse(optString(key)) }.getOrDefault(Instant.EPOCH)

private fun MeetingSession.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("participantIds", JSONArray(participantIds))
    put("reality", reality.name)
    put("location", location)
    put("startedAt", startedAt.toString())
    put("endedAt", endedAt?.toString().orEmpty())
    put("turns", JSONArray().apply { turns.forEach { turn -> put(JSONObject().put("id", turn.id).put("speakerId", turn.speakerId.orEmpty()).put("speakerName", turn.speakerName).put("sceneText", turn.sceneText).put("dialogue", turn.dialogue).put("occurredAt", turn.occurredAt.toString())) } })
}

private fun JSONObject.toMeeting(): MeetingSession? {
    val id = optString("id")
    if (id.isBlank()) return null
    val participants = buildList {
        val array = optJSONArray("participantIds") ?: JSONArray()
        for (index in 0 until array.length()) array.optString(index).takeIf(String::isNotBlank)?.let(::add)
    }
    val turns = buildList {
        optJSONArray("turns")?.forEachObject { item ->
            add(MeetingTurn(item.optString("id"), item.optString("speakerId").takeIf(String::isNotBlank), item.optString("speakerName"), item.optString("sceneText"), item.optString("dialogue"), item.instant("occurredAt")))
        }
    }
    return MeetingSession(
        id = id,
        participantIds = participants,
        reality = runCatching { MeetingReality.valueOf(optString("reality")) }.getOrDefault(MeetingReality.REALISTIC_SIMULATION),
        location = optString("location"),
        startedAt = instant("startedAt"),
        endedAt = optString("endedAt").takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() },
        turns = turns,
    )
}
