package com.jiacimu.lulu.system

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent
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

    /**
     * A surprising number of Android players advertise PLAY_PAUSE (the media
     * button) but not separate PLAY/PAUSE flags. Treat the flags as capability
     * hints, not proof that the player cannot act. A skip-to-next succeeding
     * does not imply that a pause request has been verified.
     */
    internal fun requestedAction(raw: String, playing: Boolean): String {
        val command = raw.trim().lowercase()
        return when (command) {
            "play", "resume", "start", "继续", "播放", "开始", "继续播放" -> "play"
            "pause", "stop", "暂停", "停止", "暂停播放" -> "pause"
            "toggle", "play_pause", "playpause", "播放暂停", "播放/暂停" ->
                if (playing) "pause" else "play"
            "next", "下一首", "切歌", "下一曲" -> "next"
            "previous", "prev", "上一首", "上一曲" -> "previous"
            else -> error("不支持的音乐指令；可用播放、暂停、切换播放状态、下一首、上一首")
        }
    }

    private fun explicitFlag(action: String): Long = when (action) {
        "play" -> PlaybackState.ACTION_PLAY
        "pause" -> PlaybackState.ACTION_PAUSE
        "next" -> PlaybackState.ACTION_SKIP_TO_NEXT
        "previous" -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
        else -> 0L
    }

    internal fun supports(action: String, state: PlaybackState?): Boolean {
        if (state == null) return false
        val flag = explicitFlag(action)
        return (state.actions and flag) != 0L ||
            (action in setOf("play", "pause") &&
                (state.actions and PlaybackState.ACTION_PLAY_PAUSE) != 0L)
    }

    @Volatile private var lastPlayerPackage = ""

    fun control(context: Context, command: String, packageName: String = ""): String = runCatching {
        val controllers = active(context)
        if (controllers.isEmpty()) error("未发现可控制的媒体会话；不代表手机没有声音。请检查通知使用权及播放器的系统媒体会话")
        val requested = command.trim().lowercase()
        val toggle = requested in setOf("toggle", "play_pause", "playpause", "播放暂停", "播放/暂停")
        val selected = if (packageName.isNotBlank()) {
            controllers.firstOrNull { it.packageName == packageName }
                ?: error("指定播放器未运行或未暴露媒体会话：$packageName")
        } else {
            // A paused music session should stay the target instead of some
            // unrelated newly-playing video. Never choose the first arbitrary
            // session merely because a transport bit is missing.
            controllers.firstOrNull { it.packageName == lastPlayerPackage }?.takeIf {
                val a = requestedAction(requested, it.playbackState?.state == PlaybackState.STATE_PLAYING)
                supports(a, it.playbackState)
            } ?: controllers.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING &&
                    supports(requestedAction(requested, true), it.playbackState)
            } ?: controllers.firstOrNull {
                supports(requestedAction(requested, it.playbackState?.state == PlaybackState.STATE_PLAYING), it.playbackState)
            } ?: error("发现媒体会话，但没有播放器提供对应的系统控制能力。请打开音乐App播放一次再试")
        }
        val before = selected.playbackState
        val action = requestedAction(requested, before?.state == PlaybackState.STATE_PLAYING)
        check(supports(action, before)) {
            "当前播放器未开放「$action」控制（可检查是否仅有其他媒体App的会话）"
        }
        lastPlayerPackage = selected.packageName
        val oldTitle = title(selected)
        val explicit = (before!!.actions and explicitFlag(action)) != 0L
        val sentBy = if (explicit) {
            when (action) {
                "play" -> selected.transportControls.play()
                "pause" -> selected.transportControls.pause()
                "next" -> selected.transportControls.skipToNext()
                "previous" -> selected.transportControls.skipToPrevious()
            }
            "transport_controls"
        } else {
            // Common PLAY_PAUSE-only sessions (e.g. some embedded players).
            val key = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            val down = selected.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            val up = selected.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            check(down || up) { "播放器没有接收系统播放/暂停按键" }
            "media_button"
        }
        // MediaSession callbacks may be asynchronous; an immediate stale
        // playbackState is NOT evidence that the operation failed.
        val after = selected.playbackState
        val verified = when (action) {
            "play" -> after?.state == PlaybackState.STATE_PLAYING &&
                before.state != PlaybackState.STATE_PLAYING
            "pause" -> after?.state == PlaybackState.STATE_PAUSED &&
                before.state != PlaybackState.STATE_PAUSED
            else -> title(selected).isNotBlank() && title(selected) != oldTitle
        }
        JSONObject().put("success", verified)
            .put("accepted", true)
            .put("status", if (verified) "verified" else "requested_unverified")
            .put("command", action)
            .put("sentBy", sentBy)
            .put("packageName", selected.packageName)
            .put("title", title(selected))
            .put("beforeState", before.state)
            .put("afterState", after?.state ?: PlaybackState.STATE_NONE)
            .put("verification", if (verified) "系统已经观察到播放状态或曲目发生变化"
                else "播放控制已发出，但系统尚未报告状态变化；不能断言执行失败或成功")
            .toString()
    }.getOrElse { JSONObject().put("success", false).put("accepted", false)
        .put("error", it.message).toString() }
}
