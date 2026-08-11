package com.jiacimu.lulu.study

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun StudyPlanScreen(state: StudyState, store: PostgraduateExamStore) {
    var range by remember { mutableStateOf(StudyPlanRange.Weekly) }
    var title by remember { mutableStateOf("") }
    val items = state.planItems.filter { it.range == range }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            StudyCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StudyPlanRange.entries.forEach { item ->
                        FilterChip(
                            selected = item == range,
                            onClick = { range = item },
                            label = { Text(if (item == StudyPlanRange.Weekly) "周计划" else "月计划") },
                        )
                    }
                }
            }
        }
        item {
            StudyCard {
                Text("添加${if (range == StudyPlanRange.Weekly) "周" else "月"}计划", fontWeight = FontWeight.Bold)
                OutlinedTextField(title, { title = it }, label = { Text("目标") }, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        store.addPlanItem(range, title, "")
                        title = ""
                    },
                    enabled = title.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存计划") }
            }
        }
        if (items.isEmpty()) item { StudyCard { Text("当前没有计划", color = StudyDesign.muted) } }
        items(items, key = { it.id }) { item ->
            StudyCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { store.deletePlanItem(item.id) }) { Icon(Icons.Outlined.DeleteOutline, "删除") }
                }
            }
        }
    }
}

@Composable
internal fun StudyGachaScreen(state: StudyState, store: PostgraduateExamStore) {
    var results by remember { mutableStateOf(emptyList<StudyDrawResult>()) }
    var message by remember { mutableStateOf("") }
    var revealing by remember { mutableStateOf(false) }
    var showingResults by remember { mutableStateOf(false) }

    fun draw(action: () -> List<StudyDrawResult>, insufficient: String) {
        val drawn = action()
        if (drawn.isEmpty()) {
            message = insufficient
        } else {
            results = drawn
            message = ""
            revealing = true
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(StudyDesign.paper, StudyDesign.wheatSoft.copy(alpha = .55f)))),
    ) {
        if (!showingResults) {
            CandyGachaCard(
                modifier = Modifier.fillMaxSize(),
                state = state,
                onSingle = {
                    draw(store::drawSingle, "需要1张单抽券或${SINGLE_DRAW_COST}夸夸值")
                },
                onTen = {
                    draw(store::drawTen, "需要1张十连券或${TEN_DRAW_COST}夸夸值")
                },
            )
            if (message.isNotBlank()) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(18.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) { Text(message, Modifier.padding(horizontal = 18.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onErrorContainer) }
            }
        } else {
            GachaResultScreen(results = results, onBack = { showingResults = false; results = emptyList() })
        }

        if (revealing) {
            GachaAnimationOverlay(
                results = results,
                onFinished = { revealing = false; showingResults = true },
            )
        }
    }
}

@Composable
private fun GachaAnimationOverlay(results: List<StudyDrawResult>, onFinished: () -> Unit) {
    val bestRarity = results.maxByOrNull { it.rarity.ordinal }?.rarity ?: StudyRarity.Normal
    val starRarities = remember(results) { results.map { it.rarity }.sortedByDescending { it.ordinal } }
    val revealDuration = when (bestRarity) {
        StudyRarity.Normal -> 1_250
        StudyRarity.Rare -> 1_550
        StudyRarity.Epic -> 1_850
        StudyRarity.Rainbow -> 2_250
    }
    var spinStarted by remember { mutableStateOf(false) }
    var revealStarted by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (spinStarted) 1_080f else -16f,
        animationSpec = tween(2_050),
        label = "抽卡公共旋转",
    )
    val coreScale by animateFloatAsState(
        targetValue = when {
            revealStarted -> .52f
            spinStarted -> 1.08f
            else -> .72f
        },
        animationSpec = tween(if (revealStarted) 360 else 2_050),
        label = "抽卡核心缩放",
    )
    val burstProgress by animateFloatAsState(
        targetValue = if (revealStarted) 1f else 0f,
        animationSpec = tween(revealDuration),
        label = "稀有度爆发",
    )

    LaunchedEffect(results) {
        spinStarted = true
        delay(2_100)
        revealStarted = true
        delay(revealDuration.toLong() + 650L)
        onFinished()
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val backdrop = if (!revealStarted) commonGachaBackground() else rarityBackground(bestRarity)
        Box(
            modifier = Modifier.fillMaxSize().background(backdrop),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val commonAlpha = 1f - burstProgress
                if (commonAlpha > .01f) {
                    drawCircle(
                        color = GachaBlue.copy(alpha = .12f * commonAlpha),
                        radius = size.minDimension * .47f,
                        center = center,
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = .08f * commonAlpha),
                        radius = size.minDimension * .33f,
                        center = center,
                        style = Stroke(1.5.dp.toPx()),
                    )
                    repeat(20) { index ->
                        val angle = index * (PI * 2 / 20) + rotation / 180f * PI
                        val radius = size.minDimension * if (index % 2 == 0) .31f else .37f
                        drawCircle(
                            color = GachaBlue.copy(alpha = (.38f + (index % 3) * .1f) * commonAlpha),
                            radius = (2.5f + index % 4).dp.toPx(),
                            center = Offset(
                                center.x + cos(angle).toFloat() * radius,
                                center.y + sin(angle).toFloat() * radius,
                            ),
                        )
                    }
                }

                if (burstProgress > .01f) {
                    drawRarityBurst(
                        center = center,
                        rarity = bestRarity,
                        progress = burstProgress,
                    )
                    starRarities.forEachIndexed { index, rarity ->
                        val starCenter = revealStarPosition(
                            center = center,
                            index = index,
                            total = starRarities.size,
                            radius = size.minDimension * .23f * burstProgress,
                        )
                        val baseRadius = when (rarity) {
                            StudyRarity.Normal -> 13.dp.toPx()
                            StudyRarity.Rare -> 18.dp.toPx()
                            StudyRarity.Epic -> 24.dp.toPx()
                            StudyRarity.Rainbow -> 30.dp.toPx()
                        }
                        drawGachaStar(
                            center = starCenter,
                            radius = baseRadius * burstProgress,
                            rarity = rarity,
                            rotationDegrees = rotation + index * 17f,
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .size(176.dp)
                    .graphicsLayer {
                        rotationZ = rotation
                        scaleX = coreScale
                        scaleY = coreScale
                        alpha = 1f - burstProgress
                    },
                shape = RoundedCornerShape(88.dp),
                color = Color(0xFFFDFEFF),
                border = BorderStroke(8.dp, Color(0xFFD7E9F7)),
                shadowElevation = 30.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = GachaBlue,
                            modifier = Modifier.size(62.dp),
                        )
                        Text(
                            if (results.size == 10) "十连愿望" else "愿望显现",
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF273449),
                        )
                    }
                }
            }

            if (revealStarted) {
                Column(
                    modifier = Modifier.align(Alignment.Center).offset(y = 190.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        rarityRevealTitle(bestRarity),
                        color = Color.White,
                        fontSize = when (bestRarity) {
                            StudyRarity.Normal -> 24.sp
                            StudyRarity.Rare -> 28.sp
                            StudyRarity.Epic -> 32.sp
                            StudyRarity.Rainbow -> 36.sp
                        },
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        rarityResultSummary(results),
                        color = Color.White.copy(alpha = .82f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Text(
                text = if (revealStarted) "愿望正在凝聚……" else "正在开启……",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 56.dp),
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
            )
        }
    }
}

private val GachaBlue = Color(0xFF76AEDA)
private val GachaPurple = Color(0xFFB486E6)
private val GachaGold = Color(0xFFFFC94F)
private val GachaRainbow = listOf(
    Color(0xFFFF76B7),
    Color(0xFFFFD45C),
    Color(0xFF7FE4CF),
    Color(0xFF72B8FF),
    Color(0xFFC78BFF),
)

private fun commonGachaBackground(): Brush = Brush.radialGradient(
    colors = listOf(Color(0xFF25344D), Color(0xFF111827), Color(0xFF070B13)),
)

private fun rarityBackground(rarity: StudyRarity): Brush = when (rarity) {
    StudyRarity.Normal -> Brush.radialGradient(listOf(Color(0xFF24476B), Color(0xFF0B1625), Color(0xFF050A12)))
    StudyRarity.Rare -> Brush.radialGradient(listOf(Color(0xFF5B3282), Color(0xFF241534), Color(0xFF090711)))
    StudyRarity.Epic -> Brush.radialGradient(listOf(Color(0xFF8A5A16), Color(0xFF35220A), Color(0xFF0E0A04)))
    StudyRarity.Rainbow -> Brush.radialGradient(listOf(Color(0xFF434277), Color(0xFF17142C), Color(0xFF080711)))
}

private fun DrawScope.drawRarityBurst(center: Offset, rarity: StudyRarity, progress: Float) {
    val palette = when (rarity) {
        StudyRarity.Normal -> listOf(GachaBlue, Color(0xFFBDE2FF))
        StudyRarity.Rare -> listOf(GachaPurple, Color(0xFFE2C6FF), Color(0xFF8E5AD1))
        StudyRarity.Epic -> listOf(GachaGold, Color(0xFFFFF0A6), Color(0xFFFF9E35))
        StudyRarity.Rainbow -> GachaRainbow
    }
    val rayCount = when (rarity) {
        StudyRarity.Normal -> 14
        StudyRarity.Rare -> 22
        StudyRarity.Epic -> 32
        StudyRarity.Rainbow -> 48
    }
    val ringCount = when (rarity) {
        StudyRarity.Normal -> 1
        StudyRarity.Rare -> 2
        StudyRarity.Epic -> 3
        StudyRarity.Rainbow -> 5
    }
    val maxRadius = size.minDimension * when (rarity) {
        StudyRarity.Normal -> .36f
        StudyRarity.Rare -> .43f
        StudyRarity.Epic -> .49f
        StudyRarity.Rainbow -> .58f
    }

    repeat(rayCount) { index ->
        val angle = index * (PI * 2 / rayCount)
        val startRadius = size.minDimension * .07f
        val endRadius = maxRadius * progress * (.76f + (index % 4) * .08f)
        val color = palette[index % palette.size]
        drawLine(
            color = color.copy(alpha = (.30f + (index % 3) * .12f) * progress),
            start = Offset(
                center.x + cos(angle).toFloat() * startRadius,
                center.y + sin(angle).toFloat() * startRadius,
            ),
            end = Offset(
                center.x + cos(angle).toFloat() * endRadius,
                center.y + sin(angle).toFloat() * endRadius,
            ),
            strokeWidth = when (rarity) {
                StudyRarity.Normal -> 1.5.dp.toPx()
                StudyRarity.Rare -> 2.dp.toPx()
                StudyRarity.Epic -> 3.dp.toPx()
                StudyRarity.Rainbow -> 3.5.dp.toPx()
            },
            cap = StrokeCap.Round,
        )
    }

    repeat(ringCount) { index ->
        val radius = maxRadius * progress * (.36f + index * .15f)
        drawCircle(
            color = palette[index % palette.size].copy(alpha = (.52f - index * .07f).coerceAtLeast(.16f) * progress),
            radius = radius,
            center = center,
            style = Stroke((1.5f + index * .8f).dp.toPx()),
        )
    }

    if (rarity == StudyRarity.Rainbow) {
        repeat(34) { index ->
            val angle = index * (PI * 2 / 34) + progress * PI
            val radius = maxRadius * (.32f + (index % 6) * .1f) * progress
            drawCircle(
                color = GachaRainbow[index % GachaRainbow.size].copy(alpha = .78f * progress),
                radius = (2f + index % 4).dp.toPx() * progress,
                center = Offset(
                    center.x + cos(angle).toFloat() * radius,
                    center.y + sin(angle).toFloat() * radius,
                ),
            )
        }
    }
}

private fun revealStarPosition(center: Offset, index: Int, total: Int, radius: Float): Offset {
    if (index == 0 || total <= 1) return center
    val orbitCount = (total - 1).coerceAtLeast(1)
    val angle = -PI / 2 + (index - 1) * (PI * 2 / orbitCount)
    return Offset(
        center.x + cos(angle).toFloat() * radius,
        center.y + sin(angle).toFloat() * radius,
    )
}

private fun DrawScope.drawGachaStar(
    center: Offset,
    radius: Float,
    rarity: StudyRarity,
    rotationDegrees: Float,
) {
    if (radius <= 0f) return
    val path = Path()
    repeat(10) { point ->
        val pointRadius = if (point % 2 == 0) radius else radius * .43f
        val angle = -PI / 2 + point * PI / 5 + rotationDegrees / 180f * PI
        val x = center.x + cos(angle).toFloat() * pointRadius
        val y = center.y + sin(angle).toFloat() * pointRadius
        if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()

    if (rarity == StudyRarity.Rainbow) {
        drawPath(path = path, brush = Brush.sweepGradient(GachaRainbow, center))
    } else {
        drawPath(path = path, color = rarityAccentColor(rarity))
    }
    drawPath(
        path = path,
        color = Color.White.copy(alpha = .88f),
        style = Stroke((radius * .08f).coerceAtLeast(1.2.dp.toPx())),
    )
    drawCircle(
        color = Color.White.copy(alpha = .72f),
        radius = radius * .13f,
        center = Offset(center.x - radius * .18f, center.y - radius * .2f),
    )
}

private fun rarityAccentColor(rarity: StudyRarity): Color = when (rarity) {
    StudyRarity.Normal -> GachaBlue
    StudyRarity.Rare -> GachaPurple
    StudyRarity.Epic -> GachaGold
    StudyRarity.Rainbow -> Color.White
}

private fun rarityRevealTitle(rarity: StudyRarity): String = when (rarity) {
    StudyRarity.Normal -> "蓝色愿望"
    StudyRarity.Rare -> "紫色显现"
    StudyRarity.Epic -> "金色降临"
    StudyRarity.Rainbow -> "彩色奇迹降临"
}

private fun rarityResultSummary(results: List<StudyDrawResult>): String {
    val rainbow = results.count { it.rarity == StudyRarity.Rainbow }
    val epic = results.count { it.rarity == StudyRarity.Epic }
    val rare = results.count { it.rarity == StudyRarity.Rare }
    val normal = results.count { it.rarity == StudyRarity.Normal }
    return buildList {
        if (rainbow > 0) add("$rainbow 彩")
        if (epic > 0) add("$epic 金")
        if (rare > 0) add("$rare 紫")
        if (normal > 0) add("$normal 蓝")
    }.joinToString(" · ")
}

@Composable
private fun GachaResultScreen(results: List<StudyDrawResult>, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(
                items = results.chunked(2),
                key = { row -> row.joinToString("|") { it.id } },
            ) { row ->
                if (row.size == 1) {
                    GachaResultCard(result = row.first(), modifier = Modifier.fillMaxWidth())
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEach { result ->
                            GachaResultCard(result = result, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        Surface(
            color = StudyDesign.paper,
            tonalElevation = 5.dp,
            shadowElevation = 10.dp,
        ) {
            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = StudyDesign.wheat,
                    contentColor = StudyDesign.ink,
                ),
            ) {
                Text("收下结果", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun GachaResultCard(result: StudyDrawResult, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = Color.White,
        border = BorderStroke(1.dp, rarityColor(result.rarity)),
        shadowElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 15.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = rarityColor(result.rarity),
                modifier = Modifier.size(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = StudyDesign.ink)
                }
            }
            Text(
                text = result.title,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                text = result.rarity.label,
                color = StudyDesign.muted,
                fontSize = 12.sp,
            )
            if (!result.inventoryChanged) {
                Text(
                    text = "已集满，本次不重复增加",
                    color = StudyDesign.muted,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun CandyGachaCard(modifier: Modifier = Modifier, state: StudyState, onSingle: () -> Unit, onTen: () -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("学习扭蛋机", fontSize = 27.sp, fontWeight = FontWeight.Black, color = StudyDesign.ink)
            Spacer(Modifier.height(13.dp))
            Surface(
                color = StudyDesign.card,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, StudyDesign.border),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    GachaBalance("单抽券", state.inventory.singleTickets)
                    GachaBalance("十连券", state.inventory.tenTickets)
                    GachaBalance("夸夸值", state.profile.praisePoints)
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(310.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val centerX = size.width / 2f
                    val domeCenter = Offset(centerX, size.height * .34f)
                    val domeRadius = size.minDimension * .29f
                    val bodyLeft = size.width * .19f
                    val bodyTop = size.height * .52f
                    val bodyWidth = size.width * .62f
                    val bodyHeight = size.height * .38f

                    drawRoundRect(
                        color = Color(0x22000000),
                        topLeft = Offset(bodyLeft + 8.dp.toPx(), bodyTop + 12.dp.toPx()),
                        size = Size(bodyWidth, bodyHeight),
                        cornerRadius = CornerRadius(30.dp.toPx()),
                    )
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(Color(0xFFF5D77A), Color(0xFFE7B84C))),
                        topLeft = Offset(bodyLeft, bodyTop),
                        size = Size(bodyWidth, bodyHeight),
                        cornerRadius = CornerRadius(30.dp.toPx()),
                    )
                    drawRoundRect(
                        color = Color(0xFFFFF5CF),
                        topLeft = Offset(bodyLeft + 13.dp.toPx(), bodyTop + 13.dp.toPx()),
                        size = Size(bodyWidth - 26.dp.toPx(), bodyHeight - 26.dp.toPx()),
                        cornerRadius = CornerRadius(22.dp.toPx()),
                    )

                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFFFFFFFF), Color(0xFFDCECF7), Color(0xFFB8D0E2)),
                            center = Offset(domeCenter.x - domeRadius * .3f, domeCenter.y - domeRadius * .38f),
                            radius = domeRadius * 1.35f,
                        ),
                        radius = domeRadius,
                        center = domeCenter,
                    )
                    drawCircle(
                        color = Color(0xFF829CB0),
                        radius = domeRadius,
                        center = domeCenter,
                        style = Stroke(4.dp.toPx()),
                    )
                    drawArc(
                        color = Color.White.copy(alpha = .82f),
                        startAngle = 205f,
                        sweepAngle = 96f,
                        useCenter = false,
                        topLeft = Offset(domeCenter.x - domeRadius * .75f, domeCenter.y - domeRadius * .75f),
                        size = Size(domeRadius * 1.5f, domeRadius * 1.5f),
                        style = Stroke(5.dp.toPx(), cap = StrokeCap.Round),
                    )

                    val capsules = listOf(
                        Triple(-.48f, .28f, Color(0xFF9FC7E4)),
                        Triple(-.16f, .42f, Color(0xFFCBA8EA)),
                        Triple(.22f, .34f, Color(0xFFF7D476)),
                        Triple(.46f, .05f, Color(0xFF92DCCF)),
                        Triple(-.33f, -.02f, Color(0xFFF0A9C5)),
                        Triple(.03f, .02f, Color(0xFF9FC7E4)),
                    )
                    capsules.forEachIndexed { index, (xFactor, yFactor, color) ->
                        val capsuleCenter = Offset(
                            domeCenter.x + domeRadius * xFactor,
                            domeCenter.y + domeRadius * yFactor,
                        )
                        val capsuleRadius = (16 + index % 3 * 2).dp.toPx()
                        drawCircle(color = color, radius = capsuleRadius, center = capsuleCenter)
                        drawLine(
                            color = Color.White.copy(alpha = .78f),
                            start = Offset(capsuleCenter.x - capsuleRadius * .8f, capsuleCenter.y),
                            end = Offset(capsuleCenter.x + capsuleRadius * .8f, capsuleCenter.y),
                            strokeWidth = 2.dp.toPx(),
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = .72f),
                            radius = capsuleRadius * .2f,
                            center = Offset(capsuleCenter.x - capsuleRadius * .3f, capsuleCenter.y - capsuleRadius * .34f),
                        )
                    }

                    drawRoundRect(
                        color = Color(0xFF243246),
                        topLeft = Offset(size.width * .34f, size.height * .69f),
                        size = Size(size.width * .32f, size.height * .105f),
                        cornerRadius = CornerRadius(13.dp.toPx()),
                    )
                    drawRoundRect(
                        color = Color(0xFF60788C),
                        topLeft = Offset(size.width * .37f, size.height * .705f),
                        size = Size(size.width * .26f, size.height * .055f),
                        cornerRadius = CornerRadius(8.dp.toPx()),
                    )
                    drawCircle(
                        color = Color(0xFF27364A),
                        radius = 23.dp.toPx(),
                        center = Offset(size.width * .72f, size.height * .62f),
                    )
                    drawCircle(
                        color = Color(0xFFF9F1D5),
                        radius = 11.dp.toPx(),
                        center = Offset(size.width * .72f, size.height * .62f),
                    )
                    drawLine(
                        color = Color(0xFF27364A),
                        start = Offset(size.width * .72f, size.height * .62f),
                        end = Offset(size.width * .79f, size.height * .67f),
                        strokeWidth = 8.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawRoundRect(
                        color = Color(0xFF27364A),
                        topLeft = Offset(size.width * .25f, size.height * .58f),
                        size = Size(size.width * .16f, 10.dp.toPx()),
                        cornerRadius = CornerRadius(5.dp.toPx()),
                    )
                    drawRoundRect(
                        color = Color(0xFFB6882F),
                        topLeft = Offset(size.width * .25f, size.height * .87f),
                        size = Size(size.width * .12f, 12.dp.toPx()),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                    )
                    drawRoundRect(
                        color = Color(0xFFB6882F),
                        topLeft = Offset(size.width * .63f, size.height * .87f),
                        size = Size(size.width * .12f, 12.dp.toPx()),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                    )
                }
                Surface(
                    modifier = Modifier.align(Alignment.Center).offset(y = 72.dp),
                    color = Color(0xFF2A394E),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        "愿望补给站",
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onSingle, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, StudyDesign.wheat)) {
                    Text("单抽", color = StudyDesign.ink, fontWeight = FontWeight.Bold)
                }
                Button(onClick = onTen, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = StudyDesign.wheat, contentColor = StudyDesign.ink)) {
                    Text("十连抽", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun GachaBalance(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), fontSize = 22.sp, fontWeight = FontWeight.Black, color = StudyDesign.ink)
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = StudyDesign.muted)
    }
}

private fun rarityColor(rarity: StudyRarity): Color = when (rarity) {
    StudyRarity.Normal -> Color(0xFFDCEAF4)
    StudyRarity.Rare -> Color(0xFFE8DDF2)
    StudyRarity.Epic -> Color(0xFFFFEDB8)
    StudyRarity.Rainbow -> Color(0xFFD8F3EF)
}

private data class CollectionTicket(val title: String, val amount: Int, val use: () -> Unit)

@Composable
internal fun StudyCollectionScreen(
    state: StudyState,
    store: PostgraduateExamStore,
    onOpenProbabilityDesign: () -> Unit,
) {
    var message by remember { mutableStateOf("") }
    val builtInTickets = listOf(
        CollectionTicket("抖音时长券 · 20分钟", state.inventory.douyinTickets) { message = store.redeemEntertainment(StudyEntertainmentKind.Douyin) },
        CollectionTicket("游戏局数券 · 4局", state.inventory.gameRoundTickets) { message = store.redeemEntertainment(StudyEntertainmentKind.GameRound) },
        CollectionTicket("小剧场券", state.inventory.theaterFragments) {
            message = store.redeemEntertainment(StudyEntertainmentKind.Theater)
        },
        CollectionTicket("电影券 · 1部", state.inventory.gameTickets) { message = store.redeemEntertainment(StudyEntertainmentKind.Game) },
        CollectionTicket("影视剧一季兑换券", state.inventory.animeTickets) { message = store.redeemEntertainment(StudyEntertainmentKind.Anime) },
    )
    val customTickets = state.gachaRules.filter(StudyGachaRule::custom).map { rule ->
        CollectionTicket(rule.title, state.inventory.customRewards[rule.id] ?: 0) {
            message = store.redeemCustomReward(rule.id)
        }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "collection-first-ticket") {
            CollectionTicketCard(
                ticket = builtInTickets.first(),
                topRightActionLabel = "概率设计",
                onTopRightAction = onOpenProbabilityDesign,
            )
        }
        items(builtInTickets.drop(1), key = { it.title }) { CollectionTicketCard(it) }
        if (customTickets.isNotEmpty()) {
            item(key = "custom-rewards-title") {
                Text("自定义奖励", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
            items(customTickets, key = { it.title }) { CollectionTicketCard(it) }
        }
        item { Text("画卷碎片", fontSize = 19.sp, fontWeight = FontWeight.Bold) }
        items(blueFragmentCatalog.chunked(2)) { titles ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                titles.forEach { title -> FragmentProgressCard(title, state.inventory.blueFragments[title] ?: 0, Modifier.weight(1f)) }
                if (titles.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item { StudyMessage(message, message.contains("不足") || message.contains("失败")) }
    }
}

@Composable
private fun CollectionTicketCard(
    ticket: CollectionTicket,
    modifier: Modifier = Modifier,
    topRightActionLabel: String? = null,
    onTopRightAction: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = StudyDesign.card,
        border = BorderStroke(1.dp, StudyDesign.border),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 15.dp, end = 10.dp, top = 9.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(ticket.title, fontWeight = FontWeight.Bold)
                Text("拥有 ${ticket.amount}", color = StudyDesign.muted, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!topRightActionLabel.isNullOrBlank() && onTopRightAction != null) {
                    TextButton(
                        onClick = onTopRightAction,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Icon(Icons.Outlined.Tune, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(topRightActionLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Button(
                    onClick = ticket.use,
                    enabled = ticket.amount > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = StudyDesign.wheat, contentColor = StudyDesign.ink),
                    contentPadding = PaddingValues(horizontal = 15.dp, vertical = 7.dp),
                ) { Text("使用") }
            }
        }
    }
}

@Composable
private fun FragmentProgressCard(title: String, amount: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = StudyDesign.card,
        border = BorderStroke(1.dp, StudyDesign.border),
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2, minLines = 2)
            Text("$amount/$BLUE_FRAGMENTS_PER_SCROLL", color = StudyDesign.muted, fontSize = 12.sp)
            StudyProgress(amount.toFloat() / BLUE_FRAGMENTS_PER_SCROLL)
        }
    }
}

@Composable
internal fun StudyAchievementsScreen(state: StudyState, store: PostgraduateExamStore) {
    var message by remember { mutableStateOf("") }
    val unlocked = state.achievements.count { it.unlocked }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            StudyCard {
                Text("成就", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("已解锁 $unlocked/${state.achievements.size}", color = StudyDesign.muted)
                StudyProgress(if (state.achievements.isEmpty()) 0f else unlocked.toFloat() / state.achievements.size)
            }
        }
        items(state.achievements, key = { it.id }) { achievement ->
            StudyCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.EmojiEvents, null, tint = if (achievement.unlocked) StudyDesign.wheat else StudyDesign.muted)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(achievement.title, fontWeight = FontWeight.Bold)
                        Text(achievement.description, color = StudyDesign.muted)
                    }
                    Text("${achievement.progress.coerceAtMost(achievement.target)}/${achievement.target}")
                }
                StudyProgress(achievement.progress.toFloat() / achievement.target.coerceAtLeast(1))
                Button(
                    onClick = { message = store.claimAchievement(achievement.id) },
                    enabled = achievement.unlocked && !achievement.claimed,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (achievement.claimed) "已领取" else if (achievement.unlocked) "领取奖励" else "尚未解锁") }
            }
        }
        item { StudyMessage(message) }
    }
}

@Composable
internal fun StudyShopScreen(state: StudyState, store: PostgraduateExamStore) {
    var message by remember { mutableStateOf("") }
    val canRefresh = state.manualShopRefreshDate != state.activeDate
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            StudyCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("神秘商店", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("夸夸值：${state.profile.praisePoints}", color = StudyDesign.muted)
                    }
                    IconButton(onClick = { message = store.refreshShop() }, enabled = canRefresh) { Icon(Icons.Outlined.Refresh, "刷新") }
                }
                Text("每天自动刷新3件商品；手动刷新每天最多一次。", color = StudyDesign.muted, fontSize = 12.sp)
            }
        }
        items(state.shopItems, key = { it.id }) { item ->
            StudyCard {
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(item.subtitle, color = StudyDesign.muted)
                    }
                    Text("${item.cost} 夸夸值", fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { message = store.buyShopItem(item.id) },
                    enabled = !item.purchased && state.profile.praisePoints >= item.cost,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (item.purchased) "已购买" else "购买") }
            }
        }
        item { StudyMessage(message, message.contains("不足") || message.contains("失败")) }
    }
}

@Composable
internal fun StudyGuideScreen() {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            StudyCard {
                Text("考研 App 说明", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("当前奖励、抽卡、收藏和番茄钟规则。", color = StudyDesign.muted)
            }
        }
        item { GuideCard("夸夸值", "签到、完成待办和学习时长都会获得夸夸值。累计学习每满5分钟获得100夸夸值，不足部分跨番茄保留。") }
        item { GuideCard("抽卡概率", "默认蓝色画卷93.3%；紫色5.5%（抖音券2.5%、游戏局数券2%、小剧场券1%，抽中小剧场一次发3张）；金色电影券0.8%；彩色影视剧一季兑换券0.4%。收藏页第一张券右上角的“概率设计”可以修改紫／金／彩项目的概率、单次数量并添加自定义项目；蓝色自动使用剩余概率。") }
        item { GuideCard("保底", "连续30抽没有紫／金／彩时，第30抽直接出现紫色结果；紫色保底按当前概率设计中的紫色项目权重抽取。") }
        item { GuideCard("画卷碎片", "每套画卷需要10枚自己的专属碎片。已满后仍显示本次抽中物，但不重复计入。") }
        item { GuideCard("收藏", "抽到的抖音券、游戏局数券、小剧场券、电影券、影视剧一季兑换券，以及你在概率设计里新增的自定义项目都会进入收藏。") }
        item { GuideCard("商店", "商店使用夸夸值，每日展示3件商品，手动刷新每天最多一次。") }
        item { GuideCard("番茄钟", "番茄钟提供云雾原版和深夜墨蓝两套配色，支持自定义时长、提前结束按实际分钟结算、角色语音和专注中聊天。") }
    }
}

@Composable
private fun GuideCard(title: String, content: String) {
    StudyCard {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(content, color = StudyDesign.muted)
    }
}
