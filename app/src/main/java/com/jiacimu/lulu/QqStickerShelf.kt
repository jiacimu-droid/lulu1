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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.jiacimu.lulu.ai.VisionModelService
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream

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
    var showAddMenu by remember { mutableStateOf(false) }
    var showManifestDialog by remember { mutableStateOf(false) }
    var manifestText by remember { mutableStateOf("") }
    var importingManifest by remember { mutableStateOf(false) }
    var showVisionDialog by remember { mutableStateOf(false) }
    var visionBusy by remember { mutableStateOf(false) }
    var importPackName by remember { mutableStateOf("我的添加") }
    var stickerVisionMode by remember { mutableStateOf(StickerVisionRouting.mode(context)) }
    suspend fun summarizeVision(pack: String): String {
        if (stickerVisionMode != StickerVisionRouting.DEDICATED_ON_IMPORT) return ""
        val captioned = StickerVisionRouting.labelMissingAfterImport(context, pack)
        return when {
            captioned.error.isNotBlank() -> "；自动补描述 ${captioned.labeled} 张后中止：${captioned.error}"
            captioned.attempted > 0 -> "；已自动识图补描述 ${captioned.labeled} 张（${captioned.attempted} 次请求）"
            else -> ""
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var added = 0
            var failure = ""
            for (uri in uris.take(200)) {
                StickerLibraryStore.importImage(context, uri, pack = importPackName).onSuccess { added++ }
                    .onFailure { error -> failure = error.message.orEmpty() }
            }
            if (added > 0) { selectedTab = 0; selectedPack = importPackName.ifBlank { "我的添加" } }
            val automatic = if (added > 0) summarizeVision(importPackName.ifBlank { "我的添加" }) else ""
            onNotice(if (added > 0) "已加入 $added 张到「$importPackName」$automatic" else failure.ifBlank { "添加失败" })
        }
    }

    val wordPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val filename = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
            }.getOrNull().orEmpty().ifBlank { "表情包.docx" }
            runCatching { StickerWordDocument.import(context, uri, filename) }
                .onSuccess {
                    selectedTab = 0
                    selectedPack = it.pack
                    val automatic = summarizeVision(it.pack)
                    onNotice("Word 表情包「${it.pack}」：新增 ${it.imported} 张、跳过 ${it.skipped} 张" + automatic +
                        if (it.errors.isNotEmpty()) "；${it.errors.size} 张失败：${it.errors.first().take(90)}" else "")
                }.onFailure { onNotice(it.message ?: "Word 表情包导入失败") }
        }
    }
    val packPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val filename = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
            }.getOrNull().orEmpty().ifBlank { "表情包.zip" }
            runCatching { StickerPackArchive.import(context, uri, filename) }
                .onSuccess {
                    selectedTab = 0
                    selectedPack = it.pack
                    val automatic = summarizeVision(it.pack)
                    onNotice("已导入整组「${it.pack}」：新增 ${it.imported} 张、跳过 ${it.skipped} 张" + automatic +
                        if (it.errors.isNotEmpty()) "；${it.errors.size} 张失败：${it.errors.first().take(90)}" else "")
                }.onFailure { onNotice(it.message ?: "ZIP 导入失败") }
        }
    }
    val manifestPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= 200_000) { "清单过大，请拆分导入" }
                            out.write(buffer, 0, read)
                        }
                        out.toString("UTF-8")
                    } ?: error("无法读取清单")
                }
            }.onSuccess { manifestText = it; showManifestDialog = true }
                .onFailure { onNotice(it.message ?: "清单读取失败") }
        }
    }
    fun importManifest() {
        if (importingManifest) return
        val entries = runCatching {
            StickerFlexibleTextImport.parse(manifestText, importPackName)
        }.getOrElse {
            onNotice(it.message ?: "清单格式错误")
            return
        }
        importingManifest = true
        scope.launch {
            var imported = 0
            var failed = 0
            var firstError = ""
            for (entry in entries) {
                StickerLibraryStore.importRemote(context, entry)
                    .onSuccess { imported++ }
                    .onFailure {
                        failed++
                        if (firstError.isBlank()) firstError = it.message.orEmpty()
                    }
            }
            importingManifest = false
            if (imported > 0) { selectedTab = 0; selectedPack = entries.first().pack }
            showManifestDialog = false
            val automatic = StringBuilder()
            if (imported > 0) {
                for (pack in entries.map { it.pack }.distinct()) {
                    automatic.append(summarizeVision(pack))
                }
            }
            onNotice("已处理 ${entries.size} 张，成功 $imported，失败 $failed" + automatic.toString() +
                if (failed > 0) "；$firstError" else "")
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
                    Box {
                        IconButton(onClick = { showAddMenu = true }) {
                            Icon(Icons.Outlined.AddPhotoAlternate, "批量导入表情包")
                        }
                        DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                            Text("整组导入 · 优先 Word 图文文档", color = QqInk, fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                            OutlinedTextField(
                                value = importPackName,
                                onValueChange = { importPackName = it.take(40) },
                                label = { Text("分组名称") },
                                singleLine = true,
                                modifier = Modifier.width(230.dp).padding(horizontal = 8.dp),
                            )
                            DropdownMenuItem(
                                text = { Text("识图：关闭（仅导入原有描述）") },
                                onClick = {
                                    stickerVisionMode = StickerVisionRouting.OFF
                                    StickerVisionRouting.setMode(context, stickerVisionMode)
                                    showAddMenu = false
                                    onNotice("已关闭自动识图；已有文字描述不受影响")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("识图：聊天模型支持图片（同轮直接看图）") },
                                onClick = {
                                    stickerVisionMode = StickerVisionRouting.MULTIMODAL_CHAT
                                    StickerVisionRouting.setMode(context, stickerVisionMode)
                                    showAddMenu = false
                                    onNotice("聊天模型已标记为支持图片。只会在聊天时附少量候选原图，不使用独立识图模型；若模型其实不支持图片，请关闭此模式。")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("识图：缺描述时自动调用独立识图模型（收费）") },
                                onClick = {
                                    stickerVisionMode = StickerVisionRouting.DEDICATED_ON_IMPORT
                                    StickerVisionRouting.setMode(context, stickerVisionMode)
                                    showAddMenu = false
                                    onNotice("今后导入时仅对缺描述的表情自动识图；可能产生 API 费用，每批只尝试一次")
                                },
                            )
                            Text("当前：${when(stickerVisionMode) {
                                StickerVisionRouting.MULTIMODAL_CHAT -> "聊天模型同轮看图"
                                StickerVisionRouting.DEDICATED_ON_IMPORT -> "独立模型自动补描述"
                                else -> "不自动识图"
                            }}", color = QqMuted, fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                            DropdownMenuItem(
                                text = { Text("导入 Word 文档（推荐 · 整组图文）") },
                                onClick = {
                                    showAddMenu = false
                                    wordPicker.launch(arrayOf(
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                        "application/octet-stream",
                                    ))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("从相册批量添加") },
                                onClick = { showAddMenu = false; picker.launch("image/*") },
                            )
                            DropdownMenuItem(
                                text = { Text("导入 ZIP 图片包（其他格式）") },
                                onClick = {
                                    showAddMenu = false
                                    packPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("导入 JSON / CSV / TXT 清单") },
                                onClick = {
                                    showAddMenu = false
                                    manifestPicker.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("粘贴图片链接 / 清单") },
                                onClick = {
                                    showAddMenu = false
                                    manifestText = ""
                                    showManifestDialog = true
                                },
                            )
                        }
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
                if (selectedTab == 0 && stickers.any {
                        it.pack == selectedPack && it.visualDescription.isBlank()
                    }) {
                    val count = stickers.count { it.pack == selectedPack && it.visualDescription.isBlank() }
                    TextButton(
                        onClick = { showVisionDialog = true },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        Text("未标注 $count 张 · 批量识图（可选付费）", fontSize = 11.sp)
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

    if (showVisionDialog) {
        val unlabelled = stickers.filter { it.pack == selectedPack && it.visualDescription.isBlank() }
        val configured = VisionModelService.configuration(context).ready
        AlertDialog(
            onDismissRequest = { if (!visionBusy) showVisionDialog = false },
            title = { Text("为「$selectedPack」批量识图") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("这组还有 ${unlabelled.size} 张缺少画面描述；按每批 4 张计算，预计发起 ${(unlabelled.size + 3) / 4} 次识图请求。",
                        fontSize = 13.sp)
                    Text("调用的是「识图设置」中独立配置的模型，不会调用聊天模型；已经有描述的不重复识别。API 费用依你的中转站及模型定价计算，无法在这里保证具体金额。",
                        fontSize = 12.sp, color = QqMuted)
                    if (!configured) Text("尚未配置独立识图模型，请到设置 → 识图设置选择可看图的模型。",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = configured && !visionBusy && unlabelled.isNotEmpty(),
                    onClick = {
                        visionBusy = true
                        val targets = unlabelled.toList()
                        scope.launch {
                            var success = 0
                            var failedBatches = 0
                            var firstError = ""
                            for (batch in targets.chunked(4)) {
                                val result = VisionModelService.describeStickerBatch(context, batch.map { it.uri })
                                if (result.isFailure) {
                                    failedBatches++
                                    firstError = result.exceptionOrNull()?.message.orEmpty()
                                    // Do not retry an uncertain paid API call automatically.
                                    break
                                }
                                for (label in result.getOrThrow()) {
                                    val target = batch.getOrNull(label.index - 1) ?: continue
                                    StickerLibraryStore.rename(
                                        target.id, label.name.ifBlank { target.name },
                                        label.visualDescription, label.usageHint,
                                    )
                                    success++
                                }
                            }
                            visionBusy = false
                            showVisionDialog = false
                            onNotice("成功标注 $success 张" + if (failedBatches > 0) "；识图中止：$firstError（未自动重试收费请求）" else "")
                        }
                    },
                ) { Text(if (visionBusy) "识图中…" else "确认调用识图模型") }
            },
            dismissButton = {
                TextButton(onClick = { showVisionDialog = false }, enabled = !visionBusy) { Text("暂不识图") }
            },
        )
    }

    if (showManifestDialog) {
        AlertDialog(
            onDismissRequest = { if (!importingManifest) showManifestDialog = false },
            title = { Text("整组导入表情包") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("每行：HTTPS图片直链 | 名称 | 画面描述 | 使用语气 | 分组。也支持 JSON / CSV 清单；描述已填好的无需识图。",
                        color = QqMuted, fontSize = 12.sp)
                    OutlinedTextField(
                        value = manifestText, onValueChange = { manifestText = it.take(200_000) },
                        label = { Text("链接或清单内容") }, minLines = 4, maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("导入只下载并保存图片，不会自动调用付费识图模型。",
                        color = QqMuted, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { importManifest() }, enabled = !importingManifest && manifestText.isNotBlank()) {
                    Text(if (importingManifest) "正在导入…" else "整组保存")
                }
            },
            dismissButton = { TextButton(onClick = { showManifestDialog = false }, enabled = !importingManifest) { Text("取消") } },
        )
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
