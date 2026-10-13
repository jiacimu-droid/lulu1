package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Read-only history over the existing authoritative stores. */
@Composable
internal fun CharacterLifeAuditScreen(characterId: String, modifier: Modifier = Modifier) {
    var section by rememberSaveable(characterId) { mutableIntStateOf(0) }
    var now by remember(characterId) { mutableStateOf(Instant.now()) }
    val life by CharacterLifeStore.states.collectAsState()
    val mindRevision by CharacterInnerLifeStore.revisions.collectAsState()
    val developmentRevision by CharacterDevelopmentStore.revisions.collectAsState()
    LaunchedEffect(characterId) {
        while (true) {
            delay(12_000L)
            now = Instant.now()
        }
    }
    val categories = listOf("成长", "行动", "探索")
    val history = remember(characterId, now, life, mindRevision, developmentRevision) {
        CharacterLifeVisibility.archive(characterId, now)
    }
    val visible = history.filter { it.category == categories[section] }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEachIndexed { index, title ->
                FilterChip(selected = section == index, onClick = { section = index },
                    label = { Text(title) })
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (visible.isEmpty()) item(key = "empty") {
                Text("还没有可追溯的记录。", color = Color(0xFF7A7A7E))
            }
            else itemsIndexed(visible, key = { index, entry ->
                entry.category + ":" + entry.title + ":" + entry.at.toString() + ":" +
                    entry.evidenceId + ":" + index
            }) { _, entry ->
                Surface(color = Color.White,
                    shape = RoundedCornerShape(15.dp),
                    border = BorderStroke(1.dp, Color(0xFFE7E7E7))) {
                    Column(Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(entry.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF262629))
                        Text(entry.detail, fontSize = 13.sp, lineHeight = 19.sp,
                            color = Color(0xFF49494E))
                        Text(entry.at.atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                            color = Color(0xFF818187), fontSize = 11.sp)
                        if (entry.evidenceId.isNotBlank()) {
                            var showSource by remember(entry.evidenceId) { mutableStateOf(false) }
                            TextButton(onClick = { showSource = !showSource }) { Text(if (showSource) "收起依据" else "查看依据") }
                            if (showSource) {
                                val source = SharedExperienceTimeline.eventsByIds(characterId, listOf(entry.evidenceId)).firstOrNull()
                                Text(source?.content ?: "原始依据已撤回", fontSize = 12.sp,
                                    color = Color(0xFF818187))
                            }
                        }
                    }
                }
            }
        }
    }
}
