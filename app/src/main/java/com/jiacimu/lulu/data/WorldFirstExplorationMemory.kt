package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * Small persistent journal for actions that happened because the player physically explored a world.
 *
 * This deliberately stores world facts separately from generated prose: the world exists first,
 * then narrative systems may read these facts and react to them. Entries are append-only here;
 * higher-level save rollback can clear a world/save scope when needed.
 */
object WorldFirstExplorationMemory {
    data class Entry(
        val id: String,
        val worldId: String,
        val locationId: String,
        val locationLabel: String,
        val action: String,
        val occurredAt: Instant,
    )

    private const val PREFS = "lulu_world_first_exploration"
    private const val KEY = "entries_v1"
    private const val MAX_ENTRIES = 160
    private var prefs: android.content.SharedPreferences? = null
    private val lock = Any()

    fun initialize(context: Context) {
        if (prefs != null) return
        synchronized(lock) {
            if (prefs == null) {
                prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            }
        }
    }

    fun record(
        context: Context,
        worldId: String,
        locationId: String,
        locationLabel: String,
        action: String,
        now: Instant = Instant.now(),
    ): Entry? {
        val clean = action.trim().take(900)
        if (clean.isBlank()) return null
        initialize(context)
        val entry = Entry(
            id = UUID.randomUUID().toString(),
            worldId = worldId.trim().ifBlank { "world" },
            locationId = locationId.trim().ifBlank { locationLabel.trim().ifBlank { "unknown" } },
            locationLabel = locationLabel.trim().ifBlank { "未知地点" }.take(120),
            action = clean,
            occurredAt = now,
        )
        synchronized(lock) {
            val next = (decode() + entry).takeLast(MAX_ENTRIES)
            persist(next)
        }
        return entry
    }

    fun recent(
        context: Context,
        worldId: String,
        locationId: String? = null,
        limit: Int = 12,
    ): List<Entry> {
        initialize(context)
        return synchronized(lock) {
            decode()
                .asSequence()
                .filter { it.worldId == worldId }
                .filter { locationId.isNullOrBlank() || it.locationId == locationId }
                .takeLast(limit.coerceIn(1, 40))
                .toList()
        }
    }

    fun promptSection(
        context: Context,
        worldId: String,
        locationId: String? = null,
        limit: Int = 10,
    ): String {
        val entries = recent(context, worldId, locationId, limit)
        if (entries.isEmpty()) return ""
        return buildString {
            appendLine("【这个世界中已经真实发生的探索事实｜剧情必须承认，不能刷新或改写】")
            entries.forEach { entry ->
                appendLine("- [${entry.locationLabel}] ${entry.action}")
            }
        }.trim()
    }

    fun clearWorld(context: Context, worldId: String) {
        initialize(context)
        synchronized(lock) {
            persist(decode().filterNot { it.worldId == worldId })
        }
    }

    private fun decode(): List<Entry> {
        val raw = prefs?.getString(KEY, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val action = item.optString("action").trim()
                    if (action.isBlank()) continue
                    add(
                        Entry(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            worldId = item.optString("worldId").ifBlank { "world" },
                            locationId = item.optString("locationId").ifBlank { "unknown" },
                            locationLabel = item.optString("locationLabel").ifBlank { "未知地点" },
                            action = action,
                            occurredAt = runCatching { Instant.parse(item.optString("occurredAt")) }.getOrDefault(Instant.EPOCH),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("worldId", entry.worldId)
                    .put("locationId", entry.locationId)
                    .put("locationLabel", entry.locationLabel)
                    .put("action", entry.action)
                    .put("occurredAt", entry.occurredAt.toString()),
            )
        }
        prefs?.edit()?.putString(KEY, array.toString())?.apply()
    }
}
