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

    /** Only a direct user action can re-enable future background phone sessions. */
    fun resumeByOwner() { stoppedByOwner = false }
    fun ownerHasPaused(): Boolean = stoppedByOwner
    private var adb: AdbConnection? = null
    private var stream: AdbConnection.AdbStream? = null
    private var displayId = -1
    private var activeApp = ""
    private var ownerCharacterId = ""
    private var appContext: Context? = null
    private val replies = LinkedBlockingQueue<String>()

    fun connected(): Boolean = stream != null && displayId > 0 && adb?.isClosed == false

    private fun forgetTransport() {
        appContext?.let { runCatching { LuluBackgroundPhoneNotice.clear(it) } }
        appContext = null
        displayId = -1
        activeApp = ""
        ownerCharacterId = ""
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

    suspend fun stop(): Boolean {
        // Set immediately, BEFORE waiting for another in-flight tool to
        // release the session mutex. Later model steps cannot undo STOP.
        stoppedByOwner = true
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
            check(!stoppedByOwner) {
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
            check(action in setOf("start", "launch", "tap", "swipe", "back", "type", "observe", "stop")) {
                "不支持此后台屏幕操作"
            }
            if (action == "stop") {
                if (!connected()) return JSONObject().put("success", true)
                    .put("status", "stopped").put("verification", "后台屏幕本来没有运行").toString()
                return try {
                    command(JSONObject().put("action", "stop"))
                        .put("verification", "后台屏幕已收到关闭请求").toString()
                } finally { forgetTransport() }
            }
            val requestedPackage = args.optString("packageName").trim()
            if (action == "launch") {
                check(requestedPackage.isNotEmpty()) { "必须指定准确的应用包名" }
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
                .put("status", "observed").put("mode", "independent_virtual_display")
                .put("displayId", id).put("verification", "shell 已创建独立焦点屏幕；不代表已启动其他 App").toString()

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
                check(before.connected && before.packageName == activeApp &&
                    !LuluScreenAwareness.isExcluded(context, before.packageName)) {
                    "屏幕内容已变更或目标窗口无法核实；为了避免错点，拒绝这一步"
                }
            }
            if (action == "tap") {
                val x = args.getInt("x")
                val y = args.getInt("y")
                val sensitive = withContext(Dispatchers.Main) {
                    LuluAccessibilityService.isSensitiveVirtualTap(
                        id, activeApp, x, y, 720, 1280)
                }
                check(!sensitive) {
                    "检测到付款、发送、发布、删除或无法核实的敏感点击目标；拒绝自动操作"
                }
            }
            val request = JSONObject().put("action", action)
            if (action == "launch") request.put("packageName", requestedPackage)
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
                "type" -> {
                    val value = args.getString("text")
                    require(value.isNotEmpty() && value.length <= 500) {
                        "每次只允许输入1到500个字符"
                    }
                    val accepted = withContext(Dispatchers.Main) {
                        LuluAccessibilityService.typeOnDisplay(id, activeApp, value)
                    }
                    JSONObject().put("accepted", accepted).put("displayId", id)
                }
                else -> command(request)
            }
            if (action == "launch" && applied?.optBoolean("accepted") == true) activeApp = requestedPackage
            if (applied != null && !applied.optBoolean("accepted")) {
                return applied.put("mode", "independent_virtual_display")
                    .put("status", "unverified").toString()
            }
            // A new frame is observed after the action. A successful dispatch
            // is never mistaken for a finished user task.
            val frame = command(JSONObject().put("action", "observe"))
            if (!frame.optBoolean("success")) return JSONObject()
                .put("success", false).put("status", "unverified")
                .put("accepted", applied?.optBoolean("accepted") ?: true)
                .put("mode", "independent_virtual_display").put("displayId", id)
                .put("verification", frame.optString("verification", "后台屏幕暂无可确认画面"))
                .toString()

            val visible = withContext(Dispatchers.Main) {
                LuluAccessibilityService.observeDisplay(id)
            }
            // We must be able to attribute every pixel to a permitted app.
            // A dialog, system permission or other App may appear instead;
            // do not upload it or report it as the intended page.
            if (!visible.connected || visible.packageName != activeApp ||
                LuluScreenAwareness.isExcluded(context, visible.packageName)) {
                return JSONObject().put("success", false).put("status", "unverified")
                    .put("accepted", applied?.optBoolean("accepted") ?: true)
                    .put("mode", "independent_virtual_display").put("displayId", id)
                    .put("verification", "无法核实独立屏幕的前台窗口属于已授权目标应用；已丢弃截图")
                    .toString()
            }
            val pixels = frame.optString("imageDataUrl")
            if (pixels.startsWith("data:image/jpeg;base64,") && requestId.startsWith("reply-")) {
                LuluScreenFrameHandoff.put(characterId, requestId, pixels)
            }
            val expected = args.optString("expectedText").trim()
            val verified = action == "observe" ||
                (action == "launch" && visible.packageName == requestedPackage) ||
                (expected.isNotBlank() && visible.visibleText.contains(expected))
            return JSONObject().put("success", verified)
                .put("status", if (verified) "observed" else "unverified")
                .put("accepted", applied?.optBoolean("accepted") ?: true)
                .put("mode", "independent_virtual_display")
                .put("displayId", id).put("packageName", visible.packageName)
                .put("visibleText", visible.visibleText.take(5_000))
                .put("imageForwardedToRole", requestId.startsWith("reply-") && pixels.isNotBlank())
                .put("verification", if (verified)
                    "已核实独立屏幕的目标应用与可见控件；实际业务结果仍按目标条件判断"
                    else "只确认输入派发和实际画面；尚未观察到预期文字，不能标记任务完成")
                .toString()
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
