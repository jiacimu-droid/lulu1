package com.jiacimu.lulu.system

import org.json.JSONObject

/**
 * Validate the character's chosen virtual-screen action BEFORE opening or
 * changing the shell session. An invalid model argument is a rejected decision,
 * not a broken ADB transport or permission to forget an already running App.
 * This policy only validates transport fields; it never chooses an action.
 */
internal object LuluBackgroundPhoneArgumentPolicy {
    private fun integer(args: JSONObject, key: String): Int? {
        val raw = args.opt(key)
        return when (raw) {
            is Int -> raw
            is Long -> raw.takeIf { it >= Int.MIN_VALUE.toLong() && it <= Int.MAX_VALUE.toLong() }?.toInt()
            is Number -> raw.toDouble().takeIf {
                it.isFinite() && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE &&
                    it % 1.0 == 0.0
            }?.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
    }

    private fun coordinateError(args: JSONObject, names: List<String>): String? {
        val invalid = names.filter { name ->
            val value = integer(args, name)
            value == null || value !in 0..1000
        }
        return if (invalid.isEmpty()) null
            else "坐标缺失或超出0到1000范围：" + invalid.joinToString(", ") +
                "；请根据当前后台画面重新给出完整坐标"
    }

    fun invalidReason(action: String, args: JSONObject): String? = when (action) {
        "tap" -> coordinateError(args, listOf("x", "y"))
        "swipe" -> coordinateError(args, listOf("startX", "startY", "endX", "endY"))
            ?: if (args.has("durationMs") &&
                (integer(args, "durationMs")?.let { it > 0 } != true))
                    "durationMs 必须是正整数" else null
        "type" -> args.optString("text").let {
            if (it.isBlank() || it.length > 500) "text 必须是1到500个字符" else null
        }
        "launch" -> if (args.optString("packageName").isBlank() &&
            args.optString("appName").isBlank())
            "launch 必须提供已安装应用的准确 packageName 或 appName" else null
        else -> null
    }
}
