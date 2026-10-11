package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
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
    // Timeline facts are shown separately from subjective feelings. Seeing that
    // a call ended is not the same thing as knowing *why* the user hung up.
    val recentInteraction = remember(characterId, state?.updatedAt, messageAnchor) {
        if (messageAnchor != null) null else CharacterPerceptionContext.recent(characterId)
            .lastOrNull { event ->
                event.source == InteractionSignalBridge.SOURCE &&
                    !event.occurredAt.isAfter(Instant.now()) &&
                    Duration.between(event.occurredAt, Instant.now()) <= Duration.ofHours(3)
            }
    }
    val displayHistory = remember(history, messageAnchor?.messageAt) {
        if (messageAnchor == null) history else history.filter { it.updatedAt <= messageAnchor.messageAt }
    }
    var historySelected by remember { mutableStateOf(false) }
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
    val past = remember(displayHistory, displayState) {
        displayHistory.filter { it.updatedAt != displayState?.updatedAt && it.showInHistory }
    }
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !historySelected, onClick = { historySelected = false }, label = { Text("当前") })
                            FilterChip(selected = historySelected, onClick = { historySelected = true }, label = { Text("历史") })
                        }
                    }

                    if (!historySelected) {
                        item(key = "current") {
                            if (displayState == null) {
                                Text(if (messageAnchor == null) "还没有留下此刻状态。" else "这条消息之前还没有形成可查看的此刻状态。", color = Color(0xFF7A7A7E))
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    PresenceStateContent(displayState)
                                    recentInteraction?.let { observed ->
                                        PresenceDialogSection("最近发生的互动", observed.content.take(240))
                                    }
                                }
                            }
                        }
                        if (messageAnchor == null) {
                            item(key = "inner-life") {
                                PresenceLivingMind(characterId)
                            }
                        }
                    } else if (past.isEmpty()) {
                        item(key = "empty-history") {
                            Text("这之前还没有更早的心声与动作。", color = Color(0xFF7A7A7E))
                        }
                    } else {
                        items(past, key = { it.updatedAt.toEpochMilli() }) { item ->
                            Surface(
                                color = Color(0xFFF7F7F7),
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(1.dp, Color(0xFFE7E7E7)),
                            ) {
                                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(item.updatedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")), color = Color(0xFF7A7A7E), fontSize = 11.sp)
                                    if (item.innerThought.isNotBlank()) Text("心声 · ${item.innerThought}", color = Color(0xFF1D1D1F), fontSize = 14.sp)
                                    if (item.gesture.isNotBlank()) Text("动作 · ${item.gesture}", color = Color(0xFF5F5F63), fontSize = 13.sp)
                                    val status = PresencePresentation.status(item)
                                    if (status.isNotBlank()) Text(status, color = Color(0xFF7A7A7E), fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    if (messageAnchor == null) {
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
private fun PresenceStateContent(state: CompanionPresenceState) {
    if (state.statusText.isNotBlank() || state.gesture.isNotBlank() || state.innerThought.isNotBlank() || state.mood.isNotBlank()) {
        PresenceDialogSection("此刻动作", state.gesture.ifBlank { "此刻没有留下明确的动作或神态。" })
        if (state.innerThought.isNotBlank()) PresenceDialogSection("心声", state.innerThought)
        val status = PresencePresentation.status(state)
        if (status.isNotBlank()) PresenceDialogSection("状态", status)
        Text(
            "状态记录 · " + state.updatedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")),
            color = Color(0xFF7A7A7E),
            fontSize = 11.sp,
        )
    }
    // Keep technical perception timestamps out of the character's inner voice.
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
 * "此刻" is the one place for changing, evidence-backed inner life.
 * It reads the existing canonical stores; no parallel personality state is
 * invented and nothing disappears from persistence when panels collapse.
 */
@Composable
private fun PresenceLivingMind(characterId: String) {
    val innerVersion by CharacterInnerLifeStore.revisions.collectAsState()
    val lifeStates by CharacterLifeStore.states.collectAsState()
    val growthVersion by CharacterDevelopmentStore.revisions.collectAsState()
    val tasks by CommitmentTaskStore.tasks.collectAsState()
    val mind = remember(characterId, innerVersion) { CharacterInnerLifeStore.snapshot(characterId) }
    val life = remember(characterId, lifeStates) { CharacterLifeStore.state(characterId) }
    val growth = remember(characterId, growthVersion) { CharacterDevelopmentStore.active(characterId) }
    val now = Instant.now()
    val emotion = mind.optJSONObject("emotion")
    val lastFeeling = emotion?.let {
        val whenStarted = runCatching { Instant.parse(it.optString("startedAt")) }.getOrNull()
        val within = whenStarted?.let { at ->
            !at.isAfter(now) && Duration.between(at, now).toMinutes() <=
                it.optInt("halfLifeMinutes", 180).coerceIn(30, 1440) * 3L
        } == true
        it.takeIf { within && it.optString("feeling").isNotBlank() }
    }
    val thoughts = mind.optJSONArray("thoughts")
    val recentThoughts = (0 until (thoughts?.length() ?: 0)).mapNotNull { thoughts?.optJSONObject(it) }
        .filter {
            val at = runCatching { Instant.parse(it.optString("at")) }.getOrNull()
            at != null && !at.isAfter(now) && Duration.between(at, now) <= Duration.ofHours(24)
        }.takeLast(3)
    val openConcerns = com.jiacimu.lulu.data.CharacterOpenConcernRuntime.recent(
        mind.optJSONArray("openConcerns"), now)
    val motives = mind.optJSONArray("motives")
    val livingGoals = (0 until (motives?.length() ?: 0)).mapNotNull(motives::optJSONObject)
        .filter { it.optString("status", "active") != "released" }
        .takeLast(5)
    val legacy = life.optJSONObject("intention")
    val legacyAim = legacy?.optString("aim").orEmpty()
    val distinctLegacy = legacyAim.isNotBlank() && livingGoals.none {
        sameCharacterMotive(it.optString("aim"), legacyAim)
    }
    val commitments = tasks.filter { it.characterId == characterId && it.status.isActive() }
    val choices = mind.optJSONArray("decisions")
    var showChoices by remember(characterId) { mutableStateOf(false) }
    var showGrowth by remember(characterId) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (lastFeeling != null || recentThoughts.isNotEmpty() || openConcerns.isNotEmpty()) {
            Text("心里的事", fontWeight = FontWeight.SemiBold, color = Color(0xFF262629))
            lastFeeling?.let {
                PresenceDialogSection("感受",
                    listOf(it.optString("feeling"), it.optString("cause"))
                        .filter(String::isNotBlank).joinToString(" · "))
            }
            if (recentThoughts.isNotEmpty()) PresenceDialogSection("还在琢磨",
                recentThoughts.joinToString("\n") { item ->
                    listOf(item.optString("thought"),
                        item.optString("hesitation").takeIf(String::isNotBlank)?.let { "顾虑：$it" }.orEmpty())
                        .filter(String::isNotBlank).joinToString(" · ")
                })
            openConcerns.forEach { item ->
                PresenceDialogSection("还没想通",
                    listOf(item.optString("focus"), item.optString("whyItMatters"),
                        item.optJSONObject("lastOutcome")?.optString("summary").orEmpty())
                        .filter(String::isNotBlank).joinToString(" · "))
            }
        }
        if (livingGoals.isNotEmpty() || distinctLegacy) {
            Text("正在做的打算", fontWeight = FontWeight.SemiBold, color = Color(0xFF262629))
            livingGoals.forEach { goal ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(goal.optString("aim"), fontSize = 14.sp, color = Color(0xFF333338))
                    TextButton(onClick = {
                        CharacterInnerLifeStore.stopMotive(characterId, goal.optString("id"))
                    }) { Text("放下") }
                }
            }
            if (distinctLegacy) {
                Text(legacyAim, fontSize = 14.sp, color = Color(0xFF333338))
                TextButton(onClick = { CharacterLifeStore.stopIntention(characterId) }) {
                    Text("放下")
                }
            }
        }
        if (commitments.isNotEmpty()) {
            Text("已经约好的事", fontWeight = FontWeight.SemiBold, color = Color(0xFF262629))
            commitments.take(6).forEach { task ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(task.goal, fontSize = 14.sp, color = Color(0xFF333338))
                    Text(when (task.status) {
                        CommitmentTaskStatus.Scheduled -> "已安排"
                        CommitmentTaskStatus.Running -> "正在执行"
                        CommitmentTaskStatus.WaitingForFeedback -> "等你回应"
                        CommitmentTaskStatus.Blocked -> "需要处理"
                        else -> "待确认"
                    }, color = Color(0xFF77777C), fontSize = 12.sp)
                    TextButton(onClick = { CommitmentTaskStore.cancel(task.id, "用户取消了这次约定") }) {
                        Text("取消约定")
                    }
                }
            }
        }
        if (growth.isNotEmpty()) {
            TextButton(onClick = { showGrowth = !showGrowth }) {
                Text(if (showGrowth) "收起逐渐形成的习惯" else "逐渐形成 · ${growth.size}")
            }
            if (showGrowth) growth.takeLast(6).forEach { item ->
                PresenceDialogSection(item.kind.label, item.content)
            }
        }
        if (choices != null && choices.length() > 0) {
            TextButton(onClick = { showChoices = !showChoices }) {
                Text(if (showChoices) "收起最近选择" else "最近选择")
            }
            if (showChoices) for (index in choices.length() - 1 downTo maxOf(0, choices.length() - 4)) {
                val item = choices.optJSONObject(index) ?: continue
                val summary = listOf(item.optString("reason"),
                    item.optString("outcome")).filter(String::isNotBlank).joinToString(" · ")
                if (summary.isNotBlank()) PresenceDialogSection(
                    item.optString("selected").ifBlank { "一次选择" }, summary)
            }
        }
    }
}
