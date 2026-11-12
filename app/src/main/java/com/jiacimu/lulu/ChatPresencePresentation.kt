package com.jiacimu.lulu

/**
 * "在线" means awake and able to act. "离线" means resting; there is no
 * third state where a sleeping character secretly performs work.
 *
 * A real role execution lease or its expiry review keeps the role awake
 * until the original activity actually finishes.
 */
internal fun chatPresenceLine(
    online: Boolean,
    onlineUntilText: String,
    expiryReview: Boolean,
    activeRoleWork: Boolean,
    modelLabel: String,
): String {
    val presence = when {
        activeRoleWork -> "在线·正在执行任务"
        expiryReview -> "在线·正在决定下一步"
        online -> "在线至 $onlineUntilText"
        else -> "休眠"
    }
    return "$presence · $modelLabel"
}

internal fun chatWorkHeaderLabel(
    awaitingReply: Boolean,
    activeFollowThrough: Boolean,
    checkingPhone: Boolean = false,
): String? = when {
    checkingPhone -> "正在查手机"
    awaitingReply -> "正在输入中"
    activeFollowThrough -> "执行后续中"
    else -> null
}
