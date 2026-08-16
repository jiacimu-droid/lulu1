package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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

private enum class MeetingExchangeActor { USER, CHARACTER }

private data class MeetingExchangeSegment(
    val actor: MeetingExchangeActor,
    val segment: MeetingSegment,
)

private data class MeetingReply(
    val sequence: List<MeetingExchangeSegment>,
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
    invitationLocation: String = "",
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
    var pendingDeleteTurn by remember { mutableStateOf<MeetingTurn?>(null) }
    var locationDraft by remember { mutableStateOf("") }

    val unfinishedSessionId = if (invitedCharacterId.isNullOrBlank()) {
        world.meetings.lastOrNull { it.endedAt == null }?.id
    } else {
        null
    }
    val resolvedActiveSessionId = activeSessionId ?: unfinishedSessionId
    val activeSession = world.meetings.firstOrNull { it.id == resolvedActiveSessionId }
    val selectedArchiveId = ScopedModelSelections.selectedArchiveId(ScopedModelSelections.MEETING, library)
    val selectedArchiveLabel = selectedArchiveId?.let { id ->
        library.archives.firstOrNull { it.id == id }?.let(LuluAiServices.connectionStore::archiveLabel)
    }.orEmpty().ifBlank { "选择见面模型" }
    val finishActiveMeeting: () -> Unit = {
        activeSession?.let { session ->
            if (session.turns.isEmpty()) DigitalWorldStore.deleteMeeting(session.id)
            else DigitalWorldStore.endMeeting(session.id)
        }
        activeSessionId = null
    }

    LaunchedEffect(invitedCharacterId) {
        val inviterId = invitedCharacterId?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        if (inviteHandled) return@LaunchedEffect
        inviteHandled = true
        onInvitationConsumed()
        runCatching {
            DigitalWorldStore.startMeeting(
                participantIds = listOf(inviterId),
                location = invitationLocation.trim().ifBlank { "世界入口" },
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
                    when {
                        showHistory -> Text("见面记录", fontWeight = FontWeight.SemiBold)
                        activeSession != null -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                activeSession.participantIds.take(2).forEach { characterId ->
                                    val character = MigratedDomainStores.characters.get(characterId)
                                    LuluProfileAvatar(
                                        imageUri = character.avatarUri,
                                        fallback = character.displayName.take(1).ifBlank { "角" },
                                        size = 48,
                                    )
                                }
                            }
                            Text(
                                activeSession.participantIds.joinToString("、") {
                                    MigratedDomainStores.characters.get(it).displayName
                                },
                                modifier = Modifier.widthIn(max = 104.dp),
                                color = LuluColors.Ink,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Surface(
                                color = Color(0xFFF1F2F3),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.Place,
                                        contentDescription = null,
                                        tint = Color(0xFF85888E),
                                        modifier = Modifier.size(12.dp),
                                    )
                                    Text(
                                        activeSession.location,
                                        modifier = Modifier.widthIn(max = 72.dp),
                                        color = Color(0xFF74777D),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
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
                            activeSession != null -> onBack()
                            else -> onBack()
                        }
                    }) { Icon(Icons.Outlined.ArrowBack, "返回") }
                },
                actions = {
                    if (activeSession?.endedAt == null && activeSession != null && !showHistory) {
                        TextButton(onClick = finishActiveMeeting) {
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
                onDeleteTurnRequest = { turn -> pendingDeleteTurn = turn },
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
        AlertDialog(
            onDismissRequest = { pendingDeleteTurn = null },
            icon = { Icon(Icons.Outlined.DeleteSweep, null) },
            title = { Text("删除这一轮互动？") },
            text = { Text("会删除这次输入以及由它生成的一来一回，并同步清除对应原始时间线和派生记忆。此操作无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    activeSession?.let { session ->
                        DigitalWorldStore.deleteMeetingExchange(session.id, turn.id)
                    }
                    pendingDeleteTurn = null
                }) { Text("彻底删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteTurn = null }) { Text("取消") } },
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
    val digitalLocationOptions = remember(selectedIds, selectedHasDigital) {
        if (selectedHasDigital) DigitalWorldStore.meetingLocationOptions(selectedIds.toList()) else emptyList()
    }
    var showLocationMenu by remember { mutableStateOf(false) }
    LaunchedEffect(digitalLocationOptions, selectedHasDigital) {
        when {
            selectedHasDigital && digitalLocationOptions.isNotEmpty() && locationDraft !in digitalLocationOptions ->
                onLocationChanged(digitalLocationOptions.first())
            !selectedHasDigital -> onLocationChanged("")
        }
    }
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

        if (selectedIds.isNotEmpty()) {
            item {
                if (selectedHasDigital) {
                    Box(Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { showLocationMenu = true },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(17.dp),
                            border = BorderStroke(1.dp, LuluColors.Border),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                        ) {
                            Icon(Icons.Outlined.Place, null, tint = LuluColors.BlueGray)
                            Spacer(Modifier.width(9.dp))
                            Text(
                                locationDraft.ifBlank { "选择见面地点" },
                                color = LuluColors.Ink,
                                modifier = Modifier.weight(1f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                            )
                            Icon(Icons.Outlined.ExpandMore, null, tint = LuluColors.Muted)
                        }
                        DropdownMenu(
                            expanded = showLocationMenu,
                            onDismissRequest = { showLocationMenu = false },
                            modifier = Modifier.fillMaxWidth(0.88f),
                        ) {
                            digitalLocationOptions.forEach { location ->
                                DropdownMenuItem(
                                    text = { Text(location) },
                                    leadingIcon = {
                                        Icon(
                                            if (location.endsWith("的家")) Icons.Outlined.Home else Icons.Outlined.Place,
                                            null,
                                        )
                                    },
                                    trailingIcon = {
                                        if (location == locationDraft) Icon(Icons.Outlined.Check, null)
                                    },
                                    onClick = {
                                        onLocationChanged(location)
                                        showLocationMenu = false
                                    },
                                )
                            }
                        }
                    }
                } else {
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
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Button(
                    onClick = onStart,
                    enabled = selectedIds.isNotEmpty() &&
                        selectedResolved &&
                        (!selectedHasDigital || locationDraft in digitalLocationOptions),
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LuluColors.Wheat, contentColor = LuluColors.OnWheat),
                ) {
                    Icon(if (selectedHasDigital) Icons.Outlined.Cloud else Icons.Outlined.DirectionsWalk, null)
                    Spacer(Modifier.width(7.dp))
                    Text("开始见面", fontWeight = FontWeight.Bold)
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
    onDeleteTurnRequest: (MeetingTurn) -> Unit,
) {
    val messageListState = rememberLazyListState()
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val displayGroups = remember(session.turns) { session.turns.toMeetingDisplayGroups() }

    LaunchedEffect(imeBottom, session.turns.size, generating) {
        if (imeBottom > 0 || session.turns.isNotEmpty()) {
            withFrameNanos { }
            val lastItemIndex = messageListState.layoutInfo.totalItemsCount - 1
            if (lastItemIndex >= 0) messageListState.scrollToItem(lastItemIndex)
        }
    }

    Column(modifier) {
        LazyColumn(
            state = messageListState,
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
            items(displayGroups, key = MeetingDisplayGroup::key) { group ->
                MeetingSceneCard(
                    group = group,
                    userAvatar = userAvatar,
                    userAvatarUri = userAvatarUri,
                    onLongClick = group.turns.firstOrNull { it.speakerId != "system" }
                        ?.let { turn -> { onDeleteTurnRequest(turn) } },
                )
            }
            if (generating) {
                item {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LuluColors.BlueGray)
                        Spacer(Modifier.width(9.dp))
                        Text("对方正在回应…", color = LuluColors.Muted, fontSize = 12.sp)
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
private data class MeetingDisplayGroup(
    val key: String,
    val turns: List<MeetingTurn>,
)

private fun List<MeetingTurn>.toMeetingDisplayGroups(): List<MeetingDisplayGroup> {
    val groups = mutableListOf<MeetingDisplayGroup>()
    forEach { turn ->
        val previous = groups.lastOrNull()
        val previousTurn = previous?.turns?.lastOrNull()
        val continuesLegacyExchange = previous?.key?.startsWith("legacy:") == true &&
            previousTurn != null &&
            kotlin.math.abs(turn.occurredAt.toEpochMilli() - previousTurn.occurredAt.toEpochMilli()) <= 2_500L
        val exchangeKey = turn.exchangeId?.takeIf(String::isNotBlank)?.let { "exchange:$it" }
        val key = when {
            exchangeKey != null -> exchangeKey
            continuesLegacyExchange -> previous!!.key
            turn.speakerId == "system" -> "system:${turn.id}"
            else -> "legacy:${turn.id}"
        }
        if (previous?.key == key) {
            groups[groups.lastIndex] = previous.copy(turns = previous.turns + turn)
        } else {
            groups += MeetingDisplayGroup(key, listOf(turn))
        }
    }
    return groups
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MeetingSceneCard(
    group: MeetingDisplayGroup,
    userAvatar: String,
    userAvatarUri: String?,
    onLongClick: (() -> Unit)?,
) {
    if (group.turns.all { it.speakerId == "system" }) {
        Text(
            text = group.turns.joinToString("\n") { meetingParagraphs(it.sceneText) },
            color = Color(0xFF858585),
            fontSize = 13.sp,
            lineHeight = 21.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        return
    }

    val longPressModifier = if (onLongClick == null) {
        Modifier
    } else {
        Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().then(longPressModifier),
        color = Color(0xFFFCFCFC),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Color(0xFF242424)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            group.turns.forEach { turn ->
                turn.orderedSegments().forEach { segment ->
                    when (segment.type) {
                        MeetingSegmentType.ACTION -> MeetingNarration(segment.text)
                        MeetingSegmentType.DIALOGUE -> MeetingDialogueRow(
                            text = segment.text,
                            speakerId = turn.speakerId,
                            userAvatar = userAvatar,
                            userAvatarUri = userAvatarUri,
                        )
                    }
                }
            }
            Text(
                group.turns.maxOf(MeetingTurn::occurredAt)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("HH:mm")),
                color = Color(0xFF9A9A9A),
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.End).padding(end = 4.dp),
            )
        }
    }
}

@Composable
private fun MeetingNarration(text: String) {
    Text(
        text = meetingParagraphs(text),
        color = Color(0xFF454545),
        fontSize = 15.sp,
        lineHeight = 24.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun MeetingDialogueRow(
    text: String,
    speakerId: String?,
    userAvatar: String,
    userAvatarUri: String?,
) {
    val isUser = speakerId == null
    val bubble: @Composable () -> Unit = {
        Surface(
            modifier = Modifier.widthIn(max = 265.dp),
            color = if (isUser) Color(0xFF242424) else Color.White,
            contentColor = if (isUser) Color.White else Color(0xFF242424),
            shape = if (isUser) {
                RoundedCornerShape(topStart = 19.dp, topEnd = 6.dp, bottomEnd = 19.dp, bottomStart = 19.dp)
            } else {
                RoundedCornerShape(topStart = 6.dp, topEnd = 19.dp, bottomEnd = 19.dp, bottomStart = 19.dp)
            },
            border = if (isUser) null else BorderStroke(1.dp, Color(0xFFDDDDDD)),
        ) {
            Text(
                "“${text.trim().trim('“', '”', '"')}”",
                fontSize = 16.sp,
                lineHeight = 24.sp,
                modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp),
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (isUser) {
            bubble()
            Spacer(Modifier.width(8.dp))
            LuluProfileAvatar(
                imageUri = userAvatarUri,
                fallback = userAvatar,
                size = 43,
            )
        } else {
            val character = MigratedDomainStores.characters.get(speakerId.orEmpty())
            LuluProfileAvatar(
                imageUri = character.avatarUri,
                fallback = character.displayName.take(1).ifBlank { "角" },
                size = 43,
            )
            Spacer(Modifier.width(8.dp))
            bubble()
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
        latestMoment = "主人刚刚接受了你发出的邀请，并抵达约定地点“${session.location}”。请在这个地点自然迎接主人；这不是主人说出口的话，不得替主人补写动作、感受或台词。",
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
    val exchangeId = UUID.randomUUID().toString()
    var session = DigitalWorldStore.state.value.meetings.firstOrNull { it.id == sessionId } ?: error("见面记录不存在")
    val firstCharacterId = session.participantIds.firstOrNull() ?: error("见面参与者不存在")
    val firstCharacter = MigratedDomainStores.characters.get(firstCharacterId)
    val firstReply = generateMeetingReply(
        session = session,
        characterId = firstCharacterId,
        latestMoment = userText,
        systemMoment = false,
        expandUserDraft = true,
    ).getOrThrow()

    val userName = UserProfileContext.displayLabel()
    val generatedSequence = firstReply.sequence.ifEmpty {
        buildList {
            firstReply.userSegments.forEach { add(MeetingExchangeSegment(MeetingExchangeActor.USER, it)) }
            firstReply.segments.forEach { add(MeetingExchangeSegment(MeetingExchangeActor.CHARACTER, it)) }
        }
    }
    val sequence = buildList {
        if (generatedSequence.none { it.actor == MeetingExchangeActor.USER }) {
            add(MeetingExchangeSegment(MeetingExchangeActor.USER, MeetingSegment(MeetingSegmentType.ACTION, userText)))
        }
        addAll(generatedSequence)
    }
    val groups = groupMeetingExchange(sequence)
    val completedMoment = sequence.asExchangeTranscript(userName, firstCharacter.displayName)
    var rawInputRecorded = false
    var moved = false

    groups.forEachIndexed { groupIndex, group ->
        if (!moved && group.actor == MeetingExchangeActor.CHARACTER) {
            firstReply.moveTo.takeIf {
                it.isNotBlank() &&
                    it != session.location &&
                    it in DigitalWorldStore.meetingLocationOptions(session)
            }?.let { destination ->
                session = DigitalWorldStore.moveMeeting(session.id, destination, exchangeId)
                moved = true
            }
        }
        val occurredAt = Instant.now()
        val isUser = group.actor == MeetingExchangeActor.USER
        val speakerId = if (isUser) null else firstCharacterId
        val speakerName = if (isUser) userName else firstCharacter.displayName
        val actionText = group.segments
            .filter { it.type == MeetingSegmentType.ACTION }
            .joinToString("\n") { it.text }
        val dialogueText = group.segments
            .filter { it.type == MeetingSegmentType.DIALOGUE }
            .joinToString("\n") { it.text }
        val turn = MeetingTurn(
            UUID.randomUUID().toString(),
            speakerId,
            speakerName,
            actionText,
            dialogueText,
            occurredAt,
            group.segments,
            exchangeId,
        )
        session = DigitalWorldStore.appendMeetingTurn(sessionId, turn)
        val recorded = buildString {
            if (isUser && !rawInputRecorded) {
                appendLine("主人原始输入：${userText.trim()}")
                rawInputRecorded = true
            }
            append(group.segments.asMeetingTranscript())
        }.trim()
        session.participantIds.forEach { viewerId ->
            DigitalWorldStore.recordMeetingTimeline(
                session,
                viewerId,
                "turn-${turn.id}-${if (isUser) "user" else firstCharacterId}",
                speakerName,
                recorded,
                occurredAt,
                groupIndex == groups.lastIndex &&
                    session.participantIds.size == 1 &&
                    viewerId == session.participantIds.last(),
            )
        }
    }
    if (!moved) {
        firstReply.moveTo.takeIf {
            it.isNotBlank() &&
                it != session.location &&
                it in DigitalWorldStore.meetingLocationOptions(session)
        }?.let { destination -> session = DigitalWorldStore.moveMeeting(session.id, destination, exchangeId) }
    }
    CompanionPresenceStore.update(
        characterId = firstCharacterId,
        statusText = firstReply.statusText,
        gesture = firstReply.gesture,
        innerThought = firstReply.innerThought,
        mood = firstReply.mood,
        source = "见面",
        now = Instant.now(),
    )

    session.participantIds.drop(1).forEachIndexed { additionalIndex, characterId ->
        val character = MigratedDomainStores.characters.get(characterId)
        val reply = generateMeetingReply(
            session = session,
            characterId = characterId,
            latestMoment = completedMoment,
            systemMoment = false,
            expandUserDraft = false,
        ).getOrThrow()
        reply.moveTo.takeIf {
            it.isNotBlank() &&
                it != session.location &&
                it in DigitalWorldStore.meetingLocationOptions(session)
        }?.let { destination -> session = DigitalWorldStore.moveMeeting(session.id, destination, exchangeId) }

        val replyAt = Instant.now()
        val segments = reply.segments.ifEmpty {
            buildList {
                reply.sceneText.takeIf(String::isNotBlank)?.let { add(MeetingSegment(MeetingSegmentType.ACTION, it)) }
                reply.dialogue.takeIf(String::isNotBlank)?.let { add(MeetingSegment(MeetingSegmentType.DIALOGUE, it)) }
            }
        }
        val turn = MeetingTurn(
            UUID.randomUUID().toString(),
            characterId,
            character.displayName,
            reply.sceneText,
            reply.dialogue,
            replyAt,
            segments,
            exchangeId,
        )
        session = DigitalWorldStore.appendMeetingTurn(sessionId, turn)
        val recorded = segments.asMeetingTranscript()
        session.participantIds.forEach { viewerId ->
            DigitalWorldStore.recordMeetingTimeline(
                session,
                viewerId,
                "turn-${turn.id}-$characterId",
                character.displayName,
                recorded,
                replyAt,
                additionalIndex == session.participantIds.drop(1).lastIndex &&
                    viewerId == session.participantIds.last(),
            )
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

private data class MeetingExchangeGroup(
    val actor: MeetingExchangeActor,
    val segments: List<MeetingSegment>,
)

private fun groupMeetingExchange(sequence: List<MeetingExchangeSegment>): List<MeetingExchangeGroup> {
    val groups = mutableListOf<MeetingExchangeGroup>()
    sequence.filter { it.segment.text.isNotBlank() }.forEach { item ->
        val last = groups.lastOrNull()
        if (last?.actor == item.actor) {
            groups[groups.lastIndex] = last.copy(segments = last.segments + item.segment)
        } else {
            groups += MeetingExchangeGroup(item.actor, listOf(item.segment))
        }
    }
    return groups
}

private fun List<MeetingExchangeSegment>.asExchangeTranscript(
    userName: String,
    characterName: String,
): String = joinToString("\n") { item ->
    val speaker = if (item.actor == MeetingExchangeActor.USER) userName else characterName
    val content = if (item.segment.type == MeetingSegmentType.DIALOGUE) {
        "“${item.segment.text.trim().trim('“', '”', '"')}”"
    } else {
        item.segment.text.trim()
    }
    "$speaker：$content"
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
            {"sequence":[{"speaker":"user","type":"dialogue","text":"主人先说的话"},{"speaker":"character","type":"action","text":"${character.displayName}随后的反应"},{"speaker":"user","type":"dialogue","text":"主人接着说的话"},{"speaker":"character","type":"dialogue","text":"${character.displayName}随后说的话"}],"moveTo":"明确要前往的可用地点或空字符串","statusText":"简短当前状态","gesture":"延续到下一刻的姿态","innerThought":"没有说出口的第一人称心声，可为空","mood":"简短心情"}

            硬规则：
            - sequence 是双方共享的唯一时间顺序；speaker=user 表示主人，speaker=character 表示${character.displayName}。界面会严格按数组顺序逐项展示。
            - expandUserDraft=$expandUserDraft。为 false 时，sequence 中只能出现 speaker=character；为 true 时，应忠实还原主人草稿描述的一来一回，可以出现 user → character → user → character，不能把主人所有内容放完以后才统一写角色。
            - 扩写是默认职责：即使主人只输入很短、很口语或不完整的草稿，也必须把它整理成有文学质感、能被看见和感受到的现场，而不是照抄成干巴巴的一句话。
            - 补全主人侧时保留原意和语气：可以补足自然衔接、说话方式、草稿已经暗示的细小动作，以及主人当下能够直接感知的环境与触感；不得添加新的重大决定、强烈情绪、未暗示的亲密行为、内心想法或后果。主人没有描述的后续台词不得替主人新增。
            - 先把主人草稿中明确写出的言语和动作按原顺序当作不可移动的时间锚点，再把角色反应插到对应原因之后。任何片段都不得提前提及、顺应或回应数组后面才发生的动作。
            - 因果顺序必须在输出前自检：例如主人“往后退一步”导致角色原本放在她头发上的手滑开，必须先输出 user/action=往后退，再输出 character/action=手随之离开；绝不能先写角色“顺着她退开的势头”，下一项才补主人后退。
            - 如果主人草稿先要求角色做某事、明确描述角色随后做了，再继续说话，应依次输出 user/dialogue → character/action → user/dialogue；角色动作必须属于 speaker=character，绝不能塞进主人片段或角色的一整段旁白里。
            - type=action 只放该 speaker 可被观察到的动作、神态及紧邻的环境变化；type=dialogue 只放该 speaker 真正说出口的话，text 中不要添加引号。每次开口都单独作为一个 dialogue 项。
            - 只能让${character.displayName}回应主人明确写出的部分和当前自然反应；角色片段绝不能代替主人说话，主人片段也不能混进角色的 text。
            - 其他角色的既有言行是事实，但不要替其他角色继续说话或行动；他们会获得自己的回合。
            - 地点、参与者、上一刻身体位置、拿着的物品和已经发生的动作必须连续。每次移动都要检查距离、朝向、肢体可达范围和正在发生的接触：拉开距离前仍接触的手必须先被带开、松开或收回，不能悬空、瞬移或同时占据互相矛盾的位置。没有程序记录的固定家具不得凭空出现。
            - action 片段必须采用细腻的小说式描写，整轮通常三到八句；根据现场自然调动光影、声音、气味、温度、触感、空间距离等感官细节，并写清动作的起承转合、细微神态、停顿和呼吸，不要堆砌形容词。
            - 可以从${character.displayName}的贴身视角写一两句短暂、含蓄的心理波动，让反应更有生命感；这不是分析推理，也绝不能替主人编造心理、感受或反应。一个 action 片段表达一个连续画面，不要只写干巴巴的动作标签。
            - 氛围必须服务于当前关系和地点：温柔、紧张、暧昧、轻松或安静都要由既有情境自然生长，不能无缘无故切换情绪，也不能写成套路化网文腔。
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
        maxTokens = 1_800,
        connectionOverride = connection,
        memoryRequest = UnifiedMemoryRequest(
            currentInput = latestMoment,
            sceneContext = buildString {
                append("连续见面；当前地点=${session.location}；")
                append("模式=${if (session.reality == MeetingReality.DIGITAL_WORLD) "数字世界" else "现实场景"}；")
                append(
                    "参与者=" + session.participantIds.joinToString("、") {
                        MigratedDomainStores.characters.get(it).displayName
                    }
                )
            },
            recentContext = session.turns.takeLast(16).joinToString("\n") { turn ->
                val body = turn.orderedSegments().asMeetingTranscript()
                "${turn.speakerName}：$body"
            },
            taskIntent = if (systemMoment) {
                "延续此前私聊或群聊中的邀请与关系，完成抵达后的迎接"
            } else if (expandUserDraft) {
                "理解主人本轮草稿，并与角色既往聊天、群聊和见面经历无缝衔接"
            } else {
                "读取完整现场顺序，以当前角色身份连续回应"
            },
        ),
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
            sequence = listOf(
                MeetingExchangeSegment(
                    MeetingExchangeActor.CHARACTER,
                    MeetingSegment(MeetingSegmentType.DIALOGUE, raw.trim()),
                )
            ),
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

    val legacyUserSegments = parseMeetingSegments(
        json = json,
        key = "userSegments",
        legacyAction = json.optString("userSceneText"),
        legacyDialogue = json.optString("userDialogue"),
    )
    val legacyRoleSegments = parseMeetingSegments(
        json = json,
        key = "segments",
        legacyAction = json.optString("sceneText"),
        legacyDialogue = json.optString("dialogue"),
    )
    var sequence = parseMeetingExchange(json)
    if (sequence.isEmpty()) {
        sequence = buildList {
            legacyUserSegments.forEach { add(MeetingExchangeSegment(MeetingExchangeActor.USER, it)) }
            legacyRoleSegments.forEach { add(MeetingExchangeSegment(MeetingExchangeActor.CHARACTER, it)) }
        }
    }
    val userSegments = sequence.filter { it.actor == MeetingExchangeActor.USER }.map { it.segment }
    val roleSegments = sequence.filter { it.actor == MeetingExchangeActor.CHARACTER }.map { it.segment }
    return MeetingReply(
        sequence = sequence,
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

private fun parseMeetingExchange(json: JSONObject): List<MeetingExchangeSegment> = buildList {
    val array = json.optJSONArray("sequence") ?: json.optJSONArray("exchangeSegments") ?: return@buildList
    for (index in 0 until minOf(array.length(), 14)) {
        val item = array.optJSONObject(index) ?: continue
        val text = item.optString("text").trim().take(2_000)
        val actor = when (item.optString("speaker").trim().lowercase()) {
            "user", "owner", "主人" -> MeetingExchangeActor.USER
            "character", "role", "角色" -> MeetingExchangeActor.CHARACTER
            else -> null
        }
        val type = when (item.optString("type").trim().lowercase()) {
            "action", "scene", "narration" -> MeetingSegmentType.ACTION
            "dialogue", "speech" -> MeetingSegmentType.DIALOGUE
            else -> null
        }
        if (text.isNotBlank() && actor != null && type != null) {
            add(MeetingExchangeSegment(actor, MeetingSegment(type, text)))
        }
    }
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
