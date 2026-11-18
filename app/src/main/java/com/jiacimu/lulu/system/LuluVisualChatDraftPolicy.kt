package com.jiacimu.lulu.system

/**
 * Screenshot-to-action contract for the zero-accessibility-tree case.
 *
 * Every coordinate is derived from fresh, on-device OCR of the actual owned
 * virtual display, never from a chat history list or a guessed fixed slot.
 * A visible draft is NOT evidence of a sent message.
 */
internal object LuluVisualChatDraftPolicy {
    data class Proof(val sendX: Int, val sendY: Int, val previousOutgoing: Int)

    private fun normalize(value: String): String =
        value.filterNot { it == '\u200b' || it == '\ufeff' }

    /** A point chosen by the role from the last full-frame screenshot.
     * The center-left lower area can focus an editor but cannot be a
     * right-aligned Send / payment / toolbar button. */
    fun composerPointSafe(x: Int, y: Int): Boolean =
        x in 120..780 && y in 480..965

    /** The text-mode Send label must be visible at the composer row.
     * Strict bounds prevent picking a quoted '发送' inside a message. */
    fun sendLabel(
        lines: List<LuluVirtualChatTitleOcr.Line>, width: Int, height: Int,
        imeTopY: Int = height,
    ): LuluVirtualChatTitleOcr.Line? {
        if (width <= 0 || height <= 0) return null
        return lines.filter { item ->
            val b = item.bounds
            val label = normalize(item.text)
            label in setOf("发送", "Send") &&
                b.width in 1..(width * 24 / 100).coerceAtLeast(1) &&
                b.height in 1..(height * 7 / 100).coerceAtLeast(1) &&
                b.centerX >= width * 70 / 100 &&
                b.centerY in (height * 46 / 100)..(height * 96 / 100) &&
                // The keyboard may itself offer a visually identical Send
                // action. Only the verified APP composer area may be tapped.
                b.bottom < imeTopY
        }.singleOrNull()
    }

    fun outgoingCount(
        lines: List<LuluVirtualChatTitleOcr.Line>, message: String,
        width: Int, height: Int,
    ): Int {
        if (width <= 0 || height <= 0 || message.isBlank()) return 0
        val expected = normalize(message)
        // Exact recognized message in the right half, below the toolbar.
        // Deliberately conservative: split multi-line bubbles remain unknown.
        return lines.count {
            val b = it.bounds
            normalize(it.text) == expected &&
                // Long incoming bubbles may extend past the midpoint.
                // Only right-column OCR hits can be outgoing evidence.
                b.right >= width * 75 / 100 &&
                b.centerX >= width * 48 / 100 &&
                b.centerY in (height * 18 / 100)..(height * 87 / 100)
        }
    }

    fun verifiedDraft(
        lines: List<LuluVirtualChatTitleOcr.Line>, recipient: String, message: String,
        width: Int, height: Int, imeTopY: Int = height,
    ): Proof? {
        if (!LuluVirtualChatSendPolicy.validRecipient(recipient) ||
            !LuluVirtualChatSendPolicy.validMessage(message) ||
            !LuluVirtualChatTitleOcr.hasExactToolbarTitle(
                lines, recipient, width, height, centeredToolbar = true)) return null
        val send = sendLabel(lines, width, height, imeTopY) ?: return null
        val b = send.bounds
        // Text from the actual input area is to the LEFT of the send button
        // and close to its row, even when the keyboard resized the app.
        val draft = lines.filter { line ->
            val r = line.bounds
            line !== send && r.centerX in (width * 8 / 100)..(width * 82 / 100) &&
                r.right <= b.left + width * 7 / 100 &&
                r.centerY in (b.centerY - height * 19 / 100)..(b.centerY + height * 5 / 100)
        }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .joinToString("") { normalize(it.text) }
        // A substring is not proof that the editor contains exactly this draft.
        // Otherwise a longer stale/quoted message could authorize Send.
        if (draft != normalize(message)) return null
        return Proof(
            (b.centerX * 1000L / width).toInt().coerceIn(0, 1000),
            (b.centerY * 1000L / height).toInt().coerceIn(0, 1000),
            outgoingCount(lines, message, width, height),
        )
    }
}
