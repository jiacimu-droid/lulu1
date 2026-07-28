package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelArchive
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.LuluConversation
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ChatPaper = Color(0xFFFFFCF5)
private val ChatCard = Color(0xFFFFFBF3)
private val ChatWheat = Color(0xFFF2CF70)
private val ChatMuted = Color(0xFF747887)
private val ChatBorder = Color(0xFFE7DDC8)
private val ChatInk = Color(0xFF2F2B2A)

@Composable
fun MigratedChatHubScreen(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onCharacterSettings: () -> Unit,
    onWorldBook: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val labels = listOf("消息", "角色", "朋友圈", "我的")
    val icons = listOf(
        Icons.Outlined.ChatBubbleOutline,
        Icons.Outlined.PeopleOutline,
        Icons.Outlined.DynamicFeed,
        Icons.Outlined.PersonOutline,
    )

    Scaffold(
        containerColor = ChatPaper,
        topBar = { MigratedChatTopBar("聊天", onBack) },
        bottomBar = {
            NavigationBar(containerColor = ChatCard) {
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(icons[index], label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> MigratedConversationList(onOpenConversation)
                1 -> MigratedCharacterList(onCharacterSettings, onWorldBook)
                2 -> MomentsPlaceholderScreen()
                else -> MyProfileScreen()
            }
        }
    }
}

@Composable
private fun MigratedConversationList(onOpenConversation: (String) -> Unit) {
    val conversations by MigratedDomainStores.chat.conversations.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(conversations, key = LuluConversation::id) { conversation ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            MigratedDomainStores.chat.markConversationRead(conversation.id)
                            onOpenConversation(conversation.id)
                        },
                        onLongClick = {},
                    ),
                colors = CardDefaults.cardColors(containerColor = ChatCard),
                border = BorderStroke(1.dp, ChatBorder),
                shape = RoundedCornerShape(22.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChatAvatar(conversation.title.take(1).ifBlank { "露" }, 52)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(conversation.title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(
                                conversation.updatedAt.atZone(ZoneId.systemDefault()).format(TimeFormatter),
                                color = ChatMuted,
                                fontSize = 11.sp,
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            conversation.lastMessage,
                            color = ChatMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (conversation.parentConversationId != null) {
                            Text("聊天分支", color = ChatMuted, fontSize = 10.sp)
                        }
                    }
                    if (conversation.unreadCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        Badge { Text(conversation.unreadCount.toString()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MigratedCharacterList(
    onCharacterSettings: () -> Unit,
    onWorldBook: () -> Unit,
) {
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("角色", fontWeight = FontWeight.Bold, fontSize = 25.sp)
                    Text("管理生活在露露机里的角色", color = ChatMuted)
                }
                FilledIconButton(
                    onClick = { showCreateDialog = true },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = ChatWheat),
                ) { Icon(Icons.Outlined.Add, "新建角色") }
            }
        }
        items(characters.values.toList(), key = { character -> character.characterId }) { character ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ChatCard),
                border = BorderStroke(1.dp, ChatBorder),
                shape = RoundedCornerShape(22.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChatAvatar(character.displayName.take(1).ifBlank { "角" }, 58)
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(character.displayName, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text(
                                if (character.characterId == "lulu") "当前角色" else "已创建角色",
                                color = ChatMuted,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    HorizontalDivider(color = ChatBorder)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onCharacterSettings,
                            enabled = character.characterId == "lulu",
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Tune, null)
                            Spacer(Modifier.width(5.dp))
                            Text("角色设置")
                        }
                        OutlinedButton(onClick = onWorldBook, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Public, null)
                            Spacer(Modifier.width(5.dp))
                            Text("世界书")
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateCharacterDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, persona ->
                MigratedDomainStores.characters.create(name, persona)
                showCreateDialog = false
            },
        )
    }
}

@Composable
private fun CreateCharacterDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var persona by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建角色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("角色名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = persona,
                    onValueChange = { persona = it },
                    label = { Text("角色核心设定") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name, persona) }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MigratedChatDetailScreen(
    conversationId: String,
    onBack: () -> Unit,
    onOpenBranch: (String) -> Unit,
) {
    val messages by MigratedDomainStores.chat.messages(conversationId).collectAsState()
    val conversations by MigratedDomainStores.chat.conversations.collectAsState()
    val conversation = conversations.firstOrNull { item -> item.id == conversationId }
    val characterId = conversation?.characterId ?: "lulu"
    val character = MigratedDomainStores.characters.get(characterId)
    val library by LuluAiServices.connectionStore.library.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var generationJob by remember { mutableStateOf<Job?>(null) }
    var pendingMessageId by remember { mutableStateOf<String?>(null) }
    var archiveMenuExpanded by remember { mutableStateOf(false) }
    var selectedMessage by remember { mutableStateOf<LuluChatMessage?>(null) }
    var editingMessage by remember { mutableStateOf<LuluChatMessage?>(null) }

    val activeArchive = library.archives.firstOrNull { archive -> archive.id == library.activeArchiveId }
    val activeLabel = activeArchive?.let(LuluAiServices.connectionStore::archiveLabel) ?: "未选择模型"

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
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
                    instruction = "延续当前对话，以角色本人的口吻自然回复主人。不要复述系统提示。",
                    source = "聊天",
                    title = activeLabel,
                    temperature = 0.85,
                    maxTokens = 1200,
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
        val text = input.trim()
        if (text.isBlank() || sending) return
        if (activeArchive == null) {
            scope.launch { snackbarHostState.showSnackbar("请先到设置中把模型加入存档") }
            return
        }
        val userMessage = MigratedDomainStores.chat.sendUserMessage(conversationId, text)
        input = ""
        startGeneration(text, userMessage.id)
    }

    fun retryMessage(message: LuluChatMessage) {
        if (sending) return
        startGeneration(message.content, message.id)
    }

    fun stopGeneration() {
        pendingMessageId?.let { messageId -> MigratedDomainStores.chat.markFailed(messageId) }
        generationJob?.cancel()
        generationJob = null
        pendingMessageId = null
        sending = false
    }

    Scaffold(
        containerColor = ChatPaper,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(character.displayName, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(activeLabel, color = ChatMuted, fontSize = 10.sp, maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch { snackbarHostState.showSnackbar("通话将在电话迁移阶段接入") }
                    }) { Icon(Icons.Outlined.Call, "通话") }
                    Box {
                        IconButton(onClick = { archiveMenuExpanded = true }) {
                            Icon(Icons.Outlined.SwapHoriz, "切换模型")
                        }
                        DropdownMenu(
                            expanded = archiveMenuExpanded,
                            onDismissRequest = { archiveMenuExpanded = false },
                        ) {
                            if (library.archives.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("暂无模型存档") },
                                    onClick = { archiveMenuExpanded = false },
                                    enabled = false,
                                )
                            } else {
                                library.archives.forEach { archive ->
                                    MigratedModelArchiveMenuItem(
                                        archive = archive,
                                        selected = archive.id == library.activeArchiveId,
                                        onClick = {
                                            LuluAiServices.connectionStore.selectArchive(archive.id)
                                            archiveMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = ChatPaper),
            )
        },
        bottomBar = {
            Surface(color = ChatCard, shadowElevation = 8.dp) {
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
                                tint = ChatMuted,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(activeLabel, modifier = Modifier.weight(1f), fontSize = 11.sp, color = ChatMuted)
                            if (sending) {
                                TextButton(onClick = ::stopGeneration) {
                                    Icon(Icons.Outlined.StopCircle, null, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("停止")
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(onClick = {
                            scope.launch { snackbarHostState.showSnackbar("语音输入将在语音迁移阶段接入") }
                        }) { Icon(Icons.Outlined.Mic, "语音") }
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("和${character.displayName}说点什么…") },
                            maxLines = 4,
                            shape = RoundedCornerShape(20.dp),
                        )
                        IconButton(onClick = {
                            scope.launch { snackbarHostState.showSnackbar("附件将在文件消息迁移阶段接入") }
                        }) { Icon(Icons.Outlined.AttachFile, "附件") }
                        FilledIconButton(
                            enabled = input.isNotBlank() && !sending && activeArchive != null,
                            onClick = ::sendMessage,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = ChatWheat),
                        ) {
                            if (sending) {
                                CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp, color = ChatInk)
                            } else {
                                Icon(Icons.Outlined.Send, "发送", tint = ChatInk)
                            }
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
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                val previous = messages.getOrNull(index - 1)
                val next = messages.getOrNull(index + 1)
                if (previous == null || Duration.between(previous.createdAt, message.createdAt).toMinutes() >= 5) {
                    ChatTimeDivider(message.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")))
                }
                val groupStart = previous == null ||
                    previous.sender != message.sender ||
                    Duration.between(previous.createdAt, message.createdAt).toMinutes() >= 2
                val groupEnd = next == null ||
                    next.sender != message.sender ||
                    Duration.between(message.createdAt, next.createdAt).toMinutes() >= 2
                MigratedMessageBubble(
                    message = message,
                    characterName = character.displayName,
                    showAvatar = groupStart,
                    groupEnd = groupEnd,
                    onLongClick = { selectedMessage = message },
                    onRetry = { retryMessage(message) },
                )
            }
            if (sending) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("${character.displayName}正在回复…", color = ChatMuted, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    selectedMessage?.let { message ->
        MessageActionsSheet(
            message = message,
            onDismiss = { selectedMessage = null },
            onEdit = {
                editingMessage = message
                selectedMessage = null
            },
            onDelete = {
                MigratedDomainStores.chat.deleteMessage(message.id)
                selectedMessage = null
            },
            onToggleFavorite = {
                MigratedDomainStores.chat.toggleFavorite(message.id)
                selectedMessage = null
            },
            onBranch = {
                val branch = MigratedDomainStores.chat.createBranch(conversationId, message.id)
                selectedMessage = null
                if (branch != null) onOpenBranch(branch.id)
            },
            onRetry = if (message.sender == LuluChatMessage.Sender.User) {
                {
                    selectedMessage = null
                    retryMessage(message)
                }
            } else {
                null
            },
        )
    }

    editingMessage?.let { message ->
        EditMessageDialog(
            message = message,
            onDismiss = { editingMessage = null },
            onSave = { content ->
                MigratedDomainStores.chat.editMessage(message.id, content)
                editingMessage = null
            },
        )
    }
}

@Composable
private fun MigratedModelArchiveMenuItem(
    archive: ModelArchive,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(
                    LuluAiServices.connectionStore.archiveLabel(archive),
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
                if (selected) Text("当前使用", color = ChatMuted, fontSize = 11.sp)
            }
        },
        leadingIcon = {
            Icon(if (selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked, null)
        },
        onClick = onClick,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MigratedMessageBubble(
    message: LuluChatMessage,
    characterName: String,
    showAvatar: Boolean,
    groupEnd: Boolean,
    onLongClick: () -> Unit,
    onRetry: () -> Unit,
) {
    val fromUser = message.sender == LuluChatMessage.Sender.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!fromUser) {
            if (showAvatar) ChatAvatar(characterName.take(1).ifBlank { "露" }, 32) else Spacer(Modifier.width(32.dp))
            Spacer(Modifier.width(7.dp))
        }
        Column(
            horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 310.dp),
        ) {
            Surface(
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick),
                color = if (fromUser) ChatWheat else ChatCard,
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (!fromUser && groupEnd) 5.dp else 18.dp,
                    bottomEnd = if (fromUser && groupEnd) 5.dp else 18.dp,
                ),
                border = BorderStroke(1.dp, ChatBorder),
            ) {
                Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                    Text(message.content)
                    if (message.favorite) {
                        Spacer(Modifier.height(4.dp))
                        Icon(
                            Icons.Outlined.Star,
                            "已收藏",
                            tint = ChatMuted,
                            modifier = Modifier.size(14.dp).align(Alignment.End),
                        )
                    }
                }
            }
            if (groupEnd) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        message.createdAt.atZone(ZoneId.systemDefault()).format(TimeFormatter),
                        color = ChatMuted,
                        fontSize = 10.sp,
                    )
                    if (message.status == LuluChatMessage.Status.Failed) {
                        Spacer(Modifier.width(5.dp))
                        TextButton(
                            onClick = onRetry,
                            contentPadding = PaddingValues(horizontal = 3.dp, vertical = 0.dp),
                        ) {
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
private fun ChatTimeDivider(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(color = Color(0xFFEDE8DE), shape = RoundedCornerShape(50)) {
            Text(text, modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp), color = ChatMuted, fontSize = 10.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
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
            MessageActionRow(Icons.Outlined.Edit, "编辑", onEdit)
            MessageActionRow(
                if (message.favorite) Icons.Outlined.StarOutline else Icons.Outlined.Star,
                if (message.favorite) "取消收藏" else "收藏",
                onToggleFavorite,
            )
            MessageActionRow(Icons.Outlined.AccountTree, "从这里创建分支", onBranch)
            if (onRetry != null) MessageActionRow(Icons.Outlined.Refresh, "重新生成回复", onRetry)
            MessageActionRow(Icons.Outlined.DeleteOutline, "删除", onDelete, destructive = true)
        }
    }
}

@Composable
private fun MessageActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                null,
                tint = if (destructive) MaterialTheme.colorScheme.error else LocalContentColor.current,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text,
                color = if (destructive) MaterialTheme.colorScheme.error else LocalContentColor.current,
            )
        }
    }
}

@Composable
private fun EditMessageDialog(
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
        confirmButton = {
            TextButton(enabled = content.isNotBlank(), onClick = { onSave(content.trim()) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MigratedChatTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = ChatPaper),
    )
}

@Composable
private fun ChatAvatar(text: String, size: Int) {
    Surface(shape = CircleShape, color = Color(0xFFFFE2D7), modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, fontWeight = FontWeight.Bold, color = ChatInk, fontSize = (size / 2.8).sp)
        }
    }
}

private val TimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
