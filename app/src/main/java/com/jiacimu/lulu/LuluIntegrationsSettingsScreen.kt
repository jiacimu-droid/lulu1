package com.jiacimu.lulu

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.jiacimu.lulu.data.LuluExternalMcp
import com.jiacimu.lulu.data.WebSearchProvider
import com.jiacimu.lulu.data.XhsReadingEngine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException

/**
 * Application-wide external integrations. Character settings only decide who can invoke them.
 * Credentials retain their existing storage keys; moving this screen does not reset accounts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LuluIntegrationsSettingsScreen(onBack: () -> Unit, onOpenBrowserProbe: () -> Unit, onOpenXhsNativeReader: () -> Unit) {
    val context = LocalContext.current
    val links = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("联网与阅读") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回设置")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("这里的服务器地址、密钥和搜索账号对所有角色通用；是否允许角色自主调用，仍在该角色的执行授权中单独设置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("小红书内置阅读优先 · MCP 是可选备用", style = MaterialTheme.typography.titleMedium)
        Text("先使用内置小红书网页登录、阅读和下方的真实读帖测试，不需要安装 Termux。" +
            "只有希望后台稳定阅读或本机网页确实不可用时，才考虑可选的外部 MCP。",
            style = MaterialTheme.typography.bodySmall)
        val scope = rememberCoroutineScope()
        listOf("xiaohongshu" to "小红书阅读（内置网页）", "health" to "华为健康 MCP（可选）").forEach { (service, title) ->
            var mcpUrl by remember(service) { mutableStateOf(LuluExternalMcp.endpoint(context, service)) }
            var mcpToken by remember(service) { mutableStateOf(LuluExternalMcp.savedToken(context, service)) }
            var mcpStatus by remember(service) { mutableStateOf("") }
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (service == "xiaohongshu") {
                var localReading by remember { mutableStateOf(XhsReadingEngine.localEnabled(context)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("使用已登录的本机网页", modifier = Modifier.weight(1f))
                    Switch(localReading, { enabled ->
                        if (XhsReadingEngine.setLocalEnabled(context, enabled)) localReading = enabled
                    })
                }
                Text("本机阅读需露露机保持打开；角色仍需单独开启只读授权。后台可使用下方 MCP。",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onOpenBrowserProbe) { Text("登录 / 检查小红书网页") }
                OutlinedButton(onClick = onOpenBrowserProbe) { Text("在内置网页搜索 / 阅读") }
                // Search tests must execute on a genuinely visible WebView.
                // Running a second hidden WebView from Settings creates a
                // blank/no-response page on some Android WebView versions.
                OutlinedButton(onClick = {
                    XhsReadingEngine.requestVisibleReadTest(context)
                    onOpenBrowserProbe()
                }) { Text("一键测试内置浏览器真实读帖") }
                Text("点击会打开内置小红书网页，显示搜索、读帖各阶段进度，并在网页上弹出最终结果。" +
                    "只使用本机浏览器，不使用 MCP，也不写入角色记忆。",
                    style = MaterialTheme.typography.bodySmall)
            }
            var optionalMcpExpanded by remember(service) { mutableStateOf(false) }
            if (service == "xiaohongshu") {
                TextButton(onClick = { optionalMcpExpanded = !optionalMcpExpanded }) {
                    Text(if (optionalMcpExpanded) "收起备用 MCP 设置"
                        else "备用方案：外部 MCP（可选，不影响内置网页）")
                }
            }
            if (service != "xiaohongshu" || optionalMcpExpanded) {
                if (service == "xiaohongshu") OutlinedButton(onClick = {
                    mcpUrl = "http://127.0.0.1:18060/mcp"
                    mcpToken = ""
                    mcpStatus = runCatching {
                        require(LuluExternalMcp.save(context, service, mcpUrl, mcpToken))
                        "已使用手机本机地址。先在 Termux 安装并启动只读服务，再点“保存并测试”。"
                    }.getOrElse { "配置失败：" + it.message.orEmpty() }
                }) { Text("一键使用本机 Termux · 免费") }
            OutlinedTextField(mcpUrl, { mcpUrl = it }, label = { Text("HTTPS 地址 / Termux 本机 127.0.0.1") },
                singleLine = true, modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible())
            OutlinedTextField(mcpToken, { mcpToken = it }, label = { Text("Bearer Token（可留空；建议服务端开启鉴权）") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true, modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    mcpStatus = runCatching { LuluExternalMcp.save(context, service, mcpUrl, mcpToken) }
                        .fold({ if (it) "配置已保存；接下来检查登录与数据" else "保存失败" },
                            { it.message.orEmpty() })
                }) { Text("保存") }
                OutlinedButton(enabled = mcpUrl.isNotBlank(), onClick = {
                    mcpStatus = "正在检查…"
                    scope.launch {
                        mcpStatus = runCatching {
                            require(LuluExternalMcp.save(context, service, mcpUrl, mcpToken)) { "无法保存连接信息" }
                            LuluExternalMcp.testService(context, service)
                        }.fold(
                            { it.take(240) },
                            { "服务连接失败：" + it.message.orEmpty().take(180) },
                        )
                    }
                }) { Text("测试服务") }
                OutlinedButton(onClick = {
                    mcpUrl = ""; mcpToken = ""
                    mcpStatus = if (LuluExternalMcp.save(context, service, "", "")) "已断开" else "断开失败"
                }) { Text("断开") }
            }
            if (mcpStatus.isNotBlank()) Text(mcpStatus, style = MaterialTheme.typography.bodySmall)
            if (service == "xiaohongshu") {
                HorizontalDivider()
                Text("手机扫码登录（无需电脑）", style = MaterialTheme.typography.titleSmall)
                Text("先用“测试服务”确认本机 MCP 在线；这一步不启动浏览器。下面的“检查小红书登录”才会启动 Chromium，第一次较慢，若它超时请检查 Termux 浏览器运行库或网页加载。",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = mcpUrl.isNotBlank(), onClick = {
                    mcpStatus = "正在启动浏览器并检查小红书登录，请稍候…"
                    scope.launch {
                        mcpStatus = runCatching {
                            require(LuluExternalMcp.save(context, service, mcpUrl, mcpToken))
                            LuluExternalMcp.check(context, service)
                        }.fold({ it.take(260) }, { "登录检查失败：" + it.message.orEmpty().take(180) })
                    }
                }) { Text("检查小红书登录") }
                OutlinedButton(onClick = {
                    links.openUri("https://github.com/jiacimu-droid/lulu1/blob/main/tools/xhs-termux/README.md")
                }) { Text("查看手机安装教程") }
                Text("先确保已连接可用的 MCP（云端或兼容的 Termux 本机服务）。按下面按钮生成登录码、保存到相册，然后在小红书的扫一扫中从相册选择图片。返回这里检查登录状态；二维码可能很快过期。",
                    style = MaterialTheme.typography.bodySmall)
                var qr by remember { mutableStateOf<LuluExternalMcp.XhsLoginQr?>(null) }
                var qrBusy by remember { mutableStateOf(false) }
                var qrMessage by remember { mutableStateOf("") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !qrBusy && mcpUrl.isNotBlank(), onClick = {
                        qrBusy = true
                        qrMessage = "正在从 MCP 获取二维码…"
                        scope.launch {
                            runCatching {
                                LuluExternalMcp.save(context, "xiaohongshu", mcpUrl, mcpToken)
                                LuluExternalMcp.xhsLoginQr(context)
                            }.fold(
                                { qr = it; qrMessage = it.description },
                                { qr = null; qrMessage = it.message.orEmpty().take(160) },
                            )
                            qrBusy = false
                        }
                    }) { Text(if (qrBusy) "正在获取…" else "获取登录二维码") }
                    OutlinedButton(enabled = qr?.pngBase64?.isNotBlank() == true, onClick = {
                        qrMessage = runCatching {
                            LuluExternalMcp.saveXhsLoginQrToPhotos(context, qr!!.pngBase64)
                        }.fold(
                            { if (it) "已保存到系统相册，切换小红书 App 从相册扫描" else "保存失败" },
                            { "保存失败：" + it.message.orEmpty().take(130) },
                        )
                    }) { Text("保存到相册") }
                }
                if (qrMessage.isNotBlank()) Text(qrMessage, style = MaterialTheme.typography.bodySmall)
                qr?.pngBase64?.takeIf(String::isNotBlank)?.let { encoded ->
                    val bitmap = remember(encoded) {
                        runCatching {
                            val bytes = Base64.decode(encoded, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        }.getOrNull()
                    }
                    if (bitmap != null) {
                        Image(bitmap = bitmap, contentDescription = "小红书 MCP 登录二维码",
                            modifier = Modifier.size(196.dp))
                    }
                }
            }
            } // end optional external MCP configuration
        }

        HorizontalDivider()
        Text("网络搜索（所有角色共用）", style = MaterialTheme.typography.titleMedium)
        var searchKey by remember { mutableStateOf(WebSearchProvider.savedKey(context)) }
        var searchStatus by remember { mutableStateOf("") }
        OutlinedTextField(
            value = searchKey,
            onValueChange = { searchKey = it.take(256); searchStatus = "" },
            label = { Text("Tavily Search API Key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (WebSearchProvider.isConfigured(context)) "已配置通用网页搜索" else "未配置：仍可使用百科搜索",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                searchStatus = if (WebSearchProvider.saveKey(context, searchKey)) "已保存（仅本机）" else "保存失败"
            }) { Text("保存／清除") }
        }
        if (searchStatus.isNotBlank()) Text(searchStatus, style = MaterialTheme.typography.bodySmall)
        }
    }
}
