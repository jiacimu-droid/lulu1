package com.jiacimu.lulu.games

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.runtime.remember
import com.jiacimu.lulu.data.MigratedDomainStores
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** One art legend for chest shells, mid-opening glow and treasure appraisals. */
internal fun relicGradeColor(grade:Int):Color=when(grade) {
    0->Color(0xFFB7ADA3) // weathered silver
    1->Color(0xFF7EDCC1) // mint
    2->Color(0xFF70CFFF) // azure
    3->Color(0xFFC69BFF) // violet
    4->Color(0xFFFFD37A) // gold
    5->Color(0xFFFF7783) // crimson
    else->Color(0xFFFFB6F5) // prismatic
}

/** Local procedural art: no remote images, font downloads, or expensive per-frame textures. */
@Composable
internal fun RelicEscapeWorldMap(
    run:RelicRun,selected:Pair<Int,Int>,onSelect:(Int,Int)->Unit,fullscreen:Boolean=false,
    focus: Pair<Int, Int> = run.hero.x to run.hero.y,
    openingReceipt: RelicChestReceipt? = null,
    openingProgress: Float = 0f,
) {
    // Only the player's selected, already-visible objective gets a route hint.
    // The path is a read-only projection of discovered traversable cells.
    val tension = remember(run) { relicTension(run) }
    val knownRoute = remember(run, selected) {
        val target = RelicObjectiveGuide.selectedVisible(run, selected.first, selected.second)
        target?.let { RelicObjectiveGuide.knownRouteTiles(run, it) }
    }
    // Index physical entities once per authoritative snapshot, not once per drawn tile.
    val chestByCell = remember(run.chests,run.w) { run.chests.associateBy { it.y*run.w+it.x } }
    val siteByCell = remember(run.sites,run.w) { run.sites.associateBy { it.y*run.w+it.x } }
    val dropsByCell = remember(run.drops,run.w) { run.drops.groupBy { it.y*run.w+it.x } }
    val names = run.allies.associate { ally ->
        ally.id to MigratedDomainStores.characters.get(ally.id).displayName
    }
    val namePaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    val start = Offset(run.hero.x.toFloat(), run.hero.y.toFloat())
    val heroVisual = remember(run.id) { Animatable(start, Offset.VectorConverter) }
    val previousHeroTile = remember(run.id) { arrayOf(start) }
    LaunchedEffect(run.id, run.hero.x, run.hero.y) {
        val dest = Offset(run.hero.x.toFloat(), run.hero.y.toFloat())
        val distance = abs(previousHeroTile[0].x - dest.x) +
            abs(previousHeroTile[0].y - dest.y)
        previousHeroTile[0] = dest
        // Display interpolation only; collision, loot and monster turns stay authoritative.
        if (distance <= 1f) heroVisual.animateTo(dest, tween(175, easing = LinearEasing))
        else heroVisual.snapTo(dest)
    }
    val renderFocus = if (focus == (run.hero.x to run.hero.y)) heroVisual.value
        else Offset(focus.first.toFloat(), focus.second.toFloat())
    val currentRenderFocus = rememberUpdatedState(renderFocus)
    val shimmer=rememberInfiniteTransition(label="ancient-ruins")
        .animateFloat(.35f,1f,infiniteRepeatable(tween(2000),RepeatMode.Reverse),label="rune-glow").value
    val flicker=rememberInfiniteTransition(label="relic-light")
        .animateFloat(0f,1f,infiniteRepeatable(tween(3600),RepeatMode.Reverse),label="dust-drift").value
    Surface(
        modifier=if(fullscreen)Modifier.fillMaxSize()else Modifier,
        shape=RoundedCornerShape(24.dp),
        color=Color(0xFF080D15),
        border=BorderStroke(1.dp,Color(0xFF586474)),
        shadowElevation=12.dp,
    ) {
        Canvas(
            (if(fullscreen)Modifier.fillMaxSize()else Modifier.fillMaxWidth().aspectRatio(run.w.toFloat()/run.h))
                .pointerInput(run.id, run.w, run.h, fullscreen, focus, onSelect) {
                    detectTapGestures { tap->
                        val camera = relicMapCamera(size.width.toFloat(), size.height.toFloat(),
                            run.w, run.h, fullscreen, currentRenderFocus.value.x, currentRenderFocus.value.y)
                        camera.tileAt(tap.x, tap.y, run.w, run.h)?.let { (x, y) -> onSelect(x, y) }
                    }
                }
        ) {
            val camera = relicMapCamera(size.width, size.height, run.w, run.h,
                fullscreen, renderFocus.x, renderFocus.y)
            val t = camera.tileSize
            val ox = camera.offsetX
            val oy = camera.offsetY
            translate(ox,oy) {
            fun cell(x:Int,y:Int)=Offset(x*t,y*t)
            fun center(x:Int,y:Int)=Offset((x+.5f)*t,(y+.5f)*t)
            val theme=when(run.level%3){
                1->Color(0xFF394B53) // flooded stone hall
                2->Color(0xFF45444D) // abandoned crypt
                else->Color(0xFF415047) // fungal vault
            }
            drawRect(Color(0xFF070D16))
            for(y in 0 until run.h)for(x in 0 until run.w) {
                val p=cell(x,y)
                val c=center(x,y)
                val visible=run.visible(x,y)
                if(!visible) {
                    drawRect(Color(0xFF0C1320),p,Size(t+.4f,t+.4f))
                    if((x*19+y*31+run.seed)%13==0)
                        drawCircle(Color(0xFF4A5A6C).copy(alpha=.13f+flicker*.06f),
                            t*.07f,c)
                    continue
                }
                val distance=RelicEscapeRules.distance(x,y,run.hero.x,run.hero.y)
                val lit=distance<=4 || run.flareTurns>0 &&
                    RelicEscapeRules.distance(x,y,run.flareX,run.flareY)<=6
                val kind=run.tile(x,y)
                val variation=(abs(x*17+y*31+run.seed)%5)*.018f
                val base=when(kind) {
                    1->Color(0xFF283441)
                    2->Color(0xFF314C44)
                    3->Color(0xFF575058)
                    else->theme.copy(red=(theme.red+variation).coerceAtMost(1f),
                        green=(theme.green+variation*.5f).coerceAtMost(1f))
                }
                drawRect(base,p,Size(t+.6f,t+.6f))
                // Offset paving joints and eroded scratches provide depth even without image assets.
                drawLine(Color.White.copy(alpha=.065f),p+Offset(1f,t*.12f),
                    p+Offset(t*.85f,t*.11f),strokeWidth=t*.032f)
                drawLine(Color.Black.copy(alpha=.20f),p+Offset(t*.06f,t*.93f),
                    p+Offset(t*.95f,t*.91f),strokeWidth=t*.055f)
                if((x*13+y*7+run.seed)%4==0) {
                    drawLine(Color(0xFF0D1920).copy(alpha=.40f),p+Offset(t*.2f,t*.35f),
                        p+Offset(t*.53f,t*.52f),strokeWidth=t*.045f)
                    drawLine(Color(0xFF0D1920).copy(alpha=.4f),p+Offset(t*.53f,t*.52f),
                        p+Offset(t*.48f,t*.8f),strokeWidth=t*.035f)
                }
                if(kind==1) {
                    drawRect(Color(0xFF111B28).copy(alpha=.8f),
                        p+Offset(t*.13f,t*.18f),Size(t*.86f,t*.82f))
                    drawRoundRect(Color(0xFF56616F),p+Offset(t*.02f,t*.01f),
                        Size(t*.89f,t*.70f),CornerRadius(t*.10f))
                    drawLine(Color(0xFF293744),p+Offset(t*.09f,t*.34f),
                        p+Offset(t*.87f,t*.34f),strokeWidth=t*.10f)
                    drawLine(Color(0xFF7C8792).copy(alpha=.45f),
                        p+Offset(t*.09f,t*.08f),p+Offset(t*.75f,t*.08f),strokeWidth=t*.045f)
                } else if(kind==2) {
                    drawCircle(Color(0xFF8DBD91).copy(alpha=.26f+shimmer*.1f),t*.37f,c)
                    repeat(3){i->
                        val px=p.x+t*(.18f+i*.26f)
                        drawCircle(Color(0xFFB0E9A9).copy(alpha=.16f+shimmer*.14f),
                            t*(.055f+i*.014f),Offset(px,p.y+t*(.41f+i*.11f)))
                    }
                } else if(kind==3) {
                    drawRoundRect(Color(0xFF7B777C),p+Offset(t*.16f,t*.31f),
                        Size(t*.65f,t*.42f),CornerRadius(t*.10f))
                    drawLine(Color(0xFFD0C5B1).copy(alpha=.35f),
                        p+Offset(t*.19f,t*.33f),p+Offset(t*.66f,t*.33f),strokeWidth=t*.045f)
                }
                val site=siteByCell[y*run.w+x]
                if(site!=null) {
                    if(site.kind=="supply") {
                        val tint=if(site.spent)Color(0xFF6B8382) else Color(0xFF83E6CB)
                        drawCircle(tint.copy(alpha=.12f+shimmer*.10f),t*.53f,c)
                        drawRoundRect(Color(0xFF233E44),p+Offset(t*.18f,t*.27f),
                            Size(t*.64f,t*.51f),CornerRadius(t*.08f))
                        drawRoundRect(tint,p+Offset(t*.21f,t*.30f),
                            Size(t*.58f,t*.42f),CornerRadius(t*.07f))
                        drawRect(Color(0xFF17474B),p+Offset(t*.45f,t*.33f),
                            Size(t*.10f,t*.36f))
                    } else if(site.kind=="fountain") {
                        val tint=if(site.spent)Color(0xFF6E8B8A) else Color(0xFF78D8FC)
                        drawCircle(tint.copy(alpha=.14f+shimmer*.15f),t*.58f,c)
                        drawCircle(Color(0xFF263F52),t*.40f,c)
                        drawCircle(tint,t*.31f,c,style=Stroke(t*.085f))
                        drawCircle(tint.copy(alpha=.60f),t*.22f,c)
                    } else if(site.kind=="altar") {
                        val gold=if(site.spent)Color(0xFF778188)else Color(0xFFECB879)
                        drawCircle(gold.copy(alpha=if(site.spent).11f else .13f+shimmer*.16f),
                            t*.55f,c)
                        drawCircle(gold.copy(alpha=.7f),t*.33f,c,style=Stroke(t*.055f))
                        drawCircle(gold.copy(alpha=.6f),t*.20f,c,style=Stroke(t*.06f))
                        val diamond=Path().apply{
                            moveTo(c.x,c.y-t*.26f);lineTo(c.x+t*.14f,c.y)
                            lineTo(c.x,c.y+t*.26f);lineTo(c.x-t*.14f,c.y);close()
                        }
                        drawPath(diamond,gold)
                    } else if(!site.spent && distance<=3) {
                        val red=Color(0xFFE79682)
                        drawCircle(red.copy(alpha=.12f+shimmer*.08f),t*.43f,c)
                        drawCircle(red.copy(alpha=.65f),t*.32f,c,style=Stroke(t*.05f))
                        drawLine(red,c+Offset(-t*.19f,-t*.18f),
                            c+Offset(t*.19f,t*.18f),strokeWidth=t*.045f)
                        drawLine(red,c+Offset(t*.19f,-t*.18f),
                            c+Offset(-t*.19f,t*.18f),strokeWidth=t*.045f)
                    }
                }
                val chest=chestByCell[y*run.w+x]
                if(chest!=null) {
                    if(!chest.opened && chest.id in run.playerChestMarks) {
                        drawCircle(Color(0xFFFFE4A8).copy(alpha=.18f+shimmer*.25f),
                            t*.74f,c,style=Stroke(t*.080f))
                        drawCircle(Color(0xFFFFE4A8),t*.10f,c+Offset(t*.29f,-t*.31f))
                    }
                    val progress=run.chestProgress[chest.id] ?: 0
                    val required=RelicEscapeRules.openSteps(chest)
                    // A higher-rarity beam is revealed only after genuine persisted effort.
                    // Until halfway, the treasure's grade remains concealed from the player.
                    val revealUpgrade=!chest.opened && progress>=required/2
                    val revealedGrade=if(revealUpgrade)
                        RelicEscapeRules.treasure(run,chest).grade.takeIf{it in 0..6}
                        else null
                    val committedGrade=if(chest.opened && openingReceipt?.chestId==chest.id)
                        openingReceipt.loot.grade.takeIf{it in 0..6} else null
                    val glow=relicGradeColor(committedGrade ?: revealedGrade ?: chest.visibleGrade)
                    if(!chest.opened) {
                        // Show recognizable, tier-specific treasure instead of one generic brown box.
                        val metal = when(chest.visibleGrade) {
                            0,1 -> Color(0xFF495864)
                            2 -> Color(0xFF25495D)
                            3 -> Color(0xFF493C68)
                            4 -> Color(0xFF645036)
                            5 -> Color(0xFF663747)
                            else -> Color(0xFF654B70)
                        }
                        drawCircle(Brush.radialGradient(
                            listOf(glow.copy(alpha=.19f+shimmer*.19f),Color.Transparent),
                            center=c,radius=t*.80f),t*.80f,c)
                        drawOval(Color.Black.copy(alpha=.35f),
                            p+Offset(t*.08f,t*.76f),Size(t*.85f,t*.14f))
                        drawRoundRect(Color(0xFF151A23),p+Offset(t*.10f,t*.32f),
                            Size(t*.82f,t*.49f),CornerRadius(t*.10f))
                        drawRoundRect(metal,p+Offset(t*.14f,t*.26f),
                            Size(t*.73f,t*.49f),CornerRadius(t*.08f))
                        drawRoundRect(glow.copy(alpha=.78f),p+Offset(t*.16f,t*.28f),
                            Size(t*.69f,t*.11f),CornerRadius(t*.03f))
                        drawLine(glow.copy(alpha=.85f),p+Offset(t*.20f,t*.69f),
                            p+Offset(t*.81f,t*.69f),strokeWidth=t*.025f)
                        drawRelicFacet(c+Offset(0f,t*.065f),
                            t*(if(chest.visibleGrade>=4).25f else .18f),
                            glow, chest.visibleGrade>=4)
                        if(progress>0) {
                            // Real opening progress follows the stored world action count.
                            drawRoundRect(Color(0xE3131C2A),p+Offset(t*.12f,t*.82f),
                                Size(t*.77f,t*.11f),CornerRadius(t*.04f))
                            drawRoundRect(glow,p+Offset(t*.14f,t*.84f),
                                Size(t*.73f*progress.toFloat()/required,t*.07f),
                                CornerRadius(t*.025f))
                            if(revealUpgrade && revealedGrade!=null &&
                                revealedGrade>chest.visibleGrade) {
                                drawCircle(glow.copy(alpha=.30f+shimmer*.26f),t*.73f,c,
                                    style=Stroke(t*.065f))
                            }
                        }
                        if(chest.visibleGrade>=4) {
                            // Crown finials make the rarest chest identifiable even while moving.
                            for(i in -1..1) {
                                val x0=c.x+i*t*.23f
                                drawCircle(Color(0xFFFFF1C8).copy(alpha=.68f+shimmer*.28f),
                                    t*.044f,Offset(x0,p.y+t*.23f))
                                drawLine(glow,Offset(x0,p.y+t*.26f),
                                    Offset(c.x+i*t*.13f,p.y+t*.40f),strokeWidth=t*.028f)
                            }
                        }
                    } else {
                        // The map's opening glow is driven by the exact committed
                        // receipt shown in the ceremony, never by an invented drop.
                        if (RelicChestPresentation.isCurrentOpening(run, chest.id, openingReceipt)) {
                            drawCircle(glow.copy(alpha=.18f+shimmer*.25f),t*.70f,c)
                            drawCircle(glow.copy(alpha=.72f),t*.11f,c+Offset(0f,-t*.30f))
                        }
                        drawRoundRect(Color(0xFF705B50),p+Offset(t*.17f,t*.52f),
                            Size(t*.66f,t*.21f),CornerRadius(t*.06f))
                        val lidLift = relicChestLidLift(run, chest.id, openingReceipt, openingProgress)
                        // The lid stays on the actual chest tile and follows the same
                        // committed-receipt clock as the reward ceremony.
                        // Layered hinged lid: shadow, wooden face, brass rim and rivets.
                        // All offsets use the existing committed-event clock; no loot state is touched.
                        val lidRise = t * .23f * lidLift
                        val lidTop = p + Offset(t*.16f, t*.29f - lidRise)
                        drawRoundRect(Color(0xFF231B22).copy(alpha=.50f),
                            lidTop + Offset(t*.035f,t*.075f),
                            Size(t*.72f,t*.21f),CornerRadius(t*.045f))
                        drawRoundRect(Color(0xFF493128),lidTop,
                            Size(t*.69f,t*.19f),CornerRadius(t*.045f))
                        drawRoundRect(Color(0xFF9B7245),lidTop+Offset(t*.025f,t*.018f),
                            Size(t*.64f,t*.12f),CornerRadius(t*.025f))
                        drawLine(Color(0xFFE8BF76),
                            lidTop+Offset(t*.05f,t*.045f),
                            lidTop+Offset(t*.63f,t*.045f),strokeWidth=t*.022f)
                        drawLine(Color(0xFF4B3430),
                            lidTop+Offset(t*.08f,t*.14f),
                            lidTop+Offset(t*.60f,t*.14f),strokeWidth=t*.025f)
                        drawCircle(Color(0xFFF5D18C),t*.023f,
                            lidTop+Offset(t*.10f,t*.082f))
                        drawCircle(Color(0xFFF5D18C),t*.023f,
                            lidTop+Offset(t*.59f,t*.082f))
                        // The latch remains attached to the lid as it opens.
                        drawRoundRect(Color(0xFFE4B86D),
                            lidTop+Offset(t*.305f,t*.105f),
                            Size(t*.085f,t*.09f),CornerRadius(t*.015f))
                    }
                }
                dropsByCell[y*run.w+x].orEmpty().forEach{drop->
                    val tint=when(drop.loot.tier) {
                        RelicTier.COMMON -> Color(0xFFE1D5B9)
                        RelicTier.RARE -> Color(0xFF8EE2F4)
                        RelicTier.PRECIOUS -> Color(0xFFFFDC84)
                    }
                    val gemAt=c+Offset(t*.22f,t*.20f)
                    drawCircle(tint.copy(alpha=.14f+shimmer*.17f),t*.37f,gemAt)
                    drawRelicFacet(gemAt,
                        t*(if(drop.loot.tier==RelicTier.PRECIOUS).19f else .13f),
                        tint,drop.loot.tier==RelicTier.PRECIOUS)
                }
                if(x==run.exitX && y==run.exitY) {
                    // An actual carved extraction gate, not a generic green
                    // debug circle. It occupies the same real saved exit cell.
                    val jade=Color(0xFF83F2D3)
                    val stone=Color(0xFF829D98)
                    drawCircle(Brush.radialGradient(
                        listOf(jade.copy(alpha=.22f+shimmer*.25f),Color.Transparent),
                        center=c,radius=t*.80f),t*.80f,c)
                    drawOval(Color(0xAA071923),p+Offset(t*.07f,t*.79f),
                        Size(t*.87f,t*.16f))
                    drawRoundRect(Color(0xFF334B55),p+Offset(t*.12f,t*.17f),
                        Size(t*.77f,t*.68f),CornerRadius(t*.13f))
                    drawRoundRect(stone,p+Offset(t*.17f,t*.11f),
                        Size(t*.67f,t*.71f),CornerRadius(t*.17f))
                    drawRoundRect(Color(0xFF1C4550),p+Offset(t*.28f,t*.26f),
                        Size(t*.45f,t*.52f),CornerRadius(t*.15f))
                    drawRoundRect(jade.copy(alpha=.34f+shimmer*.28f),
                        p+Offset(t*.32f,t*.27f),Size(t*.36f,t*.50f),
                        CornerRadius(t*.14f))
                    drawLine(jade.copy(alpha=.75f),p+Offset(t*.36f,t*.28f),
                        p+Offset(t*.36f,t*.68f),strokeWidth=t*.035f)
                    drawLine(Color(0xFFE2D5B4),p+Offset(t*.17f,t*.22f),
                        p+Offset(t*.30f,t*.22f),strokeWidth=t*.043f)
                    drawLine(Color(0xFFE2D5B4),p+Offset(t*.69f,t*.22f),
                        p+Offset(t*.84f,t*.22f),strokeWidth=t*.043f)
                    val rune=Path().apply {
                        moveTo(c.x,c.y-t*.27f)
                        lineTo(c.x+t*.12f,c.y-t*.10f)
                        lineTo(c.x,c.y+t*.08f)
                        lineTo(c.x-t*.12f,c.y-t*.10f)
                        close()
                    }
                    drawPath(rune,Color(0xFFD8FFF0).copy(alpha=.55f+shimmer*.40f))
                    drawCircle(jade.copy(alpha=.64f+shimmer*.28f),
                        t*.50f,c,style=Stroke(t*.045f))
                    drawCircle(Color.White.copy(alpha=.42f+shimmer*.36f),
                        t*.065f,c+Offset(t*.26f,-t*.32f))
                    if(run.phase==RelicPhase.EXTRACT) {
                        // Three actual saved escape turns; never invent elapsed seconds.
                        for(i in 0 until 3) {
                            drawCircle(if(i < run.extraction) Color(0xFFD8FFF0)
                                else Color(0xFF30474E),t*.051f,
                                p+Offset(t*(.33f+i*.17f),t*.09f))
                        }
                    }
                }
                if(!lit)drawRect(Color(0xFF090E1B).copy(alpha=.42f),
                    p,Size(t+.3f,t+.3f))
                if(x==selected.first && y==selected.second) {
                    drawRect(Color(0xFFF9DC99).copy(alpha=.96f),
                        p+Offset(t*.03f,t*.03f),
                        Size(t*.94f,t*.94f),style=Stroke(t*.055f))
                }
            }
            // Draw the discovered route over the terrain, below actors and effects.
            // Never invent a route through fog, enemies, traps or corrosive tiles.
            knownRoute?.takeIf { it.size > 1 }?.let { route ->
                val ink = Color(0xFFB8E2D9).copy(alpha=.48f)
                route.zipWithNext().forEach { (from, to) ->
                    drawLine(ink, center(from.first, from.second),
                        center(to.first, to.second), strokeWidth=t*.085f)
                }
                route.drop(1).forEach { (x,y) ->
                    drawCircle(Color(0xFFE1F7E9).copy(alpha=.62f),
                        t*.065f,center(x,y))
                }
            }
            if(run.flareTurns>0) {
                val at=center(run.flareX,run.flareY)
                drawCircle(Brush.radialGradient(listOf(Color(0xFFFEE7AB).copy(alpha=.27f*shimmer),
                    Color.Transparent),center=at,radius=t*3.8f),t*3.8f,at)
                drawCircle(Color(0xFFFFEAB7),t*.20f,at)
                drawCircle(Color.White.copy(alpha=.55f),t*.09f,at)
            }
            if(run.lureTurns>0) {
                val at=center(run.lureX,run.lureY)
                drawCircle(Color(0xFFB2A4FF).copy(alpha=.20f+shimmer*.26f),
                    t*.5f,at,style=Stroke(t*.075f))
                drawCircle(Color(0xFFD1C6FF),t*.11f,at)
            }
            run.mobs.filter{it.hp>0 && run.visible(it.x,it.y)}.forEach{mob->
                val p=center(mob.x,mob.y)
                drawCircle(Color.Black.copy(alpha=.4f),t*.35f,p+Offset(0f,t*.13f))
                if(mob.windup) {
                    drawCircle(Color(0xFFFF7183).copy(alpha=.16f+shimmer*.36f),
                        t*.64f,p,style=Stroke(t*.10f))
                    drawCircle(Color(0xFFFF7183).copy(alpha=.28f),t*.52f,p)
                } else if(mob.alert>0)drawCircle(Color(0xFFF3A6A1).copy(alpha=.35f),
                    t*.47f,p,style=Stroke(t*.035f))
                drawRelicMonsterSprite(p,t,mob.kind,shimmer)
                // The monster silhouette and its bar reflect its *real* kind
                // and difficulty; the old generic circles used a wrong 5/9 HP cap.
                val maxHp=(if(mob.kind==3)12+run.level*2
                    else 7+mob.kind*3+run.level)+run.difficulty.monsterHp
                drawRoundRect(Color(0xE30B1420),p+Offset(-t*.32f,-t*.49f),
                    Size(t*.64f,t*.095f),CornerRadius(t*.025f))
                drawRoundRect(if(mob.windup)Color(0xFFFF667B) else Color(0xFFE49C83),
                    p+Offset(-t*.30f,-t*.47f),
                    Size(t*.60f*(mob.hp.toFloat()/maxHp.coerceAtLeast(1)).coerceIn(0f,1f),
                        t*.055f),CornerRadius(t*.02f))
            }
            run.allies.filter{run.visible(it.x,it.y)}.forEachIndexed{index,ally->
                val p=center(ally.x,ally.y)
                drawCircle(Color.Black.copy(alpha=.32f),t*.39f,p+Offset(t*.03f,t*.13f))
                val cape=if(index%2==0)Color(0xFF4C9CAD)else Color(0xFF7189B3)
                val cloak=Path().apply{
                    moveTo(p.x,p.y-t*.14f);lineTo(p.x-t*.30f,p.y+t*.30f)
                    lineTo(p.x+t*.30f,p.y+t*.30f);close()
                }
                drawPath(cloak,cape)
                drawCircle(if(ally.hp>0)Color(0xFFC1E5E9)else Color(0xFF5E7784),t*.24f,p)
                drawCircle(Color.White.copy(alpha=.84f),t*.066f,p+Offset(t*.09f,-t*.065f))
                if(ally.hp<=0) {
                    drawLine(Color(0xFFF9A5AD),p+Offset(-t*.30f,-t*.3f),
                        p+Offset(t*.30f,t*.3f),strokeWidth=t*.075f)
                }
            }
            val hero=Offset((heroVisual.value.x+.5f)*t,(heroVisual.value.y+.5f)*t)
            if(run.guardedBy.isNotBlank() && run.allies.any { it.id==run.guardedBy && it.hp>0 }) {
                drawCircle(Color(0xFF98EFFF).copy(alpha=.22f+shimmer*.23f),
                    t*.57f,hero,style=Stroke(t*.074f))
            }
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFD7A5).copy(alpha=.35f),
                Color.Transparent),center=hero,radius=t*1.0f),t*1.0f,hero)
            drawCircle(Color(0xFF07131E).copy(alpha=.50f),t*.42f,hero+Offset(t*.06f,t*.15f))
            val cape=Path().apply{
                moveTo(hero.x,hero.y-t*.20f);lineTo(hero.x-t*.31f,hero.y+t*.32f)
                lineTo(hero.x+t*.31f,hero.y+t*.32f);close()
            }
            drawPath(cape,Color(0xFF4F5279))
            drawCircle(if(run.hero.hp>0)Color(0xFFFFE6BC)else Color(0xFFF2A5AF),t*.27f,hero)
            drawCircle(Color.White,t*.065f,hero+Offset(t*.11f,-t*.10f))
            // Actor labels are drawn last so terrain, monsters and overlapping actors
            // cannot erase identity. They are display projections of the real roster.
            fun label(text: String, x: Int, y: Int, color: Int,
                below: Boolean = false, visual: Offset? = null) {
                val p = visual ?: center(x, y)
                namePaint.textSize = 11.sp.toPx()
                namePaint.textAlign = android.graphics.Paint.Align.CENTER
                namePaint.isFakeBoldText = true
                val baseline = p.y + if (below) t * .75f else -t * .55f
                val half = namePaint.measureText(text) / 2f + 5.dp.toPx()
                drawRoundRect(Color(0xEE08131E), Offset(p.x - half, baseline + namePaint.ascent() - 3.dp.toPx()),
                    Size(half * 2, namePaint.descent() - namePaint.ascent() + 6.dp.toPx()), CornerRadius(4.dp.toPx()))
                namePaint.color = color
                drawContext.canvas.nativeCanvas.drawText(text, p.x, baseline, namePaint)
            }
            run.allies.filter { run.visible(it.x, it.y) }.forEach { ally ->
                label(names[ally.id].orEmpty(), ally.x, ally.y, android.graphics.Color.rgb(129, 232, 197),
                    below = ally.x == run.hero.x && ally.y == run.hero.y)
            }
            drawCircle(Color(0xFFFFD894), t * .43f, hero, style = Stroke(2.dp.toPx()))
            label("你", run.hero.x, run.hero.y,
                android.graphics.Color.rgb(255, 216, 148), visual = hero)
            if (run.visible(run.exitX, run.exitY))
                label("撤离门", run.exitX, run.exitY, android.graphics.Color.rgb(129, 232, 197), below = true)
            // Drifting dust and sparse fireflies soften the grid without masking real objects.
            repeat(12){i->
                val x=((i*43+run.seed.toLong()*7).let{abs(it)%107}/107f)
                val y=((i*37+run.seed.toLong()*3).let{abs(it)%101}/101f)
                val center=Offset(size.width*(x*.96f+.02f),
                    size.height*(y*.95f+.025f)+flicker*t*.20f)
                drawCircle(Color(0xFFEBE0BB).copy(alpha=.045f+shimmer*.075f),
                    t*(.026f+i%3*.011f),center)
            }
            if(tension.urgent) {
                val tint = if(tension.level==RelicTensionLevel.DANGER ||
                    tension.level==RelicTensionLevel.DOWNED)
                    Color(0xFFD36C75) else Color(0xFF7BC5B3)
                // Only local, restrained rings: no flashing full-screen vignette.
                drawCircle(tint.copy(alpha=.24f+shimmer*.12f),
                    t*.62f,hero,style=Stroke(t*.045f))
                drawCircle(tint.copy(alpha=.08f+shimmer*.05f),
                    t*.87f,hero,style=Stroke(t*.06f))
            }
            }
        }
    }
}

/** Locally drawn facet: no heavy textures, network assets or extra render engine. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRelicFacet(
    center:Offset,radius:Float,tint:Color,precious:Boolean,
) {
    val gem=Path().apply {
        moveTo(center.x,center.y-radius)
        lineTo(center.x+radius*.85f,center.y-radius*.14f)
        lineTo(center.x+radius*.65f,center.y+radius*.60f)
        lineTo(center.x,center.y+radius)
        lineTo(center.x-radius*.65f,center.y+radius*.60f)
        lineTo(center.x-radius*.85f,center.y-radius*.14f)
        close()
    }
    drawPath(gem,Brush.linearGradient(
        listOf(Color(0xFFF8FFFA),tint,
            if(precious)Color(0xFF936234) else Color(0xFF294B65)),
        start=Offset(center.x-radius,center.y-radius),
        end=Offset(center.x+radius,center.y+radius)))
    drawPath(gem,tint.copy(alpha=.94f),
        style=Stroke((radius*.12f).coerceAtLeast(.7f)))
    drawLine(Color.White.copy(alpha=.70f),center+Offset(0f,-radius*.82f),
        center+Offset(0f,radius*.63f),strokeWidth=radius*.07f)
    drawLine(Color.White.copy(alpha=.52f),
        center+Offset(-radius*.70f,-radius*.10f),
        center+Offset(radius*.70f,-radius*.10f),strokeWidth=radius*.055f)
}

/** Four readable creature silhouettes in place of indistinguishable HP orbs.
 * No animation here may change combat; only saved mob kind/HP is rendered. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRelicMonsterSprite(
    p:Offset,t:Float,kind:Int,shimmer:Float,
) {
    val shadow=Color(0xFF0D1420)
    val eye=Color(0xFFFFB6A0).copy(alpha=.77f+shimmer*.18f)
    when(kind) {
        0 -> {
            // Night wraith: tapered cloak, high collar, blue-violet glowing eyes.
            val wings=Path().apply {
                moveTo(p.x-t*.06f,p.y-t*.12f)
                lineTo(p.x-t*.45f,p.y-t*.23f)
                lineTo(p.x-t*.34f,p.y+t*.15f)
                lineTo(p.x-t*.48f,p.y+t*.31f)
                lineTo(p.x-t*.10f,p.y+t*.16f)
                lineTo(p.x+t*.10f,p.y+t*.16f)
                lineTo(p.x+t*.48f,p.y+t*.31f)
                lineTo(p.x+t*.34f,p.y+t*.15f)
                lineTo(p.x+t*.45f,p.y-t*.23f)
                lineTo(p.x+t*.06f,p.y-t*.12f)
                close()
            }
            drawPath(wings,Color(0xFF4D4568))
            val hood=Path().apply {
                moveTo(p.x,p.y-t*.35f)
                lineTo(p.x-t*.27f,p.y-t*.04f)
                lineTo(p.x-t*.18f,p.y+t*.30f)
                lineTo(p.x+t*.18f,p.y+t*.30f)
                lineTo(p.x+t*.27f,p.y-t*.04f)
                close()
            }
            drawPath(hood,Color(0xFF8575A7))
            drawOval(shadow,p+Offset(-t*.17f,-t*.17f),Size(t*.34f,t*.23f))
            drawCircle(Color(0xFFACE7EC),t*.045f,p+Offset(-t*.085f,-t*.07f))
            drawCircle(Color(0xFFACE7EC),t*.045f,p+Offset(t*.085f,-t*.07f))
            drawLine(Color(0xFFBDB4D4),p+Offset(-t*.11f,t*.16f),
                p+Offset(t*.09f,t*.23f),strokeWidth=t*.04f)
        }
        1 -> {
            // Tomb raider spirit: ribbed bronze plates and a crimson visor.
            drawRoundRect(shadow,p+Offset(-t*.31f,-t*.12f),
                Size(t*.62f,t*.51f),CornerRadius(t*.13f))
            drawRoundRect(Color(0xFF795568),p+Offset(-t*.27f,-t*.25f),
                Size(t*.54f,t*.53f),CornerRadius(t*.12f))
            drawRect(Color(0xFFB28779),p+Offset(-t*.22f,-t*.29f),
                Size(t*.44f,t*.12f))
            drawLine(Color(0xFF523847),p+Offset(-t*.23f,t*.06f),
                p+Offset(t*.23f,t*.06f),strokeWidth=t*.08f)
            drawCircle(eye,t*.052f,p+Offset(-t*.11f,-t*.08f))
            drawCircle(eye,t*.052f,p+Offset(t*.11f,-t*.08f))
            drawRect(Color(0xFFB39185),p+Offset(-t*.16f,t*.20f),
                Size(t*.32f,t*.045f))
        }
        2 -> {
            // Carved guardian: broad square shoulders, stone visor and cheek plates.
            drawRoundRect(shadow,p+Offset(-t*.39f,-t*.10f),
                Size(t*.78f,t*.49f),CornerRadius(t*.08f))
            drawRoundRect(Color(0xFF818B83),p+Offset(-t*.35f,-t*.26f),
                Size(t*.70f,t*.49f),CornerRadius(t*.06f))
            drawRect(Color(0xFFB4BDAE),p+Offset(-t*.22f,-t*.35f),
                Size(t*.44f,t*.14f))
            drawRect(Color(0xFF404B50),p+Offset(-t*.25f,-t*.10f),
                Size(t*.50f,t*.15f))
            drawRect(Color(0xFFF9BE7B),p+Offset(-t*.16f,-t*.07f),
                Size(t*.11f,t*.055f))
            drawRect(Color(0xFFF9BE7B),p+Offset(t*.06f,-t*.07f),
                Size(t*.11f,t*.055f))
            drawRect(Color(0xFF5B716B),p+Offset(-t*.24f,t*.18f),
                Size(t*.48f,t*.065f))
        }
        else -> {
            // Elite ancient sentinel: tall rune crown, gold seams and broad cape.
            val silhouette=Path().apply {
                moveTo(p.x,p.y-t*.48f)
                lineTo(p.x-t*.11f,p.y-t*.31f)
                lineTo(p.x-t*.32f,p.y-t*.38f)
                lineTo(p.x-t*.40f,p.y+t*.28f)
                lineTo(p.x,p.y+t*.38f)
                lineTo(p.x+t*.40f,p.y+t*.28f)
                lineTo(p.x+t*.32f,p.y-t*.38f)
                lineTo(p.x+t*.11f,p.y-t*.31f)
                close()
            }
            drawPath(silhouette,Color(0xFF655A65))
            drawRoundRect(Color(0xFFB6A188),p+Offset(-t*.25f,-t*.26f),
                Size(t*.50f,t*.51f),CornerRadius(t*.075f))
            drawRect(Color(0xFF665E5D),p+Offset(-t*.20f,-t*.11f),
                Size(t*.40f,t*.17f))
            drawCircle(Color(0xFFFFDD94),t*.065f,p+Offset(-t*.10f,-t*.035f))
            drawCircle(Color(0xFFFFDD94),t*.065f,p+Offset(t*.10f,-t*.035f))
            drawLine(Color(0xFFFFC879),p+Offset(0f,t*.07f),
                p+Offset(0f,t*.29f),strokeWidth=t*.06f)
            drawCircle(Color(0xFFFFD49B).copy(alpha=.35f+shimmer*.20f),
                t*.40f,p,style=Stroke(t*.042f))
        }
    }
}

/** Pure display projection: never creates or transfers loot. */
internal fun relicChestLidLift(run: RelicRun, chestId: Int,
    receipt: RelicChestReceipt?, progress: Float): Float {
    val chest = run.chests.firstOrNull { it.id == chestId } ?: return 0f
    if (!chest.opened) return 0f
    // An opened physical chest stays open after the transient ceremony is
    // dismissed or skipped. The receipt only animates its initial opening.
    return if (RelicChestPresentation.isCurrentOpening(run, chestId, receipt))
        ((progress - .10f) / .47f).coerceIn(0f, 1f)
    else 1f
}
