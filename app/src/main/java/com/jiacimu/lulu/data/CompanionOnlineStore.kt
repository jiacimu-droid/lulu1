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
    SelfChosenExtension,
    SelfChosenSleep,
}

data class CompanionOnlineState(
    val characterId: String,
    val onlineUntil: Instant,
    val reason: CompanionOnlineReason,
    val lastSeenAt: Instant? = null,
    val historyFloorAt: Instant? = null,
    val seenIdsAtLastSeenAt: Set<String> = emptySet(),
) {
    fun isOnline(now: Instant = Instant.now()): Boolean =
        CompanionPresenceStore.isInCall(characterId) || onlineUntil.isAfter(now)
}

data class CompanionUnreadSnapshot(
    val text: String,
    val newestAt: Instant?,
    val conversationIds: Set<String> = emptySet(),
    val newestIds: Set<String> = emptySet(),
    /** All user photos in the unread turn, not merely the latest text bubble. */
    val imageEvidenceIds: Set<String> = emptySet(),
    /** Actual evidence in this frozen snapshot, not only IDs at its last timestamp. */
    val messageIds: Set<String> = emptySet(),
)

/**
 * One shared, durable definition of character online presence.
 *
 * A wake-up means five minutes of guaranteed perception, never a guaranteed reply. While a
 * character is online, new relevant chat events schedule an independent perception for that role.
 * User Moments are perceived through MomentsStore by the same online-state predicate.
 *
 * Every five-minute boundary is a decision opportunity, not an automatic forced logout
 * or an automatic renewal. The shared autonomous decision can choose one more five-minute
 * window with a concrete personal reason, or can go offline while preserving its concerns.
 * Each exact boundary is claimed at most once, even if foreground and WorkManager race.
 */
object CompanionOnlineStore {
    private const val PREFS_NAME = "lulu_companion_online_v1"
    private const val KEY_STATES = "states"
    private const val KEY_GROUP_FOCUS = "group_focus"
    private const val DELIVERY_MIGRATION_KEY = "delivered_reply_evidence_v1"
    private const val DELIVERY_KEY_PREFIX = "delivered_reply_ids:"
    private const val DELIVERY_CURSOR_PREFIX = "delivered_reply_cursor:"
    private const val MAX_DELIVERY_EVIDENCE = 600
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

    private fun deliveredKey(characterId: String) = "${DELIVERY_KEY_PREFIX}$characterId"
    private fun deliveryCursorKey(characterId: String, conversationId: String) =
        "${DELIVERY_CURSOR_PREFIX}$characterId:$conversationId"

    private data class DeliveryCursor(val throughMillis: Long, val idsAtBoundary: Set<String>)

    private fun deliveryCursorLocked(characterId: String, conversationId: String): DeliveryCursor {
        val json = runCatching {
            JSONObject(prefs?.getString(deliveryCursorKey(characterId, conversationId), "{}").orEmpty())
        }.getOrNull() ?: return DeliveryCursor(Long.MIN_VALUE, emptySet())
        val ids = json.optJSONArray("ids")
        return DeliveryCursor(
            json.optLong("throughMillis", Long.MIN_VALUE),
            (0 until (ids?.length() ?: 0)).mapNotNull { i ->
                ids?.optString(i)?.takeIf(String::isNotBlank)
            }.toSet(),
        )
    }

    private fun cursorJson(cursor: DeliveryCursor) = JSONObject().apply {
        put("throughMillis", cursor.throughMillis)
        put("ids", JSONArray(cursor.idsAtBoundary.toList()))
    }.toString()

    private fun updatedDeliveryCursor(
        previous: DeliveryCursor, confirmed: List<LuluChatMessage>,
    ): DeliveryCursor {
        if (confirmed.isEmpty()) return previous
        val latestMillis = confirmed.maxOf { it.createdAt.toEpochMilli() }
        return when {
            latestMillis > previous.throughMillis -> DeliveryCursor(
                latestMillis, confirmed.filter {
                    it.createdAt.toEpochMilli() == latestMillis
                }.map(LuluChatMessage::id).toSet())
            latestMillis == previous.throughMillis -> previous.copy(
                idsAtBoundary = previous.idsAtBoundary +
                    confirmed.filter { it.createdAt.toEpochMilli() == latestMillis }
                        .map(LuluChatMessage::id))
            else -> previous
        }
    }

    private fun deliveredIdsLocked(characterId: String): List<String> {
        val raw = prefs?.getString(deliveredKey(characterId), "[]").orEmpty().ifBlank { "[]" }
        val json = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until json.length()).mapNotNull { index ->
            json.optString(index).takeIf(String::isNotBlank)
        }.distinct()
    }

    /**
     * Confirm only the concrete user bubbles frozen at the start of a reply.
     * A newer bubble arriving while the character is typing MUST remain unread,
     * even when it appears between two delivered semantic bubbles.
     */
    internal fun acknowledgeDeliveredReply(
        characterId: String, conversationId: String, userMessageIds: Set<String>,
    ): Boolean {
        if (characterId.isBlank() || userMessageIds.isEmpty()) return false
        val conversation = MigratedDomainStores.chat.conversations.value
            .firstOrNull { it.id == conversationId } ?: return false
        if (conversation.groupChat?.members?.any { it.characterId == characterId } != true &&
            (conversation.groupChat != null || conversation.characterId != characterId)) return false
        val confirmed = MigratedDomainStores.chat.messages(conversationId).value
            .filter { it.id in userMessageIds &&
                it.status == LuluChatMessage.Status.Sent &&
                it.sender == LuluChatMessage.Sender.User }
        if (confirmed.isEmpty()) return false
        return synchronized(lock) {
            val p = prefs ?: return@synchronized false
            val next = (deliveredIdsLocked(characterId) + confirmed.map(LuluChatMessage::id))
                .distinct().takeLast(MAX_DELIVERY_EVIDENCE)
            val cursor = updatedDeliveryCursor(
                deliveryCursorLocked(characterId, conversationId), confirmed)
            // The bounded ID cache is only an optimization. This durable
            // per-conversation checkpoint prevents old answered bubbles from
            // becoming unread again when the cache eventually evicts them.
            p.edit().putString(deliveredKey(characterId), JSONArray(next).toString())
                .putString(deliveryCursorKey(characterId, conversationId), cursorJson(cursor))
                .commit()
        }
    }

    /**
     * Pre-upgrade replies were delivered before explicit evidence receipts existed.
     * Backfill conservatively only the user messages before the FIRST bubble of
     * the last completed role turn. Never swallow user bubbles interleaved into
     * that outgoing multi-bubble turn or those sent after it.
     */
    private fun migrateDeliveredRepliesLocked() {
        val p = prefs ?: return
        if (p.getBoolean(DELIVERY_MIGRATION_KEY, false)) return
        val recovered = mutableMapOf<String, MutableList<String>>()
        val recoveredCursors = mutableMapOf<Pair<String, String>, List<LuluChatMessage>>()
        MigratedDomainStores.chat.conversations.value.forEach { conversation ->
            val messages = MigratedDomainStores.chat.messages(conversation.id).value
            val characters = conversation.groupChat?.members?.map(LuluGroupMember::characterId)
                ?: listOf(conversation.characterId)
            characters.distinct().filter(String::isNotBlank).forEach { characterId ->
                val last = messages.indexOfLast {
                    it.status == LuluChatMessage.Status.Sent &&
                        it.sender == LuluChatMessage.Sender.Character &&
                        (it.authorCharacterId ?: conversation.characterId) == characterId
                }
                if (last < 0) return@forEach
                val batchId = messages[last].replyBatchId
                val first = if (!batchId.isNullOrBlank()) messages.indexOfFirst {
                    it.replyBatchId == batchId &&
                        it.sender == LuluChatMessage.Sender.Character &&
                        (it.authorCharacterId ?: conversation.characterId) == characterId
                }.takeIf { it in 0..last } ?: last else last
                // An interleaved user turn cannot be proved handled by this old reply.
                if (messages.subList(first + 1, last + 1).any {
                        it.sender == LuluChatMessage.Sender.User }) return@forEach
                val answered = messages.take(first).filter {
                    it.status == LuluChatMessage.Status.Sent &&
                        it.sender == LuluChatMessage.Sender.User
                }
                recovered.getOrPut(characterId) { mutableListOf() }
                    .addAll(answered.map(LuluChatMessage::id))
                recoveredCursors[characterId to conversation.id] = answered
            }
        }
        val editor = p.edit()
        recovered.forEach { (characterId, ids) ->
            val combined = (deliveredIdsLocked(characterId) + ids)
                .distinct().takeLast(MAX_DELIVERY_EVIDENCE)
            editor.putString(deliveredKey(characterId), JSONArray(combined).toString())
        }
        recoveredCursors.forEach { (key, answered) ->
            val (characterId, conversationId) = key
            val cursor = updatedDeliveryCursor(
                deliveryCursorLocked(characterId, conversationId), answered)
            if (cursor.throughMillis != Long.MIN_VALUE) {
                editor.putString(deliveryCursorKey(characterId, conversationId), cursorJson(cursor))
            }
        }
        check(editor.putBoolean(DELIVERY_MIGRATION_KEY, true).commit()) {
            "旧聊天回复状态迁移失败"
        }
    }

    /** The original messages must still be pending after an asynchronous model call. */
    internal fun hasOriginalUnreadEvidence(
        original: CompanionUnreadSnapshot, current: CompanionUnreadSnapshot,
    ): Boolean = original.messageIds.any { it in current.messageIds }

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
            migrateDeliveredRepliesLocked()
            loaded.values.filter { it.isOnline(now) }.forEach { scheduleLifePulseLocked(it.characterId) }
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
            scheduleLifePulseLocked(characterId)
            persistLocked()
            scheduleExpiryLocked(characterId)
        }
        if (perceiveNow) {
            appContext?.let { ProactivePerceptionScheduler.scheduleOnline(it, characterId, trigger, collectMessages = true) }
        }
    }

    /** One real role-selected sleep action. Old expiry jobs see an obsolete lease. */
    fun goOfflineByChoice(characterId: String, now: Instant = Instant.now()): Boolean =
        synchronized(lock) {
            if (characterId.isBlank() || CompanionPresenceStore.isInCall(characterId))
                return@synchronized false
            val current = mutableStates.value[characterId] ?: return@synchronized false
            if (!current.isOnline(now)) return@synchronized true
            mutableStates.value = mutableStates.value + (characterId to current.copy(
                onlineUntil = now, reason = CompanionOnlineReason.SelfChosenSleep,
            ))
            // Do not cancel the active model worker that is executing this tool.
            // A future genuine user message can still wake the character.
            lifePulseJobs.remove(characterId)?.cancel()
            expiryJobs.remove(characterId)?.cancel()
            persistLocked()
            true
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
            scheduleLifePulseLocked(characterId)
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
                scheduleLifePulseLocked(characterId)
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
        if (conversation.groupChat == null && message.sender == LuluChatMessage.Sender.User) {
            // A real incoming user signal supersedes the pre-arranged goal
            // check, including inside a voice call. It is NOT proof the
            // longer-term goal is achieved or the user has forgiven the role.
            CharacterLifeStore.observeUserGoalEvidence(
                conversation.characterId, message.id, message.content, message.createdAt)
        }
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
            // A connected call owns text replies to its own private conversation.
            // Do not start a second asynchronous QQ reply for the same message.
            if (conversation.groupChat == null &&
                com.jiacimu.lulu.LuluVoiceCallSession.isActiveForChat(conversation.id)) {
                recordActivity(conversation.characterId, now)
                return
            }
            appContext?.let { context ->
                readers.distinct().filter(String::isNotBlank).forEach { reader ->
                    OnlineChatBatchStore.onUserBubble(context, reader, atMillis = now.toEpochMilli())
                }
            }
            // Incoming user content is an independent perception event, even when
            // the character was OFFLINE. The five-minute clock is an online lease,
            // never a cooldown for reading new messages. Do not enqueue here:
            // recipients below schedule one debounced worker for each reader.
            readers.distinct().filter(String::isNotBlank).forEach { reader ->
                wakeCharacter(
                    characterId = reader,
                    reason = CompanionOnlineReason.NewActivity,
                    trigger = "收到真实用户新消息",
                    perceiveNow = false,
                    now = now,
                )
            }
        }

        // Life receipts do not renew the autonomous window or recursively enqueue another model.
        // A bounded wake pulse lets the role reconsider after an activity, including choosing silence.
        if (message.sender == LuluChatMessage.Sender.System) return

        // Sending a message is a real expression, NOT an implicit decision to
        // stay online another five minutes. The expiry decision belongs to
        // the character's cognition, not to a chat append side effect.
        val members = conversation.groupChat?.members.orEmpty().map(LuluGroupMember::characterId).distinct()
        val recipients: List<String>
        synchronized(lock) {
            val focused = groupFocusUntil[conversation.id]?.isAfter(now) == true
            if (focused && members.isNotEmpty() &&
                message.sender == LuluChatMessage.Sender.User) {
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
            if (conversation.groupChat == null &&
                message.sender == LuluChatMessage.Sender.User &&
                isOnline(conversation.characterId, now)) {
                val current = mutableStates.value[conversation.characterId]
                if (current != null) {
                    val updated = current.copy(
                        onlineUntil = maxOf(current.onlineUntil, now.plus(onlineDuration)),
                        reason = CompanionOnlineReason.NewActivity,
                    )
                    mutableStates.value = mutableStates.value + (conversation.characterId to updated)
                    scheduleLifePulseLocked(conversation.characterId)
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
        val acknowledged = synchronized(lock) { deliveredIdsLocked(characterId).toHashSet() }
        val state = mutableStates.value[characterId]
        val after = listOfNotNull(state?.lastSeenAt, state?.historyFloorAt).maxOrNull()
        val events = MigratedDomainStores.chat.conversations.value.asSequence()
            .filter { conversation ->
                conversation.groupChat?.members?.any { it.characterId == characterId } == true ||
                    (conversation.groupChat == null && conversation.characterId == characterId && !conversation.id.endsWith("-study-focus"))
            }
            .flatMap { conversation ->
                val deliveryCursor = synchronized(lock) {
                    deliveryCursorLocked(characterId, conversation.id)
                }
                MigratedDomainStores.chat.messages(conversation.id).value.asSequence()
                    .filter { message ->
                        message.status == LuluChatMessage.Status.Sent &&
                            !message.id.startsWith("voice-") &&
                            message.sender != LuluChatMessage.Sender.System &&
                            message.authorCharacterId != characterId &&
                            message.id !in acknowledged &&
                            (message.createdAt.toEpochMilli() > deliveryCursor.throughMillis ||
                                (message.createdAt.toEpochMilli() == deliveryCursor.throughMillis &&
                                    message.id !in deliveryCursor.idsAtBoundary)) &&
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
            events.filter { (_, message) ->
                message.sender == LuluChatMessage.Sender.User &&
                    com.jiacimu.lulu.decodeQqChatImage(message.content)?.sticker == false
            }.mapTo(mutableSetOf()) { it.second.id },
            events.mapTo(mutableSetOf()) { it.second.id },
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
            val editor = prefs?.edit()?.remove(deliveredKey(characterId))
            MigratedDomainStores.chat.conversations.value.forEach { conversation ->
                editor?.remove(deliveryCursorKey(characterId, conversation.id))
            }
            editor?.commit()
            persistLocked()
        }
    }

    /**
     * The role may go offline in five minutes, but its newly saved unresolved
     * thoughts should still receive one durable later decision opportunity.
     * This is called only AFTER the outgoing chat batch has actually been sent.
     * It does not extend online time or automatically contact the user.
     */
    fun schedulePersistedConcernReview(characterId: String, now: Instant = Instant.now()) {
        val app = appContext ?: return
        if (!CharacterInnerLifeStore.needsPostOnlineReflection(characterId, now)) return
        val until = mutableStates.value[characterId]?.onlineUntil
        val remaining = until?.let { Duration.between(now, it).toMillis().coerceAtLeast(0L) } ?: 0L
        runCatching {
            ProactivePerceptionScheduler.scheduleEmotionalAftercare(
                app, characterId, delayMillis = remaining + 90_000L,
            )
        }
    }

    /**
     * Only the exact, still-current five-minute boundary may be evaluated.
     * Persist the claim BEFORE any paid model request. An older queued worker
     * cannot renew a character who received another message or a newer wake.
     */
    fun claimOnlineExpiry(characterId: String, expectedUntilMs: Long,
                          now: Instant = Instant.now()): Boolean = synchronized(lock) {
        val state = mutableStates.value[characterId] ?: return@synchronized false
        if (prefs == null || expectedUntilMs <= 0L ||
            state.onlineUntil.toEpochMilli() != expectedUntilMs ||
            now.isBefore(state.onlineUntil) ||
            now.isAfter(state.onlineUntil.plus(Duration.ofMinutes(3))) ||
            CompanionPresenceStore.isInCall(characterId) ||
            !ProactivePerceptionPolicyStore.get(characterId).enabled) return@synchronized false
        val key = "expiry_decision_claim:$characterId"
        if (prefs?.getLong(key, Long.MIN_VALUE) == expectedUntilMs) return@synchronized false
        prefs?.edit()?.putLong(key, expectedUntilMs)?.commit() == true
    }

    /** Apply ONLY the character's own 'continue' decision, never a timer heuristic. */
    fun extendByOwnChoice(characterId: String, expectedUntilMs: Long, reason: String,
                          now: Instant = Instant.now()): Boolean = synchronized(lock) {
        val current = mutableStates.value[characterId] ?: return@synchronized false
        if (reason.isBlank() || current.onlineUntil.toEpochMilli() != expectedUntilMs ||
            now.isAfter(current.onlineUntil.plus(Duration.ofMinutes(3))) ||
            prefs?.getLong("expiry_decision_claim:$characterId", Long.MIN_VALUE) != expectedUntilMs ||
            CompanionPresenceStore.isInCall(characterId)) return@synchronized false
        // New activity may already have awakened the character. If so, the stale
        // expiry cannot overwrite that session's purpose or expiry.
        if (current.isOnline(now)) return@synchronized false
        mutableStates.value = mutableStates.value + (characterId to current.copy(
            onlineUntil = now.plus(onlineDuration),
            reason = CompanionOnlineReason.SelfChosenExtension,
        ))
        persistLocked()
        scheduleLifePulseLocked(characterId)
        scheduleExpiryLocked(characterId)
        true
    }

    /**
     * Event-driven online awareness: new messages, true world/device events
     * and explicit commitments already use independent event/AlarmManager
     * scheduling. The five-minute expiry is the sole no-new-event appraisal
     * and lets the character choose to continue or sleep.
     *
     * Do not buy an extra generic model call every 150 seconds while the user
     * is asleep, or wake repeatedly just because a call remains connected.
     */
    private fun scheduleLifePulseLocked(characterId: String) {
        lifePulseJobs.remove(characterId)?.cancel()
    }

    private fun scheduleExpiryLocked(characterId: String) {
        expiryJobs.remove(characterId)?.cancel()
        val until = mutableStates.value[characterId]?.onlineUntil ?: return
        // On cold start all existing expired windows (including the original
        // default offline state) are NOT fresh five-minute sessions. Their
        // previous durable worker may resume, but initialization itself must
        // never create a paid expiry model call.
        if (!until.isAfter(Instant.now())) return
        val waitMillis = Duration.between(Instant.now(), until).toMillis().coerceAtLeast(0L)
        // Persist a backup deadline for when Android kills the app process.
        // Each work has a boundary-specific identity; stale work only checks
        // the timestamp and never asks the model to renew an old session.
        if (until.isAfter(Instant.now())) {
            appContext?.let { context ->
                runCatching {
                    ProactivePerceptionScheduler.scheduleOnlineExpiryDecision(
                        context, characterId, until.toEpochMilli(),
                    )
                }
            }
        }
        expiryJobs[characterId] = scope.launch {
            delay(waitMillis + 50L)
            val ended = synchronized(lock) {
                val current = mutableStates.value[characterId]
                if (current == null || current.isOnline()) false else {
                    mutableStates.value = mutableStates.value + (
                        // Keep the original persisted deadline: the background
                        // expiry worker must still recognize this same boundary.
                        characterId to current
                    )
                    lifePulseJobs.remove(characterId)?.cancel()
                    persistLocked()
                    expiryJobs.remove(characterId)
                    true
                }
            }
            if (ended) {
                appContext?.let { context ->
                    // Fast foreground path, competing safely with the durable work.
                    scope.launch {
                        runCatching {
                            ProactivePerceptionRuntime.runDueCycle(
                                context, trigger = "五分钟在线到期，自主决定继续醒着或休眠",
                                targetCharacterId = characterId, force = true,
                                preserveOffline = true, onlineExpiryAtMillis = until.toEpochMilli(),
                            )
                        }
                    }
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
