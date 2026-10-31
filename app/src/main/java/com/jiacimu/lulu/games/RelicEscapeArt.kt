package com.jiacimu.lulu.games

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

/** Local procedural art: no remote images, font downloads, or expensive per-frame textures. */
@Composable
internal fun RelicEscapeWorldMap(
    run:RelicRun,selected:Pair<Int,Int>,onSelect:(Int,Int)->Unit,fullscreen:Boolean=false,
    focus: Pair<Int, Int> = run.hero.x to run.hero.y,
) {
    val names = run.allies.associate { ally ->
        ally.id to MigratedDomainStores.characters.get(ally.id).displayName
    }
    val namePaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
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
                            run.w, run.h, fullscreen, focus)
                        camera.tileAt(tap.x, tap.y, run.w, run.h)?.let { (x, y) -> onSelect(x, y) }
                    }
                }
        ) {
            val camera = relicMapCamera(size.width, size.height, run.w, run.h, fullscreen, focus)
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
                val site=run.sites.firstOrNull{it.x==x && it.y==y}
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
                val chest=run.chests.firstOrNull{it.x==x && it.y==y}
                if(chest!=null) {
                    if(!chest.opened && chest.id in run.playerChestMarks) {
                        drawCircle(Color(0xFFFFE4A8).copy(alpha=.18f+shimmer*.25f),
                            t*.74f,c,style=Stroke(t*.080f))
                        drawCircle(Color(0xFFFFE4A8),t*.10f,c+Offset(t*.29f,-t*.31f))
                    }
                    val glow=when(chest.tier) {
                        RelicTier.COMMON->Color(0xFFD4A770)
                        RelicTier.RARE->Color(0xFF78D9FF)
                        RelicTier.PRECIOUS->Color(0xFFFFDB84)
                    }
                    if(!chest.opened) {
                        drawCircle(glow.copy(alpha=.14f+shimmer*.22f),t*.63f,c)
                        drawRoundRect(Color(0xFF1D1925),p+Offset(t*.10f,t*.33f),
                            Size(t*.85f,t*.52f),CornerRadius(t*.13f))
                        drawRoundRect(Color(0xFF8E5B41),p+Offset(t*.13f,t*.24f),
                            Size(t*.74f,t*.52f),CornerRadius(t*.10f))
                        drawRoundRect(Color(0xFFD0A16B),p+Offset(t*.16f,t*.26f),
                            Size(t*.68f,t*.14f),CornerRadius(t*.045f))
                        drawRect(glow,p+Offset(t*.46f,t*.37f),Size(t*.09f,t*.28f))
                        drawCircle(Color.White.copy(alpha=.55f+shimmer*.4f),t*.068f,
                            c+Offset(t*.02f,t*.04f))
                    } else {
                        drawRoundRect(Color(0xFF705B50),p+Offset(t*.17f,t*.52f),
                            Size(t*.66f,t*.21f),CornerRadius(t*.06f))
                        drawLine(Color(0xFFB29B85),p+Offset(t*.21f,t*.43f),
                            p+Offset(t*.78f,t*.32f),strokeWidth=t*.07f)
                    }
                }
                run.drops.filter{it.x==x && it.y==y}.forEach{drop->
                    val tint=if(drop.loot.tier==RelicTier.PRECIOUS)Color(0xFFFFDC84)
                        else Color(0xFFB9E8DD)
                    drawCircle(tint.copy(alpha=.2f+shimmer*.22f),t*.27f,c+Offset(t*.23f,t*.20f))
                    drawCircle(tint,t*.10f,c+Offset(t*.23f,t*.20f))
                }
                if(x==run.exitX && y==run.exitY) {
                    val green=Color(0xFF80F3CF)
                    drawCircle(green.copy(alpha=.18f+shimmer*.20f),t*.64f,c)
                    drawCircle(green,t*.34f,c,style=Stroke(t*.09f))
                    drawCircle(Color.White.copy(alpha=.45f+shimmer*.4f),t*.16f,c)
                    drawLine(green.copy(alpha=.6f),c+Offset(0f,-t*.58f),
                        c+Offset(0f,t*.58f),strokeWidth=t*.07f)
                }
                if(!lit)drawRect(Color(0xFF090E1B).copy(alpha=.42f),
                    p,Size(t+.3f,t+.3f))
                if(x==selected.first && y==selected.second) {
                    drawRect(Color(0xFFF9DC99).copy(alpha=.96f),
                        p+Offset(t*.03f,t*.03f),
                        Size(t*.94f,t*.94f),style=Stroke(t*.055f))
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
                val skin=when(mob.kind){
                    0->Color(0xFF785D94);1->Color(0xFF84606C);else->Color(0xFFAB816D)
                }
                if(mob.windup) {
                    drawCircle(Color(0xFFFF7183).copy(alpha=.16f+shimmer*.36f),
                        t*.64f,p,style=Stroke(t*.10f))
                    drawCircle(Color(0xFFFF7183).copy(alpha=.28f),t*.52f,p)
                } else if(mob.alert>0)drawCircle(Color(0xFFF3A6A1).copy(alpha=.35f),
                    t*.47f,p,style=Stroke(t*.035f))
                drawCircle(skin,t*.33f,p)
                if(mob.kind==2)drawRoundRect(Color(0xFF504051),p+Offset(-t*.25f,-t*.26f),
                    Size(t*.5f,t*.18f),CornerRadius(t*.04f))
                drawCircle(Color(0xFFFFB0B3),t*.075f,p+Offset(-t*.12f,-t*.055f))
                drawCircle(Color(0xFFFFB0B3),t*.075f,p+Offset(t*.12f,-t*.055f))
                drawRect(Color(0xFF121B24),p+Offset(-t*.29f,-t*.43f),Size(t*.58f,t*.065f))
                drawRect(Color(0xFFE17984),p+Offset(-t*.29f,-t*.43f),
                    Size(t*.58f*(mob.hp.toFloat()/(if(mob.kind==2)9 else 5)).coerceIn(0f,1f),t*.065f))
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
            val hero=center(run.hero.x,run.hero.y)
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
            fun label(text: String, x: Int, y: Int, color: Int, below: Boolean = false) {
                val p = center(x, y)
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
            label("你", run.hero.x, run.hero.y, android.graphics.Color.rgb(255, 216, 148))
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
            if(run.phase==RelicPhase.EXTRACT) {
                drawRect(Brush.radialGradient(listOf(Color.Transparent,
                    Color(0xFF9E3D4C).copy(alpha=.20f*shimmer)),center=hero,
                    radius=size.maxDimension*.72f))
            }
            }
        }
    }
}
