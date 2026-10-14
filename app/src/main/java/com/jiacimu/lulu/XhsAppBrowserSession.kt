package com.jiacimu.lulu

import android.app.Activity
import android.view.ViewGroup
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
    private var destroyed = false

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
        // Keep the verified results and login state; do not advertise the
        // detached browser as presently readable or try to run hidden work.
        XhsReadingEngine.parkBrowser(view)
        (view.parent as? ViewGroup)?.removeView(view)
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
    }
}
