package com.jiacimu.lulu

import com.jiacimu.lulu.data.CompanionPresenceState

/** Do not make one executed scene action appear twice as an enduring status. */
internal object PresencePresentation {
    fun status(state: CompanionPresenceState): String {
        val mood = state.mood.trim()
        val condition = state.statusText.trim()
        val action = state.gesture.trim()
        val meaningful = condition.takeUnless { c ->
            c.isBlank() || (action.isNotBlank() &&
                (c == action || action.contains(c) || c.contains(action))) ||
                (mood.isNotBlank() && (c == mood || mood.contains(c) || c.contains(mood)))
        }
        return listOf(mood, meaningful.orEmpty()).filter(String::isNotBlank).distinct().joinToString(" · ")
    }
}
