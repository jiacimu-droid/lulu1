package com.jiacimu.lulu

import org.json.JSONObject

/** A real, device-verified product share, not a URL invented by the model. */
internal data class QqShoppingProductLink(
    val merchant: String,
    val url: String,
    val caption: String,
)

private const val SHOPPING_LINK_MARK = "[[LULU_SHOPPING_LINK_V1]]"

internal fun encodeQqShoppingProductLink(merchant: String, url: String, caption: String): String =
    SHOPPING_LINK_MARK + JSONObject()
        .put("merchant", merchant.take(40))
        .put("url", url)
        .put("caption", caption.take(240))
        .toString()

internal fun decodeQqShoppingProductLink(content: String): QqShoppingProductLink? {
    if (!content.startsWith(SHOPPING_LINK_MARK) || content.length > 3200) return null
    return runCatching {
        val data = JSONObject(content.removePrefix(SHOPPING_LINK_MARK))
        val url = data.getString("url")
        val merchant = data.optString("merchant").take(40)
        if (!com.jiacimu.lulu.system.LuluShoppingLinkShare
                .matchesMerchantLink(merchant, url)) return null
        QqShoppingProductLink(
            data.optString("merchant").take(40).ifBlank { "商品" },
            url,
            data.optString("caption").take(240),
        )
    }.getOrNull()
}
