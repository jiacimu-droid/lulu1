package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.design.LuluColors

enum class MigrationRoute {
    Home,
    Chat,
    ChatDetail,
    CharacterSettings,
    Memory,
    Lexicon,
    WorldBook,
    Performance,
    Reading,
    Wishes,
    Study,
    Games,
    Settings,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationEmptyScreen(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
) {
    Scaffold(
        containerColor = LuluColors.Paper,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = LuluColors.Card),
                border = BorderStroke(1.dp, LuluColors.Border),
                shape = RoundedCornerShape(26.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(shape = RoundedCornerShape(20.dp), color = LuluColors.CardStrong) {
                        Icon(
                            Icons.Outlined.AutoStories,
                            null,
                            tint = LuluColors.BlueGray,
                            modifier = Modifier.padding(16.dp).size(38.dp),
                        )
                    }
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(subtitle, color = LuluColors.Muted)
                    Text(
                        "这里保留独立路由和未来数据边界，后续增加阅读功能时不会重新改桌面结构。",
                        color = LuluColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
