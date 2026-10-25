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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Visible, draggable session indicator for owner-approved study supervision.
 * The persisted LuluStudyWatch session (not this view) owns authorization.
 * Notifications remain the fallback when Android blocks application overlays.
 */
internal object LuluStudyWatchOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var shownId = ""
    private var window: WindowManager? = null
    private var view: View? = null
    private var ticker: Runnable? = null

    private fun dp(context: Context, n: Int) =
        (context.resources.displayMetrics.density * n + .5f).toInt()

    private fun surface(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            stroke?.let { setStroke(1, it) }
        }

    private fun available(context: Context) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Settings.canDrawOverlays(context)

    fun dismiss(id: String = "") {
        main.post { if (id.isBlank() || shownId == id) clear() }
    }

    private fun clear() {
        ticker?.let(main::removeCallbacks)
        ticker = null
        val old = view
        val manager = window
        view = null
        window = null
        shownId = ""
        if (old != null && manager != null) runCatching { manager.removeViewImmediate(old) }
    }

    fun show(context: Context, session: JSONObject) {
        val app = context.applicationContext
        val id = session.optString("id")
        val role = session.optString("characterId")
        if (id.isBlank()) return
        main.post {
            val actual = LuluStudyWatch.active(app, role)
            if (actual?.optString("id") != id || !available(app)) {
                if (shownId == id) clear()
                return@post
            }
            if (shownId == id && view != null) return@post
            clear()
            try {
                val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val card = LinearLayout(app).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(app, 12), dp(app, 9), dp(app, 12), dp(app, 9))
                    background = surface(Color.WHITE, dp(app, 15), Color.rgb(222, 225, 231))
                    elevation = dp(app, 8).toFloat()
                }
                val header = LinearLayout(app).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
                    TextView(app).apply {
                        this.text = value
                        textSize = size
                        setTextColor(color)
                        if (bold) setTypeface(typeface, Typeface.BOLD)
                        gravity = Gravity.CENTER_VERTICAL
                    }
                val name = LuluStudyWatch.displayName(role)
                val title = text(name + " · 学习监督", 13f, Color.rgb(29, 35, 44), true)
                header.addView(title, LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                val stop = text("结束", 12f, Color.rgb(70, 75, 83), true).apply {
                    setPadding(dp(app, 10), dp(app, 7), dp(app, 4), dp(app, 7))
                    contentDescription = "结束本次屏幕学习监督"
                    setOnClickListener {
                        LuluStudyWatch.stop(app, id, "主人主动停止监督")
                    }
                }
                header.addView(stop)
                card.addView(header)
                val status = text("等待首次查看", 11.5f, Color.rgb(104, 110, 120))
                status.setPadding(0, dp(app, 3), 0, 0)
                card.addView(status)
                val params = WindowManager.LayoutParams(
                    dp(app, 207), WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_SECURE,
                    PixelFormat.TRANSLUCENT).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = (app.resources.displayMetrics.widthPixels - dp(app, 223))
                        .coerceAtLeast(0)
                    y = dp(app, 105)
                }
                var downX = 0f
                var downY = 0f
                var startX = 0
                var startY = 0
                title.setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.rawX
                            downY = event.rawY
                            startX = params.x
                            startY = params.y
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = (startX + (event.rawX - downX).roundToInt())
                                .coerceIn(0, (app.resources.displayMetrics.widthPixels - dp(app, 207)).coerceAtLeast(0))
                            params.y = (startY + (event.rawY - downY).roundToInt())
                                .coerceIn(0, (app.resources.displayMetrics.heightPixels - dp(app, 130)).coerceAtLeast(0))
                            runCatching { manager.updateViewLayout(card, params) }
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                        else -> false
                    }
                }
                manager.addView(card, params)
                window = manager
                view = card
                shownId = id
                lateinit var tick: Runnable
                tick = Runnable {
                    if (shownId != id) return@Runnable
                    val current = LuluStudyWatch.active(app, role)
                    if (current?.optString("id") != id) {
                        // The notification and durable authorization must expire too.
                        LuluStudyWatch.stop(app, id, "学习监督时段已结束")
                        clear()
                        return@Runnable
                    }
                    if (!LuluStudyWatch.canObserve(app, role, id)) {
                        LuluStudyWatch.stop(app, id, "屏幕授权或持续通知已关闭")
                        return@Runnable
                    }
                    val remaining = ((current.optLong("endsAt") - System.currentTimeMillis() +
                        59_999L) / 60_000L).coerceAtLeast(0)
                    title.text = name + " · 还剩" + remaining + "分钟"
                    status.text = LuluStudyWatch.overlayStatus(app, id)
                    main.postDelayed(tick, 30_000L)
                }
                ticker = tick
                main.post(tick)
            } catch (failure: Exception) {
                android.util.Log.w("LuluStudyWatchOverlay", "Unable to show supervision overlay", failure)
                clear()
            }
        }
    }
}
