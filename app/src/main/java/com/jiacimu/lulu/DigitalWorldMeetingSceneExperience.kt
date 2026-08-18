package com.jiacimu.lulu

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.*
import com.jiacimu.lulu.design.LuluColors

private data class MeetingReadingPage(
    val group: MeetingUiDisplayGroup,
    val speakerId: String?,
    val speakerName: String,
    val text: String,
    val type: MeetingSegmentType,
    val voiceKey: String,
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
    val context = LocalContext.current
    val voiceEnabled by MeetingVoicePlayback.enabled.collectAsState()
    val viewOnly = session.endedAt != null
    val groups = remember(session.turns) { meetingSceneGroups(session.turns) }
    val pages = remember(groups) { groups.flatMap(::readingPagesForGroup) }
    var pageIndex by remember(session.id) { mutableIntStateOf(0) }
    var previousPageCount by remember(session.id) { mutableIntStateOf(pages.size) }
    var showMenu by remember { mutableStateOf(false) }

    val userProfilePrefs = remember(context) {
        context.getSharedPreferences("lulu_user_profile", android.content.Context.MODE_PRIVATE)
    }
    val userAvatar = remember(userProfilePrefs) {
        userProfilePrefs.getString("avatar_text", "我").orEmpty().ifBlank { "我" }.take(2)
    }
    val userAvatarUri = remember(userProfilePrefs) { userProfilePrefs.getString("avatar_uri", null) }
    val userName = remember {
        UserProfileContext.displayLabel().takeUnless { it == "用户" }.orEmpty().ifBlank { "我" }
    }

    LaunchedEffect(Unit) { MeetingVoicePlayback.initialize(context) }

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

    val currentPage = pages.getOrNull(pageIndex)

    LaunchedEffect(session.id, currentPage?.voiceKey, voiceEnabled) {
        val page = currentPage
        val speakerId = page?.speakerId
        if (
            voiceEnabled &&
            page != null &&
            page.type == MeetingSegmentType.DIALOGUE &&
            !speakerId.isNullOrBlank() &&
            speakerId != "system"
        ) {
            MeetingVoicePlayback.playVisibleDialogue(
                context = context,
                sessionId = session.id,
                pageKey = page.voiceKey,
                characterId = speakerId,
                text = page.text,
            )
        } else {
            MeetingVoicePlayback.stopVisibleDialogue(session.id)
        }
    }

    DisposableEffect(session.id) {
        onDispose { MeetingVoicePlayback.stopVisibleDialogue(session.id) }
    }

    fun toggleVoice() {
        MeetingVoicePlayback.setEnabled(context, !voiceEnabled)
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
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBackToMap) { Icon(Icons.Outlined.ArrowBack, "返回地图") }
            },
            actions = {
                if (viewOnly) {
                    MeetingToolButton(
                        icon = Icons.Outlined.DeleteOutline,
                        contentDescription = "删除",
                        onClick = onDelete,
                    )
                } else {
                    TextButton(onClick = onEnd, enabled = !generating) {
                        Text("结束", color = Color(0xFF222222), fontWeight = FontWeight.SemiBold)
                    }
                }
                MeetingVoiceToggleButton(
                    enabled = voiceEnabled,
                    onToggle = ::toggleVoice,
                )
                Box {
                    MeetingToolButton(
                        icon = Icons.Outlined.MoreVert,
                        contentDescription = "更多",
                        onClick = { showMenu = true },
                    )
                    MeetingOverflowMenu(
                        expanded = showMenu,
                        voiceEnabled = voiceEnabled,
                        onDismiss = { showMenu = false },
                        onToggleVoice = ::toggleVoice,
                        onOpenHistory = onOpenHistory,
                        onOpenModelPicker = onOpenModelPicker,
                        onOpenWritingPicker = onOpenWritingPicker,
                    )
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
        )

        // Keep the scene square even while the keyboard is resizing the available height.
        // It may become smaller, but it will never be vertically stretched or flattened.
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().weight(.54f),
            contentAlignment = Alignment.TopCenter,
        ) {
            val sceneSize = minOf(maxWidth, maxHeight)
            if (session.reality == MeetingReality.DIGITAL_WORLD) {
                DigitalWorldSceneCanvas(
                    modifier = Modifier.size(sceneSize),
                    sceneCode = sceneCode,
                    homeCharacterId = homeId,
                    characters = characters,
                    world = world,
                    onCharacterClick = { characterId -> onCharacterClick(characterId, session.location) },
                )
            } else {
                RealisticMeetingIllustration(
                    modifier = Modifier.size(sceneSize),
                    participantIds = session.participantIds,
                )
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(.35f)
                .offset(y = (-3).dp)
                .padding(horizontal = 10.dp, vertical = 3.dp)
                .then(
                    currentPage?.let { page ->
                        Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { onSceneLongClick(page.group) },
                        )
                    } ?: Modifier,
                ),
            color = Color(0xFFFEFEFD),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color(0xFF302F2D)),
            shadowElevation = 2.dp,
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 15.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                if (pages.isNotEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .padding(horizontal = 1.dp),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .align(Alignment.Center),
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = Color(0xFFE6E3DD),
                            ) {}
                            Surface(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(((pageIndex + 1f) / pages.size).coerceIn(0f, 1f)),
                                color = Color(0xFF494642),
                            ) {}
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                }

                Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.TopStart,
                ) {
                    Crossfade(
                        targetState = pageIndex,
                        animationSpec = tween(120),
                        label = "meeting-reading-page",
                    ) { targetIndex ->
                        val page = pages.getOrNull(targetIndex)
                        when {
                            page != null -> {
                                key(page.voiceKey) {
                                    val pageScroll = rememberScrollState()
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .verticalScroll(pageScroll),
                                        verticalArrangement = Arrangement.spacedBy(9.dp),
                                    ) {
                                        if (page.type == MeetingSegmentType.DIALOGUE) {
                                            val speakerId = page.speakerId
                                            val speaker = speakerId
                                                ?.takeUnless { it == "system" }
                                                ?.let { id -> characters.firstOrNull { it.characterId == id } }
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            ) {
                                                if (speakerId == null) {
                                                    LuluProfileAvatar(userAvatarUri, userAvatar, 38)
                                                    Text(
                                                        userName,
                                                        color = Color(0xFF282828),
                                                        fontSize = 17.sp,
                                                        fontWeight = FontWeight.Bold,
                                                    )
                                                } else if (speaker != null) {
                                                    LuluProfileAvatar(
                                                        speaker.avatarUri,
                                                        speaker.displayName.take(1).ifBlank { "角" },
                                                        40,
                                                    )
                                                    Text(
                                                        speaker.displayName,
                                                        color = Color(0xFF222222),
                                                        fontSize = 17.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                    )
                                                } else {
                                                    Text(
                                                        page.speakerName,
                                                        color = Color(0xFF222222),
                                                        fontSize = 17.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            page.text,
                                            color = Color(0xFF252525),
                                            style = TextStyle(
                                                fontSize = 15.sp,
                                                lineHeight = 23.sp,
                                                textIndent = TextIndent(firstLine = 2.em),
                                            ),
                                        )
                                    }
                                }
                            }
                            generating -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 1.8.dp,
                                        color = Color(0xFF333333),
                                    )
                                    Text("正在继续……", color = Color(0xFF777570), fontSize = 11.sp)
                                }
                            }
                            else -> Text("……", color = Color(0xFF999999), fontSize = 15.sp)
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MeetingPageNavButton(
                        label = "上一段",
                        icon = Icons.Outlined.ChevronLeft,
                        iconAfter = false,
                        enabled = pageIndex > 0,
                        onClick = { pageIndex = (pageIndex - 1).coerceAtLeast(0) },
                    )
                    Spacer(Modifier.weight(1f))
                    if (pages.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(99.dp),
                            color = Color(0xFFF2F1EE),
                            border = BorderStroke(.7.dp, Color(0xFFE0DED9)),
                        ) {
                            Text(
                                "${pageIndex + 1} / ${pages.size}",
                                color = Color(0xFF777570),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    MeetingPageNavButton(
                        label = "下一段",
                        icon = Icons.Outlined.ChevronRight,
                        iconAfter = true,
                        enabled = pages.isNotEmpty() && pageIndex < pages.lastIndex,
                        onClick = { pageIndex = (pageIndex + 1).coerceAtMost(pages.lastIndex) },
                    )
                }
            }
        }

        if (errorText.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    errorText,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 10.5.sp,
                    modifier = Modifier.weight(1f),
                )
                if (onRetry != null) TextButton(onClick = onRetry) { Text("重试") }
            }
        }

        if (!viewOnly) {
            Surface(color = LuluColors.Paper, tonalElevation = 0.dp) {
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
                        placeholder = { Text("写一点……", fontSize = 13.sp) },
                        minLines = 1,
                        maxLines = 3,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF34322F),
                            unfocusedBorderColor = Color(0xFFD5D2CC),
                            cursorColor = Color(0xFF2B2B2B),
                        ),
                    )
                    FilledIconButton(
                        onClick = onSend,
                        enabled = input.isNotBlank() && canSend,
                        modifier = Modifier.size(47.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFF252525),
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFFE6E4DF),
                            disabledContentColor = Color(0xFFAAA7A0),
                        ),
                    ) {
                        Icon(Icons.Outlined.Send, "发送", modifier = Modifier.size(19.dp))
                    }
                }
            }
        } else {
            Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }
    }
}

@Composable
private fun MeetingPageNavButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconAfter: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(99.dp),
        color = if (enabled) Color(0xFFFEFEFD) else Color(0xFFF6F5F2),
        border = BorderStroke(
            .8.dp,
            if (enabled) Color(0xFF373532) else Color(0xFFE2E0DB),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (!iconAfter) {
                Icon(
                    icon,
                    null,
                    tint = if (enabled) Color(0xFF34322F) else Color(0xFFB7B4AE),
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                label,
                color = if (enabled) Color(0xFF34322F) else Color(0xFFB7B4AE),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            if (iconAfter) {
                Icon(
                    icon,
                    null,
                    tint = if (enabled) Color(0xFF34322F) else Color(0xFFB7B4AE),
                    modifier = Modifier.size(16.dp),
                )
            }
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
                    Spacer(Modifier.height(4.dp))
                    Text(character.displayName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
        turn.meetingOrderedSegments().forEachIndexed { segmentIndex, segment ->
            val targetChars = if (segment.type == MeetingSegmentType.DIALOGUE) 92 else 132
            readingChunks(segment.text, targetChars).forEachIndexed { chunkIndex, chunk ->
                add(
                    MeetingReadingPage(
                        group = group,
                        speakerId = turn.speakerId,
                        speakerName = turn.speakerName,
                        text = if (segment.type == MeetingSegmentType.DIALOGUE) {
                            chunk.trim().trim('“', '”', '"')
                        } else {
                            chunk.trim()
                        },
                        type = segment.type,
                        voiceKey = "${group.key}:${turn.id}:$segmentIndex:$chunkIndex",
                    ),
                )
            }
        }
    }
}

/**
 * targetChars is a soft target, not a guillotine. Complete sentences stay intact.
 * A single unusually long sentence stays on one page and can be scrolled vertically.
 */
private fun readingChunks(raw: String, targetChars: Int): List<String> {
    val normalized = raw
        .trim()
        .replace(Regex("[\\t ]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
    if (normalized.isBlank()) return emptyList()

    val result = mutableListOf<String>()
    normalized
        .split(Regex("\\n+"))
        .map(String::trim)
        .filter(String::isNotBlank)
        .forEach { paragraph ->
            val sentences = sentenceSafePieces(paragraph)
            val page = StringBuilder()

            fun flushPage() {
                val text = page.toString().trim()
                if (text.isNotBlank()) result += text
                page.clear()
            }

            sentences.forEach { sentence ->
                if (page.isEmpty()) {
                    page.append(sentence)
                } else if (page.length + sentence.length <= targetChars) {
                    page.append(sentence)
                } else {
                    flushPage()
                    page.append(sentence)
                }
            }
            flushPage()
        }
    return result
}

private fun sentenceSafePieces(paragraph: String): List<String> {
    if (paragraph.isBlank()) return emptyList()
    val pieces = mutableListOf<String>()
    val buffer = StringBuilder()
    val closingMarks = "”’」』）》】\""
    var sentenceEnded = false

    paragraph.forEachIndexed { index, char ->
        buffer.append(char)
        if (char in "。！？!?" || char == '…') {
            sentenceEnded = true
        }

        if (sentenceEnded) {
            val next = paragraph.getOrNull(index + 1)
            val shouldWait = next != null && (next in closingMarks || next == '…')
            if (!shouldWait) {
                val sentence = buffer.toString().trim()
                if (sentence.isNotBlank()) pieces += sentence
                buffer.clear()
                sentenceEnded = false
            }
        }
    }

    val tail = buffer.toString().trim()
    if (tail.isNotBlank()) pieces += tail
    return pieces.ifEmpty { listOf(paragraph) }
}
