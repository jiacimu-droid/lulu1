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
import androidx.compose.ui.graphics.drawscope.rotate
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
        // Leave a traversable corridor beside the wreck for the 36px body
        // and the follower route grid; the old 20px gap sealed the return area.
        DeepSeaReef("spire", DeepSeaReefKind.Rock, WorldRectangle(995f, 1_040f, 1_150f, 1_295f)),
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
    visited: Set<String> = emptySet(),
    revealedRelics: Set<String> = emptySet(),
    salvagedRelics: Set<String> = emptySet(),
    observedCreatures: Set<String> = emptySet(),
    playerHeading: Float = 0f,
    companionHeading: Float = 0f,
) {
    drawRect(
        Brush.verticalGradient(
            listOf(Color(0xFF163F55), Color(0xFF082C42), Color(0xFF071E32), Color(0xFF061522)),
            startY = 0f,
            endY = DEEP_SEA_WORLD_HEIGHT,
        ),
        size = Size(DEEP_SEA_WORLD_WIDTH, DEEP_SEA_WORLD_HEIGHT),
    )

    // Distant seabed terraces and haze establish depth without adding collidable geometry.
    repeat(7) { layer ->
        val base = 220f + layer * 182f
        val silhouette = Path().apply {
            moveTo(0f, base + 90f)
            repeat(17) { step ->
                val x = step * 145f
                val y = base + sin(step * .9f + layer * 1.8f) * (34f + layer * 2f)
                lineTo(x, y)
            }
        }
        drawPath(silhouette, Color(0xFF7FD6C9).copy(alpha = .035f + (layer % 2) * .012f),
            style = Stroke(3f))
    }
    // Water currents are visible, and the same zones change actual piloting speed.
    deepSeaCurrents.forEachIndexed { index, current ->
        repeat(5) { ring ->
            val radius = 40f + ring * 37f
            val alpha = .06f + (4 - ring) * .013f
            drawArc(
                color = if (current.speedFactor > 1f) Color(0xFF99F6DB).copy(alpha = alpha)
                        else Color(0xFF9AB5F6).copy(alpha = alpha),
                startAngle = phase * 360f * (if (index == 0) -1 else 1) + ring * 29f,
                sweepAngle = 165f,
                useCenter = false,
                topLeft = Offset(current.center.x - radius, current.center.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = 6f + ring),
            )
        }
    }

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

    repeat(68) { index ->
        val seed = (index * 1_103_515_245L + 97L) and Long.MAX_VALUE
        val baseX = (seed % 2_150L).toFloat()
        val baseY = ((seed / 53L) % 1_340L).toFloat()
        val x = (baseX + phase * (24f + index % 6 * 7f)) % DEEP_SEA_WORLD_WIDTH
        val y = (baseY - phase * (60f + index % 7 * 18f) + DEEP_SEA_WORLD_HEIGHT) % DEEP_SEA_WORLD_HEIGHT
        drawCircle(Color(0xFFB4F3E9).copy(alpha = .16f + (index % 3) * .05f), 3f + index % 5, Offset(x, y), style = Stroke(1.5f))
    }

    repeat(12) { school ->
        val baseX = ((school * 317f + phase * 180f * (if (school % 2 == 0) 1 else -1)) % 2_400f) - 120f
        val baseY = 180f + school * 118f
        repeat(5) { fish ->
            drawFish(Offset(baseX + fish * 34f, baseY + sin(fish * 1.7f + phase * 6.28f) * 17f), 17f + fish % 3 * 3f, school % 2 == 0)
        }
    }

    map.reefs.sortedBy { it.bounds.bottom }.forEach { drawDeepSeaReef(it, phase) }
    deepSeaSites.forEach { site ->
        val center = site.position.toOffset()
        when (site.id) {
            "hidden-cave" -> {
                drawOval(Color(0xFF3D676C).copy(alpha = .75f),
                    Offset(center.x - 58f, center.y - 37f), Size(116f, 76f))
                drawOval(Color(0xFF031A2C),
                    Offset(center.x - 42f, center.y - 23f), Size(84f, 54f))
                drawOval(Color(0xFF91DDD4).copy(alpha = .35f),
                    Offset(center.x - 42f, center.y - 23f), Size(84f, 54f), style = Stroke(4f))
            }
            "wreck-log" -> {
                drawRoundRect(Color(0xFFB08F6D),
                    Offset(center.x - 25f, center.y - 17f), Size(50f, 34f), CornerRadius(6f))
                drawLine(Color(0xFFE4D5AE), Offset(center.x - 16f, center.y - 7f),
                    Offset(center.x + 15f, center.y - 7f), strokeWidth = 3f)
            }
            "kelp-garden" -> repeat(5) { plant ->
                val px = center.x - 32f + plant * 16f
                drawLine(Color(0xFF70C7A7).copy(alpha = .56f),
                    Offset(px, center.y + 38f),
                    Offset(px + sin(phase * 6f + plant) * 14f, center.y - 33f - plant % 3 * 9f),
                    strokeWidth = 5f, cap = StrokeCap.Round)
            }
            "whale-song" -> repeat(3) { ring ->
                drawCircle(Color(0xFF8BD9DE).copy(alpha = .2f),
                    30f + ring * 18f + sin(phase * 6.28f) * 5f, center, style = Stroke(3f))
            }
        }
        val seen = site.id in visited
        val glow = if (seen) Color(0xFF6F9C9A) else Color(0xFFBCE9DA)
        drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = if (seen) .1f else .30f), Color.Transparent),
            center = center, radius = 105f), 105f, center)
        drawCircle(glow.copy(alpha = if (seen) .35f else .75f), 14f + sin(phase * 6.28f) * 3f,
            center, style = Stroke(3f))
        drawCircle(glow.copy(alpha = .65f), 4f, center)
    }
    deepSeaCreatures.forEach { creature ->
        drawDeepSeaCreature(creature, phase, creature.id in observedCreatures)
    }
    deepSeaRelics.filter { it.id !in salvagedRelics &&
        (it.id in revealedRelics || player.distanceTo(it.position) < 185f) }.forEach { relic ->
        drawDeepSeaRelic(relic, phase)
    }
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

    companion?.let { drawDeepSeaSubmersible(it, phase, companion = true, heading = companionHeading) }
    drawDeepSeaSubmersible(player, phase, companion = false, heading = playerHeading)

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

private fun DrawScope.drawDeepSeaSubmersible(position: WorldVector, phase: Float, companion: Boolean, heading: Float) {
    val center = position.toOffset()
    val paint = if (companion) Color(0xFFBF8E89) else Color(0xFFE8BF79)
    rotate(degrees = heading, pivot = center) {
        val lamp = Path().apply {
            moveTo(position.x + 37f, position.y - 12f)
            lineTo(position.x + 295f, position.y - 94f)
            lineTo(position.x + 295f, position.y + 94f)
            lineTo(position.x + 37f, position.y + 12f)
            close()
        }
        drawPath(lamp, Brush.horizontalGradient(
            listOf(Color(0xFFB4FFF1).copy(alpha = .18f), Color.Transparent),
            startX = position.x + 37f, endX = position.x + 295f))
        // Rear fin and spinning propeller remain distinct from the glass cockpit.
        drawOval(Color.Black.copy(alpha = .23f), Offset(position.x - 50f, position.y + 29f), Size(105f, 19f))
        drawRoundRect(Color(0xFF244354), Offset(position.x - 57f, position.y - 13f),
            Size(20f, 28f), CornerRadius(7f))
        drawLine(Color(0xFF96D9C7).copy(alpha = .8f), Offset(position.x - 63f, position.y - 19f),
            Offset(position.x - 63f, position.y + 19f), 5f, StrokeCap.Round)
        drawRoundRect(paint, Offset(position.x - 45f, position.y - 27f), Size(99f, 54f), CornerRadius(26f))
        drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .22f), Color.Transparent)),
            Offset(position.x - 40f, position.y - 24f), Size(87f, 24f), CornerRadius(20f))
        drawRoundRect(Color(0xFF2C5866), Offset(position.x - 17f, position.y - 36f),
            Size(40f, 12f), CornerRadius(5f))
        drawCircle(Color(0xFF7EBDC5), 23f, Offset(position.x + 13f, position.y - 1f))
        drawCircle(Color(0xFF1D5368), 18f, Offset(position.x + 13f, position.y - 1f))
        drawCircle(Brush.radialGradient(listOf(Color(0xFFD9FFF8).copy(alpha = .85f),
            Color(0xFF5FB7BD).copy(alpha = .50f))),
            12f, Offset(position.x + 8f, position.y - 7f))
        drawCircle(Color.White.copy(alpha = .78f), 4f, Offset(position.x + 5f, position.y - 11f))
        drawRoundRect(Color(0xFF33566B), Offset(position.x - 23f, position.y + 24f),
            Size(35f, 10f), CornerRadius(5f))
        drawCircle(Color(0xFFFFEEB4), 8f, Offset(position.x + 53f, position.y))
        // Continuous small trail reads as movement without fast particle strobing.
        repeat(3) { bubble ->
            val drift = (phase * 115f + bubble * 37f) % 110f
            drawCircle(Color(0xFFB2F9E8).copy(alpha = .30f - bubble * .065f),
                4f + bubble * 2f, Offset(position.x - 70f - drift, position.y - 15f - bubble * 10f),
                style = Stroke(2f))
        }
    }
}

private fun DrawScope.drawDeepSeaRelic(relic: DeepSeaRelic, phase: Float) {
    val p = relic.position.toOffset()
    val bob = sin(phase * 6.283f + relic.position.x) * 5f
    val center = Offset(p.x, p.y + bob)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFDAB0).copy(alpha = .43f), Color.Transparent),
        center = center, radius = 74f), 74f, center)
    // An illuminated salvage capsule reads differently from the mandatory blue crystals.
    drawRoundRect(Color(0xFF284D59), Offset(center.x - 25f, center.y - 15f), Size(50f, 30f), CornerRadius(12f))
    drawRoundRect(Brush.linearGradient(listOf(Color(0xFFFFECC2), Color(0xFFE2AA63), Color(0xFF9A6B4B))),
        Offset(center.x - 17f, center.y - 11f), Size(34f, 22f), CornerRadius(9f))
    drawCircle(Color.White.copy(alpha = .8f), 5f, Offset(center.x - 6f, center.y - 5f))
    drawCircle(Color(0xFFFFE0AC).copy(alpha = .65f), 29f + sin(phase * 6.28f) * 3f,
        center, style = Stroke(2.4f))
}

private fun DrawScope.drawDeepSeaCreature(creature: DeepSeaCreature, phase: Float, observed: Boolean) {
    val p = creature.position
    val bob = sin(phase * 6.283f + p.x * .02f) * 8f
    val c = Offset(p.x, p.y + bob)
    val glow = if (observed) .12f else .26f
    drawCircle(Brush.radialGradient(
        listOf(Color(0xFFBEFFF4).copy(alpha = glow), Color.Transparent), center = c, radius = 100f),
        100f, c)
    when (creature.id) {
        "creature-jelly" -> {
            drawOval(Brush.verticalGradient(listOf(Color(0xFFC9B9FF), Color(0xFF5BB5C6))),
                Offset(c.x - 36f, c.y - 37f), Size(72f, 48f))
            repeat(5) { tentacle ->
                val x = c.x - 26f + tentacle * 13f
                val drift = sin(phase * 6.28f + tentacle) * 11f
                drawLine(Color(0xFF8CE6EA).copy(alpha = .66f), Offset(x, c.y + 6f),
                    Offset(x + drift, c.y + 62f + tentacle % 2 * 9f),
                    3f, StrokeCap.Round)
            }
            drawCircle(Color.White.copy(alpha = .62f), 7f, Offset(c.x - 12f, c.y - 17f))
        }
        "creature-ray" -> {
            val wing = Path().apply {
                moveTo(c.x - 72f, c.y - 5f)
                quadraticTo(c.x - 35f, c.y - 62f, c.x, c.y - 19f)
                quadraticTo(c.x + 35f, c.y - 62f, c.x + 72f, c.y - 5f)
                quadraticTo(c.x + 43f, c.y + 36f, c.x, c.y + 15f)
                quadraticTo(c.x - 43f, c.y + 36f, c.x - 72f, c.y - 5f)
                close()
            }
            drawPath(wing, Brush.verticalGradient(listOf(Color(0xFF82C7DC), Color(0xFF24496C))))
            drawLine(Color(0xFF78D6D8), Offset(c.x, c.y + 12f), Offset(c.x + 7f, c.y + 66f), 5f)
            drawCircle(Color(0xFFD7FFF2), 4f, Offset(c.x - 8f, c.y - 12f))
            drawCircle(Color(0xFFD7FFF2), 4f, Offset(c.x + 8f, c.y - 12f))
        }
        "creature-turtle" -> {
            repeat(4) { fin ->
                val dx = if (fin % 2 == 0) -1f else 1f
                val dy = if (fin < 2) -1f else 1f
                drawOval(Color(0xFF629C84),
                    Offset(c.x + dx * 27f - 18f, c.y + dy * 22f - 11f), Size(36f, 22f))
            }
            drawOval(Color(0xFF729F78), Offset(c.x - 37f, c.y - 26f), Size(74f, 52f))
            drawOval(Color(0xFF3B6F65), Offset(c.x - 27f, c.y - 21f), Size(54f, 42f))
            drawCircle(Color(0xFF85BA9C), 15f, Offset(c.x + 42f, c.y - 1f))
            drawCircle(Color(0xFF072837), 3f, Offset(c.x + 49f, c.y - 7f))
            drawLine(Color(0xFFA8D6B8), Offset(c.x, c.y - 19f), Offset(c.x, c.y + 19f), 3f)
        }
        else -> {
            drawOval(Color(0xFFEEBF79), Offset(c.x - 20f, c.y - 34f), Size(40f, 61f))
            drawCircle(Color(0xFFFFDFA8), 20f, Offset(c.x + 9f, c.y - 32f))
            drawCircle(Color(0xFF102E3E), 3f, Offset(c.x + 16f, c.y - 35f))
            drawArc(Color(0xFFEDBA7C), phase * 160f, 260f, false,
                Offset(c.x - 15f, c.y + 14f), Size(45f, 38f), style = Stroke(5f))
        }
    }
    if (!observed) {
        drawCircle(Color(0xFFCDFEF2).copy(alpha = .48f + .2f * sin(phase * 6.28f)), 84f, c,
            style = Stroke(2.4f))
    }
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
