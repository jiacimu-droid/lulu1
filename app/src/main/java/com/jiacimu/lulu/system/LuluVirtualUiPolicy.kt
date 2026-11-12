package com.jiacimu.lulu.system

import java.security.MessageDigest

/**
 * Pure policy for Mika-style UI references on Lulu's OWN virtual display.
 * A model never gets a reusable coordinate authority: each element ref belongs
 * to a single observed screen snapshot, owner role, app and display.
 */
internal object LuluVirtualUiPolicy {
    private val outward = Regex(
        "(付款|支付|转账|购买|下单|结算|发送|发消息|评论|回复|发布|提交|确定|确认|删除|清空|注销|解绑|关注|点赞|收藏|喜欢|拨打|接听|挂断|" +
            "password|passcode|otp|payment|checkout|order|buy|purchase|send|message|comment|reply|post|submit|confirm|delete|remove|clear|logout|unfollow|follow|like|favorite|subscribe|call)",
        RegexOption.IGNORE_CASE,
    )

    fun fingerprint(parts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString("\u001f").toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Mika ActionEffect-inspired classification of REAL local evidence.
     * A new frame by itself is not an action effect and none of these states
     * means the user's business goal is finished. */
    fun effectKind(
        action: String, accepted: Boolean,
        beforeTree: String?, afterTree: String?, inputReadbackVerified: Boolean,
    ): String {
        if (action == "observe") return "read_only"
        if (!accepted) return "not_accepted"
        if (action == "type" && inputReadbackVerified) return "input_verified"
        if (action == "type") return "input_unverified"
        if (action == "launch" && !afterTree.isNullOrBlank()) return "app_visible"
        if (beforeTree.isNullOrBlank() || afterTree.isNullOrBlank()) return "unknown"
        return if (beforeTree == afterTree) "tree_unchanged" else "tree_changed"
    }

    fun sameSnapshot(
        requestedId: String, currentId: String,
        expectedFingerprint: String, currentFingerprint: String,
    ): Boolean = requestedId.isNotBlank() && currentId.isNotBlank() &&
        requestedId == currentId && expectedFingerprint.isNotBlank() &&
        expectedFingerprint == currentFingerprint

    /** Only safe, named navigation. Unknown, empty, sensitive and outward
     * actions must fail closed, never perform a second click automatically. */
    fun mayClick(
        label: String?, resourceId: String?, descendantText: String,
        shoppingAllowed: Boolean = false,
    ): Boolean {
        val title = label.orEmpty().trim()
        val id = resourceId.orEmpty().trim()
        if (title.isEmpty() && id.isEmpty()) return false
        // Shopping is a narrow, owner-controlled exception for named, verified
        // controls. It cannot authorize generic confirm/order submission,
        // payment or unverified coordinate taps.
        if (shoppingAllowed &&
            LuluShoppingAssistPolicy.mayNavigate(title, id, descendantText)) return true
        if (outward.containsMatchIn(title + " " + id)) return false
        // Long article cards often mention "likes" or "comments" as content.
        // Only short individual child controls can make an otherwise safe
        // parent an outward action; article prose is never used as an instruction.
        return descendantText.take(1800).lineSequence()
            .map { it.trim() }.none { it.length in 1..24 && outward.containsMatchIn(it) }
    }
}
