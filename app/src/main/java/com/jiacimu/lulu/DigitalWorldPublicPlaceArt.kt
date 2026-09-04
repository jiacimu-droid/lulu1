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
import com.jiacimu.lulu.data.DigitalWorldPublicPlaces

/**
 * Hand-authored 2.5D venue kits. They intentionally share one miniature-world camera language but
 * use different materials, lighting and silhouettes so public places feel like destinations rather
 * than the same room with a different label.
 */
internal fun DrawScope.drawDigitalPublicPlaceWorld(sceneCode: String, lightPhase: Float): Boolean {
    return when (sceneCode) {
        DigitalWorldPublicPlaces.GAME_HALL -> {
            drawGameHallWorld(lightPhase)
            true
        }
        DigitalWorldPublicPlaces.READING_LOUNGE -> {
            drawReadingLoungeWorld(lightPhase)
            true
        }
        DigitalWorldPublicPlaces.CAFE -> {
            drawCafeWorld(lightPhase)
            true
        }
        DigitalWorldPublicPlaces.COURTYARD -> {
            drawCourtyardWorld(lightPhase)
            true
        }
        else -> false
    }
}

private fun DrawScope.drawGameHallWorld(lightPhase: Float) {
    drawRect(
        Brush.radialGradient(
            listOf(Color(0xFF32423F), Color(0xFF111B1A), Color(0xFF060B0A)),
            center = Offset(820f, 390f),
            radius = 1_120f,
        ),
        size = Size(DIGITAL_WORLD_WIDTH, DIGITAL_WORLD_HEIGHT),
    )
    drawVenueFloor(Color(0xFF172320), Color(0xFF30433E))
    drawWideWindow(118f, 86f, 610f, 128f, Color(0xFF688D88), lightPhase)
    drawSignPanel(680f, 92f, 250f, 80f, Color(0xFF8BE7CB), "")

    val pods = listOf(
        Offset(210f, 350f), Offset(500f, 305f), Offset(1_100f, 305f), Offset(1_390f, 350f),
        Offset(330f, 720f), Offset(1_270f, 720f),
    )
    pods.forEachIndexed { index, p ->
        drawArcadePod(p.x, p.y, if (index % 2 == 0) Color(0xFF4A6C66) else Color(0xFF675F72), lightPhase)
    }
    drawRoundRect(
        Brush.radialGradient(listOf(Color(0xFF536A64), Color(0xFF24312E))),
        Offset(605f, 520f),
        Size(390f, 250f),
        CornerRadius(92f),
    )
    drawRoundRect(Color.White.copy(alpha = .13f), Offset(624f, 540f), Size(352f, 210f), CornerRadius(80f), style = Stroke(3f))
    repeat(4) { i ->
        drawCircle(Color(0xFF9DFFE2).copy(alpha = .16f + lightPhase * .06f), 18f, Offset(685f + i * 78f, 650f))
    }
    drawCircle(Brush.radialGradient(listOf(Color(0xFF9EFFE0).copy(alpha = .16f), Color.Transparent)), 300f, Offset(800f, 630f))
}

private fun DrawScope.drawReadingLoungeWorld(lightPhase: Float) {
    drawRect(
        Brush.verticalGradient(listOf(Color(0xFF6E827E), Color(0xFFC8C5B8), Color(0xFF8E8A7E))),
        size = Size(DIGITAL_WORLD_WIDTH, DIGITAL_WORLD_HEIGHT),
    )
    drawVenueFloor(Color(0xFFB1A998), Color(0xFF877D6D))
    drawWideWindow(880f, 80f, 540f, 150f, Color(0xFF8EAAA6), lightPhase)
    drawBookshelf(115f, 170f, 300f, 470f)
    drawBookshelf(1_185f, 170f, 300f, 470f)
    drawReadingIsland(800f, 620f)
    drawReadingIsland(520f, 795f)
    drawReadingIsland(1_080f, 795f)
    repeat(3) { i ->
        val x = 595f + i * 205f
        drawPendant(x, 280f, lightPhase)
    }
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE2A4).copy(alpha = .14f), Color.Transparent)), 360f, Offset(800f, 590f))
}

private fun DrawScope.drawCafeWorld(lightPhase: Float) {
    drawRect(
        Brush.radialGradient(
            listOf(Color(0xFF806E5B), Color(0xFF332A24), Color(0xFF120F0D)),
            center = Offset(1_150f, 160f),
            radius = 1_250f,
        ),
        size = Size(DIGITAL_WORLD_WIDTH, DIGITAL_WORLD_HEIGHT),
    )
    drawVenueFloor(Color(0xFF4B3C31), Color(0xFF796554))
    drawWideWindow(150f, 78f, 560f, 148f, Color(0xFF7A9A96), lightPhase)

    drawRoundRect(Color(0xFF211B18), Offset(920f, 180f), Size(505f, 190f), CornerRadius(22f))
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFAA8765), Color(0xFF71543D))), Offset(940f, 290f), Size(465f, 180f), CornerRadius(18f))
    drawLine(Color.White.copy(alpha = .18f), Offset(960f, 306f), Offset(1_382f, 306f), 3f)
    repeat(5) { i ->
        drawCircle(Color(0xFFD9C39C), 12f, Offset(1_000f + i * 76f, 247f))
        drawLine(Color(0xFF8C6848), Offset(1_000f + i * 76f, 259f), Offset(1_000f + i * 76f, 276f), 4f, StrokeCap.Round)
    }

    listOf(Offset(340f, 560f), Offset(690f, 680f), Offset(1_095f, 680f), Offset(430f, 845f), Offset(900f, 860f)).forEachIndexed { index, p ->
        drawCafeTable(p.x, p.y, index % 2 == 0)
    }
    drawPlantCluster(1_420f, 780f)
    repeat(4) { i -> drawPendant(260f + i * 230f, 260f, lightPhase) }
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFDCA0).copy(alpha = .15f), Color.Transparent)), 390f, Offset(760f, 650f))
}

private fun DrawScope.drawCourtyardWorld(lightPhase: Float) {
    drawRect(
        Brush.verticalGradient(listOf(Color(0xFF688A8E), Color(0xFFB8C3B8), Color(0xFF495F51))),
        size = Size(DIGITAL_WORLD_WIDTH, DIGITAL_WORLD_HEIGHT),
    )
    repeat(11) { i ->
        val x = (i * 173f + 90f) % DIGITAL_WORLD_WIDTH
        val y = 90f + (i * 53f % 140f)
        drawCircle(Color.White.copy(alpha = .16f), 22f + i % 3 * 8f, Offset(x, y))
    }

    val lawn = Path().apply {
        moveTo(60f, 290f)
        cubicTo(350f, 205f, 1_220f, 210f, 1_540f, 330f)
        lineTo(1_540f, 1_000f)
        lineTo(60f, 1_000f)
        close()
    }
    drawPath(lawn, Brush.verticalGradient(listOf(Color(0xFF6D8B6E), Color(0xFF435F4A))))
    drawPath(
        Path().apply {
            moveTo(180f, 1_000f)
            cubicTo(420f, 800f, 620f, 700f, 820f, 620f)
            cubicTo(1_020f, 540f, 1_230f, 470f, 1_430f, 300f)
        },
        Color(0xFFC2BCA8),
        style = Stroke(92f, cap = StrokeCap.Round),
    )
    drawPath(
        Path().apply {
            moveTo(180f, 1_000f)
            cubicTo(420f, 800f, 620f, 700f, 820f, 620f)
            cubicTo(1_020f, 540f, 1_230f, 470f, 1_430f, 300f)
        },
        Color.White.copy(alpha = .18f),
        style = Stroke(3f, cap = StrokeCap.Round),
    )
    drawOval(Brush.radialGradient(listOf(Color(0xFF90B9B4), Color(0xFF4B7771))), Offset(930f, 650f), Size(420f, 210f))
    drawOval(Color.White.copy(alpha = .18f), Offset(948f, 666f), Size(385f, 175f), style = Stroke(3f))
    listOf(Offset(210f, 450f), Offset(430f, 390f), Offset(1_350f, 520f), Offset(1_160f, 360f), Offset(390f, 780f)).forEach { drawTree(it.x, it.y) }
    drawBench(690f, 760f)
    drawBench(1_235f, 650f)
    repeat(18) { i ->
        val x = 160f + (i * 83 % 1_250)
        val y = 380f + (i * 67 % 480)
        drawCircle(Color(0xFFFFE7A8).copy(alpha = .22f + lightPhase * .18f), 2.5f + i % 2, Offset(x, y))
    }
}

private fun DrawScope.drawVenueFloor(base: Color, line: Color) {
    drawRect(Brush.verticalGradient(listOf(base.copy(alpha = .88f), base)), Offset(40f, 220f), Size(1_520f, 800f))
    val vanish = Offset(800f, 220f)
    for (x in 80..1_520 step 120) {
        drawLine(line.copy(alpha = .22f), vanish, Offset(x.toFloat(), 1_020f), 2f)
    }
    for (y in 330..980 step 100) {
        drawLine(line.copy(alpha = .16f), Offset(50f, y.toFloat()), Offset(1_550f, y.toFloat()), 2f)
    }
}

private fun DrawScope.drawWideWindow(x: Float, y: Float, w: Float, h: Float, tint: Color, lightPhase: Float) {
    drawRoundRect(Color(0xFF182523), Offset(x - 10f, y - 10f), Size(w + 20f, h + 20f), CornerRadius(18f))
    drawRoundRect(Brush.verticalGradient(listOf(tint.lighten(.25f), tint, Color(0xFF203733))), Offset(x, y), Size(w, h), CornerRadius(12f))
    repeat(13) { i ->
        val bw = 16f + i % 3 * 5f
        val bh = 22f + (i * 19 % 74)
        val bx = x + 18f + i * ((w - 36f) / 13f)
        drawRect(Color(0xFF1D302D).copy(alpha = .78f), Offset(bx, y + h - bh), Size(bw, bh))
        if (i % 2 == 0) drawCircle(Color(0xFFFFDB93).copy(alpha = .42f + lightPhase * .20f), 2.2f, Offset(bx + bw / 2f, y + h - bh * .45f))
    }
    drawLine(Color.White.copy(alpha = .30f), Offset(x + 12f, y + 12f), Offset(x + w - 25f, y + 12f), 2f)
}

private fun DrawScope.drawSignPanel(x: Float, y: Float, w: Float, h: Float, accent: Color, text: String) {
    drawRoundRect(Color.Black.copy(alpha = .28f), Offset(x + 7f, y + 9f), Size(w, h), CornerRadius(18f))
    drawRoundRect(Brush.linearGradient(listOf(accent.copy(alpha = .45f), Color(0xFF182422))), Offset(x, y), Size(w, h), CornerRadius(18f))
    drawRoundRect(accent.copy(alpha = .65f), Offset(x + 12f, y + 12f), Size(w - 24f, h - 24f), CornerRadius(12f), style = Stroke(2f))
}

private fun DrawScope.drawArcadePod(x: Float, y: Float, body: Color, lightPhase: Float) {
    drawOval(Color.Black.copy(alpha = .24f), Offset(x - 86f, y + 92f), Size(178f, 48f))
    drawRoundRect(body.darken(.72f), Offset(x - 72f, y - 16f), Size(144f, 130f), CornerRadius(20f))
    drawRoundRect(Brush.verticalGradient(listOf(body.lighten(.14f), body)), Offset(x - 68f, y - 46f), Size(136f, 118f), CornerRadius(18f))
    drawRoundRect(Color(0xFF081311), Offset(x - 50f, y - 28f), Size(100f, 66f), CornerRadius(12f))
    drawRoundRect(Brush.linearGradient(listOf(Color(0xFF5A8D84), Color(0xFF142C29))), Offset(x - 43f, y - 21f), Size(86f, 52f), CornerRadius(9f))
    drawCircle(Color(0xFF9EFFE0).copy(alpha = .55f + lightPhase * .25f), 6f, Offset(x - 28f, y + 61f))
    drawCircle(Color(0xFFFFD99A).copy(alpha = .65f), 5f, Offset(x - 8f, y + 61f))
    drawLine(Color.White.copy(alpha = .18f), Offset(x - 54f, y - 38f), Offset(x + 42f, y - 38f), 2f)
}

private fun DrawScope.drawBookshelf(x: Float, y: Float, w: Float, h: Float) {
    drawRoundRect(Color.Black.copy(alpha = .18f), Offset(x + 12f, y + 15f), Size(w, h), CornerRadius(18f))
    drawRoundRect(Brush.linearGradient(listOf(Color(0xFFA78968), Color(0xFF6F5843))), Offset(x, y), Size(w, h), CornerRadius(16f))
    repeat(5) { row ->
        val shelfY = y + 30f + row * 84f
        drawRect(Color(0xFF594635), Offset(x + 18f, shelfY + 57f), Size(w - 36f, 10f))
        repeat(9) { col ->
            val bx = x + 28f + col * 28f
            val bh = 36f + (row * 11 + col * 7) % 24
            val color = listOf(Color(0xFF7B8F86), Color(0xFF9B7F72), Color(0xFFB29A6C), Color(0xFF697D8A))[(row + col) % 4]
            drawRoundRect(color, Offset(bx, shelfY + 55f - bh), Size(18f, bh), CornerRadius(2f))
        }
    }
}

private fun DrawScope.drawReadingIsland(cx: Float, cy: Float) {
    drawOval(Color.Black.copy(alpha = .18f), Offset(cx - 125f, cy + 45f), Size(250f, 58f))
    drawRoundRect(Color(0xFF756E65), Offset(cx - 118f, cy - 8f), Size(236f, 72f), CornerRadius(32f))
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFD6CEC0), Color(0xFFAFA79A))), Offset(cx - 108f, cy - 34f), Size(216f, 70f), CornerRadius(30f))
    drawOval(Color(0xFF9A7555), Offset(cx - 40f, cy + 10f), Size(80f, 42f))
    drawOval(Color(0xFFC8A981), Offset(cx - 34f, cy + 6f), Size(68f, 32f))
}

private fun DrawScope.drawPendant(x: Float, y: Float, lightPhase: Float) {
    drawLine(Color(0xFF404844), Offset(x, 60f), Offset(x, y), 4f, StrokeCap.Round)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE5A3).copy(alpha = .30f + lightPhase * .12f), Color.Transparent)), 94f, Offset(x, y + 26f))
    val shade = Path().apply {
        moveTo(x - 34f, y)
        lineTo(x + 34f, y)
        lineTo(x + 22f, y + 34f)
        lineTo(x - 22f, y + 34f)
        close()
    }
    drawPath(shade, Brush.verticalGradient(listOf(Color(0xFFFFE0A2), Color(0xFFB98D57)), startY = y, endY = y + 34f))
}

private fun DrawScope.drawCafeTable(cx: Float, cy: Float, withCup: Boolean) {
    drawOval(Color.Black.copy(alpha = .20f), Offset(cx - 78f, cy + 44f), Size(160f, 44f))
    drawLine(Color(0xFF5C4939), Offset(cx, cy + 6f), Offset(cx, cy + 66f), 12f, StrokeCap.Round)
    drawOval(Brush.radialGradient(listOf(Color(0xFFD0B28E), Color(0xFF8B6A4F))), Offset(cx - 72f, cy - 18f), Size(144f, 58f))
    drawOval(Color.White.copy(alpha = .19f), Offset(cx - 62f, cy - 12f), Size(124f, 42f), style = Stroke(2f))
    if (withCup) {
        drawRoundRect(Color(0xFFE9DED0), Offset(cx - 15f, cy - 28f), Size(30f, 30f), CornerRadius(7f))
        drawCircle(Color(0xFF70513D), 8f, Offset(cx, cy - 17f))
    }
}

private fun DrawScope.drawPlantCluster(cx: Float, cy: Float) {
    drawOval(Color.Black.copy(alpha = .18f), Offset(cx - 55f, cy + 70f), Size(120f, 40f))
    drawRoundRect(Color(0xFF9A795C), Offset(cx - 35f, cy + 36f), Size(70f, 65f), CornerRadius(12f))
    repeat(8) { i ->
        val dx = (i * 29 % 88 - 44).toFloat()
        val dy = (i * 41 % 72 - 64).toFloat()
        drawOval(Brush.linearGradient(listOf(Color(0xFF6C9A79), Color(0xFF345D49))), Offset(cx + dx - 22f, cy + dy - 22f), Size(44f, 70f))
    }
}

private fun DrawScope.drawTree(cx: Float, cy: Float) {
    drawOval(Color.Black.copy(alpha = .16f), Offset(cx - 55f, cy + 66f), Size(115f, 38f))
    drawLine(Color(0xFF6D5541), Offset(cx, cy + 80f), Offset(cx, cy - 18f), 18f, StrokeCap.Round)
    drawCircle(Color(0xFF54765C), 62f, Offset(cx - 22f, cy - 38f))
    drawCircle(Color(0xFF63866A), 70f, Offset(cx + 34f, cy - 48f))
    drawCircle(Color(0xFF789578), 55f, Offset(cx + 4f, cy - 92f))
}

private fun DrawScope.drawBench(cx: Float, cy: Float) {
    drawOval(Color.Black.copy(alpha = .16f), Offset(cx - 100f, cy + 48f), Size(205f, 36f))
    drawRoundRect(Color(0xFF765E47), Offset(cx - 90f, cy), Size(180f, 34f), CornerRadius(8f))
    drawRoundRect(Color(0xFF876E55), Offset(cx - 90f, cy - 48f), Size(180f, 30f), CornerRadius(7f))
    listOf(cx - 65f, cx + 65f).forEach { x ->
        drawLine(Color(0xFF4C514D), Offset(x, cy + 24f), Offset(x, cy + 67f), 9f, StrokeCap.Round)
    }
}

private fun Color.lighten(amount: Float): Color = copy(
    red = (red + (1f - red) * amount).coerceIn(0f, 1f),
    green = (green + (1f - green) * amount).coerceIn(0f, 1f),
    blue = (blue + (1f - blue) * amount).coerceIn(0f, 1f),
)

private fun Color.darken(factor: Float): Color = copy(
    red = red * factor,
    green = green * factor,
    blue = blue * factor,
)
