package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.content.Context
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Point
import android.util.DisplayMetrics
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Looper
import android.view.Display
import android.view.Surface
import com.jiacimu.lulu.system.LuluMikaFrameMath
import com.jiacimu.lulu.system.LuluShoppingLinkShare
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Minimal shell-owned display adapter based on Mika AgentDisplay/ShellService.
 * Owner-paired ADB exec transport exclusively; no agent, model or memory.
 * SOURCE: https://github.com/wangzan101/Mika (Apache-2.0), modified for Lulu.
 */
object LuluShellDisplayMain {
    private const val MARK = "LULU_DISPLAY:"
    private const val TRUSTED = 1 shl 10
    private const val OWN_GROUP = 1 shl 11
    private const val ALWAYS_UNLOCKED = 1 shl 12
    private const val OWN_FOCUS = 1 shl 14
    private const val NO_FOCUS_STEAL = 1 shl 16
    private const val TOUCH = 1 shl 6
    private const val OWN_CONTENT = 1 shl 3
    private const val PUBLIC = 1
    private const val ROTATE = 1 shl 7
    private const val DESTROY = 1 shl 8
    private const val NO_TOUCH_FEEDBACK = 1 shl 13

    private var virtual: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val frameBuffer = LuluDisplayFrameBuffer<android.media.Image>()
    private var targetPackage = ""
    private data class PendingMerchantCopy(
        val packageName: String, val previousTimestamp: Long, val startedAt: Long,
    )
    private var pendingMerchantCopy: PendingMerchantCopy? = null
    // Mika sets DISPLAY_IME_POLICY_LOCAL when the separate display is born.
    // Without this, the keyboard may appear on the owner's screen instead
    // of the virtual one even though FLAG_OWN_FOCUS was granted.
    private var localImeConfigured = false
    // Never feed a pre-action ImageReader buffer back to the character as
    // proof that a just-sent gesture changed the background display.
    private var pendingActionFrameBarrierNanos = 0L
    private var pendingActionNeedsFrame = false
    private var lastDeliveredFrameNanos = 0L
    private const val FRAME_WAIT_MS = 1_600L
    private const val FRAME_POLL_MS = 80L
    internal data class ScreenGeometry(val width: Int, val height: Int, val densityDpi: Int)

    /** Use the actual phone viewport for Android layout. Some OEMs apply
     * the physical display DPI to secondary displays even if requested DPI
     * is different; scaling display pixels there would enlarge every app. */
    internal fun chooseGeometry(realWidth: Int, realHeight: Int, realDpi: Int): ScreenGeometry {
        if (realWidth <= 0 || realHeight <= 0 || realDpi <= 0)
            return ScreenGeometry(720, 1280, 320)
        return ScreenGeometry(minOf(realWidth, realHeight), maxOf(realWidth, realHeight), realDpi)
    }

    internal fun captureSize(geometry: ScreenGeometry): Pair<Int, Int> {
        val width = geometry.width.coerceAtMost(720)
        return width to ((geometry.height.toLong() * width + geometry.width / 2) /
            geometry.width).toInt().coerceAtLeast(1)
    }

    private var frameGeometry = ScreenGeometry(720, 1280, 320)
    private val WIDTH: Int get() = frameGeometry.width
    private val HEIGHT: Int get() = frameGeometry.height

    @JvmStatic
    fun main(args: Array<String>) {
        check(android.os.Process.myUid() == 2000) { "ADB shell uid required" }
        check(Build.VERSION.SDK_INT >= 34) { "Android 14+ required for isolated focus" }
        Looper.prepareMainLooper()
        val context = ShellContext.create()
        @SuppressLint("PrivateApi")
        val manager = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)
        val input = System.`in`.bufferedReader()
        try {
            while (true) {
                val raw = input.readLine() ?: break
                val request = try {
                    require(raw.length <= 4000)
                    JSONObject(raw)
                } catch (_: Exception) {
                    reply(JSONObject().put("success", false).put("error", "invalid command"))
                    continue
                }
                val response = runCatching { execute(manager, request) }.getOrElse {
                    JSONObject().put("success", false)
                        .put("error", (it.message ?: it.javaClass.simpleName).take(180))
                }
                reply(response)
                if (request.optString("action") == "stop") break
            }
        } finally { release() }
    }

    private fun reply(value: JSONObject) {
        System.out.println(MARK + value.toString())
        System.out.flush()
    }

    private fun flags(): Int = PUBLIC or OWN_CONTENT or TOUCH or ROTATE or DESTROY or
        NO_TOUCH_FEEDBACK or TRUSTED or OWN_GROUP or ALWAYS_UNLOCKED or OWN_FOCUS or NO_FOCUS_STEAL

    @SuppressLint("PrivateApi")
    private fun setLocalImePolicy(displayId: Int): Boolean = runCatching {
        val wm = Class.forName("android.view.WindowManagerGlobal")
            .getMethod("getWindowManagerService").invoke(null)!!
        // WindowManager.DISPLAY_IME_POLICY_LOCAL = 0.
        wm.javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java)
            .invoke(wm, displayId, 0)
        true
    }.getOrDefault(false)

    private fun execute(dm: DisplayManager, cmd: JSONObject): JSONObject {
        val action = cmd.optString("action")
        if (action == "stop") {
            release()
            return JSONObject().put("success", true).put("status", "stopped")
        }
        if (action == "start") {
            if (virtual == null) {
                val main = dm.getDisplay(Display.DEFAULT_DISPLAY)
                val metrics = DisplayMetrics()
                val real = Point()
                val geometry = runCatching {
                    checkNotNull(main) { "main display unavailable" }
                    main.getRealSize(real)
                    main.getRealMetrics(metrics)
                    val ownerDpi = cmd.optInt("ownerDensityDpi")
                        .takeIf { it in 72..1_200 } ?: metrics.densityDpi
                    chooseGeometry(real.x, real.y, ownerDpi)
                }.getOrDefault(ScreenGeometry(720, 1280, 320))
                val img = ImageReader.newInstance(
                    geometry.width, geometry.height, PixelFormat.RGBA_8888, 3)
                val disp = try {
                    dm.createVirtualDisplay("lulu-companion", geometry.width, geometry.height, geometry.densityDpi,
                        img.surface, flags())
                } catch (e: Exception) {
                    img.close()
                    throw IllegalStateException("系统不支持可信独立焦点虚拟屏，禁止退回主屏", e)
                }
                check(disp.display.displayId > 0) {
                    disp.release()
                    img.close()
                    "virtual display id must not be 0"
                }
                // Mika's self-check reads the REAL Display flags. Requesting
                // OWN_FOCUS in createVirtualDisplay is not proof Android
                // granted it; some OEMs silently drop that capability.
                val ownFocusDisplayFlag = runCatching {
                    Display::class.java.getField("FLAG_OWN_FOCUS").getInt(null)
                }.getOrDefault(1 shl 11)
                if (disp.display.flags and ownFocusDisplayFlag == 0) {
                    val observedFlags = disp.display.flags
                    disp.release()
                    img.close()
                    error("系统未授予后台屏独立焦点（displayFlags=$observedFlags）；拒绝抢占主人主屏")
                }
                // Equivalent to Mika ShellService.createVirtualDisplay().
                // The virtual phone owns its IME; keyboard/focus must never
                // spill onto the owner's physical display.
                localImeConfigured = setLocalImePolicy(disp.display.displayId)
                frameGeometry = geometry
                virtual = disp
                reader = img
            }
            return JSONObject().put("success", true)
                .put("displayId", virtual!!.display.displayId)
                .put("width", WIDTH).put("height", HEIGHT)
                .put("densityDpi", frameGeometry.densityDpi)
                .put("captureWidth", captureSize(frameGeometry).first)
                .put("captureHeight", captureSize(frameGeometry).second)
                .put("ownFocus", true)
                .put("ownFocusVerifiedByDisplayFlags", true)
                .put("localImePolicyRequested", localImeConfigured)
        }
        val id = virtual?.display?.displayId ?: error("start virtual display first")
        check(id > 0) { "cannot inject into main display" }
        // One-shot, same-session handoff. Unrelated commands invalidate the marker.
        if (action != "prepare_product_copy" && action != "read_product_copy")
            pendingMerchantCopy = null
        if (action == "prepare_product_copy") {
            check(cmd.optString("packageName") == targetPackage && targetPackage.isNotBlank()) {
                "merchant copy target changed"
            }
            check(LuluShoppingLinkShare.merchantName(targetPackage) != null) {
                "not a supported merchant copy source"
            }
            val previous = LuluShellClipboard(ShellContext.create()).descriptionTimestamp()
            pendingMerchantCopy = PendingMerchantCopy(
                targetPackage, previous, System.currentTimeMillis())
            return JSONObject().put("accepted", true).put("displayId", id)
                .put("targetPackage", targetPackage)
                .put("actionMethod", "shell_merchant_copy_marker")
        }
        if (action == "read_product_copy") {
            val pending = pendingMerchantCopy
            pendingMerchantCopy = null
            if (pending == null || pending.packageName != targetPackage ||
                cmd.optString("packageName") != targetPackage)
                return JSONObject().put("accepted", false)
                    .put("errorCode", "merchant_copy_marker_expired")
            val clipboard = LuluShellClipboard(ShellContext.create())
            var reason = "merchant_copy_not_observed"
            repeat(12) {
                Thread.sleep(150)
                val current = clipboard.descriptionTimestamp()
                if (!LuluShoppingLinkShare.freshCopyEvent(
                        pending.previousTimestamp, pending.startedAt, current)) return@repeat
                val source = clipboard.sourcePackage()
                if (source != null && source != targetPackage)
                    return JSONObject().put("accepted", false)
                        .put("errorCode", "merchant_copy_source_mismatch")
                // Read CONTENT only after this newly observed copy event.
                // Unverified text stays in shell and never reaches the role.
                val clip = runCatching { clipboard.snapshot() }.getOrNull()
                if (clip == null || clip.itemCount != 1) {
                    reason = "merchant_copy_unreadable"
                    return@repeat
                }
                val link = LuluShoppingLinkShare.merchantCopyLink(
                    targetPackage, pending.previousTimestamp, pending.startedAt,
                    current, source, clip.getItemAt(0).text?.toString())
                if (link != null) return JSONObject().put("accepted", true)
                    .put("displayId", id).put("targetPackage", targetPackage)
                    .put("actionMethod", "shell_verified_merchant_clipboard")
                    .put("link", link)
                reason = "merchant_copy_not_merchant_link"
            }
            return JSONObject().put("accepted", false).put("errorCode", reason)
                .put("displayId", id).put("targetPackage", targetPackage)
        }
        if (action == "adopt_app") {
            val pkg = cmd.getString("packageName")
            check(pkg.matches(Regex("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+"))) { "invalid adopted package" }
            // No launch, task migration or input. The app adapter separately
            // proves this package is already topmost on this owned display.
            targetPackage = pkg
            return JSONObject().put("accepted", true).put("displayId", id)
                .put("targetPackage", pkg).put("actionMethod", "adopt_existing_virtual_window")
        }
        if (action == "observe") return screenFrame(id, cmd.optBoolean("fullResolution"))
        // ACTION_SET_TEXT happens in Lulu's accessibility process, not this
        // shell helper. Mark its frame barrier BEFORE dispatch so a buffered
        // pre-type page cannot be returned as evidence that input succeeded.
        if (action == "prepare_type") {
            check(targetPackage.isNotEmpty()) { "no target app on virtual screen" }
            pendingActionFrameBarrierNanos = discardPreActionFrames()
            pendingActionNeedsFrame = true
            return JSONObject().put("success", false).put("accepted", true)
                .put("displayId", id).put("targetPackage", targetPackage)
                .put("verification", "input frame barrier prepared")
        }
        if (action == "launch") {
            val pkg = cmd.optString("packageName")
            check(pkg.matches(Regex("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+"))) {
                "invalid package name"
            }
            // Android's resolved exported launcher component is selected by
            // Lulu's owner-authorized local package manager. Launching a bare
            // package-filtered intent can fail or open a chooser instead of
            // the selected app on this display.
            val component = ComponentName.unflattenFromString(
                cmd.optString("launchComponent")) ?: error("missing explicit launcher component")
            check(component.packageName == pkg) { "launcher package mismatch" }
            val frameBarrier = discardPreActionFrames()
            // A frame belonging to a previous app must never survive a launch.
            if (pkg != targetPackage) frameBuffer.close()
            val launchResult = run(listOf("am", "start", "--display", id.toString(),
                "-n", component.flattenToString(),
                "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.LAUNCHER"))
            if (launchResult.accepted) {
                targetPackage = pkg
                pendingActionFrameBarrierNanos = frameBarrier
                pendingActionNeedsFrame = true
            }
            return JSONObject().put("success", false).put("accepted", launchResult.accepted)
                .put("displayId", id).put("targetPackage", pkg)
                .put("errorCode", if (launchResult.accepted) "" else "shell_launch_rejected")
                .put("error", if (launchResult.accepted) "" else launchResult.failure)
                .put("verification", if (launchResult.accepted)
                    "launch dispatched; observe actual screen next"
                    else "虚拟屏启动应用被系统拒绝：${launchResult.failure}")
        }
        check(targetPackage.isNotEmpty()) { "no target app on virtual screen" }
        if (action == "back") {
            val requested = cmd.getString("packageName")
            check(requested == targetPackage) { "back target package changed" }
            pendingActionFrameBarrierNanos = discardPreActionFrames()
            pendingActionNeedsFrame = true
            return LuluShellTextInput.back(ShellContext.create(), id, requested, targetPackage)
        }
        if (action == "tap" || action == "long_press") {
            check(cmd.optString("packageName") == targetPackage) { "touch target package changed" }
            pendingActionFrameBarrierNanos = discardPreActionFrames()
            pendingActionNeedsFrame = true
            return LuluShellTouchInput.touch(ShellContext.create(), id, targetPackage, targetPackage,
                cmd.getInt("x"), cmd.getInt("y"), WIDTH, HEIGHT,
                if (action == "long_press") cmd.optInt("durationMs", android.view.ViewConfiguration.getLongPressTimeout() + 100)
                else (android.view.ViewConfiguration.getTapTimeout() / 2).coerceAtLeast(1))
        }
        if (action == "paste_text") {
            // Narrowly scoped internal channel: caller proves the exact
            // recipient using the independent display's pixels, taps a
            // model-selected composer point, and rechecks authorization.
            // The shell enforces its own display id, launched package and UID;
            // no shell 'input -d', display 0, or generic keyboard fallback.
            check(cmd.optString("packageName") == targetPackage) {
                "paste package does not match launched app"
            }
            pendingActionFrameBarrierNanos = discardPreActionFrames()
            pendingActionNeedsFrame = true
            return LuluShellTextInput.paste(ShellContext.create(), id,
                cmd.optString("packageName"), targetPackage, cmd.optString("text"))
        }
        // Only this helper-owned display, UID-targeted touch/Unicode and frames
        // belong to shell. No generic keyevents or main-display fallback.
        // Shell input -d is not portable: on some Android builds it requires
        // INJECT_EVENTS, which cannot be granted through the app UI. The
        // session now dispatches all input to the owned display via
        // AccessibilityService; deliberately refuse any future shell fallback.
        return JSONObject().put("success", false).put("accepted", false)
            .put("displayId", id).put("targetPackage", targetPackage)
            .put("errorCode", "shell_input_disabled")
            .put("verification", "输入操作必须由独立虚拟屏无障碍手势或控件执行；Shell不会注入触控和按键")
    }

    private data class ShellDispatch(val accepted: Boolean, val failure: String = "")

    /** Android's am sometimes exits 0 while printing "Error: Activity class
     * does not exist". Never claim the launch was accepted in that case. */
    internal fun shellCommandSucceeded(exitCode: Int, output: String): Boolean =
        exitCode == 0 && !Regex(
            "(?im)^\\s*(?:Error:|Error type\\s+\\d+|Exception occurred|" +
                "SecurityException:|Permission Denial:|Failure \\[)"
        ).containsMatchIn(output)

    private fun run(args: List<String>): ShellDispatch {
        val child = ProcessBuilder(args).redirectErrorStream(true).start()
        val ended = child.waitFor(7, TimeUnit.SECONDS)
        if (!ended) {
            child.destroyForcibly()
            child.waitFor(1, TimeUnit.SECONDS)
        }
        // All commands are fixed shell actions without user text; preserve the
        // bounded Android failure detail, never the user's screen or chat body.
        val output = runCatching {
            child.inputStream.bufferedReader().use { it.readText().take(400) }
        }.getOrDefault("")
        val exitCode = if (ended) child.exitValue() else -1
        val accepted = ended && shellCommandSucceeded(exitCode, output)
        val diagnostic = when {
            accepted -> ""
            !ended -> "shell subprocess timed out"
            output.isNotBlank() -> "exit=$exitCode；${output.replace(10.toChar(), ' ').take(280)}"
            else -> "shell subprocess exit=$exitCode"
        }
        return ShellDispatch(accepted, diagnostic)
    }

    /**
     * Drain queued frames BEFORE input and retain the last rendered page.
     * Its producer timestamp is the barrier, never proof of post-input change.
     */
    private fun discardPreActionFrames(): Long {
        // Establish the barrier without consuming the only rendered page.
        val timestamp = frameBuffer.latest { reader?.acquireLatestImage() }?.timestamp ?: 0L
        return LuluDisplayFrameFreshness.barrier(lastDeliveredFrameNanos, timestamp)
    }

    private fun screenFrame(displayId: Int, fullResolution: Boolean = false): JSONObject {
        val r = reader ?: error("display buffer missing")
        val barrier = pendingActionFrameBarrierNanos
        val afterAction = pendingActionNeedsFrame
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FRAME_WAIT_MS)
        var acquired: android.media.Image? = null
        while (System.nanoTime() < deadline) {
            val next = frameBuffer.latest { r.acquireLatestImage() }
            if (next != null) {
                if (LuluDisplayFrameFreshness.accepts(next.timestamp, barrier)) {
                    acquired = next
                    break
                }
                // Keep the current rendered page for observers, while waiting
                // for a genuinely post-action producer timestamp.
            }
            Thread.sleep(FRAME_POLL_MS)
        }
        val pixels = acquired ?: frameBuffer.latest { null } ?: run {
            return JSONObject().put("success", false)
                .put("status", "unverified").put("displayId", displayId)
                .put("frameFreshAfterAction", false)
                .put("verification", if (barrier > 0L)
                    "动作后尚无可读取帧；保持图像屏障，不能以旧截图证明执行成功"
                    else "独立虚拟屏尚未产生可读取的图像")
        }
        run {
            val timestamp = pixels.timestamp
            val freshAfterAction = afterAction &&
                LuluDisplayFrameFreshness.accepts(timestamp, barrier)
            val newerThanLast = LuluDisplayFrameFreshness.isNewer(
                timestamp, lastDeliveredFrameNanos)
            val plane = pixels.planes[0]
            val w = plane.rowStride / plane.pixelStride
            val full = Bitmap.createBitmap(w, pixels.height, Bitmap.Config.ARGB_8888)
            try {
                plane.buffer.rewind()
                full.copyPixelsFromBuffer(plane.buffer)
                // Offline OCR needs the actual glyph pixels, not the 720px
                // model preview recompressed at low quality.
                val (captureWidth, captureHeight) = if (fullResolution)
                    WIDTH to HEIGHT else captureSize(frameGeometry)
                val scaled = Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(scaled).drawBitmap(full, Rect(0, 0, WIDTH, HEIGHT),
                        Rect(0, 0, captureWidth, captureHeight), Paint(Paint.FILTER_BITMAP_FLAG))
                    // Mika FrameMath: an accepted gesture can produce a NEW
                    // all-black virtual frame (compositor/security failure).
                    // This is not a readable screenshot; never send it to the
                    // character or silently use the owner's main display.
                    val allBlack = LuluMikaFrameMath.isBlack(captureWidth, captureHeight) { x, y ->
                        scaled.getPixel(x, y)
                    }
                    if (allBlack) {
                        lastDeliveredFrameNanos = timestamp
                        pendingActionFrameBarrierNanos = 0L
                        pendingActionNeedsFrame = false
                        return JSONObject().put("success", false)
                            .put("status", "unverified").put("displayId", displayId)
                            .put("frameBlack", true).put("frameValidated", false)
                            .put("frameTimestampNanos", timestamp)
                            .put("frameFreshAfterAction", freshAfterAction)
                            .put("verification", "后台独立屏产生了全黑新帧，无法当作可读画面；仍可仅使用目标App控件树的安全证据")
                    }
                    val bytes = ByteArrayOutputStream().use { buffer ->
                        check(scaled.compress(Bitmap.CompressFormat.JPEG,
                            if (fullResolution) 95 else 65, buffer))
                        buffer.toByteArray()
                    }
                    check(bytes.size <= if (fullResolution) 4_000_000 else 700_000) { "image buffer too large" }
                    lastDeliveredFrameNanos = timestamp
                    if (!afterAction || freshAfterAction) {
                        pendingActionFrameBarrierNanos = 0L
                        pendingActionNeedsFrame = false
                    }
                    return JSONObject().put("success", true).put("status", "captured")
                        .put("displayId", displayId).put("targetPackage", targetPackage)
                        .put("frameTimestampNanos", timestamp)
                        .put("frameValidated", true)
                        .put("displayWidth", WIDTH).put("displayHeight", HEIGHT)
                        .put("captureWidth", captureWidth).put("captureHeight", captureHeight)
                        .put("frameFreshAfterAction", freshAfterAction)
                        .put("actionFrameUnverified", afterAction && !freshAfterAction)
                        .put("verification", if (afterAction && !freshAfterAction)
                            "当前仅有动作前的最后渲染画面；可查看，不能证明动作生效或用于发送核验"
                            else "已取得独立屏当前渲染画面；重复读取不等于页面变化")
                        .put("newerThanPreviousCapture", newerThanLast)
                        .put("imageDataUrl", "data:image/jpeg;base64," +
                            Base64.getEncoder().encodeToString(bytes))
                } finally { scaled.recycle() }
            } finally { full.recycle() }
        }
    }

    private fun release() {
        pendingMerchantCopy = null
        localImeConfigured = false
        targetPackage = ""
        pendingActionFrameBarrierNanos = 0L
        pendingActionNeedsFrame = false
        lastDeliveredFrameNanos = 0L
        runCatching { frameBuffer.close() }
        runCatching { virtual?.release() }
        runCatching { reader?.close() }
        virtual = null
        reader = null
        frameGeometry = ScreenGeometry(720, 1280, 320)
    }
}
