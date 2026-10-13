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
import kotlinx.coroutines.launch

/**
 * Application-wide external integrations. Character settings only decide who can invoke them.
 * Credentials retain their existing storage keys; moving this screen does not reset accounts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LuluIntegrationsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val links = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCP 与联网服务") },
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
        Text("外部 MCP 连接（所有角色共用）", style = MaterialTheme.typography.titleMedium)
        Text("小红书连接 xpzouying/xiaohongshu-mcp；手环连接 band-health-sync 配套的 health-mcp。需要先在你的电脑或服务器启动服务并登录；填 HTTPS /mcp 地址，手机无法直接访问电脑的 localhost。仅开放读取工具，不会自动评论或发帖。", style = MaterialTheme.typography.bodySmall)
        val scope = rememberCoroutineScope()
        listOf("xiaohongshu" to "小红书阅读 MCP", "health" to "华为健康 MCP（可选）").forEach { (service, title) ->
            var mcpUrl by remember(service) { mutableStateOf(LuluExternalMcp.endpoint(context, service)) }
            var mcpToken by remember(service) { mutableStateOf(LuluExternalMcp.savedToken(context, service)) }
            var mcpStatus by remember(service) { mutableStateOf("") }
            Text(title, style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(mcpUrl, { mcpUrl = it }, label = { Text("HTTPS MCP 地址") },
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
                            LuluExternalMcp.check(context, service)
                        }.fold(
                            { "连接成功：" + it.take(240) },
                            { "无法连接：" + it.message.orEmpty().take(180) },
                        )
                    }
                }) { Text("保存并测试") }
                OutlinedButton(onClick = {
                    mcpUrl = ""; mcpToken = ""
                    mcpStatus = if (LuluExternalMcp.save(context, service, "", "")) "已断开" else "断开失败"
                }) { Text("断开") }
            }
            if (mcpStatus.isNotBlank()) Text(mcpStatus, style = MaterialTheme.typography.bodySmall)
            if (service == "xiaohongshu") {
                HorizontalDivider()
                Text("手机扫码登录（无需电脑）", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = {
                    links.openUri("https://github.com/jiacimu-droid/lulu1/blob/main/docs/XHS_PHONE_ONLY_SETUP.md")
                }) { Text("一部手机部署教程 · 云端托管可能收费") }
                Text("先在手机浏览器部署好能持续运行的云端 MCP，并填入其 HTTPS 地址。按下面按钮生成登录码、保存到相册，然后打开小红书 → 我 → 左上角菜单 → 扫一扫 → 相册，选刚保存的图片完成授权。返回这里检查登录状态。二维码可能很快过期，请尽快操作。",
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
