package com.jiacimu.lulu

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
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.LuluAppearanceCatalog
import com.jiacimu.lulu.data.LuluCssSheet
import com.jiacimu.lulu.data.LuluCssStore
import com.jiacimu.lulu.data.LuluCssTargets
import com.jiacimu.lulu.design.LuluCssArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.jiacimu.lulu.design.LuluAppearanceTheme
import com.jiacimu.lulu.design.LuluCssBackground

/**
 * A blank, owner-authored native CSS workshop. Old recipe cards are intentionally absent.
 * Imported CSS remains a draft until Apply succeeds; the old saved CSS is always recoverable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuluAppearanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val saved by LuluCssStore.css.collectAsState()
    var source by rememberSaveable { mutableStateOf(saved) }
    var scope by rememberSaveable { mutableStateOf("Home") }
    var selectScope by remember { mutableStateOf(false) }
    var selectCover by remember { mutableStateOf(false) }
    var selectWidget by remember { mutableStateOf(false) }
    var widgetCode by rememberSaveable { mutableStateOf<String?>(null) }
    val widget = LuluWidgetKind.entries.firstOrNull { it.code == widgetCode }
    var pendingImageSelector by rememberSaveable { mutableStateOf<String?>(null) }
    val importScope = rememberCoroutineScope()
    val apps = remember { desktopCssApps() }
    var scopeSearch by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val sheet = remember(source) { LuluCssSheet.parse(source) }
    val preview = LuluAppearanceCatalog.builtIns.first()

    val importCss = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val stream = context.contentResolver.openInputStream(uri) ?: error("读取失败")
                val text = stream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val result = StringBuilder()
                    val buffer = CharArray(4096)
                    while (true) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        result.append(buffer, 0, n)
                        require(result.length <= LuluCssSheet.MAX_LENGTH) { "CSS 不能超过 64 KB" }
                    }
                    result.toString()
                }
                source = text
            }.onSuccess { status = "已导入草稿，确认预览后点「应用到页面」" }
                .onFailure { status = it.message ?: "导入失败" }
        }
    }
    val exportCss = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/css"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(source)
                } ?: error("无法写入文件")
            }.onSuccess { status = "CSS 文件已导出；图片素材需在其他手机重新导入" }
                .onFailure { status = it.message ?: "导出失败" }
        }
    }
    val importImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val selector = pendingImageSelector
        pendingImageSelector = null
        if (uri != null && selector != null) importScope.launch {
            runCatching { withContext(Dispatchers.IO) { LuluCssStore.importImage(context, uri) } }
                .onSuccess { asset ->
                    source += "\n$selector { background-image: url(\"$asset\"); }\n"
                    status = "图片已加入草稿，点「应用到页面」生效"
                }
                .onFailure { status = it.message ?: "图片导入失败" }
        }
    }

    if (selectCover) {
        ModalBottomSheet(onDismissRequest = { selectCover = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text("桌面应用封面", style = MaterialTheme.typography.titleLarge)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    items(apps, key = { it.first }) { (id, title) ->
                        val selector = LuluCssTargets.coverSelector(id)
                        val style = sheet.element("Home", "launcher", "app-$id")
                        val token = LuluCssSheet.assetToken(style["background-image"])
                        ListItem(
                            headlineContent = { Text(title) },
                            supportingContent = { Text(selector) },
                            leadingContent = if (token == null) null else ({
                                LuluCssArtwork(token, Modifier.size(42.dp), style, maxEdge = 256)
                            }),
                            trailingContent = {
                                if (token != null) TextButton(onClick = {
                                    source += "\n$selector { background-image: none; }\n"
                                    status = "已在草稿中恢复封面"
                                }) { Text("恢复") }
                            },
                            modifier = Modifier.clickable {
                                pendingImageSelector = selector
                                importImage.launch("image/*")
                            },
                        )
                    }
                }
            }
        }
    }

    if (selectWidget) {
        ModalBottomSheet(onDismissRequest = { selectWidget = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text("选择小组件", style = MaterialTheme.typography.titleLarge)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    items(LuluWidgetKind.entries, key = { it.code }) { kind ->
                        ListItem(
                            headlineContent = { Text(kind.title) },
                            supportingContent = { Text(LuluCssTargets.widgetSelector(kind.code)) },
                            modifier = Modifier.clickable {
                                widgetCode = kind.code
                                scope = "Home"
                                selectWidget = false
                            },
                        )
                    }
                }
            }
        }
    }

    if (selectScope) {
        ModalBottomSheet(onDismissRequest = { selectScope = false }) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("选择要装修的页面", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = scopeSearch, onValueChange = { scopeSearch = it },
                    label = { Text("搜索页面") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(LuluCssTargets.pages.filter {
                        scopeSearch.isBlank() || it.label.contains(scopeSearch, true) ||
                            it.id.contains(scopeSearch, true)
                    }, key = { it.id }) { (id, name) ->
                        ListItem(
                            headlineContent = { Text(name) },
                            supportingContent = { Text("#" + id) },
                            trailingContent = {
                                if (id == scope) Icon(Icons.Outlined.Check, contentDescription = null)
                            },
                            modifier = Modifier.fillMaxWidth().clickable {
                                scope = id
                                selectScope = false
                                scopeSearch = ""
                            },
                        )
                        HorizontalDivider()
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CSS 装修工坊", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { inset ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inset),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { selectCover = true }, modifier = Modifier.weight(1f)) {
                        Text("应用封面")
                    }
                    OutlinedButton(onClick = { selectWidget = true }, modifier = Modifier.weight(1f)) {
                        Text("小组件 CSS")
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { selectScope = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text(
                        LuluCssTargets.pages.firstOrNull { it.id == scope }?.label
                            ?: scope,
                        modifier = Modifier.weight(1f),
                    )
                    Text("#" + scope + "  ", style = MaterialTheme.typography.labelMedium)
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "更换页面")
                }
            }
            if (scope == "ListenTogether") {
                item {
                    Text("一起听 · 独立皮肤", style = MaterialTheme.typography.titleMedium)
                    Text("聊天的配色自动继承进房间；下面只覆盖音乐小窗。可自定义唱片、头像、连接线、歌单及播放区。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val snippet = """
                                #ListenTogether { --paper: #fff5f8; --ink: #47343e; }
                                #ListenTogether .music-room { border-radius: 28dp; }
                                #ListenTogether .music-artwork { height: 210dp; }
                                #ListenTogether .music-links { color: #d79aaf; border-color: #e8c7d3; border-width: 8dp; }
                                #ListenTogether .music-avatar-role { width: 60dp; }
                                #ListenTogether .music-avatar-user { width: 60dp; }
                                #ListenTogether .music-vinyl { width: 120dp; }
                                #ListenTogether .music-track-title { color: #47343e; font-size: 19sp; }
                                #ListenTogether .music-play { background-color: #b67893; color: #ffffff; width: 58dp; }
                                #ListenTogether .music-playlist-card { background-color: #fff; border-radius: 18dp; }
                            """.trimIndent()
                            if (!source.contains("#ListenTogether .music-vinyl")) {
                                source += "\n" + snippet + "\n"
                                status = "已插入一起听模板；可修改数值和导入局部图片后应用"
                            } else status = "已存在一起听唱片样式，可以直接编辑"
                        }) { Text("插入一起听样式") }
                        OutlinedButton(onClick = {
                            pendingImageSelector = "#ListenTogether .music-vinyl"
                            importImage.launch("image/*")
                        }) { Text("替换唱片图片") }
                        OutlinedButton(onClick = {
                            pendingImageSelector = "#ListenTogether .music-room"
                            importImage.launch("image/*")
                        }) { Text("房间背景") }
                    }
                }
            }
            if (scope == "WatchTogether") {
                item {
                    Text("一起看 · 独立皮肤", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            if (!source.contains("#WatchTogether .watch-player")) {
                                source += "\n#WatchTogether .watch-player { background-color: #252333; border-radius: 22dp; }\n" +
                                    "#WatchTogether .watch-chat { background-color: #f9f5fb; border-radius: 20dp; }\n"
                            }
                            status = "已加入一起看的播放器和聊天区域样式"
                        }) { Text("插入一起看样式") }
                        OutlinedButton(onClick = {
                            pendingImageSelector = "#WatchTogether"
                            importImage.launch("image/*")
                        }) { Text("放映室背景") }
                    }
                }
            }
            if (scope == "Home" && widget != null) {
                item {
                    val selector = LuluCssTargets.widgetSelector(widget.code)
                    Text(widget.title, style = MaterialTheme.typography.titleMedium)
                    Text(selector, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace)
                    LuluAppearanceTheme(preview, scope = "Home", sheetOverride = sheet) {
                        Surface(color = MaterialTheme.colorScheme.background,
                            modifier = Modifier.fillMaxWidth()) {
                            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                val previewCell = minOf(maxWidth / DESKTOP_COLUMNS, 70.dp)
                                LuluCssBackground(Modifier.matchParentSize())
                                LuluDesktopWidgetCard(
                                    widget = LuluDesktopWidget("css-preview", widget, 0, 0, 0),
                                    today = rememberLuluDesktopDate(),
                                    characterName = null,
                                    lastConversationText = null,
                                    modifier = Modifier.size(previewCell * widget.columns, previewCell * widget.rows),
                                    onOpen = {},
                                    onEdit = null,
                                    specialContent = { card, ink, _ ->
                                        Box(Modifier.fillMaxSize().padding(horizontal = 18.dp),
                                            contentAlignment = Alignment.CenterStart) {
                                            Text(card.kind.title, color = ink, fontSize = 18.sp,
                                                fontWeight = FontWeight.SemiBold)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = {
                            val style = if (widget.isDateTime) {
                                "color: #292929; font-size: 36sp; background-color: transparent; border-width: 0dp;"
                            } else {
                                "color: #292929; background-color: #fafafa; border-radius: 20dp; " +
                                    "border-width: 1dp; border-color: #e7e7e7;"
                            }
                            source += "\n$selector { $style }\n"
                            status = "样式已加入草稿"
                        }, enabled = sheet.element("Home", "widget-" + widget.code).keys.all { it == "background-image" }) {
                            Text("插入样式")
                        }
                        TextButton(onClick = {
                            pendingImageSelector = selector
                            importImage.launch("image/*")
                        }) { Text("导入组件背景") }
                    }
                }
            } else item {
                Text("页面色彩预览", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                LuluAppearanceTheme(preview, scope = scope, sheetOverride = sheet) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(165.dp),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            LuluCssBackground()
                            Column(
                                Modifier.fillMaxSize().padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(9.dp),
                            ) {
                                Text("#" + scope, style = MaterialTheme.typography.labelMedium)
                                Text("这是你的空间", style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold)
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = MaterialTheme.shapes.medium,
                                ) {
                                    Text("主题按钮", Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                        color = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                        }
                    }
                }
                Text("这是配色与素材预览。元素位置请应用后到真实页面检查。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Text("主题代码", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
                    label = { Text("style.css") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    placeholder = { Text(":root { --accent: #9f5e52; }\n#Home { --paper: #fff8ef; }\n#Chat .bubble { offset-x: 4dp; }") },
                    minLines = 12,
                    isError = sheet.errors.isNotEmpty(),
                )
                if (sheet.errors.isNotEmpty()) {
                    Text(sheet.errors.joinToString("\n"), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (sheet.warnings.isNotEmpty()) {
                    Text(sheet.warnings.take(4).joinToString("\n"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = {
                        importCss.launch(arrayOf("text/css", "text/plain", "application/octet-stream"))
                    }) { Text("导入 CSS") }
                    OutlinedButton(onClick = {
                        pendingImageSelector = "#$scope"
                        importImage.launch("image/*")
                    }) { Text("导入背景图片") }
                    OutlinedButton(onClick = { exportCss.launch("lulu-theme.css") }) { Text("导出 CSS") }
                }
            }
            item {
                Button(
                    onClick = {
                        status = if (LuluCssStore.save(source)) "已应用。返回目标页面即可查看效果。"
                            else "保存失败；请检查语法或本机存储"
                    },
                    enabled = source != saved && sheet.errors.isEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                ) { Text("应用到页面") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { source = saved; status = "已撤销未保存的更改" },
                    enabled = source != saved,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("撤销草稿") }
                TextButton(
                    onClick = { source = ""; status = "已清空草稿；点应用后才恢复默认外观" },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("清空 CSS / 恢复原生外观") }
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
