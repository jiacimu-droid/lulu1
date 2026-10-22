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

private data class SharedListeningTrack(
    val title: String, val artist: String, val packageName: String, val playing: Boolean,
)

/** Metadata is NOT audible waveform evidence; no player control can change this distinction. */
private fun listenedMediaSession(raw: String): Pair<SharedListeningTrack?, String> {
    val root = runCatching { JSONObject(raw) }.getOrNull()
        ?: return null to "媒体会话数据无效"
    if (!root.optBoolean("success")) return null to root.optString("error", "读取媒体会话失败")
    val sessions = root.optJSONArray("sessions")
    val playing = (0 until (sessions?.length() ?: 0))
        .mapNotNull { sessions?.optJSONObject(it) }.firstOrNull { it.optBoolean("playing") }
        ?: return null to "没有发现正在播放的可访问媒体会话"
    return SharedListeningTrack(
        playing.optString("title").ifBlank { "未提供歌曲名" },
        playing.optString("artist").ifBlank { "未知歌手" },
        playing.optString("packageName"),
        true,
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
    }

    fun refreshExternal() {
        val result = listenedMediaSession(LuluMediaPlayback.sessions(context))
        track = result.first
        sessionError = result.second
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
                uri = picked.toString()
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

    val visibleMessages = messages.takeLast(8)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(12.dp),
            shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.ArrowBack, "返回聊天") }
                    Text("一起听 · $characterName", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == "system", onClick = { mode = "system" },
                        label = { Text("手机正在放的歌") })
                    FilterChip(selected = mode == "local", onClick = { mode = "local" },
                        label = { Text("本地音频") })
                }
                if (mode == "system") {
                    Text(track?.let { "${it.title} · ${it.artist}" }
                        ?: sessionError.ifBlank { "正在查看播放器" },
                        style = MaterialTheme.typography.bodyLarge)
                    Text("只能从音乐 App 的媒体会话获取曲名和播放状态，不能冒充听到了实际声音。",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = ::refreshExternal) { Text("刷新歌曲") }
                        Button(enabled = track != null, onClick = {
                            track?.let {
                                recordListening("用户邀请角色一起听手机正在播放的媒体：" +
                                    "歌曲=${it.title}；歌手=${it.artist}；来源=${it.packageName}。" +
                                    "这里只取得媒体会话元数据，没有获得声波或歌词，角色不能声称已实际听见。")
                                status = "已记录本次一起听；可以在下方邀请角色聊聊"
                            }
                        }) { Text("加入一起听") }
                    }
                } else {
                    OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }) {
                        Text("选择本地歌曲")
                    }
                    Text(audioName.ifBlank { "尚未选择歌曲" },
                        style = MaterialTheme.typography.bodyLarge)
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Button(enabled = player != null, onClick = {
                            player?.let { active ->
                                if (active.isPlaying) {
                                    active.pause()
                                } else {
                                    active.start()
                                    recordListening("用户与角色开始在同一房间播放本地音频：$audioName。" +
                                        "播放器实际开始播放；角色尚未获得声波分析，不能声称已经听懂音乐。")
                                }
                                playing = active.isPlaying
                            }
                        }) { Text(if (playing) "暂停" else "播放") }
                        Text("${elapsed / 60}:${(elapsed / 1000 % 60).toString().padStart(2, '0')} / " +
                            "${duration / 60000}:${(duration / 1000 % 60).toString().padStart(2, '0')}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Button(enabled = uri.isNotBlank() && !busy, onClick = {
                        val selected = uri
                        busy = true
                        status = "正在用已选线路分析真实音频…"
                        scope.launch {
                            val result = LuluMusicSensoryRuntime.senseSelected(
                                context, characterId, Uri.parse(selected))
                            status = result.fold(
                                onSuccess = { "实际音频感知完成：" + it },
                                onFailure = { "无法分析音频：" + (it.message ?: "未知错误") })
                            busy = false
                        }
                    }) { Text(if (busy) "正在听音频…" else "让角色真正分析这首歌") }
                    Text("使用已配置的听觉模型可能收费；未配置时仅支持符合格式的 PCM16 WAV 免费波形观察。",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("边听边聊", style = MaterialTheme.typography.titleSmall)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(visibleMessages, key = { it.id }) { item ->
                        Text((if (item.sender == com.jiacimu.lulu.data.LuluChatMessage.Sender.User) "你"
                            else if (item.sender == com.jiacimu.lulu.data.LuluChatMessage.Sender.Character)
                                characterName else "记录") + "： " + item.content.take(300),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it },
                        modifier = Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("想和TA聊这首歌…") })
                    Button(enabled = draft.isNotBlank(), onClick = {
                        if (onDiscuss(draft.trim())) draft = ""
                    }) { Text("发送") }
                }
            }
        }
    }
}
