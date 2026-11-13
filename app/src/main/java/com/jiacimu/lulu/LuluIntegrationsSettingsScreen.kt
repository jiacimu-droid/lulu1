package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.LuluExternalMcp
import com.jiacimu.lulu.data.WebSearchProvider
import com.jiacimu.lulu.data.ImageSearchProvider
import com.jiacimu.lulu.data.XhsReadingReceipt
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.MigratedDomainStores
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Integrations for manual reading, historical records, general search and health.
 * Companion App browsing is now handled by the authorized virtual display.
 * Legacy XHS MCP preferences and reading history are intentionally preserved.
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
            IntegrationSettingsCard {
                Text("角色浏览手机 App", style = MaterialTheme.typography.titleMedium)
                Text(
                    "角色打开小红书、抖音、网易云音乐或购物 App，统一通过「查手机→后台画面」的独立虚拟屏操作。旧小红书 MCP 路径已停用，无需再启动 Termux/Chromium；操作前仍须无线调试配对与角色授权，具体结果以真实屏幕回执为准。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
