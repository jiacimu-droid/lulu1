package com.jiacimu.lulu.system

/**
 * Pure local safeguard for virtual-display Enter: it must be a SEARCH editor,
 * not an arbitrary focused message/comment/post/payment field. Only editor
 * metadata is inspected; user-entered field contents are never used for the
 * decision or copied to the model.
 */
internal object LuluVirtualSearchFieldPolicy {
    /** Only recover focus when exactly one visible safe editor is a search field.
     * Ambiguous input targets are never selected programmatically. */
    fun canRecoverUniqueSearchField(searchEditors: Int, otherEditors: Int): Boolean =
        searchEditors == 1 && otherEditors == 0

    fun isSearchEditor(className: String?, hint: String?, viewId: String?): Boolean {
        val metadata = listOfNotNull(className, hint, viewId)
            .joinToString(" ").lowercase()
        if (metadata.isBlank()) return false
        if (Regex("密码|口令|支付|发送|发消息|发表评论|回复评论|发布|password|message_input|comment_input").containsMatchIn(metadata))
            return false
        return Regex("搜索|搜一搜|检索|search|query|keyword").containsMatchIn(metadata)
    }
}
