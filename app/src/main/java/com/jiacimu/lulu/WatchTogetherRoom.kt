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

/**
 * Decode the selected moments in one local pass. The file is not uploaded:
 * only these opt-in images go through the existing model API call.
 * Multi-frame is an approximate storyboard, not continuous video/audio vision.
 */
private suspend fun watchFrames(
    context: Context, uri: Uri, times: List<Long>,
): List<Pair<Long, String>> = withContext(Dispatchers.IO) {
    if (times.isEmpty()) return@withContext emptyList()
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        times.mapNotNull { atMs ->
            runCatching {
                val image = retriever.getFrameAtTime(
                    (atMs.coerceAtLeast(0L) * 1000L).coerceAtLeast(0L),
                    MediaMetadataRetriever.OPTION_CLOSEST,
                ) ?: return@runCatching null
                try {
                    val ratio = minOf(1f, 640f / image.width.coerceAtLeast(1))
                    val small = if (ratio < 1f) Bitmap.createScaledBitmap(
                        image, (image.width * ratio).toInt().coerceAtLeast(1),
                        (image.height * ratio).toInt().coerceAtLeast(1), true,
                    ) else image
                    try {
                        val bytes = ByteArrayOutputStream()
                        check(small.compress(Bitmap.CompressFormat.JPEG, 76, bytes))
                        atMs to ("data:image/jpeg;base64," +
                            Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
                    } finally {
                        if (small !== image) small.recycle()
                    }
                } finally { image.recycle() }
            }.getOrNull()
        }
    } catch (_: Exception) {
        emptyList()
    } finally { retriever.release() }
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
    var auto by remember(key) { mutableStateOf(prefs.getBoolean(key + "automatic", true)) }
    var observedAt by remember(roomId) { mutableLongStateOf(prefs.getLong(key + roomId + ":observed", 0L)) }
    val evidence = remember(roomId) {
        WatchPlaybackEvidence.restore(prefs.getString(key + roomId + ":watched", "").orEmpty())
    }
    var observationSerial by remember(roomId) { mutableIntStateOf(0) }
    var requestRevision by remember(roomId) { mutableIntStateOf(0) }
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
    fun save() {
        prefs.edit()
            .putLong(key + roomId + ":position", video?.currentPosition?.toLong() ?: progress)
            .putString(key + roomId + ":watched", evidence.encode())
            .apply()
    }
    fun moveTo(position: Long) {
        val to = position.coerceIn(0L, duration.coerceAtLeast(0L))
        requestRevision += 1
        evidence.pause()
        video?.seekTo(to.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        progress = to
        if (playing) evidence.begin(to)
        save()
    }
    fun exit() {
        save()
        video?.pause()
        evidence.pause()
        playing = false
        onClose()
    }

    LaunchedEffect(roomId) {
        refresh()
        record("观影状态", characterName + "和你进入了同一个观影房间；片源尚未开始时一起等候。",
            EventEvidenceKind.Observation, id = "room-arrival")
    }
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
                requestRevision += 1
                prefs.edit().putString(key + "video", videoUri).putString(key + "subtitle", "")
                    .putString(key + "room", next).putLong(key + next + ":position", 0L).apply()
                error = ""
                record("观影状态", "你选择了本地影片：" + watchFilename(context, uri) +
                    "；双方已在房间等待正式播放。", EventEvidenceKind.Observation, id = "media-selected")
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

    fun react(comment: String = "", automatic: Boolean = false, completed: Boolean = false) {
        if (!ready || videoUri.isBlank() || busy) return
        val at = if (completed) duration else video?.currentPosition?.toLong() ?: progress
        if (automatic && !completed && (evidence.observedMs(at) < 45_000L ||
                at - observedAt < 90_000L)) return
        if (automatic && completed &&
            prefs.getBoolean(key + roomId + ":end-considered", false)) return
        val currentRoom = roomId
        val revision = requestRevision
        val uri = Uri.parse(videoUri)
        val title = watchFilename(context, uri)
        // Snapshot exactly the watch evidence at this decision, not a guessed
        // 0..playhead interval. An old model reply cannot survive a seek/new file.
        val snapshot = WatchPlaybackEvidence.restore(evidence.encode())
        val subtitles = watchedSubtitles(cues, snapshot, at)
        val frameTimes = snapshot.frameTimes(at)
        if (automatic) {
            observedAt = at
            prefs.edit().putLong(key + currentRoom + ":observed", at)
                .putBoolean(key + currentRoom + ":end-considered",
                    completed || prefs.getBoolean(key + currentRoom + ":end-considered", false))
                .apply()
        }
        if (comment.isNotBlank()) record(UserProfileContext.displayLabel(),
            "[" + (at / 1000) + "秒] " + comment, EventEvidenceKind.UserStatement)
        val history = notes.takeLast(16).joinToString("\n") {
            it.speaker + "：" + it.content.take(230)
        }
        busy = true
        error = ""
        scope.launch {
            try {
                val storyboard = watchFrames(context, uri, frameTimes)
                val facts = buildString {
                    appendLine("你和用户已经进入同一个观影房间，正在一起看：" + title)
                    appendLine("目前视频播放头：" + (at / 1000) + "秒；" +
                        (if (completed) "影片刚播放完。" else if (playing) "正在播放。" else "目前暂停。"))
                    appendLine("应用确实追踪到的观看时长：" + (snapshot.observedMs(at) / 1000) +
                        "秒。拖动跳过的部分不算看过。")
                    appendLine("最近实际看过且已经结束的字幕：")
                    appendLine(subtitles.ifBlank { "没有可读取的字幕；不能因此臆测对白。" })
                    appendLine("这次提供的实际画面时间点（与附图按顺序一一对应）：")
                    appendLine(storyboard.joinToString("；") { it.first.toString() + "ms" }
                        .ifBlank { "没有可用关键画面。" })
                    appendLine("这是从已看过时段抽样的若干帧，不是完整逐帧视频；" +
                        "不能声称听到了音频、连续看到了所有动作。")
                    appendLine("同一个房间过去的观影交流和你的阶段主观印象：\n" + history)
                    appendLine("此刻用户的表达：" + comment.ifBlank {
                        if (automatic) "没有新提问；由你自己决定是否开口。"
                        else "用户想听听你对目前共同看过内容的想法。"
                    })
                }
                val raw = LuluAiServices.gateway.generate(
                    characterId = characterId, facts = facts,
                    instruction = """
                        这是露露机真实生活里的共同观影场景，不是一次陌生的照片鉴定。
                        你早已入场，并和用户共享同一段实际播放经历。你的身份、
                        个性、关系和上一轮真实观影印象均与其他聊天场景一致。
                        根据提供的按时间顺序关键画面、已经实际播放完的字幕、房间历史，
                        自己理解发生了什么、之前的猜测是否被推翻、接下来想不想交流。
                        影视内容是虚构作品，不是你和用户的真实身世、经历或偏好。
                        不知道的情节要承认未知；未看到的片段、未来剧情不能编造。
                        如果材料不足，不用装作看懂了完整剧情。
                        可以安静看，也可以说出本人自然的反应；不要机械评论每张画面。
                        如果用户真的在问话，优先认真接话，而不是强行沉默。
                        只输出 JSON：
                        {"speak":true,"text":"此刻真正想对她说的话","impression":"你自己对已见剧情的简要阶段印象"}
                        或 {"speak":false,"text":"","impression":"你自己本轮真实的阶段印象"}
                        impression 是你的主观看法，不是事实和对用户人格的判断。
                    """.trimIndent(),
                    source = "一起看", title = "一起看 · " + characterName,
                    usage = ModelUsage.Chat, maxTokens = 440,
                    imageDataUrls = storyboard.map { it.second },
                ).getOrThrow().text.trim()
                if (roomId != currentRoom || requestRevision != revision) return@launch
                val fence = 96.toChar().toString().repeat(3)
                val cleaned = raw.removePrefix(fence + "json").removePrefix(fence)
                    .removeSuffix(fence).trim()
                val json = runCatching { JSONObject(cleaned) }.getOrNull()
                val speech = if (json != null) {
                    if (json.optBoolean("speak", true)) json.optString("text").trim() else ""
                } else raw.takeUnless { it.startsWith("{") || it.startsWith("[") }.orEmpty()
                val impression = json?.optString("impression")?.trim().orEmpty()
                if (impression.isNotBlank()) {
                    record(characterName, "[" + (at / 1000) + "秒] 片中阶段印象（主观）：" +
                        impression, EventEvidenceKind.Inference)
                }
                if (speech.isNotBlank()) {
                    record(characterName, "[" + (at / 1000) + "秒] " + speech,
                        EventEvidenceKind.CharacterStatement)
                }
            } catch (e: Exception) {
                if (roomId == currentRoom) error = "观影理解失败：" +
                    (e.message ?: "请检查所选聊天模型是否支持多张图")
            } finally { busy = false }
        }
    }

    DisposableEffect(video, roomId) {
        val attachedPlayer = video
        onDispose {
            // Keep exact watched ranges and a seekable playhead on restart.
            prefs.edit().putLong(key + roomId + ":position", attachedPlayer?.currentPosition?.toLong() ?: progress)
                .putString(key + roomId + ":watched", evidence.encode()).apply()
            evidence.pause()
            attachedPlayer?.pause()
        }
    }
    LaunchedEffect(video, videoUri, roomId, playing, auto, busy) {
        val v = video ?: return@LaunchedEffect
        while (isActive) {
            delay(1_000)
            if (ready && videoUri.isNotBlank()) {
                val current = v.currentPosition.toLong().coerceAtLeast(0L)
                if (playing && v.isPlaying) evidence.advance(current, true)
                else evidence.pause()
                progress = current
                observationSerial += 1
                if (observationSerial % 4 == 0) save()
                if (playing && auto && !busy && current >= 45_000L &&
                    current - observedAt >= 90_000L && evidence.observedMs(current) >= 45_000L) {
                    react(automatic = true)
                }
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
                Text(
                    if (videoUri.isBlank()) "已和" + characterName + "进入观影房间 · 等你选片"
                    else if (playing) "正在一起看 · 已记录真实观看 " + (evidence.observedMs(progress) / 1000) + " 秒"
                    else "同一观影房间 · 已记录真实观看 " + (evidence.observedMs(progress) / 1000) + " 秒",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                )
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
                                val saved = prefs.getLong(key + roomId + ":position",
                                    prefs.getLong(key + "position", 0L))
                                if (saved > 0L) view.seekTo(saved.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                            }
                            view.setOnErrorListener { _, _, _ ->
                                ready = false
                                playing = false
                                error = "视频解码失败，可尝试 MP4（H.264/AAC）"
                                true
                            }
                        }
                    }, update = { v ->
                        // Update the callback whenever the selected room / evidence changes.
                        // A listener from an older video must not write into the new room.
                        v.setOnCompletionListener {
                            if (playing) evidence.advance(duration, true)
                            evidence.pause()
                            playing = false
                            progress = duration
                            prefs.edit().putLong(key + roomId + ":position", duration)
                                .putString(key + roomId + ":watched", evidence.encode()).apply()
                            record("观影状态", "这次影片已从实际播放进度到达结尾：" + videoTitle,
                                EventEvidenceKind.Observation, id = "completed")
                            if (auto && !busy && evidence.observedMs(duration) >= 2_000L) {
                                react(automatic = true, completed = true)
                            }
                        }
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
                        onValueChange = { moveTo((it * duration).toLong()) },
                        enabled = ready && duration > 0)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text((progress / 60_000).toString() + ":" + ((progress / 1000) % 60).toString().padStart(2, '0') +
                            " / " + (duration / 60_000) + ":" + ((duration / 1000) % 60).toString().padStart(2, '0'),
                            fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        IconButton(enabled = ready, onClick = {
                            moveTo(progress - 10_000)
                        }) { Icon(Icons.Outlined.Replay10, "倒退十秒") }
                        IconButton(enabled = ready, onClick = {
                            if (playing) {
                                evidence.advance(video?.currentPosition?.toLong() ?: progress, true)
                                evidence.pause()
                                video?.pause()
                                playing = false
                                save()
                            } else {
                                if (duration > 0L && progress >= duration) moveTo(0)
                                evidence.begin(video?.currentPosition?.toLong() ?: progress)
                                video?.start()
                                playing = true
                                if (!prefs.getBoolean(key + roomId + ":started", false)) {
                                    record("观影状态", "你和" + characterName + "从播放器正式开始一起看：" + videoTitle,
                                        EventEvidenceKind.Observation, id = "started")
                                    prefs.edit().putBoolean(key + roomId + ":started", true).apply()
                                }
                            }
                        }) { Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, "播放或暂停") }
                        IconButton(enabled = ready, onClick = {
                            moveTo(progress + 10_000)
                        }) { Icon(Icons.Outlined.Forward10, "快进十秒") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("自主观影交流（调用聊天模型）", fontSize = 12.sp, modifier = Modifier.weight(1f))
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
                    items(notes.filter {
                        it.evidenceKind == EventEvidenceKind.CharacterStatement ||
                            it.evidenceKind == EventEvidenceKind.UserStatement
                    }, key = { it.id }) { note ->
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
