package com.jiacimu.lulu.games

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val museumBg=Color(0xFF111B25)
private val museumInk=Color(0xFFF1EEE7)
private val museumMuted=Color(0xFFAABBC4)
private val museumGold=Color(0xFFE6CDA0)

/** A standalone full-window inventory and museum, never a submenu.
 * All operations refer to real RelicSnapshot.vault item IDs. */
@Composable
internal fun RelicVaultPage(
    snapshot:RelicSnapshot,live:Boolean,onClose:()->Unit,
    onFeature:(String)->Unit,onSell:(String)->Unit,
) {
    val context=LocalContext.current
    var grade by remember { mutableStateOf("全部") }
    var museumTab by remember { mutableStateOf("藏品") }
    var selectedId by remember { mutableStateOf(snapshot.featuredLootId ?: snapshot.vault.lastOrNull()?.id) }
    var pendingSale by remember { mutableStateOf<RelicLoot?>(null) }
    val inventory=remember(snapshot.vault,grade) {
        snapshot.vault.asReversed().filter {
            grade=="全部" || RelicCollectibles.displayRank(it)==grade
        }
    }
    // The exhibit and its actions must belong to the visible filtered collection.
    // A remembered selection from another rank must not appear as an owned
    // item of the newly selected rank, especially when that rank is empty.
    val selected=inventory.firstOrNull { it.id==selectedId }
        ?: inventory.firstOrNull { it.id==snapshot.featuredLootId }
        ?: inventory.firstOrNull()
    val share:(RelicLoot)->Unit = { item ->
        val intent=Intent(Intent.ACTION_SEND).apply {
            type="text/plain"
            putExtra(Intent.EXTRA_TEXT,RelicTreasureGallery.shareText(item))
        }
        context.startActivity(Intent.createChooser(intent,"分享馆藏"))
    }
    val exhibit: @Composable () -> Unit = {
        if(selected==null) {
            Text("展柜还是空的。成功带回的漂亮宝物会在这里拥有自己的位置。",
                color=museumMuted,fontSize=13.sp,lineHeight=20.sp,
                modifier=Modifier.padding(16.dp))
        } else {
            RelicTreasureGalleryCard(selected,selected.id==snapshot.featuredLootId)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                OutlinedButton(onClick={onFeature(selected.id)},
                    enabled=selected.id!=snapshot.featuredLootId,
                    modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=4.dp)) {
                    Text(if(selected.id==snapshot.featuredLootId)"✓ 已展出" else "✦ 展出",
                        color=museumGold,fontSize=11.sp)
                }
                OutlinedButton(onClick={share(selected)},modifier=Modifier.weight(1f),
                    contentPadding=PaddingValues(horizontal=4.dp)) {
                    Text("分享",color=museumGold,fontSize=11.sp)
                }
                OutlinedButton(onClick={pendingSale=selected},enabled=!live,
                    modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=4.dp)) {
                    Text("出售",color=Color(0xFFE9B4A8),fontSize=11.sp)
                }
            }
            Text("出售只移走当前实物，永久图鉴不会消失。",
                color=museumMuted,fontSize=10.sp)
        }
    }
    val catalog: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            listOf("全部","普通","精良","稀有","珍贵","史诗","传说","传世秘宝")
                .forEach { entry ->
                    FilterChip(selected=entry==grade,onClick={grade=entry},
                        label={Text(entry,fontSize=11.sp)},
                        colors=FilterChipDefaults.filterChipColors(
                            selectedContainerColor=museumGold,
                            selectedLabelColor=museumBg,labelColor=museumMuted))
                }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f),
            verticalArrangement=Arrangement.spacedBy(5.dp),
            contentPadding=PaddingValues(bottom=14.dp)) {
            if(inventory.isEmpty())item {
                Text("这个品级还没有实物。去遗迹发现下一件珍宝吧。",
                    color=museumMuted,fontSize=12.sp,modifier=Modifier.padding(10.dp))
            }
            items(inventory,key={it.id}) { item ->
                val color=RelicTreasureGallery.accent(item)
                Surface(onClick={selectedId=item.id},
                    color=if(selected?.id==item.id) Color(0xFF283A46) else Color(0xFF1C2A36),
                    shape=RoundedCornerShape(11.dp),
                    border=BorderStroke(1.dp,color.copy(
                        alpha=if(selected?.id==item.id).78f else .18f))) {
                    Row(Modifier.fillMaxWidth().heightIn(min=72.dp)
                        .padding(horizontal=10.dp,vertical=8.dp),
                        verticalAlignment=Alignment.CenterVertically) {
                        // A gallery is for looking: show each *owned* item's real
                        // authored silhouette before its title and price.
                        Box(Modifier.size(53.dp).clip(RoundedCornerShape(10.dp))) {
                            RelicTreasureGalleryThumbnail(item,Modifier.fillMaxSize())
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text((if(item.id==snapshot.featuredLootId)"✦ " else "")+item.name,
                                fontSize=13.sp,color=museumInk,maxLines=1,
                                fontWeight=FontWeight.SemiBold)
                            Text(RelicCollectibles.displayRank(item),fontSize=11.sp,color=color)
                        }
                        Text("◇${item.value}",color=museumMuted,fontSize=12.sp)
                    }
                }
            }
        }
    }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(museumBg)) {
            val landscape=maxWidth>maxHeight
            Column(Modifier.fillMaxSize().padding(horizontal=14.dp,vertical=8.dp),
                verticalArrangement=Arrangement.spacedBy(7.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick=onClose) {
                        Text("‹ 返回",color=museumGold,fontSize=14.sp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("我的藏宝馆",color=museumInk,
                            fontSize=if(landscape)20.sp else 24.sp,fontWeight=FontWeight.Bold)
                        Text("鉴赏 · 收藏 · 展出",color=museumMuted,fontSize=10.sp)
                    }
                    Text("◇${snapshot.coins}",color=museumGold,fontSize=13.sp)
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("馆藏 ${snapshot.vault.size} 件",color=museumInk,fontSize=12.sp)
                    Text("图鉴 ${snapshot.discovered.size}/${RelicCollectibles.names.size}",
                        color=museumMuted,fontSize=12.sp)
                    if(live) Text("探险中 · 不可出售",color=museumGold,fontSize=11.sp)
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    listOf("藏品","收集目标").forEach { tab ->
                        FilterChip(selected=museumTab==tab,onClick={museumTab=tab},
                            label={Text(if(tab=="收集目标")"收集目标 "+
                                RelicCollectionGoals.completed(snapshot.discovered)+"/"+
                                RelicCollectionGoals.all.size else tab,fontSize=12.sp)},
                            colors=FilterChipDefaults.filterChipColors(
                                selectedContainerColor=museumGold,selectedLabelColor=museumBg,
                                labelColor=museumMuted))
                    }
                }
                if(museumTab=="收集目标") {
                    LazyColumn(Modifier.fillMaxSize(),
                        contentPadding=PaddingValues(bottom=22.dp),
                        verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        items(RelicCollectionGoals.all,key={it.id}) { goal ->
                            val got=goal.owned(snapshot.discovered)
                            Surface(color=Color(0xFF1C2A36),shape=RoundedCornerShape(14.dp),
                                border=BorderStroke(1.dp,museumGold.copy(alpha=.16f))) {
                                Column(Modifier.fillMaxWidth().padding(14.dp),
                                    verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                        Text(goal.title,color=museumInk,fontSize=16.sp,
                                            fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                                        Text(if(goal.complete(snapshot.discovered))"✓ 集齐" else "$got/${goal.names.size}",
                                            color=museumGold,fontSize=12.sp)
                                    }
                                    Text(goal.story,color=museumMuted,fontSize=12.sp)
                                    LinearProgressIndicator(progress={got.toFloat()/goal.names.size},
                                        modifier=Modifier.fillMaxWidth(),color=museumGold)
                                    Text("尚缺："+goal.missing(snapshot.discovered).joinToString(" · ")
                                        .ifBlank { "这一套已完整收入永久图鉴" },
                                        color=museumMuted,fontSize=11.sp,lineHeight=18.sp)
                                }
                            }
                        }
                    }
                } else if(landscape) {
                    Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(.95f).fillMaxHeight(),content=catalog)
                        Column(Modifier.weight(1.05f).fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                            verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            exhibit()
                        }
                    }
                } else {
                    Column(Modifier.weight(.58f).fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                        verticalArrangement=Arrangement.spacedBy(7.dp)) { exhibit() }
                    Column(Modifier.weight(.42f).fillMaxWidth(),content=catalog)
                }
            }
        }
    }
    pendingSale?.let { item ->
        AlertDialog(onDismissRequest={pendingSale=null},
            containerColor=Color(0xFF1D2935),
            title={Text("确认出售实物？",color=museumInk)},
            text={Text("「${item.name}」 · ${RelicCollectibles.displayRank(item)}\n"+
                "可得 ◇${item.value}；永久图鉴仍保留。",
                color=museumMuted)},
            confirmButton={TextButton(onClick={pendingSale=null;onSell(item.id)},
                enabled=!live && snapshot.vault.any { it.id==item.id }) {
                Text("确认出售",color=museumGold)
            }},
            dismissButton={TextButton(onClick={pendingSale=null}) {
                Text("留下珍藏",color=museumInk)
            }})
    }
}
