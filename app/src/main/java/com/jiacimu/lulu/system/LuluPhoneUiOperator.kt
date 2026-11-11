package com.jiacimu.lulu.system

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owner-authorized, foreground-screen Android UI operations.
 *
 * This is an execution adapter, NOT a second AI persona or a virtual display.
 * The same character selects commands through the existing ToolRouter; the
 * actual observations return through the ordinary character receipt path.
 *
 * Unlike Mika's separate shell-owned display, these operations target the
 * device's currently visible screen. This distinction must never be hidden.
 */
internal object LuluPhoneUiGeometry {
    fun normalized(value: Int): Int {
        require(value in 0..1000) { "坐标必须在0到1000之间" }
        return value
    }

    fun pixel(value: Int, extent: Int): Float {
        require(extent > 1) { "屏幕尺寸无效" }
        return ((extent - 1L) * normalized(value) / 1000L).toFloat()
    }

    fun duration(value: Int): Long {
        require(value in 180..1600) { "滑动时长必须在180到1600毫秒之间" }
        return value.toLong()
    }
}

internal object LuluPhoneUiOperator {
    private val actions = setOf("inspect", "launch", "tap", "swipe", "scroll", "type")

    suspend fun execute(context: Context, args: JSONObject): String = withContext(Dispatchers.Main) {
        val action = args.optString("action").trim().lowercase()
        require(action in actions) { "未知手机操作；应使用inspect|launch|tap|swipe|scroll|type" }
        val target = args.optString("packageName").trim()
        val before = LuluAccessibilityService.observe()
        check(before.connected) { "无障碍服务尚未连接，不能控制手机" }
        // Reuse the SAME privacy exclusion authority as capture_screen.
        // No new phone tool may read an app the owner explicitly excluded.
        if (action != "launch" && LuluScreenAwareness.isExcluded(context, before.packageName)) {
            return@withContext blocked(before.packageName, "当前应用被屏幕隐私规则排除")
        }
        if (action == "launch" && LuluScreenAwareness.isExcluded(context, target)) {
            return@withContext blocked(target, "目标应用被屏幕隐私规则排除")
        }

        if (action == "inspect") {
            return@withContext result(before, before, accepted = true, verified = true,
                action = action, controls = LuluAccessibilityService.controls(),
                note = "这是当前用户可见屏幕，不是后台独立屏幕")
        }
        require(target.isNotBlank()) { "手机操作必须传入准确的packageName" }
        if (action != "launch" && before.packageName != target) {
            return@withContext result(before, before, accepted = false, verified = false,
                action = action, controls = JSONArray(),
                note = "操作前台应用与指定packageName不一致，未执行")
        }

        val accepted = when (action) {
            "launch" -> {
                val intent = context.packageManager.getLaunchIntentForPackage(target)
                    ?: error("没有找到该包名的可启动应用")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            }
            "tap" -> LuluAccessibilityService.tapNormalized(
                args.getInt("x"), args.getInt("y"))
            "swipe" -> LuluAccessibilityService.swipeNormalized(
                args.getInt("startX"), args.getInt("startY"),
                args.getInt("endX"), args.getInt("endY"),
                LuluPhoneUiGeometry.duration(args.optInt("durationMs", 400)))
            "scroll" -> LuluAccessibilityService.scrollVisible(
                args.getString("direction").trim().lowercase())
            "type" -> {
                val value = args.getString("text")
                require(value.isNotEmpty() && value.length <= 500) {
                    "每次输入文字长度须在1到500字符之间"
                }
                LuluAccessibilityService.typeIntoFocusedField(value)
            }
            else -> false
        }

        // These are observations, not a claim that a tap submitted a form.
        // An optional expectedText verifies only the *observed* UI change.
        val expected = args.optString("expectedText").trim()
        var after = LuluAccessibilityService.observe()
        var verified = false
        if (accepted) {
            for (attempt in 0 until 6) {
                delay(300)
                after = LuluAccessibilityService.observe()
                if (LuluScreenAwareness.isExcluded(context, after.packageName)) {
                    return@withContext blocked(after.packageName,
                        "操作后进入被隐私规则排除的应用，不读取新画面")
                }
                verified = if (action == "launch") after.packageName == target
                    else expected.isNotBlank() && after.visibleText.contains(expected)
                if (verified) break
            }
        }
        result(before, after, accepted, verified, action,
            controls = LuluAccessibilityService.controls(),
            note = when {
                !accepted -> "Android 未接受本次操作，未执行或目标不可操作"
                verified && action == "launch" -> "真实观察到目标应用来到当前主屏；并非后台屏幕"
                verified -> "观察到指定的expectedText；不能据此推断屏幕外业务已完成"
                else -> "Android 接受了操作请求，但没有可核实的完成条件；请继续观察再判断"
            })
    }

    private fun blocked(packageName: String, note: String): String = JSONObject()
        .put("success", false)
        .put("status", "denied")
        .put("accepted", false)
        .put("mode", "foreground_accessibility")
        .put("packageName", packageName)
        .put("verification", note)
        .toString()

    private fun result(
        before: AccessibilitySnapshot,
        after: AccessibilitySnapshot,
        accepted: Boolean,
        verified: Boolean,
        action: String,
        controls: JSONArray,
        note: String,
    ) = JSONObject()
        .put("success", verified)
        .put("status", if (verified) "observed" else "unverified")
        .put("accepted", accepted)
        .put("verified", verified)
        .put("mode", "foreground_accessibility")
        .put("action", action)
        .put("beforePackage", before.packageName)
        .put("packageName", after.packageName)
        .put("visibleText", after.visibleText.take(4500))
        .put("controls", controls)
        .put("capturedAt", after.capturedAt?.toString().orEmpty())
        .put("verification", note)
        .toString()
}
