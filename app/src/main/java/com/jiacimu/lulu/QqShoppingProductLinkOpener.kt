package com.jiacimu.lulu

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jiacimu.lulu.system.LuluShoppingLinkShare

/**
 * Only handles owner taps on verified shopping cards, never character actions.
 * Android decides whether a merchant app supports its HTTPS/App Link; simply
 * having it installed is not proof a particular product URL is supported.
 */
internal object QqShoppingProductLinkOpener {
    enum class Result { MERCHANT_APP, SYSTEM_HANDLER, FAILED }

    internal fun candidates(product: QqShoppingProductLink): List<Intent> {
        val packages = LuluShoppingLinkShare.preferredOpenPackages(
            product.merchant, product.url)
        if (packages.isEmpty()) return emptyList()
        val url = Uri.parse(product.url)
        fun viewIntent(packageName: String?): Intent =
            Intent(Intent.ACTION_VIEW, url)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .apply { if (packageName != null) setPackage(packageName) }
        // Retain the actual URL/query data on both merchant and fallback routes.
        // Explicit packages prevent a generic browser/default handler from
        // intercepting the merchant-app attempt.
        return packages.distinct().map(::viewIntent) + viewIntent(null)
    }

    fun open(context: Context, product: QqShoppingProductLink): Result {
        for (intent in candidates(product)) {
            try {
                context.startActivity(intent)
                return if (intent.`package` != null) Result.MERCHANT_APP
                    else Result.SYSTEM_HANDLER
            } catch (_: ActivityNotFoundException) {
                // App missing or not registered to receive this HTTPS URL.
            } catch (_: SecurityException) {
                // The destination rejected a cross-app launch.
            }
        }
        return Result.FAILED
    }
}
