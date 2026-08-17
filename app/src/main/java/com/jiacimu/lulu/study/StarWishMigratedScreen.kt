package com.jiacimu.lulu.study

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class StarWishSection { HOME, SCROLL, THEATER }

@Composable
internal fun StarWishMigratedScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { StarWishStores.main }
    val studyStore = remember { PostgraduateExamStores.main }
    val state by store.state.collectAsState()
    val studyState by studyStore.state.collectAsState()
    var section by rememberSaveable { mutableStateOf(StarWishSection.HOME) }

    BackHandler(enabled = section != StarWishSection.THEATER) {
        if (section == StarWishSection.HOME) onBack() else section = StarWishSection.HOME
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        when (section) {
            StarWishSection.HOME -> Column(Modifier.fillMaxSize().padding(padding)) {
                StarWishModuleTopBar("心愿馆", onBack)
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("选择一个空间", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    StarWishEntranceCard(
                        title = "画卷",
                        subtitle = "使用图片模型生成与收藏画卷",
                        icon = { Icon(Icons.Outlined.Collections, null) },
                        onClick = { section = StarWishSection.SCROLL },
                    )
                    StarWishEntranceCard(
                        title = "小剧场",
                        subtitle = "创建故事、阅读章节与管理独立存档",
                        icon = { Icon(Icons.Outlined.AutoStories, null) },
                        onClick = { section = StarWishSection.THEATER },
                    )
                }
            }

            StarWishSection.SCROLL -> Column(Modifier.fillMaxSize().padding(padding)) {
                StarWishModuleTopBar("画卷", onBack = { section = StarWishSection.HOME })
                StarWishScrollContent(state, studyState, store, context)
            }

            StarWishSection.THEATER -> Box(Modifier.fillMaxSize().padding(padding)) {
                StarWishTheaterContentV2(
                    state = state,
                    studyState = studyState,
                    store = store,
                    onExit = { section = StarWishSection.HOME },
                )
            }
        }
    }
}

@Composable
private fun StarWishModuleTopBar(title: String, onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StarWishEntranceCard(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Box(Modifier.padding(14.dp), contentAlignment = Alignment.Center) { icon() }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, color = StudyDesign.muted, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = StudyDesign.muted)
        }
    }
}
