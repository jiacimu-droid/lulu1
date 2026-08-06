package com.jiacimu.lulu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.ai.archiveIdFor
import com.jiacimu.lulu.data.CompanionPresenceStore
import com.jiacimu.lulu.data.LuluAppPreferencesStore
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.system.LuluDeviceToolBridge
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration

internal val QqPage = Color(0xFFFFFFFF)
internal val QqHeader = Color(0xFFFCFCFC)
internal val QqMine = Color(0xFF292929)
internal val QqMineInk = Color(0xFFFFFFFF)
internal val QqOther = Color(0xFFF4F4F4)
internal val QqMuted = Color(0xFF7A7A7E)
internal val QqInk = Color(0xFF1D1D1F)
internal val QqBorder = Color(0xFFE7E7E7)
internal val QqIconSurface = Color(0xFFF4F4F4)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun QqStyleChatDetailScreen(
    conversationId: String,
    onBack: () -> Unit,
    onCharacterSettings: () -> Unit,
    onWorldBook: () -> Unit,
    onOpenGame: (String?) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val userProfilePrefs = remember { context.getSharedPreferences("lulu_user_profile", android.content.Context.MODE_PRIVATE) }
    val userAvatar = remember { userProfilePrefs.getString("avatar_text", "我").orEmpty().ifBlank { "我" }.take(2) }
    val userAvatarUri = remember { userProfilePrefs.getString("avatar_uri", null) }
    val messages by MigratedDomainStores.chat.messages(conversationId).collectAsState()
    val conversations by MigratedDomainStores.chat.conversations.collectAsState()
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val preferences by LuluAppPreferencesStore.state.collectAsState()
    val presenceStates by CompanionPresenceStore.states.collectAsState()
    val presenceHistories by CompanionPresenceStore.histories.collectAsState()
    val library by LuluAiServices.connectionStore.library.collectAsState()
    val conversation = conversations.firstOrNull { it.id == conversationId }
    val groupChat = conversation?.groupChat
    val characterId = conversation?.characterId ?: "lulu"
    val character = MigratedDomainStores.characters.get(characterId)
    val chatArchiveId = library.archiveIdFor(ModelUsage.Chat)
    val activeArchive = library.archives.firstOrNull { it.id == chatArchiveId }
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
    var replyingTo by remember { mutableStateOf<LuluChatMessage?>(null) }
    var moreExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var callVisible by remember { mutableStateOf(false) }
    var presenceCharacterId by remember { mutableStateOf<String?>(null) }
    var groupSettingsVisible by remember { mutableStateOf(false) }
    var mentionExpanded by remember { mutableStateOf(false) }
    var voiceListening by remember { mutableStateOf(false) }
    var voicePartial by remember { mutableStateOf("") }
    var voiceError by remember { mutableStateOf("") }

    if (groupSettingsVisible && groupChat != null) {
        QqGroupChatSettingsScreen(
            conversationId = conversationId,
            group = groupChat,
            characters = characters.values.sortedBy { it.displayName },
            messages = messages,
            onBack = { groupSettingsVisible = false },
            onSave = { updated ->
                MigratedDomainStores.chat.updateGroupConversation(conversationId, updated)
            },
            onDelete = {
                MigratedDomainStores.chat.deleteConversation(conversationId)
                groupSettingsVisible = false
                onBack()
            },
        )
        return
    }
    val speechRecognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }
    val voiceIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
    }
    fun beginVoiceCapture() {
        voicePartial = ""
        voiceError = ""
        if (speechRecognizer == null) {
            voiceError = "当前手机没有可用的语音识别服务"
            voiceListening = true
        } else {
            voiceListening = true
            speechRecognizer.startListening(voiceIntent)
        }
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginVoiceCapture() else {
            voiceError = "需要麦克风权限才能使用语音输入"
            voiceListening = true
        }
    }

    DisposableEffect(speechRecognizer) {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { voiceListening = true }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                voiceError = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "没有听清楚，再说一次吧"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听到声音"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络连接失败"
                    else -> "语音识别暂时失败"
                }
            }
            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                if (spoken.isNotBlank()) {
                    input = listOf(input.trim(), spoken).filter(String::isNotBlank).joinToString(" ")
                    voiceListening = false
                } else {
                    voiceError = "没有听清楚，再说一次吧"
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                voicePartial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        onDispose {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        }
    }

    LaunchedEffect(conversationId) { MigratedDomainStores.chat.markConversationRead(conversationId) }
    LaunchedEffect(messages.size, preferences.autoScrollChat) {
        if (messages.isNotEmpty() && preferences.autoScrollChat) {
            val announcementOffset = if (groupChat?.announcement.isNullOrBlank()) 0 else 1
            listState.scrollToItem(messages.lastIndex + announcementOffset)
        }
    }

    fun sendOnly() {
        val text = input.trim()
        if (text.isBlank()) return
        MigratedDomainStores.chat.sendUserMessage(conversationId, text, replyingTo?.id)
        input = ""
        replyingTo = null
    }

    fun stopReceiving() {
        generationJob?.cancel()
        generationJob = null
        receiving = false
    }

    fun sendAndReceive() {
        if (receiving) return
        if (activeArchive == null) {
            scope.launch { snackbar.showSnackbar("请先在右上角选择模型") }
            return
        }

        val currentInput = input.trim()
        if (currentInput.isNotBlank()) {
            MigratedDomainStores.chat.sendUserMessage(conversationId, currentInput, replyingTo?.id)
            input = ""
            replyingTo = null
        }

        val latestMessages = MigratedDomainStores.chat.messages(conversationId).value
        val lastCharacterIndex = latestMessages.indexOfLast { it.sender == LuluChatMessage.Sender.Character }
        val latestPending = latestMessages
            .drop(lastCharacterIndex + 1)
            .filter { it.sender == LuluChatMessage.Sender.User }
        if (latestPending.isEmpty()) return

        val pendingIds = latestPending.mapTo(mutableSetOf()) { it.id }
        val pendingText = latestPending.joinToString("\n") { it.content.trim() }
        val history = buildBoundedHistory(
            messages = latestMessages.filterNot { it.id in pendingIds },
            characterName = character.displayName,
            characterNames = characters.mapValues { it.value.displayName },
        )
        receiving = true
        generationJob = scope.launch {
            try {
                if (groupChat == null) {
                    val privateInput = buildString {
                        appendLine("[请按真实聊天的表达节奏决定发几个气泡，不按字数或标点机械切分。一个完整的动作、情绪、观点或紧密相连的句子应留在同一气泡；只有话题转折、独立的反应/追问、或有意停顿时才另开气泡。]")
                        appendLine("[需要分气泡时，只在两个气泡之间输出 $SemanticBubbleSeparator；一个气泡时不要输出标记。不要为了凑数量拆句，也不要输出格式说明。]")
                        append("用户消息：$pendingText")
                    }
                    val result = LuluDeviceToolBridge.respond(
                        characterId = characterId,
                        history = history,
                        userText = privateInput,
                        title = activeLabel,
                        archiveId = chatArchiveId,
                    )
                    if (!currentCoroutineContext().isActive) return@launch
                    result.onSuccess { reply ->
                        val semanticReply = normalizeSemanticBubbles(reply.text)
                        if (semanticReply.isNotBlank()) {
                            MigratedDomainStores.chat.appendCharacterMessage(conversationId, semanticReply)
                        } else {
                            snackbar.showSnackbar("对方刚才没有说清，再点一次试试")
                        }
                    }.onFailure { error -> snackbar.showSnackbar(error.message ?: "回复失败") }
                } else {
                    runGroupReplies(
                        conversationId = conversationId,
                        group = groupChat,
                        pendingText = pendingText,
                        initialHistory = history,
                        activeLabel = activeLabel,
                        archiveId = chatArchiveId,
                        characterNames = characters.mapValues { it.value.displayName },
                        onError = { snackbar.showSnackbar(it) },
                    )
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
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回", tint = QqInk) }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (groupChat == null) {
                            QqAvatar(character.displayName.take(1).ifBlank { "露" }, 42, character.avatarUri)
                        } else {
                            QqGroupAvatar(groupChat, 42)
                        }
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text(
                                groupChat?.let { "${it.name}（${it.members.size + 1}）" } ?: character.displayName,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 17.sp,
                                color = QqInk,
                            )
                            if (groupChat == null) Text(activeLabel, fontSize = 10.sp, color = QqMuted, maxLines = 1)
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
                                    val selected = archive.id == chatArchiveId
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
                                            LuluAiServices.connectionStore.selectArchive(archive.id, ModelUsage.Chat)
                                            modelExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    IconButton(onClick = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        callVisible = true
                    }) { Icon(Icons.Outlined.Call, if (groupChat == null) "电话" else "群聊电话", tint = QqInk) }
                    if (groupChat == null) {
                        Box {
                            IconButton(onClick = { moreExpanded = true }) { Icon(Icons.Outlined.MoreHoriz, "更多", tint = QqInk) }
                            DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                                DropdownMenuItem(text = { Text("角色设置") }, onClick = { moreExpanded = false; onCharacterSettings() })
                                DropdownMenuItem(text = { Text("角色世界书") }, onClick = { moreExpanded = false; onWorldBook() })
                            }
                        }
                    } else {
                        IconButton(onClick = { groupSettingsVisible = true }) {
                            Icon(Icons.Outlined.MoreHoriz, "群聊设置", tint = QqInk)
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = QqHeader, shadowElevation = 4.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box {
                        FilledTonalIconButton(
                            onClick = {
                                if (groupChat != null) {
                                    mentionExpanded = true
                                } else if (voiceListening) {
                                    speechRecognizer?.stopListening()
                                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    beginVoiceCapture()
                                } else {
                                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            modifier = Modifier.size(50.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = QqIconSurface,
                                contentColor = QqInk,
                            ),
                        ) {
                            Icon(
                                when {
                                    groupChat != null -> Icons.Outlined.AlternateEmail
                                    voiceListening -> Icons.Outlined.GraphicEq
                                    else -> Icons.Outlined.KeyboardVoice
                                },
                                if (groupChat != null) "@群成员" else "语音输入",
                                modifier = Modifier.size(27.dp),
                            )
                        }
                    }
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        minLines = 1,
                        maxLines = 5,
                        placeholder = {
                            Text(
                                replyingTo?.let { "回复：${it.content.take(18)}" } ?: "发消息",
                                color = QqMuted,
                                maxLines = 1,
                            )
                        },
                        trailingIcon = {
                            if (replyingTo != null) {
                                IconButton(onClick = { replyingTo = null }) {
                                    Icon(Icons.Outlined.Close, "取消回复", Modifier.size(17.dp))
                                }
                            }
                        },
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
                    ) { Icon(Icons.Outlined.Send, "只发送") }
                    FilledTonalIconButton(
                        onClick = { if (receiving) stopReceiving() else sendAndReceive() },
                        enabled = receiving || input.isNotBlank() || pendingUserMessages.isNotEmpty(),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = QqIconSurface,
                            contentColor = QqInk,
                            disabledContainerColor = QqIconSurface,
                            disabledContentColor = QqMuted.copy(alpha = 0.45f),
                        ),
                    ) {
                        Icon(
                            if (receiving) Icons.Outlined.StopCircle else Icons.Outlined.MarkChatRead,
                            if (receiving) "停止" else "发送并让对方回复",
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
            if (groupChat != null && groupChat.announcement.isNotBlank()) {
                item(key = "group-announcement") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = QqIconSurface,
                        shape = RoundedCornerShape(13.dp),
                        border = BorderStroke(1.dp, QqBorder),
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Outlined.Campaign, null, tint = QqMuted, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Column {
                                Text("群公告", color = QqInk, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                Text(groupChat.announcement, color = QqMuted, fontSize = 12.sp, maxLines = 3)
                            }
                        }
                    }
                }
            }
            itemsIndexed(messages, key = { _, item -> item.id }) { index, message ->
                val previous = messages.getOrNull(index - 1)
                val next = messages.getOrNull(index + 1)
                val groupStart = previous == null || previous.sender != message.sender ||
                    previous.authorCharacterId != message.authorCharacterId ||
                    Duration.between(previous.createdAt, message.createdAt).toMinutes() >= 2
                val groupEnd = next == null || next.sender != message.sender ||
                    next.authorCharacterId != message.authorCharacterId ||
                    Duration.between(message.createdAt, next.createdAt).toMinutes() >= 2
                val author = message.authorCharacterId?.let { characters[it] ?: MigratedDomainStores.characters.get(it) }
                    ?: character
                QqMessageRow(
                    message = message,
                    characterName = author.displayName,
                    characterAvatarUri = author.avatarUri,
                    characterLabel = groupChat?.members
                        ?.firstOrNull { it.characterId == author.characterId }
                        ?.groupNickname
                        ?.ifBlank { author.displayName }
                        ?: author.displayName,
                    showCharacterName = groupChat?.showMemberNames == true,
                    repliedMessageContent = message.replyToMessageId
                        ?.let { replyId -> messages.firstOrNull { it.id == replyId }?.content },
                    showAvatar = groupStart,
                    showTime = preferences.showMessageTimestamps && groupEnd,
                    userAvatar = userAvatar,
                    userAvatarUri = userAvatarUri,
                    onCharacterAvatarClick = { presenceCharacterId = author.characterId },
                    onLongClick = { selectedMessage = message },
                    onAcceptGame = { gameId ->
                        message.authorCharacterId?.let { com.jiacimu.lulu.games.LuluGames.store.selectCharacter(it) }
                        onOpenGame(gameId)
                    },
                )
            }
            if (receiving) {
                item {
                    Row(verticalAlignment = Alignment.Top) {
                        if (groupChat == null) {
                            QqAvatar(character.displayName.take(1).ifBlank { "露" }, 44, character.avatarUri)
                        } else {
                            QqGroupAvatar(groupChat, 44)
                        }
                        Spacer(Modifier.width(9.dp))
                        Surface(
                            color = QqOther,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, QqBorder),
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
                        replyingTo = message
                        selectedMessage = null
                    }) { Text("回复", color = QqInk) }
                    TextButton(onClick = {
                        MigratedDomainStores.chat.toggleFavorite(message.id)
                        selectedMessage = null
                    }) { Text(if (message.favorite) "取消收藏" else "收藏", color = QqInk) }
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
        if (groupChat == null) {
            LuluVoiceCallScreen(
                conversationId = conversationId,
                characterId = characterId,
                characterName = character.displayName,
                onDismiss = {
                    callVisible = false
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                },
            )
        } else {
            LuluGroupVoiceCallScreen(
                conversationId = conversationId,
                group = groupChat,
                onDismiss = {
                    callVisible = false
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                },
            )
        }
    }

    presenceCharacterId?.let { selectedCharacterId ->
        val selectedCharacter = characters[selectedCharacterId]
            ?: MigratedDomainStores.characters.get(selectedCharacterId)
        CompanionPresenceDialog(
            selectedCharacter.displayName,
            presenceStates[selectedCharacterId],
            presenceHistories[selectedCharacterId].orEmpty(),
        ) { presenceCharacterId = null }
    }

    if (mentionExpanded && groupChat != null) {
        ModalBottomSheet(
            onDismissRequest = { mentionExpanded = false },
            containerColor = QqPage,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("@群成员", color = QqInk, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("选择要提醒的人", color = QqMuted, fontSize = 12.sp)
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable {
                        input += "@全体成员 "
                        mentionExpanded = false
                    },
                    color = QqIconSurface,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(42.dp), shape = RoundedCornerShape(12.dp), color = QqMine) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Groups, null, tint = Color.White) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("@全体成员", color = QqInk, fontWeight = FontWeight.SemiBold)
                    }
                }
                groupChat.members.forEach { member ->
                    val memberCharacter = characters[member.characterId]
                        ?: MigratedDomainStores.characters.get(member.characterId)
                    val displayName = member.groupNickname.ifBlank { memberCharacter.displayName }
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable {
                            input += "@$displayName "
                            mentionExpanded = false
                        },
                        color = QqPage,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, QqBorder),
                    ) {
                        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                            QqAvatar(memberCharacter.displayName.take(1).ifBlank { "角" }, 42, memberCharacter.avatarUri)
                            Spacer(Modifier.width(12.dp))
                            Text("@$displayName", color = QqInk, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }

    if (voiceListening) {
        ModalBottomSheet(
            onDismissRequest = {
                speechRecognizer?.cancel()
                voiceListening = false
            },
            containerColor = QqPage,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    modifier = Modifier.size(86.dp),
                    shape = CircleShape,
                    color = QqMine,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.GraphicEq, null, tint = Color.White, modifier = Modifier.size(38.dp))
                    }
                }
                Text(
                    when {
                        voiceError.isNotBlank() -> voiceError
                        voicePartial.isNotBlank() -> voicePartial
                        else -> "正在听你说话…"
                    },
                    color = if (voiceError.isBlank()) QqInk else MaterialTheme.colorScheme.error,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                )
                Text("说完后点完成，识别文字会放进输入框", color = QqMuted, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            speechRecognizer?.cancel()
                            voiceListening = false
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("取消") }
                    Button(
                        onClick = {
                            if (voiceError.isNotBlank()) beginVoiceCapture() else speechRecognizer?.stopListening()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = QqMine, contentColor = Color.White),
                    ) { Text(if (voiceError.isBlank()) "完成" else "重新听") }
                }
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}
