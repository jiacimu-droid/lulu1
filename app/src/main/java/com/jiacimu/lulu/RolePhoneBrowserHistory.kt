package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.EventEvidenceKind
import com.jiacimu.lulu.data.CharacterInnerLifeStore
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.SharedTimelineEvent
import com.jiacimu.lulu.design.LuluColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A projection of actual ToolRouter receipts; no shadow browser database.
 * Failed searches stay visible as failed, while impressions are only the
 * reason saved with the role's own decision, not generated afterwards. */
internal data class RolePhoneSearchEntry(
    val id: String,
    val kind: String,
    val query: String,
    val at: java.time.Instant,
    val status: String,
    val reason: String,
    val summary: String,
    val reflection: String = "",
)

internal fun rolePhoneSearchEntries(
    events: List<SharedTimelineEvent>, innerHistory: JSONObject? = null,
): List<RolePhoneSearchEntry> =
    events.asSequence().mapNotNull { event ->
        if (event.channel != "实际工具执行" || event.source != "tool-router" ||
            event.evidenceKind != EventEvidenceKind.ToolResult) return@mapNotNull null
        val receipt = runCatching { JSONObject(event.content) }.getOrNull() ?: return@mapNotNull null
        val tool = receipt.optString("tool")
        if (tool !in setOf("web_search", "image_search", "read_xiaohongshu")) return@mapNotNull null
        val args = runCatching {
            JSONObject(receipt.optString("fingerprint").substringAfter(':'))
        }.getOrDefault(JSONObject())
        val result = runCatching { JSONObject(receipt.optString("result")) }.getOrDefault(JSONObject())
        val name = when (tool) {
            "web_search" -> "网页"
            "image_search" -> "图片"
            else -> "小红书"
        }
        val query = sequenceOf("query", "keyword", "url", "searchId")
            .map { args.optString(it).trim() }.firstOrNull(String::isNotBlank)
            ?: "未指定检索词"
        val summary = result.optString("summary").ifBlank {
            result.optString("error").ifBlank { result.optString("message") }
        }
        val transitions = innerHistory?.optJSONArray("causalTransitions")
        val reflection = (0 until (transitions?.length() ?: 0))
            .mapNotNull { transitions?.optJSONObject(it) }
            .lastOrNull { it.optString("evidenceId") == event.id }
            ?.let { transition ->
                transition.optString("innerThought").ifBlank {
                    transition.optJSONObject("appraisal")?.optString("meaning").orEmpty()
                }
            }.orEmpty()
        RolePhoneSearchEntry(
            id = event.id, kind = name, query = query.take(240),
            at = event.occurredAt,
            status = receipt.optString("status").ifBlank { "unknown" },
            reason = receipt.optString("reason").take(400),
            summary = summary.take(2400),
            reflection = reflection.take(680),
        )
    }.sortedByDescending(RolePhoneSearchEntry::at).toList()

@Composable
internal fun RolePhoneBrowserHistory(roleId: String) {
    val revision by SharedExperienceTimeline.revisions.collectAsState()
    val innerRevision by CharacterInnerLifeStore.revisions.collectAsState()
    val entries by produceState<List<RolePhoneSearchEntry>>(
        initialValue = emptyList(), roleId, revision, innerRevision,
    ) {
        value = withContext(Dispatchers.IO) {
            rolePhoneSearchEntries(
                SharedExperienceTimeline.recentEvents(roleId, 1600),
                CharacterInnerLifeStore.historySnapshot(roleId),
            )
        }
    }
    var expanded by remember(roleId) { mutableStateOf<String?>(null) }
    val clock = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm") }
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp)) {
            Text("最近还没有真实的网页、小红书或图片搜索记录", color = LuluColors.Muted)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("最近的真实搜索与阅读记录", color = LuluColors.Muted, fontSize = 12.sp) }
        items(entries, key = RolePhoneSearchEntry::id) { entry ->
            val open = expanded == entry.id
            Surface(
                modifier = Modifier.fillMaxWidth().clickable {
                    expanded = if (open) null else entry.id
                },
                shape = RoundedCornerShape(17.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(entry.kind, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        Text(entry.at.atZone(ZoneId.systemDefault()).format(clock),
                            color = LuluColors.Muted, fontSize = 11.sp)
                    }
                    Text(entry.query, fontSize = 15.sp, maxLines = if (open) 10 else 2,
                        overflow = TextOverflow.Ellipsis)
                    val success = entry.status == "succeeded"
                    Text(if (success) "已执行" else "状态：${entry.status}",
                        color = LuluColors.Muted, fontSize = 12.sp)
                    if (open) {
                        if (entry.reason.isNotBlank()) {
                            Text("当时的动机", fontWeight = FontWeight.Medium, fontSize = 12.sp)
                            Text(entry.reason, fontSize = 13.sp)
                        }
                        if (entry.reflection.isNotBlank()) {
                            Text("检索后自己的感想", fontWeight = FontWeight.Medium, fontSize = 12.sp)
                            Text(entry.reflection, fontSize = 13.sp)
                        }
                        Text("实际回执", fontWeight = FontWeight.Medium, fontSize = 12.sp)
                        Text(entry.summary.ifBlank { "没有可显示的结果正文" }, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
