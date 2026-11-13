package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.EventEvidenceKind
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.SharedTimelineEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A small read-only projection of the original ToolRouter receipts into
 * 查手机. No speculative web/app content, no model, no new shadow activity
 * store. The role can independently choose whether to tell the owner more.
 */
internal data class InspectedPhoneAction(
    val label: String,
    val purpose: String,
    val outcome: String,
    val occurredAt: Instant,
    val isUnverified: Boolean,
)

internal fun inspectedPhoneActions(events: List<SharedTimelineEvent>): List<InspectedPhoneAction> {
    val names = mapOf(
        "background_phone" to "后台手机",
        "phone_ui" to "手机操作",
        "read_xiaohongshu" to "小红书阅读",
        "web_search" to "网页搜索",
        "image_search" to "图片搜索",
    )
    return events.asSequence()
        .filter { it.source == "tool-router" &&
            it.evidenceKind == EventEvidenceKind.ToolResult }
        .sortedByDescending(SharedTimelineEvent::occurredAt)
        .mapNotNull { event ->
            val attempt = runCatching { JSONObject(event.content) }.getOrNull()
                ?: return@mapNotNull null
            val tool = attempt.optString("tool")
            val label = names[tool] ?: return@mapNotNull null
            val result = attempt.optString("result").takeIf { it.isNotBlank() }?.let {
                runCatching { JSONObject(it) }.getOrNull()
            }
            val status = result?.optString("status").orEmpty()
                .ifBlank { attempt.optString("status") }
            val outcome = when (status) {
                "denied", "failed" -> "失败 · " +
                    (result?.optString("error").orEmpty().ifBlank {
                        result?.optString("verification").orEmpty().ifBlank { "未执行成功" }
                    }).take(76).replace("\n", " ")
                "unverified", "pending", "waiting_user" ->
                    "尚未确认成功 · " + result?.optString("verification")
                        .orEmpty().take(62).replace("\n", " ")
                "observed", "captured" -> "已取得画面，任务结果仍需核实"
                "succeeded", "completed" -> "工具已返回成功回执"
                else -> "已留下工具执行回执 · ${status.ifBlank { "状态未知" }}"
            }
            val authored = attempt.optString("reason").replace("\n", " ").take(84)
            InspectedPhoneAction(label, authored, outcome, event.occurredAt,
                status !in setOf("succeeded", "completed", "observed", "captured"))
        }.take(4).toList()
}

private val ActivityTime = DateTimeFormatter.ofPattern("HH:mm")

@Composable
internal fun RolePhoneActivitySection(roleId: String) {
    // Keep the chat list close to the first viewport. The owner can reveal
    // the remaining real action receipts instead of scrolling past them.
    var expanded by remember(roleId) { mutableStateOf(false) }
    val version by SharedExperienceTimeline.revisions.collectAsState()
    val history by produceState<List<InspectedPhoneAction>>(emptyList(), roleId, version) {
        value = if (roleId.isNotBlank()) withContext(Dispatchers.IO) {
            inspectedPhoneActions(SharedExperienceTimeline.recentEvents(roleId, 120))
        } else emptyList()
    }
    if (history.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("最近使用", fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold, color = Color(0xFF1D1D1F),
                modifier = Modifier.weight(1f))
            // Technical receipts remain available below the actions, not in the chat feed.
        }
        Surface(
            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, Color(0xFFE7E8EB)),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                (if (expanded) history else history.take(2)).forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = Color(0xFFEEEEF0))
                    Row(verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.History, null, tint = Color(0xFF73767C),
                            modifier = Modifier.size(19.dp))
                        Column(Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(item.label, fontWeight = FontWeight.Medium, fontSize = 14.sp,
                                color = Color(0xFF1D1D1F))
                            if (item.purpose.isNotBlank()) {
                                Text("当时想做 · ${item.purpose}", fontSize = 12.sp,
                                    color = Color(0xFF73767C), maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                            }
                            Text(item.outcome, fontSize = 12.sp,
                                color = if (item.isUnverified) Color(0xFF946341)
                                    else Color(0xFF58615D),
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        Text(ActivityTime.format(item.occurredAt.atZone(ZoneId.systemDefault())),
                            fontSize = 12.sp, color = Color(0xFF73767C))
                    }
                }
                if (history.size > 2) {
                    TextButton(onClick = { expanded = !expanded },
                        modifier = Modifier.fillMaxWidth()) {
                        Text(if (expanded) "收起动态" else "查看另 ${history.size - 2} 条操作记录")
                        Icon(if (expanded) Icons.Outlined.ExpandLess
                            else Icons.Outlined.ExpandMore, null)
                    }
                }
            }
        }
    }
}
