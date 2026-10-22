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

    private fun artist(controller: MediaController): String =
        controller.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()

    private fun album(controller: MediaController): String =
        controller.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()

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
            playing != null -> "已发现正在播放的媒体会话：${playing.optString("packageName")}；歌曲=${playing.optString("title").ifBlank { "未提供标题" }}；歌手=${playing.optString("artist").ifBlank { "未知" }}；可使用list_media_sessions查看详情。标题/歌手不是实际听过音频的证据"
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
                        .put("title", title(c)).put("artist", artist(c))
                        .put("album", album(c)).put("playing",
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

    /** PLAY_PAUSE fallback is a toggle: never dispatch it when already in target state. */
    internal fun alreadyAtTarget(action: String, state: Int): Boolean =
        (action == "play" && state == PlaybackState.STATE_PLAYING) ||
            (action == "pause" && state in setOf(
                PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED,
                PlaybackState.STATE_NONE))

    @Volatile private var lastPlayerPackage = ""

    fun control(context: Context, command: String, packageName: String = ""): String = runCatching {
        val controllers = active(context)
        if (controllers.isEmpty()) error("未发现可控制的媒体会话；不代表手机没有声音。请检查通知使用权及播放器的系统媒体会话")
        val requested = command.trim().lowercase()
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
            } ?: controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: controllers.firstOrNull { it.packageName == lastPlayerPackage }
                ?: controllers.singleOrNull()
                ?: error("发现多个播放器但无法确定要控制哪一个；请先查询 list_media_sessions 并指定 packageName")
        }
        val before = selected.playbackState
        val action = requestedAction(requested, before?.state == PlaybackState.STATE_PLAYING)
        check(before != null) { "播放器没有提供状态，无法安全区分播放或暂停。请在音乐App里先播放一次再重试" }
        // Sending PLAY_PAUSE when paused would resume the player's music.
        // Sending it when playing would pause. An idempotent request should
        // neither replay nor interrupt anything; verify and return instead.
        if (alreadyAtTarget(action, before.state)) {
            lastPlayerPackage = selected.packageName
            return@runCatching JSONObject().put("success", true)
                .put("accepted", true).put("status", "already_at_target")
                .put("command", action).put("sentBy", "no_dispatch")
                .put("packageName", selected.packageName).put("title", title(selected))
                .put("beforeState", before.state).put("afterState", before.state)
                .put("verification", "播放器已经处于目标状态，未重复发送切换按键")
                .toString()
        }
        check(supports(action, before) || action in setOf("play", "pause")) {
            "当前播放器未开放「$action」控制"
        }
        lastPlayerPackage = selected.packageName
        val oldTitle = title(selected)
        val explicit = (before.actions and explicitFlag(action)) != 0L
        val sentBy = if (explicit) {
            when (action) {
                "play" -> selected.transportControls.play()
                "pause" -> selected.transportControls.pause()
                "next" -> selected.transportControls.skipToNext()
                "previous" -> selected.transportControls.skipToPrevious()
            }
            "transport_controls"
        } else {
            // Prefer an explicit PLAY / PAUSE media key so the requested
            // operation never becomes an accidental toggle when state changes
            // between reading it and dispatching the command. For a session
            // only advertising PLAY_PAUSE, use its own toggle key.
            val hasToggle = (before.actions and PlaybackState.ACTION_PLAY_PAUSE) != 0L
            val key = if (hasToggle) KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                else if (action == "play") KeyEvent.KEYCODE_MEDIA_PLAY
                else KeyEvent.KEYCODE_MEDIA_PAUSE
            val down = selected.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            val up = selected.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            check(down || up) { "播放器没有接收系统播放/暂停按键" }
            if (hasToggle) "media_toggle" else "media_play_pause_key"
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
