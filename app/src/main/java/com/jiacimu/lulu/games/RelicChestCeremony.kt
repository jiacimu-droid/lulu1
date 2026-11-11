package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    val tint = when (receipt.loot.tier) {
        RelicTier.COMMON -> Color(0xFFC9B9A3)
        RelicTier.RARE -> Color(0xFFB5D5DB)
        RelicTier.PRECIOUS -> Color(0xFFE4D0A4)
    }
    val legendary = RelicCollectibles.isCrownJewel(receipt.loot.name)
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color(0xC909101D))
            .clickable(onClick = onFinished),
        contentAlignment = Alignment.Center,
    ) {
        val compact = maxHeight < 360.dp
        val artHeight = relicCeremonyArtHeightDp(maxHeight.value).dp
        Surface(
            modifier = Modifier.fillMaxWidth(.87f).widthIn(max = 360.dp),
            color = Color(0xFF171F2A),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, tint.copy(alpha = .33f)),
            shadowElevation = 5.dp,
        ) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 7.dp),
            ) {
                Text(
                    if (reveal < .05f) "✦ 古老的锁扣正在开启…"
                    else if (legendary) "一件难得的珍宝"
                    else "发现了" + receipt.loot.tier.label + "珍藏",
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
                        // The exact physical loot gets much larger as the box
                        // fades. At full reveal it is the ONLY principal object.
                        val jewelCenter=Offset(x,
                            center.y-h*(.11f+.10f*reveal))
                        val diameter=h*frame.jewelDiameterFraction
                        with(RelicTreasureArt) {
                            drawTreasure(receipt.loot.name,jewelCenter,diameter,
                                tint,reveal)
                        }
                        // Four tiny glints at most; not a noisy halo of particles.
                        for(i in 0 until 4) {
                            val a=i*1.5707963f+.43f
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
                    Text("鉴定价值 ◇ " + receipt.loot.value,
                        color = tint,fontSize = 12.sp)
                    Text(
                        if (receipt.inBag) "✓ 已装入背包 · 成功撤离后才会永久收藏"
                        else "⚠ 背包已满 · 宝物真实落在箱子旁，记得拾取",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = if (receipt.inBag) Color(0xFFB7EDDA) else Color(0xFFFFC7A8),
                    )
                } else {
                    Text("光芒里藏着什么……", color = Color(0xFFB6C4D4), fontSize = 12.sp)
                }
                Text("轻点画面可跳过动画，宝物不会丢失",
                    color = Color(0xFFB6C4D4), fontSize = 10.sp)
            }
        }
    }
}
