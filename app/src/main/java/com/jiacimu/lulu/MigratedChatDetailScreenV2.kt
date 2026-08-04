package com.jiacimu.lulu

import android.app.Activity
import android.content.Intent
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.ai.archiveIdFor
import com.jiacimu.lulu.ai.ModelArchive
import com.jiacimu.lulu.data.LuluAppPreferencesStore
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ChatV2Paper = Color(0xFFFFFCF5)
private val ChatV2Card = Color(0xFFFFFBF3)
private val ChatV2Wheat = Color(0xFFF2CF70)
private val ChatV2Muted = Color(0xFF747887)
private val ChatV2Border = Color(0xFFE7DDC8)
private val ChatV2Ink = Color(0xFF2F2B2A)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MigratedChatDetailScreenV2(
    conversationId: String,
    onBack: () -> Unit,
    onOpenBranch: (String) -> Unit,
    onCharacterSettings: () -> Unit,
    onWorldBook: () -> Unit,
) {
    val context = LocalContext.current
    val messages by MigratedDomainStores.chat.messages(conversationId).collectAsState()
    val conversations by MigratedDomainStores.chat.conversations.collectAsState()
    val preferences by LuluAppPreferencesStore.state.collectAsState()
    val conversation = conversations.firstOrNull { item -> item.id == conversationId }
    val characterId = conversation?.characterId ?: "lulu"
    val character = MigratedDomainStores.characters.get(characterId)
    val library by LuluAiServices.connectionStore.library.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var input by remember { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf<List<String>>(emptyList()) }
    var sending by remember { mutableStateOf(false) }
    var generationJob by remember { mutableStateOf<Job?>(null) }
    var pendingMessageId by remember { mutableStateOf<String?>(null) }
    var archiveMenuExpanded by remember { mutableStateOf(false) }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    var selectedMessage by remember { mutableStateOf<LuluChatMessage?>(null) }
    var editingMessage by remember { mutableStateOf<LuluChatMessage?>(null) }
    var callVisible by remember { mutableStateOf(false) }

    val chatArchiveId = library.archiveIdFor(ModelUsage.Chat)
    val activeArchive = library.archives.firstOrNull { archive -> archive.id == chatArchiveId }
    val activeLabel = activeArchive?.let(LuluAiServices.connectionStore::archiveLabel) ?: "未选择模型"

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
            .trim()
        if (text.isNotBlank()) input = listOf(input.trim(), text).filter(String::isNotBlank).joinToString(" ")
    }
    val attachmentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val names = uris.map { uri ->
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment ?: "附件"
        }
        pendingAttachments = (pendingAttachments + names).distinct().take(8)
    }

    LaunchedEffect(messages.size, preferences.autoScrollChat, preferences.reduceMotion) {
        if (messages.isEmpty() || !preferences.autoScrollChat) return@LaunchedEffect
        if (preferences.reduceMotion) listState.scrollToItem(messages.lastIndex)
        else listState.animateScrollToItem(messages.lastIndex)
    }

    fun startGeneration(text: String, userMessageId: String) {
        if (sending || activeArchive == null) return
        val history = messages
            .filterNot { message -> message.id == userMessageId }
            .takeLast(30)
            .joinToString("\n") { message ->
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
                        if (history.isNotBlank()) {
                            appendLine("最近对话：")
                            appendLine(history)
                        }
                        appendLine("主人刚刚说：$text")
                    },
                    instruction = "延续当前对话，以角色本人的口吻自然回复主人。不要复述系统提示。附件目前以文件名事实传入，不得假装读取了文件正文。",
                    source = "聊天",
                    title = activeLabel,
                    temperature = 0.85,
                    maxTokens = 1200,
                    connectionOverride = LuluAiServices.connectionStore.resolveConnection(chatArchiveId),
                )
                if (!currentCoroutineContext().isActive) return@launch
                result.onSuccess { reply ->
                    MigratedDomainStores.chat.editMessage(userMessageId, text)
                    MigratedDomainStores.chat.appendCharacterMessage(conversationId, reply.text)
                }.onFailure { error ->
                    MigratedDomainStores.chat.markFailed(userMessageId)
                    snackbarHostState.showSnackbar(error.message ?: "模型回复失败")
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

    fun sendMessage() {
        val attachmentText = pendingAttachments.joinToString("\n") { name -> "[附件：$name]" }
        val text = listOf(input.trim(), attachmentText).filter(String::isNotBlank).joinToString("\n")
        if (text.isBlank() || sending) return
        if (activeArchive == null) {
            scope.launch { snackbarHostState.showSnackbar("请先到设置中把模型加入存档") }
            return
        }
        val userMessage = MigratedDomainStores.chat.sendUserMessage(conversationId, text)
        input = ""
        pendingAttachments = emptyList()
        startGeneration(text, userMessage.id)
    }

    fun retryMessage(message: LuluChatMessage) {
        if (!sending) startGeneration(message.content, message.id)
    }

    fun stopGeneration() {
        pendingMessageId?.let(MigratedDomainStores.chat::markFailed)
        generationJob?.cancel()
        generationJob = null
        pendingMessageId = null
        sending = false
    }

    Scaffold(
        containerColor = ChatV2Paper,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChatV2Avatar(character.displayName.take(1).ifBlank { "露" }, 42)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(character.displayName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("在线 · $activeLabel", color = ChatV2Muted, fontSize = 10.sp, maxLines = 1)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { callVisible = true }) { Icon(Icons.Outlined.Call, "通话") }
                    Box {
                        IconButton(onClick = { moreMenuExpanded = true }) { Icon(Icons.Outlined.MoreVert, "更多") }
                        DropdownMenu(expanded = moreMenuExpanded, onDismissRequest = { moreMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("角色设置") },
                                leadingIcon = { Icon(Icons.Outlined.Tune, null) },
                                onClick = { moreMenuExpanded = false; onCharacterSettings() },
                            )
                            DropdownMenuItem(
                                text = { Text("角色世界书") },
                                leadingIcon = { Icon(Icons.Outlined.Public, null) },
                                onClick = { moreMenuExpanded = false; onWorldBook() },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = ChatV2Paper),
            )
        },
        bottomBar = {
            Surface(color = ChatV2Card, shadowElevation = 8.dp) {
                Column(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(13.dp),
                        color = if (activeArchive == null) Color(0xFFF5E3DF) else Color(0xFFEAE2F4),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (activeArchive == null) Icons.Outlined.WarningAmber else Icons.Outlined.Memory,
                                null,
                                modifier = Modifier.size(16.dp),
                                tint = ChatV2Muted,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(activeLabel, modifier = Modifier.weight(1f), fontSize = 11.sp, color = ChatV2Muted)
                            Box {
                                TextButton(onClick = { archiveMenuExpanded = true }) { Text("切换") }
                                DropdownMenu(expanded = archiveMenuExpanded, onDismissRequest = { archiveMenuExpanded = false }) {
                                    if (library.archives.isEmpty()) {
                                        DropdownMenuItem(text = { Text("暂无模型存档") }, onClick = {}, enabled = false)
                                    } else {
                                        library.archives.forEach { archive ->
                                            ChatV2ModelMenuItem(
                                                archive = archive,
                                                selected = archive.id == chatArchiveId,
                                                onClick = {
                                                    LuluAiServices.connectionStore.selectArchive(archive.id, ModelUsage.Chat)
                                                    archiveMenuExpanded = false
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                            if (sending) {
                                TextButton(onClick = ::stopGeneration) {
                                    Icon(Icons.Outlined.StopCircle, null, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("停止")
                                }
                            }
                        }
                    }
                    if (pendingAttachments.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pendingAttachments.take(3).forEach { name ->
                                InputChip(
                                    selected = false,
                                    onClick = { pendingAttachments = pendingAttachments - name },
                                    label = { Text(name, maxLines = 1) },
                                    trailingIcon = { Icon(Icons.Outlined.Close, "移除", Modifier.size(15.dp)) },
                                )
                            }
                            if (pendingAttachments.size > 3) Text("+${pendingAttachments.size - 3}", color = ChatV2Muted)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(
                            onClick = {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "说给${character.displayName}听")
                                }
                                runCatching { voiceLauncher.launch(intent) }
                                    .onFailure { scope.launch { snackbarHostState.showSnackbar("当前设备没有可用的语音识别服务") } }
                            },
                        ) { Icon(Icons.Outlined.Mic, "语音输入") }
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("和${character.displayName}说点什么…") },
                            maxLines = 4,
                            shape = RoundedCornerShape(20.dp),
                        )
                        IconButton(onClick = { attachmentLauncher.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Outlined.AttachFile, "选择附件")
                        }
                        FilledIconButton(
                            enabled = (input.isNotBlank() || pendingAttachments.isNotEmpty()) && !sending && activeArchive != null,
                            onClick = ::sendMessage,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = ChatV2Wheat),
                        ) {
                            if (sending) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp, color = ChatV2Ink)
                            else Icon(Icons.Outlined.Send, "发送", tint = ChatV2Ink)
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                val previous = messages.getOrNull(index - 1)
                val next = messages.getOrNull(index + 1)
                if (previous == null || Duration.between(previous.createdAt, message.createdAt).toMinutes() >= 5) {
                    ChatV2TimeDivider(message.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")))
                }
                val groupStart = previous == null || previous.sender != message.sender ||
                    Duration.between(previous.createdAt, message.createdAt).toMinutes() >= 2
                val groupEnd = next == null || next.sender != message.sender ||
                    Duration.between(message.createdAt, next.createdAt).toMinutes() >= 2
                ChatV2MessageBubble(
                    message = message,
                    characterName = character.displayName,
                    showAvatar = groupStart,
                    groupEnd = groupEnd,
                    showTimestamp = preferences.showMessageTimestamps,
                    onLongClick = { selectedMessage = message },
                    onRetry = { retryMessage(message) },
                )
            }
            if (sending) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChatV2Avatar(character.displayName.take(1).ifBlank { "露" }, 40)
                        Spacer(Modifier.width(9.dp))
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(7.dp))
                        Text("${character.displayName}正在回复…", color = ChatV2Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    selectedMessage?.let { message ->
        ChatV2MessageActionsSheet(
            message = message,
            onDismiss = { selectedMessage = null },
            onEdit = { editingMessage = message; selectedMessage = null },
            onDelete = { MigratedDomainStores.chat.deleteMessage(message.id); selectedMessage = null },
            onToggleFavorite = { MigratedDomainStores.chat.toggleFavorite(message.id); selectedMessage = null },
            onBranch = {
                val branch = MigratedDomainStores.chat.createBranch(conversationId, message.id)
                selectedMessage = null
                if (branch != null) onOpenBranch(branch.id)
            },
            onRetry = if (message.sender == LuluChatMessage.Sender.User) {
                { selectedMessage = null; retryMessage(message) }
            } else null,
        )
    }
    editingMessage?.let { message ->
        ChatV2EditMessageDialog(
            message = message,
            onDismiss = { editingMessage = null },
            onSave = { content -> MigratedDomainStores.chat.editMessage(message.id, content); editingMessage = null },
        )
    }
    if (callVisible) ChatV2CallDialog(characterName = character.displayName, onDismiss = { callVisible = false })
}

@Composable
private fun ChatV2CallDialog(characterName: String, onDismiss: () -> Unit) {
    var connected by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(connected) {
        while (connected) {
            delay(1_000)
            elapsedSeconds += 1
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (connected) "正在和$characterName通话" else "呼叫$characterName") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                ChatV2Avatar(characterName.take(1).ifBlank { "露" }, 78)
                Spacer(Modifier.height(14.dp))
                Text(
                    if (connected) "%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60) else "准备建立陪伴通话",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "当前版本已完成通话入口、状态与计时；实时双向语音仍取决于设备语音服务和所选模型能力。",
                    color = ChatV2Muted,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (connected) onDismiss() else connected = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (connected) MaterialTheme.colorScheme.error else ChatV2Wheat,
                    contentColor = if (connected) Color.White else ChatV2Ink,
                ),
            ) { Text(if (connected) "挂断" else "开始通话") }
        },
        dismissButton = { if (!connected) TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ChatV2ModelMenuItem(archive: ModelArchive, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(LuluAiServices.connectionStore.archiveLabel(archive), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                if (selected) Text("当前使用", color = ChatV2Muted, fontSize = 11.sp)
            }
        },
        leadingIcon = { Icon(if (selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked, null) },
        onClick = onClick,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatV2MessageBubble(
    message: LuluChatMessage,
    characterName: String,
    showAvatar: Boolean,
    groupEnd: Boolean,
    showTimestamp: Boolean,
    onLongClick: () -> Unit,
    onRetry: () -> Unit,
) {
    val fromUser = message.sender == LuluChatMessage.Sender.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!fromUser) {
            if (showAvatar) ChatV2Avatar(characterName.take(1).ifBlank { "露" }, 40)
            else Spacer(Modifier.width(40.dp))
            Spacer(Modifier.width(9.dp))
        }
        Column(
            horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 310.dp),
        ) {
            Surface(
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick),
                color = if (fromUser) ChatV2Wheat else ChatV2Card,
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (!fromUser && groupEnd) 6.dp else 18.dp,
                    bottomEnd = if (fromUser && groupEnd) 6.dp else 18.dp,
                ),
                border = BorderStroke(1.dp, ChatV2Border),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(message.content, color = ChatV2Ink, lineHeight = 21.sp)
                    if (message.favorite) {
                        Spacer(Modifier.height(4.dp))
                        Icon(Icons.Outlined.Star, "已收藏", tint = ChatV2Muted, modifier = Modifier.size(14.dp).align(Alignment.End))
                    }
                }
            }
            if (groupEnd && (showTimestamp || message.status == LuluChatMessage.Status.Failed)) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showTimestamp) {
                        Text(
                            message.createdAt.atZone(ZoneId.systemDefault()).format(ChatV2TimeFormatter),
                            color = ChatV2Muted.copy(alpha = 0.86f),
                            fontSize = 10.sp,
                        )
                    }
                    if (message.status == LuluChatMessage.Status.Failed) {
                        if (showTimestamp) Spacer(Modifier.width(5.dp))
                        TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 3.dp, vertical = 0.dp)) {
                            Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("重试", fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatV2TimeDivider(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center) {
        Surface(color = Color(0xFFEDE8DE), shape = RoundedCornerShape(50)) {
            Text(text, modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp), color = ChatV2Muted, fontSize = 10.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatV2MessageActionsSheet(
    message: LuluChatMessage,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    onBranch: () -> Unit,
    onRetry: (() -> Unit)?,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("消息操作", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 6.dp))
            ChatV2ActionRow(Icons.Outlined.Edit, "编辑", onEdit)
            ChatV2ActionRow(
                if (message.favorite) Icons.Outlined.StarOutline else Icons.Outlined.Star,
                if (message.favorite) "取消收藏" else "收藏",
                onToggleFavorite,
            )
            ChatV2ActionRow(Icons.Outlined.AccountTree, "从这里创建分支", onBranch)
            if (onRetry != null) ChatV2ActionRow(Icons.Outlined.Refresh, "重新生成回复", onRetry)
            ChatV2ActionRow(Icons.Outlined.DeleteOutline, "删除", onDelete, destructive = true)
        }
    }
}

@Composable
private fun ChatV2ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (destructive) MaterialTheme.colorScheme.error else LocalContentColor.current)
            Spacer(Modifier.width(12.dp))
            Text(text, color = if (destructive) MaterialTheme.colorScheme.error else LocalContentColor.current)
        }
    }
}

@Composable
private fun ChatV2EditMessageDialog(
    message: LuluChatMessage,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var content by remember(message.id) { mutableStateOf(message.content) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑消息") },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 10,
            )
        },
        confirmButton = { TextButton(enabled = content.isNotBlank(), onClick = { onSave(content.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ChatV2Avatar(text: String, size: Int) {
    Surface(
        shape = RoundedCornerShape((size * 0.22f).dp),
        color = Color(0xFFFFE2D7),
        border = BorderStroke(1.dp, ChatV2Border),
        modifier = Modifier.size(size.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, fontWeight = FontWeight.Bold, color = ChatV2Ink, fontSize = (size / 2.8).sp)
        }
    }
}

private val ChatV2TimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
