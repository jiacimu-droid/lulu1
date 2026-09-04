package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot

@Composable
internal fun WorldVirtualJoystick(
    value: WorldVector,
    onValueChanged: (WorldVector) -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color(0xFFE6FFF5),
) {
    fun vectorFor(position: Offset, width: Float, height: Float): WorldVector {
        val center = Offset(width / 2f, height / 2f)
        val dx = position.x - center.x
        val dy = position.y - center.y
        val radius = minOf(width, height) * .31f
        val magnitude = hypot(dx, dy).coerceAtLeast(.001f)
        val strength = (magnitude / radius).coerceIn(0f, 1f)
        return WorldVector(dx / magnitude * strength, dy / magnitude * strength)
    }

    Canvas(
        modifier
            .size(112.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { position ->
                        onValueChanged(vectorFor(position, size.width.toFloat(), size.height.toFloat()))
                    },
                    onDragEnd = { onValueChanged(WorldVector.Zero) },
                    onDragCancel = { onValueChanged(WorldVector.Zero) },
                ) { change, _ ->
                    change.consume()
                    onValueChanged(vectorFor(change.position, size.width.toFloat(), size.height.toFloat()))
                }
            },
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outer = size.minDimension * .43f
        drawCircle(Color.Black.copy(alpha = .28f), outer + 4.dp.toPx(), center)
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = .18f), Color(0xFF071713).copy(alpha = .72f)),
                center = center,
                radius = outer,
            ),
            outer,
            center,
        )
        drawCircle(tint.copy(alpha = .28f), outer, center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.1.dp.toPx()))
        drawCircle(tint.copy(alpha = .11f), outer * .62f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(.8.dp.toPx()))

        val knobTravel = outer * .57f
        val knob = Offset(center.x + value.x * knobTravel, center.y + value.y * knobTravel)
        drawCircle(Color.Black.copy(alpha = .24f), outer * .36f + 3.dp.toPx(), knob + Offset(0f, 3.dp.toPx()))
        drawCircle(
            Brush.radialGradient(
                listOf(tint.copy(alpha = .96f), tint.copy(alpha = .58f)),
                center = knob - Offset(5.dp.toPx(), 6.dp.toPx()),
                radius = outer * .40f,
            ),
            outer * .36f,
            knob,
        )
        drawCircle(Color.White.copy(alpha = .44f), outer * .36f, knob, style = androidx.compose.ui.graphics.drawscope.Stroke(.7.dp.toPx()))
    }
}

@Composable
internal fun WorldActionButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF9DE5CF),
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(78.dp),
        shape = CircleShape,
        color = if (enabled) Color(0xE61B332C) else Color(0x99121B19),
        contentColor = if (enabled) Color.White else Color.White.copy(alpha = .46f),
        border = BorderStroke(1.dp, if (enabled) accent.copy(alpha = .9f) else Color.White.copy(alpha = .14f)),
        shadowElevation = if (enabled) 9.dp else 1.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.TouchApp, null, modifier = Modifier.size(24.dp))
                Text(label, fontSize = 10.sp, fontWeight = FontWeight.Black, maxLines = 1)
            }
        }
    }
}
