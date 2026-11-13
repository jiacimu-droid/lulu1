package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure presentation timeline. The game has already committed the exact loot
 * before this animation begins. The chest recedes rather than covering the
 * beautiful collectible during its reveal.
 */
internal data class RelicRevealFrame(
    val lidOpen: Float,
    val lidAngleDegrees: Float,
    val jewelReveal: Float,
    val chestAlpha: Float,
    val jewelDiameterFraction: Float,
)

internal object RelicRevealTimeline {
    /** Rarity has a readable physical signature, without extra particle storms. */
    fun glintCount(loot: RelicLoot): Int = when {
        RelicCollectibles.isCrownJewel(loot.name) -> 6
        loot.tier == RelicTier.PRECIOUS -> 4
        loot.tier == RelicTier.RARE -> 2
        else -> 0
    }
    /** More prestigious finds get a richer, finite reveal; no perpetual GPU animation. */
    fun beamCount(loot: RelicLoot): Int = when(RelicCollectibles.displayRank(loot)) {
        "精良" -> 2
        "稀有" -> 5
        "珍贵" -> 8
        "史诗" -> 12
        "传说" -> 16
        "传世秘宝" -> 22
        else -> 0
    }
    fun flashStrength(progress: Float):Float {
        val p=progress.coerceIn(0f,1f)
        // The flash peaks as the physical lid opens, then settles before the
        // final museum close-up. It cannot blink indefinitely.
        return (1f-kotlin.math.abs(p-.55f)/.17f).coerceIn(0f,1f)
    }
    fun at(progress: Float): RelicRevealFrame {
        val p = progress.coerceIn(0f,1f)
        val lid = ((p-.09f)/.40f).coerceIn(0f,1f)
        val reveal = ((p-.47f)/.43f).coerceIn(0f,1f)
        return RelicRevealFrame(
            lidOpen=lid,
            lidAngleDegrees=-58f * lid,
            jewelReveal=reveal,
            chestAlpha=(1f-((reveal-.10f)/.78f)).coerceIn(0f,1f),
            jewelDiameterFraction=.24f+.58f*reveal,
        )
    }
}

/** Bound the centerpiece to the actual viewport so short landscape screens keep the receipt visible. */
internal fun relicCeremonyArtHeightDp(viewportHeightDp: Float): Int = when {
    viewportHeightDp < 280f -> 72
    viewportHeightDp < 360f -> 96
    viewportHeightDp < 440f -> 124
    else -> 172
}

/** A presentation-only label derived from the already committed bag/drop outcome. */
internal fun relicCeremonyReceiptLabel(inBag: Boolean): String =
    if (inBag) "✓ 已装入背包 · 成功撤离后才会永久收藏"
    else "⚠ 背包已满 · 宝物真实落在箱子旁，记得拾取"

/** Local, one-shot animation of an already committed physical chest reward. */
@Composable
internal fun RelicChestCeremony(
    receipt: RelicChestReceipt,
    progress: Float,
    onFinished: () -> Unit,
) {
    val frame = RelicRevealTimeline.at(progress)
    val reveal = frame.jewelReveal
    val chestAlpha = frame.chestAlpha
    // Museum colors, not three competing neon flares.
    val tint = RelicTreasureGallery.accent(receipt.loot)
    val legendary = RelicCollectibles.isCrownJewel(receipt.loot.name)
    val rank = RelicCollectibles.displayRank(receipt.loot)
    val glints = RelicRevealTimeline.glintCount(receipt.loot)
    val beams = RelicRevealTimeline.beamCount(receipt.loot)
    val flash = RelicRevealTimeline.flashStrength(progress)
    val rarityCue = when(rank) {
        "传世秘宝" -> "✦ 传世秘宝 · 星河苏醒 ✦"
        "传说" -> "✦ 传说 · 被封存的心跳"
        "史诗" -> "✦ 史诗 · 千年辉光"
        "珍贵" -> "✧ 珍贵 · 暖金华彩"
        "稀有" -> "✧ 稀有 · 蓝银微光"
        "精良" -> "✧ 精良 · 翡翠微辉"
        else -> "普通 · 遗迹遗物"
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color(0xC909101D))
            .clickable(onClick = onFinished),
        contentAlignment = Alignment.Center,
    ) {
        val compact = maxHeight < 360.dp
        val artHeight = relicCeremonyArtHeightDp(maxHeight.value).dp
        Surface(
            modifier = Modifier.fillMaxWidth(.87f).widthIn(max = 360.dp).heightIn(max = maxHeight),
            color = Color(0xFF171F2A),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, tint.copy(alpha = .33f)),
            shadowElevation = 5.dp,
        ) {
            Column {
                // The physical receipt stays pinned while the long treasure description
                // scrolls. A short landscape viewport must never scroll away the truth.
                if (reveal > .05f) {
                    Text(
                        relicCeremonyReceiptLabel(receipt.inBag),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = if (receipt.inBag) Color(0xFFB7EDDA) else Color(0xFFFFC7A8),
                    )
                }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 7.dp),
            ) {
                Text(
                    if (reveal < .05f) "✦ 古老的锁扣正在开启…"
                    else rarityCue,
                    color = tint, fontWeight = FontWeight.Black, fontSize = 17.sp,
                )
                Canvas(Modifier.fillMaxWidth().height(artHeight)) {
                    val h=size.height
                    val x=size.width*.5f
                    val center=Offset(x,h*.54f)
                    // Quiet diffuse museum lighting; a single focal point.
                    drawRect(Brush.verticalGradient(listOf(
                        Color(0xFF1A2630),Color(0xFF121B25))))
                    drawCircle(
                        Brush.radialGradient(
                            listOf(tint.copy(alpha=.10f+.14f*reveal),
                                Color.Transparent),
                            center=center+Offset(0f,-h*.14f),
                            radius=h*.68f,
                        ),
                        radius=h*.68f,center=center+Offset(0f,-h*.14f),
                    )
                    if(chestAlpha>0f) {
                        drawOval(Color.Black.copy(alpha=.25f*chestAlpha),
                            Offset(x-h*.47f,center.y+h*.28f),
                            Size(h*.94f,h*.13f))
                        val base=Offset(x-h*.44f,center.y-h*.12f)
                        drawRoundRect(Color(0xFF6C5044).copy(alpha=chestAlpha),
                            base,Size(h*.88f,h*.45f),CornerRadius(h*.07f))
                        drawRoundRect(Color(0xFFC3A27D).copy(alpha=chestAlpha),
                            base+Offset(h*.055f,h*.04f),
                            Size(h*.77f,h*.33f),CornerRadius(h*.06f))
                        drawRect(tint.copy(alpha=.52f*chestAlpha),
                            base+Offset(h*.40f,h*.07f),Size(h*.08f,h*.31f))
                        // A hinged lid pivots around its back edge instead of
                        // floating vertically away from the chest.
                        val hinge=Offset(x-h*.44f,center.y-h*.18f)
                        rotate(frame.lidAngleDegrees, pivot=hinge) {
                            val lidY=center.y-h*.31f
                            drawRoundRect(Color(0xFF554342).copy(alpha=chestAlpha),
                                Offset(x-h*.48f,lidY+h*.015f),
                                Size(h*.96f,h*.23f),CornerRadius(h*.065f))
                            drawRoundRect(Color(0xFFD4B391).copy(alpha=chestAlpha),
                                Offset(x-h*.44f,lidY),
                                Size(h*.88f,h*.13f),CornerRadius(h*.06f))
                        }
                    }
                    if(reveal>0f) {
                        val jewelCenter=Offset(x,center.y-h*(.11f+.10f*reveal))
                        // An authored three-beat moment: lock release, opening
                        // flash, flying facets, then a still museum exhibit.
                        // All coordinates depend ONLY on the committed receipt
                        // and one-shot progress, never on additional loot rolls.
                        val bloom=(1f-reveal).coerceIn(0f,1f)
                        if(beams>0) {
                            val beamAlpha=(.08f+.30f*flash+.17f*bloom)*reveal
                            for(i in 0 until beams) {
                                val angle=i*6.2831853f/beams + progress*.55f
                                val inner=h*(.16f+.06f*reveal)
                                val outer=h*(.31f+.31f*reveal)
                                val direction=Offset(cos(angle),sin(angle))
                                drawLine(tint.copy(alpha=beamAlpha),
                                    jewelCenter+direction*inner,
                                    jewelCenter+direction*outer,
                                    strokeWidth=h*(if(legendary).010f else .006f))
                            }
                            // Two expanding rings make the reveal read as a
                            // magical discovery rather than a static icon swap.
                            for(ring in 0..1) {
                                val radius=h*(.18f+reveal*.26f+ring*.075f)
                                drawCircle(tint.copy(alpha=(.08f+flash*.25f)*reveal),
                                    radius=radius,center=jewelCenter,
                                    style=androidx.compose.ui.graphics.drawscope.Stroke(h*.006f))
                            }
                            val shardCount=beams.coerceAtMost(16)
                            for(i in 0 until shardCount) {
                                val angle=i*6.2831853f/shardCount + .22f
                                val travel=h*(.22f+reveal*.36f)
                                val direction=Offset(cos(angle),sin(angle))
                                val shard=jewelCenter+direction*travel
                                val alpha=(reveal*(1f-reveal)*3f).coerceIn(0f,1f)
                                drawLine(tint.copy(alpha=alpha*.75f),shard,
                                    shard+direction*h*.055f,
                                    strokeWidth=h*.010f)
                            }
                        }
                        if(flash>0f && beams>0) {
                            drawCircle(Brush.radialGradient(
                                listOf(Color.White.copy(alpha=flash*.31f),tint.copy(alpha=flash*.12f),
                                    Color.Transparent),
                                center=jewelCenter,radius=h*.58f),
                                radius=h*.58f,center=jewelCenter)
                        }
                        // Plain finds stay quiet; rare finds glow blue-silver;
                        // precious finds show a gold ring; the singular crown
                        // jewel gets a second concentric halo.
                        if(glints>0) {
                            drawCircle(tint.copy(alpha=(.15f+.14f*reveal)*reveal),
                                radius=h*(.34f+.10f*reveal),
                                center=center+Offset(0f,-h*.18f),
                                style=androidx.compose.ui.graphics.drawscope.Stroke(h*.007f))
                            if(legendary) drawCircle(tint.copy(alpha=.34f*reveal),
                                radius=h*(.43f+.09f*reveal),
                                center=center+Offset(0f,-h*.18f),
                                style=androidx.compose.ui.graphics.drawscope.Stroke(h*.012f))
                        }
                        // The exact physical loot gets much larger as the box
                        // fades. At full reveal it is the ONLY principal object.
                        val diameter=h*frame.jewelDiameterFraction
                        with(RelicTreasureArt) {
                            drawTreasure(receipt.loot.name,jewelCenter,diameter,
                                tint,reveal)
                        }
                        // 0 / 2 / 4 / 6 tiny highlights are the rarity's
                        // consistent reveal signature, not random visual noise.
                        for(i in 0 until glints) {
                            val a=i*(6.2831853f/glints)+.43f
                            val d=diameter*.60f
                            drawCircle(tint.copy(alpha=.21f*reveal),
                                h*.014f,
                                jewelCenter+Offset(cos(a)*d,sin(a)*d))
                        }
                    }
                }
                if (reveal > .05f) {
                    Text(
                        receipt.loot.name,
                        fontSize = if (legendary) 24.sp else 21.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                    )
                    Text(RelicTreasureGallery.identity(receipt.loot.name),
                        fontSize = 11.sp,lineHeight = 15.sp,
                        color = Color(0xFFD1D7D7))
                    Text("品质：" + rank + "  ·  鉴定价值 ◇ " + receipt.loot.value,
                        color = tint,fontSize = 12.sp,
                        fontWeight = FontWeight.Bold)
                } else {
                    Text("光芒里藏着什么……", color = Color(0xFFB6C4D4), fontSize = 12.sp)
                }
                Text("轻点画面跳过 · 出货已真实结算，撤离前仍可能遗失",
                    color = Color(0xFFB6C4D4), fontSize = 10.sp)
            }
            }
        }
    }
}
