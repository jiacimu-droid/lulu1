package com.jiacimu.lulu

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.LegacyLuluBackupImporter
import com.jiacimu.lulu.data.LuluAppPreferences
import com.jiacimu.lulu.data.LuluAppPreferencesStore
import com.jiacimu.lulu.data.LuluBackupManager
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SettingsHomePaper = Color(0xFFFFFCF5)
private val SettingsHomeCardColor = Color(0xFFFFFBF3)
private val SettingsHomeBorder = Color(0xFFE7DDC8)
private val SettingsHomeMuted = Color(0xFF737887)
private val SettingsHomeInk = Color(0xFF302C2B)
private val SettingsHomeAccent = Color(0xFFF2CF70)

private enum class SettingsDestination(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
) {
    Appearance("外观", "字号、动效与暖纸界面", Icons.Outlined.Palette),
    Chat("聊天", "消息时间、自动滚动与输入体验", Icons.Outlined.ChatBubbleOutline),
    Models("模型与 API", "站点、密钥、模型列表和存档", Icons.Outlined.Api),
    Memory("记忆", "最近消息排除、阈值与自动整理", Icons.Outlined.Psychology),
    Notifications("通知与主动联系", "主动消息、来电和勿扰时段", Icons.Outlined.NotificationsNone),
    Data("数据", "旧露露迁移、完整备份、缓存与重置", Icons.Outlined.Storage),
    Application("应用与权限", "版本、录音、通知和系统设置", Icons.Outlined.AdminPanelSettings),
}

@Composable
fun LuluSettingsHomeScreen(onBack: () -> Unit) {
    var destination by rememberSaveable { mutableStateOf<SettingsDestination?>(null) }
    when (val current = destination) {
        null -> SettingsDestinationList(onBack = onBack, onOpen = { destination = it })
        SettingsDestination.Models -> LuluSettingsScreen(onBack = { destination = null })
        else -> SettingsSectionScaffold(title = current.title, onBack = { destination = null }) {
            when (current) {
                SettingsDestination.Appearance -> AppearanceSettingsContent()
                SettingsDestination.Chat -> ChatSettingsContent()
                SettingsDestination.Memory -> MemorySettingsContent()
                SettingsDestination.Notifications -> NotificationSettingsContent()
                SettingsDestination.Data -> DataSettingsContent()
                SettingsDestination.Application -> ApplicationSettingsContent()
                SettingsDestination.Models -> Unit
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDestinationList(
    onBack: () -> Unit,
    onOpen: (SettingsDestination) -> Unit,
) {
    Scaffold(
        containerColor = SettingsHomePaper,
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SettingsHomePaper),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("露露机设置", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = SettingsHomeInk)
                Text("“我的”保存个人内容；这里管理应用行为、模型、记忆和数据。", color = SettingsHomeMuted)
            }
            items(SettingsDestination.entries) { item ->
                SettingsHomeCard(
                    modifier = Modifier.clickable { onOpen(item) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFF4EBDD)) {
                            Icon(item.icon, null, modifier = Modifier.padding(10.dp).size(24.dp), tint = SettingsHomeMuted)
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(item.subtitle, color = SettingsHomeMuted, fontSize = 12.sp)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = SettingsHomeMuted)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSectionScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = SettingsHomePaper,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SettingsHomePaper),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun AppearanceSettingsContent() {
    val preferences by LuluAppPreferencesStore.state.collectAsState()
    SettingsToggleCard(
        title = "较大字号",
        subtitle = "整体字号约放大 12%，重新进入页面即可稳定生效。",
        checked = preferences.largerText,
        onCheckedChange = { enabled -> LuluAppPreferencesStore.update { it.copy(largerText = enabled) } },
    )
    SettingsToggleCard(
        title = "减少动效",
        subtitle = "减少非必要动画；拖拽和加载反馈仍会保留。",
        checked = preferences.reduceMotion,
        onCheckedChange = { enabled -> LuluAppPreferencesStore.update { it.copy(reduceMotion = enabled) } },
    )
    SettingsHomeCard {
        Text("视觉基准", fontWeight = FontWeight.Bold)
        Text("全应用继续使用暖纸极简：暖白背景、浅麦黄强调、低饱和蓝灰与轻边框。", color = SettingsHomeMuted)
    }
}

@Composable
private fun ChatSettingsContent() {
    val preferences by LuluAppPreferencesStore.state.collectAsState()
    SettingsToggleCard(
        title = "显示消息时间",
        subtitle = "关闭后仅保留时间分隔，不在每组气泡下重复显示时刻。",
        checked = preferences.showMessageTimestamps,
        onCheckedChange = { enabled -> LuluAppPreferencesStore.update { it.copy(showMessageTimestamps = enabled) } },
    )
    SettingsToggleCard(
        title = "新消息自动滚到底部",
        subtitle = "关闭后，收到回复不会强制打断你正在阅读的旧消息。",
        checked = preferences.autoScrollChat,
        onCheckedChange = { enabled -> LuluAppPreferencesStore.update { it.copy(autoScrollChat = enabled) } },
    )
    SettingsHomeCard {
        Text("消息操作", fontWeight = FontWeight.Bold)
        Text("编辑、删除、收藏、重新生成和创建分支统一放在消息长按菜单中；自动对话建议链路不再提供。", color = SettingsHomeMuted)
    }
}

@Composable
private fun MemorySettingsContent() {
    val repository = LuluRepositories.memory
    val policy by repository.observePolicy("lulu").collectAsState(initial = com.jiacimu.lulu.core.MemoryPolicy())
    val scope = rememberCoroutineScope()
    var excluded by remember(policy.excludedRecentMessages) { mutableStateOf(policy.excludedRecentMessages.toString()) }
    var threshold by remember(policy.readableThreshold) { mutableStateOf(policy.readableThreshold.toString()) }
    var autoSummarize by remember(policy.autoSummarize) { mutableStateOf(policy.autoSummarize) }
    var notice by remember { mutableStateOf("") }

    SettingsHomeCard {
        Text("露露的记忆抽取规则", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        OutlinedTextField(
            value = excluded,
            onValueChange = { excluded = it.filter(Char::isDigit) },
            label = { Text("最近 N 条消息不读取") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = threshold,
            onValueChange = { threshold = it.filter(Char::isDigit) },
            label = { Text("可读消息达到此数量才整理") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("自动整理", fontWeight = FontWeight.SemiBold)
                Text("聊天回复完成后按固定批次检查，不使用滑动重叠批次。", color = SettingsHomeMuted, fontSize = 12.sp)
            }
            Switch(checked = autoSummarize, onCheckedChange = { autoSummarize = it })
        }
        Button(
            onClick = {
                scope.launch {
                    repository.updatePolicy(
                        "lulu",
                        com.jiacimu.lulu.core.MemoryPolicy(
                            excludedRecentMessages = excluded.toIntOrNull()?.coerceAtLeast(0) ?: 10,
                            readableThreshold = threshold.toIntOrNull()?.coerceAtLeast(1) ?: 20,
                            autoSummarize = autoSummarize,
                        ),
                    )
                    notice = "记忆规则已保存"
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = SettingsHomeAccent, contentColor = SettingsHomeInk),
        ) { Text("保存记忆规则", fontWeight = FontWeight.Bold) }
        if (notice.isNotBlank()) Text(notice, color = SettingsHomeMuted, fontSize = 12.sp)
    }
}

@Composable
private fun NotificationSettingsContent() {
    val preferences by LuluAppPreferencesStore.state.collectAsState()
    val lulu = MigratedDomainStores.characters.get("lulu")

    fun updatePreferences(transform: (LuluAppPreferences) -> LuluAppPreferences) {
        LuluAppPreferencesStore.update(transform)
        val next = LuluAppPreferencesStore.state.value
        MigratedDomainStores.characters.update(
            lulu.copy(
                contactPolicy = lulu.contactPolicy.copy(
                    enabled = next.proactiveContactEnabled,
                    quietHoursEnabled = next.quietHoursEnabled,
                    quietStartHour = next.quietStartHour,
                    quietEndHour = next.quietEndHour,
                    proactiveCallsEnabled = next.proactiveCallsEnabled,
                ),
            ),
        )
    }

    SettingsToggleCard(
        title = "允许通知",
        subtitle = "总开关关闭时，主动消息和主动来电同时停用。",
        checked = preferences.notificationsEnabled,
        onCheckedChange = { enabled -> updatePreferences { it.copy(notificationsEnabled = enabled) } },
    )
    SettingsToggleCard(
        title = "角色主动联系",
        subtitle = "允许角色在合适时机主动发起联系。",
        checked = preferences.proactiveContactEnabled,
        enabled = preferences.notificationsEnabled,
        onCheckedChange = { enabled -> updatePreferences { it.copy(proactiveContactEnabled = enabled) } },
    )
    SettingsToggleCard(
        title = "角色主动来电",
        subtitle = "这是全局许可；角色页仍可设置自己的来电窗口。",
        checked = preferences.proactiveCallsEnabled,
        enabled = preferences.notificationsEnabled,
        onCheckedChange = { enabled -> updatePreferences { it.copy(proactiveCallsEnabled = enabled) } },
    )
    SettingsToggleCard(
        title = "勿扰时段",
        subtitle = "勿扰只阻止主动联系，不影响你主动打开聊天。",
        checked = preferences.quietHoursEnabled,
        enabled = preferences.notificationsEnabled,
        onCheckedChange = { enabled -> updatePreferences { it.copy(quietHoursEnabled = enabled) } },
    )
    if (preferences.quietHoursEnabled && preferences.notificationsEnabled) {
        SettingsHourRangeCard(
            start = preferences.quietStartHour,
            end = preferences.quietEndHour,
            onSave = { start, end -> updatePreferences { it.copy(quietStartHour = start, quietEndHour = end) } },
        )
    }
}

@Composable
private fun DataSettingsContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf("") }
    var noticeIsError by remember { mutableStateOf(false) }
    var migrationRunning by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                writer.write(LuluBackupManager.exportJson(context))
            } ?: error("无法写入备份文件")
        }.onSuccess {
            notice = "完整备份已导出"
            noticeIsError = false
        }.onFailure { error ->
            notice = error.message ?: "导出失败"
            noticeIsError = true
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("无法读取备份文件")
            LuluBackupManager.importJson(context, raw)
        }.onSuccess { result ->
            notice = "已恢复 ${result.preferenceStoreCount} 组、${result.restoredValueCount} 项数据；请彻底退出并重新打开应用"
            noticeIsError = false
        }.onFailure { error ->
            notice = error.message ?: "恢复失败"
            noticeIsError = true
        }
    }

    val legacyImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        migrationRunning = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri) ?: error("无法读取旧露露备份")
                    LegacyLuluBackupImporter.importBackup(context, input)
                }
            }.onSuccess { result ->
                notice = "旧露露迁移完成：${result.conversationsImported} 个会话、${result.messagesImported} 条消息、${result.memoriesImported} 条记忆、${result.apiConfigurationsImported} 个 API 配置；请彻底退出并重新打开应用"
                noticeIsError = false
            }.onFailure { error ->
                notice = error.message ?: "旧露露迁移失败"
                noticeIsError = true
            }
            migrationRunning = false
        }
    }

    SettingsHomeCard {
        Text("从旧露露迁移", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("在旧露露的备份页导出包含数据库的 ZIP，再在这里选择。会迁入文本聊天、长期记忆和可识别的 API 配置。", color = SettingsHomeMuted)
        Text("原始 ZIP 和 settings.json 会保存在 Lulu1 私有目录中；无法可靠映射的旧字段只存档，不会伪造到新结构。", color = SettingsHomeMuted, fontSize = 12.sp)
        Surface(color = Color(0xFFFFE7D8), shape = RoundedCornerShape(14.dp)) {
            Text(
                "旧备份可能包含 API 密钥。迁移完成后仍要把文件当作敏感资料保管。",
                modifier = Modifier.fillMaxWidth().padding(11.dp),
                color = SettingsHomeInk,
                fontSize = 12.sp,
            )
        }
        Button(
            onClick = { legacyImportLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
            enabled = !migrationRunning,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = SettingsHomeAccent, contentColor = SettingsHomeInk),
        ) {
            if (migrationRunning) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = SettingsHomeInk)
            } else {
                Icon(Icons.Outlined.MoveToInbox, null)
            }
            Spacer(Modifier.width(7.dp))
            Text(if (migrationRunning) "正在迁移" else "选择旧露露 ZIP", fontWeight = FontWeight.Bold)
        }
    }

    SettingsHomeCard {
        Text("Lulu1 完整本地备份", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("包含聊天、角色、记忆、辞海、世界书、考研、心愿馆、游戏、桌面布局和模型 API 配置。", color = SettingsHomeMuted)
        Surface(color = Color(0xFFFFE7D8), shape = RoundedCornerShape(14.dp)) {
            Text(
                "备份中包含 API 密钥。只保存在你信任的位置，不要上传群聊、网盘公开链接或发给陌生人。",
                modifier = Modifier.fillMaxWidth().padding(11.dp),
                color = SettingsHomeInk,
                fontSize = 12.sp,
            )
        }
        Button(
            onClick = { exportLauncher.launch("lulu1-backup.json") },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = SettingsHomeAccent, contentColor = SettingsHomeInk),
        ) {
            Icon(Icons.Outlined.FileUpload, null)
            Spacer(Modifier.width(7.dp))
            Text("导出完整备份", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.FileDownload, null)
            Spacer(Modifier.width(7.dp))
            Text("从 Lulu1 备份恢复")
        }
    }

    SettingsHomeCard {
        Text("性能数据", fontWeight = FontWeight.Bold)
        Text("清理 Token 调用历史不会删除聊天、角色、记忆或考研数据。", color = SettingsHomeMuted)
        OutlinedButton(
            onClick = {
                scope.launch {
                    LuluRepositories.performance.clearCache()
                    LuluRepositories.performance.clearConsole()
                    LuluRepositories.performance.clearTimings()
                    notice = "Token 与时长诊断历史已清理"
                    noticeIsError = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("清理性能历史") }
    }

    SettingsHomeCard {
        Text("只重置应用偏好", fontWeight = FontWeight.Bold)
        Text("恢复字号、聊天显示和通知总开关的默认值，不删除业务数据。", color = SettingsHomeMuted)
        OutlinedButton(
            onClick = {
                LuluAppPreferencesStore.reset()
                notice = "应用偏好已恢复默认值"
                noticeIsError = false
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("重置应用偏好") }
    }

    if (notice.isNotBlank()) {
        Surface(
            color = if (noticeIsError) Color(0xFFF8E3DF) else Color(0xFFE8F1E6),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(notice, modifier = Modifier.fillMaxWidth().padding(12.dp), color = SettingsHomeInk)
        }
    }
}

@Composable
private fun ApplicationSettingsContent() {
    val context = LocalContext.current
    var permissionNotice by remember { mutableStateOf("") }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionNotice = if (granted) "通知权限已允许" else "通知权限未允许，可在系统设置中修改" }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionNotice = if (granted) "麦克风权限已允许" else "麦克风权限未允许，可在系统设置中修改" }

    SettingsHomeCard {
        Text("应用信息", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        SettingsValueRow("应用名", "露露机")
        SettingsValueRow("版本", BuildConfig.VERSION_NAME)
        SettingsValueRow("包名", context.packageName)
        SettingsValueRow("Android", Build.VERSION.SDK_INT.toString())
    }
    SettingsHomeCard {
        Text("权限", fontWeight = FontWeight.Bold)
        if (Build.VERSION.SDK_INT >= 33) {
            OutlinedButton(
                onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("申请通知权限") }
        }
        OutlinedButton(
            onClick = { microphonePermission.launch(Manifest.permission.RECORD_AUDIO) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("申请麦克风权限") }
        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("打开系统应用设置") }
        if (permissionNotice.isNotBlank()) Text(permissionNotice, color = SettingsHomeMuted, fontSize = 12.sp)
    }
    SettingsHomeCard {
        Text("签名与更新", fontWeight = FontWeight.Bold)
        Text("当前 GitHub debug APK 使用固定公开开发签名，可在 Lulu1 后续版本之间覆盖安装；正式商店发布必须改用私有生产签名。", color = SettingsHomeMuted)
    }
}

@Composable
private fun SettingsToggleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsHomeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = SettingsHomeInk)
                Text(subtitle, color = SettingsHomeMuted, fontSize = 12.sp)
            }
            Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun SettingsHourRangeCard(
    start: Int,
    end: Int,
    onSave: (Int, Int) -> Unit,
) {
    var startText by remember(start) { mutableStateOf(start.toString()) }
    var endText by remember(end) { mutableStateOf(end.toString()) }
    SettingsHomeCard {
        Text("勿扰时段", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = startText,
                onValueChange = { startText = it.filter(Char::isDigit).take(2) },
                label = { Text("开始小时") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = endText,
                onValueChange = { endText = it.filter(Char::isDigit).take(2) },
                label = { Text("结束小时") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        Button(
            onClick = {
                onSave(
                    startText.toIntOrNull()?.coerceIn(0, 23) ?: start,
                    endText.toIntOrNull()?.coerceIn(0, 23) ?: end,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存勿扰时段") }
    }
}

@Composable
private fun SettingsValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = SettingsHomeMuted)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SettingsHomeCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SettingsHomeCardColor),
        border = BorderStroke(1.dp, SettingsHomeBorder),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}
