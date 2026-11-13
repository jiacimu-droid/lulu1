package com.jiacimu.lulu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.jiacimu.lulu.data.CharacterLifeStore
import com.jiacimu.lulu.data.CharacterUnfinishedActionContext
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.CharacterInnerLifeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val MemoDate = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private fun memoTime(raw: String): String =
    runCatching { MemoDate.format(Instant.parse(raw).atZone(ZoneId.systemDefault())) }
        .getOrDefault("")

/** Read-only owner inspection of what the person actually saved themselves. */
@Composable
internal fun RolePhoneMemoScreen(roleId: String) {
    val context = LocalContext.current
    val lifeRevision by CharacterLifeStore.states.collectAsState()
    val timelineRevision by SharedExperienceTimeline.revisions.collectAsState()
    val mindRevision by CharacterInnerLifeStore.revisions.collectAsState()
    val memos = remember(roleId, lifeRevision) {
        CharacterLifeStore.phoneMemos(roleId).sortedByDescending { it.optString("updatedAt") }
    }
    val pending by produceState(
        initialValue = emptyList<CharacterUnfinishedActionContext.Cue>(),
        roleId, timelineRevision, mindRevision,
    ) {
        value = if (roleId.isBlank()) emptyList() else withContext(Dispatchers.IO) {
            runCatching { CharacterUnfinishedActionContext.candidates(context, roleId) }
                .getOrDefault(emptyList())
        }
    }
    var openedMemo by rememberSaveable(roleId) { mutableStateOf("") }
    val focused = memos.firstOrNull { it.optString("id") == openedMemo }
    BackHandler(enabled = focused != null) { openedMemo = "" }

    if (focused != null) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { openedMemo = "" }) {
                Icon(Icons.Outlined.ArrowBack, null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(6.dp))
                Text("全部备忘录")
            }
            Text(focused.optString("title"), fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp, lineHeight = 31.sp)
            Text(memoTime(focused.optString("updatedAt")),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
                item {
                    Text(focused.optString("content"), fontSize = 16.sp,
                        lineHeight = 26.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("notes-title") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("他的备忘录", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                Text("${memos.size} 则记录", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (memos.isEmpty()) item("notes-empty") {
            Box(Modifier.fillMaxWidth().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center) {
                Text("还没有写下备忘录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(memos, key = { it.optString("id") }) { memo ->
            Surface(
                modifier = Modifier.fillMaxWidth().clickable {
                    openedMemo = memo.optString("id")
                },
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Description, null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(memo.optString("title"), fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(memoTime(memo.optString("updatedAt")), fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(memo.optString("content"), maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 14.sp, lineHeight = 21.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (pending.isNotEmpty()) {
            item("pending-title") {
                Column(Modifier.padding(top = 15.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("心里记挂的事", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("来自已有目标和责任，尚不代表已完成",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(pending.take(12), key = { "cue:${it.kind}:${it.id}" }) { cue ->
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Schedule, null, modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(cue.title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(cue.kind, fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
