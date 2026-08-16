package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

enum class MeetingProseLength { BRIEF, BALANCED, RICH }

enum class MeetingProseStyle { NATURAL, SUBTLE, LITERARY }

data class MeetingWritingPreferences(
    val length: MeetingProseLength = MeetingProseLength.RICH,
    val style: MeetingProseStyle = MeetingProseStyle.SUBTLE,
)

data class MeetingParticipantSceneState(
    val participantId: String,
    val position: String = "",
    val posture: String = "",
    val facing: String = "",
    val contact: List<String> = emptyList(),
    val heldItems: List<String> = emptyList(),
)

data class MeetingSceneSnapshot(
    val location: String,
    val ambience: String = "",
    val participants: List<MeetingParticipantSceneState> = emptyList(),
    val updatedAt: Instant = Instant.now(),
) {
    fun promptSection(): String = buildString {
        appendLine("【现场结构化快照｜这是当前身体与空间事实，不得与正文冲突】")
        appendLine("地点：$location")
        if (ambience.isNotBlank()) appendLine("环境：$ambience")
        participants.forEach { item ->
            append("- participantId=${item.participantId}")
            if (item.position.isNotBlank()) append("；位置=${item.position}")
            if (item.posture.isNotBlank()) append("；姿态=${item.posture}")
            if (item.facing.isNotBlank()) append("；朝向=${item.facing}")
            if (item.contact.isNotEmpty()) append("；接触=${item.contact.joinToString("、")}")
            if (item.heldItems.isNotEmpty()) append("；持有=${item.heldItems.joinToString("、")}")
            appendLine()
        }
    }.trim()
}

enum class MeetingExchangeStatus { PENDING, RUNNING, COMPLETED, FAILED }

data class MeetingExchangeRecord(
    val id: String,
    val sessionId: String,
    val rawDraft: String,
    val status: MeetingExchangeStatus,
    val createdAt: Instant,
    val completedAt: Instant? = null,
    val turnIds: List<String> = emptyList(),
    val beforeScene: MeetingSceneSnapshot,
    val afterScene: MeetingSceneSnapshot? = null,
    val beforePresence: Map<String, CompanionPresenceState?> = emptyMap(),
    val directorPlan: String = "",
    val error: String = "",
)

enum class MeetingInvitationStatus { PENDING, ACCEPTED, REJECTED, EXPIRED }

data class MeetingInvitationRecord(
    val id: String,
    val characterId: String,
    val location: String,
    val message: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val status: MeetingInvitationStatus = MeetingInvitationStatus.PENDING,
    val resolvedAt: Instant? = null,
    val beforePresence: CompanionPresenceState? = null,
)

data class MeetingExperienceState(
    val writing: MeetingWritingPreferences = MeetingWritingPreferences(),
    val scenes: Map<String, MeetingSceneSnapshot> = emptyMap(),
    val sessionOrigins: Map<String, Map<String, String>> = emptyMap(),
    val exchanges: List<MeetingExchangeRecord> = emptyList(),
    val invitations: List<MeetingInvitationRecord> = emptyList(),
)

/**
 * Transaction ledger for meetings. Raw turns stay in [DigitalWorldStore]; this store binds every
 * generated exchange to the scene and presence state it derived so deletion can be a real rewind.
 */
object MeetingExperienceStore {
    private const val PREFS_NAME = "lulu_meeting_experience"
    private const val KEY_STATE = "state_v1"
    private const val MAX_EXCHANGES = 320
    private const val MAX_INVITATIONS = 120

    private val mutable = MutableStateFlow(MeetingExperienceState())
    val state: StateFlow<MeetingExperienceState> = mutable.asStateFlow()
    private var prefs: android.content.SharedPreferences? = null
    private val lock = Any()

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mutable.value = decode(prefs?.getString(KEY_STATE, null))
        expireInvitations()
    }

    fun writingPreferences(): MeetingWritingPreferences = mutable.value.writing

    fun updateWriting(length: MeetingProseLength? = null, style: MeetingProseStyle? = null) {
        synchronized(lock) {
            mutable.value = mutable.value.copy(
                writing = mutable.value.writing.copy(
                    length = length ?: mutable.value.writing.length,
                    style = style ?: mutable.value.writing.style,
                )
            )
            persistLocked()
        }
    }

    fun sceneFor(session: MeetingSession): MeetingSceneSnapshot = synchronized(lock) {
        mutable.value.scenes[session.id] ?: defaultScene(session).also { scene ->
            mutable.value = mutable.value.copy(scenes = mutable.value.scenes + (session.id to scene))
            persistLocked()
        }
    }

    fun updateScene(sessionId: String, scene: MeetingSceneSnapshot) {
        if (sessionId.isBlank()) return
        synchronized(lock) {
            mutable.value = mutable.value.copy(scenes = mutable.value.scenes + (sessionId to scene))
            persistLocked()
        }
    }

    fun registerSessionOrigin(sessionId: String, characterLocations: Map<String, String>) {
        if (sessionId.isBlank() || characterLocations.isEmpty()) return
        synchronized(lock) {
            if (sessionId in mutable.value.sessionOrigins) return
            mutable.value = mutable.value.copy(
                sessionOrigins = mutable.value.sessionOrigins + (sessionId to characterLocations)
            )
            persistLocked()
        }
    }

    fun sessionOrigin(sessionId: String): Map<String, String> =
        mutable.value.sessionOrigins[sessionId].orEmpty()

    fun beginExchange(
        session: MeetingSession,
        rawDraft: String,
        exchangeId: String = UUID.randomUUID().toString(),
        now: Instant = Instant.now(),
    ): MeetingExchangeRecord {
        val beforeScene = sceneFor(session)
        val beforePresence = session.participantIds.associateWith(CompanionPresenceStore::current)
        val record = MeetingExchangeRecord(
            id = exchangeId,
            sessionId = session.id,
            rawDraft = rawDraft.trim().take(4_000),
            status = MeetingExchangeStatus.PENDING,
            createdAt = now,
            beforeScene = beforeScene,
            beforePresence = beforePresence,
        )
        synchronized(lock) {
            mutable.value = mutable.value.copy(
                exchanges = (mutable.value.exchanges.filterNot { it.id == exchangeId } + record).takeLast(MAX_EXCHANGES)
            )
            persistLocked()
        }
        return record
    }

    fun markRunning(exchangeId: String) = updateExchange(exchangeId) { it.copy(status = MeetingExchangeStatus.RUNNING, error = "") }

    fun completeExchange(
        exchangeId: String,
        turnIds: List<String>,
        afterScene: MeetingSceneSnapshot,
        directorPlan: String,
        now: Instant = Instant.now(),
    ) = updateExchange(exchangeId) {
        it.copy(
            status = MeetingExchangeStatus.COMPLETED,
            completedAt = now,
            turnIds = turnIds.distinct(),
            afterScene = afterScene,
            directorPlan = directorPlan.take(2_000),
            error = "",
        )
    }.also { updateScene(it?.sessionId.orEmpty(), afterScene) }

    /** Saves partial output so an interrupted process can rewind before it resumes. */
    fun checkpointExchange(
        exchangeId: String,
        turnIds: List<String>,
        scene: MeetingSceneSnapshot,
        directorPlan: String = "",
    ) = updateExchange(exchangeId) {
        it.copy(
            turnIds = turnIds.distinct(),
            afterScene = scene,
            directorPlan = directorPlan.take(2_000),
        )
    }.also { updateScene(it?.sessionId.orEmpty(), scene) }

    fun failExchange(exchangeId: String, message: String) = updateExchange(exchangeId) {
        it.copy(status = MeetingExchangeStatus.FAILED, error = message.trim().ifBlank { "生成失败" }.take(500))
    }

    fun exchange(exchangeId: String): MeetingExchangeRecord? = mutable.value.exchanges.firstOrNull { it.id == exchangeId }

    fun exchangeForTurn(turn: MeetingTurn): MeetingExchangeRecord? =
        turn.exchangeId?.let(::exchange) ?: mutable.value.exchanges.firstOrNull { turn.id in it.turnIds }

    fun discardExchange(exchangeId: String) {
        synchronized(lock) {
            mutable.value = mutable.value.copy(exchanges = mutable.value.exchanges.filterNot { it.id == exchangeId })
            persistLocked()
        }
    }

    fun pendingForSession(sessionId: String): List<MeetingExchangeRecord> = mutable.value.exchanges
        .filter { it.sessionId == sessionId && it.status in setOf(MeetingExchangeStatus.PENDING, MeetingExchangeStatus.RUNNING, MeetingExchangeStatus.FAILED) }
        .sortedBy(MeetingExchangeRecord::createdAt)

    fun completedForSession(sessionId: String, limit: Int = 8): List<MeetingExchangeRecord> = mutable.value.exchanges
        .filter { it.sessionId == sessionId && it.status == MeetingExchangeStatus.COMPLETED }
        .sortedBy(MeetingExchangeRecord::createdAt)
        .takeLast(limit.coerceAtLeast(1))

    fun recordsFrom(sessionId: String, exchangeId: String): List<MeetingExchangeRecord> {
        val all = mutable.value.exchanges.filter { it.sessionId == sessionId }.sortedBy(MeetingExchangeRecord::createdAt)
        val index = all.indexOfFirst { it.id == exchangeId }
        return if (index < 0) emptyList() else all.drop(index)
    }

    fun removeRecords(sessionId: String, exchangeIds: Set<String>, restoredScene: MeetingSceneSnapshot?) {
        synchronized(lock) {
            mutable.value = mutable.value.copy(
                exchanges = mutable.value.exchanges.filterNot { it.sessionId == sessionId && it.id in exchangeIds },
                scenes = if (restoredScene == null) mutable.value.scenes - sessionId
                else mutable.value.scenes + (sessionId to restoredScene),
            )
            persistLocked()
        }
    }

    fun removeSession(sessionId: String) {
        synchronized(lock) {
            mutable.value = mutable.value.copy(
                exchanges = mutable.value.exchanges.filterNot { it.sessionId == sessionId },
                scenes = mutable.value.scenes - sessionId,
                sessionOrigins = mutable.value.sessionOrigins - sessionId,
            )
            persistLocked()
        }
    }

    fun createInvitation(
        characterId: String,
        location: String,
        message: String,
        now: Instant = Instant.now(),
    ): MeetingInvitationRecord {
        val record = MeetingInvitationRecord(
            id = UUID.randomUUID().toString(),
            characterId = characterId,
            location = location,
            message = message.trim().take(500),
            createdAt = now,
            expiresAt = now.plusSeconds(24 * 60 * 60),
            beforePresence = CompanionPresenceStore.current(characterId),
        )
        synchronized(lock) {
            mutable.value = mutable.value.copy(
                invitations = (mutable.value.invitations + record).takeLast(MAX_INVITATIONS)
            )
            persistLocked()
        }
        return record
    }

    fun invitation(invitationId: String): MeetingInvitationRecord? {
        expireInvitations()
        return mutable.value.invitations.firstOrNull { it.id == invitationId }
    }

    fun acceptInvitation(invitationId: String, now: Instant = Instant.now()): MeetingInvitationRecord? =
        resolveInvitation(invitationId, MeetingInvitationStatus.ACCEPTED, now)

    fun rejectInvitation(invitationId: String, now: Instant = Instant.now()): MeetingInvitationRecord? {
        val resolved = resolveInvitation(invitationId, MeetingInvitationStatus.REJECTED, now) ?: return null
        CompanionPresenceStore.rollbackMeetingProvenance(
            setOf("meeting-invite-${resolved.id}"),
            mapOf(resolved.characterId to resolved.beforePresence),
        )
        SharedExperienceTimeline.record(
            eventId = "meeting-invite-${resolved.id}-rejected",
            characterId = resolved.characterId,
            channel = "数字世界邀约",
            speaker = "系统",
            content = "主人婉拒了前往“${resolved.location}”见面的邀请。",
            occurredAt = now,
        )
        return resolved
    }

    fun expireInvitations(now: Instant = Instant.now()) {
        val expired = mutable.value.invitations.filter {
            it.status == MeetingInvitationStatus.PENDING && it.expiresAt <= now
        }
        if (expired.isEmpty()) return
        synchronized(lock) {
            mutable.value = mutable.value.copy(
                invitations = mutable.value.invitations.map {
                    if (it in expired) it.copy(status = MeetingInvitationStatus.EXPIRED, resolvedAt = now) else it
                }
            )
            persistLocked()
        }
        expired.forEach { invite ->
            CompanionPresenceStore.rollbackMeetingProvenance(
                setOf("meeting-invite-${invite.id}"),
                mapOf(invite.characterId to invite.beforePresence),
            )
            SharedExperienceTimeline.record(
                eventId = "meeting-invite-${invite.id}-expired",
                characterId = invite.characterId,
                channel = "数字世界邀约",
                speaker = "系统",
                content = "前往“${invite.location}”见面的邀请等待了一天后失效。",
                occurredAt = now,
            )
        }
    }

    private fun resolveInvitation(
        invitationId: String,
        status: MeetingInvitationStatus,
        now: Instant,
    ): MeetingInvitationRecord? {
        expireInvitations(now)
        var resolved: MeetingInvitationRecord? = null
        synchronized(lock) {
            val current = mutable.value.invitations.firstOrNull { it.id == invitationId } ?: return null
            if (current.status != MeetingInvitationStatus.PENDING) return null
            resolved = current.copy(status = status, resolvedAt = now)
            mutable.value = mutable.value.copy(
                invitations = mutable.value.invitations.map { if (it.id == invitationId) resolved!! else it }
            )
            persistLocked()
        }
        return resolved
    }

    private fun updateExchange(
        exchangeId: String,
        transform: (MeetingExchangeRecord) -> MeetingExchangeRecord,
    ): MeetingExchangeRecord? {
        var updated: MeetingExchangeRecord? = null
        synchronized(lock) {
            val current = mutable.value.exchanges.firstOrNull { it.id == exchangeId } ?: return null
            updated = transform(current)
            mutable.value = mutable.value.copy(
                exchanges = mutable.value.exchanges.map { if (it.id == exchangeId) updated!! else it }
            )
            persistLocked()
        }
        return updated
    }

    private fun defaultScene(session: MeetingSession): MeetingSceneSnapshot = MeetingSceneSnapshot(
        location = session.location,
        ambience = if (session.reality == MeetingReality.DIGITAL_WORLD) "数字身体已经在当前地点稳定形成" else "现实场景连续进行中",
        participants = buildList {
            add(MeetingParticipantSceneState("user", position = "与参与者处于可正常交谈的距离", posture = "自然停留"))
            session.participantIds.forEach { id ->
                add(MeetingParticipantSceneState(id, position = "与主人处于可正常交谈的距离", posture = "自然停留"))
            }
        },
        updatedAt = session.startedAt,
    )

    private fun persistLocked() {
        prefs?.edit()?.putString(KEY_STATE, encode(mutable.value).toString())?.apply()
    }

    private fun encode(state: MeetingExperienceState): JSONObject = JSONObject().apply {
        put("writing", state.writing.toJson())
        put("scenes", JSONObject().apply { state.scenes.forEach { (id, scene) -> put(id, scene.toJson()) } })
        put("sessionOrigins", JSONObject().apply {
            state.sessionOrigins.forEach { (sessionId, locations) ->
                put(sessionId, JSONObject().apply { locations.forEach { (id, location) -> put(id, location) } })
            }
        })
        put("exchanges", JSONArray().apply { state.exchanges.forEach { put(it.toJson()) } })
        put("invitations", JSONArray().apply { state.invitations.forEach { put(it.toJson()) } })
    }

    private fun decode(raw: String?): MeetingExperienceState = runCatching {
        val root = JSONObject(raw ?: "{}")
        val scenes = buildMap {
            val source = root.optJSONObject("scenes") ?: JSONObject()
            source.keys().forEach { key -> source.optJSONObject(key)?.toScene()?.let { put(key, it) } }
        }
        val exchanges = buildList {
            root.optJSONArray("exchanges")?.forEachMeetingObject { it.toExchange()?.let(::add) }
        }
        val origins = buildMap {
            val source = root.optJSONObject("sessionOrigins") ?: JSONObject()
            source.keys().forEach { sessionId ->
                val locations = source.optJSONObject(sessionId) ?: return@forEach
                put(sessionId, buildMap { locations.keys().forEach { id -> put(id, locations.optString(id)) } })
            }
        }
        val invitations = buildList {
            root.optJSONArray("invitations")?.forEachMeetingObject { it.toInvitation()?.let(::add) }
        }
        MeetingExperienceState(
            writing = root.optJSONObject("writing")?.toWriting() ?: MeetingWritingPreferences(),
            scenes = scenes,
            sessionOrigins = origins,
            exchanges = exchanges,
            invitations = invitations,
        )
    }.getOrDefault(MeetingExperienceState())
}

private fun MeetingWritingPreferences.toJson() = JSONObject().put("length", length.name).put("style", style.name)
private fun JSONObject.toWriting() = MeetingWritingPreferences(
    length = runCatching { MeetingProseLength.valueOf(optString("length")) }.getOrDefault(MeetingProseLength.RICH),
    style = runCatching { MeetingProseStyle.valueOf(optString("style")) }.getOrDefault(MeetingProseStyle.SUBTLE),
)

private fun MeetingParticipantSceneState.toJson() = JSONObject()
    .put("participantId", participantId)
    .put("position", position)
    .put("posture", posture)
    .put("facing", facing)
    .put("contact", JSONArray(contact))
    .put("heldItems", JSONArray(heldItems))

private fun JSONObject.toParticipantScene(): MeetingParticipantSceneState? {
    val id = optString("participantId").trim()
    if (id.isBlank()) return null
    return MeetingParticipantSceneState(
        participantId = id,
        position = optString("position"),
        posture = optString("posture"),
        facing = optString("facing"),
        contact = optJSONArray("contact").toStringList(),
        heldItems = optJSONArray("heldItems").toStringList(),
    )
}

private fun MeetingSceneSnapshot.toJson() = JSONObject()
    .put("location", location)
    .put("ambience", ambience)
    .put("updatedAt", updatedAt.toString())
    .put("participants", JSONArray().apply { participants.forEach { put(it.toJson()) } })

private fun JSONObject.toScene(): MeetingSceneSnapshot? {
    val location = optString("location").trim()
    if (location.isBlank()) return null
    return MeetingSceneSnapshot(
        location = location,
        ambience = optString("ambience"),
        participants = buildList { optJSONArray("participants")?.forEachMeetingObject { it.toParticipantScene()?.let(::add) } },
        updatedAt = instantOrNow("updatedAt"),
    )
}

private fun CompanionPresenceState?.toNullableJson(): Any = this?.let { state -> JSONObject()
    .put("characterId", state.characterId)
    .put("statusText", state.statusText)
    .put("gesture", state.gesture)
    .put("innerThought", state.innerThought)
    .put("mood", state.mood)
    .put("updatedAt", state.updatedAt.toString())
    .put("source", state.source)
    .put("lastPerceptionAt", state.lastPerceptionAt?.toString().orEmpty())
    .put("lastPerceptionNote", state.lastPerceptionNote)
    .put("provenanceId", state.provenanceId)
} ?: JSONObject.NULL

private fun JSONObject.toPresenceSnapshot(): CompanionPresenceState? = CompanionPresenceState(
    characterId = optString("characterId"),
    statusText = optString("statusText"),
    gesture = optString("gesture"),
    innerThought = optString("innerThought"),
    mood = optString("mood"),
    updatedAt = instantOrNow("updatedAt"),
    source = optString("source"),
    lastPerceptionAt = optString("lastPerceptionAt").takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() },
    lastPerceptionNote = optString("lastPerceptionNote"),
    provenanceId = optString("provenanceId"),
)

private fun MeetingExchangeRecord.toJson() = JSONObject()
    .put("id", id)
    .put("sessionId", sessionId)
    .put("rawDraft", rawDraft)
    .put("status", status.name)
    .put("createdAt", createdAt.toString())
    .put("completedAt", completedAt?.toString().orEmpty())
    .put("turnIds", JSONArray(turnIds))
    .put("beforeScene", beforeScene.toJson())
    .put("afterScene", afterScene?.toJson() ?: JSONObject.NULL)
    .put("beforePresence", JSONObject().apply { beforePresence.forEach { (id, state) -> put(id, state.toNullableJson()) } })
    .put("directorPlan", directorPlan)
    .put("error", error)

private fun JSONObject.toExchange(): MeetingExchangeRecord? {
    val id = optString("id")
    val sessionId = optString("sessionId")
    val beforeScene = optJSONObject("beforeScene")?.toScene()
    if (id.isBlank() || sessionId.isBlank() || beforeScene == null) return null
    val beforePresence = buildMap<String, CompanionPresenceState?> {
        val source = optJSONObject("beforePresence") ?: JSONObject()
        source.keys().forEach { key -> put(key, source.optJSONObject(key)?.toPresenceSnapshot()) }
    }
    return MeetingExchangeRecord(
        id = id,
        sessionId = sessionId,
        rawDraft = optString("rawDraft"),
        status = runCatching { MeetingExchangeStatus.valueOf(optString("status")) }.getOrDefault(MeetingExchangeStatus.FAILED),
        createdAt = instantOrNow("createdAt"),
        completedAt = optString("completedAt").takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() },
        turnIds = optJSONArray("turnIds").toStringList(),
        beforeScene = beforeScene,
        afterScene = optJSONObject("afterScene")?.toScene(),
        beforePresence = beforePresence,
        directorPlan = optString("directorPlan"),
        error = optString("error"),
    )
}

private fun MeetingInvitationRecord.toJson() = JSONObject()
    .put("id", id)
    .put("characterId", characterId)
    .put("location", location)
    .put("message", message)
    .put("createdAt", createdAt.toString())
    .put("expiresAt", expiresAt.toString())
    .put("status", status.name)
    .put("resolvedAt", resolvedAt?.toString().orEmpty())
    .put("beforePresence", beforePresence.toNullableJson())

private fun JSONObject.toInvitation(): MeetingInvitationRecord? {
    val id = optString("id")
    val characterId = optString("characterId")
    if (id.isBlank() || characterId.isBlank()) return null
    return MeetingInvitationRecord(
        id = id,
        characterId = characterId,
        location = optString("location"),
        message = optString("message"),
        createdAt = instantOrNow("createdAt"),
        expiresAt = instantOrNow("expiresAt"),
        status = runCatching { MeetingInvitationStatus.valueOf(optString("status")) }.getOrDefault(MeetingInvitationStatus.PENDING),
        resolvedAt = optString("resolvedAt").takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() },
        beforePresence = optJSONObject("beforePresence")?.toPresenceSnapshot(),
    )
}

private fun JSONArray?.toStringList(): List<String> = buildList {
    val source = this@toStringList ?: return@buildList
    for (index in 0 until source.length()) source.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
}

private fun JSONArray.forEachMeetingObject(block: (JSONObject) -> Unit) {
    for (index in 0 until length()) optJSONObject(index)?.let(block)
}

private fun JSONObject.instantOrNow(key: String): Instant =
    runCatching { Instant.parse(optString(key)) }.getOrDefault(Instant.now())
