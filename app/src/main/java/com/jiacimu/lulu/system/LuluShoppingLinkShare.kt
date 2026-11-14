package com.jiacimu.lulu.system

import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.Locale

/**
 * Links are accepted only following an owner-authorized, freshly verified
 * virtual-display "copy link" click. Reading the clipboard is NOT a tool:
 * it cannot dump arbitrary contents or run on an unrelated role request.
 */
internal object LuluShoppingLinkShare {
    private val copyLabels = setOf(
        "复制链接", "复制商品链接", "复制分享链接", "复制网址",
        // Some marketplace share sheets use these explicit link-only labels.
        "复制宝贝链接", "复制链接到剪贴板", "复制商品网址",
        "copy link", "copy product link", "copy url",
    )

    private val merchantDomains = mapOf(
        "com.xunmeng.pinduoduo" to ("拼多多" to listOf("yangkeduo.com", "pinduoduo.com", "pdd.cn")),
        // Taobao's first-level Taobao Flash entry can share ele.me URLs
        // without switching to the standalone me.ele package.
        "com.taobao.taobao" to ("淘宝" to listOf("taobao.com", "tmall.com", "tb.cn", "ele.me")),
        // Verified Android package IDs for Meituan and Meituan Waimai.
        // Only official HTTPS domains are eligible for a chat handoff;
        // other redirects/short links require separate source verification.
        "com.sankuai.meituan" to ("美团" to listOf("meituan.com")),
        "com.sankuai.meituan.takeoutnew" to ("美团外卖" to listOf("meituan.com")),
        // These are different installed apps: never infer the target from a merchant nickname.
        // Allow the confirmed share domains only, not arbitrary copied URLs.
        "com.taobao.idlefish" to ("闲鱼" to listOf("goofish.com", "taobao.com", "tb.cn")),
        "me.ele" to ("淘宝闪购" to listOf("ele.me", "tb.cn")),
        "com.jingdong.app.mall" to ("京东" to listOf("jd.com", "jingdong.com", "3.cn")),
    )

    internal fun merchantName(packageName: String): String? = merchantDomains[packageName]?.first

    /** Distinguish Flash inside the main Taobao app from an ordinary Taobao link.
     * Never infer the product kind from a caption or the character's prose. */
    internal fun merchantNameForLink(packageName: String, url: String): String? {
        if (verifiedChangedLink(packageName, null, url) != url) return null
        if (packageName == "com.taobao.taobao") {
            val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull()
            if (host == "ele.me" || host?.endsWith(".ele.me") == true) return "淘宝闪购"
        }
        return merchantName(packageName)
    }

    /** Also used when restoring a persisted chat card: reject arbitrary-site links. */
    internal fun matchesMerchantLink(merchant: String, url: String): Boolean =
        merchantDomains.any { (pkg, details) ->
            details.first == merchant && verifiedChangedLink(pkg, null, url) == url
        }

    internal fun eligibleCopy(
        packageName: String, label: String, clickable: Boolean, ownerApproved: Boolean,
    ): Boolean = ownerApproved && clickable && merchantName(packageName) != null &&
        label.trim().lowercase(Locale.ROOT) in copyLabels

    /** Compare timestamps before reading any clipboard text: do NOT read
     * pre-existing personal clipboard contents merely to look for a change. */
    fun clipboardTimestamp(context: Context): Long = runCatching {
        context.getSystemService(ClipboardManager::class.java)
            .primaryClipDescription?.timestamp ?: 0L
    }.getOrDefault(0L)

    /** Deliberately does not coerce URI items or read any other app's files. */
    private fun clipboardText(context: Context): String? = runCatching {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
        if (clip == null || clip.itemCount != 1) null
        else clip.getItemAt(0).text?.toString()?.take(6000)
    }.getOrNull()

    /** The clip must have been copied by a new event after THIS role tap. */
    internal fun freshCopyEvent(previousTimestamp: Long, tapStartedAt: Long, newTimestamp: Long): Boolean =
        newTimestamp > previousTimestamp && newTimestamp >= tapStartedAt

    private val httpUrls = Regex("""https://[^\s<>"'。，；，、]+""", RegexOption.IGNORE_CASE)

    internal fun verifiedChangedLink(
        packageName: String, before: String?, after: String?,
    ): String? {
        if (after.isNullOrBlank() || after == before) return null
        val domains = merchantDomains[packageName]?.second ?: return null
        return httpUrls.findAll(after).mapNotNull { match ->
            val value = match.value.trimEnd(')', ']', '}', '！', '!', '；', ';', '。', '，', ',')
            val uri = runCatching { URI(value) }.getOrNull() ?: return@mapNotNull null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return@mapNotNull null
            if (value.length > 2048 || uri.scheme?.lowercase(Locale.ROOT) != "https" ||
                uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443) ||
                domains.none { host == it || host.endsWith(".$it") }) null else value
        }.firstOrNull()
    }

    /** A device-only wait after the accepted copy action. Never submit clipboard
     * content to a language model; only the allowlisted URL can enter chat. */
    suspend fun awaitFreshLink(
        context: Context, packageName: String, previousTimestamp: Long, tapStartedAt: Long,
    ): String? {
        // On Android 10+ a fresh clipboard event can be visible before its
        // text becomes readable (or the read may remain blocked by focus).
        // Retry only the SAME verified event. A second clipboard update could
        // belong to another app/user action and must never be forwarded.
        var freshEventTimestamp: Long? = null
        repeat(12) {
            delay(150)
            val timestamp = withContext(Dispatchers.Main) { clipboardTimestamp(context) }
            if (!freshCopyEvent(previousTimestamp, tapStartedAt, timestamp)) return@repeat
            if (freshEventTimestamp != null && freshEventTimestamp != timestamp) return null
            freshEventTimestamp = timestamp
            val freshText = withContext(Dispatchers.Main) { clipboardText(context) }
            if (!freshText.isNullOrBlank()) {
                return verifiedChangedLink(packageName, null, freshText)
            }
        }
        // No validated merchant link; do not reuse old clipboard data.
        return null
    }
}
