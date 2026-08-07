package com.jiacimu.lulu

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    val density = LocalDensity.current
    val userProfilePrefs = remember { context.getSharedPreferences("lulu_user_profile", android.content.Context.MODE_PRIVATE) }
    val userAvatar = remember { userProfilePrefs.getString("avatar_text", "我").orEmpty().ifBlank { "我" }.take(2) }
    val userAvatarUri = remember { userProfilePrefs.getString("avatar_uri", null) }
    val messages by MigratedDomainStores.chat.messages(conversationId).collectAsState()
    val visibleMessages = remember(messages) {
        val recalledIds = recalledMessageIds(messages)
        messages.filterNot { it.id in recalledIds }
    }
    val conversations by MigratedDomainStores.chat.conversations.collectAsState()
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val preferences by LuluAppPreferencesStore.state.collectAsState()
    val presenceStates by CompanionPresenceStore.states.collectAsState()
    val presenceHistories by CompanionPresenceStore.histories.collectAsState()
    val library by LuluAiServices.connectionStore.library.collectAsState()
    val replyTaskStates by ChatReplyTaskManager.states.collectAsState()
    val replyTaskState = replyTaskStates[conversationId] ?: ChatReplyTaskManager.TaskState()
    val receiving = replyTaskState.running
    val typingCharacterId = replyTaskState.typingCharacterId
    val conversation = conversations.firstOrNull { it.id == conversationId }
    val groupChat = conversation?.groupChat
    val characterId = conversation?.characterId ?: "lulu"
    val character = MigratedDomainStores.characters.get(characterId)
    val chatArchiveId = library.archiveIdFor(ModelUsage.Chat)
    val activeArchive = library.archives.firstOrNull { it.id == chatArchiveId }
    val activeLabel = activeArchive?.let(LuluAiServices.connectionStore::archiveLabel) ?: "未连接模型"
    val pendingUserMessages = remember(messages) {
        val lastCharacterIndex = messages.indexOfLast { it.sender == LuluChatMessage.Sender.Character }
        messages.drop(lastCharacterIndex + 1).filter { message ->
            message.sender == LuluChatMessage.Sender.User ||
                (message.sender == LuluChatMessage.Sender.System && message.content.startsWith("[戳一戳] 你戳了戳"))
        }
    }

    val listState = rememberLazyListState()
    val imeBottom = WindowInsets.ime.getBottom(density)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var input by remember { mutableStateOf("") }
    var selectedMessage by remember { mutableStateOf<LuluChatMessage?>(null) }
    var replyingTo by remember { mutableStateOf<LuluChatMessage?>(null) }
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedMessageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var forwardingMessages by remember { mutableStateOf<List<LuluChatMessage>?>(null) }
    var moreExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var callVisible by remember { mutableStateOf(false) }
    var presenceCharacterId by remember { mutableStateOf<String?>(null) }
    var groupSettingsVisible by remember { mutableStateOf(false) }
    var mentionExpanded by remember { mutableStateOf(false) }
    var voiceListening by remember { mutableStateOf(false) }
    var voicePartial by remember { mutableStateOf("") }
    var voiceError by remember { mutableStateOf("") }

    fun messageLabel(message: LuluChatMessage): String = when (message.sender) {
        LuluChatMessage.Sender.User -> "我"
        LuluChatMessage.Sender.System -> "系统"
        LuluChatMessage.Sender.Character -> message.authorCharacterId
            ?.let { id ->
                val role = characters[id] ?: MigratedDomainStores.characters.get(id)
                groupChat?.members?.firstOrNull { it.characterId == id }?.groupNickname?.ifBlank { role.displayName }
                    ?: role.displayName
            }
            ?: character.displayName
    }

    fun resolvedReplyId(message: LuluChatMessage): String? =
        message.replyToMessageId ?: characterReplyQuoteId(message.content)

    fun repliedPreview(message: LuluChatMessage): String? = resolvedReplyId(message)
        ?.let { replyId -> messages.firstOrNull { it.id == replyId } }
        ?.let { original -> "${messageLabel(original)}：${stripCharacterReplyDirective(original.content).take(180)}" }

    fun beginReply(message: LuluChatMessage) {
        replyingTo = message
        selectedMessage = null
        focusManager.clearFocus(force = false)
        keyboardController?.show()
    }

    fun toggleSelected(messageId: String) {
        selectedMessageIds = if (messageId in selectedMessageIds) selectedMessageIds - messageId else selectedMessageIds + messageId
    }

    fun buildForwardPayload(values: List<LuluChatMessage>): String {
        val ordered = messages.filter { source -> values.any { it.id == source.id } }
        val title = if (groupChat != null) "${groupChat.name}的聊天记录" else "${character.displayName}的聊天记录"
        val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        return encodeQqForwardedChat(
            QqForwardedChatBundle(
                title = title,
                entries = ordered.map { item ->
                    QqForwardedChatEntry(
                        sender = messageLabel(item),
                        content = qqForwardContextText(item.content),
                        timeLabel = item.createdAt.atZone(ZoneId.systemDefault()).format(formatter),
                    )
                },
            ),
        )
    }

    if (groupSettingsVisible && groupChat != null) {
        QqGroupChatSettingsScreen(
            conversationId = conversationId,
            group = groupChat,
            characters = characters.values.sortedBy { it.displayName },
            messages = messages,
            onBack = { groupSettingsVisible = false },
            onSave = { updated -> MigratedDomainStores.chat.updateGroupConversation(conversationId, updated) },
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
    LaunchedEffect(replyTaskState.lastError) {
        replyTaskState.lastError?.let { error ->
            snackbar.showSnackbar(error)
            ChatReplyTaskManager.clearError(conversationId)
        }
    }
    LaunchedEffect(visibleMessages.size, preferences.autoScrollChat, imeBottom) {
        if (visibleMessages.isNotEmpty() && (preferences.autoScrollChat || imeBottom > 0)) {
            val announcementOffset = if (groupChat?.announcement.isNullOrBlank()) 0 else 1
            listState.scrollToItem(visibleMessages.lastIndex + announcementOffset)
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
        ChatReplyTaskManager.stop(conversationId)
    }

    fun sendAndReceive(includeDraft: Boolean = true) {
        if (ChatReplyTaskManager.state(conversationId).running) return
        if (activeArchive == null) {
            scope.launch { snackbar.showSnackbar("请先在右上角选择模型") }
            return
        }

        val currentInput = if (includeDraft) input.trim() else ""
        if (currentInput.isNotBlank()) {
            MigratedDomainStores.chat.sendUserMessage(conversationId, currentInput, replyingTo?.id)
            input = ""
            replyingTo = null
        }

        val latestMessages = MigratedDomainStores.chat.messages(conversationId).value
        val lastCharacterIndex = latestMessages.indexOfLast { it.sender == LuluChatMessage.Sender.Character }
        val latestPending = latestMessages.drop(lastCharacterIndex + 1).filter { message ->
            message.sender == LuluChatMessage.Sender.User ||
                (message.sender == LuluChatMessage.Sender.System && message.content.startsWith("[戳一戳] 你戳了戳"))
        }
        if (latestPending.isEmpty()) return

        val pendingIds = latestPending.mapTo(mutableSetOf()) { it.id }
        val pendingText = latestPending.joinToString("\n") { message ->
            if (message.sender == LuluChatMessage.Sender.System) {
                message.content.removePrefix("[戳一戳]").trim()
            } else {
                qqForwardContextText(message.content)
            }
        }
        val characterNamesSnapshot = characters.mapValues { it.value.displayName }
        val history = buildBoundedHistory(
            messages = latestMessages.filterNot { it.id in pendingIds },
            characterName = character.displayName,
            characterNames = characterNamesSnapshot,
        )

        ChatReplyTaskManager.launch(conversationId) {
            if (groupChat == null) {
                setTypingCharacter(characterId)
                val quotableUserMessages = latestMessages
                    .filter { it.sender == LuluChatMessage.Sender.User }
                    .takeLast(6)
                val privateInput = buildString {
                    appendLine("[这是连续发生的即时通讯聊天。最近对话是已经经历过的上一刻，不要把每一条新消息当成一次全新的问答；从上一刻的人物状态、关系和话题位置继续。]")
                    appendLine("[按照你此刻想表达的语气、停顿、情绪变化、补充、转折、追问、吐槽、强调、改口和自己的聊天习惯决定什么时候按一次发送。现实聊天中会在这里按发送，就在这里结束一个气泡。]")
                    appendLine("[一个气泡通常只放一个当下表达动作。先回应、再补充、再转折或追问时，通常应该连续发送几个短气泡；多个气泡之间只输出 $SemanticBubbleSeparator。不要按固定字数机械切，也不要把几个不同表达动作塞成一个长气泡。]")
                    appendLine("[只有非常少见、很符合当下人设的情况下，例如刚说出口就觉得说漏嘴、说重了或突然后悔，才可以在回复末尾输出 ⟪RECALL:n⟫，n 是本次第 n 个气泡（从1开始）。不要为了显得像真人而频繁撤回。]")
                    appendLine("[如果你此刻真的会自然地戳一下用户，可以在回复末尾输出 ⟪POKE_USER⟫；尤其用户刚戳过你时可以考虑戳回来，但不要滥用。]")
                    if (quotableUserMessages.isNotEmpty()) {
                        appendLine("[你也可以像真人聊天一样，偶尔在确实需要针对用户某一句单独回应时引用那条气泡；不要为了展示功能而每次都引用。以下是可引用的近期用户气泡：]")
                        quotableUserMessages.forEach { item ->
                            appendLine("[消息ID=${item.id} 内容=${qqForwardContextText(item.content).take(300)}]")
                        }
                        appendLine("[如果决定引用，只在整段回复最前输出 ⟪QUOTE:消息ID⟫，随后正常输出回复内容；只能使用上面真实存在的消息ID。没有必要引用时不要输出这个标记。]")
                    }
                    append("这一刻用户新增的消息：$pendingText")
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
                    val presentation = parseCharacterReplyPresentation(reply.text)
                    if (presentation.content.isNotBlank()) {
                        appendRoleReplyWithPacing(
                            conversationId = conversationId,
                            characterId = characterId,
                            characterLabel = character.displayName,
                            presentation = presentation,
                        )
                    } else {
                        reportError("对方刚才没有说清，再点一次试试")
                    }
                }.onFailure { error -> reportError(error.message ?: "回复失败") }
            } else {
                runGroupReplies(
                    conversationId = conversationId,
                    group = groupChat,
                    pendingText = pendingText,
                    initialHistory = history,
                    activeLabel = activeLabel,
                    archiveId = chatArchiveId,
                    characterNames = characterNamesSnapshot,
                    onError = { reportError(it) },
                    onSpeakerChange = { setTypingCharacter(it) },
                )
            }
        }
    }

    fun regenerateLatestReply(message: LuluChatMessage) {
        if (ChatReplyTaskManager.state(conversationId).running) {
            selectedMessage = null
            scope.launch { snackbar.showSnackbar("这一轮还在回复中，先等它说完") }
            return
        }
        if (activeArchive == null) {
            selectedMessage = null
            scope.launch { snackbar.showSnackbar("请先在右上角选择模型") }
            return
        }
        val snapshot = MigratedDomainStores.chat.messages(conversationId).value
        val turn = regeneratableLatestTurn(snapshot, message.id)
        if (turn == null) {
            selectedMessage = null
            scope.launch { snackbar.showSnackbar("只能重新生成最近一轮角色回复，避免改写已经继续发展的聊天历史") }
            return
        }
        turn.generatedMessageIds.forEach { messageId ->
            ChatAutoVoicePlayback.remove(messageId)
            MigratedDomainStores.chat.deleteMessage(messageId)
        }
        selectedMessage = null
        sendAndReceive(includeDraft = false)
    }

    Scaffold(
        containerColor = QqPage,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = QqHeader),
                navigationIcon = {
                    IconButton(onClick = {
                        if (multiSelectMode) {
                            multiSelectMode = false
                            selectedMessageIds = emptySet()
                        } else onBack()
                    }) { Icon(Icons.Outlined.ArrowBack, "返回", tint = QqInk) }
                },
                title = {
                    if (multiSelectMode) {
                        Text("已选择 ${selectedMessageIds.size} 条", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = QqInk)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (groupChat == null) QqAvatar(character.displayName.take(1).ifBlank { "露" }, 42, character.avatarUri)
                            else QqGroupAvatar(groupChat, 42)
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
                    }
                },
                actions = {
                    if (!multiSelectMode) {
                        Box {
                            IconButton(onClick = { modelExpanded = true }) { Icon(Icons.Outlined.SwapHoriz, "切换模型", tint = QqInk) }
                            DropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                                if (library.archives.isEmpty()) {
                                    DropdownMenuItem(text = { Text("还没有模型存档") }, enabled = false, onClick = {})
                                } else {
                                    library.archives.forEach { archive ->
                                        val selected = archive.id == chatArchiveId
                                        DropdownMenuItem(
                                            leadingIcon = { Icon(if (selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked, null, tint = QqInk) },
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
                            IconButton(onClick = { groupSettingsVisible = true }) { Icon(Icons.Outlined.MoreHoriz, "群聊设置", tint = QqInk) }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (multiSelectMode) {
                Surface(color = QqHeader, shadowElevation = 4.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceAround,
                    ) {
                        TextButton(onClick = {
                            multiSelectMode = false
                            selectedMessageIds = emptySet()
                        }) { Text("取消", color = QqInk) }
                        TextButton(
                            enabled = selectedMessageIds.isNotEmpty(),
                            onClick = {
                                forwardingMessages = visibleMessages.filter { it.id in selectedMessageIds }
                            },
                        ) { Icon(Icons.Outlined.Share, null); Spacer(Modifier.width(4.dp)); Text("转发") }
                        TextButton(
                            enabled = selectedMessageIds.isNotEmpty(),
                            onClick = {
                                visibleMessages.filter { it.id in selectedMessageIds && !it.favorite }
                                    .forEach { MigratedDomainStores.chat.toggleFavorite(it.id) }
                                multiSelectMode = false
                                selectedMessageIds = emptySet()
                            },
                        ) { Icon(Icons.Outlined.StarOutline, null); Spacer(Modifier.width(4.dp)); Text("收藏") }
                        TextButton(
                            enabled = selectedMessageIds.isNotEmpty(),
                            onClick = {
                                selectedMessageIds.toList().forEach { messageId ->
                                    ChatAutoVoicePlayback.remove(messageId)
                                    MigratedDomainStores.chat.deleteMessage(messageId)
                                }
                                multiSelectMode = false
                                selectedMessageIds = emptySet()
                            },
                        ) { Icon(Icons.Outlined.DeleteOutline, null); Spacer(Modifier.width(4.dp)); Text("删除") }
                    }
                }
            } else {
                Surface(color = QqHeader, shadowElevation = 4.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                        replyingTo?.let { quoted ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    color = QqIconSurface,
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                        Text("回复 ${messageLabel(quoted)}", color = QqInk, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                                        Text(stripCharacterReplyDirective(quoted.content), color = QqMuted, fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                                IconButton(onClick = { replyingTo = null }) { Icon(Icons.Outlined.Close, "取消引用", Modifier.size(18.dp)) }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box {
                                FilledTonalIconButton(
                                    onClick = {
                                        if (groupChat != null) mentionExpanded = true
                                        else if (voiceListening) speechRecognizer?.stopListening()
                                        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginVoiceCapture()
                                        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                    },
                                    modifier = Modifier.size(50.dp),
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = QqIconSurface, contentColor = QqInk),
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
                                placeholder = { Text("发消息", color = QqMuted, maxLines = 1) },
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
                                Icon(if (receiving) Icons.Outlined.StopCircle else Icons.Outlined.MarkChatRead, if (receiving) "停止" else "发送并让对方回复")
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
            itemsIndexed(visibleMessages, key = { _, item -> item.id }) { index, message ->
                val previous = visibleMessages.getOrNull(index - 1)
                val next = visibleMessages.getOrNull(index + 1)
                val groupStart = previous == null || previous.sender != message.sender ||
                    previous.authorCharacterId != message.authorCharacterId ||
                    Duration.between(previous.createdAt, message.createdAt).toMinutes() >= 2
                val groupEnd = next == null || next.sender != message.sender ||
                    next.authorCharacterId != message.authorCharacterId ||
                    Duration.between(message.createdAt, next.createdAt).toMinutes() >= 2
                val author = message.authorCharacterId?.let { characters[it] ?: MigratedDomainStores.characters.get(it) } ?: character

                if (multiSelectMode && message.sender != LuluChatMessage.Sender.System) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = message.id in selectedMessageIds,
                            onCheckedChange = { toggleSelected(message.id) },
                        )
                        Box(Modifier.weight(1f)) {
                            QqMessageRow(
                                message = message,
                                characterName = author.displayName,
                                characterAvatarUri = author.avatarUri,
                                characterLabel = groupChat?.members?.firstOrNull { it.characterId == author.characterId }?.groupNickname?.ifBlank { author.displayName } ?: author.displayName,
                                showCharacterName = groupChat?.showMemberNames == true,
                                repliedMessageContent = repliedPreview(message),
                                showAvatar = groupStart,
                                showTime = preferences.showMessageTimestamps && groupEnd,
                                userAvatar = userAvatar,
                                userAvatarUri = userAvatarUri,
                                onCharacterAvatarClick = { presenceCharacterId = author.characterId },
                                onLongClick = { toggleSelected(message.id) },
                                onSwipeReply = { toggleSelected(message.id) },
                                onAcceptGame = { gameId ->
                                    message.authorCharacterId?.let { com.jiacimu.lulu.games.LuluGames.store.selectCharacter(it) }
                                    onOpenGame(gameId)
                                },
                            )
                        }
                    }
                } else {
                    QqMessageRow(
                        message = message,
                        characterName = author.displayName,
                        characterAvatarUri = author.avatarUri,
                        characterLabel = groupChat?.members?.firstOrNull { it.characterId == author.characterId }?.groupNickname?.ifBlank { author.displayName } ?: author.displayName,
                        showCharacterName = groupChat?.showMemberNames == true,
                        repliedMessageContent = repliedPreview(message),
                        showAvatar = groupStart,
                        showTime = preferences.showMessageTimestamps && groupEnd,
                        userAvatar = userAvatar,
                        userAvatarUri = userAvatarUri,
                        onCharacterAvatarClick = { presenceCharacterId = author.characterId },
                        onLongClick = { selectedMessage = message },
                        onSwipeReply = { beginReply(message) },
                        onAcceptGame = { gameId ->
                            message.authorCharacterId?.let { com.jiacimu.lulu.games.LuluGames.store.selectCharacter(it) }
                            onOpenGame(gameId)
                        },
                    )
                }
            }
            if (receiving && !multiSelectMode) {
                item {
                    val typingId = typingCharacterId
                    val typingCharacter = typingId?.let { id -> characters[id] ?: MigratedDomainStores.characters.get(id) }
                    val typingLabel = typingId?.let { id ->
                        groupChat?.members?.firstOrNull { it.characterId == id }?.groupNickname
                            ?.ifBlank { typingCharacter?.displayName.orEmpty() }
                            ?.ifBlank { typingCharacter?.displayName.orEmpty() }
                    } ?: character.displayName
                    Row(verticalAlignment = Alignment.Top) {
                        if (groupChat == null) {
                            QqAvatar(character.displayName.take(1).ifBlank { "露" }, 44, character.avatarUri)
                        } else if (typingCharacter != null) {
                            QqAvatar(typingCharacter.displayName.take(1).ifBlank { "角" }, 44, typingCharacter.avatarUri)
                        } else {
                            QqGroupAvatar(groupChat, 44)
                        }
                        Spacer(Modifier.width(9.dp))
                        Surface(color = QqOther, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, QqBorder)) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = QqInk)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (groupChat == null) "对方正在输入…" else "$typingLabel 正在输入…",
                                    color = QqMuted,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selectedMessage?.let { message ->
        ModalBottomSheet(
            onDismissRequest = { selectedMessage = null },
            containerColor = QqPage,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stripCharacterReplyDirective(message.content), maxLines = 3, color = QqMuted, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    QqMessageAction(Icons.Outlined.Reply, "引用") { beginReply(message) }
                    QqMessageAction(Icons.Outlined.ContentCopy, "复制") {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("聊天消息", stripCharacterReplyDirective(message.content)))
                        selectedMessage = null
                    }
                    QqMessageAction(Icons.Outlined.Share, "转发") {
                        forwardingMessages = listOf(message)
                        selectedMessage = null
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    QqMessageAction(Icons.Outlined.StarOutline, if (message.favorite) "取消收藏" else "收藏") {
                        MigratedDomainStores.chat.toggleFavorite(message.id)
                        selectedMessage = null
                    }
                    QqMessageAction(Icons.Outlined.CheckBox, "多选") {
                        multiSelectMode = true
                        selectedMessageIds = setOf(message.id)
                        selectedMessage = null
                    }
                    QqMessageAction(Icons.Outlined.DeleteOutline, "删除", danger = true) {
                        ChatAutoVoicePlayback.remove(message.id)
                        MigratedDomainStores.chat.deleteMessage(message.id)
                        selectedMessage = null
                    }
                }
                if (message.sender == LuluChatMessage.Sender.Character) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        QqMessageAction(Icons.Outlined.Refresh, "重新回复") {
                            regenerateLatestReply(message)
                        }
                        QqMessageAction(Icons.Outlined.VolumeUp, "朗读") {
                            val replayed = ChatAutoVoicePlayback.replayCached(message.id)
                            selectedMessage = null
                            if (!replayed) {
                                scope.launch { snackbar.showSnackbar("这条消息没有保存的语音缓存；朗读不会重新请求语音生成") }
                            }
                        }
                    }
                }
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }

    forwardingMessages?.let { values ->
        ModalBottomSheet(
            onDismissRequest = { forwardingMessages = null },
            containerColor = QqPage,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("转发到", color = QqInk, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(if (values.size == 1) "转发这条消息" else "转发 ${values.size} 条聊天记录", color = QqMuted, fontSize = 12.sp)
                conversations.filter { it.id != conversationId }.forEach { target ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable {
                            MigratedDomainStores.chat.sendUserMessage(target.id, buildForwardPayload(values))
                            forwardingMessages = null
                            multiSelectMode = false
                            selectedMessageIds = emptySet()
                            scope.launch { snackbar.showSnackbar("已转发到 ${target.title}") }
                        },
                        color = QqIconSurface,
                        shape = RoundedCornerShape(15.dp),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(target.groupChat?.name ?: target.title, color = QqInk, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.Outlined.ChevronRight, null, tint = QqMuted)
                        }
                    }
                }
                if (conversations.none { it.id != conversationId }) {
                    Text("还没有其他可以转发到的聊天", color = QqMuted, modifier = Modifier.padding(vertical = 20.dp))
                }
                Spacer(Modifier.navigationBarsPadding())
            }
        }
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
        val selectedCharacter = characters[selectedCharacterId] ?: MigratedDomainStores.characters.get(selectedCharacterId)
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
                    val memberCharacter = characters[member.characterId] ?: MigratedDomainStores.characters.get(member.characterId)
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
                Surface(modifier = Modifier.size(86.dp), shape = CircleShape, color = QqMine) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.GraphicEq, null, tint = Color.White, modifier = Modifier.size(38.dp)) }
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
                        onClick = { if (voiceError.isNotBlank()) beginVoiceCapture() else speechRecognizer?.stopListening() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = QqMine, contentColor = Color.White),
                    ) { Text(if (voiceError.isBlank()) "完成" else "重新听") }
                }
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun QqMessageAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, tint = if (danger) MaterialTheme.colorScheme.error else QqInk)
            Text(label, color = if (danger) MaterialTheme.colorScheme.error else QqInk, fontSize = 12.sp)
        }
    }
}
