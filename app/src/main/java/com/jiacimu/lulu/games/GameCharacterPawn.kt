package com.jiacimu.lulu.games

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
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

@Composable
internal fun GameCharacterPawn(
    avatarUri: String?,
    fallback: String,
    moving: Boolean,
    facingX: Float,
    modifier: Modifier = Modifier,
    coat: Color = Color(0xFF4A625A),
    player: Boolean = false,
) {
    val motion = rememberInfiniteTransition(label = "game-pawn-motion")
    val walkPhase = motion.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (moving) 230 else 1_450), RepeatMode.Reverse),
        label = "game-pawn-step",
    ).value
    val bob = if (moving) kotlin.math.abs(walkPhase) * -2.4f else walkPhase * 1.15f
    val direction = if (facingX < -.08f) -1f else 1f

    Box(
        modifier
            .size(width = 62.dp, height = 92.dp)
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

            val bodyTop = size.height * .43f
            val bodyHeight = size.height * .34f
            drawRoundRect(
                Brush.verticalGradient(
                    listOf(coat.copy(alpha = .98f), coat.copy(alpha = .74f)),
                    startY = bodyTop,
                    endY = bodyTop + bodyHeight,
                ),
                topLeft = Offset(size.width * .25f, bodyTop),
                size = Size(size.width * .50f, bodyHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(11.dp.toPx()),
            )
            drawRoundRect(
                Color.White.copy(alpha = .14f),
                topLeft = Offset(size.width * .31f, bodyTop + 3.dp.toPx()),
                size = Size(size.width * .11f, bodyHeight * .70f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
            )

            val armSwing = if (moving) walkPhase * size.width * .055f else 0f
            drawLine(
                coat.copy(alpha = .92f),
                Offset(size.width * .27f, bodyTop + size.height * .07f),
                Offset(size.width * .15f + armSwing, bodyTop + size.height * .27f),
                strokeWidth = 5.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            drawLine(
                coat.copy(alpha = .92f),
                Offset(size.width * .73f, bodyTop + size.height * .07f),
                Offset(size.width * .85f - armSwing, bodyTop + size.height * .27f),
                strokeWidth = 5.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )

            val legSwing = if (moving) walkPhase * size.width * .055f else 0f
            drawLine(
                Color(0xFF26302D),
                Offset(centerX - size.width * .10f, bodyTop + bodyHeight * .84f),
                Offset(centerX - size.width * .13f - legSwing, size.height * .88f),
                strokeWidth = 6.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            drawLine(
                Color(0xFF26302D),
                Offset(centerX + size.width * .10f, bodyTop + bodyHeight * .84f),
                Offset(centerX + size.width * .13f + legSwing, size.height * .88f),
                strokeWidth = 6.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }

        Surface(
            modifier = Modifier.size(46.dp),
            shape = RoundedCornerShape(17.dp),
            color = Color(0xFFF5F7F5),
            border = BorderStroke(1.dp, if (player) Color(0xFFB7FFE8) else Color.White.copy(alpha = .72f)),
            shadowElevation = 5.dp,
        ) {
            LuluProfileAvatar(avatarUri, fallback.take(2).ifBlank { "人" }, 46)
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
