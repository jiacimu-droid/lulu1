package com.jiacimu.lulu

import org.json.JSONObject

/** Read facts from Motion3; no inferred gesture/emotion or generated curves. */
internal data class Live2DMotionMetadata(val durationMs: Int?, val fadeInMs: Int?,
    val parameterIds: List<String>) {
    companion object {
        fun read(json: JSONObject): Live2DMotionMetadata {
            fun millis(value: Double): Int? = value.takeIf { it.isFinite() && it >= 0 &&
                it * 1000 <= Int.MAX_VALUE }?.times(1000)?.toInt()
            val curves = json.optJSONArray("Curves")
            return Live2DMotionMetadata(millis(json.optJSONObject("Meta")?.optDouble("Duration", Double.NaN) ?: Double.NaN),
                millis(json.optDouble("FadeInTime", json.optJSONObject("Meta")?.optDouble("FadeInTime", Double.NaN) ?: Double.NaN)),
                (0 until (curves?.length() ?: 0)).mapNotNull { index ->
                    curves?.optJSONObject(index)?.takeIf { it.optString("Target") == "Parameter" }
                        ?.optString("Id")?.takeIf(String::isNotBlank)
                }.distinct())
        }
    }
}
