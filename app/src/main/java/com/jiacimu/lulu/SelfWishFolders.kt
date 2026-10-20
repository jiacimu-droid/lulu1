package com.jiacimu.lulu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class SelfWishFolder(val id: String, val name: String)

/**
 * Folder metadata only. Wishes remain in the original self-preface entries store.
 * Never migrate, delete or rewrite user text just to create/rename a folder.
 * A malformed stored JSON returns null, so callers must NOT overwrite it.
 */
internal object SelfWishFolders {
    const val ALL = "all"
    const val UNFILED = "unfiled"
    const val LEGACY_HELD = "legacy-held"
    const val COMPLETED = "fulfilled"

    private const val PREFS = "lulu_self_preface_v1"
    private const val KEY = "wish_folders_v1"
    private val reserved = setOf(ALL, UNFILED, LEGACY_HELD, COMPLETED)

    fun read(context: Context): List<SelfWishFolder>? = runCatching {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return@runCatching emptyList()
        val json = JSONArray(raw)
        (0 until json.length()).map { index ->
            val item = json.getJSONObject(index)
            val id = item.getString("id").trim()
            val name = item.getString("name").trim()
            require(id.isNotEmpty() && id !in reserved && name.isNotEmpty())
            SelfWishFolder(id, name)
        }.also { rows ->
            require(rows.distinctBy { it.id }.size == rows.size)
        }
    }.getOrNull()

    fun create(context: Context, existing: List<SelfWishFolder>, title: String): List<SelfWishFolder>? {
        val name = title.trim().take(32)
        if (name.isEmpty() || existing.any { it.name.equals(name, ignoreCase = true) }) return null
        val current = read(context) ?: return null
        if (current != existing) return null
        val updated = current + SelfWishFolder(UUID.randomUUID().toString(), name)
        return if (persist(context, updated)) updated else null
    }

    fun rename(context: Context, existing: List<SelfWishFolder>, id: String, title: String): List<SelfWishFolder>? {
        val name = title.trim().take(32)
        if (name.isEmpty() || existing.none { it.id == id } ||
            existing.any { it.id != id && it.name.equals(name, ignoreCase = true) }) return null
        val current = read(context) ?: return null
        if (current != existing) return null
        val updated = current.map { if (it.id == id) it.copy(name = name) else it }
        return if (persist(context, updated)) updated else null
    }

    private fun persist(context: Context, folders: List<SelfWishFolder>): Boolean = runCatching {
        val array = JSONArray()
        folders.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name)) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).commit()
    }.getOrDefault(false)

    fun categoryId(page: SelfPageEntry): String = when {
        page.wishFolderId.isNotBlank() -> page.wishFolderId
        SelfCompassRules.state(page) == SelfCompassRules.HELD -> LEGACY_HELD
        else -> UNFILED
    }

    fun categoryName(id: String, folders: List<SelfWishFolder>): String = when (id) {
        UNFILED -> "未分类"
        LEGACY_HELD -> "原珍藏"
        else -> folders.firstOrNull { it.id == id }?.name ?: "未分类"
    }

    fun filter(pages: List<SelfPageEntry>, tab: String): List<SelfPageEntry> =
        pages.filter { page ->
            val completed = SelfCompassRules.state(page) == SelfCompassRules.FULFILLED
            when (tab) {
                COMPLETED -> completed
                ALL -> !completed
                else -> !completed && categoryId(page) == tab
            }
        }.sortedWith(compareByDescending<SelfPageEntry> { it.starred }
            .thenByDescending { it.updated }.thenBy { it.id })

    fun setCompleted(page: SelfPageEntry, completed: Boolean, now: Long): SelfPageEntry {
        if ((SelfCompassRules.state(page) == SelfCompassRules.FULFILLED) == completed) return page
        // Store old "held" membership before replacing its state with "fulfilled".
        val preservedFolder = if (completed && page.wishFolderId.isBlank() &&
            SelfCompassRules.state(page) == SelfCompassRules.HELD) LEGACY_HELD else page.wishFolderId
        return page.copy(
            state = if (completed) SelfCompassRules.FULFILLED else SelfCompassRules.ACTIVE,
            done = completed,
            wishFolderId = preservedFolder,
            updated = now,
        )
    }
}
