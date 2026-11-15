package com.jiacimu.lulu.games

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** One intentional gesture equals at most one persisted game turn. */
internal object RelicChestSwipeGate {
    fun released(distancePx: Float, travelPx: Float): Boolean =
        travelPx > 0f && distancePx >= travelPx * .83f
}

/** Full-screen, 2.5D visual inspection of a real chest; never rolls or awards loot here. */
@Composable
internal fun RelicChestInspection(
    run: RelicRun,
    chest: RelicChest,
    onPry: () -> Unit,
    onExit: () -> Unit,
) {
    BackHandler(onBack = onExit)
    val progress = run.chestProgress[chest.id] ?: 0
    val steps = RelicEscapeRules.openSteps(chest)
    val fraction = progress.toFloat() / steps.coerceAtLeast(1)
    val observedGrade = if (progress > 0 && progress >= steps / 2)
        RelicEscapeRules.openingGlowGrade(run, chest) else chest.visibleGrade
    val accent = relicGradeColor(observedGrade)
    var yaw by remember(run.id, chest.id) { mutableFloatStateOf(-22f) }
    var slide by remember(run.id, chest.id, progress) { mutableFloatStateOf(0f) }
    val pryNow by rememberUpdatedState(onPry)

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0B121C))) {
        val compact = maxHeight < 330.dp
        Column(
            Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = if (compact) 6.dp else 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onExit) {
                    Text("‹ 返回遗迹", color = Color(0xFFE0E5EA), fontSize = 13.sp)
                }
                Spacer(Modifier.weight(1f))
                Text("宝箱检视", color = Color(0xFF93A6B7), fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text("◇ ${progress}/${steps}", color = accent, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold)
            }
            Text(chest.tier.label + "宝箱 · " + RelicQuality.at(observedGrade).label,
                color = accent, fontSize = if (compact) 15.sp else 19.sp,
                fontWeight = FontWeight.Bold)
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .pointerInput(run.id, chest.id) {
                        detectHorizontalDragGestures { change, dx ->
                            change.consume()
                            yaw = (yaw + dx * .33f).coerceIn(-58f, 58f)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val scale = minOf(size.height * .67f, size.width * .46f)
                    val cx = size.width / 2f
                    val cy = size.height * .52f
                    val radians = yaw * (kotlin.math.PI.toFloat() / 180f)
                    val frontWidth = scale * 1.23f * cos(radians).coerceAtLeast(.52f)
                    val sideWidth = scale * .33f * abs(sin(radians))
                    val left = cx - frontWidth / 2f
                    val top = cy - scale * .12f
                    val height = scale * .49f
                    drawRect(Brush.verticalGradient(listOf(Color(0xFF172331), Color(0xFF0B121C))))
                    drawOval(Color.Black.copy(alpha = .48f),
                        Offset(cx - scale * .85f, cy + scale * .42f),
                        Size(scale * 1.7f, scale * .18f))
                    if (sideWidth > 1f) {
                        val edge = if (yaw >= 0f) left + frontWidth else left
                        val side = if (yaw >= 0f) sideWidth else -sideWidth
                        val sideFace = Path().apply {
                            moveTo(edge, top)
                            lineTo(edge + side, top - scale * .11f)
                            lineTo(edge + side, top + height - scale * .11f)
                            lineTo(edge, top + height)
                            close()
                        }
                        drawPath(sideFace, Color(0xFF4C3F3C))
                        drawLine(accent.copy(alpha = .46f),
                            Offset(edge + side * .52f, top - scale * .05f),
                            Offset(edge + side * .52f, top + height - scale * .05f),
                            strokeWidth = scale * .024f)
                    }
                    drawRoundRect(Color(0xFF5E4E49), Offset(left, top),
                        Size(frontWidth, height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(scale * .047f))
                    drawRoundRect(Color(0xFF8B7867), Offset(left + scale * .055f, top + scale * .045f),
                        Size(frontWidth - scale * .11f, height - scale * .095f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(scale * .036f))
                    drawLine(Color(0xFFD1B99E), Offset(left, top + scale * .09f),
                        Offset(left + frontWidth, top + scale * .09f), strokeWidth = scale * .024f)
                    rotate(-12f - fraction * 36f, pivot = Offset(cx, top)) {
                        drawRoundRect(Color(0xFF423939),
                            Offset(left - scale * .035f, top - scale * .20f),
                            Size(frontWidth + scale * .07f, scale * .20f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(scale * .045f))
                        drawLine(Color(0xFFC5AB8E),
                            Offset(left - scale * .02f, top - scale * .17f),
                            Offset(left + frontWidth + scale * .02f, top - scale * .17f),
                            strokeWidth = scale * .027f)
                    }
                    val lock = Offset(cx, top + height * .42f)
                    drawRoundRect(Color(0xFF241F22),
                        Offset(lock.x - scale * .10f, lock.y - scale * .10f),
                        Size(scale * .20f, scale * .24f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(scale * .024f))
                    drawCircle(accent.copy(alpha = .30f + fraction * .65f),
                        radius = scale * .056f, center = lock)
                    drawCircle(Color(0xFFE9DCC8), radius = scale * .024f, center = lock)
                    for (step in 0 until steps) {
                        val x = cx + (step - (steps - 1) / 2f) * scale * .14f
                        drawLine(if (step < progress) accent else Color(0xFF3A4854),
                            Offset(x, top + height + scale * .04f),
                            Offset(x, top + height + scale * .10f),
                            strokeWidth = scale * .031f)
                    }
                }
                Text("左右拖动，观察箱体", color = Color(0xFFB1C0CB), fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp))
            }
            Text(
                if (progress == 0) "封印尚未松动 · 选择时机开锁"
                else if (observedGrade > chest.visibleGrade)
                    "锁芯里透出 ${RelicQuality.at(observedGrade).label} 光芒"
                else "已松开 ${progress} 道锁舌 · 仍须提防周围的敌人",
                color = Color(0xFFC9D4DE), fontSize = 11.sp,
            )
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(if (compact) 52.dp else 62.dp)
                    .background(Color(0xFF25313E), RoundedCornerShape(16.dp)),
            ) {
                val density = LocalDensity.current
                val travelPx = with(density) { (maxWidth - 58.dp).toPx().coerceAtLeast(1f) }
                Box(
                    Modifier.fillMaxSize().pointerInput(run.id, chest.id, progress, travelPx) {
                        detectDragGestures(
                            onDragStart = { slide = 0f },
                            onDragEnd = {
                                if (RelicChestSwipeGate.released(slide, travelPx)) pryNow()
                                slide = 0f
                            },
                            onDragCancel = { slide = 0f },
                        ) { change, distance ->
                            change.consume()
                            slide = (slide + distance.x).coerceIn(0f, travelPx)
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("向右推开第 ${progress + 1} 道锁舌",
                        color = Color(0xFFCAD3DA), fontSize = 12.sp,
                        fontWeight = FontWeight.Medium)
                    Box(
                        Modifier.align(Alignment.CenterStart)
                            .offset { IntOffset(slide.roundToInt(), 0) }
                            .padding(start = 3.dp).size(if (compact) 46.dp else 54.dp)
                            .background(accent, RoundedCornerShape(13.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("››", color = Color(0xFF14212D), fontSize = 22.sp,
                            fontWeight = FontWeight.Black)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("每次滑动 = 真实一回合，敌人也会行动",
                    color = Color(0xFF8FA2B2), fontSize = 10.sp,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { pryNow() }) {
                    Text("无障碍操作", color = accent, fontSize = 10.sp)
                }
            }
        }
    }
}
