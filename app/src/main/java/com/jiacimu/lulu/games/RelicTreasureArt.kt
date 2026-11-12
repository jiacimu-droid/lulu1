package com.jiacimu.lulu.games

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

/**
 * One shared, original vector treasure vocabulary for the physical chest reveal
 * and the permanent collection. No image downloads, bitmaps, loot rerolls, or new store.
 */
internal enum class RelicArtKind {
    COIN, CUP, COMPASS, PENDANT, BOOK, RING, CROWN, CRYSTAL,
    BOX, HOURGLASS, HARP, SCEPTRE, PEARL, STAR
}

internal object RelicTreasureArt {
    val catalogKinds:Map<String,RelicArtKind> = mapOf(
        "旧王银币" to RelicArtKind.COIN,
        "石刻铭牌" to RelicArtKind.BOOK,
        "锈蚀铜杯" to RelicArtKind.CUP,
        "铜制罗盘" to RelicArtKind.COMPASS,
        "旅人哨笛" to RelicArtKind.SCEPTRE,
        "旧日怀表" to RelicArtKind.COMPASS,
        "破损星图" to RelicArtKind.BOOK,
        "石雕徽记" to RelicArtKind.COIN,
        "银线书签" to RelicArtKind.BOOK,
        "月银胸针" to RelicArtKind.PENDANT,
        "幽蓝宝石" to RelicArtKind.CRYSTAL,
        "古老法印" to RelicArtKind.COIN,
        "月蚀吊坠" to RelicArtKind.PENDANT,
        "猫眼胸章" to RelicArtKind.PENDANT,
        "琥珀音匣" to RelicArtKind.BOX,
        "暮光戒指" to RelicArtKind.RING,
        "秘仪短杖" to RelicArtKind.SCEPTRE,
        "深海珍珠" to RelicArtKind.PEARL,
        "星穹冠冕" to RelicArtKind.CROWN,
        "永夜结晶" to RelicArtKind.CRYSTAL,
        "遗迹王印" to RelicArtKind.COIN,
        "黄金天球仪" to RelicArtKind.COMPASS,
        "王室密匣" to RelicArtKind.BOX,
        "冰魄竖琴" to RelicArtKind.HARP,
        "星河沙漏" to RelicArtKind.HOURGLASS,
        "黎明权杖" to RelicArtKind.SCEPTRE,
        "暗影碎片" to RelicArtKind.CRYSTAL,
        "锻铁徽片" to RelicArtKind.COIN,
        "祭司秘纹" to RelicArtKind.BOOK,
        "哨兵之核" to RelicArtKind.STAR,
        "封印之心" to RelicArtKind.CRYSTAL,
        RelicCollectibles.CROWN_JEWEL to RelicArtKind.STAR,
    )
    fun kind(name:String):RelicArtKind =
        catalogKinds[name] ?: RelicArtKind.CRYSTAL

    /** diameter scales with canvas, shape stays legible at 60dp album and 160dp reveal. */
    fun DrawScope.drawTreasure(
        name:String,center:Offset,diameter:Float,tint:Color,alpha:Float=1f,
    ) {
        // Three standout collectibles get individually drawn metalwork and
        // gem facets. Both chest reveals and the real museum use this method.
        if (with(RelicSignatureJewels) {
                drawSignature(name,center,diameter,alpha)
            }) return
        val s=diameter
        val x=center.x
        val y=center.y
        val color=tint.copy(alpha=alpha.coerceIn(0f,1f))
        val highlight=Color.White.copy(alpha=(alpha*.75f).coerceIn(0f,1f))
        val shade=Color(0xFF263248).copy(alpha=(alpha*.95f).coerceIn(0f,1f))
        val width=(s*.07f).coerceAtLeast(1f)
        fun line(ax:Float,ay:Float,bx:Float,by:Float,c:Color=color,w:Float=width)=
            drawLine(c,Offset(x+s*ax,y+s*ay),Offset(x+s*bx,y+s*by),strokeWidth=w)
        fun poly(vararg pts:Pair<Float,Float>):Path=Path().apply {
            pts.forEachIndexed { i,p ->
                if(i==0)moveTo(x+p.first*s,y+p.second*s)
                else lineTo(x+p.first*s,y+p.second*s)
            }
            close()
        }
        when(kind(name)){
            RelicArtKind.COIN -> {
                drawCircle(color,s*.35f,center)
                drawCircle(shade,s*.28f,center)
                drawCircle(color,s*.20f,center,style=Stroke(width))
                line(-.11f,-.11f,.10f,.11f,highlight,width*.52f)
            }
            RelicArtKind.CUP -> {
                drawPath(poly(-.32f to -.29f,.32f to -.29f,.24f to .13f,
                    .09f to .25f,-.09f to .25f,-.24f to .13f),color)
                drawOval(color,Offset(x-s*.32f,y-s*.35f),Size(s*.64f,s*.13f))
                line(0f,.24f,0f,.36f)
                line(-.20f,.37f,.20f,.37f)
                drawArc(color,270f,180f,false,
                    Offset(x+s*.18f,y-s*.20f),Size(s*.29f,s*.31f),
                    style=Stroke(width))
                line(-.23f,-.16f,.19f,-.16f,highlight,width*.45f)
            }
            RelicArtKind.COMPASS -> {
                drawCircle(color,s*.36f,center,style=Stroke(width*1.4f))
                drawCircle(shade,s*.27f,center)
                drawPath(poly(0f to -.28f,.13f to .13f,0f to .04f,
                    -.14f to .15f),color)
                drawCircle(highlight,s*.07f,center)
                line(0f,-.41f,0f,-.32f,highlight)
            }
            RelicArtKind.PENDANT -> {
                drawArc(color,200f,140f,false,
                    Offset(x-s*.29f,y-s*.40f),Size(s*.58f,s*.53f),
                    style=Stroke(width*.7f))
                drawCircle(color,s*.22f,center+Offset(0f,s*.13f))
                drawCircle(shade,s*.13f,center+Offset(0f,s*.13f))
                drawCircle(highlight,s*.045f,center+Offset(-s*.045f,s*.06f))
            }
            RelicArtKind.BOOK -> {
                drawPath(poly(-.32f to -.38f,.03f to -.31f,.03f to .34f,
                    -.32f to .25f),color)
                drawPath(poly(.04f to -.31f,.34f to -.38f,.34f to .25f,
                    .04f to .34f),color.copy(alpha=alpha*.70f))
                line(.03f,-.34f,.03f,.35f,highlight,width*.6f)
                line(-.25f,-.17f,-.06f,-.13f,shade,width*.6f)
                line(.10f,-.14f,.27f,-.18f,shade,width*.6f)
            }
            RelicArtKind.RING -> {
                drawOval(color,Offset(x-s*.31f,y-s*.06f),Size(s*.62f,s*.41f),
                    style=Stroke(s*.12f))
                drawPath(poly(0f to -.40f,.17f to -.19f,0f to .04f,
                    -.17f to -.19f),color)
                line(-.04f,-.27f,.03f,-.34f,highlight,width*.65f)
            }
            RelicArtKind.CROWN -> {
                drawPath(poly(-.38f to -.20f,-.25f to -.06f,-.19f to -.36f,
                    0f to -.13f,.19f to -.38f,.25f to -.06f,.38f to -.21f,
                    .30f to .30f,-.30f to .30f),color)
                drawRect(shade,Offset(x-s*.31f,y+s*.16f),Size(s*.62f,s*.10f))
                for(dx in listOf(-.20f,0f,.20f))
                    drawCircle(highlight,s*.045f,center+Offset(s*dx,s*.19f))
            }
            RelicArtKind.CRYSTAL -> {
                drawPath(poly(0f to -.42f,.31f to -.13f,.22f to .30f,
                    0f to .42f,-.22f to .30f,-.31f to -.13f),color)
                line(0f,-.40f,0f,.39f,highlight,width*.58f)
                line(-.30f,-.12f,.29f,-.12f,shade,width*.6f)
                line(0f,.34f,.28f,-.10f,shade,width*.53f)
            }
            RelicArtKind.BOX -> {
                drawRoundRect(color,Offset(x-s*.34f,y-s*.12f),
                    Size(s*.68f,s*.48f),CornerRadius(s*.045f))
                drawRect(shade,Offset(x-s*.30f,y+s*.02f),Size(s*.60f,s*.12f))
                drawPath(poly(-.40f to -.15f,-.22f to -.36f,
                    .22f to -.36f,.40f to -.15f),color)
                drawRect(highlight,Offset(x-s*.045f,y-s*.16f),
                    Size(s*.09f,s*.27f))
            }
            RelicArtKind.HOURGLASS -> {
                line(-.29f,-.37f,.29f,-.37f)
                line(-.29f,.37f,.29f,.37f)
                drawPath(poly(-.25f to -.30f,.25f to -.30f,
                    0f to .02f),color)
                drawPath(poly(0f to .02f,.25f to .30f,
                    -.25f to .30f),color.copy(alpha=alpha*.75f))
                line(-.29f,-.31f,-.29f,.31f,shade,width*.55f)
                line(.29f,-.31f,.29f,.31f,shade,width*.55f)
                drawCircle(highlight,s*.04f,center)
            }
            RelicArtKind.HARP -> {
                val body=Path().apply {
                    moveTo(x-s*.20f,y-s*.34f)
                    quadraticBezierTo(x+s*.34f,y-s*.43f,x+s*.27f,y+s*.31f)
                    lineTo(x-s*.27f,y+s*.31f)
                    close()
                }
                drawPath(body,color,style=Stroke(width*1.4f))
                line(-.29f,.35f,.36f,.35f)
                for(dx in listOf(-.12f,-.01f,.10f,.21f))
                    line(dx,-.26f,dx,.27f,highlight,width*.42f)
            }
            RelicArtKind.SCEPTRE -> {
                line(-.19f,.39f,.16f,-.24f,color,width*1.5f)
                drawCircle(color,s*.16f,center+Offset(s*.20f,-s*.30f))
                drawCircle(highlight,s*.056f,center+Offset(s*.17f,-s*.34f))
                line(-.24f,.34f,-.13f,.43f,shade,width*.75f)
            }
            RelicArtKind.PEARL -> {
                drawCircle(color,s*.31f,center)
                drawCircle(shade.copy(alpha=alpha*.28f),s*.24f,
                    center+Offset(s*.10f,s*.11f))
                drawCircle(highlight,s*.095f,center+Offset(-s*.11f,-s*.12f))
                drawCircle(highlight,s*.04f,center+Offset(-s*.18f,-s*.21f))
            }
            RelicArtKind.STAR -> {
                val points=(0 until 10).map { k ->
                    val a=k*Math.PI.toFloat()/5f - Math.PI.toFloat()/2f
                    val r=if(k%2==0)s*.43f else s*.18f
                    (x+cos(a)*r) to (y+sin(a)*r)
                }
                val star=Path().apply {
                    points.forEachIndexed { i,p ->
                        if(i==0)moveTo(p.first,p.second)
                        else lineTo(p.first,p.second)
                    }
                    close()
                }
                drawPath(star,color)
                drawCircle(highlight,s*.07f,center)
            }
        }
    }
}
