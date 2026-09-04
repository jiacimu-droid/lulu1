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
 * A home is composed as a small lived-in diorama, not as an inventory grid. Human-facing position
 * descriptions still matter: words such as “窗边 / 角落 / 靠墙 / 客厅” bias the real spatial slot.
 * The fallback remains deterministic so old saves do not jump around between launches.
 */
internal fun buildDigitalRoomProps(items: List<DigitalWorldItem>): List<DigitalRoomProp> {
    if (items.isEmpty()) return emptyList()
    val wallKinds = setOf(DigitalFurnitureKind.WALL_ART, DigitalFurnitureKind.CLOCK, DigitalFurnitureKind.MIRROR)
    val result = mutableListOf<DigitalRoomProp>()

    val wallSpots = listOf(
        WorldVector(230f, 137f), WorldVector(455f, 137f), WorldVector(690f, 137f),
        WorldVector(930f, 137f), WorldVector(1_170f, 137f), WorldVector(1_390f, 137f),
    )
    items.filter { DigitalFurnitureCatalog.resolve(it).kind in wallKinds }.forEachIndexed { index, item ->
        val style = DigitalFurnitureCatalog.resolve(item)
        val (w, h) = furnitureSize(style.kind)
        result += DigitalRoomProp(item, style, centeredBounds(wallSpots[index % wallSpots.size], w, h), false)
    }

    val rugs = items.filter { DigitalFurnitureCatalog.resolve(it).kind == DigitalFurnitureKind.RUG }
    rugs.forEachIndexed { index, item ->
        val spots = listOf(
            WorldVector(800f, 670f), WorldVector(470f, 760f), WorldVector(1_130f, 760f), WorldVector(800f, 875f),
        )
        val style = DigitalFurnitureCatalog.resolve(item)
        result += DigitalRoomProp(item, style, centeredBounds(spots[index % spots.size], 350f, 205f), false)
    }

    val occupied = mutableListOf<WorldRectangle>()
    val floorItems = items.filter {
        val kind = DigitalFurnitureCatalog.resolve(it).kind
        kind !in wallKinds && kind != DigitalFurnitureKind.RUG
    }.sortedByDescending { furnitureArea(DigitalFurnitureCatalog.resolve(it).kind) }

    floorItems.forEachIndexed { index, item ->
        val style = DigitalFurnitureCatalog.resolve(item)
        val kind = style.kind
        val (w, h) = furnitureSize(kind)
        val semantic = semanticSpots(item.position)
        val preferred = preferredFurnitureSpots(kind)
        val candidates = (semantic + preferred + genericFurnitureSpots()).distinct()
        val start = ((item.id + item.position).hashCode() and Int.MAX_VALUE) % candidates.size.coerceAtLeast(1)
        val center = candidates.indices.asSequence()
            .map { candidates[(start + it) % candidates.size] }
            .firstOrNull { spot ->
                val trial = centeredBounds(spot, w, h).expanded(18f)
                occupied.none { overlap(trial, it) }
            }
            ?: WorldVector(215f + index % 7 * 195f, 890f - index % 3 * 70f)
        val bounds = centeredBounds(center, w, h)
        occupied += bounds.expanded(16f)
        val blocks = kind !in setOf(
            DigitalFurnitureKind.PLANT,
            DigitalFurnitureKind.CUSHION,
            DigitalFurnitureKind.BASKET,
            DigitalFurnitureKind.DECOR,
            DigitalFurnitureKind.TABLE_LAMP,
        )
        result += DigitalRoomProp(item, style, bounds, blocks)
    }
    return result
}

private fun semanticSpots(position: String): List<WorldVector> {
    val text = position.trim()
    return when {
        listOf("窗边", "靠窗", "窗前").any(text::contains) -> listOf(
            WorldVector(1_235f, 330f), WorldVector(1_400f, 400f), WorldVector(1_125f, 410f),
        )
        listOf("角落", "墙角", "角").any(text::contains) -> listOf(
            WorldVector(180f, 300f), WorldVector(1_420f, 300f), WorldVector(185f, 880f), WorldVector(1_415f, 880f),
        )
        listOf("靠墙", "墙边", "墙面").any(text::contains) -> listOf(
            WorldVector(250f, 315f), WorldVector(520f, 315f), WorldVector(800f, 315f), WorldVector(1_080f, 315f), WorldVector(1_350f, 315f),
        )
        listOf("客厅", "中央", "中心", "主空间").any(text::contains) -> listOf(
            WorldVector(800f, 660f), WorldVector(610f, 690f), WorldVector(990f, 690f), WorldVector(800f, 820f),
        )
        listOf("床边", "床头").any(text::contains) -> listOf(
            WorldVector(450f, 405f), WorldVector(1_150f, 405f), WorldVector(450f, 820f), WorldVector(1_150f, 820f),
        )
        listOf("门口", "入口", "玄关").any(text::contains) -> listOf(
            WorldVector(720f, 900f), WorldVector(880f, 900f), WorldVector(610f, 905f), WorldVector(990f, 905f),
        )
        else -> emptyList()
    }
}

private fun preferredFurnitureSpots(kind: DigitalFurnitureKind): List<WorldVector> = when (kind) {
    DigitalFurnitureKind.BED -> listOf(
        WorldVector(330f, 390f), WorldVector(1_270f, 390f), WorldVector(335f, 820f), WorldVector(1_265f, 820f),
    )
    DigitalFurnitureKind.SOFA -> listOf(
        WorldVector(800f, 690f), WorldVector(610f, 690f), WorldVector(990f, 690f), WorldVector(800f, 835f),
    )
    DigitalFurnitureKind.TV -> listOf(
        WorldVector(800f, 330f), WorldVector(1_315f, 470f), WorldVector(285f, 470f),
    )
    DigitalFurnitureKind.COFFEE_TABLE -> listOf(
        WorldVector(800f, 625f), WorldVector(620f, 625f), WorldVector(980f, 625f), WorldVector(800f, 785f),
    )
    DigitalFurnitureKind.TABLE -> listOf(
        WorldVector(800f, 600f), WorldVector(520f, 780f), WorldVector(1_080f, 780f),
    )
    DigitalFurnitureKind.DESK -> listOf(
        WorldVector(400f, 335f), WorldVector(1_200f, 335f), WorldVector(255f, 670f), WorldVector(1_345f, 670f),
    )
    DigitalFurnitureKind.SHELF, DigitalFurnitureKind.CABINET -> listOf(
        WorldVector(185f, 340f), WorldVector(1_415f, 340f), WorldVector(180f, 590f), WorldVector(1_420f, 590f),
        WorldVector(190f, 835f), WorldVector(1_410f, 835f),
    )
    DigitalFurnitureKind.NIGHTSTAND -> listOf(
        WorldVector(500f, 400f), WorldVector(1_100f, 400f), WorldVector(500f, 830f), WorldVector(1_100f, 830f),
    )
    DigitalFurnitureKind.CHAIR -> listOf(
        WorldVector(650f, 585f), WorldVector(950f, 585f), WorldVector(650f, 805f), WorldVector(950f, 805f),
        WorldVector(440f, 535f), WorldVector(1_160f, 535f),
    )
    DigitalFurnitureKind.FLOOR_LAMP -> listOf(
        WorldVector(235f, 430f), WorldVector(1_365f, 430f), WorldVector(505f, 710f), WorldVector(1_095f, 710f),
        WorldVector(245f, 875f), WorldVector(1_355f, 875f),
    )
    DigitalFurnitureKind.TABLE_LAMP -> listOf(
        WorldVector(485f, 420f), WorldVector(1_115f, 420f), WorldVector(555f, 735f), WorldVector(1_045f, 735f),
    )
    DigitalFurnitureKind.PLANT -> listOf(
        WorldVector(175f, 265f), WorldVector(1_425f, 265f), WorldVector(180f, 910f), WorldVector(1_420f, 910f),
        WorldVector(535f, 280f), WorldVector(1_065f, 280f),
    )
    DigitalFurnitureKind.CUSHION, DigitalFurnitureKind.BASKET, DigitalFurnitureKind.DECOR -> listOf(
        WorldVector(575f, 720f), WorldVector(1_025f, 720f), WorldVector(690f, 850f), WorldVector(910f, 850f),
        WorldVector(430f, 645f), WorldVector(1_170f, 645f),
    )
    else -> genericFurnitureSpots()
}

private fun genericFurnitureSpots(): List<WorldVector> = listOf(
    WorldVector(270f, 350f), WorldVector(520f, 350f), WorldVector(800f, 350f), WorldVector(1_080f, 350f), WorldVector(1_330f, 350f),
    WorldVector(270f, 570f), WorldVector(530f, 570f), WorldVector(800f, 570f), WorldVector(1_070f, 570f), WorldVector(1_330f, 570f),
    WorldVector(275f, 800f), WorldVector(530f, 800f), WorldVector(800f, 800f), WorldVector(1_070f, 800f), WorldVector(1_325f, 800f),
    WorldVector(430f, 915f), WorldVector(800f, 915f), WorldVector(1_170f, 915f),
)

private fun centeredBounds(c: WorldVector, w: Float, h: Float) =
    WorldRectangle(c.x - w / 2f, c.y - h / 2f, c.x + w / 2f, c.y + h / 2f)

private fun overlap(a: WorldRectangle, b: WorldRectangle) =
    a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

private fun furnitureSize(kind: DigitalFurnitureKind): Pair<Float, Float> = when (kind) {
    DigitalFurnitureKind.BED -> 245f to 148f
    DigitalFurnitureKind.SOFA -> 220f to 102f
    DigitalFurnitureKind.COFFEE_TABLE -> 138f to 78f
    DigitalFurnitureKind.TABLE -> 158f to 98f
    DigitalFurnitureKind.CHAIR -> 80f to 68f
    DigitalFurnitureKind.DESK -> 178f to 82f
    DigitalFurnitureKind.SHELF -> 178f to 68f
    DigitalFurnitureKind.CABINET -> 162f to 72f
    DigitalFurnitureKind.NIGHTSTAND -> 74f to 62f
    DigitalFurnitureKind.FLOOR_LAMP -> 60f to 60f
    DigitalFurnitureKind.TABLE_LAMP -> 52f to 48f
    DigitalFurnitureKind.PLANT -> 66f to 60f
    DigitalFurnitureKind.TV -> 168f to 66f
    DigitalFurnitureKind.CUSHION -> 58f to 48f
    DigitalFurnitureKind.BASKET -> 64f to 54f
    DigitalFurnitureKind.DECOR -> 56f to 50f
    DigitalFurnitureKind.RUG -> 350f to 205f
    DigitalFurnitureKind.MIRROR -> 106f to 92f
    DigitalFurnitureKind.WALL_ART -> 134f to 82f
    DigitalFurnitureKind.CLOCK -> 68f to 68f
}

private fun furnitureArea(kind: DigitalFurnitureKind): Float = furnitureSize(kind).let { it.first * it.second }

private fun digitalFurnitureAction(item: DigitalWorldItem, kind: DigitalFurnitureKind) = when (kind) {
    DigitalFurnitureKind.BED -> "我走到${item.name}边，停下来看看要不要坐下或躺一会儿。"
    DigitalFurnitureKind.SOFA, DigitalFurnitureKind.CHAIR -> "我走近${item.name}，可以坐下来待一会儿。"
    DigitalFurnitureKind.TV -> "我走到${item.name}前，看看要不要打开它。"
    DigitalFurnitureKind.SHELF, DigitalFurnitureKind.CABINET, DigitalFurnitureKind.NIGHTSTAND -> "我来到${item.name}旁，看看这里收着和摆着什么。"
    else -> "我走近${item.name}，仔细看了看。"
}

internal fun DrawScope.drawDigitalHomeWorld(props: List<DigitalRoomProp>, lightPhase: Float) {
    drawRect(
        Brush.radialGradient(
            listOf(Color(0xFF27352F), Color(0xFF0C1311), Color(0xFF060A09)),
            center = Offset(1_250f, 120f),
            radius = 1_450f,
        ),
        size = Size(DIGITAL_WORLD_WIDTH, DIGITAL_WORLD_HEIGHT),
    )
    drawRoundRect(Color.Black.copy(alpha = .46f), Offset(30f, 44f), Size(1_540f, 974f), CornerRadius(42f))

    // Back wall and deep wooden floor. Fewer, softer perspective lines make the room feel less like
    // graph paper and give furniture more visual weight.
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFFE8E1D6), Color(0xFFD5CCC0))),
        Offset(54f, 70f), Size(1_492f, 930f), CornerRadius(30f),
    )
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFFE8E2D9), Color(0xFFD8D0C5))),
        Offset(54f, 70f), Size(1_492f, 170f), CornerRadius(30f, 30f),
    )
    drawRect(
        Brush.verticalGradient(listOf(Color(0xFF876F5B), Color(0xFF5C4A3E), Color(0xFF3B312A))),
        Offset(54f, 228f), Size(1_492f, 772f),
    )
    drawRect(Color(0xFF6B594B), Offset(54f, 218f), Size(1_492f, 16f))
    drawLine(Color.White.copy(alpha = .24f), Offset(60f, 219f), Offset(1_540f, 219f), 2f)

    val vanish = Offset(800f, 226f)
    for (x in 110..1_490 step 170) {
        drawLine(Color(0xFF221C18).copy(alpha = .15f), vanish, Offset(x.toFloat(), 1_000f), 2f)
    }
    for (y in 360..960 step 122) {
        val alpha = .10f + ((y - 360f) / 600f) * .05f
        drawLine(Color(0xFF211B17).copy(alpha = alpha), Offset(58f, y.toFloat()), Offset(1_542f, y.toFloat() + 5f), 2f)
        drawLine(Color.White.copy(alpha = .035f), Offset(58f, y + 3f), Offset(1_542f, y + 8f), 1f)
    }

    // Architectural details: one low storage wall, one large window, one entrance. These are stable
    // room facts and visually anchor the furniture without pretending to be interactable objects.
    drawRoundRect(Color(0xFF9E8B78), Offset(96f, 105f), Size(485f, 82f), CornerRadius(11f))
    drawRoundRect(Color(0xFFF4EEE5).copy(alpha = .46f), Offset(109f, 116f), Size(459f, 57f), CornerRadius(7f))
    repeat(5) { i ->
        drawLine(Color(0xFF6B5B4D).copy(alpha = .40f), Offset(128f + i * 88f, 124f), Offset(128f + i * 88f, 165f), 2f)
    }

    drawRoundRect(Color(0xFF2B3733), Offset(1_058f, 88f), Size(350f, 112f), CornerRadius(17f))
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF9DB9B2), Color(0xFF55736D), Color(0xFF1D3430))),
        Offset(1_071f, 100f), Size(324f, 88f), CornerRadius(10f),
    )
    repeat(14) { i ->
        val bw = 13f + i % 3 * 5f
        val bh = 22f + (i * 17 % 48)
        val bx = 1_082f + i * 21f
        drawRect(Color(0xFF142723).copy(alpha = .77f), Offset(bx, 186f - bh), Size(bw, bh))
        if (i % 2 == 0) drawCircle(Color(0xFFFFDA8D).copy(alpha = .42f + lightPhase * .18f), 2.2f, Offset(bx + bw / 2f, 177f - bh * .45f))
    }
    drawRect(Color(0xFF203C36), Offset(1_223f, 100f), Size(8f, 88f))
    drawRect(Color(0xFF203C36), Offset(1_071f, 140f), Size(324f, 7f))
    drawLine(Color.White.copy(alpha = .34f), Offset(1_085f, 110f), Offset(1_365f, 110f), 2f)

    drawRoundRect(Color(0xFF3B4843), Offset(730f, 87f), Size(140f, 122f), CornerRadius(8f))
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF7F938C), Color(0xFF435B54))), Offset(748f, 105f), Size(104f, 104f), CornerRadius(5f))
    drawCircle(Color(0xFFE1C083), 7f, Offset(838f, 157f))

    val windowLight = Path().apply {
        moveTo(1_070f, 187f)
        lineTo(1_395f, 187f)
        lineTo(1_535f, 805f)
        lineTo(1_005f, 665f)
        close()
    }
    drawPath(
        windowLight,
        Brush.linearGradient(
            listOf(Color(0xFFFFF5D7).copy(alpha = .18f + lightPhase * .08f), Color(0xFFB9E5D8).copy(alpha = .05f), Color.Transparent),
            Offset(1_255f, 185f), Offset(1_315f, 760f),
        ),
    )

    props.filter { it.style.kind == DigitalFurnitureKind.RUG }.forEach { drawDigitalProp(it, lightPhase) }
    props.filterNot { it.style.kind == DigitalFurnitureKind.RUG }
        .sortedBy { it.bounds.bottom }
        .forEach { drawDigitalProp(it, lightPhase) }

    // Warm pools from the lived-in room, plus dust motes in the window light.
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFD996).copy(alpha = .09f), Color.Transparent)), 330f, Offset(420f, 760f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFFB8F0DE).copy(alpha = .06f), Color.Transparent)), 360f, Offset(1_260f, 700f))
    repeat(20) { i ->
        drawCircle(
            Color(0xFFFFF3CF).copy(alpha = .08f + (i % 4) * .025f),
            1.5f + i % 3,
            Offset(1_005f + (i * 67 % 470), 245f + (i * 79 % 520)),
        )
    }
    drawRoundRect(Color.White.copy(alpha = .10f), Offset(54f, 70f), Size(1_492f, 930f), CornerRadius(30f), style = Stroke(3f))
}

internal fun DrawScope.drawDigitalSharedWorld(sceneCode: String, lightPhase: Float) {
    val cloud = sceneCode == DigitalWorldStore.CLOUD_MEADOW
    drawRect(
        if (cloud) {
            Brush.verticalGradient(listOf(Color(0xFF769BAB), Color(0xFFC2D7D5), Color(0xFFF0EDE4)))
        } else {
            Brush.radialGradient(
                listOf(Color(0xFF6B807A), Color(0xFF172723), Color(0xFF07110F)),
                center = Offset(800f, 520f), radius = 1_050f,
            )
        },
        size = Size(DIGITAL_WORLD_WIDTH, DIGITAL_WORLD_HEIGHT),
    )
    if (cloud) {
        repeat(20) { i ->
            val x = ((i * 271) % 1_680 - 80).toFloat()
            val y = (170 + i * 137 % 770).toFloat()
            val r = 72f + i % 5 * 24f
            drawOval(Color.White.copy(alpha = .28f + i % 3 * .07f), Offset(x - r, y - r * .36f), Size(r * 2.2f, r * .74f))
        }
        val island = Path().apply {
            moveTo(145f, 515f)
            cubicTo(335f, 310f, 1_245f, 300f, 1_455f, 520f)
            cubicTo(1_515f, 720f, 1_260f, 945f, 810f, 960f)
            cubicTo(335f, 950f, 65f, 740f, 145f, 515f)
            close()
        }
        drawPath(island, Brush.verticalGradient(listOf(Color(0xFFF7FAF5), Color(0xFFDCE8E1))))
        drawPath(island, Color.White.copy(alpha = .66f), style = Stroke(5f))
        repeat(7) { i ->
            val p = Offset(390f + i * 140f, 580f + (i % 2) * 105f)
            drawCircle(Color(0xFF8CB7AA).copy(alpha = .13f), 46f, p)
            drawCircle(Color.White.copy(alpha = .28f), 44f, p, style = Stroke(2f))
        }
    } else {
        repeat(5) { ring ->
            drawCircle(Color(0xFFB8F3E3).copy(alpha = .08f + lightPhase * .03f), 110f + ring * 86f, Offset(800f, 535f), style = Stroke(4f))
        }
        drawCircle(Brush.radialGradient(listOf(Color(0xFFF4FFFC), Color(0xFF9BE0CF).copy(alpha = .40f), Color.Transparent)), 176f, Offset(800f, 535f))
    }
}

private fun DrawScope.drawDigitalProp(prop: DigitalRoomProp, lightPhase: Float) {
    val kind = prop.style.kind
    val b = prop.bounds
    val color = furnitureColor(prop.style.colorKey)
    val pattern = prop.style.pattern

    when (kind) {
        DigitalFurnitureKind.RUG -> {
            drawOval(Color.Black.copy(alpha = .17f), Offset(b.left + 14f, b.top + 25f), Size(b.width, b.height))
            if (pattern == "round" || pattern == "cloud") {
                drawOval(Brush.radialGradient(listOf(color.light(.26f), color, color.dark(.72f))), Offset(b.left, b.top), Size(b.width, b.height))
                drawOval(Color.White.copy(alpha = .18f), Offset(b.left + 8f, b.top + 8f), Size(b.width - 16f, b.height - 16f), style = Stroke(2f))
            } else {
                drawRoundRect(Brush.radialGradient(listOf(color.light(.24f), color, color.dark(.74f))), Offset(b.left, b.top), Size(b.width, b.height), CornerRadius(68f))
                drawRoundRect(Color.White.copy(alpha = .17f), Offset(b.left + 8f, b.top + 8f), Size(b.width - 16f, b.height - 16f), CornerRadius(60f), style = Stroke(2f))
            }
            when (pattern) {
                "stripe" -> repeat(5) { i -> drawLine(Color.White.copy(alpha = .09f), Offset(b.left + 50f + i * 62f, b.top + 24f), Offset(b.left + 25f + i * 67f, b.bottom - 24f), 3f) }
                "check" -> {
                    repeat(4) { i -> drawLine(Color.White.copy(alpha = .07f), Offset(b.left + 58f + i * 72f, b.top + 18f), Offset(b.left + 58f + i * 72f, b.bottom - 18f), 2f) }
                    repeat(2) { i -> drawLine(Color.White.copy(alpha = .07f), Offset(b.left + 22f, b.top + 68f + i * 62f), Offset(b.right - 22f, b.top + 68f + i * 62f), 2f) }
                }
            }
            return
        }
        DigitalFurnitureKind.PLANT -> { drawPlant(b, color); return }
        DigitalFurnitureKind.FLOOR_LAMP -> { drawFloorLamp(b, lightPhase, pattern); return }
        DigitalFurnitureKind.TABLE_LAMP -> { drawTableLamp(b, lightPhase, pattern); return }
        DigitalFurnitureKind.CUSHION -> { drawSoftCushion(b, color); return }
        DigitalFurnitureKind.BASKET -> { drawBasket(b); return }
        DigitalFurnitureKind.DECOR -> { drawDecor(b, color); return }
        else -> Unit
    }

    if (kind in setOf(DigitalFurnitureKind.WALL_ART, DigitalFurnitureKind.MIRROR, DigitalFurnitureKind.CLOCK)) {
        drawWallObject(kind, b, color)
        return
    }

    val rise = when (kind) {
        DigitalFurnitureKind.SHELF, DigitalFurnitureKind.CABINET -> if (pattern in setOf("tall", "glassdoor")) 58f else 39f
        DigitalFurnitureKind.TV -> 36f
        DigitalFurnitureKind.SOFA -> 32f
        DigitalFurnitureKind.CHAIR -> 28f
        DigitalFurnitureKind.DESK, DigitalFurnitureKind.TABLE -> 25f
        DigitalFurnitureKind.BED -> if (pattern == "low") 15f else 23f
        else -> 16f
    }
    val top = drawFurnitureBody(b, color, rise)
    val side = color.dark(.62f)

    when (kind) {
        DigitalFurnitureKind.BED -> drawBed(top, b, color, side, pattern)
        DigitalFurnitureKind.SOFA -> drawSofa(top, color, side, pattern)
        DigitalFurnitureKind.TABLE, DigitalFurnitureKind.COFFEE_TABLE -> drawTable(top, b, color, side, pattern)
        DigitalFurnitureKind.CHAIR -> drawChair(top, b, color, side, pattern)
        DigitalFurnitureKind.DESK -> {
            drawLine(side, Offset(top.left + 17f, top.bottom - 7f), Offset(top.left + 14f, b.bottom + 17f), 8f, StrokeCap.Round)
            drawLine(side, Offset(top.right - 17f, top.bottom - 7f), Offset(top.right - 14f, b.bottom + 17f), 8f, StrokeCap.Round)
            drawRoundRect(Color(0xFF1C2421).copy(alpha = .28f), Offset(top.left + 18f, top.top + 15f), Size(top.width * .34f, (top.height - 28f).coerceAtLeast(8f)), CornerRadius(5f))
            drawCircle(Color(0xFFE1C88B), 3f, Offset(top.left + top.width * .43f, top.center.y))
        }
        DigitalFurnitureKind.SHELF, DigitalFurnitureKind.CABINET, DigitalFurnitureKind.NIGHTSTAND -> drawStorage(top, color, pattern)
        DigitalFurnitureKind.TV -> drawTv(top, side, lightPhase)
        else -> drawOval(Color.White.copy(alpha = .15f), Offset(top.left + 10f, top.top + 8f), Size((top.width - 20f).coerceAtLeast(8f), (top.height - 16f).coerceAtLeast(8f)))
    }
}

private fun DrawScope.drawFurnitureBody(b: WorldRectangle, color: Color, rise: Float): WorldRectangle {
    val top = WorldRectangle(b.left, b.top - rise, b.right, b.bottom - rise)
    val side = color.dark(.62f)
    val front = color.dark(.70f)
    drawOval(Color.Black.copy(alpha = .23f), Offset(b.left + 9f, b.bottom - b.height * .24f + 17f), Size(b.width + 20f, b.height * .36f))
    drawRoundRect(front, Offset(b.left, top.bottom - 2f), Size(b.width, rise + 4f), CornerRadius(8f))
    val right = Path().apply {
        moveTo(top.right - 1f, top.top + 7f)
        lineTo(b.right, b.top + 7f)
        lineTo(b.right, b.bottom - 4f)
        lineTo(top.right - 1f, top.bottom - 4f)
        close()
    }
    drawPath(right, side.copy(alpha = .94f))
    drawRoundRect(Brush.linearGradient(listOf(color.light(.22f), color, color.dark(.84f))), Offset(top.left, top.top), Size(top.width, top.height), CornerRadius(11f))
    drawLine(Color.White.copy(alpha = .23f), Offset(top.left + 9f, top.top + 7f), Offset(top.right - 14f, top.top + 7f), 2f, StrokeCap.Round)
    return top
}

private fun DrawScope.drawBed(top: WorldRectangle, b: WorldRectangle, color: Color, side: Color, pattern: String) {
    val headRise = if (pattern == "canopy") 44f else 30f
    drawRoundRect(side, Offset(top.left + 5f, top.top - headRise), Size(top.width - 10f, headRise + 12f), CornerRadius(11f))
    if (pattern == "canopy") {
        listOf(top.left + 9f, top.right - 9f).forEach { x -> drawLine(color.dark(.55f), Offset(x, top.top - 70f), Offset(x, b.bottom + 4f), 5f, StrokeCap.Round) }
        drawLine(color.dark(.55f), Offset(top.left + 9f, top.top - 68f), Offset(top.right - 9f, top.top - 68f), 5f, StrokeCap.Round)
        drawRect(Color.White.copy(alpha = .18f), Offset(top.left + 12f, top.top - 65f), Size(top.width - 24f, 57f))
    }
    drawRoundRect(Color(0xFFF4EFE7), Offset(top.left + 10f, top.top + 8f), Size(top.width - 20f, top.height - 14f), CornerRadius(15f))
    drawRoundRect(color.copy(alpha = .78f), Offset(top.left + 12f, top.top + top.height * .49f), Size(top.width - 24f, top.height * .40f), CornerRadius(12f))
    if (pattern == "stripe") repeat(4) { i -> drawLine(Color.White.copy(alpha = .22f), Offset(top.left + 26f + i * 43f, top.center.y + 5f), Offset(top.left + 12f + i * 49f, top.bottom - 13f), 3f) }
    if (pattern == "check") {
        repeat(4) { i -> drawLine(Color.White.copy(alpha = .16f), Offset(top.left + 22f + i * 48f, top.center.y + 3f), Offset(top.left + 22f + i * 48f, top.bottom - 12f), 2f) }
        drawLine(Color.White.copy(alpha = .16f), Offset(top.left + 15f, top.bottom - 31f), Offset(top.right - 15f, top.bottom - 31f), 2f)
    }
    drawRoundRect(Color.White.copy(alpha = .96f), Offset(top.left + 23f, top.top + 18f), Size(top.width * .29f, top.height * .25f), CornerRadius(14f))
    drawRoundRect(Color.White.copy(alpha = .90f), Offset(top.left + top.width * .40f, top.top + 18f), Size(top.width * .25f, top.height * .25f), CornerRadius(14f))
}

private fun DrawScope.drawSofa(top: WorldRectangle, color: Color, side: Color, pattern: String) {
    val curved = pattern == "boucle"
    drawRoundRect(side, Offset(top.left + 7f, top.top - 30f), Size(top.width - 14f, 43f), CornerRadius(if (curved) 24f else 14f))
    drawRoundRect(color.dark(.72f), Offset(top.left - 3f, top.top + 5f), Size(25f, top.height - 1f), CornerRadius(12f))
    drawRoundRect(color.dark(.72f), Offset(top.right - 22f, top.top + 5f), Size(25f, top.height - 1f), CornerRadius(12f))
    val segments = if (pattern == "modular") 4 else 3
    repeat(segments) { i ->
        val segment = (top.width - 48f) / segments
        drawRoundRect(
            Brush.verticalGradient(listOf(color.light(if (curved) .30f else .20f), color)),
            Offset(top.left + 24f + i * segment, top.top + 19f),
            Size(segment - 5f, top.height - 27f),
            CornerRadius(if (curved) 17f else 10f),
        )
    }
    if (curved) drawLine(Color.White.copy(alpha = .28f), Offset(top.left + 25f, top.top - 18f), Offset(top.right - 25f, top.top - 18f), 2f, StrokeCap.Round)
}

private fun DrawScope.drawTable(top: WorldRectangle, b: WorldRectangle, color: Color, side: Color, pattern: String) {
    if (pattern == "round" || pattern == "oval" || pattern == "pebble") {
        drawOval(Brush.radialGradient(listOf(color.light(.24f), color, color.dark(.78f))), Offset(top.left, top.top), Size(top.width, top.height))
        drawOval(Color.White.copy(alpha = .18f), Offset(top.left + 8f, top.top + 6f), Size(top.width - 16f, top.height - 12f), style = Stroke(2f))
    } else if (pattern == "glass" || pattern == "round_glass") {
        drawRoundRect(Color(0xFFBDE2DD).copy(alpha = .45f), Offset(top.left + 5f, top.top + 5f), Size(top.width - 10f, top.height - 10f), CornerRadius(15f))
        drawRoundRect(Color.White.copy(alpha = .38f), Offset(top.left + 9f, top.top + 9f), Size(top.width - 18f, top.height - 18f), CornerRadius(12f), style = Stroke(2f))
    } else {
        drawRoundRect(Color.White.copy(alpha = .14f), Offset(top.left + 9f, top.top + 7f), Size(top.width - 18f, top.height - 14f), CornerRadius(11f), style = Stroke(2f))
    }
    listOf(top.left + 20f, top.right - 20f).forEach { x -> drawLine(side, Offset(x, top.bottom - 8f), Offset(x, b.bottom + 13f), 7f, StrokeCap.Round) }
}

private fun DrawScope.drawChair(top: WorldRectangle, b: WorldRectangle, color: Color, side: Color, pattern: String) {
    val lounge = pattern == "lounge"
    drawRoundRect(side, Offset(top.left + 9f, top.top - if (lounge) 30f else 25f), Size(top.width - 18f, if (lounge) 42f else 36f), CornerRadius(if (lounge) 16f else 10f))
    drawRoundRect(Brush.verticalGradient(listOf(color.light(.23f), color)), Offset(top.left + 12f, top.top + 12f), Size(top.width - 24f, top.height - 21f), CornerRadius(if (lounge) 14f else 9f))
    drawLine(side, Offset(top.left + 15f, top.bottom - 6f), Offset(top.left + 12f, b.bottom + 12f), 6f, StrokeCap.Round)
    drawLine(side, Offset(top.right - 15f, top.bottom - 6f), Offset(top.right - 12f, b.bottom + 12f), 6f, StrokeCap.Round)
    if (pattern == "rattan") repeat(3) { i -> drawLine(Color(0xFFE6C49A).copy(alpha = .38f), Offset(top.left + 22f + i * 13f, top.top - 16f), Offset(top.left + 22f + i * 13f, top.top + 7f), 1.5f) }
}

private fun DrawScope.drawStorage(top: WorldRectangle, color: Color, pattern: String) {
    val slots = if (top.width > 115f) 3 else 2
    repeat(slots) { i ->
        val x = top.left + 10f + i * (top.width - 20f) / slots
        val width = (top.width - 30f) / slots
        val fill = if (pattern == "glassdoor") Color(0xFFB7D8D4).copy(alpha = .28f) else Color(0xFF17211F).copy(alpha = .35f)
        drawRoundRect(fill, Offset(x, top.top + 14f), Size(width, (top.height - 27f).coerceAtLeast(8f)), CornerRadius(5f))
        drawCircle(Color(0xFFE4CA8E), 3f, Offset(x + width - 8f, top.center.y))
        if (pattern == "glassdoor") drawLine(Color.White.copy(alpha = .28f), Offset(x + 6f, top.top + 18f), Offset(x + width - 9f, top.bottom - 18f), 2f)
    }
}

private fun DrawScope.drawTv(top: WorldRectangle, side: Color, lightPhase: Float) {
    drawRoundRect(Color(0xFF050A09), Offset(top.left + 10f, top.top - 25f), Size(top.width - 20f, top.height + 3f), CornerRadius(10f))
    drawRoundRect(
        Brush.linearGradient(listOf(Color(0xFF68948B), Color(0xFF17302C), Color(0xFF050C0B))),
        Offset(top.left + 17f, top.top - 18f), Size(top.width - 34f, top.height - 11f), CornerRadius(7f),
    )
    drawCircle(Brush.radialGradient(listOf(Color(0xFFB9FFE8).copy(alpha = .10f + lightPhase * .07f), Color.Transparent)), 85f, top.center.toOffset())
    drawLine(Color.White.copy(alpha = .27f), Offset(top.left + 27f, top.top - 9f), Offset(top.right - 39f, top.bottom - 30f), 3f, StrokeCap.Round)
    drawRoundRect(side, Offset(top.center.x - 24f, top.bottom - 8f), Size(48f, 14f), CornerRadius(4f))
}

private fun DrawScope.drawWallObject(kind: DigitalFurnitureKind, b: WorldRectangle, color: Color) {
    drawRoundRect(Color.Black.copy(alpha = .20f), Offset(b.left + 8f, b.top + 10f), Size(b.width, b.height), CornerRadius(12f))
    when (kind) {
        DigitalFurnitureKind.CLOCK -> {
            drawCircle(Brush.radialGradient(listOf(color.light(.22f), color)), b.width * .45f, b.center.toOffset())
            drawCircle(Color.White.copy(alpha = .55f), b.width * .45f, b.center.toOffset(), style = Stroke(3f))
            drawLine(Color(0xFF1C2522), b.center.toOffset(), Offset(b.center.x, b.center.y - 19f), 3.5f, StrokeCap.Round)
            drawLine(Color(0xFF1C2522), b.center.toOffset(), Offset(b.center.x + 14f, b.center.y + 8f), 3f, StrokeCap.Round)
            drawCircle(Color(0xFFE3C887), 3f, b.center.toOffset())
        }
        DigitalFurnitureKind.MIRROR -> {
            drawRoundRect(Color(0xFF756C60), Offset(b.left, b.top), Size(b.width, b.height), CornerRadius(30f))
            drawRoundRect(Brush.linearGradient(listOf(Color(0xFFF5FFFF), Color(0xFFA8C8C2), Color(0xFF607B75))), Offset(b.left + 6f, b.top + 6f), Size(b.width - 12f, b.height - 12f), CornerRadius(26f))
            drawLine(Color.White.copy(alpha = .72f), Offset(b.left + 19f, b.top + 16f), Offset(b.right - 28f, b.bottom - 25f), 4f, StrokeCap.Round)
        }
        else -> {
            drawRoundRect(Color(0xFF5B4C40), Offset(b.left, b.top), Size(b.width, b.height), CornerRadius(8f))
            drawRoundRect(Brush.linearGradient(listOf(color.light(.24f), color.dark(.78f))), Offset(b.left + 8f, b.top + 8f), Size(b.width - 16f, b.height - 16f), CornerRadius(5f))
            drawPath(Path().apply {
                moveTo(b.left + 18f, b.bottom - 20f)
                lineTo(b.left + b.width * .36f, b.top + 24f)
                lineTo(b.left + b.width * .58f, b.bottom - 25f)
                lineTo(b.right - 17f, b.top + 18f)
            }, Color.White.copy(alpha = .20f), style = Stroke(3f))
        }
    }
}

private fun DrawScope.drawPlant(b: WorldRectangle, color: Color) {
    val cx = b.center.x
    drawOval(Color.Black.copy(alpha = .18f), Offset(cx - 37f, b.bottom - 6f), Size(80f, 26f))
    drawLine(Color(0xFF365742), Offset(cx, b.bottom - 30f), Offset(cx, b.top - 26f), 7f, StrokeCap.Round)
    val leaves = listOf(-42f to -38f, -26f to -61f, -8f to -73f, 14f to -69f, 34f to -52f, 44f to -27f, -36f to -13f, 0f to -42f, 29f to -15f)
    leaves.forEachIndexed { index, (dx, dy) ->
        val leaf = if (index % 2 == 0) Color(0xFF6E9877) else Color(0xFF3C6B52)
        drawOval(Brush.linearGradient(listOf(leaf.light(.18f), leaf.dark(.76f))), Offset(cx + dx - 19f, b.bottom + dy - 22f), Size(38f, 52f))
    }
    val pot = if (color.red + color.green > 1.2f) Color(0xFFC1A17B) else Color(0xFF90745A)
    drawRoundRect(Brush.verticalGradient(listOf(pot.light(.20f), pot.dark(.76f))), Offset(cx - 25f, b.bottom - 35f), Size(50f, 35f), CornerRadius(7f))
    drawOval(pot.light(.17f), Offset(cx - 26f, b.bottom - 40f), Size(52f, 14f))
}

private fun DrawScope.drawFloorLamp(b: WorldRectangle, lightPhase: Float, pattern: String) {
    val cx = b.center.x
    val topY = b.top - 61f
    drawOval(Color.Black.copy(alpha = .17f), Offset(cx - 32f, b.bottom - 4f), Size(67f, 20f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE2A0).copy(alpha = .35f + lightPhase * .13f), Color.Transparent)), 78f + lightPhase * 10f, Offset(cx, topY + 12f))
    if (pattern == "tripod") {
        listOf(-16f, 0f, 16f).forEach { dx -> drawLine(Color(0xFF685846), Offset(cx, topY + 30f), Offset(cx + dx, b.bottom), 5f, StrokeCap.Round) }
    } else {
        drawLine(Color(0xFF4B534E), Offset(cx, b.bottom - 4f), Offset(cx, topY + 28f), 6f, StrokeCap.Round)
        drawOval(Color(0xFF4B534E), Offset(cx - 23f, b.bottom - 10f), Size(46f, 15f))
    }
    val shade = Path().apply { moveTo(cx - 33f, topY + 5f); lineTo(cx + 33f, topY + 5f); lineTo(cx + 21f, topY + 39f); lineTo(cx - 21f, topY + 39f); close() }
    drawPath(shade, Brush.verticalGradient(listOf(Color(0xFFFFE9BA), Color(0xFFC99B61)), startY = topY, endY = topY + 40f))
    if (pattern == "sparkle") repeat(5) { i -> drawCircle(Color(0xFFFFE4A0).copy(alpha = .52f), 2.5f, Offset(cx - 28f + i * 14f, topY - 10f + (i % 2) * 10f)) }
}

private fun DrawScope.drawTableLamp(b: WorldRectangle, lightPhase: Float, pattern: String) {
    val cx = b.center.x
    val topY = b.top - 30f
    drawOval(Color.Black.copy(alpha = .14f), Offset(cx - 24f, b.bottom - 2f), Size(50f, 16f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE9B2).copy(alpha = .34f + lightPhase * .10f), Color.Transparent)), 52f, Offset(cx, topY + 13f))
    if (pattern == "orb") {
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF4D1), Color(0xFFD8B16B))), 21f, Offset(cx, topY + 20f))
        drawLine(Color(0xFF555B56), Offset(cx, topY + 41f), Offset(cx, b.bottom - 3f), 5f, StrokeCap.Round)
    } else {
        drawLine(Color(0xFF555B56), Offset(cx, b.bottom - 2f), Offset(cx, topY + 25f), 5f, StrokeCap.Round)
        drawOval(Color(0xFF555B56), Offset(cx - 15f, b.bottom - 7f), Size(30f, 10f))
        val shade = Path().apply { moveTo(cx - 24f, topY + 5f); lineTo(cx + 24f, topY + 5f); lineTo(cx + 16f, topY + 29f); lineTo(cx - 16f, topY + 29f); close() }
        drawPath(shade, Brush.verticalGradient(listOf(Color(0xFFFFE9B9), Color(0xFFD0A36A)), startY = topY, endY = topY + 30f))
    }
}

private fun DrawScope.drawSoftCushion(b: WorldRectangle, color: Color) {
    drawOval(Color.Black.copy(alpha = .15f), Offset(b.left + 5f, b.bottom - 14f), Size(b.width, 22f))
    drawRoundRect(Brush.radialGradient(listOf(color.light(.24f), color, color.dark(.78f)), center = b.center.toOffset(), radius = b.width), Offset(b.left, b.top), Size(b.width, b.height), CornerRadius(20f))
    drawRoundRect(Color.White.copy(alpha = .20f), Offset(b.left + 5f, b.top + 5f), Size(b.width - 10f, b.height - 10f), CornerRadius(17f), style = Stroke(1.8f))
    drawCircle(color.dark(.68f), 3f, b.center.toOffset())
}

private fun DrawScope.drawBasket(b: WorldRectangle) {
    val wicker = Color(0xFFAA8258)
    drawOval(Color.Black.copy(alpha = .16f), Offset(b.left + 5f, b.bottom - 10f), Size(b.width + 6f, 20f))
    drawRoundRect(Brush.verticalGradient(listOf(wicker.light(.22f), wicker.dark(.72f))), Offset(b.left, b.top + 10f), Size(b.width, b.height - 10f), CornerRadius(11f))
    drawOval(Color(0xFF735438), Offset(b.left + 3f, b.top + 4f), Size(b.width - 6f, 18f))
    drawOval(Color(0xFFD7B58B), Offset(b.left + 8f, b.top + 7f), Size(b.width - 16f, 11f))
    repeat(4) { i -> drawLine(Color(0xFF6B4C32).copy(alpha = .44f), Offset(b.left + 11f + i * 12f, b.top + 18f), Offset(b.left + 8f + i * 13f, b.bottom - 5f), 1.7f) }
}

private fun DrawScope.drawDecor(b: WorldRectangle, color: Color) {
    val cx = b.center.x
    drawOval(Color.Black.copy(alpha = .14f), Offset(cx - 24f, b.bottom - 5f), Size(52f, 16f))
    val vase = Path().apply {
        moveTo(cx - 10f, b.top + 4f); lineTo(cx + 10f, b.top + 4f)
        cubicTo(cx + 8f, b.top + 18f, cx + 23f, b.top + 22f, cx + 20f, b.bottom - 8f)
        cubicTo(cx + 15f, b.bottom + 1f, cx - 15f, b.bottom + 1f, cx - 20f, b.bottom - 8f)
        cubicTo(cx - 23f, b.top + 22f, cx - 8f, b.top + 18f, cx - 10f, b.top + 4f); close()
    }
    drawPath(vase, Brush.linearGradient(listOf(color.light(.24f), color, color.dark(.72f))))
    drawLine(Color.White.copy(alpha = .30f), Offset(cx - 8f, b.top + 11f), Offset(cx - 12f, b.bottom - 14f), 2f, StrokeCap.Round)
}

private fun WorldVector.toOffset() = Offset(x, y)

private fun furnitureColor(key: String): Color = when (key) {
    "sage", "olive", "leaf", "cactus" -> Color(0xFF758D7B)
    "sky", "navy" -> Color(0xFF687F88)
    "rose" -> Color(0xFF9B7C78)
    "charcoal" -> Color(0xFF4A514E)
    "latte", "rattan", "wood", "walnut" -> Color(0xFF9B7655)
    "glass" -> Color(0xFF8BB0AA)
    "warm" -> Color(0xFFC4975D)
    "white", "cream" -> Color(0xFFD9D2C7)
    else -> Color(0xFFA9A198)
}

private fun Color.dark(factor: Float) = copy(red = red * factor, green = green * factor, blue = blue * factor)

private fun Color.light(amount: Float = .18f) = copy(
    red = (red + (1f - red) * amount).coerceAtMost(1f),
    green = (green + (1f - green) * amount).coerceAtMost(1f),
    blue = (blue + (1f - blue) * amount).coerceAtMost(1f),
)
