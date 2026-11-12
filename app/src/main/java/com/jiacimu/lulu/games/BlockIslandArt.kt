package com.jiacimu.lulu.games

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.abs

/**
 * Hand-authored procedural pixel-style art. No third-party sprites or copyrighted game assets.
 * Visuals are always derived from real BlockIslandState tiles and positions.
 * Paint only the visible viewport to keep this affordable on low-end Android GPUs.
 */
internal data class BlockIslandImpact(
    val x: Int, val y: Int, val action: String, val turn: Int, val label: String = "",
    val targetMobId: Int? = null,
)

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
    // Stable palette: a frame must not allocate two new lists just to paint companions.
    private val companionOutfits = listOf(
        Color(0xFF71BBD0), Color(0xFFE1B783), Color(0xFFA8A4D5), Color(0xFFB7D38C),
    )
    // Reused geometry offsets; drawing the same island must not allocate per-frame coordinate lists.
    private val houseLightOffsets = floatArrayOf(.55f, 2.44f)
    private val houseWindowOffsets = floatArrayOf(.33f, 2.37f)
    // Garden stalk spacing is immutable artwork geometry, reused for each visible crop.
    private val gardenStalkOffsets = floatArrayOf(.32f, .51f, .68f)
    private val neighborOffsets = arrayOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
    private val selectionCorners = arrayOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)
    private val companionHair = listOf(
        Color(0xFF3F627F), Color(0xFF594D4B), Color(0xFF574D73), Color(0xFF6F5D46),
    )

    internal fun DrawScope.paint(
        island: BlockIslandState,
        selected: Pair<Int, Int>,
        companionPing: Pair<Int, Int>? = null,
        playerVisual: Offset? = null,
        companionVisuals: Map<String,Offset> = emptyMap(),
        mobVisuals: Map<Int,Offset> = emptyMap(),
        companionLabels: Map<String,String> = emptyMap(),
        playerFacing: Pair<Int,Int> = 0 to 1,
        camera: IslandFollowCamera,
        navigationStep: Pair<Int, Int>? = null,
        night: Boolean,
        phase: Float,
        impact: BlockIslandImpact? = null,
        impactProgress: Float = 1f,
        buildPreview: IslandBuildPreview? = null,
        hurtProgress: Float = 1f,
        terrainAtlas: ImageBitmap? = null,
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
                val seaUp=w.cell(x,y-1)==8
                val seaLeft=w.cell(x-1,y)==8
                val seaDown=w.cell(x,y+1)==8
                val seaRight=w.cell(x+1,y)==8
                if(terrainAtlas!=null)
                    drawTerrainAtlas(terrainAtlas,cell,base,tile,x,y,w.seed,phase,
                        seaUp,seaLeft,seaDown,seaRight)
                else
                    drawGround(cell,base,tile,x,y,phase,w.seed,
                        seaUp,seaLeft,seaDown,seaRight)
                // Quiet seasonal lighting over the original, recognisable terrain atlas.
                // This is visual only; growth rules use the same saved turn number.
                if (cell != 8) {
                    val seasonTint=when(IslandGarden.season(w.turns)) {
                        "春" -> Color(0xFFBDE6AF).copy(alpha=.05f)
                        "夏" -> Color(0xFFF8D992).copy(alpha=.05f)
                        "秋" -> Color(0xFFE4B274).copy(alpha=.13f)
                        else -> Color(0xFFE6EFF3).copy(alpha=.19f)
                    }
                    drawRect(seasonTint,topLeft=base,size=Size(tile,tile))
                }
            }
        }
        // Scenery rendered in a separate pass so tree crowns can overhang adjacent tiles.
        for (y in top until (top + rows).coerceAtMost(w.height)) {
            for (x in left until (left + cols).coerceAtMost(w.width)) {
                val base = origin(x, y)
                when (w.cell(x, y)) {
                    1 -> drawTree(base, tile, x, y, phase)
                    2 -> {
                        drawBoulder(base, tile, x, y)
                        if(IslandProgression.crystalVein(w.seed,w.index(x,y))) {
                            val c=base+Offset(tile*.59f,tile*.39f)
                            val crystal=Path().apply {
                                moveTo(c.x,c.y-tile*.20f)
                                lineTo(c.x+tile*.16f,c.y)
                                lineTo(c.x,c.y+tile*.18f)
                                lineTo(c.x-tile*.14f,c.y)
                                close()
                            }
                            drawCircle(Color(0xFF83ECF2).copy(alpha=.17f+
                                .11f*(.5f+.5f*sin(phase*6.28318f))),tile*.28f,c)
                            drawPath(crystal,Color(0xFF70C9F4))
                            drawLine(Color(0xFFD8FCFF),c+Offset(0f,-tile*.16f),
                                c+Offset(0f,tile*.14f),strokeWidth=tile*.024f)
                        }
                    }
                    3 -> drawWall(base, tile)
                    7 -> drawSpawn(base, tile, phase)
                }
                when (island.structures[w.index(x, y)]) {
                    "bridge" -> drawBridge(base, tile)
                    "door" -> drawDoor(base, tile)
                    "workbench" -> drawWorkbench(base, tile)
                    "bed" -> drawBed(base, tile)
                    "campfire" -> drawFire(base, tile, phase, night)
                    "lantern" -> drawIslandLantern(base, tile, phase)
                    "crystal_lantern" -> {
                        drawIslandLantern(base,tile,phase)
                        val c=base+Offset(tile*.50f,tile*.29f)
                        drawCircle(Color(0xFF97DAF7).copy(alpha=.28f),tile*.36f,c)
                        drawCircle(Color(0xFFBDFBFF),tile*.14f,c)
                        drawCircle(Color(0xFF57B3E5),tile*.08f,c)
                    }
                    "pet_house" -> drawPetHouse(base, tile)
                    "garden" -> {
                        // Each visible leaf corresponds to a crop persisted in the save.
                        drawRoundRect(Color(0xFF805F47),
                            topLeft=base+Offset(tile*.10f,tile*.21f),
                            size=Size(tile*.80f,tile*.61f),
                            cornerRadius=CornerRadius(tile*.10f))
                        drawRoundRect(Color(0xFF9C7654),
                            topLeft=base+Offset(tile*.14f,tile*.25f),
                            size=Size(tile*.72f,tile*.50f),
                            cornerRadius=CornerRadius(tile*.07f))
                        val crop=island.gardens[w.index(x,y)]
                        if(crop!=null) {
                            val grown=crop.tendedTurn>=0
                            val ripe=IslandGarden.isReady(crop,w.turns)
                            for(dx in gardenStalkOffsets) {
                                drawLine(Color(0xFF346E4B),
                                    base+Offset(tile*dx,tile*.65f),
                                    base+Offset(tile*dx,tile*(if(grown).37f else .53f)),
                                    strokeWidth=tile*.045f)
                                drawCircle(Color(0xFF65A86F),
                                    radius=tile*(if(grown).14f else .08f),
                                    center=base+Offset(tile*dx,tile*(if(grown).38f else .53f)))
                                if(ripe)drawCircle(Color(0xFFE38171),
                                    radius=tile*.065f,
                                    center=base+Offset(tile*dx,tile*.40f))
                            }
                        }
                    }
                }
            }
        }
        // Dropped supplies remain on the real map until a player recovers them.
        island.lostPacks.forEach { (index,pack) ->
            val x=index%w.width
            val y=index/w.width
            if(x !in left until left+cols || y !in top until top+rows) return@forEach
            val p=origin(x,y)
            val pulse=.5f+.5f*sin(phase*6.28318f)
            drawCircle(Color(0xFFFFD8A6).copy(alpha=.13f+.12f*pulse),
                radius=tile*.53f,center=center(x,y))
            drawRoundRect(Color(0xFF5C413A),p+Offset(tile*.22f,tile*.29f),
                Size(tile*.55f,tile*.53f),CornerRadius(tile*.12f))
            drawRoundRect(Color(0xFFB88451),p+Offset(tile*.28f,tile*.30f),
                Size(tile*.43f,tile*.39f),CornerRadius(tile*.10f))
            drawRect(Color(0xFFF0DDB2),p+Offset(tile*.45f,tile*.49f),
                Size(tile*.09f,tile*.08f))
        }
        island.houseProject?.takeIf { it.phase=="complete" }?.let { house ->
            if(house.left+3>=left && house.left<left+cols &&
                house.top+3>=top && house.top<top+rows)
                drawFinishedCompanionHouse(origin(house.left,house.top),tile,house.style,phase)
        }
        // Two map-readable travel waypoints, projected from the same physical
        // coordinates and awarded IDs used by the rules. These are floor sigils,
        // NOT structures, resources or collision targets. Completed sites settle
        // into quiet blue-gold mosaics instead of disappearing from the world.
        for (site in IslandExpeditionTrail.landmarks) {
            val (x,y)=IslandExpeditionTrail.position(island,site)
            if(x !in left until left+cols || y !in top until top+rows)continue
            val position=center(x,y)
            val completed=site.id in island.awardedChallenges
            val pulse=.5f+.5f*sin(phase*6.28318f + x*.31f)
            val accent=if(completed)Color(0xFF8BDDC8)else Color(0xFFFFD995)
            val glow=.09f+if(completed).02f else pulse*.09f
            drawCircle(accent.copy(alpha=glow),tile*.94f,position)
            drawCircle(Color(0xFF244F4D).copy(alpha=.66f),tile*.39f,position)
            drawCircle(Color(0xFFE1D1A1).copy(alpha=.83f),tile*.30f,position,
                style=Stroke(tile*.055f))
            drawCircle(accent.copy(alpha=if(completed).44f else .72f+.20f*pulse),
                tile*(if(completed).36f else .40f+.06f*pulse),position,
                style=Stroke(tile*.045f))
            val jewel=Path().apply {
                moveTo(position.x,position.y-tile*.27f)
                lineTo(position.x+tile*.19f,position.y)
                lineTo(position.x,position.y+tile*.27f)
                lineTo(position.x-tile*.19f,position.y)
                close()
            }
            drawPath(jewel,if(completed)Color(0xFF75C9B6)else Color(0xFFFFF1B8))
            drawLine(Color(0xFF6F8F8B),position+Offset(-tile*.08f,0f),
                position+Offset(tile*.08f,0f),strokeWidth=tile*.035f)
            if(!completed) {
                drawCircle(Color(0xFFFCF4DC).copy(alpha=.56f+.35f*pulse),
                    tile*.055f,position+Offset(tile*.30f,-tile*.40f))
                drawCircle(Color(0xFFFCE6AC).copy(alpha=.50f+.26f*pulse),
                    tile*.038f,position+Offset(-tile*.42f,-tile*.22f))
            }
        }
        if (night) {
            // Small translucent veil allows genuine color changes, rather than an opaque black map.
            drawRect(Color(0xFF0D234C).copy(alpha = .27f))
            // Warm pools of light come from actual built fires and the saved spawn point.
            for (y in top until (top + rows).coerceAtMost(w.height)) {
                for (x in left until (left + cols).coerceAtMost(w.width)) {
                    val index = w.index(x, y)
                    val decor = island.structures[index]
                    if (decor == "campfire" || decor == "lantern" ||
                        decor == "crystal_lantern" || w.cell(x, y) == 7) {
                        val c = center(x, y)
                        val bright=if(decor=="crystal_lantern")2.8f
                            else if(decor=="lantern")1.9f else 1.4f
                        drawCircle(Color(0xFFFFCF87).copy(alpha = .11f), tile*bright, c)
                        drawCircle(Color(0xFFFFDB9A).copy(alpha = .19f), tile*.85f, c)
                        if(decor=="lantern")
                            drawCircle(Color(0xFFFFF0C1).copy(alpha=.18f),tile*.38f,c)
                        if(decor=="crystal_lantern") {
                            drawCircle(Color(0xFF9BDDF8).copy(alpha=.15f),tile*2.9f,c)
                            drawCircle(Color(0xFFCAF6FF).copy(alpha=.24f),tile*.9f,c)
                        }
                    }
                }
            }
            island.houseProject?.takeIf { it.phase=="complete" }?.let { house ->
                val base=origin(house.left,house.top)
                val glow=Color(0xFFFFDBA5)
                for(dx in houseLightOffsets) {
                    val center=base+Offset(tile*dx,tile*2.20f)
                    drawCircle(glow.copy(alpha=.18f),tile*.74f,center)
                    drawCircle(glow.copy(alpha=.38f),tile*.24f,center)
                }
            }
        }
        // Only immediately interactable targets glow; no invented objectives or world changes.
        for ((dx, dy) in neighborOffsets) {
            val x = w.x + dx
            val y = w.y + dy
            if (!w.inBounds(x, y)) continue
            val actionable = when {
                w.mobs.any { it.hp > 0 && it.x == x && it.y == y } -> Color(0xFFFFA78C)
                island.animals.any { animal ->
                    animal.x==x && animal.y==y &&
                        (if(animal.befriended)IslandWildlife.canGift(island,animal)
                        else IslandWildlife.canFeed(island,animal))
                } -> Color(0xFFFFDCAC)
                w.friends.any { it.hp <= 0 && it.x == x && it.y == y } &&
                    island.fruit >= 2 -> Color(0xFFA9F4E8)
                w.index(x,y) in island.lostPacks -> Color(0xFFFFD4A0)
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
        w.mobs.forEach { mob ->
                if (mob.hp <= 0 || mob.x !in left until left + cols || mob.y !in top until top + rows) return@forEach
                // Monsters can move after the player's real attack in the same turn.
                // Identify the hit by stable mob ID, not by its old target coordinate.
                val hit = impact?.takeIf { it.action=="fight" &&
                    it.targetMobId==mob.id && impactProgress<1f }
                val pos=actorCenter(mobVisuals[mob.id],mob.x,mob.y)
                if(mob.id==IslandGuardianEncounter.id)
                    drawGuardian(pos,tile,mob.hp,phase,
                        hit?.let { 1f-impactProgress } ?: 0f,
                        IslandGuardianEncounter.impendingHeavyAttack(island))
                else drawSlime(pos,tile,mob.hp,phase,mob.id,
                    hit?.let { 1f-impactProgress } ?: 0f)
            }
        // Every living creature is rendered from real saved entities, not decorative sprites.
        island.animals.forEach { animal ->
                if (animal.x !in left until left + cols || animal.y !in top until top + rows) return@forEach
                drawIslandAnimal(center(animal.x,animal.y),tile,animal,phase)
            }
        // One label paint per frame, rather than one allocation/typeface lookup per visible friend.
        val companionNamePaint = android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color(0xFFE5FFF1).toArgb()
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        w.friends.forEach { friend ->
                if (friend.x !in left until left + cols || friend.y !in top until top + rows) return@forEach
                val look = (friend.id.hashCode() and Int.MAX_VALUE) % companionOutfits.size
                val visual = companionVisuals[friend.id]
                val walking = visual != null &&
                    abs(visual.x-friend.x)+abs(visual.y-friend.y)>.025f
                val head=actorCenter(visual,friend.x,friend.y)
                if (friend.hp > 0) drawPerson(
                    head,tile,
                    companionOutfits[look], companionHair[look], phase + look * .13f, false,
                    walking = walking,
                ) else drawFallen(head,tile)
                // Directly label real party members in-world so their palette color is
                // not the only way to distinguish them in a two-/three-person adventure.
                val name=companionLabels[friend.id]?.take(4).orEmpty()
                if(name.isNotBlank() && tile>=22f) {
                    val plateWidth=(tile*.34f*name.length).coerceAtLeast(tile*.70f)+tile*.19f
                    drawRoundRect(Color(0xC512343A),
                        topLeft=head+Offset(-plateWidth*.5f,-tile*.80f),
                        size=Size(plateWidth,tile*.28f),
                        cornerRadius=CornerRadius(tile*.09f))
                    companionNamePaint.textSize = (tile*.23f).coerceIn(9f,15f)
                    drawContext.canvas.nativeCanvas.drawText(name,
                        head.x,head.y-tile*.59f,companionNamePaint)
                }
            }
        if (w.x in left until left + cols && w.y in top until top + rows) {
            val playerCenter=actorCenter(playerVisual,w.x,w.y)
            val walking=playerVisual != null &&
                abs(playerVisual.x-w.x)+abs(playerVisual.y-w.y)>.025f
            drawPerson(playerCenter,tile,
                Color(0xFFEEA87C),Color(0xFF715047),phase,true,walking=walking)
            // Clear directional cue beneath the feet: turning does not spend a game turn.
            val heading=Offset(playerFacing.first.toFloat(),playerFacing.second.toFloat())
            if(abs(playerFacing.first)+abs(playerFacing.second)==1) {
                val tip=playerCenter+heading*tile*.39f
                val side=Offset(-heading.y,heading.x)
                drawCircle(Color(0xFFDFF6D4).copy(alpha=.22f),tile*.17f,tip)
                drawLine(Color(0xFFFFE09C),tip-heading*tile*.14f,tip,
                    strokeWidth=(tile*.09f).coerceAtLeast(1.5f))
                drawLine(Color(0xFFFFE09C),tip,
                    tip-heading*tile*.11f+side*tile*.08f,
                    strokeWidth=(tile*.055f).coerceAtLeast(1f))
                drawLine(Color(0xFFFFE09C),tip,
                    tip-heading*tile*.11f-side*tile*.08f,
                    strokeWidth=(tile*.055f).coerceAtLeast(1f))
            }
        }
        // Real once-per-island salvage stashes, drawn directly from persisted locations.
        for (index in island.supplyCaches) {
            if (index in island.openedSupplies) continue
            val x = index % w.width
            val y = index / w.width
            if (x !in left until left + cols || y !in top until top + rows) continue
            val p = origin(x,y)
            val c = center(x,y)
            drawCircle(Color(0xFFD8EFC0).copy(alpha = .12f + .12f * sin(phase * 6.28318f)),
                tile * .48f, c)
            drawRoundRect(Color(0xFF403B38), p + Offset(tile*.15f,tile*.30f),
                Size(tile*.72f,tile*.54f),CornerRadius(tile*.11f))
            drawRoundRect(Color(0xFFD9AD72), p + Offset(tile*.17f,tile*.26f),
                Size(tile*.66f,tile*.48f),CornerRadius(tile*.09f))
            drawRect(Color(0xFF76594C), p + Offset(tile*.43f,tile*.27f),
                Size(tile*.13f,tile*.46f))
            drawCircle(Color(0xFFFFE6A8),tile*.10f,c + Offset(0f,tile*.04f))
        }
        // Build-plan footprints are only a projection of the saved character project;
        // empty tiles remain empty until actual wood is consumed and a real action succeeds.
        island.houseProject?.let { plan ->
            val outline = if (plan.phase=="demolishing") Color(0xFFD3A8F2)
                else Color(0xFF9FE5CC)
            for ((index, _) in IslandHouseProjects.parts(w, plan.left, plan.top)) {
                val x=index%w.width
                val y=index/w.width
                if(x !in left until left+cols || y !in top until top+rows)continue
                val built=index in plan.placed
                drawRoundRect(outline.copy(alpha=if(built).21f else .65f),
                    origin(x,y)+Offset(tile*.10f,tile*.10f),
                    Size(tile*.80f,tile*.80f),CornerRadius(tile*.11f),
                    style=Stroke((tile*.055f).coerceAtLeast(1.5f)))
            }
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
        if (buildPreview == null &&
            selected.first in left until left + cols &&
            selected.second in top until top + rows) {
            val c = center(selected.first, selected.second)
            val ring = tile * (.42f + .035f * sin(phase * 6.28318f))
            val color = Color(0xFFFFE19A)
            drawRect(color.copy(alpha = .8f), origin(selected.first, selected.second) +
                Offset(2f, 2f), Size(tile - 4f, tile - 4f), style = Stroke((tile * .055f).coerceAtLeast(1.5f)))
            // Four corner highlights, clear even at the small default zoom.
            selectionCorners.forEach { (dx, dy) ->
                drawRect(cream, topLeft = c + Offset(dx * ring, dy * ring) -
                    Offset(tile * .05f, tile * .05f), size = Size(tile * .10f, tile * .10f))
            }
        }
        // Placement ghost, based on the SAME validation as the rule engine.
        // It never changes the world, consumes inventory or advances a turn.
        if (buildPreview != null &&
            buildPreview.x in left until left + cols &&
            buildPreview.y in top until top + rows) {
            val p = origin(buildPreview.x, buildPreview.y)
            val ok = buildPreview.allowed
            val tint = if (ok) Color(0xFF91F0B3) else Color(0xFFFF9588)
            if(buildPreview.action in setOf("house_warm","house_pine")) {
                // Blueprints preview the actual 3×3 footprint instead of
                // misleadingly highlighting only the chosen top-left cell.
                drawRoundRect(tint.copy(alpha=.18f),
                    topLeft=p+Offset(tile*.04f,tile*.04f),
                    size=Size(tile*2.92f,tile*2.92f),
                    cornerRadius=CornerRadius(tile*.12f))
                drawRoundRect(tint.copy(alpha=.88f),
                    topLeft=p+Offset(tile*.04f,tile*.04f),
                    size=Size(tile*2.92f,tile*2.92f),
                    cornerRadius=CornerRadius(tile*.12f),
                    style=Stroke((tile*.055f).coerceAtLeast(1.4f)))
                // Each outline is a future tile that still needs wood and a
                // deliberate physical step; none of these are real objects yet.
                for (dx in 0..2) for (dy in 0..2) {
                    drawRect(tint.copy(alpha=.34f),
                        topLeft=p+Offset(tile*(dx+.07f),tile*(dy+.07f)),
                        size=Size(tile*.86f,tile*.86f),
                        style=Stroke((tile*.018f).coerceAtLeast(1f)))
                }
            }
            drawRoundRect(tint.copy(alpha = if(ok) .26f else .20f),
                topLeft = p + Offset(tile*.06f,tile*.06f),
                size = Size(tile*.88f,tile*.88f), cornerRadius = CornerRadius(tile*.11f))
            drawRoundRect(tint.copy(alpha = .94f),
                topLeft = p + Offset(tile*.06f,tile*.06f),
                size = Size(tile*.88f,tile*.88f), cornerRadius = CornerRadius(tile*.11f),
                style = Stroke((tile*.07f).coerceAtLeast(1.4f)))
            // Different recipes get distinct translucent footprints. They are
            // still only previews, not additional placed or saved world objects.
            when(buildPreview.action) {
                "wall", "door" -> {
                    drawRect(tint.copy(alpha=.66f),p+Offset(tile*.24f,tile*.23f),
                        Size(tile*.10f,tile*.55f))
                    drawRect(tint.copy(alpha=.66f),p+Offset(tile*.67f,tile*.23f),
                        Size(tile*.10f,tile*.55f))
                    if(buildPreview.action=="wall")
                        drawRect(tint.copy(alpha=.45f),p+Offset(tile*.27f,tile*.36f),
                            Size(tile*.48f,tile*.15f))
                }
                "floor", "bridge" -> repeat(3) { row ->
                    drawRect(tint.copy(alpha=.60f),
                        p+Offset(tile*.19f,tile*(.24f+row*.21f)),
                        Size(tile*.63f,tile*.13f))
                }
                "campfire", "lantern" -> {
                    drawCircle(tint.copy(alpha=.70f),tile*.20f,
                        p+Offset(tile*.50f,tile*.53f))
                    drawCircle(tint.copy(alpha=.36f),tile*.30f,
                        p+Offset(tile*.50f,tile*.53f),style=Stroke(tile*.06f))
                }
                "pet_house" -> {
                    drawRoundRect(tint.copy(alpha=.52f),
                        p+Offset(tile*.12f,tile*.36f),
                        Size(tile*.76f,tile*.52f),CornerRadius(tile*.10f))
                    drawLine(tint,p+Offset(tile*.12f,tile*.36f),
                        p+Offset(tile*.5f,tile*.08f),strokeWidth=tile*.09f)
                    drawLine(tint,p+Offset(tile*.5f,tile*.08f),
                        p+Offset(tile*.88f,tile*.36f),strokeWidth=tile*.09f)
                }
                "plant" -> {
                    drawRect(tint.copy(alpha=.6f),p+Offset(tile*.47f,tile*.38f),
                        Size(tile*.07f,tile*.42f))
                    drawCircle(tint.copy(alpha=.64f),tile*.24f,
                        p+Offset(tile*.50f,tile*.39f))
                }
                "bed", "workbench" -> {
                    drawRoundRect(tint.copy(alpha=.52f),
                        p+Offset(tile*.20f,tile*.22f),
                        Size(tile*.62f,tile*.57f),CornerRadius(tile*.07f))
                    drawRect(tint.copy(alpha=.72f),
                        p+Offset(tile*.24f,tile*.27f),
                        Size(tile*.54f,tile*.10f))
                }
                "remove" -> {
                    drawLine(tint,p+Offset(tile*.28f,tile*.28f),
                        p+Offset(tile*.72f,tile*.72f),strokeWidth=tile*.10f)
                    drawLine(tint,p+Offset(tile*.72f,tile*.28f),
                        p+Offset(tile*.28f,tile*.72f),strokeWidth=tile*.10f)
                }
            }
            if(!ok && buildPreview.action!="remove") {
                drawLine(Color(0xFFFFF0DE),p+Offset(tile*.28f,tile*.72f),
                    p+Offset(tile*.72f,tile*.28f),strokeWidth=tile*.06f)
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
        // A health drop is a real game event; briefly tint the world edges without
        // obscuring movement controls or showing damage on rejected actions.
        if(hurtProgress<1f) {
            val intensity=(1f-hurtProgress)*.29f
            drawRect(Brush.radialGradient(
                colors=listOf(Color.Transparent,Color(0xFFB6333D).copy(alpha=intensity)),
                center=Offset(size.width*.5f,size.height*.5f),
                radius=kotlin.math.max(size.width,size.height)*.71f))
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
            repeat(if (impact.action == "fight") 9 else 6) { i ->
                val angle = i * 1.04719f + phase
                val radius = tile * (.18f + .77f * impactProgress)
                val particle = point + Offset(cos(angle) * radius, sin(angle) * radius)
                if(impact.action=="fight") {
                    drawLine(color.copy(alpha=fade),particle,
                        particle+Offset(cos(angle)*tile*.13f,sin(angle)*tile*.13f),
                        strokeWidth=(tile*.06f*fade).coerceAtLeast(.8f))
                } else {
                    drawRect(color.copy(alpha = fade), particle,
                        Size(tile * .10f * fade, tile * .10f * fade))
                }
            }
            // Actual material/health delta from the successfully applied game action.
            // Text floats above the hit tile; a rejected action never creates this effect.
            if(impact.label.isNotBlank()) {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = (if(impact.action=="fight") Color(0xFFFFD3B8)
                        else Color(0xFFE8FFE1)).copy(alpha=fade).toArgb()
                    textAlign=android.graphics.Paint.Align.CENTER
                    typeface=android.graphics.Typeface.create(
                        android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD)
                    textSize=(tile*.34f).coerceAtLeast(13f)
                    setShadowLayer(tile*.06f,0f,2f,Color(0xFF112B27).copy(alpha=fade).toArgb())
                }
                drawContext.canvas.nativeCanvas.drawText(impact.label,
                    point.x,point.y-tile*(.36f+impactProgress*.65f),paint)
            }
        }
    }

    /** A real Figma-authored 6-cell texture atlas, displayed from the SAME
     * saved tile type as movement, harvesting, building and monster collision.
     * No parallel art map or hidden model call. Keep DrawScope fallback for
     * render-only tests and previews that do not load Android resources. */
    private fun DrawScope.drawTerrainAtlas(
        atlas:ImageBitmap,kind:Int,p:Offset,t:Float,x:Int,y:Int,seed:Int,
        phase:Float,waterAbove:Boolean,waterLeft:Boolean,
        waterBelow:Boolean,waterRight:Boolean,
    ) {
        val frame=IslandTerrainPalette.atlasFrame(kind,x,y,seed)
        val left=kotlin.math.floor(p.x.toDouble()).toInt()
        val top=kotlin.math.floor(p.y.toDouble()).toInt()
        val right=kotlin.math.ceil((p.x+t).toDouble()).toInt()
        val bottom=kotlin.math.ceil((p.y+t).toDouble()).toInt()
        drawImage(atlas,
            srcOffset=IntOffset(frame*128,0),srcSize=IntSize(128,128),
            dstOffset=IntOffset(left,top),
            dstSize=IntSize((right-left).coerceAtLeast(1),(bottom-top).coerceAtLeast(1)),
            // Smooth scaled artwork on modern phone densities; nearest-neighbor
            // sampling made these soft hand-painted textures look pixelated.
            filterQuality=FilterQuality.Medium)
        if(kind==8){
            drawRect(Color(0xFFD3E9E4).copy(alpha=.12f),p,Size(t,t))
            // The atlas provides texture while small waves remain animated;
            // fishing/shoreline rules still read the original world tile.
            val drift=t*(.10f+.22f*phase)
            drawRoundRect(Color(0x99DDF5EA).copy(alpha=.20f+.12f*phase),
                topLeft=p+Offset(t*.16f+drift*.15f,t*.36f),
                size=Size(t*.21f,t*.035f),cornerRadius=CornerRadius(t*.02f))
        }else {
            // One quiet tint ties adjacent atlas tiles together without
            // obscuring the authored grass and stone brushwork.
            if(kind!=3 && kind!=4)
                drawRect(Color(0xFFDFE7CE).copy(alpha=.07f),p,Size(t,t))
            if(waterAbove || waterBelow || waterLeft || waterRight) {
            val edge=Color(0xFFF3E1BA).copy(alpha=.32f)
            val width=t*.055f
            if(waterAbove)drawRect(edge,p,Size(t,width))
            if(waterBelow)drawRect(edge,p+Offset(0f,t-width),Size(t,width))
            if(waterLeft)drawRect(edge,p,Size(width,t))
            if(waterRight)drawRect(edge,p+Offset(t-width,0f),Size(width,t))
            }
        }
    }

    private fun DrawScope.drawGround(
        kind: Int, p: Offset, t: Float, x: Int, y: Int, phase: Float, seed: Int,
        waterAbove: Boolean, waterLeft: Boolean,
        waterBelow: Boolean, waterRight: Boolean,
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
            if (!waterRight) drawRect(Color(0xFFB8DCE0).copy(alpha = .34f),
                topLeft = p + Offset(t*.91f,0f), size = Size(t*.09f,t))
            if (!waterBelow) drawRect(Color(0xFFB8DCE0).copy(alpha = .34f),
                topLeft = p + Offset(0f,t*.91f), size = Size(t,t*.09f))
            // Alternating transparent sea bands and short wave crests, animated
            // in place. They do not affect navigation, fishing or treasure drops.
            drawRect(Color(0xFF0B5272).copy(alpha=.20f),
                p+Offset(0f,t*.56f),Size(t,t*.28f))
            if(hash % 3==0) {
                val surf=t*(.11f+.05f*sin(phase*6.28318f+x*.7f))
                drawOval(Color(0xFFC4EFE4).copy(alpha=.29f),
                    p+Offset(t*.15f,t*.78f-surf),Size(t*.42f,t*.065f))
            }
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
        // Shoreline highlights follow the real land/water boundaries. No new
        // terrain, items or separate tile state are introduced by this shading.
        if (waterAbove || waterLeft || waterBelow || waterRight) {
            val sand=Color(0xFFDEC28F).copy(alpha=.57f)
            val width=t*.11f
            if(waterAbove)drawRect(sand,p,Size(t,width))
            if(waterLeft)drawRect(sand,p,Size(width,t))
            if(waterBelow)drawRect(sand,p+Offset(0f,t-width),Size(t,width))
            if(waterRight)drawRect(sand,p+Offset(t-width,0f),Size(width,t))
        }
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
        if(hash%23==0) {
            // A hand-shaped cluster of blossoms, with an outlined golden core.
            val center=p+Offset(t*.36f,t*.37f)
            val petal=Color(0xFFF4D1DC)
            drawCircle(Color(0xFF365A4D).copy(alpha=.20f),t*.16f,
                center+Offset(t*.035f,t*.10f))
            for (i in 0..3) {
                val angle=i*1.5707963f
                drawOval(petal,center+Offset(cos(angle)*t*.10f-t*.06f,
                    sin(angle)*t*.10f-t*.07f),Size(t*.12f,t*.15f))
            }
            drawCircle(Color(0xFFFFD17E),t*.067f,center)
        }
    }

    private fun DrawScope.drawTree(p: Offset, t: Float, x: Int, y: Int, phase: Float) {
        val sway=sin(phase*6.28318f+x*.58f+y*.73f)*t*.035f
        val variant=((x*23+y*17) and Int.MAX_VALUE)%3
        val dark=if(variant==0)Color(0xFF235A48) else Color(0xFF285B47)
        val leaf=if(variant==1)Color(0xFF4A9967) else Color(0xFF438F65)
        // Broad soft canopy shadow and a visible trunk anchor each tree to its tile.
        drawOval(Color.Black.copy(alpha=.24f),p+Offset(t*.09f,t*.77f),
            Size(t*.87f,t*.18f))
        drawRoundRect(Color(0xFF503E35),p+Offset(t*.43f,t*.43f),
            Size(t*.18f,t*.42f),CornerRadius(t*.055f))
        drawRect(Color(0xFF9D7652),p+Offset(t*.47f,t*.49f),
            Size(t*.052f,t*.31f))
        drawLine(Color(0xFF6C513D),p+Offset(t*.48f,t*.53f),
            p+Offset(t*.27f,t*.38f),strokeWidth=t*.072f)
        drawLine(Color(0xFF6C513D),p+Offset(t*.54f,t*.48f),
            p+Offset(t*.77f,t*.34f),strokeWidth=t*.065f)
        // Multiple offset clumps, warm highlights and light gaps create a
        // hand-painted silhouette while keeping the actual terrain one tile.
        drawOval(dark,p+Offset(t*.10f+sway,t*.19f),
            Size(t*.55f,t*.47f))
        drawOval(dark,p+Offset(t*.42f+sway,t*.17f),
            Size(t*.53f,t*.45f))
        drawOval(leaf,p+Offset(t*.15f+sway,t*.09f),
            Size(t*.62f,t*.45f))
        drawOval(leafTop,p+Offset(t*.30f+sway,t*.08f),
            Size(t*.51f,t*.32f))
        drawOval(Color(0xFF83B57A),p+Offset(t*.26f+sway,t*.13f),
            Size(t*.23f,t*.10f))
        drawOval(Color(0xFF9ACC8A).copy(alpha=.78f),
            p+Offset(t*.55f+sway,t*.18f),Size(t*.17f,t*.08f))
        drawOval(Color(0xFF2E7454),p+Offset(t*.21f+sway,t*.43f),
            Size(t*.25f,t*.11f))
        if(variant==2) {
            drawCircle(Color(0xFFD6B979),t*.045f,
                p+Offset(t*.66f+sway,t*.40f))
            drawCircle(Color(0xFFE7CA8C),t*.035f,
                p+Offset(t*.36f+sway,t*.36f))
        }
    }

    private fun DrawScope.drawBoulder(p: Offset, t: Float, x: Int, y: Int) {
        drawOval(Color.Black.copy(alpha=.26f),p+Offset(t*.08f,t*.74f),
            Size(t*.88f,t*.17f))
        val outline=Path().apply {
            moveTo(p.x+t*.12f,p.y+t*.72f)
            lineTo(p.x+t*.17f,p.y+t*.38f)
            lineTo(p.x+t*.37f,p.y+t*.21f)
            lineTo(p.x+t*.69f,p.y+t*.26f)
            lineTo(p.x+t*.87f,p.y+t*.47f)
            lineTo(p.x+t*.82f,p.y+t*.79f)
            close()
        }
        drawPath(outline,Color(0xFF3E5960))
        val facets=Path().apply {
            moveTo(p.x+t*.20f,p.y+t*.42f)
            lineTo(p.x+t*.39f,p.y+t*.25f)
            lineTo(p.x+t*.66f,p.y+t*.31f)
            lineTo(p.x+t*.52f,p.y+t*.55f)
            close()
        }
        drawPath(facets,Color(0xFFC4D1C5))
        val shade=Path().apply {
            moveTo(p.x+t*.52f,p.y+t*.55f)
            lineTo(p.x+t*.66f,p.y+t*.31f)
            lineTo(p.x+t*.81f,p.y+t*.48f)
            lineTo(p.x+t*.77f,p.y+t*.72f)
            close()
        }
        drawPath(shade,Color(0xFF748A88))
        drawPath(Path().apply {
            moveTo(p.x+t*.20f,p.y+t*.42f)
            lineTo(p.x+t*.52f,p.y+t*.55f)
            lineTo(p.x+t*.28f,p.y+t*.75f)
            close()
        },Color(0xFF97AAA4))
        drawLine(Color(0xFF5F7978),p+Offset(t*.52f,t*.55f),
            p+Offset(t*.48f,t*.73f),strokeWidth=t*.032f)
        if((x+y)%4==0) {
            drawOval(Color(0xFF6CA38D),p+Offset(t*.20f,t*.55f),
                Size(t*.18f,t*.06f))
            drawOval(Color(0xFFD6DBCC),p+Offset(t*.35f,t*.30f),
                Size(t*.16f,t*.05f))
        }
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

    /** The same nine saved construction cells gain one cohesive hand-painted roof at completion. */
    private fun DrawScope.drawFinishedCompanionHouse(
        p:Offset,t:Float,style:String,phase:Float,
    ) {
        val pine=style=="pine"
        val roof=if(pine)Color(0xFF466F64) else Color(0xFFAC5C4E)
        val roofLight=if(pine)Color(0xFF7AA58B) else Color(0xFFD99A75)
        val roofShade=if(pine)Color(0xFF294C49) else Color(0xFF78473F)
        drawRoundRect(Color(0xFF302F2F).copy(alpha=.17f),
            p+Offset(t*.03f,t*.65f),Size(t*2.94f,t*2.31f),CornerRadius(t*.11f))
        // A complete three-tile façade sits beneath the overhanging roof.
        // The original rendering left the windows floating above bare terrain.
        // This remains decorative: the original 9 persisted structure cells
        // are still the sole authority for collision and construction.
        val plaster=if(pine)Color(0xFFD9E0CB) else Color(0xFFF0DCC0)
        val plasterShade=if(pine)Color(0xFFA2B6A7) else Color(0xFFC9AE90)
        val trim=if(pine)Color(0xFF607D72) else Color(0xFF9F7D62)
        drawRoundRect(plasterShade,p+Offset(t*.18f,t*1.16f),
            Size(t*2.64f,t*1.63f),CornerRadius(t*.13f))
        drawRoundRect(plaster,p+Offset(t*.24f,t*1.20f),
            Size(t*2.53f,t*1.49f),CornerRadius(t*.10f))
        drawRect(trim.copy(alpha=.32f),
            p+Offset(t*.24f,t*1.69f),Size(t*2.53f,t*.055f))
        // Quiet vertical support timbers and a simple stone plinth give the
        // cottage structure and scale, without cluttering its roof silhouette.
        for(dx in listOf(.27f,2.68f)) {
            drawRoundRect(trim,p+Offset(t*dx,t*1.62f),
                Size(t*.075f,t*1.10f),CornerRadius(t*.02f))
        }
        drawRoundRect(Color(0xFF89938B).copy(alpha=.75f),
            p+Offset(t*.16f,t*2.69f),Size(t*2.67f,t*.18f),
            CornerRadius(t*.05f))
        // A solid entrance, frame, recessed panels and a single brass knob.
        drawRoundRect(Color(0xFF5D5149),p+Offset(t*1.17f,t*1.89f),
            Size(t*.67f,t*.85f),CornerRadius(t*.10f))
        drawRoundRect(if(pine)Color(0xFF708676) else Color(0xFFBD8B64),
            p+Offset(t*1.24f,t*1.97f),
            Size(t*.53f,t*.74f),CornerRadius(t*.075f))
        drawRoundRect(Color(0xFF4D625C).copy(alpha=.34f),
            p+Offset(t*1.30f,t*2.06f),
            Size(t*.40f,t*.48f),CornerRadius(t*.06f))
        drawCircle(Color(0xFFE6C791),t*.033f,
            p+Offset(t*1.67f,t*2.38f))
        drawRoundRect(Color(0xFFB7AD94),p+Offset(t*1.04f,t*2.78f),
            Size(t*.92f,t*.13f),CornerRadius(t*.04f))
        // Broad gabled roof with overhang; the foreground door stays visible.
        val shape=Path().apply {
            moveTo(p.x-t*.04f,p.y+t*.95f)
            lineTo(p.x+t*1.50f,p.y+t*.18f)
            lineTo(p.x+t*3.04f,p.y+t*.95f)
            lineTo(p.x+t*2.86f,p.y+t*1.80f)
            lineTo(p.x+t*.14f,p.y+t*1.80f)
            close()
        }
        drawPath(shape,roof)
        val ridge=Path().apply {
            moveTo(p.x+t*.10f,p.y+t*.94f)
            lineTo(p.x+t*1.50f,p.y+t*.32f)
            lineTo(p.x+t*2.90f,p.y+t*.94f)
        }
        drawPath(ridge,roofLight,style=Stroke(t*.075f))
        drawLine(roofShade,p+Offset(t*.14f,t*1.70f),
            p+Offset(t*2.86f,t*1.70f),strokeWidth=t*.12f)
        repeat(4){i->
            val y=.96f+i*.19f
            drawLine(roofLight.copy(alpha=.35f),
                p+Offset(t*(.42f-i*.065f),t*y),
                p+Offset(t*(2.58f+i*.065f),t*y),
                strokeWidth=t*.032f)
        }
        // A protruding little chimney and a bright dormer.
        drawRect(Color(0xFF7D6057),
            p+Offset(t*2.25f,-t*.02f),Size(t*.36f,t*.60f))
        drawRect(roofLight,p+Offset(t*2.21f,-t*.08f),Size(t*.44f,t*.13f))
        drawRoundRect(Color(0xFF3D3F42),p+Offset(t*1.26f,t*.82f),
            Size(t*.50f,t*.48f),CornerRadius(t*.09f))
        drawRect(Color(0xFFFFE7B0),p+Offset(t*1.32f,t*.87f),
            Size(t*.38f,t*.31f))
        drawLine(Color(0xFF84664B),p+Offset(t*1.51f,t*.88f),
            p+Offset(t*1.51f,t*1.19f),strokeWidth=t*.055f)
        // Front-facing windows with planter boxes make this visibly lived in.
        for(x in houseWindowOffsets){
            drawRoundRect(Color(0xFF60473B),p+Offset(t*x,t*2.02f),
                Size(t*.32f,t*.40f),CornerRadius(t*.04f))
            drawRect(Color(0xFFF7D7A0),p+Offset(t*(x+.05f),t*2.07f),
                Size(t*.22f,t*.28f))
            drawLine(Color(0xFF856649),
                p+Offset(t*(x+.16f),t*2.07f),
                p+Offset(t*(x+.16f),t*2.35f),strokeWidth=t*.03f)
            drawRect(Color(0xFF805C43),p+Offset(t*(x-.05f),t*2.37f),
                Size(t*.43f,t*.13f))
            drawCircle(Color(0xFFDBB2AF),t*.055f,
                p+Offset(t*(x+.11f),t*2.38f))
            drawCircle(Color(0xFFBFE2A0),t*.06f,
                p+Offset(t*(x+.25f),t*2.38f))
        }
        drawRoundRect(roofLight,p+Offset(t*1.02f,t*2.00f),
            Size(t*.96f,t*.13f),CornerRadius(t*.04f))
        val gleam=(.55f+.45f*sin(phase*6.28318f))
        drawCircle(Color(0xFFFFE3AE).copy(alpha=.15f*gleam),
            t*.45f,p+Offset(t*1.49f,t*1.03f))
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

    private fun DrawScope.drawIslandLantern(p: Offset, t: Float, phase: Float) {
        drawOval(Color.Black.copy(alpha=.22f),p+Offset(t*.19f,t*.74f),
            Size(t*.63f,t*.14f))
        drawRect(Color(0xFF6D4F44),p+Offset(t*.43f,t*.46f),
            Size(t*.14f,t*.34f))
        drawRect(Color(0xFFB59B76),p+Offset(t*.28f,t*.28f),
            Size(t*.44f,t*.35f))
        drawRect(Color(0xFFFFE6A2),p+Offset(t*.34f,t*.32f),
            Size(t*.32f,t*.27f))
        drawCircle(Color(0xFFFFF1C9).copy(alpha=.62f+
            .20f*sin(phase*6.28318f)),t*.17f,
            p+Offset(t*.5f,t*.43f))
        drawRect(Color(0xFF59453B),p+Offset(t*.27f,t*.24f),
            Size(t*.47f,t*.09f))
        drawRect(Color(0xFF463B3C),p+Offset(t*.43f,t*.19f),
            Size(t*.14f,t*.09f))
    }

    private fun DrawScope.drawPetHouse(p: Offset, t: Float) {
        drawOval(Color.Black.copy(alpha=.26f),p+Offset(t*.07f,t*.76f),
            Size(t*.88f,t*.15f))
        drawRoundRect(Color(0xFF9F745A),p+Offset(t*.12f,t*.38f),
            Size(t*.76f,t*.44f),CornerRadius(t*.09f))
        drawRect(Color(0xFFEDCAA3),p+Offset(t*.19f,t*.46f),
            Size(t*.62f,t*.27f))
        // A broad triangle roof gives the hut a distinct silhouette at mobile scale.
        val roof=Path().apply{
            moveTo(p.x+t*.06f,p.y+t*.43f)
            lineTo(p.x+t*.50f,p.y+t*.10f)
            lineTo(p.x+t*.96f,p.y+t*.43f)
            close()
        }
        drawPath(roof,Color(0xFFB55E57))
        drawLine(Color(0xFFE7A17D),p+Offset(t*.50f,t*.12f),
            p+Offset(t*.84f,t*.38f),strokeWidth=t*.04f)
        drawRoundRect(Color(0xFF6B5147),p+Offset(t*.39f,t*.57f),
            Size(t*.24f,t*.25f),CornerRadius(t*.09f))
        drawCircle(Color(0xFFDEC8AD),t*.035f,p+Offset(t*.40f,t*.25f))
        drawCircle(Color(0xFFDEC8AD),t*.035f,p+Offset(t*.55f,t*.22f))
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

    /** A sculpted stone guardian with separate jaw, arm, runes, impact and
     * telegraphed strike. Visuals never hit or mint resources by themselves. */
    private fun DrawScope.drawGuardian(
        c:Offset,t:Float,hp:Int,phase:Float,hit:Float,heavyNext:Boolean,
    ) {
        val beat=(sin(phase*6.28318f)+1f)*.5f
        val shift=if(hit>0f)sin(hit*29f)*t*.06f else 0f
        val core=c+Offset(shift,-t*.05f+sin(phase*6.28318f)*t*.018f)
        drawOval(Color(0xB30D2D30),c+Offset(-t*.50f,t*.31f),
            Size(t*1.02f,t*.24f))
        val aura=if(heavyNext)Color(0xFFFF8D70) else Color(0xFFEAC891)
        drawCircle(Brush.radialGradient(
                listOf(aura.copy(alpha=.28f+.15f*beat),Color.Transparent),
                center=core,radius=t*.78f),
            t*.78f,core)
        // A rough four-pronged broken crown, not a second invisible enemy.
        val crest=Path().apply {
            moveTo(core.x-t*.37f,core.y-t*.25f)
            lineTo(core.x-t*.42f,core.y-t*.60f)
            lineTo(core.x-t*.22f,core.y-t*.42f)
            lineTo(core.x-t*.08f,core.y-t*.66f)
            lineTo(core.x+t*.08f,core.y-t*.66f)
            lineTo(core.x+t*.22f,core.y-t*.42f)
            lineTo(core.x+t*.42f,core.y-t*.60f)
            lineTo(core.x+t*.37f,core.y-t*.25f)
            close()
        }
        drawPath(crest,Color(0xFF708581))
        drawPath(crest,Color(0xFFB9AB88).copy(alpha=.46f),
            style=Stroke(t*.025f))
        // Individual physical stone limbs to read much larger than the slimes.
        drawRoundRect(Color(0xFF596A69),core+Offset(-t*.53f,-t*.18f),
            Size(t*.23f,t*.55f),CornerRadius(t*.07f))
        drawRoundRect(Color(0xFF596A69),core+Offset(t*.30f,-t*.18f),
            Size(t*.23f,t*.55f),CornerRadius(t*.07f))
        drawRoundRect(Color(0xFF778580),core+Offset(-t*.35f,-t*.29f),
            Size(t*.70f,t*.66f),CornerRadius(t*.15f))
        drawRoundRect(Color(0xFFA0AFA0),core+Offset(-t*.27f,-t*.30f),
            Size(t*.53f,t*.34f),CornerRadius(t*.085f))
        drawRoundRect(Color(0xFF3B494E),core+Offset(-t*.23f,-t*.18f),
            Size(t*.46f,t*.19f),CornerRadius(t*.04f))
        drawCircle(Color(0xFFFFBD79).copy(alpha=.75f+.20f*beat),
            t*.065f,core+Offset(-t*.115f,-t*.09f))
        drawCircle(Color(0xFFFFBD79).copy(alpha=.75f+.20f*beat),
            t*.065f,core+Offset(t*.115f,-t*.09f))
        drawRect(Color(0xFF4A585B),core+Offset(-t*.20f,t*.22f),
            Size(t*.40f,t*.08f))
        // Embedded rune mirrors the boss' saved HP and current warning state.
        val sigil=Path().apply {
            moveTo(core.x,core.y+t*.045f)
            lineTo(core.x+t*.075f,core.y+t*.145f)
            lineTo(core.x,core.y+t*.235f)
            lineTo(core.x-t*.075f,core.y+t*.145f)
            close()
        }
        drawPath(sigil,aura.copy(alpha=.67f+.30f*beat))
        if(heavyNext)drawCircle(Color(0xFFFF927E).copy(alpha=.35f+.28f*beat),
            t*.58f,core,style=Stroke(t*.075f))
        if(hit>0f)drawCircle(Color(0xFFFFEAC7).copy(alpha=hit*.65f),
            t*.42f,core,style=Stroke(t*.10f))
        drawRoundRect(Color(0xC52F2930),core+Offset(-t*.48f,-t*.77f),
            Size(t*.96f,t*.13f),CornerRadius(t*.06f))
        drawRoundRect(if(heavyNext)Color(0xFFFF947E) else Color(0xFFFFD18B),
            core+Offset(-t*.45f,-t*.74f),
            Size(t*.90f*(hp/IslandGuardianEncounter.hp.toFloat()).coerceIn(0f,1f),
                t*.075f),CornerRadius(t*.035f))
    }

    private fun DrawScope.drawSlime(
        c: Offset, t: Float, hp: Int, phase: Float, seed: Int, hit: Float = 0f,
    ) {
        val bob = sin(phase * 6.28318f + seed) * t * .05f
        val shake = if(hit>0f) sin(hit*31f)*t*.08f else 0f
        drawOval(Color.Black.copy(alpha = .25f), c + Offset(-t * .34f, t * .23f),
            Size(t * .69f, t * .17f))
        val p = c + Offset(-t * .32f+shake, -t * .22f + bob)
        drawRect(Color(0xFF4D3A5B), p + Offset(0f, t * .12f), Size(t * .65f, t * .40f))
        drawRect(Color(0xFF84658D), p + Offset(t * .09f, 0f), Size(t * .48f, t * .42f))
        drawRect(Color(0xFFAE8AAF), p + Offset(t * .17f, t * .04f), Size(t * .23f, t * .10f))
        if(hit>0f) {
            drawRect(Color(0xFFFFD9CC).copy(alpha=hit*.64f),
                p+Offset(t*.08f,t*.02f),Size(t*.5f,t*.48f))
        }
        drawRect(Color.White, p + Offset(t * .16f, t * .23f), Size(t * .09f, t * .10f))
        drawRect(Color.White, p + Offset(t * .43f, t * .23f), Size(t * .09f, t * .10f))
        drawRect(Color(0xFF31293A), p + Offset(t * .19f, t * .26f), Size(t * .04f, t * .06f))
        drawRect(Color(0xFF31293A), p + Offset(t * .46f, t * .26f), Size(t * .04f, t * .06f))
        drawRect(Color(0xFF532F41), c + Offset(-t * .31f, -t * .35f),
            Size(t * .62f * (hp / 4f).coerceIn(0f, 1f), t * .045f))
    }

    private fun DrawScope.drawPerson(
        c: Offset, t: Float, coat: Color, hair: Color, phase: Float,
        user: Boolean, walking: Boolean = false,
    ) {
        val stride = if(walking) sin(phase * 6.28318f * 13f)*t*.10f else 0f
        val bob = sin(phase * 6.28318f)*t*.023f +
            if(walking) abs(stride)*.23f else 0f
        val p = c + Offset(0f, bob)
        drawOval(Color.Black.copy(alpha = .25f), p + Offset(-t * .26f, t * .24f),
            Size(t * .52f, t * .14f))
        drawRect(Color(0xFF49465B), p + Offset(-t * .20f,t*.08f-stride),
            Size(t*.15f,t*.24f))
        drawRect(Color(0xFF49465B), p + Offset(t*.05f,t*.08f+stride),
            Size(t*.15f,t*.24f))
        drawRect(coat, p + Offset(-t * .24f, -t * .12f), Size(t * .48f, t * .32f))
        drawRect(coat.copy(alpha = .75f), p + Offset(-t*.32f,-t*.05f+stride*.5f),
            Size(t*.09f,t*.23f))
        drawRect(coat.copy(alpha = .75f), p + Offset(t*.23f,-t*.05f-stride*.5f),
            Size(t*.09f,t*.23f))
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

    private fun DrawScope.drawIslandAnimal(
        center: Offset, t: Float, animal: IslandAnimal, phase: Float,
    ) {
        val bob = sin(phase * 6.28318f + animal.id.toFloat()) * t * .028f
        val p = center + Offset(0f,bob)
        drawOval(Color(0x660E332E),p+Offset(-t*.32f,t*.20f),
            Size(t*.64f,t*.14f))
        when(animal.species) {
            "hare" -> {
                val fur=Color(0xFFE6D7BA)
                drawRoundRect(fur,p+Offset(-t*.24f,-t*.44f),
                    Size(t*.16f,t*.32f),CornerRadius(t*.08f))
                drawRoundRect(fur,p+Offset(t*.08f,-t*.45f),
                    Size(t*.16f,t*.33f),CornerRadius(t*.08f))
                drawRect(Color(0xFFEBA7A7),p+Offset(-t*.18f,-t*.39f),
                    Size(t*.055f,t*.20f))
                drawRect(Color(0xFFEBA7A7),p+Offset(t*.14f,-t*.39f),
                    Size(t*.055f,t*.20f))
                drawOval(fur,p+Offset(-t*.30f,-t*.18f),Size(t*.60f,t*.47f))
                drawOval(Color(0xFFFFF5DE),p+Offset(-t*.15f,-t*.03f),
                    Size(t*.30f,t*.22f))
                drawCircle(Color(0xFF303941),t*.039f,p+Offset(-t*.13f,-t*.08f))
                drawCircle(Color(0xFF303941),t*.039f,p+Offset(t*.13f,-t*.08f))
                drawCircle(Color(0xFFD88E94),t*.045f,p+Offset(0f,t*.03f))
            }
            "duck" -> {
                drawOval(Color(0xFFF3E8CB),p+Offset(-t*.34f,-t*.19f),
                    Size(t*.68f,t*.50f))
                drawOval(Color(0xFFD8CCB1),p+Offset(-t*.29f,-t*.04f),
                    Size(t*.30f,t*.26f))
                drawCircle(Color(0xFFF9F4DB),t*.21f,p+Offset(t*.06f,-t*.19f))
                drawOval(Color(0xFFE8AA52),p+Offset(t*.15f,-t*.16f),
                    Size(t*.28f,t*.13f))
                drawCircle(Color(0xFF353A3A),t*.039f,p+Offset(t*.12f,-t*.25f))
                drawRect(Color(0xFFCD8B44),p+Offset(-t*.20f,t*.23f),
                    Size(t*.11f,t*.055f))
                drawRect(Color(0xFFCD8B44),p+Offset(t*.12f,t*.23f),
                    Size(t*.11f,t*.055f))
            }
            "fox" -> {
                drawOval(Color(0xFFBF6B42),p+Offset(-t*.43f,-t*.08f),
                    Size(t*.44f,t*.28f))
                drawOval(Color(0xFFF2DEC1),p+Offset(-t*.47f,-t*.07f),
                    Size(t*.18f,t*.17f))
                drawOval(Color(0xFFD98047),p+Offset(-t*.23f,-t*.19f),
                    Size(t*.56f,t*.47f))
                drawRoundRect(Color(0xFF7B4C44),p+Offset(-t*.18f,-t*.42f),
                    Size(t*.17f,t*.25f),CornerRadius(t*.035f))
                drawRoundRect(Color(0xFF7B4C44),p+Offset(t*.14f,-t*.42f),
                    Size(t*.17f,t*.25f),CornerRadius(t*.035f))
                drawOval(Color(0xFFF7E8CD),p+Offset(-t*.18f,-t*.02f),
                    Size(t*.38f,t*.25f))
                drawCircle(Color(0xFF30363A),t*.047f,p+Offset(-t*.10f,-t*.09f))
                drawCircle(Color(0xFF30363A),t*.047f,p+Offset(t*.14f,-t*.09f))
                drawCircle(Color(0xFF44393B),t*.042f,p+Offset(t*.01f,t*.045f))
            }
        }
        if(animal.befriended) {
            // Golden companion marker is tied to saved friendship.
            val sparkle=p+Offset(t*.27f,-t*.36f)
            drawCircle(Color(0xDFF9DA87),t*.15f,sparkle)
            drawCircle(Color(0xFFFFF7D4),t*.07f,sparkle)
        }
    }

    private fun DrawScope.drawFallen(c: Offset, t: Float) {
        drawOval(Color.Black.copy(alpha = .27f), c + Offset(-t * .31f, t * .13f), Size(t * .64f, t * .14f))
        drawRect(Color(0xFF87A6BB), c + Offset(-t * .27f, -t * .06f), Size(t * .53f, t * .16f))
        drawRect(Color(0xFFF3D4B5), c + Offset(t * .11f, -t * .10f), Size(t * .19f, t * .17f))
        drawRect(Color(0xFFFFAAA7), c + Offset(-t * .04f, -t * .38f), Size(t * .09f, t * .12f))
    }
}
