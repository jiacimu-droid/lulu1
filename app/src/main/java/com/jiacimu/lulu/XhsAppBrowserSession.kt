package com.jiacimu.lulu

import android.app.Activity
import android.view.ViewGroup
import android.view.View
import android.widget.FrameLayout
import android.webkit.WebView
import com.jiacimu.lulu.data.XhsReadingEngine

/**
 * One browser per foreground Activity, not one browser per screen.
 *
 * Screens borrow the SAME real WebView. Leaving a screen parks its view
 * without discarding the site session, navigation and verified cache. The
 * Activity owns destruction. This is deliberately not a background/headless
 * WebView service: Android can suspend rendering when the app is not visible.
 */
internal class XhsAppBrowserSession(private val activity: Activity) {
    private var retained: WebView? = null
    private var parkingSlot: FrameLayout? = null
    private var destroyed = false

    /** An attached and measured slot underneath the root Compose UI.
     * Its viewport is large enough for the site's virtualized cards.
     * It does not receive taps and is not a guarantee of background execution.
     */
    private fun foregroundSlot(): FrameLayout {
        parkingSlot?.let { return it }
        val root = activity.findViewById<FrameLayout>(android.R.id.content)
        val slot = object : FrameLayout(activity) {
            // The parked website is not an interactive surface. Prevent
            // unhandled taps on the overlaying UI reaching invisible links.
            override fun onInterceptTouchEvent(ev: android.view.MotionEvent?): Boolean = true
            override fun onTouchEvent(event: android.view.MotionEvent?): Boolean = true
        }.apply {
            isClickable = true
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val height = (420f * activity.resources.displayMetrics.density).toInt()
        root.addView(slot, 0, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, height))
        parkingSlot = slot
        return slot
    }

    fun reuse(): WebView? {
        check(!destroyed) { "小红书会话已经销毁" }
        val view = retained ?: return null
        // Compose's AndroidView will attach it to its new current slot.
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    fun adopt(view: WebView) {
        check(!destroyed) { "小红书会话已经销毁" }
        val current = retained
        require(current == null || current === view) {
            "应用级小红书会话已持有另一个 WebView；禁止创建第二份登录网页"
        }
        retained = view
    }

    fun park(view: WebView) {
        if (destroyed || retained !== view) return
        if (activity.isFinishing || activity.isDestroyed) {
            XhsReadingEngine.detachBrowser(view)
            return
        }
        // Place the SAME browser behind the app's normal Compose surface.
        // Unlike a detached WebView, an attached/measured WebView can service
        // online character reads while the user is on another app page.
        // If Android pauses the Activity, the engine refuses new requests.
        val host = foregroundSlot()
        view.clearFocus()
        (view.parent as? ViewGroup)?.removeView(view)
        host.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))
        XhsReadingEngine.parkBrowser(view)
    }

    fun hasBrowser(): Boolean = retained != null && !destroyed

    fun destroy() {
        if (destroyed) return
        destroyed = true
        retained?.let { view ->
            XhsReadingEngine.detachBrowser(view)
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.destroy()
        }
        retained = null
        parkingSlot?.let { slot ->
            (slot.parent as? ViewGroup)?.removeView(slot)
            slot.removeAllViews()
        }
        parkingSlot = null
    }
}
