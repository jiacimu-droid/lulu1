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
    val scan = transition.animateFloat(
        initialValue = -.08f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(5_200), RepeatMode.Restart),
        label = "arcade-scan",
    ).value
    val shimmer = transition.animateFloat(
        initialValue = .2f,
        targetValue = .62f,
        animationSpec = infiniteRepeatable(tween(2_800), RepeatMode.Reverse),
        label = "arcade-shimmer",
    ).value
    Canvas(modifier) {
        val grid = Color(0xFFCDD5E5).copy(alpha = .22f)
        val step = size.width / 7f
        var x = -size.height
        while (x < size.width + size.height) {
            drawLine(grid, Offset(x, 0f), Offset(x + size.height, size.height), strokeWidth = 1f)
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }
        drawLine(
            Color(0xFF8294C4).copy(alpha = .08f + shimmer * .08f),
            Offset(0f, size.height * scan),
            Offset(size.width, size.height * scan),
            strokeWidth = 8f,
        )
        drawCircle(
            Color(0xFFAAB9DF).copy(alpha = .11f),
            radius = size.minDimension * .27f,
            center = Offset(size.width * .88f, size.height * .14f),
            style = Stroke(width = 2f),
        )
    }
}
