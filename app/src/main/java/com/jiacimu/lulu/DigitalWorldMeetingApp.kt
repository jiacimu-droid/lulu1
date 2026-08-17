package com.jiacimu.lulu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ScopedModelSelections
import com.jiacimu.lulu.data.*
import com.jiacimu.lulu.design.LuluColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DigitalWorldMeetingApp(
    onBack: () -> Unit,
    invitedCharacterId: String? = null,
    invitationText: String = "",
    invitationLocation: String = "",
    invitationId: String = "",
    onInvitationConsumed: () -> Unit = {},
) {
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val profiles by DigitalLifeProfileStore.profiles.collectAsState()
    val world by DigitalWorldStore.state.collectAsState()
    val experience by MeetingExperienceStore.state.collectAsState()
    val taskStates by MeetingReplyTaskManager.states.collectAsState()
    val library by LuluAiServices.connectionStore.library.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val meetingVoiceEnabled by MeetingVoicePlayback.enabled.collectAsState()
    val meetingVoicePace by MeetingVoicePlayback.pace.collectAsState()
    val profilePrefs = remember { context.getSharedPreferences("lulu_user_profile", android.content.Context.MODE_PRIVATE) }
    val userAvatar = remember { profilePrefs.getString("avatar_text", "我").orEmpty().ifBlank { "我" }.take(2) }
    val userAvatarUri = remember { profilePrefs.getString("avatar_uri", null) }

    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var activeSessionId by remember { mutableStateOf<String?>(null) }
    var inviteHandled by remember(invitedCharacterId, invitationId) { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
    var showModelPicker by remember { mutableStateOf(false) }
    var showWritingPicker by remember { mutableStateOf(false) }
    var showVoiceSettings by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var pendingDeleteSession by remember { mutableStateOf<MeetingSession?>(null) }
    var pendingDeleteTurn by remember { mutableStateOf<MeetingTurn?>(null) }
    var selectedSceneGroup by remember { mutableStateOf<MeetingUiDisplayGroup?>(null) }
    var locationDraft by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { MeetingVoicePlayback.initialize(context) }

    val unfinishedSessionId = if (invitedCharacterId.isNullOrBlank()) {
        world.meetings.lastOrNull { it.endedAt == null }?.id
    } else null
    val resolvedActiveSessionId = activeSessionId ?: unfinishedSessionId
    val activeSession = world.meetings.firstOrNull { it.id == resolvedActiveSessionId }
    val activeTask = activeSession?.id?.let(taskStates::get)
    val generating = activeTask?.running == true
    val visibleError = errorText.ifBlank { activeTask?.lastError.orEmpty() }
    val failedExchange = activeSession?.id
        ?.let(MeetingExperienceStore::pendingForSession)
        ?.lastOrNull { it.status == MeetingExchangeStatus.FAILED }
    val selectedArchiveId = ScopedModelSelections.selectedArchiveId(ScopedModelSelections.MEETING, library)
    val selectedArchiveLabel = selectedArchiveId
        ?.let { id -> library.archives.firstOrNull { it.id == id }?.let(LuluAiServices.connectionStore::archiveLabel) }
        .orEmpty()
        .ifBlank { "选择见面模型" }

    var seenVoiceTurnIds by remember(activeSession?.id) {
        mutableStateOf(activeSession?.turns.orEmpty().mapTo(mutableSetOf(), MeetingTurn::id))
    }
    LaunchedEffect(activeSession?.id) {
        activeSession?.let { MeetingVoicePlayback.resetSession(it.id) }
        seenVoiceTurnIds = activeSession?.turns.orEmpty().mapTo(mutableSetOf(), MeetingTurn::id)
    }
    LaunchedEffect(activeSession?.id, activeSession?.turns?.size, meetingVoiceEnabled) {
        val session = activeSession ?: return@LaunchedEffect
        val fresh = session.turns.filterNot { it.id in seenVoiceTurnIds }
        if (fresh.isNotEmpty()) {
            seenVoiceTurnIds = (seenVoiceTurnIds + fresh.map(MeetingTurn::id)).toMutableSet()
            if (meetingVoiceEnabled && session.endedAt == null) {
                MeetingVoicePlayback.enqueueTurns(context, session.id, fresh)
            }
        }
    }

    fun launchExchange(session: MeetingSession, rawDraft: String, invitedOpening: Boolean = false): Boolean {
        val storedDraft = if (invitedOpening) MEETING_INVITED_OPENING_PREFIX_V2 + rawDraft else rawDraft
        val record = MeetingExperienceStore.beginExchange(session, storedDraft)
        val launched = MeetingReplyTaskManager.launch(session.id, record.id) {
            if (invitedOpening) {
                meetingRunInvitedOpeningV2(session.id, rawDraft, record.id)
            } else {
                meetingRunTurnV2(session.id, rawDraft, record.id)
            }
        }
        if (!launched) MeetingExperienceStore.discardExchange(record.id)
        return launched
    }

    fun rewindAndRegenerate(record: MeetingExchangeRecord, editOnly: Boolean) {
        val rawDraft = record.rawDraft.removePrefix(MEETING_INVITED_OPENING_PREFIX_V2)
        val affected = MeetingExperienceStore.recordsFrom(record.sessionId, record.id)
        DigitalWorldStore.rewindMeetingExchanges(record.sessionId, affected, record.beforeScene)
        val rewound = DigitalWorldStore.state.value.meetings.firstOrNull { it.id == record.sessionId }
        if (editOnly) {
            MeetingReplyTaskManager.clearError(record.sessionId)
            input = rawDraft
        } else if (rewound != null) {
            errorText = ""
            launchExchange(rewound, rawDraft, record.rawDraft.startsWith(MEETING_INVITED_OPENING_PREFIX_V2))
        }
    }

    val finishActiveMeeting: () -> Unit = {
        activeSession?.let { session ->
            if (session.turns.isEmpty()) DigitalWorldStore.deleteMeeting(session.id)
            else DigitalWorldStore.endMeeting(session.id)
        }
        activeSessionId = null
    }

    LaunchedEffect(invitedCharacterId, invitationId) {
        val inviterId = invitedCharacterId?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        if (inviteHandled) return@LaunchedEffect
        inviteHandled = true
        onInvitationConsumed()
        runCatching {
            if (invitationId.isNotBlank()) {
                val invite = MeetingExperienceStore.invitation(invitationId) ?: error("这份见面邀请已经不存在")
                require(invite.characterId == inviterId) { "见面邀请与角色不一致" }
                require(invite.status == MeetingInvitationStatus.PENDING) {
                    when (invite.status) {
                        MeetingInvitationStatus.ACCEPTED -> "这份邀请已经接受过了"
                        MeetingInvitationStatus.REJECTED -> "这份邀请已经被婉拒"
                        MeetingInvitationStatus.EXPIRED -> "这份邀请已经过期"
                        MeetingInvitationStatus.PENDING -> ""
                    }
                }
            }
            val session = DigitalWorldStore.startMeeting(
                participantIds = listOf(inviterId),
                location = invitationLocation.trim().ifBlank { "世界入口" },
                invitedByCharacterId = inviterId,
                invitationText = invitationText,
            )
            if (invitationId.isNotBlank()) MeetingExperienceStore.acceptInvitation(invitationId)
            session
        }.onSuccess { session ->
            activeSessionId = session.id
            errorText = ""
            launchExchange(session, inviterId, true)
        }.onFailure { error ->
            errorText = error.message ?: "无法接受这次见面邀请"
        }
    }

    LaunchedEffect(activeSession?.id) {
        val session = activeSession ?: return@LaunchedEffect
        if (session.endedAt != null || MeetingReplyTaskManager.state(session.id).running) return@LaunchedEffect
        val pending = MeetingExperienceStore.pendingForSession(session.id)
            .firstOrNull { it.status == MeetingExchangeStatus.PENDING || it.status == MeetingExchangeStatus.RUNNING }
            ?: return@LaunchedEffect
        val resumedSession = if (pending.status == MeetingExchangeStatus.RUNNING) {
            DigitalWorldStore.rewindMeetingExchanges(session.id, listOf(pending), pending.beforeScene)
            DigitalWorldStore.state.value.meetings.firstOrNull { it.id == session.id } ?: return@LaunchedEffect
        } else session
        val opening = pending.rawDraft.startsWith(MEETING_INVITED_OPENING_PREFIX_V2)
        val rawDraft = pending.rawDraft.removePrefix(MEETING_INVITED_OPENING_PREFIX_V2)
        val record = MeetingExperienceStore.beginExchange(resumedSession, pending.rawDraft, pending.id)
        val resumed = MeetingReplyTaskManager.launch(resumedSession.id, record.id) {
            if (opening) meetingRunInvitedOpeningV2(resumedSession.id, rawDraft, record.id)
            else meetingRunTurnV2(resumedSession.id, rawDraft, record.id)
        }
        if (!resumed) MeetingExperienceStore.discardExchange(record.id)
    }

    Scaffold(
        containerColor = LuluColors.Paper,
        topBar = {
            TopAppBar(
                title = {
                    when {
                        showHistory -> Text("见面记录", fontWeight = FontWeight.SemiBold)
                        activeSession != null -> MeetingTopTitle(activeSession)
                        else -> Text("见面", fontWeight = FontWeight.SemiBold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            showHistory -> showHistory = false
                            activeSession?.endedAt != null -> {
                                activeSessionId = null
                                showHistory = true
                            }
                            else -> onBack()
                        }
                    }) { Icon(Icons.Outlined.ArrowBack, "返回") }
                },
                actions = {
                    if (activeSession?.endedAt == null && activeSession != null && !showHistory) {
                        TextButton(onClick = finishActiveMeeting, enabled = !generating) {
                            Text("结束", color = LuluColors.Ink, fontWeight = FontWeight.SemiBold)
                        }
                    } else if (activeSession?.endedAt != null && !showHistory) {
                        IconButton(onClick = { pendingDeleteSession = activeSession }) {
                            Icon(Icons.Outlined.DeleteOutline, "彻底删除这次见面")
                        }
                    }
                    Box {
                        IconButton(onClick = { showTopMenu = true }) { Icon(Icons.Outlined.MoreVert, "见面菜单") }
                        DropdownMenu(expanded = showTopMenu, onDismissRequest = { showTopMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("见面记录") },
                                leadingIcon = { Icon(Icons.Outlined.History, null) },
                                enabled = activeSession == null,
                                onClick = { showTopMenu = false; showHistory = true },
                            )
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("见面模型")
                                        Text(selectedArchiveLabel, color = LuluColors.Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                },
                                leadingIcon = { Icon(Icons.Outlined.Memory, null) },
                                onClick = { showTopMenu = false; showModelPicker = true },
                            )
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("见面写法")
                                        Text(
                                            "${meetingLengthLabel(experience.writing.length)} · ${meetingStyleLabel(experience.writing.style)}",
                                            color = LuluColors.Muted,
                                            fontSize = 10.sp,
                                        )
                                    }
                                },
                                leadingIcon = { Icon(Icons.Outlined.AutoStories, null) },
                                onClick = { showTopMenu = false; showWritingPicker = true },
                            )
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("语音设置")
                                        Text(
                                            if (meetingVoiceEnabled) "自动朗读角色台词 · ${meetingVoicePace.label}" else "自动朗读已关闭",
                                            color = LuluColors.Muted,
                                            fontSize = 10.sp,
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(if (meetingVoiceEnabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff, null)
                                },
                                onClick = { showTopMenu = false; showVoiceSettings = true },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
            )
        },
    ) { padding ->
        when {
            showHistory -> MeetingUiHistory(
                modifier = Modifier.fillMaxSize().padding(padding),
                characters = characters.values.sortedBy(CharacterSettings::displayName),
                meetings = world.meetings.filter { it.endedAt != null && it.turns.isNotEmpty() },
                onOpen = { showHistory = false; activeSessionId = it },
                onDelete = { pendingDeleteSession = it },
            )
            activeSession == null -> MeetingUiLobby(
                modifier = Modifier.fillMaxSize().padding(padding),
                characters = characters.values.sortedBy(CharacterSettings::displayName),
                profiles = profiles,
                selectedIds = selectedIds,
                locationDraft = locationDraft,
                onToggle = { id -> selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id },
                onLocationChanged = { locationDraft = it.take(80) },
                onStart = {
                    runCatching { DigitalWorldStore.startMeeting(selectedIds.toList(), locationDraft) }
                        .onSuccess { activeSessionId = it.id; input = ""; errorText = "" }
                        .onFailure { errorText = it.message.orEmpty() }
                },
                errorText = errorText,
            )
            else -> MeetingUiRoom(
                modifier = Modifier.fillMaxSize().padding(padding),
                session = activeSession,
                viewOnly = activeSession.endedAt != null,
                userAvatar = userAvatar,
                userAvatarUri = userAvatarUri,
                input = input,
                generating = generating,
                canSend = !generating && failedExchange == null,
                errorText = visibleError,
                onInputChanged = { input = it.take(2_000) },
                onSceneLongClick = { selectedSceneGroup = it },
                onRetry = failedExchange?.takeIf { !generating }?.let { record -> { rewindAndRegenerate(record, false) } },
                onSend = {
                    val userText = input.trim()
                    if (userText.isNotBlank() && !generating && failedExchange == null) {
                        errorText = ""
                        if (launchExchange(activeSession, userText)) input = ""
                        else errorText = "上一轮还在生成，请稍等一下"
                    }
                },
            )
        }
    }

    pendingDeleteSession?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSession = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, null) },
            title = { Text("删除这次见面？") },
            text = { Text("会同时删除见面内容、对应原始时间线和由这些记录产生的派生记忆，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    DigitalWorldStore.deleteMeeting(session.id)
                    if (activeSessionId == session.id) activeSessionId = null
                    pendingDeleteSession = null
                }) { Text("彻底删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteSession = null }) { Text("取消") } },
        )
    }

    pendingDeleteTurn?.let { turn ->
        val record = MeetingExperienceStore.exchangeForTurn(turn)
        val laterCount = record?.let { MeetingExperienceStore.recordsFrom(record.sessionId, it.id).size - 1 } ?: 0
        AlertDialog(
            onDismissRequest = { pendingDeleteTurn = null },
            icon = { Icon(Icons.Outlined.DeleteSweep, null) },
            title = { Text("删除这一轮互动？") },
            text = {
                Text(buildString {
                    append("会删除这次输入及完整场景，并同步清除原始时间线、语义记忆来源、姿态、心声和地点状态。")
                    if (laterCount > 0) append(" 为保证因果连续，后面的 $laterCount 个场景也会一起回退。")
                    append(" 此操作无法恢复。")
                })
            },
            confirmButton = {
                TextButton(onClick = {
                    activeSession?.let { DigitalWorldStore.deleteMeetingExchange(it.id, turn.id) }
                    pendingDeleteTurn = null
                }) { Text("彻底删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteTurn = null }) { Text("取消") } },
        )
    }

    selectedSceneGroup?.let { group ->
        val turn = group.turns.firstOrNull { it.speakerId != "system" }
        val record = turn?.let(MeetingExperienceStore::exchangeForTurn)
        val canRewrite = activeSession?.endedAt == null && record != null && !generating
        ModalBottomSheet(onDismissRequest = { selectedSceneGroup = null }) {
            Text("这一段", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            ListItem(
                headlineContent = { Text("复制完整场景") },
                leadingContent = { Icon(Icons.Outlined.ContentCopy, null) },
                modifier = Modifier.clickable {
                    clipboard.setText(AnnotatedString(group.copyText()))
                    selectedSceneGroup = null
                },
            )
            ListItem(
                headlineContent = { Text("编辑原输入并重来") },
                supportingContent = if (canRewrite) ({ Text("这一段之后的内容会一并回退") }) else null,
                leadingContent = { Icon(Icons.Outlined.Edit, null) },
                modifier = Modifier.clickable(enabled = canRewrite) {
                    rewindAndRegenerate(record!!, true)
                    selectedSceneGroup = null
                },
                colors = ListItemDefaults.colors(headlineColor = if (canRewrite) LuluColors.Ink else LuluColors.Muted),
            )
            ListItem(
                headlineContent = { Text("用原输入重新生成") },
                supportingContent = if (canRewrite) ({ Text("从这一刻重新续写，不叠加旧结果") }) else null,
                leadingContent = { Icon(Icons.Outlined.Refresh, null) },
                modifier = Modifier.clickable(enabled = canRewrite) {
                    rewindAndRegenerate(record!!, false)
                    selectedSceneGroup = null
                },
                colors = ListItemDefaults.colors(headlineColor = if (canRewrite) LuluColors.Ink else LuluColors.Muted),
            )
            ListItem(
                headlineContent = { Text("彻底删除这一段", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Outlined.DeleteSweep, null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable(enabled = turn != null && !generating) {
                    pendingDeleteTurn = turn
                    selectedSceneGroup = null
                },
            )
            Spacer(Modifier.navigationBarsPadding().height(10.dp))
        }
    }

    if (showModelPicker) {
        ModelArchivePickerSheet(
            title = "见面模型",
            subtitle = "只影响模拟见面和现实场景见面，不会改动聊天、电话或游戏模型。",
            selectedArchiveId = selectedArchiveId,
            onSelect = {
                ScopedModelSelections.select(ScopedModelSelections.MEETING, it)
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
        )
    }
    if (showWritingPicker) {
        MeetingWritingPreferencesDialogV2(
            preferences = experience.writing,
            onLength = { MeetingExperienceStore.updateWriting(length = it) },
            onStyle = { MeetingExperienceStore.updateWriting(style = it) },
            onDismiss = { showWritingPicker = false },
        )
    }
    if (showVoiceSettings) {
        MeetingVoiceSettingsDialogV2(
            enabled = meetingVoiceEnabled,
            pace = meetingVoicePace,
            onEnabled = { MeetingVoicePlayback.setEnabled(context, it) },
            onPace = { MeetingVoicePlayback.setPace(context, it) },
            onDismiss = { showVoiceSettings = false },
        )
    }
}

@Composable
private fun MeetingTopTitle(session: MeetingSession) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            session.participantIds.take(2).forEach { id ->
                val character = MigratedDomainStores.characters.get(id)
                LuluProfileAvatar(character.avatarUri, character.displayName.take(1).ifBlank { "角" }, 42)
            }
        }
        Text(
            session.participantIds.joinToString("、") { MigratedDomainStores.characters.get(it).displayName },
            modifier = Modifier.widthIn(max = 104.dp),
            color = LuluColors.Ink,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Surface(color = Color(0xFFF1F2F3), shape = RoundedCornerShape(11.dp)) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Place, null, tint = Color(0xFF85888E), modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(3.dp))
                Text(session.location, Modifier.widthIn(max = 72.dp), color = Color(0xFF74777D), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun meetingLengthLabel(value: MeetingProseLength): String = when (value) {
    MeetingProseLength.BRIEF -> "简略"
    MeetingProseLength.BALANCED -> "适中"
    MeetingProseLength.RICH -> "丰富"
}
private fun meetingStyleLabel(value: MeetingProseStyle): String = when (value) {
    MeetingProseStyle.NATURAL -> "自然"
    MeetingProseStyle.SUBTLE -> "细腻含蓄"
    MeetingProseStyle.LITERARY -> "氛围文学"
}

@Composable
private fun MeetingVoiceSettingsDialogV2(
    enabled: Boolean,
    pace: MeetingVoicePace,
    onEnabled: (Boolean) -> Unit,
    onPace: (MeetingVoicePace) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("见面语音") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自动朗读角色台词", fontWeight = FontWeight.Medium)
                        Text("只念角色真正说出口的话。", color = LuluColors.Muted, fontSize = 12.sp)
                    }
                    Switch(checked = enabled, onCheckedChange = onEnabled)
                }
                Text(
                    "动作、环境和你的文字不会被念出来；如果两句角色台词之间夹着这些内容，下一句会自动多等一会儿，给你阅读时间。声音沿用每个角色自己的 Voice ID。",
                    color = LuluColors.Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("阅读节奏", fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        MeetingVoicePace.values().forEach { option ->
                            FilterChip(
                                selected = pace == option,
                                onClick = { onPace(option) },
                                enabled = enabled,
                                label = { Text(option.label) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun MeetingWritingPreferencesDialogV2(
    preferences: MeetingWritingPreferences,
    onLength: (MeetingProseLength) -> Unit,
    onStyle: (MeetingProseStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("见面写法") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("情节丰富度", fontWeight = FontWeight.Medium)
                    Text("控制这一小步展开多少细节，不要求你把输入写完整。", color = LuluColors.Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        MeetingProseLength.values().forEach { option ->
                            FilterChip(selected = preferences.length == option, onClick = { onLength(option) }, label = { Text(meetingLengthLabel(option)) })
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("描写风格", fontWeight = FontWeight.Medium)
                    Text("“细腻含蓄”会用目光、停顿、呼吸与触感呈现情绪，少直接宣告心声。", color = LuluColors.Muted, fontSize = 12.sp)
                    MeetingProseStyle.values().forEach { option ->
                        FilterChip(
                            selected = preferences.style == option,
                            onClick = { onStyle(option) },
                            label = { Text(meetingStyleLabel(option)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}
