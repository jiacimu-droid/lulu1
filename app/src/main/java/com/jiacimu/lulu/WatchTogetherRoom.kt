package com.jiacimu.lulu

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.data.EventEvidenceKind
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.SharedTimelineEvent
import com.jiacimu.lulu.data.UserProfileContext
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A time-bounded piece of film dialogue, not a future screenplay. */
internal data class WatchSubtitleCue(val startMs: Long, val endMs: Long, val text: String)

internal fun parseWatchSubtitles(input: String): List<WatchSubtitleCue> {
    val normalized = input.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    return normalized.split(Regex("\n[ \t]*\n")).mapNotNull { block ->
        val lines = block.lines().filter(String::isNotBlank)
        val at = lines.indexOfFirst { "-->" in it }
        if (at < 0) return@mapNotNull null
        val stamps = lines[at].split("-->", limit = 2)
        fun stamp(raw: String): Long? {
            val parts = raw.trim().substringBefore(' ').replace(',', '.').split(':')
            if (parts.size !in 2..3) return null
            return runCatching {
                val sec = parts.last().toDouble()
                val min = parts[parts.lastIndex - 1].toLong()
                val hour = if (parts.size == 3) parts.first().toLong() else 0L
                if (!sec.isFinite() || sec < 0 || min !in 0L..59L || hour < 0) null
                else ((hour * 3600 + min * 60) * 1000 + sec * 1000).toLong()
            }.getOrNull()
        }
        val start = stamp(stamps[0]) ?: return@mapNotNull null
        val end = stamp(stamps[1]) ?: return@mapNotNull null
        val text = lines.drop(at + 1).joinToString(" ").replace(Regex("<[^>]+>"), "").trim()
        if (start >= end || text.isEmpty()) null else WatchSubtitleCue(start, end, text)
    }.sortedBy(WatchSubtitleCue::startMs)
}

/** No cue that has not finished playing is permitted to cross the spoiler gate. */
internal fun watchedSubtitleWindow(cues: List<WatchSubtitleCue>, playheadMs: Long): String =
    cues.asSequence().filter { it.endMs <= playheadMs && it.startMs >= (playheadMs - 180_000).coerceAtLeast(0) }
        .toList().takeLast(24).joinToString("\n") { "[" + it.startMs / 1000 + "s] " + it.text }

private fun watchFilename(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.takeIf(String::isNotBlank) ?: uri.lastPathSegment.orEmpty().takeLast(80)

private suspend fun watchSubtitleFile(context: Context, uri: Uri): List<WatchSubtitleCue> =
    withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                check(out.size() + n <= 16 * 1024 * 1024) { "字幕过大，不能安全解析" }
                out.write(buf, 0, n)
            }
            parseWatchSubtitles(out.toString(Charsets.UTF_8.name()))
        }.orEmpty()
    }

/** Extract a single past-or-current frame on demand, without streaming the entire movie to AI. */
private suspend fun watchFrame(context: Context, uri: Uri, atMs: Long): String? = withContext(Dispatchers.IO) {
    runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val image = retriever.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
                ?: return@runCatching null
            val ratio = minOf(1f, 640f / image.width.coerceAtLeast(1))
            val small = if (ratio < 1) Bitmap.createScaledBitmap(image,
                (image.width * ratio).toInt().coerceAtLeast(1),
                (image.height * ratio).toInt().coerceAtLeast(1), true) else image
            val bytes = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 76, bytes)
            if (small !== image) small.recycle()
            image.recycle()
            "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
        } finally {
            retriever.release()
        }
    }.getOrNull()
}

/** A scene adapter: current character gateway, model archive, and ONE shared evidence timeline. */
@Composable
internal fun WatchTogetherRoom(
    conversationId: String, characterId: String, characterName: String, onClose: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("lulu_watch_together_v1", Context.MODE_PRIVATE) }
    val key = remember(conversationId) { "chat:" + conversationId + ":" }
    var videoUri by remember(key) { mutableStateOf(prefs.getString(key + "video", "").orEmpty()) }
    var subtitleUri by remember(key) { mutableStateOf(prefs.getString(key + "subtitle", "").orEmpty()) }
    var roomId by remember(key) {
        mutableStateOf(prefs.getString(key + "room", "").orEmpty().ifBlank { UUID.randomUUID().toString() })
    }
    var video by remember { mutableStateOf<VideoView?>(null) }
    var loadedUri by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var cues by remember { mutableStateOf(emptyList<WatchSubtitleCue>()) }
    var notes by remember { mutableStateOf(emptyList<SharedTimelineEvent>()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var auto by remember(key) { mutableStateOf(prefs.getBoolean(key + "automatic", false)) }
    var observedAt by remember(roomId) { mutableLongStateOf(prefs.getLong(key + roomId + ":observed", 0L)) }
    val scope = rememberCoroutineScope()
    val videoTitle = remember(videoUri) {
        if (videoUri.isBlank()) "" else watchFilename(context, Uri.parse(videoUri))
    }

    fun refresh() {
        notes = SharedExperienceTimeline.sessionEvents(characterId, roomId, 160)
            .filter { it.channel == "一起看" && it.source == "watch-together" }
    }
    fun record(speaker: String, message: String, kind: EventEvidenceKind, id: String = UUID.randomUUID().toString()) {
        SharedExperienceTimeline.record(
            eventId = "watch:" + roomId + ":" + id, characterId = characterId,
            channel = "一起看", speaker = speaker, content = message, occurredAt = Instant.now(),
            triggerExtraction = false, sessionId = roomId, source = "watch-together", evidenceKind = kind,
        )
        refresh()
    }
    fun save() { prefs.edit().putLong(key + "position", video?.currentPosition?.toLong() ?: progress).apply() }
    fun exit() {
        save()
        video?.pause()
        playing = false
        onClose()
    }

    LaunchedEffect(roomId) { refresh() }
    LaunchedEffect(subtitleUri) {
        cues = emptyList()
        if (subtitleUri.isNotBlank()) {
            runCatching { watchSubtitleFile(context, Uri.parse(subtitleUri)) }
                .onSuccess { cues = it }
                .onFailure { error = "字幕读取失败：" + it.message }
        }
    }

    val chooseVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val ok = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
            if (!ok) error = "视频文件未授权长期访问，请重新选择"
            else {
                save()
                video?.stopPlayback()
                val next = UUID.randomUUID().toString()
                videoUri = uri.toString()
                subtitleUri = ""
                roomId = next
                loadedUri = ""
                progress = 0L
                duration = 0L
                ready = false
                playing = false
                notes = emptyList()
                prefs.edit().putString(key + "video", videoUri).putString(key + "subtitle", "")
                    .putString(key + "room", next).putLong(key + "position", 0L).apply()
                error = ""
            }
        }
    }
    val chooseSubtitle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val ok = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
            if (ok) {
                subtitleUri = uri.toString()
                prefs.edit().putString(key + "subtitle", subtitleUri).apply()
            } else error = "字幕文件无法长期访问，请重新选择"
        }
    }

    fun react(comment: String = "", automatic: Boolean = false) {
        if (!ready || videoUri.isBlank() || busy) return
        val at = video?.currentPosition?.toLong() ?: progress
        if (automatic && (at < 120_000 || at - observedAt < 120_000)) return
        val currentRoom = roomId
        val uri = Uri.parse(videoUri)
        val title = watchFilename(context, uri)
        val subtitles = watchedSubtitleWindow(cues, at)
        if (automatic) {
            observedAt = at
            prefs.edit().putLong(key + currentRoom + ":observed", at).apply()
        }
        if (comment.isNotBlank()) record(UserProfileContext.displayLabel(),
            "[" + (at / 1000) + "秒] " + comment, EventEvidenceKind.UserStatement)
        val history = notes.takeLast(12).joinToString("\n") { it.speaker + "：" + it.content.take(200) }
        busy = true
        error = ""
        scope.launch {
            try {
                val frame = watchFrame(context, uri, at)
                val facts = buildString {
                    appendLine("此刻和用户在露露机上共同观看真实本地视频：" + title)
                    appendLine("播放器当前：" + (at / 1000) + "秒；以下内容只来自已经播放到的位置。")
                    appendLine("当前播放位置以前最近三分钟的字幕（跳播的区间不等于实际看过）：")
                    appendLine(subtitles.ifBlank { "没有可读取的定时对白，不能臆测台词。" })
                    appendLine(if (frame == null) "未取得视频画面，不能声称看见人物和场景。" else
                        "附带仅这一时刻的单张画面，不代表连续看到了所有动作。")
                    appendLine("房间中已经发生的交流：\n" + history)
                    appendLine("用户此刻的话：" + comment.ifBlank { "没有新提问；决定自己要不要说话。" })
                }
                val raw = LuluAiServices.gateway.generate(
                    characterId = characterId, facts = facts,
                    instruction = """
                        当前是你和用户一起看电影的生活场景，继承你本人的完整性格、关系与记忆。
                        只依据已经展示的字幕与当前画面，不预测、不剧透尚未播出的情节；
                        电影人物与事件都是片中虚构内容，不能误认为你与用户的现实经历。
                        由你本人决定吐槽、好奇、回复还是安静看。不要机械定时解说或写长篇作文。
                        回复严格用 JSON：{"speak":true,"text":"想说的自然台词"}；
                        不想说话时：{"speak":false,"text":""}。
                        没有提供音轨识别结果时，不能自称听见声音，也不要虚构播放器操作。
                    """.trimIndent(),
                    source = "一起看", title = "一起看 · " + characterName,
                    maxTokens = 330, usage = ModelUsage.Chat,
                    imageDataUrls = frame?.let { listOf(it) } ?: emptyList(),
                ).getOrThrow().text.trim()
                if (roomId != currentRoom || (video?.currentPosition?.toLong() ?: 0) + 15_000 < at) return@launch
                val fence = 96.toChar().toString().repeat(3)
                val cleaned = raw.removePrefix(fence + "json").removePrefix(fence).removeSuffix(fence).trim()
                val json = runCatching { JSONObject(cleaned) }.getOrNull()
                val speech = if (json != null) {
                    if (json.optBoolean("speak", true)) json.optString("text").trim() else ""
                } else raw.takeUnless { it.startsWith("{") || it.startsWith("[") }.orEmpty()
                if (speech.isNotBlank()) record(characterName,
                    "[" + (at / 1000) + "秒] " + speech, EventEvidenceKind.CharacterStatement)
            } catch (e: Exception) {
                if (roomId == currentRoom) error = "观影理解未成功：" + (e.message ?: "请检查聊天模型")
            } finally { busy = false }
        }
    }

    DisposableEffect(video) {
        onDispose {
            video?.let { player ->
                prefs.edit().putLong(key + "position", player.currentPosition.toLong()).apply()
                player.stopPlayback()
            }
        }
    }
    LaunchedEffect(video, videoUri, playing, auto, busy) {
        val v = video ?: return@LaunchedEffect
        while (isActive) {
            delay(1_000)
            if (ready && videoUri.isNotBlank()) {
                progress = v.currentPosition.toLong()
                if (progress > 0 && progress % 5_000 < 1_000)
                    prefs.edit().putLong(key + "position", progress).apply()
                if (playing && auto && !busy && progress >= 120_000 && progress - observedAt >= 120_000)
                    react(automatic = true)
            }
        }
    }

    Dialog(onDismissRequest = ::exit, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::exit) { Icon(Icons.Outlined.ArrowBack, "返回聊天") }
                    Text("一起看 · " + characterName, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f), maxLines = 1)
                    TextButton(onClick = { chooseVideo.launch(arrayOf("video/*")) }) { Text("选视频") }
                    TextButton(enabled = videoUri.isNotBlank(),
                        onClick = { chooseSubtitle.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream")) }) {
                        Text("字幕")
                    }
                }
                if (videoUri.isBlank()) {
                    Column(Modifier.fillMaxWidth().height(220.dp)
                        .background(Color(0xFF171A20), RoundedCornerShape(16.dp)),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Movie, null, Modifier.size(40.dp), tint = Color.White)
                        Text("从手机选择一部影片，和 " + characterName + " 一起看", color = Color.White, fontSize = 13.sp)
                        Button(onClick = { chooseVideo.launch(arrayOf("video/*")) }) { Text("选择视频") }
                    }
                } else {
                    Text(videoTitle, fontSize = 13.sp, maxLines = 1)
                    AndroidView(factory = { ctx ->
                        VideoView(ctx).also { view ->
                            video = view
                            view.setOnPreparedListener {
                                ready = true
                                duration = view.duration.toLong().coerceAtLeast(0L)
                                val saved = prefs.getLong(key + "position", 0L)
                                if (saved > 0L) view.seekTo(saved.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                            }
                            view.setOnErrorListener { _, _, _ ->
                                ready = false
                                playing = false
                                error = "视频解码失败，可尝试 MP4（H.264/AAC）"
                                true
                            }
                            view.setOnCompletionListener {
                                playing = false
                                record("观影状态", "影片已播放到结尾：" + videoTitle,
                                    EventEvidenceKind.Observation, id = "completed")
                            }
                        }
                    }, update = { v ->
                        if (loadedUri != videoUri) {
                            loadedUri = videoUri
                            ready = false
                            playing = false
                            v.setVideoURI(Uri.parse(videoUri))
                        }
                    }, modifier = Modifier.fillMaxWidth().height(220.dp).background(Color.Black))
                    cues.lastOrNull { progress >= it.startMs && progress < it.endMs }?.let {
                        Text(it.text, modifier = Modifier.fillMaxWidth(), fontSize = 14.sp)
                    }
                    Slider(value = if (duration > 0) (progress.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = {
                            val target = (it * duration).toLong()
                            progress = target
                            video?.seekTo(target.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                            prefs.edit().putLong(key + "position", target).apply()
                        }, enabled = ready && duration > 0)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text((progress / 60_000).toString() + ":" + ((progress / 1000) % 60).toString().padStart(2, '0') +
                            " / " + (duration / 60_000) + ":" + ((duration / 1000) % 60).toString().padStart(2, '0'),
                            fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        IconButton(enabled = ready, onClick = {
                            video?.seekTo((progress - 10_000).coerceAtLeast(0).toInt())
                        }) { Icon(Icons.Outlined.Replay10, "倒退十秒") }
                        IconButton(enabled = ready, onClick = {
                            if (playing) { video?.pause(); playing = false }
                            else {
                                video?.start(); playing = true
                                if (!prefs.getBoolean(key + roomId + ":started", false)) {
                                    record("观影状态", "开始播放本地视频：" + videoTitle,
                                        EventEvidenceKind.Observation, id = "started")
                                    prefs.edit().putBoolean(key + roomId + ":started", true).apply()
                                }
                            }
                        }) { Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, "播放或暂停") }
                        IconButton(enabled = ready, onClick = {
                            video?.seekTo((progress + 10_000).coerceAtMost(duration).toInt())
                        }) { Icon(Icons.Outlined.Forward10, "快进十秒") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("自动感想（约每两分钟一次，可沉默）", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Switch(checked = auto, onCheckedChange = {
                        auto = it
                        prefs.edit().putBoolean(key + "automatic", it).apply()
                    })
                    OutlinedButton(onClick = { react() }, enabled = ready && !busy) { Text("问问他") }
                }
                if (error.isNotBlank()) Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                HorizontalDivider()
                Text("观影聊天", style = MaterialTheme.typography.titleSmall)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(notes, key = { it.id }) { note ->
                        Column {
                            Text(note.speaker, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(note.content, fontSize = 13.sp)
                        }
                    }
                    if (busy) item { Text(characterName + " 正在想这一段……", fontSize = 12.sp) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = input, onValueChange = { input = it },
                        modifier = Modifier.weight(1f), maxLines = 3, placeholder = { Text("边看边聊……") })
                    IconButton(enabled = input.isNotBlank() && ready && !busy, onClick = {
                        val message = input.trim()
                        input = ""
                        react(comment = message)
                    }) { Icon(Icons.Outlined.Send, "发送观影消息") }
                }
            }
        }
    }
}
