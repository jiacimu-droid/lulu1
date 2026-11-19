package com.jiacimu.lulu.system

import org.json.JSONObject

/**
 * Validate the character's chosen virtual-screen action BEFORE opening or
 * changing the shell session. An invalid model argument is a rejected decision,
 * not a broken ADB transport or permission to forget an already running App.
 * This policy only validates transport fields; it never chooses an action.
 */
internal object LuluBackgroundPhoneArgumentPolicy {
    const val INDEPENDENT_MODE = "independent_virtual_display"
    const val FOREGROUND_MODE = "foreground_accessibility"

    fun usesMainScreen(mode: String, foregroundActive: Boolean, independentAvailable: Boolean): Boolean =
        when (mode) {
            INDEPENDENT_MODE -> false
            FOREGROUND_MODE -> true
            else -> foregroundActive || !independentAvailable
        }

    fun inheritMode(args: JSONObject, actualMode: String): JSONObject = args.apply {
        if (actualMode == INDEPENDENT_MODE || actualMode == FOREGROUND_MODE) put("mode", actualMode)
    }

    fun privateBrowse(args: JSONObject): Boolean =
        args.optString("mode") == INDEPENDENT_MODE && args.opt("reportToUser") == false

    fun verifiedPrivateBrowseLaunch(args: JSONObject, receipt: JSONObject): Boolean =
        privateBrowse(args) && args.optString("action") == "launch" && receipt.optBoolean("success") &&
            receipt.optString("mode") == INDEPENDENT_MODE && receipt.optInt("displayId", 0) > 0 &&
            receipt.optString("packageName").isNotBlank() && receipt.optBoolean("targetWindowVerified") &&
            receipt.optBoolean("imageForwardedToRole") && receipt.optBoolean("frameValidated")

    // The action contract is shared by the model's main turn and the background
    // continuation loop. Unknown actions are rejected as model arguments,
    // never as broken shell transport (which would discard a valid display).
    private val supportedActions = setOf(
        "start", "launch", "adopt_app", "tap", "long_press", "tap_element", "swipe", "back",
        "type", "focus_search", "submit", "search", "send_chat_message",
        "observe", "stop", LuluVirtualCommentPolicy.ACTION, LuluVirtualNavigation.ACTION, LuluWeChatMomentsPolicy.OPEN_COMPOSER, LuluWeChatMomentsPolicy.PUBLISH,
    )

    fun isSupportedAction(action: String): Boolean = action in supportedActions

    fun needsSnapshot(action: String): Boolean =
        action in setOf("tap", "long_press", "tap_element", "send_chat_message") || action == LuluVirtualCommentPolicy.ACTION ||
            action == LuluWeChatMomentsPolicy.OPEN_COMPOSER || action == LuluWeChatMomentsPolicy.PUBLISH

    fun needsElement(action: String): Boolean =
        action == "tap_element" || action == LuluWeChatMomentsPolicy.OPEN_COMPOSER

    private fun unsupportedActionReason(action: String): String =
        "后台手机 args.action=${action.ifBlank { "（缺失）" }.take(64)} 不在支持的操作中。" +
            "要打开微信等已安装App，请明确选择 " +
            "args={\"action\":\"launch\",\"appName\":\"微信\"}；" +
            "只创建虚拟屏用start，读取已有页面用observe；" +
            "其他可选操作为tap/long_press/tap_element/navigate/swipe/back/type/focus_search/submit/search/" +
            "send_chat_message/publish_comment/open_wechat_moments_composer/publish_wechat_moment/stop。动作不合法时没有操作手机，保留已有会话。"

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

    fun invalidReason(action: String, args: JSONObject): String? {
        if (!isSupportedAction(action)) return unsupportedActionReason(action)
        if ((action == LuluVirtualNavigation.ACTION ||
                (action == "launch" && args.has(LuluVirtualNavigation.LABELS))) &&
            LuluVirtualNavigation.labels(args) == null)
            return "navigationLabels 需要按顺序提供1到4个页面上准确可见的导航名称"
        return when (action) {
        "tap", "long_press" -> coordinateError(args, listOf("x", "y")) ?: when {
            args.optString("screenSnapshotId").isBlank() -> "看图点击或长按必须绑定当前screenSnapshotId"
            args.optString("target").trim().length !in 1..120 -> "target需要说明本次真实画面中要点击或长按的对象"
            action == "long_press" && args.has("durationMs") &&
                integer(args, "durationMs")?.let { it in 1..LuluPhoneInteractionPolicy.MAX_HOLD_MS } != true ->
                "长按durationMs必须为1到${LuluPhoneInteractionPolicy.MAX_HOLD_MS}毫秒"
            else -> null
        }
        "swipe" -> coordinateError(args, listOf("startX", "startY", "endX", "endY"))
            ?: if (args.has("durationMs") &&
                (integer(args, "durationMs")?.let { it > 0 } != true))
                    "durationMs 必须是正整数" else null
        "type" -> when {
            args.optString("text").isBlank() ||
                args.optString("text").length > LuluVirtualTextInputPolicy.MAX_TEXT_CHARS ->
                "text 必须是1到${LuluVirtualTextInputPolicy.MAX_TEXT_CHARS}个字符"
            args.has("inputX") != args.has("inputY") ->
                "视觉文字输入必须同时提供inputX/inputY，不能猜测另一个坐标"
            args.has("inputX") && coordinateError(args, listOf("inputX", "inputY")) != null ->
                coordinateError(args, listOf("inputX", "inputY"))
            args.has("inputX") && args.optString("screenSnapshotId").isBlank() ->
                "视觉文字输入必须绑定当前后台屏的screenSnapshotId，拒绝旧截图坐标"
            else -> null
        }
        "search" -> args.optString("text").let {
            if (it.isBlank() || it.length > 500) "text 必须是1到500个字符" else null
        }
        "send_chat_message" -> when {
            !LuluVirtualChatSendPolicy.validRecipient(args.optString("recipient")) ->
                "发送聊天消息需要明确的收件人（1到80字符）"
            !LuluVirtualChatSendPolicy.validMessage(args.optString("text")) ->
                "聊天正文必须为1到500个字符"
            args.optString("screenSnapshotId").isBlank() ->
                "发送聊天消息必须提供最近一次真实聊天界面的screenSnapshotId"
            args.has("composerX") != args.has("composerY") ->
                "视觉输入回退需要同时提供composerX和composerY；不能猜测坐标"
            args.has("composerX") && coordinateError(args, listOf("composerX", "composerY")) != null ->
                coordinateError(args, listOf("composerX", "composerY"))
            else -> null
        }
        LuluVirtualCommentPolicy.ACTION -> when {
            args.optString("screenSnapshotId").isBlank() || args.optString("editorRef").isBlank() ||
                args.optString("elementRef").isBlank() || args.optString("editorRef") == args.optString("elementRef") ->
                "发表评论需要当前screenSnapshotId、评论输入框editorRef和发表按钮elementRef"
            args.optString("text").isBlank() || args.optString("text").length > LuluVirtualTextInputPolicy.MAX_TEXT_CHARS ->
                "评论正文必须为1到${LuluVirtualTextInputPolicy.MAX_TEXT_CHARS}个字符，且与已读回草稿完全一致"
            args.optString("contextText").trim().length !in 2..160 ||
                args.optString("contextText").trim() == args.optString("text") ->
                "contextText必须为当前帖子或被回复内容中2到160字符的真实原文，不能用自己的草稿代替"
            else -> null
        }
        LuluWeChatMomentsPolicy.OPEN_COMPOSER -> if (
            args.optString("screenSnapshotId").isBlank() || args.optString("elementRef").isBlank())
            "打开微信朋友圈文字编辑页需要最新screenSnapshotId和相机elementRef" else null
        LuluWeChatMomentsPolicy.PUBLISH -> if (
            args.optString("screenSnapshotId").isBlank() ||
            !LuluVirtualChatSendPolicy.validMessage(args.optString("text")))
            "发表微信朋友圈需要最新编辑页screenSnapshotId和1到500字符正文" else null
        "adopt_app" -> if (!args.optString("packageName").matches(Regex("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+")))
            "adopt_app需要回执actualPackage中已在本独立屏出现的准确包名" else null
        "launch" -> if (args.optString("packageName").isBlank() &&
            args.optString("appName").isBlank())
            "launch 必须提供已安装应用的准确 packageName 或 appName" else null
        else -> null
        }
    }
}
