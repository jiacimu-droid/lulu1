package com.jiacimu.lulu

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
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
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.ai.archiveIdFor
import com.jiacimu.lulu.data.LuluAppPreferencesStore
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.LuluGroupChat
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.CompanionPresenceStore
import com.jiacimu.lulu.data.UserProfileContext
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun QqStyleChatDetailScreen(
    conversationId: String,
    onBack: () -> Unit,
    onOpenBranch: (String) -> Unit,
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
                groupSettingsVisible = false
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
                    val result = LuluDeviceToolBridge.respond(
                        characterId = characterId,
                        history = history,
                        userText = pendingText,
                        title = activeLabel,
                        archiveId = chatArchiveId,
                    )
                    if (!currentCoroutineContext().isActive) return@launch
                    result.onSuccess { reply ->
                        if (reply.text.isNotBlank()) {
                            MigratedDomainStores.chat.appendCharacterMessage(conversationId, reply.text)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QqMessageRow(
    message: LuluChatMessage,
    characterName: String,
    characterAvatarUri: String?,
    characterLabel: String,
    showCharacterName: Boolean,
    repliedMessageContent: String?,
    showAvatar: Boolean,
    showTime: Boolean,
    userAvatar: String,
    userAvatarUri: String?,
    onCharacterAvatarClick: () -> Unit,
    onLongClick: () -> Unit,
    onAcceptGame: (String) -> Unit,
) {
    if (message.sender == LuluChatMessage.Sender.System) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(
                color = Color(0xFFF1F1F1),
                shape = RoundedCornerShape(99.dp),
                border = BorderStroke(1.dp, QqBorder),
            ) {
                Text(
                    message.content.removePrefix("[共同活动]").trim(),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    color = QqMuted,
                    fontSize = 11.sp,
                )
            }
        }
        return
    }
    val mine = message.sender == LuluChatMessage.Sender.User
    val gameInvite = remember(message.content, mine) { if (mine) null else parseGameInvite(message.content) }
    val visibleContent = gameInvite?.message ?: message.content
    val bubbles = remember(visibleContent, mine, gameInvite) {
        when {
            gameInvite != null -> emptyList()
            mine -> listOf(visibleContent)
            else -> splitCharacterBubbles(visibleContent)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(44.dp), contentAlignment = Alignment.TopCenter) {
            if (!mine && showAvatar) {
                QqAvatar(
                    characterName.take(1).ifBlank { "露" },
                    44,
                    characterAvatarUri,
                    Modifier.clickable(onClick = onCharacterAvatarClick),
                )
            }
        }
        Spacer(Modifier.width(9.dp))
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            if (!mine && showCharacterName && showAvatar) {
                Text(
                    characterLabel,
                    color = QqMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 3.dp),
                )
            }
            if (gameInvite != null) {
                GameInviteMessageCard(gameInvite, onAccept = { onAcceptGame(gameInvite.gameId) })
                Spacer(Modifier.height(5.dp))
            }
            bubbles.filter { it.isNotBlank() }.forEachIndexed { index, bubble ->
                val bubbleWidth = if (bubble.length >= 52) Modifier.fillMaxWidth() else Modifier.widthIn(max = 300.dp)
                Surface(
                    modifier = bubbleWidth.combinedClickable(onClick = {}, onLongClick = onLongClick),
                    color = if (mine) QqMine else QqOther,
                    shape = RoundedCornerShape(15.dp),
                    border = BorderStroke(1.dp, if (mine) QqMine else QqBorder),
                    shadowElevation = 0.dp,
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        repliedMessageContent?.let { quoted ->
                            Surface(
                                color = if (mine) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.78f),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(
                                    quoted,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                                    color = if (mine) QqMineInk.copy(alpha = 0.72f) else QqMuted,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        Text(
                            bubble,
                            color = if (mine) QqMineInk else QqInk,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                        )
                        if (message.favorite && index == bubbles.lastIndex) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "★ 已收藏",
                                color = if (mine) Color.White.copy(alpha = 0.68f) else QqMuted,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
                if (index != bubbles.lastIndex) Spacer(Modifier.height(5.dp))
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
        Spacer(Modifier.width(9.dp))
        Box(Modifier.width(44.dp), contentAlignment = Alignment.TopCenter) {
            if (mine && showAvatar) QqAvatar(userAvatar, 44, userAvatarUri)
        }
    }
}

private data class GameInviteMessage(val gameId: String, val title: String, val message: String)

private fun parseGameInvite(content: String): GameInviteMessage? {
    val match = Regex("^\\[游戏邀约\\|([^|\\]]+)\\|([^\\]]+)]\\s*(.*)$", RegexOption.DOT_MATCHES_ALL).find(content.trim())
        ?: return null
    return GameInviteMessage(
        gameId = match.groupValues[1].trim(),
        title = match.groupValues[2].trim().ifBlank { "一起玩游戏" },
        message = match.groupValues[3].trim(),
    )
}

@Composable
private fun GameInviteMessageCard(invite: GameInviteMessage, onAccept: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, QqBorder),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(86.dp).background(Color(0xFF292929)),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(14.dp), color = Color.White.copy(alpha = 0.14f), modifier = Modifier.size(50.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.SportsEsports, null, tint = Color.White) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("游戏邀约", color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp)
                        Text(invite.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (invite.message.isNotBlank()) Text(invite.message, color = QqInk, lineHeight = 20.sp)
                Button(
                    onClick = onAccept,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = QqMine, contentColor = Color.White),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(19.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("接受邀约")
                }
            }
        }
    }
}

private fun splitCharacterBubbles(text: String): List<String> {
    val paragraphs = text.trim()
        .split(Regex("\\n\\s*\\n"))
        .map(String::trim)
        .filter(String::isNotBlank)
    if (paragraphs.isEmpty()) return listOf(text.trim())
    return paragraphs.flatMap { paragraph ->
        val normalized = paragraph.lines().map(String::trim).filter(String::isNotBlank).joinToString("\n")
        if (normalized.length <= 78) {
            listOf(normalized)
        } else {
            val sentences = normalized.split(Regex("(?<=[。！？!?；;…])\\s*"))
                .map(String::trim)
                .filter(String::isNotBlank)
            val bubbles = mutableListOf<String>()
            var current = ""
            sentences.forEach { sentence ->
                val joinedLength = current.length + sentence.length
                if (current.isBlank() || joinedLength <= 58 || current.length < 24) {
                    current += sentence
                } else {
                    bubbles += current
                    current = sentence
                }
            }
            if (current.isNotBlank()) bubbles += current
            bubbles.ifEmpty { listOf(normalized) }
        }
    }
}

internal suspend fun runGroupReplies(
    conversationId: String,
    group: LuluGroupChat,
    pendingText: String,
    initialHistory: String,
    activeLabel: String,
    archiveId: String?,
    characterNames: Map<String, String>,
    onError: suspend (String) -> Unit,
    sceneContext: String = "你正在群聊《${group.name}》中。群里的所有消息对在场成员可见。",
    onSpeakerChange: (String?) -> Unit = {},
    afterReply: suspend (String, String) -> Unit = { _, _ -> },
) {
    val validMembers = group.members.filter { it.characterId in characterNames }
    if (validMembers.size < 2) {
        onError("群聊至少需要两个仍然存在的角色")
        return
    }
    val mentioned = validMembers.filter { member ->
        val name = member.groupNickname.ifBlank { characterNames[member.characterId].orEmpty() }
        name.isNotBlank() && pendingText.contains("@$name", ignoreCase = true)
    }
    val lastSpeaker = MigratedDomainStores.chat.messages(conversationId).value
        .lastOrNull { it.sender == LuluChatMessage.Sender.Character }
        ?.authorCharacterId
    val remaining = validMembers
        .filterNot { candidate -> mentioned.any { it.characterId == candidate.characterId } }
        .let { members ->
            if (members.size > 1 && members.firstOrNull()?.characterId == lastSpeaker) members.drop(1) + members.first()
            else members
        }
    val ordered = mentioned + remaining
    val explicitAll = pendingText.contains("@全体成员")
    val targetReplies = when {
        explicitAll -> group.maxAutoReplies
        mentioned.isNotEmpty() && group.allowCharacterConversation -> group.maxAutoReplies.coerceAtLeast(mentioned.size)
        mentioned.isNotEmpty() -> mentioned.size.coerceAtMost(group.maxAutoReplies)
        group.allowCharacterConversation -> group.maxAutoReplies
        else -> 1
    }
    val speakingTurns = List(targetReplies.coerceIn(1, 8)) { turn -> ordered[turn % ordered.size] }

    speakingTurns.forEachIndexed { index, member ->
        if (!currentCoroutineContext().isActive) return
        onSpeakerChange(member.characterId)
        val character = MigratedDomainStores.characters.get(member.characterId)
        val memberLabel = member.groupNickname.ifBlank { character.displayName }
        val latestMessages = MigratedDomainStores.chat.messages(conversationId).value
        val history = if (index == 0) initialHistory else buildBoundedHistory(
            messages = latestMessages,
            characterName = memberLabel,
            characterNames = characterNames,
        )
        val memberList = group.members.joinToString("、") { candidate ->
            candidate.groupNickname.ifBlank { characterNames[candidate.characterId] ?: candidate.characterId }
        }
        val groupInput = buildString {
            appendLine("[这是群聊，不是私聊。群名：${group.name}；群成员：${group.userGroupNickname}、$memberList。]")
            appendLine("[当前由你（$memberLabel）发言。只代表你自己，严格遵循你的人设和关系边界；不要替别人说话，不要输出姓名标签。]")
            if (index > 0) {
                appendLine("[前面已经有人回应。你可以直接回应、赞同、质疑、追问、开玩笑或转向另一位成员；要对上一轮真实内容产生反应，推动成员之间互相聊天，不要只围着用户轮流答题。]")
            }
            if (index >= validMembers.size) appendLine("[这是群内继续接话，同一角色可以再次回应刚才的新内容，但不能复述自己的上一句话。]")
            append("用户最初在群里说：$pendingText")
        }
        val result = LuluDeviceToolBridge.respond(
            characterId = member.characterId,
            history = history,
            userText = groupInput,
            title = activeLabel,
            archiveId = archiveId,
            sceneContext = sceneContext,
        )
        if (!currentCoroutineContext().isActive) return
        val reply = result.getOrNull()
        if (reply != null) {
            if (reply.text.isNotBlank()) {
                MigratedDomainStores.chat.appendCharacterMessage(
                    conversationId = conversationId,
                    content = reply.text,
                    authorCharacterId = member.characterId,
                )
                afterReply(member.characterId, reply.text)
            }
        } else {
            val error = result.exceptionOrNull()
            onError("${character.displayName}回复失败：${error?.message ?: "未知错误"}")
        }
    }
    onSpeakerChange(null)
}

internal fun buildBoundedHistory(
    messages: List<LuluChatMessage>,
    characterName: String,
    characterNames: Map<String, String> = emptyMap(),
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
        val role = if (message.sender == LuluChatMessage.Sender.User) {
            UserProfileContext.displayLabel()
        } else {
            message.authorCharacterId?.let { characterNames[it] } ?: characterName
        }
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
private fun QqGroupAvatar(group: LuluGroupChat, size: Int) {
    if (!group.avatarUri.isNullOrBlank()) {
        QqAvatar(group.name.take(1).ifBlank { "群" }, size, group.avatarUri)
    } else {
        Surface(
            modifier = Modifier.size(size.dp),
            color = QqOther,
            shape = RoundedCornerShape((size * 0.28f).dp),
            border = BorderStroke(1.dp, QqBorder),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Groups, null, tint = QqInk, modifier = Modifier.size((size * 0.55f).dp))
            }
        }
    }
}

@Composable
private fun QqAvatar(label: String, size: Int, imageUri: String? = null, modifier: Modifier = Modifier) {
    LuluProfileAvatar(imageUri = imageUri, fallback = label, size = size, modifier = modifier)
}
