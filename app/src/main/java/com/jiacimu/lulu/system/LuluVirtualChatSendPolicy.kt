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

    /** Match the real WeChat chat title, not a guessed model recipient.
     * Android/WeChat sometimes exposes the same title via text or content
     * description, with layout-only zero-width characters. Never allow a
     * partial nickname match to send to a different conversation. */
    fun headerMatches(text: String?, description: String?, recipient: String): Boolean {
        if (!validRecipient(recipient)) return false
        fun normalized(s: String): String = s
            .replace(Regex("[\\u200B\\u200E\\u200F\\uFEFF]"), "").trim()
        val expected = normalized(recipient)
        // WeChat occasionally coalesces the small unread-count badge and the
        // toolbar title into ONE accessibility description. Accept only that
        // exact structural decoration, not a fuzzy substring from chat history.
        val unreadBadge = "(?:[1-9][0-9]{0,2}|[(][1-9][0-9]{0,2}[)]|（[1-9][0-9]{0,2}）)"
        val decorated = Regex("^(?:返回\\s*)?(?:$unreadBadge\\s+)?${Regex.escape(expected)}$")
        return listOf(text, description).filterNotNull().any {
            val candidate = normalized(it)
            candidate == expected || decorated.matches(candidate)
        }
    }

    /** An exact Android window title is useful when a custom WeChat toolbar
     * exposes no text node. Generic application titles prove nothing about the
     * current conversation. Never accept a partial nickname or a guessed title. */
    fun windowTitleMatches(title: String?, recipient: String): Boolean =
        title?.trim()?.lowercase() !in setOf("微信", "wechat", "聊天", "聊天信息") &&
            headerMatches(title, null, recipient)

    /** A title TextView and its large accessibility wrapper commonly have
     * different centres. Count them as ONE title only when their rectangles
     * really overlap the same small title, never across different rows. */
    fun uniqueHeaderTitle(bounds: List<ComposerBounds>, screenHeight: Int): Boolean {
        if (bounds.isEmpty() || screenHeight <= 0) return false
        val smallest = bounds.minByOrNull { it.width.toLong() * it.height } ?: return false
        if (smallest.width <= 0 || smallest.height <= 0) return false
        return bounds.all { other ->
            other.width > 0 && other.height > 0 &&
                ((smallest.centerX in other.left..other.right &&
                  smallest.centerY in other.top..other.bottom) ||
                 (kotlin.math.abs(smallest.centerY - other.centerY) <=
                    (screenHeight / 65).coerceAtLeast(8) &&
                  maxOf(smallest.left, other.left) < minOf(smallest.right, other.right)))
        }
    }

    /** Both a clickable label and its wrapping layout may be exposed as
     * separate accessibility nodes for the ONE physical Send button. Their
     * centres may differ: accept genuine containment but reject two buttons. */
    fun uniqueSendTarget(
        controls: List<ComposerBounds>, screenWidth: Int, screenHeight: Int,
    ): Int? {
        if (controls.isEmpty() || screenWidth <= 0 || screenHeight <= 0) return null
        val smallestIndex = controls.indices.minByOrNull {
            controls[it].width.toLong() * controls[it].height
        } ?: return null
        val target = controls[smallestIndex]
        if (target.width <= 0 || target.height <= 0) return null
        if (controls.any { other ->
                other.width <= 0 || other.height <= 0 ||
                    !(target.centerX in other.left..other.right &&
                      target.centerY in other.top..other.bottom)
            }) return null
        return smallestIndex
    }

    /** The send button must be on the same bottom composer row as the
     * live text editor, not a matching "发送" label inside chat history. */
    fun sendControlNearEditor(
        control: ComposerBounds, editor: ComposerBounds,
        screenWidth: Int, screenHeight: Int,
    ): Boolean = control.width > 0 && control.height > 0 &&
        editor.width > 0 && editor.height > 0 && screenWidth > 0 &&
        screenHeight > 0 && control.centerX >= screenWidth / 2 &&
        control.centerX >= editor.centerX &&
        kotlin.math.abs(control.centerY - editor.centerY) <=
            maxOf(editor.height, screenHeight / 16)

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
            normalized == "可按住转文字" || normalized == "按住转文字" ||
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
