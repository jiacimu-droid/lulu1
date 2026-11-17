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
        val saved = if (present) readClip?.let { call(it) as? ClipData } ?: manager.primaryClip else null
        check(!present || saved != null) { "existing clipboard unreadable; refusing replacement" }
        return saved
    }

    fun write(text: String) {
        manager.setPrimaryClip(ClipData.newPlainText("", text))
    }

    fun restore(saved: ClipData?) {
        if (saved == null) manager.clearPrimaryClip() else manager.setPrimaryClip(saved)
    }
}
