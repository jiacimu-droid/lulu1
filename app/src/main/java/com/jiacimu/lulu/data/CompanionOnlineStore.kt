package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

enum class CompanionOnlineReason {
    BackgroundPerception,
    PrivateWake,
    GroupWake,
    MomentsWake,
    NewActivity,
}

data class CompanionOnlineState(
    val characterId: String,
    val onlineUntil: Instant,
    val reason: CompanionOnlineReason,
    val lastSeenAt: Instant? = null,
    val historyFloorAt: Instant? = null,
) {
    fun isOnline(now: Instant = Instant.now()): Boolean = onlineUntil.isAfter(now)
}

data class CompanionUnreadSnapshot(
    val text: String,
    val newestAt: Instant?,
)

/**
 * One shared, durable definition of character online presence.
 *
 * A wake-up means five minutes of guaranteed perception, never a guaranteed reply. While a
 * character is online, new relevant chat events schedule an independent perception for that role.
 */
object CompanionOnlineStore {
    private const val PREFS_NAME = "lulu_companion_online_v1"
    private const val KEY_STATES = "states"
    private const val KEY_GROUP_FOCUS = "group_focus"
    private val onlineDuration: Duration = Duration.ofMinutes(5)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val expiryJobs = mutableMapOf<String, Job>()
    private val lock = Any()
    private var appContext: Context? = null
    private var prefs: android.content.SharedPreferences? = null
    private val mutableStates = MutableStateFlow<Map<String, CompanionOnlineState>>(emptyMap())
    val states: StateFlow<Map<String, CompanionOnlineState>> = mutableStates.asStateFlow()
    private var groupFocusUntil: Map<String, Instant> = emptyMap()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            appContext = context.applicationContext
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val now = Instant.now()
            val loaded = decodeStates(prefs?.getString(KEY_STATES, null)).toMutableMap()
            MigratedDomainStores.characters.settings.value.keys.forEach { characterId ->
                loaded.putIfAbsent(
                    characterId,
                    CompanionOnlineState(
                        characterId = characterId,
                        onlineUntil = now,
                        reason = CompanionOnlineReason.BackgroundPerception,
                        lastSeenAt = latestRelevantChatAt(characterId),
                    ),
                )
            }
            mutableStates.value = loaded
            groupFocusUntil = decodeGroupFocus(prefs?.getString(KEY_GROUP_FOCUS, null))
                .filterValues { it.isAfter(now) }
            persistLocked()
            mutableStates.value.keys.forEach(::scheduleExpiryLocked)
        }
    }

    fun isOnline(characterId: String, now: Instant = Instant.now()): Boolean =
        mutableStates.value[characterId]?.isOnline(now) == true

    fun wakeCharacter(
        characterId: String,
        reason: CompanionOnlineReason,
        trigger: String,
        perceiveNow: Boolean = true,
        now: Instant = Instant.now(),
    ) {
        if (characterId.isBlank()) return
        synchronized(lock) {
            val previous = mutableStates.value[characterId]
            val state = (previous ?: CompanionOnlineState(characterId, now, reason)).copy(
                onlineUntil = now.plus(onlineDuration),
                reason = reason,
            )
            mutableStates.value = mutableStates.value + (characterId to state)
            persistLocked()
            scheduleExpiryLocked(characterId)
        }
        if (perceiveNow) {
            appContext?.let { ProactivePerceptionScheduler.scheduleOnline(it, characterId, trigger) }
        }
    }

    fun wakeGroup(
        conversation: LuluConversation,
        trigger: String = "用户在群聊呼唤全员上线",
        now: Instant = Instant.now(),
    ) {
        val memberIds = conversation.groupChat?.members.orEmpty().map(LuluGroupMember::characterId).distinct()
        if (memberIds.isEmpty()) return
        synchronized(lock) {
            groupFocusUntil = groupFocusUntil + (conversation.id to now.plus(onlineDuration))
            memberIds.forEach { characterId ->
                val previous = mutableStates.value[characterId]
                val state = (previous ?: CompanionOnlineState(characterId, now, CompanionOnlineReason.GroupWake)).copy(
                    onlineUntil = now.plus(onlineDuration),
                    reason = CompanionOnlineReason.GroupWake,
                )
                mutableStates.value = mutableStates.value + (characterId to state)
                scheduleExpiryLocked(characterId)
            }
            persistLocked()
        }
        memberIds.forEach { characterId ->
            appContext?.let { ProactivePerceptionScheduler.scheduleOnline(it, characterId, trigger) }
        }
    }

    /** Called after a chat event is durably appended. */
    fun onConversationMessage(conversation: LuluConversation, message: LuluChatMessage) {
        if (message.status != LuluChatMessage.Status.Sent || message.sender == LuluChatMessage.Sender.System) return
        val now = message.createdAt
        val members = conversation.groupChat?.members.orEmpty().map(LuluGroupMember::characterId).distinct()
        val recipients: List<String>
        synchronized(lock) {
            val focused = groupFocusUntil[conversation.id]?.isAfter(now) == true
            if (focused && members.isNotEmpty()) {
                groupFocusUntil = groupFocusUntil + (conversation.id to now.plus(onlineDuration))
                members.forEach { characterId ->
                    val previous = mutableStates.value[characterId]
                    val state = (previous ?: CompanionOnlineState(characterId, now, CompanionOnlineReason.NewActivity)).copy(
                        onlineUntil = now.plus(onlineDuration),
                        reason = CompanionOnlineReason.NewActivity,
                    )
                    mutableStates.value = mutableStates.value + (characterId to state)
                    scheduleExpiryLocked(characterId)
                }
                persistLocked()
            }
            if (
                conversation.groupChat == null &&
                isOnline(conversation.characterId, now)
            ) {
                val current = mutableStates.value[conversation.characterId]
                if (current != null) {
                    mutableStates.value = mutableStates.value + (
                        conversation.characterId to current.copy(
                            onlineUntil = now.plus(onlineDuration),
                            reason = CompanionOnlineReason.NewActivity,
                        )
                    )
                    scheduleExpiryLocked(conversation.characterId)
                    persistLocked()
                }
            }
            recipients = if (conversation.groupChat != null) {
                members.filter { characterId ->
                    characterId != message.authorCharacterId && isOnline(characterId, now)
                }
            } else {
                listOf(conversation.characterId).filter { characterId ->
                    message.sender == LuluChatMessage.Sender.User && isOnline(characterId, now)
                }
            }
        }
        recipients.forEach { characterId ->
            appContext?.let {
                ProactivePerceptionScheduler.scheduleOnline(
                    it,
                    characterId,
                    if (conversation.groupChat == null) "在线期间收到私聊新消息" else "在线期间群聊出现新消息",
                )
            }
        }
    }

    fun unreadChatSnapshot(characterId: String, limit: Int = 30): CompanionUnreadSnapshot {
        val state = mutableStates.value[characterId]
        val after = listOfNotNull(state?.lastSeenAt, state?.historyFloorAt).maxOrNull()
        val events = MigratedDomainStores.chat.conversations.value.asSequence()
            .filter { conversation ->
                conversation.groupChat?.members?.any { it.characterId == characterId } == true ||
                    (conversation.groupChat == null && conversation.characterId == characterId && !conversation.id.endsWith("-study-focus"))
            }
            .flatMap { conversation ->
                MigratedDomainStores.chat.messages(conversation.id).value.asSequence()
                    .filter { message ->
                        message.status == LuluChatMessage.Status.Sent &&
                            message.sender != LuluChatMessage.Sender.System &&
                            message.authorCharacterId != characterId &&
                            (after == null || message.createdAt.isAfter(after))
                    }
                    .map { message -> conversation to message }
            }
            .toList()
            .sortedBy { (_, message) -> message.createdAt }
            .takeLast(limit)
        val text = events.joinToString("\n") { (conversation, message) ->
            val scene = conversation.groupChat?.name?.let { "群聊《$it》" } ?: "私聊"
            val speaker = when (message.sender) {
                LuluChatMessage.Sender.User -> UserProfileContext.displayLabel()
                LuluChatMessage.Sender.Character -> message.authorCharacterId
                    ?.let { MigratedDomainStores.characters.get(it).displayName }
                    .orEmpty().ifBlank { "角色" }
                LuluChatMessage.Sender.System -> "系统"
            }
            "- ${message.createdAt}｜$scene｜$speaker：${message.content.take(600)}"
        }
        return CompanionUnreadSnapshot(text, events.maxOfOrNull { (_, message) -> message.createdAt })
    }

    fun markSeen(characterId: String, seenThrough: Instant?) {
        if (characterId.isBlank() || seenThrough == null) return
        synchronized(lock) {
            val current = mutableStates.value[characterId] ?: return
            val nextSeen = listOfNotNull(current.lastSeenAt, seenThrough).maxOrNull()
            mutableStates.value = mutableStates.value + (characterId to current.copy(lastSeenAt = nextSeen))
            persistLocked()
        }
    }

    /** Role reset starts a new subjective life without erasing other roles' witness history. */
    fun resetCharacter(characterId: String, now: Instant = Instant.now()) {
        if (characterId.isBlank()) return
        synchronized(lock) {
            expiryJobs.remove(characterId)?.cancel()
            mutableStates.value = mutableStates.value + (
                characterId to CompanionOnlineState(
                    characterId = characterId,
                    onlineUntil = now,
                    reason = CompanionOnlineReason.BackgroundPerception,
                    lastSeenAt = now,
                    historyFloorAt = now,
                )
            )
            persistLocked()
        }
    }

    private fun scheduleExpiryLocked(characterId: String) {
        expiryJobs.remove(characterId)?.cancel()
        val until = mutableStates.value[characterId]?.onlineUntil ?: return
        val waitMillis = Duration.between(Instant.now(), until).toMillis().coerceAtLeast(0L)
        expiryJobs[characterId] = scope.launch {
            delay(waitMillis + 50L)
            synchronized(lock) {
                val current = mutableStates.value[characterId] ?: return@synchronized
                if (!current.isOnline()) {
                    // Change the stored value at the exact expiry so Compose updates immediately.
                    mutableStates.value = mutableStates.value + (
                        characterId to current.copy(onlineUntil = Instant.now().minusMillis(1L))
                    )
                    persistLocked()
                    expiryJobs.remove(characterId)
                }
            }
        }
    }

    private fun persistLocked() {
        prefs?.edit()
            ?.putString(KEY_STATES, encodeStates(mutableStates.value))
            ?.putString(KEY_GROUP_FOCUS, encodeGroupFocus(groupFocusUntil))
            ?.apply()
    }

    private fun encodeStates(states: Map<String, CompanionOnlineState>): String = JSONArray().apply {
        states.values.forEach { state ->
            put(JSONObject().apply {
                put("characterId", state.characterId)
                put("onlineUntil", state.onlineUntil.toEpochMilli())
                put("reason", state.reason.name)
                state.lastSeenAt?.let { put("lastSeenAt", it.toEpochMilli()) }
                state.historyFloorAt?.let { put("historyFloorAt", it.toEpochMilli()) }
            })
        }
    }.toString()

    private fun latestRelevantChatAt(characterId: String): Instant? =
        MigratedDomainStores.chat.conversations.value.asSequence()
            .filter { conversation ->
                conversation.groupChat?.members?.any { it.characterId == characterId } == true ||
                    (conversation.groupChat == null && conversation.characterId == characterId)
            }
            .flatMap { conversation -> MigratedDomainStores.chat.messages(conversation.id).value.asSequence() }
            .filter { it.status == LuluChatMessage.Status.Sent }
            .maxOfOrNull(LuluChatMessage::createdAt)

    private fun decodeStates(raw: String?): Map<String, CompanionOnlineState> = runCatching {
        val array = JSONArray(raw ?: "[]")
        buildMap {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val characterId = item.optString("characterId").trim()
                if (characterId.isBlank()) continue
                put(
                    characterId,
                    CompanionOnlineState(
                        characterId = characterId,
                        onlineUntil = Instant.ofEpochMilli(item.optLong("onlineUntil")),
                        reason = runCatching { CompanionOnlineReason.valueOf(item.optString("reason")) }
                            .getOrDefault(CompanionOnlineReason.BackgroundPerception),
                        lastSeenAt = item.optLong("lastSeenAt").takeIf { it > 0L }?.let(Instant::ofEpochMilli),
                        historyFloorAt = item.optLong("historyFloorAt").takeIf { it > 0L }?.let(Instant::ofEpochMilli),
                    ),
                )
            }
        }
    }.getOrDefault(emptyMap())

    private fun encodeGroupFocus(values: Map<String, Instant>): String = JSONObject().apply {
        values.forEach { (groupId, until) -> put(groupId, until.toEpochMilli()) }
    }.toString()

    private fun decodeGroupFocus(raw: String?): Map<String, Instant> = runCatching {
        val json = JSONObject(raw ?: "{}")
        buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                json.optLong(key).takeIf { it > 0L }?.let { put(key, Instant.ofEpochMilli(it)) }
            }
        }
    }.getOrDefault(emptyMap())
}
