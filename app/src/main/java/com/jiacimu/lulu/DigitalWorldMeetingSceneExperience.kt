package com.jiacimu.lulu

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.*
import com.jiacimu.lulu.games.GameAmbientAudioButton
import com.jiacimu.lulu.games.GameAmbientSoundscape
import com.jiacimu.lulu.games.GameSoundscape

private data class MeetingReadingPage(
    val group: MeetingUiDisplayGroup,
    val speakerId: String?,
    val speakerName: String,
    val text: String,
    val type: MeetingSegmentType,
    val voiceKey: String,
    val speechText: String,
)

private enum class MeetingSceneMode { Explore, Story }

@OptIn(ExperimentalFoundationApi::class)
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
    onPhysicalInteraction: (String, String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onOpenWritingPicker: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val voiceEnabled by MeetingVoicePlayback.enabled.collectAsState()
    val bgmEnabled = GameAmbientSoundscape(GameSoundscape.Meeting)
    val viewOnly = session.endedAt != null
    val isDigitalWorld = session.reality == MeetingReality.DIGITAL_WORLD
    val meetingExperience by MeetingExperienceStore.state.collectAsState()
    val followers = if (viewOnly) emptySet() else meetingExperience.scenes[session.id]?.participants.orEmpty()
        .filter { it.participantId in session.participantIds && it.explorationMode == "FOLLOW_USER" }
        .mapTo(mutableSetOf()) { it.participantId }
    val groups = remember(session.turns) { meetingSceneGroups(session.turns) }
    val pages = remember(groups) { groups.flatMap(::readingPagesForGroup) }
    var pageIndex by remember(session.id) { mutableIntStateOf(0) }
    var previousPageCount by remember(session.id) { mutableIntStateOf(pages.size) }
    var showMenu by remember { mutableStateOf(false) }
    var narrativeExpanded by rememberSaveable(session.id) { mutableStateOf(true) }
    var sceneModeName by rememberSaveable(session.id) { mutableStateOf(MeetingSceneMode.Story.name) }
    val sceneMode = if (!isDigitalWorld || viewOnly) MeetingSceneMode.Story else {
        runCatching { MeetingSceneMode.valueOf(sceneModeName) }.getOrDefault(MeetingSceneMode.Story)
    }
    val exploring = isDigitalWorld && !viewOnly && sceneMode == MeetingSceneMode.Explore
    val storyVisible = !isDigitalWorld || !exploring

    val userPrefs = remember(context) {
        context.getSharedPreferences("lulu_user_profile", android.content.Context.MODE_PRIVATE)
    }
    val userAvatar = remember(userPrefs) {
        userPrefs.getString("avatar_text", "我").orEmpty().ifBlank { "我" }.take(2)
    }
    val userAvatarUri = remember(userPrefs) { userPrefs.getString("avatar_uri", null) }
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
            narrativeExpanded = true
            if (isDigitalWorld) sceneModeName = MeetingSceneMode.Story.name
        } else if (pageIndex > pages.lastIndex) {
            pageIndex = pages.lastIndex
        }
        previousPageCount = pages.size
    }
    val currentPage = pages.getOrNull(pageIndex)

    LaunchedEffect(session.id, currentPage?.voiceKey, voiceEnabled, storyVisible) {
        val page = currentPage
        val speakerId = page?.speakerId
        if (
            storyVisible && voiceEnabled && page != null && page.speechText.isNotBlank() &&
            !speakerId.isNullOrBlank() && speakerId != "system"
        ) {
            MeetingVoicePlayback.playVisibleDialogue(
                context = context,
                sessionId = session.id,
                pageKey = page.voiceKey,
                characterId = speakerId,
                text = page.text,
                speechText = page.speechText,
            )
        } else {
            MeetingVoicePlayback.stopVisibleDialogue(session.id)
        }
    }
    DisposableEffect(session.id) {
        onDispose { MeetingVoicePlayback.stopVisibleDialogue(session.id) }
    }

    val sceneCode = DigitalWorldStore.meetingLocationCode(session.location)
    val homeId = sceneCode.takeIf { it.startsWith("home:") }?.removePrefix("home:")

    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xFF08110F))
            .imePadding(),
    ) {
        if (isDigitalWorld) {
            DigitalWorldSceneCanvas(
                modifier = Modifier.fillMaxSize().padding(top = 56.dp),
                sceneCode = sceneCode,
                homeCharacterId = homeId,
                characters = characters,
                world = world,
                onCharacterClick = { characterId ->
                    sceneModeName = MeetingSceneMode.Story.name
                    narrativeExpanded = true
                    onCharacterClick(characterId, session.location)
                },
                onWorldAction = { suggestedAction ->
                    onInputChanged(suggestedAction)
                    sceneModeName = MeetingSceneMode.Story.name
                    narrativeExpanded = true
                },
                onPhysicalInteraction = onPhysicalInteraction,
                controlsBottomPadding = 94.dp,
                controlsEnabled = exploring && !generating,
                showExplorationHud = exploring,
                followerIds = followers,
            )
        } else {
            RealisticMeetingStage(
                modifier = Modifier.fillMaxSize().padding(top = 56.dp),
                participantIds = session.participantIds,
            )
        }

        if (isDigitalWorld && !exploring) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = 56.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0x12040A08),
                                Color(0x36040A08),
                                Color(0x76040A08),
                            ),
                        ),
                    ),
            )
        }

        MeetingImmersiveTopBar(
            location = session.location,
            viewOnly = viewOnly,
            generating = generating,
            voiceEnabled = voiceEnabled,
            bgmEnabled = bgmEnabled,
            menuExpanded = showMenu,
            digitalWorld = isDigitalWorld,
            exploring = exploring,
            onBack = onBackToMap,
            onEnd = onEnd,
            onDelete = onDelete,
            onToggleVoice = { MeetingVoicePlayback.setEnabled(context, !voiceEnabled) },
            onToggleMenu = { showMenu = true },
            onDismissMenu = { showMenu = false },
            onOpenHistory = onOpenHistory,
            onOpenModelPicker = onOpenModelPicker,
            onOpenWritingPicker = onOpenWritingPicker,
            onOpenVoiceSettings = onOpenVoiceSettings,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        if (storyVisible) {
            MeetingNarrativeOverlay(
                page = currentPage,
                pageIndex = pageIndex,
                pageCount = pages.size,
                expanded = if (isDigitalWorld) true else narrativeExpanded,
                viewOnly = viewOnly,
                generating = generating,
                characters = characters,
                userName = userName,
                userAvatar = userAvatar,
                userAvatarUri = userAvatarUri,
                input = input,
                canSend = canSend,
                errorText = errorText,
                onExpandedChanged = { narrativeExpanded = it },
                onReturnToExplore = if (isDigitalWorld && !viewOnly && !generating) {
                    {
                        MeetingVoicePlayback.stopVisibleDialogue(session.id)
                        sceneModeName = MeetingSceneMode.Explore.name
                    }
                } else null,
                onPrevious = { pageIndex = (pageIndex - 1).coerceAtLeast(0) },
                onNext = { pageIndex = (pageIndex + 1).coerceAtMost(pages.lastIndex) },
                onInputChanged = onInputChanged,
                onSend = onSend,
                onRetry = onRetry,
                onLongClick = { currentPage?.let { onSceneLongClick(it.group) } },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            )
        } else {
            MeetingExploreDock(
                pageIndex = pageIndex,
                pageCount = pages.size,
                generating = generating,
                onOpenStory = { sceneModeName = MeetingSceneMode.Story.name },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun MeetingImmersiveTopBar(
    location: String,
    viewOnly: Boolean,
    generating: Boolean,
    voiceEnabled: Boolean,
    bgmEnabled: Boolean,
    menuExpanded: Boolean,
    digitalWorld: Boolean,
    exploring: Boolean,
    onBack: () -> Unit,
    onEnd: () -> Unit,
    onDelete: () -> Unit,
    onToggleVoice: () -> Unit,
    onToggleMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onOpenWritingPicker: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val subtitle = when {
        !digitalWorld -> "沉浸见面 · 剧情互动"
        exploring -> "自由活动 · 移动、靠近、点击互动"
        else -> "剧情互动 · 移动操控已暂停"
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xF20A1512), Color(0xD90A1512), Color.Transparent),
                ),
            )
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回地图", tint = Color.White) }
        Column(Modifier.weight(1f)) {
            Text(location, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Color(0xFF9CCABD), fontSize = 8.5.sp, letterSpacing = .25.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (viewOnly) {
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "删除", tint = Color.White) }
        } else {
            TextButton(onClick = onEnd, enabled = !generating) {
                Text("结束", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        MeetingVoiceToggleButton(enabled = voiceEnabled, onToggle = onToggleVoice)
        GameAmbientAudioButton(enabled = bgmEnabled, tint = Color.White)
        Box {
            IconButton(onClick = onToggleMenu) { Icon(Icons.Outlined.MoreVert, "更多", tint = Color.White) }
            MeetingOverflowMenu(
                expanded = menuExpanded,
                voiceEnabled = voiceEnabled,
                onDismiss = onDismissMenu,
                onToggleVoice = onToggleVoice,
                onOpenHistory = onOpenHistory,
                onOpenModelPicker = onOpenModelPicker,
                onOpenWritingPicker = onOpenWritingPicker,
                onOpenVoiceSettings = onOpenVoiceSettings,
            )
        }
    }
}

@Composable
private fun MeetingExploreDock(
    pageIndex: Int,
    pageCount: Int,
    generating: Boolean,
    onOpenStory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        color = Color(0xE60D1916),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .14f)),
        shadowElevation = 14.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                color = Color(0xFF9EFFE0).copy(alpha = .13f),
                shape = RoundedCornerShape(13.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Explore, null, tint = Color(0xFFBFFFEA), modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("自由活动", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Black)
                Text(
                    if (generating) "剧情正在后台继续，移动暂时锁定" else "摇杆移动 · 靠近人物或家具后再互动",
                    color = Color(0xFF91AAA2),
                    fontSize = 8.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (pageCount > 0 || generating) {
                FilledTonalButton(
                    onClick = onOpenStory,
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFF9EFFE0).copy(alpha = .14f),
                        contentColor = Color(0xFFD5FFF1),
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Outlined.AutoStories, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (pageCount > 0) "剧情 ${pageIndex + 1}/$pageCount" else "看剧情",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MeetingNarrativeOverlay(
    page: MeetingReadingPage?,
    pageIndex: Int,
    pageCount: Int,
    expanded: Boolean,
    viewOnly: Boolean,
    generating: Boolean,
    characters: List<CharacterSettings>,
    userName: String,
    userAvatar: String,
    userAvatarUri: String?,
    input: String,
    canSend: Boolean,
    errorText: String,
    onExpandedChanged: (Boolean) -> Unit,
    onReturnToExplore: (() -> Unit)?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: (() -> Unit)?,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val swipeThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { 44.dp.toPx() }
    var swipeDistance by remember(page?.voiceKey, pageCount) { mutableFloatStateOf(0f) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .animateContentSize(tween(180))
            .combinedClickable(onClick = {}, onLongClick = onLongClick),
        color = Color(0xF2101B18),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .18f)),
        shadowElevation = 18.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MeetingSpeakerBadge(page, characters, userName, userAvatar, userAvatarUri)
                Spacer(Modifier.weight(1f))
                if (pageCount > 1) {
                    Surface(
                        color = Color(0xFF9EFFE0).copy(alpha = .12f),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, Color(0xFF9EFFE0).copy(alpha = .20f)),
                    ) {
                        Text(
                            "${pageIndex + 1} / $pageCount",
                            color = Color(0xFFC8FFEE),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.width(5.dp))
                }
                if (onReturnToExplore != null) {
                    TextButton(
                        onClick = onReturnToExplore,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                    ) {
                        Icon(Icons.Outlined.Explore, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("自由活动", fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    IconButton(onClick = { onExpandedChanged(!expanded) }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                            if (expanded) "收起剧情" else "展开剧情",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }

            Crossfade(page?.voiceKey to expanded, animationSpec = tween(130), label = "meeting-story-layer") { (_, showFull) ->
                val visiblePage = page
                if (showFull) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 68.dp, max = 154.dp)
                            .pointerInput(visiblePage?.voiceKey, pageIndex, pageCount) {
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        when {
                                            swipeDistance <= -swipeThresholdPx && pageIndex < pageCount - 1 -> onNext()
                                            swipeDistance >= swipeThresholdPx && pageIndex > 0 -> onPrevious()
                                        }
                                        swipeDistance = 0f
                                    },
                                    onDragCancel = { swipeDistance = 0f },
                                    onHorizontalDrag = { _, dragAmount -> swipeDistance += dragAmount },
                                )
                            },
                        contentAlignment = Alignment.TopStart,
                    ) {
                        when {
                            visiblePage != null -> key(visiblePage.voiceKey) {
                                Text(
                                    visiblePage.text,
                                    color = Color(0xFFF2F6F3),
                                    fontSize = 14.5.sp,
                                    lineHeight = 22.sp,
                                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                                )
                            }
                            generating -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFF9EFFE0))
                                Spacer(Modifier.width(8.dp))
                                Text("场景正在继续……", color = Color(0xFFC6D8D1), fontSize = 11.sp)
                            }
                            else -> Text("现在没有正在播放的剧情，你可以直接说话或描述行动。", color = Color(0xFFB8C9C3), fontSize = 12.sp)
                        }
                    }
                } else {
                    Text(
                        visiblePage?.text ?: if (generating) "场景正在继续……" else "剧情互动",
                        color = Color(0xFFCFDDD8),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    )
                }
            }

            if (expanded && pageCount > 1) {
                Row(
                    Modifier.fillMaxWidth().height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledIconButton(
                        onClick = onPrevious,
                        enabled = pageIndex > 0,
                        modifier = Modifier.size(40.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color.White.copy(alpha = .10f),
                            contentColor = Color.White,
                            disabledContainerColor = Color.White.copy(alpha = .035f),
                            disabledContentColor = Color.White.copy(alpha = .18f),
                        ),
                    ) {
                        Icon(Icons.Outlined.ChevronLeft, "上一段", Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "第 ${pageIndex + 1} 段 · 共 $pageCount 段",
                            color = Color(0xFFE8F5F0),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(5.dp))
                        LinearProgressIndicator(
                            progress = { (pageIndex + 1f) / pageCount.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth(.68f).height(3.dp).clip(RoundedCornerShape(99.dp)),
                            color = Color(0xFF9EFFE0),
                            trackColor = Color.White.copy(alpha = .12f),
                        )
                        Spacer(Modifier.height(3.dp))
                        Text("左右滑动文字也可以翻页", color = Color(0xFF7FA69A), fontSize = 7.5.sp)
                    }
                    FilledIconButton(
                        onClick = onNext,
                        enabled = pageIndex < pageCount - 1,
                        modifier = Modifier.size(40.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFF9EFFE0).copy(alpha = .18f),
                            contentColor = Color(0xFFC7FFED),
                            disabledContainerColor = Color.White.copy(alpha = .035f),
                            disabledContentColor = Color.White.copy(alpha = .18f),
                        ),
                    ) {
                        Icon(Icons.Outlined.ChevronRight, "下一段", Modifier.size(22.dp))
                    }
                }
            }

            if (errorText.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(errorText, color = Color(0xFFFF9D9D), fontSize = 10.sp, modifier = Modifier.weight(1f), maxLines = 2)
                    if (onRetry != null) TextButton(onClick = onRetry) { Text("重试") }
                }
            }

            if (!viewOnly) {
                Row(
                    Modifier.fillMaxWidth().padding(top = if (expanded) 5.dp else 2.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = onInputChanged,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("说话，或描述你的行动……", fontSize = 11.5.sp) },
                        minLines = 1,
                        maxLines = if (expanded) 2 else 1,
                        shape = RoundedCornerShape(15.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF9EFFE0),
                            unfocusedBorderColor = Color.White.copy(alpha = .22f),
                            cursorColor = Color(0xFF9EFFE0),
                            focusedContainerColor = Color.Black.copy(alpha = .16f),
                            unfocusedContainerColor = Color.Black.copy(alpha = .12f),
                            focusedPlaceholderColor = Color(0xFF91A8A0),
                            unfocusedPlaceholderColor = Color(0xFF91A8A0),
                        ),
                    )
                    FilledIconButton(
                        onClick = onSend,
                        enabled = input.isNotBlank() && canSend,
                        modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(15.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFF9EFFE0),
                            contentColor = Color(0xFF10211C),
                            disabledContainerColor = Color.White.copy(alpha = .10f),
                            disabledContentColor = Color.White.copy(alpha = .34f),
                        ),
                    ) {
                        if (generating) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.NorthEast, "发送", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MeetingSpeakerBadge(
    page: MeetingReadingPage?,
    characters: List<CharacterSettings>,
    userName: String,
    userAvatar: String,
    userAvatarUri: String?,
) {
    val character = page?.speakerId?.takeUnless { it == "system" }
        ?.let { id -> characters.firstOrNull { it.characterId == id } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            page?.type != MeetingSegmentType.DIALOGUE -> {
                Surface(shape = RoundedCornerShape(9.dp), color = Color(0xFF9EFFE0).copy(alpha = .14f), modifier = Modifier.size(29.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.AutoStories, null, tint = Color(0xFFB7FFE8), modifier = Modifier.size(16.dp)) }
                }
            }
            page.speakerId == null -> LuluProfileAvatar(userAvatarUri, userAvatar, 30)
            character != null -> LuluProfileAvatar(character.avatarUri, character.displayName.take(1), 30)
            else -> {
                Surface(shape = RoundedCornerShape(9.dp), color = Color.White.copy(alpha = .12f), modifier = Modifier.size(29.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text(page.speakerName.take(1), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                when {
                    page == null -> "当下"
                    page.type != MeetingSegmentType.DIALOGUE -> "场景"
                    page.speakerId == null -> userName
                    character != null -> character.displayName
                    else -> page.speakerName
                },
                color = Color.White,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
            )
            Text(
                if (page?.type == MeetingSegmentType.DIALOGUE) "正在说话" else "环境叙事",
                color = Color(0xFF88A99F),
                fontSize = 7.5.sp,
            )
        }
    }
}

@Composable
private fun RealisticMeetingStage(modifier: Modifier, participantIds: List<String>) {
    Box(
        modifier.background(
            Brush.radialGradient(
                listOf(Color(0xFF536763), Color(0xFF1B2A27), Color(0xFF08110F)),
            ),
        ),
    ) {
        participantIds.take(4).forEachIndexed { index, id ->
            val character = MigratedDomainStores.characters.get(id)
            val alignments = listOf(Alignment.CenterStart, Alignment.CenterEnd, Alignment.TopCenter, Alignment.BottomCenter)
            Surface(
                modifier = Modifier.align(alignments[index % alignments.size]).padding(28.dp),
                color = Color.White.copy(alpha = .08f),
                shape = RoundedCornerShape(30.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .15f)),
                shadowElevation = 10.dp,
            ) {
                Column(Modifier.padding(13.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    StatefulCharacterPortrait(id, character.avatarUri, character.displayName, 92)
                    Spacer(Modifier.height(7.dp))
                    Text(character.displayName, color = Color.White, fontWeight = FontWeight.Bold)
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
        if (previous?.key == key) groups[groups.lastIndex] = previous.copy(turns = previous.turns + turn)
        else groups += MeetingUiDisplayGroup(key, listOf(turn))
    }
    return groups
}

private fun readingPagesForGroup(group: MeetingUiDisplayGroup): List<MeetingReadingPage> = buildList {
    group.turns.forEach { turn ->
        turn.meetingOrderedSegments().forEachIndexed { segmentIndex, segment ->
            val targetChars = if (segment.type == MeetingSegmentType.DIALOGUE) 92 else 132
            var textOffset = 0
            readingChunks(segment.text, targetChars).forEachIndexed { chunkIndex, chunk ->
                val start = segment.text.indexOf(chunk, textOffset).takeIf { it >= 0 } ?: textOffset
                val end = start + chunk.length
                val audio = if (segment.type == MeetingSegmentType.ACTION) {
                    if (chunkIndex == 0) segment.speechText else ""
                } else if (segment.speechText.isBlank()) chunk else VoicePerformance.slice(segment.speechText, start, end).takeIf { VoicePerformance.plain(it) == chunk.trim() } ?: chunk
                textOffset = end
                add(
                    MeetingReadingPage(
                        group = group,
                        speakerId = turn.speakerId,
                        speakerName = turn.speakerName,
                        text = if (segment.type == MeetingSegmentType.DIALOGUE) chunk.trim().trim('“', '”', '"') else chunk.trim(),
                        type = segment.type,
                        voiceKey = "${group.key}:${turn.id}:$segmentIndex:$chunkIndex",
                        speechText = audio,
                    ),
                )
            }
        }
    }
}

private fun readingChunks(raw: String, targetChars: Int): List<String> {
    val normalized = raw.trim().replace(Regex("[\\t ]+"), " ").replace(Regex("\\n{3,}"), "\n\n")
    if (normalized.isBlank()) return emptyList()
    val result = mutableListOf<String>()
    normalized.split(Regex("\\n+")).map(String::trim).filter(String::isNotBlank).forEach { paragraph ->
        val page = StringBuilder()
        fun flush() {
            page.toString().trim().takeIf(String::isNotBlank)?.let(result::add)
            page.clear()
        }
        sentenceSafePieces(paragraph).forEach { sentence ->
            if (page.isNotEmpty() && page.length + sentence.length > targetChars) flush()
            page.append(sentence)
        }
        flush()
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
        if (char in "。！？!?" || char == '…') sentenceEnded = true
        if (sentenceEnded) {
            val next = paragraph.getOrNull(index + 1)
            if (next == null || (next !in closingMarks && next != '…')) {
                buffer.toString().trim().takeIf(String::isNotBlank)?.let(pieces::add)
                buffer.clear()
                sentenceEnded = false
            }
        }
    }
    buffer.toString().trim().takeIf(String::isNotBlank)?.let(pieces::add)
    return pieces.ifEmpty { listOf(paragraph) }
}
