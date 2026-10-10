package com.jiacimu.lulu

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val kaomojiChoices = listOf(
    "开心" to "(๑˃̵ᴗ˂̵)و",
    "害羞" to "(⁄ ⁄•⁄ω⁄•⁄ ⁄)",
    "喜欢" to "(♡˙︶˙♡)",
    "委屈" to "(｡•́︿•̀｡)",
    "撒娇" to "(´｡• ᵕ •｡\u0060)♡",
    "得意" to "(｡•̀ᴗ-)✧",
    "震惊" to "(⊙_⊙)",
    "疑惑" to "(・・?)",
    "生气" to "(╬ಠ益ಠ)",
    "加油" to "(ง •̀_•́)ง",
    "流泪" to "(╥﹏╥)",
    "摆手" to "(｡･ω･)ﾉﾞ",
    "贴贴" to "(づ｡◕‿‿◕｡)づ",
    "睡觉" to "(－ω－) zzZ",
    "吐舌" to "(๑>؂<๑)",
    "坏笑" to "(￣▽￣)~*",
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QqStickerShelf(
    onSendSticker: (LuluSticker) -> Boolean,
    onInsertText: (String) -> Unit,
    onNotice: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(context) {
        StickerLibraryStore.initialize(context)
        StickerLibraryStore.ensureBuiltIns(context)
    }
    val stickers by StickerLibraryStore.items.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedPack by remember { mutableStateOf("猫猫表情") }
    val packs = remember(stickers) { stickers.map(LuluSticker::pack).distinct() }
    LaunchedEffect(packs) {
        if (packs.isNotEmpty() && selectedPack !in packs) selectedPack = packs.first()
    }
    var selectedSticker by remember { mutableStateOf<LuluSticker?>(null) }
    var editName by remember { mutableStateOf("") }
    var editDescription by remember { mutableStateOf("") }
    var editUsage by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var added = 0
            var failure = ""
            for (uri in uris.take(30)) {
                StickerLibraryStore.importImage(context, uri).onSuccess { added++ }
                    .onFailure { error -> failure = error.message.orEmpty() }
            }
            onNotice(if (added > 0) "已加入 $added 张自选表情；长按表情可命名、收藏或排序" else failure.ifBlank { "添加失败" })
        }
    }
    Surface(color = QqPage, border = BorderStroke(1.dp, QqBorder)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("表情包", "颜文字", "★ 收藏").forEachIndexed { index, title ->
                    FilterChip(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        label = { Text(title, fontSize = 11.sp) },
                    )
                }
                Spacer(Modifier.weight(1f))
                if (selectedTab != 1) {
                    IconButton(onClick = { picker.launch("image/*") }) {
                        Icon(Icons.Outlined.AddPhotoAlternate, "从相册添加表情包")
                    }
                }
            }
            if (selectedTab == 1) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().height(211.dp),
                    contentPadding = PaddingValues(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    items(kaomojiChoices) { (name, symbol) ->
                        Surface(
                            modifier = Modifier.combinedClickable(
                                onClick = { onInsertText(symbol) },
                                onLongClick = { onInsertText(symbol) },
                            ),
                            color = QqIconSurface,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Column(
                                Modifier.height(55.dp).padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(symbol, color = QqInk, fontSize = 14.sp, maxLines = 1)
                                Text(name, color = QqMuted, fontSize = 10.sp)
                            }
                        }
                    }
                }
            } else {
                if (selectedTab == 0 && packs.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        packs.forEach { category ->
                            FilterChip(
                                selected = selectedPack == category,
                                onClick = { selectedPack = category },
                                label = { Text(category, fontSize = 11.sp) },
                            )
                        }
                    }
                }
                val visible = if (selectedTab == 2) stickers.filter(LuluSticker::favorite)
                else stickers.filter { it.pack == selectedPack }
                if (visible.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(166.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (selectedTab == 2) "还没有收藏表情" else "你的专属表情图库还是空的", color = QqInk, fontSize = 13.sp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (selectedTab == 2) "长按表情，点星星即可收藏" else "先从相册选择你喜欢的表情包，不会自动塞入陌生素材",
                                color = QqMuted, fontSize = 11.sp,
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.fillMaxWidth().height(224.dp),
                        contentPadding = PaddingValues(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        items(visible, key = LuluSticker::id) { sticker ->
                            Surface(
                                modifier = Modifier.combinedClickable(
                                    onClick = { if (!onSendSticker(sticker)) onNotice("这张表情暂时无法发送") },
                                    onLongClick = {
                                        selectedSticker = sticker
                                        editName = sticker.name
                                        editDescription = sticker.visualDescription
                                        editUsage = sticker.usageHint
                                    },
                                ),
                                shape = RoundedCornerShape(12.dp),
                                color = QqIconSurface,
                                border = BorderStroke(1.dp, QqBorder),
                            ) {
                                Box(Modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
                                    LuluSelectedPhoto(
                                        imageUri = sticker.uri,
                                        modifier = Modifier.fillMaxSize().padding(5.dp),
                                    )
                                    if (sticker.favorite) {
                                        Text("★", color = QqInk, fontSize = 15.sp,
                                            modifier = Modifier.align(Alignment.TopEnd).padding(3.dp))
                                    }
                                }
                            }
                        }
                    }
                    Text("点按发送；长按可收藏、改名、排序或删除", color = QqMuted,
                        fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
            Text(
                "开源表情来源：OpenMoji · CC BY-SA 4.0；由你自行添加的表情仍归各自作者所有。",
                modifier = Modifier.padding(top = 3.dp, bottom = 2.dp),
                color = QqMuted,
                fontSize = 9.sp,
            )
        }
    }

    selectedSticker?.let { sticker ->
        AlertDialog(
            onDismissRequest = { selectedSticker = null },
            title = { Text("管理表情包") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    LuluSelectedPhoto(imageUri = sticker.uri, modifier = Modifier.size(116.dp))
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it.take(90) },
                        label = { Text("表情名称") },
                        supportingText = { Text("这张图的简短名字，例如：猫猫亲亲") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editDescription,
                        onValueChange = { editDescription = it.take(400) },
                        label = { Text("画面说明 · 角色据此认识图片") },
                        supportingText = { Text("具体说明图里有什么、表情和姿势怎样；预装表情已自动填好") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editUsage,
                        onValueChange = { editUsage = it.take(160) },
                        label = { Text("可能的语气 / 使用场合") },
                        supportingText = { Text("比如：撒娇、得意、求夸夸；只是建议，不是图像事实") },
                        minLines = 1,
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            StickerLibraryStore.toggleFavorite(sticker.id)
                            selectedSticker = null
                        }) { Icon(if (sticker.favorite) Icons.Outlined.Star else Icons.Outlined.StarOutline, "切换收藏") }
                        Text(if (sticker.favorite) "已收藏" else "收藏", fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            StickerLibraryStore.move(sticker.id, -1)
                            selectedSticker = null
                        }) { Icon(Icons.Outlined.ArrowBack, "顺序前移") }
                        IconButton(onClick = {
                            StickerLibraryStore.move(sticker.id, 1)
                            selectedSticker = null
                        }) { Icon(Icons.Outlined.ArrowForward, "顺序后移") }
                    }
                    TextButton(onClick = {
                        StickerLibraryStore.remove(sticker.id)
                        selectedSticker = null
                        onNotice("已从表情图库移除；聊天里曾发送的图片不会被删除")
                    }) {
                        Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("从我的图库删除")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    StickerLibraryStore.rename(sticker.id, editName, editDescription, editUsage)
                    selectedSticker = null
                }) { Text("保存名称") }
            },
            dismissButton = { TextButton(onClick = { selectedSticker = null }) { Text("取消") } },
        )
    }
}
