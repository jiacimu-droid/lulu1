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
        Text("小红书 · 本机阅读 / 独立云端阅读", style = MaterialTheme.typography.titleMedium)
        Text("本机网页登录仍可手动浏览；想让角色关掉小红书页面后也能访问，就连接常驻云端 MCP 并选“云端优先”。云端浏览器不依赖本机 WebView，安卓后台唤醒仍需单独验证。",
            style = MaterialTheme.typography.bodySmall)
        val scope = rememberCoroutineScope()
        listOf("xiaohongshu" to "小红书独立阅读线路", "health" to "华为健康 MCP（可选）").forEach { (service, title) ->
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
                Text("内置浏览器是主线路；角色仍需单独开启只读授权。外部 MCP 不必配置，" +
                    "也不会因仅保存本机地址就自动接管阅读。",
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = onOpenBrowserProbe, modifier = Modifier.fillMaxWidth()) {
                    Text("打开小红书")
                }
                Text("直接浏览帖子和评论。网页阅读、搜索、提取图文、真实读帖测试和诊断都统一放进阅读页右上角菜单；不再需要三个不同入口。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            var optionalMcpExpanded by remember(service) {
                mutableStateOf(service == "xiaohongshu" && LuluExternalMcp.configured(context, service))
            }
            if (service == "xiaohongshu") {
                TextButton(onClick = { optionalMcpExpanded = !optionalMcpExpanded }) {
                    Text(if (optionalMcpExpanded) "收起独立 MCP 设置"
                        else "配置独立云端 MCP / 本机 Termux")
                }
            }
            if (service != "xiaohongshu" || optionalMcpExpanded) {
                if (service == "xiaohongshu") {
                    var fallbackOn by remember(service) {
                        mutableStateOf(XhsReadingEngine.mcpFallbackEnabled(context))
                    }
                    var remotePrimary by remember(service) {
                        mutableStateOf(XhsReadingEngine.mcpPrimaryEnabled(context))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("角色自主阅读：优先使用独立 MCP", modifier = Modifier.weight(1f))
                        Switch(remotePrimary, { enabled ->
                            val saved = runCatching {
                                XhsReadingEngine.setMcpPrimaryEnabled(context, enabled)
                            }
                            if (saved.getOrDefault(false)) {
                                remotePrimary = enabled
                                fallbackOn = XhsReadingEngine.mcpFallbackEnabled(context)
                                mcpStatus = if (enabled) "已设为云端优先；请务必测试登录和真实读帖。" else "已恢复内置网页优先"
                            } else mcpStatus = saved.exceptionOrNull()?.message.orEmpty().ifBlank { "保存失败" }
                        })
                    }
                    Text("打开后，角色无论在聊天还是后台执行，都会先使用独立 MCP 搜索；不再等内置网页超时。本机手动浏览入口仍然保留。",
                        style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("本机网页失败后尝试 MCP 备用", modifier = Modifier.weight(1f))
                        Switch(fallbackOn, { enabled ->
                            if (XhsReadingEngine.setMcpFallbackEnabled(context, enabled)) {
                                fallbackOn = enabled
                                remotePrimary = XhsReadingEngine.mcpPrimaryEnabled(context)
                            }
                        })
                    }
                    Text("两种模式都需要外部 MCP 真正在线；单纯保存地址不会启动服务端。",
                        style = MaterialTheme.typography.bodySmall)
                }
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
            OutlinedTextField(mcpToken, { mcpToken = it }, label = { Text("Bearer Token（云端必须填写，勿公开）") },
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
                Text("先测试 MCP 服务，再检查独立 Chromium 的登录状态，最后测试真实搜索和正文。服务在线不代表已登录，已登录也不代表能读到笔记。",
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        links.openUri("https://github.com/jiacimu-droid/lulu1/blob/main/tools/xhs-cloud/README.md")
                    }) { Text("手机部署云端教程") }
                    OutlinedButton(onClick = {
                        links.openUri("https://github.com/jiacimu-droid/lulu1/blob/main/tools/xhs-termux/README.md")
                    }) { Text("Termux 教程") }
                }
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
