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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.LuluCssSheet
import com.jiacimu.lulu.design.LuluCssArtwork
import com.jiacimu.lulu.design.cssColor
import com.jiacimu.lulu.design.cssDp
import com.jiacimu.lulu.design.luluCssElement
import com.jiacimu.lulu.design.luluCssStyle

private val rose = Color(0xFFB98A98)
private val ink = Color(0xFF25232A)

/** Two lightning-shaped links connect the enlarged portraits to a compact record. */
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
    val artStyle = luluCssStyle("music-artwork")
    val connectionStyle = luluCssStyle("music-links")
    val roleStyle = luluCssStyle("music-avatar-role")
    val userStyle = luluCssStyle("music-avatar-user")
    val vinylStyle = luluCssStyle("music-vinyl")
    val roleSize = roleStyle.cssDp("width")?.coerceIn(40.dp, 80.dp) ?: 64.dp
    val userSize = userStyle.cssDp("width")?.coerceIn(40.dp, 80.dp) ?: 64.dp
    val discSize = vinylStyle.cssDp("width")?.coerceIn(80.dp, 166.dp) ?: 108.dp
    val discAsset = LuluCssSheet.assetToken(vinylStyle["background-image"])
    BoxWithConstraints(Modifier.fillMaxWidth()
        .height(artStyle.cssDp("height")?.coerceIn(120.dp, 320.dp) ?: 140.dp)
        .luluCssElement("music-artwork")) {
        Canvas(Modifier.fillMaxSize()) {
            val mid = size.width / 2f
            val y = size.height * .46f
            val left = 40.dp.toPx()
            val right = size.width - left
            val discEdge = mid - (discSize.toPx() / 2f) - 2.dp.toPx()
            val lineSpan = (discEdge - left).coerceAtLeast(8f)
            val color = connectionStyle.cssColor("color") ?: Color(0xFFBA8199)
            val glow = connectionStyle.cssColor("border-color") ?: Color(0xFFEEDDE5)
            val lineWidth = connectionStyle.cssDp("border-width")?.coerceIn(1.dp, 7.dp) ?: 2.6.dp
            fun signal(leftSide: Boolean): Path {
                val start = if (leftSide) left else right
                val sign = if (leftSide) 1f else -1f
                return Path().apply {
                    moveTo(start, y)
                    lineTo(start + sign * lineSpan * .28f, y - 10.dp.toPx())
                    lineTo(start + sign * lineSpan * .49f, y + 8.dp.toPx())
                    lineTo(start + sign * lineSpan * .71f, y - 7.dp.toPx())
                    lineTo(start + sign * lineSpan, y)
                }
            }
            for (side in listOf(true, false)) {
                val path = signal(side)
                drawPath(path, glow.copy(alpha = .70f), style = Stroke(
                    width = (lineWidth * 2.8f).toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, color, style = Stroke(width = lineWidth.toPx(),
                    cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            drawCircle(rose.copy(alpha = .08f), radius = 61.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(mid, y))
        }
        Row(
            Modifier.fillMaxWidth().align(Alignment.Center).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(roleSize).clip(CircleShape).luluCssElement("music-avatar-role")) {
                    LuluCssArtwork(LuluCssSheet.assetToken(roleStyle["background-image"]),
                        Modifier.fillMaxSize(), roleStyle) {
                        LuluProfileAvatar(roleAvatar, roleName.take(1).ifBlank { "角" }, roleSize.value.toInt())
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(roleName, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = roleStyle.cssColor("color") ?: ink)
            }
            Box(Modifier.size(discSize + 8.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(discSize)
                    .graphicsLayer { rotationZ = if (spinning) currentAngle else lastAngle }
                    .clip(CircleShape).luluCssElement("music-vinyl"),
                    contentAlignment = Alignment.Center) {
                    LuluCssArtwork(discAsset, Modifier.fillMaxSize(), vinylStyle) {
                        Canvas(Modifier.fillMaxSize()) {
                            val r = size.minDimension / 2f
                            val discTint = vinylStyle.cssColor("background-color")
                            if (discTint != null) drawCircle(discTint)
                            else drawCircle(brush = Brush.radialGradient(
                                listOf(Color(0xFF57515B), Color(0xFF19171C),
                                    Color(0xFF313038), Color(0xFF101015))))
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
                }
            }
            Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(userSize).clip(CircleShape).luluCssElement("music-avatar-user")) {
                    LuluCssArtwork(LuluCssSheet.assetToken(userStyle["background-image"]),
                        Modifier.fillMaxSize(), userStyle) {
                        LuluProfileAvatar(userAvatar, userName.take(1).ifBlank { "我" }, userSize.value.toInt())
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(userName, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = userStyle.cssColor("color") ?: ink)
            }
        }
    }
}
