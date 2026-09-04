package com.jiacimu.lulu.games

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun MeetingAtmosphereOverlay(
    modifier: Modifier = Modifier,
    dark: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "meeting-atmosphere")
    val drift = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(12_000), RepeatMode.Restart),
        label = "meeting-motes",
    ).value
    val breathe = transition.animateFloat(
        initialValue = .25f,
        targetValue = .72f,
        animationSpec = infiniteRepeatable(tween(3_600), RepeatMode.Reverse),
        label = "meeting-light",
    ).value
    Canvas(modifier) {
        val tint = if (dark) Color(0xFFBFD7CE) else Color(0xFFFFF8D8)
        repeat(17) { index ->
            val seedX = ((index * 43) % 101) / 101f
            val speed = .58f + (index % 5) * .12f
            val x = size.width * ((seedX + sin((drift * 2 * PI + index).toFloat()) * .018f).coerceIn(0f, 1f))
            val yUnit = (1.12f - (drift * speed + index * .091f) % 1.2f)
            val y = size.height * yUnit
            val radius = 1.2f + (index % 4) * .72f
            drawCircle(tint.copy(alpha = (.08f + breathe * .13f) * (1f - index % 3 * .16f)), radius, Offset(x, y))
        }
        drawCircle(
            Brush.radialGradient(
                listOf(tint.copy(alpha = .12f * breathe), Color.Transparent),
                center = Offset(size.width * .76f, size.height * .16f),
                radius = size.minDimension * .42f,
            ),
            radius = size.minDimension * .42f,
            center = Offset(size.width * .76f, size.height * .16f),
        )
    }
}

@Composable
internal fun ApocalypseAtmosphereOverlay(
    tension: Int,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "apocalypse-atmosphere")
    val fall = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween((4_800 - tension * 180).coerceAtLeast(2_700)), RepeatMode.Restart),
        label = "ash-fall",
    ).value
    val pulse = transition.animateFloat(
        initialValue = .16f,
        targetValue = .43f,
        animationSpec = infiniteRepeatable(tween(1_950), RepeatMode.Reverse),
        label = "danger-pulse",
    ).value
    Canvas(modifier) {
        repeat(24) { index ->
            val x = size.width * (((index * 37) % 103) / 103f)
            val y = size.height * ((fall * (.74f + index % 6 * .08f) + index * .117f) % 1.08f)
            val length = 5f + (index % 5) * 2.1f
            drawLine(
                Color(0xFFDEE8E1).copy(alpha = .055f + tension.coerceIn(0, 10) * .004f),
                Offset(x, y),
                Offset(x - length * .48f, y + length),
                strokeWidth = .7f + index % 3 * .35f,
            )
        }
        drawRect(
            Brush.radialGradient(
                colors = listOf(Color.Transparent, Color(0xFF07110D).copy(alpha = .68f)),
                center = center,
                radius = size.maxDimension * .72f,
            ),
        )
        drawRect(Color(0xFF789B86).copy(alpha = pulse * .018f))
    }
}

@Composable
internal fun ArcadeAtmosphere(
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "arcade-atmosphere")
    val drift = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(8_800), RepeatMode.Restart),
        label = "arcade-depth-drift",
    ).value
    val breathe = transition.animateFloat(
        initialValue = .22f,
        targetValue = .72f,
        animationSpec = infiniteRepeatable(tween(3_400), RepeatMode.Reverse),
        label = "arcade-volumetric-light",
    ).value

    Canvas(modifier) {
        val horizon = size.height * .29f
        val vanishing = Offset(size.width * .58f, horizon)

        drawRect(
            Brush.verticalGradient(
                listOf(
                    Color(0xFF141B28).copy(alpha = .30f),
                    Color.Transparent,
                    Color(0xFF050911).copy(alpha = .30f),
                ),
            ),
        )
        drawCircle(
            Brush.radialGradient(
                listOf(
                    Color(0xFFDDE8FF).copy(alpha = .10f + breathe * .06f),
                    Color(0xFF7489B8).copy(alpha = .035f),
                    Color.Transparent,
                ),
                center = Offset(size.width * .80f, size.height * .12f),
                radius = size.maxDimension * .52f,
            ),
            radius = size.maxDimension * .52f,
            center = Offset(size.width * .80f, size.height * .12f),
        )

        val lightCone = Path().apply {
            moveTo(size.width * .74f, 0f)
            lineTo(size.width * .96f, 0f)
            lineTo(size.width * .72f, size.height * .88f)
            lineTo(size.width * .37f, size.height * .88f)
            close()
        }
        drawPath(
            lightCone,
            Brush.linearGradient(
                listOf(
                    Color(0xFFD8E5FF).copy(alpha = .055f + breathe * .045f),
                    Color.Transparent,
                ),
                start = Offset(size.width * .82f, 0f),
                end = Offset(size.width * .50f, size.height),
            ),
        )

        val perspective = Color(0xFFC8D4EA).copy(alpha = .10f)
        for (index in -7..7) {
            val bottomX = size.width * .5f + index * size.width * .13f
            val topX = vanishing.x + index * size.width * .004f
            drawLine(
                perspective.copy(alpha = .055f + (7 - kotlin.math.abs(index)) * .006f),
                Offset(topX, horizon),
                Offset(bottomX, size.height),
                strokeWidth = if (index == 0) 1.35f else .8f,
            )
        }
        repeat(9) { row ->
            val t = row / 8f
            val eased = t * t
            val y = horizon + (size.height - horizon) * eased
            drawLine(
                perspective.copy(alpha = .035f + t * .08f),
                Offset(0f, y),
                Offset(size.width, y),
                strokeWidth = .7f + t,
            )
        }
        drawLine(
            Color(0xFFB7C8EA).copy(alpha = .08f + breathe * .05f),
            Offset(0f, horizon),
            Offset(size.width, horizon),
            strokeWidth = 2f,
        )

        repeat(24) { index ->
            val depth = ((index * 37) % 97) / 97f
            val xBase = ((index * 61) % 101) / 101f
            val x = size.width * ((xBase + sin((drift * 2 * PI + index * .7f).toFloat()) * .025f).coerceIn(-.05f, 1.05f))
            val y = size.height * ((index * .137f + drift * (.18f + depth * .34f)) % 1.05f)
            val radius = 1.2f + depth * 3.8f
            drawCircle(
                Color(0xFFE4ECFF).copy(alpha = .035f + depth * .10f),
                radius,
                Offset(x, y),
            )
        }

        drawCircle(
            Color(0xFF05080F).copy(alpha = .18f),
            radius = size.minDimension * .31f,
            center = Offset(size.width * .05f, size.height * .98f),
        )
        drawCircle(
            Color(0xFF05080F).copy(alpha = .14f),
            radius = size.minDimension * .23f,
            center = Offset(size.width * 1.02f, size.height * .83f),
        )
        drawCircle(
            Color.White.copy(alpha = .10f + breathe * .04f),
            radius = size.minDimension * .26f,
            center = Offset(size.width * .86f, size.height * .14f),
            style = Stroke(width = 1.4f),
        )
    }
}
