package com.jiacimu.lulu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp

/**
 * Original vector stationery for 所思, drawn entirely on-device with Compose.
 * No bitmap, web image, embedded screenshot, network, decoding or fuzzy upscale.
 * Layout coordinates use a 1000 x 1500 page and scale with the book itself;
 * text, navigation and the date remain independent accessible Compose controls.
 *
 * Art direction: sea glass / coral ink, constellation paths and an origami boat.
 * The flowers and purple foliage from the reference are deliberately NOT reused.
 */
@Composable
internal fun SelfThoughtNotebookArtwork(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        color = Color(0xFFF9FCFA),
        shape = RoundedCornerShape(25.dp),
        shadowElevation = 9.dp,
    ) {
        Box {
            Canvas(Modifier.fillMaxSize()) {
                val sx = size.width / 1000f
                val sy = size.height / 1500f
                withTransform({ scale(sx, sy, pivot = Offset.Zero) }) {
                    val ink = Color(0xFF477B80)
                    val pale = Color(0xFFBBD5D0)
                    val coral = Color(0xFFD9907E)
                    val gold = Color(0xFFE1BDA0)
                    val paper = Color(0xFFFFFDF8)
                    // Staggered pages and a slim binding: silhouettes are part of the illustration.
                    drawRoundRect(
                        color = Color(0xFFCEDDD8),
                        topLeft = Offset(36f, 34f), size = Size(942f, 1434f),
                        cornerRadius = CornerRadius(64f),
                    )
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            listOf(Color(0xFFDEEAE6), Color(0xFFF1F8F4)),
                            start = Offset(110f, 35f), end = Offset(1000f, 1400f),
                        ),
                        topLeft = Offset(25f, 17f), size = Size(940f, 1412f),
                        cornerRadius = CornerRadius(67f),
                    )
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            listOf(Color(0xFFFFFCF5), paper, Color(0xFFF1F6F2)),
                            start = Offset(0f, 20f), end = Offset(980f, 1500f),
                        ),
                        topLeft = Offset(12f, 42f), size = Size(934f, 1362f),
                        cornerRadius = CornerRadius(61f),
                    )
                    drawRoundRect(
                        color = Color(0xFF94B2AE).copy(alpha = .24f),
                        topLeft = Offset(12f, 42f), size = Size(934f, 1362f),
                        cornerRadius = CornerRadius(61f),
                        style = Stroke(width = 2.2f),
                    )
                    // Fine inner stitched rim: visible even on full-HD screens.
                    drawRoundRect(
                        color = Color(0xFF8DACAB).copy(alpha = .24f),
                        topLeft = Offset(69f, 85f), size = Size(817f, 1264f),
                        cornerRadius = CornerRadius(37f),
                        style = Stroke(width = 1.4f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(11f, 12f))),
                    )
                    // Horizontal writing guides are extremely faint, not fake prewritten text.
                    for (y in 340..1195 step 58) {
                        drawLine(Color(0xFF8BA8A3).copy(alpha = .080f),
                            Offset(135f, y.toFloat()), Offset(858f, y.toFloat()), 1.4f)
                    }
                    // A cloud of translucent watercolor-like circles, clean crisp edges.
                    drawCircle(Color(0xFFE0EEE9).copy(alpha = .44f), radius = 140f,
                        center = Offset(83f, 1240f))
                    drawCircle(Color(0xFFF9E4DC).copy(alpha = .46f), radius = 91f,
                        center = Offset(860f, 1335f))
                    drawCircle(Color(0xFFD6E8E4).copy(alpha = .40f), radius = 83f,
                        center = Offset(810f, 290f))

                    // A sun orbit, two trails, and hand-drawn small stars near the heading.
                    // Keep the ornament delicate and clear of the first writing line.
                    withTransform({ scale(.67f, .67f, pivot = Offset.Zero) }) {
                    val orbit = Path().apply {
                        moveTo(110f, 276f)
                        cubicTo(178f, 128f, 344f, 113f, 433f, 198f)
                        cubicTo(479f, 242f, 449f, 291f, 402f, 295f)
                    }
                    drawPath(orbit, ink.copy(alpha = .48f), style = Stroke(width = 3.2f))
                    drawCircle(Color(0xFFFDF7E8), radius = 54f,
                        center = Offset(226f, 227f))
                    drawCircle(gold.copy(alpha = .85f), radius = 54f,
                        center = Offset(226f, 227f), style = Stroke(2.5f))
                    drawCircle(coral, radius = 5f, center = Offset(435f, 202f))
                    drawCircle(ink.copy(alpha = .5f), radius = 5f, center = Offset(137f, 325f))
                    drawCircle(coral.copy(alpha = .6f), radius = 3.5f, center = Offset(389f, 334f))
                    }
                    // Tiny original 4-point twinkles, repeated sparingly.
                    fun star(x: Float, y: Float, length: Float, color: Color) {
                        drawLine(color, Offset(x - length, y), Offset(x + length, y), 2.5f)
                        drawLine(color, Offset(x, y - length), Offset(x, y + length), 2.5f)
                        drawCircle(color, 2.3f, Offset(x, y))
                    }
                    star(376f, 130f, 12f, coral)
                    star(507f, 315f, 11f, ink.copy(alpha = .65f))
                    star(810f, 1002f, 13f, coral.copy(alpha = .8f))
                    star(240f, 1273f, 10f, ink.copy(alpha = .47f))
                    // A minimal celestial crescent - a different language from floral decoration.
                    drawCircle(ink.copy(alpha = .22f), 31f, Offset(760f, 1037f))
                    drawCircle(paper, 28f, Offset(772f, 1028f))

                    // Original paper boat and two soft wave lines in the lower-right margin.
                    val boat = Path().apply {
                        moveTo(719f, 1178f); lineTo(815f, 1213f)
                        lineTo(907f, 1178f); lineTo(877f, 1242f)
                        lineTo(763f, 1242f); close()
                    }
                    drawPath(boat, color = pale.copy(alpha = .27f))
                    drawPath(boat, color = ink.copy(alpha = .73f),
                        style = Stroke(width = 3.5f))
                    drawLine(ink.copy(alpha = .60f), Offset(812f, 1213f),
                        Offset(812f, 1090f), 3.6f)
                    val sail = Path().apply {
                        moveTo(807f, 1099f); lineTo(807f, 1195f)
                        lineTo(748f, 1189f); close()
                    }
                    drawPath(sail, color = Color(0xFFEED5C7).copy(alpha = .40f))
                    drawPath(sail, color = coral, style = Stroke(width = 3f))
                    val littleSail = Path().apply {
                        moveTo(821f, 1113f); lineTo(821f, 1196f)
                        lineTo(873f, 1191f); close()
                    }
                    drawPath(littleSail, ink.copy(alpha = .48f), style = Stroke(width = 2.7f))
                    for (idx in 0..1) {
                        val y = 1261f + idx * 22f
                        val wave = Path().apply {
                            moveTo(702f, y)
                            cubicTo(727f, y - 12f, 753f, y + 12f, 778f, y)
                            cubicTo(805f, y - 12f, 830f, y + 12f, 861f, y)
                        }
                        drawPath(wave, ink.copy(alpha = if (idx == 0) .42f else .25f),
                            style = Stroke(width = 2.7f))
                    }
                    // Ring binding. Draw after front page, never use the blurry photo rings.
                    for (y in listOf(335f, 765f, 1165f)) {
                        drawCircle(Color(0xFFB7D1CC), 29f,
                            Offset(31f, y))
                        drawCircle(Color(0xFFF7FCF8), 22f,
                            Offset(31f, y))
                        drawArc(color = ink.copy(alpha = .76f),
                            startAngle = 55f, sweepAngle = 263f,
                            useCenter = false,
                            topLeft = Offset(7f, y - 26f),
                            size = Size(52f, 52f), style = Stroke(8f))
                    }
                }
            }
            content()
        }
    }
}
