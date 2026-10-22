package com.jiacimu.lulu

import android.graphics.BitmapFactory
import android.util.Base64
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.LuluExternalMcp
import com.jiacimu.lulu.data.WebSearchProvider
import com.jiacimu.lulu.data.XhsReadingEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Compact integrations screen. Configuration, login, read verification and
 * web-search remain separate actions; only confirmed reading promotes MCP.
 * Existing persisted settings keys and service routes are unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LuluIntegrationsSettingsScreen(
    onBack: () -> Unit,
    onOpenBrowserProbe: () -> Unit,
    onOpenXhsNativeReader: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mcpUrl by remember { mutableStateOf(LuluExternalMcp.endpoint(context, "xiaohongshu")) }
    var mcpToken by remember { mutableStateOf(LuluExternalMcp.savedToken(context, "xiaohongshu")) }
    var connectionStatus by remember { mutableStateOf("") }
    var loginStatus by remember { mutableStateOf("") }
    var readingStatus by remember { mutableStateOf("") }
    var mcpExpanded by remember {
        mutableStateOf(LuluExternalMcp.configured(context, "xiaohongshu"))
    }
    var localReading by remember { mutableStateOf(XhsReadingEngine.localEnabled(context)) }
    var mcpPrimary by remember { mutableStateOf(XhsReadingEngine.mcpPrimaryEnabled(context)) }
    var mcpFallback by remember { mutableStateOf(XhsReadingEngine.mcpFallbackEnabled(context)) }
    var keyword by remember { mutableStateOf("") }
    var readingBusy by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf<LuluExternalMcp.XhsLoginQr?>(null) }
    var qrBusy by remember { mutableStateOf(false) }
    var qrStatus by remember { mutableStateOf("") }

    var healthExpanded by remember { mutableStateOf(false) }
    var healthUrl by remember { mutableStateOf(LuluExternalMcp.endpoint(context, "health")) }
    var healthToken by remember { mutableStateOf(LuluExternalMcp.savedToken(context, "health")) }
    var healthStatus by remember { mutableStateOf("") }

    var deepSeekKey by remember { mutableStateOf(WebSearchProvider.savedDeepSeekKey(context)) }
    var tavilyKey by remember { mutableStateOf(WebSearchProvider.savedKey(context)) }
    var searchStatus by remember { mutableStateOf("") }

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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("小红书", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("本机网页阅读", modifier = Modifier.weight(1f))
                Switch(
                    checked = localReading,
                    onCheckedChange = { enabled ->
                        if (XhsReadingEngine.setLocalEnabled(context, enabled)) localReading = enabled
                    },
                )
            }
            OutlinedButton(onClick = onOpenBrowserProbe, modifier = Modifier.fillMaxWidth()) {
                Text("打开小红书")
            }

            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("独立 MCP", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { mcpExpanded = !mcpExpanded }) {
                    Text(if (mcpExpanded) "收起" else "展开")
                }
            }
            if (mcpExpanded) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("优先使用 MCP", modifier = Modifier.weight(1f))
                    Switch(
                        checked = mcpPrimary,
                        onCheckedChange = { enabled ->
                            runCatching {
                                check(XhsReadingEngine.setMcpPrimaryEnabled(context, enabled))
                            }.fold(
                                {
                                    mcpPrimary = XhsReadingEngine.mcpPrimaryEnabled(context)
                                    mcpFallback = XhsReadingEngine.mcpFallbackEnabled(context)
                                },
                                { connectionStatus = "设置失败：" + it.message.orEmpty().take(120) },
                            )
                        },
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("作为备用线路", modifier = Modifier.weight(1f))
                    Switch(
                        checked = mcpFallback,
                        onCheckedChange = { enabled ->
                            if (XhsReadingEngine.setMcpFallbackEnabled(context, enabled)) {
                                mcpFallback = XhsReadingEngine.mcpFallbackEnabled(context)
                                mcpPrimary = XhsReadingEngine.mcpPrimaryEnabled(context)
                            }
                        },
                    )
                }
                OutlinedTextField(
                    value = mcpUrl,
                    onValueChange = { mcpUrl = it },
                    label = { Text("MCP 地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible(),
                    trailingIcon = {
                        TextButton(onClick = { mcpUrl = "http://127.0.0.1:18061/mcp" }) {
                            Text("本机")
                        }
                    },
                )
                OutlinedTextField(
                    value = mcpToken,
                    onValueChange = { mcpToken = it },
                    label = { Text("Token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            connectionStatus = runCatching {
                                check(LuluExternalMcp.save(context, "xiaohongshu", mcpUrl, mcpToken))
                                "已保存"
                            }.getOrElse { "保存失败：" + it.message.orEmpty() }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("保存") }
                    Button(
                        enabled = mcpUrl.isNotBlank(),
                        onClick = {
                            connectionStatus = "连接中…"
                            scope.launch {
                                connectionStatus = runCatching {
                                    check(LuluExternalMcp.save(context, "xiaohongshu", mcpUrl, mcpToken))
                                    LuluExternalMcp.testService(context, "xiaohongshu")
                                }.fold(
                                    { "MCP 已响应 · 登录和读帖待验证" },
                                    { "连接失败：" + it.message.orEmpty().take(180) },
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("测试连接") }
                    TextButton(
                        onClick = {
                            mcpUrl = ""
                            mcpToken = ""
                            connectionStatus = if (LuluExternalMcp.save(context, "xiaohongshu", "", "")) {
                                XhsReadingEngine.setMcpPrimaryEnabled(context, false)
                                XhsReadingEngine.setMcpFallbackEnabled(context, false)
                                mcpPrimary = false
                                mcpFallback = false
                                qr = null
                                "已断开"
                            } else "断开失败"
                        },
                    ) { Text("断开") }
                }
                if (connectionStatus.isNotBlank()) {
                    Text(connectionStatus, style = MaterialTheme.typography.bodySmall)
                }

                HorizontalDivider()
                Text("登录", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = mcpUrl.isNotBlank(),
                        onClick = {
                            loginStatus = "检查登录中…"
                            scope.launch {
                                loginStatus = runCatching {
                                    check(LuluExternalMcp.save(context, "xiaohongshu", mcpUrl, mcpToken))
                                    LuluExternalMcp.check(context, "xiaohongshu")
                                }.fold(
                                    { it.take(260) },
                                    { "登录检查失败：" + it.message.orEmpty().take(180) },
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("检查登录") }
                    OutlinedButton(
                        enabled = mcpUrl.isNotBlank() && !qrBusy,
                        onClick = {
                            qrBusy = true
                            qrStatus = "获取中…"
                            scope.launch {
                                try {
                                    check(LuluExternalMcp.save(context, "xiaohongshu", mcpUrl, mcpToken))
                                    qr = LuluExternalMcp.xhsLoginQr(context)
                                    qrStatus = qr?.description.orEmpty()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    qr = null
                                    qrStatus = error.message.orEmpty().take(160)
                                } finally {
                                    qrBusy = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (qrBusy) "获取中…" else "获取二维码") }
                }
                if (loginStatus.isNotBlank()) {
                    Text(loginStatus, style = MaterialTheme.typography.bodySmall)
                }
                qr?.pngBase64?.takeIf(String::isNotBlank)?.let { encoded ->
                    val bitmap = remember(encoded) {
                        runCatching {
                            val data = Base64.decode(encoded, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()
                        }.getOrNull()
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "小红书登录二维码",
                            modifier = Modifier.size(196.dp),
                        )
                        OutlinedButton(onClick = {
                            qrStatus = runCatching {
                                if (LuluExternalMcp.saveXhsLoginQrToPhotos(context, encoded)) {
                                    "已保存到相册"
                                } else "保存失败"
                            }.getOrElse { "保存失败：" + it.message.orEmpty().take(130) }
                        }) { Text("保存二维码到相册") }
                    }
                }
                if (qrStatus.isNotBlank()) {
                    Text(qrStatus, style = MaterialTheme.typography.bodySmall)
                }

                HorizontalDivider()
                Text("真实读帖", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it.take(64) },
                    label = { Text("搜索关键词") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible(),
                )
                Button(
                    enabled = !readingBusy && mcpUrl.isNotBlank() && keyword.isNotBlank(),
                    onClick = {
                        readingBusy = true
                        readingStatus = "正在搜索并读取正文…"
                        scope.launch {
                            try {
                                check(LuluExternalMcp.save(context, "xiaohongshu", mcpUrl, mcpToken))
                                val details = LuluExternalMcp.testXhsReading(context, keyword)
                                check(XhsReadingEngine.setMcpPrimaryEnabled(context, true))
                                mcpPrimary = XhsReadingEngine.mcpPrimaryEnabled(context)
                                mcpFallback = XhsReadingEngine.mcpFallbackEnabled(context)
                                readingStatus = "真实读帖成功 · 已设为优先线路\n" + details
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                readingStatus = "真实读帖失败：" + error.message.orEmpty().take(230)
                            } finally {
                                readingBusy = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (readingBusy) "读取中…" else "搜索并读帖") }
                if (readingStatus.isNotBlank()) {
                    Text(readingStatus, style = MaterialTheme.typography.bodySmall)
                }
            }

            HorizontalDivider()
            Text("网络搜索", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = deepSeekKey,
                onValueChange = { deepSeekKey = it.take(256); searchStatus = "" },
                label = { Text("DeepSeek Search API Key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible(),
            )
            OutlinedTextField(
                value = tavilyKey,
                onValueChange = { tavilyKey = it.take(256); searchStatus = "" },
                label = { Text("Tavily API Key（备用）") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    when {
                        WebSearchProvider.savedDeepSeekKey(context).isNotBlank() -> "当前：DeepSeek"
                        WebSearchProvider.savedKey(context).isNotBlank() -> "当前：Tavily"
                        else -> "当前：百科检索"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = {
                    searchStatus = runCatching {
                        check(WebSearchProvider.saveDeepSeekKey(context, deepSeekKey))
                        check(WebSearchProvider.saveKey(context, tavilyKey))
                        "已保存"
                    }.getOrElse { "保存失败：" + it.message.orEmpty() }
                }) { Text("保存") }
            }
            if (searchStatus.isNotBlank()) {
                Text(searchStatus, style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("华为健康 MCP", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { healthExpanded = !healthExpanded }) {
                    Text(if (healthExpanded) "收起" else "展开")
                }
            }
            if (healthExpanded) {
                OutlinedTextField(
                    value = healthUrl,
                    onValueChange = { healthUrl = it },
                    label = { Text("MCP 地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible(),
                )
                OutlinedTextField(
                    value = healthToken,
                    onValueChange = { healthToken = it },
                    label = { Text("Token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        healthStatus = runCatching {
                            check(LuluExternalMcp.save(context, "health", healthUrl, healthToken))
                            "已保存"
                        }.getOrElse { "保存失败：" + it.message.orEmpty() }
                    }, modifier = Modifier.weight(1f)) { Text("保存") }
                    Button(
                        enabled = healthUrl.isNotBlank(),
                        onClick = {
                            healthStatus = "连接中…"
                            scope.launch {
                                healthStatus = runCatching {
                                    check(LuluExternalMcp.save(context, "health", healthUrl, healthToken))
                                    LuluExternalMcp.testService(context, "health")
                                }.fold(
                                    { "MCP 已响应" },
                                    { "连接失败：" + it.message.orEmpty().take(180) },
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("测试连接") }
                    TextButton(onClick = {
                        healthUrl = ""
                        healthToken = ""
                        healthStatus = if (LuluExternalMcp.save(context, "health", "", "")) {
                            "已断开"
                        } else "断开失败"
                    }) { Text("断开") }
                }
                if (healthStatus.isNotBlank()) {
                    Text(healthStatus, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
