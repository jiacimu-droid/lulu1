package com.jiacimu.lulu

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * A zero-JPEG display path for Android WebView black-compositor devices.
 *
 * Chromium documents presenting hardware-accelerated WebView through a
 * VirtualDisplay/Presentation. Here the VirtualDisplay writes to a SurfaceView
 * directly, so the normal Android screen compositor consumes native Surface
 * buffers without readPixels / toBlob / WebMessage / BitmapFactory each frame.
 *
 * Reuses the already-loaded WebView. The legacy ImageView JPEG overlay remains
 * on screen until a real Surface PixelCopy sees the model; an error restores
 * the same WebView to its original FrameLayout without restarting the call.
 *
 * Does not use MediaProjection or capture any other app's screen.
 */
internal class Live2DGpuSurfacePresentation(
    private val stage: WebView,
    private val host: FrameLayout,
    private val onResult: (Boolean, String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var output: SurfaceView? = null
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var active = false
    private var verified = false
    private var generation = 0
    private var lastWidth = 0
    private var lastHeight = 0

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            mountDisplay(holder)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (!active) return
            if (display == null) mountDisplay(holder)
            else if (width >= 64 && height >= 64 &&
                (width != lastWidth || height != lastHeight)) {
                lastWidth = width
                lastHeight = height
                runCatching {
                    display?.resize(width, height, host.resources.displayMetrics.densityDpi)
                    display?.setSurface(holder.surface)
                }.onFailure { fail("GPU 画面尺寸切换失败") }
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (active) fail("GPU 显示表面已失效，恢复兼容画面")
        }
    }

    fun start() {
        if (active) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onResult(false, "Android 版本不支持 GPU 显示验证")
            return
        }
        if (stage.parent !== host || host.width < 64 || host.height < 64) {
            onResult(false, "视频画布尚未就绪，保持原画质兼容通道")
            return
        }
        active = true
        val surface = SurfaceView(host.context)
        output = surface
        surface.holder.addCallback(surfaceCallback)
        try {
            host.removeView(stage)
            host.addView(surface, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        } catch (_: Exception) {
            fail("GPU 显示窗口初始化失败")
        }
    }

    private fun mountDisplay(holder: SurfaceHolder) {
        if (!active || display != null || !holder.surface.isValid) return
        val surface = output ?: return
        val width = surface.width
        val height = surface.height
        if (width < 64 || height < 64) return
        try {
            val manager = host.context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            val virtual = manager.createVirtualDisplay(
                "Lulu-Live2D-OwnSurface", width, height,
                host.resources.displayMetrics.densityDpi.coerceAtLeast(1),
                holder.surface, flags,
            ) ?: throw IllegalStateException("virtual display unavailable")
            display = virtual
            lastWidth = width
            lastHeight = height
            val window = Presentation(host.context, virtual.display)
            presentation = window
            window.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.rgb(21, 23, 25)))
                setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
            // The WebView is attached to this display's real window, not
            // software-drawn into a Bitmap. Its Pixi ticker stays running.
            window.setContentView(stage)
            window.show()
            probe(++generation, 0)
        } catch (_: Exception) {
            fail("此设备不支持独立 GPU 显示，已恢复原画面")
        }
    }

    private fun probe(token: Int, attempt: Int) {
        if (!active || verified || token != generation) return
        handler.postDelayed({
            if (!active || verified || token != generation) return@postDelayed
            val surface = output
            if (surface == null || !surface.holder.surface.isValid) {
                fail("GPU 表面未就绪，已恢复原画面")
                return@postDelayed
            }
            val image = Bitmap.createBitmap(96, 160, Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(surface, image, { status ->
                    if (!active || token != generation) {
                        image.recycle()
                        return@request
                    }
                    val visible = status == PixelCopy.SUCCESS && runCatching {
                        val pixels = IntArray(image.width * image.height)
                        image.getPixels(pixels, 0, image.width, 0, 0,
                            image.width, image.height)
                        Live2DGpuSurfaceFrameCheck.hasCharacterPixels(pixels)
                    }.getOrDefault(false)
                    image.recycle()
                    if (visible) {
                        verified = true
                        onResult(true, "GPU 原生画面已验证 · 无 JPEG 中转")
                    } else if (attempt < 11) {
                        probe(token, attempt + 1)
                    } else {
                        fail("GPU 通道未检测到人物，已恢复原画面")
                    }
                }, handler)
            } catch (_: Exception) {
                image.recycle()
                if (attempt < 11) probe(token, attempt + 1)
                else fail("GPU 画面读取失败，已恢复原画面")
            }
        }, if (attempt == 0) 450L else 200L)
    }

    private fun fail(reason: String) {
        if (!active) return
        stop()
        onResult(false, reason)
    }

    fun stop() {
        if (!active && output == null && display == null) return
        active = false
        generation++
        // Keep release nonthrowing: the legacy host is the sole fallback.
        runCatching { presentation?.dismiss() }
        presentation = null
        runCatching { (stage.parent as? ViewGroup)?.removeView(stage) }
        runCatching { display?.release() }
        display = null
        output?.let { view ->
            runCatching { view.holder.removeCallback(surfaceCallback) }
            runCatching { host.removeView(view) }
        }
        output = null
        if (stage.parent == null) {
            runCatching {
                host.addView(stage, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ))
            }
        }
    }
}

/** Pure color validator: a black Surface must never pass as a working model. */
internal object Live2DGpuSurfaceFrameCheck {
    fun hasCharacterPixels(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return false
        var colored = 0
        val minimum = maxOf(64, pixels.size / 30)
        for (pixel in pixels) {
            val r = (pixel ushr 16) and 255
            val g = (pixel ushr 8) and 255
            val b = pixel and 255
            if (maxOf(r, g, b) > 65 || maxOf(r, g, b) - minOf(r, g, b) > 22) {
                if (++colored >= minimum) return true
            }
        }
        return false
    }
}
