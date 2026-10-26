package com.jiacimu.lulu.ai

import android.content.Context

/**
 * Model choices that belong to a feature rather than to the global Chat / Voice / Game slots.
 *
 * These preferences deliberately store only an archive id. API credentials and model definitions
 * continue to live in ModelConnectionStore, so changing a feature choice never duplicates secrets.
 */
object ScopedModelSelections {
    const val APOCALYPSE = "apocalypse_survival"
    const val THEATER = "star_wish_theater"
    const val MEETING = "meeting"
    const val RESIDENT_DAILY = "resident_daily"
    const val EXPRESSION = "character_expression"
    const val JEV = "thought_directions_jev"

    private var prefs: android.content.SharedPreferences? = null

    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun selectedArchiveId(
        scope: String,
        library: ModelLibraryState = LuluAiServices.connectionStore.library.value,
    ): String? {
        val preferences = prefs
        val saved = preferences?.getString(key(scope), null)
            ?.takeIf { candidate -> library.archives.any { it.id == candidate } }
        if (saved != null) return saved

        // First use migrates from the generic game selection only as a starting value. After this id
        // is persisted the two selectors are completely independent.
        val fallback = when (scope) {
            APOCALYPSE, THEATER -> library.archiveIdFor(ModelUsage.Game)
            MEETING -> library.archiveIdFor(ModelUsage.Chat)
            RESIDENT_DAILY, EXPRESSION, JEV -> null // Optional roles never silently select a paid model.
            else -> library.activeArchiveId?.takeIf { candidate -> library.archives.any { it.id == candidate } }
                ?: library.archives.firstOrNull()?.id
        }
        if (fallback != null) preferences?.edit()?.putString(key(scope), fallback)?.apply()
        return fallback
    }

    fun select(scope: String, archiveId: String) {
        val library = LuluAiServices.connectionStore.library.value
        require(library.archives.any { it.id == archiveId }) { "模型存档不存在" }
        checkNotNull(prefs) { "ScopedModelSelections 尚未初始化" }
            .edit()
            .putString(key(scope), archiveId)
            .apply()
    }

    fun resolveConnection(scope: String): ModelConnection {
        val archiveId = selectedArchiveId(scope)
            ?: error("请先在 API 设置中保存模型存档，再为当前应用选择模型")
        return LuluAiServices.connectionStore.resolveConnection(archiveId)
    }

    fun clear(scope: String) {
        checkNotNull(prefs) { "ScopedModelSelections 尚未初始化" }
            .edit().remove(key(scope)).apply()
    }

    /** Change the writer only at the existing expression stage, never the
     * character's decision, isolated task, or number of model calls. */
    internal fun expressionConnection(source: String, mode: CompanionContextMode,
        store: ModelConnectionStore): ModelConnection? {
        if (mode == CompanionContextMode.Isolated ||
            !com.jiacimu.lulu.data.CharacterExpressionGuide.isExpressionOnly(source)) return null
        val id = selectedArchiveId(EXPRESSION, store.library.value) ?: return null
        return store.resolveConnection(id)
    }

    private fun key(scope: String): String = "archive_${scope.trim()}"

    private const val PREFS_NAME = "lulu_scoped_model_selections_v1"
}
