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
        Capability("list_media_sessions", "phone", "device_read", "active Android media sessions via user-enabled notification listener", true),
        Capability("control_music", "phone", "media", "Android MediaController dispatch and observable playback state; app support varies", false),
        Capability("control_volume", "phone", "media", "Observed, capped STREAM_MUSIC volume; restores previous volume on request", false),
        Capability("ring_in_call_wake", "phone", "alarm", "short native ringtone inside the current connected call; never redials", false),
        Capability("read_screen", "phone", "device_read", "fresh accessibility snapshot", true),
        Capability("capture_screen", "phone", "screen_vision", "owner-consented screenshot plus vision", false),
        Capability("summon_lulu", "phone", "screen_summon", "user-tappable notification", false),
        Capability("click_text", "phone", "screen", "post-action screen observation", false),
        Capability("screen_action", "phone", "screen", "post-action screen observation", false),
        Capability("screen_sequence", "phone", "screen", "bounded fresh observations and expected text per step", false),
        Capability("cloud_task", "cloud", "cloud", "persistent task id; file success comes later", false),
    )
    val application = listOf("send_private_message", "send_group_message", "send_private_sticker", "send_group_sticker", "send_game_invite", "play_solo_game",
        "publish_moment", "write_journal", "plan_personal_activity", "reschedule_personal_activity", "cancel_personal_activity", "start_call", "read_book", "send_world_invite", "digital_world_action", "set_user_remark", "set_self_nickname", "favorite_user_message", "grant_sleep_reward", "learn_topic")
        .map { Capability(it, if (it == "digital_world_action") "world" else "app", "existing_app_policy", "existing executor persisted result", false) }
    val external = listOf(
        Capability("read_xiaohongshu", "external", "xhs_read", "Shared local/MCP reader + confirmed note body and source", false),
        Capability("read_health_mcp", "external", "health_read", "authenticated read-only health_read response", true),
    )
    fun find(name: String): Capability? = (device + application + external).firstOrNull { it.name == name }

    fun allows(context: Context, characterId: String, capability: Capability, userRequested: Boolean): Boolean {
        if (capability.domain == "app" || capability.domain == "world") return true // Existing executor owns contact/world policy.
        if (capability.name == "capture_screen") return if (userRequested)
            com.jiacimu.lulu.system.LuluScreenAwareness.onDemandAllowed(context)
            else com.jiacimu.lulu.system.LuluScreenAwareness.autonomousAllowed(context)
        if (capability.name == "summon_lulu") return com.jiacimu.lulu.system.LuluScreenAwareness.summonAllowed(context)
        if (userRequested) return true
        val prefs = context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
        // This is read-only discovery in the user's already-authorized XHS
        // browser, not screen control or account posting. Characters may
        // explore by default; an explicit OFF preference still wins.
        if (capability.name == "read_xiaohongshu")
            return prefs.getBoolean("$characterId:xhs_read", true)
        // Authorizing media transport reasonably includes inspecting the
        // media session it will control, without granting all device_read data.
        if (capability.name == "list_media_sessions" &&
            prefs.getBoolean("$characterId:media", false)) return true
        return prefs.getBoolean("$characterId:${capability.permission}", false)
    }

    /**
     * One source of truth for model-visible screen-vision affordance. Both online
     * private chat and the foreground/phone planner consume this exact wording.
     * Capability registration, owner consent and a successful execution are distinct facts.
     */
    fun screenVisionContext(context: Context, characterId: String): String {
        val screen = com.jiacimu.lulu.system.LuluScreenAwareness
        val registered = find("capture_screen") != null
        val permittedOnDemand = registered && screen.onDemandAllowed(context)
        val permittedAutonomous = registered && screen.autonomousAllowed(context)
        val summonRegistered = find("summon_lulu") != null
        return buildString {
            appendLine("【Android 屏幕视觉 · 当前已注册能力与授权｜不是已执行的回执】")
            appendLine("capture_screen 工具已注册=$registered；主人按需识图授权=$permittedOnDemand；角色自主观察授权=$permittedAutonomous。")
            appendLine("read_screen 只能读无障碍文字，capture_screen 才会真实获取一帧屏幕并调用已配置的视觉模型。")
            if (permittedOnDemand) {
                appendLine("当你根据对话实际需求或自身好奇心决定看屏幕时，选择 action=tool,tool=capture_screen,args={}；用户刚明确请求可按需读取，自己主动读取须有自主授权且两次至少间隔5分钟。是否真的看到以及画面内容只能根据实际工具回执回答；截屏可能因锁屏、隐私排除或系统保护失败，识图有 API 费用。")
            } else {
                appendLine("目前 capture_screen 不能执行：需先由主人到露露机『权限与能力→角色屏幕视觉』阅读并同意授权、开启无障碍服务及识图模型。你可以直接提出授权请求，但不得说工具根本不存在或已经看到了屏幕。")
            }
            appendLine("summon_lulu 工具已注册=$summonRegistered；发送可点击的回到露露机通知获授权=${summonRegistered && screen.summonAllowed(context)}。已获授权时可用 action=tool,tool=summon_lulu,args={}，不是强制切回 App。")
            appendLine("以上是当前本机能力记录。不要声称自己查过『动作清单』、拿到了截图或真正打开某 App，除非本轮有相应执行回执。")
        }.trim()
    }

    fun context(context: Context, characterId: String): String = device.joinToString("\n", "统一手机/云端能力（离线手机操作不能由云端宣称成功）：\n") { cap ->
        "- ${cap.name}；主动允许=${allows(context, characterId, cap, false)}；依据=${cap.successEvidence}" +
            if (cap.name == "cloud_task") "；args={kind:research|pptx|docx,request:需求,sources:[真实HTTPS网址],notes:提供的原文}；服务已配置=${CloudTaskBridge.isConfigured()}"
            else if (cap.name == "screen_sequence") "；args={allowedPackages:[用户授权的应用包名],steps:[{text:点击文字,expectedText:动作后验证文字}]}；最多5步" else ""
    }
}
