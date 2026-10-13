package com.jiacimu.lulu.system

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Uses standard Android media transport controls, not accessibility screen tapping.
 * Control is limited to existing playback sessions from apps that expose them.
 */
internal object LuluMediaPlayback {
    private fun active(context: Context): List<MediaController> {
        check(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) {
            "请先在系统设置中给露露机开启「通知读取」权限，才能控制正在运行的音乐 App"
        }
        val manager = context.getSystemService(MediaSessionManager::class.java)
        return manager.getActiveSessions(ComponentName(context, LuluNotificationListenerService::class.java))
            .filter { it.packageName != context.packageName }
    }

    private fun title(controller: MediaController): String =
        controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()

    fun sessions(context: Context): String = runCatching {
        val controllers = active(context)
        JSONObject().put("success", true).put("count", controllers.size).put("sessions",
            JSONArray().apply {
                controllers.forEach { c ->
                    put(JSONObject().put("packageName", c.packageName)
                        .put("title", title(c)).put("playing",
                            c.playbackState?.state == PlaybackState.STATE_PLAYING)
                        .put("availableActions", c.playbackState?.actions ?: 0L))
                }
            }).toString()
    }.getOrElse { JSONObject().put("success", false).put("error", it.message).toString() }

    fun control(context: Context, command: String, packageName: String = ""): String = runCatching {
        val action = command.trim().lowercase()
        val actionFlag = when (action) {
            "play" -> PlaybackState.ACTION_PLAY
            "pause" -> PlaybackState.ACTION_PAUSE
            "next" -> PlaybackState.ACTION_SKIP_TO_NEXT
            "previous" -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
            else -> error("不支持的音乐指令；仅支持 play、pause、next、previous")
        }
        val controllers = active(context)
        val selected = if (packageName.isNotBlank()) {
            controllers.firstOrNull { it.packageName == packageName }
        } else {
            controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: controllers.firstOrNull()
        } ?: error("尚未发现可用的音乐播放会话；请先打开音乐 App 播放一首歌")
        val before = selected.playbackState
        check(before != null && (before.actions and actionFlag) != 0L) {
            "当前音乐播放器未开放这项播放控制"
        }
        val previousTitle = title(selected)
        when (action) {
            "play" -> selected.transportControls.play()
            "pause" -> selected.transportControls.pause()
            "next" -> selected.transportControls.skipToNext()
            "previous" -> selected.transportControls.skipToPrevious()
        }
        val after = selected.playbackState
        val newTitle = title(selected)
        val verified = when (action) {
            "play" -> after?.state == PlaybackState.STATE_PLAYING
            "pause" -> after?.state == PlaybackState.STATE_PAUSED
            else -> newTitle.isNotBlank() && previousTitle != newTitle
        }
        JSONObject().put("success", verified)
            .put("status", if (verified) "verified" else "requested_unverified")
            .put("accepted", true)
            .put("packageName", selected.packageName)
            .put("command", action)
            .put("title", newTitle)
            .put("verification", if (verified) "已观察到播放状态或曲目变化"
                else "已向播放器发出控制请求，播放器可能异步处理或拒绝；暂不能宣称已经完成")
            .toString()
    }.getOrElse { JSONObject().put("success", false).put("error", it.message).toString() }
}
