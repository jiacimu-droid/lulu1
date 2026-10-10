package com.jiacimu.lulu.data

import android.content.Context

/**
 * Persistent, bounded acknowledgement of observed *stimuli*, not messages read
 * or actions completed. Worker rescheduling must not create a new emotion.
 */
internal object PerceptionStimulusLedger {
    private var prefs: android.content.SharedPreferences? = null

    @Synchronized fun initialize(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("lulu_perception_stimuli", Context.MODE_PRIVATE)
    }

    @Synchronized fun hasSeen(context: Context, characterId: String, evidenceId: String): Boolean {
        if (prefs == null) initialize(context)
        val token = evidenceId.hashCode().toString()
        return prefs?.getString("seen:$characterId", "").orEmpty().lineSequence().any { it == token }
    }

    fun claim(context: Context, characterId: String, stimulus: PerceptionStimulus): Boolean {
        if (characterId.isBlank() || stimulus.evidenceId.isBlank()) return false
        val pref = prefs ?: run {
            initialize(context)
            checkNotNull(prefs)
        }
        val key = "seen:$characterId"
        val token = stimulus.evidenceId.hashCode().toString()
        synchronized(this) {
            val seen = pref.getString(key, "").orEmpty().lineSequence()
                .filter(String::isNotBlank).toList()
            if (token in seen) return false
            check(pref.edit().putString(key, (seen.takeLast(63) + token).joinToString("\n")).commit()) {
                "未能保存本轮感知来源"
            }
        }
        return true
    }

    @Synchronized fun invalidate(eventId: String) {
        if (eventId.isBlank()) return
        val sourceIds = setOf(eventId, eventId.substringBefore(":group:"))
        val tokens = sourceIds.map { it.hashCode().toString() }.toSet()
        val store = prefs ?: return
        store.all.keys.filter { it.startsWith("seen:") }.forEach { key ->
            val old = store.getString(key, "").orEmpty()
            val remaining = old.lineSequence().filter { token -> token !in tokens }
                .filter(String::isNotBlank).joinToString("\n")
            if (remaining != old) store.edit().putString(key, remaining).apply()
        }
    }

    @Synchronized fun clear(characterId: String) {
        prefs?.edit()?.remove("seen:$characterId")?.apply()
    }
}
