package com.jiacimu.lulu.games

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/** Local, one-shot animation of an already committed physical chest reward. */
@Composable
internal fun RelicChestCeremony(
    receipt: RelicChestReceipt,
    onFinished: () -> Unit,
) {
    val progress = remember(receipt.runId, receipt.chestId) { Animatable(0f) }
    val finish by rememberUpdatedState(onFinished)
    LaunchedEffect(receipt.runId, receipt.chestId) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(1750, easing = FastOutSlowInEasing))
        delay(1150)
        finish()
    }
    val p = progress.value
    val unlocked = ((p - .10f) / .47f).coerceIn(0f, 1f)
    val reveal = ((p - .55f) / .32f).coerceIn(0f, 1f)
    val tint = when (receipt.loot.tier) {
        RelicTier.COMMON -> Color(0xFFE1B38A)
        RelicTier.RARE -> Color(0xFF91E5FF)
        RelicTier.PRECIOUS -> Color(0xFFFFDE90)
    }
    val legendary = RelicCollectibles.isCrownJewel(receipt.loot.name)
    Box(
        Modifier.fillMaxSize().background(Color(0xC909101D)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(.87f).widthIn(max = 360.dp),
            color = Color(0xFF172535),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, tint.copy(alpha = .65f)),
            shadowElevation = 16.dp,
        ) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    if (reveal < .05f) "✦ 古老的锁扣正在开启…"
                    else if (legendary) "✦ 隐世奇珍 · 命运的发现"
                    else "✧ " + receipt.loot.tier.label + "宝藏出世",
                    color = tint, fontWeight = FontWeight.Black, fontSize = 17.sp,
                )
                Canvas(Modifier.fillMaxWidth().height(158.dp)) {
                    val scale = size.height
                    val center = Offset(size.width * .5f, size.height * .62f)
                    val spread = .65f + reveal * .7f
                    drawCircle(
                        Brush.radialGradient(
                            listOf(tint.copy(alpha = reveal * .42f), Color.Transparent),
                            center = center + Offset(0f, -scale * .24f),
                            radius = scale * spread,
                        ),
                        radius = scale * spread,
                        center = center + Offset(0f, -scale * .24f),
                    )
                    drawOval(Color.Black.copy(alpha = .38f),
                        Offset(center.x - scale * .47f, center.y + scale * .23f),
                        Size(scale * .94f, scale * .17f))
                    val base = Offset(center.x - scale * .44f, center.y - scale * .10f)
                    drawRoundRect(Color(0xFF654636), base,
                        Size(scale * .88f, scale * .45f), CornerRadius(scale * .095f))
                    drawRoundRect(Color(0xFF9B6B4C),
                        base + Offset(scale * .045f, scale * .042f),
                        Size(scale * .79f, scale * .34f), CornerRadius(scale * .055f))
                    drawRect(tint.copy(alpha = .75f),
                        base + Offset(scale * .39f, scale * .08f),
                        Size(scale * .10f, scale * .29f))
                    val lidY = center.y - scale * (.28f + unlocked * .47f)
                    drawRoundRect(Color(0xFF4C3540),
                        Offset(center.x - scale * .48f, lidY + scale * .015f),
                        Size(scale * .96f, scale * .25f), CornerRadius(scale * .075f))
                    drawRoundRect(Color(0xFFC39267),
                        Offset(center.x - scale * .44f, lidY),
                        Size(scale * .88f, scale * .15f), CornerRadius(scale * .05f))
                    drawRect(tint, Offset(center.x - scale * .048f, lidY),
                        Size(scale * .096f, scale * .19f))
                    if (reveal > 0f) {
                        val jewelCenter = center + Offset(0f, -scale * (.27f + reveal * .30f))
                        val radius = scale * (.11f + reveal * .14f)
                        with(RelicTreasureArt) {
                            drawTreasure(receipt.loot.name,jewelCenter,radius*2f,
                                tint,reveal)
                        }
                        for (i in 0 until 12) {
                            val a = (i * 6.2831853f / 12f)
                            val distance = radius * (1.5f + reveal * 1.5f)
                            val sparkle = jewelCenter + Offset(cos(a) * distance, sin(a) * distance)
                            drawCircle(tint.copy(alpha = reveal * .80f),
                                scale * (if (i % 3 == 0) .024f else .012f), sparkle)
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
                    Text("鉴定价值 ◇ " + receipt.loot.value, color = tint, fontSize = 13.sp)
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
            }
        }
    }
}
