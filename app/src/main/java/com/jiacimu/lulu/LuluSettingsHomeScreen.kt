package com.jiacimu.lulu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.design.LuluEditorialHeader
import com.jiacimu.lulu.design.LuluEditorialSectionHeading
import com.jiacimu.lulu.design.LocalLuluAppearance
import com.jiacimu.lulu.design.JournalPressedFlower
import com.jiacimu.lulu.design.isJournal
import com.jiacimu.lulu.design.journalPaper
import com.jiacimu.lulu.design.journalLinen

private val SettingsHomePaper: Color @Composable get() = MaterialTheme.colorScheme.background
private val SettingsHomeCard: Color @Composable get() = MaterialTheme.colorScheme.surface
private val SettingsHomeBorder: Color @Composable get() = MaterialTheme.colorScheme.outline
private val SettingsHomeMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val SettingsHomeInk: Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val SettingsHomeAccent: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
private val SettingsHomeAccentStrong: Color @Composable get() = MaterialTheme.colorScheme.primary

@Composable
fun LuluSettingsHomeScreen(onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(SettingsHomePage.Home) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(page) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
    BackHandler(enabled = page != SettingsHomePage.Home) {
        page = SettingsHomePage.Home
    }
    when (page) {
        SettingsHomePage.Api -> LuluSettingsScreen(onBack = { page = SettingsHomePage.Home })
        SettingsHomePage.Capabilities -> LuluCapabilitiesScreen(onBack = { page = SettingsHomePage.Home })
        SettingsHomePage.Integrations -> LuluIntegrationsSettingsScreen(
            onBack = { page = SettingsHomePage.Home },
            onOpenBrowserProbe = { page = SettingsHomePage.BrowserProbe },
            onOpenXhsNativeReader = { page = SettingsHomePage.XhsNativeReader },
        )
        // A second standalone browser page used to create a competing SPA.
        // Both "login website" and "read" now enter the SAME Activity-scoped
        // reader. Expose its website preview for manual login/verification.
        SettingsHomePage.BrowserProbe -> LuluXhsNativeReaderScreen(
            onBack = { page = SettingsHomePage.Integrations },
            initialShowWebsite = true,
        )
        SettingsHomePage.XhsNativeReader -> LuluXhsNativeReaderScreen(
            onBack = { page = SettingsHomePage.Integrations },
        )
        SettingsHomePage.Voice -> LuluVoiceSettingsScreen(onBack = { page = SettingsHomePage.Home })
        SettingsHomePage.Memory -> LuluMemorySettingsScreen(onBack = { page = SettingsHomePage.Home })
        SettingsHomePage.Residents -> LuluResidentManagementScreen(onBack = { page = SettingsHomePage.Home })
        SettingsHomePage.Image -> LuluImageSettingsScreen(onBack = { page = SettingsHomePage.Home })
        SettingsHomePage.Vision -> LuluVisionSettingsScreen(onBack = { page = SettingsHomePage.Home })
        SettingsHomePage.Backup -> LuluBackupScreen(onBack = { page = SettingsHomePage.Home })
        SettingsHomePage.Home -> SettingsEntryScreen(
            onBack = onBack,
            onOpenApi = { page = SettingsHomePage.Api },
            onOpenCapabilities = { page = SettingsHomePage.Capabilities },
            onOpenIntegrations = { page = SettingsHomePage.Integrations },
            onOpenVoice = { page = SettingsHomePage.Voice },
            onOpenMemory = { page = SettingsHomePage.Memory },
            onOpenResidents = { page = SettingsHomePage.Residents },
            onOpenImage = { page = SettingsHomePage.Image },
            onOpenVision = { page = SettingsHomePage.Vision },
            onOpenBackup = { page = SettingsHomePage.Backup },
        )
    }
}

private enum class SettingsHomePage { Home, Api, Capabilities, Integrations, BrowserProbe, XhsNativeReader, Voice, Memory, Residents, Image, Vision, Backup }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsEntryScreen(
    onBack: () -> Unit,
    onOpenApi: () -> Unit,
    onOpenCapabilities: () -> Unit,
    onOpenIntegrations: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenResidents: () -> Unit,
    onOpenImage: () -> Unit,
    onOpenVision: () -> Unit,
    onOpenBackup: () -> Unit,
) {
    val journal = LocalLuluAppearance.current.isJournal()
    var showUpdateNotes by rememberSaveable { mutableStateOf(false) }
    if (showUpdateNotes) LuluUpdateNotesDialog { showUpdateNotes = false }
    Scaffold(
        modifier = if (journal) Modifier.journalLinen(LocalLuluAppearance.current) else Modifier,
        containerColor = if (journal) Color.Transparent else SettingsHomePaper,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("设置", fontWeight = FontWeight.SemiBold, color = SettingsHomeInk)
                        if (journal) JournalPressedFlower(Modifier.padding(start = 8.dp).size(22.dp))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, "返回", tint = SettingsHomeInk)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SettingsHomePaper),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                LuluEditorialHeader(
                    overline = "LULU  /  PREFERENCES",
                    title = "让每一处，都合你心意",
                    subtitle = "管理连接、角色能力与数据，让日常相处更轻松。",
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("露露机 ${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_REVISION}",
                        modifier = Modifier.weight(1f), color = SettingsHomeMuted, fontSize = 12.sp)
                    TextButton(onClick = { showUpdateNotes = true }) { Text("更新内容", fontSize = 12.sp) }
                }
            }
            item { LuluEditorialSectionHeading(title = "模型与连接") }
            item {
                SettingsEntryRow(
                    icon = Icons.Outlined.Api,
                    title = "API 设置",
                    subtitle = "配置聊天站点、模型与当前聊天模型",
                    onClick = onOpenApi,
                )
            }
            item {
                SettingsEntryRow(
                    icon = Icons.Outlined.Extension,
                    title = "MCP 与联网服务",
                    subtitle = "小红书、华为手环、通用网页搜索 · 所有角色共用",
                    onClick = onOpenIntegrations,
                )
            }
            item {
                SettingsEntryRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    title = "语音设置",
                    subtitle = "配置 TTS、自动朗读、语言、语速与音调",
                    onClick = onOpenVoice,
                )
            }
            item {
                SettingsEntryRow(
                    icon = Icons.Outlined.Psychology,
                    title = "记忆设置",
                    subtitle = "配置记忆抽取、Embedding 向量检索与 Rerank 模型",
                    onClick = onOpenMemory,
                )
            }
            item { LuluEditorialSectionHeading(title = "角色与设备") }
            item {
                SettingsEntryRow(
                    icon = Icons.Outlined.Groups,
                    title = "居民管理",
                    subtitle = "管理数字世界居民的每日活动安排",
                    onClick = onOpenResidents,
                )
            }
            item {
                SettingsEntryRow(
                    icon = Icons.Outlined.Visibility,
                    title = "识图设置",
                    subtitle = "配置朋友圈图片理解模型，让角色真正看懂你发的图片",
                    onClick = onOpenVision,
                )
            }
            item {
                SettingsEntryRow(
                    icon = Icons.Outlined.Image,
                    title = "生图设置",
                    subtitle = "配置图片生成接口、模型、尺寸与默认提示",
                    onClick = onOpenImage,
                )
            }
            item {
                SettingsEntryRow(
                    icon = Icons.Outlined.Security,
                    title = "权限与能力",
                    subtitle = "位置、闹钟、应用感知、通知、屏幕控制与后台运行",
                    onClick = onOpenCapabilities,
                )
            }
            item { LuluEditorialSectionHeading(title = "数据与安心") }
            item {
                SettingsEntryRow(
                    icon = Icons.Outlined.Backup,
                    title = "备份与恢复",
                    subtitle = "把全部数据打成包，卸了 app 也还在；支持每天自动备份与云备份",
                    onClick = onOpenBackup,
                )
            }
        }
    }
}

@Composable
private fun SettingsEntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = if (LocalLuluAppearance.current.isJournal())
            RoundedCornerShape(8.dp, 20.dp, 9.dp, 18.dp) else
                RoundedCornerShape(LocalLuluAppearance.current.corner.coerceIn(12, 26).dp),
        color = SettingsHomeCard,
        border = BorderStroke(1.dp, SettingsHomeBorder.copy(alpha = 0.68f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .then(if (LocalLuluAppearance.current.isJournal())
                    Modifier.journalPaper(LocalLuluAppearance.current) else Modifier)
                .padding(horizontal = 15.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = RoundedCornerShape(13.dp),
                color = SettingsHomeAccent.copy(alpha = 0.7f)) {
                Icon(
                    icon,
                    null,
                    modifier = Modifier.padding(11.dp).size(21.dp),
                    tint = SettingsHomeAccentStrong,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = SettingsHomeInk)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = SettingsHomeMuted, fontSize = 12.sp, lineHeight = 18.sp)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = SettingsHomeMuted)
        }
    }
}
