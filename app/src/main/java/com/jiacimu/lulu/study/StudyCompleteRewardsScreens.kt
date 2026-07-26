package com.jiacimu.lulu.study

import android.content.Intent
import android.net.Uri
import android.widget.ImageView
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.launch

private enum class GachaPool(val label: String) { Moonlight("月光池"), Classic("经典碎片池") }

@Composable
internal fun StudyGachaCompleteScreen(state: StudyState, store: PostgraduateExamStore) {
    var pool by remember { mutableStateOf(GachaPool.Moonlight) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GachaPool.entries.forEach { item ->
                FilterChip(selected = item == pool, onClick = { pool = item }, label = { Text(item.label) })
            }
        }
        Box(Modifier.fillMaxSize()) {
            when (pool) {
                GachaPool.Moonlight -> StudyGachaScreen(state, store)
                GachaPool.Classic -> StudyClassicGachaScreen()
            }
        }
    }
}

@Composable
private fun StudyClassicGachaScreen() {
    val legacy by StudyLegacyRewards.store.state.collectAsState()
    var results by remember { mutableStateOf(emptyList<StudyClassicDrawResult>()) }
    var message by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            StudyCard {
                Text("经典碎片池", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("保留旧版普通／稀有／史诗／彩色碎片池，以及视频、游戏、番剧券。", color = StudyDesign.muted)
                Text("普通72% · 稀有21% · 史诗6.5% · 彩色0.5%", color = StudyDesign.muted)
                Text("稀有保底25抽 · 史诗保底80抽", color = StudyDesign.muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LegacyWalletChip("经典十连券", legacy.legacyTenDrawTickets)
                    LegacyWalletChip("距稀有", (25 - legacy.drawsSinceRare).coerceIn(1, 25))
                    LegacyWalletChip("距史诗", (80 - legacy.drawsSinceEpic).coerceIn(1, 80))
                }
                Button(
                    onClick = {
                        results = StudyLegacyRewards.store.classicDrawTen()
                        message = if (results.isEmpty()) "没有经典十连券，请在学习币商店购买" else "经典十连完成"
                    },
                    enabled = legacy.legacyTenDrawTickets > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("经典十连") }
            }
        }
        if (results.isNotEmpty()) {
            item { Text("本次结果", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            items(results, key = { it.id }) { result ->
                StudyCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(result.title, fontWeight = FontWeight.Bold)
                            Text(result.type.name, color = StudyDesign.muted, fontSize = 12.sp)
                        }
                        Surface(color = classicRarityColor(result.rarity), shape = RoundedCornerShape(14.dp)) {
                            Text(result.rarity.label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                }
            }
        }
        item { StudyMessage(message) }
        item {
            StudyCard {
                Text("经典库存", fontWeight = FontWeight.Bold)
                Text("普通 ${legacy.normalFragments} · 稀有 ${legacy.rareFragments} · 史诗 ${legacy.epicFragments} · 彩色 ${legacy.rainbowFragments}")
                Text("游戏券 ${legacy.gameFragments} · 视频卡 ${legacy.videoFragments} · 番剧券 ${legacy.animeFragments}", color = StudyDesign.muted)
            }
        }
    }
}

private fun classicRarityColor(rarity: StudyClassicRarity): Color = when (rarity) {
    StudyClassicRarity.Common -> Color(0xFFE9E7E0)
    StudyClassicRarity.Rare -> Color(0xFFDCEAF4)
    StudyClassicRarity.Epic -> Color(0xFFE8DDF2)
    StudyClassicRarity.Rainbow -> Color(0xFFFFE2C8)
}

@Composable
private fun LegacyWalletChip(label: String, value: Int) {
    Surface(color = StudyDesign.wheatSoft, shape = RoundedCornerShape(13.dp)) {
        Text("$label $value", Modifier.padding(horizontal = 9.dp, vertical = 6.dp), fontSize = 12.sp)
    }
}

private enum class CompleteCollectionTab(val label: String) {
    Outfits("服装画卷"), Entertainment("娱乐券"), Theater("月光剧场"), Backpack("盲盒背包"), Media("媒体收藏"),
}

@Composable
internal fun StudyCollectionCompleteScreen(
    state: StudyState,
    onOpenGames: () -> Unit,
    onOpenWishes: () -> Unit,
) {
    val legacy by StudyLegacyRewards.store.state.collectAsState()
    val contents by StudyCollectionContents.store.contents.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(CompleteCollectionTab.Outfits) }
    var message by remember { mutableStateOf("") }
    var pendingMediaTitle by remember { mutableStateOf("") }
    var viewer by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var theaterTitle by remember { mutableStateOf<String?>(null) }
    var generatingTheater by remember { mutableStateOf(false) }

    fun persistUri(title: String, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        StudyLegacyRewards.store.attachMedia(title, uri.toString())
        message = "已为《$title》保存本机媒体"
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val title = pendingMediaTitle
        if (uri != null && title.isNotBlank()) persistUri(title, uri)
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val title = pendingMediaTitle
        if (uri != null && title.isNotBlank()) persistUri(title, uri)
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            StudyCard {
                Text("收藏与兑换", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("旧版服装、券、月光剧场、盲盒和媒体查看全部保留。", color = StudyDesign.muted)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CompleteCollectionTab.entries.forEach { item ->
                        FilterChip(selected = item == tab, onClick = { tab = item }, label = { Text(item.label) })
                    }
                }
            }
        }
        when (tab) {
            CompleteCollectionTab.Outfits -> {
                item {
                    StudyCard {
                        Text("碎片钱包", fontWeight = FontWeight.Bold)
                        Text("普通 ${legacy.normalFragments} · 稀有 ${legacy.rareFragments} · 史诗 ${legacy.epicFragments}")
                        Text("万能稀有 ${legacy.universalRareFragments} · 万能史诗 ${legacy.universalEpicFragments}", color = StudyDesign.muted)
                    }
                }
                items(legacyOutfitNames) { outfit ->
                    val amount = legacy.outfitFragments[outfit] ?: 0
                    val unlocked = outfit in legacy.unlockedOutfits
                    StudyCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(outfit, fontWeight = FontWeight.Bold)
                                Text(if (unlocked) "画卷已解锁" else "碎片 $amount/4", color = StudyDesign.muted)
                            }
                            if (unlocked) Icon(Icons.Outlined.CheckCircle, null, tint = StudyDesign.success)
                        }
                        StudyProgress(amount / 4f)
                        if (!unlocked) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                AssistChip(onClick = { message = StudyLegacyRewards.store.convertNormalToOutfit(outfit) }, enabled = legacy.normalFragments > 0, label = { Text("普通+1") })
                                AssistChip(onClick = { message = StudyLegacyRewards.store.useRareFragment(outfit) }, enabled = legacy.rareFragments > 0, label = { Text("稀有+1") })
                                AssistChip(onClick = { message = StudyLegacyRewards.store.useEpicFragment(outfit) }, enabled = legacy.epicFragments > 0, label = { Text("史诗+2") })
                                AssistChip(onClick = { message = StudyLegacyRewards.store.useUniversalRare(outfit) }, enabled = legacy.universalRareFragments > 0, label = { Text("万能稀有") })
                                AssistChip(onClick = { message = StudyLegacyRewards.store.useUniversalEpic(outfit) }, enabled = legacy.universalEpicFragments > 0, label = { Text("万能史诗") })
                            }
                        } else {
                            val uri = legacy.mediaUris[outfit]
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        pendingMediaTitle = outfit
                                        imagePicker.launch(arrayOf("image/*"))
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text(if (uri == null) "添加画卷图片" else "更换图片") }
                                Button(
                                    onClick = {
                                        if (uri == null) onOpenWishes() else viewer = outfit to false
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text(if (uri == null) "前往心愿馆生成" else "查看画卷") }
                            }
                        }
                    }
                }
            }
            CompleteCollectionTab.Entertainment -> {
                item {
                    StudyCard {
                        Text("游戏畅玩券：${legacy.gameFragments}", fontWeight = FontWeight.Bold)
                        Button(
                            onClick = {
                                message = StudyLegacyRewards.store.redeemGameTicket()
                                if (message.startsWith("已使用")) onOpenGames()
                            },
                            enabled = legacy.gameFragments > 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("使用并进入游戏 App") }
                    }
                }
                item {
                    StudyCard {
                        Text("视频解锁卡：${legacy.videoFragments}", fontWeight = FontWeight.Bold)
                        Button(onClick = { message = StudyLegacyRewards.store.redeemVideoCard() }, enabled = legacy.videoFragments > 0, modifier = Modifier.fillMaxWidth()) { Text("解锁下一个视频") }
                    }
                }
                item {
                    StudyCard {
                        Text("番剧兑换券：${legacy.animeFragments}", fontWeight = FontWeight.Bold)
                        Button(onClick = { message = StudyLegacyRewards.store.redeemAnimeTicket() }, enabled = legacy.animeFragments > 0, modifier = Modifier.fillMaxWidth()) { Text("解锁下一集番剧") }
                    }
                }
            }
            CompleteCollectionTab.Theater -> {
                item {
                    StudyCard {
                        Text("月光彩碎：${legacy.rainbowFragments}", fontWeight = FontWeight.Bold)
                        Button(onClick = { message = StudyLegacyRewards.store.unlockTheaterFromRainbow() }, enabled = legacy.rainbowFragments > 0, modifier = Modifier.fillMaxWidth()) { Text("解锁下一幕月光剧场") }
                    }
                }
                if (legacy.unlockedTheaters.isEmpty()) item { StudyCard { Text("还没有解锁月光剧场", color = StudyDesign.muted) } }
                items(legacyTheaterNames.filter { it in legacy.unlockedTheaters }) { title ->
                    StudyCard(Modifier.clickable { theaterTitle = title }) {
                        Text(title, fontWeight = FontWeight.Bold)
                        Text(if (contents[title].isNullOrBlank()) "已解锁，尚未生成剧情正文" else contents[title]!!.take(100), color = StudyDesign.muted, maxLines = 3)
                        Button(
                            onClick = {
                                if (!contents[title].isNullOrBlank()) {
                                    theaterTitle = title
                                } else {
                                    generatingTheater = true
                                    scope.launch {
                                        LuluAiServices.gateway.generate(
                                            characterId = state.profile.selectedCharacterId,
                                            facts = buildString {
                                                appendLine(state.roleStudyContext())
                                                appendLine("已解锁剧场：$title")
                                                appendLine("这是一段收藏剧情，不是现实中已经发生的事件。")
                                            },
                                            instruction = "根据角色真实人设、关系边界和已知记忆创作一段独立小剧场，500-900字。必须标明为虚构剧情，不得改写角色设定，不得默认亲密关系。",
                                            source = "考研",
                                            title = "月光剧场生成",
                                            maxTokens = 1400,
                                        ).onSuccess {
                                            StudyCollectionContents.store.save(title, it.text)
                                            theaterTitle = title
                                            message = "剧场正文已生成"
                                        }.onFailure { message = it.message ?: "剧场生成失败" }
                                        generatingTheater = false
                                    }
                                }
                            },
                            enabled = !generatingTheater,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (contents[title].isNullOrBlank()) "生成剧情正文" else "阅读") }
                    }
                }
            }
            CompleteCollectionTab.Backpack -> {
                item {
                    StudyCard {
                        Text("盲盒背包", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("共有 ${legacy.blindBoxes.size} 个盲盒", color = StudyDesign.muted)
                    }
                }
                if (legacy.blindBoxes.isEmpty()) item { StudyCard { Text("每累计25分钟专注会获得一个盲盒。", color = StudyDesign.muted) } }
                items(legacy.blindBoxes, key = { it.id }) { box ->
                    StudyCard {
                        Text("学习盲盒", fontWeight = FontWeight.Bold)
                        Text("内含学习币与普通碎片，打开前不会显示具体数量。", color = StudyDesign.muted)
                        Button(onClick = { message = StudyLegacyRewards.store.openBlindBox(box.id) }, modifier = Modifier.fillMaxWidth()) { Text("打开") }
                    }
                }
            }
            CompleteCollectionTab.Media -> {
                item { Text("已解锁视频", fontSize = 19.sp, fontWeight = FontWeight.Bold) }
                if (legacy.unlockedVideos.isEmpty()) item { StudyCard { Text("还没有解锁视频", color = StudyDesign.muted) } }
                items(legacy.unlockedVideos) { title -> MediaCollectionRow(title, true, legacy.mediaUris[title], onImport = { pendingMediaTitle = title; videoPicker.launch(arrayOf("video/*")) }, onView = { viewer = title to true }) }
                item { Text("已解锁番剧", fontSize = 19.sp, fontWeight = FontWeight.Bold) }
                if (legacy.unlockedAnime.isEmpty()) item { StudyCard { Text("还没有解锁番剧", color = StudyDesign.muted) } }
                items(legacy.unlockedAnime) { title -> MediaCollectionRow(title, true, legacy.mediaUris[title], onImport = { pendingMediaTitle = title; videoPicker.launch(arrayOf("video/*")) }, onView = { viewer = title to true }) }
            }
        }
        item { StudyMessage(message, message.contains("不足") || message.contains("失败")) }
    }

    viewer?.let { (title, video) ->
        val uri = legacy.mediaUris[title]
        if (uri != null) MediaViewerDialog(title, uri, video) { viewer = null } else viewer = null
    }
    theaterTitle?.let { title ->
        TheaterReaderDialog(title, contents[title].orEmpty(), onDismiss = { theaterTitle = null })
    }
}

@Composable
private fun MediaCollectionRow(title: String, video: Boolean, uri: String?, onImport: () -> Unit, onView: () -> Unit) {
    StudyCard {
        Text(title, fontWeight = FontWeight.Bold)
        Text(if (uri == null) "已解锁；请关联本机媒体文件" else "媒体已关联", color = StudyDesign.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text(if (uri == null) "选择文件" else "更换") }
            Button(onClick = onView, enabled = uri != null, modifier = Modifier.weight(1f)) { Text(if (video) "播放" else "查看") }
        }
    }
}

@Composable
private fun MediaViewerDialog(title: String, uri: String, video: Boolean, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = StudyDesign.card, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭") }
                }
                if (video) {
                    AndroidView(
                        factory = { context ->
                            VideoView(context).apply {
                                val controller = MediaController(context)
                                controller.setAnchorView(this)
                                setMediaController(controller)
                                setVideoURI(Uri.parse(uri))
                                setOnPreparedListener { start() }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    )
                } else {
                    AndroidView(
                        factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageURI(Uri.parse(uri)) } },
                        modifier = Modifier.fillMaxWidth().height(420.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TheaterReaderDialog(title: String, content: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = StudyDesign.card, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭") }
                    }
                }
                item { Text(content.ifBlank { "剧场正文尚未生成。" }) }
            }
        }
    }
}

private enum class CompleteShopTab(val label: String) { Praise("夸夸值商店"), Kudos("学习币商店") }

@Composable
internal fun StudyShopCompleteScreen(state: StudyState, store: PostgraduateExamStore) {
    val legacy by StudyLegacyRewards.store.state.collectAsState()
    var tab by remember { mutableStateOf(CompleteShopTab.Kudos) }
    var message by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompleteShopTab.entries.forEach { item ->
                FilterChip(selected = item == tab, onClick = { tab = item }, label = { Text(item.label) })
            }
        }
        when (tab) {
            CompleteShopTab.Praise -> StudyShopScreen(state, store)
            CompleteShopTab.Kudos -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                item {
                    StudyCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("学习币商店", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Text("学习币 ${legacy.kudos}/1000 · 今日刷新 ${legacy.shopRefreshCount} 次", color = StudyDesign.muted)
                            }
                            IconButton(onClick = { message = StudyLegacyRewards.store.refreshShop() }) { Icon(Icons.Outlined.Refresh, "刷新") }
                        }
                        Text("学习币由真实完成的专注分钟产生；与夸夸值分开计算。", color = StudyDesign.muted, fontSize = 12.sp)
                    }
                }
                items(legacy.shopItems, key = { it.id }) { item ->
                    StudyCard {
                        Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(item.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text(item.subtitle, color = StudyDesign.muted)
                                Text("库存 ${item.remaining}/${item.stock}", color = StudyDesign.muted, fontSize = 12.sp)
                            }
                            Text("${item.cost} 币", fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = { message = StudyLegacyRewards.store.buy(item.id) },
                            enabled = item.remaining > 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (item.remaining > 0) "购买" else "已售罄") }
                    }
                }
                item { StudyMessage(message, message.contains("不足") || message.contains("失败")) }
            }
        }
    }
}
