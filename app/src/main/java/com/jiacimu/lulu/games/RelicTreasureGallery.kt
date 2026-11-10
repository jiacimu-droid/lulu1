package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The artwork is generated from the EXACT owned RelicLoot record.
 * The showcase cannot mint items or pretend an unextracted prize is owned.
 * Text and the native share action always describe that actual treasure.
 */
internal object RelicTreasureGallery {
    fun identity(name:String):String=when(name) {
        "月银胸针" -> "月光淬成的银枝，中心嵌着一粒浅蓝星石"
        "幽蓝宝石" -> "幽海般的透明蓝光，转动时能看见细密晶面"
        "月蚀吊坠" -> "黑金月轮拥住一颗银白宝石"
        "琥珀音匣" -> "小小琥珀匣，封存着已经失落的旋律"
        "暮光戒指" -> "暮色宝石缠绕细金藤蔓，带着柔和余晖"
        "深海珍珠" -> "蓝紫虹彩在珍珠表面缓慢游走"
        "星穹冠冕" -> "镂空金冠托起星芒宝石，仿佛一整片夜空"
        "永夜结晶" -> "多棱深蓝晶体中藏着宛如银河的光"
        "黄金天球仪" -> "金色星轨环绕蓝宝石，精巧得像掌心宇宙"
        "冰魄竖琴" -> "冰蓝琴弦与银色花纹交织成一件艺术品"
        "星河沙漏" -> "透明晶壁间有金色星砂不断流动"
        "黎明权杖" -> "日出金纹托起暖白晶石，光芒如晨曦"
        "晨星之核" -> "流动的星河被封在一颗晶莹的心脏里"
        else -> when(RelicTreasureArt.kind(name)) {
            RelicArtKind.COIN -> "金属浮雕和岁月磨痕记录了遗迹故事"
            RelicArtKind.BOOK -> "古老纹样和精细雕刻带着收藏的温度"
            RelicArtKind.CRYSTAL -> "晶面折射层层幽光，近看更漂亮"
            RelicArtKind.CROWN -> "珍贵金饰，适合陈列在藏宝馆最醒目的位置"
            RelicArtKind.RING,RelicArtKind.PENDANT -> "小巧又精致的可收藏饰物"
            RelicArtKind.HARP,RelicArtKind.BOX -> "兼具工艺与故事感的古典珍品"
            else -> "一件有独立形态与出处的遗迹藏品"
        }
    }
    /** Real feature availability is explicit, never invent a passive combat buff. */
    fun useHint(name:String):String=when(name) {
        "星穹冠冕","月银胸针","暮光戒指","月蚀吊坠","猫眼胸章" ->
            "可在藏宝馆展出并分享；角色佩戴功能仍待接入"
        "琥珀音匣","冰魄竖琴" ->
            "可在藏宝馆展出并分享；乐器互动功能仍待接入"
        else -> "可在藏宝馆展出、鉴赏并分享"
    }
    fun shareText(loot:RelicLoot):String =
        "我在《迷雾摸金》带回了「${loot.name}」！\n"+
        identity(loot.name)+"\n"+
        "收藏品级：${loot.tier.label} · 鉴定价值：${loot.value}\n"+
        "这件珍宝已经真实收入我的藏宝馆。"
}

@Composable
internal fun RelicTreasureGalleryCard(loot:RelicLoot,featured:Boolean) {
    val accent=when(loot.tier) {
        RelicTier.COMMON -> Color(0xFFE7C39F)
        RelicTier.RARE -> Color(0xFF9EE0FF)
        RelicTier.PRECIOUS -> Color(0xFFFFD787)
    }
    Surface(shape=RoundedCornerShape(20.dp),color=Color(0xFF182839),
        border=BorderStroke(if(featured)2.dp else 1.dp,
            accent.copy(alpha=if(featured).94f else .57f))) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(if(featured)"✦ 正在展出 · 我的珍宝" else "✧ 收藏鉴赏",
                color=accent,fontSize=12.sp,fontWeight=FontWeight.Bold)
            Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                val c=Offset(size.width*.5f,size.height*.48f)
                drawCircle(Brush.radialGradient(
                    listOf(accent.copy(alpha=.27f),Color.Transparent),
                    center=c,radius=size.height*.60f),size.height*.60f,c)
                drawCircle(accent.copy(alpha=.38f),size.height*.39f,c,
                    style=androidx.compose.ui.graphics.drawscope.Stroke(1.7.dp.toPx()))
                with(RelicTreasureArt) {
                    drawTreasure(loot.name,c,size.height*.87f,accent)
                }
            }
            Text(loot.name,color=Color.White,fontSize=20.sp,
                fontWeight=FontWeight.Black)
            Text(RelicTreasureGallery.identity(loot.name),color=Color(0xFFCFDEEA),
                fontSize=12.sp,lineHeight=18.sp)
            Text("◇ ${loot.tier.label}珍藏 · 价值 ${loot.value}",
                color=accent,fontSize=11.sp)
            Text(RelicTreasureGallery.useHint(loot.name),color=Color(0xFFADC9C5),
                fontSize=10.sp)
        }
    }
}
