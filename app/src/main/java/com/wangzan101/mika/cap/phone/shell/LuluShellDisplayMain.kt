package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Looper
import android.view.Surface
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
    private var targetPackage = ""
    private const val WIDTH = 720
    private const val HEIGHT = 1280

    @JvmStatic
    fun main(args: Array<String>) {
        check(android.os.Process.myUid() == 2000) { "ADB shell uid required" }
        check(Build.VERSION.SDK_INT >= 34) { "Android 14+ required for isolated focus" }
        Looper.prepareMainLooper()
        val context = ShellContext.create()
        @SuppressLint("PrivateApi")
        val manager = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)
        val input = System.in.bufferedReader()
        try {
            while (true) {
                val raw = input.readLine() ?: break
                val request = runCatching {
                    require(raw.length <= 4000)
                    JSONObject(raw)
                }.getOrElse {
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

    private fun execute(dm: DisplayManager, cmd: JSONObject): JSONObject {
        val action = cmd.optString("action")
        if (action == "stop") {
            release()
            return JSONObject().put("success", true).put("status", "stopped")
        }
        if (action == "start") {
            if (virtual == null) {
                val img = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 3)
                val disp = try {
                    dm.createVirtualDisplay("lulu-companion", WIDTH, HEIGHT, 320,
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
                virtual = disp
                reader = img
            }
            return JSONObject().put("success", true)
                .put("displayId", virtual!!.display.displayId)
                .put("width", WIDTH).put("height", HEIGHT).put("ownFocus", true)
        }
        val id = virtual?.display?.displayId ?: error("start virtual display first")
        check(id > 0) { "cannot inject into main display" }
        if (action == "observe") return screenFrame(id)
        if (action == "launch") {
            val pkg = cmd.optString("packageName")
            check(pkg.matches(Regex("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+"))) {
                "invalid package name"
            }
            val ok = run(listOf("am", "start", "--display", id.toString(),
                "-a", "android.intent.action.MAIN", "-c",
                "android.intent.category.LAUNCHER", "-p", pkg))
            if (ok) targetPackage = pkg
            return JSONObject().put("success", false).put("accepted", ok)
                .put("displayId", id).put("targetPackage", pkg)
                .put("verification", "launch dispatched; observe actual screen next")
        }
        check(targetPackage.isNotEmpty()) { "no target app on virtual screen" }
        val args = when (action) {
            "tap" -> listOf("input", "-d", id.toString(), "tap",
                point(cmd, "x", WIDTH).toString(), point(cmd, "y", HEIGHT).toString())
            "swipe" -> listOf("input", "-d", id.toString(), "swipe",
                point(cmd, "startX", WIDTH).toString(), point(cmd, "startY", HEIGHT).toString(),
                point(cmd, "endX", WIDTH).toString(), point(cmd, "endY", HEIGHT).toString(),
                cmd.optInt("durationMs", 400).coerceIn(180, 1600).toString())
            "back" -> listOf("input", "-d", id.toString(), "keyevent", "KEYCODE_BACK")
            else -> error("unsupported virtual display operation")
        }
        val sent = run(args)
        return JSONObject().put("success", false).put("accepted", sent)
            .put("displayId", id).put("targetPackage", targetPackage)
            .put("verification", "input sent only to virtual display; completion unverified")
    }

    private fun point(json: JSONObject, key: String, dimension: Int): Int {
        val n = json.getInt(key)
        require(n in 0..1000) { "normalized coordinate out of bounds" }
        return ((dimension - 1L) * n / 1000).toInt()
    }

    private fun run(args: List<String>): Boolean {
        val child = ProcessBuilder(args).redirectErrorStream(true).start()
        val ended = child.waitFor(7, TimeUnit.SECONDS)
        if (!ended) child.destroyForcibly()
        return ended && child.exitValue() == 0
    }

    private fun screenFrame(displayId: Int): JSONObject {
        val r = reader ?: error("display buffer missing")
        var image = r.acquireLatestImage()
        if (image == null) {
            Thread.sleep(300)
            image = r.acquireLatestImage()
        }
        val pixels = image ?: return JSONObject().put("success", false)
            .put("status", "unverified").put("displayId", displayId)
            .put("verification", "virtual display has no frame yet")
        try {
            val plane = pixels.planes[0]
            val w = plane.rowStride / plane.pixelStride
            val full = Bitmap.createBitmap(w, pixels.height, Bitmap.Config.ARGB_8888)
            try {
                plane.buffer.rewind()
                full.copyPixelsFromBuffer(plane.buffer)
                val crop = Bitmap.createBitmap(full, 0, 0, WIDTH, HEIGHT)
                try {
                    val bytes = ByteArrayOutputStream().use { buffer ->
                        check(crop.compress(Bitmap.CompressFormat.JPEG, 65, buffer))
                        buffer.toByteArray()
                    }
                    check(bytes.size <= 700_000) { "image buffer too large" }
                    return JSONObject().put("success", true).put("status", "captured")
                        .put("displayId", displayId).put("targetPackage", targetPackage)
                        .put("imageDataUrl", "data:image/jpeg;base64," +
                            Base64.getEncoder().encodeToString(bytes))
                } finally { crop.recycle() }
            } finally { full.recycle() }
        } finally { pixels.close() }
    }

    private fun release() {
        targetPackage = ""
        runCatching { virtual?.release() }
        runCatching { reader?.close() }
        virtual = null
        reader = null
    }
}
