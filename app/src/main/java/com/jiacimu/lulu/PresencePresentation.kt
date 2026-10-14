package com.jiacimu.lulu

import com.jiacimu.lulu.data.CompanionPresenceState

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

    fun uniqueMood(value: String): String =
        clean(value).split(Regex("\\s*[·•；;]+\\s*"))
            .map(String::trim).filter(String::isNotBlank)
            .distinctBy { it.replace(Regex("\\s+"), "") }
            .joinToString(" · ")

    fun stateFields(state: CompanionPresenceState): List<Pair<String, String>> {
        val mood = uniqueMood(state.mood)
        val status = clean(state.statusText).takeUnless {
            it.isBlank() || (mood.isNotBlank() && equivalent(it, mood))
        }.orEmpty()
        val gesture = clean(state.gesture).takeUnless {
            it.isBlank() || (mood.isNotBlank() && equivalent(it, mood)) ||
                (status.isNotBlank() && equivalent(it, status))
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
