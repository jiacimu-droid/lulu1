package com.jiacimu.lulu

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jiacimu.lulu.ai.LuluMusicSensoryRuntime
import com.jiacimu.lulu.data.EventEvidenceKind
import com.jiacimu.lulu.data.InteractionSignalBridge
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.system.LuluMediaPlayback
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class SharedListeningTrack(
    val title: String, val artist: String, val packageName: String, val playing: Boolean,
    val album: String = "", val positionMs: Long = 0L, val durationMs: Long = 0L,
)

/** Playback shares an explicitly selected file once per room entry; manual retry stays available. */
internal fun shouldAutoAnalyzeSharedAudio(selectedUri: String, lastAttemptUri: String, busy: Boolean): Boolean =
    selectedUri.isNotBlank() && selectedUri != lastAttemptUri && !busy

/**
 * The room needs TWO distinct facts: an available controller (even paused,
 * so the user can press Play), and an ACTUALLY playing session. Paused metadata
 * must never become evidence that somebody is listening.
 */
private fun selectSharedMediaSession(
    raw: String,
    allowPaused: Boolean,
): Pair<SharedListeningTrack?, String> {
    val root = runCatching { JSONObject(raw) }.getOrNull()
        ?: return null to "媒体会话数据无效"
    if (!root.optBoolean("success"))
        return null to root.optString("error", "读取媒体会话失败")
    val sessions = root.optJSONArray("sessions")
    val candidates = (0 until (sessions?.length() ?: 0)).mapNotNull { sessions?.optJSONObject(it) }
    val selected = candidates.firstOrNull {
        it.optString("packageName") == "com.netease.cloudmusic" && it.optBoolean("playing")
    } ?: candidates.firstOrNull { it.optBoolean("playing") }
        ?: (if (allowPaused) candidates.firstOrNull {
            it.optString("packageName") == "com.netease.cloudmusic"
        } ?: candidates.firstOrNull() else null)
        ?: return null to if (candidates.isEmpty()) {
            "未发现播放器。请在网易云音乐里播放一次，或检查通知使用权"
        } else {
            "已找到播放器，但当前没有报告正在播放的歌曲"
        }
    return SharedListeningTrack(
        selected.optString("title").ifBlank { "未提供歌曲名" },
        selected.optString("artist").ifBlank { "未知歌手" },
        selected.optString("packageName"),
        selected.optBoolean("playing"),
        album = selected.optString("album"),
        positionMs = selected.optLong("positionMs").coerceAtLeast(0L),
        durationMs = selected.optLong("durationMs").coerceAtLeast(0L),
    ) to ""
}

/** Only a PLAYING session is evidence of active playback, never actual audio understanding. */
internal fun listenedMediaSession(raw: String): Pair<SharedListeningTrack?, String> =
    selectSharedMediaSession(raw, allowPaused = false)

/** The UI may show a paused controller so its Play button can resume music. */
internal fun controllableMediaSession(raw: String): Pair<SharedListeningTrack?, String> =
    selectSharedMediaSession(raw, allowPaused = true)

private fun listenedAudioName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()?.takeIf(String::isNotBlank)
        ?: uri.lastPathSegment.orEmpty().takeLast(60).ifBlank { "本地音频" }

/**
 * One optional scene adapter of the existing chat, timeline and audio sensing.
 * Other-player MediaSession metadata never passes for actual waveform analysis.
 */
@Composable
internal fun ListenTogetherRoom(
    conversationId: String,
    characterId: String,
    characterName: String,
    characterAvatarUri: String? = null,
    onOpenMusic: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("lulu_listen_together_v1", Context.MODE_PRIVATE) }
    val key = remember(conversationId) { "chat:$conversationId:" }
    val roomId = remember(conversationId) {
        prefs.getString(key + "room", null).orEmpty().ifBlank { UUID.randomUUID().toString() }
    }
    // One actual visit may contain several play/pause actions, not several daily habits.
    val listeningVisitId = remember(conversationId) { UUID.randomUUID().toString() }
    var mode by remember { mutableStateOf("system") }
    var track by remember { mutableStateOf<SharedListeningTrack?>(null) }
    var sessionError by remember { mutableStateOf("") }
    var uri by remember(conversationId) { mutableStateOf(prefs.getString(key + "audio", "").orEmpty()) }
    var audioName by remember(uri) { mutableStateOf(if (uri.isBlank()) "" else listenedAudioName(context, Uri.parse(uri))) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var elapsed by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var lastAnalysisAttemptUri by remember(conversationId) { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var autoPlaySelectedUri by remember { mutableStateOf("") }

    fun recordListening(note: String) {
        SharedExperienceTimeline.record(
            eventId = "listen-$roomId-${UUID.randomUUID()}",
            characterId = characterId, channel = "一起听", speaker = "一起听状态",
            content = note, occurredAt = Instant.now(),
            triggerExtraction = false, sessionId = roomId, source = "listen-together",
            evidenceKind = EventEvidenceKind.Observation,
        )
        InteractionSignalBridge.recordUserActivity(characterId, listeningVisitId,
            "listen-together", "start", channel = "一起听")
    }

    fun refreshExternal() {
        val result = controllableMediaSession(LuluMediaPlayback.sessions(context))
        track = result.first
        sessionError = result.second
    }

    fun analyzeSharedFile(selectedUri: String, retry: Boolean = false) {
        if (busy || selectedUri.isBlank()) return
        if (!retry && !shouldAutoAnalyzeSharedAudio(selectedUri, lastAnalysisAttemptUri, busy)) return
        lastAnalysisAttemptUri = selectedUri
        busy = true
        status = "正在分析实际音频，完成后角色才能了解声音内容…"
        scope.launch {
            val result = LuluMusicSensoryRuntime.senseSelected(
                context, characterId, Uri.parse(selectedUri))
            if (selectedUri == uri) {
                status = result.fold(
                    onSuccess = { "音频已分享给角色：" + it },
                    onFailure = { "音频分析失败：" + (it.message ?: "未知错误") })
            }
            busy = false
        }
    }

    LaunchedEffect(mode) {
        if (mode == "system") {
            while (isActive) {
                refreshExternal()
                delay(3_000L)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
            if (persisted) {
                mode = "local"
                uri = picked.toString()
                lastAnalysisAttemptUri = ""
                prefs.edit().putString(key + "audio", uri).putInt(key + "position", 0).apply()
                status = ""
            } else status = "无法持久读取这份音频，请重新选择"
        }
    }

    LaunchedEffect(uri) {
        player?.release()
        player = null
        playing = false
        elapsed = 0
        duration = 0
        if (uri.isBlank()) return@LaunchedEffect
        val prepared = withContext(Dispatchers.IO) {
            runCatching { MediaPlayer.create(context, Uri.parse(uri)) }.getOrNull()
        }
        if (prepared == null) {
            status = "音频无法播放，可能是文件失效或格式不受支持"
            return@LaunchedEffect
        }
        prepared.setOnCompletionListener { playing = false }
        val oldPosition = prefs.getInt(key + "position", 0).coerceAtLeast(0)
        if (oldPosition in 1 until prepared.duration) {
            prepared.seekTo(oldPosition)
            elapsed = oldPosition
        }
        duration = prepared.duration.coerceAtLeast(0)
        player = prepared
        if (autoPlaySelectedUri == uri) {
            autoPlaySelectedUri = ""
            runCatching {
                prepared.start()
                playing = prepared.isPlaying
                if (playing) {
                    recordListening("用户在一起听歌单中选中并播放本地音频：" + audioName +
                        "；角色是否听懂以独立音频感知回执为准。")
                    analyzeSharedFile(uri)
                }
            }.onFailure { status = "播放选中音频失败：" + it.message.orEmpty() }
        }
    }

    LaunchedEffect(SharedMusicWindowController.requestedTrack?.id) {
        val entry = SharedMusicWindowController.requestedTrack ?: return@LaunchedEffect
        if (SharedMusicWindowController.room?.characterId != characterId) return@LaunchedEffect
        if (entry.source == "local") {
            if (entry.uri.isBlank()) {
                status = "这条本地收藏没有可访问的文件"
            } else {
                mode = "local"
                if (entry.uri == uri) {
                    runCatching {
                        player?.seekTo(0)
                        player?.start()
                        playing = player?.isPlaying == true
                    }.onFailure { status = "无法播放：" + it.message.orEmpty() }
                    if (playing) {
                        recordListening("用户在歌单中播放本地歌曲：" + entry.title)
                        analyzeSharedFile(uri)
                    }
                } else {
                    autoPlaySelectedUri = entry.uri
                    uri = entry.uri
                    lastAnalysisAttemptUri = ""
                    prefs.edit().putString(key + "audio", uri).putInt(key + "position", 0).apply()
                }
            }
        } else {
            mode = "system"
            status = NeteaseSongLink.open(context, entry)
            // The external app owns playback; only a later real MediaSession
            // state may confirm the track rather than this open request.
        }
    }
    LaunchedEffect(player) {
        while (isActive) {
            player?.let { current ->
                elapsed = runCatching { current.currentPosition }.getOrDefault(elapsed)
                playing = runCatching { current.isPlaying }.getOrDefault(false)
            }
            delay(800L)
        }
    }
    DisposableEffect(Unit) {
        prefs.edit().putString(key + "room", roomId).apply()
        onDispose {
            player?.let { active ->
                runCatching {
                    prefs.edit().putInt(key + "position", active.currentPosition).apply()
                    active.release()
                }
            }
        }
    }

    fun controlExternal(command: String) {
        scope.launch {
            val raw = withContext(Dispatchers.IO) {
                LuluMediaPlayback.control(context, command, track?.packageName.orEmpty())
            }
            val result = runCatching { JSONObject(raw) }.getOrNull()
            status = when {
                result?.optBoolean("success") == true -> ""
                result?.optBoolean("accepted") == true ->
                    "已发出控制请求，等待播放器报告新状态"
                else -> result?.optString("error").orEmpty().ifBlank { "播放器未接受操作" }
            }
            delay(400L)
            refreshExternal()
        }
    }

    fun toggleLocal() {
        player?.let { active ->
            runCatching {
                if (active.isPlaying) active.pause()
                else {
                    active.start()
                    if (active.isPlaying) {
                        recordListening("用户与角色播放本地音频：" + audioName +
                            "；音频内容仍以实际感知回执为准。")
                        analyzeSharedFile(uri)
                    }
                }
                playing = active.isPlaying
            }.onFailure { status = "播放失败：" + it.message.orEmpty() }
        }
    }

    fun skipLocal(next: Boolean) {
        val songs = SharedListenPlaylists.read(context, characterId)
            .flatMap { it.tracks }.filter { it.source == "local" && it.uri.isNotBlank() }
        val index = songs.indexOfFirst { it.uri == uri }
        val target = if (next) index + 1 else index - 1
        if (index < 0 || target !in songs.indices) {
            status = "当前歌曲不在歌单顺序中，或已是第一/最后一首"
        } else {
            autoPlaySelectedUri = songs[target].uri
            uri = songs[target].uri
            lastAnalysisAttemptUri = ""
            prefs.edit().putString(key + "audio", uri).putInt(key + "position", 0).apply()
        }
    }

    ListenTogetherMiniWindow(
        roleName = characterName,
        roleAvatar = characterAvatarUri,
        systemMode = mode == "system",
        external = track,
        externalError = sessionError,
        localName = audioName,
        localPlaying = playing,
        localUri = uri,
        localPosition = elapsed,
        localDuration = duration,
        status = status,
        onToggleMode = { mode = if (mode == "system") "local" else "system"; status = "" },
        onToggle = { if (mode == "system") controlExternal(if (track?.playing == true) "pause" else "play")
            else toggleLocal() },
        onPrevious = { if (mode == "system") controlExternal("previous") else skipLocal(false) },
        onNext = { if (mode == "system") controlExternal("next") else skipLocal(true) },
        onSelectLocal = { picker.launch(arrayOf("audio/*")) },
        onOpenMusic = onOpenMusic,
        onOpenNetease = {
            val launch = context.packageManager.getLaunchIntentForPackage(NeteaseSongLink.packageName)
            status = if (launch == null) "请先安装网易云音乐并在官方 App 登录"
                else runCatching { context.startActivity(launch); "已打开网易云，请在官方 App 选择歌曲" }
                    .getOrElse { "打开网易云失败：" + it.message.orEmpty() }
        },
        onJoin = {
            track?.takeIf { it.playing }?.let {
                recordListening("用户邀请角色一起听手机媒体会话：" + it.title +
                    "；歌手=" + it.artist + "；来源=" + it.packageName +
                    "。仅获得媒体元数据，未获取实际音频。")
                status = "已加入一起听 ♡"
            }
        },
        onClose = onClose,
    )
}
