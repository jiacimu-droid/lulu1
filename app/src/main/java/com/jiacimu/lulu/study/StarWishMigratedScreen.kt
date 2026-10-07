package com.jiacimu.lulu.study

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun StarWishScrollApp(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { StarWishStores.main }
    val studyStore = remember { PostgraduateExamStores.main }
    val state by store.state.collectAsState()
    val studyState by studyStore.state.collectAsState()

    BackHandler(onBack = onBack)

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            StarWishBackOnlyBar(onBack)
            StarWishScrollContent(state, studyState, store, context)
        }
    }
}

@Composable
internal fun StarWishTheaterApp(onBack: () -> Unit) {
    val store = remember { StarWishStores.main }
    val studyStore = remember { PostgraduateExamStores.main }
    val state by store.state.collectAsState()
    val studyState by studyStore.state.collectAsState()

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            StarWishTheaterContentV2(
                state = state,
                studyState = studyState,
                store = store,
                onExit = onBack,
            )
        }
    }
}

/** Kept only so an old in-memory route cannot crash after an app update. */
@Composable
internal fun StarWishMigratedScreen(onBack: () -> Unit) {
    StarWishTheaterApp(onBack)
}

@Composable
private fun StarWishBackOnlyBar(onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, "返回桌面")
            }
        }
    }
}
