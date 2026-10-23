package com.jiacimu.lulu

/** Keep the selected contact tool through DOWN, MOVE and release. */
internal object Live2DTouchGesture {
    fun performed(mode: String, handGesture: String): String = when (mode) {
        "kiss" -> "kiss"
        "pat" -> when (handGesture) {
            "stroke-left" -> "slap-left"
            "stroke-right" -> "slap-right"
            else -> "slap"
        }
        else -> handGesture
    }
}

internal data class Live2DTouchContact(
    val region: String,
    val gesture: String,
    val force: Float,
    val durationMillis: Long,
)
