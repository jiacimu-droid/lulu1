package com.jiacimu.lulu.study

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun StarWishMigratedScreen(onBack: () -> Unit, initialTab: StarWishTab = StarWishTab.Scroll) {
    val context = LocalContext.current
    val store = remember { StarWishStores.main }
    val studyStore = remember { PostgraduateExamStores.main }
    val state by store.state.collectAsState()
    val studyState by studyStore.state.collectAsState()
    var tab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }
    fun stepBack() {
        if (tab == StarWishTab.Scroll) onBack() else tab = StarWishTab.Scroll
    }
    BackHandler { stepBack() }

    Scaffold(containerColor = StudyDesign.paper) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Surface(color = StudyDesign.paper, tonalElevation = 1.dp, shadowElevation = 1.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().height(58.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = ::stepBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                    StarWishTab.entries.forEach { item ->
                        val selected = tab == item
                        TextButton(
                            onClick = { tab = item },
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else StudyDesign.muted,
                            ),
                            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
                        ) {
                            Text(item.label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (tab == StarWishTab.Theater) {
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(
                                "小剧场券 ${studyState.inventory.theaterFragments}",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            when (tab) {
                StarWishTab.Scroll -> StarWishScrollContent(state, studyState, store, context)
                StarWishTab.Theater -> StarWishTheaterContentV2(state, studyState, store, studyStore)
            }
        }
    }
}
