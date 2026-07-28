package com.jiacimu.lulu

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelArchive
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.games.LuluGamesApp
import com.jiacimu.lulu.study.PostgraduateExamApp
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val FinalPaper = Color(0xFFFFFCF5)
private val FinalCard = Color(0xEFFFFFF8)
private val FinalWheat = Color(0xFFF2CF70)
private val FinalMuted = Color(0xFF747887)
private val FinalBorder = Color(0xFFE7DDC8)
private val FinalInk = Color(0xFF2F2B2A)
private val FinalRose = Color(0xFFF6D9D0)
private val FinalLavender = Color(0xFFE7E1F6)

private const val LauncherColumns = 4
private const val LauncherSlotCount = 12

private enum class FinalRoute {
    Home, Chat, ChatDetail, CharacterSettings, Memory, Lexicon, WorldBook, Performance,
    Reading, Wishes, Study, Games, Settings,
}

private data class FinalLauncher(
    val title: String,
    val icon: ImageVector,
    val route: FinalRoute,
    val tileStart: Color,
    val tileEnd: Color,
    val iconTint: Color,
)

private val FinalLaunchers = listOf(
    FinalLauncher("聊天", Icons.Outlined.ChatBubbleOutline, FinalRoute.Chat, Color(0xFFFFE8D9), Color(0xFFF9CFC2), Color(0xFF7D5147)),
    FinalLauncher("记忆", Icons.Outlined.Psychology, FinalRoute.Memory, Color(0xFFECE7FA), Color(0xFFDCD1F2), Color(0xFF5E567C)),
    FinalLauncher("辞海", Icons.Outlined.MenuBook, FinalRoute.Lexicon, Color(0xFFE8F1E4), Color(0xFFD3E4CE), Color(0xFF4F6B55)),
    FinalLauncher("世界书", Icons.Outlined.Public, FinalRoute.WorldBook, Color(0xFFE5F0F6), Color(0xFFD0E2EC), Color(0xFF4C6574)),
    FinalLauncher("性能监测", Icons.Outlined.MonitorHeart, FinalRoute.Performance, Color(0xFFFFE8E4), Color(0xFFF4D1CB), Color(0xFF80554E)),
    FinalLauncher("阅读", Icons.Outlined.AutoStories, FinalRoute.Reading, Color(0xFFFFEFD5), Color(0xFFF5DDAF), Color(0xFF775F37)),
    FinalLauncher("心愿馆", Icons.Outlined.StarOutline, FinalRoute.Wishes, Color(0xFFF4E6F3), Color(0xFFE6D0E5), Color(0xFF735A72)),
    FinalLauncher("考研", Icons.Outlined.School, FinalRoute.Study, Color(0xFFE2EEF0), Color(0xFFCEE0E2), Color(0xFF4B686B)),
    FinalLauncher("游戏", Icons.Outlined.SportsEsports, FinalRoute.Games, Color(0xFFE9E4F7), Color(0xFFD8CEF0), Color(0xFF5F567B)),
    FinalLauncher("设置", Icons.Outlined.Settings, FinalRoute.Settings, Color(0xFFEDEBE6), Color(0xFFDCD8D0), Color(0xFF605D58)),
)

@Composable
fun LuluFinalRootApp() {
    var route by remember { mutableStateOf(FinalRoute.Home) }
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = FinalWheat,
            onPrimary = FinalInk,
            background = FinalPaper,
            surface = FinalCard,
            onSurface = FinalInk,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = FinalPaper) {
            when (route) {
                FinalRoute.Home -> FinalHome { route = it }
                FinalRoute.Chat -> FinalChatHub(
                    onBack = { route = FinalRoute.Home },
                    onOpenConversation = { route = FinalRoute.ChatDetail },
                    onCharacterSettings = { route = FinalRoute.CharacterSettings },
                )
                FinalRoute.ChatDetail -> FinalChatDetail { route = FinalRoute.Chat }
                FinalRoute.CharacterSettings -> CharacterSettingsScreen { route = FinalRoute.Chat }
                FinalRoute.Memory -> MemoryFeatureScreen { route = FinalRoute.Home }
                FinalRoute.Lexicon -> LexiconFeatureScreen { route = FinalRoute.Home }
                FinalRoute.WorldBook -> CharacterWorldBookScreen { route = FinalRoute.Home }
                FinalRoute.Performance -> PerformanceFeatureScreen { route = FinalRoute.Home }
                FinalRoute.Study -> PostgraduateExamApp { route = FinalRoute.Home }
                FinalRoute.Games -> LuluGamesApp { route = FinalRoute.Home }
                FinalRoute.Settings -> LuluSettingsScreen { route = FinalRoute.Home }
                FinalRoute.Reading -> FinalEmpty("阅读", "阅读空间已保留，下一整块迁移时接入书架、阅读器与笔记。") { route = FinalRoute.Home }
                FinalRoute.Wishes -> FinalEmpty("心愿馆", "心愿馆已保留，下一整块迁移时接入愿望、进度与角色回应。") { route = FinalRoute.Home }
            }
        }
    }
}

@Composable
private fun FinalHome(onOpen: (FinalRoute) -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("lulu_desktop_layout", Context.MODE_PRIVATE)
    }
    val launcherSlots = remember(preferences) {
        val occupied = mutableSetOf<Int>()
        mutableStateMapOf<FinalRoute, Int>().apply {
            FinalLaunchers.forEachIndexed { defaultSlot, launcher ->
                val savedSlot = preferences.getInt("slot_${launcher.route.name}", defaultSlot)
                val candidate = savedSlot.takeIf { it in 0 until LauncherSlotCount } ?: defaultSlot
                val resolved = candidate.takeIf { it !in occupied }
                    ?: (0 until LauncherSlotCount).first { it !in occupied }
                this[launcher.route] = resolved
                occupied += resolved
            }
        }
    }
    val conversations by MigratedDomainStores.chat.conversations.collectAsState()
    val latest = conversations.firstOrNull()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFFFFCF5), Color(0xFFFFF9EF), Color(0xFFFDF8F1)),
                ),
            ),
    ) {
        Box(
            Modifier
                .offset(x = 270.dp, y = 78.dp)
                .size(150.dp)
                .clip(CircleShape)
                .background(FinalRose.copy(alpha = 0.22f)),
        )
        Box(
            Modifier
                .offset(x = (-45).dp, y = 340.dp)
                .size(135.dp)
                .clip(CircleShape)
                .background(FinalLavender.copy(alpha = 0.22f)),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 26.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "欢迎回来，主人",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.6.sp,
                        color = FinalInk,
                    )
                    Text("今天也慢慢来，露露会把每一件小事陪好。", fontSize = 14.sp, color = FinalMuted)
                }
            }
            item {
                FinalWelcomeCard(
                    title = latest?.title ?: "露露",
                    message = latest?.lastMessage.orEmpty().ifBlank { "今天也会一直陪着主人呀～" },
                    onClick = { onOpen(FinalRoute.Chat) },
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("我的桌面", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = FinalInk)
                    Text("长按图标，可自由拖到空位", fontSize = 11.sp, color = FinalMuted)
                }
            }
            item {
                DesktopLauncherGrid(
                    launchers = FinalLaunchers,
                    slots = launcherSlots,
                    onOpen = onOpen,
                    onMove = { route, targetSlot ->
                        val occupiedByAnother = launcherSlots.any { (otherRoute, slot) ->
                            otherRoute != route && slot == targetSlot
                        }
                        if (!occupiedByAnother) {
                            launcherSlots[route] = targetSlot
                            preferences.edit().putInt("slot_${route.name}", targetSlot).apply()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun FinalWelcomeCard(title: String, message: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(30.dp), ambientColor = Color.Black.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.9f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFFFFF1D7), Color(0xFFFFE7DA), Color(0xFFF3E8F4)),
                    ),
                )
                .padding(18.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 26.dp, y = (-28).dp)
                    .size(112.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.28f)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(Color.White.copy(alpha = 0.68f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.FavoriteBorder, null, tint = Color(0xFF8B605B), modifier = Modifier.size(23.dp))
                    }
                    Spacer(Modifier.width(11.dp))
                    Column {
                        Text(title, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = FinalInk)
                        Text("在线陪伴中", fontSize = 11.sp, color = FinalMuted)
                    }
                }
                Text(message, fontSize = 15.sp, color = Color(0xFF5E5A61), lineHeight = 22.sp)
                Surface(
                    onClick = onClick,
                    shape = RoundedCornerShape(50),
                    color = Color.White.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.9f)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("继续聊天", fontWeight = FontWeight.Bold, color = FinalInk)
                        Spacer(Modifier.width(5.dp))
                        Icon(Icons.Outlined.ArrowForward, null, tint = FinalInk, modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopLauncherGrid(
    launchers: List<FinalLauncher>,
    slots: Map<FinalRoute, Int>,
    onOpen: (FinalRoute) -> Unit,
    onMove: (FinalRoute, Int) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var draggingRoute by remember { mutableStateOf<FinalRoute?>(null) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val horizontalGap = 8.dp
        val verticalGap = 8.dp
        val cellWidth = (maxWidth - horizontalGap * (LauncherColumns - 1)) / LauncherColumns
        val cellHeight = 103.dp
        val rowCount = (LauncherSlotCount + LauncherColumns - 1) / LauncherColumns
        val totalHeight = cellHeight * rowCount + verticalGap * (rowCount - 1)
        val cellWidthPx = with(density) { cellWidth.toPx() }
        val cellHeightPx = with(density) { cellHeight.toPx() }
        val horizontalGapPx = with(density) { horizontalGap.toPx() }
        val verticalGapPx = with(density) { verticalGap.toPx() }
        val gridWidthPx = with(density) { maxWidth.toPx() }
        val gridHeightPx = with(density) { totalHeight.toPx() }

        Box(Modifier.fillMaxWidth().height(totalHeight)) {
            if (draggingRoute != null) {
                (0 until LauncherSlotCount).forEach { slot ->
                    val occupied = slots.any { (route, occupiedSlot) ->
                        route != draggingRoute && occupiedSlot == slot
                    }
                    if (!occupied) {
                        val column = slot % LauncherColumns
                        val row = slot / LauncherColumns
                        EmptyLauncherSlot(
                            modifier = Modifier
                                .offset(
                                    x = (cellWidth + horizontalGap) * column,
                                    y = (cellHeight + verticalGap) * row,
                                )
                                .width(cellWidth)
                                .height(cellHeight),
                        )
                    }
                }
            }

            launchers.forEach { launcher ->
                val slot = slots[launcher.route] ?: return@forEach
                val column = slot % LauncherColumns
                val row = slot / LauncherColumns
                val baseX = (cellWidth + horizontalGap) * column
                val baseY = (cellHeight + verticalGap) * row
                val baseXPx = with(density) { baseX.toPx() }
                val baseYPx = with(density) { baseY.toPx() }
                val isDragging = draggingRoute == launcher.route

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (baseXPx + if (isDragging) dragDelta.x else 0f).roundToInt(),
                                y = (baseYPx + if (isDragging) dragDelta.y else 0f).roundToInt(),
                            )
                        }
                        .width(cellWidth)
                        .height(cellHeight)
                        .zIndex(if (isDragging) 10f else 1f)
                        .graphicsLayer {
                            scaleX = if (isDragging) 1.08f else 1f
                            scaleY = if (isDragging) 1.08f else 1f
                            shadowElevation = if (isDragging) 18.dp.toPx() else 0f
                        }
                        .pointerInput(launcher.route, slot) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingRoute = launcher.route
                                    dragDelta = Offset.Zero
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragDelta += dragAmount
                                },
                                onDragCancel = {
                                    draggingRoute = null
                                    dragDelta = Offset.Zero
                                },
                                onDragEnd = {
                                    val centerX = baseXPx + dragDelta.x + cellWidthPx / 2f
                                    val centerY = baseYPx + dragDelta.y + cellHeightPx / 2f
                                    if (centerX in 0f..gridWidthPx && centerY in 0f..gridHeightPx) {
                                        val targetColumn = (centerX / (cellWidthPx + horizontalGapPx))
                                            .toInt()
                                            .coerceIn(0, LauncherColumns - 1)
                                        val targetRow = (centerY / (cellHeightPx + verticalGapPx))
                                            .toInt()
                                            .coerceIn(0, rowCount - 1)
                                        val targetSlot = targetRow * LauncherColumns + targetColumn
                                        if (targetSlot in 0 until LauncherSlotCount) onMove(launcher.route, targetSlot)
                                    }
                                    draggingRoute = null
                                    dragDelta = Offset.Zero
                                },
                            )
                        }
                        .clickable(enabled = draggingRoute == null) { onOpen(launcher.route) },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    LauncherTile(launcher, isDragging, cellWidth - 12.dp)
                }
            }
        }
    }
}

@Composable
private fun LauncherTile(
    launcher: FinalLauncher,
    isDragging: Boolean,
    maxIconSize: androidx.compose.ui.unit.Dp,
) {
    val iconSize = minOf(66.dp, maxIconSize)
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Box(
            modifier = Modifier
                .size(iconSize)
                .shadow(
                    elevation = if (isDragging) 12.dp else 5.dp,
                    shape = RoundedCornerShape(21.dp),
                    ambientColor = launcher.iconTint.copy(alpha = 0.18f),
                    spotColor = launcher.iconTint.copy(alpha = 0.16f),
                )
                .clip(RoundedCornerShape(21.dp))
                .background(Brush.linearGradient(listOf(launcher.tileStart, launcher.tileEnd)))
                .border(1.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(21.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 7.dp, y = 6.dp)
                    .size(iconSize * 0.46f)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.22f)),
            )
            Icon(launcher.icon, launcher.title, tint = launcher.iconTint, modifier = Modifier.size(iconSize * 0.43f))
        }
        Spacer(Modifier.height(8.dp))
        Text(launcher.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = FinalInk, maxLines = 1)
    }
}

@Composable
private fun EmptyLauncherSlot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(Color.White.copy(alpha = 0.22f))
            .border(1.dp, FinalMuted.copy(alpha = 0.22f), RoundedCornerShape(23.dp)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(66.dp)
                .clip(RoundedCornerShape(21.dp))
                .background(Color.White.copy(alpha = 0.2f)),
        )
    }
}

@Composable
private fun FinalChatHub(
    onBack: () -> Unit,
    onOpenConversation: () -> Unit,
    onCharacterSettings: () -> Unit,
) {
    val conversations by MigratedDomainStores.chat.conversations.collectAsState()
    Scaffold(
        containerColor = FinalPaper,
        topBar = { FinalTopBar("聊天", onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                FinalCardBox(Modifier.clickable(onClick = onCharacterSettings)) {
                    Text("角色设置", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("主动联系、来电、勿扰、世界书与角色设定。", color = FinalMuted)
                }
            }
            items(conversations, key = { it.id }) { conversation ->
                FinalCardBox(
                    Modifier.clickable {
                        MigratedDomainStores.chat.markConversationRead(conversation.id)
                        onOpenConversation()
                    },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = Color(0xFFFFE2D7), modifier = Modifier.size(48.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("露", fontWeight = FontWeight.Bold, color = FinalInk)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(conversation.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(conversation.lastMessage, color = FinalMuted, maxLines = 1)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = FinalMuted)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinalChatDetail(onBack: () -> Unit) {
    val conversationId = "lulu-main"
    val messages by MigratedDomainStores.chat.messages(conversationId).collectAsState()
    val library by LuluAiServices.connectionStore.library.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var archiveMenuExpanded by remember { mutableStateOf(false) }

    val activeArchive = library.archives.firstOrNull { it.id == library.activeArchiveId }
    val activeLabel = activeArchive?.let(LuluAiServices.connectionStore::archiveLabel) ?: "未选择模型"

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    fun sendMessage() {
        val text = input.trim()
        if (text.isBlank() || sending) return
        if (activeArchive == null) {
            scope.launch { snackbarHostState.showSnackbar("请先到设置中把模型加入存档") }
            return
        }
        val history = messages.takeLast(30).joinToString("\n") { message ->
            val role = if (message.sender == LuluChatMessage.Sender.User) "主人" else "露露"
            "$role：${message.content}"
        }
        MigratedDomainStores.chat.sendUserMessage(conversationId, text)
        input = ""
        sending = true
        scope.launch {
            LuluAiServices.gateway.generate(
                characterId = "lulu",
                facts = buildString {
                    if (history.isNotBlank()) {
                        appendLine("最近对话：")
                        appendLine(history)
                    }
                    appendLine("主人刚刚说：$text")
                },
                instruction = "延续当前对话，以角色本人的口吻自然回复主人。不要复述系统提示。",
                source = "聊天",
                title = activeLabel,
                temperature = 0.85,
                maxTokens = 1200,
            ).onSuccess { reply ->
                MigratedDomainStores.chat.appendCharacterMessage(conversationId, reply.text)
            }.onFailure { error ->
                snackbarHostState.showSnackbar(error.message ?: "模型回复失败")
            }
            sending = false
        }
    }

    Scaffold(
        containerColor = FinalPaper,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("露露", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(activeLabel, color = FinalMuted, fontSize = 10.sp, maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
                },
                actions = {
                    Box {
                        TextButton(onClick = { archiveMenuExpanded = true }) {
                            Icon(Icons.Outlined.SwapHoriz, null)
                            Spacer(Modifier.width(3.dp))
                            Text("切换")
                        }
                        DropdownMenu(
                            expanded = archiveMenuExpanded,
                            onDismissRequest = { archiveMenuExpanded = false },
                        ) {
                            if (library.archives.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("暂无模型存档") },
                                    onClick = { archiveMenuExpanded = false },
                                    enabled = false,
                                )
                            } else {
                                library.archives.forEach { archive ->
                                    ModelArchiveMenuItem(
                                        archive = archive,
                                        selected = archive.id == library.activeArchiveId,
                                        onClick = {
                                            LuluAiServices.connectionStore.selectArchive(archive.id)
                                            archiveMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = FinalPaper),
            )
        },
        bottomBar = {
            Surface(color = Color(0xFFFFFBF3), shadowElevation = 8.dp) {
                Column(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(13.dp),
                        color = if (activeArchive == null) Color(0xFFF5E3DF) else Color(0xFFEAE2F4),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (activeArchive == null) Icons.Outlined.WarningAmber else Icons.Outlined.Memory,
                                null,
                                modifier = Modifier.size(17.dp),
                                tint = FinalMuted,
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(activeLabel, modifier = Modifier.weight(1f), fontSize = 12.sp, color = FinalMuted)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("和露露说点什么…") },
                            maxLines = 4,
                            shape = RoundedCornerShape(20.dp),
                        )
                        FilledIconButton(
                            enabled = input.isNotBlank() && !sending && activeArchive != null,
                            onClick = ::sendMessage,
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = FinalWheat),
                        ) {
                            if (sending) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = FinalInk)
                            } else {
                                Icon(Icons.Outlined.Send, "发送", tint = FinalInk)
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
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(messages, key = { it.id }) { message -> FinalMessageBubble(message) }
            if (sending) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("露露正在回复…", color = FinalMuted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelArchiveMenuItem(
    archive: ModelArchive,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(LuluAiServices.connectionStore.archiveLabel(archive), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                if (selected) Text("当前使用", color = FinalMuted, fontSize = 11.sp)
            }
        },
        leadingIcon = {
            Icon(if (selected) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked, null)
        },
        onClick = onClick,
    )
}

@Composable
private fun FinalMessageBubble(message: LuluChatMessage) {
    val fromUser = message.sender == LuluChatMessage.Sender.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!fromUser) {
            Surface(shape = CircleShape, color = Color(0xFFFFE2D7), modifier = Modifier.size(34.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("露", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start) {
            Surface(
                color = if (fromUser) FinalWheat else Color(0xFFFFFBF3),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, FinalBorder),
            ) {
                Text(message.content, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
            Text(
                message.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")),
                color = FinalMuted,
                fontSize = 10.sp,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinalTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = FinalPaper),
    )
}

@Composable
private fun FinalEmpty(title: String, subtitle: String, onBack: () -> Unit) {
    Scaffold(containerColor = FinalPaper, topBar = { FinalTopBar(title, onBack) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            FinalCardBox(Modifier.padding(18.dp)) {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = FinalMuted)
            }
        }
    }
}

@Composable
private fun FinalCardBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FinalCard),
        border = BorderStroke(1.dp, FinalBorder),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            content = content,
        )
    }
}
