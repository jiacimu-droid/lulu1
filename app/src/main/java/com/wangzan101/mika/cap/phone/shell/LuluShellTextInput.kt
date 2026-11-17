/*
 * Isolated-display keyboard injection adapted from Mika ShellService.kt
 * (Apache-2.0, Copyright 2026 Mika authors).
 * SOURCE: https://github.com/wangzan101/Mika
 * THIRD_PARTY_MODIFIED: targeted paste only, never main display or arbitrary key events.
 */
package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import org.json.JSONObject

/**
 * The ordinary app process cannot write to a WeChat editor omitted from the
 * virtual-display accessibility tree. The ADB-shell process that OWNS that
 * display can send keyboard events with both displayId and target app UID.
 * This is a transport, NOT proof that any message was typed or sent.
 */
internal object LuluShellTextInput {
    private const val WAIT_FOR_FINISH = 2
    private const val VIRTUAL_KEYBOARD = -1

    fun mayPaste(displayId: Int, packageName: String, text: String, launchedPackage: String): Boolean =
        displayId > 0 && packageName == launchedPackage &&
        packageName == "com.tencent.mm" && text.isNotBlank() &&
        text.length <= 500 && Build.VERSION.SDK_INT >= 34

    @SuppressLint("PrivateApi")
    private fun injectTargeted(event: KeyEvent, displayId: Int, targetUid: Int): Boolean = try {
        event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
        val manager = runCatching {
            Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
        }.getOrNull() ?: Class.forName("android.hardware.input.InputManager")
            .getMethod("getInstance").invoke(null)
        manager.javaClass.getMethod("injectInputEvent",
            InputEvent::class.java, Int::class.java, Int::class.java)
            .invoke(manager, event, WAIT_FOR_FINISH, targetUid) as Boolean
    } catch (_: Throwable) {
        false
    }

    private fun combo(displayId: Int, uid: Int, letter: Int): Pair<Boolean, Boolean> {
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        fun key(code: Int, action: Int, modifiers: Int): KeyEvent =
            KeyEvent(now, SystemClock.uptimeMillis(), action, code, 0,
                modifiers, VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val ctrlDown = injectTargeted(key(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_DOWN, 0), displayId, uid)
        if (!ctrlDown) return false to false
        var letterSent = false
        var complete = false
        try {
            val down = injectTargeted(key(letter, KeyEvent.ACTION_DOWN, meta), displayId, uid)
            if (down) {
                letterSent = true
                complete = injectTargeted(key(letter, KeyEvent.ACTION_UP, meta), displayId, uid)
            }
        } finally {
            // Never leave Ctrl held after a failed injection.
            val ctrlUp = key(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_UP, 0)
            if (!injectTargeted(ctrlUp, displayId, uid)) injectTargeted(ctrlUp, displayId, uid)
        }
        return complete to letterSent
    }

    /** Called only after owner authorization, verified virtual window, and a
     * model-selected one-time tap of the real screenshot's composer region.
     * Any uncertain/partial key dispatch is terminal: no duplicate paste. */
    fun paste(
        context: Context, displayId: Int, packageName: String,
        launchedPackage: String, text: String,
    ): JSONObject {
        fun result(code: String, pressed: Boolean, restored: Boolean) = JSONObject()
            .put("accepted", pressed && restored && code.isBlank())
            .put("inputDispatched", pressed)
            .put("clipboardRestored", restored)
            .put("errorCode", code)
            .put("actionMethod", "mika_shell_target_uid_unicode_paste")
            .put("displayId", displayId)
            .put("taskCompleted", false)
        if (!mayPaste(displayId, packageName, text, launchedPackage))
            return result("visual_input_not_authorized", false, true)
        val uid = runCatching { context.packageManager.getPackageUid(packageName, 0) }
            .getOrNull()?.takeIf { it >= 0 }
            ?: return result("visual_input_target_uid_unverified", false, true)
        val clipboard = LuluShellClipboard(context)
        val saved = runCatching { clipboard.snapshot() }
            .getOrElse { return result("visual_input_clipboard_unreadable", false, true) }
        var pressed = false
        var restored = false
        var code = ""
        try {
            // If the original clipboard cannot be read, paste never starts.
            clipboard.write(text)
            val selected = combo(displayId, uid, KeyEvent.KEYCODE_A)
            if (!selected.first) {
                code = if (selected.second) "visual_input_selection_ambiguous"
                    else "visual_input_target_focus_rejected"
            } else {
                val pasted = combo(displayId, uid, KeyEvent.KEYCODE_V)
                pressed = pasted.second
                if (!pasted.first) code = if (pasted.second)
                    "visual_input_paste_ambiguous" else "visual_input_paste_rejected"
                // Apps sometimes consume clipboard asynchronously.
                SystemClock.sleep(380)
            }
        } catch (_: Throwable) {
            code = "visual_input_key_dispatch_failed"
        } finally {
            restored = runCatching { clipboard.restore(saved); true }.getOrDefault(false)
        }
        return result(if (!restored) "visual_input_clipboard_restore_failed" else code,
            pressed, restored)
    }
}
