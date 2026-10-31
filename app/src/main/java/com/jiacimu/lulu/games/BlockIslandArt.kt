package com.jiacimu.lulu.games

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.min

/**
 * Hand-authored procedural pixel-style art. No third-party sprites or copyrighted game assets.
 * Visuals are always derived from real BlockIslandState tiles and positions.
 * Paint only the visible viewport to keep this affordable on low-end Android GPUs.
 */
internal data class BlockIslandImpact(val x: Int, val y: Int, val action: String, val turn: Int)

internal object BlockIslandArt {
    private val grassA = Color(0xFF628E62)
    private val grassB = Color(0xFF709A6C)
    private val grassC = Color(0xFF779C69)
    private val water = Color(0xFF2F7796)
    private val waterLight = Color(0xFF5799AE)
    private val pine = Color(0xFF255D4B)
    private val leaf = Color(0xFF39815B)
    private val leafTop = Color(0xFF69A77A)
    private val outline = Color(0xFF25423F)
    private val dirt = Color(0xFF88694B)
    private val wood = Color(0xFFAD865B)
    private val cream = Color(0xFFF1D2A4)

    internal fun DrawScope.paint(
        island: BlockIslandState,
        selected: Pair<Int, Int>,
        companionPing: Pair<Int, Int>? = null,
        playerVisual: Offset? = null,
        companionVisuals: Map<String,Offset> = emptyMap(),
        camera: IslandFollowCamera,
        navigationStep: Pair<Int, Int>? = null,
        night: Boolean,
        phase: Float,
        impact: BlockIslandImpact? = null,
        impactProgress: Float = 1f,
    ) {
        val w = island.world
        val tile = camera.tileSize
        val left = camera.firstColumn
        val top = camera.firstRow
        val cols = camera.endColumnExclusive - left
        val rows = camera.endRowExclusive - top
        fun origin(x: Int, y: Int) = Offset(
            camera.originX + x * tile,
            camera.originY + y * tile,
        )
        fun center(x: Int, y: Int) = origin(x, y) + Offset(tile * .5f, tile * .5f)
        fun actorCenter(visual: Offset?,x: Int,y: Int): Offset = if(visual==null)center(x,y) else
            Offset(camera.originX+(visual.x+.5f)*tile,camera.originY+(visual.y+.5f)*tile)
        // A non-clamped camera may look past the island's edge; paint the surrounding sea.
        drawRect(Color(0xFF194C63))
        for (y in top until (top + rows).coerceAtMost(w.height)) {
            for (x in left until (left + cols).coerceAtMost(w.width)) {
                val cell = w.cell(x, y)
                val base = origin(x, y)
                drawGround(cell, base, tile, x, y, phase, w.seed,
                    w.cell(x, y - 1) == 8, w.cell(x - 1, y) == 8)
            }
        }
        // Scenery rendered in a separate pass so tree crowns can overhang adjacent tiles.
        for (y in top until (top + rows).coerceAtMost(w.height)) {
            for (x in left until (left + cols).coerceAtMost(w.width)) {
                val base = origin(x, y)
                when (w.cell(x, y)) {
                    1 -> drawTree(base, tile, x, y, phase)
                    2 -> drawBoulder(base, tile, x, y)
                    3 -> drawWall(base, tile)
                    7 -> drawSpawn(base, tile, phase)
                }
                when (island.structures[w.index(x, y)]) {
                    "bridge" -> drawBridge(base, tile)
                    "door" -> drawDoor(base, tile)
                    "workbench" -> drawWorkbench(base, tile)
                    "bed" -> drawBed(base, tile)
                    "campfire" -> drawFire(base, tile, phase, night)
                }
            }
        }
        if (night) {
            // Small translucent veil allows genuine color changes, rather than an opaque black map.
            drawRect(Color(0xFF0D234C).copy(alpha = .27f))
            // Warm pools of light come from actual built fires and the saved spawn point.
            for (y in top until (top + rows).coerceAtMost(w.height)) {
                for (x in left until (left + cols).coerceAtMost(w.width)) {
                    val index = w.index(x, y)
                    if (island.structures[index] == "campfire" || w.cell(x, y) == 7) {
                        val c = center(x, y)
                        drawCircle(Color(0xFFFFCF87).copy(alpha = .10f), tile * 1.40f, c)
                        drawCircle(Color(0xFFFFDB9A).copy(alpha = .13f), tile * .70f, c)
                    }
                }
            }
        }
        // Only immediately interactable targets glow; no invented objectives or world changes.
        for ((x, y) in listOf(w.x - 1 to w.y, w.x + 1 to w.y, w.x to w.y - 1, w.x to w.y + 1)) {
            if (!w.inBounds(x, y)) continue
            val actionable = when {
                w.mobs.any { it.hp > 0 && it.x == x && it.y == y } -> Color(0xFFFFA78C)
                w.friends.any { it.hp <= 0 && it.x == x && it.y == y } &&
                    island.fruit >= 2 -> Color(0xFFA9F4E8)
                w.cell(x, y) in 1..2 -> Color(0xFFA9EDAD)
                w.cell(x, y) == 8 && "rod" in island.tools -> Color(0xFF8EDCF2)
                else -> null
            } ?: continue
            drawCircle(actionable.copy(alpha = .36f + .12f * sin(phase * 6.28318f)),
                radius = tile * .42f, center = center(x, y),
                style = Stroke((tile * .065f).coerceAtLeast(1.5f)))
        }
        if (navigationStep != null) {
            val next = center(navigationStep.first, navigationStep.second)
            val from = actorCenter(playerVisual, w.x, w.y)
            drawLine(Color(0xB3C8FFE2), from, next,
                strokeWidth = (tile * .075f).coerceAtLeast(1.5f))
            drawCircle(Color(0xFFCDFFEA).copy(alpha = .6f + .2f * sin(phase * 6.28318f)),
                radius = tile * .18f, center = next)
        }
        w.mobs.filter { it.hp > 0 && it.x in left until left + cols && it.y in top until top + rows }
            .forEach { mob ->
                drawSlime(center(mob.x, mob.y), tile, mob.hp, phase, mob.id)
            }
        val companionOutfits = listOf(
            Color(0xFF71BBD0), Color(0xFFE1B783), Color(0xFFA8A4D5), Color(0xFFB7D38C),
        )
        val companionHair = listOf(
            Color(0xFF3F627F), Color(0xFF594D4B), Color(0xFF574D73), Color(0xFF6F5D46),
        )
        w.friends.filter { it.x in left until left + cols && it.y in top until top + rows }
            .forEach { friend ->
                val look = (friend.id.hashCode() and Int.MAX_VALUE) % companionOutfits.size
                if (friend.hp > 0) drawPerson(
                    actorCenter(companionVisuals[friend.id],friend.x,friend.y), tile,
                    companionOutfits[look], companionHair[look], phase + look * .13f, false,
                ) else drawFallen(actorCenter(companionVisuals[friend.id],friend.x,friend.y), tile)
            }
        if (w.x in left until left + cols && w.y in top until top + rows) {
            drawPerson(actorCenter(playerVisual,w.x,w.y), tile,
                Color(0xFFEEA87C), Color(0xFF715047), phase, true)
        }
        // A teammate's actual selected destination, distinct from the player's yellow selection.
        // Cyan pulses are painted from verified actions, never generated decorative targets.
        if (companionPing != null &&
            companionPing.first in left until left + cols &&
            companionPing.second in top until top + rows) {
            val c = center(companionPing.first, companionPing.second)
            val glow = tile * (.39f + .09f * sin(phase * 6.28318f))
            drawCircle(Color(0xFF72F0E4).copy(alpha = .17f), glow + tile * .20f, c)
            drawCircle(Color(0xFF9BFFF0), glow, c,
                style = Stroke((tile * .085f).coerceAtLeast(1.5f)))
            drawCircle(Color.White, tile * .09f, c)
        }
        if (selected.first in left until left + cols && selected.second in top until top + rows) {
            val c = center(selected.first, selected.second)
            val ring = tile * (.42f + .035f * sin(phase * 6.28318f))
            val color = Color(0xFFFFE19A)
            drawRect(color.copy(alpha = .8f), origin(selected.first, selected.second) +
                Offset(2f, 2f), Size(tile - 4f, tile - 4f), style = Stroke((tile * .055f).coerceAtLeast(1.5f)))
            // Four corner highlights, clear even at the small default zoom.
            listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).forEach { (dx, dy) ->
                drawRect(cream, topLeft = c + Offset(dx * ring, dy * ring) -
                    Offset(tile * .05f, tile * .05f), size = Size(tile * .10f, tile * .10f))
            }
        }
        // Sparse foreground details are deterministic decorations, never collectible rewards.
        // The same seed retains its scenery across sessions without storing a second world.
        for (y in top until (top + rows).coerceAtMost(w.height)) {
            for (x in left until (left + cols).coerceAtMost(w.width)) {
                if (w.cell(x,y) != 0 || w.index(x,y) in island.structures) continue
                val hash = ((x * 83492791) xor (y * 19349663) xor w.seed) and Int.MAX_VALUE
                val p = origin(x,y)
                if (hash % 29 == 0) {
                    // A patch of amber mushrooms with shaded caps and cream stems.
                    repeat(3) { i ->
                        val m = p + Offset(tile*(.24f+i*.18f),tile*(.64f-i%2*.16f))
                        drawRect(Color(0xFFE6D3AE),m,Size(tile*.04f,tile*.14f))
                        drawOval(Color(0xFF76443E),m-Offset(tile*.08f,tile*.07f),Size(tile*.20f,tile*.12f))
                        drawOval(Color(0xFFCF9770),m-Offset(tile*.07f,tile*.08f),Size(tile*.17f,tile*.08f))
                    }
                }
                if (!night && hash % 41 == 0) {
                    val b = p + Offset(tile*(.5f+.24f*sin(phase*6.28318f+x)),
                        tile*(.3f+.15f*cos(phase*6.28318f+y)))
                    val wing = tile*(.04f+.055f*kotlin.math.abs(sin(phase*18.84954f)))
                    drawOval(Color(0xFFFFE6B4),b-Offset(wing,tile*.06f),Size(wing,tile*.12f))
                    drawOval(Color(0xFFF3C69E),b,Size(wing,tile*.12f))
                }
                if (night && hash % 19 == 0) {
                    val b = p+Offset(tile*.5f,tile*(.2f+.13f*sin(phase*6.28318f+x)))
                    drawCircle(Brush.radialGradient(listOf(Color(0x6673E9BD),Color.Transparent),
                        b,tile*.28f),tile*.28f,b)
                    drawCircle(Color(0xFFD9F5B5),tile*.035f,b)
                }
            }
        }
        // Broad moving shadows read as clouds without introducing opaque foreground sprites.
        if (!night) repeat(3) { i ->
            val at = Offset(size.width*((phase*.25f+i*.42f)%1.4f-.2f),size.height*(.16f+i*.31f))
            drawOval(Color(0xFF1C4354).copy(alpha=.055f),at,Size(tile*7f,tile*2.8f))
        }
        if (impact != null && impactProgress < 1f &&
            impact.x in left until left + cols && impact.y in top until top + rows) {
            val point = center(impact.x, impact.y)
            val color = when (impact.action) {
                "gather", "plant" -> Color(0xFFB5ED8A)
                "fight" -> Color(0xFFFFA8A8)
                "eat", "rest", "revive" -> Color(0xFF90EDC8)
                else -> Color(0xFFFFE0A6)
            }
            val fade = (1f - impactProgress).coerceIn(0f, 1f)
            drawCircle(color.copy(alpha = fade * .8f), tile * (.22f + impactProgress * .85f),
                point, style = Stroke((tile * .075f * fade).coerceAtLeast(.8f)))
            repeat(6) { i ->
                val angle = i * 1.04719f + phase
                val radius = tile * (.18f + .77f * impactProgress)
                val particle = point + Offset(cos(angle) * radius, sin(angle) * radius)
                drawRect(color.copy(alpha = fade), particle,
                    Size(tile * .10f * fade, tile * .10f * fade))
            }
        }
    }

    private fun DrawScope.drawGround(
        kind: Int, p: Offset, t: Float, x: Int, y: Int, phase: Float, seed: Int,
        waterAbove: Boolean, waterLeft: Boolean,
    ) {
        val hash = ((x * 73856093) xor (y * 19349663) xor seed) and Int.MAX_VALUE
        val g = when (hash % 5) { 0 -> grassB; 1 -> grassC; else -> grassA }
        val bg = when (kind) {
            8 -> water
            4 -> dirt
            3 -> Color(0xFF8A714F)
            7 -> Color(0xFFC7AC77)
            else -> g
        }
        drawRect(bg, topLeft = p, size = Size(t + .6f, t + .6f))
        if (kind == 8) {
            if (!waterAbove) drawRect(waterLight, topLeft = p,
                size = Size(t, t * .11f))
            if (!waterLeft) drawRect(Color(0xFF8CC5BE).copy(alpha = .4f),
                topLeft = p, size = Size(t * .07f, t))
            val drift = ((phase * t * .28f + hash % 7) % (t * .26f))
            drawRect(waterLight.copy(alpha = .6f), p + Offset(t * .14f + drift, t * .36f),
                Size(t * .20f, t * .07f))
            drawRect(Color(0xFF8CBCC6).copy(alpha = .5f), p + Offset(t * .56f - drift, t * .72f),
                Size(t * .13f, t * .05f))
            if (hash % 13 == 0) {
                // A tiny school of fish is decorative, never an unearned fishing result.
                val fish = p + Offset(t * (.45f + .11f * sin(phase * 6.28318f)), t * .55f)
                drawRect(Color(0xFFB6D5CB), fish, Size(t * .16f, t * .07f))
                drawRect(Color(0xFF9BC5CF), fish + Offset(-t * .06f, -t * .04f),
                    Size(t * .07f, t * .14f))
            }
            return
        }
        if (kind == 4) {
            // Continuous timber planks with offset grain. Floor tile stays traversable.
            drawRect(Color(0xFFD0AD77), p + Offset(0f, t * .03f), Size(t, t * .13f))
            drawRect(Color(0xFF654F41).copy(alpha = .65f), p + Offset(0f, t * .48f), Size(t, t * .045f))
            drawRect(wood.copy(alpha = .7f), p + Offset(t * .12f, t * .13f), Size(t * .06f, t * .24f))
            drawRect(Color(0xFFE3BE88), p + Offset(t * .61f, t * .63f), Size(t * .25f, t * .05f))
            return
        }
        if (kind == 3 || kind == 7) return
        // Deterministic grasses/flowers: no random calls inside the rendering loop.
        if (hash % 4 == 0) {
            drawRect(Color(0xFF2E674C), p + Offset(t * .22f, t * .51f),
                Size(t * .05f, t * .18f))
            drawRect(Color(0xFFA3C57F), p + Offset(t * .29f, t * .44f),
                Size(t * .08f, t * .13f))
        }
        if (hash % 17 == 0) {
            drawRect(Color(0xFFFFD68F), p + Offset(t * .72f, t * .25f),
                Size(t * .10f, t * .11f))
            drawRect(Color(0xFFFAF4CC), p + Offset(t * .75f, t * .22f),
                Size(t * .04f, t * .05f))
        }
        if (hash % 9 == 0) {
            drawRect(Color(0xFF4B7B59), p + Offset(t * .61f, t * .84f),
                Size(t * .18f, t * .06f))
        }
    }

    private fun DrawScope.drawTree(p: Offset, t: Float, x: Int, y: Int, phase: Float) {
        val sway = sin(phase * 6.28318f + x * .58f + y * .73f) * t * .035f
        drawOval(Color.Black.copy(alpha = .24f),
            topLeft = p + Offset(t * .16f, t * .72f),
            size = Size(t * .76f, t * .24f))
        drawRect(Color(0xFF5D493C), p + Offset(t * .42f, t * .43f),
            Size(t * .19f, t * .44f))
        drawRect(Color(0xFF98714A), p + Offset(t * .45f, t * .45f),
            Size(t * .07f, t * .36f))
        drawRect(pine, p + Offset(t * .12f + sway, t * .19f),
            Size(t * .76f, t * .45f))
        drawRect(leaf, p + Offset(t * .20f + sway, t * .10f),
            Size(t * .62f, t * .49f))
        drawRect(leafTop, p + Offset(t * .25f + sway, t * .09f),
            Size(t * .49f, t * .17f))
        drawRect(Color(0xFF4D9569), p + Offset(t * .11f + sway, t * .39f),
            Size(t * .22f, t * .15f))
        drawRect(Color(0xFF93BE7B), p + Offset(t * .33f + sway, t * .19f),
            Size(t * .11f, t * .10f))
    }

    private fun DrawScope.drawBoulder(p: Offset, t: Float, x: Int, y: Int) {
        drawOval(Color.Black.copy(alpha = .25f), p + Offset(t * .10f, t * .69f),
            Size(t * .8f, t * .22f))
        drawRect(outline, p + Offset(t * .16f, t * .38f), Size(t * .72f, t * .43f))
        drawRect(Color(0xFF9CA9AB), p + Offset(t * .22f, t * .24f), Size(t * .60f, t * .48f))
        drawRect(Color(0xFFC4D0C6), p + Offset(t * .31f, t * .19f), Size(t * .42f, t * .18f))
        drawRect(Color(0xFF687D83), p + Offset(t * .51f, t * .52f), Size(t * .29f, t * .14f))
        if ((x + y) % 4 == 0) drawRect(Color(0xFF72A69C),
            p + Offset(t * .22f, t * .48f), Size(t * .16f, t * .06f))
    }

    private fun DrawScope.drawWall(p: Offset, t: Float) {
        drawRect(Color.Black.copy(alpha = .3f), p + Offset(t * .08f, t * .16f),
            Size(t * .92f, t * .84f))
        drawRect(Color(0xFF594B3F), p + Offset(t * .05f, t * .14f),
            Size(t * .86f, t * .75f))
        drawRect(wood, p + Offset(t * .12f, t * .07f), Size(t * .75f, t * .67f))
        drawRect(cream.copy(alpha = .65f), p + Offset(t * .13f, t * .08f),
            Size(t * .70f, t * .11f))
        drawRect(Color(0xFF70513B), p + Offset(t * .45f, t * .11f),
            Size(t * .07f, t * .63f))
        drawRect(Color(0xFF70513B), p + Offset(t * .13f, t * .48f),
            Size(t * .75f, t * .07f))
    }

    private fun DrawScope.drawBridge(p: Offset, t: Float) {
        // Crossing water becomes visually legible: pilings, broad plank deck and rails.
        drawRect(Color(0xFF3E5560).copy(alpha = .65f),
            p + Offset(t * .08f, t * .14f), Size(t * .86f, t * .79f))
        drawRect(Color(0xFF6D5545), p + Offset(t * .13f, t * .08f),
            Size(t * .76f, t * .85f))
        repeat(4) { i ->
            drawRect(if (i % 2 == 0) Color(0xFFCEA77D) else Color(0xFFB68F68),
                p + Offset(t * .20f, t * (.10f + i * .20f)),
                Size(t * .60f, t * .16f))
        }
        drawRect(Color(0xFFE5C099), p + Offset(t * .08f, t * .07f),
            Size(t * .12f, t * .88f))
        drawRect(Color(0xFF76594B), p + Offset(t * .79f, t * .07f),
            Size(t * .12f, t * .88f))
        drawRect(Color(0xFF66564D), p + Offset(t * .10f, t * .26f),
            Size(t * .80f, t * .045f))
    }

    private fun DrawScope.drawDoor(p: Offset, t: Float) {
        drawRect(Color(0xFF4C433A), p + Offset(t * .19f, t * .08f), Size(t * .63f, t * .81f))
        drawRect(Color(0xFFB17C4F), p + Offset(t * .23f, t * .11f), Size(t * .54f, t * .72f))
        drawRect(Color(0xFFD7A36C), p + Offset(t * .28f, t * .15f), Size(t * .41f, t * .12f))
        drawRect(Color(0xFF6B4F43), p + Offset(t * .29f, t * .37f), Size(t * .40f, t * .045f))
        drawRect(Color(0xFFFFDF87), p + Offset(t * .61f, t * .51f), Size(t * .08f, t * .09f))
        drawRect(Color(0xFF4F443C), p + Offset(t * .11f, t * .85f), Size(t * .77f, t * .05f))
    }

    private fun DrawScope.drawWorkbench(p: Offset, t: Float) {
        drawOval(Color.Black.copy(alpha = .25f), p + Offset(t * .11f, t * .67f),
            Size(t * .8f, t * .19f))
        drawRect(Color(0xFF583F35), p + Offset(t * .15f, t * .40f), Size(t * .68f, t * .43f))
        drawRect(Color(0xFFD8B27D), p + Offset(t * .09f, t * .31f), Size(t * .82f, t * .19f))
        drawRect(Color(0xFFF4CE9B), p + Offset(t * .11f, t * .27f), Size(t * .75f, t * .08f))
        drawRect(Color(0xFF8A6457), p + Offset(t * .20f, t * .51f), Size(t * .16f, t * .20f))
        drawRect(Color(0xFF6B93A2), p + Offset(t * .58f, t * .15f), Size(t * .10f, t * .20f))
    }

    private fun DrawScope.drawBed(p: Offset, t: Float) {
        drawRect(Color(0xFF584439), p + Offset(t * .16f, t * .10f), Size(t * .73f, t * .81f))
        drawRect(Color(0xFFBF9C7F), p + Offset(t * .23f, t * .16f), Size(t * .58f, t * .68f))
        drawRect(Color(0xFF86BAC0), p + Offset(t * .24f, t * .44f), Size(t * .56f, t * .36f))
        drawRect(Color(0xFFDDEDE1), p + Offset(t * .27f, t * .22f), Size(t * .51f, t * .23f))
        drawRect(Color(0xFFA2D4C9), p + Offset(t * .24f, t * .73f), Size(t * .56f, t * .07f))
    }

    private fun DrawScope.drawSpawn(p: Offset, t: Float, phase: Float) {
        drawRect(Color(0xFF956E55), p + Offset(t * .15f, t * .49f), Size(t * .72f, t * .36f))
        drawRect(Color(0xFFEDD1A0), p + Offset(t * .23f, t * .28f), Size(t * .56f, t * .39f))
        drawRect(Color(0xFF8C6456), p + Offset(t * .13f, t * .19f), Size(t * .77f, t * .10f))
        drawRect(Color(0xFFC7835A), p + Offset(t * .21f, t * .10f), Size(t * .61f, t * .12f))
        drawRect(Color(0xFF785A4B), p + Offset(t * .43f, t * .53f), Size(t * .18f, t * .30f))
        if (sin(phase * 6.28318f) > 0) drawRect(Color(0xFFFFDC9A),
            p + Offset(t * .69f, t * .40f), Size(t * .07f, t * .08f))
    }

    private fun DrawScope.drawFire(p: Offset, t: Float, phase: Float, night: Boolean) {
        val flicker = sin(phase * 12.56636f) * t * .07f
        if (night) drawCircle(Color(0xFFFFBF69).copy(alpha = .20f), t * .62f,
            p + Offset(t * .5f, t * .5f))
        drawRect(Color(0xFF6A4B3C), p + Offset(t * .20f, t * .69f), Size(t * .64f, t * .12f))
        drawRect(Color(0xFF8B6C53), p + Offset(t * .24f, t * .61f), Size(t * .55f, t * .09f))
        drawRect(Color(0xFFFF8A46), p + Offset(t * .31f, t * .37f + flicker),
            Size(t * .39f, t * .29f - flicker))
        drawRect(Color(0xFFFFDB81), p + Offset(t * .41f, t * .31f - flicker),
            Size(t * .20f, t * .26f))
        drawRect(Color(0xFFFFF0C5), p + Offset(t * .46f, t * .43f),
            Size(t * .09f, t * .13f))
    }

    private fun DrawScope.drawSlime(c: Offset, t: Float, hp: Int, phase: Float, seed: Int) {
        val bob = sin(phase * 6.28318f + seed) * t * .05f
        drawOval(Color.Black.copy(alpha = .25f), c + Offset(-t * .34f, t * .23f),
            Size(t * .69f, t * .17f))
        val p = c + Offset(-t * .32f, -t * .22f + bob)
        drawRect(Color(0xFF4D3A5B), p + Offset(0f, t * .12f), Size(t * .65f, t * .40f))
        drawRect(Color(0xFF84658D), p + Offset(t * .09f, 0f), Size(t * .48f, t * .42f))
        drawRect(Color(0xFFAE8AAF), p + Offset(t * .17f, t * .04f), Size(t * .23f, t * .10f))
        drawRect(Color.White, p + Offset(t * .16f, t * .23f), Size(t * .09f, t * .10f))
        drawRect(Color.White, p + Offset(t * .43f, t * .23f), Size(t * .09f, t * .10f))
        drawRect(Color(0xFF31293A), p + Offset(t * .19f, t * .26f), Size(t * .04f, t * .06f))
        drawRect(Color(0xFF31293A), p + Offset(t * .46f, t * .26f), Size(t * .04f, t * .06f))
        drawRect(Color(0xFF532F41), c + Offset(-t * .31f, -t * .35f),
            Size(t * .62f * (hp / 4f).coerceIn(0f, 1f), t * .045f))
    }

    private fun DrawScope.drawPerson(c: Offset, t: Float, coat: Color, hair: Color, phase: Float, user: Boolean) {
        val bob = sin(phase * 6.28318f) * t * .035f
        val p = c + Offset(0f, bob)
        drawOval(Color.Black.copy(alpha = .25f), p + Offset(-t * .26f, t * .24f),
            Size(t * .52f, t * .14f))
        drawRect(Color(0xFF49465B), p + Offset(-t * .20f, t * .08f), Size(t * .15f, t * .24f))
        drawRect(Color(0xFF49465B), p + Offset(t * .05f, t * .08f), Size(t * .15f, t * .24f))
        drawRect(coat, p + Offset(-t * .24f, -t * .12f), Size(t * .48f, t * .32f))
        drawRect(coat.copy(alpha = .75f), p + Offset(-t * .32f, -t * .05f), Size(t * .09f, t * .23f))
        drawRect(coat.copy(alpha = .75f), p + Offset(t * .23f, -t * .05f), Size(t * .09f, t * .23f))
        drawRect(Color(0xFFF5D7B1), p + Offset(-t * .21f, -t * .40f), Size(t * .42f, t * .32f))
        drawRect(hair, p + Offset(-t * .25f, -t * .44f), Size(t * .50f, t * .13f))
        drawRect(hair, p + Offset(-t * .25f, -t * .34f), Size(t * .08f, t * .14f))
        drawRect(Color(0xFF344354), p + Offset(-t * .12f, -t * .22f), Size(t * .055f, t * .06f))
        drawRect(Color(0xFF344354), p + Offset(t * .08f, -t * .22f), Size(t * .055f, t * .06f))
        if (user) {
            drawRect(Color(0xFFFFF4BB), p + Offset(-t * .23f, -t * .51f), Size(t * .46f, t * .07f))
            drawRect(Color(0xFFFCE4A3), p + Offset(-t * .10f, t * .01f), Size(t * .20f, t * .07f))
        }
    }

    private fun DrawScope.drawFallen(c: Offset, t: Float) {
        drawOval(Color.Black.copy(alpha = .27f), c + Offset(-t * .31f, t * .13f), Size(t * .64f, t * .14f))
        drawRect(Color(0xFF87A6BB), c + Offset(-t * .27f, -t * .06f), Size(t * .53f, t * .16f))
        drawRect(Color(0xFFF3D4B5), c + Offset(t * .11f, -t * .10f), Size(t * .19f, t * .17f))
        drawRect(Color(0xFFFFAAA7), c + Offset(-t * .04f, -t * .38f), Size(t * .09f, t * .12f))
    }
}
