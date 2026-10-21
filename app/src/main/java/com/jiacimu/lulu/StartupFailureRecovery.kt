package com.jiacimu.lulu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Records early Android startup crashes without resetting any user's character,
 * timeline, memory or credentials. An exception can occur after Kotlin
 * compilation and a successful APK build; the phone needs an actionable trace.
 */
internal object StartupFailureRecovery {
    private const val PREFS = "lulu_startup_failure_diagnostic"
    private const val REPORT = "last_report"
    private var installed = false
    @Volatile private var currentStage = "Activity launching"
    @Volatile private var startupUntil = 0L
    @Volatile var recovering: Boolean = false
        private set

    fun install(context: Context) {
        currentStage = "MigrationActivity.onCreate"
        startupUntil = android.os.SystemClock.elapsedRealtime() + 20_000L
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (android.os.SystemClock.elapsedRealtime() < startupUntil) {
                record(app, currentStage, error)
            }
            previous?.uncaughtException(thread, error)
                ?: run { android.os.Process.killProcess(android.os.Process.myPid()) }
        }
    }

    fun stage(stage: String) { currentStage = stage }

    fun ready() {
        currentStage = "App ready"
        startupUntil = 0L
    }

    fun latest(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(REPORT, null)?.takeIf { it.isNotBlank() }

    fun recordCurrent(context: Context, error: Throwable) = record(context, currentStage, error)

    /** A noncritical peripheral failure must not become a fatal-recovery loop. */
    fun recordWarning(context: Context, stage: String, error: Throwable) {
        runCatching {
            val writer = StringWriter()
            error.printStackTrace(PrintWriter(writer))
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("last_warning", "模块：$stage\n" + writer.toString().take(3000))
                .apply()
        }
    }


    fun record(context: Context, stage: String, error: Throwable) {
        runCatching {
            val writer = StringWriter()
            error.printStackTrace(PrintWriter(writer))
            val message = "启动阶段：$stage\n异常类型：${error.javaClass.name}\n" +
                writer.toString().take(6_500)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(REPORT, message).commit()
        }
    }

    fun display(activity: ComponentActivity, trace: String) {
        recovering = true
        activity.setContent { RecoveryScreen(trace, onRetry = {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(REPORT).commit()
            recovering = false
            activity.recreate()
        }, onCopy = {
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("Lulu startup crash", trace))
        }) }
    }

    @Composable private fun RecoveryScreen(trace: String, onRetry: () -> Unit, onCopy: () -> Unit) {
        MaterialTheme {
            Column(Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("露露机启动诊断", style = MaterialTheme.typography.headlineMedium)
                Text("启动过程遇到了运行时异常。角色数据、聊天记录、记忆和设置没有被本页面清除。请不要卸载或清除应用数据。")
                Text(trace.lineSequence().take(4).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = onCopy) { Text("复制完整崩溃记录") }
                OutlinedButton(onClick = onRetry) { Text("重新尝试正常启动") }
            }
        }
    }
}
