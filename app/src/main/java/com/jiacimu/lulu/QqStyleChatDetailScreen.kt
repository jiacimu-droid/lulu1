package com.jiacimu.lulu

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.data.LuluAppPreferencesStore
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.system.LuluDeviceToolBridge
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val QqPage = Color(0xFFF7F9F8)
private val QqHeader = Color(0xFFFCFDFC)
private val QqMine = Color(0xFFDDEAE6)
private val QqOther = Color.White
private val QqMuted = Color(0xFF7D8C88)
private val QqInk = Color(0xFF34413F)
private val QqBorder = Color(0xFFDDE7E3)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QqStyleChatDetailScreen(
    conversationId: String,
    onBack: () -> Unit,
    onOpenBranch: (String) -> Unit,
    onCharacterSettings: () -> Unit,
    onWorldBook: () -> Unit,
) {
    val messages by MigratedDomainStores.chat.messages(conversationId).collectAsState()
    val conversations by MigratedDomainStores.chat.conversations.collectAsState()
    val preferences by LuluAppPreferencesStore.state.collectAsState()
    val library by LuluAiServices.connectionStore.library.collectAsState()
    val conversation = conversations.firstOrNull { it.id == conversationId }
    val characterId = conversation?.characterId ?: "lulu"
    val character = MigratedDomainStores.characters.get(characterId)
    val activeArchive = library.archives.firstOrNull { it.id == library.activeArchiveId }
    val activeLabel = activeArchive?.let(LuluAiServices.connectionStore::archiveLabel) ?: "未连接模型"
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var generationJob by remember { mutableStateOf<Job?>(null) }
    var pendingMessageId by remember { mutableStateOf<String?>(null) }
    var selectedMessage by remember { mutableStateOf<LuluChatMessage?>(null) }
    var moreExpanded by remember { mutableStateOf(false) }
    var callVisible by remember { mutableStateOf(false) }

    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (spoken.isNotBlank()) input = listOf(input.trim(), spoken.trim()).filter(String::isNotBlank).joinToString(" ")
        }
    }

    LaunchedEffect(conversationId) { MigratedDomainStores.chat.markConversationRead(conversationId) }
    LaunchedEffect(messages.size, preferences.autoScrollChat) {
        if (messages.isNotEmpty() && preferences.autoScrollChat) listState.scrollToItem(messages.lastIndex)
    }

    fun generateReply(text: String, userMessageId: String) {
        if (sending || activeArchive == null) return
        val history = buildBoundedHistory(
            messages = messages.filterNot { it.id == userMessageId },
            characterName = character.displayName,
        )
        sending = true
        pendingMessageId = userMessageId
        generationJob = scope.launch {
            try {
                val result = LuluDeviceToolBridge.respond(
                    characterId = characterId,
                    history = history,
                    userText = text,
                    title = activeLabel,
                )
                if (!currentCoroutineContext().isActive) return@launch
                result.onSuccess { reply ->
                    if (reply.text.isNotBlank()) MigratedDomainStores.chat.appendCharacterMessage(conversationId, reply.text)
                }.onFailure { error ->
                    MigratedDomainStores.chat.markFailed(userMessageId)
                    snackbar.showSnackbar(error.message ?: "回复失败")
                }
            } finally {
                if (pendingMessageId == userMessageId) {
                    sending = false
                    pendingMessageId = null
                    generationJob = null
                }
            }
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isBlank() || sending) return
        if (activeArchive == null) {
            scope.launch { snackbar.showSnackbar("请先在设置里选择模型") }
            return
        }
        val message = MigratedDomainStores.chat.sendUserMessage(conversationId, text)
        input = ""
        generateReply(text, message.id)
    }

    Scaffold(
        containerColor = QqPage,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = QqHeader),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        QqAvatar(character.displayName.take(1).ifBlank { "露" }, 42)
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text(character.displayName, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = QqInk)
                            Text(activeLabel, fontSize = 10.sp, color = QqMuted, maxLines = 1)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { callVisible = true }) { Icon(Icons.Outlined.Call, "通话") }
                    Box {
                        IconButton(onClick = { moreExpanded = true }) { Icon(Icons.Outlined.MoreHoriz, "更多") }
                        DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                            DropdownMenuItem(text = { Text("角色设置") }, onClick = { moreExpanded = false; onCharacterSettings() })
                            DropdownMenuItem(text = { Text("角色世界书") }, onClick = { moreExpanded = false; onWorldBook() })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = QqHeader, tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IconButton(onClick = {
                        voiceLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        })
                    }) { Icon(Icons.Outlined.KeyboardVoice, "语音") }
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        minLines = 1,
                        maxLines = 5,
                        placeholder = { Text("发消息", color = QqMuted) },
                        shape = RoundedCornerShape(18.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                    FilledIconButton(
                        onClick = {
                            if (sending) {
                                pendingMessageId?.let(MigratedDomainStores.chat::markFailed)
                                generationJob?.cancel()
                                sending = false
                                pendingMessageId = null
                            } else send()
                        },
                        enabled = sending || input.isNotBlank(),
                    ) {
                        Icon(if (sending) Icons.Outlined.StopCircle else Icons.Outlined.Send, if (sending) "停止" else "发送")
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(messages, key = { _, item -> item.id }) { index, message ->
                val previous = messages.getOrNull(index - 1)
                val next = messages.getOrNull(index + 1)
                val groupStart = previous == null || previous.sender != message.sender ||
                    Duration.between(previous.createdAt, message.createdAt).toMinutes() >= 2
                val groupEnd = next == null || next.sender != message.sender ||
                    Duration.between(message.createdAt, next.createdAt).toMinutes() >= 2
                QqMessageRow(
                    message = message,
                    characterName = character.displayName,
                    showAvatar = groupStart,
                    showTime = preferences.showMessageTimestamps && groupEnd,
                    onClick = {
                        if (message.status == LuluChatMessage.Status.Failed && message.sender == LuluChatMessage.Sender.User && !sending) {
                            generateReply(message.content, message.id)
                        }
                    },
                    onLongClick = { selectedMessage = message },
                )
            }
            if (sending) {
                item {
                    Row(verticalAlignment = Alignment.Top) {
                        QqAvatar(character.displayName.take(1).ifBlank { "露" }, 44)
                        Spacer(Modifier.width(9.dp))
                        Surface(color = QqOther, shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, QqBorder)) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("正在思考或执行手机操作…", color = QqMuted, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    selectedMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { selectedMessage = null },
            title = { Text("消息操作") },
            text = { Text(message.content, maxLines = 4) },
            confirmButton = {
                Row {
                    TextButton(onClick = { MigratedDomainStores.chat.toggleFavorite(message.id); selectedMessage = null }) {
                        Text(if (message.favorite) "取消收藏" else "收藏")
                    }
                    TextButton(onClick = {
                        MigratedDomainStores.chat.createBranch(conversationId, message.id)?.let { onOpenBranch(it.id) }
                        selectedMessage = null
                    }) { Text("分支") }
                }
            },
            dismissButton = {
                TextButton(onClick = { MigratedDomainStores.chat.deleteMessage(message.id); selectedMessage = null }) { Text("删除") }
            },
        )
    }
    if (callVisible) {
        AlertDialog(
            onDismissRequest = { callVisible = false },
            title = { Text("与${character.displayName}通话") },
            text = { Text("通话入口已接入角色页面；实时双向流式语音仍需要继续完成音频传输层。") },
            confirmButton = { Button(onClick = { callVisible = false }) { Text("知道了") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QqMessageRow(
    message: LuluChatMessage,
    characterName: String,
    showAvatar: Boolean,
    showTime: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val mine = message.sender == LuluChatMessage.Sender.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!mine) {
            if (showAvatar) QqAvatar(characterName.take(1).ifBlank { "露" }, 44) else Spacer(Modifier.width(44.dp))
            Spacer(Modifier.width(9.dp))
        }
        Column(
            modifier = Modifier.widthIn(max = 300.dp),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            Surface(
                modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
                color = if (mine) QqMine else QqOther,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, QqBorder),
                shadowElevation = if (mine) 0.dp else 1.dp,
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(message.content, color = QqInk, fontSize = 15.sp, lineHeight = 22.sp)
                    if (message.status == LuluChatMessage.Status.Failed) {
                        Spacer(Modifier.height(4.dp))
                        Text("发送失败 · 点击重试", color = MaterialTheme.colorScheme.error, fontSize = 10.sp)
                    }
                    if (message.favorite) {
                        Spacer(Modifier.height(4.dp))
                        Text("★ 已收藏", color = QqMuted, fontSize = 10.sp)
                    }
                }
            }
            if (showTime) {
                Spacer(Modifier.height(4.dp))
                Text(
                    message.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")),
                    color = QqMuted,
                    fontSize = 10.sp,
                    textAlign = if (mine) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        if (mine) {
            Spacer(Modifier.width(9.dp))
            if (showAvatar) QqAvatar("我", 44) else Spacer(Modifier.width(44.dp))
        }
    }
}

private fun buildBoundedHistory(
    messages: List<LuluChatMessage>,
    characterName: String,
    maxMessages: Int = 30,
    maxChars: Int = 12_000,
): String {
    val normalized = messages
        .filter { it.sender != LuluChatMessage.Sender.System }
        .fold(mutableListOf<LuluChatMessage>()) { result, message ->
            val previous = result.lastOrNull()
            val duplicate = previous != null && previous.sender == message.sender && previous.content.trim() == message.content.trim()
            if (!duplicate) result += message
            result
        }
        .takeLast(maxMessages)
    val lines = normalized.map { message ->
        val role = if (message.sender == LuluChatMessage.Sender.User) "主人" else characterName
        "$role：${message.content.trim()}"
    }
    val selected = ArrayDeque<String>()
    var chars = 0
    for (line in lines.asReversed()) {
        if (selected.isNotEmpty() && chars + line.length > maxChars) break
        selected.addFirst(line)
        chars += line.length
    }
    return selected.joinToString("\n")
}

@Composable
private fun QqAvatar(label: String, size: Int) {
    Surface(
        modifier = Modifier.size(size.dp),
        shape = CircleShape,
        color = Color(0xFFE9F0EE),
        border = androidx.compose.foundation.BorderStroke(1.dp, QqBorder),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontWeight = FontWeight.Bold, color = QqInk, fontSize = (size / 3).sp)
        }
    }
}
