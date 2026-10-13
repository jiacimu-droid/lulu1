package com.jiacimu.lulu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
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
                        mcpStatus = runCatching { LuluExternalMcp.check(context, service) }
                            .fold({ "连接返回：" + it.take(240) }, { "无法连接：" + it.message.orEmpty().take(180) })
                    }
                }) { Text("测试连接") }
                OutlinedButton(onClick = {
                    mcpUrl = ""; mcpToken = ""
                    mcpStatus = if (LuluExternalMcp.save(context, service, "", "")) "已断开" else "断开失败"
                }) { Text("断开") }
            }
            if (mcpStatus.isNotBlank()) Text(mcpStatus, style = MaterialTheme.typography.bodySmall)
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
