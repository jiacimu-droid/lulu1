package com.jiacimu.lulu.data

/** A caption can render before the note's media and remaining text hydrate. */
internal class XhsNoteReadiness {
    private var signature = ""
    private var changedAt = 0L

    fun ready(body: String, imageUrls: List<String>, expectedImages: Int, nowMs: Long): Boolean {
        if (body.isBlank() && imageUrls.isEmpty()) {
            signature = ""
            return false
        }
        val next = body + "\u0000" + imageUrls.joinToString("\u0000")
        if (next != signature) {
            signature = next
            changedAt = nowMs
        }
        // Give delayed carousels time to mount. Text-only/video captions can
        // still finish, and partially loaded galleries remain explicitly partial.
        val settleMs = if (imageUrls.isEmpty() || imageUrls.size < expectedImages) 4_500L else 1_050L
        return nowMs - changedAt >= settleMs
    }
}
