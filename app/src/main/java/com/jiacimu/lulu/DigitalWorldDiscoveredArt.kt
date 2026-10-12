package com.jiacimu.lulu

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.jiacimu.lulu.data.DigitalWorldDiscoveredPlace

/**
 * Discovered-world terrain is driven by a committed place, not imaginary scene prose.
 * Architecture depends on recorded purpose; repeated visits get stable geometry.
 */
internal fun DrawScope.drawDigitalDiscoveredWorld(place: DigitalWorldDiscoveredPlace, phase: Float) {
    val description = place.label + " " + place.purpose + " " + place.subtitle
    val terrace = listOf("平台", "露台", "云", "观景", "天台").any { it in description }
    val indoor = !terrace && listOf("室", "屋", "馆", "店", "居", "房").any { it in description }
    val seed = place.code.hashCode() and Int.MAX_VALUE

    if (terrace) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF719BAA), Color(0xFFB8D7D2), Color(0xFFF4EDE2))),
            size = Size(DIGITAL_WORLD_WIDTH, DIGITAL_WORLD_HEIGHT))
        repeat(12) { i ->
            val x = ((i * 227 + seed % 193) % 1_620).toFloat()
            val y = (90 + i * 79 % 800).toFloat()
            drawOval(Color.White.copy(alpha = .29f + i % 3 * .07f),
                Offset(x - 95f, y), Size(240f, 58f))
        }
        drawRoundRect(Color(0xFF3B595C).copy(alpha = .30f), Offset(110f, 318f),
            Size(1_380f, 630f), CornerRadius(65f))
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFE7DDD0), Color(0xFFB9B8AD))),
            Offset(110f, 295f), Size(1_380f, 615f), CornerRadius(55f))
        // Rails surround a walkable raised terrace; wooden seams stay parallel.
        for (i in 0..11) {
            val x = 145f + i * 118f
            drawLine(Color(0xFF537D81), Offset(x, 302f), Offset(x, 247f), 9f, StrokeCap.Round)
            drawLine(Color(0xFF537D81), Offset(x, 905f), Offset(x, 945f), 9f, StrokeCap.Round)
        }
        drawLine(Color(0xFF436F75), Offset(145f, 245f), Offset(1_455f, 245f), 12f)
        drawLine(Color(0xFF436F75), Offset(145f, 943f), Offset(1_455f, 943f), 12f)
        for (row in 0..6) {
            val y = 362f + row * 77f
            drawLine(Color(0xFF6C6A61).copy(alpha = .12f),
                Offset(135f, y), Offset(1_465f, y), 2f)
        }
    } else if (indoor) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF273D37), Color(0xFF0B1714))),
            size = Size(DIGITAL_WORLD_WIDTH, DIGITAL_WORLD_HEIGHT))
        drawRoundRect(Color.Black.copy(alpha = .27f), Offset(38f, 58f),
            Size(1_524f, 957f), CornerRadius(36f))
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFE8E7E1), Color(0xFFD3D7D1))),
            Offset(56f, 77f), Size(1_488f, 926f), CornerRadius(30f))
        drawRoundRect(Color(0xFF80978D), Offset(56f, 210f), Size(1_488f, 785f))
        drawLine(Color(0xFF506860), Offset(56f, 210f), Offset(1_544f, 210f), 10f)
        for (row in 0..6) {
            val y = 302f + row * 104f
            drawLine(Color(0xFF263A35).copy(alpha = .10f),
                Offset(60f, y), Offset(1_540f, y), 2f)
            for (col in 0..4) {
                val x = 90f + col * 335f + if (row % 2 == 0) 0f else 120f
                if (x < 1_540f) drawLine(Color(0xFF263A35).copy(alpha = .075f),
                    Offset(x, y), Offset(x, (y + 99f).coerceAtMost(996f)), 2f)
            }
        }
        drawRoundRect(Color(0xFF455950), Offset(965f, 94f), Size(410f, 100f), CornerRadius(12f))
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFC8DFD9), Color(0xFF78A29A))),
            Offset(978f, 107f), Size(384f, 74f), CornerRadius(8f))
        drawRect(Color(0xFF455950), Offset(1_163f, 107f), Size(7f, 74f))
    } else {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF85A9A4), Color(0xFFB2C7B1), Color(0xFF40594B))),
            size = Size(DIGITAL_WORLD_WIDTH, DIGITAL_WORLD_HEIGHT))
        val grove = Path().apply {
            moveTo(75f, 305f)
            cubicTo(460f, 208f, 1_260f, 212f, 1_523f, 345f)
            lineTo(1_522f, 991f)
            lineTo(78f, 991f)
            close()
        }
        drawPath(grove, Brush.verticalGradient(listOf(Color(0xFF98B5A0), Color(0xFF547A5D))))
        val path = Path().apply {
            moveTo(745f, 1_005f)
            cubicTo(630f, 796f, 930f, 646f, 802f, 490f)
            cubicTo(715f, 405f, 820f, 390f, 830f, 335f)
        }
        drawPath(path, Color(0xFF3A5142).copy(alpha = .23f),
            style = Stroke(115f, cap = StrokeCap.Round))
        drawPath(path, Color(0xFFE2D5B9),
            style = Stroke(94f, cap = StrokeCap.Round))
        repeat(15) { i ->
            val x = 120f + ((i * 307 + seed % 97) % 1_320)
            val y = 345f + ((i * 97 + seed % 137) % 515)
            if (x in 615f..990f) return@repeat
            drawOval(Color(0xFF172F20).copy(alpha = .21f), Offset(x - 37f, y + 12f), Size(74f, 24f))
            drawCircle(Color(0xFF446D4C), 38f, Offset(x, y - 20f))
            drawCircle(Color(0xFF88AC83).copy(alpha = .85f), 24f, Offset(x - 12f, y - 31f))
        }
    }
    // Shared visual atmosphere without implying unregistered activities, objects or new NPCs.
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .07f + phase * .05f), Color.Transparent)),
        330f, Offset(1_255f, 310f))
}
