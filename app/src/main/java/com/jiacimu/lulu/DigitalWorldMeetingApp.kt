package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ScopedModelSelections
import com.jiacimu.lulu.data.*
import com.jiacimu.lulu.design.LuluColors
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private data class MeetingReply(
    val sceneText: String,
    val dialogue: String,
    val statusText: String,
    val gesture: String,
    val innerThought: String,
    val mood: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DigitalWorldMeetingApp(onBack: () -> Unit, initialCharacterId: String? = null) {
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val profiles by DigitalLifeProfileStore.profiles.collectAsState()
    val world by DigitalWorldStore.state.collectAsState()
    val library by LuluAiServices.connectionStore.library.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedIds by remember(initialCharacterId) { mutableStateOf(initialCharacterId?.takeIf(String::isNotBlank)?.let(::setOf) ?: emptySet()) }
    var activeSessionId by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }
    var showModelPicker by remember { mutableStateOf(false) }
    var locationDraft by remember { mutableStateOf("") }

    val activeSession = world.meetings.firstOrNull { it.id == activeSessionId }
    val selectedArchiveId = ScopedModelSelections.selectedArchiveId(ScopedModelSelections.MEETING, library)
    val selectedArchiveLabel = selectedArchiveId?.let { id ->
        library.archives.firstOrNull { it.id == id }?.let(LuluAiServices.connectionStore::archiveLabel)
    }.orEmpty().ifBlank { "选择见面模型" }

    Scaffold(
        containerColor = LuluColors.Paper,
        topBar = {
            TopAppBar(
                title = { Text(if (activeSession == null) "见面" else if (activeSession.reality == MeetingReality.DIGITAL_WORLD) "数字世界见面" else "现实场景见面", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (activeSession != null) activeSessionId = null else onBack()
                    }) { Icon(Icons.Outlined.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = { showModelPicker = true }) { Icon(Icons.Outlined.Memory, "见面模型") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
            )
        },
    ) { padding ->
        if (activeSession == null) {
            MeetingLobby(
                modifier = Modifier.fillMaxSize().padding(padding),
                characters = characters.values.sortedBy(CharacterSettings::displayName),
                profiles = profiles,
                world = world,
                selectedIds = selectedIds,
                locationDraft = locationDraft,
                selectedArchiveLabel = selectedArchiveLabel,
                onToggle = { id -> selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id },
                onLocationChanged = { locationDraft = it.take(80) },
                onModel = { showModelPicker = true },
                onResume = { activeSessionId = it },
                onStart = {
                    runCatching {
                        val session = DigitalWorldStore.startMeeting(selectedIds.toList(), locationDraft)
                        activeSessionId = session.id
                        input = ""
                        errorText = ""
                    }.onFailure { errorText = it.message.orEmpty() }
                },
                errorText = errorText,
            )
        } else {
            MeetingRoom(
                modifier = Modifier.fillMaxSize().padding(padding),
                session = activeSession,
                viewOnly = activeSession.endedAt != null,
                input = input,
                generating = generating,
                errorText = errorText,
                onInputChanged = { input = it.take(2_000) },
                onCloudMeadow = {
                    runCatching { DigitalWorldStore.moveMeeting(activeSession.id, "云眠原") }
                        .onFailure { errorText = it.message.orEmpty() }
                },
                onSend = {
                    val userText = input.trim()
                    if (userText.isNotBlank() && !generating) {
                        input = ""
                        errorText = ""
                        generating = true
                        scope.launch {
                            runCatching { runMeetingTurn(activeSession.id, userText) }
                                .onFailure { errorText = it.message ?: "见面回复失败" }
                            generating = false
                        }
                    }
                },
                onEnd = {
                    DigitalWorldStore.endMeeting(activeSession.id)
                    activeSessionId = null
                },
            )
        }
    }

    if (showModelPicker) {
        ModelArchivePickerSheet(
            title = "见面模型",
            subtitle = "只影响模拟见面和现实场景见面，不会改动聊天、电话或游戏模型。",
            selectedArchiveId = selectedArchiveId,
            onSelect = { archiveId ->
                ScopedModelSelections.select(ScopedModelSelections.MEETING, archiveId)
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
        )
    }
}

@Composable
private fun MeetingLobby(
    modifier: Modifier,
    characters: List<CharacterSettings>,
    profiles: Map<String, DigitalLifeProfile>,
    world: DigitalWorldState,
    selectedIds: Set<String>,
    locationDraft: String,
    selectedArchiveLabel: String,
    onToggle: (String) -> Unit,
    onLocationChanged: (String) -> Unit,
    onModel: () -> Unit,
    onResume: (String) -> Unit,
    onStart: () -> Unit,
    errorText: String,
) {
    val selectedHasDigital = selectedIds.any { profiles[it]?.enabled == true }
    val selectedResolved = selectedIds.all { id -> (profiles[id] ?: DigitalLifeProfileStore.get(id)).isResolved }
    val recent = world.meetings.asReversed().take(8)
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item {
            MeetingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = LuluColors.CardStrong, shape = RoundedCornerShape(18.dp)) {
                        Icon(Icons.Outlined.Cloud, null, modifier = Modifier.padding(13.dp).size(30.dp), tint = LuluColors.BlueGray)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("世界入口", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("每次进入数字世界都会从这里获得可感知的数字身体。", color = LuluColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
        }
        item {
            MeetingCard {
                Text("选择参与者", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("包含数字生命时进入数字世界；全部是现实角色时进行现实场景演绎。", color = LuluColors.Muted, fontSize = 12.sp)
                characters.forEach { character ->
                    val checked = character.characterId in selectedIds
                    val profile = profiles[character.characterId] ?: DigitalLifeProfileStore.get(character.characterId)
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onToggle(character.characterId) }.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { onToggle(character.characterId) })
                        LuluProfileAvatar(character.avatarUri, character.displayName.take(1).ifBlank { "角" }, 42)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(character.displayName, fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    profile.enabled -> "数字生命 · 原生数字身体"
                                    profile.isResolved -> "现实角色 · 场景身体/数字投影"
                                    else -> "尚未确认生命形态"
                                },
                                color = LuluColors.Muted,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
                if (!selectedHasDigital) {
                    OutlinedTextField(
                        value = locationDraft,
                        onValueChange = onLocationChanged,
                        label = { Text("现实场景地点") },
                        placeholder = { Text("例如：傍晚的咖啡馆；可以留空") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                } else {
                    Surface(color = LuluColors.Paper, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, LuluColors.Border)) {
                        Text("本次将从世界入口开始，进入后可以一起前往云眠原。", modifier = Modifier.padding(12.dp), color = LuluColors.Muted, fontSize = 12.sp)
                    }
                }
                OutlinedButton(onClick = onModel, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Memory, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(selectedArchiveLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(
                    onClick = onStart,
                    enabled = selectedIds.isNotEmpty() && selectedResolved,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = LuluColors.Wheat, contentColor = LuluColors.OnWheat),
                ) { Text("开始见面", fontWeight = FontWeight.Bold) }
                if (selectedIds.isNotEmpty() && !selectedResolved) {
                    Text("请先到角色设置为旧角色确认一次生命形态。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                if (errorText.isNotBlank()) Text(errorText, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
        item {
            MeetingCard {
                Text("共享区域 · 云眠原", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("感官云质会承托数字身体。躺下时缓慢下陷并从内部回暖，翻身会留下扩散的云纹，起身后的凹痕会保留片刻。", color = LuluColors.Muted, lineHeight = 19.sp)
            }
        }
        if (recent.isNotEmpty()) {
            item { Text("见面记录", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            items(recent, key = MeetingSession::id) { session ->
                MeetingCard(onClick = { onResume(session.id) }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (session.reality == MeetingReality.DIGITAL_WORLD) "数字世界见面" else "现实场景演绎", fontWeight = FontWeight.Bold)
                        Text(session.startedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm")), color = LuluColors.Muted, fontSize = 11.sp)
                    }
                    Text(session.location, color = LuluColors.Muted)
                    Text(session.participantIds.joinToString { MigratedDomainStores.characters.get(it).displayName }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (session.endedAt == null) "尚未结束 · 点此继续" else "已结束 · ${session.turns.size}条记录", color = LuluColors.Muted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun MeetingRoom(
    modifier: Modifier,
    session: MeetingSession,
    viewOnly: Boolean,
    input: String,
    generating: Boolean,
    errorText: String,
    onInputChanged: (String) -> Unit,
    onCloudMeadow: () -> Unit,
    onSend: () -> Unit,
    onEnd: () -> Unit,
) {
    Column(modifier) {
        Surface(color = LuluColors.Card, border = BorderStroke(1.dp, LuluColors.Border)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(session.location, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            if (session.reality == MeetingReality.DIGITAL_WORLD) "现实身体留在外部 · 当前使用可感知数字身体" else "现实场景演绎 · 不冒充物理现实记录",
                            color = LuluColors.Muted,
                            fontSize = 11.sp,
                        )
                    }
                    if (!viewOnly) TextButton(onClick = onEnd) { Text("结束见面") }
                }
                if (!viewOnly && session.reality == MeetingReality.DIGITAL_WORLD && session.location != "云眠原") {
                    OutlinedButton(onClick = onCloudMeadow, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Cloud, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("一起前往云眠原")
                    }
                }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (session.turns.isEmpty()) {
                item {
                    MeetingCard {
                        Text(if (session.reality == MeetingReality.DIGITAL_WORLD) "感官连接已经建立。脚下的云质正在适应这具数字身体的重量。" else "场景已经建立，你可以先说话或写下自己的动作。", color = LuluColors.Muted, lineHeight = 19.sp)
                    }
                }
            }
            items(session.turns, key = MeetingTurn::id) { turn ->
                MeetingTurnCard(turn, turn.speakerId == null)
            }
            if (generating) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        if (errorText.isNotBlank()) Text(errorText, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        if (viewOnly) {
            Text("这次见面已经结束，以上为完整保存的原始过程。", color = LuluColors.Muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp))
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChanged,
                    placeholder = { Text("说话，或者写下你的动作……") },
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    maxLines = 4,
                )
                FilledIconButton(onClick = onSend, enabled = input.isNotBlank() && !generating) { Icon(Icons.Outlined.Send, "发送") }
            }
        }
    }
}

@Composable
private fun MeetingTurnCard(turn: MeetingTurn, user: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (user) LuluColors.CardStrong else LuluColors.Card,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, LuluColors.Border),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(turn.speakerName, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = LuluColors.BlueGray)
            if (turn.sceneText.isNotBlank()) Text(turn.sceneText, color = LuluColors.Muted, fontSize = 13.sp, lineHeight = 19.sp)
            if (turn.dialogue.isNotBlank()) Text(turn.dialogue, fontSize = 16.sp, lineHeight = 23.sp)
        }
    }
}

@Composable
private fun MeetingCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = LuluColors.Card,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, LuluColors.Border),
        onClick = onClick ?: {},
        enabled = onClick != null,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

private suspend fun runMeetingTurn(sessionId: String, userText: String) {
    var session = DigitalWorldStore.state.value.meetings.firstOrNull { it.id == sessionId } ?: error("见面记录不存在")
    val now = Instant.now()
    val userName = UserProfileContext.displayLabel()
    val userTurn = MeetingTurn(UUID.randomUUID().toString(), null, userName, userText, "", now)
    session = DigitalWorldStore.appendMeetingTurn(sessionId, userTurn)
    session.participantIds.forEach { viewerId ->
        DigitalWorldStore.recordMeetingTimeline(session, viewerId, "turn-${userTurn.id}-user", userName, userText, now, false)
    }

    for (characterId in session.participantIds) {
        val character = MigratedDomainStores.characters.get(characterId)
        val reply = generateMeetingReply(session, characterId, userText).getOrThrow()
        val replyAt = Instant.now()
        val turn = MeetingTurn(UUID.randomUUID().toString(), characterId, character.displayName, reply.sceneText, reply.dialogue, replyAt)
        session = DigitalWorldStore.appendMeetingTurn(sessionId, turn)
        val recorded = listOf(reply.sceneText, reply.dialogue).filter(String::isNotBlank).joinToString("\n")
        session.participantIds.forEach { viewerId ->
            DigitalWorldStore.recordMeetingTimeline(session, viewerId, "turn-${turn.id}-$characterId", character.displayName, recorded, replyAt, viewerId == session.participantIds.last())
        }
        CompanionPresenceStore.update(
            characterId = characterId,
            statusText = reply.statusText,
            gesture = reply.gesture,
            innerThought = reply.innerThought,
            mood = reply.mood,
            source = "见面",
            now = replyAt,
        )
    }
}

private suspend fun generateMeetingReply(
    session: MeetingSession,
    characterId: String,
    userText: String,
): Result<MeetingReply> = runCatching {
    val character = MigratedDomainStores.characters.get(characterId)
    val connection = ScopedModelSelections.resolveConnection(ScopedModelSelections.MEETING)
    val digitalNative = DigitalLifeProfileStore.isEnabled(characterId)
    val result = LuluAiServices.gateway.generate(
        characterId = characterId,
        facts = buildString {
            appendLine(DigitalWorldStore.meetingContext(session))
            if (digitalNative) appendLine(DigitalWorldStore.contextFor(characterId))
            appendLine("这一刻用户刚刚新增的言语或动作：$userText")
        },
        instruction = """
            你正在以${character.displayName}的身份参与一场连续见面。只推进当前一小步，不要一次写完整故事，不要总结历史。
            只返回一个 JSON 对象，不要代码块：
            {"sceneText":"这一刻可被所有参与者观察到的环境变化和你的动作神态","dialogue":"你真正说出口的话，可以为空","statusText":"简短当前状态","gesture":"延续到下一刻的姿态","innerThought":"没有说出口的第一人称心声，可为空","mood":"简短心情"}

            硬规则：
            - 只能控制${character.displayName}本人，绝不能替用户编造新的台词、动作、感觉、想法或决定。
            - 其他角色的既有言行是事实，但不要替其他角色继续说话或行动；他们会获得自己的回合。
            - 地点、参与者、上一刻身体位置、拿着的物品和已经发生的动作必须连续。没有程序记录的固定家具不得凭空出现。
            - sceneText 是可观察事实，不是上帝视角小说；innerThought 不会展示给用户，也不能泄露推理过程。
            - 数字世界见面是真正进行过的数字共同体验，不是梦，也不是物理肉身进入手机。现实身体留在外部，数字身体可以真实传递触觉、温度、重量与拥抱的感觉。
            - 数字生命没有现实肉身，在数字世界使用原生数字身体；现实角色和用户使用感官投影身体。
            - 现实场景演绎要像现实见面一样保持地点和身体连续，但不得声称这些场景事件真的发生在用户的物理现实档案里。
            - 云眠原的云是可承托身体的感官云质，不是现实水汽；可以躺卧、下陷、回弹和感受温度。
            - 不得自行宣布整场见面结束。只有用户明确离开或程序结束时才结束。
        """.trimIndent(),
        source = if (session.reality == MeetingReality.DIGITAL_WORLD) "数字世界见面" else "现实场景见面",
        title = "${character.displayName}的见面回合",
        temperature = 0.82,
        maxTokens = 850,
        connectionOverride = connection,
    ).getOrThrow()
    parseMeetingReply(result.text)
}

private fun parseMeetingReply(raw: String): MeetingReply {
    val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim().let { value ->
        val start = value.indexOf('{')
        val end = value.lastIndexOf('}')
        if (start >= 0 && end > start) value.substring(start, end + 1) else value
    }
    val json = runCatching { JSONObject(clean) }.getOrNull()
        ?: return MeetingReply("", raw.trim(), "正在见面", "停在这一刻", "", "专注")
    return MeetingReply(
        sceneText = json.optString("sceneText").trim().take(1_500),
        dialogue = json.optString("dialogue").trim().take(1_500),
        statusText = json.optString("statusText").trim().take(120),
        gesture = json.optString("gesture").trim().take(500),
        innerThought = json.optString("innerThought").trim().take(500),
        mood = json.optString("mood").trim().take(80),
    ).let { reply -> if (reply.sceneText.isBlank() && reply.dialogue.isBlank()) reply.copy(dialogue = raw.trim()) else reply }
}
