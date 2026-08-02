package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SettingsHomePaper = Color(0xFFF8FAF8)
private val SettingsHomeCard = Color(0xFFFCFDFC)
private val SettingsHomeBorder = Color(0xFFDDE7E3)
private val SettingsHomeMuted = Color(0xFF7D8C88)
private val SettingsHomeInk = Color(0xFF34413F)
private val SettingsHomeAccent = Color(0xFFDCEAE6)
private val SettingsHomeAccentStrong = Color(0xFF607A75)

@Composable
fun LuluSettingsHomeScreen(onBack: () -> Unit) {
    var showApiSettings by rememberSaveable { mutableStateOf(false) }
    if (showApiSettings) {
        LuluSettingsScreen(onBack = { showApiSettings = false })
        return
    }

    SettingsEntryScreen(
        onBack = onBack,
        onOpenApi = { showApiSettings = true },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsEntryScreen(
    onBack: () -> Unit,
    onOpenApi: () -> Unit,
) {
    Scaffold(
        containerColor = SettingsHomePaper,
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, "返回")
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
                    title = "API 设置",
                    subtitle = "配置站点、拉取模型并管理模型存档",
                    onClick = onOpenApi,
                )
            }
        }
    }
}

@Composable
private fun SettingsEntryRow(
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
                    Icons.Outlined.Api,
                    null,
                    modifier = Modifier.padding(9.dp).size(22.dp),
                    tint = SettingsHomeAccentStrong,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = SettingsHomeInk)
                Text(subtitle, color = SettingsHomeMuted, fontSize = 12.sp)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = SettingsHomeMuted)
        }
    }
}
