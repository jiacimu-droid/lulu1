package com.jiacimu.lulu.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

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
    var revealing by remember { mutableStateOf(false) }
    var showingResults by remember { mutableStateOf(false) }

    fun draw(action: () -> List<StudyDrawResult>, insufficient: String) {
        val drawn = action()
        if (drawn.isEmpty()) {
            message = insufficient
        } else {
            results = drawn
            message = ""
            revealing = true
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(StudyDesign.paper, StudyDesign.wheatSoft.copy(alpha = .55f)))),
    ) {
        if (!showingResults) {
            CandyGachaCard(
                modifier = Modifier.fillMaxSize(),
                state = state,
                onSingle = {
                    draw(store::drawSingle, "需要1张单抽券或${SINGLE_DRAW_COST}夸夸值")
                },
                onTen = {
                    draw(store::drawTen, "需要1张十连券或${TEN_DRAW_COST}夸夸值")
                },
            )
            if (state.superMomentAvailable) {
                Button(
                    onClick = { message = store.claimSuperMoment() },
                    modifier = Modifier.align(Alignment.TopEnd).padding(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = StudyDesign.wheat, contentColor = StudyDesign.ink),
                ) { Text("领取十连券") }
            }
            if (message.isNotBlank()) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(18.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) { Text(message, Modifier.padding(horizontal = 18.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onErrorContainer) }
            }
        } else {
            GachaResultScreen(results = results, onBack = { showingResults = false; results = emptyList() })
        }

        if (revealing) {
            GachaAnimationOverlay(
                count = results.size,
                bestRarity = results.maxByOrNull { it.rarity.ordinal }?.rarity ?: StudyRarity.Normal,
                onFinished = { revealing = false; showingResults = true },
            )
        }
    }
}

@Composable
private fun GachaAnimationOverlay(count: Int, bestRarity: StudyRarity, onFinished: () -> Unit) {
    var burst by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (burst) 720f else -12f, tween(1_650), label = "扭蛋旋转")
    val scale by animateFloatAsState(if (burst) 1.38f else .72f, tween(1_650), label = "扭蛋放大")
    LaunchedEffect(Unit) {
        burst = true
        delay(1_850)
        onFinished()
    }
    val glow = rarityColor(bestRarity)
    Box(
        Modifier.fillMaxSize().background(Color(0xEE111526)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(glow.copy(alpha = .18f), radius = size.minDimension * .48f)
            repeat(18) { index ->
                val angle = index * (Math.PI * 2 / 18)
                val radius = size.minDimension * .34f
                drawCircle(
                    color = glow.copy(alpha = .45f),
                    radius = (3 + index % 4).dp.toPx(),
                    center = Offset(size.width / 2 + kotlin.math.cos(angle).toFloat() * radius, size.height / 2 + kotlin.math.sin(angle).toFloat() * radius),
                )
            }
        }
        Surface(
            modifier = Modifier.size(166.dp).graphicsLayer { rotationZ = rotation; scaleX = scale; scaleY = scale },
            shape = RoundedCornerShape(83.dp),
            color = Color.White,
            border = BorderStroke(7.dp, glow),
            shadowElevation = 26.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = StudyDesign.wheat, modifier = Modifier.size(54.dp))
                    Text(if (count == 10) "十连显现" else "愿望显现", fontWeight = FontWeight.Black, color = StudyDesign.ink)
                }
            }
        }
        Text("正在开启…", color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GachaResultScreen(results: List<StudyDrawResult>, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回抽卡") }
            Column(Modifier.weight(1f)) {
                Text("本次抽卡结果", fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text("${results.size}份奖励已收入收藏", color = StudyDesign.muted, fontSize = 12.sp)
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(results, key = { it.id }) { result ->
                Surface(
                    shape = RoundedCornerShape(22.dp), color = Color.White,
                    border = BorderStroke(1.dp, rarityColor(result.rarity)),
                    shadowElevation = 3.dp,
                ) {
                    Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(15.dp), color = rarityColor(result.rarity), modifier = Modifier.size(52.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.AutoAwesome, null, tint = StudyDesign.ink) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(result.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("${result.kind.label} · ${result.rarity.label}", color = StudyDesign.muted, fontSize = 12.sp)
                            if (!result.inventoryChanged) Text("已集满，本次不重复增加", color = StudyDesign.muted, fontSize = 11.sp)
                        }
                    }
                }
            }
            item { Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("收下结果") } }
        }
    }
}

@Composable
private fun CandyGachaCard(modifier: Modifier = Modifier, state: StudyState, onSingle: () -> Unit, onTen: () -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("学习扭蛋机", fontSize = 27.sp, fontWeight = FontWeight.Black, color = StudyDesign.ink)
            Spacer(Modifier.height(13.dp))
            Surface(
                color = StudyDesign.card,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, StudyDesign.border),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    GachaBalance("单抽券", state.inventory.singleTickets)
                    GachaBalance("十连券", state.inventory.tenTickets)
                    GachaBalance("夸夸值", state.profile.praisePoints)
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(270.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2, size.height * .38f)
                    val domeRadius = size.minDimension * .31f
                    drawCircle(Color.White.copy(alpha = .72f), domeRadius, center)
                    drawCircle(StudyDesign.border, domeRadius, center, style = Stroke(3.dp.toPx()))
                    drawCircle(Color(0xFFDCEAF4), 21.dp.toPx(), Offset(center.x - 50.dp.toPx(), center.y + 24.dp.toPx()))
                    drawCircle(Color(0xFFE8DDF2), 19.dp.toPx(), Offset(center.x - 9.dp.toPx(), center.y + 38.dp.toPx()))
                    drawCircle(Color(0xFFFFEDB8), 22.dp.toPx(), Offset(center.x + 37.dp.toPx(), center.y + 22.dp.toPx()))
                    drawCircle(Color(0xFFD8F3EF), 17.dp.toPx(), Offset(center.x + 7.dp.toPx(), center.y - 5.dp.toPx()))
                    drawRoundRect(
                        color = StudyDesign.wheat,
                        topLeft = Offset(size.width * .24f, size.height * .63f),
                        size = androidx.compose.ui.geometry.Size(size.width * .52f, size.height * .25f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(25.dp.toPx()),
                    )
                    drawCircle(StudyDesign.ink, 21.dp.toPx(), Offset(size.width / 2, size.height * .72f))
                    drawCircle(StudyDesign.card, 9.dp.toPx(), Offset(size.width / 2, size.height * .72f))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onSingle, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, StudyDesign.wheat)) {
                    Text("单抽", color = StudyDesign.ink, fontWeight = FontWeight.Bold)
                }
                Button(onClick = onTen, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = StudyDesign.wheat, contentColor = StudyDesign.ink)) {
                    Text("十连抽", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun GachaBalance(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), fontSize = 22.sp, fontWeight = FontWeight.Black, color = StudyDesign.ink)
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = StudyDesign.muted)
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
        CollectionTicket("抖音时长券 · 20分钟", state.inventory.douyinTickets) { message = store.redeemEntertainment(StudyEntertainmentKind.Douyin) },
        CollectionTicket("游戏局数券 · 4局", state.inventory.gameRoundTickets) { message = store.redeemEntertainment(StudyEntertainmentKind.GameRound) },
        CollectionTicket("小剧场券", state.inventory.theaterFragments) {
            message = store.redeemEntertainment(StudyEntertainmentKind.Theater)
            if (!message.contains("不足") && !message.contains("全部")) onOpenTheater()
        },
        CollectionTicket("电影券 · 1部", state.inventory.gameTickets) { message = store.redeemEntertainment(StudyEntertainmentKind.Game) },
        CollectionTicket("视频解锁卡", state.inventory.videoCards) { message = store.redeemEntertainment(StudyEntertainmentKind.Video) },
        CollectionTicket("影视剧一季兑换券", state.inventory.animeTickets) { message = store.redeemEntertainment(StudyEntertainmentKind.Anime) },
    )

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(tickets) { CollectionTicketCard(it) }
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
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = StudyDesign.card, border = BorderStroke(1.dp, StudyDesign.border)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ticket.title, fontWeight = FontWeight.Bold)
                Text("拥有 ${ticket.amount}", color = StudyDesign.muted, fontSize = 12.sp)
            }
            Button(
                onClick = ticket.use,
                enabled = ticket.amount > 0,
                colors = ButtonDefaults.buttonColors(containerColor = StudyDesign.wheat, contentColor = StudyDesign.ink),
            ) { Text("使用") }
        }
    }
}

@Composable
private fun FragmentProgressCard(title: String, amount: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = StudyDesign.card,
        border = BorderStroke(1.dp, StudyDesign.border),
    ) {
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
        item { GuideCard("抽卡概率", "蓝色画卷93.95%；紫色4.5%（抖音券2.5%、游戏局数券1%、小剧场券1%）；金色1.2%（电影券0.8%、视频解锁卡0.4%）；彩色影视剧一季兑换券0.35%。") }
        item { GuideCard("保底", "连续30抽没有紫／金／彩时，第30抽直接出现紫色结果；紫色保底内部按抖音券、游戏局数券和小剧场券的原始比例抽取。") }
        item { GuideCard("画卷碎片", "每套画卷需要10枚自己的专属碎片。已满后仍显示本次抽中物，但不重复计入。") }
        item { GuideCard("收藏", "抽到的奖励都会进入收藏：抖音20分钟券、游戏4局券、小剧场券、电影券、视频解锁卡和影视剧一季兑换券。") }
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
