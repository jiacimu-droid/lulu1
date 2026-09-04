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
    } else null
}

/**
 * Furniture is arranged like a room instead of being thrown onto a generic grid. Large pieces claim
 * meaningful zones first (bed by a wall, sofa around the living area, TV opposite it, storage along
 * the edges), then smaller props fill the remaining pockets. This makes every home read as a place
 * someone could actually live in while keeping placement deterministic and persistent.
 */
internal fun buildDigitalRoomProps(items: List<DigitalWorldItem>): List<DigitalRoomProp> {
    if (items.isEmpty()) return emptyList()
    val wallKinds = setOf(DigitalFurnitureKind.WALL_ART, DigitalFurnitureKind.CLOCK, DigitalFurnitureKind.MIRROR)
    val result = mutableListOf<DigitalRoomProp>()

    items.filter { DigitalFurnitureCatalog.resolve(it).kind in wallKinds }.forEachIndexed { index, item ->
        val style = DigitalFurnitureCatalog.resolve(item)
        val (w, h) = furnitureSize(style.kind)
        val wallSpots = listOf(
            WorldVector(225f, 128f), WorldVector(430f, 128f), WorldVector(640f, 128f),
            WorldVector(940f, 128f), WorldVector(1_150f, 128f), WorldVector(1_360f, 128f),
        )
        result += DigitalRoomProp(item, style, centeredBounds(wallSpots[index % wallSpots.size], w, h), false)
    }

    items.filter { DigitalFurnitureCatalog.resolve(it).kind == DigitalFurnitureKind.RUG }.forEachIndexed { index, item ->
        val spots = listOf(
            WorldVector(800f, 610f),
            WorldVector(420f, 735f),
            WorldVector(1_180f, 735f),
            WorldVector(800f, 845f),
        )
        result += DigitalRoomProp(item, DigitalFurnitureCatalog.resolve(item), centeredBounds(spots[index % spots.size], 300f, 170f), false)
    }

    val floorItems = items.filter {
        val kind = DigitalFurnitureCatalog.resolve(it).kind
        kind !in wallKinds && kind != DigitalFurnitureKind.RUG
    }.sortedByDescending { furnitureArea(DigitalFurnitureCatalog.resolve(it).kind) }

    val occupied = mutableListOf<WorldRectangle>()
    floorItems.forEachIndexed { index, item ->
        val style = DigitalFurnitureCatalog.resolve(item)
        val kind = style.kind
        val (w, h) = furnitureSize(kind)
        val preferred = preferredFurnitureSpots(kind)
        val fallback = genericFurnitureSpots()
        val candidates = (preferred + fallback).distinct()
        val start = ((item.id + item.position).hashCode() and Int.MAX_VALUE) % candidates.size.coerceAtLeast(1)
        val center = candidates.indices.asSequence()
            .map { candidates[(start + it) % candidates.size] }
            .firstOrNull { spot ->
                val trial = centeredBounds(spot, w, h).expanded(15f)
                occupied.none { overlap(trial, it) }
            }
            ?: WorldVector(180f + index % 8 * 175f, 850f - index % 3 * 58f)
        val bounds = centeredBounds(center, w, h)
        occupied += bounds.expanded(14f)
        result += DigitalRoomProp(item, style, bounds, true)
    }
    return result
}

private fun preferredFurnitureSpots(kind: DigitalFurnitureKind): List<WorldVector> = when (kind) {
    DigitalFurnitureKind.BED -> listOf(
        WorldVector(300f, 350f), WorldVector(1_300f, 350f), WorldVector(315f, 820f), WorldVector(1_285f, 820f),
    )
    DigitalFurnitureKind.SOFA -> listOf(
        WorldVector(800f, 650f), WorldVector(545f, 650f), WorldVector(1_055f, 650f), WorldVector(800f, 820f),
    )
    DigitalFurnitureKind.TV -> listOf(
        WorldVector(800f, 325f), WorldVector(1_285f, 450f), WorldVector(315f, 450f),
    )
    DigitalFurnitureKind.COFFEE_TABLE -> listOf(
        WorldVector(800f, 600f), WorldVector(550f, 600f), WorldVector(1_050f, 600f), WorldVector(800f, 770f),
    )
    DigitalFurnitureKind.TABLE -> listOf(
        WorldVector(800f, 565f), WorldVector(530f, 760f), WorldVector(1_070f, 760f),
    )
    DigitalFurnitureKind.DESK -> listOf(
        WorldVector(380f, 325f), WorldVector(1_220f, 325f), WorldVector(250f, 690f), WorldVector(1_350f, 690f),
    )
    DigitalFurnitureKind.SHELF, DigitalFurnitureKind.CABINET -> listOf(
        WorldVector(180f, 300f), WorldVector(1_420f, 300f), WorldVector(180f, 540f), WorldVector(1_420f, 540f),
        WorldVector(180f, 790f), WorldVector(1_420f, 790f),
    )
    DigitalFurnitureKind.NIGHTSTAND -> listOf(
        WorldVector(465f, 350f), WorldVector(1_135f, 350f), WorldVector(465f, 820f), WorldVector(1_135f, 820f),
    )
    DigitalFurnitureKind.CHAIR -> listOf(
        WorldVector(650f, 560f), WorldVector(950f, 560f), WorldVector(650f, 760f), WorldVector(950f, 760f),
        WorldVector(430f, 510f), WorldVector(1_170f, 510f),
    )
    DigitalFurnitureKind.FLOOR_LAMP -> listOf(
        WorldVector(225f, 405f), WorldVector(1_375f, 405f), WorldVector(490f, 690f), WorldVector(1_110f, 690f),
        WorldVector(240f, 865f), WorldVector(1_360f, 865f),
    )
    DigitalFurnitureKind.TABLE_LAMP -> listOf(
        WorldVector(470f, 390f), WorldVector(1_130f, 390f), WorldVector(540f, 720f), WorldVector(1_060f, 720f),
    )
    DigitalFurnitureKind.PLANT -> listOf(
        WorldVector(165f, 245f), WorldVector(1_435f, 245f), WorldVector(175f, 910f), WorldVector(1_425f, 910f),
        WorldVector(520f, 260f), WorldVector(1_080f, 260f),
    )
    DigitalFurnitureKind.CUSHION, DigitalFurnitureKind.BASKET, DigitalFurnitureKind.DECOR -> listOf(
        WorldVector(575f, 690f), WorldVector(1_025f, 690f), WorldVector(690f, 825f), WorldVector(910f, 825f),
        WorldVector(420f, 620f), WorldVector(1_180f, 620f),
    )
    else -> genericFurnitureSpots()
}

private fun genericFurnitureSpots(): List<WorldVector> = listOf(
    WorldVector(265f, 350f), WorldVector(500f, 350f), WorldVector(800f, 350f), WorldVector(1_100f, 350f), WorldVector(1_335f, 350f),
    WorldVector(260f, 560f), WorldVector(520f, 560f), WorldVector(800f, 560f), WorldVector(1_080f, 560f), WorldVector(1_340f, 560f),
    WorldVector(265f, 785f), WorldVector(520f, 785f), WorldVector(800f, 785f), WorldVector(1_080f, 785f), WorldVector(1_335f, 785f),
    WorldVector(430f, 920f), WorldVector(800f, 920f), WorldVector(1_170f, 920f),
)

private fun centeredBounds(c: WorldVector, w: Float, h: Float) =
    WorldRectangle(c.x - w / 2f, c.y - h / 2f, c.x + w / 2f, c.y + h / 2f)

private fun overlap(a: WorldRectangle, b: WorldRectangle) =
    a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

private fun furnitureSize(kind: DigitalFurnitureKind): Pair<Float, Float> = when (kind) {
    DigitalFurnitureKind.BED -> 238f to 146f
    DigitalFurnitureKind.SOFA -> 208f to 98f
    DigitalFurnitureKind.COFFEE_TABLE -> 128f to 76f
    DigitalFurnitureKind.TABLE -> 150f to 96f
    DigitalFurnitureKind.CHAIR -> 78f to 66f
    DigitalFurnitureKind.DESK -> 172f to 78f
    DigitalFurnitureKind.SHELF -> 170f to 64f
    DigitalFurnitureKind.CABINET -> 154f to 68f
    DigitalFurnitureKind.NIGHTSTAND -> 72f to 60f
    DigitalFurnitureKind.FLOOR_LAMP -> 58f to 58f
    DigitalFurnitureKind.TABLE_LAMP -> 52f to 48f
    DigitalFurnitureKind.PLANT -> 64f to 58f
    DigitalFurnitureKind.TV -> 160f to 64f
    DigitalFurnitureKind.CUSHION -> 56f to 46f
    DigitalFurnitureKind.BASKET -> 62f to 52f
    DigitalFurnitureKind.DECOR -> 54f to 48f
    DigitalFurnitureKind.RUG -> 300f to 170f
    DigitalFurnitureKind.MIRROR -> 104f to 90f
    DigitalFurnitureKind.WALL_ART -> 130f to 78f
    DigitalFurnitureKind.CLOCK -> 66f to 66f
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
    drawRect(
        Brush.radialGradient(
            listOf(Color(0xFF263A35), Color(0xFF091210)),
            center = Offset(1_250f, 120f),
            radius = 1_500f,
        ),
        size = Size(DIGITAL_WORLD_WIDTH, DIGITAL_WORLD_HEIGHT),
    )
    drawRoundRect(Color.Black.copy(alpha = .38f), Offset(34f, 48f), Size(1_532f, 966f), CornerRadius(38f))

    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFFE5E1D8), Color(0xFFD4D3CC))),
        Offset(54f, 70f),
        Size(1_492f, 930f),
        CornerRadius(28f),
    )
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFFD9D9D2), Color(0xFFC4C9C4))),
        Offset(54f, 70f),
        Size(1_492f, 142f),
        CornerRadius(28f, 28f),
    )
    drawRect(
        Brush.verticalGradient(listOf(Color(0xFFB8B7AE), Color(0xFF8D948E))),
        Offset(54f, 198f),
        Size(1_492f, 802f),
    )

    drawRect(Color(0xFF263B36), Offset(54f, 188f), Size(1_492f, 14f))
    drawLine(Color.White.copy(alpha = .25f), Offset(58f, 188f), Offset(1_542f, 188f), 2f)

    // Perspective floor boards: they converge gently toward the back wall instead of forming a flat
    // checkerboard, so furniture sits in a believable room volume.
    val vanishY = 202f
    for (x in 80..1_520 step 116) {
        val topX = 800f + (x - 800f) * .24f
        drawLine(Color(0xFF4D5B55).copy(alpha = .17f), Offset(topX, vanishY), Offset(x.toFloat(), 1_000f), 2.2f)
        drawLine(Color.White.copy(alpha = .08f), Offset(topX + 3f, vanishY), Offset(x + 3f, 1_000f), 1f)
    }
    for (y in 280..950 step 92) {
        val fade = ((y - 240f) / 760f).coerceIn(0f, 1f)
        drawLine(
            Color(0xFF56625D).copy(alpha = .12f + fade * .05f),
            Offset(62f, y.toFloat()),
            Offset(1_538f, y.toFloat() + 4f),
            2f,
        )
    }

    // Back-wall architectural detail.
    drawRoundRect(Color(0xFFBBC2BC), Offset(92f, 92f), Size(430f, 78f), CornerRadius(9f))
    repeat(4) { index ->
        drawRoundRect(
            Color.White.copy(alpha = .28f),
            Offset(108f + index * 102f, 103f),
            Size(82f, 54f),
            CornerRadius(5f),
            style = Stroke(2f),
        )
    }

    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFFCFE8E3), Color(0xFF66827F), Color(0xFF1A2F2D))),
        Offset(1_070f, 82f),
        Size(320f, 96f),
        CornerRadius(13f),
    )
    repeat(13) { i ->
        val w = 13f + i % 4 * 4f
        val h = 20f + i * 17 % 50
        val x = 1_082f + i * 23f
        drawRect(Color(0xFF172927).copy(alpha = .78f), Offset(x, 172f - h), Size(w, h))
        if (i % 2 == 0) {
            drawCircle(
                Color(0xFFFFDEA1).copy(alpha = .48f + lightPhase * .24f),
                2.4f,
                Offset(x + w * .5f, 160f - h * .48f),
            )
        }
    }
    drawRect(Color(0xFF17302D), Offset(1_224f, 82f), Size(9f, 96f))
    drawRect(Color(0xFF17302D), Offset(1_070f, 124f), Size(320f, 8f))
    drawRoundRect(Color.White.copy(alpha = .26f), Offset(1_079f, 90f), Size(302f, 79f), CornerRadius(8f), style = Stroke(3f))

    val light = Path().apply {
        moveTo(1_070f, 174f)
        lineTo(1_390f, 174f)
        lineTo(1_535f, 800f)
        lineTo(995f, 655f)
        close()
    }
    drawPath(
        light,
        Brush.linearGradient(
            listOf(
                Color(0xFFF7FFF4).copy(alpha = .22f + lightPhase * .08f),
                Color(0xFFD0EFE4).copy(alpha = .06f),
                Color.Transparent,
            ),
            Offset(1_250f, 180f),
            Offset(1_350f, 760f),
        ),
    )
    repeat(18) { i ->
        drawCircle(
            Color(0xFFFFF7DA).copy(alpha = .10f + i % 4 * .03f),
            1.6f + i % 3,
            Offset(1_030f + (i * 61 % 450), 215f + (i * 83 % 500)),
        )
    }

    drawRoundRect(Color(0xFF203631), Offset(730f, 76f), Size(140f, 108f), CornerRadius(6f))
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF8FA79E), Color(0xFF536D66))), Offset(748f, 94f), Size(104f, 90f), CornerRadius(4f))
    drawCircle(Color(0xFFE4C896), 7f, Offset(839f, 139f))

    props.filter { it.style.kind == DigitalFurnitureKind.RUG }.forEach { drawDigitalProp(it, lightPhase) }
    props.filterNot { it.style.kind == DigitalFurnitureKind.RUG }
        .sortedBy { it.bounds.bottom }
        .forEach { drawDigitalProp(it, lightPhase) }

    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFFFE7B3).copy(alpha = .08f), Color.Transparent)),
        300f,
        Offset(340f, 790f),
    )
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFB9F5E4).copy(alpha = .07f), Color.Transparent)),
        340f,
        Offset(1_290f, 700f),
    )
    drawRoundRect(Color.White.copy(alpha = .14f), Offset(54f, 70f), Size(1_492f, 930f), CornerRadius(28f), style = Stroke(3f))
}

internal fun DrawScope.drawDigitalSharedWorld(sceneCode: String, lightPhase: Float) {
    val cloud = sceneCode == DigitalWorldStore.CLOUD_MEADOW
    drawRect(
        if (cloud) {
            Brush.verticalGradient(listOf(Color(0xFF799EAD), Color(0xFFC4D8D7), Color(0xFFF2EFE5)))
        } else {
            Brush.radialGradient(
                listOf(Color(0xFF6E817E), Color(0xFF172724), Color(0xFF071311)),
                center = Offset(800f, 520f),
                radius = 1_050f,
            )
        },
        size = Size(DIGITAL_WORLD_WIDTH, DIGITAL_WORLD_HEIGHT),
    )
    if (cloud) {
        repeat(18) { i ->
            val x = ((i * 271) % 1_650 - 80).toFloat()
            val y = (180 + i * 137 % 760).toFloat()
            val r = 74f + i % 5 * 24f
            drawOval(Color.White.copy(alpha = .30f + i % 3 * .08f), Offset(x - r, y - r * .36f), Size(r * 2.2f, r * .74f))
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
    }
}

private fun DrawScope.drawDigitalProp(prop: DigitalRoomProp, lightPhase: Float) {
    val kind = prop.style.kind
    val b = prop.bounds
    val color = furnitureColor(prop.style.colorKey)

    when (kind) {
        DigitalFurnitureKind.RUG -> {
            drawRoundRect(Color.Black.copy(alpha = .16f), Offset(b.left + 10f, b.top + 14f), Size(b.width, b.height), CornerRadius(54f))
            drawRoundRect(
                Brush.radialGradient(listOf(color.light(), color.copy(alpha = .96f), color.dark(.78f))),
                Offset(b.left, b.top),
                Size(b.width, b.height),
                CornerRadius(52f),
            )
            drawRoundRect(Color.White.copy(alpha = .22f), Offset(b.left + 6f, b.top + 6f), Size(b.width - 12f, b.height - 12f), CornerRadius(46f), style = Stroke(2f))
            repeat(5) { i ->
                drawLine(
                    Color.White.copy(alpha = .07f),
                    Offset(b.left + 34f + i * 52f, b.top + 18f),
                    Offset(b.left + 12f + i * 58f, b.bottom - 18f),
                    2f,
                )
            }
            return
        }
        DigitalFurnitureKind.PLANT -> {
            drawPlant(b, color)
            return
        }
        DigitalFurnitureKind.FLOOR_LAMP -> {
            drawFloorLamp(b, lightPhase)
            return
        }
        DigitalFurnitureKind.TABLE_LAMP -> {
            drawTableLamp(b, lightPhase)
            return
        }
        DigitalFurnitureKind.CUSHION -> {
            drawSoftCushion(b, color)
            return
        }
        DigitalFurnitureKind.BASKET -> {
            drawBasket(b)
            return
        }
        DigitalFurnitureKind.DECOR -> {
            drawDecor(b, color)
            return
        }
        else -> Unit
    }

    if (kind in setOf(DigitalFurnitureKind.WALL_ART, DigitalFurnitureKind.MIRROR, DigitalFurnitureKind.CLOCK)) {
        drawRoundRect(Color.Black.copy(alpha = .24f), Offset(b.left + 7f, b.top + 9f), Size(b.width, b.height), CornerRadius(11f))
        when (kind) {
            DigitalFurnitureKind.CLOCK -> {
                drawCircle(Brush.radialGradient(listOf(color.light(), color)), b.width * .45f, b.center.toOffset())
                drawCircle(Color.White.copy(alpha = .58f), b.width * .45f, b.center.toOffset(), style = Stroke(3f))
                drawLine(Color(0xFF182724), b.center.toOffset(), Offset(b.center.x, b.center.y - 18f), 3.5f, StrokeCap.Round)
                drawLine(Color(0xFF182724), b.center.toOffset(), Offset(b.center.x + 14f, b.center.y + 7f), 3f, StrokeCap.Round)
                drawCircle(Color(0xFFE7D49F), 3f, b.center.toOffset())
            }
            DigitalFurnitureKind.MIRROR -> {
                drawRoundRect(Color(0xFF6E756F), Offset(b.left, b.top), Size(b.width, b.height), CornerRadius(28f))
                drawRoundRect(
                    Brush.linearGradient(listOf(Color(0xFFF2FFFF), Color(0xFFA5C2BF), Color(0xFF657F7C))),
                    Offset(b.left + 6f, b.top + 6f),
                    Size(b.width - 12f, b.height - 12f),
                    CornerRadius(24f),
                )
                drawLine(Color.White.copy(alpha = .78f), Offset(b.left + 18f, b.top + 16f), Offset(b.right - 28f, b.bottom - 24f), 4f, StrokeCap.Round)
            }
            else -> {
                drawRoundRect(color.dark(.68f), Offset(b.left, b.top), Size(b.width, b.height), CornerRadius(7f))
                drawRoundRect(
                    Brush.linearGradient(listOf(color.light(), color.dark(.82f))),
                    Offset(b.left + 7f, b.top + 7f),
                    Size(b.width - 14f, b.height - 14f),
                    CornerRadius(4f),
                )
                drawPath(
                    Path().apply {
                        moveTo(b.left + 18f, b.bottom - 20f)
                        lineTo(b.left + b.width * .38f, b.top + 24f)
                        lineTo(b.left + b.width * .58f, b.bottom - 25f)
                        lineTo(b.right - 17f, b.top + 18f)
                    },
                    Color.White.copy(alpha = .20f),
                    style = Stroke(3f),
                )
            }
        }
        return
    }

    val rise = when (kind) {
        DigitalFurnitureKind.SHELF, DigitalFurnitureKind.CABINET, DigitalFurnitureKind.TV -> 34f
        DigitalFurnitureKind.SOFA -> 28f
        DigitalFurnitureKind.CHAIR -> 25f
        DigitalFurnitureKind.DESK, DigitalFurnitureKind.TABLE -> 23f
        DigitalFurnitureKind.BED -> 20f
        else -> 14f
    }
    val top = drawFurnitureBody(b, color, rise)
    val side = color.dark(.64f)

    when (kind) {
        DigitalFurnitureKind.BED -> {
            drawRoundRect(side, Offset(top.left + 4f, top.top - 24f), Size(top.width - 8f, 35f), CornerRadius(8f))
            drawRoundRect(Color(0xFFF3EFE8), Offset(top.left + 10f, top.top + 8f), Size(top.width - 20f, top.height - 14f), CornerRadius(13f))
            drawRoundRect(color.copy(alpha = .78f), Offset(top.left + 12f, top.top + top.height * .48f), Size(top.width - 24f, top.height * .42f), CornerRadius(10f))
            drawRoundRect(Color.White.copy(alpha = .94f), Offset(top.left + 23f, top.top + 18f), Size(top.width * .28f, top.height * .25f), CornerRadius(12f))
            drawRoundRect(Color.White.copy(alpha = .88f), Offset(top.left + top.width * .39f, top.top + 18f), Size(top.width * .24f, top.height * .25f), CornerRadius(12f))
            drawLine(Color.White.copy(alpha = .26f), Offset(top.left + 16f, top.center.y), Offset(top.right - 16f, top.center.y), 2f)
        }
        DigitalFurnitureKind.SOFA -> {
            drawRoundRect(side, Offset(top.left + 8f, top.top - 26f), Size(top.width - 16f, 39f), CornerRadius(12f))
            drawRoundRect(color.dark(.76f), Offset(top.left - 3f, top.top + 5f), Size(23f, top.height - 2f), CornerRadius(9f))
            drawRoundRect(color.dark(.76f), Offset(top.right - 20f, top.top + 5f), Size(23f, top.height - 2f), CornerRadius(9f))
            repeat(3) { i ->
                val segment = (top.width - 48f) / 3f
                drawRoundRect(
                    Brush.verticalGradient(listOf(color.light(), color)),
                    Offset(top.left + 24f + i * segment, top.top + 20f),
                    Size(segment - 5f, top.height - 28f),
                    CornerRadius(10f),
                )
                drawLine(
                    Color.White.copy(alpha = .20f),
                    Offset(top.left + 27f + i * segment, top.top + 25f),
                    Offset(top.left + 27f + i * segment + segment - 12f, top.top + 25f),
                    2f,
                )
            }
        }
        DigitalFurnitureKind.TABLE, DigitalFurnitureKind.COFFEE_TABLE -> {
            drawRoundRect(Color.White.copy(alpha = .16f), Offset(top.left + 9f, top.top + 7f), Size(top.width - 18f, top.height - 14f), CornerRadius(10f), style = Stroke(2f))
            listOf(top.left + 18f, top.right - 18f).forEach { x ->
                drawLine(side, Offset(x, top.bottom - 8f), Offset(x, b.bottom + 13f), 7f, StrokeCap.Round)
            }
        }
        DigitalFurnitureKind.CHAIR -> {
            drawRoundRect(side, Offset(top.left + 10f, top.top - 24f), Size(top.width - 20f, 35f), CornerRadius(9f))
            drawRoundRect(Brush.verticalGradient(listOf(color.light(), color)), Offset(top.left + 12f, top.top + 12f), Size(top.width - 24f, top.height - 21f), CornerRadius(9f))
            drawLine(side, Offset(top.left + 15f, top.bottom - 6f), Offset(top.left + 12f, b.bottom + 12f), 6f, StrokeCap.Round)
            drawLine(side, Offset(top.right - 15f, top.bottom - 6f), Offset(top.right - 12f, b.bottom + 12f), 6f, StrokeCap.Round)
        }
        DigitalFurnitureKind.DESK -> {
            drawLine(side, Offset(top.left + 17f, top.bottom - 7f), Offset(top.left + 14f, b.bottom + 17f), 8f, StrokeCap.Round)
            drawLine(side, Offset(top.right - 17f, top.bottom - 7f), Offset(top.right - 14f, b.bottom + 17f), 8f, StrokeCap.Round)
            drawRoundRect(Color(0xFF1C2A27).copy(alpha = .34f), Offset(top.left + 18f, top.top + 15f), Size(top.width * .34f, top.height - 28f), CornerRadius(5f))
            drawCircle(Color(0xFFE6CF96), 3f, Offset(top.left + top.width * .43f, top.center.y))
        }
        DigitalFurnitureKind.SHELF, DigitalFurnitureKind.CABINET, DigitalFurnitureKind.NIGHTSTAND -> {
            val slots = if (top.width > 110f) 3 else 2
            repeat(slots) { i ->
                val x = top.left + 10f + i * (top.width - 20f) / slots
                val width = (top.width - 30f) / slots
                drawRoundRect(Color(0xFF172825).copy(alpha = .42f), Offset(x, top.top + 14f), Size(width, top.height - 27f), CornerRadius(4f))
                drawCircle(Color(0xFFE8D49E), 3f, Offset(x + width - 8f, top.center.y))
            }
        }
        DigitalFurnitureKind.TV -> {
            drawRoundRect(Color(0xFF07100F), Offset(top.left + 10f, top.top - 23f), Size(top.width - 20f, top.height + 1f), CornerRadius(8f))
            drawRoundRect(
                Brush.linearGradient(listOf(Color(0xFF527874), Color(0xFF17312E), Color(0xFF061110))),
                Offset(top.left + 16f, top.top - 17f),
                Size(top.width - 32f, top.height - 11f),
                CornerRadius(5f),
            )
            drawLine(Color.White.copy(alpha = .23f), Offset(top.left + 25f, top.top - 8f), Offset(top.right - 36f, top.bottom - 30f), 3f, StrokeCap.Round)
            drawRoundRect(side, Offset(top.center.x - 22f, top.bottom - 8f), Size(44f, 13f), CornerRadius(4f))
        }
        else -> {
            drawOval(Color.White.copy(alpha = .18f), Offset(top.left + 10f, top.top + 8f), Size((top.width - 20f).coerceAtLeast(8f), (top.height - 16f).coerceAtLeast(8f)))
        }
    }
}

private fun DrawScope.drawFurnitureBody(b: WorldRectangle, color: Color, rise: Float): WorldRectangle {
    val top = WorldRectangle(b.left, b.top - rise, b.right, b.bottom - rise)
    val side = color.dark(.64f)
    val front = color.dark(.74f)
    drawOval(Color.Black.copy(alpha = .22f), Offset(b.left + 8f, b.bottom - b.height * .28f + 15f), Size(b.width + 18f, b.height * .40f))
    drawRoundRect(front, Offset(b.left, top.bottom - 2f), Size(b.width, rise + 3f), CornerRadius(7f))
    val right = Path().apply {
        moveTo(top.right - 1f, top.top + 7f)
        lineTo(b.right, b.top + 7f)
        lineTo(b.right, b.bottom - 4f)
        lineTo(top.right - 1f, top.bottom - 4f)
        close()
    }
    drawPath(right, side.copy(alpha = .92f))
    drawRoundRect(
        Brush.linearGradient(listOf(color.light(), color, color.dark(.86f))),
        Offset(top.left, top.top),
        Size(top.width, top.height),
        CornerRadius(9f),
    )
    drawLine(Color.White.copy(alpha = .24f), Offset(top.left + 8f, top.top + 7f), Offset(top.right - 13f, top.top + 7f), 2f, StrokeCap.Round)
    return top
}

private fun DrawScope.drawPlant(b: WorldRectangle, color: Color) {
    val cx = b.center.x
    drawOval(Color.Black.copy(alpha = .18f), Offset(cx - 35f, b.bottom - 6f), Size(76f, 25f))
    drawLine(Color(0xFF3D614D), Offset(cx, b.bottom - 30f), Offset(cx, b.top - 22f), 7f, StrokeCap.Round)
    val leaves = listOf(
        -40f to -36f, -25f to -58f, -8f to -68f, 13f to -64f, 32f to -48f, 42f to -24f,
        -34f to -12f, 0f to -38f, 27f to -13f,
    )
    leaves.forEachIndexed { index, (dx, dy) ->
        val leafColor = if (index % 2 == 0) Color(0xFF6E9879) else Color(0xFF3F7159)
        drawOval(
            Brush.linearGradient(listOf(leafColor.light(), leafColor.dark(.82f))),
            Offset(cx + dx - 18f, b.bottom + dy - 22f),
            Size(36f, 49f),
        )
    }
    val pot = if (color.red + color.green > 1.2f) Color(0xFFC1A583) else Color(0xFF8F765F)
    drawRoundRect(Brush.verticalGradient(listOf(pot.light(), pot.dark(.80f))), Offset(cx - 24f, b.bottom - 34f), Size(48f, 34f), CornerRadius(6f))
    drawOval(pot.light(), Offset(cx - 25f, b.bottom - 39f), Size(50f, 13f))
}

private fun DrawScope.drawFloorLamp(b: WorldRectangle, lightPhase: Float) {
    val cx = b.center.x
    val topY = b.top - 58f
    drawOval(Color.Black.copy(alpha = .17f), Offset(cx - 31f, b.bottom - 4f), Size(65f, 20f))
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFFFE8AA).copy(alpha = .34f + lightPhase * .12f), Color.Transparent)),
        72f + lightPhase * 10f,
        Offset(cx, topY + 9f),
    )
    drawLine(Color(0xFF4C5550), Offset(cx, b.bottom - 4f), Offset(cx, topY + 26f), 6f, StrokeCap.Round)
    drawOval(Color(0xFF4B5550), Offset(cx - 23f, b.bottom - 10f), Size(46f, 15f))
    val shade = Path().apply {
        moveTo(cx - 31f, topY + 5f)
        lineTo(cx + 31f, topY + 5f)
        lineTo(cx + 20f, topY + 37f)
        lineTo(cx - 20f, topY + 37f)
        close()
    }
    drawPath(shade, Brush.verticalGradient(listOf(Color(0xFFFFE8B3), Color(0xFFC99D63)), startY = topY, endY = topY + 40f))
    drawLine(Color.White.copy(alpha = .30f), Offset(cx - 22f, topY + 11f), Offset(cx + 22f, topY + 11f), 2f)
}

private fun DrawScope.drawTableLamp(b: WorldRectangle, lightPhase: Float) {
    val cx = b.center.x
    val topY = b.top - 28f
    drawOval(Color.Black.copy(alpha = .15f), Offset(cx - 24f, b.bottom - 2f), Size(50f, 16f))
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFFFE9B2).copy(alpha = .32f + lightPhase * .10f), Color.Transparent)),
        49f,
        Offset(cx, topY + 12f),
    )
    drawLine(Color(0xFF555B56), Offset(cx, b.bottom - 2f), Offset(cx, topY + 24f), 5f, StrokeCap.Round)
    drawOval(Color(0xFF555B56), Offset(cx - 15f, b.bottom - 7f), Size(30f, 10f))
    val shade = Path().apply {
        moveTo(cx - 23f, topY + 5f)
        lineTo(cx + 23f, topY + 5f)
        lineTo(cx + 15f, topY + 28f)
        lineTo(cx - 15f, topY + 28f)
        close()
    }
    drawPath(shade, Brush.verticalGradient(listOf(Color(0xFFFFE9B9), Color(0xFFD0A66A)), startY = topY, endY = topY + 30f))
}

private fun DrawScope.drawSoftCushion(b: WorldRectangle, color: Color) {
    drawOval(Color.Black.copy(alpha = .16f), Offset(b.left + 5f, b.bottom - 14f), Size(b.width, 22f))
    drawRoundRect(
        Brush.radialGradient(listOf(color.light(), color, color.dark(.82f)), center = b.center.toOffset(), radius = b.width),
        Offset(b.left, b.top),
        Size(b.width, b.height),
        CornerRadius(18f),
    )
    drawRoundRect(Color.White.copy(alpha = .22f), Offset(b.left + 5f, b.top + 5f), Size(b.width - 10f, b.height - 10f), CornerRadius(15f), style = Stroke(1.8f))
    drawCircle(color.dark(.72f), 3f, b.center.toOffset())
}

private fun DrawScope.drawBasket(b: WorldRectangle) {
    val wicker = Color(0xFFAA835C)
    drawOval(Color.Black.copy(alpha = .16f), Offset(b.left + 5f, b.bottom - 10f), Size(b.width + 6f, 20f))
    drawRoundRect(Brush.verticalGradient(listOf(wicker.light(), wicker.dark(.76f))), Offset(b.left, b.top + 10f), Size(b.width, b.height - 10f), CornerRadius(10f))
    drawOval(Color(0xFF75583D), Offset(b.left + 3f, b.top + 4f), Size(b.width - 6f, 18f))
    drawOval(Color(0xFFD7B78E), Offset(b.left + 8f, b.top + 7f), Size(b.width - 16f, 11f))
    repeat(4) { i ->
        drawLine(Color(0xFF6F5037).copy(alpha = .46f), Offset(b.left + 11f + i * 12f, b.top + 18f), Offset(b.left + 8f + i * 13f, b.bottom - 5f), 1.7f)
    }
    repeat(2) { i ->
        val y = b.top + 28f + i * 13f
        drawLine(Color.White.copy(alpha = .15f), Offset(b.left + 7f, y), Offset(b.right - 7f, y), 1.6f)
    }
}

private fun DrawScope.drawDecor(b: WorldRectangle, color: Color) {
    val cx = b.center.x
    drawOval(Color.Black.copy(alpha = .15f), Offset(cx - 24f, b.bottom - 5f), Size(52f, 16f))
    val vase = Path().apply {
        moveTo(cx - 10f, b.top + 4f)
        lineTo(cx + 10f, b.top + 4f)
        cubicTo(cx + 8f, b.top + 18f, cx + 23f, b.top + 22f, cx + 20f, b.bottom - 8f)
        cubicTo(cx + 15f, b.bottom + 1f, cx - 15f, b.bottom + 1f, cx - 20f, b.bottom - 8f)
        cubicTo(cx - 23f, b.top + 22f, cx - 8f, b.top + 18f, cx - 10f, b.top + 4f)
        close()
    }
    drawPath(vase, Brush.linearGradient(listOf(color.light(), color, color.dark(.75f))))
    drawLine(Color.White.copy(alpha = .32f), Offset(cx - 8f, b.top + 11f), Offset(cx - 12f, b.bottom - 14f), 2f, StrokeCap.Round)
}

private fun WorldVector.toOffset() = Offset(x, y)

private fun furnitureColor(key: String): Color = when (key) {
    "sage", "olive", "leaf", "cactus" -> Color(0xFF718B7D)
    "sky", "navy" -> Color(0xFF667F8A)
    "rose" -> Color(0xFF9B7D78)
    "charcoal" -> Color(0xFF46514E)
    "latte", "rattan", "wood", "walnut" -> Color(0xFF9D795A)
    "glass" -> Color(0xFF88A9A7)
    "warm" -> Color(0xFFC69B61)
    "white" -> Color(0xFFD8DDD8)
    else -> Color(0xFFAAA39A)
}

private fun Color.dark(factor: Float) = copy(
    red = red * factor,
    green = green * factor,
    blue = blue * factor,
)

private fun Color.light() = copy(
    red = (red + (1f - red) * .18f).coerceAtMost(1f),
    green = (green + (1f - green) * .18f).coerceAtMost(1f),
    blue = (blue + (1f - blue) * .18f).coerceAtMost(1f),
)
