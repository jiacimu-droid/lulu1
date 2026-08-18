package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
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
import com.jiacimu.lulu.data.*
import com.jiacimu.lulu.design.LuluColors

private data class MeetingReadingPage(
    val group: MeetingUiDisplayGroup,
    val speakerId: String?,
    val speakerName: String,
    val text: String,
    val type: MeetingSegmentType,
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun DigitalWorldMeetingSceneExperience(
    modifier: Modifier,
    session: MeetingSession,
    characters: List<CharacterSettings>,
    world: DigitalWorldState,
    input: String,
    generating: Boolean,
    canSend: Boolean,
    errorText: String,
    onBackToMap: () -> Unit,
    onEnd: () -> Unit,
    onDelete: () -> Unit,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: (() -> Unit)?,
    onSceneLongClick: (MeetingUiDisplayGroup) -> Unit,
    onCharacterClick: (String, String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onOpenWritingPicker: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
) {
    val viewOnly = session.endedAt != null
    val groups = remember(session.turns) { meetingSceneGroups(session.turns) }
    val pages = remember(groups) { groups.flatMap(::readingPagesForGroup) }
    var pageIndex by remember(session.id) { mutableIntStateOf(0) }
    var previousPageCount by remember(session.id) { mutableIntStateOf(pages.size) }
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(pages.size) {
        val oldCount = previousPageCount
        if (pages.isEmpty()) {
            pageIndex = 0
        } else if (pages.size > oldCount) {
            val wasAtEnd = oldCount == 0 || pageIndex >= oldCount - 1
            if (wasAtEnd) pageIndex = oldCount.coerceAtMost(pages.lastIndex)
        } else if (pageIndex > pages.lastIndex) {
            pageIndex = pages.lastIndex
        }
        previousPageCount = pages.size
    }

    val homeId = world.homes.values.firstOrNull { it.name == session.location }?.characterId
    val sceneCode = when (session.location) {
        "世界入口" -> DigitalWorldStore.ARRIVAL
        "云眠原" -> DigitalWorldStore.CLOUD_MEADOW
        else -> homeId?.let(DigitalWorldStore::homeLocation) ?: DigitalWorldStore.ARRIVAL
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    session.location,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBackToMap) { Icon(Icons.Outlined.ArrowBack, "返回地图") }
            },
            actions = {
                if (viewOnly) {
                    IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "删除") }
                } else {
                    TextButton(onClick = onEnd, enabled = !generating) {
                        Text("结束", color = Color(0xFF222222), fontWeight = FontWeight.SemiBold)
                    }
                }
                Box {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Outlined.MoreVert, "更多") }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("见面记录") },
                            leadingIcon = { Icon(Icons.Outlined.History, null) },
                            onClick = { showMenu = false; onOpenHistory() },
                        )
                        DropdownMenuItem(
                            text = { Text("见面模型") },
                            leadingIcon = { Icon(Icons.Outlined.Memory, null) },
                            onClick = { showMenu = false; onOpenModelPicker() },
                        )
                        DropdownMenuItem(
                            text = { Text("见面写法") },
                            leadingIcon = { Icon(Icons.Outlined.AutoStories, null) },
                            onClick = { showMenu = false; onOpenWritingPicker() },
                        )
                        DropdownMenuItem(
                            text = { Text("语音设置") },
                            leadingIcon = { Icon(Icons.Outlined.VolumeUp, null) },
                            onClick = { showMenu = false; onOpenVoiceSettings() },
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
        )

        Box(Modifier.fillMaxWidth().weight(.54f)) {
            if (session.reality == MeetingReality.DIGITAL_WORLD) {
                DigitalWorldSceneCanvas(
                    modifier = Modifier.fillMaxSize(),
                    sceneCode = sceneCode,
                    homeCharacterId = homeId,
                    characters = characters,
                    world = world,
                    onCharacterClick = { characterId -> onCharacterClick(characterId, session.location) },
                )
            } else {
                RealisticMeetingIllustration(
                    modifier = Modifier.fillMaxSize(),
                    participantIds = session.participantIds,
                )
            }
        }

        val currentPage = pages.getOrNull(pageIndex)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(.31f)
                .padding(horizontal = 10.dp, vertical = 5.dp)
                .then(
                    currentPage?.let { page ->
                        Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { onSceneLongClick(page.group) },
                        )
                    } ?: Modifier,
                ),
            color = Color(0xFFFEFEFD),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFF252525)),
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.CenterStart) {
                    when {
                        currentPage != null -> {
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                if (currentPage.type == MeetingSegmentType.DIALOGUE) {
                                    Text(
                                        if (currentPage.speakerId == null) "我" else currentPage.speakerName,
                                        color = Color(0xFF777777),
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                Text(
                                    currentPage.text,
                                    color = Color(0xFF252525),
                                    fontSize = 15.sp,
                                    lineHeight = 24.sp,
                                )
                            }
                        }
                        generating -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF333333),
                            )
                        }
                        else -> Text("……", color = Color(0xFF999999), fontSize = 15.sp)
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { pageIndex = (pageIndex - 1).coerceAtLeast(0) },
                        enabled = pageIndex > 0,
                    ) {
                        Icon(Icons.Outlined.ChevronLeft, null, Modifier.size(18.dp))
                        Text("上一段")
                    }
                    Spacer(Modifier.weight(1f))
                    if (pages.isNotEmpty()) {
                        Text("${pageIndex + 1} / ${pages.size}", color = Color(0xFF888888), fontSize = 10.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { pageIndex = (pageIndex + 1).coerceAtMost(pages.lastIndex) },
                        enabled = pages.isNotEmpty() && pageIndex < pages.lastIndex,
                    ) {
                        Text("下一段")
                        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
                    }
                }
            }
        }

        if (errorText.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(errorText, color = MaterialTheme.colorScheme.error, fontSize = 10.5.sp, modifier = Modifier.weight(1f))
                if (onRetry != null) TextButton(onClick = onRetry) { Text("重试") }
            }
        }

        if (!viewOnly) {
            Surface(color = LuluColors.Paper, tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = onInputChanged,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("写一点……") },
                        minLines = 1,
                        maxLines = 3,
                        shape = RoundedCornerShape(16.dp),
                    )
                    FilledIconButton(
                        onClick = onSend,
                        enabled = input.isNotBlank() && canSend,
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFF252525),
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(Icons.Outlined.Send, "发送")
                    }
                }
            }
        } else {
            Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }
    }
}

@Composable
private fun RealisticMeetingIllustration(
    modifier: Modifier,
    participantIds: List<String>,
) {
    Surface(
        modifier = modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        color = Color(0xFFF5F4F1),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFF252525)),
    ) {
        Row(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            participantIds.take(4).forEach { id ->
                val character = MigratedDomainStores.characters.get(id)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LuluProfileAvatar(character.avatarUri, character.displayName.take(1).ifBlank { "角" }, 58)
                    Spacer(Modifier.height(6.dp))
                    Text(character.displayName, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun meetingSceneGroups(turns: List<MeetingTurn>): List<MeetingUiDisplayGroup> {
    val groups = mutableListOf<MeetingUiDisplayGroup>()
    turns.forEach { turn ->
        val key = turn.exchangeId?.takeIf(String::isNotBlank)?.let { "exchange:$it" }
            ?: if (turn.speakerId == "system") "system:${turn.id}" else "legacy:${turn.id}"
        val previous = groups.lastOrNull()
        if (previous?.key == key) {
            groups[groups.lastIndex] = previous.copy(turns = previous.turns + turn)
        } else {
            groups += MeetingUiDisplayGroup(key, listOf(turn))
        }
    }
    return groups
}

private fun readingPagesForGroup(group: MeetingUiDisplayGroup): List<MeetingReadingPage> = buildList {
    group.turns.forEach { turn ->
        turn.meetingOrderedSegments().forEach { segment ->
            readingChunks(segment.text).forEach { chunk ->
                add(
                    MeetingReadingPage(
                        group = group,
                        speakerId = turn.speakerId,
                        speakerName = turn.speakerName,
                        text = if (segment.type == MeetingSegmentType.DIALOGUE) {
                            chunk.trim().trim('“', '”', '"')
                        } else {
                            chunk
                        },
                        type = segment.type,
                    ),
                )
            }
        }
    }
}

private fun readingChunks(raw: String): List<String> {
    val normalized = raw.trim().replace(Regex("[\\t ]+"), " ").replace(Regex("\\n{3,}"), "\n\n")
    if (normalized.isBlank()) return emptyList()
    val explicit = normalized.split(Regex("\\n+")).map(String::trim).filter(String::isNotBlank)
    if (explicit.size > 1) return explicit
    val sentences = Regex(""".*?[。！？!?](?:[”’])?|.+$""")
        .findAll(normalized)
        .map { it.value.trim() }
        .filter(String::isNotBlank)
        .toList()
    return if (sentences.size <= 2) listOf(normalized) else sentences.chunked(2).map { it.joinToString("") }
}
