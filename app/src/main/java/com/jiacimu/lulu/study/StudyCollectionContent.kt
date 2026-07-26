package com.jiacimu.lulu.study

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

class StudyCollectionContentStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val contents: StateFlow<Map<String, String>> = mutable.asStateFlow()

    fun save(title: String, content: String) {
        if (title.isBlank() || content.isBlank()) return
        mutable.update { it + (title to content.trim()) }
        persist()
    }

    fun delete(title: String) {
        mutable.update { it - title }
        persist()
    }

    private fun persist() {
        prefs.edit().putString(KEY_CONTENTS, JSONObject(mutable.value).toString()).apply()
    }

    private fun load(): Map<String, String> = runCatching {
        val json = JSONObject(prefs.getString(KEY_CONTENTS, "{}") ?: "{}")
        buildMap { json.keys().forEach { key -> put(key, json.optString(key)) } }
    }.getOrDefault(emptyMap())

    companion object {
        private const val PREFS_NAME = "lulu_study_collection_content"
        private const val KEY_CONTENTS = "contents"
        fun create(context: Context): StudyCollectionContentStore = StudyCollectionContentStore(context.applicationContext)
    }
}

object StudyCollectionContents {
    private var internal: StudyCollectionContentStore? = null
    val store: StudyCollectionContentStore get() = checkNotNull(internal) { "StudyCollectionContents 尚未初始化" }
    fun initialize(context: Context) { if (internal == null) internal = StudyCollectionContentStore.create(context) }
}
