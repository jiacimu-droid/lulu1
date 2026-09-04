package com.jiacimu.lulu.games

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal const val APOCALYPSE_WORLD_WIDTH = 1_900f
internal const val APOCALYPSE_WORLD_HEIGHT = 1_200f
internal val APOCALYPSE_WORLD_BOUNDS = WorldRectangle(46f, 46f, 1_854f, 1_154f)

internal enum class ApocalypseTerrain { Forest, Road, City, Facility, Waterside }

internal enum class ApocalypseRuinKind {
    Building,
    Wreck,
    Barricade,
    Tree,
    Cache,
    Exit,
    Anomaly,
}

internal data class ApocalypseRuinObject(
    val id: String,
    val label: String,
    val kind: ApocalypseRuinKind,
    val bounds: WorldRectangle,
    val action: String,
    val blocksMovement: Boolean = true,
) {
    val obstacle: WorldObstacle? = if (blocksMovement) WorldObstacle(id, bounds, label, action) else null
}

internal data class ApocalypseExplorationMap(
    val terrain: ApocalypseTerrain,
    val location: String,
    val objects: List<ApocalypseRuinObject>,
    val playerStart: WorldVector,
    val threatStart: WorldVector,
)

internal fun buildApocalypseExplorationMap(location: String, tension: Int): ApocalypseExplorationMap {
    val terrain = when {
        listOf("水库", "河", "湖", "码头", "岸").any(location::contains) -> ApocalypseTerrain.Waterside
        listOf("林", "山", "野", "谷").any(location::contains) -> ApocalypseTerrain.Forest
        listOf("医院", "研究", "基地", "站", "厂", "库").any(location::contains) -> ApocalypseTerrain.Facility
        listOf("城", "市", "街", "商场", "小区").any(location::contains) -> ApocalypseTerrain.City
        else -> ApocalypseTerrain.Road
    }
    val suffix = (location.hashCode() and Int.MAX_VALUE).toString(36)
    val sceneName = location.ifBlank { "未知区域" }
    val objects = mutableListOf<ApocalypseRuinObject>()

    objects += ApocalypseRuinObject(
        "$suffix-building-a",
        if (terrain == ApocalypseTerrain.Forest) "废弃护林站" else "封死的建筑",
        ApocalypseRuinKind.Building,
        WorldRectangle(85f, 90f, 545f, 355f),
        "我贴近建筑外墙，寻找能进入的缺口和仍可利用的房间。",
    )
    objects += ApocalypseRuinObject(
        "$suffix-building-b",
        if (terrain == ApocalypseTerrain.Facility) "隔离实验楼" else "坍塌街区",
        ApocalypseRuinKind.Building,
        WorldRectangle(1_360f, 120f, 1_820f, 395f),
        "我观察${sceneName}里这片建筑的出入口，先确认里面有没有活动迹象。",
    )
    objects += ApocalypseRuinObject(
        "$suffix-wreck",
        "熄火的越野车",
        ApocalypseRuinKind.Wreck,
        WorldRectangle(665f, 405f, 955f, 535f),
        "我借掩体靠近熄火的越野车，检查车厢、油量和后备箱。",
    )
    objects += ApocalypseRuinObject(
        "$suffix-barricade",
        "临时路障",
        ApocalypseRuinKind.Barricade,
        WorldRectangle(1_120f, 600f, 1_480f, 688f),
        "我检查临时路障上的痕迹，判断它是谁留下的、多久前还有人经过。",
    )
    val treeCount = if (terrain == ApocalypseTerrain.Forest) 9 else 4
    repeat(treeCount) { index ->
        val seed = (location.hashCode().toLong() * 43L + index * 719L) and Long.MAX_VALUE
        val x = 130f + (seed % 1_610L).toFloat()
        val y = 380f + ((seed / 31L) % 680L).toFloat()
        val bounds = WorldRectangle(x - 38f, y - 34f, x + 38f, y + 34f)
        if (
            objects.none { overlap(bounds.expanded(22f), it.bounds) } &&
            WorldVector(x, y).distanceTo(WorldVector(940f, 910f)) > 120f
        ) {
            objects += ApocalypseRuinObject(
                "$suffix-tree-$index",
                if (terrain == ApocalypseTerrain.City) "倾倒的路灯" else "枯死的树",
                ApocalypseRuinKind.Tree,
                bounds,
                "我借着遮挡停下，倾听${sceneName}周围的动静。",
            )
        }
    }
    objects += ApocalypseRuinObject(
        "$suffix-cache",
        "未开启的物资箱",
        ApocalypseRuinKind.Cache,
        WorldRectangle(315f, 820f, 435f, 915f),
        "我保持警戒靠近物资箱，先排除陷阱，再检查里面有什么。",
    )
    objects += ApocalypseRuinObject(
        "$suffix-anomaly",
        "不稳定的空间回响",
        ApocalypseRuinKind.Anomaly,
        WorldRectangle(1_500f, 820f, 1_630f, 945f),
        "我放慢呼吸，释放空间感知去触碰那片异常回响，尝试判断它通向哪里。",
        blocksMovement = false,
    )
    objects += ApocalypseRuinObject(
        "$suffix-exit",
        if (terrain == ApocalypseTerrain.Waterside) "通向大坝的检修道" else "通向区域深处的路线",
        ApocalypseRuinKind.Exit,
        WorldRectangle(840f, 1_045f, 1_060f, 1_145f),
        "我确认队伍状态和退路，准备沿着这条路线继续深入${sceneName}。",
        blocksMovement = false,
    )
    return ApocalypseExplorationMap(
        terrain = terrain,
        location = sceneName,
        objects = objects,
        playerStart = WorldVector(940f, 910f),
        threatStart = WorldVector(1_035f + tension * 16f, 330f),
    )
}

private fun overlap(a: WorldRectangle, b: WorldRectangle): Boolean =
    a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

internal fun DrawScope.drawApocalypseExplorationWorld(
    map: ApocalypseExplorationMap,
    phase: Float,
    tension: Int,
    exploredIds: Set<String>,
    threat: WorldVector,
) {
    val palette = apocalypsePalette(map.terrain)
    drawRect(palette.ground, size = Size(APOCALYPSE_WORLD_WIDTH, APOCALYPSE_WORLD_HEIGHT))
    drawRect(
        Brush.radialGradient(
            listOf(Color(0xFFD9E4CF).copy(alpha = .13f), Color.Transparent),
            center = Offset(850f, 60f),
            radius = 1_100f,
        ),
        size = Size(APOCALYPSE_WORLD_WIDTH, APOCALYPSE_WORLD_HEIGHT),
    )

    when (map.terrain) {
        ApocalypseTerrain.Road, ApocalypseTerrain.City, ApocalypseTerrain.Facility -> {
            val road = Path().apply {
                moveTo(635f, -30f)
                lineTo(1_255f, -30f)
                lineTo(1_145f, 1_230f)
                lineTo(745f, 1_230f)
                close()
            }
            drawPath(road, palette.road)
            drawPath(road, Color.White.copy(alpha = .055f), style = Stroke(7f))
            repeat(10) { index ->
                val y = 45f + index * 132f
                drawRoundRect(
                    Color(0xFFD5D3C2).copy(alpha = .23f),
                    topLeft = Offset(925f - index * 5f, y),
                    size = Size(22f, 67f),
                    cornerRadius = CornerRadius(5f),
                )
            }
            repeat(17) { index ->
                val x = 710f + (index * 83 % 480)
                val y = 80f + (index * 127 % 1_040)
                val crack = Path().apply {
                    moveTo(x, y)
                    lineTo(x + 22f, y + 12f)
                    lineTo(x + 8f, y + 31f)
                    lineTo(x + 38f, y + 45f)
                }
                drawPath(crack, Color(0xFF111A18).copy(alpha = .33f), style = Stroke(3f, cap = StrokeCap.Round))
            }
        }
        ApocalypseTerrain.Waterside -> {
            drawRect(
                Brush.linearGradient(
                    listOf(Color(0xFF253B3B), Color(0xFF0B2429), Color(0xFF071D24)),
                    start = Offset(1_040f, 0f),
                    end = Offset(1_900f, 1_200f),
                ),
                topLeft = Offset(1_220f, 0f),
                size = Size(680f, 1_200f),
            )
            repeat(22) { index ->
                val y = (index * 59f + phase * 42f) % 1_260f
                drawLine(Color(0xFFB8E0DD).copy(alpha = .08f), Offset(1_245f, y), Offset(1_860f, y + 32f), 3f, StrokeCap.Round)
            }
        }
        ApocalypseTerrain.Forest -> Unit
    }

    if (map.terrain != ApocalypseTerrain.Forest) {
        repeat(12) { index ->
            val seed = (map.location.hashCode().toLong() * 113L + index * 811L) and Long.MAX_VALUE
            val x = 100f + (seed % 1_680L)
            val y = 240f + ((seed / 37L) % 820L)
            val width = 54f + (seed % 120L)
            drawOval(
                Brush.radialGradient(
                    listOf(Color(0xFF9BC3BC).copy(alpha = .13f), Color(0xFF081311).copy(alpha = .20f)),
                    center = Offset(x + width * .35f, y + 7f),
                    radius = width,
                ),
                topLeft = Offset(x, y),
                size = Size(width, 22f + index % 4 * 5f),
            )
            drawLine(Color.White.copy(alpha = .07f), Offset(x + 8f, y + 5f), Offset(x + width * .64f, y + 5f), 2f, StrokeCap.Round)
        }
    }

    repeat(if (map.terrain == ApocalypseTerrain.Forest) 90 else 48) { index ->
        val seed = (index * 1_103_515_245L + map.location.hashCode() * 97L) and Long.MAX_VALUE
        val x = (seed % 1_900L).toFloat()
        val y = ((seed / 71L) % 1_200L).toFloat()
        val length = 13f + (seed % 29L)
        drawLine(palette.debris.copy(alpha = .12f + (index % 4) * .035f), Offset(x, y), Offset(x + length, y + (index % 3 - 1) * 9f), 2.5f, StrokeCap.Round)
    }

    map.objects.sortedBy { it.bounds.bottom }.forEach { objectInWorld ->
        drawApocalypseObject(objectInWorld, palette, objectInWorld.id in exploredIds, phase)
    }

    drawCircle(
        Brush.radialGradient(
            listOf(Color(0xFFFF514F).copy(alpha = .24f + tension * .012f), Color.Transparent),
            center = Offset(threat.x, threat.y),
            radius = 145f,
        ),
        145f,
        Offset(threat.x, threat.y),
    )
    drawOval(Color.Black.copy(alpha = .48f), Offset(threat.x - 34f, threat.y + 31f), Size(68f, 26f))
    drawLine(Color(0xFF120B0B), Offset(threat.x - 13f, threat.y + 20f), Offset(threat.x - 22f, threat.y + 59f), 13f, StrokeCap.Round)
    drawLine(Color(0xFF120B0B), Offset(threat.x + 13f, threat.y + 20f), Offset(threat.x + 24f, threat.y + 59f), 13f, StrokeCap.Round)
    drawLine(Color(0xFF160C0C), Offset(threat.x - 21f, threat.y - 2f), Offset(threat.x - 45f, threat.y + 30f), 10f, StrokeCap.Round)
    drawLine(Color(0xFF160C0C), Offset(threat.x + 21f, threat.y - 2f), Offset(threat.x + 46f, threat.y + 27f), 10f, StrokeCap.Round)
    drawCircle(Color(0xFF170E0E), 31f, Offset(threat.x, threat.y))
    drawCircle(Color(0xFF100808), 19f, Offset(threat.x, threat.y - 31f))
    drawCircle(Color(0xFFF65B55), 5f, Offset(threat.x - 10f, threat.y - 5f))
    drawCircle(Color(0xFFF65B55), 5f, Offset(threat.x + 10f, threat.y - 5f))

    drawRect(
        Brush.verticalGradient(
            listOf(Color.Black.copy(alpha = .16f), Color.Transparent, Color.Black.copy(alpha = .20f)),
            startY = 0f,
            endY = APOCALYPSE_WORLD_HEIGHT,
        ),
        size = Size(APOCALYPSE_WORLD_WIDTH, APOCALYPSE_WORLD_HEIGHT),
    )
}

private data class ApocalypseWorldPalette(
    val ground: Brush,
    val road: Color,
    val concrete: Color,
    val metal: Color,
    val vegetation: Color,
    val debris: Color,
)

private fun apocalypsePalette(terrain: ApocalypseTerrain): ApocalypseWorldPalette = when (terrain) {
    ApocalypseTerrain.Forest -> ApocalypseWorldPalette(
        Brush.linearGradient(listOf(Color(0xFF25332D), Color(0xFF101F1B), Color(0xFF17241F))),
        Color(0xFF343A34), Color(0xFF53605A), Color(0xFF45524E), Color(0xFF344B3B), Color(0xFF879187),
    )
    ApocalypseTerrain.Waterside -> ApocalypseWorldPalette(
        Brush.linearGradient(listOf(Color(0xFF37413C), Color(0xFF1B2C2A), Color(0xFF102321))),
        Color(0xFF444B45), Color(0xFF5D6862), Color(0xFF455955), Color(0xFF375344), Color(0xFF91A49B),
    )
    ApocalypseTerrain.City -> ApocalypseWorldPalette(
        Brush.linearGradient(listOf(Color(0xFF42443F), Color(0xFF282E2B), Color(0xFF1B2522))),
        Color(0xFF353936), Color(0xFF676B65), Color(0xFF4D5855), Color(0xFF405247), Color(0xFF9A9B91),
    )
    ApocalypseTerrain.Facility -> ApocalypseWorldPalette(
        Brush.linearGradient(listOf(Color(0xFF3E4845), Color(0xFF1C2B28), Color(0xFF17221F))),
        Color(0xFF343D3A), Color(0xFF6A7470), Color(0xFF50645F), Color(0xFF42584D), Color(0xFF9AA9A4),
    )
    ApocalypseTerrain.Road -> ApocalypseWorldPalette(
        Brush.linearGradient(listOf(Color(0xFF45483F), Color(0xFF2D362F), Color(0xFF18251F))),
        Color(0xFF373A36), Color(0xFF686B62), Color(0xFF515B55), Color(0xFF48594A), Color(0xFFA09F90),
    )
}

private fun DrawScope.drawApocalypseObject(
    item: ApocalypseRuinObject,
    palette: ApocalypseWorldPalette,
    explored: Boolean,
    phase: Float,
) {
    val b = item.bounds
    when (item.kind) {
        ApocalypseRuinKind.Building -> {
            drawRoundRect(Color.Black.copy(alpha = .36f), Offset(b.left + 18f, b.top + 22f), Size(b.width, b.height), CornerRadius(16f))
            drawRoundRect(palette.concrete.copy(alpha = .98f), Offset(b.left, b.top + 20f), Size(b.width, b.height), CornerRadius(14f))
            drawRoundRect(
                Brush.linearGradient(listOf(palette.concrete.copy(alpha = .95f), Color(0xFF343D39))),
                Offset(b.left, b.top),
                Size(b.width, b.height - 22f),
                CornerRadius(14f),
            )
            repeat(4) { index ->
                val x = b.left + 46f + index * (b.width - 92f) / 3f
                drawRoundRect(Color(0xFF0A1715), Offset(x - 26f, b.top + 48f), Size(52f, 66f), CornerRadius(5f))
                drawLine(Color(0xFF81928C).copy(alpha = .42f), Offset(x - 21f, b.top + 58f), Offset(x + 19f, b.top + 101f), 3f)
            }
            repeat(7) { index ->
                val x = b.left + 24f + (index * 61f) % (b.width - 48f)
                val y = b.top + 22f + (index * 37f) % (b.height - 44f)
                drawLine(Color(0xFF1A2622).copy(alpha = .54f), Offset(x, y), Offset(x + 34f, y + 19f), 3f)
            }
            drawRect(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = .14f), Color.Transparent, Color.Black.copy(alpha = .24f))),
                Offset(b.left, b.top),
                Size(b.width, b.height),
            )
            drawRoundRect(Color(0xFF201C18).copy(alpha = .88f), Offset(b.center.x - 72f, b.top + 132f), Size(144f, 46f), CornerRadius(5f))
            drawLine(Color(0xFFD7C08B).copy(alpha = .42f), Offset(b.center.x - 50f, b.top + 155f), Offset(b.center.x + 50f, b.top + 155f), 5f, StrokeCap.Round)
        }
        ApocalypseRuinKind.Wreck -> {
            drawOval(Color.Black.copy(alpha = .42f), Offset(b.left + 8f, b.top + 30f), Size(b.width, b.height))
            drawRoundRect(palette.metal, Offset(b.left, b.top + 10f), Size(b.width, b.height - 18f), CornerRadius(35f))
            drawRoundRect(Color(0xFF182320), Offset(b.left + 58f, b.top + 20f), Size(b.width - 116f, b.height * .42f), CornerRadius(18f))
            drawCircle(Color(0xFF111615), 26f, Offset(b.left + 62f, b.bottom - 7f))
            drawCircle(Color(0xFF111615), 26f, Offset(b.right - 62f, b.bottom - 7f))
            drawLine(Color(0xFFB89572).copy(alpha = .70f), Offset(b.left + 14f, b.top + 30f), Offset(b.right - 18f, b.bottom - 30f), 7f)
        }
        ApocalypseRuinKind.Barricade -> {
            repeat(4) { index ->
                val x = b.left + index * b.width / 4f
                drawRoundRect(palette.metal, Offset(x, b.top + if (index % 2 == 0) 4f else 20f), Size(b.width / 4f + 14f, 44f), CornerRadius(7f))
                drawLine(Color(0xFFD0A45F).copy(alpha = .60f), Offset(x + 12f, b.top + 12f), Offset(x + b.width / 4f, b.top + 49f), 8f)
            }
        }
        ApocalypseRuinKind.Tree -> {
            drawOval(Color.Black.copy(alpha = .28f), Offset(b.left - 18f, b.top + 25f), Size(b.width + 36f, b.height * .75f))
            drawCircle(palette.vegetation.copy(alpha = .92f), b.width * .52f, Offset(b.center.x, b.center.y - 12f))
            drawCircle(Color(0xFF1B2A22).copy(alpha = .65f), b.width * .30f, Offset(b.center.x + 17f, b.center.y - 24f))
            drawLine(Color(0xFF615240), Offset(b.center.x, b.center.y), Offset(b.center.x + 4f, b.bottom + 22f), 13f, StrokeCap.Round)
        }
        ApocalypseRuinKind.Cache -> {
            val glow = if (explored) Color(0xFF94B3A8) else Color(0xFFE7C76F)
            drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = .25f + phase * .08f), Color.Transparent)), 102f, b.center.toOffset())
            drawRoundRect(Color.Black.copy(alpha = .42f), Offset(b.left + 9f, b.top + 13f), Size(b.width, b.height), CornerRadius(10f))
            drawRoundRect(if (explored) palette.metal else Color(0xFF86754A), Offset(b.left, b.top), Size(b.width, b.height), CornerRadius(9f))
            drawLine(Color(0xFFD9C58E), Offset(b.left + 7f, b.center.y), Offset(b.right - 7f, b.center.y), 7f)
            drawRoundRect(Color(0xFF1E2926), Offset(b.center.x - 13f, b.center.y - 15f), Size(26f, 30f), CornerRadius(4f))
        }
        ApocalypseRuinKind.Exit -> {
            drawRoundRect(Color(0xFF9AB4A9).copy(alpha = .18f), Offset(b.left, b.top), Size(b.width, b.height), CornerRadius(45f))
            repeat(3) { index ->
                val y = b.top + 23f + index * 23f
                drawLine(Color(0xFFCBDED6).copy(alpha = .65f), Offset(b.left + 35f, y), Offset(b.right - 34f, y), 6f, StrokeCap.Round)
            }
            drawLine(Color(0xFFE9D38F), Offset(b.center.x, b.top - 20f), Offset(b.center.x, b.bottom + 13f), 4f, StrokeCap.Round)
        }
        ApocalypseRuinKind.Anomaly -> {
            repeat(4) { ring ->
                drawCircle(
                    Color(0xFF8EDFCB).copy(alpha = .16f + phase * .08f),
                    34f + ring * 19f,
                    b.center.toOffset(),
                    style = Stroke(4f),
                )
            }
            repeat(9) { index ->
                val angle = index * .71f + phase * 1.8f
                val radius = 45f + index * 5f
                drawCircle(Color(0xFFB8FCE9).copy(alpha = .62f), 4f, Offset(b.center.x + cos(angle) * radius, b.center.y + sin(angle) * radius))
            }
        }
    }
}

private fun WorldVector.toOffset() = Offset(x, y)
