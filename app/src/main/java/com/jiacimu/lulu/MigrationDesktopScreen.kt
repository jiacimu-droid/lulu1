package com.jiacimu.lulu

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlin.math.roundToInt

internal val MigrationPaper = Color(0xFFFFFCF5)
internal val MigrationCard = Color(0xEFFFFFF8)
internal val MigrationWheat = Color(0xFFF2CF70)
internal val MigrationMuted = Color(0xFF747887)
internal val MigrationInk = Color(0xFF2F2B2A)
private val MigrationRose = Color(0xFFF6D9D0)
private val MigrationLavender = Color(0xFFE7E1F6)

private const val MigrationLauncherColumns = 4
private const val MigrationLauncherSlotCount = 12

internal enum class MigrationRoute {
    Home,
    Chat,
    ChatDetail,
    CharacterSettings,
    Memory,
    Lexicon,
    WorldBook,
    Performance,
    Reading,
    Wishes,
    Study,
    Games,
    Settings,
}

private data class MigrationLauncher(
    val title: String,
    val icon: ImageVector,
    val route: MigrationRoute,
    val tileStart: Color,
    val tileEnd: Color,
    val iconTint: Color,
)

private val MigrationLaunchers = listOf(
    MigrationLauncher("聊天", Icons.Outlined.ChatBubbleOutline, MigrationRoute.Chat, Color(0xFFFFE8D9), Color(0xFFF9CFC2), Color(0xFF7D5147)),
    MigrationLauncher("记忆", Icons.Outlined.Psychology, MigrationRoute.Memory, Color(0xFFECE7FA), Color(0xFFDCD1F2), Color(0xFF5E567C)),
    MigrationLauncher("辞海", Icons.Outlined.MenuBook, MigrationRoute.Lexicon, Color(0xFFE8F1E4), Color(0xFFD3E4CE), Color(0xFF4F6B55)),
    MigrationLauncher("世界书", Icons.Outlined.Public, MigrationRoute.WorldBook, Color(0xFFE5F0F6), Color(0xFFD0E2EC), Color(0xFF4C6574)),
    MigrationLauncher("性能监测", Icons.Outlined.MonitorHeart, MigrationRoute.Performance, Color(0xFFFFE8E4), Color(0xFFF4D1CB), Color(0xFF80554E)),
    MigrationLauncher("阅读", Icons.Outlined.AutoStories, MigrationRoute.Reading, Color(0xFFFFEFD5), Color(0xFFF5DDAF), Color(0xFF775F37)),
    MigrationLauncher("心愿馆", Icons.Outlined.StarOutline, MigrationRoute.Wishes, Color(0xFFF4E6F3), Color(0xFFE6D0E5), Color(0xFF735A72)),
    MigrationLauncher("考研", Icons.Outlined.School, MigrationRoute.Study, Color(0xFFE2EEF0), Color(0xFFCEE0E2), Color(0xFF4B686B)),
    MigrationLauncher("游戏", Icons.Outlined.SportsEsports, MigrationRoute.Games, Color(0xFFE9E4F7), Color(0xFFD8CEF0), Color(0xFF5F567B)),
    MigrationLauncher("设置", Icons.Outlined.Settings, MigrationRoute.Settings, Color(0xFFEDEBE6), Color(0xFFDCD8D0), Color(0xFF605D58)),
)

@Composable
internal fun MigrationHome(
    onOpen: (MigrationRoute) -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("lulu_desktop_layout", Context.MODE_PRIVATE)
    }
    val launcherSlots = remember(preferences) {
        val occupied = mutableSetOf<Int>()
        mutableStateMapOf<MigrationRoute, Int>().apply {
            MigrationLaunchers.forEachIndexed { defaultSlot, launcher ->
                val savedSlot = preferences.getInt("slot_${launcher.route.name}", defaultSlot)
                val candidate = savedSlot.takeIf { it in 0 until MigrationLauncherSlotCount } ?: defaultSlot
                val resolved = candidate.takeIf { it !in occupied }
                    ?: (0 until MigrationLauncherSlotCount).first { it !in occupied }
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
                .background(MigrationRose.copy(alpha = 0.22f)),
        )
        Box(
            Modifier
                .offset(x = (-45).dp, y = 340.dp)
                .size(135.dp)
                .clip(CircleShape)
                .background(MigrationLavender.copy(alpha = 0.22f)),
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
                        color = MigrationInk,
                    )
                    Text("今天也慢慢来，露露会把每一件小事陪好。", fontSize = 14.sp, color = MigrationMuted)
                }
            }
            item {
                MigrationWelcomeCard(
                    title = latest?.title ?: "露露",
                    message = latest?.lastMessage.orEmpty().ifBlank { "今天也会一直陪着主人呀～" },
                    onClick = {
                        latest?.let { conversation -> onOpenConversation(conversation.id) }
                            ?: onOpen(MigrationRoute.Chat)
                    },
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("我的桌面", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MigrationInk)
                    Text("长按图标，可自由拖到空位", fontSize = 11.sp, color = MigrationMuted)
                }
            }
            item {
                MigrationLauncherGrid(
                    launchers = MigrationLaunchers,
                    slots = launcherSlots,
                    onOpen = onOpen,
                    onMove = { launcherRoute, targetSlot ->
                        val occupiedByAnother = launcherSlots.any { (otherRoute, slot) ->
                            otherRoute != launcherRoute && slot == targetSlot
                        }
                        if (!occupiedByAnother) {
                            launcherSlots[launcherRoute] = targetSlot
                            preferences.edit().putInt("slot_${launcherRoute.name}", targetSlot).apply()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun MigrationWelcomeCard(title: String, message: String, onClick: () -> Unit) {
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
                        Text(title, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = MigrationInk)
                        Text("在线陪伴中", fontSize = 11.sp, color = MigrationMuted)
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
                        Text("继续聊天", fontWeight = FontWeight.Bold, color = MigrationInk)
                        Spacer(Modifier.width(5.dp))
                        Icon(Icons.Outlined.ArrowForward, null, tint = MigrationInk, modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MigrationLauncherGrid(
    launchers: List<MigrationLauncher>,
    slots: Map<MigrationRoute, Int>,
    onOpen: (MigrationRoute) -> Unit,
    onMove: (MigrationRoute, Int) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var draggingRoute by remember { mutableStateOf<MigrationRoute?>(null) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val horizontalGap = 8.dp
        val verticalGap = 8.dp
        val cellWidth = (maxWidth - horizontalGap * (MigrationLauncherColumns - 1)) / MigrationLauncherColumns
        val cellHeight = 103.dp
        val rowCount = (MigrationLauncherSlotCount + MigrationLauncherColumns - 1) / MigrationLauncherColumns
        val totalHeight = cellHeight * rowCount + verticalGap * (rowCount - 1)
        val cellWidthPx = with(density) { cellWidth.toPx() }
        val cellHeightPx = with(density) { cellHeight.toPx() }
        val horizontalGapPx = with(density) { horizontalGap.toPx() }
        val verticalGapPx = with(density) { verticalGap.toPx() }
        val gridWidthPx = with(density) { maxWidth.toPx() }
        val gridHeightPx = with(density) { totalHeight.toPx() }

        Box(Modifier.fillMaxWidth().height(totalHeight)) {
            if (draggingRoute != null) {
                (0 until MigrationLauncherSlotCount).forEach { slot ->
                    val occupied = slots.any { (route, occupiedSlot) ->
                        route != draggingRoute && occupiedSlot == slot
                    }
                    if (!occupied) {
                        val column = slot % MigrationLauncherColumns
                        val row = slot / MigrationLauncherColumns
                        MigrationEmptyLauncherSlot(
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
                val column = slot % MigrationLauncherColumns
                val row = slot / MigrationLauncherColumns
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
                                            .coerceIn(0, MigrationLauncherColumns - 1)
                                        val targetRow = (centerY / (cellHeightPx + verticalGapPx))
                                            .toInt()
                                            .coerceIn(0, rowCount - 1)
                                        val targetSlot = targetRow * MigrationLauncherColumns + targetColumn
                                        if (targetSlot in 0 until MigrationLauncherSlotCount) onMove(launcher.route, targetSlot)
                                    }
                                    draggingRoute = null
                                    dragDelta = Offset.Zero
                                },
                            )
                        }
                        .clickable(enabled = draggingRoute == null) { onOpen(launcher.route) },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    MigrationLauncherTile(launcher, isDragging, cellWidth - 12.dp)
                }
            }
        }
    }
}

@Composable
private fun MigrationLauncherTile(
    launcher: MigrationLauncher,
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
        Text(launcher.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MigrationInk, maxLines = 1)
    }
}

@Composable
private fun MigrationEmptyLauncherSlot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(Color.White.copy(alpha = 0.22f))
            .border(1.dp, MigrationMuted.copy(alpha = 0.22f), RoundedCornerShape(23.dp)),
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
internal fun MigrationEmptyScreen(title: String, subtitle: String, onBack: () -> Unit) {
    Scaffold(
        containerColor = MigrationPaper,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MigrationPaper),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                colors = CardDefaults.cardColors(containerColor = MigrationCard),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = MigrationMuted)
                }
            }
        }
    }
}
