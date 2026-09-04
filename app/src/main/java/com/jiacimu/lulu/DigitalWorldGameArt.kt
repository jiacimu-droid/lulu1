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
    val obstacle: WorldObstacle? = if (blocksMovement) {
        WorldObstacle(item.id, bounds, item.name, digitalFurnitureAction(item, style.kind))
    } else {
        null
    }
}

internal fun buildDigitalRoomProps(items: List<DigitalWorldItem>): List<DigitalRoomProp> {
    if (items.isEmpty()) return emptyList()
    val wallKinds = setOf(DigitalFurnitureKind.WALL_ART, DigitalFurnitureKind.CLOCK, DigitalFurnitureKind.MIRROR)
    val rugItems = items.filter { DigitalFurnitureCatalog.resolve(it).kind == DigitalFurnitureKind.RUG }
    val wallItems = items.filter { DigitalFurnitureCatalog.resolve(it).kind in wallKinds }
    val floorItems = items.filter { item ->
        val kind = DigitalFurnitureCatalog.resolve(item).kind
        kind !in wallKinds && kind != DigitalFurnitureKind.RUG
    }.sortedByDescending { furnitureArea(DigitalFurnitureCatalog.resolve(it).kind) }

    val result = mutableListOf<DigitalRoomProp>()
    wallItems.forEachIndexed { index, item ->
        val style = DigitalFurnitureCatalog.resolve(item)
        val width = when (style.kind) {
            DigitalFurnitureKind.MIRROR -> 112f
            DigitalFurnitureKind.CLOCK -> 74f
            else -> 142f
        }
        val height = when (style.kind) {
            DigitalFurnitureKind.MIRROR -> 98f
            DigitalFurnitureKind.CLOCK -> 74f
            else -> 86f
        }
        val column = index % 8
        val row = index / 8
        val center = WorldVector(160f + column * 180f, 118f + row * 78f)
        result += DigitalRoomProp(item, style, centeredBounds(center, width, height), false)
    }

    rugItems.forEachIndexed { index, item ->
        val style = DigitalFurnitureCatalog.resolve(item)
        val centers = listOf(
            WorldVector(790f, 520f),
            WorldVector(350f, 700f),
            WorldVector(1_230f, 700f),
            WorldVector(790f, 860f),
        )
        val center = centers[index % centers.size] + WorldVector((index / centers.size) * 22f, 0f)
        result += DigitalRoomProp(item, style, centeredBounds(center, 330f, 190f), false)
    }

    val candidates = buildList {
        val rows = listOf(295f, 475f, 655f, 835f)
        val columns = listOf(165f, 360f, 555f, 750f, 945f, 1_140f, 1_335f)
        rows.forEachIndexed { row, y ->
            val ordered = if (row % 2 == 0) columns else columns.reversed()
            ordered.forEach { x -> add(WorldVector(x, y)) }
        }
    }
    val occupied = mutableListOf<WorldRectangle>()
    floorItems.forEachIndexed { index, item ->
        val style = DigitalFurnitureCatalog.resolve(item)
        val (width, height) = furnitureSize(style.kind)
        val start = ((item.id + item.position).hashCode() and Int.MAX_VALUE) % candidates.size
        val center = (candidates.indices)
            .asSequence()
            .map { candidates[(start + it) % candidates.size] }
            .firstOrNull { candidate ->
                val trial = centeredBounds(candidate, width, height).expanded(18f)
                occupied.none { existing -> rectanglesOverlap(trial, existing) }
            }
            ?: WorldVector(120f + (index % 10) * 145f, 900f - (index % 3) * 44f)
        val bounds = centeredBounds(center, width, height)
        occupied += bounds.expanded(16f)
        result += DigitalRoomProp(item, style, bounds, true)
    }
    return result
}

private fun centeredBounds(center: WorldVector, width: Float, height: Float) = WorldRectangle(
    center.x - width / 2f,
    center.y - height / 2f,
    center.x + width / 2f,
    center.y + height / 2f,
)

private fun rectanglesOverlap(a: WorldRectangle, b: WorldRectangle): Boolean =
    a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

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

private fun furnitureArea(kind: DigitalFurnitureKind): Float {
    val (width, height) = furnitureSize(kind)
    return width * height
}

private fun digitalFurnitureAction(item: DigitalWorldItem, kind: DigitalFurnitureKind): String = when (kind) {
    DigitalFurnitureKind.BED -> "我走到${item.name}边，坐下来感受了一下这个房间。"
    DigitalFurnitureKind.SOFA, DigitalFurnitureKind.CHAIR -> "我在${item.name}旁停下，轻轻坐了下来。"
    DigitalFurnitureKind.TV -> "我走到${item.name}前，伸手打开它。"
    DigitalFurnitureKind.DESK, DigitalFurnitureKind.TABLE, DigitalFurnitureKind.COFFEE_TABLE -> "我走近${item.name}，看看上面有没有留下什么。"
    DigitalFurnitureKind.SHELF, DigitalFurnitureKind.CABINET, DigitalFurnitureKind.NIGHTSTAND -> "我来到${item.name}旁，认真看看里面收着什么。"
    DigitalFurnitureKind.FLOOR_LAMP, DigitalFurnitureKind.TABLE_LAMP -> "我靠近${item.name}，让灯光落在我们身上。"
    DigitalFurnitureKind.PLANT -> "我走到${item.name}前，轻轻碰了碰叶片。"
    DigitalFurnitureKind.RUG, DigitalFurnitureKind.CUSHION -> "我在${item.name}旁坐下，抬头看向房间里的人。"
    else -> "我走近${item.name}，仔细看了看。"
}

internal fun DrawScope.drawDigitalHomeWorld(
    props: List<DigitalRoomProp>,
    lightPhase: Float,
) {
    drawRect(
        Brush.radialGradient(
            listOf(Color(0xFF263B38), Color(0xFF0C1515)),
            center = Offset(1_245f, 150f),
            radius = 1_450f,
        ),
        size = Size(DIGITAL_WORLD_WIDTH, DIGITAL_WORLD_HEIGHT),
    )
    drawRoundRect(
        Color.Black.copy(alpha = .34f),
        topLeft = Offset(40f, 52f),
        size = Size(1_520f, 960f),
        cornerRadius = CornerRadius(38f),
    )
    drawRoundRect(
        Brush.linearGradient(
            listOf(Color(0xFFE7E1D5), Color(0xFFAEBFB8), Color(0xFFD7C9B8)),
            start = Offset(70f, 90f),
            end = Offset(1_530f, 970f),
        ),
        topLeft = Offset(54f, 70f),
        size = Size(1_492f, 930f),
        cornerRadius = CornerRadius(30f),
    )

    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF263D3A), Color(0xFF172A29))),
        topLeft = Offset(54f, 70f),
        size = Size(1_492f, 116f),
        cornerRadius = CornerRadius(30f, 30f),
    )
    drawRect(Color(0xFF172A29), topLeft = Offset(54f, 142f), size = Size(1_492f, 44f))

    // Layered wall paint and moulding create depth without relying on bitmap assets.
    drawRect(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = .10f), Color.Transparent, Color(0xFF273B37).copy(alpha = .12f)),
            startY = 70f,
            endY = 186f,
        ),
        topLeft = Offset(54f, 70f),
        size = Size(1_492f, 116f),
    )
    drawLine(Color(0xFF0D1F1D).copy(alpha = .75f), Offset(58f, 185f), Offset(1_542f, 185f), 10f)
    drawLine(Color.White.copy(alpha = .22f), Offset(58f, 179f), Offset(1_542f, 179f), 2f)

    val floorTop = 186f
    for (x in -900..1_700 step 105) {
        drawLine(
            Color.White.copy(alpha = .13f),
            Offset(x.toFloat(), floorTop),
            Offset(x + 900f, 1_000f),
            strokeWidth = 2f,
        )
    }
    for (x in -100..2_500 step 105) {
        drawLine(
            Color(0xFF51655F).copy(alpha = .16f),
            Offset(x.toFloat(), floorTop),
            Offset(x - 900f, 1_000f),
            strokeWidth = 2f,
        )
    }
    repeat(8) { row ->
        val y = floorTop + 70f + row * 101f
        drawLine(
            Color(0xFF5B665F).copy(alpha = .11f),
            Offset(66f, y),
            Offset(1_534f, y + row * 4f),
            strokeWidth = 3f,
        )
        drawLine(
            Color.White.copy(alpha = .10f),
            Offset(70f, y + 5f),
            Offset(1_530f, y + 9f + row * 4f),
            strokeWidth = 2f,
        )
    }
    repeat(54) { index ->
        val seed = index * 131 + 47
        val x = 90f + (seed * 37 % 1_410)
        val y = 215f + (seed * 71 % 745)
        val width = 18f + (seed % 44)
        drawLine(
            Color(0xFF344B45).copy(alpha = .07f + (index % 3) * .025f),
            Offset(x, y),
            Offset(x + width, y + (index % 5 - 2) * 2f),
            3f,
            StrokeCap.Round,
        )
    }

    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFFBDE5E0), Color(0xFF5E7F80), Color(0xFF182E31))),
        topLeft = Offset(1_080f, 82f),
        size = Size(306f, 88f),
        cornerRadius = CornerRadius(13f),
    )
    // A tiny living skyline gives the window a sense of distance and parallax.
    repeat(13) { index ->
        val buildingWidth = 14f + (index % 4) * 4f
        val buildingHeight = 20f + (index * 17 % 48)
        val left = 1_088f + index * 22f
        drawRect(
            Color(0xFF182927).copy(alpha = .72f),
            topLeft = Offset(left, 169f - buildingHeight),
            size = Size(buildingWidth, buildingHeight),
        )
        if (index % 2 == 0) {
            drawCircle(
                Color(0xFFFFDEA1).copy(alpha = .55f + lightPhase * .20f),
                2.4f,
                Offset(left + buildingWidth * .5f, 158f - buildingHeight * .48f),
            )
        }
    }
    drawRect(Color(0xFF18302E), topLeft = Offset(1_227f, 82f), size = Size(10f, 88f))
    drawRect(Color(0xFF18302E), topLeft = Offset(1_080f, 122f), size = Size(306f, 9f))
    drawCircle(Color.White.copy(alpha = .62f), 20f, Offset(1_324f, 111f))
    drawRoundRect(Color.White.copy(alpha = .28f), Offset(1_087f, 88f), Size(292f, 75f), CornerRadius(9f), style = Stroke(3f))

    val light = Path().apply {
        moveTo(1_075f, 170f)
        lineTo(1_390f, 170f)
        lineTo(1_525f, 800f)
        lineTo(985f, 650f)
        close()
    }
    drawPath(
        light,
        Brush.linearGradient(
            listOf(Color(0xFFF6FFF4).copy(alpha = .23f + lightPhase * .09f), Color(0xFFD2F1E7).copy(alpha = .07f), Color.Transparent),
            start = Offset(1_250f, 180f),
            end = Offset(1_350f, 720f),
        ),
    )
    repeat(22) { index ->
        val drift = (lightPhase * 34f + index * 41f) % 128f
        val x = 1_040f + (index * 61 % 430) + drift
        val y = 215f + (index * 83 % 500)
        drawCircle(Color(0xFFFFF6D8).copy(alpha = .16f + (index % 4) * .035f), 2.5f + index % 3, Offset(x, y))
    }

    drawRoundRect(Color(0xFF102220), topLeft = Offset(730f, 70f), size = Size(140f, 116f), cornerRadius = CornerRadius(7f))
    drawRoundRect(Color(0xFF748F87), topLeft = Offset(750f, 90f), size = Size(100f, 96f), cornerRadius = CornerRadius(5f))
    drawCircle(Color(0xFFE6C99A), 8f, Offset(838f, 139f))

    props.filter { it.style.kind == DigitalFurnitureKind.RUG }.forEach { drawDigitalProp(it, lightPhase) }
    props.filterNot { it.style.kind == DigitalFurnitureKind.RUG }.sortedBy { it.bounds.bottom }.forEach { drawDigitalProp(it, lightPhase) }

    // Soft pools of bounced light make the room feel painted rather than diagrammatic.
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFFFE7B3).copy(alpha = .11f), Color.Transparent)),
        310f,
        Offset(360f, 785f),
    )
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFB9F5E4).copy(alpha = .10f), Color.Transparent)),
        360f,
        Offset(1_280f, 690f),
    )

    drawRoundRect(
        Color.White.copy(alpha = .18f),
        topLeft = Offset(54f, 70f),
        size = Size(1_492f, 930f),
        cornerRadius = CornerRadius(30f),
        style = Stroke(3f),
    )
}

internal fun DrawScope.drawDigitalSharedWorld(sceneCode: String, lightPhase: Float) {
    val cloud = sceneCode == DigitalWorldStore.CLOUD_MEADOW
    drawRect(
        if (cloud) {
            Brush.verticalGradient(listOf(Color(0xFF799EAD), Color(0xFFC4D8D7), Color(0xFFF2EFE5)))
        } else {
            Brush.radialGradient(listOf(Color(0xFF6E817E), Color(0xFF172724), Color(0xFF071311)), center = Offset(800f, 520f), radius = 1_050f)
        },
        size = Size(DIGITAL_WORLD_WIDTH, DIGITAL_WORLD_HEIGHT),
    )

    if (cloud) {
        repeat(18) { index ->
            val x = ((index * 271) % 1_650 - 80).toFloat()
            val y = (180 + (index * 137) % 760).toFloat()
            val radius = 74f + (index % 5) * 24f
            drawOval(
                Color.White.copy(alpha = .30f + (index % 3) * .08f),
                topLeft = Offset(x - radius, y - radius * .36f),
                size = Size(radius * 2.2f, radius * .74f),
            )
        }
        val island = Path().apply {
            moveTo(150f, 510f)
            cubicTo(330f, 310f, 1_250f, 300f, 1_450f, 520f)
            cubicTo(1_510f, 720f, 1_260f, 940f, 810f, 955f)
            cubicTo(340f, 945f, 70f, 740f, 150f, 510f)
            close()
        }
        drawPath(island, Color(0xFFEDF3EC))
        drawPath(island, Color.White.copy(alpha = .62f), style = Stroke(5f))
        repeat(34) { index ->
            val x = 170f + ((index * 211) % 1_240)
            val y = 480f + ((index * 97) % 390)
            drawCircle(Color(0xFF73968B).copy(alpha = .38f), 3f + index % 4, Offset(x, y))
            drawCircle(Color(0xFFFFF5D5).copy(alpha = .85f), 2f, Offset(x + 4f, y - 5f))
        }
    } else {
        repeat(5) { ring ->
            drawCircle(
                Color(0xFFB8F3E3).copy(alpha = .08f + lightPhase * .03f),
                110f + ring * 86f,
                Offset(800f, 535f),
                style = Stroke(4f),
            )
        }
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFFF4FFFC), Color(0xFF9BE0CF).copy(alpha = .40f), Color.Transparent)),
            176f,
            Offset(800f, 535f),
        )
        repeat(30) { index ->
            val angle = index * .63f
            val radius = 260f + (index * 67 % 520)
            val x = 800f + kotlin.math.cos(angle) * radius
            val y = 535f + kotlin.math.sin(angle) * radius * .58f
            drawCircle(Color(0xFFB8F3E3).copy(alpha = .24f), 3f + index % 4, Offset(x, y))
        }
    }
}

private fun DrawScope.drawDigitalProp(prop: DigitalRoomProp, lightPhase: Float) {
    val kind = prop.style.kind
    val b = prop.bounds
    val color = furnitureColor(prop.style.colorKey)
    if (kind == DigitalFurnitureKind.RUG) {
        drawOval(Color.Black.copy(alpha = .10f), Offset(b.left + 8f, b.top + 13f), Size(b.width, b.height))
        drawOval(color.copy(alpha = .74f), Offset(b.left, b.top), Size(b.width, b.height))
        drawOval(Color.White.copy(alpha = .34f), Offset(b.left, b.top), Size(b.width, b.height), style = Stroke(4f))
        if (prop.style.pattern in setOf("stripe", "check")) {
            repeat(5) { index ->
                val x = b.left + b.width * (.18f + index * .16f)
                drawLine(Color.White.copy(alpha = .22f), Offset(x, b.top + 22f), Offset(x + 20f, b.bottom - 22f), 7f, StrokeCap.Round)
            }
        }
        return
    }

    if (kind in setOf(DigitalFurnitureKind.WALL_ART, DigitalFurnitureKind.MIRROR, DigitalFurnitureKind.CLOCK)) {
        drawRoundRect(Color.Black.copy(alpha = .25f), Offset(b.left + 7f, b.top + 8f), Size(b.width, b.height), CornerRadius(12f))
        when (kind) {
            DigitalFurnitureKind.CLOCK -> {
                drawCircle(color, b.width * .45f, b.center.toOffset())
                drawCircle(Color.White.copy(alpha = .55f), b.width * .45f, b.center.toOffset(), style = Stroke(4f))
                drawLine(Color(0xFF1C2C29), b.center.toOffset(), Offset(b.center.x, b.center.y - 20f), 4f, StrokeCap.Round)
                drawLine(Color(0xFF1C2C29), b.center.toOffset(), Offset(b.center.x + 17f, b.center.y + 8f), 4f, StrokeCap.Round)
            }
            DigitalFurnitureKind.MIRROR -> {
                drawRoundRect(Brush.linearGradient(listOf(Color(0xFFE8FFFF), Color(0xFF739B9B))), Offset(b.left, b.top), Size(b.width, b.height), CornerRadius(35f, 35f))
                drawLine(Color.White.copy(alpha = .72f), Offset(b.left + 20f, b.top + 18f), Offset(b.right - 28f, b.bottom - 25f), 5f, StrokeCap.Round)
            }
            else -> {
                drawRoundRect(color, Offset(b.left, b.top), Size(b.width, b.height), CornerRadius(10f))
                drawRoundRect(Color(0xFF152724), Offset(b.left + 8f, b.top + 8f), Size(b.width - 16f, b.height - 16f), CornerRadius(7f))
                drawCircle(color.copy(alpha = .88f), 18f, Offset(b.center.x + 20f, b.center.y - 7f))
                drawLine(color.copy(alpha = .82f), Offset(b.left + 24f, b.bottom - 25f), Offset(b.center.x, b.top + 27f), 6f, StrokeCap.Round)
            }
        }
        return
    }

    val side = color.copy(red = color.red * .72f, green = color.green * .72f, blue = color.blue * .72f)
    drawRoundRect(Color.Black.copy(alpha = .23f), Offset(b.left + 11f, b.top + 16f), Size(b.width, b.height), CornerRadius(16f))
    drawRoundRect(side, Offset(b.left, b.top + 13f), Size(b.width, b.height), CornerRadius(14f))
    drawRoundRect(
        Brush.linearGradient(listOf(color.copy(alpha = .98f), color.copy(alpha = .78f))),
        Offset(b.left, b.top),
        Size(b.width, b.height),
        CornerRadius(14f),
    )
    drawRoundRect(Color.White.copy(alpha = .28f), Offset(b.left + 4f, b.top + 4f), Size(b.width - 8f, b.height - 8f), CornerRadius(12f), style = Stroke(3f))

    when (kind) {
        DigitalFurnitureKind.BED -> {
            drawRoundRect(Color(0xFFF6F1E8), Offset(b.left + 15f, b.top + 15f), Size(b.width - 30f, b.height - 30f), CornerRadius(18f))
            drawRoundRect(color.copy(alpha = .75f), Offset(b.left + 17f, b.top + b.height * .48f), Size(b.width - 34f, b.height * .39f), CornerRadius(12f))
            drawRoundRect(Color.White.copy(alpha = .93f), Offset(b.left + 27f, b.top + 25f), Size(b.width * .29f, b.height * .28f), CornerRadius(17f))
            drawRoundRect(Color.White.copy(alpha = .88f), Offset(b.left + b.width * .56f, b.top + 25f), Size(b.width * .27f, b.height * .28f), CornerRadius(17f))
        }
        DigitalFurnitureKind.SOFA -> {
            repeat(3) { index ->
                drawRoundRect(Color.White.copy(alpha = .19f), Offset(b.left + 17f + index * (b.width - 34f) / 3f, b.top + 17f), Size((b.width - 45f) / 3f, b.height - 34f), CornerRadius(12f))
            }
            drawRoundRect(side, Offset(b.left + 7f, b.top + 9f), Size(19f, b.height - 18f), CornerRadius(9f))
            drawRoundRect(side, Offset(b.right - 26f, b.top + 9f), Size(19f, b.height - 18f), CornerRadius(9f))
        }
        DigitalFurnitureKind.TABLE, DigitalFurnitureKind.COFFEE_TABLE -> {
            drawOval(Color.White.copy(alpha = .26f), Offset(b.left + 13f, b.top + 9f), Size(b.width - 26f, b.height - 28f))
            repeat(4) { index ->
                val x = if (index % 2 == 0) b.left + 24f else b.right - 24f
                val y = if (index < 2) b.top + 24f else b.bottom - 19f
                drawCircle(side, 8f, Offset(x, y))
            }
        }
        DigitalFurnitureKind.CHAIR -> {
            drawRoundRect(Color.White.copy(alpha = .22f), Offset(b.left + 17f, b.top + 14f), Size(b.width - 34f, b.height - 28f), CornerRadius(13f))
            drawLine(side, Offset(b.left + 13f, b.top + 10f), Offset(b.left + 13f, b.bottom - 8f), 8f, StrokeCap.Round)
        }
        DigitalFurnitureKind.DESK, DigitalFurnitureKind.SHELF, DigitalFurnitureKind.CABINET, DigitalFurnitureKind.NIGHTSTAND -> {
            val slots = if (b.width > 120f) 3 else 2
            repeat(slots) { index ->
                val x = b.left + 12f + index * (b.width - 24f) / slots
                drawRoundRect(Color(0xFF172825).copy(alpha = .45f), Offset(x, b.top + 16f), Size((b.width - 34f) / slots, b.height - 34f), CornerRadius(5f))
                drawCircle(Color(0xFFE8D49E), 3.5f, Offset(x + (b.width - 34f) / slots - 9f, b.center.y))
            }
        }
        DigitalFurnitureKind.TV -> {
            drawRoundRect(Color(0xFF081311), Offset(b.left + 10f, b.top + 9f), Size(b.width - 20f, b.height - 27f), CornerRadius(9f))
            drawRoundRect(Brush.linearGradient(listOf(Color(0xFF315B5A), Color(0xFF0D2221))), Offset(b.left + 17f, b.top + 15f), Size(b.width - 34f, b.height - 39f), CornerRadius(6f))
            drawLine(Color.White.copy(alpha = .34f), Offset(b.left + 27f, b.top + 23f), Offset(b.center.x, b.top + 23f), 4f, StrokeCap.Round)
        }
        DigitalFurnitureKind.PLANT -> {
            drawOval(side, Offset(b.left + 20f, b.top + b.height * .55f), Size(b.width - 40f, b.height * .40f))
            repeat(7) { index ->
                val dx = ((index * 37) % 53 - 26).toFloat()
                val dy = ((index * 23) % 38 - 19).toFloat()
                drawOval(Color(0xFF497762).copy(alpha = .88f), Offset(b.center.x + dx - 18f, b.center.y + dy - 26f), Size(36f, 52f))
            }
        }
        DigitalFurnitureKind.FLOOR_LAMP, DigitalFurnitureKind.TABLE_LAMP -> {
            val glow = 44f + lightPhase * 12f
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE8A8).copy(alpha = .38f), Color.Transparent)), glow, b.center.toOffset())
            drawCircle(Color(0xFFFFE4A0), minOf(b.width, b.height) * .26f, Offset(b.center.x, b.top + b.height * .38f))
            drawLine(side, Offset(b.center.x, b.center.y), Offset(b.center.x, b.bottom - 6f), 7f, StrokeCap.Round)
        }
        DigitalFurnitureKind.CUSHION, DigitalFurnitureKind.BASKET, DigitalFurnitureKind.DECOR -> {
            drawOval(Color.White.copy(alpha = .24f), Offset(b.left + 12f, b.top + 10f), Size(b.width - 24f, b.height - 20f))
        }
        else -> Unit
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
