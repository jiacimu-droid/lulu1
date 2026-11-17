/*
 * Targeted input transport adapted from Mika ShellService.kt (Apache-2.0).
 * Copyright 2026 wangzan101 / Mika authors.
 * SOURCE: https://github.com/wangzan101/Mika/blob/84cbb1d2e4b9793c8026b23c72cffda09d13e6aa/app/src/main/java/com/wangzan101/mika/cap/phone/shell/ShellService.kt
 * THIRD_PARTY_MODIFIED: owned display + target UID only; one touch sequence,
 * no input-command/main-display fallback, partial dispatch is not repeated.
 */
package com.wangzan101.mika.cap.phone.shell

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.jiacimu.lulu.system.LuluPhoneInteractionPolicy
import org.json.JSONObject

internal object LuluShellTouchInput {
    data class Dispatch(val attempted: Boolean, val down: Boolean, val up: Boolean)

    fun allowed(displayId: Int, requestedPackage: String, launchedPackage: String,
        x: Int, y: Int, holdMs: Int): Boolean = displayId > 0 &&
        requestedPackage.isNotBlank() && requestedPackage == launchedPackage &&
        x in 0..1000 && y in 0..1000 && holdMs in 1..LuluPhoneInteractionPolicy.MAX_HOLD_MS

    /** Once DOWN was tried, never invoke another click transport. Cancel only
     * releases an uncertain pressed pointer; it cannot activate the button. */
    fun dispatch(sendDown: () -> Boolean, sendUp: () -> Boolean, cancel: () -> Unit): Dispatch {
        var down = false
        var up = false
        try {
            down = sendDown()
            if (down) up = sendUp()
        } catch (_: Exception) {
            // Android may already have received DOWN even when reflection threw.
        } finally {
            if (!up) cancel()
        }
        return Dispatch(true, down, up)
    }

    fun touch(context: Context, displayId: Int, requestedPackage: String, launchedPackage: String,
        x: Int, y: Int, width: Int, height: Int, holdMs: Int): JSONObject {
        fun receipt(attempted: Boolean, accepted: Boolean, code: String) = JSONObject()
            .put("accepted", accepted).put("dispatchAttempted", attempted)
            .put("displayId", displayId).put("taskCompleted", false)
            .put("actionMethod", "shell_target_uid_virtual_touch").put("errorCode", code)
        if (!allowed(displayId, requestedPackage, launchedPackage, x, y, holdMs) || width <= 0 || height <= 0)
            return receipt(false, false, "virtual_touch_target_invalid")
        val uid = runCatching { context.packageManager.getPackageUid(requestedPackage, 0) }
            .getOrNull()?.takeIf { it >= 0 } ?: return receipt(false, false, "virtual_touch_uid_missing")
        val px = (width - 1) * x / 1000f
        val py = (height - 1) * y / 1000f
        val started = SystemClock.uptimeMillis()
        fun send(action: Int): Boolean {
            val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action, px, py, 0)
            return try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                LuluShellTextInput.injectTargeted(event, displayId, uid)
            } finally { event.recycle() }
        }
        val actual = dispatch(
            sendDown = { send(MotionEvent.ACTION_DOWN) },
            sendUp = { SystemClock.sleep(holdMs.toLong()); send(MotionEvent.ACTION_UP) },
            cancel = { runCatching { send(MotionEvent.ACTION_CANCEL) }; Unit },
        )
        return receipt(actual.attempted, actual.down && actual.up,
            if (actual.down && actual.up) "" else "virtual_touch_dispatch_unverified")
            .put("verification", "只向所属独立屏和目标App UID派发了一次触摸；页面效果仍需观察")
    }
}
