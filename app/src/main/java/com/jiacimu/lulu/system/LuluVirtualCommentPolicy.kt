package com.jiacimu.lulu.system

/** Public comments use an explicit transaction, never a generic navigation tap.
 * Metadata describes actual widgets; entered prose is not used to classify them. */
internal object LuluVirtualCommentPolicy {
    const val ACTION = "publish_comment"

    private val entry = Regex(
        "(?:评论|回复|全部评论|查看评论|查看全部评论|查看回复|展开回复|comments|replies)(?:\\s*[0-9.,万亿kKmM+]+(?:条)?)?|" +
            "查看(?:全部)?[0-9]+条(?:评论|回复)", RegexOption.IGNORE_CASE)
    private val editor = Regex("评论|回复|说点什么|留下你的|comment|reply", RegexOption.IGNORE_CASE)
    private val excludedEditor = Regex("搜索|私信|验证码|密码|search|message|password|captcha|otp", RegexOption.IGNORE_CASE)

    fun isReadOnlyEntry(label: String, hasComposer: Boolean): Boolean =
        !hasComposer && entry.matches(label.trim())

    fun isCommentEditor(hint: String?, resourceId: String?): Boolean {
        val metadata = hint.orEmpty() + " " + resourceId.orEmpty().substringAfterLast('/')
        return editor.containsMatchIn(metadata) && !excludedEditor.containsMatchIn(metadata)
    }

    fun isSubmitLabel(label: String): Boolean = label.trim().lowercase() in setOf(
        "发送", "发布", "发表", "评论", "回复", "send", "post", "publish", "comment", "reply")

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** A reply link on an existing comment is not the draft's submit button. */
    fun besideEditor(editor: Box, button: Box, width: Int, height: Int): Boolean =
        width > 0 && height > 0 && editor.left >= 0 && editor.top >= 0 &&
            editor.right <= width && editor.bottom <= height &&
            editor.right > editor.left && editor.bottom > editor.top &&
            button.left >= 0 && button.top >= 0 && button.right <= width && button.bottom <= height &&
            button.right > button.left && button.bottom > button.top &&
            button.right - button.left <= width / 3 && button.bottom - button.top <= height / 6 &&
            button.left >= editor.right - (editor.right - editor.left) / 3 &&
            (button.top + button.bottom) / 2 in editor.top..editor.bottom

    data class Dispatch(val attempted: Boolean, val clicked: Boolean, val error: String = "")

    fun dispatchOnce(recheck: () -> String?, markAttempt: () -> Unit, click: () -> Boolean): Dispatch {
        recheck()?.let { return Dispatch(false, false, it) }
        markAttempt()
        return try { Dispatch(true, click()) }
        catch (error: Exception) {
            if (error is java.util.concurrent.CancellationException) throw error
            Dispatch(true, false, "comment_dispatch_result_unknown")
        }
    }

    data class Evidence(
        val snapshotCurrent: Boolean, val ownedWindow: Boolean, val scanComplete: Boolean,
        val editorVerified: Boolean, val draftMatches: Boolean,
        val buttonVerified: Boolean, val contextMatches: Boolean,
    )

    fun rejection(e: Evidence): String? = when {
        !e.ownedWindow -> "comment_window_unverified"
        !e.snapshotCurrent -> "comment_snapshot_expired"
        !e.scanComplete -> "comment_tree_incomplete"
        !e.contextMatches -> "comment_context_changed"
        !e.editorVerified -> "comment_editor_unverified"
        !e.draftMatches -> "comment_draft_mismatch"
        !e.buttonVerified -> "comment_button_unverified"
        else -> null
    }

    /** Client display evidence only, never a server-delivery acknowledgement. */
    fun appeared(before: Int, after: Int, sameContext: Boolean, draftCleared: Boolean): Boolean =
        before >= 0 && after > before && sameContext && draftCleared
}
