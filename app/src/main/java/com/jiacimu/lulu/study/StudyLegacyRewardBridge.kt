package com.jiacimu.lulu.study

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

class StudyLegacyRewardBridge private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun sync(totalStudyMinutes: Int): String? {
        val credited = prefs.getInt(KEY_CREDITED_MINUTES, 0).coerceAtLeast(0)
        val safeTotal = totalStudyMinutes.coerceAtLeast(0)
        if (safeTotal <= credited) return null
        val delta = safeTotal - credited
        val message = StudyLegacyRewards.store.rewardFocus(delta)
        prefs.edit().putInt(KEY_CREDITED_MINUTES, safeTotal).apply()
        return message
    }

    companion object {
        private const val PREFS_NAME = "lulu_study_legacy_bridge"
        private const val KEY_CREDITED_MINUTES = "credited_minutes"
        fun create(context: Context): StudyLegacyRewardBridge = StudyLegacyRewardBridge(context.applicationContext)
    }
}

object StudyLegacyRewardBridges {
    private var internal: StudyLegacyRewardBridge? = null
    val main: StudyLegacyRewardBridge get() = checkNotNull(internal) { "StudyLegacyRewardBridges 尚未初始化" }
    fun initialize(context: Context) { if (internal == null) internal = StudyLegacyRewardBridge.create(context) }
}

@Composable
internal fun StudyLegacyRewardSync(state: StudyState) {
    LaunchedEffect(state.profile.totalStudyMinutes) {
        StudyLegacyRewardBridges.main.sync(state.profile.totalStudyMinutes)
    }
}
