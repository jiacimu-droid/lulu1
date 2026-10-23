package com.jiacimu.lulu

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.LuluExternalMcp
import com.jiacimu.lulu.data.WebSearchProvider
import com.jiacimu.lulu.data.ImageSearchProvider
import com.jiacimu.lulu.data.XhsReadingEngine
import com.jiacimu.lulu.data.XhsReadingReceipt
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.MigratedDomainStores
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        mutableStateOf(!LuluExternalMcp.configured(context, "xiaohongshu"))
    }
    var localReading by remember { mutableStateOf(XhsReadingEngine.localEnabled(context)) }
    var mcpPrimary by remember { mutableStateOf(XhsReadingEngine.mcpPrimaryEnabled(context)) }
    var mcpFallback by remember { mutableStateOf(XhsReadingEngine.mcpFallbackEnabled(context)) }
    var keyword by remember { mutableStateOf("") }
    var readingBusy by remember { mutableStateOf(false) }
    var receiptHistoryOpen by remember { mutableStateOf(false) }
    var receiptPageSize by remember { mutableIntStateOf(12) }
    val timelineRevision by SharedExperienceTimeline.revisions.collectAsState()
    val receiptRows by produceState<List<XhsReadingReceipt>>(
        emptyList(), timelineRevision, receiptHistoryOpen, receiptPageSize,
    ) {
        val count = if (receiptHistoryOpen) receiptPageSize + 1 else 1
        value = withContext(Dispatchers.IO) {
            SharedExperienceTimeline.recentXhsReadings(count).mapNotNull(XhsReadingReceipt::from)
        }
    }
    val receiptDateFormat = remember {
        DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
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
    var searchBusy by remember { mutableStateOf(false) }
    var searchExpanded by remember { mutableStateOf(false) }
    var imageExpanded by remember { mutableStateOf(false) }
    var serperKey by remember { mutableStateOf(ImageSearchProvider.serperKey(context)) }
    var giphyKey by remember { mutableStateOf(ImageSearchProvider.giphyKey(context)) }
    var imageBusy by remember { mutableStateOf(false) }
    var imageStatus by remember { mutableStateOf("") }


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
            IntegrationSettingsCard {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("内置网页", modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenBrowserProbe) { Text("打开") }
                    Switch(
                        checked = localReading,
                        onCheckedChange = { enabled ->
                            if (XhsReadingEngine.setLocalEnabled(context, enabled)) localReading = enabled
                        },
                    )
                }
            }

            IntegrationSettingsCard {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("独立 MCP", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { mcpExpanded = !mcpExpanded }) {
                        Text(if (mcpExpanded) "收起设置" else "连接设置")
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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

                if (mcpExpanded) {
                    HorizontalDivider()
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
                        label = { Text("访问 Token") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible(),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("备用线路", modifier = Modifier.weight(1f))
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                connectionStatus = runCatching {
                                    check(LuluExternalMcp.save(context, "xiaohongshu", mcpUrl, mcpToken))
                                    "已保存"
                                }.getOrElse { "保存失败：" + it.message.orEmpty() }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("保存连接") }
                        TextButton(onClick = {
                            val cleared = runCatching {
                                LuluExternalMcp.save(context, "xiaohongshu", "", "")
                            }.getOrDefault(false)
                            connectionStatus = if (cleared) {
                                XhsReadingEngine.setMcpPrimaryEnabled(context, false)
                                XhsReadingEngine.setMcpFallbackEnabled(context, false)
                                mcpPrimary = false
                                mcpFallback = false
                                mcpUrl = ""
                                mcpToken = ""
                                qr = null
                                "已断开"
                            } else "断开失败"
                        }) { Text("断开") }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = mcpUrl.isNotBlank(),
                        onClick = {
                            connectionStatus = "连接中…"
                            scope.launch {
                                connectionStatus = runCatching {
                                    check(LuluExternalMcp.save(context, "xiaohongshu", mcpUrl, mcpToken))
                                    LuluExternalMcp.testService(context, "xiaohongshu")
                                }.fold(
                                    { "MCP 已响应 · 登录待验证" },
                                    { "连接失败：" + it.message.orEmpty().take(180) },
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("测试连接") }
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
                }
                IntegrationFeedback(connectionStatus)
                IntegrationFeedback(loginStatus)

                HorizontalDivider()
                Button(
                    enabled = mcpUrl.isNotBlank() && !qrBusy,
                    onClick = {
                        qrBusy = true
                        qrStatus = "获取中…"
                        scope.launch {
                            try {
                                check(LuluExternalMcp.save(context, "xiaohongshu", mcpUrl, mcpToken))
                                qr = LuluExternalMcp.xhsLoginQr(context)
                                qrStatus = if (qr?.pngBase64.isNullOrBlank()) qr?.description.orEmpty()
                                    else "二维码已生成"
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                qr = null
                                qrStatus = "二维码获取失败：" + error.message.orEmpty().take(160)
                            } finally {
                                qrBusy = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(when {
                        qrBusy -> "获取中…"
                        qr != null -> "重新获取二维码"
                        else -> "获取登录二维码"
                    })
                }
                qr?.pngBase64?.takeIf(String::isNotBlank)?.let { encoded ->
                    val bitmap = remember(encoded) {
                        runCatching {
                            val data = Base64.decode(encoded, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()
                        }.getOrNull()
                    }
                    if (bitmap != null) {
                        Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "小红书登录二维码",
                                modifier = Modifier.size(196.dp),
                            )
                            TextButton(onClick = {
                                qrStatus = runCatching {
                                    if (LuluExternalMcp.saveXhsLoginQrToPhotos(context, encoded)) {
                                        "已保存到相册"
                                    } else "保存失败"
                                }.getOrElse { "保存失败：" + it.message.orEmpty().take(130) }
                            }) { Text("保存到相册") }
                        }
                    }
                }
                IntegrationFeedback(qrStatus)

                HorizontalDivider()
                Text("真实读帖测试", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it.take(64) },
                    label = { Text("搜索关键词") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible(),
                )
                OutlinedButton(
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
                IntegrationFeedback(readingStatus)
            }

            IntegrationSettingsCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("阅读记录", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        receiptHistoryOpen = !receiptHistoryOpen
                        if (!receiptHistoryOpen) receiptPageSize = 12
                    }) { Text(if (receiptHistoryOpen) "收起" else "查看历史") }
                }
                if (receiptRows.isEmpty()) {
                    Text("暂无阅读记录", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
                receiptRows.take(if (receiptHistoryOpen) receiptPageSize else 1).forEach { receipt ->
                    HorizontalDivider()
                    Text(receipt.title, style = MaterialTheme.typography.titleSmall,
                        maxLines = 2)
                    Text(
                        "${MigratedDomainStores.characters.get(receipt.characterId).displayName} · " +
                            receiptDateFormat.format(receipt.occurredAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(receipt.bodyLabel, style = MaterialTheme.typography.labelSmall)
                        Text(receipt.commentsLabel, style = MaterialTheme.typography.labelSmall)
                        Text(receipt.imagesLabel, style = MaterialTheme.typography.labelSmall)
                    }
                    if (receiptHistoryOpen && receipt.imagesLinked != null && receipt.imagesLinked > 0 &&
                        receipt.imagesAnalyzed == 0) {
                        Text("图片尚未取得有效视觉分析",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (receiptHistoryOpen && receiptRows.size > receiptPageSize) {
                    TextButton(onClick = { receiptPageSize += 12 }) { Text("加载更多") }
                }
            }

            IntegrationSettingsCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("网络搜索", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { searchExpanded = !searchExpanded }) {
                        Text(if (searchExpanded) "收起" else "设置")
                    }
                }
                if (searchExpanded) {
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
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "当前：${WebSearchProvider.configuredProvider(context)}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            searchStatus = runCatching {
                                check(WebSearchProvider.saveDeepSeekKey(context, deepSeekKey))
                                check(WebSearchProvider.saveKey(context, tavilyKey))
                                "已保存"
                            }.getOrElse { "保存失败：" + it.message.orEmpty() }
                        }) { Text("保存") }
                        TextButton(
                            enabled = !searchBusy && WebSearchProvider.isConfigured(context),
                            onClick = {
                                searchBusy = true
                                searchStatus = "正在测试…"
                                scope.launch {
                                    val outcome = withContext(Dispatchers.IO) {
                                        runCatching { WebSearchProvider.search(context, "DeepSeek").size }
                                    }
                                    searchStatus = outcome.fold(
                                        onSuccess = { "搜索成功 · ${it} 条结果" },
                                        onFailure = { "搜索失败：${it.message.orEmpty().take(130)}" },
                                    )
                                    searchBusy = false
                                }
                            },
                        ) { Text("测试搜索") }
                    }
                    IntegrationFeedback(searchStatus)
                }
            }

            IntegrationSettingsCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("图片搜索", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { imageExpanded = !imageExpanded }) {
                        Text(if (imageExpanded) "收起" else "设置")
                    }
                }
                if (imageExpanded) {
                    OutlinedTextField(
                        value = serperKey,
                        onValueChange = { serperKey = it.take(256); imageStatus = "" },
                        label = { Text("Serper API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible(),
                    )
                    OutlinedTextField(
                        value = giphyKey,
                        onValueChange = { giphyKey = it.take(256); imageStatus = "" },
                        label = { Text("GIPHY API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible(),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            imageStatus = runCatching {
                                check(ImageSearchProvider.saveKeys(context, serperKey, giphyKey))
                                "已保存"
                            }.getOrElse { "保存失败：${it.message.orEmpty()}" }
                        }) { Text("保存") }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            enabled = !imageBusy && ImageSearchProvider.configured(context, "serper"),
                            onClick = {
                                imageBusy = true
                                imageStatus = "正在测试 Serper…"
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { ImageSearchProvider.search(context, "serper", "熊猫头表情包").size }
                                    }
                                    imageStatus = result.fold(
                                        onSuccess = { "Serper · ${it} 张" },
                                        onFailure = { "Serper 失败：${it.message.orEmpty().take(110)}" },
                                    )
                                    imageBusy = false
                                }
                            },
                        ) { Text("测 Serper") }
                        TextButton(
                            enabled = !imageBusy && ImageSearchProvider.configured(context, "giphy"),
                            onClick = {
                                imageBusy = true
                                imageStatus = "正在测试 GIPHY…"
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { ImageSearchProvider.search(context, "giphy", "funny cat").size }
                                    }
                                    imageStatus = result.fold(
                                        onSuccess = { "GIPHY · ${it} 张" },
                                        onFailure = { "GIPHY 失败：${it.message.orEmpty().take(110)}" },
                                    )
                                    imageBusy = false
                                }
                            },
                        ) { Text("测 GIPHY") }
                    }
                    IntegrationFeedback(imageStatus)
                }
            }

            IntegrationSettingsCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("华为健康 MCP", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { healthExpanded = !healthExpanded }) {
                        Text(if (healthExpanded) "收起" else "设置")
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
                            healthStatus = if (LuluExternalMcp.save(context, "health", "", "")) {
                                healthUrl = ""
                                healthToken = ""
                                "已断开"
                            } else "断开失败"
                        }) { Text("断开") }
                    }
                    IntegrationFeedback(healthStatus)
                }
            }
        }
    }
}

@Composable
private fun IntegrationSettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun IntegrationFeedback(message: String) {
    if (message.isNotBlank()) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (message.contains("失败") || message.contains("错误"))
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
