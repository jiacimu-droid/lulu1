package com.jiacimu.lulu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.DigitalWorldPublicPlaces
import com.jiacimu.lulu.data.DigitalWorldStore

/**
 * Small, deliberately illustrated, top-down / three-quarter map thumbnails.
 * Geometry follows the same family as the walkable scenes; no network assets or fake locations.
 */
@Composable
internal fun WorldMapIllustration(sceneCode: String, modifier: Modifier = Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(13.dp))) {
        val w = size.width
        val h = size.height
        val home = sceneCode.startsWith("home:") || sceneCode.startsWith("resident-home:")
        val cloud = sceneCode == DigitalWorldStore.CLOUD_MEADOW || sceneCode == DigitalWorldStore.ARRIVAL
        val garden = sceneCode == DigitalWorldPublicPlaces.COURTYARD || sceneCode.startsWith("shared:discovered:")
        val cafe = sceneCode == DigitalWorldPublicPlaces.CAFE
        val game = sceneCode == DigitalWorldPublicPlaces.GAME_HALL
        val palette = when {
            cloud -> listOf(Color(0xFFD6EAE8), Color(0xFF9CC7C2))
            home -> listOf(Color(0xFFE2E8DF), Color(0xFFC1CEC4))
            cafe -> listOf(Color(0xFFF0E5D5), Color(0xFFD2B9A0))
            game -> listOf(Color(0xFFD6DFE9), Color(0xFFAAB8CC))
            else -> listOf(Color(0xFFE4EBDD), Color(0xFFA9CBB6))
        }
        drawRect(Brush.verticalGradient(palette))
        drawCircle(Color.White.copy(alpha = .60f), h * .28f, Offset(w * .78f, h * .21f))
        // Terrain strips and a soft diagonal lane establish spatial depth.
        drawOval(Color.White.copy(alpha = .17f), Offset(-w * .08f, h * .61f), Size(w * 1.18f, h * .55f))
        val lane = Path().apply {
            moveTo(w * .34f, h)
            lineTo(w * .51f, h * .40f)
            lineTo(w * .63f, h * .40f)
            lineTo(w * .86f, h)
            close()
        }
        drawPath(lane, Color.White.copy(alpha = .29f))
        // Small map tiles share materials with the full scenes: fine paving,
        // individual stones and a soft ground shadow, not a flat icon rectangle.
        repeat(16) { i ->
            val x = w * ((i * 37 % 97) / 100f)
            val y = h * (.59f + (i * 13 % 32) / 100f)
            drawOval(Color(0xFF49695D).copy(alpha = .08f + (i % 3) * .02f),
                Offset(x, y), Size(w * (.018f + i % 3 * .008f), h * .014f))
        }
        when {
            home -> {
                // Three-quarter house: projecting roof, visible front elevation, doorway and windows.
                val l = w * .29f
                val r = w * .68f
                val roofY = h * .25f
                val frontY = h * .77f
                drawOval(Color(0xFF394D40).copy(alpha = .14f),
                    Offset(l - w * .05f, frontY - h * .07f), Size((r - l) + w * .12f, h * .25f))
                drawRoundRect(Color(0xFFF5EFE5), Offset(l, roofY + h * .10f),
                    Size(r - l, frontY - roofY - h * .10f), CornerRadius(4f))
                drawRoundRect(Color(0xFFC0D1C6), Offset(l, roofY + h * .33f),
                    Size(r - l, h * .13f), CornerRadius(3f))
                val roof = Path().apply {
                    moveTo(l - w * .055f, roofY + h * .13f)
                    lineTo(l + (r - l) * .48f, roofY - h * .18f)
                    lineTo(r + w * .06f, roofY + h * .13f)
                    close()
                }
                drawPath(roof, Color(0xFF566F64))
                repeat(4) { row ->
                    val ry = roofY + h * (.012f + row * .030f)
                    val inset = w * (.06f - row * .008f)
                    drawLine(Color(0xFFDCE8DF).copy(alpha = .25f),
                        Offset(l + inset, ry), Offset(r - inset, ry + h * .003f), 1.1f)
                }
                drawRoundRect(Color(0xFFE4E3D3), Offset(l + w * .012f, frontY - h * .07f),
                    Size((r - l) - w * .024f, h * .055f), CornerRadius(2f))
                drawLine(Color(0xFF8CA899), Offset(l - w * .035f, roofY + h * .12f),
                    Offset(r + w * .04f, roofY + h * .12f), 2.5f, StrokeCap.Round)
                drawRoundRect(Color(0xFF657D72), Offset(w * .47f, frontY - h * .27f),
                    Size(w * .072f, h * .27f), CornerRadius(3f))
                listOf(w * .355f, w * .58f).forEach { x ->
                    drawRoundRect(Color(0xFF9BBEC1), Offset(x, frontY - h * .25f),
                        Size(w * .065f, h * .14f), CornerRadius(2f))
                    drawLine(Color.White.copy(alpha = .73f), Offset(x + w * .032f, frontY - h * .25f),
                        Offset(x + w * .032f, frontY - h * .11f), 1.4f)
                }
                drawMapTree(w * .16f, h * .73f, h * .24f)
                drawMapTree(w * .81f, h * .77f, h * .20f)
                repeat(5) { i ->
                    drawCircle(Color(0xFFE9F1D9), h * .012f,
                        Offset(w * (.18f + .12f * i), h * (.85f + .025f * (i % 2))))
                }
            }
            cloud -> {
                repeat(5) { i ->
                    val x = w * (.12f + .19f * i)
                    drawOval(Color.White.copy(alpha = .68f), Offset(x - w * .15f, h * (.44f + i % 2 * .12f)),
                        Size(w * .27f, h * .28f))
                }
                drawOval(Color(0xFF7DB6AE).copy(alpha = .44f),
                    Offset(w * .29f, h * .55f), Size(w * .40f, h * .18f))
                drawOval(Color.White.copy(alpha = .78f),
                    Offset(w * .34f, h * .50f), Size(w * .28f, h * .12f))
                repeat(7) { i ->
                    val x = w * (.18f + .10f * i)
                    val y = h * (.30f + (i % 3) * .14f)
                    drawCircle(Color(0xFFFFF9E7).copy(alpha = .48f), h * .012f + i % 3, Offset(x, y))
                }
                val cloudBridge = Path().apply {
                    moveTo(w * .29f, h * .82f)
                    cubicTo(w * .40f, h * .68f, w * .57f, h * .68f, w * .71f, h * .81f)
                }
                drawPath(cloudBridge, Color(0xFFFFFFFF).copy(alpha = .32f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(h * .05f, cap = StrokeCap.Round))
            }
            cafe -> {
                drawRoundRect(Color(0xFFF5F0E5), Offset(w * .30f, h * .23f),
                    Size(w * .43f, h * .52f), CornerRadius(6f))
                drawRoundRect(Color(0xFF9A6954), Offset(w * .27f, h * .23f),
                    Size(w * .49f, h * .15f), CornerRadius(4f))
                repeat(5) { i ->
                    drawRect(if (i % 2 == 0) Color(0xFFFFEADC) else Color(0xFFB77562),
                        Offset(w * (.29f + .087f * i), h * .34f), Size(w * .087f, h * .14f))
                }
                drawRoundRect(Color(0xFF739990), Offset(w * .40f, h * .52f),
                    Size(w * .15f, h * .22f), CornerRadius(3f))
                drawRoundRect(Color(0xFFAECFD0), Offset(w * .414f, h * .535f),
                    Size(w * .12f, h * .12f), CornerRadius(2f))
                drawLine(Color(0xFFEEF9F0), Offset(w * .473f, h * .54f),
                    Offset(w * .473f, h * .652f), 1.2f)
                drawOval(Color(0xFF684B39).copy(alpha = .25f),
                    Offset(w * .35f, h * .775f), Size(w * .26f, h * .040f))
                drawOval(Color(0xFFF6E9D2), Offset(w * .38f, h * .752f), Size(w * .19f, h * .040f))
                drawCircle(Color(0xFFE6B77C), h * .038f, Offset(w * .47f, h * .749f))
                drawCircle(Color(0xFFFFF3D5), h * .016f, Offset(w * .47f, h * .735f))
                drawMapTree(w * .16f, h * .79f, h * .18f)
                drawMapTree(w * .86f, h * .78f, h * .22f)
            }
            game -> {
                repeat(3) { i ->
                    val x = w * (.22f + i * .22f)
                    drawRoundRect(Color(0xFF4B586C), Offset(x, h * .22f),
                        Size(w * .16f, h * .51f), CornerRadius(5f))
                    drawRoundRect(if (i == 1) Color(0xFF9ED6D8) else Color(0xFFABBAE8),
                        Offset(x + w * .016f, h * .30f), Size(w * .128f, h * .22f), CornerRadius(4f))
                    drawCircle(Color(0xFFFFE2AD), 3f, Offset(x + w * .08f, h * .61f))
                    drawLine(Color(0xFFCADDEC), Offset(x + w * .024f, h * .25f),
                        Offset(x + w * .128f, h * .25f), 1.1f)
                    repeat(3) { star ->
                        drawCircle(Color.White.copy(alpha = .72f), h * .012f,
                            Offset(x + w * (.037f + star * .039f), h * (.38f + ((star + i) % 2) * .07f)))
                    }
                }
                drawOval(Color(0xFF697B91).copy(alpha = .30f),
                    Offset(w * .22f, h * .75f), Size(w * .57f, h * .10f))
            }
            else -> {
                val path = Path().apply {
                    moveTo(0f, h * .82f)
                    cubicTo(w * .25f, h * .66f, w * .35f, h * .93f, w, h * .61f)
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(path, Color(0xFF91B7A0).copy(alpha = .55f))
                drawMapTree(w * .17f, h * .77f, h * .30f)
                drawMapTree(w * .39f, h * .68f, h * .19f)
                drawMapTree(w * .79f, h * .74f, h * .27f)
                if (garden) {
                    repeat(4) { i ->
                        val x = w * (.20f + .18f * i)
                        drawCircle(Color(0xFFF6DCE0), h * .023f, Offset(x, h * (.79f - .04f * (i % 2))))
                        drawCircle(Color(0xFFFFEAB0), h * .009f, Offset(x, h * (.79f - .04f * (i % 2))))
                    }
                    drawOval(Color(0xFF85B6BC).copy(alpha = .75f),
                        Offset(w * .47f, h * .57f), Size(w * .22f, h * .16f))
                    drawOval(Color.White.copy(alpha = .46f),
                        Offset(w * .51f, h * .59f), Size(w * .13f, h * .06f))
                }
            }
        }
        drawLine(Color.White.copy(alpha = .52f), Offset(0f, h - 1.2f),
            Offset(w, h - 1.2f), 2.4f)
    }
}

private fun DrawScope.drawMapTree(x: Float, base: Float, height: Float) {
    val trunk = height * .36f
    drawLine(Color(0xFF937C69), Offset(x, base), Offset(x, base - trunk), 3.5f, StrokeCap.Round)
    drawOval(Color(0xFF5B8872), Offset(x - height * .31f, base - height),
        Size(height * .62f, height * .64f))
    drawOval(Color(0xFF8BB6A1).copy(alpha = .77f),
        Offset(x - height * .25f, base - height * .96f), Size(height * .42f, height * .33f))
}
