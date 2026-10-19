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
    LaunchedEffect(messageAnchor?.messageAt) { selectedTab = 0 }
    var manualCheckRequested by remember { mutableStateOf(false) }
    var diagnosticsExpanded by remember(characterId) { mutableStateOf(false) }
    var deleteCurrentConfirm by remember(characterId) { mutableStateOf(false) }
    var deletionTarget by remember(characterId) { mutableStateOf<Instant?>(null) }
    val plans by PerceptionWakePlanStore.plans.collectAsState()
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

    if (deleteCurrentConfirm || deletionTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteCurrentConfirm = false; deletionTarget = null },
            title = { Text("删除这条状态记录？") },
            text = { Text("将删除选中的此刻状态、相关心声快照及派生状态记录，不删除原始聊天消息。") },
            confirmButton = {
                TextButton(onClick = {
                    val at = deletionTarget ?: displayState?.updatedAt
                    if (at != null) CompanionPresenceStore.deleteMoment(characterId, at)
                    deleteCurrentConfirm = false
                    deletionTarget = null
                }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCurrentConfirm = false; deletionTarget = null }) {
                    Text("取消")
                }
            },
        )
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
            shadowElevation = 2.dp,
        ) {
            Box(Modifier.fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "header") {
                        Surface(color = Color(0xFFF8F6F7),
                            shape = RoundedCornerShape(19.dp),
                            border = BorderStroke(1.dp, Color(0xFFF0ECEE))) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("PERSONAL MOMENT  ·  这一刻", color = Color(0xFF93868D),
                                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                Text(characterName, color = Color(0xFF1D1D1F),
                                    fontWeight = FontWeight.Bold, fontSize = 23.sp)
                                Text(if (messageAnchor != null) "这条消息发出时的状态与心声"
                                     else "状态是此刻的，想法与行动在相邻页继续",
                                    color = Color(0xFF7A7A7E), fontSize = 11.sp)
                            }
                        }
                    }
                    item(key = "tabs") {
                        CharacterSectionTabs(if (messageAnchor == null) listOf("此刻", "想法与行动", "心声历史") else listOf("此刻", "心声历史"), selectedTab) { selectedTab = it }

                    }

                    if (selectedTab == 0) {
                        if (messageAnchor == null) item(key = "character-perception") {
                            val perceptionError = PerceptionStatusPresentation.errorFor(state)
                            val photoStatus = QqChatImageModelInput.lastReadStatus(context, characterId)
                            val photoFailed = photoStatus.contains("读取失败")
                            val lastAt = state?.lastPerceptionAt
                                ?.atZone(ZoneId.systemDefault())
                                ?.format(DateTimeFormatter.ofPattern("MM-dd HH:mm")).orEmpty()
                            val latestNote = state?.lastPerceptionNote.orEmpty()
                            val summary = when {
                                perceptionError != null -> "感知异常 · $lastAt · ${perceptionError.take(100)}"
                                photoFailed -> "图片读取失败 · ${photoStatus.takeLast(100)}"
                                lastAt.isBlank() -> "尚无感知运行记录"
                                latestNote.startsWith("感知成功") -> {
                                    val chosen = latestNote.substringAfter("感知成功 · ", "")
                                        .substringBefore(" · ").trim()
                                    "最近感知 · $lastAt · " +
                                        if (chosen.equals("silent", true)) "本人选择安静"
                                        else "成功${chosen.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}"
                                }
                                latestNote.startsWith("感知启动") ->
                                    "最近感知 · $lastAt · 已启动，尚无完成记录"
                                else -> "最近感知 · $lastAt · 已更新"
                            }
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(summary,
                                        modifier = Modifier.weight(1f),
                                        fontSize = 12.sp,
                                        color = if (perceptionError != null || photoFailed)
                                            Color(0xFFAF4545) else Color(0xFF7A7A7E),
                                        maxLines = 3,
                                    )
                                    TextButton(onClick = { diagnosticsExpanded = !diagnosticsExpanded },
                                        contentPadding = PaddingValues(horizontal = 6.dp)) {
                                        Text(if (diagnosticsExpanded) "收起" else "详情", fontSize = 12.sp)
                                    }
                                }
                                if (diagnosticsExpanded) {
                                    PerceptionStatusPresentation.lines(
                                        state, policy, plans[characterId],
                                        onlineStates[characterId]?.isOnline(now) == true,
                                        CompanionPresenceStore.isInCall(characterId), now,
                                    ).forEach { line ->
                                        Text(line, color = Color(0xFF66666A), fontSize = 12.sp)
                                    }
                                    state?.lastPerceptionNote?.takeIf(String::isNotBlank)?.let {
                                        Text("感知回执 · ${it.take(180)}",
                                            color = Color(0xFF66666A), fontSize = 12.sp)
                                    }
                                    Text("识图回执 · $photoStatus",
                                        color = if (photoFailed) Color(0xFFAF4545)
                                            else Color(0xFF66666A), fontSize = 12.sp)
                                    Text("这里仅显示${characterName}自己的记录，不包含其他角色。",
                                        color = Color(0xFF8A8A90), fontSize = 11.sp)
                                }
                            }
                        }
                        if (messageAnchor == null) item(key = "recent-interaction-awareness") {
                            // Show factual perception progress without fabricating a new
                            // feeling or heart voice when the character chose silence.
                            val latestCallEnd = SharedExperienceTimeline.recentEvents(characterId, 80)
                                .lastOrNull { event ->
                                    event.id.startsWith("interaction-call-end-") &&
                                        !event.occurredAt.isAfter(now) &&
                                        Duration.between(event.occurredAt, now) <= Duration.ofHours(2)
                                }
                            latestCallEnd?.let { event ->
                                val completed = CharacterCausalAppraisalStage.hasCompletedDecision(characterId, event.id)
                                val failed = state?.lastPerceptionAt?.let { !it.isBefore(event.occurredAt) } == true &&
                                    state?.lastPerceptionNote.orEmpty().let { note ->
                                        note.contains("失败") || note.contains("不完整") ||
                                            note.contains("暂停") || note.contains("未完成")
                                    }
                                val label = when {
                                    completed -> "这通电话结束后已完成一次感知与选择；没有新心声也属于有效结果"
                                    failed -> "电话结束后的感知尚未完成，可在下方查看最近感知状态"
                                    else -> "已记录电话结束，正在等待角色的感知结果"
                                }
                                Text(label, fontSize = 12.sp, color = Color(0xFF7A7A7E),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                            }
                        }
                        item(key = "current") {
                            if (displayState == null) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(if (messageAnchor == null) "还没有留下心情或心声。" else "这条消息之前还没有形成可查看的此刻状态。",
                                        color = Color(0xFF7A7A7E))

                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    PresenceStateContent(displayState, latestHeartMoment)
                                    if (messageAnchor == null) {
                                        TextButton(onClick = { deleteCurrentConfirm = true }) {
                                            Text("删除这一刻的状态与心声", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                    // A structured consideration is NOT a second heart voice.
                                    // Show it only when materially different, with its own label.
                                    val reflection = latestThinkingMoment?.momentReflection.orEmpty()
                                    if (reflection.isNotBlank() &&
                                        !CharacterHeartVoicePolicy.sameMeaning(
                                            reflection, latestHeartMoment?.innerThought.orEmpty(),
                                        )) {
                                        val whenThought = latestThinkingMoment?.updatedAt
                                            ?.atZone(ZoneId.systemDefault())
                                            ?.format(DateTimeFormatter.ofPattern("MM-dd HH:mm")).orEmpty()
                                        PresenceDialogSection("最近的想法 · $whenThought", reflection)
                                    }

                                }
                            }
                        }
                    } else if (selectedTab == 1 && messageAnchor == null) {
                        item(key = "life-threads") {
                            PresenceLifeThreads(characterId, now, latestThinkingMoment)
                        }
                    } else if (selectedTab == 2 || (selectedTab == 1 && messageAnchor != null)) {
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
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(item.updatedAt.atZone(ZoneId.systemDefault())
                                                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm")),
                                                color = Color(0xFF7A7A7E), fontSize = 11.sp,
                                                modifier = Modifier.weight(1f))
                                            TextButton(onClick = { deletionTarget = item.updatedAt }) {
                                                Text("删除", color = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                        if (item.innerThought.isNotBlank()) Text("心声 · ${item.innerThought}",
                                            color = Color(0xFF1D1D1F), fontSize = 14.sp)
                                        // This is a separate structured reflection, NOT a
                                        // second paragraph of the character's heart voice.
                                        if (item.momentReflection.isNotBlank() &&
                                            !CharacterHeartVoicePolicy.sameMeaning(item.momentReflection, item.innerThought))
                                            Text("当时的想法 · ${item.momentReflection}",
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
                    }

                    if (messageAnchor == null && selectedTab == 0) {
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
) {
    // Keep the active action/status separate from the most recent *authored*
    // inner voice. An old thought may be the latest one, but it must retain its
    // original time, never be falsely attributed to today's state update.
    // "PresencePresentation.status" already contains mood, which used to
    // duplicate the mood exactly as in the reported screenshot. Show stable
    // state, mood and gesture as DIFFERENT fields; do not concatenate them.
    // The same cleanup serves this live page and other presence projections.
    // Never present the same emotional words as three separate state fields.
    val visibleFields = PresencePresentation.stateFields(state)
    visibleFields.forEach { (label, value) ->
        Text("$label · $value", color = Color(0xFF7A7A7E),
            fontSize = 13.sp, lineHeight = 19.sp)
    }
    val voice = heartMoment?.innerThought.orEmpty()
    if (voice.isNotBlank()) {
        val time = heartMoment!!.updatedAt.atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        val label = if (heartMoment.updatedAt == state.updatedAt) "心声"
            else "最近一次心声 · $time"
        PresenceDialogSection(label, voice)
    }
    // A chosen life activity is a real queued executor request, never merely
    // the model's private prose. Only show its receipt on the live tab, not a
    // historical message's anchored state.
    if (visibleFields.isEmpty() && voice.isBlank()) {
        Text("这一刻还没有留下心声或动作。", color = Color(0xFF7A7A7E))
    }

}

/**
 * The trace is a projection of real timeline evidence, personal revisions and
 * the existing growth store. It never fabricates a diary or assumes that a
 * feedback message means the character has already learned.
 */
@Composable
private fun PresenceDialogSection(title: String, content: String, onDelete: (() -> Unit)? = null) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFF4F4F4),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFFE7E7E7)),
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color(0xFF7A7A7E), fontSize = 11.sp,
                    modifier = Modifier.weight(1f))
                if (onDelete != null) TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
            Text(content, color = Color(0xFF1D1D1F), fontSize = 15.sp, lineHeight = 21.sp)
        }
    }
}


/**
 * Read-only view of the actual durable stores. No extra AI call and no invented
 * "currently thinking" state. Plans, attempts and verified results are separate.
 */

private data class LifeThoughtEntry(
    val section: String, val title: String, val status: String,
    val detail: String = "", val factual: Boolean = false,
)

@Composable
private fun PresenceLifeThreads(characterId: String, now: Instant, thoughtMoment: CompanionPresenceState?) {
    val context = LocalContext.current
    val revisions by CharacterInnerLifeStore.revisions.collectAsState()
    val lives by CharacterLifeStore.states.collectAsState()
    // Recompose from actual saved stores; never run a paid model for this UI.
    @Suppress("UNUSED_VARIABLE")
    val refresh = Triple(now.epochSecond / 15L, revisions, lives[characterId])
    val goal = CharacterLifeStore.state(characterId).optJSONObject("intention")
    val mind = CharacterInnerLifeStore.snapshot(characterId)
    val motives = mind.optJSONArray("motives")
    val pending = CharacterSelfActivityRuntime.latestConsideration(context, characterId)
    val performed = CharacterSelfActivityRuntime.latest(context, characterId)
    val curiosity = CharacterCuriosityRuntime.snapshot(characterId)
    val questions = curiosity.optJSONArray("questions")
    val threads = curiosity.optJSONArray("threads")
    val evidence = CharacterLifeFollowThroughStore.auditSnapshot(characterId)
        .filter { it.optString("shareStatus") == "held" }.takeLast(2).asReversed()
    var expandedKey by remember(characterId) { mutableStateOf("") }
    var showOlder by remember(characterId) { mutableStateOf(false) }
    val ideas = mutableListOf<LifeThoughtEntry>()
    thoughtMoment?.momentReflection?.takeIf(String::isNotBlank)?.let { reflection ->
        val date = thoughtMoment.updatedAt.atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        ideas += LifeThoughtEntry("想法与顾虑", reflection.take(250), "记录于 $date")
    }
    CharacterLifeVisibility.current(characterId, now).forEach { entry ->
        if (entry.title == "还在挂心") ideas += LifeThoughtEntry("持续牵挂", entry.detail.take(240), "仍在意")
    }

    val canonicalGoals = (0 until (motives?.length() ?: 0))
        .mapNotNull { motives?.optJSONObject(it) }
    val currentGoals = canonicalGoals.filter {
        it.optString("aim").isNotBlank() &&
            (CharacterMotiveState.isActive(it) || it.optString("status") == "paused")
    }
    val pastGoals = canonicalGoals.filter {
        it.optString("aim").isNotBlank() &&
            it.optString("status") in setOf("achieved", "released")
    }.takeLast(2)
    // Live wishes first: finished history must not push current motivations
    // off the front page. Show old closures only when inspecting older items.
    (currentGoals.takeLast(6).asReversed() +
        if (showOlder) pastGoals.asReversed() else emptyList()).forEach { m ->
            val stage = when (m.optString("status", "active")) {
                "waiting_feedback" -> "已尝试 · 等待效果反馈"
                "paused" -> "暂缓"
                "released" -> "本人已放下"
                "achieved" -> "有证据确认达成"
                else -> "仍想推进"
            }
            val outcomes = m.optJSONArray("outcomes")
            val last = outcomes?.optJSONObject(outcomes.length() - 1)
            ideas += LifeThoughtEntry(
                "持续意愿", m.optString("aim"), stage,
                listOfNotNull(
                    m.optString("why").takeIf(String::isNotBlank)?.let { "心里的缘由：$it" },
                    m.optString("nextStep").takeIf(String::isNotBlank)?.let { "考虑下一步：$it" },
                    last?.optString("summary")?.takeIf(String::isNotBlank)
                        ?.let { "实际尝试：$it" },
                    m.optString("waitingFor").takeIf(String::isNotBlank)
                        ?.let { "仍待确认：$it" },
                    m.optString("closedBecause").takeIf(String::isNotBlank)
                        ?.let { "结束原因：$it" },
                ).joinToString("\n"),
            )
        }
    // Legacy intention is only a backward-compatible projection. If the same
    // desire already has a canonical motive, never show a duplicate card.
    goal?.takeIf { legacy ->
        legacy.optString("aim").isNotBlank() &&
            canonicalGoals.none { sameCharacterMotive(it.optString("aim"), legacy.optString("aim")) }
    }?.let { g ->
        val stage = when {
            g.optBoolean("userRequestedSpace") -> "尊重你的空间 · 暂停主动联系"
            g.optString("stage") in setOf("decided", "executing") -> "打算去做"
            g.optString("stage") == "following_up" -> "已采取行动 · 目标待确认"
            g.optString("stage") == "paused" -> "暂时搁置"
            g.optString("stage") == "blocked" -> "遇到阻碍"
            else -> "还在考虑"
        }
        val outcomes = g.optJSONArray("outcomes")
        val outcome = outcomes?.optJSONObject((outcomes.length() - 1).coerceAtLeast(0))
        ideas += LifeThoughtEntry("想做的事", g.optString("aim"), stage,
            listOfNotNull(
                g.optString("motive").takeIf(String::isNotBlank)?.let { "起因：$it" },
                g.optString("nextStep").takeIf(String::isNotBlank)?.let { "可能的下一步：$it" },
                outcome?.optString("summary")?.takeIf(String::isNotBlank)
                    ?.let { "已经发生：$it" },
                g.optString("nextReviewAt").takeIf(String::isNotBlank)?.let { iso ->
                    runCatching {
                        Instant.parse(iso).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                    }.getOrNull()?.let { "计划重新看看：$it（不一定发消息）" }
                },
                g.optInt("unansweredContactAttempts").takeIf { it > 0 }?.let {
                    "此前实际联系但未获新回复：$it 次；联系成功不等于目标达成"
                },
                g.optString("lastUserEvidenceAt").takeIf(String::isNotBlank)?.let {
                    "已记录你的新回复，旧定时复查已取消；等待依据内容调整目标"
                },
            ).joinToString("\n"))
    }
    pending?.optString("action")?.takeIf(String::isNotBlank)?.let { name ->
        ideas += LifeThoughtEntry("一闪而过的小打算", name, "尚未执行",
            pending.optString("whyNow").takeIf(String::isNotBlank)
                ?.let { "当时为什么想到：$it" }.orEmpty())
    }
    performed?.let { item ->
        val status = when (item.optString("status")) {
            "pending" -> "排队中"
            "running" -> "正在进行"
            "completed" -> "有完成回执"
            "failed" -> "未完成"
            else -> "未确认"
        }
        val title = item.optString("label").ifBlank { item.optString("tool") }
        if (title.isNotBlank()) ideas += LifeThoughtEntry("最近的行动", title,
            status, listOfNotNull(
                item.optString("whyNow").takeIf(String::isNotBlank)
                    ?.let { "原本想做：$it" },
                item.optString("result").takeIf(String::isNotBlank)
                    ?.let { "实际结果：$it" },
            ).joinToString("\n"), factual = status == "有完成回执")
    }
    (0 until (questions?.length() ?: 0)).mapNotNull { questions?.optJSONObject(it) }
        .filter { q -> SharedExperienceTimeline.eventsByIds(
            characterId, listOf(q.optString("observedId"))).isNotEmpty() }
        .takeLast(3).asReversed().forEach { q ->
            val question = q.optString("question").ifBlank { q.optString("topic") }
            if (question.isNotBlank()) ideas += LifeThoughtEntry(
                "还没弄清楚", question, "好奇中",
                listOfNotNull(q.optString("why").takeIf(String::isNotBlank)
                    ?.let { "为什么好奇：$it" },
                    q.optString("nextStep").takeIf(String::isNotBlank)
                        ?.let { "想尝试：$it" }).joinToString("\n"))
        }
    (0 until (threads?.length() ?: 0)).mapNotNull { threads?.optJSONObject(it) }
        .filter { it.optString("status") == "exploring" }.takeLast(2).asReversed()
        .forEach { t ->
            val steps = t.optJSONArray("steps")
            val last = steps?.optJSONObject((steps.length() - 1).coerceAtLeast(0))
            if (last != null && SharedExperienceTimeline.eventsByIds(
                characterId, listOf(last.optString("evidenceId"))).isNotEmpty()) {
                ideas += LifeThoughtEntry("正在探索", t.optString("topic"), "已有真实尝试",
                    last.optString("receipt").take(240), factual = true)
            }
        }
    evidence.forEach { record ->
        ideas += LifeThoughtEntry("还想分享", record.optString("fact").take(200),
            "放在心里，还没说",
            record.optString("shareWhy").takeIf(String::isNotBlank)
                ?.let { "为什么想说：$it" }.orEmpty())
    }
    val unique = ideas.filter { it.title.isNotBlank() }
        .distinctBy { item ->
            // Multiple parallel motives must remain separately visible.
            // The previous title-only key silently collapsed all of them.
            if (item.title == "持续意愿") item.title + ":" + item.detail.trim()
            else item.title.replace(Regex("\\s+"), "").take(80)
        }
    val visible = if (showOlder) unique else unique.take(4)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("心里的那些事", fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                color = Color(0xFF28282A))
            Text("想法不等于行动；只有真实执行后才会出现结果。",
                fontSize = 12.sp, color = Color(0xFF88868A))
        }
        if (visible.isEmpty()) {
            Text("现在没有特别想做或正在琢磨的事。",
                color = Color(0xFF858388), fontSize = 14.sp)
        }
        visible.forEachIndexed { index, item ->
            val key = "${item.section}-${item.title}-$index"
            val expanded = expandedKey == key
            Surface(
                modifier = Modifier.fillMaxWidth(),
                onClick = { expandedKey = if (expanded) "" else key },
                color = if (item.factual) Color(0xFFF3F7F4) else Color(0xFFF8F7F6),
                shape = RoundedCornerShape(19.dp),
            ) {
                Column(Modifier.padding(horizontal = 15.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(item.section, color = Color(0xFF68666A), fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text(item.status, color = if (item.factual) Color(0xFF3C7C65)
                            else Color(0xFF969197), fontSize = 10.5.sp)
                    }
                    Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        color = Color(0xFF242326), lineHeight = 23.sp,
                        maxLines = if (expanded) 12 else 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (item.detail.isNotBlank()) {
                        if (expanded) {
                            HorizontalDivider(color = Color(0xFFE5E2E2))
                            Text(item.detail, fontSize = 13.sp, lineHeight = 20.sp,
                                color = Color(0xFF67656B))
                        } else {
                            Text("轻点查看缘由与进展", color = Color(0xFFA29FA3),
                                fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        if (unique.size > 4) {
            TextButton(onClick = { showOlder = !showOlder },
                modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (showOlder) "收起其他想法" else "再看看其他${unique.size - 4}件事")
            }
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
        }.distinctBy { it.optString("id") }
            .sortedWith(compareByDescending<org.json.JSONObject> { it.optInt("priority", 2) }
                .thenByDescending { it.optString("updatedAt") })
            .take(8)
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
            oldThoughts.forEach { entry ->
                val time = runCatching {
                    Instant.parse(entry.optString("at")).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                }.getOrDefault("")
                val details = listOf(
                    entry.optString("thought").takeIf(String::isNotBlank),
                    entry.optString("hesitation").takeIf(String::isNotBlank)?.let { "当时顾虑：$it" },
                ).filterNotNull().joinToString("\n")
                if (details.isNotBlank()) PresenceDialogSection("曾经的念头 · $time", details,
                    onDelete = {
                        CharacterInnerLifeStore.forgetExactThought(characterId, entry.optString("thought"))
                    })
            }
            priorEmotions.forEach { entry ->
                val detail = listOf(entry.optString("feeling"), entry.optString("cause"))
                    .filter(String::isNotBlank).joinToString(" · ")
                if (detail.isNotBlank()) PresenceDialogSection("曾经的感受", detail,
                    onDelete = {
                        CharacterInnerLifeStore.forgetPrivateEmotion(characterId,
                            entry.optString("startedAt"), entry.optString("feeling"))
                    })
            }
            olderConcerns.forEach { entry ->
                val journey = entry.optJSONArray("journey")
                val developments = (0 until (journey?.length() ?: 0))
                    .mapNotNull { journey?.optJSONObject(it) }.takeLast(3)
                    .joinToString("\n") {
                        listOf(it.optString("phase"), it.optString("focus"),
                            it.optString("hesitation")).filter(String::isNotBlank).joinToString(" · ")
                    }
                val detail = listOf(
                    entry.optString("focus").takeIf(String::isNotBlank),
                    entry.optString("whyItMatters").takeIf(String::isNotBlank)?.let { "缘由：$it" },
                    entry.optString("hesitation").takeIf(String::isNotBlank)?.let { "仍在犹豫：$it" },
                    entry.optString("possibleNextStep").takeIf(String::isNotBlank)?.let { "可能下一步：$it" },
                    developments.takeIf(String::isNotBlank)?.let { "心路变化：$it" },
                ).filterNotNull().joinToString("\n")
                if (detail.isNotBlank()) PresenceDialogSection(
                    "仍放在心上的事 · 重要度${entry.optInt("priority", 2)}", detail,
                    onDelete = {
                        CharacterInnerLifeStore.deletePrivateConcern(characterId, entry.optString("id"))
                    },
                )
            }
            ended.forEach { entry ->
                val result = entry.optString("resolution").ifBlank { "后来放下了" }
                PresenceDialogSection("一段心事的结束", entry.optString("focus") + "\n" + result,
                    onDelete = {
                        CharacterInnerLifeStore.forgetClosedConcern(characterId, entry.optString("id"))
                    })
            }
        }
    }
}
