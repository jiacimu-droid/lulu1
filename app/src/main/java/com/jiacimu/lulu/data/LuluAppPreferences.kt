package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stable application-level preferences shared by the rebuilt pages. */
data class LuluAppPreferences(
    val largerText: Boolean = false,
    val reduceMotion: Boolean = false,
    val showMessageTimestamps: Boolean = true,
    val autoScrollChat: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val proactiveContactEnabled: Boolean = true,
    val proactiveCallsEnabled: Boolean = false,
    val quietHoursEnabled: Boolean = true,
    val quietStartHour: Int = 23,
    val quietEndHour: Int = 7,
)

object LuluAppPreferencesStore {
    private val mutable = MutableStateFlow(LuluAppPreferences())
    val state: StateFlow<LuluAppPreferences> = mutable.asStateFlow()

    private var prefs: android.content.SharedPreferences? = null
    private val lock = Any()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            mutable.value = load()
        }
    }

    fun update(transform: (LuluAppPreferences) -> LuluAppPreferences) {
        synchronized(lock) {
            val next = transform(mutable.value).normalized()
            mutable.value = next
            persist(next)
        }
    }

    fun reset() {
        synchronized(lock) {
            val next = LuluAppPreferences()
            mutable.value = next
            prefs?.edit()?.clear()?.apply()
        }
    }

    private fun load(): LuluAppPreferences {
        val source = prefs ?: return LuluAppPreferences()
        return LuluAppPreferences(
            largerText = source.getBoolean(KEY_LARGER_TEXT, false),
            reduceMotion = source.getBoolean(KEY_REDUCE_MOTION, false),
            showMessageTimestamps = source.getBoolean(KEY_SHOW_TIMESTAMPS, true),
            autoScrollChat = source.getBoolean(KEY_AUTO_SCROLL, true),
            notificationsEnabled = source.getBoolean(KEY_NOTIFICATIONS, true),
            proactiveContactEnabled = source.getBoolean(KEY_PROACTIVE_CONTACT, true),
            proactiveCallsEnabled = source.getBoolean(KEY_PROACTIVE_CALLS, false),
            quietHoursEnabled = source.getBoolean(KEY_QUIET_HOURS, true),
            quietStartHour = source.getInt(KEY_QUIET_START, 23),
            quietEndHour = source.getInt(KEY_QUIET_END, 7),
        ).normalized()
    }

    private fun persist(value: LuluAppPreferences) {
        prefs?.edit()
            ?.putBoolean(KEY_LARGER_TEXT, value.largerText)
            ?.putBoolean(KEY_REDUCE_MOTION, value.reduceMotion)
            ?.putBoolean(KEY_SHOW_TIMESTAMPS, value.showMessageTimestamps)
            ?.putBoolean(KEY_AUTO_SCROLL, value.autoScrollChat)
            ?.putBoolean(KEY_NOTIFICATIONS, value.notificationsEnabled)
            ?.putBoolean(KEY_PROACTIVE_CONTACT, value.proactiveContactEnabled)
            ?.putBoolean(KEY_PROACTIVE_CALLS, value.proactiveCallsEnabled)
            ?.putBoolean(KEY_QUIET_HOURS, value.quietHoursEnabled)
            ?.putInt(KEY_QUIET_START, value.quietStartHour)
            ?.putInt(KEY_QUIET_END, value.quietEndHour)
            ?.apply()
    }

    private fun LuluAppPreferences.normalized(): LuluAppPreferences = copy(
        quietStartHour = quietStartHour.coerceIn(0, 23),
        quietEndHour = quietEndHour.coerceIn(0, 23),
        proactiveContactEnabled = proactiveContactEnabled && notificationsEnabled,
        proactiveCallsEnabled = proactiveCallsEnabled && notificationsEnabled,
    )

    private const val PREFS_NAME = "lulu_app_preferences"
    private const val KEY_LARGER_TEXT = "larger_text"
    private const val KEY_REDUCE_MOTION = "reduce_motion"
    private const val KEY_SHOW_TIMESTAMPS = "show_message_timestamps"
    private const val KEY_AUTO_SCROLL = "auto_scroll_chat"
    private const val KEY_NOTIFICATIONS = "notifications_enabled"
    private const val KEY_PROACTIVE_CONTACT = "proactive_contact_enabled"
    private const val KEY_PROACTIVE_CALLS = "proactive_calls_enabled"
    private const val KEY_QUIET_HOURS = "quiet_hours_enabled"
    private const val KEY_QUIET_START = "quiet_start_hour"
    private const val KEY_QUIET_END = "quiet_end_hour"
}
