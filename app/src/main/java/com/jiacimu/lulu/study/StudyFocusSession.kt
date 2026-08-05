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

enum class StudyFocusTheme(val label: String) {
    CLOUD("云雾原版"),
    MIDNIGHT("深夜墨蓝"),
}

data class StudyFocusPreferences(
    val task: String = "完成当前最重要的一项学习任务",
    val theme: StudyFocusTheme = StudyFocusTheme.CLOUD,
    val automaticDialogueEnabled: Boolean = true,
    val activeSessionId: String = "",
    val activeCharacterId: String = "",
    val activeTask: String = "",
    val sessionMessageStart: Int = 0,
    val sessionStartedAtEpochMillis: Long = 0L,
    val openingHandled: Boolean = false,
    val completionHandled: Boolean = false,
)

private fun recentFocusCharacterLines(conversationId: String, limit: Int = 8): List<String> =
    MigratedDomainStores.chat.messages(conversationId).value
        .asReversed()
        .asSequence()
        .filter { it.sender == LuluChatMessage.Sender.Character }
        .map { message -> message.content.trim().replace(Regex("\\s+"), " ") }
        .filter(String::isNotBlank)
        .take(limit)
        .toList()

private fun compactFocusUtterance(raw: String, maxChars: Int): String {
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

class StudyFocusSessionStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<StudyFocusPreferences> = mutable.asStateFlow()

    fun updateTask(task: String) {
        update { it.copy(task = task.trim().take(200)) }
    }

    fun updateTheme(theme: StudyFocusTheme) {
        update { it.copy(theme = theme) }
    }

    fun updateAutomaticDialogue(enabled: Boolean) {
        update { it.copy(automaticDialogueEnabled = enabled) }
    }

    fun beginSession(
        characterId: String,
        task: String,
        messageStart: Int,
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
            )
        }
    }

    fun markOpeningHandled() {
        update { it.copy(openingHandled = true) }
    }

    fun markCompletionHandled() {
        update { it.copy(completionHandled = true) }
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
            )
        }
    }

    private fun update(transform: (StudyFocusPreferences) -> StudyFocusPreferences) {
        val next = transform(mutable.value)
        mutable.value = next
        prefs.edit()
            .putString(KEY_TASK, next.task)
            .putString(KEY_THEME, next.theme.name)
            .putBoolean(KEY_AUTOMATIC_DIALOGUE, next.automaticDialogueEnabled)
            .putString(KEY_ACTIVE_SESSION_ID, next.activeSessionId)
            .putString(KEY_ACTIVE_CHARACTER_ID, next.activeCharacterId)
            .putString(KEY_ACTIVE_TASK, next.activeTask)
            .putInt(KEY_SESSION_MESSAGE_START, next.sessionMessageStart)
            .putLong(KEY_SESSION_STARTED_AT, next.sessionStartedAtEpochMillis)
            .putBoolean(KEY_OPENING_HANDLED, next.openingHandled)
            .putBoolean(KEY_COMPLETION_HANDLED, next.completionHandled)
            .apply()
    }

    private fun load(): StudyFocusPreferences {
        val rawTheme = prefs.getString(KEY_THEME, null).orEmpty()
        val theme = when (rawTheme) {
            "Charcoal", "MidnightBlue", StudyFocusTheme.MIDNIGHT.name -> StudyFocusTheme.MIDNIGHT
            "WarmBrown", StudyFocusTheme.CLOUD.name -> StudyFocusTheme.CLOUD
            else -> StudyFocusTheme.CLOUD
        }
        val task = prefs.getString(KEY_TASK, null).orEmpty().ifBlank { "完成当前最重要的一项学习任务" }
        return StudyFocusPreferences(
            task = task,
            theme = theme,
            automaticDialogueEnabled = prefs.getBoolean(KEY_AUTOMATIC_DIALOGUE, true),
            activeSessionId = prefs.getString(KEY_ACTIVE_SESSION_ID, "").orEmpty(),
            activeCharacterId = prefs.getString(KEY_ACTIVE_CHARACTER_ID, "").orEmpty(),
            activeTask = prefs.getString(KEY_ACTIVE_TASK, "").orEmpty(),
            sessionMessageStart = prefs.getInt(KEY_SESSION_MESSAGE_START, 0).coerceAtLeast(0),
            sessionStartedAtEpochMillis = prefs.getLong(KEY_SESSION_STARTED_AT, 0L).coerceAtLeast(0L),
            openingHandled = prefs.getBoolean(KEY_OPENING_HANDLED, false),
            completionHandled = prefs.getBoolean(KEY_COMPLETION_HANDLED, false),
        )
    }

    companion object {
        private const val PREFS_NAME = "lulu_study_focus"
        private const val KEY_TASK = "task"
        private const val KEY_THEME = "theme"
        private const val KEY_AUTOMATIC_DIALOGUE = "automatic_dialogue"
        private const val KEY_ACTIVE_SESSION_ID = "active_session_id"
        private const val KEY_ACTIVE_CHARACTER_ID = "active_character_id"
        private const val KEY_ACTIVE_TASK = "active_task"
        private const val KEY_SESSION_MESSAGE_START = "session_message_start"
        private const val KEY_SESSION_STARTED_AT = "session_started_at"
        private const val KEY_OPENING_HANDLED = "opening_handled"
        private const val KEY_COMPLETION_HANDLED = "completion_handled"

        fun create(context: Context): StudyFocusSessionStore = StudyFocusSessionStore(context.applicationContext)
    }
}

object StudyFocusSessions {
    private var internal: StudyFocusSessionStore? = null
    private var speechEngine: LuluSpeechEngine? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val store: StudyFocusSessionStore
        get() = checkNotNull(internal) { "StudyFocusSessions 尚未初始化" }

    fun initialize(context: Context) {
        if (internal == null) internal = StudyFocusSessionStore.create(context.applicationContext)
        if (speechEngine == null) speechEngine = LuluSpeechEngine(context.applicationContext)
    }

    fun beginSession(characterId: String, task: String, messageStart: Int) {
        store.beginSession(characterId, task, messageStart)
    }

    fun clearSession() {
        store.clearSession()
    }

    fun speakIfEnabled(text: String, enabled: Boolean) {
        if (!enabled || text.isBlank()) return
        speechEngine?.speak(text, scope)
    }

    fun requestOpeningLine(studyState: StudyState) {
        val focus = store.state.value
        if (focus.activeSessionId.isBlank() || focus.openingHandled) return
        store.markOpeningHandled()
        if (!focus.automaticDialogueEnabled) return

        val characterId = focus.activeCharacterId.ifBlank { studyState.profile.selectedCharacterId }
        val task = focus.activeTask.ifBlank { focus.task }
        val conversationId = "$characterId-study-focus"
        val character = MigratedDomainStores.characters.get(characterId)
        ensureStudyFocusConversation(characterId, character.displayName)
        val recentLines = recentFocusCharacterLines(conversationId)

        scope.launch {
            LuluAiServices.gateway.generate(
                characterId = characterId,
                facts = buildString {
                    appendLine(studyState.roleStudyContext())
                    appendLine("本次专注任务：$task")
                    appendLine("计划时长：${studyState.pomodoro.selectedMinutes}分钟")
                    appendLine("番茄钟已经由程序真实启动。")
                    if (recentLines.isNotEmpty()) {
                        appendLine("近期专注中角色说过的话（可自然延续，但不要原句复读，也不要连续使用过于相似的开头和句式）：")
                        recentLines.forEach { appendLine("- $it") }
                    }
                },
                instruction = "以角色自己的身份，自然说一句或两句本轮专注开始时会说的话。可以解释任务、提及时长，也可以使用角色平时会用的常见表达，不必刻意回避；只要符合人设、关系和此刻语境即可。整体尽量简短自然，不要展开成长段落；避免照搬近期台词，尤其不要连续使用相同开头或近似句式。不得虚构用户已经完成任务。只输出角色台词，不加分析或格式说明。",
                source = "考研",
                title = "番茄钟开场",
                temperature = 0.98,
                maxTokens = 120,
            ).onSuccess { reply ->
                val text = compactFocusUtterance(reply.text, maxChars = 52)
                if (text.isNotBlank()) {
                    MigratedDomainStores.chat.appendCharacterMessage(conversationId, text)
                    speakIfEnabled(text, studyState.pomodoro.voiceEnabled)
                }
            }
        }
    }

    fun handleNaturalCompletion(studyStore: PostgraduateExamStore, actualMinutes: Int) {
        completeSession(
            studyStore = studyStore,
            actualMinutes = actualMinutes,
            reason = "番茄钟自然结束",
            rewardMessage = "完整完成本轮专注",
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
        val focus = store.state.value
        if (focus.activeSessionId.isBlank() || focus.completionHandled) return
        store.markCompletionHandled()

        val studyState = studyStore.state.value
        val characterId = focus.activeCharacterId.ifBlank { studyState.profile.selectedCharacterId }
        val task = focus.activeTask.ifBlank { focus.task }
        val conversationId = "$characterId-study-focus"
        val character = MigratedDomainStores.characters.get(characterId)
        ensureStudyFocusConversation(characterId, character.displayName)

        if (recordExperience && actualMinutes > 0) {
            val allMessages = MigratedDomainStores.chat.messages(conversationId).value
            val start = focus.sessionMessageStart.coerceIn(0, allMessages.size)
            val transcript = allMessages
                .drop(start)
                .filter { it.sender != LuluChatMessage.Sender.System }
                .joinToString("\n") { message ->
                    val speaker = if (message.sender == LuluChatMessage.Sender.User) "用户" else character.displayName
                    "$speaker：${message.content.trim()}"
                }
            SharedExperienceTimeline.remember(
                memoryId = "focus-${focus.activeSessionId}",
                characterId = characterId,
                label = "共同专注",
                detail = buildString {
                    append("任务“$task”，实际专注 ${actualMinutes.coerceAtLeast(1)} 分钟，$reason。")
                    if (transcript.isNotBlank()) append("专注期间的对话：\n$transcript")
                },
                occurredAt = focus.sessionStartedAtEpochMillis
                    .takeIf { it > 0L }
                    ?.let(Instant::ofEpochMilli)
                    ?: Instant.now(),
                strength = 6,
                source = "study-focus",
            )
        }

        if (!focus.automaticDialogueEnabled) return
        val recentLines = recentFocusCharacterLines(conversationId)
        scope.launch {
            LuluAiServices.gateway.generate(
                characterId = characterId,
                facts = buildString {
                    appendLine(studyState.roleStudyContext())
                    appendLine("本次任务：$task")
                    appendLine("实际记录时长：${actualMinutes.coerceAtLeast(0)}分钟")
                    appendLine("结束方式：$reason")
                    if (rewardMessage.isNotBlank()) appendLine("程序结算：$rewardMessage")
                    if (recentLines.isNotEmpty()) {
                        appendLine("近期专注中角色说过的话（可自然延续，但不要原句复读，也不要连续使用过于相似的开头和句式）：")
                        recentLines.forEach { appendLine("- $it") }
                    }
                },
                instruction = "以角色自己的身份，自然说一句或两句这轮结束时会说的话。可以提到任务、时长或完成情况，也可以使用角色平时会说的常见表达；不必刻意避开任何词，只需符合人设和真实结果。整体尽量简短，不要写成长篇总结；避免照搬近期台词或连续使用近似句式。提前结束或未满1分钟时，不得说成完整完成。只输出角色台词，不加分析或格式说明。",
                source = "考研",
                title = "番茄钟结束",
                temperature = 0.95,
                maxTokens = 130,
            ).onSuccess { reply ->
                val text = compactFocusUtterance(reply.text, maxChars = 58)
                if (text.isNotBlank()) {
                    MigratedDomainStores.chat.appendCharacterMessage(conversationId, text)
                    speakIfEnabled(text, studyState.pomodoro.voiceEnabled)
                }
            }
        }
    }
}
