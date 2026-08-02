package com.jiacimu.lulu.study

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Desktop reading entry.
 *
 * It intentionally reuses the same theater bookshelf and reader as Star Wish,
 * so reading progress, generated chapters and story plans stay in one source of truth.
 */
@Composable
fun LuluReadingScreen(onBack: () -> Unit) {
    val store = remember { StarWishStores.main }
    val studyStore = remember { PostgraduateExamStores.main }
    val state by store.state.collectAsState()
    val studyState by studyStore.state.collectAsState()

    Scaffold(containerColor = StudyDesign.paper) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Surface(
                color = StudyDesign.paper,
                tonalElevation = 1.dp,
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(58.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                    Text(
                        text = "阅读",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            StarWishTheaterContent(
                state = state,
                studyState = studyState,
                store = store,
                studyStore = studyStore,
            )
        }
    }
}
