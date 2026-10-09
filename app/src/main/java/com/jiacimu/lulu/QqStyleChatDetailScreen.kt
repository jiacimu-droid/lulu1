package com.jiacimu.lulu

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.ai.archiveIdFor
import com.jiacimu.lulu.data.*
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
    routeVisible: Boolean = true,
    onBack: () -> Unit,
    onCharacterSettings: () -> Unit,
    onWorldBook: () -> Unit,
    onOpenGame: (String?) -> Unit,
) {
    val context = LocalContext.current
    DisposableEffect(conversationId, routeVisible) {
        if (routeVisible) ChatAutoVoicePlayback.setVisibleConversation(conversationId)
        else ChatAutoVoicePlayback.clearVisibleConversation(conversationId)
        onDispose { ChatAutoVoicePlayback.clearVisibleConversation(conversationId) }
    }
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
    val lifeStates by CharacterLifeStore.states.collectAsState()
    val onlineStates by CompanionOnlineStore.states.collectAsState()
    val presenceHistories by CompanionPresenceStore.histories.collectAsState()
    val library by LuluAiServices.connectionStore.library.collectAsState()
    val replyTaskStates by ChatReplyTaskManager.states.collectAsState()
    val replyTaskState = replyTaskStates[conversationId] ?: ChatReplyTaskManager.TaskState()
    val actualActivities by ChatGenerationActivity.activities.collectAsState()
    val activeTypists = actualActivities.values.filter { conversationId in it.conversationIds }.map { it.characterId }.distinct()
    val receiving = replyTaskState.running || activeTypists.isNotEmpty()
    // A user can send several bubbles in succession. No typing indicator
    // until THREE SECONDS after the LAST real user bubble in this conversation.
    // Simply pressing wake is presence, not evidence that the role is typing.
    val lastPendingUserBubble = messages.lastOrNull { it.sender == LuluChatMessage.Sender.User }
    val lastBubbleDeadline = lastPendingUserBubble?.createdAt?.toEpochMilli()
        ?.plus(OnlineChatBatchStore.QUIET_MILLIS)
    var quietWindowElapsed by remember(conversationId, lastPendingUserBubble?.id) {
        mutableStateOf(lastBubbleDeadline == null || System.currentTimeMillis() >= lastBubbleDeadline)
    }
    LaunchedEffect(conversationId, lastPendingUserBubble?.id) {
        lastBubbleDeadline?.let { deadline ->
            val remaining = deadline - System.currentTimeMillis()
            if (remaining > 0) kotlinx.coroutines.delay(remaining)
        }
        quietWindowElapsed = true
    }
    val showTypingIndicator = quietWindowElapsed &&
        (replyTaskState.typingCharacterId != null || activeTypists.isNotEmpty())
    val conversation = conversations.firstOrNull { it.id == conversationId }
    val groupChat = conversation?.groupChat
    val characterId = conversation?.characterId ?: "lulu"
    val recordedResponsibilities by CommitmentTaskStore.tasks.collectAsState()
    val visibleResponsibility = remember(recordedResponsibilities, characterId) {
        recordedResponsibilities.asSequence()
            .filter { it.characterId == characterId && it.status.isActive() }
            .sortedBy { it.dueAt ?: java.time.Instant.MAX }.firstOrNull()
    }
    val character = MigratedDomainStores.characters.get(characterId)
    val chatNickname = remember(characterId, lifeStates) {
        CharacterLifeStore.state(characterId).optJSONObject("socialNames")
            ?.optString("selfNickname").orEmpty().ifBlank { character.displayName }
    }
    val chatArchiveId = library.archiveIdFor(ModelUsage.Chat)
    val activeArchive = library.archives.firstOrNull { it.id == chatArchiveId }
    val activeLabel = activeArchive?.let(LuluAiServices.connectionStore::archiveLabel) ?: "未连接模型"
    val onlineMemberCount = groupChat?.members.orEmpty().count { member -> onlineStates[member.characterId]?.isOnline() == true }
    val privateOnline = onlineStates[characterId]?.isOnline() == true
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
    var callVisible by remember { mutableStateOf(false) }
    var presenceCharacterId by remember { mutableStateOf<String?>(null) }
    var groupSettingsVisible by remember { mutableStateOf(false) }
    var mentionExpanded by remember { mutableStateOf(false) }

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

    fun repliedPreview(message: LuluChatMessage): String? {
        val replyId = message.replyToMessageId ?: characterReplyQuoteId(message.content)
        return replyId?.let { id -> messages.firstOrNull { it.id == id } }
            ?.let { original -> "${messageLabel(original)}：${qqForwardContextText(original.content).take(180)}" }
    }

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

    fun appendDraft(payload: QqComposerPayload): Boolean {
        val image = payload.imageUri?.takeIf(String::isNotBlank)
        val text = payload.text.trim()
        if (image == null && text.isBlank()) return false
        val content = if (image != null) {
            encodeQqChatImage(image, text, payload.imageDescription)
        } else text
        MigratedDomainStores.chat.sendUserMessage(conversationId, content, replyingTo?.id)
        input = ""
        replyingTo = null
        return true
    }

    fun stopReceiving() {
        ChatReplyTaskManager.stop(conversationId)
        (groupChat?.members?.map { it.characterId } ?: listOf(characterId)).forEach {
            ProactivePerceptionScheduler.cancelOnline(context, it)
        }
        ChatGenerationActivity.clearConversation(conversationId)
    }

    fun wakeOnline(payload: QqComposerPayload? = null): Boolean {
        val currentConversation = conversation ?: return false
        if (activeArchive == null) {
            scope.launch { snackbar.showSnackbar("请先选择聊天模型") }
            return false
        }
        payload?.let { appendDraft(it) }
        if (groupChat == null) {
            CompanionOnlineStore.wakeCharacter(
                characterId = characterId,
                reason = CompanionOnlineReason.PrivateWake,
                trigger = "用户在私聊呼唤上线",
            )
        } else {
            CompanionOnlineStore.wakeGroup(currentConversation)
        }
        return true
    }

    fun sendAndReceive(includeDraft: Boolean = true) {
        if (ChatReplyTaskManager.state(conversationId).running || ChatGenerationActivity.isRunning(conversationId)) return
        if (activeArchive == null) {
            scope.launch { snackbar.showSnackbar("请先选择聊天模型") }
            return
        }
        if (includeDraft && input.isNotBlank()) {
            appendDraft(QqComposerPayload(input))
        }
        val latestMessages = MigratedDomainStores.chat.messages(conversationId).value
        val lastCharacterIndex = latestMessages.indexOfLast { it.sender == LuluChatMessage.Sender.Character }
        val latestPending = latestMessages.drop(lastCharacterIndex + 1).filter { message ->
            message.sender == LuluChatMessage.Sender.User ||
                (message.sender == LuluChatMessage.Sender.System && message.content.startsWith("[戳一戳] 你戳了戳"))
        }
        if (latestPending.isEmpty()) return
        val pendingIds = latestPending.mapTo(mutableSetOf(), LuluChatMessage::id)
        val pendingText = latestPending.joinToString("\n") { message ->
            if (message.sender == LuluChatMessage.Sender.System) message.content.removePrefix("[戳一戳]").trim()
            else qqForwardContextText(message.content)
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
                val actionable = latestPending.filter { it.sender == LuluChatMessage.Sender.User }
                val privateInput = buildString {
                    appendLine("[这是连续发生的一对一即时通讯私聊。你只代表自己，从上一刻的人物状态、关系和话题位置继续。]")
                    appendLine("[按真人聊天习惯决定什么时候按一次发送；一个表达动作一个气泡，需要分开发送时用 $SemanticBubbleSeparator。]")
                    appendLine("[撤回 ⟪RECALL:n⟫、戳用户 ⟪POKE_USER⟫ 都只在真的自然时偶尔使用。]")
                    if (actionable.isNotEmpty()) {
                        appendLine("[本轮可引用/收藏的用户消息：]")
                        actionable.forEach { appendLine("[消息ID=${it.id} 内容=${qqForwardContextText(it.content).take(500)}]") }
                        appendLine("[收藏是低频强意图，只有真的想长期留住时才 ⟪FAVORITE:消息ID⟫。]")
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
                            actionableUserMessageIds = actionable.mapTo(mutableSetOf(), LuluChatMessage::id),
                        )
                    } else reportError("对方刚才没有说清，再点一次试试")
                }.onFailure { reportError(it.message ?: "回复失败") }
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
        if (ChatReplyTaskManager.state(conversationId).running || ChatGenerationActivity.isRunning(conversationId)) {
            selectedMessage = null
            scope.launch { snackbar.showSnackbar("这一轮还在回复中，先等它说完") }
            return
        }
        if (activeArchive == null) {
            selectedMessage = null
            scope.launch { snackbar.showSnackbar("请先选择聊天模型") }
            return
        }
        val snapshot = MigratedDomainStores.chat.messages(conversationId).value
        val turn = regeneratableLatestTurn(snapshot, message.id)
        if (turn == null) {
            selectedMessage = null
            scope.launch { snackbar.showSnackbar("只能重新生成最近一轮角色回复，避免改写已经继续发展的聊天历史") }
            return
        }
        turn.generatedMessageIds.forEach { id ->
            ChatAutoVoicePlayback.remove(id)
            MigratedDomainStores.chat.deleteMessage(id)
        }
        selectedMessage = null
        if (groupChat == null) sendAndReceive(includeDraft = false) else wakeOnline()
    }

    if (groupSettingsVisible && groupChat != null) {
        QqGroupChatSettingsScreen(
            conversationId = conversationId,
            group = groupChat,
            characters = characters.values.sortedBy { it.displayName },
            messages = messages,
            onBack = { groupSettingsVisible = false },
            onSave = { MigratedDomainStores.chat.updateGroupConversation(conversationId, it) },
            onDelete = {
                MigratedDomainStores.chat.deleteConversation(conversationId)
                groupSettingsVisible = false
                onBack()
            },
        )
        return
    }

    LaunchedEffect(conversationId) { MigratedDomainStores.chat.markConversationRead(conversationId) }
    LaunchedEffect(replyTaskState.errorId) {
        replyTaskState.lastError?.let { error ->
            val id = replyTaskState.errorId
            val notice = chatErrorNotice(error)
            android.widget.Toast.makeText(context, notice, android.widget.Toast.LENGTH_LONG).show()
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(notice, duration = SnackbarDuration.Short)
            ChatReplyTaskManager.clearError(conversationId, id)
        }
    }
    LaunchedEffect(visibleMessages.size, preferences.autoScrollChat, imeBottom) {
        if (visibleMessages.isNotEmpty() && (preferences.autoScrollChat || imeBottom > 0)) {
            val announcementOffset = if (groupChat?.announcement.isNullOrBlank()) 0 else 1
            listState.scrollToItem(visibleMessages.lastIndex + announcementOffset)
        }
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
                            Column(Modifier.weight(1f)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(groupChat?.let { "${it.name}（${it.members.size + 1}）" } ?: chatNickname,
                                        modifier = Modifier.weight(1f, fill = false), fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
                                        color = QqInk, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    if (showTypingIndicator) Text("正在输入中", modifier = Modifier.padding(start = 5.dp),
                                        color = Color.Black, fontSize = 14.sp, maxLines = 1)
                                }
                                Text(
                                    if (groupChat == null) {
                                        val onlineUntil = onlineStates[characterId]?.onlineUntil
                                        val untilText = if (privateOnline && onlineUntil != null) {
                                            java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                                                .withZone(java.time.ZoneId.systemDefault()).format(onlineUntil)
                                        } else ""
                                        "${if (privateOnline) "在线至 $untilText" else "离线"} · $activeLabel"
                                    }
                                    else "$onlineMemberCount 人在线",
                                    fontSize = 10.sp,
                                    color = if ((groupChat == null && privateOnline) || (groupChat != null && onlineMemberCount > 0)) Color(0xFF2A9D63) else QqMuted,
                                    maxLines = 1,
                                )
                                if (groupChat == null && visibleResponsibility != null) {
                                    val task = visibleResponsibility
                                    val whenText = task.dueAt?.let {
                                        java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm")
                                            .withZone(java.time.ZoneId.systemDefault()).format(it)
                                    }.orEmpty()
                                    val confirmed = task.status == CommitmentTaskStatus.Scheduled &&
                                        task.linkedAlarmId != null
                                    Text(
                                        if (confirmed) "⏰ $whenText 已登记真实提醒"
                                        else "⚠ 约定${if (whenText.isNotBlank()) " $whenText" else ""} 尚未成功安排",
                                        fontSize = 10.sp,
                                        color = if (confirmed) Color(0xFF566977) else Color(0xFFC05E4A),
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    if (!multiSelectMode) {
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
                        TextButton(onClick = { multiSelectMode = false; selectedMessageIds = emptySet() }) { Text("取消", color = QqInk) }
                        TextButton(
                            enabled = selectedMessageIds.isNotEmpty(),
                            onClick = { forwardingMessages = visibleMessages.filter { it.id in selectedMessageIds } },
                        ) { Icon(Icons.Outlined.Share, null); Spacer(Modifier.width(4.dp)); Text("转发") }
                        TextButton(
                            enabled = selectedMessageIds.isNotEmpty(),
                            onClick = {
                                val selected = visibleMessages.filter { it.id in selectedMessageIds && !it.favorite }
                                val failed = selected.count { !MigratedDomainStores.chat.toggleFavorite(it.id) }
                                scope.launch { snackbar.showSnackbar(if (failed == 0) "已加入我的收藏" else "有 $failed 条消息未能保存收藏，请重试") }
                                multiSelectMode = false
                                selectedMessageIds = emptySet()
                            },
                        ) { Icon(Icons.Outlined.StarOutline, null); Spacer(Modifier.width(4.dp)); Text("收藏") }
                        TextButton(
                            enabled = selectedMessageIds.isNotEmpty(),
                            onClick = {
                                selectedMessageIds.forEach { id -> ChatAutoVoicePlayback.remove(id); MigratedDomainStores.chat.deleteMessage(id) }
                                multiSelectMode = false
                                selectedMessageIds = emptySet()
                            },
                        ) { Icon(Icons.Outlined.DeleteOutline, null); Spacer(Modifier.width(4.dp)); Text("删除") }
                    }
                }
            } else {
                Surface(color = QqHeader, shadowElevation = 4.dp) {
                    Column(Modifier.fillMaxWidth()) {
                        replyingTo?.let { quoted ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(Modifier.weight(1f), color = QqIconSurface, shape = RoundedCornerShape(10.dp)) {
                                    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                        Text("回复 ${messageLabel(quoted)}", color = QqInk, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                                        Text(qqForwardContextText(quoted.content), color = QqMuted, fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                                IconButton(onClick = { replyingTo = null }) { Icon(Icons.Outlined.Close, "取消引用", Modifier.size(18.dp)) }
                            }
                        }
                        QqChatComposer(
                            input = input,
                            onInputChange = { input = it },
                            groupMode = groupChat != null,
                            receiving = receiving,
                            onMention = { mentionExpanded = true },
                            onCall = {
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                                if (groupChat == null) {
                                    LuluVoiceCallSession.prepare(context, conversationId, characterId, character.displayName)
                                    LuluCallWindowController.show()
                                } else callVisible = true
                            },
                            onSendOnly = { payload -> appendDraft(payload) },
                            onWakeOrReply = { payload -> wakeOnline(payload) },
                            onStop = ::stopReceiving,
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
                    Surface(Modifier.fillMaxWidth(), color = QqIconSurface, shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, QqBorder)) {
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
                val groupStart = previous == null || previous.sender != message.sender || previous.authorCharacterId != message.authorCharacterId ||
                    Duration.between(previous.createdAt, message.createdAt).toMinutes() >= 2
                val groupEnd = next == null || next.sender != message.sender || next.authorCharacterId != message.authorCharacterId ||
                    Duration.between(message.createdAt, next.createdAt).toMinutes() >= 2
                val author = message.authorCharacterId?.let { characters[it] ?: MigratedDomainStores.characters.get(it) } ?: character
                val imageMessage = decodeQqChatImage(message.content)

                if (imageMessage != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (multiSelectMode) Checkbox(message.id in selectedMessageIds, { toggleSelected(message.id) })
                        Box(Modifier.weight(1f)) {
                            QqChatImageRow(
                                image = imageMessage,
                                mine = message.sender == LuluChatMessage.Sender.User,
                                characterName = author.displayName,
                                characterAvatarUri = author.avatarUri,
                                userAvatar = userAvatar,
                                userAvatarUri = userAvatarUri,
                                showAvatar = groupStart,
                                repliedMessageContent = repliedPreview(message),
                                onCharacterAvatarClick = { presenceCharacterId = author.characterId },
                                onLongClick = { if (multiSelectMode) toggleSelected(message.id) else selectedMessage = message },
                            )
                        }
                    }
                } else {
                    val row: @Composable () -> Unit = {
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
                            onLongClick = { if (multiSelectMode) toggleSelected(message.id) else selectedMessage = message },
                            onSwipeReply = { if (multiSelectMode) toggleSelected(message.id) else beginReply(message) },
                            onAcceptGame = { gameId ->
                                message.authorCharacterId?.let { com.jiacimu.lulu.games.LuluGames.store.selectCharacter(it) }
                                onOpenGame(gameId)
                            },
                        )
                    }
                    if (multiSelectMode && message.sender != LuluChatMessage.Sender.System) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(message.id in selectedMessageIds, { toggleSelected(message.id) })
                            Box(Modifier.weight(1f)) { row() }
                        }
                    } else row()
                }
            }

        }
    }

    selectedMessage?.let { message ->
        ModalBottomSheet(onDismissRequest = { selectedMessage = null }, containerColor = QqPage) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(qqForwardContextText(message.content), maxLines = 3, color = QqMuted, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    QqMessageAction(Icons.Outlined.Reply, "引用") { beginReply(message) }
                    QqMessageAction(Icons.Outlined.ContentCopy, "复制") {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("聊天消息", qqForwardContextText(message.content)))
                        selectedMessage = null
                    }
                    QqMessageAction(Icons.Outlined.Share, "转发") { forwardingMessages = listOf(message); selectedMessage = null }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    QqMessageAction(Icons.Outlined.StarOutline, if (message.favorite) "取消收藏" else "收藏") {
                        val saved = MigratedDomainStores.chat.toggleFavorite(message.id)
                        selectedMessage = null
                        scope.launch { snackbar.showSnackbar(if (!saved) "收藏保存失败，请重试" else if (message.favorite) "已取消收藏" else "已加入我的收藏") }
                    }
                    QqMessageAction(Icons.Outlined.CheckBox, "多选") {
                        multiSelectMode = true; selectedMessageIds = setOf(message.id); selectedMessage = null
                    }
                    QqMessageAction(Icons.Outlined.DeleteOutline, "删除", danger = true) {
                        ChatAutoVoicePlayback.remove(message.id); MigratedDomainStores.chat.deleteMessage(message.id); selectedMessage = null
                    }
                }
                if (message.sender == LuluChatMessage.Sender.Character && decodeQqChatImage(message.content) == null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        QqMessageAction(Icons.Outlined.Refresh, "重新回复") { regenerateLatestReply(message) }
                        QqMessageAction(
                            Icons.Outlined.VolumeUp,
                            if (message.id.startsWith("voice-") && ChatAutoVoicePlayback.hasCached(message.id))
                                "重听通话原声" else "朗读"
                        ) {
                            val replayed = ChatAutoVoicePlayback.replayCached(message.id)
                            selectedMessage = null
                            if (!replayed) scope.launch {
                                snackbar.showSnackbar(
                                    if (message.id.startsWith("voice-"))
                                        "这段旧通话没有保存原声音频，无法还原当时的语气；之后的新直连通话会自动保留原声"
                                    else "这条消息暂时无法朗读，请检查角色语音设置"
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }

    forwardingMessages?.let { values ->
        ModalBottomSheet(onDismissRequest = { forwardingMessages = null }, containerColor = QqPage) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            Spacer(Modifier.weight(1f)); Icon(Icons.Outlined.ChevronRight, null, tint = QqMuted)
                        }
                    }
                }
                if (conversations.none { it.id != conversationId }) Text("还没有其他可以转发到的聊天", color = QqMuted, modifier = Modifier.padding(vertical = 20.dp))
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }

    if (callVisible && groupChat != null) {
        LuluGroupVoiceCallScreen(conversationId, groupChat) {
            callVisible = false
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
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
        ModalBottomSheet(onDismissRequest = { mentionExpanded = false }, containerColor = QqPage) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("@群成员", color = QqInk, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("选择要提醒的人", color = QqMuted, fontSize = 12.sp)
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { input += "@全体成员 "; mentionExpanded = false },
                    color = QqIconSurface,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(42.dp), shape = RoundedCornerShape(12.dp), color = QqMine) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Groups, null, tint = Color.White) }
                        }
                        Spacer(Modifier.width(12.dp)); Text("@全体成员", color = QqInk, fontWeight = FontWeight.SemiBold)
                    }
                }
                groupChat.members.forEach { member ->
                    val memberCharacter = characters[member.characterId] ?: MigratedDomainStores.characters.get(member.characterId)
                    val displayName = member.groupNickname.ifBlank { memberCharacter.displayName }
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { input += "@$displayName "; mentionExpanded = false },
                        color = QqPage,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, QqBorder),
                    ) {
                        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                            QqAvatar(memberCharacter.displayName.take(1).ifBlank { "角" }, 42, memberCharacter.avatarUri)
                            Spacer(Modifier.width(12.dp)); Text("@$displayName", color = QqInk, fontWeight = FontWeight.Medium)
                        }
                    }
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
