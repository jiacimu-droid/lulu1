package com.jiacimu.lulu.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun StudyPlanScreen(state: StudyState, store: PostgraduateExamStore) {
    var range by remember { mutableStateOf(StudyPlanRange.Weekly) }
    var title by remember { mutableStateOf("") }
    val items = state.planItems.filter { it.range == range }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            StudyCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StudyPlanRange.entries.forEach { item ->
                        FilterChip(
                            selected = item == range,
                            onClick = { range = item },
                            label = { Text(if (item == StudyPlanRange.Weekly) "周计划" else "月计划") },
                        )
                    }
                }
            }
        }
        item {
            StudyCard {
                Text("添加${if (range == StudyPlanRange.Weekly) "周" else "月"}计划", fontWeight = FontWeight.Bold)
                OutlinedTextField(title, { title = it }, label = { Text("目标") }, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        store.addPlanItem(range, title, "")
                        title = ""
                    },
                    enabled = title.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存计划") }
            }
        }
        if (items.isEmpty()) item { StudyCard { Text("当前没有计划", color = StudyDesign.muted) } }
        items(items, key = { it.id }) { item ->
            StudyCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { store.deletePlanItem(item.id) }) { Icon(Icons.Outlined.DeleteOutline, "删除") }
                }
            }
        }
    }
}

@Composable
internal fun StudyGachaScreen(state: StudyState, store: PostgraduateExamStore) {
    var results by remember { mutableStateOf(emptyList<StudyDrawResult>()) }
    var message by remember { mutableStateOf("") }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            CandyGachaCard(
                state = state,
                onSingle = {
                    results = store.drawSingle()
                    message = if (results.isEmpty()) "需要1张单抽券或${SINGLE_DRAW_COST}夸夸值" else "完成单抽"
                },
                onTen = {
                    results = store.drawTen()
                    message = if (results.isEmpty()) "需要1张十连券或${TEN_DRAW_COST}夸夸值" else "完成十连"
                },
            )
        }
        if (results.isNotEmpty()) {
            item { Text("本次结果", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            items(results, key = { it.id }) { result ->
                StudyCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(result.title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(result.kind.label, color = StudyDesign.muted)
                        }
                        Surface(color = rarityColor(result.rarity), shape = RoundedCornerShape(14.dp)) {
                            Text(result.rarity.label, Modifier.padding(horizontal = 11.dp, vertical = 7.dp), fontSize = 12.sp)
                        }
                    }
                    if (!result.inventoryChanged) {
                        Text("这套画卷已经集满；本次抽中物仍显示，但不会重复增加。", color = StudyDesign.muted)
                    }
                }
            }
        }
        if (state.superMomentAvailable) {
            item {
                StudyCard {
                    Text("今日待办全清", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("可领取十连抽券 x1。", color = StudyDesign.muted)
                    Button(
                        onClick = { message = store.claimSuperMoment() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("领取") }
                }
            }
        }
        item { StudyMessage(message, message.contains("需要") || message.contains("不足")) }
    }
}

@Composable
private fun CandyGachaCard(state: StudyState, onSingle: () -> Unit, onTen: () -> Unit) {
    val pink = Color(0xFFFF88A7)
    val navy = Color(0xFF42384A)
    Surface(
        shape = RoundedCornerShape(30.dp),
        color = Color(0xFFFFF6F8),
        border = BorderStroke(1.dp, Color(0xFFFFD3DE)),
        shadowElevation = 4.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFFFFF7FA), Color(0xFFFFEAF0)))).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("星糖扭蛋机", fontSize = 25.sp, fontWeight = FontWeight.Black, color = navy)
            Text("把今天的努力，换成一颗闪闪发光的糖", color = Color(0xFF856E79), fontSize = 13.sp)
            Box(Modifier.fillMaxWidth().height(210.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Color(0xFFFFDDE7), size.minDimension * .42f, Offset(size.width / 2, size.height * .42f))
                    drawCircle(Color(0xFFF7C8D6), 18.dp.toPx(), Offset(size.width * .38f, size.height * .35f))
                    drawCircle(Color(0xFFFFF1A8), 15.dp.toPx(), Offset(size.width * .53f, size.height * .29f))
                    drawCircle(Color(0xFFC9E9F7), 17.dp.toPx(), Offset(size.width * .61f, size.height * .42f))
                    drawRoundRect(Color(0xFFFFAFC4), Offset(size.width * .27f, size.height * .62f), androidx.compose.ui.geometry.Size(size.width * .46f, size.height * .30f), 22.dp.toPx(), 22.dp.toPx())
                }
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(56.dp), tint = Color.White)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                GachaBalance("单抽券", state.inventory.singleTickets)
                GachaBalance("十连券", state.inventory.tenTickets)
                GachaBalance("夸夸值", state.profile.praisePoints)
            }
            Text("距保底 ${(NON_NORMAL_PITY - state.drawsSinceNonNormal).coerceIn(1, NON_NORMAL_PITY)} 抽", color = Color(0xFF856E79), fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onSingle, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, pink)) {
                    Text("单抽", color = pink, fontWeight = FontWeight.Bold)
                }
                Button(onClick = onTen, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = pink)) {
                    Text("十连抽", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun GachaBalance(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF42384A))
        Text(label, fontSize = 11.sp, color = Color(0xFF856E79))
    }
}

private fun rarityColor(rarity: StudyRarity): Color = when (rarity) {
    StudyRarity.Normal -> Color(0xFFDCEAF4)
    StudyRarity.Rare -> Color(0xFFE8DDF2)
    StudyRarity.Epic -> Color(0xFFFFEDB8)
    StudyRarity.Rainbow -> Color(0xFFD8F3EF)
}

private data class CollectionTicket(val title: String, val amount: Int, val use: () -> Unit)

@Composable
internal fun StudyCollectionScreen(state: StudyState, store: PostgraduateExamStore, onOpenTheater: () -> Unit) {
    var message by remember { mutableStateOf("") }
    val tickets = listOf(
        CollectionTicket("时长券 · 20分钟", state.inventory.douyinTickets) { message = store.redeemEntertainment(StudyEntertainmentKind.Douyin) },
        CollectionTicket("畅玩券 · 120分钟", state.inventory.gameTickets) { message = store.redeemEntertainment(StudyEntertainmentKind.Game) },
        CollectionTicket("视频解锁卡", state.inventory.videoCards) { message = store.redeemEntertainment(StudyEntertainmentKind.Video) },
        CollectionTicket("番剧兑换券 · 3小时", state.inventory.animeTickets) { message = store.redeemEntertainment(StudyEntertainmentKind.Anime) },
        CollectionTicket("小剧场券", state.inventory.theaterFragments) {
            message = store.redeemEntertainment(StudyEntertainmentKind.Theater)
            if (!message.contains("不足") && !message.contains("全部")) onOpenTheater()
        },
    )

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(tickets.chunked(2)) { rowTickets ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowTickets.forEach { CollectionTicketCard(it, Modifier.weight(1f)) }
                if (rowTickets.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item { Text("画卷碎片", fontSize = 19.sp, fontWeight = FontWeight.Bold) }
        items(blueFragmentCatalog.chunked(2)) { titles ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                titles.forEach { title -> FragmentProgressCard(title, state.inventory.blueFragments[title] ?: 0, Modifier.weight(1f)) }
                if (titles.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item { StudyMessage(message, message.contains("不足")) }
    }
}

@Composable
private fun CollectionTicketCard(ticket: CollectionTicket, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(ticket.title, fontWeight = FontWeight.Bold, minLines = 2)
            Text("拥有 ${ticket.amount}", color = StudyDesign.muted)
            Button(onClick = ticket.use, enabled = ticket.amount > 0, modifier = Modifier.fillMaxWidth()) { Text("使用") }
        }
    }
}

@Composable
private fun FragmentProgressCard(title: String, amount: Int, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2, minLines = 2)
            Text("$amount/$BLUE_FRAGMENTS_PER_SCROLL", color = StudyDesign.muted, fontSize = 12.sp)
            StudyProgress(amount.toFloat() / BLUE_FRAGMENTS_PER_SCROLL)
        }
    }
}

@Composable
internal fun StudyAchievementsScreen(state: StudyState, store: PostgraduateExamStore) {
    var message by remember { mutableStateOf("") }
    val unlocked = state.achievements.count { it.unlocked }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            StudyCard {
                Text("成就", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("已解锁 $unlocked/${state.achievements.size}", color = StudyDesign.muted)
                StudyProgress(if (state.achievements.isEmpty()) 0f else unlocked.toFloat() / state.achievements.size)
            }
        }
        items(state.achievements, key = { it.id }) { achievement ->
            StudyCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.EmojiEvents, null, tint = if (achievement.unlocked) StudyDesign.wheat else StudyDesign.muted)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(achievement.title, fontWeight = FontWeight.Bold)
                        Text(achievement.description, color = StudyDesign.muted)
                    }
                    Text("${achievement.progress.coerceAtMost(achievement.target)}/${achievement.target}")
                }
                StudyProgress(achievement.progress.toFloat() / achievement.target.coerceAtLeast(1))
                Button(
                    onClick = { message = store.claimAchievement(achievement.id) },
                    enabled = achievement.unlocked && !achievement.claimed,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (achievement.claimed) "已领取" else if (achievement.unlocked) "领取奖励" else "尚未解锁") }
            }
        }
        item { StudyMessage(message) }
    }
}

@Composable
internal fun StudyShopScreen(state: StudyState, store: PostgraduateExamStore) {
    var message by remember { mutableStateOf("") }
    val canRefresh = state.manualShopRefreshDate != state.activeDate
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            StudyCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("神秘商店", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("夸夸值：${state.profile.praisePoints}", color = StudyDesign.muted)
                    }
                    IconButton(onClick = { message = store.refreshShop() }, enabled = canRefresh) { Icon(Icons.Outlined.Refresh, "刷新") }
                }
                Text("每天自动刷新3件商品；手动刷新每天最多一次。", color = StudyDesign.muted, fontSize = 12.sp)
            }
        }
        items(state.shopItems, key = { it.id }) { item ->
            StudyCard {
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(item.subtitle, color = StudyDesign.muted)
                    }
                    Text("${item.cost} 夸夸值", fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { message = store.buyShopItem(item.id) },
                    enabled = !item.purchased && state.profile.praisePoints >= item.cost,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (item.purchased) "已购买" else "购买") }
            }
        }
        item { StudyMessage(message, message.contains("不足") || message.contains("失败")) }
    }
}

@Composable
internal fun StudyGuideScreen() {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            StudyCard {
                Text("考研 App 说明", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("当前奖励、抽卡、收藏和番茄钟规则。", color = StudyDesign.muted)
            }
        }
        item { GuideCard("夸夸值", "签到、完成待办和学习时长都会获得夸夸值。累计学习每满5分钟获得100夸夸值，不足部分跨番茄保留。") }
        item { GuideCard("抽卡概率", "蓝色画卷92.15%；紫色6%（抖音5%、剧场1%）；金色1.5%（游戏券1.2%、视频卡0.3%）；彩色番剧券0.35%。") }
        item { GuideCard("保底", "连续30抽没有紫／金／彩时，第30抽直接出现紫色结果。") }
        item { GuideCard("画卷碎片", "每套画卷需要10枚自己的专属碎片。已满后仍显示本次抽中物，但不重复计入。") }
        item { GuideCard("收藏", "紫色、金色和彩色奖励抽到后进入对应收藏：时长券、小剧场券、畅玩券、视频卡和番剧券。") }
        item { GuideCard("商店", "商店使用夸夸值，每日展示3件商品，手动刷新每天最多一次。") }
        item { GuideCard("番茄钟", "番茄钟提供云雾原版和深夜墨蓝两套配色，支持自定义时长、提前结束按实际分钟结算、角色语音和专注中聊天。") }
    }
}

@Composable
private fun GuideCard(title: String, content: String) {
    StudyCard {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(content, color = StudyDesign.muted)
    }
}
