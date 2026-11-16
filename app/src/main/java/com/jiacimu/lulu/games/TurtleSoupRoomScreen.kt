package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One room, one locked truth, one persistent transcript.
 * Unlike ordinary private chat, this room never relies on the last 35 chat messages.
 * Companion turns see every public question, ruling and discussion, but NOT the truth.
 */
@Composable
internal fun TurtleSoupRoomScreen(store: LuluGameStore) {
    val state by store.state.collectAsState()
    val context = LocalContext.current
    val archive = remember(context) { TurtleSoupRoomArchive(context) }
    val initial = remember { archive.read() ?: SoupRoom(
        companions = if (state.playWithCharacter) state.selectedCharacterIds else emptyList(),
    ) }
    var room by remember { mutableStateOf(initial) }
    var input by remember(room.id) { mutableStateOf("") }
    var composingQuestion by remember(room.id) { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var confirmReveal by remember { mutableStateOf(false) }
    var autoThink by remember(room.id) { mutableStateOf(true) }
    // A bounded burst prevents a phone left open from paying for an infinite AI-vs-AI loop.
    // The user can keep the companion thinking with one tap, without a fixed sensor timer.
    var remainingAutonomousSteps by remember(room.id) { mutableIntStateOf(3) }
    var lastAutonomousTrigger by remember(room.id) { mutableIntStateOf(-1) }
    var companionCursor by remember(room.id) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun name(id: String): String = MigratedDomainStores.characters.get(id).displayName
    fun hostName(): String = when (room.host) {
        SoupHost.AI -> "固定主持人"
        SoupHost.CHARACTER -> room.hostId?.let(::name)?.plus(" · 主持") ?: "主持人"
        SoupHost.USER -> "你 · 主持"
    }
    fun commit(next: SoupRoom): Boolean = try {
        archive.save(next)
        room = next
        error = ""
        true
    } catch (e: Exception) {
        error = e.message ?: "保存对局失败，消息未发送"
        false
    }

    suspend fun judge(questionIndex: Int) {
        val snapshot = room
        val line = snapshot.lines.getOrNull(questionIndex) ?: return
        if (line.kind != SoupKind.QUESTION || snapshot.host == SoupHost.USER) return
        val result = LuluAiServices.gateway.generate(
            characterId = if (snapshot.host == SoupHost.AI) "lulu" else requireNotNull(snapshot.hostId),
            facts = buildString {
                append("当前主持方式：").append(snapshot.host.name).append('\n')
                append("锁定汤底：").append(snapshot.puzzle.truth).append('\n')
                append("事实限定：").append(snapshot.puzzle.boundaries).append('\n')
                append(snapshot.publicContext())
                append("待回答问题编号：").append(questionIndex + 1).append('\n')
                append("待回答问题：").append(line.text)
            },
            instruction = "你现在只执行海龟汤的公平裁判职责。只按锁定汤底与事实限定回答当前问题。" +
                "只能输出【是】【否】【部分是】【无关】四个词之一，禁止解释、暗示、泄底、讲故事。" +
                "复杂问题中部分成立就回答部分是；与核心因果无关回答无关。" +
                "不得擅自改写既有裁定，不确定的问题保守判无关。" +
                if (snapshot.host == SoupHost.AI) "这是独立的固定主持人，不是同伴角色。" else "你是指定的角色主持人，不能参与猜题。",
            source = "游戏",
            title = "海龟汤 · 公平裁定",
            maxTokens = 40,
            usage = ModelUsage.Game,
            contextMode = if (snapshot.host == SoupHost.AI) CompanionContextMode.Isolated else CompanionContextMode.PersonaAndScenario,
            contextMode = if (snapshot.host == SoupHost.AI) CompanionContextMode.Isolated else CompanionContextMode.Full,
        )
        result.onSuccess { response ->
            val verdict = normalizeSoupVerdict(response.text)
            if (verdict == null) {
                error = "主持人没有给出合法裁定，本次问题保留，可重新判题。"
            } else if (room.id == snapshot.id && room.pendingQuestion == questionIndex) {
                commit(room.copy(lines = room.lines + SoupLine(hostName(), SoupKind.ANSWER, verdict, questionIndex)))
            }
        }.onFailure { failure ->
            error = failure.message ?: "主持人暂时没有回应。问题仍保留，可重试。"
        }
    }

    fun sendOwner() {
        val message = input.trim()
        if (message.isEmpty() || busy || !room.started || room.revealed) return
        val kind = if (room.host != SoupHost.USER && composingQuestion) SoupKind.QUESTION else SoupKind.DISCUSSION
        val next = room.copy(lines = room.lines + SoupLine("你", kind, message))
        if (!commit(next)) return
        input = ""
        remainingAutonomousSteps = 3
        if (kind == SoupKind.QUESTION) {
            val questionIndex = next.lines.lastIndex
            busy = true
            scope.launch {
                try { judge(questionIndex) } finally { busy = false }
            }
        }
    }

    fun sendOwnerVerdict(verdict: String) {
        val pending = room.pendingQuestion
        if (busy || room.revealed || room.host != SoupHost.USER || pending < 0) return
        commit(room.copy(lines = room.lines + SoupLine("你 · 主持", SoupKind.ANSWER, verdict, pending)))
    }

    fun companionStep() {
        if (busy || !room.started || room.revealed || room.pendingQuestion >= 0 || room.guessers.isEmpty()) return
        val snapshot = room
        val actor = snapshot.guessers[companionCursor % snapshot.guessers.size]
        companionCursor++
        busy = true
        scope.launch {
            try {
                val decision = LuluAiServices.gateway.generate(
                    characterId = actor,
                    facts = snapshot.publicContext() +
                        "\n你在本局中的身份：与其他猜题者同桌的玩家；主持人是" + hostName() +
                        "。你只知道公开题面、公共问答及讨论，没有汤底。",
                    instruction = "你是正在真实参与海龟汤的角色本人，不是主持人，不可读取或假装知道汤底。" +
                        "用人设和已公开证据形成假设，选择下一步：向主持人提一个可用是/否判断的新问题，" +
                        "或者跟同伴自然讨论、指出矛盾，或者决定先等待玩家。" +
                        "不要重复已否定的假设，也不要凭空宣称问题已解决。" +
                        "只输出JSON对象：{\"type\":\"question|discussion|wait\",\"text\":\"自然口语内容\"}。" +
                        "如选择wait，text可为空。不得替其他玩家发言。",
                    source = "游戏",
                    title = "海龟汤 · 同伴推理",
                    maxTokens = 220,
                    usage = ModelUsage.Game,
                    contextMode = CompanionContextMode.PersonaAndScenario,
                )
                decision.onSuccess { response ->
                    val parsed = parseSoupCompanionDecision(response.text)
                    if (parsed == null) {
                        error = "同伴的本轮决定格式不正确，未写入对局。可以再试。"
                    } else if (room.id == snapshot.id) {
                        when (parsed.type) {
                            "wait" -> Unit
                            "discussion", "question" -> {
                                val kind = if (parsed.type == "question") SoupKind.QUESTION else SoupKind.DISCUSSION
                                val next = room.copy(lines = room.lines + SoupLine(name(actor), kind, parsed.text))
                                if (commit(next) && kind == SoupKind.QUESTION && next.host != SoupHost.USER) {
                                    judge(next.lines.lastIndex)
                                }
                            }
                        }
                    }
                }.onFailure { failure ->
                    error = failure.message ?: "角色思考失败，可继续尝试。"
                }
            } finally { busy = false }
        }
    }

    // Reactive to real room events, not a background perception polling clock.
    LaunchedEffect(room.id, room.started, room.lines.size, busy, autoThink, remainingAutonomousSteps) {
        if (busy || !room.started || !autoThink || room.revealed || remainingAutonomousSteps <= 0 ||
            room.guessers.isEmpty() || room.pendingQuestion >= 0) return@LaunchedEffect
        val last = room.lines.lastOrNull()
        val shouldRespond = last == null || last.kind == SoupKind.ANSWER ||
            (last.kind == SoupKind.DISCUSSION && last.speaker == "你")
        if (!shouldRespond || lastAutonomousTrigger == room.lines.size) return@LaunchedEffect
        lastAutonomousTrigger = room.lines.size
        delay(1400) // Let the player interrupt or pause before spending the next model call.
        remainingAutonomousSteps--
        companionStep()
    }

    LaunchedEffect(room.lines.size, room.revealed) {
        if (room.lines.isNotEmpty()) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
    }

    fun requestHint() {
        if (busy || !room.started || room.revealed) return
        val used = room.lines.count { it.speaker == "系统提示" }
        val nextHint = room.puzzle.hints.getOrNull(used) ?: return
        commit(room.copy(lines = room.lines + SoupLine("系统提示", SoupKind.DISCUSSION, nextHint)))
    }

    fun finish() {
        if (room.revealed || !room.started || busy) return
        val finished = room.copy(revealed = true)
        if (!commit(finished)) return
        val recordId = store.recordExternalGame(
            type = LuluGameType.TurtleSoup,
            title = "海龟汤 · " + finished.puzzle.title,
            score = 100,
            reward = 0,
            summary = "《" + finished.puzzle.title + "》由" + hostName() +
                "主持，共提出" + finished.questionCount + "个问题，参与同伴" +
                finished.companions.size + "位；已揭晓原定汤底。",
            detailsJson = finished.toJson(),
            characterIdOverride = finished.companions.firstOrNull() ?: state.selectedCharacterId,
            playedWithCharacterOverride = finished.companions.isNotEmpty(),
            recordIdOverride = finished.id,
        )
        saveGameAsSharedMemory(scope, store, recordId)
    }

    fun startNew(puzzleId: String) {
        if (!room.revealed) return
        val next = SoupRoom(caseId = puzzleId,
            companions = if (state.playWithCharacter) state.selectedCharacterIds.distinct().take(3) else emptyList())
        if (commit(next)) {
            input = ""
            error = ""
            busy = false
        }
    }

    val background = Color(0xFF0B1421)
    val gold = Color(0xFFF5D6A6)
    if (confirmReveal) {
        AlertDialog(
            onDismissRequest = { confirmReveal = false },
            title = { Text("确定揭晓汤底？") },
            text = { Text("会保存完整的同桌问答和讨论记录。揭晓之后不能继续猜这一题。") },
            confirmButton = { TextButton(onClick = { confirmReveal = false; finish() }) { Text("揭晓并存档") } },
            dismissButton = { TextButton(onClick = { confirmReveal = false }) { Text("继续推理") } },
        )
    }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(
        listOf(Color(0xFF26384A), background, Color(0xFF080E18))))) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("TURTLE SOUP / 推理聊天室", color = gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(room.puzzle.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(room.puzzle.category + " · " + room.puzzle.difficulty + " · " +
                        room.questionCount + "次提问", color = Color(0xFFB9C7D9), fontSize = 12.sp)
                }
            }
            item {
                Surface(color = Color(0xFF213247), shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, gold.copy(alpha = .4f))) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("锁定汤面 · 整局不变", color = gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(room.puzzle.surface, color = Color.White, fontSize = 17.sp, lineHeight = 27.sp)
                        HorizontalDivider(color = Color.White.copy(alpha = .12f))
                        Text("主持：" + hostName() + "   /   猜题：" +
                            (listOf("你").filter { room.host != SoupHost.USER } +
                                room.guessers.map(::name)).joinToString("、"),
                            color = Color(0xFFC7D4E6), fontSize = 12.sp)
                    }
                }
            }
            if (!room.started) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("开局身份（尚未开问，可以切换）", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf(
                                SoupHost.AI to "AI主持",
                                SoupHost.CHARACTER to "角色主持",
                                SoupHost.USER to "我主持",
                            ).forEach { (choice, label) ->
                                FilterChip(selected = room.host == choice,
                                    onClick = {
                                        if (!busy && !room.started &&
                                            (choice != SoupHost.CHARACTER || room.companions.isNotEmpty())) {
                                            commit(room.copy(host = choice))
                                        }
                                    },
                                    enabled = choice != SoupHost.CHARACTER || room.companions.isNotEmpty(),
                                    label = { Text(label, fontSize = 11.sp) })
                            }
                        }
                        if (room.host == SoupHost.USER) {
                            Text("你已获得汤底查看权；同伴的提问由你按四种裁定回答。",
                                color = gold, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(9.dp))
                        Text("开局前选一碗汤", color = Color.White,
                            fontWeight = FontWeight.SemiBold)
                        Text("按自己的喜好选题；开始前不会调用队友模型。",
                            color = Color(0xFFB9C7D9), fontSize = 12.sp)
                        SoupLibrary.cases.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                pair.forEach { candidate ->
                                    FilterChip(
                                        modifier = Modifier.weight(1f),
                                        selected = room.caseId == candidate.id,
                                        onClick = { if (!busy) commit(room.copy(caseId = candidate.id)) },
                                        label = {
                                            Text(candidate.title + " · " + candidate.difficulty,
                                                maxLines = 1, fontSize = 11.sp)
                                        },
                                    )
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        Button(
                            onClick = { commit(room.copy(started = true)) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = gold, contentColor = background),
                        ) { Text("开始推理") }
                    }
                }
            }
            if (room.host == SoupHost.USER) {
                item {
                    Surface(color = Color(0xFF342D3D), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("仅主持可见 · 汤底", color = gold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(room.puzzle.truth, color = Color.White, fontSize = 13.sp, lineHeight = 20.sp)
                        }
                    }
                }
            }
            itemsIndexed(room.lines) { index, line ->
                val isOwner = line.speaker.startsWith("你")
                Surface(color = when {
                    line.kind == SoupKind.ANSWER -> Color(0xFF344233)
                    isOwner -> Color(0xFF354B65)
                    else -> Color(0xFF253448)
                }, shape = RoundedCornerShape(15.dp),
                    modifier = Modifier.fillMaxWidth().padding(
                        start = if (isOwner) 35.dp else 0.dp,
                        end = if (isOwner) 0.dp else 35.dp)) {
                    Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(line.speaker + " · " + when (line.kind) {
                            SoupKind.ANSWER -> "裁定"
                            SoupKind.QUESTION -> "提问"
                            SoupKind.DISCUSSION -> "讨论"
                        } + "  #" + (index + 1),
                            color = gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(line.text, color = Color.White, fontSize = 14.sp, lineHeight = 21.sp)
                    }
                }
            }
            if (room.revealed) {
                item {
                    Surface(color = Color(0xFF2A3446), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("真相揭晓", color = gold, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(room.puzzle.truth, color = Color.White, fontSize = 14.sp, lineHeight = 23.sp)
                            Text("本局原始问答与讨论已随游戏记录保存。", color = Color(0xFFB9C7D9), fontSize = 11.sp)
                        }
                    }
                }
                items(SoupLibrary.cases.filter { it.id != room.caseId }) { other ->
                    TextButton(onClick = { startNew(other.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text("下一碗 · " + other.title + "（" + other.category + "）", color = gold)
                    }
                }
            }
            if (error.isNotBlank()) {
                item { Text(error, color = Color(0xFFFFB2B2), fontSize = 12.sp) }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        if (room.started && !room.revealed) {
            Surface(color = Color(0xFF162438), shadowElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (room.host == SoupHost.USER && room.pendingQuestion >= 0) {
                        Text("轮到主持人回答：" + room.lines[room.pendingQuestion].text,
                            color = gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf("是", "否", "部分是", "无关").forEach { verdict ->
                                TextButton(onClick = { sendOwnerVerdict(verdict) }, enabled = !busy) {
                                    Text(verdict, color = gold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    if (room.host != SoupHost.USER && room.pendingQuestion >= 0 && !busy) {
                        TextButton(onClick = {
                            busy = true
                            scope.launch {
                                try { judge(room.pendingQuestion) } finally { busy = false }
                            }
                        }) { Text("重新请求主持人裁定", color = gold) }
                    }
                    if (room.host != SoupHost.USER) {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            FilterChip(selected = composingQuestion, onClick = { composingQuestion = true },
                                label = { Text("向主持人提问") })
                            FilterChip(selected = !composingQuestion, onClick = { composingQuestion = false },
                                label = { Text("跟同伴讨论") })
                        }
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text(if (room.host == SoupHost.USER) "以主持人身份讨论" else
                            if (composingQuestion) "用是/否能回答的问题" else "说出你的想法") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = ::sendOwner, enabled = input.isNotBlank() && !busy &&
                            room.pendingQuestion < 0, modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = gold, contentColor = background)) {
                            Text(if (busy) "思考中" else "发送")
                        }
                        if (room.guessers.isNotEmpty()) {
                            TextButton(onClick = { remainingAutonomousSteps = 3; companionStep() },
                                enabled = !busy && room.pendingQuestion < 0) {
                                Text("让同伴思考", color = gold, fontSize = 12.sp)
                            }
                        }
                    }
                    if (room.guessers.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("同伴主动推理 · 还可连续" + remainingAutonomousSteps + "步",
                                modifier = Modifier.weight(1f), color = Color(0xFFB9C7D9), fontSize = 11.sp)
                            Switch(checked = autoThink, onCheckedChange = { autoThink = it })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val usedHints = room.lines.count { it.speaker == "系统提示" }
                        TextButton(onClick = ::requestHint, enabled = !busy && usedHints < room.puzzle.hints.size) {
                            Text("提示 " + usedHints + "/" + room.puzzle.hints.size, color = gold, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (busy) "正在推理或裁定…" else "所有提问、裁定、讨论都会存进本局",
                            modifier = Modifier.weight(1f), color = Color(0xFF94AAC2), fontSize = 11.sp)
                        TextButton(onClick = { confirmReveal = true }, enabled = !busy) {
                            Text("揭晓汤底", color = gold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
