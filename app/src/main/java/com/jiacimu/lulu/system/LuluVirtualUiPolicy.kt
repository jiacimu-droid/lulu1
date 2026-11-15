package com.jiacimu.lulu.system

import java.security.MessageDigest

/**
 * Pure policy for Mika-style UI references on Lulu's OWN virtual display.
 * A model never gets a reusable coordinate authority: each element ref belongs
 * to a single observed screen snapshot, owner role, app and display.
 */
internal object LuluVirtualUiPolicy {
    private val outward = Regex(
        "(付款|支付|转账|购买|下单|结算|购物车|加购|选规格|选择规格|cart|checkout|发送|发消息|评论|回复|发布|提交|确定|确认|删除|清空|注销|解绑|关注|点赞|收藏|喜欢|拨打|接听|挂断|" +
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

    /** Only a bounded, actual toolbar control may implement the role's
     * already selected BACK action. This is a physical action resolver, not
     * a new model deciding whether to navigate. Indexes refer to the live
     * virtual-display accessibility tree, not to stored screenshot pixels. */
    data class AppBarControl(
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        val label: String = "", val resourceId: String = "",
        val clickable: Boolean = true,
    ) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    private val BACK_LABELS = setOf(
        "返回", "返回按钮", "返回上一页", "返回上一级", "上一页",
        "back", "go back", "navigate up", "back button",
    )
    private val BACK_ID = Regex(
        "(^|[_\\-])(back|arrow_back|back_arrow|navigate_up|nav_back|btnback|icback|title_back)([_\\-]|$)",
        RegexOption.IGNORE_CASE,
    )

    fun isNamedAppBack(label: String, resourceId: String): Boolean {
        val normalized = label.trim().lowercase().replace(Regex("\\s+"), " ")
        val id = resourceId.substringAfterLast('/').lowercase()
        return normalized in BACK_LABELS || BACK_ID.containsMatchIn(id)
    }

    /** A unique icon at the left edge of a verified toolbar may be anonymous
     * in custom-drawn WebViews. Require a separate wide toolbar/search control
     * to the right, otherwise do not guess what the unlabeled icon does. */
    fun appBackControlIndex(
        screenWidth: Int, screenHeight: Int, controls: List<AppBarControl>,
    ): Int? {
        if (screenWidth <= 0 || screenHeight <= 0) return null
        val topLeft = controls.withIndex().filter { (_, c) ->
            c.clickable && c.width in 12..(screenWidth / 4).coerceAtLeast(12) &&
                c.height in 12..(screenHeight / 9).coerceAtLeast(12) &&
                c.left >= 0 && c.top >= screenHeight / 30 &&
                c.centerX < screenWidth * 27 / 100 &&
                c.centerY < screenHeight * 28 / 100
        }
        val named = topLeft.filter { isNamedAppBack(it.value.label, it.value.resourceId) }
        val candidates = if (named.isNotEmpty()) named else topLeft.filter { entry ->
            val c = entry.value
            c.label.isBlank() && c.resourceId.isBlank() &&
                controls.any { neighbor ->
                    neighbor !== c && neighbor.left >= c.right &&
                        neighbor.width > screenWidth / 3 &&
                        kotlin.math.abs(neighbor.centerY - c.centerY) <
                            screenHeight / 25 &&
                        neighbor.centerY < screenHeight * 28 / 100
                }
        }
        if (candidates.isEmpty()) return null
        val first = candidates.minWithOrNull(
            compareBy<IndexedValue<AppBarControl>> {
                it.value.width.toLong() * it.value.height
            }.thenBy { it.value.centerX }
        ) ?: return null
        // Duplicate accessibility wrappers around one glyph are okay; two
        // different top-left buttons are not an unambiguous BACK target.
        if (candidates.any {
                kotlin.math.abs(it.value.centerX - first.value.centerX) >
                    screenWidth / 40 ||
                kotlin.math.abs(it.value.centerY - first.value.centerY) >
                    screenHeight / 40
            }) return null
        return first.index
    }

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
