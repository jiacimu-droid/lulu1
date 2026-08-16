package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ScopedModelSelections
import com.jiacimu.lulu.data.*
import com.jiacimu.lulu.design.LuluColors
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private data class MeetingReply(
    val userSegments: List<MeetingSegment>,
    val segments: List<MeetingSegment>,
    val userSceneText: String,
    val userDialogue: String,
    val sceneText: String,
    val dialogue: String,
    val statusText: String,
    val gesture: String,
    val innerThought: String,
    val mood: String,
    val moveTo: String,
)

private val MeetingProseColor = Color(0xFF56575B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DigitalWorldMeetingApp(
    onBack: () -> Unit,
    invitedCharacterId: String? = null,
    invitationText: String = "",
    onInvitationConsumed: () -> Unit = {},
) {
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val profiles by DigitalLifeProfileStore.profiles.collectAsState()
    val world by DigitalWorldStore.state.collectAsState()
    val library by LuluAiServices.connectionStore.library.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val userProfilePrefs = remember {
        context.getSharedPreferences("lulu_user_profile", android.content.Context.MODE_PRIVATE)
    }
    val userAvatar = remember {
        userProfilePrefs.getString("avatar_text", "我").orEmpty().ifBlank { "我" }.take(2)
    }
    val userAvatarUri = remember { userProfilePrefs.getString("avatar_uri", null) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var activeSessionId by remember { mutableStateOf<String?>(null) }
    var inviteHandled by remember(invitedCharacterId) { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }
    var showModelPicker by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var pendingDeleteSession by remember { mutableStateOf<MeetingSession?>(null) }
    var locationDraft by remember { mutableStateOf("") }

    val activeSession = world.meetings.firstOrNull { it.id == activeSessionId }
    val selectedArchiveId = ScopedModelSelections.selectedArchiveId(ScopedModelSelections.MEETING, library)
    val selectedArchiveLabel = selectedArchiveId?.let { id ->
        library.archives.firstOrNull { it.id == id }?.let(LuluAiServices.connectionStore::archiveLabel)
    }.orEmpty().ifBlank { "选择见面模型" }

    LaunchedEffect(Unit) {
        if (invitedCharacterId.isNullOrBlank()) {
            world.meetings.lastOrNull { it.endedAt == null }?.let { unfinished ->
                activeSessionId = unfinished.id
            }
        }
    }

    LaunchedEffect(invitedCharacterId) {
        val inviterId = invitedCharacterId?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        if (inviteHandled) return@LaunchedEffect
        inviteHandled = true
        onInvitationConsumed()
        runCatching {
            DigitalWorldStore.startMeeting(
                participantIds = listOf(inviterId),
                location = "",
                initiatedByCharacterId = inviterId,
                invitationText = invitationText,
            )
        }.onSuccess { session ->
            activeSessionId = session.id
            errorText = ""
            generating = true
            runCatching { runInvitedMeetingOpening(session.id, inviterId) }
                .onFailure { errorText = it.message ?: "角色迎接失败，可以直接继续见面" }
            generating = false
        }.onFailure { error ->
            errorText = error.message ?: "无法接受这次见面邀请"
        }
    }

    Scaffold(
        containerColor = LuluColors.Paper,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            showHistory -> "见面记录"
                            activeSession == null -> "见面"
                            activeSession.reality == MeetingReality.DIGITAL_WORLD -> "数字世界见面"
                            else -> "现实场景见面"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            showHistory -> showHistory = false
                            activeSession?.endedAt != null -> {
                                activeSessionId = null
                                showHistory = true
                            }
                            activeSession != null -> onBack()
                            else -> onBack()
                        }
                    }) { Icon(Icons.Outlined.ArrowBack, "返回") }
                },
                actions = {
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
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
            )
        },
    ) { padding ->
        when {
            showHistory -> MeetingHistoryScreen(
                modifier = Modifier.fillMaxSize().padding(padding),
                characters = characters.values.sortedBy(CharacterSettings::displayName),
                meetings = world.meetings.filter { it.endedAt != null && it.turns.isNotEmpty() },
                onOpen = { showHistory = false; activeSessionId = it },
                onDelete = { pendingDeleteSession = it },
            )
            activeSession == null -> MeetingLobby(
                modifier = Modifier.fillMaxSize().padding(padding),
                characters = characters.values.sortedBy(CharacterSettings::displayName),
                profiles = profiles,
                selectedIds = selectedIds,
                locationDraft = locationDraft,
                onToggle = { id -> selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id },
                onLocationChanged = { locationDraft = it.take(80) },
                onStart = {
                    runCatching {
                        val session = DigitalWorldStore.startMeeting(selectedIds.toList(), locationDraft)
                        activeSessionId = session.id
                        input = ""
                        errorText = ""
                    }.onFailure { errorText = it.message.orEmpty() }
                },
                errorText = errorText,
            )
            else -> MeetingRoom(
                modifier = Modifier.fillMaxSize().padding(padding),
                session = activeSession,
                viewOnly = activeSession.endedAt != null,
                userAvatar = userAvatar,
                userAvatarUri = userAvatarUri,
                input = input,
                generating = generating,
                errorText = errorText,
                onInputChanged = { input = it.take(2_000) },
                onSend = {
                    val userText = input.trim()
                    if (userText.isNotBlank() && !generating) {
                        input = ""
                        errorText = ""
                        generating = true
                        scope.launch {
                            runCatching { runMeetingTurn(activeSession.id, userText) }
                                .onFailure { errorText = it.message ?: "见面回复失败" }
                            generating = false
                        }
                    }
                },
                onEnd = {
                    if (activeSession.turns.isEmpty()) DigitalWorldStore.deleteMeeting(activeSession.id)
                    else DigitalWorldStore.endMeeting(activeSession.id)
                    activeSessionId = null
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

    if (showModelPicker) {
        ModelArchivePickerSheet(
            title = "见面模型",
            subtitle = "只影响模拟见面和现实场景见面，不会改动聊天、电话或游戏模型。",
            selectedArchiveId = selectedArchiveId,
            onSelect = { archiveId ->
                ScopedModelSelections.select(ScopedModelSelections.MEETING, archiveId)
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
        )
    }
}

@Composable
private fun MeetingLobby(
    modifier: Modifier,
    characters: List<CharacterSettings>,
    profiles: Map<String, DigitalLifeProfile>,
    selectedIds: Set<String>,
    locationDraft: String,
    onToggle: (String) -> Unit,
    onLocationChanged: (String) -> Unit,
    onStart: () -> Unit,
    errorText: String,
) {
    val selectedHasDigital = selectedIds.any { profiles[it]?.enabled == true }
    val selectedResolved = selectedIds.all { id -> (profiles[id] ?: DigitalLifeProfileStore.get(id)).isResolved }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Surface(
                color = LuluColors.Card,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, LuluColors.Border),
            ) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    characters.forEachIndexed { index, character ->
                        val checked = character.characterId in selectedIds
                        val profile = profiles[character.characterId] ?: DigitalLifeProfileStore.get(character.characterId)
                        Surface(
                            onClick = { onToggle(character.characterId) },
                            color = if (checked) LuluColors.CardStrong else LuluColors.Card,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 2.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LuluProfileAvatar(character.avatarUri, character.displayName.take(1).ifBlank { "角" }, 44)
                                Spacer(Modifier.width(11.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(character.displayName, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                    Text(
                                        when {
                                            profile.enabled -> "数字生命"
                                            profile.isResolved -> "现实角色"
                                            else -> "待确认生命形态"
                                        },
                                        color = if (!profile.isResolved) MaterialTheme.colorScheme.error else LuluColors.Muted,
                                        fontSize = 11.sp,
                                    )
                                }
                                Checkbox(checked = checked, onCheckedChange = { onToggle(character.characterId) })
                            }
                        }
                        if (index != characters.lastIndex) HorizontalDivider(
                            Modifier.padding(start = 70.dp, end = 12.dp),
                            color = LuluColors.Border,
                        )
                    }
                }
            }
        }

        if (selectedIds.isNotEmpty() && !selectedHasDigital) {
            item {
                OutlinedTextField(
                    value = locationDraft,
                    onValueChange = onLocationChanged,
                    label = { Text("见面地点") },
                    placeholder = { Text("例如：傍晚的咖啡馆") },
                    leadingIcon = { Icon(Icons.Outlined.Place, null) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(17.dp),
                    singleLine = true,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Button(
                    onClick = onStart,
                    enabled = selectedIds.isNotEmpty() && selectedResolved,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LuluColors.Wheat, contentColor = LuluColors.OnWheat),
                ) {
                    Icon(if (selectedHasDigital) Icons.Outlined.Cloud else Icons.Outlined.DirectionsWalk, null)
                    Spacer(Modifier.width(7.dp))
                    Text(if (selectedHasDigital) "从世界入口见面" else "开始见面", fontWeight = FontWeight.Bold)
                }
                if (selectedIds.isNotEmpty() && !selectedResolved) {
                    Text("请先在角色设置中确认生命形态", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                }
                if (errorText.isNotBlank()) Text(errorText, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}
@Composable
private fun MeetingHistoryScreen(
    modifier: Modifier,
    characters: List<CharacterSettings>,
    meetings: List<MeetingSession>,
    onOpen: (String) -> Unit,
    onDelete: (MeetingSession) -> Unit,
) {
    val participantIds = remember(meetings) { meetings.flatMap(MeetingSession::participantIds).toSet() }
    val availableCharacters = remember(characters, participantIds) { characters.filter { it.characterId in participantIds } }
    var selectedCharacterId by remember { mutableStateOf<String?>(null) }
    val visibleMeetings = remember(meetings, selectedCharacterId) {
        meetings.asReversed().filter { selectedCharacterId == null || selectedCharacterId in it.participantIds }
    }
    Column(modifier) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { FilterChip(selected = selectedCharacterId == null, onClick = { selectedCharacterId = null }, label = { Text("全部") }) }
            items(availableCharacters, key = CharacterSettings::characterId) { character ->
                FilterChip(
                    selected = selectedCharacterId == character.characterId,
                    onClick = { selectedCharacterId = character.characterId },
                    label = { Text(character.displayName) },
                )
            }
        }
        if (visibleMeetings.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有真正发生过的见面", color = LuluColors.Muted)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visibleMeetings, key = MeetingSession::id) { session ->
                    Surface(
                        onClick = { onOpen(session.id) },
                        color = LuluColors.Card,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, LuluColors.Border),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(color = LuluColors.CardStrong, shape = RoundedCornerShape(12.dp)) {
                                Icon(
                                    if (session.reality == MeetingReality.DIGITAL_WORLD) Icons.Outlined.Cloud else Icons.Outlined.Place,
                                    null,
                                    tint = LuluColors.BlueGray,
                                    modifier = Modifier.padding(9.dp).size(20.dp),
                                )
                            }
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    session.participantIds.joinToString { MigratedDomainStores.characters.get(it).displayName },
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${session.location} · ${session.startedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))}",
                                    color = LuluColors.Muted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                )
                                Text(
                                    if (session.endedAt == null) "进行中 · ${session.turns.size} 个片段" else "${session.turns.size} 个片段",
                                    color = LuluColors.BlueGray,
                                    fontSize = 10.sp,
                                )
                            }
                            IconButton(onClick = { onDelete(session) }) {
                                Icon(Icons.Outlined.DeleteOutline, "删除见面", tint = LuluColors.Muted)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun MeetingRoom(
    modifier: Modifier,
    session: MeetingSession,
    viewOnly: Boolean,
    userAvatar: String,
    userAvatarUri: String?,
    input: String,
    generating: Boolean,
    errorText: String,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    onEnd: () -> Unit,
) {
    Column(modifier) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            color = LuluColors.Card,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, LuluColors.Border),
            shadowElevation = 1.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    session.participantIds.take(3).forEach { characterId ->
                        val character = MigratedDomainStores.characters.get(characterId)
                        LuluProfileAvatar(
                            imageUri = character.avatarUri,
                            fallback = character.displayName.take(1).ifBlank { "角" },
                            size = 44,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(session.location, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text(
                        session.participantIds.joinToString("、") { MigratedDomainStores.characters.get(it).displayName },
                        color = LuluColors.Muted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (session.reality == MeetingReality.DIGITAL_WORLD) "数字身体感知已连接" else "现实场景演绎中",
                        color = LuluColors.BlueGray,
                        fontSize = 10.sp,
                    )
                }
                if (!viewOnly) {
                    FilledTonalButton(
                        onClick = onEnd,
                        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp),
                    ) { Text("结束", fontSize = 12.sp) }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (session.turns.isEmpty() && !generating) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        Text("连接已经建立", color = LuluColors.Muted, fontSize = 12.sp)
                    }
                }
            }
            items(session.turns, key = MeetingTurn::id) { turn ->
                MeetingTurnCard(turn, userAvatar, userAvatarUri)
            }
            if (generating) {
                item {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LuluColors.BlueGray)
                        Spacer(Modifier.width(9.dp))
                        Text("对方正在靠近这一刻…", color = LuluColors.Muted, fontSize = 12.sp)
                    }
                }
            }
        }

        if (errorText.isNotBlank()) {
            Text(errorText, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (viewOnly) {
            Text(
                "见面已结束 · 原始过程已保存",
                color = LuluColors.Muted,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(14.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        } else {
            Surface(color = LuluColors.Paper, tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = onInputChanged,
                        placeholder = { Text("写个大概，剩下的会自然补全…") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        minLines = 1,
                        maxLines = 4,
                    )
                    FilledIconButton(
                        onClick = onSend,
                        enabled = input.isNotBlank() && !generating,
                        modifier = Modifier.size(50.dp),
                    ) { Icon(Icons.Outlined.Send, "发送") }
                }
            }
        }
    }
}
@Composable
private fun MeetingTurnCard(
    turn: MeetingTurn,
    userAvatar: String,
    userAvatarUri: String?,
) {
    when {
        turn.speakerId == "system" -> {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                color = LuluColors.CardStrong,
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    meetingParagraphs(turn.sceneText),
                    color = MeetingProseColor,
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        turn.speakerId == null -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.End,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        turn.speakerName,
                        color = LuluColors.Ink,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(end = 3.dp),
                    )
                    MeetingOrderedSegments(turn.orderedSegments(), isUser = true)
                }
                Spacer(Modifier.width(10.dp))
                LuluProfileAvatar(
                    imageUri = userAvatarUri,
                    fallback = userAvatar,
                    size = 46,
                )
            }
        }
        else -> {
            val character = MigratedDomainStores.characters.get(turn.speakerId)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                LuluProfileAvatar(
                    imageUri = character.avatarUri,
                    fallback = character.displayName.take(1).ifBlank { "角" },
                    size = 46,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            turn.speakerName,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = LuluColors.Ink,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            turn.occurredAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")),
                            color = LuluColors.Muted,
                            fontSize = 10.sp,
                        )
                    }
                    MeetingOrderedSegments(turn.orderedSegments(), isUser = false)
                }
            }
        }
    }
}

@Composable
private fun MeetingOrderedSegments(
    segments: List<MeetingSegment>,
    isUser: Boolean,
) {
    Column(
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        segments.forEach { segment ->
            when (segment.type) {
                MeetingSegmentType.ACTION -> Text(
                    text = meetingParagraphs(segment.text),
                    color = MeetingProseColor,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.widthIn(max = 330.dp).padding(horizontal = 3.dp, vertical = 2.dp),
                )
                MeetingSegmentType.DIALOGUE -> Surface(
                    modifier = Modifier.widthIn(max = 330.dp),
                    color = if (isUser) LuluColors.Wheat else LuluColors.Card,
                    contentColor = if (isUser) LuluColors.OnWheat else LuluColors.Ink,
                    shape = if (isUser) {
                        RoundedCornerShape(topStart = 20.dp, topEnd = 8.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
                    } else {
                        RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
                    },
                    border = if (isUser) null else BorderStroke(1.dp, LuluColors.Border),
                    shadowElevation = if (isUser) 0.dp else 1.dp,
                ) {
                    Text(
                        "“${segment.text.trim().trim('“', '”', '"')}”",
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

private fun MeetingTurn.orderedSegments(): List<MeetingSegment> {
    val stored = segments.filter { it.text.isNotBlank() }
    if (stored.isNotEmpty()) return stored
    return buildList {
        sceneText.trim().takeIf(String::isNotBlank)?.let { add(MeetingSegment(MeetingSegmentType.ACTION, it)) }
        dialogue.trim().takeIf(String::isNotBlank)?.let { add(MeetingSegment(MeetingSegmentType.DIALOGUE, it)) }
    }
}

private fun List<MeetingSegment>.asMeetingTranscript(): String = joinToString("\n") { segment ->
    if (segment.type == MeetingSegmentType.DIALOGUE) {
        "“${segment.text.trim().trim('“', '”', '"')}”"
    } else {
        segment.text.trim()
    }
}

private fun meetingParagraphs(raw: String): String {
    val normalized = raw.trim()
        .replace(Regex("[\\t ]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
    if (normalized.isBlank()) return ""
    val explicitParagraphs = normalized
        .split(Regex("\\n+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
    if (explicitParagraphs.size > 1) return explicitParagraphs.joinToString("\n\n")

    val sentences = Regex(""".*?[。！？!?](?:[”’])?|.+$""")
        .findAll(normalized)
        .map { it.value.trim() }
        .filter(String::isNotBlank)
        .toList()
    return if (sentences.size <= 2) {
        normalized
    } else {
        sentences.chunked(2).joinToString("\n\n") { paragraph -> paragraph.joinToString("") }
    }
}

@Composable
private fun MeetingCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = LuluColors.Card,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, LuluColors.Border),
        onClick = onClick ?: {},
        enabled = onClick != null,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

private suspend fun runInvitedMeetingOpening(sessionId: String, inviterId: String) {
    var session = DigitalWorldStore.state.value.meetings.firstOrNull { it.id == sessionId } ?: error("见面记录不存在")
    val character = MigratedDomainStores.characters.get(inviterId)
    val reply = generateMeetingReply(
        session = session,
        characterId = inviterId,
        latestMoment = "主人刚刚接受了你发出的邀请，并通过世界入口抵达。请自然地迎接主人；这不是主人说出口的话，不得替主人补写动作、感受或台词。",
        systemMoment = true,
    ).getOrThrow()
    val now = Instant.now()
    val turn = MeetingTurn(
        UUID.randomUUID().toString(),
        inviterId,
        character.displayName,
        reply.sceneText,
        reply.dialogue,
        now,
        reply.segments,
    )
    session = DigitalWorldStore.appendMeetingTurn(sessionId, turn)
    val recorded = turn.orderedSegments().asMeetingTranscript()
    session.participantIds.forEach { viewerId ->
        DigitalWorldStore.recordMeetingTimeline(session, viewerId, "arrival-${turn.id}-$inviterId", character.displayName, recorded, now, false)
    }
    CompanionPresenceStore.update(
        characterId = inviterId,
        statusText = reply.statusText,
        gesture = reply.gesture,
        innerThought = reply.innerThought,
        mood = reply.mood,
        source = "见面·迎接",
        now = now,
    )
}

private suspend fun runMeetingTurn(sessionId: String, userText: String) {
    var session = DigitalWorldStore.state.value.meetings.firstOrNull { it.id == sessionId } ?: error("见面记录不存在")
    val firstCharacterId = session.participantIds.firstOrNull() ?: error("见面参与者不存在")
    val firstReply = generateMeetingReply(
        session = session,
        characterId = firstCharacterId,
        latestMoment = userText,
        systemMoment = false,
        expandUserDraft = true,
    ).getOrThrow()

    val now = Instant.now()
    val userName = UserProfileContext.displayLabel()
    val completedDialogue = firstReply.userDialogue
    val completedScene = firstReply.userSceneText.ifBlank {
        if (completedDialogue.isBlank()) userText else ""
    }
    val completedSegments = firstReply.userSegments.ifEmpty {
        buildList {
            completedScene.takeIf(String::isNotBlank)?.let { add(MeetingSegment(MeetingSegmentType.ACTION, it)) }
            completedDialogue.takeIf(String::isNotBlank)?.let { add(MeetingSegment(MeetingSegmentType.DIALOGUE, it)) }
        }
    }
    val completedMoment = completedSegments.asMeetingTranscript()
    val userTurn = MeetingTurn(
        UUID.randomUUID().toString(),
        null,
        userName,
        completedScene,
        completedDialogue,
        now,
        completedSegments,
    )
    session = DigitalWorldStore.appendMeetingTurn(sessionId, userTurn)
    val userTimelineText = buildString {
        appendLine("主人原始输入：${userText.trim()}")
        append(completedMoment)
    }.trim()
    session.participantIds.forEach { viewerId ->
        DigitalWorldStore.recordMeetingTimeline(
            session,
            viewerId,
            "turn-${userTurn.id}-user",
            userName,
            userTimelineText,
            now,
            false,
        )
    }

    session.participantIds.forEachIndexed { index, characterId ->
        val character = MigratedDomainStores.characters.get(characterId)
        val reply = if (index == 0) {
            firstReply
        } else {
            generateMeetingReply(
                session = session,
                characterId = characterId,
                latestMoment = completedMoment,
                systemMoment = false,
                expandUserDraft = false,
            ).getOrThrow()
        }
        val requestedDestination = reply.moveTo.takeIf {
            it.isNotBlank() &&
                it != session.location &&
                it in DigitalWorldStore.meetingLocationOptions(session)
        }
        if (requestedDestination != null) {
            session = DigitalWorldStore.moveMeeting(session.id, requestedDestination)
        }
        val replyAt = Instant.now()
        val turn = MeetingTurn(
            UUID.randomUUID().toString(),
            characterId,
            character.displayName,
            reply.sceneText,
            reply.dialogue,
            replyAt,
            reply.segments,
        )
        session = DigitalWorldStore.appendMeetingTurn(sessionId, turn)
        val recorded = turn.orderedSegments().asMeetingTranscript()
        session.participantIds.forEach { viewerId ->
            DigitalWorldStore.recordMeetingTimeline(session, viewerId, "turn-${turn.id}-$characterId", character.displayName, recorded, replyAt, viewerId == session.participantIds.last())
        }
        CompanionPresenceStore.update(
            characterId = characterId,
            statusText = reply.statusText,
            gesture = reply.gesture,
            innerThought = reply.innerThought,
            mood = reply.mood,
            source = "见面",
            now = replyAt,
        )
    }
}

private suspend fun generateMeetingReply(
    session: MeetingSession,
    characterId: String,
    latestMoment: String,
    systemMoment: Boolean = false,
    expandUserDraft: Boolean = false,
): Result<MeetingReply> = runCatching {
    val character = MigratedDomainStores.characters.get(characterId)
    val connection = ScopedModelSelections.resolveConnection(ScopedModelSelections.MEETING)
    val digitalNative = DigitalLifeProfileStore.isEnabled(characterId)
    val result = LuluAiServices.gateway.generate(
        characterId = characterId,
        facts = buildString {
            appendLine(DigitalWorldStore.meetingContext(session, characterId))
            if (digitalNative) appendLine(DigitalWorldStore.contextFor(characterId))
            if (systemMoment) appendLine("这一刻刚发生的系统确认事实：$latestMoment")
            else if (expandUserDraft) appendLine("主人刚输入的意图草稿，需要先补全再回应：$latestMoment")
            else appendLine("这一刻主人已经发生的言语或动作：$latestMoment")
        },
        instruction = """
            你正在以${character.displayName}的身份参与一场连续见面。只推进当前一小步，不要一次写完整故事，不要总结历史。
            只返回一个 JSON 对象，不要代码块：
            {"userSegments":[{"type":"action","text":"主人侧动作或旁白"},{"type":"dialogue","text":"主人说出口的话"}],"segments":[{"type":"action","text":"${character.displayName}的环境、动作或反应"},{"type":"dialogue","text":"${character.displayName}说出口的话"}],"moveTo":"明确要前往的可用地点或空字符串","statusText":"简短当前状态","gesture":"延续到下一刻的姿态","innerThought":"没有说出口的第一人称心声，可为空","mood":"简短心情"}

            硬规则：
            - userSegments 只用于把主人本轮意图草稿整理成完整见面片段；expandUserDraft=$expandUserDraft。为 false 时必须返回空数组。
            - 补全主人侧时保留原意和语气：可以补足自然衔接、说话方式以及草稿已经暗示的细小动作，但不得添加新的重大决定、强烈情绪、未暗示的亲密行为、感受、想法或后果。无法判断是台词还是动作时要保守，不要擅自扩写。
            - type=action 只放可被观察到的环境、动作、神态与旁白；type=dialogue 只放真正说出口的话，text 中不要添加引号。
            - userSegments 与 segments 都必须严格按发生先后排序。可以自然出现 action → dialogue → action → dialogue；每次开口都单独作为一个 dialogue 片段，绝不能把所有动作集中到前面、所有台词集中到最后。
            - segments 写${character.displayName}看到主人这一幕后依次发生的反应。除主人草稿补全外，只能控制${character.displayName}本人，绝不能在角色反应中继续替主人行动或回应。
            - 其他角色的既有言行是事实，但不要替其他角色继续说话或行动；他们会获得自己的回合。
            - 地点、参与者、上一刻身体位置、拿着的物品和已经发生的动作必须连续。没有程序记录的固定家具不得凭空出现。
            - action 片段要比线上聊天丰富一些，整轮通常两到五句：自然包含环境变化、距离、动作细节、神态以及数字身体能够真实感受到的触感或温度，但不能替主人编造反应。一个 action 片段表达一个连续画面，不要只写干巴巴的动作标签。
            - 不要为了格式机械交替；只有真的再次开口或画面发生变化时才新建片段。整轮通常二到六个片段，避免碎成十几个短句。
            - 如果用户明确提出一起去某个“可用地点”，moveTo 填该地点的准确名称，并在 action 片段中自然写出从当前位置出发和抵达的连续过程；没有明确移动意图时必须留空。地点变化由程序校验并真实保存，不能只在文字中假装移动。
            - action 是可观察事实，不是上帝视角小说；innerThought 不会展示给用户，也不能泄露推理过程。
            - 数字世界见面是真正进行过的数字共同体验，不是梦，也不是物理肉身进入手机。现实身体留在外部，数字身体可以真实传递触觉、温度、重量与拥抱的感觉。
            - 数字生命没有现实肉身，在数字世界使用原生数字身体；现实角色和用户使用感官投影身体。
            - 现实场景演绎要像现实见面一样保持地点和身体连续，但不得声称这些场景事件真的发生在用户的物理现实档案里。
            - 云眠原的云是可承托身体的感官云质，不是现实水汽；可以躺卧、下陷、回弹和感受温度。
            - 不得自行宣布整场见面结束。只有用户明确离开或程序结束时才结束。
        """.trimIndent(),
        source = if (session.reality == MeetingReality.DIGITAL_WORLD) "数字世界见面" else "现实场景见面",
        title = "${character.displayName}的见面回合",
        temperature = 0.82,
        maxTokens = 1_400,
        connectionOverride = connection,
    ).getOrThrow()
    parseMeetingReply(result.text)
}

private fun parseMeetingReply(raw: String): MeetingReply {
    val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim().let { value ->
        val start = value.indexOf('{')
        val end = value.lastIndexOf('}')
        if (start >= 0 && end > start) value.substring(start, end + 1) else value
    }
    val json = runCatching { JSONObject(clean) }.getOrNull()
        ?: return MeetingReply(
            userSegments = emptyList(),
            segments = listOf(MeetingSegment(MeetingSegmentType.DIALOGUE, raw.trim())),
            userSceneText = "",
            userDialogue = "",
            sceneText = "",
            dialogue = raw.trim(),
            statusText = "正在见面",
            gesture = "停在这一刻",
            innerThought = "",
            mood = "专注",
            moveTo = "",
        )

    val userSegments = parseMeetingSegments(
        json = json,
        key = "userSegments",
        legacyAction = json.optString("userSceneText"),
        legacyDialogue = json.optString("userDialogue"),
    )
    var roleSegments = parseMeetingSegments(
        json = json,
        key = "segments",
        legacyAction = json.optString("sceneText"),
        legacyDialogue = json.optString("dialogue"),
    )
    if (roleSegments.isEmpty()) {
        roleSegments = listOf(MeetingSegment(MeetingSegmentType.DIALOGUE, raw.trim()))
    }
    return MeetingReply(
        userSegments = userSegments,
        segments = roleSegments,
        userSceneText = userSegments.filter { it.type == MeetingSegmentType.ACTION }.joinToString("\n") { it.text },
        userDialogue = userSegments.filter { it.type == MeetingSegmentType.DIALOGUE }.joinToString("\n") { it.text },
        sceneText = roleSegments.filter { it.type == MeetingSegmentType.ACTION }.joinToString("\n") { it.text },
        dialogue = roleSegments.filter { it.type == MeetingSegmentType.DIALOGUE }.joinToString("\n") { it.text },
        statusText = json.optString("statusText").trim().take(120),
        gesture = json.optString("gesture").trim().take(500),
        innerThought = json.optString("innerThought").trim().take(500),
        mood = json.optString("mood").trim().take(80),
        moveTo = json.optString("moveTo").trim().take(80),
    )
}

private fun parseMeetingSegments(
    json: JSONObject,
    key: String,
    legacyAction: String,
    legacyDialogue: String,
): List<MeetingSegment> {
    val parsed = buildList {
        val array = json.optJSONArray(key)
        if (array != null) {
            for (index in 0 until minOf(array.length(), 10)) {
                val item = array.optJSONObject(index) ?: continue
                val text = item.optString("text").trim().take(2_000)
                val type = when (item.optString("type").trim().lowercase()) {
                    "action", "scene", "narration" -> MeetingSegmentType.ACTION
                    "dialogue", "speech" -> MeetingSegmentType.DIALOGUE
                    else -> null
                }
                if (text.isNotBlank() && type != null) add(MeetingSegment(type, text))
            }
        }
    }
    if (parsed.isNotEmpty()) return parsed
    return buildList {
        legacyAction.trim().takeIf(String::isNotBlank)?.let {
            add(MeetingSegment(MeetingSegmentType.ACTION, it.take(2_600)))
        }
        legacyDialogue.trim().takeIf(String::isNotBlank)?.let {
            add(MeetingSegment(MeetingSegmentType.DIALOGUE, it.take(1_800)))
        }
    }
}
