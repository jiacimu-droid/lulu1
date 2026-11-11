package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.window.Dialog
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

/** Same art at thumbnail and close-up sizes; not a second loot renderer. */
@Composable
private fun TreasureMuseumStage(
    loot:RelicLoot,accent:Color,modifier:Modifier=Modifier,
) {
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(
            Color(0xFF28323B),Color(0xFF1B2631),Color(0xFF111820))))
        val c=Offset(size.width*.5f,size.height*.47f)
        // One shallow stone plinth and a small grounded reflection:
        // keep attention on the individually authored treasure silhouette.
        drawOval(Color(0xFF435058),
            topLeft=Offset(size.width*.22f,size.height*.84f),
            size=Size(size.width*.56f,size.height*.075f))
        drawLine(accent.copy(alpha=.32f),
            Offset(size.width*.24f,size.height*.87f),
            Offset(size.width*.76f,size.height*.87f),
            strokeWidth=1.dp.toPx())
        with(RelicTreasureArt) {
            drawTreasure(loot.name,c,size.height*.70f,accent)
        }
    }
}

@Composable
internal fun RelicTreasureGalleryCard(loot:RelicLoot,featured:Boolean) {
    // This local-only view state never changes the collection or the displayed
    // item. Both close-up and inventory use the same actual RelicLoot.id/name.
    var inspecting by remember(loot.id) { mutableStateOf(false) }
    val accent=when(loot.tier) {
        RelicTier.COMMON -> Color(0xFFC8C4B9)
        RelicTier.RARE -> Color(0xFFB4CDD6)
        RelicTier.PRECIOUS -> Color(0xFFDBC7A4)
    }
    Surface(shape=RoundedCornerShape(16.dp),color=Color(0xFF171D27),
        border=BorderStroke(1.dp,accent.copy(alpha=if(featured).52f else .24f))) {
        Column(Modifier.fillMaxWidth().padding(15.dp),
            horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.spacedBy(9.dp)) {
            Text(if(featured)"正在展出" else "馆藏鉴赏",
                color=accent,fontSize=12.sp,fontWeight=FontWeight.Medium)
            TreasureMuseumStage(loot,accent,
                Modifier.fillMaxWidth().height(182.dp)
                    .clickable(onClickLabel="放大鉴赏${loot.name}") {
                        inspecting=true
                    })
            Text("轻触珍宝 · 放大鉴赏",color=Color(0xFFABB7BC),
                fontSize=11.sp)
            Text(loot.name,color=Color(0xFFF3F0E9),fontSize=21.sp,
                fontWeight=FontWeight.Bold)
            Text(RelicTreasureGallery.identity(loot.name),
                color=Color(0xFFCCD1D5),fontSize=12.sp,lineHeight=18.sp)
            Text("${loot.tier.label}珍藏 · 鉴定价值 ${loot.value}",
                color=accent,fontSize=11.sp)
            Text(RelicTreasureGallery.useHint(loot.name),
                color=Color(0xFFA9B3B5),fontSize=10.sp)
        }
    }

    if(inspecting) {
        Dialog(onDismissRequest={inspecting=false}) {
            Surface(
                modifier=Modifier.fillMaxWidth().widthIn(max=480.dp),
                color=Color(0xFF161D28),
                shape=RoundedCornerShape(18.dp),
                border=BorderStroke(1.dp,accent.copy(alpha=.45f)),
            ) {
                Column(Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                    horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(11.dp)) {
                    Text("馆藏珍品",color=accent,fontSize=11.sp)
                    Text(loot.name,color=Color(0xFFF1EEE7),fontSize=23.sp,
                        fontWeight=FontWeight.Bold)
                    TreasureMuseumStage(loot,accent,
                        Modifier.fillMaxWidth().height(256.dp))
                    Text(RelicTreasureGallery.identity(loot.name),
                        color=Color(0xFFCCD6D6),fontSize=13.sp,
                        lineHeight=19.sp)
                    Text("${loot.tier.label} · 馆藏实物 · 鉴定价值 ${loot.value}",
                        color=accent,fontSize=11.sp)
                    TextButton(onClick={inspecting=false}) {
                        Text("收起鉴赏",color=accent,fontSize=13.sp)
                    }
                }
            }
        }
    }
}
