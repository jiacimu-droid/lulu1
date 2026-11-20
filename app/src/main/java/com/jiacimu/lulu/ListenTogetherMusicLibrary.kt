package com.jiacimu.lulu

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.design.LuluCssPageScope
import com.jiacimu.lulu.design.LocalLuluAppearance
import com.jiacimu.lulu.design.LocalLuluCssSheet
import com.jiacimu.lulu.design.luluCssElement
import com.jiacimu.lulu.design.luluCssStyle
import com.jiacimu.lulu.design.cssColor

private val musicRose = Color(0xFFA87288)
private val musicInk = Color(0xFF29242B)

/** A real in-app Music destination, separate from the compact chat overlay. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListenTogetherMusicLibrary(onBack: () -> Unit) {
    val context = LocalContext.current
    val conversations by MigratedDomainStores.chat.conversations.collectAsState()
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val available = conversations.filter { it.groupChat == null && it.characterId.isNotBlank() }
        .distinctBy { it.characterId }
    var selectedConversationId by remember {
        mutableStateOf(SharedMusicWindowController.room?.conversationId.orEmpty())
    }
    val conversation = available.firstOrNull { it.id == selectedConversationId }
        ?: available.firstOrNull()
    val roleId = conversation?.characterId.orEmpty()
    val role = characters[roleId] ?: roleId.takeIf(String::isNotBlank)?.let {
        MigratedDomainStores.characters.get(it)
    }
    val room = conversation?.let {
        SharedMusicRoom(it.id, it.characterId, role?.displayName ?: "角色", role?.avatarUri)
    }

    var libraryRevision by remember { mutableIntStateOf(0) }
    val lists = remember(roleId, libraryRevision) { SharedListenPlaylists.read(context, roleId) }
    var selectedPlaylistId by remember(roleId) { mutableStateOf("") }
    val playlist = lists.firstOrNull { it.id == selectedPlaylistId } ?: lists.firstOrNull()
    var newName by remember { mutableStateOf("") }
    var shareLink by remember { mutableStateOf("") }
    var songTitle by remember { mutableStateOf("") }
    var songArtist by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var awaitingBinding by remember { mutableStateOf<SharedListenEntry?>(null) }
    fun reload() { libraryRevision++ }

    val localPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null && playlist != null) {
            val allowed = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
            if (!allowed) message = "无法长期读取本地音频，请重新选择"
            else {
                val title = picked.lastPathSegment.orEmpty().substringAfterLast('/')
                    .ifBlank { "本地歌曲" }.take(80)
                message = if (SharedListenPlaylists.add(context, roleId, playlist.id,
                        SharedListenEntry(title = title, artist = "", source = "local",
                            uri = picked.toString()))) {
                    reload()
                    "已加入「${playlist.name}」"
                } else "保存音频失败"
            }
        }
    }

    LuluCssPageScope("MusicLibrary") {
        val pagePaper = if (LocalLuluCssSheet.current.page("MusicLibrary")["--paper"] != null)
            Color(LocalLuluAppearance.current.palette.paper.toInt()) else Color(0xFFFCFAFB)
        val listStyle = luluCssStyle("music-playlist-card")
        Scaffold(
        containerColor = pagePaper,
        topBar = {
            TopAppBar(
                title = { Text("音乐", color = musicInk, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = pagePaper),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)
                .luluCssElement("music-playlist"),
            contentPadding = PaddingValues(bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            item {
                Text("OUR LITTLE MUSIC", color = musicRose, letterSpacing = 2.sp,
                    fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                Text("选好一首歌，回聊天里一起听。", color = musicInk,
                    fontSize = 16.sp, modifier = Modifier.padding(top = 5.dp, bottom = 10.dp))
            }
            if (available.isEmpty()) {
                item { Text("先创建角色并打开一次私聊，才能建立双人一起听房间。",
                    color = Color.Gray, fontSize = 13.sp) }
            } else {
                item {
                    Text("与谁一起听", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        available.take(5).forEach { chat ->
                            val name = characters[chat.characterId]?.displayName ?: "角色"
                            FilterChip(selected = chat.id == conversation?.id,
                                onClick = { selectedConversationId = chat.id },
                                label = { Text(name, maxLines = 1) })
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("我的歌单", color = musicInk, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            room?.let { SharedMusicWindowController.open(it) }
                        }) {
                            Icon(Icons.Outlined.Album, null, Modifier.size(18.dp))
                            Text("展开一起听")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(newName, { newName = it.take(48) },
                            label = { Text("新歌单") }, singleLine = true,
                            modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            message = if (SharedListenPlaylists.create(context, roleId, newName)) {
                                newName = ""; reload(); "歌单已创建"
                            } else "请输入不重复的歌单名"
                        }, enabled = newName.isNotBlank()) { Text("创建") }
                    }
                }
                item {
                    if (lists.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            lists.take(6).forEach { list ->
                                FilterChip(selected = list.id == playlist?.id,
                                    onClick = { selectedPlaylistId = list.id; awaitingBinding = null },
                                    label = { Text(list.name, maxLines = 1) })
                            }
                        }
                    } else Text("先创建一张歌单。", color = Color.Gray)
                }
                if (playlist != null) {
                    item {
                        Card(modifier = Modifier.luluCssElement("music-playlist-card"),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = listStyle.cssColor("background-color") ?: Color.White),
                            border = BorderStroke(1.dp, Color(0xFFE8E2E5))) {
                            Column(Modifier.fillMaxWidth().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("导入指定歌曲", color = musicInk, fontWeight = FontWeight.Medium)
                                Text("在网易云复制「单曲分享链接」，不是歌单链接。输入准确 ID 后才可定位曲目。",
                                    color = Color.Gray, fontSize = 11.sp)
                                OutlinedTextField(shareLink, { shareLink = it.take(1_000) },
                                    label = { Text("网易云单曲链接 / 歌曲 ID") },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(songTitle, { songTitle = it.take(80) },
                                    label = { Text("歌曲名称（可选）") },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(songArtist, { songArtist = it.take(80) },
                                    label = { Text("歌手（可选）") },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                                Button(onClick = {
                                    val id = NeteaseSongLink.songId(shareLink)
                                    message = when {
                                        id == null -> "没有识别到网易云单曲 ID，未保存为可播放歌曲"
                                        awaitingBinding != null -> {
                                            val ok = SharedListenPlaylists.bindSongId(context,
                                                roleId, playlist.id, awaitingBinding!!.id, id)
                                            if (ok) {
                                                awaitingBinding = null
                                                shareLink = ""; reload()
                                                "旧收藏已绑定指定歌曲 $id"
                                            } else "绑定失败，请重试"
                                        }
                                        SharedListenPlaylists.add(context, roleId, playlist.id,
                                            SharedListenEntry(
                                                title = songTitle.ifBlank { "网易云单曲 #$id" },
                                                artist = songArtist, source = "external",
                                                uri = "https://music.163.com/song?id=$id",
                                                packageName = NeteaseSongLink.packageName,
                                                songId = id,
                                            )) -> {
                                            shareLink = ""; songTitle = ""; songArtist = ""
                                            reload(); "已保存单曲 $id"
                                        }
                                        else -> "保存失败"
                                    }
                                }, enabled = shareLink.isNotBlank()) {
                                    Text(if (awaitingBinding == null) "加入歌单" else "绑定旧收藏")
                                }
                                OutlinedButton(onClick = { localPicker.launch(arrayOf("audio/*")) }) {
                                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp))
                                    Text("  导入本地音频")
                                }
                            }
                        }
                    }
                    items(playlist.tracks, key = { it.id }) { entry ->
                        Surface(
                            modifier = Modifier.luluCssElement("music-playlist-card"),
                            shape = RoundedCornerShape(16.dp),
                            color = listStyle.cssColor("background-color") ?: Color.White,
                            border = BorderStroke(1.dp, Color(0xFFECE6EA))) {
                            Row(Modifier.fillMaxWidth().padding(start = 13.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (entry.source == "local") Icons.Outlined.MusicNote
                                    else Icons.Outlined.Album, null, tint = musicRose)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = musicInk, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                    Text(if (entry.source == "local") "本地音频 · 可直接播放"
                                        else if (entry.songId.isNotBlank()) "网易云单曲 ${entry.songId}"
                                        else "旧收藏缺少歌曲 ID · 请绑定链接",
                                        color = Color.Gray, fontSize = 10.sp, maxLines = 1)
                                }
                                IconButton(onClick = {
                                    if (entry.source == "external" && entry.songId.isBlank() &&
                                        NeteaseSongLink.songId(entry.uri) == null) {
                                        awaitingBinding = entry
                                        songTitle = entry.title
                                        songArtist = entry.artist
                                        message = "先给「${entry.title}」绑定准确的单曲链接"
                                    } else {
                                        room?.let { SharedMusicWindowController.select(it, entry) }
                                        message = "已选择：${entry.title}"
                                    }
                                }) {
                                    Icon(if (entry.source == "external" && entry.songId.isBlank())
                                        Icons.Outlined.Link else Icons.Outlined.PlayArrow,
                                        "选择指定歌曲", tint = musicRose)
                                }
                                IconButton(onClick = {
                                    if (SharedListenPlaylists.removeTrack(context,
                                            roleId, playlist.id, entry.id)) reload()
                                }) {
                                    Icon(Icons.Outlined.DeleteOutline, "移除", tint = Color.Gray,
                                        modifier = Modifier.size(19.dp))
                                }
                            }
                        }
                    }
                    item {
                        TextButton(onClick = {
                            if (SharedListenPlaylists.delete(context, roleId, playlist.id)) {
                                selectedPlaylistId = ""; reload()
                            }
                        }) { Text("删除当前歌单", fontSize = 11.sp, color = Color.Gray) }
                    }
                }
                item {
                    HorizontalDivider(color = Color(0xFFE7E1E4))
                    Text("网易云账号", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp))
                    Text("账号登录仍在网易云官方 App 中进行。这里管理你主动导入的单曲和本地文件，不会索取密码，也不会假装已经读取网易云云端歌单。",
                        color = Color.Gray, fontSize = 11.sp)
                    OutlinedButton(onClick = {
                        val launch = context.packageManager.getLaunchIntentForPackage(
                            NeteaseSongLink.packageName)
                        message = if (launch == null) "请先安装网易云音乐"
                        else runCatching {
                            context.startActivity(launch)
                            "已打开网易云官方 App"
                        }.getOrElse { "网易云打开失败：" + it.message.orEmpty() }
                    }) {
                        Icon(Icons.Outlined.OpenInNew, null, Modifier.size(17.dp))
                        Text("  打开网易云登录 / 选歌")
                    }
                }
            }
            if (message.isNotBlank()) {
                item { Text(message, color = musicRose, fontSize = 12.sp) }
            }
        }
        }
    }
}
