package com.jiacimu.lulu

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.CharacterVoicePreferenceStore
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.UserMessageFavorite
import com.jiacimu.lulu.data.UserMessageFavorites
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun UserMessageFavoritesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = UserMessageFavorites.store
    val entries by store.entries.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val engine = remember(context) { LuluSpeechEngine(context.applicationContext) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<UserMessageFavorite?>(null) }
    BackHandler(onBack = onBack)
    DisposableEffect(engine) { onDispose { engine.shutdown() } }

    fun play(entry: UserMessageFavorite) {
        if (playingId == entry.messageId) { engine.stop(); playingId = null; return }
        engine.stop()
        playingId = entry.messageId
        fun finished() {
            if (playingId == entry.messageId) playingId = null
            if (engine.lastError.isNotBlank()) scope.launch { snackbar.showSnackbar(engine.lastError) }
        }
        val original = store.audioFile(entry)
        if (original != null) {
            if (!engine.playCached(original, ::finished)) {
                playingId = null
                scope.launch { snackbar.showSnackbar("这段收藏语音暂时无法播放") }
            }
        } else {
            val base = ChatAutoVoicePlayback.favoriteAudioBase(entry.messageId)
            val text = stripCharacterReplyDirective(entry.content).trim()
            if (base == null || text.isBlank()) { playingId = null; return }
            engine.speakAndCache(text, base, scope,
                voiceIdOverride = CharacterVoicePreferenceStore.playbackVoiceId(entry.characterId),
                onFinished = {
                    engine.cachedAudioFile(base)?.let { audio ->
                        runCatching { store.retainAudio(entry.messageId, audio) }.onFailure {
                            scope.launch { snackbar.showSnackbar("语音保存失败，请重试") }
                        }
                    }
                    finished()
                }, allowGeneration = { store.contains(entry.messageId) })
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { CenterAlignedTopAppBar(title = { Text("我的收藏", fontSize = 18.sp) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } }) },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("还没有收藏的消息", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(entries, key = { it.messageId }) { entry ->
                Surface(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { selected = entry }),
                    shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LuluProfileAvatar(imageUri = entry.avatarUri, fallback = entry.authorName.take(1), size = 36)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.authorName, fontWeight = FontWeight.SemiBold)
                                Text(entry.messageAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            }
                            IconButton(onClick = { selected = entry }) { Icon(Icons.Outlined.MoreHoriz, "收藏操作") }
                        }
                        Text(qqForwardContextText(entry.content), lineHeight = 23.sp)
                        if (decodeQqChatImage(entry.content) == null && entry.characterMessage) {
                            val hasAudio = store.audioFile(entry) != null
                            OutlinedButton(onClick = { play(entry) }) {
                                Icon(if (playingId == entry.messageId) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                                    null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (playingId == entry.messageId) "停止" else if (hasAudio) "播放收藏语音" else "朗读并保存语音")
                            }
                        }
                    }
                }
            }
        }
    }
    selected?.let { entry ->
        ModalBottomSheet(onDismissRequest = { selected = null }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                        ClipData.newPlainText("收藏消息", qqForwardContextText(entry.content)))
                    selected = null
                }) { Icon(Icons.Outlined.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("复制文字") }
                TextButton(onClick = {
                    if (playingId == entry.messageId) { engine.stop(); playingId = null }
                    val removed = MigratedDomainStores.chat.removeUserFavorite(entry.messageId)
                    if (!removed) scope.launch { snackbar.showSnackbar("取消收藏失败，请重试") }
                    selected = null
                }) { Icon(Icons.Outlined.StarOutline, null); Spacer(Modifier.width(8.dp)); Text("取消收藏") }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
