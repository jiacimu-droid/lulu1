package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * Presentation-only overlap detection. It never infers what the role wanted,
 * alters world evidence, or erases persisted history. Exact word sequences
 * are often reordered in a model description of the SAME confirmed motion.
 */
internal object PresenceFieldBoundary {
    private fun normalize(text: String): String =
        text.replace(Regex("[\\s，。！？!?、；;：:·‘’“”]+"), "")

    private fun grams(text: String, size: Int): Set<String> =
        if (text.length < size) emptySet() else
            text.windowed(size, 1).toSet()

    private fun overlap(first: String, second: String, size: Int): Double {
        val a = grams(first, size)
        val b = grams(second, size)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / minOf(a.size, b.size)
    }

    fun repeatsConfirmedAction(status: String, action: String): Boolean {
        val a = normalize(status)
        val b = normalize(action)
        if (a.isBlank() || b.isBlank()) return false
        if (a == b || a.contains(b) || b.contains(a)) return true
        // Only suppress very strong overlap between a sustained-state field
        // and a verified action description; different aims or feelings survive.
        return minOf(a.length, b.length) >= 12 &&
            overlap(a, b, 3) >= 0.55 && overlap(a, b, 2) >= 0.64
    }
}

data class CompanionPresenceState(
    val characterId: String,
    val statusText: String = "",
    val gesture: String = "",
    val innerThought: String = "",
    val mood: String = "",
    val updatedAt: Instant = Instant.EPOCH,
    val source: String = "",
    val lastPerceptionAt: Instant? = null,
    val lastPerceptionNote: String = "",
    val provenanceId: String = "",
    val showInHistory: Boolean = true,
    /** Causal identity of the surfaced heart-voice event; prose may change while cause stays same. */
    val innerThoughtFingerprint: String = "",
    /** Private appraisal details produced only for this very moment, never inherited. */
    val momentReflection: String = "",
)

data class CompanionPresenceMessageAnchor(
    val characterId: String,
    val messageAt: Instant,
    val state: CompanionPresenceState?,
)

/** Current private/visible role presence shared by chat and background perception. */
object CompanionPresenceStore {
    private const val PREFS_NAME = "lulu_companion_presence"
    private const val KEY_STATES = "states_v1"
    private const val KEY_HISTORY = "history_v1"
    private const val KEY_MESSAGE_MOMENTS = "message_moments_v1"
    // Separate diagnostic metadata, NOT an alternate source of character memory.
    // A recovery "started" note must never overwrite evidence of what last failed.
    private const val KEY_LAST_PERCEPTION_FAILURE = "last_perception_failure_v1"
    private val mutableStates = MutableStateFlow<Map<String, CompanionPresenceState>>(emptyMap())
    val states: StateFlow<Map<String, CompanionPresenceState>> = mutableStates.asStateFlow()
    private val mutableHistories = MutableStateFlow<Map<String, List<CompanionPresenceState>>>(emptyMap())
    val histories: StateFlow<Map<String, List<CompanionPresenceState>>> = mutableHistories.asStateFlow()
    private var prefs: android.content.SharedPreferences? = null
    private val messageMoments = linkedMapOf<String, CompanionPresenceState>()

    private val activeCalls = mutableSetOf<String>()

    @Volatile
    private var selectedMessageAnchor: CompanionPresenceMessageAnchor? = null

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mutableStates.value = decode(prefs?.getString(KEY_STATES, null))
        mutableHistories.value = decodeHistory(prefs?.getString(KEY_HISTORY, null))
        messageMoments.clear()
        messageMoments.putAll(decodeMessageMoments(prefs?.getString(KEY_MESSAGE_MOMENTS, null)))
        // Android cannot keep a call alive across a process restart. Repair old current state only;
        // historical message snapshots remain exact records of their original moment.
        mutableStates.value.keys.toList().forEach(::finishCall)
    }

    fun current(characterId: String): CompanionPresenceState? = states.value[characterId]

    @Synchronized
    fun isInCall(characterId: String): Boolean = characterId in activeCalls

    @Synchronized
    fun beginCall(characterId: String) {
        if (characterId.isBlank()) return
        activeCalls += characterId
        update(characterId, "通话中", "正在接听电话", null, null, source = "通话")
    }

    @Synchronized
    fun finishCall(characterId: String) {
        activeCalls -= characterId
        val previous = mutableStates.value[characterId] ?: return
        if (!describesOngoingCall(previous.statusText) && !describesOngoingCall(previous.gesture)) return
        update(characterId,
            if (describesOngoingCall(previous.statusText)) "通话已结束" else previous.statusText,
            if (describesOngoingCall(previous.gesture)) "刚放下电话" else previous.gesture,
            null, null, source = "通话结束")
    }

    internal fun describesOngoingCall(text: String): Boolean {
        if (Regex("已结束|挂断|挂了|放下|结束了|想.*电话|准备.*电话").containsMatchIn(text)) return false
        return Regex(
            "通话中|电话中|正在.*(?:通话|电话)|(?:通话|电话).*正在|接听(?:着)?电话|打着电话|还在.*(?:电话|通话)|" +
                "拿着.*(?:手机|电话).*(?:听|说)|" +
                "(?:拿|举|握|捧|贴|靠).*(?:手机|电话).*(?:耳边|耳侧|耳旁|耳朵)|" +
                "(?:手机|电话).*(?:贴|靠|举|拿).*(?:耳边|耳侧|耳旁|耳朵)"
        ).containsMatchIn(text)
    }

    /**
     * Gesture is a momentary visible action unless its wording clearly describes an activity/posture
     * that can still be true on the next observation. This prevents "摇头/失笑/抬眼" from becoming
     * a permanent pose merely because a later model turn omitted gesture.
     */
    internal fun isSustainedGesture(text: String): Boolean {
        val clean = text.trim()
        if (clean.isBlank()) return false
        if (describesOngoingCall(clean)) return true
        if (Regex("突然|忽然|刚刚|刚才|一下|一瞬|失笑|笑出|摇头|点头|眨眼|挑眉|叹气|抬眼|抬头|皱眉|耸肩|愣住|怔住|抿唇").containsMatchIn(clean)) {
            return false
        }
        return Regex("正在|继续|保持|坐着|躺着|站着|靠着|趴着|抱着|捧着|拿着|阅读|看书|学习|工作|做饭|吃着|喝着|散步|走着|跑着|睡(?:着|觉)?|休息|玩(?:着)?游戏|洗澡|收拾|整理|等着|等待").containsMatchIn(clean)
    }

    private fun inheritedGesture(previous: CompanionPresenceState?, now: Instant): String {
        val old = previous ?: return ""
        val ageMinutes = runCatching { Duration.between(old.updatedAt, now).toMinutes() }.getOrDefault(Long.MAX_VALUE)
        return old.gesture.takeIf { ageMinutes in 0..(8 * 60) && isSustainedGesture(it) }.orEmpty()
    }

    private fun normalizedThought(text: String): String = text
        .lowercase()
        .replace(Regex("[\\s，。！？!?、；;：:“”‘’…~～—_-]+"), "")
        .replace(Regex("^(还是|就是|只是|现在|这会儿|此刻|嗯|唔|好吧)+"), "")

    private fun meaningfullyDifferentThought(previous: String, next: String): Boolean {
        val left = normalizedThought(previous)
        val right = normalizedThought(next)
        if (right.isBlank()) return false
        if (left.isBlank()) return true
        if (left == right || left.contains(right) || right.contains(left)) return false
        if (left.length < 4 || right.length < 4) return true
        val leftPairs = left.windowed(2).toSet()
        val rightPairs = right.windowed(2).toSet()
        val denominator = minOf(leftPairs.size, rightPairs.size).coerceAtLeast(1)
        val overlap = leftPairs.intersect(rightPairs).size.toDouble() / denominator
        return overlap < 0.68
    }

    /**
     * Anchors the next presence dialog to the state that existed when this concrete chat message
     * was sent. New chat turns are recorded one-by-one in history, so different message avatars no
     * longer all open the role's newest state. Older messages fall back to the closest saved state
     * at or before their timestamp instead of incorrectly showing a future state.
     */
    fun selectMessageAnchor(characterId: String, messageAt: Instant, messageId: String? = null) {
        if (characterId.isBlank()) return
        // A message has its own moment; never attach an hours-old psychological
        // state simply because it happened to be the latest known snapshot.
        // The planner can commit presence just after the message timestamp.
        val exact = messageId?.let { messageMoments[it] }?.takeIf { it.characterId == characterId }
        val candidates = buildList {
            addAll(mutableHistories.value[characterId].orEmpty())
            mutableStates.value[characterId]?.let(::add)
        }.distinctBy(CompanionPresenceState::updatedAt)
        val inferred = candidates.mapNotNull { state ->
            val gapMs = runCatching { Duration.between(state.updatedAt, messageAt).toMillis() }.getOrNull()
                ?: return@mapNotNull null
            if (gapMs !in -30_000L..120_000L) null else state to kotlin.math.abs(gapMs)
        }.minByOrNull { it.second }?.first
        selectedMessageAnchor = CompanionPresenceMessageAnchor(
            characterId = characterId,
            messageAt = messageAt,
            state = exact ?: inferred,
        )
    }

    /**
     * Associate an ACTUALLY delivered chat bubble with the already-persisted
     * psychological snapshot. A newly delivered bubble may have identical
     * timestamps to another, so approximate time lookup alone is insufficient.
     * Do not fabricate a thought if the role never produced a nearby snapshot.
     */
    @Synchronized fun bindDeliveredMessage(
        characterId: String, messageId: String, at: Instant, refreshAfterDelivery: Boolean = false,
    ) {
        if (characterId.isBlank() || messageId.isBlank() ||
            (!refreshAfterDelivery && messageId in messageMoments)) return
        val current = mutableStates.value[characterId] ?: return
        val elapsed = runCatching { Duration.between(current.updatedAt, at).toMillis() }.getOrNull()
            ?: return
        if (elapsed !in -30_000L..300_000L) return
        // A history entry is a DELTA and deliberately omits unchanged private
        // state. An avatar is a SNAPSHOT: it must capture what the role really
        // knew/felt at delivery, not the sparse delta (which can be blank).
        // Freeze a copy now so later independent inner-life changes cannot
        // rewrite this message's historical moment.
        messageMoments[messageId] = current.copy()
        while (messageMoments.size > 500) messageMoments.remove(messageMoments.keys.first())
        persist()
    }

    @Synchronized fun forgetDeliveredMessage(messageId: String) {
        if (messageMoments.remove(messageId) != null) persist()
    }

    fun selectedMessageAnchor(characterId: String): CompanionPresenceMessageAnchor? =
        selectedMessageAnchor?.takeIf { it.characterId == characterId }

    fun clearMessageAnchor() {
        selectedMessageAnchor = null
    }

    /** The last REAL failure survives retry startup; no raw model text or keys are kept. */
    @Synchronized
    fun lastPerceptionFailure(characterId: String): Pair<Instant, String>? {
        if (characterId.isBlank()) return null
        val data = prefs?.getString(KEY_LAST_PERCEPTION_FAILURE + "_" + characterId, null)
            ?: return null
        val time = data.substringBefore('|').toLongOrNull() ?: return null
        val message = data.substringAfter('|', "").trim().take(180)
        return if (message.isNotEmpty()) Instant.ofEpochMilli(time) to message else null
    }

    /** Clears old troubleshooting evidence only after a real completed perception. */
    @Synchronized
    fun clearPerceptionFailure(characterId: String) {
        if (characterId.isBlank()) return
        prefs?.edit()?.remove(KEY_LAST_PERCEPTION_FAILURE + "_" + characterId)?.apply()
    }

    /** Records that the perception pipeline actually ran, including skips and failures. */
    @Synchronized
    fun recordPerceptionAttempt(characterId: String, note: String, now: Instant = Instant.now()) {
        if (characterId.isBlank()) return
        val previous = mutableStates.value[characterId]
        val next = (previous ?: CompanionPresenceState(characterId = characterId)).copy(
            lastPerceptionAt = now,
            lastPerceptionNote = note.trim().take(180),
        )
        mutableStates.value = mutableStates.value + (characterId to next)
        if (note.startsWith("感知失败") || note.startsWith("模型请求失败") ||
            note.startsWith("模型回复格式不完整") || note.startsWith("动作失败") ||
            note.startsWith("动作未完成") || note.startsWith("新消息重试排程失败") ||
            note.startsWith("回复处理失败")) {
            prefs?.edit()?.putString(KEY_LAST_PERCEPTION_FAILURE + "_" + characterId,
                now.toEpochMilli().toString() + "|" + note.trim().take(180))?.apply()
        } else if (note.startsWith("感知成功") ||
            note.startsWith("旧消息已由其他回复处理")) {
            clearPerceptionFailure(characterId)
        }
        persist()
    }

    @Synchronized
    fun update(
        characterId: String,
        statusText: String?,
        gesture: String?,
        innerThought: String?,
        mood: String?,
        source: String,
        now: Instant = Instant.now(),
        provenanceId: String = "",
        innerThoughtFingerprint: String? = null,
        momentReflection: String? = null,
    ) {
        if (characterId.isBlank()) return
        val previous = mutableStates.value[characterId]
        val next = CompanionPresenceState(
            characterId = characterId,
            statusText = (statusText.cleanPresence(120) ?: previous?.statusText.orEmpty()).let {
                if (characterId !in activeCalls && describesOngoingCall(it)) "通话已结束" else it
            },
            gesture = (if (gesture == null) inheritedGesture(previous, now) else gesture.cleanPresence(500).orEmpty()).let {
                if (characterId !in activeCalls && describesOngoingCall(it)) "刚放下电话" else it
            },
            innerThought = if (innerThought == null) previous?.innerThought.orEmpty() else innerThought.cleanPresence(1_200).orEmpty(),
            mood = mood.cleanPresence(80) ?: previous?.mood.orEmpty(),
            updatedAt = now,
            source = source.take(40),
            lastPerceptionAt = if (source.contains("感知")) now else previous?.lastPerceptionAt,
            lastPerceptionNote = if (source.contains("感知")) "最近已更新" else previous?.lastPerceptionNote.orEmpty(),
            provenanceId = provenanceId,
            // It is never valid for yesterday's hesitation to become this turn's
            // private thought just because the newest model omitted the field.
            momentReflection = momentReflection.cleanPresence(420).orEmpty(),
            innerThoughtFingerprint = when {
                innerThought == null -> previous?.innerThoughtFingerprint.orEmpty()
                innerThought.isBlank() -> ""
                !innerThoughtFingerprint.isNullOrBlank() -> innerThoughtFingerprint.trim().take(120)
                else -> ""
            },
        )
        if (next.statusText.isBlank() && next.gesture.isBlank() && next.innerThought.isBlank() &&
            next.mood.isBlank() && next.momentReflection.isBlank()) {
            recordPerceptionAttempt(characterId, "模型返回了空状态", now)
            return
        }
        mutableStates.value = mutableStates.value + (characterId to next)
        val visibleChanged = previous == null ||
            previous.statusText != next.statusText ||
            previous.gesture != next.gesture ||
            previous.mood != next.mood
        val thoughtChanged = previous == null ||
            meaningfullyDifferentThought(previous.innerThought, next.innerThought)
        val hasCausalFingerprint = next.innerThoughtFingerprint.isNotBlank()
        val causalFingerprintChanged = hasCausalFingerprint &&
            (previous?.innerThoughtFingerprint.isNullOrBlank() ||
                previous?.innerThoughtFingerprint != next.innerThoughtFingerprint)
        val newHeartVoice = when {
            innerThought == null -> ""
            next.innerThought.isBlank() -> ""
            hasCausalFingerprint -> if (causalFingerprintChanged) next.innerThought else ""
            previous == null || thoughtChanged -> next.innerThought
            else -> ""
        }
        val heartVoiceEventChanged = newHeartVoice.isNotBlank()
        val earlierReflection = mutableHistories.value[characterId].orEmpty()
            .firstOrNull { it.momentReflection.isNotBlank() }?.momentReflection
            ?: previous?.momentReflection.orEmpty()
        val reflectionChanged = next.momentReflection.isNotBlank() &&
            (earlierReflection.isBlank() ||
                meaningfullyDifferentThought(earlierReflection, next.momentReflection))
        val isChatTurn = source.contains("聊天") || source.contains("群聊")
        val lastRecordedAt = mutableHistories.value[characterId]?.firstOrNull()?.updatedAt
        val thoughtHistoryDue = lastRecordedAt == null ||
            runCatching { Duration.between(lastRecordedAt, now).toMinutes() >= 15 }.getOrDefault(true)

        // A sent chat message owns a concrete moment. Background perception is different: a role can
        // stay aware without manufacturing a new historical "heart voice" every minute. Visible state
        // changes are recorded immediately; thought-only background changes are rate-limited and must
        // contain materially new wording. Exact/near repeats remain current state only.
        if (isChatTurn || visibleChanged || reflectionChanged ||
            (heartVoiceEventChanged && thoughtHistoryDue)) {
            val historySnapshot = next.copy(
                innerThought = newHeartVoice,
                momentReflection = if (reflectionChanged) next.momentReflection else "",
                showInHistory = visibleChanged || newHeartVoice.isNotBlank() ||
                    reflectionChanged || !isChatTurn,
            )
            mutableHistories.value = mutableHistories.value +
                (characterId to (listOf(historySnapshot) + mutableHistories.value[characterId].orEmpty())
                    .distinctBy { it.updatedAt }
                    .take(500))
            if (historySnapshot.showInHistory) recordPresenceTimeline(historySnapshot)
        }
        persist()
    }

    @Synchronized
    fun rollbackMeetingProvenance(
        provenanceIds: Set<String>,
        snapshots: Map<String, CompanionPresenceState?>,
    ) {
        if (provenanceIds.isEmpty()) return
        val affectedCharacters = snapshots.keys + mutableStates.value.values
            .filter { it.provenanceId in provenanceIds }
            .map(CompanionPresenceState::characterId)
        val nextHistories = mutableHistories.value.toMutableMap()
        val nextStates = mutableStates.value.toMutableMap()
        affectedCharacters.distinct().forEach { characterId ->
            val remaining = nextHistories[characterId].orEmpty()
                .filterNot { it.provenanceId in provenanceIds }
            nextHistories[characterId] = remaining
            val current = nextStates[characterId]
            if (current?.provenanceId?.let { it in provenanceIds } == true) {
                val restored = snapshots[characterId] ?: remaining.maxByOrNull(CompanionPresenceState::updatedAt)
                if (restored == null) nextStates.remove(characterId) else nextStates[characterId] = restored
            }
            provenanceIds.forEach { provenanceId ->
                SharedExperienceTimeline.deleteEvent("presence-$provenanceId-$characterId")
            }
        }
        mutableHistories.value = nextHistories
        mutableStates.value = nextStates
        persist()
    }

    /** Delete one real presence record, its private snapshot and its derived
     * timeline entry; never remove the user's original chat message. */
    @Synchronized
    fun deleteMoment(characterId: String, at: Instant): Boolean {
        val history = mutableHistories.value[characterId].orEmpty()
        val moments = history.filter { it.updatedAt == at }
        val current = mutableStates.value[characterId]?.takeIf { it.updatedAt == at }
        if (moments.isEmpty() && current == null) return false
        mutableHistories.value = mutableHistories.value +
            (characterId to history.filterNot { it.updatedAt == at })
        if (current != null) mutableStates.value = mutableStates.value - characterId
        messageMoments.entries.removeAll { it.value.characterId == characterId && it.value.updatedAt == at }
        if (selectedMessageAnchor?.characterId == characterId &&
            selectedMessageAnchor?.state?.updatedAt == at) selectedMessageAnchor = null
        persist()
        (moments + listOfNotNull(current)).distinctBy { it.updatedAt }.forEach { moment ->
            val eventId = moment.provenanceId.takeIf(String::isNotBlank)
                ?.let { "presence-$it-$characterId" }
                ?: "presence-$characterId-${moment.updatedAt.toEpochMilli()}"
            SharedExperienceTimeline.deleteEvent(eventId)
            CharacterInnerLifeStore.forgetExactThought(characterId, moment.innerThought)
        }
        return true
    }

    @Synchronized
    fun clearCharacter(characterId: String) {
        if (characterId.isBlank()) return
        mutableStates.value = mutableStates.value - characterId
        mutableHistories.value = mutableHistories.value - characterId
        messageMoments.entries.removeAll { it.value.characterId == characterId }
        if (selectedMessageAnchor?.characterId == characterId) selectedMessageAnchor = null
        persist()
    }

    private fun recordPresenceTimeline(state: CompanionPresenceState) {
        val characterName = runCatching { MigratedDomainStores.characters.get(state.characterId).displayName }
            .getOrDefault("角色")
        val detail = buildList {
            state.statusText.takeIf(String::isNotBlank)?.let { add("状态：$it") }
            state.gesture.takeIf(String::isNotBlank)?.let { add("动作：$it") }
            state.mood.takeIf(String::isNotBlank)?.let { add("心情：$it") }
            state.innerThought.takeIf(String::isNotBlank)?.let { add("心声：$it") }
        }.joinToString("；")
        if (detail.isBlank()) return
        SharedExperienceTimeline.record(
            eventId = state.provenanceId.takeIf(String::isNotBlank)
                ?.let { "presence-$it-${state.characterId}" }
                ?: "presence-${state.characterId}-${state.updatedAt.toEpochMilli()}",
            characterId = state.characterId,
            channel = "此刻",
            speaker = characterName,
            content = detail,
            occurredAt = state.updatedAt,
            source = "presence",
            evidenceKind = EventEvidenceKind.Inference,
        )
    }

    private fun persist() {
        val array = JSONArray()
        mutableStates.value.values.forEach { array.put(it.toJson()) }
        prefs?.edit()?.putString(KEY_STATES, array.toString())?.apply()
        val historyRoot = JSONObject()
        mutableHistories.value.forEach { (characterId, history) ->
            historyRoot.put(characterId, JSONArray().apply { history.forEach { put(it.toJson()) } })
        }
        prefs?.edit()?.putString(KEY_HISTORY, historyRoot.toString())?.apply()
        val bindings = JSONArray()
        messageMoments.forEach { (messageId, moment) ->
            bindings.put(JSONObject().put("messageId", messageId).put("state", moment.toJson()))
        }
        prefs?.edit()?.putString(KEY_MESSAGE_MOMENTS, bindings.toString())?.apply()
    }

    private fun decode(raw: String?): Map<String, CompanionPresenceState> = runCatching {
        val array = JSONArray(raw ?: "[]")
        buildMap {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val characterId = item.optString("characterId").trim()
                if (characterId.isBlank()) continue
                item.toPresenceState(characterId)?.let { put(characterId, it) }
            }
        }
    }.getOrDefault(emptyMap())

    private fun decodeHistory(raw: String?): Map<String, List<CompanionPresenceState>> = runCatching {
        val root = JSONObject(raw ?: "{}")
        buildMap {
            root.keys().forEach { characterId ->
                val array = root.optJSONArray(characterId) ?: return@forEach
                put(characterId, buildList {
                    for (index in 0 until array.length()) array.optJSONObject(index)?.toPresenceState(characterId)?.let(::add)
                })
            }
        }
    }.getOrDefault(emptyMap())
}

private fun decodeMessageMoments(raw: String?): Map<String, CompanionPresenceState> = runCatching {
    val array = JSONArray(raw ?: "[]")
    buildMap {
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val id = entry.optString("messageId")
            val state = entry.optJSONObject("state")?.toPresenceState("")
            if (id.isNotBlank() && state != null) put(id, state)
        }
    }
}.getOrDefault(emptyMap())

private fun CompanionPresenceState.toJson(): JSONObject = JSONObject().apply {
    put("characterId", characterId)
    put("statusText", statusText)
    put("gesture", gesture)
    put("innerThought", innerThought)
    put("mood", mood)
    put("updatedAt", updatedAt.toString())
    put("source", source)
    put("lastPerceptionAt", lastPerceptionAt?.toString().orEmpty())
    put("lastPerceptionNote", lastPerceptionNote)
    put("provenanceId", provenanceId)
    put("showInHistory", showInHistory)
    put("innerThoughtFingerprint", innerThoughtFingerprint)
    put("momentReflection", momentReflection)
}

private fun JSONObject.toPresenceState(fallbackCharacterId: String): CompanionPresenceState? {
    val id = optString("characterId").ifBlank { fallbackCharacterId }
    if (id.isBlank()) return null
    return CompanionPresenceState(
        characterId = id,
        statusText = optString("statusText"),
        gesture = optString("gesture"),
        innerThought = optString("innerThought"),
        mood = optString("mood"),
        updatedAt = runCatching { Instant.parse(optString("updatedAt")) }.getOrDefault(Instant.EPOCH),
        source = optString("source"),
        lastPerceptionAt = optString("lastPerceptionAt").takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() },
        lastPerceptionNote = optString("lastPerceptionNote"),
        provenanceId = optString("provenanceId"),
        showInHistory = optBoolean("showInHistory", true),
        innerThoughtFingerprint = optString("innerThoughtFingerprint"),
        momentReflection = optString("momentReflection"),
    )
}

private fun String?.cleanPresence(limit: Int): String? = this
    ?.trim()
    ?.replace(Regex("\\s+"), " ")
    ?.take(limit)
    ?.takeIf(String::isNotBlank)
