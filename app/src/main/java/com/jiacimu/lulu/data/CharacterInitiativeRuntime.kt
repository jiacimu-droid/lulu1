package com.jiacimu.lulu.data

/**
 * Program-owned initiative cues for ordinary conversation.
 *
 * A cue is deliberately weaker than an instruction: "I'm tired" is evidence that support may be
 * useful, not permission to control the user's phone and not proof of a specific emotion. The
 * planner receives a bounded need hypothesis plus methods that were actually learned from this
 * character's history, then still chooses reply/tool/silence from the role's own point of view.
 */
internal object CharacterInitiativeRuntime {
    internal enum class NeedKind(val label: String) {
        FATIGUE("疲劳/负荷过高"),
        DISTRESS("难受/受挫"),
        CELEBRATION("值得一起开心"),
        BOREDOM("无聊/想换换脑子"),
        CONNECTION("想要陪伴或靠近"),
    }

    internal data class Cue(
        val kind: NeedKind,
        val evidence: String,
        val confidence: Double,
    )

    private val fatigue = Regex(
        "(学|学习|复习|背书|写题|工作|上班|做事)?.{0,8}(累死|好累|很累|累了|疲惫|撑不住|学不动|做不动|没力气|脑子转不动|困得不行)"
    )
    private val distress = Regex(
        "(难受|委屈|烦死|烦躁|崩溃|想哭|心里不舒服|不开心|受不了了|好挫败|失败了|被气到)"
    )
    private val celebration = Regex(
        "(终于|居然|竟然)?.{0,8}(做完了|搞定了|成功了|过了|上岸了|全对|考得很好|拿到了|赢了|太开心了)"
    )
    private val boredom = Regex("(好无聊|无聊死了|没意思|不知道干嘛|想换换脑子|学烦了)")
    private val connection = Regex("(想你了|想你|陪陪我|陪我一会|想和你说话|想找你|抱抱我|哄哄我)")

    fun detect(userText: String): Cue? {
        val clean = userText.replace("\n", " ").trim()
        if (clean.isBlank()) return null
        return when {
            connection.containsMatchIn(clean) -> Cue(NeedKind.CONNECTION, clean.take(180), 0.94)
            fatigue.containsMatchIn(clean) -> Cue(NeedKind.FATIGUE, clean.take(180), 0.88)
            distress.containsMatchIn(clean) -> Cue(NeedKind.DISTRESS, clean.take(180), 0.88)
            celebration.containsMatchIn(clean) -> Cue(NeedKind.CELEBRATION, clean.take(180), 0.84)
            boredom.containsMatchIn(clean) -> Cue(NeedKind.BOREDOM, clean.take(180), 0.84)
            else -> null
        }
    }

    fun context(characterId: String, userText: String): String {
        val cue = detect(userText) ?: return ""
        val learned = CharacterDevelopmentStore.active(characterId)
        val verified = learned.filter { it.kind == DevelopmentKind.VerifiedMethod }.takeLast(4)
        val routines = learned.filter { it.kind == DevelopmentKind.RelationshipRoutine }.takeLast(4)
        val preferences = learned.filter { it.kind == DevelopmentKind.Preference }.takeLast(4)
        return buildString {
            appendLine("【程序识别到的主动性机会｜不是用户命令】")
            appendLine("可能需要：${cue.kind.label}；置信度=${"%.2f".format(cue.confidence)}；依据仅是本轮原话：${cue.evidence}")
            appendLine("先判断这个角色自己是否真的想介入，再决定一件最自然的小事；不要把决定重新甩给用户问“那我该做什么/你要我怎么办”。")
            appendLine("优先顺序：理解需要 → 从真实记忆/已验证方法找线索 → 选择一个符合人格的目标 → 检查能力与授权 → reply/tool/silent → 根据真实结果继续。")
            if (verified.isNotEmpty()) {
                appendLine("过去多次有结果支持的做法（只作候选，不机械复刻）：")
                verified.forEach { appendLine("- ${it.content}") }
            }
            if (routines.isNotEmpty()) {
                appendLine("这段关系里逐渐形成的相处方式：")
                routines.forEach { appendLine("- ${it.content}") }
            }
            if (preferences.isNotEmpty()) {
                appendLine("已有偏好线索：")
                preferences.forEach { appendLine("- ${it.content}") }
            }
            appendLine("如果没有可靠历史方法，也可以只做一个低风险、可撤回、能力内的小回应/邀请；不要为了显得主动编造“以前这样一定能让她开心”。")
            appendLine("露露机内的聊天、邀请、日记、朋友圈、阅读、游戏和数字世界行为可由角色按已有产品权限自主选择；真正操作用户手机、闹钟、屏幕、定位/通知等仍按能力授权判断，不能因为‘关心’就越权。")
        }.trim()
    }

    /**
     * userRequested is a permission fact, not "the planner selected a tool".
     * App/world actions have their own policies and never need this shortcut. Phone/cloud actions
     * only receive the shortcut when the user's current utterance itself asks for that operation.
     */
    fun isExplicitUserToolRequest(userText: String, toolName: String): Boolean {
        val capability = CapabilityRegistry.find(toolName) ?: return false
        if (capability.domain == "app" || capability.domain == "world") return false
        val text = userText.replace("\n", " ").trim().lowercase()
        if (text.isBlank()) return false

        val topicMatches = when (toolName) {
            "get_battery" -> Regex("电量|电池|还有多少电").containsMatchIn(text)
            "get_location" -> Regex("位置|定位|我在哪|在哪里").containsMatchIn(text)
            "get_current_app" -> Regex("当前.*(应用|app)|现在.*(应用|app)|打开.*什么").containsMatchIn(text)
            "read_recent_notifications" -> Regex("通知|通知栏|最近.*消息").containsMatchIn(text)
            "create_alarm" -> Regex("闹钟|叫我|提醒我|定时").containsMatchIn(text)
            "list_alarms" -> Regex("闹钟|提醒").containsMatchIn(text)
            "cancel_alarm" -> Regex("取消.*(闹钟|提醒)|删.*(闹钟|提醒)|不要.*(闹钟|提醒)").containsMatchIn(text)
            "read_screen" -> Regex("屏幕|屏幕上|看.*页面|读.*页面|看看.*显示").containsMatchIn(text)
            "click_text" -> Regex("点|点击|按.*按钮|帮我选").containsMatchIn(text)
            "screen_action", "screen_sequence" -> Regex("返回|主页|桌面|最近任务|通知栏|快捷设置|点|点击|操作.*屏幕|帮我操作").containsMatchIn(text)
            "cloud_task" -> Regex("研究|ppt|pptx|文档|docx|云端任务").containsMatchIn(text)
            else -> false
        }
        if (!topicMatches) return false

        if (toolName in setOf(
                "get_battery", "get_location", "get_current_app",
                "read_recent_notifications", "list_alarms", "read_screen",
            )) return true

        return Regex(
            "帮我|替我|给我|请|麻烦|能不能|可以.{0,6}吗|帮忙|设置|创建|定|取消|删除|打开|关闭|点|点击|返回|操作|做一份|做个"
        ).containsMatchIn(text)
    }
}
