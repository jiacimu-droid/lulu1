package com.jiacimu.lulu.system

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import com.jiacimu.lulu.data.CompanionOnlineStore
import com.wangzan101.mika.core.adb.AdbConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The role's existing ToolRouter is the only decision/action authority.
 * This adapter exclusively owns the shell-backed virtual display transport.
 * No second agent, no direct API model call, no persona or memory store.
 *
 * The helper's adbd exec stdin/stdout is not exposed as a generic shell tool.
 * No action can inject into display 0. If trusted focus fails, refuse instead
 * of stealing the owner's main-screen focus.
 */
internal object LuluBackgroundPhoneSession {
    private const val REPLY_MARK = "LULU_DISPLAY:"
    private val mutex = Mutex()
    private val activeWork = MutableStateFlow<Map<String, Int>>(emptyMap())
    val runningWork = activeWork.asStateFlow()

    suspend fun <T> duringWork(characterId: String, work: suspend () -> T): T {
        activeWork.update { it + (characterId to (it.getOrDefault(characterId, 0) + 1)) }
        return try {
            work()
        } finally {
            activeWork.update {
                val count = it.getOrDefault(characterId, 0)
                if (count <= 1) it - characterId else it + (characterId to (count - 1))
            }
        }
    }
    @Volatile private var stoppedByOwner = false
    private const val OWNER_PREF = "lulu_background_phone_owner"
    private const val PAUSED = "paused"

    /** Owner STOP is persisted, so app death/restart cannot silently re-arm control. */
    fun resumeByOwner(context: Context) {
        check(context.getSharedPreferences(OWNER_PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(PAUSED, false).commit()) { "无法保存恢复授权" }
        stoppedByOwner = false
    }
    fun ownerHasPaused(context: Context): Boolean =
        stoppedByOwner || context.getSharedPreferences(OWNER_PREF, Context.MODE_PRIVATE)
            .getBoolean(PAUSED, false)
    private var adb: AdbConnection? = null
    private var stream: AdbConnection.AdbStream? = null
    private var displayId = -1
    private var activeApp = ""
    private var ownerCharacterId = ""
    private var appContext: Context? = null
    private val replies = LinkedBlockingQueue<String>()
    @Volatile private var lastHelperDiagnostic = ""

    private data class ObservedUi(
        val token: String,
        val fingerprint: String,
        val characterId: String,
        val displayId: Int,
        val packageName: String,
        // A semantic-node snapshot is not automatically a picture. Visual
        // coordinates require a real image forwarded to the SAME role.
        val visualFrameObserved: Boolean,
        val visualImage: String,
        val windowIdentity: String,
    )
    private var lastObservedUi: ObservedUi? = null
    // Scoped to the one authorized virtual session and cleared when navigating away.
    private var openedMomentsComposer = false

    /** No pixels are invented: every semantic ref comes from the owned
     * virtual App's CURRENT accessibility tree. A new observation supersedes
     * the preceding refs, and STOP/transport loss clears them. */
    private suspend fun attachUiEvidence(
        output: JSONObject, characterId: String, id: Int, packageName: String,
        frameImage: String? = null,
    ): JSONObject {
        val inventory = withContext(Dispatchers.Main) {
            LuluAccessibilityService.virtualUiSnapshot(id, packageName)
        }
        val fingerprint = inventory.optString("fingerprint")
        val elements = inventory.optJSONArray("elements")
        val pictureObserved = !frameImage.isNullOrBlank() &&
            output.optBoolean("imageForwardedToRole") &&
            output.optBoolean("frameValidated")
        val windowIdentity = withContext(Dispatchers.Main) {
            LuluAccessibilityService.virtualChatWindowIdentity(id, packageName)
        }
        // Some OEMs render a complete app screenshot while exposing no
        // accessibility tree at all. Do not throw away the role's ONE-SHOT
        // visual action token in that case. Never manufacture a fake tree.
        val semanticObserved = fingerprint.isNotBlank() && elements != null
        if (!semanticObserved && !(pictureObserved && windowIdentity.isNotBlank())) {
            lastObservedUi = null
            return output.put("uiEvidenceSource", "unavailable")
        }
        val token = java.util.UUID.randomUUID().toString().substring(0, 12)
        lastObservedUi = ObservedUi(token, if (semanticObserved) fingerprint else "",
            characterId, id, packageName,
            pictureObserved, if (pictureObserved) frameImage.orEmpty() else "", windowIdentity)
        if (openedMomentsComposer && packageName == LuluVirtualChatSendPolicy.WECHAT)
            output.put("momentComposerVerified", withContext(Dispatchers.Main) {
                LuluAccessibilityService.virtualWeChatMomentsComposerVisible(id)
            })
        return output.put("screenSnapshotId", token)
            .put("uiTreeFingerprint", if (semanticObserved) fingerprint else "")
            .put("interactiveElements", if (semanticObserved) elements else org.json.JSONArray())
            .put("focusedEditorVerified", withContext(Dispatchers.Main) {
                LuluAccessibilityService.hasFocusedVirtualEditor(id, packageName)
            })
            .put("chatHeaderTexts", inventory.optJSONArray("chatHeaderTexts") ?: org.json.JSONArray())
            .apply {
                // The screenshot can be valid while HyperOS exposes zero
                // accessible controls. Preserve that diagnosis in the actual
                // role-visible observe receipt instead of dropping it here.
                inventory.optJSONObject("chatInputDiagnostics")?.let {
                    put("chatInputDiagnostics", it)
                }
            }
            .put("uiEvidenceSource", when {
                !semanticObserved -> "virtual_picture_only"
                elements!!.length() == 0 -> "virtual_accessibility_tree_empty"
                else -> "virtual_accessibility_tree"
            })
    }

    fun connected(): Boolean = stream != null && displayId > 0 && adb?.isClosed == false

    /**
     * Owner-only read-only look into the ALREADY running virtual display.
     * No helper startup, model request, user main-screen screenshot, image
     * persistence, timeline action, or role-frame handoff. A missing window,
     * app ownership change, expired permission or protected app is a hard stop.
     */
    data class OwnerPreviewFrame(
        val displayId: Int,
        val packageName: String,
        val imageDataUrl: String,
        val timestampNanos: Long,
        val newerThanPreviousCapture: Boolean,
    )

    suspend fun ownerPreview(context: Context, characterId: String): OwnerPreviewFrame {
        mutex.lock()
        try {
            LuluBackgroundPhonePreviewGate.requireAccess(
                requestedCharacterId = characterId,
                ownerCharacterId = ownerCharacterId,
                displayId = displayId,
                packageName = activeApp,
                connected = connected(),
                ownerPaused = ownerHasPaused(context),
                screenConsent = LuluRolePhoneAuthorization.isGranted(context, characterId),
                deviceLocked = context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true,
                excluded = LuluScreenAwareness.isExcluded(context, activeApp),
            )
            val id = displayId
            val pkg = activeApp
            val before = withContext(Dispatchers.Main) {
                LuluAccessibilityService.observeDisplay(id)
            }
            check(before.connected && before.packageName == pkg) {
                "后台窗口已切换或无法核实；禁止显示未经确认的画面"
            }
            val captured = command(JSONObject().put("action", "observe"))
            check(captured.optBoolean("success") &&
                captured.optBoolean("frameValidated") &&
                captured.optLong("frameTimestampNanos", 0L) > 0L) {
                captured.optString("verification").ifBlank { "后台没有新的可核验截图" }
            }
            val after = withContext(Dispatchers.Main) {
                LuluAccessibilityService.observeDisplay(id)
            }
            check(after.connected && after.packageName == pkg &&
                !LuluScreenAwareness.isExcluded(context, after.packageName)) {
                "截图期间后台窗口发生变化，已丢弃这帧图像"
            }
            val pixels = captured.optString("imageDataUrl")
            check(pixels.startsWith("data:image/jpeg;base64,")) {
                "独立后台屏没有可读取的 JPEG 画面"
            }
            return OwnerPreviewFrame(id, pkg, pixels,
                captured.optLong("frameTimestampNanos"),
                captured.optBoolean("newerThanPreviousCapture"))
        } finally {
            mutex.unlock()
        }
    }


    /** Mika's bounded ChangeWait algorithm; polls the verified app's
     * accessibility tree, not a new paid model call. A new frame alone does
     * not imply that a navigation, search, or gesture really worked. */
    private suspend fun awaitStableUi(
        displayId: Int, packageName: String, beforeFingerprint: String,
        navigation: Boolean = false,
    ): Boolean {
        if (beforeFingerprint.isBlank()) return false
        return withContext(Dispatchers.Main) {
            fun fingerprint() = if (navigation)
                LuluAccessibilityService.virtualNavigationFingerprint(displayId, packageName)
            else LuluAccessibilityService.virtualUiSnapshot(displayId, packageName).optString("fingerprint")
            val start = android.os.SystemClock.uptimeMillis()
            var lastTree = beforeFingerprint
            var lastChangeAt = start
            LuluMikaChangeWait.awaitStableChange(
                now = android.os.SystemClock::uptimeMillis,
                sleep = { kotlinx.coroutines.delay(it) },
                frame = { null },
                quietForMs = { android.os.SystemClock.uptimeMillis() - lastChangeAt },
                treeChanged = {
                    val observed = fingerprint()
                    if (observed.isNotBlank() && observed != lastTree) {
                        lastTree = observed
                        lastChangeAt = android.os.SystemClock.uptimeMillis()
                    }
                    lastTree != beforeFingerprint
                },
                beforeFrame = null,
            )
            // The wait may have seen a transient transition that subsequently
            // returned to the original page. Only the final owned UI counts.
            val finalWindow = LuluAccessibilityService.observeDisplay(displayId)
            val finalFingerprint = if (finalWindow.connected &&
                finalWindow.packageName == packageName) {
                fingerprint()
            } else ""
            finalFingerprint.isNotBlank() && finalFingerprint != beforeFingerprint
        }
    }

    /** The last Mika ScreenDiagnosis port scanned all page prose, which
     * could misread an ordinary Xiaohongshu note mentioning "验证码" as a
     * captcha. Only actual actionable security controls (or password nodes)
     * can block normal reading now. The page body stays read-only evidence. */
    private suspend fun humanOnlyPage(displayId: Int, packageName: String): String? {
        val (controls, password) = withContext(Dispatchers.Main) {
            val nodes = LuluAccessibilityService.virtualUiSnapshot(displayId, packageName)
                .optJSONArray("elements")
            val controls = (0 until (nodes?.length() ?: 0)).mapNotNull { index ->
                nodes?.optJSONObject(index)?.let { node ->
                    LuluVirtualHumanGate.Control(
                        name = node.optString("name"),
                        role = node.optString("role"),
                        resourceId = node.optString("resourceId"),
                        clickable = node.optBoolean("clickable"),
                        editable = node.optBoolean("editable"),
                    )
                }
            }
            controls to LuluAccessibilityService.virtualHasPasswordField(displayId, packageName)
        }
        val cues = LuluVirtualHumanGate.securityLabels(controls)
        if (cues.isEmpty() && !password) return null
        val checked = LuluMikaScreenDiagnosis.classify(
            LuluMikaScreenFacts(
                currentApp = packageName, hasWindow = true,
                readableElements = controls.size,
                changing = false, blackFrames = emptyList(),
                texts = cues, passwordField = password,
            )
        )
        return if (checked.needsHuman) checked.takeoverReason ?: "manual_verification" else null
    }

    /** Resolve brief Android window handoffs locally, without charging another
     * model call or issuing any input. Never wait through a system dialog or
     * different application's window: those must go back to the role/user. */
    private suspend fun windowGate(displayId: Int, targetApp: String): JSONObject {
        var gate = withContext(Dispatchers.Main) {
            LuluAccessibilityService.virtualWindowStatus(displayId, targetApp)
        }
        repeat(4) {
            val kind = gate.optString("kind")
            val reason = gate.optString("windowDiagnostic")
            if (!LuluVirtualWindowPolicy.canRetryReadOnly(kind, reason) &&
                !LuluVirtualWindowPolicy.canRetryServiceConnectionReadOnly(kind, reason)) return gate
            kotlinx.coroutines.delay(200)
            gate = withContext(Dispatchers.Main) {
                LuluAccessibilityService.virtualWindowStatus(displayId, targetApp)
            }
        }
        return gate
    }

    /** A picker, permission layer or another app is not permission to
     * click an underlying window. We never move the owner's task ourselves. */
    private fun pausedWindowReceipt(displayId: Int, gate: JSONObject): String {
        val kind = gate.optString("kind", "unknown")
        val manual = kind == "system_dialog"
        val diagnostic = gate.optString("windowDiagnostic")
        val reason = when (kind) {
            "system_dialog" -> "后台屏有系统选择器、权限或其他覆盖弹窗，不能点击底下的目标 App；需要主人确认"
            "other_app" -> "本独立屏当前进入actualPackage所示应用；没有操作它。可由本人选择adopt_app(packageName=actualPackage)核实后接续，或launch打开别的已授权App"
            else -> when (diagnostic) {
                "accessibility_disconnected" -> "无障碍服务未连接；后台屏内容不可核实，请检查本机服务状态"
                "unsupported_sdk", "invalid_display" -> "独立后台屏不可用；请检查设备或显示会话"
                "target_unspecified" -> "尚未确认目标应用，不能读取其他应用的窗口"
                "no_window" -> "独立屏暂时没有可观察的应用窗口；可以稍后只读观察"
                "root_unreadable", "package_unreadable" -> "独立屏有窗口但暂时不能核实应用归属；可以稍后只读观察"
                else -> "后台窗口归属暂时不可核实；不可点击或输入，可再只读观察"
            }
        }
        return JSONObject().put("success", false)
            .put("status", if (manual) "waiting_user" else "unverified")
            .put("errorCode", if (manual) "window_takeover" else "window_unverified")
            .put("mode", "independent_virtual_display").put("displayId", displayId)
            .put("windowKind", kind)
            .put("windowDiagnostic", diagnostic)
            .put("readOnlyReobserveAllowed",
                LuluVirtualWindowPolicy.canRetryReadOnly(kind, diagnostic))
            .put("actualPackage", gate.optString("actualPackage").take(160))
            .put("needsHuman", manual)
            .put("targetWindowVerified", false).put("accepted", false)
            .put("taskCompleted", false).put("verification", reason).toString()
    }

    private fun humanOnlyReceipt(
        displayId: Int, packageName: String, reason: String,
    ): String {
        if (reason == "window_changed") return pausedWindowReceipt(displayId,
            JSONObject().put("kind", "other_app").put("actualPackage", packageName))
        // A verified merchant payment screen is a HANDOFF, not proof the
        // owner's main screen has inherited the virtual-display checkout task.
        if (reason == "payment" && packageName == activeApp &&
            ownerCharacterId.isNotBlank()) {
            appContext?.let { ownerContext ->
                runCatching {
                    LuluBackgroundPhoneNotice.showPaymentHandoff(ownerContext, packageName)
                }
            }
        }
        return JSONObject().put("success", false).put("status", "waiting_user")
        .put("mode", "independent_virtual_display").put("displayId", displayId)
        .put("packageName", packageName).put("screenState", "needs_human")
        .put("needsHuman", true).put("humanReason", reason)
        .put("accepted", false).put("taskCompleted", false)
        .put("verification", if (reason == "window_changed")
            "后台屏顶层窗口切换到了另一应用或系统弹窗；未确认仍属于本任务，已暂停操作，需主人或原角色核对"
            else "后台出现需要主人亲自处理的登录、验证码、支付或安全验证界面（$reason）；已停止自动操作，不读取密码或代替确认")
        .toString()
    }

    private fun forgetTransport() {
        appContext?.let { runCatching { LuluBackgroundPhoneNotice.clear(it) } }
        appContext = null
        displayId = -1
        activeApp = ""
        openedMomentsComposer = false
        ownerCharacterId = ""
        lastObservedUi = null
        runCatching { stream?.close() }
        runCatching { adb?.close() }
        stream = null
        adb = null
        replies.clear()
        lastHelperDiagnostic = ""
    }

    private suspend fun ensureStarted(context: Context): Int {
        if (connected()) return displayId
        forgetTransport()
        check(Build.VERSION.SDK_INT >= 34) { "独立焦点虚拟屏需要 Android 14 及以上" }
        val source = context.applicationInfo.sourceDir
        check(source.matches(Regex("/[a-zA-Z0-9_./~+=-]+\\.apk"))) {
            "不能安全定位本机 APK 的 classpath"
        }
        val conn = LuluWirelessDebugging.openShell(context)
        try {
            // Exactly the same app_process mechanism used by Mika, but a
            // smaller helper with no Mika chat loop/HTTP server/role identity.
            val launchCommand = "CLASSPATH=" + source +
                " app_process /system/bin --nice-name=lulu:display " +
                "com.wangzan101.mika.cap.phone.shell.LuluShellDisplayMain"
            val pipe = conn.exec(launchCommand)
            adb = conn
            stream = pipe
            replies.clear()
            lastHelperDiagnostic = ""
            thread(isDaemon = true, name = "lulu-background-display-replies") {
                try {
                    pipe.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (line.startsWith(REPLY_MARK)) replies.offer(line.removePrefix(REPLY_MARK))
                            else if (line.isNotBlank()) lastHelperDiagnostic = line.take(220)
                        }
                    }
                } catch (_: Exception) {
                    // The in-flight tool will time out and release the helper.
                }
            }
            val primaryDensity = context.getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.let { primary ->
                    context.createDisplayContext(primary).resources.configuration.densityDpi
                }
            val started = command(JSONObject().put("action", "start")
                .put("ownerDensityDpi", primaryDensity ?: 0))
            check(started.optBoolean("success") && started.optBoolean("ownFocus")) {
                started.optString("error").ifBlank { "后台独立焦点屏创建失败" }
            }
            val id = started.optInt("displayId", -1)
            check(id > 0) { "系统返回主屏/无效屏幕ID，拒绝操作" }
            displayId = id
            return id
        } catch (failure: Throwable) {
            forgetTransport()
            conn.close()
            throw failure
        }
    }

    // Once a helper write is attempted, a missing reply cannot prove that
    // the device did nothing. Do not invite a replay of an uncertain click.
    internal class UnverifiedHelperCommandException(message: String, cause: Throwable? = null) :
        IllegalStateException(message, cause)

    internal fun commandFailureStatus(error: Throwable): String =
        if (error is UnverifiedHelperCommandException) "unverified" else "failed"

    private suspend fun command(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        // STOP may arrive while a multi-step operation is already holding mutex.
        // Reject every subsequent helper command at its final dispatch boundary;
        // allow only the explicit stop command to close the owned display.
        if (args.optString("action") != "stop") {
            check(!stoppedByOwner && (appContext == null || !ownerHasPaused(appContext!!))) {
                "主人已停止后台手机操作；本次设备命令未派发"
            }
        }
        val pipe = stream ?: error("shell helper disconnected")
        try {
            pipe.write((args.toString() + "\n").toByteArray(Charsets.UTF_8))
        } catch (error: Exception) {
            // A partial write may already have reached the helper.
            throw UnverifiedHelperCommandException("shell helper write outcome unknown", error)
        }
        val line = replies.poll(13, TimeUnit.SECONDS)
            ?: throw UnverifiedHelperCommandException(
                "shell helper reply missing for ${args.optString("action")}" +
                    lastHelperDiagnostic.takeIf(String::isNotBlank)?.let { "；helper: $it" }.orEmpty()
            )
        val response = try {
            JSONObject(line)
        } catch (error: Exception) {
            throw UnverifiedHelperCommandException("shell helper reply malformed; device action outcome unknown", error)
        }
        // Only start/stop may omit an owned display identifier.
        if (args.optString("action") !in setOf("start", "stop")) {
            if (displayId <= 0 || response.optInt("displayId", -1) != displayId) {
                throw UnverifiedHelperCommandException("来自未知虚拟屏的回执已丢弃；设备动作结果未知")
            }
        }
        response
    }

    suspend fun stop(context: Context? = null): Boolean {
        // The in-memory flag takes effect immediately, before an in-flight
        // model/ADB command releases the lock.
        stoppedByOwner = true
        val ownerContext = context ?: appContext
        if (ownerContext != null) {
            check(ownerContext.getSharedPreferences(OWNER_PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(PAUSED, true).commit()) {
                "停止请求已应用但未能持久保存；请关闭系统无线调试"
            }
        }
        mutex.lock()
        try {
            if (!connected()) return false
            return runCatching { command(JSONObject().put("action", "stop")) }
                .getOrNull()?.optBoolean("success") == true
        } finally {
            forgetTransport()
            mutex.unlock()
        }
    }

    private suspend fun typeSearchText(
        id: Int, packageName: String, text: String,
    ): LuluMikaTextAccuracy.Verification = withContext(Dispatchers.Main) {
        LuluMikaTextAccuracy.typeAndVerify(
            field = object : LuluMikaTextAccuracy.TextField {
                override fun read() = LuluAccessibilityService.readVirtualSearchFieldText(id, packageName)
                override fun write(text: String) =
                    LuluAccessibilityService.typeOnDisplay(id, packageName, text)
            },
            text = text,
            settle = { kotlinx.coroutines.delay(100) },
        )
    }

    private suspend fun pressVisualTarget(
        id: Int, packageName: String, x: Int, y: Int, holdMs: Int?, authorized: () -> Boolean,
    ): JSONObject {
        val prepared = command(JSONObject().put("action", "prepare_type"))
        if (!prepared.optBoolean("accepted")) return JSONObject().put("accepted", false)
            .put("errorCode", "visual_touch_frame_barrier_missing")
            .put("verification", "无法建立触摸前的画面校验，未派发触摸")
        val touch = withContext(Dispatchers.Main) {
            LuluAccessibilityService.pressVirtualDisplay(id, packageName, x, y, holdMs, authorized)
        }
        // Only a synchronous, explicit rejection means the first transport
        // did not take the gesture. Cancellation, timeout and no UI change
        // are never a reason to send another tap.
        if (touch.optString("errorCode") != "virtual_gesture_dispatch_rejected" ||
            !authorized() || windowGate(id, packageName).optString("kind") != "target" ||
            humanOnlyPage(id, packageName) != null || !authorized()) return touch
        return command(JSONObject().put("action", if (holdMs == null) "tap" else "long_press")
            .put("packageName", packageName).put("x", x).put("y", y)
            .apply { if (holdMs != null) put("durationMs", holdMs) })
    }

    /** A snapshot-selected point can only write a draft on the owned display.
     * A keyboard window is useful evidence, but custom editors may accept
     * targeted paste without ever showing a soft keyboard (Mika typeAt). */
    private suspend fun typeVisualVirtualField(
        id: Int, packageName: String, text: String,
        x: Int, y: Int, authorized: () -> Boolean, requiredWindow: String? = null,
    ): JSONObject {
        fun stopped(code: String, detail: String): JSONObject = JSONObject()
            .put("accepted", false).put("inputTextReadbackVerified", false)
            .put("inputVerification", "unverified")
            .put("errorCode", code).put("taskCompleted", false)
            .put("verification", detail)

        suspend fun windowIdentity() = withContext(Dispatchers.Main) {
            LuluAccessibilityService.virtualChatWindowIdentity(id, packageName)
        }
        val previousWindow = windowIdentity()
        if (!authorized() || previousWindow.isBlank() ||
            (requiredWindow != null && previousWindow != requiredWindow))
            return stopped("visual_input_window_unverified", "当前独立屏的目标窗口或授权未核实，未输入")
        val imeBefore = withContext(Dispatchers.Main) {
            LuluAccessibilityService.virtualInputImeTopPermille(id, packageName)
        }
        if (!LuluVirtualTextInputPolicy.inputPointSafe(x, y, imeBefore))
            return stopped("visual_input_field_point_invalid",
                "本次真实截图的输入点落在状态栏、键盘或屏幕外，未输入")
        val selectedEditor = withContext(Dispatchers.Main) {
            LuluAccessibilityService.visualEditorAtPoint(id, packageName, x, y)
        }
        val protected = withContext(Dispatchers.Main) {
            val elements = LuluAccessibilityService.virtualUiSnapshot(id, packageName).optJSONArray("elements")
            (elements?.length() ?: 0) > 0 &&
                LuluAccessibilityService.isSensitiveVirtualTap(id, packageName, x, y)
        }
        if (protected) return stopped("visual_input_sensitive_field",
            "当前输入位置与受保护控件重合，拒绝按文字输入处理")
        if (windowIdentity() != previousWindow || !authorized())
            return stopped("visual_input_window_unverified", "聚焦前独立屏窗口或授权已变化，未输入")
        val tap = pressVisualTarget(id, packageName, x, y, holdMs = null, authorized = authorized)
        if (!tap.optBoolean("accepted")) return stopped("visual_input_focus_tap_unverified",
            tap.optString("verification").ifBlank { "设备未确认完成对输入框的触摸" })
            .put("inputFocusTapCompleted", false)
            .put("inputFocusTapError", tap.optString("errorCode"))

        var pasteWindow = ""
        var focusRecoveryAttempted = false
        suspend fun resolveFocus() = withContext(Dispatchers.Main) {
            LuluAccessibilityService.visualInputFocus(id, packageName, x, y, selectedEditor, previousWindow)
        }
        // Give the accessibility editor time to appear or move before using the
        // hardware fallback. Once text was attempted, only read back.
        for (attempt in 0 until 8) {
            if (!authorized() || (requiredWindow != null && windowIdentity() != requiredWindow)) break
            val focus = resolveFocus()
            if (attempt == 2 && focus.route == LuluVirtualTextInputPolicy.VisualInputRoute.WAIT &&
                selectedEditor != null && windowIdentity() == previousWindow &&
                humanOnlyPage(id, packageName) == null) {
                focusRecoveryAttempted = withContext(Dispatchers.Main) {
                    LuluAccessibilityService.requestVisualEditorFocus(
                        id, packageName, selectedEditor, previousWindow, authorized)
                }
            }
            if (focus.route == LuluVirtualTextInputPolicy.VisualInputRoute.SET_TEXT) {
                if (humanOnlyPage(id, packageName) != null) break
                val typed = withContext(Dispatchers.Main) {
                    val current = resolveFocus()
                    if (current.editor != focus.editor ||
                        current.route != LuluVirtualTextInputPolicy.VisualInputRoute.SET_TEXT ||
                        (requiredWindow != null && windowIdentity() != requiredWindow)) null
                    else LuluAccessibilityService.typeFocusedVisualEditor(
                        id, packageName, checkNotNull(current.editor), text, authorized)
                }
                if (typed != null) {
                    typed.put("inputFocusTapCompleted", true)
                        .put("inputFocusRecoveryAttempted", focusRecoveryAttempted)
                    if (typed.optBoolean("accepted") && !typed.optBoolean("inputTextReadbackVerified")) {
                        repeat(2) {
                            kotlinx.coroutines.delay(120)
                            if (!authorized() || resolveFocus().editor != focus.editor) return typed
                            val matched = withContext(Dispatchers.Main) {
                                LuluAccessibilityService.focusedVirtualDraftMatches(id, packageName, text)
                            }
                            if (matched) return typed.put("inputTextReadbackVerified", true)
                                .put("inputVerification", "match").put("errorCode", "")
                                .put("verification", "当前后台输入框已精确读回文字；草稿尚未提交")
                        }
                    }
                    return typed
                }
            }
            if (attempt >= 2 && focus.route == LuluVirtualTextInputPolicy.VisualInputRoute.TARGETED_PASTE) {
                val focusedWindow = windowIdentity()
                if (focusedWindow.isBlank() || humanOnlyPage(id, packageName) != null) break
                if (authorized() && (requiredWindow == null || focusedWindow == requiredWindow) &&
                    resolveFocus().route == LuluVirtualTextInputPolicy.VisualInputRoute.TARGETED_PASTE &&
                    windowIdentity() == focusedWindow) {
                    pasteWindow = focusedWindow
                    break
                }
            }
            if (attempt < 7) kotlinx.coroutines.delay(150)
        }
        if (pasteWindow.isBlank() || !authorized()) return stopped("visual_input_focus_unverified",
            "设备已回报触摸完成，但未确认目标文字框取得焦点，文字未派发。请从新画面核对输入栏和光标。")
            .put("inputFocusTapCompleted", true)
            .put("inputFocusRecoveryAttempted", focusRecoveryAttempted)

        // The shell binds the shortcut to THIS display and target UID. Focus
        // evidence authorizes a single attempt, never proves it inserted text.
        val pasted = command(JSONObject().put("action", "paste_text")
            .put("packageName", packageName).put("text", text))
        var exactDraft = false
        if (pasted.optBoolean("inputDispatched")) {
            for (attempt in 0 until 3) {
                if (!authorized() || windowIdentity() != pasteWindow) break
                exactDraft = withContext(Dispatchers.Main) {
                    LuluAccessibilityService.focusedVirtualDraftMatches(id, packageName, text)
                }
                if (exactDraft) break
                if (attempt < 2) kotlinx.coroutines.delay(140)
            }
        }
        return JSONObject().put("accepted", pasted.optBoolean("accepted"))
            .put("inputFocusTapCompleted", true)
            .put("inputFocusRecoveryAttempted", focusRecoveryAttempted)
            .put("inputDispatched", pasted.optBoolean("inputDispatched"))
            .put("inputTextReadbackVerified", exactDraft)
            .put("inputVerification", if (exactDraft) "match" else "unverified")
            .put("inputMethod", "visual_target_uid_unicode")
            .put("actionMethod", pasted.optString("actionMethod"))
            .put("errorCode", pasted.optString("errorCode"))
            .put("taskCompleted", false)
            .put("verification", when {
                exactDraft -> "已从独立屏目标输入框精确读回文字；只是草稿，未发送或提交"
                pasted.optBoolean("accepted") ->
                    "已向独立屏目标App派发一次Unicode草稿；请核对新画面，未发送或提交"
                else -> "定向输入未被完整核实；请核对当前画面，未发送或提交"
            })
    }

    /**
     * One verified visual WeChat transaction for ROMs where a real virtual
     * screenshot exists but no chat EditText / ACTION_SET_TEXT node does.
     *
     * The model supplies an input point from its last owned screenshot.
     * The outgoing Send point comes exclusively from local OCR of
     * a fresh screenshot showing title + exact draft + Send together.
     *
     * The complete draft and app Send control must be verified before any
     * send. An ambiguous send attempt can only be followed by observation.
     */
    private suspend fun visualWeChatSend(
        id: Int, packageName: String, recipient: String, message: String,
        composerX: Int, composerY: Int, authorized: () -> Boolean,
        frameSource: suspend () -> android.graphics.Bitmap?,
    ): JSONObject {
        var titleVerified = false
        fun denied(code: String, explanation: String): JSONObject = JSONObject()
            .put("accepted", false).put("messageSendClicked", false)
            .put("messageSendAttempted", false)
            .put("chatRecipientVerified", titleVerified)
            .put("taskCompleted", false).put("errorCode", code)
            .put("verification", explanation)
            .put("actionMethod", "visual_chat_targeted_unicode")
        if (packageName != LuluVirtualChatSendPolicy.WECHAT ||
            !LuluVisualChatDraftPolicy.composerPointSafe(composerX, composerY))
            return denied("visual_chat_composer_point_invalid",
                "视觉输入位置不在可接受的聊天输入区，未点击或输入")
        if (!authorized() || windowGate(id, packageName).optString("kind") != "target")
            return denied("visual_chat_window_unverified", "后台微信窗口或授权未核实，未输入")
        val before = withContext(Dispatchers.Main) {
            LuluAccessibilityService.virtualChatWindowIdentity(id, packageName)
        }
        if (before.isBlank()) return denied("visual_chat_window_unverified", "独立微信窗口身份不可用")
        // Verify the actual rendered header BEFORE even focusing the editor.
        val title = LuluVirtualChatTitleOcr.matches(
            id, packageName, recipient, forceFresh = true, frameSource = frameSource)
        if (!title || !authorized()) return denied("chat_recipient_unverified",
            "没有从本次后台微信真实截图核实唯一收件人标题；未输入")
        titleVerified = true
        // An exact current draft can be reused, including after a pre-send
        // interruption. Otherwise use the same input transport as every app.
        val existingDraft = LuluVirtualChatTitleOcr.inspectVisualDraft(
            id, packageName, recipient, message, frameSource)
        val proof = if (existingDraft != null) existingDraft else {
            val typed = typeVisualVirtualField(
                id, packageName, message, composerX, composerY, authorized, requiredWindow = before)
            if (!typed.optBoolean("accepted")) return denied(
                typed.optString("errorCode").ifBlank { "visual_chat_draft_input_unverified" },
                typed.optString("verification"),
            ).put("inputDispatched", typed.optBoolean("inputDispatched"))
                .put("inputAttempted", typed.optBoolean("inputAttempted"))
            val visualDraft = LuluVirtualChatTitleOcr.inspectVisualDraft(
                id, packageName, recipient, message, frameSource)
            if (visualDraft == null && typed.optBoolean("inputTextReadbackVerified") && authorized()) {
                // Some ROMs hide the IME bounds needed to distinguish an OCR
                // Send label from the keyboard. An actual app-owned control
                // can still be verified. This route may only reuse the draft.
                return withContext(Dispatchers.Main) {
                    val snapshot = LuluAccessibilityService.virtualUiSnapshot(id, packageName)
                    LuluAccessibilityService.sendVirtualChatMessage(
                        id, packageName, recipient, message, snapshot.optString("fingerprint"),
                        frameSource = frameSource, beforeTextInput = { false },
                        stillAuthorized = {
                            authorized() &&
                                LuluAccessibilityService.virtualChatWindowIdentity(id, packageName) == before
                        },
                    )
                }.put("inputDispatched", typed.optBoolean("inputDispatched"))
            }
            visualDraft ?: return denied("visual_chat_draft_or_send_unverified",
                    "已处理后台微信草稿；当前画面未同时核实收件人、全文和应用自己的发送键，未点发送")
                    .put("inputDispatched", typed.optBoolean("inputDispatched"))
                    .put("messageInputReadbackVerified", typed.optBoolean("inputTextReadbackVerified"))
        }
        if (!authorized() || windowGate(id, packageName).optString("kind") != "target" ||
            withContext(Dispatchers.Main) {
                LuluAccessibilityService.virtualChatWindowIdentity(id, packageName)
            } != before) return denied("visual_chat_changed_before_send",
            "发送前后台窗口或授权变化，已保留未发送草稿")
            .put("messageInputReadbackVerified", true).put("chatRecipientVerified", true)
        if (!command(JSONObject().put("action", "prepare_type")).optBoolean("accepted"))
            return denied("visual_chat_send_barrier_missing",
                "正文核实但发送前无法建立图像屏障，未点发送")
                .put("messageInputReadbackVerified", true).put("chatRecipientVerified", true)
        // ONE irreversible action; OCR provided the center of a unique button.
        val click = withContext(Dispatchers.Main) {
            LuluAccessibilityService.tapVirtualDisplay(id, packageName,
                proof.sendX, proof.sendY, authorized)
        }
        val attempted = click.optBoolean("dispatchAttempted")
        val clicked = click.optBoolean("accepted")
        return JSONObject().put("accepted", clicked)
            .put("messageSendAttempted", attempted)
            .put("messageSendClicked", clicked)
            .put("messageSendMethod", "fresh_visual_ocr_unique_send")
            .put("messageReusedExistingDraft", existingDraft != null)
            .put("messageInputReadbackVerified", true)
            .put("chatRecipientVerified", true)
            .put("visualOutgoingBefore", proof.previousOutgoing)
            .put("errorCode", if (clicked) "" else "visual_chat_send_click_unverified")
            .put("taskCompleted", false)
            .put("verification", if (clicked)
                "截图已核实收件人/草稿/唯一发送键，并仅在独立屏点发送一次；仍需核实新消息气泡"
                else "一次发送手势的效果不确定，禁止再次点击发送")
    }

    private data class SearchDispatch(val receipt: JSONObject, val beforeSubmitTree: String? = null)

    /** Mika BatchRunner pattern for one explicitly selected search. Every
     * boundary rechecks the actual target; only the final screen is captured. */
    private suspend fun search(
        context: Context, id: Int, packageName: String, characterId: String,
        unreadAtStart: Set<String>, text: String,
    ): SearchDispatch {
        suspend fun interruption(): JSONObject? {
            if (ownerHasPaused(context) || !LuluRolePhoneAuthorization.isGranted(context, characterId) ||
                LuluScreenAwareness.isExcluded(context, packageName) ||
                context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true) {
                return JSONObject().put("status", "denied")
                    .put("verification", "后台搜索已暂停：屏幕授权或设备状态已变化")
            }
            if (LuluBackgroundPhoneContinuation.hasNewUnreadInput(
                    unreadAtStart, CompanionOnlineStore.unreadChatSnapshot(characterId).messageIds.toSet())) {
                return JSONObject().put("status", "unverified").put("errorCode", "new_user_input")
                    .put("verification", "收到主人新消息，未继续提交旧搜索")
            }
            val gate = windowGate(id, packageName)
            if (gate.optString("kind") != "target") return JSONObject(pausedWindowReceipt(id, gate))
            return humanOnlyPage(id, packageName)?.let { JSONObject(humanOnlyReceipt(id, packageName, it)) }
        }

        fun stopped(stage: String, reason: String): JSONObject = JSONObject()
            .put("searchStage", stage).put("errorCode", "search_" + stage)
            .put("verification", reason).put("accepted", false)

        interruption()?.let { return SearchDispatch(it.put("searchStage", "before_focus")) }
        val focused = withContext(Dispatchers.Main) {
            LuluAccessibilityService.hasFocusedVirtualSearchEditor(id, packageName) ||
                LuluAccessibilityService.focusUniqueVirtualSearchEditor(id, packageName)
        }
        if (!focused) return SearchDispatch(stopped("focus_unverified",
            "未找到可核实的唯一搜索框。请根据后台控件打开搜索入口，未输入或提交。"))

        interruption()?.let { return SearchDispatch(it.put("searchStage", "before_input")) }
        val typed = typeSearchText(id, packageName, text)
        interruption()?.let { return SearchDispatch(it.put("searchStage", "before_submit")) }
        // Use the tree AFTER typing as the baseline for Enter. Otherwise
        // suggestions from typing can look like a settled search result.
        val beforeSubmit = if (typed == LuluMikaTextAccuracy.Verification.MATCH) {
            withContext(Dispatchers.Main) {
                val tree = LuluAccessibilityService.virtualUiSnapshot(id, packageName).optString("fingerprint")
                tree.takeIf { LuluAccessibilityService.readVirtualSearchFieldText(id, packageName) == text }
            }
        } else null
        if (beforeSubmit == null) return SearchDispatch(stopped("input_unverified",
            "未能从当前搜索框准确读回所输文字，未提交。").put("inputVerification", typed.wire))
        val submitted = withContext(Dispatchers.Main) {
            LuluAccessibilityService.submitVirtualSearch(id, packageName)
        }
        val accepted = submitted.optBoolean("accepted")
        val receipt = submitted.put("searchStage", if (accepted) "submitted" else "submit_rejected")
            .put("searchInputVerifiedBeforeSubmit", true)
            .put("searchSubmitted", accepted)
            .also { if (!accepted) it.put("status", "unverified") }
            .put("verification", if (accepted)
                "已读回关键词并提交搜索；请核对页面里的实际搜索结果。"
            else "已读回关键词，但设备未接受搜索提交。")
        return SearchDispatch(receipt, beforeSubmit)
    }

    private fun JSONObject.attachInputFocusReceipt(applied: JSONObject?): JSONObject {
        if (applied?.has("inputFocusTapCompleted") != true) return this
        return put("inputFocusTapCompleted", applied.optBoolean("inputFocusTapCompleted"))
            .put("inputFocusTapError", applied.optString("inputFocusTapError"))
            .put("inputFocusRecoveryAttempted", applied.optBoolean("inputFocusRecoveryAttempted"))
    }

    private fun JSONObject.attachSearchReceipt(applied: JSONObject?): JSONObject {
        if (applied?.has("searchStage") != true) return this
        return put("searchStage", applied.optString("searchStage"))
            .put("searchInputVerifiedBeforeSubmit", applied.optBoolean("searchInputVerifiedBeforeSubmit"))
            .put("searchSubmitted", applied.optBoolean("searchSubmitted"))
            .also { result ->
                applied.optString("errorCode").takeIf(String::isNotBlank)
                    ?.let { result.put("errorCode", it) }
                val searchVerification = applied.optString("verification")
                if (searchVerification.isNotBlank()) {
                    val observation = result.optString("verification")
                    result.put("verification", listOf(searchVerification, observation)
                        .filter(String::isNotBlank).distinct().joinToString(" "))
                }
            }
    }

    private fun JSONObject.attachNavigationReceipt(navigation: JSONObject?): JSONObject {
        if (navigation == null) return this
        val completed = navigation.optBoolean("navigationCompleted")
        val error = navigation.optString("navigationError")
        put("navigationAttempted", true)
        put("navigationCompleted", completed)
        val steps = navigation.optJSONArray("navigationSteps")
        put("navigationSteps", steps)
        val clickAccepted = (0 until (steps?.length() ?: 0)).any { index ->
            steps?.optJSONObject(index)?.optBoolean("accepted") == true
        }
        put("navigationClickAccepted", clickAccepted)
        if (!navigation.optBoolean("navigationLaunchedApp")) put("accepted", clickAccepted)
        if (!completed) {
            put("success", false)
            if (optString("status") in setOf("", "observed"))
                put("status", "unverified")
            put("navigationError", error)
            if (error == "new_user_input") put("errorCode", error)
        }
        return this
    }

    private fun JSONObject.attachMomentReceipt(applied: JSONObject?): JSONObject {
        if (applied == null) return this
        val attempted = applied.optBoolean("momentPublishAttempted")
        val clicked = applied.optBoolean("momentPublishClicked")
        return put("momentPublishAttempted", attempted)
            .put("momentPublishClicked", clicked)
            .put("momentInputReadbackVerified", applied.optBoolean("momentInputReadbackVerified"))
            .put("momentPublishError", applied.optString("errorCode"))
            .put("momentVerification", when {
                clicked -> "正文已读回，微信接受了一次发表点击；需在朋友圈查看结果。"
                attempted -> "已尝试一次发表，微信未确认接收点击。发表结果未知，请只读核对。"
                else -> "尚未点击发表，请根据当前编辑页和实际错误继续判断。"
            })
    }

    private fun JSONObject.attachCommentReceipt(applied: JSONObject?): JSONObject {
        if (applied == null) return this
        listOf("commentPublishAttempted", "commentPublishClicked", "commentDraftVerified",
            "commentAppeared", "commentDeliveryVerified").forEach { put(it, applied.optBoolean(it)) }
        put("commentError", applied.optString("errorCode"))
        put("commentVerification", when {
            applied.optBoolean("commentAppeared") -> "客户端已显示新增评论；服务器送达未知"
            applied.optBoolean("commentPublishAttempted") -> "已尝试单次发表，结果未证实；只能查看，不得重复发表"
            else -> "尚未点击发表；核对草稿、目标帖子和实际按钮后再决定"
        })
        if (!applied.optBoolean("commentAppeared")) {
            put("success", false)
            if (optString("status") !in setOf("failed", "denied", "waiting_user")) put("status", "unverified")
        }
        return put("taskCompleted", false)
    }

    suspend fun execute(
        context: Context, characterId: String, requestId: String, args: JSONObject,
    ): String = duringWork(characterId) { executeOwned(context, characterId, requestId, args) }

    private suspend fun executeOwned(
        context: Context, characterId: String, requestId: String, args: JSONObject,
    ): String {
        val action = args.optString("action").trim().lowercase()
        val navigationLabels = if (action == "launch" || action == LuluVirtualNavigation.ACTION)
            LuluVirtualNavigation.labels(args) else null
        val unreadAtStart = if (action == "search" || navigationLabels != null)
            CompanionOnlineStore.unreadChatSnapshot(characterId).messageIds.toSet() else emptySet()
        mutex.lock()
        val receipts = OwnedActionReceipts()
        var adoptedApp: JSONObject? = null
        try {
            check(!ownerHasPaused(context)) {
                "主人已停止后台手机操作；必须由主人在设置中重新允许"
            }
            check(LuluRolePhoneAuthorization.isGranted(context, characterId)) {
                "请先开启本角色的虚拟屏幕总授权"
            }
            val userLocked = context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
            check(!userLocked) { "手机处于锁屏状态，后台屏幕操作暂不可用" }
            // One real virtual phone session belongs to one character at a
            // time. Never let a second role inherit the first role's screen.
            check(ownerCharacterId.isBlank() || ownerCharacterId == characterId) {
                "后台屏幕由另一角色使用中；请先由主人停止该会话"
            }
            // A mistaken/missing model action is an argument rejection, NOT
            // a shell failure. Preserve the already-running virtual display
            // so the same role can choose a corrected action from this receipt.
            LuluBackgroundPhoneArgumentPolicy.invalidReason(action, args)?.let { reason ->
                return JSONObject().put("success", false).put("accepted", false)
                    .put("status", "failed")
                    .put("errorCode", if (LuluBackgroundPhoneArgumentPolicy.isSupportedAction(action))
                        "invalid_action_arguments" else "unsupported_action")
                    .put("action", action).put("taskCompleted", false)
                    .put("mode", "independent_virtual_display")
                    .put("sessionPreserved", connected())
                    .put("verification", reason).toString()
            }
            if (action == "stop") {
                if (!connected()) return JSONObject().put("success", true)
                    .put("status", "stopped").put("verification", "后台屏幕本来没有运行").toString()
                return try {
                    command(JSONObject().put("action", "stop"))
                        .put("mode", "independent_virtual_display")
                        .put("verification", "后台屏幕已收到关闭请求").toString()
                } finally { forgetTransport() }
            }
            if (action == "send_chat_message" &&
                !LuluVirtualChatSendPolicy.isEnabled(context, characterId)) {
                return JSONObject().put("success", false).put("accepted", false)
                    .put("status", "denied").put("errorCode", "chat_send_owner_permission_missing")
                    .put("taskCompleted", false).put("mode", "independent_virtual_display")
                    .put("verification", "请先开启本角色的虚拟屏幕总授权，尚未发送任何消息")
                    .toString()
            }
            receipts.failureStage = "app_resolution"
            val requestedPackage = if (action == "launch" &&
                args.optString("packageName").isBlank()) {
                // A model can choose the actual installed app by its visible name;
                // the local resolver never guesses a package or bypasses exclusions.
                // A bad or ambiguous app label is a recoverable decision
                // error. It must not destroy a previously opened virtual App.
                val lookup = runCatching {
                    LuluInstalledAppResolver.resolve(context, args.optString("appName"))
                }
                if (lookup.isFailure) return JSONObject()
                    .put("success", false).put("accepted", false)
                    .put("status", "failed").put("errorCode", "app_resolution_failed")
                    .put("mode", "independent_virtual_display")
                    .put("sessionPreserved", connected()).put("taskCompleted", false)
                    .put("verification", lookup.exceptionOrNull()?.message.orEmpty()
                        .take(220)).toString()
                lookup.getOrThrow()
            } else args.optString("packageName").trim()
            if (action == "launch") {
                if (requestedPackage.isEmpty()) return JSONObject()
                    .put("success", false).put("accepted", false)
                    .put("status", "failed").put("errorCode", "app_not_found")
                    .put("mode", "independent_virtual_display")
                    .put("sessionPreserved", connected()).put("taskCompleted", false)
                    .put("verification", "未找到唯一匹配的已安装应用；请核对应用名称或准确包名").toString()
                if (LuluScreenAwareness.isExcluded(context, requestedPackage))
                    return JSONObject().put("success", false).put("accepted", false)
                        .put("status", "denied").put("errorCode", "excluded_app")
                        .put("mode", "independent_virtual_display")
                        .put("sessionPreserved", connected()).put("taskCompleted", false)
                        .put("verification", "该应用处于主人的隐私排除名单，未启动").toString()
                val main = withContext(Dispatchers.Main) { LuluAccessibilityService.observe() }
                if (main.packageName == requestedPackage)
                    return JSONObject().put("success", false).put("accepted", false)
                        .put("status", "unverified").put("errorCode", "owner_using_app")
                        .put("mode", "independent_virtual_display")
                        .put("sessionPreserved", connected()).put("taskCompleted", false)
                        .put("verification", "主人正在主屏使用这个App，未尝试移动到后台屏幕").toString()
            }
            receipts.failureStage = "display_start"
            val id = ensureStarted(context)
            receipts.failureStage = "window_and_action"
            if (ownerCharacterId.isBlank()) ownerCharacterId = characterId
            appContext = context.applicationContext
            LuluBackgroundPhoneNotice.show(context)
            if (action == "start") return JSONObject().put("success", true)
                .put("status", "succeeded").put("mode", "independent_virtual_display")
                .put("displayId", id).put("observationAvailable", false)
                .put("ownFocusVerifiedByDisplayFlags", true)
                .put("verification", "shell 已创建独立焦点屏幕；尚未观察到 App 或桌面画面").toString()

            if (action == "adopt_app") {
                val current = withContext(Dispatchers.Main) {
                    LuluAccessibilityService.virtualWindowStatus(id, requestedPackage)
                }
                if (!LuluVirtualWindowPolicy.canAdoptExistingWindow(id, requestedPackage,
                        current.optString("actualPackage"), current.optString("kind"),
                        LuluScreenAwareness.isExcluded(context, requestedPackage))) return JSONObject()
                    .put("success", false).put("accepted", false).put("status", "unverified")
                    .put("mode", "independent_virtual_display").put("displayId", id)
                    .put("errorCode", "adopt_window_unverified").put("taskCompleted", false)
                    .put("verification", "新App不在这块独立屏的真实前台，或在隐私排除名单；未接续").toString()
                humanOnlyPage(id, requestedPackage)?.let {
                    return humanOnlyReceipt(id, requestedPackage, it)
                }
                adoptedApp = command(JSONObject().put("action", "adopt_app").put("packageName", requestedPackage))
                if (adoptedApp?.optBoolean("accepted") != true) return checkNotNull(adoptedApp)
                    .put("mode", "independent_virtual_display").toString()
                activeApp = requestedPackage
                lastObservedUi = null
            }
            if (action != "launch" && activeApp.isBlank()) return JSONObject()
                .put("success", false).put("accepted", false)
                .put("status", "unverified").put("errorCode", "target_app_not_open")
                .put("mode", "independent_virtual_display").put("displayId", id)
                .put("sessionPreserved", true).put("taskCompleted", false)
                .put("observationAvailable", false)
                .put("verification", "独立后台屏已创建，但尚未打开已授权应用；由角色选择launch指定准确应用后再观察或操作").toString()
            if (action != "launch" && LuluScreenAwareness.isExcluded(context, activeApp)) {
                error("原授权应用现已列入隐私排除名单")
            }
            if (action != "launch" && requestedPackage.isNotBlank() && requestedPackage != activeApp) {
                error("当前后台屏幕目标与指定包名不一致，拒绝执行")
            }
            if (action != "launch") {
                val top = windowGate(id, activeApp)
                if (top.optString("kind") != "target" &&
                    !(action == "observe" && top.optString("kind") == "unknown")) {
                    lastObservedUi = null
                    return pausedWindowReceipt(id, top)
                }
                val before = withContext(Dispatchers.Main) {
                    LuluAccessibilityService.observeDisplay(id)
                }
                if (!before.connected && action != "observe") {
                    lastObservedUi = null
                    return JSONObject().put("success", false).put("status", "unverified")
                        .put("mode", "independent_virtual_display").put("displayId", id)
                        .put("taskCompleted", false).put("targetWindowVerified", false)
                        .put("verification", "后台暂时没有可核实的应用窗口；未执行输入或点击，可以先observe重新观察")
                        .toString()
                }
                if (before.connected) {
                    if (before.packageName != activeApp) {
                        lastObservedUi = null
                        return humanOnlyReceipt(id, before.packageName, "window_changed")
                    }
                    val human = humanOnlyPage(id, activeApp)
                    if (human != null) return humanOnlyReceipt(id, activeApp, human)
                }
            }
            // Forward only the new link produced by the chosen Copy Link control.
            val genericShare = args.optBoolean("sendLinkToChat")
            val wantsLink = genericShare || args.optBoolean("sendProductLinkToChat")
            val merchantOnly = !genericShare
            var beforeProductCopyTimestamp = 0L
            var productCopyStartedAt = 0L
            var shellMerchantCopyPrepared = false
            if (wantsLink && action !in setOf("tap_element", "tap")) {
                return JSONObject().put("success", false).put("status", "denied")
                    .put("accepted", false).put("linkSent", false).put("productLinkSent", false)
                    .put("errorCode", "product_link_requires_verified_control")
                    .put("sessionPreserved", true).put("taskCompleted", false)
                    .put("verification", "分享链接需要刚观察到的真实复制控件，或同一后台画面核实的复制按钮坐标；两种路径都必须重新验证本次复制的链接，不允许直接读取任意剪贴板")
                    .toString()
            }
            if (LuluBackgroundPhoneArgumentPolicy.needsSnapshot(action) ||
                (action == "type" && args.has("inputX") && args.has("inputY"))) {
                val snapshot = lastObservedUi
                val requestedSnapshot = args.optString("screenSnapshotId")
                val requestedElement = args.optString("elementRef")
                val owned = snapshot != null && snapshot.characterId == characterId &&
                    snapshot.displayId == id && snapshot.packageName == activeApp
                // Semantic refs demand a real tree; visual taps, visual typing
                // and screenshot-guided chat instead demand the SAME actually
                // delivered picture and verified window. A zero-node tree
                // must never make visible search icons untouchable.
                val pictureAction = when (action) {
                    "tap", "long_press" -> true
                    "type" -> args.has("inputX") && args.has("inputY")
                    "send_chat_message" -> args.has("composerX") && args.has("composerY")
                    else -> false
                }
                val semanticValid = LuluVirtualUiPolicy.sameSnapshot(
                    requestedSnapshot, snapshot?.token.orEmpty(),
                    snapshot?.fingerprint.orEmpty(), snapshot?.fingerprint.orEmpty())
                val pictureValid = pictureAction && LuluVirtualUiPolicy.sameObservedPicture(
                    requestedSnapshot, snapshot?.token.orEmpty(),
                    snapshot?.visualFrameObserved == true,
                    snapshot?.windowIdentity.orEmpty())
                if (!owned || (LuluBackgroundPhoneArgumentPolicy.needsElement(action) && requestedElement.isBlank()) ||
                    !(semanticValid || pictureValid)) {
                    return JSONObject().put("success", false).put("accepted", false)
                        .put("status", "unverified").put("taskCompleted", false)
                        .put("mode", "independent_virtual_display").put("displayId", id)
                        .put("errorCode", if (action == "send_chat_message") "chat_snapshot_expired"
                            else "virtual_snapshot_expired")
                        .put("verification", "控件编号已经过期或不属于此角色的后台App；请先observe重新读取，拒绝按旧位置点击")
                        .toString()
                }
                if (((action == "type" && args.has("inputX")) || action in setOf("tap", "long_press") ||
                        (action == "send_chat_message" && args.has("composerX"))) &&
                    snapshot?.visualFrameObserved != true) {
                    return JSONObject().put("success", false).put("accepted", false)
                        .put("status", "unverified").put("taskCompleted", false)
                        .put("mode", "independent_virtual_display").put("displayId", id)
                        .put("errorCode", "visual_input_picture_not_observed")
                        .put("verification",
                            "这个快照只包含控件状态，没有真正交给本角色的新后台画面；不能凭它猜文字输入位置，请先重新观察")
                        .toString()
                }
                if (wantsLink) {
                    if (action == "tap") {
                        if (!LuluShoppingLinkShare.eligibleVisualCopy(
                                activeApp, args.optString("target"),
                                LuluRolePhoneAuthorization.isGranted(context, characterId), merchantOnly)) {
                            return JSONObject().put("success", false).put("accepted", false)
                                .put("status", "unverified")
                                .put("mode", "independent_virtual_display").put("displayId", id)
                                .put("packageName", activeApp).put("targetWindowVerified", true)
                                .put("productLinkSent", false).put("taskCompleted", false)
                                .put("errorCode", "product_copy_visual_target_unverified")
                                .put("verification", "本次画面没有明确选择复制操作；未读取剪贴板或发送链接，重新看图后可选择真实复制按钮")
                                .toString()
                        }
                    } else {
                    val live = withContext(Dispatchers.Main) {
                        LuluAccessibilityService.virtualUiSnapshot(id, activeApp)
                    }
                    val elements = live.optJSONArray("elements")
                    val chosen = (0 until (elements?.length() ?: 0)).mapNotNull { index ->
                        elements?.optJSONObject(index)
                    }.firstOrNull { it.optString("ref") == requestedElement }
                    // Product sharing uses the already-granted role-phone
                    // permission; this is not an order or purchase operation.
                    // Distinguish actual revocation from stale/unnamed UI.
                    val approved = LuluRolePhoneAuthorization.isGranted(context, characterId)
                    val copyNameVerified = LuluShoppingLinkShare.isCopyLabel(
                        chosen?.optString("name").orEmpty())
                    val copyClickable = chosen?.optBoolean("clickable") == true
                    val snapshotCurrent = live.optString("fingerprint") == snapshot?.fingerprint
                    val eligible = LuluShoppingLinkShare.eligibleCopy(
                        activeApp, chosen?.optString("name").orEmpty(),
                        copyClickable, approved, merchantOnly,
                    )
                    if (!eligible || !snapshotCurrent) {
                        if (!snapshotCurrent) lastObservedUi = null
                        val code = when {
                            !approved -> "product_copy_authorization_missing"
                            merchantOnly && LuluShoppingLinkShare.merchantName(activeApp) == null ->
                                "product_copy_merchant_unverified"
                            !snapshotCurrent -> "product_copy_snapshot_changed"
                            chosen == null -> "product_copy_control_missing"
                            !copyClickable -> "product_copy_control_not_clickable"
                            else -> "product_copy_label_unrecognized"
                        }
                        return JSONObject().put("success", false)
                            .put("status", if (!approved) "denied" else "unverified")
                            .put("accepted", false).put("linkSent", false).put("productLinkSent", false)
                            .put("errorCode", code)
                            .put("mode", "independent_virtual_display").put("displayId", id)
                            .put("packageName", activeApp).put("targetWindowVerified", true)
                            .put("copyControlObserved", chosen != null)
                            .put("copyControlClickable", copyClickable)
                            .put("copyControlNameVerified", copyNameVerified)
                            .put("copySnapshotCurrent", snapshotCurrent)
                            .put("sessionPreserved", true).put("taskCompleted", false)
                            .put("verification", if (!approved)
                                "后台手机授权已失效；未执行复制或读取剪贴板"
                            else "复制动作尚未执行（$code）；请重新观察当前分享页并选择真实复制控件，本次未分享链接")
                            .toString()
                    }
                    }
                    beforeProductCopyTimestamp = withContext(Dispatchers.Main) {
                        LuluShoppingLinkShare.clipboardTimestamp(context)
                    }
                    productCopyStartedAt = System.currentTimeMillis()
                }
            }
            if (action == "submit") {
                val safeSearch = withContext(Dispatchers.Main) {
                    LuluAccessibilityService.hasFocusedVirtualSearchEditor(id, activeApp)
                }
                if (!safeSearch) return JSONObject()
                    .put("success", false).put("accepted", false)
                    .put("status", "unverified").put("errorCode", "search_focus_unverified")
                    .put("mode", "independent_virtual_display").put("displayId", id)
                    .put("sessionPreserved", true).put("taskCompleted", false)
                    .put("verification", "没有可确认的已聚焦搜索框；拒绝提交以避免误发消息。可由角色重新观察或聚焦安全搜索框").toString()
            }
            if (action in setOf("tap", "long_press")) {
                val x = args.getInt("x")
                val y = args.getInt("y")
                val sensitive = withContext(Dispatchers.Main) {
                    LuluAccessibilityService.isSensitiveVirtualTap(
                        id, activeApp, x, y, ordinaryInteractionAllowed = true)
                }
                if (sensitive) return JSONObject()
                    .put("success", false).put("accepted", false)
                    .put("status", "denied").put("errorCode", "sensitive_target")
                    .put("mode", "independent_virtual_display").put("displayId", id)
                    .put("sessionPreserved", true).put("taskCompleted", false)
                    .put("verification", "当前点击目标涉及付款、删除或凭据保护；没有点击，原后台会话保留").toString()
            }
            // Snapshot BEFORE the action to measure actual UI effect. An
            // accepted gesture or a new frame is not itself a changed page.
            var beforeUiFingerprint = if (action in setOf("launch", "search")) "" else
                withContext(Dispatchers.Main) {
                    LuluAccessibilityService.virtualUiSnapshot(id, activeApp)
                        .optString("fingerprint")
                }
            val beforeScrollFrame = lastObservedUi?.takeIf {
                action == "swipe" && it.characterId == characterId && it.displayId == id &&
                    it.packageName == activeApp && it.visualFrameObserved && it.windowIdentity.isNotBlank()
            }
            val beforeScrollText = if (action == "swipe")
                withContext(Dispatchers.Main) {
                    LuluAccessibilityService.observeDisplay(id).visibleText.take(5_000)
                } else ""
            if (action !in setOf("observe", LuluWeChatMomentsPolicy.PUBLISH)) openedMomentsComposer = false
            fun stillAuthorized(): Boolean =
                !ownerHasPaused(context) &&
                    LuluRolePhoneAuthorization.isGranted(context, characterId) &&
                    context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != true &&
                    !LuluScreenAwareness.isExcluded(context, activeApp)
            suspend fun captureChatTitleFrame(): android.graphics.Bitmap? {
                if (!stillAuthorized() || windowGate(id, activeApp).optString("kind") != "target")
                    return null
                val shot = try {
                    command(JSONObject().put("action", "observe").put("fullResolution", true))
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return null
                }
                val pixels = shot.optString("imageDataUrl")
                if (!shot.optBoolean("success") || shot.optInt("displayId") != id ||
                    shot.optBoolean("actionFrameUnverified") ||
                    shot.optString("targetPackage") != activeApp || !stillAuthorized() ||
                    !LuluVirtualFrameSafety.canSendToRole(shot.optBoolean("frameValidated"),
                        shot.optLong("frameTimestampNanos"), pixels, shot.optBoolean("frameBlack")) ||
                    windowGate(id, activeApp).optString("kind") != "target") return null
                return withContext(Dispatchers.IO) {
                    val bytes = java.util.Base64.getDecoder().decode(
                        pixels.removePrefix("data:image/jpeg;base64,"))
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
            val request = JSONObject().put("action", action)
            if (action == "launch") {
                // Pass Android's actual exported launcher component, not just a
                // package-filtered implicit MAIN intent. Some apps have multiple
                // launcher activities and 'am start -p pkg' is ambiguous.
                val launch = context.packageManager.getLaunchIntentForPackage(requestedPackage)
                val component = launch?.component
                if (component == null || component.packageName != requestedPackage)
                    return JSONObject().put("success", false).put("accepted", false)
                        .put("status", "failed").put("errorCode", "launcher_not_verified")
                        .put("mode", "independent_virtual_display").put("displayId", id)
                        .put("sessionPreserved", true).put("taskCompleted", false)
                        .put("verification", "未找到与所选应用包名完全匹配的可启动组件，未执行打开").toString()
                request.put("packageName", requestedPackage)
                    .put("launchComponent", component.flattenToString())
            }
            if (action == "swipe") {
                for (name in listOf("startX", "startY", "endX", "endY")) {
                    request.put(name, args.getInt(name))
                }
                request.put("durationMs", args.optInt("durationMs", 400))
            }
            val visualType = action == "type" && args.has("inputX") && args.has("inputY")
            val visualPress = action in setOf("tap", "long_press")
            var beforeTouchPixels = ""
            if (visualType || visualPress) {
                val visualX = args.getInt(if (visualType) "inputX" else "x")
                val visualY = args.getInt(if (visualType) "inputY" else "y")
                val prior = checkNotNull(lastObservedUi)
                val shot = command(JSONObject().put("action", "observe"))
                val freshPixels = shot.optString("imageDataUrl")
                val windowIdentity = withContext(Dispatchers.Main) {
                    LuluAccessibilityService.virtualChatWindowIdentity(id, activeApp)
                }
                val sameTarget = prior.windowIdentity.isNotBlank() && prior.windowIdentity == windowIdentity &&
                    shot.optInt("displayId") == id && shot.optString("targetPackage") == activeApp &&
                    LuluVirtualFrameSafety.canSendToRole(shot.optBoolean("frameValidated"),
                        shot.optLong("frameTimestampNanos"), freshPixels, shot.optBoolean("frameBlack")) &&
                    withContext(Dispatchers.Default) {
                        LuluVisualTargetFrame.unchanged(prior.visualImage, freshPixels,
                            visualX, visualY)
                    }
                val liveWindow = windowGate(id, activeApp)
                if (liveWindow.optString("kind") != "target") return pausedWindowReceipt(id, liveWindow)
                humanOnlyPage(id, activeApp)?.let { return humanOnlyReceipt(id, activeApp, it) }
                if (!sameTarget || !stillAuthorized()) {
                    val receipt = JSONObject().put("success", false).put("accepted", false)
                        .put("status", "unverified").put("sessionPreserved", true)
                        .put("mode", "independent_virtual_display").put("displayId", id)
                        .put("packageName", activeApp).put("errorCode", "visual_target_changed")
                        .put("taskCompleted", false).put("verification", "目标位置或窗口已经变化，未操作；按当前画面重新选择")
                    if (shot.optInt("displayId") == id && shot.optString("targetPackage") == activeApp &&
                        LuluVirtualFrameSafety.canSendToRole(shot.optBoolean("frameValidated"),
                            shot.optLong("frameTimestampNanos"), freshPixels, shot.optBoolean("frameBlack")) &&
                        stillAuthorized()) {
                        LuluScreenFrameHandoff.put(characterId, requestId, freshPixels)
                        receipt.put("targetWindowVerified", true)
                            .put("imageForwardedToRole", true).put("frameValidated", true)
                            .put("frameTimestampNanos", shot.optLong("frameTimestampNanos"))
                        attachUiEvidence(receipt, characterId, id, activeApp, freshPixels)
                    } else lastObservedUi = null
                    return receipt.toString()
                }
                if (visualPress) beforeTouchPixels = freshPixels
            }
            // Arm the one-shot merchant clipboard boundary only AFTER the
            // visual target/snapshot has been independently revalidated. This
            // covers both Mika-style image clicks and semantic element taps.
            if (wantsLink && stillAuthorized()) {
                val marker = try {
                    command(JSONObject().put("action", "prepare_product_copy")
                        .put("packageName", activeApp).put("merchantOnly", merchantOnly))
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) { null }
                shellMerchantCopyPrepared = marker?.optBoolean("accepted") == true
            }
            receipts.failureStage = "command_" + action
            val applied = when (action) {
                "adopt_app" -> checkNotNull(adoptedApp)
                "observe", LuluVirtualNavigation.ACTION -> null
                "tap", "long_press" -> {
                    lastObservedUi = null
                    val holdMs = if (action == "long_press")
                        args.optInt("durationMs", android.view.ViewConfiguration.getLongPressTimeout() + 100)
                    else null
                    pressVisualTarget(id, activeApp, args.getInt("x"), args.getInt("y"), holdMs, ::stillAuthorized)
                        .put("interactionTarget", args.getString("target"))
                        .put("outwardEffectUnverified", true)
                }
                "tap_element" -> {
                    val prior = lastObservedUi
                        ?: error("后台控件快照不存在")
                    lastObservedUi = null
                    // The same pre-action frame barrier used by focus_search
                    // keeps an old image from claiming the click succeeded.
                    val prepared = command(JSONObject().put("action", "prepare_type"))
                    check(prepared.optBoolean("accepted")) { "无法核实本次点击前的后台画面" }
                    val clicked = withContext(Dispatchers.Main) {
                        LuluAccessibilityService.tapVirtualUiElement(
                            id, activeApp, prior.fingerprint, args.optString("elementRef"),
                            shoppingAllowed = LuluShoppingAssistPolicy.isEnabled(context, characterId),
                            ordinaryInteractionAllowed = true, longPress = args.optBoolean("longPress"))
                    }
                    JSONObject().put("accepted", clicked).put("dispatchAttempted", true)
                        .put("outwardEffectUnverified", true).put("displayId", id)
                }
                "swipe" -> {
                    // Follow Mika ScreenAccessibility: dispatchGesture with an
                    // explicit displayId. Shell "input -d ... swipe" is not a
                    // reliable route on every Android build. Never run both
                    // dispatchers or replay an uncertain gesture.
                    val prepared = command(JSONObject().put("action", "prepare_type"))
                    check(prepared.optBoolean("accepted")) {
                        "无法建立独立屏滑动前的图像时间戳屏障"
                    }
                    withContext(Dispatchers.Main) {
                        LuluAccessibilityService.swipeVirtualDisplay(
                            id, activeApp, args.getInt("startX"), args.getInt("startY"),
                            args.getInt("endX"), args.getInt("endY"),
                            args.optInt("durationMs", 400),
                        )
                    }
                }
                "back" -> {
                    // Use a verified in-app arrow, or one display- and UID-bound
                    // Android BACK when no such arrow is exposed. A rejected
                    // toolbar dispatch can be ambiguous, so never send both.
                    val prepared = command(JSONObject().put("action", "prepare_type"))
                    check(prepared.optBoolean("accepted")) {
                        "无法建立虚拟屏返回前的图像时间戳屏障"
                    }
                    val toolbar = withContext(Dispatchers.Main) {
                        LuluAccessibilityService.tapVirtualAppBackControl(
                            id, activeApp, beforeUiFingerprint, ::stillAuthorized)
                    }
                    if (toolbar.optBoolean("attempted")) toolbar else {
                        val gate = windowGate(id, activeApp)
                        if (gate.optString("kind") != "target") return pausedWindowReceipt(id, gate)
                        if (!stillAuthorized()) return JSONObject()
                            .put("success", false).put("accepted", false).put("status", "denied")
                            .put("mode", "independent_virtual_display").put("displayId", id)
                            .put("errorCode", "virtual_back_authorization_changed").toString()
                        command(JSONObject().put("action", "back").put("packageName", activeApp))
                    }
                }
                "submit" -> {
                    val prepared = command(JSONObject().put("action", "prepare_type"))
                    check(prepared.optBoolean("accepted")) {
                        "无法建立虚拟屏搜索提交前的图像时间戳屏障"
                    }
                    withContext(Dispatchers.Main) {
                        LuluAccessibilityService.submitVirtualSearch(id, activeApp)
                    }
                }
                "focus_search" -> {
                    val prepared = command(JSONObject().put("action", "prepare_type"))
                    check(prepared.optBoolean("accepted")) { "无法建立后台聚焦画面屏障" }
                    val accepted = withContext(Dispatchers.Main) {
                        LuluAccessibilityService.focusUniqueVirtualSearchEditor(id, activeApp)
                    }
                    JSONObject().put("accepted", accepted).put("displayId", id)
                }
                "type" -> {
                    val value = args.getString("text")
                    require(value.isNotEmpty() && value.length <= LuluVirtualTextInputPolicy.MAX_TEXT_CHARS) {
                        "每次输入1到${LuluVirtualTextInputPolicy.MAX_TEXT_CHARS}个字符"
                    }
                    // One generic phone text operation across apps.
                    // Standard focused editors use ACTION_SET_TEXT and exact
                    // field readback. Zero-tree apps use a model-chosen point
                    // in the currently observed frame, then a display- and
                    // UID-targeted paste, without auto-submitting.
                    if (visualType) {
                        lastObservedUi = null
                        typeVisualVirtualField(id, activeApp, value,
                            args.getInt("inputX"), args.getInt("inputY"),
                            ::stillAuthorized)
                    } else {
                        check(command(JSONObject().put("action", "prepare_type")).optBoolean("accepted")) {
                            "无法建立后台屏文字输入的画面核验屏障"
                        }
                        withContext(Dispatchers.Main) {
                            LuluAccessibilityService.typeFocusedVirtualEditor(id, activeApp, value)
                        }
                    }
                }
                LuluVirtualCommentPolicy.ACTION -> {
                    val fingerprint = checkNotNull(lastObservedUi).fingerprint
                    lastObservedUi = null
                    val prepared = command(JSONObject().put("action", "prepare_type")).optBoolean("accepted")
                    receipts.commentReceipt = JSONObject().put("commentPublishAttempted", false)
                        .put("errorCode", "comment_frame_barrier_unverified")
                    if (prepared) {
                        receipts.commentReceipt = withContext(Dispatchers.Main) {
                            LuluAccessibilityService.publishVirtualComment(id, activeApp, fingerprint,
                                args.getString("editorRef"), args.getString("elementRef"),
                                args.getString("text"), args.getString("contextText").trim(), ::stillAuthorized,
                                onDispatch = {
                                    receipts.commentReceipt = JSONObject().put("commentPublishAttempted", true)
                                        .put("commentDraftVerified", true)
                                        .put("errorCode", "comment_dispatch_result_unknown")
                                })
                        }
                    }
                    checkNotNull(receipts.commentReceipt)
                }
                LuluWeChatMomentsPolicy.OPEN_COMPOSER -> {
                    val fingerprint = checkNotNull(lastObservedUi).fingerprint
                    lastObservedUi = null
                    val prepared = activeApp == LuluVirtualChatSendPolicy.WECHAT &&
                        command(JSONObject().put("action", "prepare_type")).optBoolean("accepted")
                    openedMomentsComposer = prepared && withContext(Dispatchers.Main) {
                        LuluAccessibilityService.openVirtualWeChatMomentsComposer(
                            id, fingerprint, args.getString("elementRef"), ::stillAuthorized)
                    }
                    JSONObject().put("accepted", openedMomentsComposer)
                        .put("actionMethod", "wechat_moments_camera_long_press")
                        .put("errorCode", if (openedMomentsComposer) "" else "moments_camera_unverified")
                }
                LuluWeChatMomentsPolicy.PUBLISH -> {
                    val fingerprint = checkNotNull(lastObservedUi).fingerprint
                    lastObservedUi = null
                    val prepared = activeApp == LuluVirtualChatSendPolicy.WECHAT &&
                        openedMomentsComposer &&
                        command(JSONObject().put("action", "prepare_type")).optBoolean("accepted")
                    val result = if (prepared) withContext(Dispatchers.Main) {
                        LuluAccessibilityService.publishVirtualWeChatMoment(
                            id, fingerprint, args.getString("text"), ::stillAuthorized)
                    } else JSONObject().put("accepted", false)
                        .put("errorCode", "moments_open_composer_first")
                    if (result.optBoolean("momentPublishAttempted")) openedMomentsComposer = false
                    receipts.momentReceipt = result
                    result
                }
                "send_chat_message" -> {
                    val recipient = args.getString("recipient")
                    val message = args.getString("text")
                    if (!LuluVirtualChatSendPolicy.supports(activeApp))
                        return JSONObject().put("success", false).put("accepted", false)
                            .put("status", "unverified").put("errorCode", "chat_app_unsupported")
                            .put("mode", "independent_virtual_display").put("displayId", id)
                            .put("taskCompleted", false)
                            .put("verification", "当前后台应用没有接入会话发送；微信和抖音可用")
                            .toString()
                    // The model chose the whole message once. WeChat sometimes
                    // starts in voice/press-to-talk mode, so prepare its editor
                    // locally instead of paying for another vision-model turn.
                    // This switch is NOT a send attempt; it clicks once and is
                    // restricted to the verified recipient and current display.
                    var fingerprint = checkNotNull(lastObservedUi).fingerprint
                    val alreadyText = withContext(Dispatchers.Main) {
                        LuluAccessibilityService.virtualChatTextInputVisible(id, activeApp)
                    }
                    // A chat editor may be readable while the custom WeChat
                    // title/Send controls remain invisible to accessibility.
                    // Honor the role's screenshot-chosen composer whenever
                    // explicitly provided; the visual route independently
                    // verifies exact recipient, draft and ONE Send control.
                    if (activeApp == LuluVirtualChatSendPolicy.WECHAT &&
                        args.has("composerX") && args.has("composerY")) {
                        visualWeChatSend(
                            id, activeApp, recipient, message,
                            args.getInt("composerX"), args.getInt("composerY"),
                            ::stillAuthorized, ::captureChatTitleFrame,
                        )
                    } else {
                    var toggleClicked = false
                    var switchFailure: JSONObject? = null
                    val editorReady = if (alreadyText || activeApp != LuluVirtualChatSendPolicy.WECHAT) true else {
                        val switched = withContext(Dispatchers.Main) {
                            LuluAccessibilityService.switchVirtualChatToText(
                                id, activeApp, recipient, fingerprint,
                                frameSource = ::captureChatTitleFrame, stillAuthorized = ::stillAuthorized)
                        }
                        toggleClicked = switched.optBoolean("inputModeSwitchClicked")
                        switchFailure = switched
                        if (switched.optBoolean("inputModeTextVerified")) true else if (
                            // A missing voice bar can mean the app changed
                            // modes during the scan. Only recheck an actual
                            // editor, never blindly click another keyboard slot.
                            LuluVirtualChatSendPolicy.mayRecheckTextEditorAfterVoiceProbe(
                                switched.optBoolean("accepted"),
                                switched.optString("errorCode"),
                            )
                        ) {
                            var ready = false
                            for (attempt in 0 until 8) {
                                ready = withContext(Dispatchers.Main) {
                                    LuluAccessibilityService.virtualChatTextInputVisible(id, activeApp)
                                }
                                if (ready || !stillAuthorized()) break
                                if (attempt < 7) kotlinx.coroutines.delay(180)
                            }
                            ready
                        } else false
                    }
                    if (!editorReady) {
                        val failure = switchFailure ?: JSONObject()
                        if (toggleClicked) failure.put("errorCode", "chat_text_mode_unverified")
                            .put("verification", "已尝试切换键盘，但还没有出现可写的微信文字框")
                        failure.put("accepted", false).put("messageSendClicked", false)
                            .put("inputModeSwitchClicked", toggleClicked)
                    } else {
                        // Even when the toggle was not accepted, a late
                        // editor can appear after the failed voice-bar probe.
                        // Bind any text write to the NOW-current screen and
                        // reverify recipient before sending, never an old ref.
                        if (activeApp == LuluVirtualChatSendPolicy.WECHAT) fingerprint =
                            withContext(Dispatchers.Main) {
                                LuluAccessibilityService.virtualUiSnapshot(
                                    id, activeApp).optString("fingerprint")
                            }
                        if (fingerprint.isBlank()) {
                            JSONObject().put("accepted", false)
                                .put("messageSendClicked", false)
                                .put("errorCode", "chat_text_snapshot_unavailable")
                                .put("verification", "文字输入框可见，但缺少当前微信屏快照；未发送")
                        } else {
                            withContext(Dispatchers.Main) {
                                LuluAccessibilityService.sendVirtualChatMessage(
                                    id, activeApp, recipient, message, fingerprint,
                                    frameSource = ::captureChatTitleFrame,
                                    beforeTextInput = {
                                        command(JSONObject().put("action", "prepare_type"))
                                            .optBoolean("accepted")
                                    },
                                    stillAuthorized = ::stillAuthorized,
                                )
                            }.put("inputModeSwitchClicked", toggleClicked)
                        }
                    }
                    }
                }
                "search" -> search(context, id, activeApp, characterId, unreadAtStart, args.getString("text"))
                    .let { dispatch ->
                        beforeUiFingerprint = dispatch.beforeSubmitTree.orEmpty()
                        dispatch.receipt
                    }
                else -> command(request)
            }
            if (wantsLink) {
                // This is a terminal receipt for the copy/share step, not
                // proof that the goods were added to cart or purchased.
                val acceptedCopyClick = applied?.optBoolean("accepted") == true
                // Privileged shell can read copies that Android hides from
                // Lulu's background app UID. Only a merchant-verified fresh
                // copy may cross from the owned display into chat.
                val shellCopy = if (acceptedCopyClick && shellMerchantCopyPrepared &&
                    stillAuthorized()) try {
                    command(JSONObject().put("action", "read_product_copy")
                        .put("packageName", activeApp))
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    JSONObject().put("errorCode", "merchant_shell_read_failed")
                } else null
                val shellLink = shellCopy?.optString("link").orEmpty().takeIf {
                    shellCopy?.optBoolean("accepted") == true &&
                        LuluSharedWebLinks.uri(it) != null
                }
                // A source mismatch or a clipboard race cannot be overridden
                // by the app-side reader, which can't see the writer package.
                val link = if (!acceptedCopyClick || !stillAuthorized() ||
                    shellCopy?.optString("errorCode") in setOf("merchant_copy_source_mismatch", "link_copy_event_changed")) null
                else shellLink ?: LuluShoppingLinkShare.awaitFreshLink(
                    context, activeApp, beforeProductCopyTimestamp, productCopyStartedAt, merchantOnly)
                val merchant = link?.let { LuluShoppingLinkShare.merchantNameForLink(activeApp, it) }
                var deliveryError = ""
                val sent = if (link != null && stillAuthorized()) {
                    try {
                        com.jiacimu.lulu.data.CompanionActionRuntime.sendCopiedWebLink(
                            characterId, link, args.optString("caption").take(240), merchant)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        deliveryError = error.message.orEmpty().take(150)
                        null
                    }
                } else null
                return JSONObject().put("success", sent?.success == true)
                    .put("status", if (sent?.success == true) "delivered" else "unverified")
                    .put("accepted", applied?.optBoolean("accepted") == true)
                    .put("mode", "independent_virtual_display").put("displayId", id)
                    .put("packageName", activeApp).put("taskCompleted", false)
                    .put("linkSent", sent?.success == true)
                    .put("productLinkSent", sent?.success == true && merchant != null)
                    .put("errorCode", when {
                        sent?.success == true -> ""
                        !acceptedCopyClick -> "merchant_copy_click_rejected"
                        deliveryError.isNotBlank() -> "merchant_link_delivery_failed"
                        !stillAuthorized() -> "merchant_copy_authorization_changed"
                        link == null -> shellCopy?.optString("errorCode")
                            ?.takeIf { it.isNotBlank() } ?: "merchant_clipboard_unavailable"
                        else -> "merchant_link_delivery_unverified"
                    })
                    .put("linkUrl", if (sent?.success == true) link else "")
                    .put("productLinkUrl", if (sent?.success == true && merchant != null) link else "")
                    .put("conversationId", sent?.conversationId.orEmpty())
                    .put("evidenceId", sent?.evidenceId.orEmpty())
                    .put("verification", if (sent?.success == true)
                        "已核实本次复制的链接并以卡片发到主人私聊"
                    else if (applied?.optBoolean("accepted") != true)
                        "设备没有接受复制链接动作；未发送链接"
                    else if (deliveryError.isNotBlank())
                        "链接发送失败：$deliveryError；未声称送达"
                    else if (!stillAuthorized())
                        "本次链接交接期间后台操作授权或设备状态已变化；未发送链接"
                    else if (link == null)
                        "复制动作已执行，但尚未获得这次复制的有效链接（" +
                            shellCopy?.optString("errorCode").orEmpty().ifBlank { "clipboard_unavailable" } +
                            "）；未发送旧链接，可重新观察分享方式"
                    else "已获取本次有效链接，但私聊卡片未确认送达；未声称发送成功")
                    .toString()
            }
            return completeOwnedAction(
                context = context, characterId = characterId, requestId = requestId,
                args = args, action = action, id = id, requestedPackage = requestedPackage,
                unreadAtStart = unreadAtStart, applied = applied,
                beforeUiFingerprint = beforeUiFingerprint,
                beforeScrollFrameImage = beforeScrollFrame?.visualImage,
                beforeScrollFrameWindow = beforeScrollFrame?.windowIdentity,
                beforeScrollText = beforeScrollText, visualPress = visualPress,
                beforeTouchPixels = beforeTouchPixels, receipts = receipts,
                stillAuthorized = ::stillAuthorized,
                captureChatTitleFrame = ::captureChatTitleFrame,
            )
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) {
                forgetTransport()
                throw error
            }
            // An unknown transport/display state must never be retried with
            // an old action ID; release before the next request.
            forgetTransport()
            val detail = error.message?.takeIf(String::isNotBlank)
                ?: error::class.java.simpleName
            val outcome = commandFailureStatus(error)
            return JSONObject().put("success", false).put("status", outcome)
                .put("taskCompleted", false)
                .put("effectKind", if (outcome == "unverified") "unknown" else "none")
                .put("retrySafe", false)
                .put("mode", "independent_virtual_display")
                .put("errorCode", "virtual_phone_" + receipts.failureStage)
                .put("diagnosticStage", receipts.failureStage)
                .put("error", detail.take(220))
                .put("accepted", receipts.searchReceipt?.optBoolean("searchSubmitted") == true)
                .attachSearchReceipt(receipts.searchReceipt).attachMomentReceipt(receipts.momentReceipt).attachCommentReceipt(receipts.commentReceipt).attachNavigationReceipt(receipts.navigationReceipt).toString()
        } finally { mutex.unlock() }
    }

    // ART rejected the monolithic executeOwned coroutine's merged DEX registers.
    // Keep the post-action verification in a separate coroutine state machine.
    private class OwnedActionReceipts {
        var searchReceipt: JSONObject? = null
        var momentReceipt: JSONObject? = null
        var commentReceipt: JSONObject? = null
        var navigationReceipt: JSONObject? = null
        var failureStage: String = "owner_authorization"
    }

    private suspend fun completeOwnedAction(
        context: Context,
        characterId: String,
        requestId: String,
        args: JSONObject,
        action: String,
        id: Int,
        requestedPackage: String,
        unreadAtStart: Set<String>,
        applied: JSONObject?,
        beforeUiFingerprint: String,
        beforeScrollFrameImage: String?,
        beforeScrollFrameWindow: String?,
        beforeScrollText: String,
        visualPress: Boolean,
        beforeTouchPixels: String,
        receipts: OwnedActionReceipts,
        stillAuthorized: () -> Boolean,
        captureChatTitleFrame: suspend () -> android.graphics.Bitmap?,
    ): String {
        val navigationLabels = if (action == "launch" || action == LuluVirtualNavigation.ACTION)
            LuluVirtualNavigation.labels(args) else null
            if (action == "send_chat_message") {
                // The sole irreversible click was already attempted inside
                // the verified accessibility flow. Do not automatically
                // repeat it when pixels/UI acknowledgement are unavailable.
                lastObservedUi = null
                val dispatched = applied?.optBoolean("messageSendClicked") == true
                val attempted = applied?.optBoolean("messageSendAttempted") == true
                val outgoingBefore = applied?.optInt("outgoingVisibleBefore", -1) ?: -1
                val recipient = args.optString("recipient")
                val message = args.optString("text")
                // WeChat redraws the chat asynchronously after accepting
                // Send. Poll READ-ONLY local UI evidence, never click twice.
                var appearedInChat = false
                if (attempted && outgoingBefore >= 0) {
                    kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                        for (attempt in 0 until 7) {
                            if (ownerHasPaused(context) ||
                                !LuluRolePhoneAuthorization.isGranted(context, characterId) ||
                                context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true)
                                break
                            val after = withContext(Dispatchers.Main) {
                                LuluAccessibilityService.virtualChatOutgoingTextCount(
                                    id, activeApp, recipient, message, captureChatTitleFrame)
                            }
                            if (after > outgoingBefore) {
                                appearedInChat = true
                                break
                            }
                            if (attempt < 6) kotlinx.coroutines.delay(180)
                        }
                    }
                }
                // Screenshot-only recovery has no accessible message list.
                // Check for a NEW right-side client bubble after the ONE click,
                // against the OCR count captured immediately before sending.
                // Never replay a click if the receipt/pixels remain ambiguous.
                if (!appearedInChat && attempted &&
                    (applied?.optInt("visualOutgoingBefore", -1) ?: -1) >= 0 &&
                    stillAuthorized()) {
                    appearedInChat = LuluVirtualChatTitleOcr.outgoingAppearedVisually(
                        id, activeApp, recipient, message,
                        applied?.optInt("visualOutgoingBefore", -1) ?: -1,
                        captureChatTitleFrame,
                    )
                }
                val appName = LuluVirtualChatSendPolicy.appName(activeApp)
                val inputVerified = applied?.optBoolean("messageInputReadbackVerified") == true
                val stage = when {
                    appearedInChat -> "client_message_visible"
                    attempted -> "send_click"
                    inputVerified -> "draft_written"
                    applied?.optString("errorCode") == "chat_recipient_unverified" -> "recipient_check"
                    applied?.optBoolean("inputModeSwitchClicked") == true -> "keyboard_switch"
                    applied?.optBoolean("chatRecipientVerified") == true -> "text_input"
                    else -> "conversation_check"
                }
                val receipt = JSONObject().put("success", appearedInChat)
                    .put("accepted", dispatched || appearedInChat)
                    .put("status", when {
                        appearedInChat -> "client_message_visible"
                        dispatched -> "send_clicked_delivery_unverified"
                        else -> "unverified"
                    })
                    .put("mode", "independent_virtual_display").put("displayId", id)
                    .put("packageName", activeApp)
                    .put("chatStage", stage)
                    .put("messageInputReadbackVerified", inputVerified)
                    .put("chatRecipientVerified",
                        applied?.optBoolean("chatRecipientVerified") == true)
                    .put("messageSendAttempted", attempted)
                    .put("messageSendClicked", dispatched)
                    .put("messageSendMethod", applied?.optString("messageSendMethod").orEmpty())
                    .put("messageAppearedInChat", appearedInChat)
                    .put("inputModeSwitchClicked",
                        applied?.optBoolean("inputModeSwitchClicked") == true)
                    .put("messageDeliveryVerified", false)
                    .put("taskCompleted", false)
                    .put("errorCode", applied?.optString("errorCode").orEmpty())
                    .apply {
                        if (!attempted && !dispatched && activeApp == LuluVirtualChatSendPolicy.WECHAT) {
                            put("chatInputDiagnostics", withContext(Dispatchers.Main) {
                                LuluAccessibilityService.virtualChatInputDiagnostics(id, activeApp)
                            })
                        }
                    }
                    .put("verification", if (appearedInChat)
                        "已在真实${appName}会话中观察到新增的右侧消息气泡，确认客户端显示发出；服务器或对方是否收到尚无独立证据"
                    else if (dispatched)
                        "当前${appName}聊天对象和正文已核实，发送按钮仅被点击一次，但未观察到新消息气泡；结果不确定，不得重复点击发送"
                    else if (attempted)
                        "已尝试点一次发送，设备未确认是否接收，结果未知"
                    else if (applied?.optBoolean("messageInputReadbackVerified") == true)
                        "聊天文字已写入并读回，但发送按钮或收件人未能再次核实；没有确认发送，请先只读观察"
                    else applied?.optString("verification")?.takeIf(String::isNotBlank)
                        ?: if (applied?.optBoolean("chatRecipientVerified") == true)
                            "已核实目标${appName}会话，在切换或填写文字阶段停下；未点发送"
                        else "${appName}消息尚未确认发送；详情见errorCode")
                    .put("observedChatHeaderTexts", applied?.optJSONArray("observedChatHeaderTexts")
                        ?: org.json.JSONArray())
                // Keep the continuation on the live chat after a pre-send
                // failure; a new token is bound to the observed current page.
                if (!attempted && !dispatched && stillAuthorized() &&
                    windowGate(id, activeApp).optString("kind") == "target") {
                    receipt.put("targetWindowVerified", true)
                    val (current, textMode) = withContext(Dispatchers.Main) {
                        LuluAccessibilityService.observeDisplay(id) to
                            LuluAccessibilityService.virtualChatTextInputVisible(id, activeApp)
                    }
                    if (current.connected && current.packageName == activeApp) {
                        receipt.put("visibleText", current.visibleText.take(3_000))
                            .put("chatTextInputVisible", textMode)
                        if (textMode && stage == "recipient_check") receipt.put("verification",
                            "当前已出现文字输入框，发送停在收件人标题核实；尚未点发送")
                    }
                    // Return the actual page with a pre-click rejection, so
                    // the same role can choose a different visible entry or
                    // correct the recipient without a paid observe-only turn.
                    val page = command(JSONObject().put("action", "observe"))
                    val pagePixels = page.optString("imageDataUrl")
                    if (page.optInt("displayId") == id &&
                        page.optString("targetPackage") == activeApp && stillAuthorized() &&
                        windowGate(id, activeApp).optString("kind") == "target" &&
                        LuluVirtualFrameSafety.canSendToRole(page.optBoolean("frameValidated"),
                            page.optLong("frameTimestampNanos"), pagePixels, page.optBoolean("frameBlack"))) {
                        LuluScreenFrameHandoff.put(characterId, requestId, pagePixels)
                        receipt.put("imageForwardedToRole", true)
                            .put("frameValidated", true)
                            .put("frameTimestampNanos", page.optLong("frameTimestampNanos"))
                            .put("frameFreshAfterAction", page.optBoolean("frameFreshAfterAction"))
                            .put("actionFrameUnverified", page.optBoolean("actionFrameUnverified"))
                            .put("newerThanPreviousCapture", page.optBoolean("newerThanPreviousCapture"))
                            .put("screenSource", "virtual_display")
                    }
                    attachUiEvidence(receipt, characterId, id, activeApp, pagePixels)
                }
                return receipt.toString()
            }
            if (action == "search") receipts.searchReceipt = applied
            if (action == "search" && applied?.optString("status").orEmpty().isNotBlank()) {
                lastObservedUi = null
                return checkNotNull(applied).put("success", false).put("accepted", false)
                    .put("mode", "independent_virtual_display").put("displayId", id)
                    .put("taskCompleted", false).toString()
            }
            if (action == "launch" && applied?.optBoolean("accepted") == true) activeApp = requestedPackage
            if (applied != null && !applied.optBoolean("accepted") &&
                action !in setOf("type", "focus_search", "tap", "long_press", "tap_element", "search",
                    LuluWeChatMomentsPolicy.OPEN_COMPOSER, LuluWeChatMomentsPolicy.PUBLISH)) {
                // A rejected Android shell command is a REAL execution
                // failure, not an ordinary awaiting-screen observation.
                // Preserve am/input stderr instead of erasing it with a
                // generic unverified receipt that performance logs suppress.
                val rejectedByShell = applied.optString("errorCode") in setOf(
                    "shell_launch_rejected", "shell_input_rejected")
                return applied.put("mode", "independent_virtual_display")
                    .put("status", if (rejectedByShell) "failed" else "unverified")
                    .put("verification", applied.optString("verification").ifBlank {
                        "该后台操作未被设备接受，不得声称成功"
                    }).toString()
            }
            // Android launches apps asynchronously. Wait briefly for this exact
            // package to own the virtual screen before taking a new screenshot.
            // These are local accessibility checks, not additional model calls.
            if (action == "launch") {
                for (attempt in 0 until 6) {
                    val visibleNow = withContext(Dispatchers.Main) {
                        LuluAccessibilityService.observeDisplay(id)
                    }
                    if (visibleNow.connected && visibleNow.packageName == requestedPackage) break
                    if (attempt < 5) kotlinx.coroutines.delay(300)
                }
            }
            if (navigationLabels != null) {
                receipts.failureStage = "navigate_visible_labels"
                receipts.navigationReceipt = LuluVirtualNavigation.run(
                    navigationLabels,
                    pauseReason = {
                        when {
                            !stillAuthorized() -> "navigation_interrupted"
                            LuluBackgroundPhoneContinuation.hasNewUnreadInput(
                                unreadAtStart,
                                CompanionOnlineStore.unreadChatSnapshot(characterId).messageIds.toSet(),
                            ) -> "new_user_input"
                            windowGate(id, activeApp).optString("kind") != "target" ->
                                "navigation_window_unverified"
                            humanOnlyPage(id, activeApp) != null -> "navigation_requires_user"
                            else -> null
                        }
                    },
                    click = { label ->
                        lastObservedUi = null
                        val baseline = withContext(Dispatchers.Main) {
                            LuluAccessibilityService.virtualNavigationFingerprint(id, activeApp)
                        }
                        if (baseline.isBlank() ||
                            !command(JSONObject().put("action", "prepare_type")).optBoolean("accepted"))
                            JSONObject().put("accepted", false)
                                .put("errorCode", "navigation_observation_unavailable")
                        else withContext(Dispatchers.Main) {
                            LuluAccessibilityService.navigateVirtualVisibleLabel(
                                id, activeApp, label, stillAuthorized)
                        }.put("navigationBaseline", baseline)
                    },
                    changed = { outcome ->
                        awaitStableUi(id, activeApp, outcome.optString("navigationBaseline"), navigation = true)
                    },
                ).put("navigationLaunchedApp", action == "launch")
            }
            // A new frame is observed after the action. A successful dispatch
            // is never mistaken for a finished user task.
            val uiSettled = if (receipts.navigationReceipt?.optBoolean("navigationCompleted") == true) true
            else if (applied?.optBoolean("accepted") == true &&
                action in setOf("tap", "long_press", "tap_element", "swipe", "submit", "search", "back",
                    LuluWeChatMomentsPolicy.OPEN_COMPOSER, LuluWeChatMomentsPolicy.PUBLISH))
                awaitStableUi(id, activeApp, beforeUiFingerprint) else false
            val backOutcome = if (action == "back" && applied?.optBoolean("accepted") == true)
                when {
                    uiSettled -> "page_change_observed"
                    beforeUiFingerprint.isBlank() -> "no_prior_ui_evidence"
                    else -> "no_change_verified"
                } else if (action == "back") "not_accepted" else ""
            receipts.failureStage = "read_virtual_frame"
            val frame = command(JSONObject().put("action", "observe"))
            if (!frame.optBoolean("success")) {
                val top = windowGate(id, activeApp)
                if (top.optString("kind") != "target") {
                    lastObservedUi = null
                    return JSONObject(pausedWindowReceipt(id, top)).attachSearchReceipt(applied).attachMomentReceipt(receipts.momentReceipt).attachCommentReceipt(receipts.commentReceipt).attachNavigationReceipt(receipts.navigationReceipt).toString()
                }
                // Mika can still verify a UI tree when ImageReader has no new
                // frame. Preserve accessibility facts without fabricating pixels.
                val observed = withContext(Dispatchers.Main) {
                    LuluAccessibilityService.observeDisplay(id)
                }
                val owned = observed.connected && observed.packageName == activeApp &&
                    !LuluScreenAwareness.isExcluded(context, observed.packageName)
                if (observed.connected && observed.packageName != activeApp) {
                    lastObservedUi = null
                    return JSONObject(humanOnlyReceipt(id, observed.packageName, "window_changed"))
                        .attachSearchReceipt(applied).attachMomentReceipt(receipts.momentReceipt).attachCommentReceipt(receipts.commentReceipt).attachNavigationReceipt(receipts.navigationReceipt).toString()
                }
                if (owned) {
                    val human = humanOnlyPage(id, activeApp)
                    if (human != null) {
                        lastObservedUi = null
                        return JSONObject(humanOnlyReceipt(id, activeApp, human)).attachSearchReceipt(applied).attachMomentReceipt(receipts.momentReceipt).attachCommentReceipt(receipts.commentReceipt).attachNavigationReceipt(receipts.navigationReceipt).toString()
                    }
                }
                val search = if (owned) withContext(Dispatchers.Main) {
                    LuluAccessibilityService.virtualSearchFieldStatus(id, activeApp)
                } else JSONObject()
                val typed = owned && action == "type" && applied?.optBoolean("accepted") == true &&
                    (applied.optBoolean("inputTextReadbackVerified") ||
                        withContext(Dispatchers.Main) {
                            LuluAccessibilityService.typedTextReadbackOnDisplay(
                                id, activeApp, args.getString("text"))
                        })
                val unverified = JSONObject().put("success", false).put("status", "unverified")
                    .put("accepted", applied?.optBoolean("accepted") ?: true)
                    .put("mode", "independent_virtual_display").put("displayId", id)
                    .put("targetWindowVerified", owned)
                    .put("packageName", if (owned) observed.packageName else "")
                    .put("visibleText", if (owned) observed.visibleText.take(5_000) else "")
                    .put("searchEditorStatus", search)
                    .put("inputTextReadbackVerified", typed)
                    .put("inputVerification", if (action == "type" && typed)
                    "match" else applied?.optString("inputVerification").orEmpty())
                .put("inputStage", if (action == "type")
                    LuluVirtualTextInputPolicy.stage(
                        applied?.optBoolean("accepted") == true, typed)
                    else "")
                .put("inputMethod", applied?.optString("inputMethod").orEmpty())
                .put("inputDispatched", applied?.optBoolean("inputDispatched") == true)
                .put("inputAttempted", applied?.optBoolean("inputAttempted") == true)
                .attachInputFocusReceipt(applied)
                    .put("frameValidated", false).put("taskCompleted", false)
                    .put("frameBlack", frame.optBoolean("frameBlack", false))
                    .put("screenCaptureStatus", if (frame.optBoolean("frameBlack", false))
                        "all_black_unreadable" else "no_fresh_frame")
                    .put("uiSettled", uiSettled)
                    .put("backOutcome", backOutcome)
                    .put("actionMethod", applied?.optString("actionMethod").orEmpty())
                    .put("dispatchAttempted", applied?.optBoolean("dispatchAttempted") == true)
                    .put("outwardEffectUnverified", applied?.optBoolean("outwardEffectUnverified") == true)
                    .put("actionErrorCode", applied?.optString("errorCode").orEmpty())
                    .put("errorCode", if (action == "type" && !typed)
                        applied?.optString("errorCode").orEmpty()
                            .ifBlank { "virtual_input_no_fresh_frame" }
                        else "")
                    .put("verification", listOfNotNull(
                        applied?.optString("verification")?.takeIf(String::isNotBlank),
                        frame.optString("verification", "后台屏幕暂无可确认画面"),
                    ).joinToString("；"))
                    .attachSearchReceipt(applied).attachMomentReceipt(receipts.momentReceipt).attachCommentReceipt(receipts.commentReceipt).attachNavigationReceipt(receipts.navigationReceipt)
                if (!owned) {
                    lastObservedUi = null
                    return unverified.put("effectKind", "unknown").toString()
                }
                attachUiEvidence(unverified, characterId, id, activeApp)
                if (action == "swipe") {
                    unverified.put("scrollOutcome", LuluVirtualScrollOutcome.classify(
                        applied?.optBoolean("accepted") != false, owned,
                        beforeUiFingerprint, lastObservedUi?.fingerprint,
                        beforeScrollText, observed.visibleText).wire)
                }
                return unverified.put("effectKind", LuluVirtualUiPolicy.effectKind(
                    action, applied?.optBoolean("accepted") != false,
                    beforeUiFingerprint, lastObservedUi?.fingerprint,
                    typed)).toString()
            }

            val top = windowGate(id, activeApp)
            if (top.optString("kind") != "target") {
                lastObservedUi = null
                return JSONObject(pausedWindowReceipt(id, top)).attachSearchReceipt(applied).attachMomentReceipt(receipts.momentReceipt).attachCommentReceipt(receipts.commentReceipt).attachNavigationReceipt(receipts.navigationReceipt).toString()
            }
            val visible = withContext(Dispatchers.Main) {
                LuluAccessibilityService.observeDisplay(id)
            }
            // We must be able to attribute every pixel to a permitted app.
            // A dialog, system permission or other App may appear instead;
            // do not upload it or report it as the intended page.
            if (visible.connected && visible.packageName != activeApp) {
                lastObservedUi = null
                return JSONObject(humanOnlyReceipt(id, visible.packageName, "window_changed"))
                    .attachSearchReceipt(applied).attachMomentReceipt(receipts.momentReceipt).attachCommentReceipt(receipts.commentReceipt).attachNavigationReceipt(receipts.navigationReceipt).toString()
            }
            if (!visible.connected || visible.packageName != activeApp ||
                LuluScreenAwareness.isExcluded(context, visible.packageName)) {
                return JSONObject().put("success", false).put("status", "unverified")
                    .put("accepted", applied?.optBoolean("accepted") ?: true)
                    .put("mode", "independent_virtual_display").put("displayId", id)
                    .put("verification", "无法核实独立屏幕的前台窗口属于已授权目标应用；已丢弃截图")
                    .also { lastObservedUi = null }.attachSearchReceipt(applied).attachMomentReceipt(receipts.momentReceipt).attachCommentReceipt(receipts.commentReceipt).attachNavigationReceipt(receipts.navigationReceipt).toString()
            }
            val human = humanOnlyPage(id, activeApp)
            if (human != null) {
                lastObservedUi = null
                return JSONObject(humanOnlyReceipt(id, activeApp, human)).attachSearchReceipt(applied).attachMomentReceipt(receipts.momentReceipt).attachCommentReceipt(receipts.commentReceipt).attachNavigationReceipt(receipts.navigationReceipt).toString()
            }
            val pixels = frame.optString("imageDataUrl")
            val frameFresh = frame.optBoolean("frameFreshAfterAction", false)
            // Pass the owned virtual-display frame only to the same role and
            // exact tool request, regardless of foreground/online entry point.
            // capture_screen remains strictly the owner's main display.
            val hasFrame = LuluVirtualFrameSafety.canSendToRole(
                frame.optBoolean("frameValidated", false),
                frame.optLong("frameTimestampNanos", 0L),
                pixels,
                frame.optBoolean("frameBlack", false),
            )
            // Archive real NEW virtual-display frames in the same per-role
            // owner-inspection album as consented main-screen captures.
            // No second screenshot, model call, synthetic pixels or chat post.
            val screenArchive = if (hasFrame &&
                frame.optBoolean("newerThanPreviousCapture", false) &&
                LuluRolePhoneAuthorization.isGranted(context, characterId) &&
                !LuluScreenAwareness.isExcluded(context, visible.packageName) &&
                !ownerHasPaused(context) &&
                context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != true) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        com.jiacimu.lulu.RolePhotoAlbumStore.recordVirtualScreenObservation(
                            context, characterId, pixels, visible.packageName, id,
                            java.time.Instant.now())
                    }
                }
            } else null
            if (hasFrame) LuluScreenFrameHandoff.put(characterId, requestId, pixels)
            val searchStatus = withContext(Dispatchers.Main) {
                LuluAccessibilityService.virtualSearchFieldStatus(id, activeApp)
            }
            val expected = args.optString("expectedText").trim()
            val textReadbackVerified = if (action == "type" &&
                applied?.optBoolean("accepted") == true) {
                when {
                    applied.optBoolean("inputTextReadbackVerified") -> true
                    applied.optString("inputMethod") == "visual_target_uid_unicode" &&
                        hasFrame && frameFresh && args.has("inputX") && args.has("inputY") ->
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val bytes = java.util.Base64.getDecoder().decode(
                                    pixels.removePrefix("data:image/jpeg;base64,"))
                                val bitmap = android.graphics.BitmapFactory.decodeByteArray(
                                    bytes, 0, bytes.size)
                                    ?: return@runCatching false
                                LuluVirtualTextReadbackOcr.verify(
                                    bitmap, args.getString("text"),
                                    args.getInt("inputX"), args.getInt("inputY"))
                            }.getOrDefault(false)
                        }
                    else -> false
                }
            } else false
            val actionConditionVerified = action == "observe" ||
                (action == "launch" && visible.packageName == requestedPackage) ||
                (action == "focus_search" && searchStatus.optBoolean("focusedSearchField")) ||
                (expected.isNotBlank() && visible.visibleText.contains(expected))
            // A real post-action frame is sufficient to let the SAME character
            // inspect the result and select another action. It is NOT proof that
            // a swipe changed content or that the requested task was completed.
            // Previously a swipe without expectedText was always "unverified",
            // so continuous browsing stopped after just one swipe.
            val observed = hasFrame && (actionConditionVerified || expected.isBlank()) &&
                // A retained static page remains viewable, but cannot verify
                // the preceding gesture/input when no post-action frame exists.
                (action == "observe" || !frame.optBoolean("actionFrameUnverified")) &&
                // A fresh pixel frame is NOT proof that BACK navigated; the
                // verified app UI must actually change before we report success.
                (action != "back" || uiSettled) &&
                (applied?.optBoolean("accepted") != false) &&
                (action != "type" || textReadbackVerified) &&
                (action != "focus_search" || searchStatus.optBoolean("focusedSearchField"))
            val sharedReceipt = if (args.optBoolean("sendToChat")) {
                if (!hasFrame) JSONObject().put("sentToChat", false)
                    .put("shareError", "后台虚拟屏未获得真实画面，不能发主屏截图替代")
                else if (!LuluRolePhoneAuthorization.isGranted(context, characterId) ||
                    ownerHasPaused(context) ||
                    context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true ||
                    LuluScreenAwareness.isExcluded(context, visible.packageName) ||
                    displayId != id || ownerCharacterId != characterId ||
                    activeApp != visible.packageName ||
                    windowGate(id, activeApp).optString("kind") != "target") {
                    JSONObject().put("sentToChat", false)
                        .put("shareError", "发送前后台屏授权或目标窗口已变化，未发送截图")
                } else try {
                    // Only pixels produced by this exact, independently
                    // verified displayId may be forwarded as a chat image.
                    // The user's live main screen is NEVER used here.
                    val raw = java.util.Base64.getDecoder().decode(
                        pixels.removePrefix("data:image/jpeg;base64,"))
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
                        ?: error("后台截图像素解码失败")
                    try {
                        val sent = com.jiacimu.lulu.data.CompanionActionRuntime.sendCapturedScreenshot(
                            context, characterId, bitmap,
                            args.optString("caption").take(300), visible.packageName,
                            sourceDisplay = "virtual")
                        JSONObject().put("sentToChat", sent.success)
                            .put("shareSummary", sent.summary)
                            .put("conversationId", sent.conversationId.orEmpty())
                            .put("evidenceId", sent.evidenceId.orEmpty())
                    } finally { bitmap.recycle() }
                } catch (failure: Exception) {
                    JSONObject().put("sentToChat", false)
                        .put("shareError", failure.message.orEmpty().take(140))
                }
            } else JSONObject()
            val touchVerification = if (!visualPress || applied == null) "" else when {
                !applied.optBoolean("accepted") -> applied.optString("verification", "触摸派发未获确认")
                uiSettled || (hasFrame && frameFresh && beforeTouchPixels.isNotBlank() &&
                    beforeTouchPixels != pixels) -> "设备已完成触摸，观察到页面变化，请核对是否到达目标"
                else -> "设备已派发触摸，尚未观察到页面变化"
            }
            val finalReceipt = JSONObject().put("success", observed)
                .put("status", if (observed) "observed" else "unverified")
                .put("actionConditionVerified", actionConditionVerified)
                .put("taskCompleted", false)
                .put("inputTextReadbackVerified", textReadbackVerified)
                .put("inputVerification", if (action == "type" && textReadbackVerified)
                    "match" else applied?.optString("inputVerification").orEmpty())
                .put("inputDispatched", applied?.optBoolean("inputDispatched") == true)
                .put("inputAttempted", applied?.optBoolean("inputAttempted") == true)
                .attachInputFocusReceipt(applied)
                .put("inputStage", if (action == "type")
                    LuluVirtualTextInputPolicy.stage(
                        applied?.optBoolean("accepted") == true, textReadbackVerified)
                    else "")
                .put("targetWindowVerified", true)
                .put("searchEditorStatus", searchStatus)
                .put("uiSettled", uiSettled)
                .put("backOutcome", backOutcome)
                .put("actionMethod", applied?.optString("actionMethod").orEmpty())
                .put("dispatchAttempted", applied?.optBoolean("dispatchAttempted") == true)
                .put("outwardEffectUnverified", applied?.optBoolean("outwardEffectUnverified") == true)
                .put("actionErrorCode", applied?.optString("errorCode").orEmpty())
                // Expose the precise Android/IME/Unicode failure to the SAME
                // role and task history. A fresh picture is not a successful
                // draft readback, and a hidden error code makes recovery blind.
                .put("errorCode", if (action == "type" && !textReadbackVerified)
                    applied?.optString("errorCode").orEmpty()
                        .ifBlank { "virtual_input_readback_unverified" }
                    else "")
                .put("accepted", applied?.optBoolean("accepted") ?: true)
                .put("mode", "independent_virtual_display")
                .put("displayId", id).put("packageName", visible.packageName)
                .put("visibleText", visible.visibleText.take(5_000))
                .put("screenRecordSaved", screenArchive?.isSuccess == true)
                .put("screenRecordId", screenArchive?.getOrNull()?.id.orEmpty())
                .put("screenRecordError", screenArchive?.exceptionOrNull()?.message.orEmpty().take(100))
                .put("imageForwardedToRole", hasFrame)
                .put("frameFreshAfterAction", frameFresh)
                .put("actionFrameUnverified", frame.optBoolean("actionFrameUnverified"))
                .put("frameValidated", hasFrame)
                .put("frameBlack", frame.optBoolean("frameBlack", false))
                .put("frameTimestampNanos", frame.optLong("frameTimestampNanos", 0L))
                .put("displayWidth", frame.optInt("displayWidth"))
                .put("displayHeight", frame.optInt("displayHeight"))
                .put("captureWidth", frame.optInt("captureWidth"))
                .put("captureHeight", frame.optInt("captureHeight"))
                .put("newerThanPreviousCapture", frame.optBoolean("newerThanPreviousCapture", false))
                .put("screenSource", "virtual_display")
                .put("sentToChat", sharedReceipt.optBoolean("sentToChat"))
                .put("shareSummary", sharedReceipt.optString("shareSummary"))
                .put("shareError", sharedReceipt.optString("shareError"))
                .put("verification", if (touchVerification.isNotBlank()) touchVerification
                    else if (frame.optBoolean("actionFrameUnverified"))
                    frame.optString("verification")
                    else if (action == "back")
                    (applied?.optString("verification").orEmpty() + "；" +
                        if (uiSettled) "后台App控件树已确认发生变化，实际目标页面仍需核对"
                        else "未确认页面发生变化，不能声称返回成功；可重新观察并针对可见的App返回箭头选择新操作")
                    else if (action == "focus_search")
                    (if (searchStatus.optBoolean("focusedSearchField"))
                        "已核实唯一搜索框获得焦点；未输入或提交内容"
                    else "搜索框焦点未能核实；没有点击其他输入框")
                    else if (action == "type" && !textReadbackVerified)
                    (applied?.optString("verification").orEmpty()
                        .ifBlank { "目标输入区文字尚未读回；请根据新的真实画面核对" } +
                        "；输入状态=" + (applied?.optString("errorCode").orEmpty()
                            .ifBlank { "virtual_input_readback_unverified" }) +
                        "；不能说已输入成功，更不代表已发送或提交")
                    else if (action == "type" && observed)
                    "已从当前后台App的输入区读回所输入文字；只完成输入草稿，没有发送、发布或提交"
                    else if (actionConditionVerified)
                    "已观察到后台目标窗口与指定的验证条件；实际业务任务是否完成仍由角色依据画面判断"
                    else if (observed)
                        "只确认输入已派发且取得后台虚拟屏新画面，未证明画面发生变化或业务目标完成"
                    else "未观察到预期文字，动作结果仍未核实；不得称任务完成")
                .attachSearchReceipt(applied).attachMomentReceipt(receipts.momentReceipt).attachCommentReceipt(receipts.commentReceipt).attachNavigationReceipt(receipts.navigationReceipt)
            attachUiEvidence(finalReceipt, characterId, id, activeApp, if (hasFrame) pixels else null)
            if (action == "swipe") {
                finalReceipt.put("scrollOutcome", LuluVirtualScrollOutcome.classify(
                    applied?.optBoolean("accepted") != false, true,
                    beforeUiFingerprint, lastObservedUi?.fingerprint,
                    beforeScrollText, visible.visibleText,
                    freshPixelsChanged = hasFrame && frameFresh && beforeScrollFrameImage != null &&
                        beforeScrollFrameWindow == lastObservedUi?.windowIdentity &&
                        beforeScrollFrameImage != pixels).wire)
                finalReceipt.put("scrollVerification", when (finalReceipt.optString("scrollOutcome")) {
                    "content_changed" -> "后台可读内容或控件树发生变化；仍需核对是否到达预期内容"
                    "frame_changed" -> "后台画面像素有变化；请核对是滑到了新内容还是原视频仍在播放"
                    "no_visible_change" -> "滑动后没有可证实的内容变化；可能已到底、未滚动或尚未加载，不得自动认定到底"
                    else -> "本次滑动结果不确定；不要重复滑动或声称看过新内容"
                })
            }
            return finalReceipt.put("effectKind", LuluVirtualUiPolicy.effectKind(
                action, applied?.optBoolean("accepted") != false,
                beforeUiFingerprint, lastObservedUi?.fingerprint,
                textReadbackVerified)).toString()
    }

}
