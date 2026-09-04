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
import com.jiacimu.lulu.games.WorldVector

/**
 * Hand-authored 2.5D venue kits. Every public place has a distinct material language, focal point and
 * activity topology. Spatial anchors are also reflected in the scenery so “去窗边 / 去吧台 / 去
 * 长椅” corresponds to something the player can actually see.
 */
internal fun DrawScope.drawDigitalPublicPlaceWorld(sceneCode: String, lightPhase: Float): Boolean = when (sceneCode) {
    DigitalWorldPublicPlaces.GAME_HALL -> { drawGameHallWorld(lightPhase); true }
    DigitalWorldPublicPlaces.READING_LOUNGE -> { drawReadingLoungeWorld(lightPhase); true }
    DigitalWorldPublicPlaces.CAFE -> { drawCafeWorld(lightPhase); true }
    DigitalWorldPublicPlaces.COURTYARD -> { drawCourtyardWorld(lightPhase); true }
    else -> false
}

private fun DrawScope.drawGameHallWorld(lightPhase: Float) {
    drawRect(
        Brush.radialGradient(
            listOf(Color(0xFF31433E), Color(0xFF111C19), Color(0xFF050908)),
            center = Offset(810f, 360f), radius = 1_160f,
        ), size = Size(DIGITAL_WORLD_WIDTH, DIGITAL_WORLD_HEIGHT),
    )
    drawVenueShell(Color(0xFF18231F), Color(0xFF31433C), warm = false)
    drawWideWindow(105f, 86f, 560f, 125f, Color(0xFF5C817B), lightPhase)
    drawCeilingRail(110f, 735f, 247f, lightPhase, Color(0xFF8BFFE1))
    drawCeilingRail(865f, 1_485f, 247f, lightPhase, Color(0xFFA8C7FF))

    drawRoundRect(Color.Black.copy(alpha = .42f), Offset(690f, 85f), Size(220f, 126f), CornerRadius(18f))
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF243C36), Color(0xFF101C19))), Offset(703f, 97f), Size(194f, 101f), CornerRadius(13f))
    repeat(5) { i ->
        val width = 105f - i * 10f
        drawRoundRect(
            if (i == 0) Color(0xFF9EFFE0).copy(alpha = .58f) else Color.White.copy(alpha = .12f),
            Offset(723f, 114f + i * 14f), Size(width, 5f), CornerRadius(4f),
        )
    }

    val pods = listOf(
        Offset(220f, 405f), Offset(490f, 355f), Offset(1_110f, 355f), Offset(1_380f, 405f),
        Offset(300f, 770f), Offset(1_300f, 770f),
    )
    pods.forEachIndexed { index, p ->
        drawArcadePod(p.x, p.y, if (index % 2 == 0) Color(0xFF4B7067) else Color(0xFF635B72), lightPhase)
    }

    drawOval(Color.Black.copy(alpha = .24f), Offset(600f, 600f), Size(410f, 265f))
    drawRoundRect(Brush.radialGradient(listOf(Color(0xFF526C64), Color(0xFF26342F))), Offset(610f, 555f), Size(380f, 250f), CornerRadius(102f))
    drawRoundRect(Color.White.copy(alpha = .11f), Offset(630f, 577f), Size(340f, 207f), CornerRadius(87f), style = Stroke(3f))
    repeat(4) { i -> drawRoundSeat(690f + i * 75f, 708f, if (i % 2 == 0) Color(0xFF8AB6A9) else Color(0xFF918399)) }

    drawGuideStrip(235f, 560f, 520f, 520f, Color(0xFF8BFFE1), lightPhase)
    drawGuideStrip(1_365f, 560f, 1_080f, 520f, Color(0xFFAABEFF), lightPhase)
    drawGuideStrip(800f, 905f, 800f, 790f, Color(0xFF8BFFE1), lightPhase)
    drawVenueAnchorGlows(DigitalWorldPublicPlaces.GAME_HALL, lightPhase, Color(0xFF9EFFE0))
}

private fun DrawScope.drawReadingLoungeWorld(lightPhase: Float) {
    drawRect(
        Brush.verticalGradient(listOf(Color(0xFF81918C), Color(0xFFC7C0B2), Color(0xFF85796B))),
        size = Size(DIGITAL_WORLD_WIDTH, DIGITAL_WORLD_HEIGHT),
    )
    drawVenueShell(Color(0xFFA79D8D), Color(0xFF796D5E), warm = true)
    drawWideWindow(885f, 82f, 520f, 158f, Color(0xFF91AAA4), lightPhase)
    drawBookshelf(105f, 168f, 315f, 500f)
    drawBookshelf(1_180f, 168f, 315f, 500f)
    drawLowShelf(480f, 240f, 310f)
    drawReadingIsland(800f, 655f, 250f)
    drawReadingIsland(520f, 825f, 210f)
    drawReadingIsland(1_085f, 830f, 215f)
    drawWindowBench(1_020f, 405f, 285f)
    repeat(4) { i -> drawPendant(500f + i * 205f, 290f, lightPhase) }
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE0A0).copy(alpha = .17f), Color.Transparent)), 400f, Offset(800f, 610f))
    drawVenueAnchorGlows(DigitalWorldPublicPlaces.READING_LOUNGE, lightPhase, Color(0xFFFFE0A5))
}

private fun DrawScope.drawCafeWorld(lightPhase: Float) {
    drawRect(
        Brush.radialGradient(
            listOf(Color(0xFF806D59), Color(0xFF33281F), Color(0xFF100C0A)),
            center = Offset(1_180f, 150f), radius = 1_280f,
        ), size = Size(DIGITAL_WORLD_WIDTH, DIGITAL_WORLD_HEIGHT),
    )
    drawVenueShell(Color(0xFF4D3A2E), Color(0xFF715945), warm = true)
    drawWideWindow(125f, 77f, 585f, 155f, Color(0xFF77958F), lightPhase)

    drawRoundRect(Color.Black.copy(alpha = .32f), Offset(900f, 180f), Size(545f, 320f), CornerRadius(28f))
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF4A382D), Color(0xFF251B17))), Offset(925f, 180f), Size(495f, 126f), CornerRadius(20f))
    repeat(3) { row ->
        drawLine(Color(0xFF9A7655).copy(alpha = .55f), Offset(955f, 207f + row * 30f), Offset(1_382f, 207f + row * 30f), 5f, StrokeCap.Round)
        repeat(7) { col -> drawCircle(Color(0xFFD2B184), 6f + (col % 2), Offset(985f + col * 55f, 195f + row * 30f)) }
    }
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFB18C67), Color(0xFF6C4D36))), Offset(935f, 300f), Size(475f, 185f), CornerRadius(18f))
    drawRoundRect(Color.White.copy(alpha = .12f), Offset(950f, 313f), Size(445f, 155f), CornerRadius(15f), style = Stroke(2f))
    repeat(4) { i -> drawBarStool(990f + i * 105f, 520f) }

    drawWindowBench(190f, 345f, 410f)
    listOf(Offset(330f, 570f), Offset(700f, 710f), Offset(1_090f, 720f), Offset(450f, 875f), Offset(900f, 890f)).forEachIndexed { index, p ->
        drawCafeTable(p.x, p.y, index % 2 == 0)
    }
    drawPlantCluster(1_420f, 815f)
    drawPlantCluster(190f, 840f)
    repeat(4) { i -> drawPendant(250f + i * 205f, 278f, lightPhase) }
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFD69A).copy(alpha = .17f), Color.Transparent)), 410f, Offset(745f, 650f))
    drawVenueAnchorGlows(DigitalWorldPublicPlaces.CAFE, lightPhase, Color(0xFFFFD79B))
}

private fun DrawScope.drawCourtyardWorld(lightPhase: Float) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF65878A), Color(0xFFB5C2B5), Color(0xFF405846))), size = Size(DIGITAL_WORLD_WIDTH, DIGITAL_WORLD_HEIGHT))
    repeat(12) { i ->
        val x = (i * 167f + 80f) % DIGITAL_WORLD_WIDTH
        val y = 80f + (i * 59f % 155f)
        drawCircle(Color.White.copy(alpha = .14f), 25f + i % 3 * 9f, Offset(x, y))
    }

    val lawn = Path().apply {
        moveTo(45f, 275f)
        cubicTo(335f, 205f, 1_255f, 205f, 1_555f, 330f)
        lineTo(1_555f, 1_020f)
        lineTo(45f, 1_020f)
        close()
    }
    drawPath(lawn, Brush.verticalGradient(listOf(Color(0xFF789476), Color(0xFF405C45))))

    val path = Path().apply {
        moveTo(150f, 1_020f)
        cubicTo(380f, 825f, 620f, 715f, 820f, 630f)
        cubicTo(1_030f, 540f, 1_245f, 455f, 1_465f, 285f)
    }
    drawPath(path, Color.Black.copy(alpha = .13f), style = Stroke(105f, cap = StrokeCap.Round))
    drawPath(path, Brush.linearGradient(listOf(Color(0xFFD0C7B1), Color(0xFFB1A68F))), style = Stroke(91f, cap = StrokeCap.Round))
    drawPath(path, Color.White.copy(alpha = .18f), style = Stroke(3f, cap = StrokeCap.Round))

    drawOval(Color.Black.copy(alpha = .15f), Offset(900f, 675f), Size(470f, 235f))
    drawOval(Brush.radialGradient(listOf(Color(0xFF96C0B9), Color(0xFF47756C))), Offset(915f, 650f), Size(450f, 220f))
    drawOval(Color.White.copy(alpha = .20f), Offset(935f, 670f), Size(410f, 180f), style = Stroke(3f))
    repeat(6) { i -> drawOval(Color.White.copy(alpha = .08f), Offset(990f + i * 45f, 710f + (i % 2) * 23f), Size(65f, 18f), style = Stroke(2f)) }

    listOf(Offset(195f, 455f), Offset(420f, 395f), Offset(1_370f, 520f), Offset(1_160f, 350f), Offset(390f, 805f), Offset(1_470f, 830f)).forEach { drawTree(it.x, it.y) }
    drawBench(700f, 790f)
    drawBench(1_235f, 635f)
    drawFlowerBed(1_245f, 465f)
    drawFlowerBed(250f, 670f)
    repeat(22) { i ->
        val x = 145f + (i * 83 % 1_300)
        val y = 360f + (i * 67 % 500)
        drawCircle(Color(0xFFFFE6A3).copy(alpha = .20f + lightPhase * .18f), 2.5f + i % 2, Offset(x, y))
    }
    drawVenueAnchorGlows(DigitalWorldPublicPlaces.COURTYARD, lightPhase, Color(0xFFC9FFE0))
}

private fun DrawScope.drawVenueShell(base: Color, line: Color, warm: Boolean) {
    drawRoundRect(Color.Black.copy(alpha = .30f), Offset(35f, 58f), Size(1_530f, 960f), CornerRadius(34f))
    drawRect(Brush.verticalGradient(listOf(base.lighten(if (warm) .18f else .10f), base)), Offset(45f, 220f), Size(1_510f, 790f))
    val vanish = Offset(800f, 220f)
    for (x in 95..1_505 step 155) drawLine(line.copy(alpha = .17f), vanish, Offset(x.toFloat(), 1_010f), 2f)
    for (y in 355..980 step 125) drawLine(line.copy(alpha = .11f), Offset(52f, y.toFloat()), Offset(1_548f, y.toFloat() + 4f), 2f)
    drawLine(Color.White.copy(alpha = .12f), Offset(50f, 224f), Offset(1_550f, 224f), 2f)
}

private fun DrawScope.drawVenueAnchorGlows(sceneCode: String, lightPhase: Float, accent: Color) {
    DigitalWorldVenueAnchors.forScene(sceneCode).forEachIndexed { index, anchor ->
        val r = 28f + (index % 2) * 5f
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .05f + lightPhase * .03f), Color.Transparent)), r * 2.4f, anchor.position.toOffset())
        drawCircle(accent.copy(alpha = .10f + lightPhase * .05f), r, anchor.position.toOffset(), style = Stroke(2f))
    }
}

private fun DrawScope.drawGuideStrip(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, lightPhase: Float) {
    drawLine(color.copy(alpha = .08f + lightPhase * .05f), Offset(x1, y1), Offset(x2, y2), 5f, StrokeCap.Round)
    drawLine(Color.White.copy(alpha = .05f), Offset(x1, y1 - 2f), Offset(x2, y2 - 2f), 1f, StrokeCap.Round)
}

private fun DrawScope.drawCeilingRail(x1: Float, x2: Float, y: Float, lightPhase: Float, accent: Color) {
    drawLine(Color(0xFF232D2A), Offset(x1, y), Offset(x2, y), 8f, StrokeCap.Round)
    repeat(6) { i ->
        val x = x1 + (x2 - x1) * (i + .5f) / 6f
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = .23f + lightPhase * .09f), Color.Transparent)), 34f, Offset(x, y + 12f))
        drawCircle(accent.copy(alpha = .65f), 3.8f, Offset(x, y + 8f))
    }
}

private fun DrawScope.drawWideWindow(x: Float, y: Float, w: Float, h: Float, tint: Color, lightPhase: Float) {
    drawRoundRect(Color(0xFF18231F), Offset(x - 10f, y - 10f), Size(w + 20f, h + 20f), CornerRadius(18f))
    drawRoundRect(Brush.verticalGradient(listOf(tint.lighten(.28f), tint, Color(0xFF203632))), Offset(x, y), Size(w, h), CornerRadius(12f))
    repeat(13) { i ->
        val bw = 16f + i % 3 * 5f
        val bh = 22f + (i * 19 % 74)
        val bx = x + 18f + i * ((w - 36f) / 13f)
        drawRect(Color(0xFF1B2D29).copy(alpha = .78f), Offset(bx, y + h - bh), Size(bw, bh))
        if (i % 2 == 0) drawCircle(Color(0xFFFFD98D).copy(alpha = .40f + lightPhase * .20f), 2.2f, Offset(bx + bw / 2f, y + h - bh * .45f))
    }
    drawLine(Color.White.copy(alpha = .32f), Offset(x + 12f, y + 12f), Offset(x + w - 25f, y + 12f), 2f)
}

private fun DrawScope.drawArcadePod(x: Float, y: Float, body: Color, lightPhase: Float) {
    drawOval(Color.Black.copy(alpha = .28f), Offset(x - 88f, y + 94f), Size(182f, 50f))
    drawRoundRect(body.darken(.66f), Offset(x - 76f, y - 10f), Size(152f, 138f), CornerRadius(22f))
    val side = Path().apply {
        moveTo(x + 68f, y - 44f)
        lineTo(x + 83f, y - 31f)
        lineTo(x + 83f, y + 96f)
        lineTo(x + 68f, y + 82f)
        close()
    }
    drawPath(side, body.darken(.50f))
    drawRoundRect(Brush.verticalGradient(listOf(body.lighten(.16f), body)), Offset(x - 70f, y - 48f), Size(138f, 121f), CornerRadius(19f))
    drawRoundRect(Color(0xFF060D0B), Offset(x - 52f, y - 29f), Size(104f, 69f), CornerRadius(13f))
    drawRoundRect(Brush.linearGradient(listOf(Color(0xFF5C9187), Color(0xFF17322D), Color(0xFF07110F))), Offset(x - 45f, y - 22f), Size(90f, 55f), CornerRadius(9f))
    drawLine(Color.White.copy(alpha = .19f), Offset(x - 35f, y - 14f), Offset(x + 26f, y + 14f), 2f)
    drawCircle(Color(0xFF9EFFE0).copy(alpha = .58f + lightPhase * .22f), 6f, Offset(x - 29f, y + 62f))
    drawCircle(Color(0xFFFFD89B).copy(alpha = .68f), 5f, Offset(x - 8f, y + 62f))
    drawCircle(Color(0xFFB3C4FF).copy(alpha = .58f), 4.5f, Offset(x + 11f, y + 62f))
}

private fun DrawScope.drawBookshelf(x: Float, y: Float, w: Float, h: Float) {
    drawRoundRect(Color.Black.copy(alpha = .20f), Offset(x + 13f, y + 16f), Size(w, h), CornerRadius(18f))
    drawRoundRect(Brush.linearGradient(listOf(Color(0xFFA88968), Color(0xFF6A513C))), Offset(x, y), Size(w, h), CornerRadius(17f))
    repeat(5) { row ->
        val shelfY = y + 30f + row * 88f
        drawRect(Color(0xFF513C2D), Offset(x + 18f, shelfY + 60f), Size(w - 36f, 10f))
        repeat(9) { col ->
            val bx = x + 28f + col * 29f
            val bh = 36f + (row * 11 + col * 7) % 25
            val color = listOf(Color(0xFF72897F), Color(0xFF987B70), Color(0xFFAF9567), Color(0xFF657884))[(row + col) % 4]
            drawRoundRect(color, Offset(bx, shelfY + 57f - bh), Size(18f, bh), CornerRadius(2f))
        }
    }
    drawLine(Color.White.copy(alpha = .16f), Offset(x + 12f, y + 12f), Offset(x + w - 18f, y + 12f), 2f)
}

private fun DrawScope.drawLowShelf(x: Float, y: Float, w: Float) {
    drawOval(Color.Black.copy(alpha = .14f), Offset(x + 10f, y + 77f), Size(w, 30f))
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFA88C70), Color(0xFF725943))), Offset(x, y), Size(w, 88f), CornerRadius(13f))
    repeat(4) { i -> drawRoundRect(Color(0xFF342A22).copy(alpha = .30f), Offset(x + 18f + i * 70f, y + 17f), Size(54f, 54f), CornerRadius(6f)) }
}

private fun DrawScope.drawReadingIsland(cx: Float, cy: Float, width: Float) {
    drawOval(Color.Black.copy(alpha = .18f), Offset(cx - width / 2f, cy + 48f), Size(width, 60f))
    drawRoundRect(Color(0xFF73695F), Offset(cx - width * .47f, cy - 8f), Size(width * .94f, 75f), CornerRadius(34f))
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFD8CFC0), Color(0xFFADA295))), Offset(cx - width * .43f, cy - 37f), Size(width * .86f, 73f), CornerRadius(31f))
    drawOval(Color(0xFF987351), Offset(cx - 42f, cy + 10f), Size(84f, 43f))
    drawOval(Color(0xFFC8A77E), Offset(cx - 35f, cy + 6f), Size(70f, 33f))
}

private fun DrawScope.drawWindowBench(x: Float, y: Float, w: Float) {
    drawOval(Color.Black.copy(alpha = .16f), Offset(x + 12f, y + 73f), Size(w, 36f))
    drawRoundRect(Color(0xFF705E4F), Offset(x, y + 45f), Size(w, 48f), CornerRadius(16f))
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFD7CBB8), Color(0xFFA99B87))), Offset(x + 8f, y), Size(w - 16f, 62f), CornerRadius(20f))
    repeat(3) { i -> drawRoundRect(Color(0xFFE8DECF).copy(alpha = .75f), Offset(x + 24f + i * (w - 60f) / 3f, y + 13f), Size(52f, 31f), CornerRadius(11f)) }
}

private fun DrawScope.drawPendant(x: Float, y: Float, lightPhase: Float) {
    drawLine(Color(0xFF3F4742), Offset(x, 60f), Offset(x, y), 4f, StrokeCap.Round)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE3A0).copy(alpha = .31f + lightPhase * .12f), Color.Transparent)), 96f, Offset(x, y + 27f))
    val shade = Path().apply {
        moveTo(x - 35f, y)
        lineTo(x + 35f, y)
        lineTo(x + 22f, y + 35f)
        lineTo(x - 22f, y + 35f)
        close()
    }
    drawPath(shade, Brush.verticalGradient(listOf(Color(0xFFFFE1A3), Color(0xFFB98953)), startY = y, endY = y + 35f))
}

private fun DrawScope.drawCafeTable(cx: Float, cy: Float, withCup: Boolean) {
    drawOval(Color.Black.copy(alpha = .20f), Offset(cx - 80f, cy + 46f), Size(164f, 45f))
    drawLine(Color(0xFF584432), Offset(cx, cy + 6f), Offset(cx, cy + 68f), 12f, StrokeCap.Round)
    drawOval(Brush.radialGradient(listOf(Color(0xFFD2B18B), Color(0xFF87654A))), Offset(cx - 74f, cy - 19f), Size(148f, 60f))
    drawOval(Color.White.copy(alpha = .18f), Offset(cx - 63f, cy - 13f), Size(126f, 43f), style = Stroke(2f))
    drawCafeChair(cx - 93f, cy + 38f, -1f)
    drawCafeChair(cx + 93f, cy + 38f, 1f)
    if (withCup) {
        drawRoundRect(Color(0xFFE9DDD0), Offset(cx - 15f, cy - 29f), Size(30f, 31f), CornerRadius(7f))
        drawCircle(Color(0xFF704F39), 8f, Offset(cx, cy - 18f))
    }
}

private fun DrawScope.drawCafeChair(cx: Float, cy: Float, direction: Float) {
    drawOval(Color.Black.copy(alpha = .13f), Offset(cx - 31f, cy + 31f), Size(65f, 23f))
    drawRoundRect(Color(0xFF76604E), Offset(cx - 27f, cy - 5f), Size(54f, 42f), CornerRadius(13f))
    drawRoundRect(Color(0xFF8B735E), Offset(cx - 24f, cy - 32f), Size(48f, 36f), CornerRadius(13f))
    drawLine(Color(0xFF4F463D), Offset(cx - 18f * direction, cy + 27f), Offset(cx - 22f * direction, cy + 55f), 6f, StrokeCap.Round)
}

private fun DrawScope.drawBarStool(cx: Float, cy: Float) {
    drawOval(Color.Black.copy(alpha = .14f), Offset(cx - 35f, cy + 43f), Size(73f, 24f))
    drawOval(Brush.radialGradient(listOf(Color(0xFFB08A67), Color(0xFF72543D))), Offset(cx - 29f, cy - 12f), Size(58f, 29f))
    drawLine(Color(0xFF51463E), Offset(cx, cy + 7f), Offset(cx, cy + 55f), 7f, StrokeCap.Round)
}

private fun DrawScope.drawRoundSeat(cx: Float, cy: Float, color: Color) {
    drawOval(Color.Black.copy(alpha = .15f), Offset(cx - 32f, cy + 24f), Size(66f, 22f))
    drawOval(Brush.radialGradient(listOf(color.lighten(.20f), color.darken(.78f))), Offset(cx - 29f, cy - 10f), Size(58f, 42f))
}

private fun DrawScope.drawPlantCluster(cx: Float, cy: Float) {
    drawOval(Color.Black.copy(alpha = .18f), Offset(cx - 55f, cy + 70f), Size(120f, 40f))
    drawRoundRect(Color(0xFF98775A), Offset(cx - 35f, cy + 36f), Size(70f, 65f), CornerRadius(12f))
    repeat(8) { i ->
        val dx = (i * 29 % 88 - 44).toFloat()
        val dy = (i * 41 % 72 - 64).toFloat()
        drawOval(Brush.linearGradient(listOf(Color(0xFF6C9A77), Color(0xFF315C46))), Offset(cx + dx - 22f, cy + dy - 22f), Size(44f, 70f))
    }
}

private fun DrawScope.drawTree(cx: Float, cy: Float) {
    drawOval(Color.Black.copy(alpha = .16f), Offset(cx - 55f, cy + 66f), Size(115f, 38f))
    drawLine(Color(0xFF6B523E), Offset(cx, cy + 80f), Offset(cx, cy - 18f), 18f, StrokeCap.Round)
    drawCircle(Color(0xFF507456), 62f, Offset(cx - 22f, cy - 38f))
    drawCircle(Color(0xFF628667), 70f, Offset(cx + 34f, cy - 48f))
    drawCircle(Color(0xFF789674), 55f, Offset(cx + 4f, cy - 92f))
    drawCircle(Color.White.copy(alpha = .08f), 38f, Offset(cx - 6f, cy - 85f))
}

private fun DrawScope.drawBench(cx: Float, cy: Float) {
    drawOval(Color.Black.copy(alpha = .16f), Offset(cx - 102f, cy + 50f), Size(209f, 37f))
    drawRoundRect(Color(0xFF755D46), Offset(cx - 92f, cy), Size(184f, 35f), CornerRadius(8f))
    drawRoundRect(Color(0xFF866D53), Offset(cx - 92f, cy - 50f), Size(184f, 31f), CornerRadius(7f))
    repeat(5) { i -> drawLine(Color.White.copy(alpha = .08f), Offset(cx - 76f + i * 37f, cy - 45f), Offset(cx - 76f + i * 37f, cy + 28f), 1.5f) }
    listOf(cx - 66f, cx + 66f).forEach { x -> drawLine(Color(0xFF4B504C), Offset(x, cy + 24f), Offset(x, cy + 69f), 9f, StrokeCap.Round) }
}

private fun DrawScope.drawFlowerBed(cx: Float, cy: Float) {
    drawOval(Color.Black.copy(alpha = .12f), Offset(cx - 90f, cy + 35f), Size(185f, 48f))
    drawOval(Color(0xFF466143), Offset(cx - 86f, cy + 15f), Size(172f, 54f))
    repeat(14) { i ->
        val x = cx - 70f + (i * 31 % 140)
        val y = cy + (i * 19 % 42)
        val flower = listOf(Color(0xFFFFE1A3), Color(0xFFD9B8C5), Color(0xFFC8D8B3))[i % 3]
        drawCircle(flower.copy(alpha = .72f), 4f, Offset(x, y))
    }
}

private fun WorldVector.toOffset() = Offset(x, y)

private fun Color.lighten(amount: Float): Color = copy(
    red = (red + (1f - red) * amount).coerceIn(0f, 1f),
    green = (green + (1f - green) * amount).coerceIn(0f, 1f),
    blue = (blue + (1f - blue) * amount).coerceIn(0f, 1f),
)

private fun Color.darken(factor: Float): Color = copy(red = red * factor, green = green * factor, blue = blue * factor)
