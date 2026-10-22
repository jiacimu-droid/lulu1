package com.jiacimu.lulu.system

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A small, user-operated overlay. Never captures the screen or opens the app
 * on its own. The already-persisted request and the system notification remain
 * authoritative if Android refuses overlays or this process is killed.
 */
internal object LuluScreenRequestPopup {
    private val main = Handler(Looper.getMainLooper())
    private var activeKey: String? = null
    private var activeView: View? = null
    private var activeManager: WindowManager? = null

    fun available(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Settings.canDrawOverlays(context)

    fun showCapture(context: Context, role: String, id: String,
        stillPending: () -> Boolean, onAllow: () -> Unit, onDeny: () -> Unit): Boolean =
        show(context, "capture:$id", "请求查看屏幕", "$role 想看一眼你现在的屏幕",
            "只查看当前一帧。点击允许后才会截图并交给识图模型。",
            listOf("拒绝" to onDeny, "允许一次" to onAllow),
            120_000L, stillPending)

    fun showReturn(context: Context, role: String,
        onReturn: () -> Unit, onLater: () -> Unit): Boolean =
        show(context, "summon", "请求返回露露机", "$role 想邀请你回来",
            "你可以继续忙自己的事，想回来时再点开。",
            listOf("稍后" to onLater, "返回露露机" to onReturn), 25_000L)

    fun showProgress(context: Context, text: String): Boolean =
        show(context, "screen-progress", "正在查看屏幕", text,
            "本次只读取一帧，不会持续录屏。",
            emptyList(), 120_000L)

    fun dismissCapture(id: String) = dismiss("capture:$id")
    fun dismissProgress() = dismiss("screen-progress")
    fun dismissReturn() = dismiss("summon")

    fun dismiss(key: String) {
        main.post { if (activeKey == key) dismissNow() }
    }

    private fun dismissNow() {
        val view = activeView
        val manager = activeManager
        activeKey = null
        activeView = null
        activeManager = null
        if (view != null && manager != null) {
            runCatching { manager.removeViewImmediate(view) }
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (context.resources.displayMetrics.density * value + 0.5f).toInt()

    private fun rounded(color: Int, corner: Float, borderColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = corner
            borderColor?.let { setStroke(1, it) }
        }

    private fun show(context: Context, key: String, title: String, subtitle: String,
        details: String, actions: List<Pair<String, () -> Unit>>, duration: Long,
        valid: () -> Boolean = { true }): Boolean {
        if (!available(context)) return false
        val app = context.applicationContext
        main.post {
            if (!available(app) || !valid()) return@post
            try {
                dismissNow()
                val window = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val card = LinearLayout(app).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(app, 18), dp(app, 15), dp(app, 18), dp(app, 15))
                    background = rounded(Color.WHITE, dp(app, 20).toFloat(), Color.rgb(226, 227, 231))
                    elevation = dp(app, 12).toFloat()
                }
                fun line(value: String, size: Float, color: Int, strong: Boolean,
                    top: Int): TextView = TextView(app).apply {
                    text = value
                    textSize = size
                    setTextColor(color)
                    if (strong) setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(app, top), 0, 0)
                    gravity = Gravity.CENTER_VERTICAL
                }
                card.addView(line("✧  $title", 16f, Color.rgb(28, 30, 34), true, 0))
                card.addView(line(subtitle, 14f, Color.rgb(44, 46, 52), false, 7))
                card.addView(line(details, 12f, Color.rgb(110, 114, 123), false, 4))
                if (actions.isNotEmpty()) {
                    val buttons = LinearLayout(app).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END
                        setPadding(0, dp(app, 14), 0, 0)
                    }
                    actions.forEachIndexed { index, action ->
                        val affirmative = index == actions.lastIndex
                        val button = TextView(app).apply {
                            text = action.first
                            textSize = 13.5f
                            gravity = Gravity.CENTER
                            setPadding(dp(app, 16), dp(app, 11), dp(app, 16), dp(app, 11))
                            setTextColor(if (affirmative) Color.WHITE else Color.rgb(43, 45, 49))
                            background = rounded(
                                if (affirmative) Color.rgb(43, 45, 49) else Color.rgb(241, 242, 244),
                                dp(app, 12).toFloat())
                            isClickable = true
                            setOnClickListener {
                                if (activeKey != key) return@setOnClickListener
                                dismissNow()
                                action.second.invoke()
                            }
                        }
                        val margin = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT)
                        if (index > 0) margin.leftMargin = dp(app, 9)
                        buttons.addView(button, margin)
                    }
                    card.addView(buttons)
                }
                val width = (app.resources.displayMetrics.widthPixels - dp(app, 32))
                    .coerceAtLeast(dp(app, 250))
                val params = WindowManager.LayoutParams(
                    width, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_SECURE,
                    PixelFormat.TRANSLUCENT).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dp(app, 58)
                    // Only the card consumes touches. Other apps remain usable.
                    windowAnimations = android.R.style.Animation_Dialog
                }
                window.addView(card, params)
                activeView = card
                activeManager = window
                activeKey = key
                main.postDelayed({ if (activeKey == key) dismissNow() }, duration)
            } catch (_: Exception) {
                // Android may reject overlays while its security/permission UI is active.
                // Caller already created a system notification fallback where possible.
                dismissNow()
            }
        }
        return true
    }
}
