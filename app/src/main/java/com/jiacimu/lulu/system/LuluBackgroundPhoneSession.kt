package com.jiacimu.lulu.system

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import com.wangzan101.mika.core.adb.AdbConnection
import kotlinx.coroutines.Dispatchers
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

    private data class ObservedUi(
        val token: String,
        val fingerprint: String,
        val characterId: String,
        val displayId: Int,
        val packageName: String,
    )
    private var lastObservedUi: ObservedUi? = null

    /** No pixels are invented: every semantic ref comes from the owned
     * virtual App's CURRENT accessibility tree. A new observation supersedes
     * the preceding refs, and STOP/transport loss clears them. */
    private suspend fun attachUiEvidence(
        output: JSONObject, characterId: String, id: Int, packageName: String,
    ): JSONObject {
        val inventory = withContext(Dispatchers.Main) {
            LuluAccessibilityService.virtualUiSnapshot(id, packageName)
        }
        val fingerprint = inventory.optString("fingerprint")
        val elements = inventory.optJSONArray("elements")
        if (fingerprint.isBlank() || elements == null || elements.length() == 0) {
            lastObservedUi = null
            return output
        }
        val token = java.util.UUID.randomUUID().toString().substring(0, 12)
        lastObservedUi = ObservedUi(token, fingerprint, characterId, id, packageName)
        return output.put("screenSnapshotId", token)
            .put("interactiveElements", elements)
            .put("uiEvidenceSource", "virtual_accessibility_tree")
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
                screenConsent = LuluScreenAwareness.onDemandAllowed(context),
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
    ): Boolean {
        if (beforeFingerprint.isBlank()) return false
        return withContext(Dispatchers.Main) {
            val start = android.os.SystemClock.uptimeMillis()
            var lastTree = beforeFingerprint
            var lastChangeAt = start
            LuluMikaChangeWait.awaitStableChange(
                now = android.os.SystemClock::uptimeMillis,
                sleep = { kotlinx.coroutines.delay(it) },
                frame = { null },
                quietForMs = { android.os.SystemClock.uptimeMillis() - lastChangeAt },
                treeChanged = {
                    val observed = LuluAccessibilityService
                        .virtualUiSnapshot(displayId, packageName).optString("fingerprint")
                    if (observed.isNotBlank() && observed != lastTree) {
                        lastTree = observed
                        lastChangeAt = android.os.SystemClock.uptimeMillis()
                    }
                    lastTree != beforeFingerprint
                },
                beforeFrame = null,
            )
        }
    }

    /** Ported Mika ScreenDiagnosis on THIS independent display only.
     * It reports a human-only page, never reads a password value or
     * replaces the original character's decision. */
    private suspend fun humanOnlyPage(
        displayId: Int, packageName: String, visibleText: String,
    ): String? {
        val password = withContext(Dispatchers.Main) {
            LuluAccessibilityService.virtualHasPasswordField(displayId, packageName)
        }
        val checked = LuluMikaScreenDiagnosis.classify(
            LuluMikaScreenFacts(
                currentApp = packageName,
                hasWindow = true,
                readableElements = if (visibleText.isBlank()) 0 else 1,
                changing = false,
                blackFrames = emptyList(),
                texts = listOf(visibleText.take(2_500)),
                passwordField = password,
            )
        )
        return if (checked.needsHuman) checked.takeoverReason ?: "manual_verification" else null
    }

    private fun humanOnlyReceipt(
        displayId: Int, packageName: String, reason: String,
    ): String = JSONObject().put("success", false).put("status", "waiting_user")
        .put("mode", "independent_virtual_display").put("displayId", displayId)
        .put("packageName", packageName).put("screenState", "needs_human")
        .put("needsHuman", true).put("humanReason", reason)
        .put("accepted", false).put("taskCompleted", false)
        .put("verification", if (reason == "window_changed")
            "后台屏顶层窗口切换到了另一应用或系统弹窗；未确认仍属于本任务，已暂停操作，需主人或原角色核对"
            else "后台出现需要主人亲自处理的登录、验证码、支付或安全验证界面（$reason）；已停止自动操作，不读取密码或代替确认")
        .toString()

    private fun forgetTransport() {
        appContext?.let { runCatching { LuluBackgroundPhoneNotice.clear(it) } }
        appContext = null
        displayId = -1
        activeApp = ""
        ownerCharacterId = ""
        lastObservedUi = null
        runCatching { stream?.close() }
        runCatching { adb?.close() }
        stream = null
        adb = null
        replies.clear()
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
            thread(isDaemon = true, name = "lulu-background-display-replies") {
                try {
                    pipe.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (line.startsWith(REPLY_MARK)) replies.offer(line.removePrefix(REPLY_MARK))
                        }
                    }
                } catch (_: Exception) {
                    // The in-flight tool will time out and release the helper.
                }
            }
            val started = command(JSONObject().put("action", "start"))
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

    private suspend fun command(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val pipe = stream ?: error("shell helper disconnected")
        pipe.write((args.toString() + "\n").toByteArray(Charsets.UTF_8))
        val line = replies.poll(13, TimeUnit.SECONDS) ?: error("shell helper did not reply")
        val response = JSONObject(line)
        // Only start/stop may omit an owned display identifier.
        if (args.optString("action") !in setOf("start", "stop")) {
            check(displayId > 0 && response.optInt("displayId", -1) == displayId) {
                "来自未知虚拟屏的回执已丢弃"
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

    suspend fun execute(
        context: Context, characterId: String, requestId: String, args: JSONObject,
    ): String {
        mutex.lock()
        try {
            check(!ownerHasPaused(context)) {
                "主人已停止后台手机操作；必须由主人在设置中重新允许"
            }
            check(LuluScreenAwareness.onDemandAllowed(context)) {
                "主人尚未授权手机屏幕访问"
            }
            val userLocked = context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
            check(!userLocked) { "手机处于锁屏状态，后台屏幕操作暂不可用" }
            val action = args.optString("action").trim().lowercase()
            // One real virtual phone session belongs to one character at a
            // time. Never let a second role inherit the first role's screen.
            check(ownerCharacterId.isBlank() || ownerCharacterId == characterId) {
                "后台屏幕由另一角色使用中；请先由主人停止该会话"
            }
            check(action in setOf("start", "launch", "tap", "tap_element", "swipe", "back", "type", "focus_search", "submit", "observe", "stop")) {
                "不支持此后台屏幕操作"
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
            val requestedPackage = if (action == "launch" &&
                args.optString("packageName").isBlank()) {
                // A model can choose the actual installed app by its visible name;
                // the local resolver never guesses a package or bypasses exclusions.
                LuluInstalledAppResolver.resolve(context, args.optString("appName"))
            } else args.optString("packageName").trim()
            if (action == "launch") {
                check(requestedPackage.isNotEmpty()) { "必须指定准确的应用包名或显示名称" }
                check(!LuluScreenAwareness.isExcluded(context, requestedPackage)) {
                    "该应用处于主人的隐私排除名单，不允许操作"
                }
                val main = withContext(Dispatchers.Main) { LuluAccessibilityService.observe() }
                check(main.packageName != requestedPackage) {
                    "主人正在主屏使用这个 App，不允许将它移到后台屏幕"
                }
            }
            val id = ensureStarted(context)
            if (ownerCharacterId.isBlank()) ownerCharacterId = characterId
            appContext = context.applicationContext
            LuluBackgroundPhoneNotice.show(context)
            if (action == "start") return JSONObject().put("success", true)
                .put("status", "succeeded").put("mode", "independent_virtual_display")
                .put("displayId", id).put("observationAvailable", false)
                .put("ownFocusVerifiedByDisplayFlags", true)
                .put("verification", "shell 已创建独立焦点屏幕；尚未观察到 App 或桌面画面").toString()

            if (action != "launch" && activeApp.isBlank()) error("请先在后台屏幕打开一个已授权应用")
            if (action != "launch" && LuluScreenAwareness.isExcluded(context, activeApp)) {
                error("原授权应用现已列入隐私排除名单")
            }
            if (action != "launch" && requestedPackage.isNotBlank() && requestedPackage != activeApp) {
                error("当前后台屏幕目标与指定包名不一致，拒绝执行")
            }
            if (action != "launch") {
                val before = withContext(Dispatchers.Main) {
                    LuluAccessibilityService.observeDisplay(id)
                }
                if (!before.connected) {
                    lastObservedUi = null
                    return JSONObject().put("success", false).put("status", "unverified")
                        .put("mode", "independent_virtual_display").put("displayId", id)
                        .put("taskCompleted", false).put("targetWindowVerified", false)
                        .put("verification", "后台暂时没有可核实的应用窗口；未执行动作，可以先observe重新观察")
                        .toString()
                }
                if (before.packageName != activeApp) {
                    lastObservedUi = null
                    return humanOnlyReceipt(id, before.packageName, "window_changed")
                }
                val human = humanOnlyPage(id, activeApp, before.visibleText)
                if (human != null) return humanOnlyReceipt(id, activeApp, human)
            }
            if (action == "tap_element") {
                val snapshot = lastObservedUi
                val requestedSnapshot = args.optString("screenSnapshotId")
                val requestedElement = args.optString("elementRef")
                val owned = snapshot != null && snapshot.characterId == characterId &&
                    snapshot.displayId == id && snapshot.packageName == activeApp
                if (!owned || requestedElement.isBlank() ||
                    !LuluVirtualUiPolicy.sameSnapshot(
                        requestedSnapshot, snapshot?.token.orEmpty(),
                        snapshot?.fingerprint.orEmpty(), snapshot?.fingerprint.orEmpty())) {
                    return JSONObject().put("success", false).put("accepted", false)
                        .put("status", "unverified").put("taskCompleted", false)
                        .put("mode", "independent_virtual_display").put("displayId", id)
                        .put("verification", "控件编号已经过期或不属于此角色的后台App；请先observe重新读取，拒绝按旧位置点击")
                        .toString()
                }
            }
            if (action == "submit") {
                val safeSearch = withContext(Dispatchers.Main) {
                    LuluAccessibilityService.hasFocusedVirtualSearchEditor(id, activeApp)
                }
                check(safeSearch) {
                    "后台没有可确认的已聚焦搜索输入框；Enter 也可能变成发送/提交操作，为安全起见已拒绝。请先点击搜索框并输入文字，或查看后台小窗核对输入焦点"
                }
            }
            if (action == "tap") {
                val x = args.getInt("x")
                val y = args.getInt("y")
                val sensitive = withContext(Dispatchers.Main) {
                    LuluAccessibilityService.isSensitiveVirtualTap(
                        id, activeApp, x, y)
                }
                check(!sensitive) {
                    "检测到付款、发送、发布、删除或无法核实的敏感点击目标；拒绝自动操作"
                }
            }
            // Mika ClickFallback: select a small named, non-outward node
            // before dispatch. If found we click ONE verified node; if its
            // callback fails, we never automatically click again by shell.
            val semanticTap = if (action == "tap") withContext(Dispatchers.Main) {
                LuluAccessibilityService.safeVirtualTapTarget(
                    id, activeApp, args.getInt("x"), args.getInt("y"))
            } else JSONObject()
            // Snapshot BEFORE the action to measure actual UI effect. An
            // accepted gesture or a new frame is not itself a changed page.
            val beforeUiFingerprint = if (action == "launch") "" else
                withContext(Dispatchers.Main) {
                    LuluAccessibilityService.virtualUiSnapshot(id, activeApp)
                        .optString("fingerprint")
                }
            val request = JSONObject().put("action", action)
            if (action == "launch") {
                // Pass Android's actual exported launcher component, not just a
                // package-filtered implicit MAIN intent. Some apps have multiple
                // launcher activities and 'am start -p pkg' is ambiguous.
                val launch = context.packageManager.getLaunchIntentForPackage(requestedPackage)
                    ?: error("本机未找到该包名的真实启动入口")
                val component = launch.component
                    ?: error("目标 App 启动入口没有明确组件")
                check(component.packageName == requestedPackage) {
                    "本机启动组件与已授权 App 包名不匹配"
                }
                request.put("packageName", requestedPackage)
                    .put("launchComponent", component.flattenToString())
            }
            if (action == "tap") {
                request.put("x", args.getInt("x"))
                request.put("y", args.getInt("y"))
            }
            if (action == "swipe") {
                for (name in listOf("startX", "startY", "endX", "endY")) {
                    request.put(name, args.getInt(name))
                }
                request.put("durationMs", args.optInt("durationMs", 400))
            }
            val applied = when (action) {
                "observe" -> null
                "tap" -> {
                    val elementRef = semanticTap.optString("elementRef")
                    if (elementRef.isBlank()) {
                        command(request).put("actionMethod", "verified_coordinate")
                    } else {
                        val prepared = command(JSONObject().put("action", "prepare_type"))
                        check(prepared.optBoolean("accepted")) {
                            "无法建立单次安全控件点击的后台画面屏障"
                        }
                        val clicked = withContext(Dispatchers.Main) {
                            LuluAccessibilityService.tapVirtualUiElement(
                                id, activeApp, semanticTap.optString("fingerprint"), elementRef)
                        }
                        JSONObject().put("accepted", clicked)
                            .put("actionMethod", "mika_accessibility_node")
                            .put("displayId", id)
                    }
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
                            id, activeApp, prior.fingerprint, args.optString("elementRef"))
                    }
                    JSONObject().put("accepted", clicked).put("displayId", id)
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
                    require(value.isNotEmpty() && value.length <= 500) {
                        "每次只允许输入1到500个字符"
                    }
                    val prepared = command(JSONObject().put("action", "prepare_type"))
                    check(prepared.optBoolean("accepted")) {
                        "无法建立后台屏文字输入的画面核验屏障"
                    }
                    // Direct Mika Accuracy.typeAndVerify port: write, await
                    // local input settling, read exact field back and retry
                    // ACTION_SET_TEXT once. This never targets a message box,
                    // never alters global clipboard, and costs no model calls.
                    val verified = withContext(Dispatchers.Main) {
                        LuluMikaTextAccuracy.typeAndVerify(
                            field = object : LuluMikaTextAccuracy.TextField {
                                override fun read(): String? =
                                    LuluAccessibilityService.readVirtualSearchFieldText(id, activeApp)
                                override fun write(text: String): Boolean =
                                    LuluAccessibilityService.typeOnDisplay(id, activeApp, text)
                            },
                            text = value,
                            attempts = 2,
                            settle = { kotlinx.coroutines.delay(100) },
                        )
                    }
                    JSONObject().put("accepted",
                        verified != LuluMikaTextAccuracy.Verification.MISMATCH)
                        .put("inputVerification", verified.wire)
                        .put("displayId", id)
                }
                else -> command(request)
            }
            if (action == "launch" && applied?.optBoolean("accepted") == true) activeApp = requestedPackage
            if (applied != null && !applied.optBoolean("accepted") &&
                action !in setOf("type", "focus_search", "tap_element")) {
                return applied.put("mode", "independent_virtual_display")
                    .put("status", "unverified")
                    .put("verification", if (action == "type")
                        "后台 App 没有获得输入焦点或编辑框拒绝文字输入；请先观察真正后台画面、点击搜索框后再尝试，不得声称已搜索"
                        else "该后台操作未被设备接受，不得声称成功").toString()
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
            // A new frame is observed after the action. A successful dispatch
            // is never mistaken for a finished user task.
            val frame = command(JSONObject().put("action", "observe"))
            val uiSettled = if (frame.optBoolean("success") &&
                action in setOf("tap", "tap_element", "swipe", "submit", "back"))
                awaitStableUi(id, activeApp, beforeUiFingerprint) else false
            if (!frame.optBoolean("success")) {
                // Mika can still verify a UI tree when ImageReader has no new
                // frame. Preserve accessibility facts without fabricating pixels.
                val observed = withContext(Dispatchers.Main) {
                    LuluAccessibilityService.observeDisplay(id)
                }
                val owned = observed.connected && observed.packageName == activeApp &&
                    !LuluScreenAwareness.isExcluded(context, observed.packageName)
                if (observed.connected && observed.packageName != activeApp) {
                    lastObservedUi = null
                    return humanOnlyReceipt(id, observed.packageName, "window_changed")
                }
                if (owned) {
                    val human = humanOnlyPage(id, activeApp, observed.visibleText)
                    if (human != null) {
                        lastObservedUi = null
                        return humanOnlyReceipt(id, activeApp, human)
                    }
                }
                val search = if (owned) withContext(Dispatchers.Main) {
                    LuluAccessibilityService.virtualSearchFieldStatus(id, activeApp)
                } else JSONObject()
                val typed = owned && action == "type" && applied?.optBoolean("accepted") == true &&
                    withContext(Dispatchers.Main) {
                        LuluAccessibilityService.typedTextReadbackOnDisplay(
                            id, activeApp, args.getString("text"))
                    }
                val unverified = JSONObject().put("success", false).put("status", "unverified")
                    .put("accepted", applied?.optBoolean("accepted") ?: true)
                    .put("mode", "independent_virtual_display").put("displayId", id)
                    .put("targetWindowVerified", owned)
                    .put("visibleText", if (owned) observed.visibleText.take(5_000) else "")
                    .put("searchEditorStatus", search)
                    .put("inputTextReadbackVerified", typed)
                    .put("inputVerification", applied?.optString("inputVerification").orEmpty())
                    .put("frameValidated", false).put("taskCompleted", false)
                    .put("uiSettled", false)
                    .put("actionMethod", applied?.optString("actionMethod").orEmpty())
                    .put("verification", frame.optString("verification", "后台屏幕暂无可确认画面") +
                        if (owned) "；仍有目标App无障碍控件证据，可选择核实的搜索框操作"
                        else "；目标App窗口未核实，禁止盲点")
                if (!owned) {
                    lastObservedUi = null
                    return unverified.put("effectKind", "unknown").toString()
                }
                attachUiEvidence(unverified, characterId, id, activeApp)
                return unverified.put("effectKind", LuluVirtualUiPolicy.effectKind(
                    action, applied?.optBoolean("accepted") != false,
                    beforeUiFingerprint, lastObservedUi?.fingerprint,
                    typed)).toString()
            }

            val visible = withContext(Dispatchers.Main) {
                LuluAccessibilityService.observeDisplay(id)
            }
            // We must be able to attribute every pixel to a permitted app.
            // A dialog, system permission or other App may appear instead;
            // do not upload it or report it as the intended page.
            if (visible.connected && visible.packageName != activeApp) {
                lastObservedUi = null
                return humanOnlyReceipt(id, visible.packageName, "window_changed")
            }
            if (!visible.connected || visible.packageName != activeApp ||
                LuluScreenAwareness.isExcluded(context, visible.packageName)) {
                return JSONObject().put("success", false).put("status", "unverified")
                    .put("accepted", applied?.optBoolean("accepted") ?: true)
                    .put("mode", "independent_virtual_display").put("displayId", id)
                    .put("verification", "无法核实独立屏幕的前台窗口属于已授权目标应用；已丢弃截图")
                    .also { lastObservedUi = null }.toString()
            }
            val human = humanOnlyPage(id, activeApp, visible.visibleText)
            if (human != null) {
                lastObservedUi = null
                return humanOnlyReceipt(id, activeApp, human)
            }
            val pixels = frame.optString("imageDataUrl")
            val frameFresh = frame.optBoolean("frameFreshAfterAction", false)
            // Pass the owned virtual-display frame only to the same role and
            // exact tool request, regardless of foreground/online entry point.
            // capture_screen remains strictly the owner's main display.
            val hasFrame = frame.optBoolean("frameValidated", false) &&
                frame.optLong("frameTimestampNanos", 0L) > 0L &&
                pixels.startsWith("data:image/jpeg;base64,")
            if (hasFrame) LuluScreenFrameHandoff.put(characterId, requestId, pixels)
            val searchStatus = withContext(Dispatchers.Main) {
                LuluAccessibilityService.virtualSearchFieldStatus(id, activeApp)
            }
            val expected = args.optString("expectedText").trim()
            val textReadbackVerified = if (action == "type" && applied?.optBoolean("accepted") == true) {
                withContext(Dispatchers.Main) {
                    LuluAccessibilityService.typedTextReadbackOnDisplay(
                        id, activeApp, args.getString("text"))
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
                (applied?.optBoolean("accepted") != false) &&
                (action != "type" || textReadbackVerified) &&
                (action != "focus_search" || searchStatus.optBoolean("focusedSearchField"))
            val sharedReceipt = if (args.optBoolean("sendToChat")) {
                if (!hasFrame) JSONObject().put("sentToChat", false)
                    .put("shareError", "后台虚拟屏未获得真实画面，不能发主屏截图替代")
                else try {
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
            val finalReceipt = JSONObject().put("success", observed)
                .put("status", if (observed) "observed" else "unverified")
                .put("actionConditionVerified", actionConditionVerified)
                .put("taskCompleted", false)
                .put("inputTextReadbackVerified", textReadbackVerified)
                .put("inputVerification", applied?.optString("inputVerification").orEmpty())
                .put("targetWindowVerified", true)
                .put("searchEditorStatus", searchStatus)
                .put("uiSettled", uiSettled)
                .put("actionMethod", applied?.optString("actionMethod").orEmpty())
                .put("accepted", applied?.optBoolean("accepted") ?: true)
                .put("mode", "independent_virtual_display")
                .put("displayId", id).put("packageName", visible.packageName)
                .put("visibleText", visible.visibleText.take(5_000))
                .put("imageForwardedToRole", hasFrame)
                .put("frameFreshAfterAction", frameFresh)
                .put("frameValidated", hasFrame)
                .put("frameTimestampNanos", frame.optLong("frameTimestampNanos", 0L))
                .put("newerThanPreviousCapture", frame.optBoolean("newerThanPreviousCapture", false))
                .put("screenSource", "virtual_display")
                .put("sentToChat", sharedReceipt.optBoolean("sentToChat"))
                .put("shareSummary", sharedReceipt.optString("shareSummary"))
                .put("shareError", sharedReceipt.optString("shareError"))
                .put("verification", if (action == "focus_search")
                    (if (searchStatus.optBoolean("focusedSearchField"))
                        "已核实唯一搜索框获得焦点；未输入或提交内容"
                    else "搜索框焦点未能核实；没有点击其他输入框")
                    else if (action == "type" && !textReadbackVerified)
                    "后台搜索文字未能从目标输入框读回，可能未取得焦点或App拒绝输入；此画面仅供本人排查，不能声称已经搜到"
                    else if (action == "type" && observed)
                    "已从后台目标应用输入框读回所输文字；尚未执行搜索提交，实际搜索结果仍需观察"
                    else if (actionConditionVerified)
                    "已观察到后台目标窗口与指定的验证条件；实际业务任务是否完成仍由角色依据画面判断"
                    else if (observed)
                        "只确认输入已派发且取得后台虚拟屏新画面，未证明画面发生变化或业务目标完成"
                    else "未观察到预期文字，动作结果仍未核实；不得称任务完成")
            attachUiEvidence(finalReceipt, characterId, id, activeApp)
            return finalReceipt.put("effectKind", LuluVirtualUiPolicy.effectKind(
                action, applied?.optBoolean("accepted") != false,
                beforeUiFingerprint, lastObservedUi?.fingerprint,
                textReadbackVerified)).toString()
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) {
                forgetTransport()
                throw error
            }
            // An unknown transport/display state must never be retried with
            // an old action ID; release before the next request.
            forgetTransport()
            return JSONObject().put("success", false).put("status", "failed")
                .put("mode", "independent_virtual_display")
                .put("error", error.message.orEmpty().take(220)).toString()
        } finally { mutex.unlock() }
    }
}
