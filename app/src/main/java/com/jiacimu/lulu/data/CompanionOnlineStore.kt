package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.qqForwardContextText
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
    ScheduledCommitment,
}

data class CompanionOnlineState(
    val characterId: String,
    val onlineUntil: Instant,
    val reason: CompanionOnlineReason,
    val lastSeenAt: Instant? = null,
    val historyFloorAt: Instant? = null,
    val seenIdsAtLastSeenAt: Set<String> = emptySet(),
) {
    fun isOnline(now: Instant = Instant.now()): Boolean = onlineUntil.isAfter(now)
}

data class CompanionUnreadSnapshot(
    val text: String,
    val newestAt: Instant?,
    val conversationIds: Set<String> = emptySet(),
    val newestIds: Set<String> = emptySet(),
)

/**
 * One shared, durable definition of character online presence.
 *
 * A wake-up means five minutes of guaranteed perception, never a guaranteed reply. While a
 * character is online, new relevant chat events schedule an independent perception for that role.
 * User Moments are perceived through MomentsStore by the same online-state predicate.
 *
 * A fixed five-minute wake pulse offers further choices without recursively extending itself. This lets "go somewhere" naturally become "do something there" or lets a finished
 * reading/game become a fresh decision about sharing, journaling or another activity without
 * turning one wake into an unbounded model-call loop.
 */
object CompanionOnlineStore {
    private const val PREFS_NAME = "lulu_companion_online_v1"
    private const val KEY_STATES = "states"
    private const val KEY_GROUP_FOCUS = "group_focus"
    private val onlineDuration: Duration = Duration.ofMinutes(5)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val expiryJobs = mutableMapOf<String, Job>()
    private val lifePulseJobs = mutableMapOf<String, Job>()
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
            loaded.values.filter { it.isOnline(now) }.forEach { scheduleLifePulseLocked(it.characterId, it.onlineUntil) }
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
            scheduleLifePulseLocked(characterId, state.onlineUntil)
            persistLocked()
            scheduleExpiryLocked(characterId)
        }
        if (perceiveNow) {
            appContext?.let { ProactivePerceptionScheduler.scheduleOnline(it, characterId, trigger, collectMessages = true) }
        }
    }

    fun recordActivity(characterId: String, now: Instant = Instant.now()) {
        if (characterId.isBlank()) return
        synchronized(lock) {
            val previous = mutableStates.value[characterId]
                ?: CompanionOnlineState(characterId, now, CompanionOnlineReason.NewActivity)
            val updated = previous.copy(
                onlineUntil = maxOf(previous.onlineUntil, now.plus(onlineDuration)),
                reason = CompanionOnlineReason.NewActivity,
            )
            mutableStates.value = mutableStates.value + (characterId to updated)
            scheduleLifePulseLocked(characterId, updated.onlineUntil)
            persistLocked()
            scheduleExpiryLocked(characterId)
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
                scheduleLifePulseLocked(characterId, state.onlineUntil)
                scheduleExpiryLocked(characterId)
            }
            persistLocked()
        }
        memberIds.forEach { characterId ->
            appContext?.let { ProactivePerceptionScheduler.scheduleOnline(it, characterId, trigger, collectMessages = true) }
        }
    }

    /** Called after a chat or private activity event is durably appended. */
    fun onConversationMessage(conversation: LuluConversation, message: LuluChatMessage) {
        if (message.status != LuluChatMessage.Status.Sent) return
        if (message.id.startsWith("voice-")) {
            val participants = conversation.groupChat?.members?.map(LuluGroupMember::characterId)
                ?: listOf(conversation.characterId)
            participants.forEach { recordActivity(it, message.createdAt) }
            return // Phone speech never enters the text reply queue.
        }
        val now = message.createdAt
        if (message.sender == LuluChatMessage.Sender.User) {
            // Open the quiet window at the FIRST bubble even if the character is
            // still offline. Pressing the wake button later must reuse this
            // timestamp rather than pretending a new message arrived at wake.
            val readers = conversation.groupChat?.members.orEmpty().map(LuluGroupMember::characterId)
                .takeIf { it.isNotEmpty() } ?: listOf(conversation.characterId)
            // A stable spoken-name preference is a relationship fact, distinct from
            // a character's private contact remark. Only the addressee's genuine
            // one-to-one user message can update it.
            if (conversation.groupChat == null) {
                CharacterAddressPreference.observeUserMessage(
                    conversation.characterId, message.content, message.id
                )
            }
            appContext?.let { context ->
                readers.distinct().filter(String::isNotBlank).forEach { reader ->
                    OnlineChatBatchStore.onUserBubble(context, reader, atMillis = now.toEpochMilli())
                }
            }
        }

        // Life receipts do not renew the autonomous window or recursively enqueue another model.
        // A bounded wake pulse lets the role reconsider after an activity, including choosing silence.
        if (message.sender == LuluChatMessage.Sender.System) return

        if (message.sender == LuluChatMessage.Sender.Character) {
            recordActivity(message.authorCharacterId ?: conversation.characterId, now)
        }
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
            if (conversation.groupChat == null && isOnline(conversation.characterId, now)) {
                val current = mutableStates.value[conversation.characterId]
                if (current != null) {
                    val updated = current.copy(
                        onlineUntil = maxOf(current.onlineUntil, now.plus(onlineDuration)),
                        reason = CompanionOnlineReason.NewActivity,
                    )
                    mutableStates.value = mutableStates.value + (conversation.characterId to updated)
                    scheduleLifePulseLocked(conversation.characterId, updated.onlineUntil)
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
                    collectMessages = true,
                    requiresUnread = true,
                )
            }
        }
    }

    /** Same-millisecond messages sent during a multi-bubble reply cannot be lost. */
    internal fun isUnreadAtCursor(
        id: String, timestamp: Instant, after: Instant?,
        cursorAt: Instant?, seenIds: Set<String>,
    ): Boolean = after == null || timestamp.isAfter(after) ||
        (timestamp == after && cursorAt == after && id !in seenIds)

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
                            !message.id.startsWith("voice-") &&
                            message.sender != LuluChatMessage.Sender.System &&
                            message.authorCharacterId != characterId &&
                            isUnreadAtCursor(message.id, message.createdAt, after,
                                state?.lastSeenAt, state?.seenIdsAtLastSeenAt.orEmpty())
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
            "- ${message.createdAt}｜$scene｜$speaker：${qqForwardContextText(message.content).take(600)}"
        }
        val newest = events.maxOfOrNull { (_, message) -> message.createdAt }
        return CompanionUnreadSnapshot(
            text, newest, events.mapTo(mutableSetOf()) { it.first.id },
            events.filter { it.second.createdAt == newest }.mapTo(mutableSetOf()) { it.second.id },
        )
    }

    /** A snapshot is frozen BEFORE executing/model output; only its actual IDs are marked read. */
    fun markSeen(characterId: String, snapshot: CompanionUnreadSnapshot) {
        val seenThrough = snapshot.newestAt ?: return
        if (characterId.isBlank()) return
        synchronized(lock) {
            val current = mutableStates.value[characterId] ?: return
            if (current.lastSeenAt != null && current.lastSeenAt.isAfter(seenThrough)) return
            val seenIds = if (current.lastSeenAt == seenThrough)
                current.seenIdsAtLastSeenAt + snapshot.newestIds else snapshot.newestIds
            mutableStates.value = mutableStates.value + (characterId to current.copy(
                lastSeenAt = seenThrough, seenIdsAtLastSeenAt = seenIds.take(100).toSet(),
            ))
            persistLocked()
        }
    }

    fun resetCharacter(characterId: String, now: Instant = Instant.now()) {
        if (characterId.isBlank()) return
        synchronized(lock) {
            expiryJobs.remove(characterId)?.cancel()
            lifePulseJobs.remove(characterId)?.cancel()
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

    /** A fixed wake window: autonomous actions cannot keep their own pulse alive indefinitely. */
    private fun scheduleLifePulseLocked(characterId: String, until: Instant) {
        lifePulseJobs.remove(characterId)?.cancel()
        val context = appContext ?: return
        lifePulseJobs[characterId] = scope.launch {
            // Allow a newly online character to start living after the user's
            // initial quiet window. Subsequent pulses are gentle, not an action quota.
            delay(25_000L)
            while (until.isAfter(Instant.now()) && isOnline(characterId)) {
                if (CompanionPresenceStore.isInCall(characterId) ||
                    MigratedDomainStores.chat.conversations.value.any {
                        it.characterId == characterId && ChatGenerationActivity.isRunning(it.id)
                    }) {
                    delay(55_000L)
                    continue
                }
                // A user-message batch keeps its first-bubble deadline; the scheduler never bypasses it.
                // A separate KEEP work item prevents a backlog of stale online
                // chat revisions. Waits for unfinished user-message batches.
                ProactivePerceptionScheduler.scheduleOnlineReflection(context, characterId,
                    "在线生活继续：没有用户在说话时，也可以依自己的兴趣读书、散步、找朋友、布置房间、改备注或网名，亦可安静待着",
                    delayMillis = 0L)
                delay(55_000L)
            }
        }
    }

    private fun scheduleExpiryLocked(characterId: String) {
        expiryJobs.remove(characterId)?.cancel()
        val until = mutableStates.value[characterId]?.onlineUntil ?: return
        val waitMillis = Duration.between(Instant.now(), until).toMillis().coerceAtLeast(0L)
        expiryJobs[characterId] = scope.launch {
            delay(waitMillis + 50L)
            val ended = synchronized(lock) {
                val current = mutableStates.value[characterId]
                if (current == null || current.isOnline()) false else {
                    mutableStates.value = mutableStates.value + (
                        characterId to current.copy(onlineUntil = Instant.now().minusMillis(1L))
                    )
                    lifePulseJobs.remove(characterId)?.cancel()
                    persistLocked()
                    expiryJobs.remove(characterId)
                    true
                }
            }
            if (ended) {
                appContext?.let { context ->
                    runCatching { ProactivePerceptionScheduler.scheduleEmotionalAftercare(context, characterId) }
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
                if (state.seenIdsAtLastSeenAt.isNotEmpty())
                    put("seenIdsAtLastSeenAt", JSONArray(state.seenIdsAtLastSeenAt.toList()))
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
                        seenIdsAtLastSeenAt = item.optJSONArray("seenIdsAtLastSeenAt")?.let { ids ->
                            (0 until ids.length()).mapNotNull { i ->
                                ids.optString(i).takeIf(String::isNotBlank)
                            }.toSet()
                        }.orEmpty(),
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
