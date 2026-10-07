package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONObject

data class Capability(val name: String, val domain: String, val permission: String, val successEvidence: String, val retrySafe: Boolean)

object CapabilityRegistry {
    val device = listOf(
        Capability("get_battery", "phone", "device_read", "Android battery broadcast", true),
        Capability("get_location", "phone", "device_read", "location permission and timestamped fix", true),
        Capability("get_current_app", "phone", "device_read", "usage/accessibility snapshot", true),
        Capability("read_recent_notifications", "phone", "device_read", "notification listener snapshot", true),
        Capability("create_alarm", "phone", "alarm", "AlarmManager registration or explicit pending clock confirmation", false),
        Capability("list_alarms", "phone", "device_read", "character-owned persisted alarm records", true),
        Capability("cancel_alarm", "phone", "alarm", "character-owned alarm cancellation", false),
        Capability("read_screen", "phone", "device_read", "fresh accessibility snapshot", true),
        Capability("click_text", "phone", "screen", "post-action screen observation", false),
        Capability("screen_action", "phone", "screen", "post-action screen observation", false),
        Capability("screen_sequence", "phone", "screen", "bounded fresh observations and expected text per step", false),
        Capability("cloud_task", "cloud", "cloud", "persistent task id; file success comes later", false),
    )
    val application = listOf("send_private_message", "send_group_message", "send_game_invite", "play_solo_game",
        "publish_moment", "write_journal", "start_call", "read_book", "send_world_invite", "digital_world_action", "grant_sleep_reward")
        .map { Capability(it, if (it == "digital_world_action") "world" else "app", "existing_app_policy", "existing executor persisted result", false) }
    fun find(name: String): Capability? = (device + application).firstOrNull { it.name == name }

    fun allows(context: Context, characterId: String, capability: Capability, userRequested: Boolean): Boolean {
        if (capability.domain == "app" || capability.domain == "world") return true // Existing executor owns contact/world policy.
        if (userRequested) return true
        return context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
            .getBoolean("$characterId:${capability.permission}", false)
    }

    fun context(context: Context, characterId: String): String = device.joinToString("\n", "统一手机/云端能力（离线手机操作不能由云端宣称成功）：\n") { cap ->
        "- ${cap.name}；主动允许=${allows(context, characterId, cap, false)}；依据=${cap.successEvidence}" +
            if (cap.name == "cloud_task") "；args={kind:research|pptx|docx,request:需求,sources:[真实HTTPS网址],notes:提供的原文}；服务已配置=${CloudTaskBridge.isConfigured()}"
            else if (cap.name == "screen_sequence") "；args={allowedPackages:[用户授权的应用包名],steps:[{text:点击文字,expectedText:动作后验证文字}]}；最多5步" else ""
    }
}
