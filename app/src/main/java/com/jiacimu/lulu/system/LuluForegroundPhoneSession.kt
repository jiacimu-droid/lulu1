package com.jiacimu.lulu.system

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Base64
import com.jiacimu.lulu.data.CapabilityRegistry
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * No-ADB phone transport. Unlike the shell-owned display, this mode takes over
 * the owner's VISIBLE display 0. It deliberately shares ToolRouter, role
 * authorization, per-action receipts and the normal phone continuation.
 *
 * The owner can stop it; losing the foreground, grant or target invalidates
 * its snapshot rather than silently tapping some other application.
 */
internal object LuluForegroundPhoneSession {
    private val mutex = Mutex()
    private var owner = ""
    private var target = ""
    private data class Screen(val token: String, val pkg: String, val fingerprint: String,
        val controls: JSONArray, val hasPicture: Boolean)
    private var screen: Screen? = null

    /** Called from the real STOP notification after the shared pause flag is set. */
    suspend fun stopSession(context: Context) {
        mutex.lock()
        try {
            owner = ""; target = ""; screen = null
            LuluScreenFrameHandoff.clear()
            LuluBackgroundPhoneNotice.clear(context)
        } finally { mutex.unlock() }
    }

    fun activeFor(characterId: String): Boolean = owner.isNotBlank() && owner == characterId

    internal fun requiresNewObservation(action: String): Boolean = action in setOf(
        "tap", "long_press", "tap_element", "type", "navigate", "search", "focus_search",
    )

    internal fun allowsObservedTarget(expected: String, observed: String, excluded: Boolean): Boolean =
        expected.isNotBlank() && expected == observed && !excluded

    private fun rejection(action: String, reason: String, code: String,
        status: String = "unverified"): String = JSONObject()
        .put("success", false).put("accepted", false).put("taskCompleted", false)
        .put("mode", "foreground_accessibility").put("status", status)
        .put("action", action).put("errorCode", code)
        .put("verification", reason).toString()

    suspend fun execute(context: Context, characterId: String, requestId: String,
        args: JSONObject): String {
        mutex.lock()
        try {
            return executeLocked(context, characterId, requestId, args)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun executeLocked(context: Context, role: String, requestId: String,
        args: JSONObject): String {
        val action = args.optString("action").trim().lowercase()
        if (action == "stop") {
            if (owner == role) {
                owner = ""; target = ""; screen = null
                LuluBackgroundPhoneNotice.clear(context)
            }
            return JSONObject().put("success", true).put("status", "stopped")
                .put("mode", "foreground_accessibility").put("taskCompleted", false)
                .put("verification", "已终止可见主屏控制；未关闭第三方App").toString()
        }
        if (LuluBackgroundPhoneSession.ownerHasPaused(context))
            return rejection(action, "主人已停止手机控制", "owner_paused", "denied")
        if (!LuluRolePhoneAuthorization.isGranted(context, role) ||
            !LuluScreenAwareness.onDemandAllowed(context))
            return rejection(action, "纯无障碍主屏操作需要本角色手机操作授权、屏幕读取授权及Android无障碍服务", "foreground_consent_missing", "waiting_user")
        if (context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true)
            return rejection(action, "手机已锁屏，没有操作或截取主屏", "device_locked", "waiting_user")
        val initial = withContext(Dispatchers.Main) { LuluAccessibilityService.observe() }
        if (!initial.connected)
            return rejection(action, "无障碍服务未连接；不要求无线调试", "accessibility_disconnected", "waiting_user")
        if (owner.isNotBlank() && owner != role)
            return rejection(action, "另一角色已使用可见主屏，请主人先停止", "owned_by_other_role", "waiting_user")
        if (!LuluBackgroundPhoneArgumentPolicy.isSupportedAction(action))
            return rejection(action, "不支持的主屏操作；请根据当前实际控件选择launch/observe/tap/tap_element/type/swipe/back/navigate", "unsupported_action")
        LuluBackgroundPhoneArgumentPolicy.invalidReason(action, args)?.let { reason ->
            return rejection(action, reason, "invalid_action_arguments")
        }
        if (action == "start") return rejection(action,
            "主屏模式没有独立屏可启动；请选择launch打开目标App，主人会看到操作画面",
            "launch_required", "unverified")

        if (action == "launch") {
            val pkg = args.optString("packageName").trim().ifBlank {
                runCatching { LuluInstalledAppResolver.resolve(context, args.optString("appName")) }
                    .getOrDefault("")
            }
            if (pkg.isBlank()) return rejection(action, "未找到唯一已安装应用，请提供准确App名称或包名", "app_resolution_failed")
            if (LuluScreenAwareness.isExcluded(context, pkg))
                return rejection(action, "目标App被主人隐私排除", "excluded_app", "denied")
            // Autonomous foreground takeover is only possible while Lulu (or
            // the same owned session) is visible; never steal an unrelated app.
            if (initial.packageName != context.packageName &&
                !(owner == role && initial.packageName == target))
                return rejection(action, "主人正在使用其他App；纯无障碍不能在隐藏后台另开屏幕，请回到露露机后重试",
                    "owner_using_foreground", "waiting_user")
            if (context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != true)
                return rejection(action, "主屏操作必须有可见的停止通知；请先打开露露机通知权限",
                    "stop_notification_unavailable", "waiting_user")
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return rejection(action, "系统未提供目标App的启动入口", "launcher_not_found")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val launched = runCatching { context.startActivity(intent) }.isSuccess
            if (!launched) return rejection(action, "Android拒绝打开目标App", "launch_rejected")
            var shown = false
            repeat(8) {
                delay(250)
                val now = withContext(Dispatchers.Main) { LuluAccessibilityService.observe() }
                if (now.packageName == pkg &&
                    LuluAccessibilityService.ownerForegroundPackage() == pkg) {
                    shown = true
                    return@repeat
                }
            }
            if (!shown) return rejection(action, "已请求打开App，但主屏尚未确认目标窗口；不能继续点击", "launch_unverified")
            owner = role; target = pkg; screen = null
            LuluBackgroundPhoneNotice.showForeground(context)
            return observe(context, role, requestId, action, accepted = true).toString()
        }

        if (owner != role || target.isBlank())
            return rejection(action, "尚未取得可见主屏会话；先launch目标App", "foreground_session_missing")
        if (!allowsObservedTarget(target, initial.packageName,
                LuluScreenAwareness.isExcluded(context, initial.packageName)) ||
            LuluAccessibilityService.ownerForegroundPackage() != target) {
            screen = null
            return rejection(action, "主人或系统已切换主屏窗口；已暂停，不再对旧坐标点击",
                "foreground_window_changed", "waiting_user")
        }
        if (action == "observe") return observe(context, role, requestId, action).toString()
        if (action == "adopt_app") return rejection(action,
            "可见主屏不会自动接管跳转后的第三方App；请主人回到露露机再明确launch",
            "foreground_adoption_requires_owner", "waiting_user")

        val previous = screen
        if (requiresNewObservation(action)) {
            if (previous == null)
                return rejection(action, "先observe取得本次真实主屏和screenSnapshotId", "foreground_snapshot_missing")
            val needsToken = action in setOf("tap", "long_press", "tap_element") ||
                (action == "type" && args.has("inputX"))
            if (needsToken && args.optString("screenSnapshotId") != previous.token)
                return rejection(action, "主屏快照编号已失效，先observe", "virtual_snapshot_expired")
            val fresh = withContext(Dispatchers.Main) { LuluAccessibilityService.observe() }
            val controls = withContext(Dispatchers.Main) { LuluAccessibilityService.controls() }
            if (previous.pkg != target || fresh.packageName != target ||
                fingerprint(fresh.visibleText, controls) != previous.fingerprint) {
                screen = null
                return rejection(action, "实际主屏内容已改变，旧控件和坐标全部失效", "virtual_snapshot_expired")
            }
        }
        if (args.optBoolean("sendLinkToChat") || args.optBoolean("sendProductLinkToChat"))
            return rejection(action, "主屏模式不能在后台读取第三方App的剪贴板并证明私聊卡片已送达；请使用系统分享入口，不把复制链接冒充已发送",
                "foreground_link_delivery_unavailable", "unverified")
        val accepted = when (action) {
            "tap", "long_press" -> {
                if (previous?.hasPicture != true || !LuluPhoneInteractionPolicy.mayInteract(
                        args.optString("target"), "", "",
                        LuluShoppingAssistPolicy.isEnabled(context, role)))
                    false
                else withContext(Dispatchers.Main) {
                    if (action == "tap") LuluAccessibilityService.tapNormalized(
                        args.optInt("x", -1), args.optInt("y", -1))
                    else LuluAccessibilityService.swipeNormalized(
                        args.optInt("x", -1), args.optInt("y", -1),
                        args.optInt("x", -1), args.optInt("y", -1),
                        args.optLong("durationMs", 700).coerceIn(180, 1600))
                }
            }
            "tap_element" -> {
                val ref = args.optString("elementRef")
                val chosen = (0 until (previous?.controls?.length() ?: 0)).mapNotNull {
                    previous?.controls?.optJSONObject(it)
                }.singleOrNull { it.optString("ref") == ref }
                if (chosen == null || !chosen.optBoolean("clickable") ||
                    !LuluPhoneInteractionPolicy.mayInteract(chosen.optString("name"), "",
                        "", LuluShoppingAssistPolicy.isEnabled(context, role))) false
                else withContext(Dispatchers.Main) {
                    LuluAccessibilityService.tapNormalized(chosen.optInt("x"), chosen.optInt("y"))
                }
            }
            "swipe" -> withContext(Dispatchers.Main) {
                LuluAccessibilityService.swipeNormalized(
                    args.optInt("startX", -1), args.optInt("startY", -1),
                    args.optInt("endX", -1), args.optInt("endY", -1),
                    args.optLong("durationMs", 400).coerceIn(180, 1600))
            }
            "back" -> withContext(Dispatchers.Main) {
                LuluAccessibilityService.perform(LuluScreenAction.Back)
            }
            "type" -> {
                if (args.has("inputX") && args.has("inputY")) {
                    if (previous?.hasPicture != true) return rejection(action,
                        "本角色没有本次真实主屏图片，不能猜输入框", "visual_input_unavailable")
                    val tapped = withContext(Dispatchers.Main) {
                        LuluAccessibilityService.tapNormalized(args.optInt("inputX", -1),
                            args.optInt("inputY", -1))
                    }
                    if (!tapped) return rejection(action, "未能点击输入框", "editor_focus_rejected")
                    delay(300)
                }
                val text = args.optString("text")
                if (text.isBlank() || text.length > 500) return rejection(action,
                    "输入文字必须为1至500字符", "invalid_action_arguments")
                withContext(Dispatchers.Main) { LuluAccessibilityService.typeIntoFocusedField(text) }
            }
            "navigate", "focus_search", "search" -> {
                if (action == "navigate" && args.optJSONArray("navigationLabels")?.length() != 1)
                    return rejection(action, "主屏模式每次只导航一个当前可见标签，必须观察新页面后才选下一项",
                        "foreground_navigation_one_step")
                val items = withContext(Dispatchers.Main) { LuluAccessibilityService.controls() }
                val label = if (action == "navigate") args.optJSONArray("navigationLabels")
                    ?.optString(0).orEmpty() else "搜索"
                val choices = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                    .filter { it.optBoolean("clickable") || it.optBoolean("editable") }
                    .filter {
                        val name = it.optString("text").ifBlank { it.optString("description") }
                        name.trim() == label ||
                            (action != "navigate" && it.optBoolean("editable") &&
                                (name.contains("搜索") || name.contains("Search", true)))
                    }
                if (choices.size != 1) false else {
                    val pos = choices[0].optJSONArray("bounds1000")
                    if (pos == null || !LuluPhoneInteractionPolicy.mayInteract(label, "", "",
                            LuluShoppingAssistPolicy.isEnabled(context, role))) false
                    else {
                        val clicked = withContext(Dispatchers.Main) {
                            LuluAccessibilityService.tapNormalized((pos.optInt(0) + pos.optInt(2)) / 2,
                                (pos.optInt(1) + pos.optInt(3)) / 2)
                        }
                        if (clicked && action == "search") {
                            delay(300)
                            val searched = withContext(Dispatchers.Main) {
                                LuluAccessibilityService.typeIntoFocusedField(args.optString("text"))
                            }
                            delay(150)
                            val exact = withContext(Dispatchers.Main) {
                                LuluAccessibilityService.focusedMainEditorMatches(
                                    target, args.optString("text"))
                            }
                            if (!searched || !exact) return rejection(action,
                                "搜索框未能精确读回文字，未自动点搜索提交；请重新observe",
                                "search_input_unverified")
                            val buttons = withContext(Dispatchers.Main) {
                                LuluAccessibilityService.controls()
                            }
                            val matches = (0 until buttons.length()).mapNotNull {
                                buttons.optJSONObject(it)
                            }.filter {
                                it.optBoolean("clickable") && !it.optBoolean("editable") &&
                                    it.optString("text").ifBlank {
                                        it.optString("description")
                                    }.trim() in setOf("搜索", "Search", "搜一搜", "查找")
                            }
                            if (matches.size != 1) return rejection(action,
                                "搜索草稿已精确写入，页面无唯一安全搜索按钮；请观察新截图自行决定是否点搜索",
                                "search_submit_button_unverified")
                            val bounds = matches.single().optJSONArray("bounds1000")
                                ?: return rejection(action, "搜索按钮没有有效坐标", "search_submit_button_unverified")
                            return if (withContext(Dispatchers.Main) {
                                LuluAccessibilityService.tapNormalized(
                                    (bounds.optInt(0) + bounds.optInt(2)) / 2,
                                    (bounds.optInt(1) + bounds.optInt(3)) / 2)
                            }) {
                                delay(380)
                                observe(context, role, requestId, action, accepted = true)
                                    .put("inputTextReadbackVerified", true)
                                    .put("searchSubmitClicked", true)
                                    .put("searchSubmitted", false)
                                    .put("taskCompleted", false)
                                    .put("verification", "已精确输入并点击一个搜索按钮；当前仅核实提交尝试，搜索结果需再观察确认")
                                    .toString()
                            } else rejection(action, "搜索草稿已输入，但搜索按钮点击未获Android接受",
                                "search_submit_rejected")
                        }
                        clicked
                    }
                }
            }
            else -> return rejection(action,
                "主屏模式请用逐步可验证的tap/type等动作；复合发送、发表评论和复制链接自动送达尚不能证明安全完成",
                "foreground_composite_unavailable")
        }
        if (!accepted) return rejection(action, "Android未接受操作、目标不唯一或命中受保护控件；未证明执行完成",
            "foreground_action_rejected")
        delay(380)
        val result = observe(context, role, requestId, action, accepted = true)
        if (action == "type") {
            val exact = withContext(Dispatchers.Main) {
                LuluAccessibilityService.focusedMainEditorMatches(target, args.optString("text"))
            }
            result.put("inputTextReadbackVerified", exact)
                .put("inputVerification", if (exact) "match" else "unverified")
                .put("success", exact)
                .put("status", if (exact) "observed" else "unverified")
                .put("verification", if (exact)
                    "已在当前主屏目标App焦点输入框精确读回草稿；没有自动发送"
                    else "已尝试输入，但未从同一焦点编辑框精确读回；不能认为草稿写入成功")
        }
        if (action == "search") result.put("searchSubmitted", false)
            .put("verification", "仅聚焦当前搜索入口；仍须type草稿并由角色依据真实画面点击搜索")
        if (action in setOf("tap", "tap_element", "long_press"))
            result.put("outwardEffectUnverified", true)
                .put("verification", "已尝试一次点击并观察新主屏；消息/评论/订单/链接是否送达仍未知，不能盲目重发")
        return result.toString()
    }

    private fun fingerprint(text: String, controls: JSONArray): String =
        (text.take(3500) + "#" + controls.toString()).hashCode().toUInt().toString(16)

    private fun elements(controls: JSONArray): JSONArray = JSONArray().also { out ->
        for (index in 0 until controls.length()) {
            val c = controls.optJSONObject(index) ?: continue
            val bounds = c.optJSONArray("bounds1000") ?: continue
            out.put(JSONObject().put("ref", "control-$index")
                .put("name", c.optString("text").ifBlank { c.optString("description") })
                .put("role", if (c.optBoolean("editable")) "editor" else "control")
                .put("clickable", c.optBoolean("clickable"))
                .put("editable", c.optBoolean("editable"))
                .put("x", (bounds.optInt(0) + bounds.optInt(2)) / 2)
                .put("y", (bounds.optInt(1) + bounds.optInt(3)) / 2))
        }
    }

    private suspend fun observe(context: Context, role: String, requestId: String,
        action: String, accepted: Boolean = false): JSONObject {
        val before = withContext(Dispatchers.Main) { LuluAccessibilityService.observe() }
        if (!allowsObservedTarget(target, before.packageName,
                LuluScreenAwareness.isExcluded(context, before.packageName)) ||
            LuluAccessibilityService.ownerForegroundPackage() != target) {
            screen = null
            return JSONObject(rejection(action, "主屏已不属于本角色当前目标App，停止截图和操作",
                "foreground_window_changed", "waiting_user"))
        }
        val controls = withContext(Dispatchers.Main) { LuluAccessibilityService.controls() }
        val pic = captureFrame(role, requestId)
        val after = withContext(Dispatchers.Main) { LuluAccessibilityService.observe() }
        if (after.packageName != target || LuluAccessibilityService.ownerForegroundPackage() != target ||
            LuluScreenAwareness.isExcluded(context, after.packageName)) {
            LuluScreenFrameHandoff.discard(role, requestId)
            screen = null
            return JSONObject(rejection(action, "观察时主屏发生切换，丢弃了旧截图",
                "foreground_window_changed", "waiting_user"))
        }
        val freshControls = withContext(Dispatchers.Main) { LuluAccessibilityService.controls() }
        val token = UUID.randomUUID().toString().take(12)
        val fp = fingerprint(after.visibleText, freshControls)
        val observed = elements(freshControls)
        screen = Screen(token, target, fp, observed, pic)
        return JSONObject().put("success", true).put("status", "observed")
            .put("accepted", accepted).put("taskCompleted", false)
            .put("mode", "foreground_accessibility").put("displayId", 0)
            .put("packageName", target).put("action", action)
            .put("targetWindowVerified", true).put("screenSnapshotId", token)
            .put("uiTreeFingerprint", fp).put("interactiveElements", observed)
            .put("visibleText", after.visibleText.take(4500))
            .put("imageForwardedToRole", pic).put("frameValidated", pic)
            .put("observationAvailable", pic || observed.length() > 0)
            .put("verification", if (pic)
                "真实可见主屏已交给同一角色；不是后台独立虚拟屏，主人操作或切换App会中断"
                else "只有可见主屏无障碍节点；未获取截图，不可凭空使用视觉坐标")
    }

    private suspend fun captureFrame(role: String, requestId: String): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 30) return false
        return try {
            val original = LuluAccessibilityService.captureBitmap()
            val image = withContext(Dispatchers.Default) {
                try {
                    val scale = minOf(1f, 680f / maxOf(original.width, original.height))
                    val scaled = if (scale < 1f)
                        Bitmap.createScaledBitmap(original,
                            maxOf(1, (original.width * scale).toInt()),
                            maxOf(1, (original.height * scale).toInt()), true) else original
                    try {
                        val buffer = ByteArrayOutputStream()
                        check(scaled.compress(Bitmap.CompressFormat.JPEG, 64, buffer))
                        "data:image/jpeg;base64," +
                            Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)
                    } finally {
                        if (scaled !== original) scaled.recycle()
                    }
                } finally { original.recycle() }
            }
            LuluScreenFrameHandoff.put(role, requestId, image)
            true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            false
        }
    }
}
