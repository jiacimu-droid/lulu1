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
import kotlin.math.cos
import kotlin.math.sin

internal const val DEEP_SEA_WORLD_WIDTH = 2_150f
internal const val DEEP_SEA_WORLD_HEIGHT = 1_340f
internal val DEEP_SEA_WORLD_BOUNDS = WorldRectangle(40f, 55f, 2_110f, 1_300f)

internal enum class DeepSeaReefKind { Rock, Coral, Arch, Wreck }

internal data class DeepSeaReef(
    val id: String,
    val kind: DeepSeaReefKind,
    val bounds: WorldRectangle,
) {
    val obstacle = WorldObstacle(id, bounds, kind.name)
}

internal data class DeepSeaEcho(
    val id: String,
    val position: WorldVector,
    val title: String,
)

internal data class DeepSeaMap(
    val reefs: List<DeepSeaReef>,
    val echoes: List<DeepSeaEcho>,
    val gate: WorldVector,
    val start: WorldVector,
)

internal fun buildDeepSeaMap(): DeepSeaMap = DeepSeaMap(
    reefs = listOf(
        DeepSeaReef("ridge-west", DeepSeaReefKind.Rock, WorldRectangle(80f, 160f, 430f, 480f)),
        DeepSeaReef("coral-west", DeepSeaReefKind.Coral, WorldRectangle(280f, 760f, 545f, 1_100f)),
        DeepSeaReef("arch-center", DeepSeaReefKind.Arch, WorldRectangle(780f, 330f, 1_145f, 620f)),
        DeepSeaReef("wreck", DeepSeaReefKind.Wreck, WorldRectangle(1_280f, 785f, 1_720f, 1_040f)),
        DeepSeaReef("ridge-east", DeepSeaReefKind.Rock, WorldRectangle(1_690f, 100f, 2_080f, 430f)),
        DeepSeaReef("coral-east", DeepSeaReefKind.Coral, WorldRectangle(1_775f, 925f, 2_060f, 1_245f)),
        DeepSeaReef("spire", DeepSeaReefKind.Rock, WorldRectangle(1_105f, 1_040f, 1_260f, 1_295f)),
    ),
    echoes = listOf(
        DeepSeaEcho("echo-aurora", WorldVector(555f, 260f), "极光回声"),
        DeepSeaEcho("echo-whale", WorldVector(650f, 900f), "鲸歌残响"),
        DeepSeaEcho("echo-moon", WorldVector(1_230f, 250f), "月海微光"),
        DeepSeaEcho("echo-city", WorldVector(1_540f, 620f), "沉城记忆"),
        DeepSeaEcho("echo-home", WorldVector(1_870f, 720f), "归航信标"),
    ),
    gate = WorldVector(1_640f, 1_180f),
    start = WorldVector(230f, 1_160f),
)

internal fun DrawScope.drawDeepSeaWorld(
    map: DeepSeaMap,
    phase: Float,
    collected: Set<String>,
    player: WorldVector,
    companion: WorldVector?,
) {
    drawRect(
        Brush.verticalGradient(
            listOf(Color(0xFF163F55), Color(0xFF082C42), Color(0xFF071E32), Color(0xFF061522)),
            startY = 0f,
            endY = DEEP_SEA_WORLD_HEIGHT,
        ),
        size = Size(DEEP_SEA_WORLD_WIDTH, DEEP_SEA_WORLD_HEIGHT),
    )

    repeat(9) { ray ->
        val topX = 120f + ray * 270f + sin(phase * 6.28f + ray) * 36f
        val light = Path().apply {
            moveTo(topX, 0f)
            lineTo(topX + 90f, 0f)
            lineTo(topX + 330f, DEEP_SEA_WORLD_HEIGHT)
            lineTo(topX + 35f, DEEP_SEA_WORLD_HEIGHT)
            close()
        }
        drawPath(
            light,
            Brush.verticalGradient(
                listOf(Color(0xFFB7FFF2).copy(alpha = .11f), Color(0xFF5CCEC7).copy(alpha = .025f), Color.Transparent),
                startY = 0f,
                endY = DEEP_SEA_WORLD_HEIGHT,
            ),
        )
    }

    repeat(45) { index ->
        val seed = (index * 1_103_515_245L + 97L) and Long.MAX_VALUE
        val baseX = (seed % 2_150L).toFloat()
        val baseY = ((seed / 53L) % 1_340L).toFloat()
        val x = (baseX + phase * (24f + index % 6 * 7f)) % DEEP_SEA_WORLD_WIDTH
        val y = (baseY - phase * (60f + index % 7 * 18f) + DEEP_SEA_WORLD_HEIGHT) % DEEP_SEA_WORLD_HEIGHT
        drawCircle(Color(0xFFB4F3E9).copy(alpha = .16f + (index % 3) * .05f), 3f + index % 5, Offset(x, y), style = Stroke(1.5f))
    }

    repeat(8) { school ->
        val baseX = ((school * 317f + phase * 180f * (if (school % 2 == 0) 1 else -1)) % 2_400f) - 120f
        val baseY = 180f + school * 118f
        repeat(5) { fish ->
            drawFish(Offset(baseX + fish * 34f, baseY + sin(fish * 1.7f + phase * 6.28f) * 17f), 17f + fish % 3 * 3f, school % 2 == 0)
        }
    }

    map.reefs.sortedBy { it.bounds.bottom }.forEach { drawDeepSeaReef(it, phase) }
    map.echoes.filterNot { it.id in collected }.forEachIndexed { index, echo ->
        drawEchoCrystal(echo.position, phase, index)
    }

    val gateReady = collected.size >= map.echoes.size
    repeat(5) { ring ->
        drawCircle(
            (if (gateReady) Color(0xFF9DFFE8) else Color(0xFF5A7E83)).copy(alpha = .10f + phase * .07f),
            38f + ring * 23f,
            map.gate.toOffset(),
            style = Stroke(5f),
        )
    }
    drawCircle(
        Brush.radialGradient(
            listOf(
                (if (gateReady) Color(0xFFD7FFF3) else Color(0xFF5B7E80)).copy(alpha = .75f),
                Color.Transparent,
            ),
            center = map.gate.toOffset(),
            radius = 100f,
        ),
        100f,
        map.gate.toOffset(),
    )

    companion?.let { drawDeepSeaDiver(it, phase, companion = true) }
    drawDeepSeaDiver(player, phase, companion = false)

    drawRect(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = .03f), Color.Transparent, Color.Black.copy(alpha = .20f)),
            startY = 0f,
            endY = DEEP_SEA_WORLD_HEIGHT,
        ),
        size = Size(DEEP_SEA_WORLD_WIDTH, DEEP_SEA_WORLD_HEIGHT),
    )
}

private fun DrawScope.drawDeepSeaReef(reef: DeepSeaReef, phase: Float) {
    val b = reef.bounds
    when (reef.kind) {
        DeepSeaReefKind.Rock -> {
            val rock = Path().apply {
                moveTo(b.left, b.bottom)
                lineTo(b.left + b.width * .10f, b.top + b.height * .43f)
                lineTo(b.left + b.width * .31f, b.top + b.height * .12f)
                lineTo(b.left + b.width * .54f, b.top)
                lineTo(b.left + b.width * .82f, b.top + b.height * .31f)
                lineTo(b.right, b.bottom)
                close()
            }
            drawPath(rock, Color.Black.copy(alpha = .32f))
            drawPath(rock, Brush.linearGradient(listOf(Color(0xFF31515A), Color(0xFF112E3B))))
            drawPath(rock, Color(0xFF8BC0BD).copy(alpha = .15f), style = Stroke(5f))
            repeat(7) { index ->
                val x = b.left + b.width * (.14f + (index * 17 % 72) / 100f)
                val y = b.top + b.height * (.16f + (index * 29 % 70) / 100f)
                drawCircle(Color(0xFF6CB5A2).copy(alpha = .22f), 9f + index % 4 * 4f, Offset(x, y))
            }
        }
        DeepSeaReefKind.Coral -> {
            drawOval(Color.Black.copy(alpha = .28f), Offset(b.left, b.bottom - 55f), Size(b.width, 75f))
            repeat(14) { index ->
                val x = b.left + 24f + (index * 53f) % (b.width - 48f)
                val height = 80f + (index * 37 % (b.height.toInt() - 70).coerceAtLeast(30))
                val coral = if (index % 3 == 0) Color(0xFFE78C83) else if (index % 3 == 1) Color(0xFFB285B7) else Color(0xFFE2C176)
                drawLine(coral.copy(alpha = .78f), Offset(x, b.bottom - 22f), Offset(x + sin(index + phase * 6f) * 18f, b.bottom - height), 12f, StrokeCap.Round)
                if (index % 2 == 0) {
                    drawLine(coral.copy(alpha = .70f), Offset(x, b.bottom - height * .57f), Offset(x - 28f, b.bottom - height * .77f), 8f, StrokeCap.Round)
                }
            }
        }
        DeepSeaReefKind.Arch -> {
            drawRoundRect(Color.Black.copy(alpha = .30f), Offset(b.left + 18f, b.top + 22f), Size(b.width, b.height), CornerRadius(80f))
            drawRoundRect(Brush.linearGradient(listOf(Color(0xFF38616A), Color(0xFF183846))), Offset(b.left, b.top), Size(b.width, b.height), CornerRadius(75f))
            drawRoundRect(Color(0xFF0A2B3E), Offset(b.left + 82f, b.top + 80f), Size(b.width - 164f, b.height - 70f), CornerRadius(65f))
            repeat(18) { index ->
                val angle = index / 17f * 3.14159f
                val x = b.center.x + cos(angle) * b.width * .40f
                val y = b.bottom - sin(angle) * b.height * .84f
                drawCircle(Color(0xFF8AC7B2).copy(alpha = .30f), 8f + index % 3 * 3f, Offset(x, y))
            }
        }
        DeepSeaReefKind.Wreck -> {
            val hull = Path().apply {
                moveTo(b.left, b.top + b.height * .38f)
                lineTo(b.right - 40f, b.top)
                lineTo(b.right, b.top + b.height * .68f)
                lineTo(b.left + 105f, b.bottom)
                close()
            }
            drawPath(hull, Color.Black.copy(alpha = .35f))
            drawPath(hull, Brush.linearGradient(listOf(Color(0xFF526569), Color(0xFF1B3841))))
            repeat(5) { index ->
                val x = b.left + 100f + index * 60f
                drawCircle(Color(0xFF071E2A), 22f, Offset(x, b.center.y))
                drawCircle(Color(0xFF7BA8A7).copy(alpha = .25f), 22f, Offset(x, b.center.y), style = Stroke(5f))
            }
            drawLine(Color(0xFFD8A567).copy(alpha = .52f), Offset(b.left + 45f, b.top + 65f), Offset(b.right - 55f, b.bottom - 48f), 9f)
        }
    }
}

private fun DrawScope.drawEchoCrystal(position: WorldVector, phase: Float, index: Int) {
    val pulse = .82f + sin(phase * 6.28f + index) * .12f
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFAFFFF0).copy(alpha = .44f), Color.Transparent)),
        92f * pulse,
        position.toOffset(),
    )
    val crystal = Path().apply {
        moveTo(position.x, position.y - 38f * pulse)
        lineTo(position.x + 24f * pulse, position.y)
        lineTo(position.x, position.y + 43f * pulse)
        lineTo(position.x - 24f * pulse, position.y)
        close()
    }
    drawPath(crystal, Brush.linearGradient(listOf(Color.White, Color(0xFF79E5D1), Color(0xFF5A9FC5))))
    drawPath(crystal, Color.White.copy(alpha = .75f), style = Stroke(3f))
}

private fun DrawScope.drawDeepSeaDiver(position: WorldVector, phase: Float, companion: Boolean) {
    val body = if (companion) Color(0xFFB88972) else Color(0xFFE1B866)
    val direction = if (companion) -1f else 1f
    drawOval(Color.Black.copy(alpha = .28f), Offset(position.x - 46f, position.y + 35f), Size(92f, 26f))
    drawRoundRect(body, Offset(position.x - 39f, position.y - 30f), Size(78f, 64f), CornerRadius(25f))
    drawCircle(Color(0xFFBDEAE3), 27f, Offset(position.x + direction * 34f, position.y - 3f))
    drawCircle(Color.White.copy(alpha = .55f), 27f, Offset(position.x + direction * 34f, position.y - 3f), style = Stroke(4f))
    drawRoundRect(Color(0xFF223A42), Offset(position.x - direction * 51f - 12f, position.y - 22f), Size(24f, 47f), CornerRadius(8f))
    drawLine(body.copy(alpha = .95f), Offset(position.x - 21f, position.y + 25f), Offset(position.x - 55f, position.y + 49f + sin(phase * 12f) * 8f), 11f, StrokeCap.Round)
    drawLine(body.copy(alpha = .95f), Offset(position.x + 6f, position.y + 28f), Offset(position.x - 22f, position.y + 61f - sin(phase * 12f) * 8f), 11f, StrokeCap.Round)
    drawCircle(Color(0xFFC7FFF2).copy(alpha = .35f), 8f, Offset(position.x - direction * 62f, position.y - 41f - phase * 20f))
    drawCircle(Color(0xFFC7FFF2).copy(alpha = .25f), 5f, Offset(position.x - direction * 75f, position.y - 70f - phase * 30f))
}

private fun DrawScope.drawFish(center: Offset, length: Float, right: Boolean) {
    val direction = if (right) 1f else -1f
    drawOval(Color(0xFFB7D8D1).copy(alpha = .24f), Offset(center.x - length, center.y - length * .28f), Size(length * 2f, length * .56f))
    val tail = Path().apply {
        moveTo(center.x - direction * length * .82f, center.y)
        lineTo(center.x - direction * length * 1.35f, center.y - length * .42f)
        lineTo(center.x - direction * length * 1.35f, center.y + length * .42f)
        close()
    }
    drawPath(tail, Color(0xFFB7D8D1).copy(alpha = .20f))
}

private fun WorldVector.toOffset() = Offset(x, y)
