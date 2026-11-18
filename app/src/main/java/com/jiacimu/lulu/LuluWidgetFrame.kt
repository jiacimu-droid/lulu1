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

        fun mark(x1: Float, y1: Float, x2: Float, y2: Float, color: Color = edgeInk) {
            drawLine(color, Offset(x1, y1), Offset(x2, y2), stroke, cap = StrokeCap.Round)
        }

        when (kind) {
            LuluWidgetKind.CHAT -> {
                // A short highlight and a second, offset corner echo the avatar mat.
                val reach = 12.dp.toPx()
                drawPath(Path().apply {
                    moveTo(left, top + radius + reach * .5f)
                    lineTo(left, top + radius)
                    quadraticTo(left, top, left + radius, top)
                    lineTo(left + radius + reach, top)
                }, edgeInk, style = Stroke(width = stroke, cap = StrokeCap.Round))
                val tail = 25.dp.toPx()
                mark(right - radius - tail, bottom, right - radius, bottom)
                mark(right - radius - tail * .6f, bottom - 3.dp.toPx(), right - radius, bottom - 3.dp.toPx(), innerLine)
            }
            LuluWidgetKind.BULLETIN -> {
                // Engraved dots along the edge of the notice.
                val gap = 5.dp.toPx()
                val y = bottom - 2.dp.toPx()
                repeat(5) { index ->
                    drawCircle(edgeInk, .85.dp.toPx(), Offset(left + radius + index * gap, y))
                }
                mark(right - radius - gap * 3, y, right - radius, y)
            }
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
            LuluWidgetKind.PHOTO -> {
                drawRoundRect(innerLine, Offset(left, top), innerSize, CornerRadius(radius), style = Stroke(stroke))
            }
            LuluWidgetKind.COUNTDOWN -> {
                val gap = 4.dp.toPx()
                val x = right - radius - gap * 4
                repeat(5) { index ->
                    val height = if (index == 2) 6.dp.toPx() else 3.dp.toPx()
                    mark(x + index * gap, bottom - height, x + index * gap, bottom)
                }
                mark(left + radius, top, left + radius + gap * 3, top)
            }
            LuluWidgetKind.PLANNER -> {
                // A double rule like the binding edge of a small planner.
                val y1 = top + radius
                val y2 = bottom - radius
                mark(left, y1, left, y2, innerLine)
                mark(left + 3.dp.toPx(), y1, left + 3.dp.toPx(), y2, innerLine)
            }
            LuluWidgetKind.CALENDAR -> {
                val reach = 8.dp.toPx()
                mark(left + radius, bottom, left + radius + reach * 2, bottom)
                mark(right - radius - reach, bottom, right - radius, bottom)
            }
        }

        val progress = edge.value
        if (progress > 0f && progress < 1f) {
            val sweep = sin(progress * PI).toFloat()
            val x = size.width * progress
            val beam = size.width * .28f
            val glint = Brush.horizontalGradient(
                listOf(Color.Transparent, ink.copy(alpha = ink.alpha * .50f * sweep), Color.Transparent),
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
