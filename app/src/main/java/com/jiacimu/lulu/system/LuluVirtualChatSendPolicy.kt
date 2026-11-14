package com.jiacimu.lulu.system

import android.content.Context

/** Chat sending follows the owner's single virtual-phone grant.
 * Recipient, input and send-button verification still apply to each action.
 */
internal object LuluVirtualChatSendPolicy {
    const val WECHAT = "com.tencent.mm"

    fun isEnabled(context: Context, characterId: String): Boolean =
        LuluRolePhoneAuthorization.isGranted(context, characterId)

    /** Compatibility for older callers: there is no independent chat consent. */
    fun setEnabled(context: Context, characterId: String, enabled: Boolean): Boolean =
        LuluRolePhoneAuthorization.setGranted(context, characterId, enabled)

    fun validRecipient(recipient: String): Boolean =
        recipient.isNotBlank() && recipient == recipient.trim() &&
            recipient.length <= 80 && recipient.none { it.code == 10 || it.code == 13 }

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
