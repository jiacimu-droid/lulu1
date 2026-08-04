package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.ai.ModelConnection

internal object MemoryModelRuntime {
    private var prefs: android.content.SharedPreferences? = null

    fun initialize(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
    }

    fun vectorEnabled(): Boolean = prefs?.getBoolean("memory_vector_enabled", false) == true
    fun rerankEnabled(): Boolean = prefs?.getBoolean("memory_rerank_enabled", false) == true
    fun embeddingConnection(): ModelConnection? = connection("memory_embedding")
    fun rerankConnection(): ModelConnection? = connection("memory_rerank")

    private fun connection(prefix: String): ModelConnection? {
        val settings = prefs ?: return null
        val baseUrl = settings.getString("${prefix}_url", "").orEmpty().trim().trimEnd('/')
        val apiKey = settings.getString("${prefix}_key", "").orEmpty().trim()
        val model = settings.getString("${prefix}_model", "").orEmpty().trim()
        return if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) null else ModelConnection(baseUrl, apiKey, model)
    }
}
