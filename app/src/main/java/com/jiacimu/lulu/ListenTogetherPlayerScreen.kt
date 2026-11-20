package com.jiacimu.lulu

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.design.LocalLuluAppearance
import com.jiacimu.lulu.design.LuluCssBackground
import com.jiacimu.lulu.design.LuluCssPageScope
import com.jiacimu.lulu.design.cssColor
import com.jiacimu.lulu.design.cssDp
import com.jiacimu.lulu.design.cssFontSize
import com.jiacimu.lulu.design.luluCssElement
import com.jiacimu.lulu.design.luluCssStyle

private val listeningInk = Color(0xFF29242B)
private val listeningMuted = Color(0xFF797179)
private val listeningRose = Color(0xFFA87288)
private val listeningPaper = Color(0xFFFBF9F9)

private fun musicTime(ms: Long): String =
    (ms / 60000).toString() + ":" + ((ms / 1000) % 60).toString().padStart(2, '0')

/**
 * Music-room presentation only. Playback comes from real MediaSessions or local
 * MediaPlayer, and playlist entries do not pretend to be licensed stream URLs.
 */
@Composable
internal fun ListenTogetherPlayerScreen(
    characterId: String,
    characterName: String,
    characterAvatarUri: String?,
    mode: String,
    onModeChange: (String) -> Unit,
    external: SharedListeningTrack?,
    externalError: String,
    onRefreshExternal: () -> Unit,
    onExternalControl: (String) -> Unit,
    onJoinExternal: () -> Unit,
    onOpenNetease: () -> Unit,
    localName: String,
    localUri: String,
    localPlaying: Boolean,
    localPosition: Int,
    localDuration: Int,
    onSelectLocal: () -> Unit,
    onLocalToggle: () -> Unit,
    onAnalyzeLocal: () -> Unit,
    onChooseLocal: (String) -> Unit,
    busy: Boolean,
    status: String,
    messages: List<LuluChatMessage>,
    onDiscuss: (String) -> Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val userProfile = remember { context.getSharedPreferences("lulu_user_profile", android.content.Context.MODE_PRIVATE) }
    val userName = remember { userProfile.getString("display_name", "我").orEmpty().ifBlank { "我" } }
    val userAvatarUri = remember { userProfile.getString("avatar_uri", null) }
    var showLibrary by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var newListName by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var libraryMessage by remember { mutableStateOf("") }
    var lists by remember(characterId) { mutableStateOf(SharedListenPlaylists.read(context, characterId)) }
    fun reload() { lists = SharedListenPlaylists.read(context, characterId) }
    val system = mode == "system"
    val name = if (system) external?.title.orEmpty().ifBlank { "从喜欢的歌开始" }
        else localName.ifBlank { "选一首音乐" }
    val artist = if (system) external?.artist.orEmpty().ifBlank { "打开网易云，开始播放" }
        else "本地音频 · 可让角色真正感知"
    val playing = if (system) external?.playing == true else localPlaying
    val position = if (system) external?.positionMs ?: 0L else localPosition.toLong()
    val duration = if (system) external?.durationMs ?: 0L else localDuration.toLong()
    val current = if (system) external?.takeIf { it.title.isNotBlank() && it.title != "未提供歌曲名" }?.let {
        SharedListenEntry(title = it.title, artist = it.artist, source = "external",
            packageName = it.packageName)
    } else localUri.takeIf(String::isNotBlank)?.let {
        SharedListenEntry(title = localName.ifBlank { "本地音频" },
            artist = "", source = "local", uri = it)
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LuluCssPageScope("ListenTogether") {
            // An embedded chat room, not another launcher/App. Parent #Chat and
            // #ChatDetail rules inherit; #ListenTogether rules override locally.
            val roomStyle = luluCssStyle("music-room")
            val baseInk = roomStyle.cssColor("color") ?: listeningInk
            val roomColor = roomStyle.cssColor("background-color")
                ?: Color(LocalLuluAppearance.current.palette.paper.toInt()).let {
                    if (luluCssStyle("music-room").isEmpty()) listeningPaper else it
                }
            val titleStyle = luluCssStyle("music-track-title")
            val artistStyle = luluCssStyle("music-track-artist")
            val progressStyle = luluCssStyle("music-progress")
            val playStyle = luluCssStyle("music-play")
            Box(
                Modifier.fillMaxWidth().fillMaxHeight(.98f)
                    .padding(horizontal = 7.dp, vertical = 4.dp).imePadding()
                    .clip(RoundedCornerShape(28.dp))
                    .background(roomColor).luluCssElement("music-room"),
            ) {
                LuluCssBackground(Modifier.matchParentSize())
                Column(Modifier.fillMaxSize().padding(horizontal = 17.dp, vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth().luluCssElement("music-header"),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.ArrowBack, "返回聊天", tint = listeningInk)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("一起听", fontSize = 19.sp, color = baseInk,
                            fontWeight = FontWeight.SemiBold)
                        Text("OUR LITTLE MUSIC ROOM", fontSize = 9.sp,
                            letterSpacing = 1.sp, color = listeningMuted)
                    }
                    IconButton(onClick = { showLibrary = !showLibrary; showAdd = false }) {
                        Icon(Icons.Outlined.LibraryMusic, "我的歌单", tint = listeningRose)
                    }
                }
                Row(Modifier.fillMaxWidth().luluCssElement("music-source"), horizontalArrangement = Arrangement.Center) {
                    FilterChip(selected = system,
                        onClick = { onModeChange("system"); showLibrary = false },
                        label = { Text("手机播放器") })
                    Spacer(Modifier.width(9.dp))
                    FilterChip(selected = !system,
                        onClick = { onModeChange("local"); showLibrary = false },
                        label = { Text("本地音乐") })
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!showLibrary) {
                        ListenTogetherArtwork(
                            roleName = characterName, roleAvatar = characterAvatarUri,
                            userName = userName, userAvatar = userAvatarUri, spinning = playing)
                        Text(name, modifier = Modifier.luluCssElement("music-track-title"),
                            color = titleStyle.cssColor("color") ?: baseInk,
                            fontSize = titleStyle.cssFontSize() ?: 20.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(artist, modifier = Modifier.luluCssElement("music-track-artist"),
                            color = artistStyle.cssColor("color") ?: listeningMuted,
                            fontSize = artistStyle.cssFontSize() ?: 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(20.dp))
                        LinearProgressIndicator(
                            progress = { if (duration > 0L) (position.toFloat() / duration)
                                .coerceIn(0f, 1f) else 0f },
                            modifier = Modifier.fillMaxWidth().luluCssElement("music-progress"),
                            color = progressStyle.cssColor("color") ?: listeningRose,
                            trackColor = progressStyle.cssColor("background-color") ?: Color(0xFFEDE5E9))
                        Row(Modifier.fillMaxWidth().padding(top = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(musicTime(position), fontSize = 11.sp, color = listeningMuted)
                            Text(if (duration > 0) musicTime(duration) else "—:—",
                                fontSize = 11.sp, color = listeningMuted)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.luluCssElement("music-controls"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            IconButton(onClick = {
                                if (system) onExternalControl("previous") else {
                                    val tracks = lists.flatMap { it.tracks }.filter {
                                        it.source == "local" && it.uri.isNotBlank()
                                    }
                                    val i = tracks.indexOfFirst { it.uri == localUri }
                                    if (i > 0) onChooseLocal(tracks[i - 1].uri)
                                    else libraryMessage = "已是歌单中第一首，或当前歌曲不在本地歌单"
                                }
                            }, enabled = if (system) external != null else localUri.isNotBlank()) {
                                Icon(Icons.Outlined.SkipPrevious, "上一首", tint = listeningInk)
                            }
                            FilledIconButton(
                                onClick = {
                                    if (system) onExternalControl(if (playing) "pause" else "play")
                                    else onLocalToggle()
                                },
                                enabled = if (system) external != null else localUri.isNotBlank(),
                                modifier = Modifier.size(playStyle.cssDp("width")?.coerceIn(48.dp, 80.dp)
                                    ?: 58.dp).luluCssElement("music-play"),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = playStyle.cssColor("background-color") ?: listeningRose,
                                    contentColor = playStyle.cssColor("color") ?: Color.White),
                            ) {
                                Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                    if (playing) "暂停" else "播放", Modifier.size(30.dp))
                            }
                            IconButton(onClick = { if (system) onExternalControl("next") else {
                                val tracks = lists.flatMap { it.tracks }.filter {
                                    it.source == "local" && it.uri.isNotBlank()
                                }
                                val i = tracks.indexOfFirst { it.uri == localUri }
                                if (i >= 0 && i + 1 < tracks.size) onChooseLocal(tracks[i + 1].uri)
                                else libraryMessage = "已是歌单中最后一首，或当前歌曲不在本地歌单"
                            } }, enabled = if (system) external != null else localUri.isNotBlank()) {
                                Icon(Icons.Outlined.SkipNext, "下一首", tint = listeningInk)
                            }
                        }
                        Spacer(Modifier.height(15.dp))
                        Text(if (system) "歌词尚未由手机媒体会话提供"
                            else "听过的歌，可以慢慢变成你们的回忆",
                            modifier = Modifier.luluCssElement("music-caption"),
                            color = luluCssStyle("music-caption").cssColor("color") ?: listeningMuted,
                            fontSize = luluCssStyle("music-caption").cssFontSize() ?: 12.sp)
                        Spacer(Modifier.height(11.dp))
                        Row(Modifier.luluCssElement("music-actions"),
                            horizontalArrangement = Arrangement.Center) {
                            TextButton(onClick = { showAdd = !showAdd }, enabled = current != null) {
                                Icon(Icons.Outlined.PlaylistAdd, null, Modifier.size(19.dp))
                                Text("加入歌单")
                            }
                            TextButton(onClick = { showChat = !showChat }) {
                                Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(18.dp))
                                Text(if (showChat) "收起聊天" else "边听边聊")
                            }
                        }
                        if (showAdd && current != null) {
                            if (lists.isEmpty()) Text("还没有歌单，点右上角先创建一张",
                                fontSize = 12.sp, color = listeningMuted)
                            lists.forEach { list ->
                                TextButton(onClick = {
                                    libraryMessage = if (SharedListenPlaylists.add(
                                            context, characterId, list.id, current)) {
                                        reload()
                                        showAdd = false
                                        "已收藏到「" + list.name + "」"
                                    } else "收藏失败，请重试"
                                }) { Text("＋ " + list.name, color = listeningInk) }
                            }
                        }
                        Spacer(Modifier.height(9.dp))
                        if (system) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onOpenNetease) {
                                    Icon(Icons.Outlined.OpenInNew, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("打开网易云")
                                }
                                OutlinedButton(onClick = onJoinExternal, enabled = external?.playing == true) {
                                    Text(if (external != null && !external.playing) "播放后一起听" else "一起听 ♡")
                                }
                            }
                            if (external == null) {
                                Spacer(Modifier.height(9.dp))
                                Text(externalError.ifBlank { "正在寻找手机播放器…" },
                                    fontSize = 12.sp, color = listeningMuted)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = onRefreshExternal) { Text("重新连接") }
                                    TextButton(onClick = {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                        }.onFailure {
                                            libraryMessage = "无法打开系统通知使用权设置"
                                        }
                                    }) { Text("设置通知使用权") }
                                }
                            }
                        } else {
                            OutlinedButton(onClick = onSelectLocal) {
                                Icon(Icons.Outlined.Album, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (localUri.isBlank()) "选择本地音乐" else "换一首")
                            }
                            TextButton(onClick = onAnalyzeLocal,
                                enabled = localUri.isNotBlank() && !busy) {
                                Text(if (busy) "正在感知…" else "让角色感知声音")
                            }
                        }
                        if (showChat) {
                            HorizontalDivider(Modifier.padding(vertical = 10.dp))
                            messages.takeLast(5).forEach { msg ->
                                val who = when (msg.sender) {
                                    LuluChatMessage.Sender.User -> "你"
                                    LuluChatMessage.Sender.Character -> characterName
                                    else -> "记录"
                                }
                                Text(who + "：" + msg.content.take(200),
                                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                    fontSize = 12.sp, color = listeningInk)
                            }
                        }
                    } else {
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("我们的歌单", fontSize = 23.sp, color = listeningInk,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showLibrary = false }) { Text("返回唱片") }
                        }
                        Text("收藏属于你们的旋律", color = listeningMuted, fontSize = 12.sp)
                        Spacer(Modifier.height(15.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(newListName, onValueChange = { newListName = it.take(48) },
                                modifier = Modifier.weight(1f), singleLine = true,
                                label = { Text("新歌单名称") })
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                libraryMessage = if (SharedListenPlaylists.create(
                                        context, characterId, newListName)) {
                                    newListName = ""
                                    reload()
                                    "歌单已创建"
                                } else "歌单名不能为空或重复"
                            }, enabled = newListName.isNotBlank()) { Text("创建") }
                        }
                        if (lists.isEmpty()) {
                            Spacer(Modifier.height(30.dp))
                            Text("从第一张歌单开始吧", color = listeningMuted)
                        }
                        lists.forEach { list ->
                            Spacer(Modifier.height(16.dp))
                            Surface(color = luluCssStyle("music-playlist-card").cssColor("background-color")
                                ?: Color.White, shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.fillMaxWidth().luluCssElement("music-playlist-card")) {
                                Column(Modifier.padding(13.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.LibraryMusic, null, tint = listeningRose)
                                        Spacer(Modifier.width(8.dp))
                                        Text(list.name, Modifier.weight(1f), color = listeningInk,
                                            fontWeight = FontWeight.Medium)
                                        Text(list.tracks.size.toString() + " 首",
                                            fontSize = 11.sp, color = listeningMuted)
                                        IconButton(onClick = {
                                            if (SharedListenPlaylists.delete(context, characterId, list.id))
                                                reload()
                                        }) { Icon(Icons.Outlined.DeleteOutline, "删除歌单",
                                            tint = listeningMuted, modifier = Modifier.size(18.dp)) }
                                    }
                                    list.tracks.forEach { entry ->
                                        Row(Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f).clickable {
                                                if (entry.source == "local") {
                                                    onChooseLocal(entry.uri)
                                                    showLibrary = false
                                                    libraryMessage = "已载入本地歌曲，按播放键开始"
                                                } else {
                                                    onModeChange("system")
                                                    onOpenNetease()
                                                    libraryMessage = "外部歌曲已收藏；请在网易云选择它播放。当前尚无直点播授权"
                                                    showLibrary = false
                                                }
                                            }) {
                                                Text(entry.title, maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    fontSize = 14.sp, color = listeningInk)
                                                Text(entry.artist.ifBlank {
                                                    if (entry.source == "local") "本地可播放" else "外部曲目收藏"
                                                }, fontSize = 11.sp, color = listeningMuted)
                                            }
                                            IconButton(onClick = {
                                                if (SharedListenPlaylists.removeTrack(
                                                        context, characterId, list.id, entry.id)) reload()
                                            }) {
                                                Icon(Icons.Outlined.DeleteOutline, "移除歌曲",
                                                    tint = listeningMuted, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (status.isNotBlank() || libraryMessage.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text((if (status.isNotBlank()) status else libraryMessage).take(260),
                            color = listeningMuted, fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth())
                    }
                }
                if (!showLibrary) {
                    HorizontalDivider(color = Color(0xFFEDE7E9))
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp).luluCssElement("music-chat"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        OutlinedTextField(draft, onValueChange = { draft = it },
                            modifier = Modifier.weight(1f).luluCssElement("music-chat-input"), singleLine = true,
                            placeholder = { Text("想和TA聊聊…", fontSize = 13.sp) },
                            shape = RoundedCornerShape(22.dp))
                        Button(onClick = { if (onDiscuss(draft.trim())) draft = "" },
                            enabled = draft.isNotBlank()) { Text("发送") }
                    }
                }
                }
            }
        }
    }
}
