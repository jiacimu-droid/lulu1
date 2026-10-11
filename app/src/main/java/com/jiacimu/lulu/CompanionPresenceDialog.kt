package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jiacimu.lulu.data.CompanionPresenceState
import com.jiacimu.lulu.data.CompanionPresenceStore
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.ProactivePerceptionScheduler
import java.time.Instant
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import com.jiacimu.lulu.data.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun CompanionPresenceDialog(
    characterName: String,
    state: CompanionPresenceState?,
    history: List<CompanionPresenceState>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val characterId = state?.characterId ?: MigratedDomainStores.characters.settings.value.values
        .firstOrNull { it.displayName == characterName }?.characterId ?: "lulu"
    val messageAnchor = CompanionPresenceStore.selectedMessageAnchor(characterId)
    val displayState = if (messageAnchor != null) messageAnchor.state else state
    val displayHistory = remember(history, messageAnchor?.messageAt) {
        if (messageAnchor == null) history else history.filter { it.updatedAt <= messageAnchor.messageAt }
    }
    var selectedTab by remember(characterId) { mutableIntStateOf(0) }
    var manualCheckRequested by remember { mutableStateOf(false) }
    val plans by PerceptionWakePlanStore.plans.collectAsState()
    val schedulingError by PerceptionWakePlanStore.schedulingError.collectAsState()
    val policies by ProactivePerceptionPolicyStore.policies.collectAsState()
    val onlineStates by CompanionOnlineStore.states.collectAsState()
    val policy = policies[characterId] ?: ProactivePerceptionPolicyStore.get(characterId)
    var now by remember { mutableStateOf(Instant.now()) }
    val maxDialogHeight = LocalConfiguration.current.screenHeightDp.dp * 0.88f
    LaunchedEffect(characterId, policy, state?.lastPerceptionAt, state?.updatedAt) {
        withContext(Dispatchers.IO) { ProactivePerceptionRuntime.wakePlanFor(context, characterId) }
    }
    LaunchedEffect(characterId) {
        while (true) { delay(15_000L); now = Instant.now() }
    }
    // History contains EVERY saved meaningful moment, including the newest one.
    // "Current" never enumerates the character's accumulated private thought store.
    val past = remember(displayHistory) { PresenceHistoryPresentation.entries(displayHistory) }
    val latestHeartMoment = PresenceHistoryPresentation.latestVoice(
        displayState, displayHistory, messageAnchored = messageAnchor != null,
    )
    val latestThinkingMoment = PresenceHistoryPresentation.latestReflection(
        displayState, displayHistory, messageAnchored = messageAnchor != null,
    )
    val dismiss = {
        CompanionPresenceStore.clearMessageAnchor()
        onDismiss()
    }

    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 22.dp, vertical = 24.dp)
                .fillMaxWidth()
                .heightIn(max = maxDialogHeight),
            color = Color.White,
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, Color(0xFFE7E7E7)),
            shadowElevation = 10.dp,
        ) {
            Box(Modifier.fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "header") {
                        Column(
                            modifier = Modifier.padding(end = 42.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            
                            Text("$characterName · 此刻", color = Color(0xFF1D1D1F), fontWeight = FontWeight.Bold, fontSize = 23.sp)
                            if (messageAnchor != null) {
                                Text("这条消息发出时的此刻", color = Color(0xFF7A7A7E), fontSize = 11.sp)
                            }
                        }
                    }
                    item(key = "tabs") {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(selected = selectedTab == 0, onClick = { selectedTab = 0 }, label = { Text("当前") })
                            FilterChip(selected = selectedTab == 1, onClick = { selectedTab = 1 }, label = { Text("历史") })
                            FilterChip(selected = selectedTab == 2, onClick = { selectedTab = 2 }, label = { Text("成长轨迹") })
                            FilterChip(selected = selectedTab == 3, onClick = { selectedTab = 3 }, label = { Text("自主行动") })
                        }
                    }

                    if (selectedTab == 0) {
                        item(key = "current") {
                            if (displayState == null) {
                                Text(if (messageAnchor == null) "还没有留下此刻状态。" else "这条消息之前还没有形成可查看的此刻状态。", color = Color(0xFF7A7A7E))
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    PresenceStateContent(displayState, latestHeartMoment, latestThinkingMoment)
                                    if (messageAnchor == null) {
                                        CharacterSelfActivityRuntime.latest(context, characterId)?.let { activity ->
                                            val created = activity.optLong("createdAt")
                                            if (created > 0L && now.toEpochMilli() - created in 0L..(3 * 60 * 60_000L)) {
                                                val status = when (activity.optString("status")) {
                                                    "pending" -> "已决定 · 等待执行"
                                                    "running" -> "正在真实执行"
                                                    "completed" -> "已执行 · 有回执"
                                                    "failed" -> "没有完成"
                                                    else -> "尚未确认"
                                                }
                                                val lines = listOf(
                                                    activity.optString("label"),
                                                    activity.optString("whyNow").takeIf(String::isNotBlank),
                                                    activity.optString("result").takeIf(String::isNotBlank),
                                                ).filterNotNull().joinToString("\n")
                                                PresenceDialogSection("自己的生活动作 · $status", lines)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else if (selectedTab == 1) {
                        if (past.isEmpty()) {
                            item(key = "empty-history") {
                                Text("还没有留下可查看的历史状态。", color = Color(0xFF7A7A7E))
                            }
                        } else {
                            items(past, key = { it.state.updatedAt.toEpochMilli() }) { entry ->
                                val item = entry.state
                                Surface(
                                    color = Color(0xFFF7F7F7),
                                    shape = RoundedCornerShape(18.dp),
                                    border = BorderStroke(1.dp, Color(0xFFE7E7E7)),
                                ) {
                                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(item.updatedAt.atZone(ZoneId.systemDefault())
                                            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm")),
                                            color = Color(0xFF7A7A7E), fontSize = 11.sp)
                                        if (item.innerThought.isNotBlank()) Text("心声 · ${item.innerThought}",
                                            color = Color(0xFF1D1D1F), fontSize = 14.sp)
                                        if (item.momentReflection.isNotBlank()) Text(item.momentReflection,
                                            color = Color(0xFF5F5F63), fontSize = 13.sp)
                                        if (entry.gesture.isNotBlank()) Text("动作 · ${entry.gesture}",
                                            color = Color(0xFF5F5F63), fontSize = 13.sp)
                                        if (entry.status.isNotBlank()) Text("状态 · ${entry.status}",
                                            color = Color(0xFF7A7A7E), fontSize = 12.sp)
                                        if (entry.mood.isNotBlank()) Text("心情 · ${entry.mood}",
                                            color = Color(0xFF7A7A7E), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        item(key = "mental-archive") {
                            PresenceMentalHistory(characterId, messageAnchor?.messageAt)
                        }
                    } else if (selectedTab == 2) {
                        item(key = "growth-trail") {
                            PresenceGrowthTrajectory(characterId, messageAnchor?.messageAt ?: now)
                        }
                    } else {
                        item(key = "autonomous-decisions") {
                            PresenceAutonomousDecisions(characterId, messageAnchor?.messageAt ?: now)
                        }
                    }

                    if (messageAnchor == null && selectedTab < 2) {
                        item(key = "awareness") {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                PerceptionStatusPresentation.lines(state, policy, plans[characterId],
                                    onlineStates[characterId]?.isOnline(now) == true,
                                    CompanionPresenceStore.isInCall(characterId), now).forEach {
                                    Text(it, color = Color(0xFF7A7A7E), fontSize = 12.sp)
                                }
                                if (schedulingError.isNotBlank()) Text(schedulingError,
                                    color = Color(0xFFB04C4C), fontSize = 12.sp)
                            }
                        }
                        item(key = "manual-check") {
                            OutlinedButton(
                                onClick = {
                                    ProactivePerceptionScheduler.scheduleManual(context, characterId)
                                    manualCheckRequested = true
                                },
                                enabled = !manualCheckRequested,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Text(if (manualCheckRequested) "已请求唤醒" else "唤醒一下")
                            }
                        }
                    }
                }

                IconButton(
                    onClick = dismiss,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
                ) {
                    Icon(Icons.Outlined.Close, "关闭此刻", tint = Color(0xFF1D1D1F))
                }
            }
        }
    }
}

@Composable
private fun PresenceStateContent(
    state: CompanionPresenceState,
    heartMoment: CompanionPresenceState?,
    thoughtMoment: CompanionPresenceState?,
) {
    // Keep the active action/status separate from the most recent *authored*
    // inner voice. An old thought may be the latest one, but it must retain its
    // original time, never be falsely attributed to today's state update.
    if (state.gesture.isNotBlank()) PresenceDialogSection("当前动作", state.gesture)
    val status = PresencePresentation.status(state)
    if (status.isNotBlank()) PresenceDialogSection("状态", status)
    val voice = heartMoment?.innerThought.orEmpty()
    if (voice.isNotBlank()) {
        val time = heartMoment!!.updatedAt.atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        val label = if (heartMoment.updatedAt == state.updatedAt) "这一刻的心声"
            else "最近一次心声 · $time"
        PresenceDialogSection(label, voice)
    }
    thoughtMoment?.momentReflection?.takeIf(String::isNotBlank)?.let { detail ->
        val date = thoughtMoment.updatedAt.atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        PresenceDialogSection(
            if (thoughtMoment.updatedAt == state.updatedAt) "此刻的小心思与顾虑"
            else "最近的小心思与顾虑 · $date",
            detail,
        )
    }
    // A chosen life activity is a real queued executor request, never merely
    // the model's private prose. Only show its receipt on the live tab, not a
    // historical message's anchored state.
    if (state.gesture.isBlank() && status.isBlank() && voice.isBlank()) {
        Text("这一刻还没有留下心声或动作。", color = Color(0xFF7A7A7E))
    }
    Text(
        "状态记录 · " + state.updatedAt.atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm")),
        color = Color(0xFF7A7A7E), fontSize = 11.sp,
    )
}

/**
 * The trace is a projection of real timeline evidence, personal revisions and
 * the existing growth store. It never fabricates a diary or assumes that a
 * feedback message means the character has already learned.
 */
@Composable
private fun PresenceGrowthTrajectory(characterId: String, cutoff: Instant) {
    val personalRevision by CharacterInnerLifeStore.revisions.collectAsState()
    val developmentRevision by CharacterDevelopmentStore.revisions.collectAsState()
    val entries = remember(characterId, cutoff, personalRevision, developmentRevision) {
        CharacterGrowthTrajectory.entries(characterId, cutoff)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("成长轨迹", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF262629))
        Text("从真实聊天、私人心理变化、日记及成长记录汇总。收到反馈不等于已经反思，写下决心不等于兑现；尚未发生的行动不会冒充经历。",
            fontSize = 12.sp, color = Color(0xFF747477))
        if (entries.isEmpty()) {
            PresenceDialogSection("暂无可核实的轨迹", "还没有形成可以追溯的反馈、心理转变或成长记录。普通闲聊不必强制产生改变。")
        } else {
            entries.forEach { entry ->
                val time = entry.at.atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                PresenceDialogSection("${entry.label} · $time",
                    "${entry.detail}\n来源：${entry.category} · ${entry.evidenceId.take(32)}")
            }
        }
    }
}

@Composable
private fun PresenceAutonomousDecisions(characterId: String, cutoff: Instant) {
    val presenceStates by CompanionPresenceStore.states.collectAsState()
    val latest = presenceStates[characterId]
    val lastAttemptAt = latest?.lastPerceptionAt
    val revision by CharacterInnerLifeStore.revisions.collectAsState()
    val mind = remember(characterId, revision) { CharacterInnerLifeStore.snapshot(characterId) }
    val items = mind.optJSONArray("decisions")
    val entries = (0 until (items?.length() ?: 0))
        .mapNotNull { items?.optJSONObject(it) }
        .filter { entry ->
            val time = runCatching { Instant.parse(entry.optString("at")) }.getOrNull()
            entry.optString("id").startsWith("perception:") &&
                time != null && !time.isAfter(cutoff)
        }.takeLast(24).asReversed()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("自主行动记录", fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold, color = Color(0xFF262629))
        Text("每次真实的自主决策：选择了什么、为什么、最终是否执行成功。安静不是错误，失败也不会假装成完成。这里只展示已保存的决策，不是新的模型调用。",
            color = Color(0xFF747477), fontSize = 12.sp)
        if (lastAttemptAt != null && !lastAttemptAt.isAfter(cutoff) &&
            !latest.lastPerceptionNote.isBlank()) {
            val time = lastAttemptAt.atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
            PresenceDialogSection("最近一次感知 · $time", latest.lastPerceptionNote)
        }
        if (entries.isEmpty()) {
            PresenceDialogSection("暂无保存的自主决策", "角色可能尚未在线感知，或以前的决策尚未记录在这份轨迹中。")
        } else {
            entries.forEach { entry ->
                val at = runCatching {
                    Instant.parse(entry.optString("at"))
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
                }.getOrDefault("")
                val action = entry.optString("selected")
                val silent = action == "silent"
                val succeeded = entry.optBoolean("succeeded")
                val status = when {
                    silent -> "自行选择安静"
                    succeeded -> "有执行回执"
                    else -> "尝试失败"
                }
                val detail = buildString {
                    appendLine("选择：$action")
                    val reason = entry.optString("reason").trim()
                    appendLine("理由：${reason.ifBlank { "没有给出具体理由" }}")
                    appendLine("结果：${entry.optString("outcome").ifBlank { "未取得可验证的执行结果" }}")
                    if (entry.optString("causalEvidenceId").isNotBlank()) {
                        appendLine("依据：${entry.optString("causalEvidenceId").take(80)}")
                    }
                }.trim()
                PresenceDialogSection("$at · $status", detail)
            }
        }
    }
}

@Composable
private fun PresenceDialogSection(title: String, content: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFF4F4F4),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFFE7E7E7)),
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = Color(0xFF7A7A7E), fontSize = 11.sp)
            Text(content, color = Color(0xFF1D1D1F), fontSize = 15.sp, lineHeight = 21.sp)
        }
    }
}

/**
 * History is the only surface for older thoughts, unsettled questions and
 * earlier psychological changes. These live records must never be merged
 * into the latest presence snapshot or into a specific message's heart voice.
 */
@Composable
private fun PresenceMentalHistory(characterId: String, cutoff: Instant?) {
    val revision by CharacterInnerLifeStore.revisions.collectAsState()
    val mind = remember(characterId, revision) { CharacterInnerLifeStore.snapshot(characterId) }
    val thoughts = mind.optJSONArray("thoughts")
    val oldThoughts = (0 until (thoughts?.length() ?: 0))
        .mapNotNull { thoughts?.optJSONObject(it) }
        .filter { entry ->
            val time = runCatching { Instant.parse(entry.optString("at")) }.getOrNull()
            time != null && (cutoff == null || !time.isAfter(cutoff))
        }.takeLast(10).asReversed()
    val emotions = mind.optJSONArray("emotionHistory")
    val priorEmotions = (0 until (emotions?.length() ?: 0))
        .mapNotNull { emotions?.optJSONObject(it) }
        .filter { entry ->
            val time = runCatching { Instant.parse(entry.optString("startedAt")) }.getOrNull()
            time != null && (cutoff == null || !time.isAfter(cutoff))
        }.takeLast(3).asReversed()
    val concerns = mind.optJSONArray("openConcerns")
    val olderConcerns = (0 until (concerns?.length() ?: 0))
        .mapNotNull { concerns?.optJSONObject(it) }
        .filter { entry ->
            val time = runCatching { Instant.parse(entry.optString("createdAt")) }.getOrNull()
            time != null && (cutoff == null || !time.isAfter(cutoff))
        }.takeLast(4).asReversed()
    val closed = mind.optJSONArray("closedConcerns")
    val ended = (0 until (closed?.length() ?: 0))
        .mapNotNull { closed?.optJSONObject(it) }
        .filter { entry ->
            val time = runCatching { Instant.parse(entry.optString("closedAt")) }.getOrNull()
            time != null && (cutoff == null || !time.isAfter(cutoff))
        }.takeLast(5).asReversed()

    if (oldThoughts.isNotEmpty() || priorEmotions.isNotEmpty() ||
        olderConcerns.isNotEmpty() || ended.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("过去的心路", fontWeight = FontWeight.SemiBold, color = Color(0xFF262629))
            Text("这里保留过去的想法与变化；它们不代表角色此刻仍在这样想。",
                fontSize = 11.sp, color = Color(0xFF7A7A7E))
            oldThoughts.forEach { entry ->
                val time = runCatching {
                    Instant.parse(entry.optString("at")).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                }.getOrDefault("")
                val details = listOf(
                    entry.optString("thought").takeIf(String::isNotBlank),
                    entry.optString("hesitation").takeIf(String::isNotBlank)?.let { "当时顾虑：$it" },
                ).filterNotNull().joinToString("\n")
                if (details.isNotBlank()) PresenceDialogSection("曾经的念头 · $time", details)
            }
            priorEmotions.forEach { entry ->
                val detail = listOf(entry.optString("feeling"), entry.optString("cause"))
                    .filter(String::isNotBlank).joinToString(" · ")
                if (detail.isNotBlank()) PresenceDialogSection("曾经的感受", detail)
            }
            olderConcerns.forEach { entry ->
                val journey = entry.optJSONArray("journey")
                val developments = (0 until (journey?.length() ?: 0))
                    .mapNotNull { journey?.optJSONObject(it) }.takeLast(3)
                    .joinToString("\n") {
                        listOf(it.optString("phase"), it.optString("focus"),
                            it.optString("hesitation")).filter(String::isNotBlank).joinToString(" · ")
                    }
                val detail = developments.ifBlank { entry.optString("focus") }
                if (detail.isNotBlank()) PresenceDialogSection("曾经挂心的事", detail)
            }
            ended.forEach { entry ->
                val result = entry.optString("resolution").ifBlank { "后来放下了" }
                PresenceDialogSection("一段心事的结束", entry.optString("focus") + "\n" + result)
            }
        }
    }
}
