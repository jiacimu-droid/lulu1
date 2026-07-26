package com.jiacimu.lulu.study

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StudyFocusTheme(val label: String) {
    Charcoal("深灰"), MidnightBlue("深蓝"), WarmBrown("暖棕"),
}

data class StudyFocusPreferences(
    val task: String = "完成当前最重要的一项学习任务",
    val theme: StudyFocusTheme = StudyFocusTheme.Charcoal,
)

class StudyFocusSessionStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<StudyFocusPreferences> = mutable.asStateFlow()

    fun updateTask(task: String) {
        val clean = task.trim().take(200)
        val next = mutable.value.copy(task = clean)
        mutable.value = next
        prefs.edit().putString(KEY_TASK, clean).apply()
    }

    fun updateTheme(theme: StudyFocusTheme) {
        val next = mutable.value.copy(theme = theme)
        mutable.value = next
        prefs.edit().putString(KEY_THEME, theme.name).apply()
    }

    private fun load(): StudyFocusPreferences = StudyFocusPreferences(
        task = prefs.getString(KEY_TASK, null).orEmpty().ifBlank { "完成当前最重要的一项学习任务" },
        theme = runCatching {
            StudyFocusTheme.valueOf(prefs.getString(KEY_THEME, null).orEmpty())
        }.getOrDefault(StudyFocusTheme.Charcoal),
    )

    companion object {
        private const val PREFS_NAME = "lulu_study_focus"
        private const val KEY_TASK = "task"
        private const val KEY_THEME = "theme"
        fun create(context: Context): StudyFocusSessionStore = StudyFocusSessionStore(context.applicationContext)
    }
}

object StudyFocusSessions {
    private var internal: StudyFocusSessionStore? = null
    val store: StudyFocusSessionStore
        get() = checkNotNull(internal) { "StudyFocusSessions 尚未初始化" }

    fun initialize(context: Context) {
        if (internal == null) internal = StudyFocusSessionStore.create(context)
    }
}
