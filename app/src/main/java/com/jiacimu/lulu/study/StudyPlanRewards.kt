package com.jiacimu.lulu.study

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun StudyPlanScreen(state: StudyState, store: PostgraduateExamStore) {
    var range by remember { mutableStateOf(StudyPlanRange.Weekly) }
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val items = state.planItems.filter { it.range == range }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            StudyCard {
                Text("长期计划", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("周计划与月计划会作为 AI 今日时间表的约束和参考。", color = StudyDesign.muted)
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
                OutlinedTextField(note, { note = it }, label = { Text("备注或节点") }, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        store.addPlanItem(range, title, note)
                        title = ""
                        note = ""
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
                    Checkbox(checked = item.completed, onCheckedChange = { store.togglePlanItem(item.id) })
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.Bold)
                        if (item.note.isNotBlank()) Text(item.note, color = StudyDesign.muted)
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
            StudyCard {
                Text("抽卡", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("蓝色画卷 92.15%", color = StudyDesign.muted)
                Text("紫色 6%：抖音时长券 5% · 剧场碎片 1%", color = StudyDesign.muted)
                Text("金色 1.5%：游戏畅玩券 1.2% · 视频解锁卡 0.3%", color = StudyDesign.muted)
                Text("彩色番剧兑换券 0.35%", color = StudyDesign.muted)
                Text("连续30抽没有紫／金／彩时，第30抽直接出现紫色。", color = StudyDesign.muted, fontSize = 12.sp)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WalletChip("单抽券", state.inventory.singleTickets)
                    WalletChip("十连券", state.inventory.tenTickets)
                    WalletChip("夸夸值", state.profile.praisePoints)
                }
                Text("距保底：${(NON_NORMAL_PITY - state.drawsSinceNonNormal).coerceIn(1, NON_NORMAL_PITY)} 抽", fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        results = store.drawSingle()
                        message = if (results.isEmpty()) "需要1张单抽券或${SINGLE_DRAW_COST}夸夸值" else "完成单抽"
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("单抽") }
                Button(
                    onClick = {
                        results = store.drawTen()
                        message = if (results.isEmpty()) "需要1张十连券或${TEN_DRAW_COST}夸夸值" else "完成十连"
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("十连") }
            }
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
private fun WalletChip(label: String, value: Int) {
    Surface(color = StudyDesign.wheatSoft, shape = RoundedCornerShape(14.dp)) {
        Text("$label $value", Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 12.sp)
    }
}

private fun rarityColor(rarity: StudyRarity): Color = when (rarity) {
    StudyRarity.Normal -> Color(0xFFDCEAF4)
    StudyRarity.Rare -> Color(0xFFE8DDF2)
    StudyRarity.Epic -> Color(0xFFFFEDB8)
    StudyRarity.Rainbow -> Color(0xFFD8F3EF)
}

private enum class CollectionView(val label: String) {
    Scrolls("已解锁画卷"), Theaters("小剧场"), Entertainment("娱乐券"), Fragments("画卷碎片"),
}

@Composable
internal fun StudyCollectionScreen(state: StudyState, store: PostgraduateExamStore) {
    var view by remember { mutableStateOf(CollectionView.Scrolls) }
    var message by remember { mutableStateOf("") }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            StudyCard {
                Text("收藏", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("每套画卷只使用自己的专属碎片。", color = StudyDesign.muted)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CollectionView.entries.forEach { item ->
                        FilterChip(selected = item == view, onClick = { view = item }, label = { Text(item.label) })
                    }
                }
            }
        }
        when (view) {
            CollectionView.Scrolls -> {
                if (state.inventory.unlockedScrolls.isEmpty()) {
                    item { StudyCard { Text("还没有解锁画卷。每套需要${BLUE_FRAGMENTS_PER_SCROLL}枚专属碎片。", color = StudyDesign.muted) } }
                }
                items(state.inventory.unlockedScrolls) { title ->
                    StudyCard {
                        Icon(Icons.Outlined.Image, null, tint = StudyDesign.muted)
                        Text(title, fontWeight = FontWeight.Bold)
                        Text("完整画卷已解锁", color = StudyDesign.muted)
                    }
                }
            }
            CollectionView.Theaters -> {
                item {
                    StudyCard {
                        Text("剧场碎片：${state.inventory.theaterFragments}", fontWeight = FontWeight.Bold)
                        Text("每枚可解锁一章小剧场。", color = StudyDesign.muted)
                        Button(
                            onClick = { message = store.redeemEntertainment(StudyEntertainmentKind.Theater) },
                            enabled = state.inventory.theaterFragments > 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("解锁下一章") }
                    }
                }
                if (state.inventory.unlockedTheaters.isEmpty()) item { StudyCard { Text("还没有解锁小剧场", color = StudyDesign.muted) } }
                items(state.inventory.unlockedTheaters) { title -> StudyCard { Text(title, fontWeight = FontWeight.Bold) } }
            }
            CollectionView.Entertainment -> {
                item {
                    EntertainmentRow(
                        title = "抖音时长券 · 20分钟",
                        amount = state.inventory.douyinTickets,
                        onUse = { message = store.redeemEntertainment(StudyEntertainmentKind.Douyin) },
                    )
                }
                item {
                    EntertainmentRow(
                        title = "游戏畅玩券 · 120分钟",
                        amount = state.inventory.gameTickets,
                        onUse = { message = store.redeemEntertainment(StudyEntertainmentKind.Game) },
                    )
                }
                item {
                    EntertainmentRow(
                        title = "视频解锁卡",
                        amount = state.inventory.videoCards,
                        onUse = { message = store.redeemEntertainment(StudyEntertainmentKind.Video) },
                    )
                }
                item {
                    EntertainmentRow(
                        title = "番剧兑换券 · 3小时",
                        amount = state.inventory.animeTickets,
                        onUse = { message = store.redeemEntertainment(StudyEntertainmentKind.Anime) },
                    )
                }
                if (state.inventory.unlockedVideos.isNotEmpty()) {
                    item { Text("已解锁视频", fontSize = 19.sp, fontWeight = FontWeight.Bold) }
                    items(state.inventory.unlockedVideos) { title -> StudyCard { Text(title, fontWeight = FontWeight.Bold) } }
                }
            }
            CollectionView.Fragments -> {
                items(blueFragmentCatalog) { title ->
                    val amount = state.inventory.blueFragments[title] ?: 0
                    StudyCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(title, fontWeight = FontWeight.SemiBold)
                            Text("$amount/$BLUE_FRAGMENTS_PER_SCROLL", color = StudyDesign.muted)
                        }
                        StudyProgress(amount.toFloat() / BLUE_FRAGMENTS_PER_SCROLL)
                    }
                }
            }
        }
        item { StudyMessage(message, message.contains("不足")) }
    }
}

@Composable
private fun EntertainmentRow(title: String, amount: Int, onUse: () -> Unit) {
    StudyCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text("拥有 $amount", color = StudyDesign.muted)
            }
            Button(onClick = onUse, enabled = amount > 0) { Text("使用") }
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
        item { GuideCard("收藏", "紫色、金色和彩色奖励抽到后进入对应收藏：抖音券、小剧场碎片、游戏券、视频卡和番剧券。") }
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
