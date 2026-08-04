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

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun QqStyleChatDetailScreen(
    conversationId: String,
    onBack: () -> Unit,
    onOpenBranch: (String) -> Unit,
    onCharacterSettings: () -> Unit,
    onWorldBook: () -> Unit,
) {
    val context = LocalContext.current
    val userProfilePrefs = remember { context.getSharedPreferences("lulu_user_profile", android.content.Context.MODE_PRIVATE) }
    val userAvatar = remember { userProfilePrefs.getString("avatar_text", "主").orEmpty().ifBlank { "主" }.take(2) }
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
    var voiceListening by remember { mutableStateOf(false) }
    var voicePartial by remember { mutableStateOf("") }
    var voiceError by remember { mutableStateOf("") }
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

    fun sendAndReceive() {
        if (receiving) return
        if (activeArchive == null) {
            scope.launch { snackbar.showSnackbar("请先在右上角选择模型") }
            return
        }

        val currentInput = input.trim()
        if (currentInput.isNotBlank()) {
            MigratedDomainStores.chat.sendUserMessage(conversationId, currentInput)
            input = ""
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
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回", tint = QqInk) }
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
                    IconButton(onClick = { callVisible = true }) { Icon(Icons.Outlined.Call, "电话", tint = QqInk) }
                    Box {
                        IconButton(onClick = { moreExpanded = true }) { Icon(Icons.Outlined.MoreHoriz, "更多", tint = QqInk) }
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
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilledTonalIconButton(
                        onClick = {
                            if (voiceListening) {
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
                            if (voiceListening) Icons.Outlined.GraphicEq else Icons.Outlined.KeyboardVoice,
                            "语音输入",
                            modifier = Modifier.size(27.dp),
                        )
                    }
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
                    userAvatar = userAvatar,
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
    showAvatar: Boolean,
    showTime: Boolean,
    userAvatar: String,
    onLongClick: () -> Unit,
) {
    val mine = message.sender == LuluChatMessage.Sender.User
    val bubbles = remember(message.content, mine) {
        if (mine) listOf(message.content) else splitCharacterBubbles(message.content)
    }
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
            bubbles.forEachIndexed { index, bubble ->
                Surface(
                    modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick),
                    color = if (mine) QqMine else QqOther,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, if (mine) QqMine else QqBorder),
                    shadowElevation = 0.dp,
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
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
        if (mine) {
            Spacer(Modifier.width(9.dp))
            if (showAvatar) QqAvatar(userAvatar, 44) else Spacer(Modifier.width(44.dp))
        }
    }
}

private fun splitCharacterBubbles(text: String): List<String> {
    val paragraphs = text.trim()
        .split(Regex("\\n\\s*\\n|\\n"))
        .map(String::trim)
        .filter(String::isNotBlank)
    val pieces = paragraphs.flatMap { paragraph ->
        if (paragraph.length <= 92) {
            listOf(paragraph)
        } else {
            paragraph.split(Regex("(?<=[。！？!?；;…])\\s*"))
                .map(String::trim)
                .filter(String::isNotBlank)
                .flatMap { sentence -> if (sentence.length <= 110) listOf(sentence) else sentence.chunked(100) }
        }
    }
    if (pieces.isEmpty()) return listOf(text.trim())
    val bubbles = mutableListOf<String>()
    var current = ""
    pieces.forEach { piece ->
        if (current.isBlank()) {
            current = piece
        } else if (current.length + piece.length <= 92) {
            current += piece
        } else {
            bubbles += current
            current = piece
        }
    }
    if (current.isNotBlank()) bubbles += current
    return bubbles
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
        border = BorderStroke(1.dp, QqBorder),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontWeight = FontWeight.Bold, color = QqInk, fontSize = (size / 3).sp)
        }
    }
}
