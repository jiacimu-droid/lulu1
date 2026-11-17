package com.jiacimu.lulu.system

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** A role can choose a short sequence of visible navigation labels. Each step
 * is independently resolved on the current screen and must show an effect. */
internal object LuluVirtualNavigation {
    const val ACTION = "navigate"
    const val LABELS = "navigationLabels"
    private const val MAX_STEPS = 4

    fun label(text: String?): String =
        text.orEmpty().replace(Regex("[\\u200B\\u200E\\u200F\\uFEFF]"), "").trim()

    fun labels(args: JSONObject): List<String>? {
        val list = args.optJSONArray(LABELS) ?: return null
        if (list.length() !in 1..MAX_STEPS) return null
        return (0 until list.length()).map { index ->
            val value = list.opt(index) as? String ?: return null
            label(value).takeIf { it.length in 1..90 && !it.contains('\n') && !it.contains('\r') }
                ?: return null
        }
    }

    enum class Resolution(val code: String) {
        UNIQUE(""), MISSING("navigation_label_not_found"), AMBIGUOUS("navigation_label_ambiguous")
    }

    fun resolution(physicalTargets: Int): Resolution = when (physicalTargets) {
        0 -> Resolution.MISSING
        1 -> Resolution.UNIQUE
        else -> Resolution.AMBIGUOUS
    }

    fun allowsTouchFallback(
        nodeRejectedOrUnavailable: Boolean, sameTarget: Boolean, navigationOnly: Boolean,
    ): Boolean = nodeRejectedOrUnavailable && sameTarget && navigationOnly

    suspend fun run(
        labels: List<String>,
        pauseReason: suspend () -> String?,
        click: suspend (String) -> JSONObject,
        changed: suspend (JSONObject) -> Boolean,
    ): JSONObject {
        val steps = JSONArray()
        fun result(completed: Boolean, error: String = "") = JSONObject()
            .put("navigationAttempted", true).put("navigationCompleted", completed)
            .put("navigationSteps", steps).put("navigationError", error)
        for (label in labels) {
            pauseReason()?.let { return result(false, it) }
            val step = JSONObject().put("label", label)
            steps.put(step)
            val receipt = try {
                click(label)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return result(false, "navigation_device_error")
            }
            step
                .put("method", receipt.optString("actionMethod"))
                .put("accepted", receipt.optBoolean("accepted"))
            if (!receipt.optBoolean("accepted")) {
                return result(false, receipt.optString("errorCode").ifBlank { "navigation_click_rejected" })
            }
            val changedPage = changed(receipt)
            step.put("uiChanged", changedPage)
            if (!changedPage) return result(false, "navigation_change_unverified")
        }
        return result(true)
    }
}
