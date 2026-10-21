package com.jiacimu.lulu

import com.jiacimu.lulu.data.CompanionPresenceState
import com.jiacimu.lulu.data.PresenceFieldBoundary

/**
 * One moment, three distinct dimensions. Old model turns can accidentally
 * echo the same emotion across mood/status/gesture or repeat it with " · ".
 * Presentation must remove those echoes without losing a real ongoing action.
 */
internal object PresencePresentation {
    private fun clean(value: String): String = value.trim().replace(Regex("\\s+"), " ")
    private fun equivalent(a: String, b: String): Boolean {
        val left = clean(a).replace(Regex("[\\s，。！？!?、；;：:·]+"), "")
        val right = clean(b).replace(Regex("[\\s，。！？!?、；;：:·]+"), "")
        return left.isNotBlank() && (left == right || left.contains(right) || right.contains(left))
    }

    /** Keep independent ongoing concerns in 状态, but leave the confirmed
     * physical event to 动作. Only remove an exact, completed leading action,
     * never try to interpret the character's intention by word matching. */
    private fun removeLeadingRepeatedAction(status: String, action: String): String {
        val state = clean(status)
        val performed = clean(action)
        if (performed.isBlank() || !state.startsWith(performed)) return state
        val rest = state.drop(performed.length)
        if (rest.isBlank()) return ""
        if (rest.first() !in "，,。;；：:、 \n") return state
        return rest.trimStart { it.isWhitespace() || it in "，,。;；：:、" }
    }

    fun uniqueMood(value: String): String =
        clean(value).split(Regex("\\s*[·•；;]+\\s*"))
            .map(String::trim).filter(String::isNotBlank)
            .distinctBy { it.replace(Regex("\\s+"), "") }
            .joinToString(" · ")

    fun stateFields(state: CompanionPresenceState): List<Pair<String, String>> {
        val mood = uniqueMood(state.mood)
        val gestureRaw = clean(state.gesture)
        val status = removeLeadingRepeatedAction(state.statusText, gestureRaw).takeUnless {
            it.isBlank() || (mood.isNotBlank() && equivalent(it, mood)) ||
                PresenceFieldBoundary.repeatsConfirmedAction(it, gestureRaw)
        }.orEmpty()
        val gesture = gestureRaw.takeUnless {
            it.isBlank() || (mood.isNotBlank() && equivalent(it, mood)) ||
                (status.isNotBlank() &&
                (equivalent(it, status) ||
                    PresenceFieldBoundary.repeatsConfirmedAction(status, it)))
        }.orEmpty()
        return listOf(
            "心情" to mood,
            "状态" to status,
            "动作" to gesture,
        ).filter { it.second.isNotBlank() }
    }

    fun status(state: CompanionPresenceState): String =
        stateFields(state).filter { it.first != "动作" }.joinToString(" · ") { it.second }
}
