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

private val SettingsHomePaper = Color.White
private val SettingsHomeCard = Color(0xFFFCFCFC)
private val SettingsHomeBorder = Color(0xFFE7E7E7)
private val SettingsHomeMuted = Color(0xFF7A7A7E)
private val SettingsHomeInk = Color(0xFF1D1D1F)
private val SettingsHomeAccent = Color(0xFFF4F4F4)
private val SettingsHomeAccentStrong = Color(0xFF292929)

@Composable
fun LuluSettingsHomeScreen(onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(SettingsHomePage.Home) }
    BackHandler(enabled = page != SettingsHomePage.Home) {
        page = SettingsHomePage.Home
    }
    when (page) {
        SettingsHomePage.Api -> LuluSettingsScreen(onBack = { page = SettingsHomePage.Home })
        SettingsHomePage.Capabilities -> LuluCapabilitiesScreen(onBack = { page = SettingsHomePage.Home })
        SettingsHomePage.Voice -> LuluVoiceSettingsScreen(onBack = { page = SettingsHomePage.Home })
        SettingsHomePage.Memory -> LuluMemorySettingsScreen(onBack = { page = SettingsHomePage.Home })
        SettingsHomePage.Image -> LuluImageSettingsScreen(onBack = { page = SettingsHomePage.Home })
        SettingsHomePage.Home -> SettingsEntryScreen(
            onBack = onBack,
            onOpenApi = { page = SettingsHomePage.Api },
            onOpenCapabilities = { page = SettingsHomePage.Capabilities },
            onOpenVoice = { page = SettingsHomePage.Voice },
            onOpenMemory = { page = SettingsHomePage.Memory },
            onOpenImage = { page = SettingsHomePage.Image },
        )
    }
}

private enum class SettingsHomePage { Home, Api, Capabilities, Voice, Memory, Image }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsEntryScreen(
    onBack: () -> Unit,
    onOpenApi: () -> Unit,
    onOpenCapabilities: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenImage: () -> Unit,
) {
    Scaffold(
        containerColor = SettingsHomePaper,
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.SemiBold, color = SettingsHomeInk) },
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
        shape = RoundedCornerShape(18.dp),
        color = SettingsHomeCard,
        border = BorderStroke(1.dp, SettingsHomeBorder),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = SettingsHomeAccent) {
                Icon(
                    icon,
                    null,
                    modifier = Modifier.padding(9.dp).size(22.dp),
                    tint = SettingsHomeAccentStrong,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = SettingsHomeInk)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = SettingsHomeMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = SettingsHomeMuted)
        }
    }
}
