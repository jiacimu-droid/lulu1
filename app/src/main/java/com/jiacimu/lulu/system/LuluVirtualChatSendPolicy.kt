package com.jiacimu.lulu.system

import android.content.Context

/** Chat sending follows the owner's single virtual-phone grant.
 * Recipient, input and send-button verification still apply to each action.
 */
internal object LuluVirtualChatSendPolicy {
    const val WECHAT = "com.tencent.mm"

    fun isEnabled(context: Context, characterId: String): Boolean =
        LuluRolePhoneAuthorization.isGranted(context, characterId)

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

    /** The WeChat voice composer exposes a press-to-talk control instead of
     * an EditText. Only the model-selected switch_chat_input may switch modes.
     * Never mistake a chat-history quote for the current composer. */
    fun isPressToTalk(text: String?): Boolean {
        val normalized = text.orEmpty().replace(Regex("\\s+"), "").lowercase()
        return normalized == "按住说话" || normalized == "按住讲话" ||
            normalized == "holdtotalk"
    }

    data class ComposerBounds(
        val left: Int, val top: Int, val right: Int, val bottom: Int,
    ) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    data class ComposerControl(
        val bounds: ComposerBounds,
        val label: String,
        val resourceId: String,
        val clickable: Boolean,
    )

    /** The keyboard toggle is the compact control immediately LEFT of a
     * verified bottom press-to-talk composer. Never use a fixed coordinate,
     * guess an unidentified page button, or select a Send/Add control. */
    fun voiceToTextControl(
        talk: ComposerBounds, controls: List<ComposerControl>,
        screenWidth: Int, screenHeight: Int,
    ): Int? {
        if (screenWidth <= 0 || screenHeight <= 0 ||
            talk.top < screenHeight / 2 || talk.width < screenWidth / 3)
            return null
        val matches = controls.withIndex().filter { (_, c) ->
            val b = c.bounds
            val label = c.label.replace(Regex("\\s+"), "").lowercase()
            c.clickable && b.width in 12..(screenWidth / 4).coerceAtLeast(12) &&
                b.height in 12..(screenHeight / 7).coerceAtLeast(12) &&
                b.centerX < talk.left &&
                talk.left - b.right in 0..(screenWidth / 6).coerceAtLeast(12) &&
                kotlin.math.abs(b.centerY - talk.centerY) <=
                    maxOf(talk.height / 2, screenHeight / 32) &&
                !label.contains("切换到语音") && !label.contains("切换成语音") &&
                !label.contains("holdtotalk") &&
                (LuluVirtualUiPolicy.mayClick(c.label, c.resourceId, "") ||
                    (c.label.isBlank() && c.resourceId.isBlank()))
        }.sortedWith(compareBy<IndexedValue<ComposerControl>> {
            talk.left - it.value.bounds.right
        }.thenBy { it.value.bounds.width.toLong() * it.value.bounds.height })
        val best = matches.firstOrNull() ?: return null
        // Multiple nodes may wrap one icon; different physical targets mean
        // the geometry is ambiguous and nothing is clicked.
        if (matches.any {
                kotlin.math.abs(it.value.bounds.centerX - best.value.bounds.centerX) >
                    screenWidth / 35 ||
                kotlin.math.abs(it.value.bounds.centerY - best.value.bounds.centerY) >
                    screenHeight / 35
            }) return null
        return best.index
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
