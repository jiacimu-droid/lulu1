package com.jiacimu.lulu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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

/**
 * Owner-authorized Android overlay for other apps. Never request an invisible
 * permission or create a second audio engine. All controls delegate to the
 * existing app-owned room. Android may remove overlays under system restrictions.
 */
internal object ListenTogetherSystemOverlay {
    private var manager: WindowManager? = null
    private var surface: View? = null
    private var title: TextView? = null
    private var subtitle: TextView? = null
    private var play: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null

    private fun dp(context: Context, value: Int): Int =
        (context.resources.displayMetrics.density * value + .5f).toInt()

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Settings.canDrawOverlays(context)

    fun present(context: Context) {
        val room = SharedMusicWindowController.room ?: return
        if (SharedMusicWindowController.display == SharedMusicWindow.Hidden ||
            !hasPermission(context)) return
        if (surface != null) { refresh(); return }
        val app = context.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val base = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(app, 16), dp(app, 9), dp(app, 16), dp(app, 12))
            background = GradientDrawable().apply {
                setColor(Color.rgb(253, 250, 252))
                cornerRadius = dp(app, 24).toFloat()
                setStroke(dp(app, 1), Color.rgb(226, 210, 220))
            }
            elevation = dp(app, 12).toFloat()
        }
        val header = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun text(value: String, size: Float, color: Int): TextView =
            TextView(app).apply {
                this.text = value
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
            }
        fun action(value: String, hint: String, callback: () -> Unit): TextView =
            text(value, 19f, Color.rgb(55, 45, 58)).apply {
                contentDescription = hint
                gravity = Gravity.CENTER
                setPadding(dp(app, 10), dp(app, 5), dp(app, 10), dp(app, 5))
                setOnClickListener { callback() }
            }
        val heading = text("一起听 · ${room.characterName}", 14f, Color.rgb(65, 48, 64)).apply {
            setTypeface(typeface, Typeface.BOLD)
        }
        header.addView(heading, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(action("−", "收起悬浮窗") {
            SharedMusicWindowController.compact()
            dismiss()
            present(app)
        })
        header.addView(action("◌", "隐藏悬浮窗") {
            SharedMusicWindowController.hide()
            dismiss()
        })
        header.addView(action("×", "关闭一起听") {
            SharedMusicWindowController.close()
            dismiss()
        })
        base.addView(header)

        if (SharedMusicWindowController.display == SharedMusicWindow.Expanded) {
            val art = object : View(app) {
                private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    val center = width / 2f
                    val y = height / 2f
                    val r = dp(app, 43).toFloat()
                    pen.style = Paint.Style.STROKE
                    pen.strokeWidth = dp(app, 3).toFloat()
                    pen.strokeJoin = Paint.Join.ROUND
                    pen.strokeCap = Paint.Cap.ROUND
                    pen.color = Color.rgb(176, 112, 147)
                    for (left in listOf(true, false)) {
                        val dir = if (left) 1f else -1f
                        val start = if (left) dp(app, 45).toFloat() else width - dp(app, 45).toFloat()
                        val distance = ((center - r) - dp(app, 45)).coerceAtLeast(8f)
                        val path = Path().apply {
                            moveTo(start, y)
                            lineTo(start + dir * distance * .28f, y - dp(app, 10))
                            lineTo(start + dir * distance * .50f, y + dp(app, 7))
                            lineTo(start + dir * distance * .73f, y - dp(app, 6))
                            lineTo(start + dir * distance, y)
                        }
                        canvas.drawPath(path, pen)
                    }
                    pen.style = Paint.Style.FILL
                    pen.color = Color.rgb(36, 32, 42)
                    canvas.drawCircle(center, y, r, pen)
                    pen.color = Color.rgb(202, 167, 185)
                    canvas.drawCircle(center, y, dp(app, 19).toFloat(), pen)
                    pen.color = Color.rgb(35, 31, 42)
                    canvas.drawCircle(center, y, dp(app, 3).toFloat(), pen)
                    pen.color = Color.rgb(230, 216, 225)
                    canvas.drawCircle(dp(app, 31).toFloat(), y, dp(app, 26).toFloat(), pen)
                    canvas.drawCircle(width - dp(app, 31).toFloat(), y,
                        dp(app, 26).toFloat(), pen)
                    pen.color = Color.rgb(80, 53, 72)
                    pen.textAlign = Paint.Align.CENTER
                    pen.typeface = Typeface.DEFAULT_BOLD
                    pen.textSize = dp(app, 15).toFloat()
                    canvas.drawText(room.characterName.take(1).ifBlank { "角" },
                        dp(app, 31).toFloat(), y + dp(app, 5), pen)
                    canvas.drawText("我", width - dp(app, 31).toFloat(), y + dp(app, 5), pen)
                }
            }
            base.addView(art, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(app, 102)))
        }
        val name = text("", 15f, Color.rgb(41, 36, 43)).apply {
            setTypeface(typeface, Typeface.BOLD)
        }
        val artist = text("", 11f, Color.rgb(146, 113, 132))
        base.addView(name)
        base.addView(artist)
        title = name
        subtitle = artist
        val controls = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        controls.addView(action("◀◀", "上一首") {
            SharedMusicWindowController.previousPlayback?.invoke()
            handler.postDelayed({ refresh() }, 400)
        })
        val playButton = action("▶", "播放或暂停") {
            SharedMusicWindowController.togglePlayback?.invoke()
            handler.postDelayed({ refresh() }, 400)
        }
        play = playButton
        controls.addView(playButton)
        controls.addView(action("▶▶", "下一首") {
            SharedMusicWindowController.nextPlayback?.invoke()
            handler.postDelayed({ refresh() }, 400)
        })
        controls.addView(action("打开露露机", "回到聊天和音乐页") {
            dismiss()
            val intent = android.content.Intent(app, MigrationActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { app.startActivity(intent) }
        })
        base.addView(controls)

        val params = WindowManager.LayoutParams(
            app.resources.displayMetrics.widthPixels - dp(app, 26),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(app, 65)
        }
        var downY = 0f
        var originalY = params.y
        base.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = event.rawY; originalY = params.y; true }
                MotionEvent.ACTION_MOVE -> {
                    params.y = (originalY + (event.rawY - downY).toInt()).coerceAtLeast(dp(app, 15))
                    runCatching { wm.updateViewLayout(base, params) }
                    true
                }
                MotionEvent.ACTION_UP -> true
                else -> false
            }
        }
        if (runCatching { wm.addView(base, params) }.isFailure) {
            title = null; subtitle = null; play = null
            return
        }
        manager = wm
        surface = base
        refresh()
        val runnable = object : Runnable {
            override fun run() {
                if (surface == null) return
                if (SharedMusicWindowController.room == null ||
                    SharedMusicWindowController.display == SharedMusicWindow.Hidden) {
                    dismiss()
                } else {
                    refresh()
                    handler.postDelayed(this, 2_000L)
                }
            }
        }
        tick = runnable
        handler.postDelayed(runnable, 2_000L)
    }

    private fun refresh() {
        title?.text = SharedMusicWindowController.playbackTitle
        subtitle?.text = SharedMusicWindowController.playbackArtist
        play?.text = if (SharedMusicWindowController.playbackPlaying) "Ⅱ" else "▶"
    }

    fun dismiss() {
        tick?.let(handler::removeCallbacks)
        tick = null
        val old = surface
        surface = null
        if (old != null) runCatching { manager?.removeViewImmediate(old) }
        manager = null
        title = null
        subtitle = null
        play = null
    }
}
