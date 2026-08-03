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

private val QqPage = Color(0xFFFFFFFF)
private val QqHeader = Color(0xFFFCFCFC)
private val QqMine = Color(0xFF292929)
private val QqMineInk = Color(0xFFFFFFFF)
private val QqOther = Color(0xFFF4F4F4)
private val QqMuted = Color(0xFF7A7A7E)
private val QqInk = Color(0xFF1D1D1F)
private val QqBorder = Color(0xFFE7E7E7)
private val QqIconSurface = Color(0xFFF4F4F4)

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
    val pendingUserMessages = remember(messages) {
        val lastCharacterIndex = messages.indexOfLast { it.sender == LuluChatMessage.Sender.Character }
        messages.drop(lastCharacterIndex + 1).filter { it.sender == LuluChatMessage.Sender.User }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var input by remember { mutableStateOf("") }
    var receiving by remember { mutableStateOf(false) }
    var generationJob by remember { mutableStateOf<Job?>(null) }
    var selectedMessage by remember { mutableStateOf<LuluChatMessage?>(null) }
    var moreExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var callVisible by remember { mutableStateOf(false) }

    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (spoken.isNotBlank()) {
                input = listOf(input.trim(), spoken.trim()).filter(String::isNotBlank).joinToString(" ")
            }
        }
    }

    LaunchedEffect(conversationId) { MigratedDomainStores.chat.markConversationRead(conversationId) }
    LaunchedEffect(messages.size, preferences.autoScrollChat) {
        if (messages.isNotEmpty() && preferences.autoScrollChat) listState.scrollToItem(messages.lastIndex)
    }

    fun sendOnly() {
        val text = input.trim()
        if (text.isBlank()) return
        MigratedDomainStores.chat.sendUserMessage(conversationId, text)
        input = ""
    }

    fun stopReceiving() {
        generationJob?.cancel()
        generationJob = null
        receiving = false
    }

    fun receiveReply() {
        if (receiving) return
        if (activeArchive == null) {
            scope.launch { snackbar.showSnackbar("请先在右上角选择模型") }
            return
        }
        if (pendingUserMessages.isEmpty()) return

        val pendingIds = pendingUserMessages.mapTo(mutableSetOf()) { it.id }
        val pendingText = pendingUserMessages.joinToString("\n") { it.content.trim() }
        val history = buildBoundedHistory(
            messages = messages.filterNot { it.id in pendingIds },
            characterName = character.displayName,
        )
        receiving = true
        generationJob = scope.launch {
            try {
                val result = LuluDeviceToolBridge.respond(
                    characterId = characterId,
                    history = history,
                    userText = pendingText,
                    title = activeLabel,
                )
                if (!currentCoroutineContext().isActive) return@launch
                result.onSuccess { reply ->
                    if (reply.text.isNotBlank()) {
                        MigratedDomainStores.chat.appendCharacterMessage(conversationId, reply.text)
                    } else {
                        snackbar.showSnackbar("对方刚才没有说清，再点一次试试")
                    }
                }.onFailure { error ->
                    snackbar.showSnackbar(error.message ?: "回复失败")
                }
            } finally {
                receiving = false
                generationJob = null
            }
        }
    }

    Scaffold(
        containerColor = QqPage,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = QqHeader),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, "返回", tint = QqInk)
                    }
                },
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
                    Box {
                        IconButton(onClick = { modelExpanded = true }) {
                            Icon(Icons.Outlined.SwapHoriz, "切换模型", tint = QqInk)
                        }
                        DropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                            if (library.archives.isEmpty()) {
                                DropdownMenuItem(text = { Text("还没有模型存档") }, enabled = false, onClick = {})
                            } else {
                                library.archives.forEach { archive ->
                                    val selected = archive.id == library.activeArchiveId
                                    DropdownMenuItem(
                                        leadingIcon = {
                                            Icon(
                                                if (selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                                                null,
                                                tint = QqInk,
                                            )
                                        },
                                        text = { Text(LuluAiServices.connectionStore.archiveLabel(archive)) },
                                        onClick = {
                                            LuluAiServices.connectionStore.selectArchive(archive.id)
                                            modelExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    IconButton(onClick = { callVisible = true }) {
                        Icon(Icons.Outlined.Call, "电话", tint = QqInk)
                    }
                    Box {
                        IconButton(onClick = { moreExpanded = true }) {
                            Icon(Icons.Outlined.MoreHoriz, "更多", tint = QqInk)
                        }
                        DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                            DropdownMenuItem(text = { Text("角色设置") }, onClick = { moreExpanded = false; onCharacterSettings() })
                            DropdownMenuItem(text = { Text("角色世界书") }, onClick = { moreExpanded = false; onWorldBook() })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = QqHeader, shadowElevation = 4.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IconButton(onClick = {
                        voiceLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        })
                    }) { Icon(Icons.Outlined.KeyboardVoice, "语音输入", tint = QqInk) }
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        minLines = 1,
                        maxLines = 5,
                        placeholder = { Text("发消息", color = QqMuted) },
                        shape = RoundedCornerShape(18.dp),
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = QqInk,
                            unfocusedTextColor = QqInk,
                            focusedContainerColor = QqIconSurface,
                            unfocusedContainerColor = QqIconSurface,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = QqInk,
                        ),
                    )
                    FilledIconButton(
                        onClick = ::sendOnly,
                        enabled = input.isNotBlank(),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = QqInk,
                            contentColor = Color.White,
                            disabledContainerColor = QqBorder,
                            disabledContentColor = QqMuted,
                        ),
                    ) {
                        Icon(Icons.Outlined.Send, "发送")
                    }
                    FilledTonalIconButton(
                        onClick = { if (receiving) stopReceiving() else receiveReply() },
                        enabled = receiving || pendingUserMessages.isNotEmpty(),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = QqIconSurface,
                            contentColor = QqInk,
                            disabledContainerColor = QqIconSurface,
                            disabledContentColor = QqMuted.copy(alpha = 0.45f),
                        ),
                    ) {
                        Icon(
                            if (receiving) Icons.Outlined.StopCircle else Icons.Outlined.MarkChatRead,
                            if (receiving) "停止" else "让对方回复",
                        )
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
                    onLongClick = { selectedMessage = message },
                )
            }
            if (receiving) {
                item {
                    Row(verticalAlignment = Alignment.Top) {
                        QqAvatar(character.displayName.take(1).ifBlank { "露" }, 44)
                        Spacer(Modifier.width(9.dp))
                        Surface(
                            color = QqOther,
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, QqBorder),
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = QqInk)
                                Spacer(Modifier.width(8.dp))
                                Text("对方正在输入…", color = QqMuted, fontSize = 13.sp)
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
                    TextButton(onClick = {
                        MigratedDomainStores.chat.toggleFavorite(message.id)
                        selectedMessage = null
                    }) { Text(if (message.favorite) "取消收藏" else "收藏", color = QqInk) }
                    TextButton(onClick = {
                        MigratedDomainStores.chat.createBranch(conversationId, message.id)?.let { onOpenBranch(it.id) }
                        selectedMessage = null
                    }) { Text("分支", color = QqInk) }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    MigratedDomainStores.chat.deleteMessage(message.id)
                    selectedMessage = null
                }) { Text("删除") }
            },
        )
    }

    if (callVisible) {
        LuluVoiceCallScreen(
            conversationId = conversationId,
            characterId = characterId,
            characterName = character.displayName,
            onDismiss = { callVisible = false },
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
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick),
                color = if (mine) QqMine else QqOther,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (mine) QqMine else QqBorder),
                shadowElevation = 0.dp,
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        message.content,
                        color = if (mine) QqMineInk else QqInk,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                    )
                    if (message.favorite) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "★ 已收藏",
                            color = if (mine) Color.White.copy(alpha = 0.68f) else QqMuted,
                            fontSize = 10.sp,
                        )
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
        color = QqIconSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, QqBorder),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontWeight = FontWeight.Bold, color = QqInk, fontSize = (size / 3).sp)
        }
    }
}
