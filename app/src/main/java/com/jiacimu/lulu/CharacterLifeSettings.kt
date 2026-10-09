package com.jiacimu.lulu

import com.jiacimu.lulu.design.LuluAlertDialog as AlertDialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.CharacterLifeStore
import com.jiacimu.lulu.data.CharacterInnerLifeStore
import com.jiacimu.lulu.data.sameCharacterMotive
import com.jiacimu.lulu.data.CharacterProfileSchema
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.CommitmentTaskStore
import com.jiacimu.lulu.data.CommitmentTaskStatus
import com.jiacimu.lulu.data.isActive
import com.jiacimu.lulu.system.LuluAlarmSystem
import androidx.compose.ui.platform.LocalContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts


@Composable
internal fun CharacterLifeSettings(characterId: String) {
    val states by CharacterLifeStore.states.collectAsState()
    val innerRevision by CharacterInnerLifeStore.revisions.collectAsState()
    val context = LocalContext.current
    val commitments by CommitmentTaskStore.tasks.collectAsState()
    val activeCommitments = commitments.filter { it.characterId == characterId && it.status.isActive() }
    var permissionsRefresh by remember { mutableIntStateOf(0) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionsRefresh += 1 }
    val exactClockAllowed = remember(permissionsRefresh) { LuluAlarmSystem.canScheduleExact() }
    val notificationsAllowed = remember(permissionsRefresh) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

    val innerRoot = remember(characterId, innerRevision) { CharacterInnerLifeStore.snapshot(characterId) }
    val onlineStates by com.jiacimu.lulu.data.CompanionOnlineStore.states.collectAsState()
    val online = onlineStates[characterId]
    val presenceStates by com.jiacimu.lulu.data.CompanionPresenceStore.states.collectAsState()
    val presence = presenceStates[characterId]
    val growthRevision by com.jiacimu.lulu.data.CharacterDevelopmentStore.revisions.collectAsState()
    val root = remember(states, characterId) { CharacterLifeStore.state(characterId) }
    var editing by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var showFixedDefinition by remember(characterId) { mutableStateOf(false) }
    var showPastChoices by remember(characterId) { mutableStateOf(false) }
    var showInnerDetails by remember(characterId) { mutableStateOf(false) }
    var showAllGrowth by remember(characterId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("基础人格 · 手动设定", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold)
        Text("长期的价值、判断和表达方式由你设定；当前心情、正在牵挂、后天习惯从真实经历发展，不会偷偷改写这些设定。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        CharacterProfileSchema.featuredFields.forEach { field ->
            val value = root.optJSONObject("profile")?.optString(field.key).orEmpty()
            Column(Modifier.fillMaxWidth().clickable { editing = field.key; draft = value }
                .padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(field.label, fontWeight = FontWeight.SemiBold)
                    Text("编辑", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall)
                }
                Text(value.ifBlank { "尚未设定，点击填写" }, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
        Row(Modifier.fillMaxWidth().clickable { showFixedDefinition = !showFixedDefinition }
            .padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("其他基础人格", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            Text(if (showFixedDefinition) "收起" else "展开编辑",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (showFixedDefinition) {
            CharacterProfileSchema.otherFields.groupBy { it.group }.forEach { (group, fields) ->
                Text(group, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                fields.forEach { field ->
                    val value = root.optJSONObject("profile")?.optString(field.key).orEmpty()
                    Column(Modifier.fillMaxWidth().clickable { editing = field.key; draft = value }
                        .padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(field.label, fontWeight = FontWeight.Medium)
                        Text(value.ifBlank { "未设定" }, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("此刻 · 自动变化", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                if (online?.isOnline() == true) "在线 · " +
                    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(online.onlineUntil)
                else "离线",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                presence?.let { moment ->
                    // One glance: thought is subjective; mood/status summarize
                    // an ongoing condition; only gesture shows visible motion.
                    if (moment.innerThought.isNotBlank()) Text(moment.innerThought,
                        style = MaterialTheme.typography.bodyMedium)
                    val status = PresencePresentation.status(moment)
                    if (status.isNotBlank()) Text(status, fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodySmall)
                    if (moment.gesture.isNotBlank() && status != moment.gesture.trim()) {
                        Text(moment.gesture, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (status.isBlank() && moment.innerThought.isBlank() && moment.gesture.isBlank())
                        Text("暂无近况", style = MaterialTheme.typography.bodySmall)
                } ?: Text("暂无近况", style = MaterialTheme.typography.bodySmall)
            }
        }
        val learned = remember(characterId, growthRevision, states) {
            com.jiacimu.lulu.data.CharacterDevelopmentStore.active(characterId)
        }
        if (learned.isNotEmpty()) {
            Text("后天成长 · ${learned.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            (if (showAllGrowth) learned.takeLast(10) else learned.takeLast(3)).forEach { learnedItem ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${learnedItem.kind.label} · ${learnedItem.content}",
                        style = MaterialTheme.typography.bodyMedium)
                    val sourceKinds = com.jiacimu.lulu.data.SharedExperienceTimeline
                        .eventsByIds(characterId, learnedItem.evidence.keys).map { event ->
                            when {
                                event.channel.startsWith("独自阅读") -> "阅读"
                                event.channel.startsWith("独自游戏") -> "游戏"
                                event.channel.startsWith("数字世界") -> "数字世界"
                                event.channel.startsWith("现实世界窗口") -> "现实文化"
                                event.channel.startsWith("群聊") -> "伙伴交流"
                                event.channel.contains("电话") -> "电话"
                                event.channel == "私聊" -> "私聊"
                                event.channel.contains("日记") -> "日记"
                                event.channel.contains("朋友圈") -> "朋友圈"
                                else -> "其他经历"
                            }
                        }.distinct().take(4)
                    Text("${learnedItem.evidence.size}条依据 · ${sourceKinds.joinToString("、")} · 版本${learnedItem.version}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (learned.size > 3) TextButton(onClick = { showAllGrowth = !showAllGrowth }) {
                Text(if (showAllGrowth) "收起变化" else "查看更多变化")
            }
        }
        HorizontalDivider()
        if (activeCommitments.isNotEmpty()) {
        Text("待履行的约定 · ${activeCommitments.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        activeCommitments.take(8).forEach { task ->
            val timeLabel = task.dueAt?.let {
                DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.systemDefault()).format(it)
            }.orEmpty()
            val stateText = when (task.status) {
                CommitmentTaskStatus.Scheduled -> if (task.linkedAlarmId != null) "已安排手机叫醒任务" else "尚未确认闹钟已安排"
                CommitmentTaskStatus.NeedsClarification -> "缺少明确时间，尚未安排"
                CommitmentTaskStatus.Running -> "正在执行"
                CommitmentTaskStatus.WaitingForFeedback -> "到点已尝试叫醒，等待你反馈"
                CommitmentTaskStatus.Blocked -> "执行受阻"
                else -> task.status.name
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(task.goal, fontWeight = FontWeight.Medium)
                Text("$stateText${if (timeLabel.isNotBlank()) " · $timeLabel" else ""}", style = MaterialTheme.typography.bodySmall)
                if (task.lastActionResult.isNotBlank()) {
                    Text("实际进展：${task.lastActionResult}", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { CommitmentTaskStore.cancel(task.id, "用户在角色设置里取消了这次约定") }) {
                    Text("取消此约定")
                }
            }
        }
        if (!exactClockAllowed) {
            Text("尚未授予精确闹钟权限。系统可能延迟叫醒；不要把它当作保证准点的闹钟。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching {
                        context.startActivity(android.content.Intent(
                            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:${context.packageName}"),
                        ))
                    }
                }
                permissionsRefresh += 1
            }) { Text("授予精确闹钟权限") }
        }
        if (!notificationsAllowed) {
            Text("系统通知权限未开启，到点可能不会响铃或弹出叫醒通知。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { Text("开启叫醒通知权限") }
        }
        }
        HorizontalDivider()
        Text("正在牵挂 · 随经历变化", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        val motives = innerRoot.optJSONArray("motives")
        val legacyMotive = root.optJSONObject("intention")
        val legacyAim = legacyMotive?.optString("aim").orEmpty().trim()
        val legacyUnique = legacyAim.isNotBlank() &&
            (0 until (motives?.length() ?: 0)).none { i ->
                sameCharacterMotive(motives?.optJSONObject(i)?.optString("aim").orEmpty(), legacyAim)
            }
        if ((motives == null || motives.length() == 0) && !legacyUnique) {
            Text("暂时没有明确的长期打算", style = MaterialTheme.typography.bodySmall)
        } else {
            for (i in 0 until (motives?.length() ?: 0)) {
                val goal = motives?.optJSONObject(i) ?: continue
                val id = goal.optString("id")
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(goal.optString("aim"), fontWeight = FontWeight.Medium)
                    Text(goal.optString("why"), style = MaterialTheme.typography.bodySmall)
                    Text(if (goal.optString("status") == "paused") "暂时搁置" else "仍在意 · 优先级${goal.optInt("priority", 2)}", style = MaterialTheme.typography.bodySmall)
                    goal.optString("changedBecause").takeIf(String::isNotBlank)?.let {
                        Text("变化原因：$it", style = MaterialTheme.typography.bodySmall)
                    }
                    val outcomes = goal.optJSONArray("outcomes")
                    outcomes?.optJSONObject(outcomes.length() - 1)?.let { outcome ->
                        Text("最近实际行动：${if (outcome.optBoolean("success")) "成功" else "未完成"} · ${outcome.optString("summary")}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { CharacterInnerLifeStore.stopMotive(characterId, id) }) {
                        Text("结束这件事")
                    }
                }
            }
        }
        if (legacyUnique && legacyMotive != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(legacyAim, fontWeight = FontWeight.Medium)
                Text(legacyMotive.optString("motive"), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { CharacterLifeStore.stopIntention(characterId) }) {
                    Text("结束这件事")
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().clickable { showInnerDetails = !showInnerDetails }.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("更多心绪与选择", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(if (showInnerDetails) "收起" else "展开", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showInnerDetails) {
        // Emotional state and raw thought ledger are details of the SAME
        // living personality, not a second competing "current status" panel.
        val subjectiveEmotion = innerRoot.optJSONObject("emotion")
        subjectiveEmotion?.takeIf { it.optString("feeling").isNotBlank() }?.let { feeling ->
            Text("当前感受", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(feeling.optString("feeling"), style = MaterialTheme.typography.bodyMedium)
            feeling.optString("cause").takeIf(String::isNotBlank)?.let { cause ->
                Text(cause, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val thoughtLedger = innerRoot.optJSONArray("thoughts")
        val latestThoughts = (0 until (thoughtLedger?.length() ?: 0))
            .mapNotNull { thoughtLedger?.optJSONObject(it) }
            .filter { item ->
                runCatching { java.time.Instant.parse(item.optString("at")) }.getOrNull()
                    ?.let { at -> !at.isAfter(java.time.Instant.now()) &&
                        java.time.Duration.between(at, java.time.Instant.now()) <= java.time.Duration.ofHours(24) } == true
            }.takeLast(4)
        if (latestThoughts.isNotEmpty()) {
            Text("心里同时浮现的念头", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            latestThoughts.forEach { item ->
                Column(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.optString("thought"), style = MaterialTheme.typography.bodyMedium)
                    item.optString("impulse").takeIf(String::isNotBlank)?.let {
                        Text("想做：$it", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item.optString("hesitation").takeIf(String::isNotBlank)?.let {
                        Text("顾虑：$it", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        val choices = innerRoot.optJSONArray("decisions")
        if (choices != null && choices.length() > 0) {
            TextButton(onClick = { showPastChoices = !showPastChoices }) {
                Text(if (showPastChoices) "收起最近的选择" else "查看最近的选择（${choices.length()}）")
            }
            if (showPastChoices) for (i in choices.length() - 1 downTo maxOf(0, choices.length() - 8)) {
                val decision = choices.optJSONObject(i) ?: continue
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("这次选择：${decision.optString("selected")}",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    decision.optString("reason").takeIf(String::isNotBlank)?.let {
                        Text("为什么：$it", style = MaterialTheme.typography.bodySmall)
                    }
                    val alternatives = decision.optJSONArray("alternatives")
                    if (alternatives != null) for (j in 0 until alternatives.length()) {
                        val alternate = alternatives.optJSONObject(j) ?: continue
                        Text("暂时没做：${alternate.optString("idea")} · ${alternate.optString("whyNot")}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("实际结果：${if (decision.optBoolean("succeeded")) "执行成功" else "未执行成功"} · ${decision.optString("outcome")}",
                        style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
        innerRoot.optJSONArray("emotionHistory")?.let { history ->
            if (history.length() > 0) {
                Text("最近的情绪变化", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                for (i in maxOf(0, history.length() - 3) until history.length()) {
                    val item = history.optJSONObject(i) ?: continue
                    Text("${item.optString("feeling")} · ${item.optString("cause")}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val innerVoices = innerRoot.optJSONArray("innerVoices")
        if (innerVoices != null && innerVoices.length() > 0) {
            Text("内心的声音 · 没说出口的想法", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            for (i in maxOf(0, innerVoices.length() - 3) until innerVoices.length()) {
                val thought = innerVoices.optJSONObject(i) ?: continue
                Text(thought.optString("thought"), style = MaterialTheme.typography.bodySmall)
            }
        }
        val bonds = innerRoot.optJSONObject("bonds")
        if (bonds != null && bonds.length() > 0) {
            Text("逐渐形成的主观看法", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            bonds.keys().asSequence().take(6).forEach { id ->
                val opinion = bonds.optJSONObject(id) ?: return@forEach
                val whom = if (id == "user") "对你" else "对其他角色"
                Text("$whom：${opinion.optString("interpretation")}", style = MaterialTheme.typography.bodySmall)
                opinion.optString("reason").takeIf(String::isNotBlank)?.let {
                    Text("缘由：$it", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val corrections = innerRoot.optJSONArray("corrections")
        corrections?.optJSONObject(corrections.length() - 1)?.let { insight ->
            Text("最近的一次自我修正", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(insight.optString("realization"), style = MaterialTheme.typography.bodyMedium)
            Text("准备换种做法：${insight.optString("nextTime")}", style = MaterialTheme.typography.bodySmall)
        }
        }
        HorizontalDivider()
        Text("角色使用的称呼", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        val socialNames = root.optJSONObject("socialNames")
        val userRemark = socialNames?.optString("userRemark").orEmpty()
        val selfNickname = socialNames?.optString("selfNickname").orEmpty()
        Text("给你的私人备注：" + userRemark.ifBlank { "还没有设置" }, style = MaterialTheme.typography.bodyMedium)
        if (userRemark.isNotBlank()) TextButton(onClick = { CharacterLifeStore.setSocialName(characterId, "userRemark", "") }) { Text("清除这条备注") }
        Text("自己的聊天网名：" + selfNickname.ifBlank { "沿用角色原名" }, style = MaterialTheme.typography.bodyMedium)
        if (selfNickname.isNotBlank()) TextButton(onClick = { CharacterLifeStore.setSocialName(characterId, "selfNickname", "") }) { Text("恢复原网名") }


    }
    editing?.let { key ->
        val field = CharacterProfileSchema.fields.first { it.key == key }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(field.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (key == "speechHabits") {
                        Text("描述这个角色自己的表达习惯，不用填固定台词。你主动编辑的内容不会被后天成长覆盖。",
                            style = MaterialTheme.typography.bodySmall)
                        if (MigratedDomainStores.characters.get(characterId).displayName in setOf("江渡", "江都")) {
                            TextButton(onClick = { draft = CharacterProfileSchema.jiangDuSpeechHabits }) {
                                Text("填入江渡推荐的语言习惯")
                            }
                        }
                    }
                    OutlinedTextField(draft, { draft = it }, placeholder = { Text(field.hint) },
                        minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton({ CharacterLifeStore.setProfile(characterId, key, draft); editing = null }) { Text("保存") } },
            dismissButton = { TextButton({ editing = null }) { Text("取消") } })
    }

}
