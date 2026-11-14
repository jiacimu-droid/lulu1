package com.jiacimu.lulu.system

import android.content.Context

/**
 * Owner-controlled virtual-phone messaging permission. A screen grant alone
 * does not authorize sending messages in an unrelated third-party account.
 * This grants an affordance, never a scripted character action.
 */
internal object LuluVirtualChatSendPolicy {
    const val WECHAT = "com.tencent.mm"
    private const val PREFS = "lulu_virtual_chat_send_owner_v1"

    fun isEnabled(context: Context, characterId: String): Boolean =
        characterId.isNotBlank() && !characterId.startsWith("resident:") &&
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(characterId, false)

    fun setEnabled(context: Context, characterId: String, enabled: Boolean): Boolean {
        if (characterId.isBlank() || characterId.startsWith("resident:")) return false
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(characterId, enabled).commit()
    }

    fun validRecipient(recipient: String): Boolean =
        recipient.isNotBlank() && recipient == recipient.trim() &&
            recipient.length <= 80 && recipient.none { it == '\\n' || it == '\\r' }

    fun validMessage(message: String): Boolean =
        message.isNotBlank() && message.length <= 500

    private val nonChatEditor = Regex(
        "搜索|搜一搜|检索|search|query|password|passcode|captcha|验证码|口令|支付|payment|" +
            "评论|comment|朋友圈|moment|动态|post|用户名|昵称|account",
        RegexOption.IGNORE_CASE,
    )

    /** Only one writable non-search/non-password editor on a verified
     * WeChat conversation page may be used. Generic search stays unchanged. */
    fun chatEditorCandidate(className: String?, hint: String?, resourceId: String?): Boolean {
        val cls = className.orEmpty()
        if (!cls.contains("EditText", true)) return false
        val metadata = hint.orEmpty() + " " + resourceId.orEmpty()
        return !nonChatEditor.containsMatchIn(metadata)
    }

    /** A deliberately narrow action rather than a general allow-list override
     * for all buttons marked 发送 / submit / post. */
    fun sendButton(label: String?, resourceId: String?): Boolean {
        val text = label.orEmpty().trim()
        val id = resourceId.orEmpty().substringAfterLast('/').lowercase()
        return text.equals("发送", true) || text.equals("Send", true) ||
            (text.isBlank() && id in setOf("send", "send_btn", "btn_send"))
    }
}
