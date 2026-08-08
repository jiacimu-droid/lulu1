package com.jiacimu.lulu.study

import android.content.Context
import com.jiacimu.lulu.LuluSpeechEngine
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.SharedExperienceTimeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil

enum class PomodoroTimerMode { Countdown, CountUp }
enum class PomodoroSkin { Light, Dark }

data class PomodoroCompanionPreferences(
    val task: String = "完成当前最重要的一项学习任务",
    val automaticDialogueEnabled: Boolean = true,
    val activeSessionId: String = "",
    val activeCharacterId: String = "",
    val activeTask: String = "",
    val sessionMessageStart: Int = 0,
    val sessionStartedAtEpochMillis: Long = 0L,
    val openingHandled: Boolean = false,
    val completionHandled: Boolean = false,
    val timerMode: PomodoroTimerMode = PomodoroTimerMode.Countdown,
    val skin: PomodoroSkin = PomodoroSkin.Light,
    val countUpRunning: Boolean = false,
    val countUpElapsedSeconds: Int = 0,
    val countUpAnchorEpochMillis: Long = 0L,
)

private fun recentPomodoroCharacterLines(conversationId: String, limit: Int = 8): List<String> =
    MigratedDomainStores.chat.messages(conversationId).value
        .asReversed()
        .asSequence()
        .filter { it.sender == LuluChatMessage.Sender.Character }
        .map { message -> message.content.trim().replace(Regex("\\s+"), " ") }
        .filter(String::isNotBlank)
        .take(limit)
        .toList()

private fun compactPomodoroUtterance(raw: String, maxChars: Int): String {
    val normalized = raw
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString("")
        .removePrefix("-")
        .removePrefix("•")
        .trim()
        .trim('“', '”', '"')
    if (normalized.isBlank()) return ""

    var sentenceCount = 0
    var secondSentenceEnd = -1
    for (index in normalized.indices) {
        if (normalized[index] in "。！？!?") {
            sentenceCount += 1
            if (sentenceCount == 2) {
                secondSentenceEnd = index
                break
            }
        }
    }
    val upToTwoSentences = if (secondSentenceEnd >= 0) normalized.take(secondSentenceEnd + 1) else normalized
    if (upToTwoSentences.length <= maxChars) return upToTwoSentences

    val rawCut = upToTwoSentences.take(maxChars)
    val naturalBoundary = rawCut.indexOfLast { it in "，、；： " }
    val cut = if (naturalBoundary >= maxChars / 2) rawCut.take(naturalBoundary) else rawCut
    return cut.trimEnd('，', '、', '；', ':', '：', ' ').plus("。")
}

class PomodoroCompanionStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<PomodoroCompanionPreferences> = mutable.asStateFlow()

    fun updateTask(task: String) = update { it.copy(task = task.trim().take(200)) }
    fun updateAutomaticDialogue(enabled: Boolean) = update { it.copy(automaticDialogueEnabled = enabled) }
    fun updateTimerMode(mode: PomodoroTimerMode) = update { current ->
        if (current.activeSessionId.isNotBlank() && !current.completionHandled) current else current.copy(timerMode = mode)
    }
    fun updateSkin(skin: PomodoroSkin) = update { it.copy(skin = skin) }

    fun beginSession(
        characterId: String,
        task: String,
        messageStart: Int,
        mode: PomodoroTimerMode,
        startedAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val cleanTask = task.trim().take(200)
        update {
            it.copy(
                task = cleanTask,
                activeSessionId = UUID.randomUUID().toString(),
                activeCharacterId = characterId.trim().ifBlank { "lulu" },
                activeTask = cleanTask,
                sessionMessageStart = messageStart.coerceAtLeast(0),
                sessionStartedAtEpochMillis = startedAtEpochMillis,
                openingHandled = false,
                completionHandled = false,
                timerMode = mode,
                countUpRunning = mode == PomodoroTimerMode.CountUp,
                countUpElapsedSeconds = 0,
                countUpAnchorEpochMillis = if (mode == PomodoroTimerMode.CountUp) startedAtEpochMillis else 0L,
            )
        }
    }

    fun markOpeningHandled() = update { it.copy(openingHandled = true) }
    fun markCompletionHandled() = update { it.copy(completionHandled = true, countUpRunning = false, countUpAnchorEpochMillis = 0L) }

    fun currentCountUpSeconds(nowMillis: Long = System.currentTimeMillis()): Int {
        val current = mutable.value
        if (!current.countUpRunning || current.countUpAnchorEpochMillis <= 0L) return current.countUpElapsedSeconds.coerceAtLeast(0)
        val delta = ((nowMillis - current.countUpAnchorEpochMillis).coerceAtLeast(0L) / 1000L).toInt()
        return (current.countUpElapsedSeconds + delta).coerceAtLeast(0)
    }

    /** Refreshes the visible counter in memory; the persisted anchor remains enough to recover after process death. */
    fun syncCountUpClock(nowMillis: Long = System.currentTimeMillis()): Int {
        val elapsed = currentCountUpSeconds(nowMillis)
        val current = mutable.value
        if (current.countUpRunning) {
            mutable.value = current.copy(
                countUpElapsedSeconds = elapsed,
                countUpAnchorEpochMillis = nowMillis,
            )
        }
        return elapsed
    }

    fun toggleCountUp(nowMillis: Long = System.currentTimeMillis()) {
        val current = mutable.value
        if (current.timerMode != PomodoroTimerMode.CountUp || current.activeSessionId.isBlank() || current.completionHandled) return
        if (current.countUpRunning) {
            val elapsed = currentCountUpSeconds(nowMillis)
            update { it.copy(countUpRunning = false, countUpElapsedSeconds = elapsed, countUpAnchorEpochMillis = 0L) }
        } else {
            update { it.copy(countUpRunning = true, countUpAnchorEpochMillis = nowMillis) }
        }
    }

    fun stopCountUp(nowMillis: Long = System.currentTimeMillis()): Int {
        val elapsed = currentCountUpSeconds(nowMillis)
        update { it.copy(countUpRunning = false, countUpElapsedSeconds = elapsed, countUpAnchorEpochMillis = 0L) }
        return elapsed
    }

    fun clearSession() {
        update {
            it.copy(
                activeSessionId = "",
                activeCharacterId = "",
                activeTask = "",
                sessionMessageStart = 0,
                sessionStartedAtEpochMillis = 0L,
                openingHandled = false,
                completionHandled = false,
                countUpRunning = false,
                countUpElapsedSeconds = 0,
                countUpAnchorEpochMillis = 0L,
            )
        }
    }

    private fun update(transform: (PomodoroCompanionPreferences) -> PomodoroCompanionPreferences) {
        val next = transform(mutable.value)
        mutable.value = next
        prefs.edit()
            .putString(KEY_TASK, next.task)
            .putBoolean(KEY_AUTOMATIC_DIALOGUE, next.automaticDialogueEnabled)
            .putString(KEY_ACTIVE_SESSION_ID, next.activeSessionId)
            .putString(KEY_ACTIVE_CHARACTER_ID, next.activeCharacterId)
            .putString(KEY_ACTIVE_TASK, next.activeTask)
            .putInt(KEY_SESSION_MESSAGE_START, next.sessionMessageStart)
            .putLong(KEY_SESSION_STARTED_AT, next.sessionStartedAtEpochMillis)
            .putBoolean(KEY_OPENING_HANDLED, next.openingHandled)
            .putBoolean(KEY_COMPLETION_HANDLED, next.completionHandled)
            .putString(KEY_TIMER_MODE, next.timerMode.name)
            .putString(KEY_SKIN, next.skin.name)
            .putBoolean(KEY_COUNT_UP_RUNNING, next.countUpRunning)
            .putInt(KEY_COUNT_UP_ELAPSED, next.countUpElapsedSeconds)
            .putLong(KEY_COUNT_UP_ANCHOR, next.countUpAnchorEpochMillis)
            .apply()
    }

    private fun load(): PomodoroCompanionPreferences = PomodoroCompanionPreferences(
        task = prefs.getString(KEY_TASK, null).orEmpty().ifBlank { "完成当前最重要的一项学习任务" },
        automaticDialogueEnabled = prefs.getBoolean(KEY_AUTOMATIC_DIALOGUE, true),
        activeSessionId = prefs.getString(KEY_ACTIVE_SESSION_ID, "").orEmpty(),
        activeCharacterId = prefs.getString(KEY_ACTIVE_CHARACTER_ID, "").orEmpty(),
        activeTask = prefs.getString(KEY_ACTIVE_TASK, "").orEmpty(),
        sessionMessageStart = prefs.getInt(KEY_SESSION_MESSAGE_START, 0).coerceAtLeast(0),
        sessionStartedAtEpochMillis = prefs.getLong(KEY_SESSION_STARTED_AT, 0L).coerceAtLeast(0L),
        openingHandled = prefs.getBoolean(KEY_OPENING_HANDLED, false),
        completionHandled = prefs.getBoolean(KEY_COMPLETION_HANDLED, false),
        timerMode = runCatching { PomodoroTimerMode.valueOf(prefs.getString(KEY_TIMER_MODE, PomodoroTimerMode.Countdown.name).orEmpty()) }
            .getOrDefault(PomodoroTimerMode.Countdown),
        skin = runCatching { PomodoroSkin.valueOf(prefs.getString(KEY_SKIN, PomodoroSkin.Light.name).orEmpty()) }
            .getOrDefault(PomodoroSkin.Light),
        countUpRunning = prefs.getBoolean(KEY_COUNT_UP_RUNNING, false),
        countUpElapsedSeconds = prefs.getInt(KEY_COUNT_UP_ELAPSED, 0).coerceAtLeast(0),
        countUpAnchorEpochMillis = prefs.getLong(KEY_COUNT_UP_ANCHOR, 0L).coerceAtLeast(0L),
    )

    companion object {
        private const val PREFS_NAME = "lulu_study_focus"
        private const val KEY_TASK = "task"
        private const val KEY_AUTOMATIC_DIALOGUE = "automatic_dialogue"
        private const val KEY_ACTIVE_SESSION_ID = "active_session_id"
        private const val KEY_ACTIVE_CHARACTER_ID = "active_character_id"
        private const val KEY_ACTIVE_TASK = "active_task"
        private const val KEY_SESSION_MESSAGE_START = "session_message_start"
        private const val KEY_SESSION_STARTED_AT = "session_started_at"
        private const val KEY_OPENING_HANDLED = "opening_handled"
        private const val KEY_COMPLETION_HANDLED = "completion_handled"
        private const val KEY_TIMER_MODE = "timer_mode"
        private const val KEY_SKIN = "skin"
        private const val KEY_COUNT_UP_RUNNING = "count_up_running"
        private const val KEY_COUNT_UP_ELAPSED = "count_up_elapsed"
        private const val KEY_COUNT_UP_ANCHOR = "count_up_anchor"

        fun create(context: Context): PomodoroCompanionStore = PomodoroCompanionStore(context.applicationContext)
    }
}

object PomodoroCompanionSessions {
    private var internal: PomodoroCompanionStore? = null
    private var speechEngine: LuluSpeechEngine? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val store: PomodoroCompanionStore
        get() = checkNotNull(internal) { "PomodoroCompanionSessions 尚未初始化" }

    fun initialize(context: Context) {
        if (internal == null) internal = PomodoroCompanionStore.create(context.applicationContext)
        if (speechEngine == null) speechEngine = LuluSpeechEngine(context.applicationContext)
    }

    fun beginSession(characterId: String, task: String, messageStart: Int, mode: PomodoroTimerMode) {
        store.beginSession(characterId, task, messageStart, mode)
    }

    fun clearSession() = store.clearSession()
    fun toggleCountUp() = store.toggleCountUp()
    fun syncCountUpClock(): Int = store.syncCountUpClock()
    fun currentCountUpSeconds(): Int = store.currentCountUpSeconds()

    fun speakIfEnabled(text: String, enabled: Boolean) {
        if (!enabled || text.isBlank()) return
        speechEngine?.speak(text, scope)
    }

    fun requestOpeningLine(studyState: StudyState) {
        val pomodoro = store.state.value
        if (pomodoro.activeSessionId.isBlank() || pomodoro.openingHandled) return
        store.markOpeningHandled()
        if (!pomodoro.automaticDialogueEnabled) return

        val characterId = pomodoro.activeCharacterId.ifBlank { studyState.profile.selectedCharacterId }
        val task = pomodoro.activeTask.ifBlank { pomodoro.task }
        val character = MigratedDomainStores.characters.get(characterId)
        val conversationId = MigratedDomainStores.chat.ensureConversation(characterId, character.displayName).id
        val recentLines = recentPomodoroCharacterLines(conversationId)

        scope.launch {
            LuluAiServices.gateway.generate(
                characterId = characterId,
                facts = buildString {
                    appendLine(studyState.roleStudyContext())
                    appendLine("本次番茄钟任务：$task")
                    if (pomodoro.timerMode == PomodoroTimerMode.Countdown) {
                        appendLine("模式：倒计时；计划时长：${studyState.pomodoro.selectedMinutes}分钟")
                    } else {
                        appendLine("模式：正计时；没有预设结束时长")
                    }
                    appendLine("番茄钟已经由程序真实启动。")
                    appendLine("这句话会作为普通消息进入你和用户唯一的一对一私聊，不会创建学习专用聊天。")
                    if (recentLines.isNotEmpty()) {
                        appendLine("近期你在私聊里说过的话：")
                        recentLines.forEach { appendLine("- $it") }
                    }
                },
                instruction = "以角色自己的身份，自然说一句或两句番茄钟开始时会说的话。保持人设、关系和连续状态；简短自然，不要写系统说明，不要虚构用户已经完成任务，只输出角色台词。",
                source = "考研",
                title = "番茄钟开场",
                temperature = 0.98,
                maxTokens = 120,
            ).onSuccess { reply ->
                val text = compactPomodoroUtterance(reply.text, maxChars = 52)
                if (text.isNotBlank()) {
                    MigratedDomainStores.chat.appendCharacterMessage(conversationId, text, characterId)
                    speakIfEnabled(text, studyState.pomodoro.voiceEnabled)
                }
            }
        }
    }

    fun finishActiveSession(studyStore: PostgraduateExamStore, reason: String = "用户结束番茄钟"): Pair<Int, String> {
        val pomodoro = store.state.value
        if (pomodoro.activeSessionId.isBlank() || pomodoro.completionHandled) return 0 to "本轮已经结束"

        val elapsedSeconds = if (pomodoro.timerMode == PomodoroTimerMode.CountUp) {
            store.stopCountUp()
        } else {
            val timer = studyStore.state.value.pomodoro
            if (timer.running) studyStore.togglePomodoro()
            val latest = studyStore.state.value.pomodoro
            (latest.selectedMinutes * 60 - latest.remainingSeconds).coerceAtLeast(0)
        }
        val actualMinutes = ceil(elapsedSeconds / 60.0).toInt()

        if (actualMinutes <= 0) {
            if (pomodoro.timerMode == PomodoroTimerMode.Countdown) studyStore.resetPomodoro()
            completeSession(
                studyStore = studyStore,
                actualMinutes = 0,
                reason = reason,
                rewardMessage = "未满1分钟，本次不记录奖励",
                recordExperience = false,
            )
            return 0 to "未满1分钟，本次不记录"
        }

        val reward = studyStore.completePomodoro(actualMinutes)
        completeSession(
            studyStore = studyStore,
            actualMinutes = actualMinutes,
            reason = reason,
            rewardMessage = reward,
            recordExperience = true,
        )
        return actualMinutes to reward
    }

    fun handleNaturalCompletion(studyStore: PostgraduateExamStore, actualMinutes: Int) {
        completeSession(
            studyStore = studyStore,
            actualMinutes = actualMinutes,
            reason = "番茄钟自然结束",
            rewardMessage = "完整完成本轮番茄钟",
            recordExperience = true,
        )
    }

    fun completeSession(
        studyStore: PostgraduateExamStore,
        actualMinutes: Int,
        reason: String,
        rewardMessage: String = "",
        recordExperience: Boolean = true,
    ) {
        val pomodoro = store.state.value
        if (pomodoro.activeSessionId.isBlank() || pomodoro.completionHandled) return
        store.markCompletionHandled()

        val studyState = studyStore.state.value
        val characterId = pomodoro.activeCharacterId.ifBlank { studyState.profile.selectedCharacterId }
        val task = pomodoro.activeTask.ifBlank { pomodoro.task }
        val character = MigratedDomainStores.characters.get(characterId)
        val conversationId = MigratedDomainStores.chat.ensureConversation(characterId, character.displayName).id

        if (recordExperience && actualMinutes > 0) {
            val allMessages = MigratedDomainStores.chat.messages(conversationId).value
            val start = pomodoro.sessionMessageStart.coerceIn(0, allMessages.size)
            val transcript = allMessages
                .drop(start)
                .filter { it.sender != LuluChatMessage.Sender.System }
                .joinToString("\n") { message ->
                    val speaker = if (message.sender == LuluChatMessage.Sender.User) "用户" else character.displayName
                    "$speaker：${message.content.trim()}"
                }
            SharedExperienceTimeline.remember(
                memoryId = "pomodoro-${pomodoro.activeSessionId}",
                characterId = characterId,
                label = "共同番茄钟",
                detail = buildString {
                    append("任务“$task”，实际学习 ${actualMinutes.coerceAtLeast(1)} 分钟，$reason。")
                    if (rewardMessage.isNotBlank()) append("结算：$rewardMessage。")
                    if (transcript.isNotBlank()) append("期间的私聊：\n$transcript")
                },
                occurredAt = pomodoro.sessionStartedAtEpochMillis.takeIf { it > 0L }?.let(Instant::ofEpochMilli) ?: Instant.now(),
                strength = 6,
                source = "pomodoro",
            )
        }
    }
}
