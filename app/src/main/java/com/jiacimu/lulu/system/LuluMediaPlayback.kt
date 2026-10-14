package com.jiacimu.lulu.system

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
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

    /** Active audio is only a hint; videos/games may also emit audio. */
    private fun audioActive(context: Context): Boolean? = runCatching {
        context.getSystemService(AudioManager::class.java)?.isMusicActive
    }.getOrNull()

    /** Human-readable, evidence-aware snapshot used at perception time. */
    fun observation(context: Context): String {
        val snapshot = JSONObject(sessions(context))
        val audio = audioActive(context)
        if (!snapshot.optBoolean("success")) {
            return "播放器未知：${snapshot.optString("error").take(140)}；系统音频活跃=${audio ?: "未知"}；不能断言没有播放音乐"
        }
        val items = snapshot.optJSONArray("sessions")
        val playing = (0 until (items?.length() ?: 0)).mapNotNull { items?.optJSONObject(it) }
            .firstOrNull { it.optBoolean("playing") }
        return when {
            playing != null -> "已发现正在播放的媒体会话：${playing.optString("packageName")}；歌曲=${playing.optString("title").ifBlank { "未提供标题" }}；可使用list_media_sessions查看详情"
            snapshot.optInt("count") > 0 -> "发现${snapshot.optInt("count")}个媒体会话，但未报告STATE_PLAYING；系统音频活跃=${audio ?: "未知"}；不可断言主人没有播放"
            audio == true -> "系统报告有音频活动，但未发现可控制的媒体会话；可能是音乐、视频或游戏，具体来源未知"
            else -> "没有发现可控制的媒体会话；系统音频活跃=${audio ?: "未知"}，不能据此断言主人没有播放音乐"
        }
    }

    fun sessions(context: Context): String = runCatching {
        val controllers = active(context)
        JSONObject().put("success", true).put("count", controllers.size).put("sessions",
            JSONArray().apply {
                controllers.forEach { c ->
                    put(JSONObject().put("packageName", c.packageName)
                        .put("title", title(c)).put("playing",
                            c.playbackState?.state == PlaybackState.STATE_PLAYING)
                        .put("playbackState", c.playbackState?.state ?: PlaybackState.STATE_NONE)
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
            // Prefer the audible player, then a paused player actually exposing
            // this transport action. Do not pick an unrelated inactive session.
            controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING &&
                ((it.playbackState?.actions ?: 0L) and actionFlag) != 0L }
                ?: controllers.firstOrNull { ((it.playbackState?.actions ?: 0L) and actionFlag) != 0L }
        } ?: error(
            if (controllers.isEmpty()) "未检测到可控制的媒体会话；这不代表主人没有播放音乐。请检查通知使用权和音乐App的媒体控制支持"
            else "已发现${controllers.size}个媒体会话，但没有找到支持此操作的播放器"
        )
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
