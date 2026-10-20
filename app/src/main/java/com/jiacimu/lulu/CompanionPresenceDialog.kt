package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
    val mindRevision by CharacterInnerLifeStore.revisions.collectAsState()
    val sourceRevision by SharedExperienceTimeline.revisions.collectAsState()
    val past = remember(characterId, displayHistory, mindRevision, sourceRevision, messageAnchor?.messageAt) {
        CharacterStateHistory.entries(characterId, displayHistory, messageAnchor?.messageAt ?: Instant.now())
    }
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
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .padding(start = 4.dp, end = 36.dp, top = 3.dp, bottom = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                if (messageAnchor == null) "这一刻" else "消息发出时",
                                fontSize = 11.sp, color = Color(0xFF989398),
                            )
                            Text(
                                characterName, fontSize = 25.sp, fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF222124),
                            )
                        }
                    }
                    item(key = "tabs") {
                        PresenceMomentTabs(
                            listOf("此刻", "心声历史"),
                            selectedTab,
                        ) { selectedTab = it }
                    }

                    if (selectedTab == 0) {
                        item(key = "current") {
                            if (displayState == null) {
                                Text(
                                    if (messageAnchor == null) "还没有留下心情或心声。"
                                    else "这条消息之前还没有状态记录。",
                                    color = Color(0xFF8C888C), fontSize = 14.sp,
                                    modifier = Modifier.padding(vertical = 18.dp),
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    PresenceStateContent(displayState, latestHeartMoment)
                                    if (messageAnchor == null) {
                                        TextButton(
                                            onClick = { deleteCurrentConfirm = true },
                                            modifier = Modifier.align(Alignment.End),
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                        ) {
                                            Text("删除记录", color = Color(0xFF9C9297), fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                        if (messageAnchor == null) item(key = "current-thoughts") {
                            PresenceLifeThreads(characterId, now, latestThinkingMoment)
                            CharacterLearningPlans(characterId)
                        }
                        if (messageAnchor == null) item(key = "character-perception") {
                            val perceptionError = PerceptionStatusPresentation.errorFor(state)
                            val photoStatus = QqChatImageModelInput.lastReadStatus(context, characterId)
                            val photoFailed = photoStatus.contains("读取失败")
                            val lastAt = state?.lastPerceptionAt
                                ?.atZone(ZoneId.systemDefault())
                                ?.format(DateTimeFormatter.ofPattern("MM-dd HH:mm")).orEmpty()
                            val latestNote = state?.lastPerceptionNote.orEmpty()
                            val lastFailure = CompanionPresenceStore.lastPerceptionFailure(characterId)
                            val failed = perceptionError != null
                            val shortStatus = PerceptionStatusPresentation.status(
                                latestNote, lastAt.isNotBlank(), lastFailure != null,
                            )
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                HorizontalDivider(color = Color(0xFFEEECEE), thickness = 1.dp)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("最近感知", fontSize = 12.sp, color = Color(0xFF8C888C))
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        listOf(lastAt, shortStatus).filter(String::isNotBlank).joinToString("  ·  "),
                                        fontSize = 11.sp,
                                        color = if (failed) Color(0xFFB15B5B) else Color(0xFF969196),
                                    )
                                    TextButton(
                                        onClick = { diagnosticsExpanded = !diagnosticsExpanded },
                                        contentPadding = PaddingValues(start = 9.dp, end = 2.dp),
                                    ) {
                                        Text(
                                            if (diagnosticsExpanded) "收起" else "详情",
                                            color = Color(0xFF757077), fontSize = 11.sp,
                                        )
                                    }
                                }
                                if (diagnosticsExpanded) {
                                    val issue = perceptionError ?: if (photoFailed) photoStatus else ""
                                    if (issue.isNotBlank()) {
                                        Text(if (photoFailed && perceptionError == null)
                                            "图片读取 · $issue" else issue,
                                            color = Color(0xFFB15B5B),
                                            fontSize = 12.sp, lineHeight = 18.sp)
                                    }
                                    lastFailure?.let { (failedAt, failedReason) ->
                                        val failureTime = failedAt.atZone(ZoneId.systemDefault())
                                            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
                                        Text("最近一次实际失败 · $failureTime",
                                            color = Color(0xFFB15B5B), fontSize = 11.sp)
                                        Text(failedReason, color = Color(0xFF8B5555),
                                            fontSize = 12.sp, lineHeight = 18.sp)
                                        if (latestNote.startsWith("感知启动")) {
                                            Text("当前处于再次尝试中；开始运行不等于回复已发送。",
                                                color = Color(0xFF858086), fontSize = 11.sp)
                                        }
                                    }
                                    PerceptionStatusPresentation.lines(
                                        state, policy, plans[characterId],
                                        onlineStates[characterId]?.isOnline(now) == true,
                                        CompanionPresenceStore.isInCall(characterId), now,
                                    ).forEach { line ->
                                        Text(line, color = Color(0xFF858086),
                                            fontSize = 11.sp, lineHeight = 17.sp)
                                    }
                                    state?.lastPerceptionNote?.takeIf(String::isNotBlank)?.let {
                                        Text("运行记录 · " + it.take(180),
                                            color = Color(0xFF858086), fontSize = 11.sp)
                                    }
                                    Text("图片读取 · " + photoStatus,
                                        color = Color(0xFF858086), fontSize = 11.sp)
                                    TextButton(
                                        onClick = {
                                            ProactivePerceptionScheduler.scheduleManual(context, characterId)
                                            manualCheckRequested = true
                                        },
                                        enabled = !manualCheckRequested,
                                        contentPadding = PaddingValues(horizontal = 2.dp),
                                    ) {
                                        Text(if (manualCheckRequested) "已请求唤醒" else "唤醒一下",
                                            fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    } else {
                        if (past.isEmpty()) item(key = "empty-history") {
                            Text("还没有留下可查看的历史状态。", color = Color(0xFF7A7A7E))
                        }
                        items(past, key = { it.key }) { entry -> CharacterMomentHistoryCard(characterId, entry) }
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
private fun PresenceMomentTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        labels.forEachIndexed { index, label ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(
                    onClick = { onSelect(index) },
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 7.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (index == selected) Color(0xFF242225)
                        else Color(0xFF969196),
                    ),
                ) {
                    Text(
                        label, fontSize = 14.sp,
                        fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
                Box(
                    Modifier.width(22.dp).height(2.dp)
                        .background(if (index == selected) Color(0xFF333033) else Color.Transparent)
                )
            }
        }
    }
}

@Composable
private fun PresenceMomentLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, modifier = Modifier.width(30.dp), fontSize = 11.sp,
            color = Color(0xFFA09AA0), lineHeight = 21.sp)
        Text(value, modifier = Modifier.weight(1f), fontSize = 14.sp,
            lineHeight = 22.sp, color = Color(0xFF5B575B))
    }
}

@Composable
private fun PresenceStateContent(
    state: CompanionPresenceState,
    heartMoment: CompanionPresenceState?,
) {
    // The same source of truth as the other projections: deduplicate mood,
    // ongoing state and gestures without changing the character's saved data.
    val fields = PresencePresentation.stateFields(state).toMap()
    val mood = fields["心情"].orEmpty()
    val status = fields["状态"].orEmpty()
    val gesture = fields["动作"].orEmpty()
    val voice = heartMoment?.innerThought.orEmpty()

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (mood.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("心情", fontSize = 11.sp, color = Color(0xFFA09AA0))
                Text(mood, fontSize = 19.sp, lineHeight = 27.sp,
                    fontWeight = FontWeight.Medium, color = Color(0xFF292529))
            }
        }
        if (status.isNotBlank() || gesture.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PresenceMomentLine("状态", status)
                PresenceMomentLine("动作", gesture)
            }
        }
        if (voice.isNotBlank()) {
            val time = heartMoment!!.updatedAt.atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            val label = if (heartMoment.updatedAt == state.updatedAt) "心声" else "心声 · " + time
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFF8F7F6),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 17.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Text(label, fontSize = 11.sp, color = Color(0xFF8D858B),
                        fontWeight = FontWeight.Medium)
                    Text(voice, fontSize = 15.sp, lineHeight = 25.sp,
                        color = Color(0xFF292629))
                }
            }
        }
        if (fields.isEmpty() && voice.isBlank()) {
            Text("这一刻还没有留下状态或心声。",
                color = Color(0xFF8C888C), fontSize = 14.sp)
        }
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
    val onDelete: (() -> Unit)? = null,
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
    thoughtMoment?.momentReflection?.takeIf { it.isNotBlank() &&
        !CharacterHeartVoicePolicy.sameMeaning(it, thoughtMoment.innerThought) }?.let { reflection ->
        val date = thoughtMoment.updatedAt.atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        ideas += LifeThoughtEntry("想法与顾虑", reflection.take(250), "记录于 $date",
            onDelete = { CompanionPresenceStore.deleteMoment(characterId, thoughtMoment.updatedAt); Unit })
    }
    CharacterLifeVisibility.current(characterId, now).forEach { entry ->
        if (entry.title == "还在挂心") {
            val concern = (0 until (mind.optJSONArray("openConcerns")?.length() ?: 0))
                .mapNotNull { mind.optJSONArray("openConcerns")?.optJSONObject(it) }
                .lastOrNull { it.optString("focus").isNotBlank() && entry.detail.contains(it.optString("focus")) }
            ideas += LifeThoughtEntry("持续牵挂", entry.detail.take(240), "仍在意",
                onDelete = concern?.optString("id")?.takeIf(String::isNotBlank)?.let { id ->
                    { CharacterInnerLifeStore.deletePrivateConcern(characterId, id); Unit }
                })
        }
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
                onDelete = m.optString("id").takeIf(String::isNotBlank)?.let { id ->
                    { CharacterInnerLifeStore.deletePrivateMotive(characterId, id); Unit }
                },
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
            ).joinToString("\n"),
            onDelete = { CharacterLifeStore.deleteCurrentIntention(characterId); Unit })
    }
    pending?.optString("action")?.takeIf(String::isNotBlank)?.let { name ->
        ideas += LifeThoughtEntry("一闪而过的小打算", name, "尚未执行",
            pending.optString("whyNow").takeIf(String::isNotBlank)
                ?.let { "当时为什么想到：$it" }.orEmpty())
    }
    performed?.takeIf { it.optString("status") in setOf("pending", "running") }?.let { item ->
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
                        item.onDelete?.let { delete ->
                            TextButton(onClick = delete) {
                                Text("删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
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
