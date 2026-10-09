package com.jiacimu.lulu.data

import android.content.Context

/**
 * Persistent, bounded acknowledgement of observed *stimuli*, not messages read
 * or actions completed. Worker rescheduling must not create a new emotion.
 */
internal object PerceptionStimulusLedger {
    fun claim(context: Context, characterId: String, stimulus: PerceptionStimulus): Boolean {
        if (characterId.isBlank() || stimulus.evidenceId.isBlank()) return false
        val pref = context.applicationContext.getSharedPreferences("lulu_perception_stimuli", Context.MODE_PRIVATE)
        val key = "seen:$characterId"
        val token = "${stimulus.evidenceId.hashCode()}:${stimulus.description.hashCode()}"
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

    fun clear(context: Context, characterId: String) {
        context.applicationContext.getSharedPreferences("lulu_perception_stimuli", Context.MODE_PRIVATE)
            .edit().remove("seen:$characterId").apply()
    }
}
