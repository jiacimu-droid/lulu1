package com.jiacimu.lulu

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import com.jiacimu.lulu.data.LuluAppearanceCatalog
import com.jiacimu.lulu.data.LuluCssSheet
import com.jiacimu.lulu.data.LuluCssStore
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
    var scopeSearch by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val sheet = remember(source) { LuluCssSheet.parse(source) }
    val preview = remember(source, scope) {
        sheet.profile(scope, LuluAppearanceCatalog.builtIns.first())
    }

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
        if (uri != null) {
            runCatching { LuluCssStore.importImage(context, uri) }
                .onSuccess { asset ->
                    source += "\n#" + scope + " {\n  background-image: url(\"" +
                        asset + "\");\n}\n"
                    status = "图片已保存在本机，并加入当前页面的草稿"
                }
                .onFailure { status = it.message ?: "图片导入失败" }
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
                    items(LuluAppearanceCatalog.scopes.filter {
                        scopeSearch.isBlank() || it.second.contains(scopeSearch, true) ||
                            it.first.contains(scopeSearch, true)
                    }) { (id, name) ->
                        ListItem(
                            headlineContent = { Text(name + "  #" + id) },
                            trailingContent = {
                                if (id == scope) Icon(Icons.Outlined.Check, contentDescription = null)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        HorizontalDivider()
                        Spacer(Modifier.height(2.dp))
                        if (id != scope) {
                            // Full-width explicit target, independent of swiping the sheet.
                            TextButton(
                                onClick = { scope = id; selectScope = false; scopeSearch = "" },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("编辑" + name) }
                        }
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
                Text("每个页面，由你定义。", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text("不再提供固定风格套装。写 CSS、导入图片，然后把它应用到真正的原生界面。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
            item {
                OutlinedButton(
                    onClick = { selectScope = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text(
                        LuluAppearanceCatalog.scopes.firstOrNull { it.first == scope }?.second
                            ?: scope,
                        modifier = Modifier.weight(1f),
                    )
                    Text("#" + scope + "  ", style = MaterialTheme.typography.labelMedium)
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "更换页面")
                }
            }
            item {
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
                    OutlinedButton(onClick = { importImage.launch("image/*") }) { Text("导入背景图片") }
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
            item {
                HorizontalDivider()
                Text("选择器和已支持的属性", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    ":root  全局基础配色\n#Home / #Chat / #Settings 等  单页覆盖\n" +
                        "#Home .launcher  桌面图标\n#Home .companion  桌面角色卡\n" +
                        "#Chat .bubble  聊天气泡\n\n" +
                        "--paper、--card、--ink、--accent、--on-accent、--border 等颜色\n" +
                        "font-family: serif / sans-serif / monospace\n" +
                        "border-radius: 18dp; offset-x: 8dp; offset-y: -4dp;\n" +
                        "background-image: url(\"asset://已导入的图片\")",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text("这里是受控的原生 CSS 子集，不支持网页选择器、脚本、外链或任意 HTML。" +
                    "目前仅已开放样式接口的组件能改位置；更多控件需要逐页接入，游戏画面不会被 CSS 重排。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
