package com.jiacimu.lulu.games

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.LuluProfileAvatar
import com.jiacimu.lulu.ResidentPose

@Composable
internal fun GameCharacterPawn(
    avatarUri: String?,
    fallback: String,
    moving: Boolean,
    facingX: Float,
    modifier: Modifier = Modifier,
    coat: Color = Color(0xFF4A625A),
    player: Boolean = false,
    pose: ResidentPose = ResidentPose.STAND,
) {
    val motion = rememberInfiniteTransition(label = "game-pawn-motion")
    val walkPhase = motion.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (moving) 230 else 1_450), RepeatMode.Reverse),
        label = "game-pawn-step",
    ).value
    val bob = if (pose != ResidentPose.STAND) 0f else if (moving) kotlin.math.abs(walkPhase) * -2.4f else walkPhase * 1.15f
    val direction = if (facingX < -.08f) -1f else 1f

    Box(
        modifier
            .size(width = if (pose == ResidentPose.LIE) 95.dp else 62.dp, height = 92.dp)
            .graphicsLayer {
                translationY = bob
                scaleX = direction
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val centerX = size.width / 2f
            drawOval(
                Color.Black.copy(alpha = .28f),
                topLeft = Offset(size.width * .18f, size.height * .87f),
                size = Size(size.width * .64f, size.height * .085f),
            )
            if (player) {
                drawOval(
                    Color(0xFF9EFFE0).copy(alpha = .34f),
                    topLeft = Offset(size.width * .10f, size.height * .81f),
                    size = Size(size.width * .80f, size.height * .16f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.4.dp.toPx()),
                )
            }

            when (pose) {
                ResidentPose.LIE -> {
                    // Horizontal silhouette across the bed/rug. The avatar is
                    // rotated independently below rather than standing above it.
                    drawRoundRect(
                        Brush.horizontalGradient(listOf(coat, coat.copy(alpha = .76f))),
                        topLeft = Offset(size.width * .24f, size.height * .62f),
                        size = Size(size.width * .54f, size.height * .16f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
                    )
                    drawLine(Color(0xFF28322F), Offset(size.width * .77f, size.height * .68f),
                        Offset(size.width * .96f, size.height * .72f),
                        strokeWidth = 7.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(coat.copy(alpha = .9f), Offset(size.width * .36f, size.height * .69f),
                        Offset(size.width * .55f, size.height * .81f),
                        strokeWidth = 5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
                ResidentPose.SIT, ResidentPose.STAND -> {
                    val seated = pose == ResidentPose.SIT
                    val bodyTop = size.height * if (seated) .49f else .43f
                    val bodyHeight = size.height * if (seated) .26f else .34f
                    drawRoundRect(
                        Brush.verticalGradient(listOf(coat.copy(alpha = .98f), coat.copy(alpha = .74f)),
                            startY = bodyTop, endY = bodyTop + bodyHeight),
                        topLeft = Offset(size.width * .25f, bodyTop),
                        size = Size(size.width * .50f, bodyHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(11.dp.toPx()),
                    )
                    val armSwing = if (moving && !seated) walkPhase * size.width * .055f else 0f
                    drawLine(coat, Offset(size.width * .27f, bodyTop + size.height * .07f),
                        Offset(size.width * .15f + armSwing, bodyTop + size.height * .27f),
                        strokeWidth = 5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(coat, Offset(size.width * .73f, bodyTop + size.height * .07f),
                        Offset(size.width * .85f - armSwing, bodyTop + size.height * .27f),
                        strokeWidth = 5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    if (seated) {
                        // Bent thighs cross the seat, with shins falling over
                        // its near edge; standing and sitting no longer overlap.
                        drawLine(Color(0xFF26302D),
                            Offset(centerX - size.width * .12f, size.height * .73f),
                            Offset(centerX + size.width * .23f, size.height * .79f),
                            strokeWidth = 7.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        drawLine(Color(0xFF26302D),
                            Offset(centerX + size.width * .23f, size.height * .79f),
                            Offset(centerX + size.width * .26f, size.height * .91f),
                            strokeWidth = 6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    } else {
                        val legSwing = if (moving) walkPhase * size.width * .055f else 0f
                        drawLine(Color(0xFF26302D),
                            Offset(centerX - size.width * .10f, bodyTop + bodyHeight * .84f),
                            Offset(centerX - size.width * .13f - legSwing, size.height * .88f),
                            strokeWidth = 6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        drawLine(Color(0xFF26302D),
                            Offset(centerX + size.width * .10f, bodyTop + bodyHeight * .84f),
                            Offset(centerX + size.width * .13f + legSwing, size.height * .88f),
                            strokeWidth = 6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                }
            }
        }

        Surface(
            modifier = when (pose) {
                ResidentPose.LIE -> Modifier.align(Alignment.CenterStart).offset(x = 3.dp, y = 8.dp)
                    .size(37.dp).graphicsLayer { rotationZ = -68f }
                ResidentPose.SIT -> Modifier.align(Alignment.TopCenter).offset(y = 8.dp).size(46.dp)
                ResidentPose.STAND -> Modifier.align(Alignment.TopCenter).size(46.dp)
            },
            shape = RoundedCornerShape(17.dp),
            color = Color(0xFFF5F7F5),
            border = BorderStroke(1.dp, if (player) Color(0xFFB7FFE8) else Color.White.copy(alpha = .72f)),
            shadowElevation = 5.dp,
        ) {
            LuluProfileAvatar(avatarUri, fallback.take(2).ifBlank { "人" }, if (pose == ResidentPose.LIE) 37 else 46)
        }
        if (player) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = 2.dp).size(11.dp),
                shape = RoundedCornerShape(99.dp),
                color = Color(0xFF9EFFE0),
                border = BorderStroke(1.dp, Color(0xFF173C32)),
            ) {}
        }
    }
}
