package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.*
import com.jiacimu.lulu.design.LuluColors
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class MeetingUiDisplayGroup(val key: String, val turns: List<MeetingTurn>)

@Composable
internal fun MeetingUiLobby(
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
    DigitalWorldMapLobby(
        modifier = modifier,
        characters = characters,
        profiles = profiles,
        selectedIds = selectedIds,
        locationDraft = locationDraft,
        onToggle = onToggle,
        onLocationChanged = onLocationChanged,
        onStart = onStart,
        errorText = errorText,
    )
}

@Composable
internal fun MeetingUiHistory(
    modifier: Modifier,
    characters: List<CharacterSettings>,
    meetings: List<MeetingSession>,
    onOpen: (String) -> Unit,
    onDelete: (MeetingSession) -> Unit,
) {
    val participantIds = remember(meetings) { meetings.flatMap(MeetingSession::participantIds).toSet() }
    val available = remember(characters, participantIds) { characters.filter { it.characterId in participantIds } }
    var selectedCharacterId by remember { mutableStateOf<String?>(null) }
    val visible = remember(meetings, selectedCharacterId) {
        meetings.asReversed().filter { selectedCharacterId == null || selectedCharacterId in it.participantIds }
    }
    Column(modifier) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { FilterChip(selected = selectedCharacterId == null, onClick = { selectedCharacterId = null }, label = { Text("全部") }) }
            items(available, key = CharacterSettings::characterId) { character ->
                FilterChip(
                    selected = selectedCharacterId == character.characterId,
                    onClick = { selectedCharacterId = character.characterId },
                    label = { Text(character.displayName) },
                )
            }
        }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有真正发生过的见面", color = LuluColors.Muted)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visible, key = MeetingSession::id) { session ->
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
internal fun MeetingUiRoom(
    modifier: Modifier,
    session: MeetingSession,
    viewOnly: Boolean,
    userAvatar: String,
    userAvatarUri: String?,
    input: String,
    generating: Boolean,
    canSend: Boolean,
    errorText: String,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    onSceneLongClick: (MeetingUiDisplayGroup) -> Unit,
    onRetry: (() -> Unit)?,
) {
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val groups = remember(session.turns) { session.turns.meetingUiGroups() }
    var autoFollow by remember(session.id) { mutableStateOf(true) }
    var forcingBottom by remember(session.id) { mutableStateOf(false) }
    val hasUnseen by remember { derivedStateOf { !autoFollow && listState.canScrollForward } }

    suspend fun scrollToBottom() {
        forcingBottom = true
        try {
            withFrameNanos { }
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) {
                listState.scrollToItem(last)
                listState.scrollBy(Float.MAX_VALUE)
                autoFollow = true
            }
        } finally {
            forcingBottom = false
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, canForward) ->
            if (scrolling && !forcingBottom) autoFollow = !canForward
        }
    }
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0) {
            autoFollow = true
            scrollToBottom()
        }
    }
    LaunchedEffect(session.turns.size, generating) {
        if (autoFollow) scrollToBottom()
    }

    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
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
                items(groups, key = MeetingUiDisplayGroup::key) { group ->
                    MeetingUiSceneCard(
                        group = group,
                        userAvatar = userAvatar,
                        userAvatarUri = userAvatarUri,
                        onLongClick = group.turns.firstOrNull { it.speakerId != "system" }?.let { { onSceneLongClick(group) } },
                    )
                }
                if (generating) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LuluColors.BlueGray)
                            Spacer(Modifier.width(9.dp))
                            Text("现场正在继续…", color = LuluColors.Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
            if (hasUnseen) {
                SmallFloatingActionButton(
                    onClick = {
                        autoFollow = true
                        scrollScope.launch { scrollToBottom() }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp),
                    containerColor = Color(0xFF242424),
                    contentColor = Color.White,
                ) {
                    Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("新内容", fontSize = 11.sp)
                    }
                }
            }
        }
        if (errorText.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(errorText, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.weight(1f))
                if (onRetry != null) TextButton(onClick = onRetry) { Text("重试") }
            }
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
                        enabled = input.isNotBlank() && canSend,
                        modifier = Modifier.size(50.dp),
                    ) {
                        Icon(Icons.Outlined.Send, "发送")
                    }
                }
            }
        }
    }
}

internal fun MeetingUiDisplayGroup.copyText(): String = turns.joinToString("\n") { turn ->
    val body = turn.meetingOrderedSegments().meetingTranscript()
    if (turn.speakerId == "system") body else "${turn.speakerName}：$body"
}

private fun List<MeetingTurn>.meetingUiGroups(): List<MeetingUiDisplayGroup> {
    val groups = mutableListOf<MeetingUiDisplayGroup>()
    forEach { turn ->
        val previous = groups.lastOrNull()
        val previousTurn = previous?.turns?.lastOrNull()
        val continuesLegacy = previous?.key?.startsWith("legacy:") == true && previousTurn != null &&
            kotlin.math.abs(turn.occurredAt.toEpochMilli() - previousTurn.occurredAt.toEpochMilli()) <= 2_500L
        val exchangeKey = turn.exchangeId?.takeIf(String::isNotBlank)?.let { "exchange:$it" }
        val key = when {
            exchangeKey != null -> exchangeKey
            continuesLegacy -> previous!!.key
            turn.speakerId == "system" -> "system:${turn.id}"
            else -> "legacy:${turn.id}"
        }
        if (previous?.key == key) {
            groups[groups.lastIndex] = previous.copy(turns = previous.turns + turn)
        } else {
            groups += MeetingUiDisplayGroup(key, listOf(turn))
        }
    }
    return groups
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MeetingUiSceneCard(
    group: MeetingUiDisplayGroup,
    userAvatar: String,
    userAvatarUri: String?,
    onLongClick: (() -> Unit)?,
) {
    if (group.turns.all { it.speakerId == "system" }) {
        Text(
            group.turns.joinToString("\n") { meetingUiParagraphs(it.sceneText) },
            color = Color(0xFF858585),
            fontSize = 13.sp,
            lineHeight = 21.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        return
    }
    val longPress = if (onLongClick == null) Modifier else Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick)
    Surface(
        modifier = Modifier.fillMaxWidth().then(longPress),
        color = Color.White,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Color(0xFF242424)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            group.turns.forEach { turn ->
                turn.meetingOrderedSegments().forEach { segment ->
                    when (segment.type) {
                        MeetingSegmentType.ACTION -> Text(
                            meetingUiParagraphs(segment.text),
                            color = Color(0xFF454545),
                            fontSize = 15.sp,
                            lineHeight = 24.sp,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                        MeetingSegmentType.DIALOGUE -> MeetingUiDialogue(segment.text, turn.speakerId, userAvatar, userAvatarUri)
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
private fun MeetingUiDialogue(text: String, speakerId: String?, userAvatar: String, userAvatarUri: String?) {
    val isUser = speakerId == null
    val bubble: @Composable () -> Unit = {
        Surface(
            modifier = Modifier.widthIn(max = 248.dp),
            color = if (isUser) Color(0xFF242424) else Color(0xFFFCFCFC),
            contentColor = if (isUser) Color.White else Color(0xFF242424),
            shape = if (isUser) {
                RoundedCornerShape(topStart = 17.dp, topEnd = 6.dp, bottomEnd = 17.dp, bottomStart = 17.dp)
            } else {
                RoundedCornerShape(topStart = 6.dp, topEnd = 17.dp, bottomEnd = 17.dp, bottomStart = 17.dp)
            },
            border = if (isUser) null else BorderStroke(1.dp, Color(0xFFDDDDDD)),
        ) {
            Text(
                text.trim().trim('“', '”', '"'),
                fontSize = 15.sp,
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
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
            LuluProfileAvatar(userAvatarUri, userAvatar, 43)
        } else {
            val character = MigratedDomainStores.characters.get(speakerId.orEmpty())
            LuluProfileAvatar(character.avatarUri, character.displayName.take(1).ifBlank { "角" }, 43)
            Spacer(Modifier.width(8.dp))
            bubble()
        }
    }
}

internal fun MeetingTurn.meetingOrderedSegments(): List<MeetingSegment> {
    val stored = segments.filter { it.text.isNotBlank() }
    if (stored.isNotEmpty()) return stored
    return buildList {
        sceneText.trim().takeIf(String::isNotBlank)?.let { add(MeetingSegment(MeetingSegmentType.ACTION, it)) }
        dialogue.trim().takeIf(String::isNotBlank)?.let { add(MeetingSegment(MeetingSegmentType.DIALOGUE, it)) }
    }
}

internal fun List<MeetingSegment>.meetingTranscript(): String = joinToString("\n") { segment ->
    if (segment.type == MeetingSegmentType.DIALOGUE) {
        "“${segment.text.trim().trim('“', '”', '"')}”"
    } else {
        segment.text.trim()
    }
}

private fun meetingUiParagraphs(raw: String): String {
    val normalized = raw.trim()
        .replace(Regex("[\\t ]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
    if (normalized.isBlank()) return ""
    val explicit = normalized.split(Regex("\\n+")).map(String::trim).filter(String::isNotBlank)
    if (explicit.size > 1) return explicit.joinToString("\n\n")
    val sentences = Regex(""".*?[。！？!?](?:[”’])?|.+$""")
        .findAll(normalized)
        .map { it.value.trim() }
        .filter(String::isNotBlank)
        .toList()
    return if (sentences.size <= 2) normalized else sentences.chunked(2).joinToString("\n\n") { it.joinToString("") }
}
