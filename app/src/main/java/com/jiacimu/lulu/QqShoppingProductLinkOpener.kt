package com.jiacimu.lulu

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jiacimu.lulu.data.XhsSharedLinkResolver
import com.jiacimu.lulu.system.LuluSharedWebLinks
import com.jiacimu.lulu.system.LuluShoppingLinkShare
import kotlinx.coroutines.CancellationException

/** Opens original links on owner tap, with app-specific handling for verified official domains. */
internal object QqShoppingProductLinkOpener {
    enum class Result { MERCHANT_APP, SYSTEM_HANDLER, FAILED }

    internal fun candidates(product: QqShoppingProductLink): List<Intent> {
        val packages = LuluShoppingLinkShare.preferredOpenPackages(
            product.merchant, product.url)
        if (packages.isEmpty()) return emptyList()
        val url = Uri.parse(product.url)
        return packages.distinct().map { viewIntent(url, it) } + viewIntent(url)
    }

    private fun xhsNoteIntent(noteUrl: String): Intent? {
        val note = runCatching { XhsSharedLinkResolver.parseDirect(noteUrl) }.getOrNull() ?: return null
        val query = Uri.parse(note.url).encodedQuery
        val target = Uri.Builder().scheme("xhsdiscover").authority("item")
            .appendPath(note.id).encodedQuery(query).build()
        return viewIntent(target, LuluSharedWebLinks.XHS_PACKAGE)
    }

    private fun viewIntent(url: Uri, packageName: String? = null): Intent =
        Intent(Intent.ACTION_VIEW, url)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .setPackage(packageName)

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    suspend fun openSharedLink(context: Context, url: String, merchant: String? = null): Result {
        if (merchant != null) return open(context, QqShoppingProductLink(merchant, url, ""))
        if (LuluSharedWebLinks.uri(url) == null) return Result.FAILED
        val xhs = LuluSharedWebLinks.xhsUrl(url)
        if (xhs != null) {
            val direct = xhsNoteIntent(xhs)
            if (direct != null && launch(context, direct)) return Result.MERCHANT_APP
            if (launch(context, viewIntent(Uri.parse(xhs), LuluSharedWebLinks.XHS_PACKAGE)))
                return Result.MERCHANT_APP
            if (direct == null && Uri.parse(xhs).host?.lowercase() in LuluSharedWebLinks.xhsShortHosts &&
                context.packageManager.getLaunchIntentForPackage(LuluSharedWebLinks.XHS_PACKAGE) != null) {
                val note = try {
                    XhsSharedLinkResolver.resolve(xhs)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (note != null) {
                    val native = xhsNoteIntent(note.url)
                    if (native != null && launch(context, native)) return Result.MERCHANT_APP
                }
            }
        }
        return if (launch(context, viewIntent(Uri.parse(xhs ?: url)))) Result.SYSTEM_HANDLER
        else Result.FAILED
    }

    fun open(context: Context, product: QqShoppingProductLink): Result {
        for (intent in candidates(product)) {
            if (launch(context, intent)) return if (intent.`package` != null) Result.MERCHANT_APP
            else Result.SYSTEM_HANDLER
        }
        return Result.FAILED
    }
}
