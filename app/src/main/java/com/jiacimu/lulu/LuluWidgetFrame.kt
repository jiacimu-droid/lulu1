package com.jiacimu.lulu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun rememberWidgetEdgeMotion(
    widgetId: String,
    active: Boolean,
    content: String?,
): State<Float> {
    val progress = remember(widgetId) { Animatable(1f) }
    LaunchedEffect(active, content) {
        if (active) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(620, easing = FastOutSlowInEasing))
        } else {
            progress.snapTo(1f)
        }
    }
    return progress.asState()
}

/** All accents live inside the card and leave its text and touch targets alone. */
@Composable
internal fun LuluWidgetFrame(
    kind: LuluWidgetKind,
    ink: Color,
    line: Color,
    corner: Dp,
    press: State<Float>,
    edge: State<Float>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        if (size.width < 56.dp.toPx() || size.height < 42.dp.toPx()) return@Canvas
        val inset = 5.dp.toPx()
        val stroke = .8.dp.toPx()
        val radius = (corner.toPx() - inset).coerceIn(0f, size.minDimension / 3f)
        val left = inset
        val top = inset
        val right = size.width - inset
        val bottom = size.height - inset
        val edgeInk = ink.copy(alpha = ink.alpha * (.18f + press.value * .16f))
        val innerLine = line.copy(alpha = line.alpha * .72f)
        val innerSize = Size(right - left, bottom - top)

        when (kind) {
            LuluWidgetKind.HOROSCOPE -> {
                val orbit = size.minDimension * .49f
                val center = Offset(right - orbit * .25f, bottom + orbit * .53f)
                repeat(2) { ring ->
                    val length = if (ring == 0) orbit else orbit * .79f
                    drawArc(if (ring == 0) edgeInk else innerLine,
                        200f, 127f, useCenter = false, topLeft = center - Offset(length, length),
                        size = Size(length * 2f, length * 2f), style = Stroke(stroke))
                }
                val star = Offset(right - 17.dp.toPx(), bottom - orbit * .35f)
                drawFrameStar(star, 4.dp.toPx(), edgeInk, stroke)
            }
            LuluWidgetKind.NOTE -> {
                // A tiny crease in the lower corner of the note.
                val fold = 12.dp.toPx()
                drawPath(Path().apply {
                    moveTo(right - radius - fold, bottom)
                    lineTo(right - radius - fold, bottom - fold)
                    lineTo(right - radius, bottom - fold)
                }, edgeInk, style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
            else -> Unit
        }

        val progress = edge.value
        if (progress > 0f && progress < 1f) {
            val sweep = sin(progress * PI).toFloat()
            val x = size.width * progress
            val beam = size.width * .28f
            val glint = Brush.horizontalGradient(
                listOf(Color.Transparent, ink.copy(alpha = ink.alpha * .26f * sweep), Color.Transparent),
                startX = x - beam, endX = x + beam,
            )
            drawRoundRect(glint, Offset(left, top), innerSize, CornerRadius(radius), style = Stroke(1.15.dp.toPx()))
        }
    }
}

private fun DrawScope.drawFrameStar(center: Offset, reach: Float, color: Color, stroke: Float) {
    drawLine(color, center - Offset(reach, 0f), center + Offset(reach, 0f), stroke, StrokeCap.Round)
    drawLine(color, center - Offset(0f, reach), center + Offset(0f, reach), stroke, StrokeCap.Round)
    drawCircle(color, radius = .9.dp.toPx(), center = center + Offset(-reach * 2.2f, reach * 1.4f))
}
