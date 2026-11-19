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

/** Metadata is NOT audible waveform evidence; no player control can change this distinction. */
internal fun listenedMediaSession(raw: String): Pair<SharedListeningTrack?, String> {
    val root = runCatching { JSONObject(raw) }.getOrNull()
        ?: return null to "媒体会话数据无效"
    if (!root.optBoolean("success")) return null to root.optString("error", "读取媒体会话失败")
    val sessions = root.optJSONArray("sessions")
    val candidates = (0 until (sessions?.length() ?: 0)).mapNotNull { sessions?.optJSONObject(it) }
    val selected = candidates.firstOrNull {
        it.optString("packageName") == "com.netease.cloudmusic" && it.optBoolean("playing")
    } ?: candidates.firstOrNull { it.optBoolean("playing") }
        ?: candidates.firstOrNull { it.optString("packageName") == "com.netease.cloudmusic" }
        ?: candidates.firstOrNull()
        ?: return null to "未发现播放器。请在网易云音乐里播放一次，或检查通知使用权"
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
    onDiscuss: (String) -> Boolean,
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
    val messages by MigratedDomainStores.chat.messages(conversationId).collectAsState()

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
        val result = listenedMediaSession(LuluMediaPlayback.sessions(context))
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

    ListenTogetherPlayerScreen(
        characterId = characterId,
        characterName = characterName,
        characterAvatarUri = characterAvatarUri,
        mode = mode,
        onModeChange = { mode = it; status = "" },
        external = track,
        externalError = sessionError,
        onRefreshExternal = { refreshExternal() },
        onExternalControl = { command ->
            scope.launch {
                val raw = withContext(Dispatchers.IO) {
                    LuluMediaPlayback.control(context, command, track?.packageName.orEmpty())
                }
                val result = runCatching { JSONObject(raw) }.getOrNull()
                status = when {
                    result?.optBoolean("success") == true -> ""
                    result?.optBoolean("accepted") == true ->
                        "请求已发给播放器，正在等待最新播放状态"
                    else -> result?.optString("error").orEmpty().ifBlank { "播放器未接受此操作" }
                }
                delay(400L)
                refreshExternal()
            }
        },
        onJoinExternal = {
            track?.let {
                recordListening(
                    "用户邀请角色一起听手机媒体会话：" + it.title +
                        "；歌手=" + it.artist + "；来源=" + it.packageName +
                        "。只取得媒体元数据，尚未提供实际音频或歌词。")
                status = "你们已加入一起听 ♡"
            }
        },
        onOpenNetease = {
            val launch = context.packageManager.getLaunchIntentForPackage("com.netease.cloudmusic")
            if (launch == null) {
                status = "未发现网易云音乐 App，请先在手机安装并播放歌曲"
            } else runCatching { context.startActivity(launch) }
                .onFailure { status = "打开网易云失败：" + it.message.orEmpty() }
        },
        localName = audioName,
        localUri = uri,
        localPlaying = playing,
        localPosition = elapsed,
        localDuration = duration,
        onSelectLocal = { picker.launch(arrayOf("audio/*")) },
        onLocalToggle = {
            player?.let { active ->
                runCatching {
                    if (active.isPlaying) {
                        active.pause()
                    } else {
                        active.start()
                        if (active.isPlaying) {
                            recordListening(
                                "用户与角色在同一房间播放本地音频：" + audioName +
                                    "；是否听懂以随后独立音频感知回执为准。")
                            analyzeSharedFile(uri)
                        }
                    }
                    playing = active.isPlaying
                }.onFailure { status = "播放失败：" + it.message.orEmpty() }
            }
        },
        onAnalyzeLocal = { analyzeSharedFile(uri, retry = true) },
        onChooseLocal = { selected ->
            if (selected == uri) {
                runCatching { player?.seekTo(0) }
            } else {
                uri = selected
                lastAnalysisAttemptUri = ""
                prefs.edit().putString(key + "audio", uri).putInt(key + "position", 0).apply()
            }
            mode = "local"
        },
        busy = busy,
        status = status,
        messages = messages.takeLast(5),
        onDiscuss = onDiscuss,
        onClose = onClose,
    )
}
