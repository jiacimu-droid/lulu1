package com.jiacimu.lulu

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val rose = Color(0xFFB98A98)
private val ink = Color(0xFF25232A)

/** Curved duet orbit: two real portraits embrace the spinning record, without a rigid bar. */
@Composable
internal fun ListenTogetherArtwork(
    roleName: String,
    roleAvatar: String?,
    userAvatar: String?,
    userName: String,
    spinning: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "vinyl")
    val currentAngle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(13000, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart),
        label = "record-rotation",
    )
    var lastAngle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(spinning) { if (!spinning) lastAngle = currentAngle }
    BoxWithConstraints(Modifier.fillMaxWidth().height(224.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val mid = size.width / 2f
            val y = size.height * .46f
            val left = size.width * .12f
            val right = size.width * .88f
            val topArc = Path().apply {
                moveTo(left, y)
                cubicTo(size.width * .26f, size.height * .06f, size.width * .74f, size.height * .06f, right, y)
            }
            val bottomArc = Path().apply {
                moveTo(left, y)
                cubicTo(size.width * .3f, size.height * .91f, size.width * .7f, size.height * .91f, right, y)
            }
            drawPath(topArc, Color(0xFFE7D4D9), style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round))
            drawPath(bottomArc, Color(0xFFF0E7E8), style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(rose.copy(alpha = .12f), radius = 102.dp.toPx(), center = androidx.compose.ui.geometry.Offset(mid, y))
        }
        Row(
            Modifier.fillMaxWidth().align(Alignment.Center).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.width(66.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(54.dp).clip(CircleShape)) {
                    LuluProfileAvatar(roleAvatar, roleName.take(1).ifBlank { "角" }, 54)
                }
                Spacer(Modifier.height(8.dp))
                Text(roleName, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = ink)
            }
            Box(Modifier.size(156.dp), contentAlignment = Alignment.Center) {
                Canvas(
                    Modifier.size(148.dp).graphicsLayer {
                        rotationZ = if (spinning) currentAngle else lastAngle
                    }
                ) {
                    val r = size.minDimension / 2f
                    drawCircle(brush = Brush.radialGradient(
                        listOf(Color(0xFF57515B), Color(0xFF19171C), Color(0xFF313038), Color(0xFF101015))))
                    for (fraction in listOf(.58f,.64f,.71f,.78f,.84f,.90f,.96f)) {
                        drawCircle(Color.White.copy(alpha = .10f), radius = r*fraction,
                            style = Stroke(width = .65.dp.toPx()))
                    }
                    drawCircle(brush = Brush.linearGradient(
                        listOf(Color(0xFFCCB3BE), Color(0xFFD9CDC5), Color(0xFF987F90))),
                        radius = r*.43f)
                    drawCircle(Color.White.copy(alpha = .27f), radius = r*.36f,
                        style = Stroke(width = 1.dp.toPx()))
                    drawCircle(Color(0xFF24212A), radius = r*.065f)
                    drawCircle(Color(0xFFEBDFE3), radius = r*.025f)
                }
            }
            Column(Modifier.width(66.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(54.dp).clip(CircleShape)) {
                    LuluProfileAvatar(userAvatar, userName.take(1).ifBlank { "我" }, 54)
                }
                Spacer(Modifier.height(8.dp))
                Text(userName, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = ink)
            }
        }
    }
}
