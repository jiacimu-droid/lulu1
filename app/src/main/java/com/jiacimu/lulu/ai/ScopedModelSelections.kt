package com.jiacimu.lulu.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private const val CHAT_CHARACTER_PREFIX = "chat_character:"
    private val mutableRevision = MutableStateFlow(0L)
    val changes = mutableRevision.asStateFlow()

    private fun chatKey(characterId: String): String {
        require(characterId.isNotBlank()) { "角色 ID 不能为空" }
        return key(CHAT_CHARACTER_PREFIX + characterId)
    }

    /** A missing override follows the current public chat model.
     * Selecting A never changes B or the global model. */
    fun selectedCharacterChatArchiveId(characterId: String,
        library: ModelLibraryState = LuluAiServices.connectionStore.library.value): String? {
        val selected = prefs?.getString(chatKey(characterId), null)
        return selected?.takeIf { id ->
            library.archives.any { it.id == id && !it.model.startsWith("jev-") }
        } ?: library.archiveIdFor(ModelUsage.Chat)
    }

    fun selectCharacterChatArchive(characterId: String, archiveId: String,
        library: ModelLibraryState = LuluAiServices.connectionStore.library.value) {
        require(library.archives.any { it.id == archiveId && !it.model.startsWith("jev-") }) {
            "请选择已保存的聊天模型"
        }
        check(checkNotNull(prefs) { "角色模型尚未初始化" }
            .edit().putString(chatKey(characterId), archiveId).commit()) {
            "角色模型保存失败"
        }
        mutableRevision.value++
    }

    fun clearCharacterChatArchive(characterId: String) {
        check(checkNotNull(prefs) { "角色模型尚未初始化" }
            .edit().remove(chatKey(characterId)).commit()) {
            "角色模型重置失败"
        }
        mutableRevision.value++
    }

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
