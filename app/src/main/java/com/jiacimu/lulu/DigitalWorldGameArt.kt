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
import com.jiacimu.lulu.data.DigitalFurnitureCatalog
import com.jiacimu.lulu.data.DigitalFurnitureKind
import com.jiacimu.lulu.data.DigitalFurnitureStyle
import com.jiacimu.lulu.data.DigitalWorldItem
import com.jiacimu.lulu.data.DigitalWorldStore
import com.jiacimu.lulu.games.WorldObstacle
import com.jiacimu.lulu.games.WorldRectangle
import com.jiacimu.lulu.games.WorldVector

internal const val DIGITAL_WORLD_WIDTH = 1_600f
internal const val DIGITAL_WORLD_HEIGHT = 1_050f
internal val DIGITAL_WORLD_BOUNDS = WorldRectangle(54f, 70f, 1_546f, 1_000f)

internal data class DigitalRoomProp(
    val item: DigitalWorldItem,
    val style: DigitalFurnitureStyle,
    val bounds: WorldRectangle,
    val blocksMovement: Boolean,
) {
    val obstacle: WorldObstacle? = if (blocksMovement) WorldObstacle(item.id, bounds, item.name, digitalFurnitureAction(item, style.kind)) else null
}

internal fun buildDigitalRoomProps(items: List<DigitalWorldItem>): List<DigitalRoomProp> {
    if (items.isEmpty()) return emptyList()
    val wallKinds = setOf(DigitalFurnitureKind.WALL_ART, DigitalFurnitureKind.CLOCK, DigitalFurnitureKind.MIRROR)
    val result = mutableListOf<DigitalRoomProp>()
    items.filter { DigitalFurnitureCatalog.resolve(it).kind in wallKinds }.forEachIndexed { index, item ->
        val style = DigitalFurnitureCatalog.resolve(item)
        val (w, h) = furnitureSize(style.kind)
        result += DigitalRoomProp(item, style, centeredBounds(WorldVector(160f + index % 8 * 180f, 118f + index / 8 * 78f), w, h), false)
    }
    items.filter { DigitalFurnitureCatalog.resolve(it).kind == DigitalFurnitureKind.RUG }.forEachIndexed { index, item ->
        val spots = listOf(WorldVector(790f, 520f), WorldVector(350f, 700f), WorldVector(1_230f, 700f), WorldVector(790f, 860f))
        result += DigitalRoomProp(item, DigitalFurnitureCatalog.resolve(item), centeredBounds(spots[index % spots.size], 330f, 190f), false)
    }
    val floorItems = items.filter {
        val kind = DigitalFurnitureCatalog.resolve(it).kind
        kind !in wallKinds && kind != DigitalFurnitureKind.RUG
    }.sortedByDescending { furnitureArea(DigitalFurnitureCatalog.resolve(it).kind) }
    val spots = buildList {
        listOf(295f, 475f, 655f, 835f).forEachIndexed { row, y ->
            val xs = listOf(165f, 360f, 555f, 750f, 945f, 1_140f, 1_335f)
            (if (row % 2 == 0) xs else xs.reversed()).forEach { add(WorldVector(it, y)) }
        }
    }
    val occupied = mutableListOf<WorldRectangle>()
    floorItems.forEachIndexed { index, item ->
        val style = DigitalFurnitureCatalog.resolve(item)
        val (w, h) = furnitureSize(style.kind)
        val start = ((item.id + item.position).hashCode() and Int.MAX_VALUE) % spots.size
        val center = spots.indices.asSequence().map { spots[(start + it) % spots.size] }.firstOrNull { spot ->
            val trial = centeredBounds(spot, w, h).expanded(18f)
            occupied.none { overlap(trial, it) }
        } ?: WorldVector(130f + index % 9 * 155f, 900f - index % 3 * 46f)
        val bounds = centeredBounds(center, w, h)
        occupied += bounds.expanded(16f)
        result += DigitalRoomProp(item, style, bounds, true)
    }
    return result
}

private fun centeredBounds(c: WorldVector, w: Float, h: Float) = WorldRectangle(c.x - w / 2f, c.y - h / 2f, c.x + w / 2f, c.y + h / 2f)
private fun overlap(a: WorldRectangle, b: WorldRectangle) = a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

private fun furnitureSize(kind: DigitalFurnitureKind): Pair<Float, Float> = when (kind) {
    DigitalFurnitureKind.BED -> 270f to 168f
    DigitalFurnitureKind.SOFA -> 230f to 112f
    DigitalFurnitureKind.COFFEE_TABLE -> 142f to 92f
    DigitalFurnitureKind.TABLE -> 166f to 116f
    DigitalFurnitureKind.CHAIR -> 92f to 78f
    DigitalFurnitureKind.DESK -> 190f to 92f
    DigitalFurnitureKind.SHELF -> 190f to 76f
    DigitalFurnitureKind.CABINET -> 172f to 82f
    DigitalFurnitureKind.NIGHTSTAND -> 88f to 72f
    DigitalFurnitureKind.FLOOR_LAMP -> 74f to 74f
    DigitalFurnitureKind.TABLE_LAMP -> 68f to 62f
    DigitalFurnitureKind.PLANT -> 86f to 78f
    DigitalFurnitureKind.TV -> 176f to 74f
    DigitalFurnitureKind.CUSHION -> 66f to 54f
    DigitalFurnitureKind.BASKET -> 78f to 62f
    DigitalFurnitureKind.DECOR -> 66f to 56f
    DigitalFurnitureKind.RUG -> 330f to 190f
    DigitalFurnitureKind.MIRROR -> 112f to 98f
    DigitalFurnitureKind.WALL_ART -> 142f to 86f
    DigitalFurnitureKind.CLOCK -> 74f to 74f
}
private fun furnitureArea(kind: DigitalFurnitureKind): Float = furnitureSize(kind).let { it.first * it.second }

private fun digitalFurnitureAction(item: DigitalWorldItem, kind: DigitalFurnitureKind) = when (kind) {
    DigitalFurnitureKind.BED -> "我走到${item.name}边，坐下来感受了一下这个房间。"
    DigitalFurnitureKind.SOFA, DigitalFurnitureKind.CHAIR -> "我在${item.name}旁停下，轻轻坐了下来。"
    DigitalFurnitureKind.TV -> "我走到${item.name}前，伸手打开它。"
    DigitalFurnitureKind.SHELF, DigitalFurnitureKind.CABINET, DigitalFurnitureKind.NIGHTSTAND -> "我来到${item.name}旁，认真看看里面收着什么。"
    else -> "我走近${item.name}，仔细看了看。"
}

internal fun DrawScope.drawDigitalHomeWorld(props: List<DigitalRoomProp>, lightPhase: Float) {
    drawRect(Brush.radialGradient(listOf(Color(0xFF314743), Color(0xFF0B1513)), center = Offset(1_250f, 110f), radius = 1_520f), size = Size(DIGITAL_WORLD_WIDTH, DIGITAL_WORLD_HEIGHT))
    drawRoundRect(Color.Black.copy(alpha = .34f), Offset(38f, 50f), Size(1_524f, 962f), CornerRadius(40f))
    drawRoundRect(Brush.linearGradient(listOf(Color(0xFFE8E2D8), Color(0xFFB2C3BC), Color(0xFFD5C5B2))), Offset(54f, 70f), Size(1_492f, 930f), CornerRadius(30f))
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF304A45), Color(0xFF142725))), Offset(54f, 70f), Size(1_492f, 120f), CornerRadius(30f, 30f))
    drawRect(Color(0xFF142725), Offset(54f, 145f), Size(1_492f, 45f))
    drawLine(Color.Black.copy(alpha = .46f), Offset(58f, 190f), Offset(1_542f, 190f), 11f)
    drawLine(Color.White.copy(alpha = .18f), Offset(60f, 182f), Offset(1_540f, 182f), 2f)

    for (x in -920..1_760 step 104) drawLine(Color.White.copy(alpha = .13f), Offset(x.toFloat(), 190f), Offset(x + 920f, 1_000f), 2f)
    for (x in -80..2_560 step 104) drawLine(Color(0xFF405852).copy(alpha = .17f), Offset(x.toFloat(), 190f), Offset(x - 920f, 1_000f), 2f)
    repeat(8) { row ->
        val y = 262f + row * 101f
        drawLine(Color(0xFF43544E).copy(alpha = .12f), Offset(64f, y), Offset(1_536f, y + row * 4f), 3f)
    }

    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFC7E8E1), Color(0xFF638784), Color(0xFF18302E))), Offset(1_074f, 82f), Size(316f, 91f), CornerRadius(14f))
    repeat(13) { i ->
        val w = 14f + i % 4 * 4f
        val h = 20f + i * 17 % 48
        val x = 1_084f + i * 23f
        drawRect(Color(0xFF172927).copy(alpha = .76f), Offset(x, 170f - h), Size(w, h))
        if (i % 2 == 0) drawCircle(Color(0xFFFFDEA1).copy(alpha = .52f + lightPhase * .22f), 2.4f, Offset(x + w * .5f, 158f - h * .48f))
    }
    drawRect(Color(0xFF18302E), Offset(1_226f, 82f), Size(10f, 91f))
    drawRect(Color(0xFF18302E), Offset(1_074f, 122f), Size(316f, 9f))
    drawRoundRect(Color.White.copy(alpha = .25f), Offset(1_082f, 89f), Size(300f, 76f), CornerRadius(9f), style = Stroke(3f))

    val light = Path().apply { moveTo(1_070f, 170f); lineTo(1_392f, 170f); lineTo(1_535f, 810f); lineTo(975f, 650f); close() }
    drawPath(light, Brush.linearGradient(listOf(Color(0xFFF7FFF4).copy(alpha = .24f + lightPhase * .08f), Color(0xFFD0EFE4).copy(alpha = .07f), Color.Transparent), Offset(1_250f, 180f), Offset(1_350f, 760f)))
    repeat(22) { i -> drawCircle(Color(0xFFFFF7DA).copy(alpha = .13f + i % 4 * .035f), 2f + i % 3, Offset(1_030f + (i * 61 % 450), 215f + (i * 83 % 520))) }

    drawRoundRect(Color(0xFF102220), Offset(730f, 70f), Size(140f, 116f), CornerRadius(7f))
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF88A49B), Color(0xFF556E68))), Offset(750f, 90f), Size(100f, 96f), CornerRadius(5f))
    drawCircle(Color(0xFFE6C99A), 8f, Offset(838f, 139f))

    props.filter { it.style.kind == DigitalFurnitureKind.RUG }.forEach { drawDigitalProp(it, lightPhase) }
    props.filterNot { it.style.kind == DigitalFurnitureKind.RUG }.sortedBy { it.bounds.bottom }.forEach { drawDigitalProp(it, lightPhase) }

    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE7B3).copy(alpha = .10f), Color.Transparent)), 320f, Offset(350f, 795f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFFB9F5E4).copy(alpha = .09f), Color.Transparent)), 370f, Offset(1_280f, 700f))
    drawRoundRect(Color.White.copy(alpha = .16f), Offset(54f, 70f), Size(1_492f, 930f), CornerRadius(30f), style = Stroke(3f))
}

internal fun DrawScope.drawDigitalSharedWorld(sceneCode: String, lightPhase: Float) {
    val cloud = sceneCode == DigitalWorldStore.CLOUD_MEADOW
    drawRect(if (cloud) Brush.verticalGradient(listOf(Color(0xFF799EAD), Color(0xFFC4D8D7), Color(0xFFF2EFE5))) else Brush.radialGradient(listOf(Color(0xFF6E817E), Color(0xFF172724), Color(0xFF071311)), center = Offset(800f, 520f), radius = 1_050f), size = Size(DIGITAL_WORLD_WIDTH, DIGITAL_WORLD_HEIGHT))
    if (cloud) {
        repeat(18) { i ->
            val x = ((i * 271) % 1_650 - 80).toFloat(); val y = (180 + i * 137 % 760).toFloat(); val r = 74f + i % 5 * 24f
            drawOval(Color.White.copy(alpha = .30f + i % 3 * .08f), Offset(x - r, y - r * .36f), Size(r * 2.2f, r * .74f))
        }
        val island = Path().apply { moveTo(150f, 510f); cubicTo(330f, 310f, 1_250f, 300f, 1_450f, 520f); cubicTo(1_510f, 720f, 1_260f, 940f, 810f, 955f); cubicTo(340f, 945f, 70f, 740f, 150f, 510f); close() }
        drawPath(island, Color(0xFFEDF3EC)); drawPath(island, Color.White.copy(alpha = .62f), style = Stroke(5f))
    } else {
        repeat(5) { ring -> drawCircle(Color(0xFFB8F3E3).copy(alpha = .08f + lightPhase * .03f), 110f + ring * 86f, Offset(800f, 535f), style = Stroke(4f)) }
        drawCircle(Brush.radialGradient(listOf(Color(0xFFF4FFFC), Color(0xFF9BE0CF).copy(alpha = .40f), Color.Transparent)), 176f, Offset(800f, 535f))
    }
}

private fun DrawScope.drawDigitalProp(prop: DigitalRoomProp, lightPhase: Float) {
    val kind = prop.style.kind
    val b = prop.bounds
    val color = furnitureColor(prop.style.colorKey)
    if (kind == DigitalFurnitureKind.RUG) {
        drawOval(Color.Black.copy(alpha = .16f), Offset(b.left + 12f, b.top + 17f), Size(b.width, b.height))
        drawOval(Brush.radialGradient(listOf(color.copy(alpha = .92f), color.copy(alpha = .66f))), Offset(b.left, b.top), Size(b.width, b.height))
        drawOval(Color.White.copy(alpha = .32f), Offset(b.left + 3f, b.top + 3f), Size(b.width - 6f, b.height - 6f), style = Stroke(3f))
        return
    }
    if (kind in setOf(DigitalFurnitureKind.WALL_ART, DigitalFurnitureKind.MIRROR, DigitalFurnitureKind.CLOCK)) {
        drawRoundRect(Color.Black.copy(alpha = .30f), Offset(b.left + 9f, b.top + 11f), Size(b.width, b.height), CornerRadius(13f))
        if (kind == DigitalFurnitureKind.CLOCK) {
            drawCircle(color, b.width * .45f, b.center.toOffset()); drawCircle(Color.White.copy(alpha = .60f), b.width * .45f, b.center.toOffset(), style = Stroke(4f))
            drawLine(Color(0xFF1C2C29), b.center.toOffset(), Offset(b.center.x, b.center.y - 20f), 4f, StrokeCap.Round)
        } else if (kind == DigitalFurnitureKind.MIRROR) {
            drawRoundRect(Brush.linearGradient(listOf(Color(0xFFF0FFFF), Color(0xFF94BCBA), Color(0xFF587D7D))), Offset(b.left, b.top), Size(b.width, b.height), CornerRadius(35f))
            drawLine(Color.White.copy(alpha = .80f), Offset(b.left + 18f, b.top + 16f), Offset(b.right - 30f, b.bottom - 26f), 5f, StrokeCap.Round)
        } else {
            drawRoundRect(color, Offset(b.left, b.top), Size(b.width, b.height), CornerRadius(10f)); drawRoundRect(Color(0xFF152724), Offset(b.left + 9f, b.top + 9f), Size(b.width - 18f, b.height - 18f), CornerRadius(7f))
        }
        return
    }

    val rise = when (kind) {
        DigitalFurnitureKind.SHELF, DigitalFurnitureKind.CABINET, DigitalFurnitureKind.TV -> 38f
        DigitalFurnitureKind.SOFA -> 30f
        DigitalFurnitureKind.CHAIR -> 28f
        DigitalFurnitureKind.DESK, DigitalFurnitureKind.TABLE -> 25f
        DigitalFurnitureKind.BED -> 22f
        else -> 16f
    }
    val top = WorldRectangle(b.left, b.top - rise, b.right, b.bottom - rise)
    val side = color.dark(.68f)
    val front = color.dark(.78f)
    drawOval(Color.Black.copy(alpha = .21f), Offset(b.left + 8f, b.bottom - b.height * .30f + 18f), Size(b.width + 20f, b.height * .43f))
    drawRoundRect(front, Offset(b.left, top.bottom - 4f), Size(b.width, rise + 4f), CornerRadius(10f))
    val right = Path().apply { moveTo(top.right - 2f, top.top + 8f); lineTo(b.right, b.top + 8f); lineTo(b.right, b.bottom - 4f); lineTo(top.right - 2f, top.bottom - 4f); close() }
    drawPath(right, side.copy(alpha = .92f))
    drawRoundRect(Brush.linearGradient(listOf(color.light(), color, color.dark(.86f))), Offset(top.left, top.top), Size(top.width, top.height), CornerRadius(14f))
    drawRoundRect(Color.White.copy(alpha = .25f), Offset(top.left + 4f, top.top + 4f), Size(top.width - 8f, top.height - 8f), CornerRadius(12f), style = Stroke(2.5f))

    when (kind) {
        DigitalFurnitureKind.BED -> {
            drawRoundRect(Color(0xFFF7F2EA), Offset(top.left + 15f, top.top + 14f), Size(top.width - 30f, top.height - 27f), CornerRadius(18f))
            drawRoundRect(color.copy(alpha = .84f), Offset(top.left + 16f, top.top + top.height * .48f), Size(top.width - 32f, top.height * .40f), CornerRadius(12f))
            drawRoundRect(Color.White.copy(alpha = .95f), Offset(top.left + 27f, top.top + 24f), Size(top.width * .29f, top.height * .27f), CornerRadius(17f))
        }
        DigitalFurnitureKind.SOFA -> {
            drawRoundRect(side, Offset(top.left + 10f, top.top - 22f), Size(top.width - 20f, 42f), CornerRadius(16f))
            repeat(3) { i -> drawRoundRect(Color.White.copy(alpha = .17f), Offset(top.left + 17f + i * (top.width - 34f) / 3f, top.top + 24f), Size((top.width - 45f) / 3f, top.height - 38f), CornerRadius(12f)) }
        }
        DigitalFurnitureKind.TABLE, DigitalFurnitureKind.COFFEE_TABLE -> {
            drawOval(Color.White.copy(alpha = .23f), Offset(top.left + 13f, top.top + 9f), Size(top.width - 26f, top.height - 28f))
            listOf(top.left + 22f, top.right - 22f).forEach { x -> drawLine(side, Offset(x, top.bottom - 12f), Offset(x, b.bottom + 16f), 8f, StrokeCap.Round) }
        }
        DigitalFurnitureKind.CHAIR -> {
            drawRoundRect(side, Offset(top.left + 13f, top.top - 24f), Size(top.width - 26f, 40f), CornerRadius(11f))
            drawRoundRect(Color.White.copy(alpha = .17f), Offset(top.left + 17f, top.top + 16f), Size(top.width - 34f, top.height - 29f), CornerRadius(13f))
        }
        DigitalFurnitureKind.DESK, DigitalFurnitureKind.SHELF, DigitalFurnitureKind.CABINET, DigitalFurnitureKind.NIGHTSTAND -> {
            val slots = if (top.width > 120f) 3 else 2
            repeat(slots) { i ->
                val x = top.left + 12f + i * (top.width - 24f) / slots
                drawRoundRect(Color(0xFF172825).copy(alpha = .48f), Offset(x, top.top + 17f), Size((top.width - 34f) / slots, top.height - 35f), CornerRadius(5f))
                drawCircle(Color(0xFFE8D49E), 3.5f, Offset(x + (top.width - 34f) / slots - 9f, top.center.y))
            }
        }
        DigitalFurnitureKind.TV -> {
            drawRoundRect(Color(0xFF07100F), Offset(top.left + 9f, top.top - 18f), Size(top.width - 18f, top.height - 6f), CornerRadius(10f))
            drawRoundRect(Brush.linearGradient(listOf(Color(0xFF4D7873), Color(0xFF102A28), Color(0xFF061110))), Offset(top.left + 16f, top.top - 11f), Size(top.width - 32f, top.height - 22f), CornerRadius(7f))
        }
        DigitalFurnitureKind.PLANT -> repeat(7) { i ->
            val dx = (i * 37 % 53 - 26).toFloat(); val dy = (i * 23 % 38 - 19).toFloat()
            drawOval(Brush.linearGradient(listOf(Color(0xFF6C9A7E), Color(0xFF315C49))), Offset(top.center.x + dx - 18f, top.center.y + dy - 34f), Size(36f, 58f))
        }
        DigitalFurnitureKind.FLOOR_LAMP, DigitalFurnitureKind.TABLE_LAMP -> {
            val glow = 52f + lightPhase * 15f
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE8A8).copy(alpha = .42f), Color.Transparent)), glow, Offset(top.center.x, top.center.y - 8f))
            drawLine(side, Offset(top.center.x, top.center.y), Offset(top.center.x, b.bottom + 10f), 7f, StrokeCap.Round)
            drawCircle(Color(0xFFFFE7A4), 21f, Offset(top.center.x, top.center.y - 20f))
        }
        else -> drawOval(Color.White.copy(alpha = .22f), Offset(top.left + 12f, top.top + 10f), Size((top.width - 24f).coerceAtLeast(8f), (top.height - 20f).coerceAtLeast(8f)))
    }
}

private fun WorldVector.toOffset() = Offset(x, y)
private fun furnitureColor(key: String): Color = when (key) {
    "sage", "olive", "leaf", "cactus" -> Color(0xFF769688)
    "sky", "navy" -> Color(0xFF6D8D9C)
    "rose" -> Color(0xFFB88D8B)
    "charcoal" -> Color(0xFF4A5553)
    "latte", "rattan", "wood", "walnut" -> Color(0xFFA78363)
    "glass" -> Color(0xFF9AC0BF)
    "warm" -> Color(0xFFD7AD68)
    "white" -> Color(0xFFDDE3DF)
    else -> Color(0xFFC8BFAF)
}
private fun Color.dark(factor: Float) = copy(red = red * factor, green = green * factor, blue = blue * factor)
private fun Color.light() = copy(red = (red + (1f - red) * .18f).coerceAtMost(1f), green = (green + (1f - green) * .18f).coerceAtMost(1f), blue = (blue + (1f - blue) * .18f).coerceAtMost(1f))
