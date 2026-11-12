package com.jiacimu.lulu

/**
 * The five-minute personal online lease and an already-accepted background
 * tool activity are different facts. A real tool may still be finishing even
 * after the role stops taking new online conversation turns.
 *
 * Presentation only: never renew a lease, mark a tool complete, or decide
 * that a role chose to sleep.
 */
internal fun chatPresenceLine(
    online: Boolean,
    onlineUntilText: String,
    expiryReview: Boolean,
    activeFollowThrough: Boolean,
    modelLabel: String,
): String {
    val presence = when {
        online -> "在线至 $onlineUntilText"
        expiryReview -> "在线收尾中·正决定下一步"
        activeFollowThrough -> "聊天离线·后台任务仍在执行"
        else -> "离线"
    }
    return "$presence · $modelLabel"
}

internal fun chatWorkHeaderLabel(
    awaitingReply: Boolean,
    activeFollowThrough: Boolean,
    online: Boolean,
): String? = when {
    awaitingReply -> "正在输入中"
    activeFollowThrough && online -> "执行后续中"
    activeFollowThrough -> "后台执行中"
    else -> null
}
