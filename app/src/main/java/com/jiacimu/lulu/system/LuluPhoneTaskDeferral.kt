package com.jiacimu.lulu.system

import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

/** The character authors the time and purpose. Reuse the durable day-plan
 * engine and reacquire the app; never persist an executable old screen ref. */
internal object LuluPhoneTaskDeferral {
    fun plan(authored: JSONObject, observedPackage: String, now: Instant): JSONObject {
        require(observedPackage.isNotBlank()) { "尚未核实目标App，不能保存手机续接日程" }
        val title = authored.optString("title").trim()
        val intent = authored.optString("intent").trim()
        require(title.length in 2..100 && intent.length in 3..300) {
            "续接需要具体title和保留原目标、已做进度与未完成项的intent"
        }
        val due = OffsetDateTime.parse(authored.getString("dueAt")).toInstant()
        require(due.isAfter(now.plusSeconds(60))) { "续接时间必须由本人明确选择，且晚于当前一分钟" }
        return JSONObject().put("title", title).put("intent", intent).put("dueAt", due.toString())
            .put("repeat", "once").put("activityAction", "background_phone")
            .put("activityArgs", JSONObject().put("action", "launch").put("packageName", observedPackage))
    }
}
