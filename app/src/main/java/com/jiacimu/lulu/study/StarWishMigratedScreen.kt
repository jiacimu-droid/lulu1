package com.jiacimu.lulu.study

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarWishMigratedScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { StarWishStores.main }
    val studyStore = remember { PostgraduateExamStores.main }
    val state by store.state.collectAsState()
    val studyState by studyStore.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(StarWishTab.Scroll) }

    Scaffold(
        containerColor = StudyDesign.paper,
        topBar = {
            TopAppBar(
                title = { Text("心愿馆", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = StudyDesign.paper),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StarWishTab.entries.forEach { item ->
                    FilterChip(
                        selected = tab == item,
                        onClick = { tab = item },
                        label = { Text(item.label) },
                    )
                }
            }
            when (tab) {
                StarWishTab.Scroll -> StarWishScrollContent(
                    state = state,
                    studyState = studyState,
                    store = store,
                    context = context,
                )
                StarWishTab.Theater -> StarWishTheaterContent(
                    state = state,
                    studyState = studyState,
                    store = store,
                    studyStore = studyStore,
                )
                StarWishTab.Video -> StarWishVideoContent(
                    state = state,
                    studyState = studyState,
                    store = store,
                    studyStore = studyStore,
                    context = context,
                )
            }
        }
    }
}
