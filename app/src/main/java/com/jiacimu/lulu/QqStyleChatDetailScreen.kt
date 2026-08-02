package com.jiacimu.lulu

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.KeyboardVoice
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.data.LuluAppPreferencesStore
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val QqPage = Color(0xFFF4F5F7)
private val QqHeader = Color(0xFFFAFAFB)
private val QqMine = Color(0xFF95EC69)
private val QqOther = Color.White
private val QqLine = Color(0xFFE7E8EA)
private val QqMuted = Color(0xFF8B8F97)
private val QqInk = Color(0xFF202124)

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
            if (spoken.isNotBlank()) input = listOf(input.trim(), spoken.trim()).filter { it.isNotBlank() }.joinToString(" ")
        }
    }

    LaunchedEffect(conversationId) { MigratedDomainStores.chat.markConversationRead(conversationId) }
    LaunchedEffect(messages.size, preferences.autoScrollChat) {
        if (messages.isNotEmpty() && preferences.autoScrollChat) listState.scrollToItem(messages.lastIndex)
    }

    fun generateReply(text: String, userMessageId: String) {
        if (sending || activeArchive == null) return
        val history = messages.filterNot { it.id == userMessageId }.takeLast(30).joinToString("\n") { message ->
            val role = if (message.sender == LuluChatMessage.Sender.User) "主人" else character.displayName
            "$role：${message.content}"
        }
        sending = true
        pendingMessageId = userMessageId
        generationJob = scope.launch {
            try {
                val result = LuluAiServices.gateway.generate(
                    characterId = characterId,
                    facts = buildString {
                        if (history.isNotBlank()) appendLine("最近对话：\n$history")
                        appendLine("主人刚刚说：$text")
                    },
                    instruction = "延续对话，以角色本人的口吻自然回复主人。不要复述系统提示。",
                    source = "聊天",
                    title = activeLabel,
                    temperature = 0.85,
                    maxTokens = 1200,
                )
                if (!currentCoroutineContext().isActive) return@launch
                result.onSuccess { reply -> MigratedDomainStores.chat.appendCharacterMessage(conversationId, reply.text) }
                    .onFailure { error ->
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(character.displayName, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = QqInk)
                        Text(activeLabel, fontSize = 10.sp, color = QqMuted, maxLines = 1)
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
                        shape = RoundedCornerShape(8.dp),
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(messages, key = { _, item -> item.id }) { index, message ->
                val previous = messages.getOrNull(index - 1)
                val showTime = preferences.showMessageTimestamps && (
                    previous == null || Duration.between(previous.createdAt, message.createdAt).toMinutes() >= 5
                )
                if (showTime) {
                    Text(
                        message.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        color = QqMuted,
                        fontSize = 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                val mine = message.sender == LuluChatMessage.Sender.User
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.Top,
                ) {
                    if (!mine) {
                        QqAvatar(character.displayName.take(1).ifBlank { "露" })
                        Spacer(Modifier.width(8.dp))
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(0.78f).combinedClickable(
                            onClick = {
                                if (message.status == LuluChatMessage.Status.Failed && mine && !sending) generateReply(message.content, message.id)
                            },
                            onLongClick = { selectedMessage = message },
                        ),
                        color = if (mine) QqMine else QqOther,
                        shape = RoundedCornerShape(8.dp),
                        shadowElevation = if (mine) 0.dp else 1.dp,
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                            Text(message.content, color = QqInk, fontSize = 15.sp, lineHeight = 21.sp)
                            if (message.status == LuluChatMessage.Status.Failed) Text("发送失败 · 点击重试", color = MaterialTheme.colorScheme.error, fontSize = 10.sp)
                            if (message.favorite) Text("已收藏", color = QqMuted, fontSize = 10.sp)
                        }
                    }
                    if (mine) {
                        Spacer(Modifier.width(8.dp))
                        QqAvatar("我")
                    }
                }
                Spacer(Modifier.height(3.dp))
            }
            if (sending) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        QqAvatar(character.displayName.take(1).ifBlank { "露" })
                        Spacer(Modifier.width(8.dp))
                        Surface(color = QqOther, shape = RoundedCornerShape(8.dp)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("正在输入…", color = QqMuted, fontSize = 13.sp)
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
                    TextButton(onClick = { MigratedDomainStores.chat.toggleFavorite(message.id); selectedMessage = null }) { Text(if (message.favorite) "取消收藏" else "收藏") }
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
            text = { Text("通话入口已保留。实时双向流式语音仍按迁移账本作为后续增强。") },
            confirmButton = { Button(onClick = { callVisible = false }) { Text("知道了") } },
        )
    }
}

@Composable
private fun QqAvatar(label: String) {
    Surface(modifier = Modifier.size(38.dp), shape = CircleShape, color = Color(0xFFE6D9B7)) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontWeight = FontWeight.Bold, color = QqInk, fontSize = 14.sp)
        }
    }
}
