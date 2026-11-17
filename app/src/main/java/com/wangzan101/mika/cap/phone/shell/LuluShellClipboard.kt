/*
 * Based on Mika ShellClipboard (Apache-2.0, Copyright 2026 Mika authors).
 * SOURCE: https://github.com/wangzan101/Mika
 * THIRD_PARTY_MODIFIED: read/restore-only compatibility for Lulu's owned
 * virtual-display text input. No clipboard data crosses the role/model wire.
 */
package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import java.lang.reflect.Method

/**
 * Shell app_process has no ordinary ActivityThread provider registration.
 * Use the framework clipboard for writes and the direct system Binder for
 * restoring an existing owner's clipboard; never discard an unreadable clip.
 */
@SuppressLint("PrivateApi")
internal class LuluShellClipboard(context: Context) {
    private val manager: ClipboardManager by lazy {
        ClipboardManager::class.java.getDeclaredConstructor(Context::class.java, Handler::class.java)
            .apply { isAccessible = true }
            .newInstance(context, Handler(Looper.getMainLooper() ?: Looper.myLooper()!!))
    }
    private val service: Any? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "clipboard") as IBinder
            Class.forName("android.content.IClipboard\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        }.getOrNull()
    }
    private val readClip by lazy { method("getPrimaryClip") }
    private val readDescription by lazy { method("getPrimaryClipDescription") }
    private val hasClip by lazy { method("hasPrimaryClip") }

    private fun method(name: String): Method? {
        if (service == null) return null
        val type = runCatching { Class.forName("android.content.IClipboard") }.getOrNull() ?: return null
        val s = String::class.java
        val i = Int::class.javaPrimitiveType!!
        val candidates = listOf(
            arrayOf(s, s, i, i),
            arrayOf(s, s, i),
            arrayOf(s, s, s, s, i, i, Boolean::class.javaPrimitiveType!!),
        )
        return candidates.firstNotNullOfOrNull {
            runCatching { type.getMethod(name, *it) }.getOrNull()
        }
    }

    private fun call(method: Method): Any? {
        val userId = Process.myUid() / 100_000
        val args: Array<Any?> = when (method.parameterTypes.size) {
            7 -> arrayOf("com.android.shell", "Shell", "", null, userId, 0, false)
            4 -> arrayOf("com.android.shell", null, userId, 0)
            else -> arrayOf("com.android.shell", null, userId)
        }
        return method.invoke(service, *args)
    }

    fun snapshot(): ClipData? {
        val present = hasClip?.let { call(it) as? Boolean } ?: manager.hasPrimaryClip()
        val description = readDescription?.let { call(it) } ?: manager.primaryClipDescription
        val saved = if (present || description != null)
            readClip?.let { call(it) as? ClipData } ?: manager.primaryClip else null
        // Android can report no clip to this background process while its
        // description still says the owner's private clipboard is populated.
        check((!present && description == null) || saved != null) {
            "existing clipboard unreadable; refusing replacement"
        }
        return saved
    }

    private fun isOurClip(clip: ClipData?, text: String): Boolean =
        clip?.description?.label?.toString() == "Lulu-virtual-input" &&
            clip.itemCount == 1 && clip.getItemAt(0).text?.toString() == text

    /** Verify what was actually written before pressing Ctrl+V. Some ROMs
     * silently decline shell clipboard writes: pasting the OWNER's old clip
     * into someone else's chat would be an unacceptable failure. */
    fun write(text: String): Boolean {
        val clip = ClipData.newPlainText("Lulu-virtual-input", text)
        val extras = PersistableBundle().apply {
            putBoolean("com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY", true)
            if (Build.VERSION.SDK_INT >= 33)
                putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        clip.description.extras = extras
        manager.setPrimaryClip(clip)
        return isOurClip(snapshot(), text)
    }

    /** If the owner copied something new while a paste was being processed,
     * do not overwrite that new clipboard with the old saved value. */
    fun restore(saved: ClipData?, ourText: String): Boolean {
        val current = snapshot()
        if (!isOurClip(current, ourText)) return true
        if (saved == null) manager.clearPrimaryClip() else manager.setPrimaryClip(saved)
        val after = snapshot()
        return if (saved == null) after == null else
            after?.description?.label == saved.description.label &&
                after.itemCount == saved.itemCount &&
                (0 until saved.itemCount).all { index ->
                    after.getItemAt(index).text?.toString() ==
                        saved.getItemAt(index).text?.toString()
                }
    }
}
