package com.jiacimu.lulu.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import java.time.LocalDate

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
                Text("月光抽卡", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("普通概率：蓝色88% · 紫色6% · 抖音5% · 番外小剧场1%", color = StudyDesign.muted)
                Text("连续30次未抽到非蓝奖励时触发保底。", color = StudyDesign.muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WalletChip("单抽券", state.inventory.singleTickets)
                    WalletChip("十连券", state.inventory.tenTickets)
                    WalletChip("安全抽", state.inventory.safePurpleTickets)
                }
                Text("夸夸值：${state.profile.praisePoints} · 距离保底：${(30 - state.drawsSinceNonNormal).coerceIn(1, 30)} 抽", fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        results = store.drawSingle()
                        message = if (results.isEmpty()) "没有单抽券，且夸夸值不足20" else "完成单抽"
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("单抽") }
                Button(
                    onClick = {
                        results = store.drawTen()
                        message = if (results.isEmpty()) "没有十连券，且夸夸值不足180" else "完成十连"
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("十连") }
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    results = store.drawSafePurple()
                    message = if (results.isEmpty()) "今日安全抽已使用，或没有安全抽券" else "安全抽完成"
                },
                enabled = state.safeDrawUsedDate != LocalDate.now().toString() && state.inventory.safePurpleTickets > 0,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("今日安全抽 · 必得紫色碎片") }
        }
        if (results.isNotEmpty()) {
            item { Text("本次结果", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            items(results, key = { it.id }) { result ->
                StudyCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(result.title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(result.rarityLabel, color = StudyDesign.muted)
                        }
                        Surface(color = rarityColor(result.kind), shape = CircleShape) {
                            Text(result.kind.label(), Modifier.padding(horizontal = 11.dp, vertical = 7.dp), fontSize = 12.sp)
                        }
                    }
                    if (!result.inventoryChanged) Text("该蓝色收藏已满，但仍完整展示了本次抽中物。", color = StudyDesign.muted)
                }
            }
        }
        item { StudyMessage(message) }
        item {
            StudyCard {
                Text("神秘盒子", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("拥有 ${state.inventory.mysteryBoxes} 个；可开出夸夸值，并有概率获得万能蓝碎片。", color = StudyDesign.muted)
                Button(
                    onClick = { message = store.openMysteryBox() },
                    enabled = state.inventory.mysteryBoxes > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("打开一个") }
            }
        }
        if (state.superMomentAvailable) {
            item {
                StudyCard {
                    Text("Super Moment", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("完成阶段节点后，选择一个奖励。", color = StudyDesign.muted)
                    StudySuperChoice.entries.forEach { choice ->
                        OutlinedButton(
                            onClick = { message = store.claimSuperMoment(choice) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(choice.label) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WalletChip(label: String, value: Int) {
    Surface(color = StudyDesign.wheatSoft, shape = RoundedCornerShape(14.dp)) {
        Text("$label $value", Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 12.sp)
    }
}

private fun StudyDrawKind.label(): String = when (this) {
    StudyDrawKind.BlueFragment -> "蓝色"
    StudyDrawKind.PurpleFragment -> "紫色"
    StudyDrawKind.VideoFragment -> "视频"
    StudyDrawKind.TheaterFragment -> "小剧场"
}

private fun rarityColor(kind: StudyDrawKind): Color = when (kind) {
    StudyDrawKind.BlueFragment -> Color(0xFFDDE8F1)
    StudyDrawKind.PurpleFragment -> Color(0xFFE8DDF2)
    StudyDrawKind.VideoFragment -> Color(0xFFE2EFE5)
    StudyDrawKind.TheaterFragment -> Color(0xFFFFE5D6)
}

private enum class CollectionView(val label: String) { Scrolls("已解锁画卷"), Videos("抖音收藏"), Theaters("小剧场"), Fragments("碎片") }

@Composable
internal fun StudyCollectionScreen(state: StudyState, store: PostgraduateExamStore) {
    var view by remember { mutableStateOf(CollectionView.Scrolls) }
    var message by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            StudyCard {
                Text("收藏", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("万能蓝碎片 ${state.inventory.universalBlueFragments} · 紫色碎片 ${state.inventory.purpleFragments}", color = StudyDesign.muted)
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
                if (state.inventory.unlockedScrolls.isEmpty()) item { StudyCard { Text("还没有解锁画卷。集齐每种蓝碎片5枚可解锁。", color = StudyDesign.muted) } }
                items(state.inventory.unlockedScrolls) { title -> StudyCard { Icon(Icons.Outlined.Image, null, tint = StudyDesign.muted); Text(title, fontWeight = FontWeight.Bold) } }
            }
            CollectionView.Videos -> {
                item {
                    StudyCard {
                        Text("抖音碎片：${state.inventory.entertainmentFragments[StudyEntertainmentKind.Douyin] ?: 0}")
                        Button(
                            onClick = { message = store.redeemEntertainment(StudyEntertainmentKind.Douyin) },
                            enabled = (state.inventory.entertainmentFragments[StudyEntertainmentKind.Douyin] ?: 0) > 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("兑换下一个短视频") }
                    }
                }
                if (state.inventory.unlockedVideos.isEmpty()) item { StudyCard { Text("还没有解锁短视频", color = StudyDesign.muted) } }
                items(state.inventory.unlockedVideos) { title -> StudyCard { Text(title, fontWeight = FontWeight.Bold); Text("已解锁，可在后续媒体资源迁入后播放。", color = StudyDesign.muted) } }
            }
            CollectionView.Theaters -> {
                item {
                    StudyCard {
                        Text("小剧场碎片：${state.inventory.entertainmentFragments[StudyEntertainmentKind.SideStory] ?: 0}")
                        Button(
                            onClick = { message = store.redeemEntertainment(StudyEntertainmentKind.SideStory) },
                            enabled = (state.inventory.entertainmentFragments[StudyEntertainmentKind.SideStory] ?: 0) > 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("兑换下一个小剧场") }
                    }
                }
                if (state.inventory.unlockedTheaters.isEmpty()) item { StudyCard { Text("还没有解锁小剧场", color = StudyDesign.muted) } }
                items(state.inventory.unlockedTheaters) { title -> StudyCard { Text(title, fontWeight = FontWeight.Bold) } }
            }
            CollectionView.Fragments -> {
                item {
                    StudyCard {
                        Text("蓝色收藏碎片", fontWeight = FontWeight.Bold)
                        Text("万能蓝碎片：${state.inventory.universalBlueFragments}", color = StudyDesign.muted)
                        Button(
                            onClick = { message = store.applyUniversalBlue() },
                            enabled = state.inventory.universalBlueFragments > 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("自动补最少的一项") }
                    }
                }
                items(blueFragmentCatalog) { key ->
                    val amount = state.inventory.blueFragments[key] ?: 0
                    StudyCard(Modifier.clickable(enabled = state.inventory.universalBlueFragments > 0 && amount < 5) { message = store.applyUniversalBlue(key) }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(key, fontWeight = FontWeight.SemiBold)
                            Text("$amount/5", color = StudyDesign.muted)
                        }
                        StudyProgress(amount / 5f)
                    }
                }
            }
        }
        item { StudyMessage(message) }
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
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            StudyCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("每日商店", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("夸夸值：${state.profile.praisePoints} · 刷新次数：${state.shopRefreshCount}", color = StudyDesign.muted)
                    }
                    IconButton(onClick = { message = store.refreshShop() }) { Icon(Icons.Outlined.Refresh, "刷新") }
                }
                Text("首次进入直接显示当日商品；不需要手动刷新才能购买。", color = StudyDesign.muted, fontSize = 12.sp)
            }
        }
        items(state.shopItems, key = { it.id }) { item ->
            StudyCard {
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(item.subtitle, color = StudyDesign.muted)
                        Text("库存 ${item.remaining}/${item.stock}", color = StudyDesign.muted, fontSize = 12.sp)
                    }
                    Text("${item.cost} 夸夸值", fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { message = store.buyShopItem(item.id) },
                    enabled = item.remaining > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (item.remaining > 0) "购买" else "已售罄") }
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
                Text("本页保留旧版玩法说明，并明确角色与系统的职责边界。", color = StudyDesign.muted)
            }
        }
        item { GuideCard("陪伴与夸夸", "签到、睡眠奖励、阶段反馈和学习回应都由当前角色结合人设、关系、记忆和真实数据处理。睡眠时间只是参考基线，系统不会硬性否决。") }
        item { GuideCard("今日与计划", "今日页包含待办、AI时间表、明日待办和Tips；计划页保存周计划与月计划。AI生成日程时只安排未完成内容，并保留缓冲。") }
        item { GuideCard("番茄钟", "时长与语音开关会持久保存。完成后同步累计学习时长、任务进度、经验、夸夸值、神秘盒子和阶段奖励。") }
        item { GuideCard("抽卡概率", "每一抽独立按蓝色88%、紫色6%、抖音视频5%、番外小剧场1%结算。连续30抽未获得非蓝奖励触发保底；安全抽必得紫色。") }
        item { GuideCard("蓝色碎片已满", "蓝色收藏达到5/5后，仍会完整显示本次抽中了什么，只是不重复增加库存。万能蓝碎片可补未满项目。") }
        item { GuideCard("收藏与娱乐兑换", "抽到的抖音和小剧场碎片进入收藏页，再兑换下一个未解锁内容；蓝碎片集齐后解锁画卷。") }
        item { GuideCard("商店与成就", "商店每日自动生成商品，支持库存、售罄与手动刷新；成就和等级奖励分别领取，不会因首次进入全部耗尽。") }
        item { GuideCard("数据安全", "学习数据使用主存档和备份存档双份保存；日期切换只创建新一天的默认任务，不会删除最近90天记录。") }
    }
}

@Composable
private fun GuideCard(title: String, content: String) {
    StudyCard {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(content, color = StudyDesign.muted)
    }
}
