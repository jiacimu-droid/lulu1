package com.jiacimu.lulu.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * Historical pages owned by CharacterInnerLifeStore in its EXISTING preferences.
 * Live JSON only holds a small hot window; old source-linked private snapshots
 * remain available on demand without ever entering ordinary model context.
 */
internal object CharacterInnerHistoryArchive {
    private const val PREFIX = "__lulu_inner_history__:"
    private const val META = "_historyArchiveSegments"
    private const val PAGE_SIZE = 48
    private val limits = linkedMapOf(
        "innerVoices" to 12, "thoughts" to 32, "causalTransitions" to 96,
        "emotionHistory" to 20, "closedConcerns" to 20,
    )
    fun isArchiveKey(key: String) = key.startsWith(PREFIX)
    fun fields(): Set<String> = limits.keys
    private fun key(role: String, field: String, page: Int) =
        "$PREFIX${role.length}:$role:$field:$page"
    private fun rows(array: JSONArray?): List<JSONObject> =
        (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }
    private fun read(store: SharedPreferences, role: String, field: String, page: Int): List<JSONObject> =
        runCatching { rows(JSONArray(store.getString(key(role, field, page), "[]"))) }
            .getOrDefault(emptyList())

    fun hasMatch(store: SharedPreferences?, role: String, root: JSONObject,
        predicate: (String, JSONObject) -> Boolean): Boolean {
        if (store == null) return false
        val meta = root.optJSONObject(META) ?: return false
        for (field in limits.keys) for (page in 0 until meta.optInt(field, 0)) {
            if (read(store, role, field, page).any { predicate(field, it) }) return true
        }
        return false
    }

    /** One atomic editor includes new pages, source retractions and the hot snapshot. */
    fun prepare(store: SharedPreferences, role: String, root: JSONObject,
        edit: SharedPreferences.Editor,
        remove: ((String, JSONObject) -> Boolean)? = null) {
        val meta = root.optJSONObject(META) ?: JSONObject()
        for ((field, hotLimit) in limits) {
            val count = meta.optInt(field, 0)
            if (remove != null) for (page in 0 until count) {
                val original = read(store, role, field, page)
                val retained = original.filterNot { remove(field, it) }
                if (retained.size != original.size) edit.putString(key(role, field, page),
                    JSONArray().apply { retained.forEach(::put) }.toString())
            }
            val hot = rows(root.optJSONArray(field))
            if (hot.size <= hotLimit) continue
            root.put(field, JSONArray().apply { hot.takeLast(hotLimit).forEach(::put) })
            var page = if (count == 0) 0 else count - 1
            var last = if (count == 0) emptyList() else read(store, role, field, page)
            if (remove != null) last = last.filterNot { remove(field, it) }
            for (item in hot.dropLast(hotLimit)) {
                if (last.size >= PAGE_SIZE) {
                    edit.putString(key(role, field, page),
                        JSONArray().apply { last.forEach(::put) }.toString())
                    page++
                    last = emptyList()
                }
                last = last + item
            }
            edit.putString(key(role, field, page),
                JSONArray().apply { last.forEach(::put) }.toString())
            meta.put(field, page + 1)
        }
        root.put(META, meta)
    }

    fun tail(store: SharedPreferences?, role: String, root: JSONObject,
        field: String, limit: Int): JSONArray {
        val target = limit.coerceAtLeast(0)
        val result = ArrayDeque<JSONObject>()
        rows(root.optJSONArray(field)).takeLast(target).forEach(result::addLast)
        if (store != null && result.size < target) {
            val count = root.optJSONObject(META)?.optInt(field, 0) ?: 0
            for (page in count - 1 downTo 0) {
                for (entry in read(store, role, field, page).asReversed()) {
                    if (result.size >= target) break
                    result.addFirst(entry)
                }
                if (result.size >= target) break
            }
        }
        return JSONArray().apply { result.forEach(::put) }
    }

    fun hasOlder(store: SharedPreferences?, role: String, root: JSONObject, limit: Int): Boolean {
        val prefs = store ?: return false
        val meta = root.optJSONObject(META)
        for (field in limits.keys) {
            var total = rows(root.optJSONArray(field)).size
            if (total > limit) return true
            for (page in 0 until (meta?.optInt(field, 0) ?: 0)) {
                total += read(prefs, role, field, page).size
                if (total > limit) return true
            }
        }
        return false
    }

    fun clear(role: String, root: JSONObject, edit: SharedPreferences.Editor,
        fields: Set<String> = limits.keys) {
        val meta = root.optJSONObject(META) ?: return
        for (field in fields) {
            for (page in 0 until meta.optInt(field, 0)) edit.remove(key(role, field, page))
            meta.remove(field)
        }
        root.put(META, meta)
    }
}
