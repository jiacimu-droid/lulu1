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
    const val RESIDENT_SOCIAL = "resident_social"
    const val WORLD_SOCIAL = "world_social_character"
    const val WORLD_SCENE = "world_social_scene"
    const val WORLD_LIFE = "world_life_episode"
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
        // Retired global role selectors do not override the visible choices
        // in character chat, resident management or the stable fallback.
        when (scope) {
            RESIDENT_SOCIAL -> return selectedArchiveId(RESIDENT_DAILY, library)
            WORLD_SOCIAL, WORLD_SCENE, WORLD_LIFE ->
                return library.archiveIdFor(ModelUsage.Sleep)
            EXPRESSION -> return null // Legacy separate writer no longer overrides the character.
        }
        val saved = prefs?.getString(key(scope), null)
            ?.takeIf { id -> library.archives.any { it.id == id && !it.model.startsWith("jev-") || scope == JEV && it.id == id } }
        if (saved != null) return saved

        // Screen-owned choices are independent; with no choice they dynamically
        // follow the fallback rather than caching an obsolete global game/chat id.
        return when (scope) {
            RESIDENT_DAILY, JEV -> null
            else -> library.archiveIdFor(ModelUsage.Sleep)
        }
    }

    fun select(scope: String, archiveId: String) {
        val library = LuluAiServices.connectionStore.library.value
        require(library.archives.any { it.id == archiveId }) { "模型存档不存在" }
        checkNotNull(prefs) { "ScopedModelSelections 尚未初始化" }
            .edit()
            .putString(key(scope), archiveId)
            .apply()
        mutableRevision.value++
    }

    /** All resident planning, chat and social choices share the single model
     * selected in Resident Management. Character-specific private chat is separate. */
    fun residentSocialArchiveId(
        library: ModelLibraryState = LuluAiServices.connectionStore.library.value,
    ): String? = selectedArchiveId(RESIDENT_DAILY, library)

    fun worldSocialArchiveId(characterId: String,
        library: ModelLibraryState = LuluAiServices.connectionStore.library.value,
    ): String? = if (characterId.startsWith("resident:"))
        residentSocialArchiveId(library) else library.archiveIdFor(ModelUsage.Sleep)

    fun worldSceneArchiveId(
        library: ModelLibraryState = LuluAiServices.connectionStore.library.value,
    ): String? = library.archiveIdFor(ModelUsage.Sleep)

    fun worldLifeArchiveId(characterId: String,
        library: ModelLibraryState = LuluAiServices.connectionStore.library.value,
    ): String? = if (characterId.startsWith("resident:"))
        residentSocialArchiveId(library) else library.archiveIdFor(ModelUsage.Sleep)

    fun residentSocialConnection(): ModelConnection {
        val archiveId = residentSocialArchiveId()
            ?: error("请先在居民管理中选择居民模型")
        return LuluAiServices.connectionStore.resolveConnection(archiveId)
    }

    fun worldSocialConnection(characterId: String): ModelConnection? {
        if (characterId.startsWith("resident:") && worldSocialArchiveId(characterId) == null)
            error("请先在居民管理中选择居民模型")
        return LuluAiServices.connectionStore.resolveConnection(worldSocialArchiveId(characterId))
    }

    fun worldSceneConnection(): ModelConnection =
        LuluAiServices.connectionStore.resolveConnection(worldSceneArchiveId())

    fun worldLifeConnection(characterId: String): ModelConnection? {
        if (characterId.startsWith("resident:") && worldLifeArchiveId(characterId) == null)
            error("请先在居民管理中选择居民模型")
        return LuluAiServices.connectionStore.resolveConnection(worldLifeArchiveId(characterId))
    }

    fun resolveConnection(scope: String): ModelConnection {
        val archiveId = selectedArchiveId(scope)
            ?: error("请先在 API 设置中保存模型存档，再为当前应用选择模型")
        return LuluAiServices.connectionStore.resolveConnection(archiveId)
    }

    fun clear(scope: String) {
        checkNotNull(prefs) { "ScopedModelSelections 尚未初始化" }
            .edit().remove(key(scope)).apply()
        mutableRevision.value++
    }

    private fun key(scope: String): String = "archive_${scope.trim()}"

    private const val PREFS_NAME = "lulu_scoped_model_selections_v1"
}
