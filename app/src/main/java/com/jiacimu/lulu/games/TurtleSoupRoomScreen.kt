package com.jiacimu.lulu.games

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val live = rememberGameLiveSession("turtle_soup", room.id, room.companions, allowSoloHost = true)
    // Historic archive lines never masquerade as new audible speech on resume.
    var lastNarratedLine by remember(room.id) { mutableIntStateOf(room.lines.lastIndex) }
    var input by remember(room.id) { mutableStateOf("") }
    var inputKind by remember(room.id) {
        mutableStateOf(if (room.guessers.isEmpty() && room.host != SoupHost.USER) SoupKind.QUESTION else SoupKind.DISCUSSION)
    }
    var keyboardRequest by remember(room.id) { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var confirmReveal by remember { mutableStateOf(false) }
    var switchCase by remember { mutableStateOf(false) }
    var autoThink by remember(room.id) { mutableStateOf(true) }
    var evidenceExpanded by remember(room.id) { mutableStateOf(false) }
    var showCase by remember(room.id) { mutableStateOf(false) }
    var speechFailed by remember(room.id) { mutableStateOf(false) }
    var showRoundOptions by remember(room.id) { mutableStateOf(false) }
    var showSetupOptions by remember(room.id) { mutableStateOf(false) }
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

    fun conclude(solved: Boolean, skipped: Boolean = false) {
        if (room.revealed || !room.started) return
        val finished = room.copy(revealed = true, solved = solved)
        if (!commit(finished)) return
        live.mic.mute()
        val recordId = store.recordExternalGame(
            type = LuluGameType.TurtleSoup,
            title = "海龟汤 · " + finished.puzzle.title,
            score = if (solved) 100 else 25,
            reward = 0,
            summary = "《" + finished.puzzle.title + "》由" + hostName() +
                "主持，共提问" + finished.questionCount + "次，尝试还原" +
                finished.solutionAttempts + "次，使用提示" +
                finished.lines.count { it.speaker == "系统提示" } + "条，参与同伴" + finished.companions.size +
                "位；" + (if (finished.questionBudget > 0) "12问挑战；" else "经典不限问；") +
                when {
                     solved -> "成功还原真相。"
                     skipped -> "本局结束，已换题。"
                     else -> "主动揭晓汤底。"
                 },
            detailsJson = finished.toJson(),
            characterIdOverride = finished.companions.firstOrNull() ?: state.selectedCharacterId,
            playedWithCharacterOverride = finished.companions.isNotEmpty(),
            recordIdOverride = finished.id,
        )
        saveGameAsSharedMemory(scope, store, recordId)
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
            instruction = SoupHostGuide.question(snapshot.host),
            source = "游戏",
            title = "海龟汤 · 公平裁定",
            maxTokens = 40,
            usage = ModelUsage.Game,
            contextMode = if (snapshot.host == SoupHost.AI) CompanionContextMode.Isolated else CompanionContextMode.PersonaAndScenario,
        )
        result.onSuccess { response ->
            val verdict = normalizeSoupVerdict(response.text)
            if (verdict == null) {
                error = "主持人没有给出合法裁定，本次问题保留，可重新判题。"
            } else if (room.id == snapshot.id && room.pendingQuestion == questionIndex) {
                commit(room.copy(lines = room.lines + SoupLine(hostName(), SoupKind.ANSWER, verdict, questionIndex)))
            }
        }.onFailure {
            error = "主持人暂时没有回应。问题仍保留，可重试。"
        }
    }

    suspend fun judgeSolution(solutionIndex: Int) {
        val snapshot = room
        val solution = snapshot.lines.getOrNull(solutionIndex) ?: return
        if (snapshot.host == SoupHost.USER || snapshot.pendingSolution != solutionIndex ||
            solution.kind != SoupKind.SOLUTION) return
        val result = LuluAiServices.gateway.generate(
            characterId = if (snapshot.host == SoupHost.AI) "lulu" else requireNotNull(snapshot.hostId),
            facts = "固定汤底（只给裁判，玩家不能看到）：" + snapshot.puzzle.truth +
                "\n事实边界：" + snapshot.puzzle.boundaries +
                "\n还原关键因果点（只给裁判，不能泄给玩家）：" +
                snapshot.puzzle.milestones.joinToString("；") +
                "\n" + snapshot.publicContext() +
                "\n正在核验的完整还原：" + solution.text,
            instruction = SoupHostGuide.solution(snapshot.host),
            source = "游戏", title = "海龟汤 · 还原核验",
            maxTokens = 140, usage = ModelUsage.Game,
            contextMode = if (snapshot.host == SoupHost.AI) CompanionContextMode.Isolated else CompanionContextMode.PersonaAndScenario,
        )
        result.onSuccess { response ->
            val judgment = parseSoupSolutionJudgment(response.text)
            if (judgment == null) {
                error = "裁判输出不合法，本次完整还原已保存，可以重新核验。"
            } else if (room.id == snapshot.id && room.pendingSolution == solutionIndex) {
                val verdict = if (judgment.correct) "还原成功 · " + judgment.feedback
                    else "还差一点 · 核心原因或关键过程尚未说明完整，可以继续提问。"
                if (commit(room.copy(lines = room.lines + SoupLine(
                        hostName(), SoupKind.SOLUTION_RESULT, verdict, solutionIndex,
                    ))) && judgment.correct) conclude(true)
            }
        }.onFailure { failure ->
            error = failure.message ?: "本轮核验失败，完整还原已保存，可重试。"
        }
    }

    fun runHostTurn(index: Int, kind: SoupKind) {
        busy = true
        scope.launch {
            try {
                if (kind == SoupKind.QUESTION) judge(index) else judgeSolution(index)
            } finally { busy = false }
        }
    }

    fun sendOwner(textInput: String = input, fromSpeech: Boolean = false) {
        val message = textInput.trim()
        if (message.isEmpty() || !room.started || room.revealed) return
        val kind = if (room.host == SoupHost.USER) SoupKind.DISCUSSION else inputKind
        if (!fromSpeech && (busy || room.pendingSpeech.isNotEmpty() ||
            room.pendingQuestion >= 0 || room.pendingSolution >= 0)) return
        if (!fromSpeech && kind == SoupKind.QUESTION && !room.canAsk) {
            error = "12问挑战已用尽提问次数，可讨论、看线索板或还原真相。"
            return
        }
        val next = if (fromSpeech) room.receiveSpeech(message)
        else room.copy(lines = room.lines + SoupLine("你", kind, message))
        if (!commit(next)) return
        // Only committed public game speech becomes a lived role observation.
        // The owner is heard by the actual participants, not by every character.
        GameTeamConversationTimeline.recordOwner(
            context, "turtle_soup", next.id, next.companions, message,
            fromSpeech = fromSpeech, utteranceId = "owner-${next.lines.lastIndex}",
        )
        if (fromSpeech) {
            remainingAutonomousSteps = 1
        } else {
            input = ""
            remainingAutonomousSteps = 3
            if (kind == SoupKind.QUESTION || kind == SoupKind.SOLUTION) runHostTurn(next.lines.lastIndex, kind)
        }
    }

    SideEffect { live.onOwnerMessage = { text, spoken -> sendOwner(text, spoken) } }

    fun rememberCompanionLine(actor: String, next: SoupRoom, index: Int) {
        GameTeamConversationTimeline.recordCompanion(
            context, "turtle_soup", next.id, next.companions, actor, next.lines[index].text,
            utteranceId = "companion-$index",
        )
    }

    fun handleSpeech() {
        val snapshot = room
        val index = snapshot.pendingSpeech.firstOrNull() ?: return
        if (busy || snapshot.revealed || snapshot.pendingQuestion >= 0 || snapshot.pendingSolution >= 0) return
        val actor = snapshot.guessers.getOrNull(companionCursor % snapshot.guessers.size.coerceAtLeast(1))
        val actorName = actor?.let(::name)
        busy = true
        scope.launch {
            try {
                val result = LuluAiServices.gateway.generate(
                    characterId = actor ?: "lulu",
                    facts = snapshot.publicContext(index) +
                        "\n主持人：" + hostName() +
                        "\n玩家刚刚说（对应日志第" + (index + 1) + "条）：" + snapshot.lines[index].text +
                        "\n允许玩家向主持提问：" + (snapshot.host != SoupHost.USER && snapshot.canAsk) +
                        "\n允许玩家提交还原：" + (snapshot.host != SoupHost.USER) +
                        if (actorName == null) "\n玩家当前没有猜题队友。" else "\n你的名字是" + actorName + "，你也是同桌的猜题者。",
                    instruction = "理解玩家刚刚说的话和接话对象。向队友问候、闲聊、商量、试探想法、请队友帮问主持，或没说完的话，都归 team。" +
                        "只把已经说完整、明确是在请主持人判断的可用是/否回答的问题归 host；独自猜题时清楚的游戏问句可直接归 host。" +
                        "只有玩家在明确提交完整还原时归 solution；猜一个片段或交换想法归 team。拿不准时归 team。不能提交的动作也归 team。" +
                        "选 team 且有猜题队友时，由你本人先用自然的短口语接这句话。问候就回应，不必硬拐回推理；讨论时根据已知线索说出自己的想法。" +
                        "你也可自选向主持人新提一个是/否问题(question)或完整还原(solve)，也可以只说话(none)。" +
                        "只用当前公开题面和线索推理，不得读取、引用或假装知道汤底；不能替玩家说话或假冒主持人裁定。" +
                        "只输出JSON对象：{\"to\":\"team|host|solution\",\"reply\":\"队友对玩家说的话\",\"action\":\"none|question|solve\",\"move\":\"队友向主持提出的内容\"}。" +
                        "如果不是 team 或没有队友，reply 留空，action 为 none。",
                    source = "游戏 · 海龟汤同桌连麦",
                    title = "海龟汤 · 同桌连麦",
                    maxTokens = 240,
                    usage = ModelUsage.Game,
                    contextMode = if (actor == null) CompanionContextMode.Isolated else CompanionContextMode.Full,
                )
                if (room.id != snapshot.id || room.pendingSpeech.firstOrNull() != index || room.revealed) return@launch
                val decision = result.getOrNull()?.let { parseSoupSpeechTurn(it.text, actorName) }
                if (decision == null) {
                    speechFailed = true
                    error = "这句字幕已保存，可以重试处理。"
                    return@launch
                }
                var next = room.resolveSpeech(index, decision.destination)
                val wasRoutedToHost = next.lines[index].kind != SoupKind.DISCUSSION
                val replyIndex = next.lines.size
                if (actorName != null && decision.reply.isNotBlank()) {
                    next = next.copy(lines = next.lines + SoupLine(actorName, SoupKind.DISCUSSION, decision.reply))
                }
                val move = decision.move?.takeIf { it.kind != SoupKind.QUESTION || next.canAsk }
                if (move != null) next = next.copy(lines = next.lines + move)
                if (!commit(next)) {
                    speechFailed = true
                    return@launch
                }
                lastAutonomousTrigger = next.lines.size
                if (actor != null) {
                    companionCursor++
                    for (reply in replyIndex until next.lines.size) {
                        rememberCompanionLine(actor, next, reply)
                    }
                }
                val judgeIndex = when {
                    wasRoutedToHost -> index
                    move != null && next.host != SoupHost.USER -> next.lines.lastIndex
                    else -> return@launch
                }
                if (next.lines[judgeIndex].kind == SoupKind.QUESTION) judge(judgeIndex) else judgeSolution(judgeIndex)
            } finally { busy = false }
        }
    }

    LaunchedEffect(room.id, room.lines.size) {
        if (room.lines.lastIndex <= lastNarratedLine) return@LaunchedEffect
        for (index in (lastNarratedLine + 1)..room.lines.lastIndex) {
            val line = room.lines[index]
            if ((line.speaker == "你" || line.speaker == "你 · 主持") || line.speaker == "系统提示") continue
            val actor = room.companions.firstOrNull { name(it) == line.speaker ||
                (room.hostId == it && line.speaker == hostName()) }
            when {
                actor != null -> live.companionSaid(actor, line.text, "companion-$index")
                room.host == SoupHost.AI && (line.kind == SoupKind.ANSWER ||
                    line.kind == SoupKind.SOLUTION_RESULT) -> live.fixedHostSaid(line.text)
            }
        }
        lastNarratedLine = room.lines.lastIndex
    }

    fun sendOwnerVerdict(verdict: String) {
        val pending = room.pendingQuestion
        if (busy || room.revealed || room.host != SoupHost.USER || pending < 0) return
        commit(room.copy(lines = room.lines + SoupLine("你 · 主持", SoupKind.ANSWER, verdict, pending)))
    }

    fun sendOwnerSolutionVerdict(correct: Boolean) {
        val pending = room.pendingSolution
        if (busy || !room.started || room.revealed || room.host != SoupHost.USER || pending < 0) return
        val reply = if (correct) "还原成功 · 主持人确认核心真相已还原"
            else "还差一点 · 主持人尚未确认"
        if (commit(room.copy(lines = room.lines + SoupLine(
                "你 · 主持", SoupKind.SOLUTION_RESULT, reply, pending,
            ))) && correct) conclude(true)
    }

    fun companionStep() {
        if (busy || !room.started || room.revealed || room.pendingQuestion >= 0 ||
            room.pendingSolution >= 0 || room.pendingSpeech.isNotEmpty() || room.guessers.isEmpty()) return
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
                        "。你只知道公开题面、公共问答及讨论，没有汤底。" +
                        (if (snapshot.canAsk) "" else "12问挑战已用尽提问次数，只能选discussion、solve或wait。"),
                    instruction = "你是正在真实参与海龟汤的角色本人，不是主持人，不可读取或假装知道汤底。" +
                        "你过去的亲身经历和人格只能影响思考风格、关系与交流；本局隐藏汤底只能用这局公开的题面和问答推理，不能借用旧题答案或其他角色的私密记忆。" +
                        "用人设和已公开证据形成假设，选择下一步：向主持人提一个可用是/否判断的新问题，" +
                        "或者跟同伴讨论、指出矛盾，或者尝试完整还原真相（solve），或者决定等待玩家。" +
                        "如果玩家刚刚向你说话，请先自然地回应那句话。主持人给出新裁定后，把线索和你们刚刚讨论的想法接起来。" +
                        "不要重复已否定的假设，也不要凭空宣称问题已解决。" +
                        "只输出JSON对象：{\"type\":\"question|discussion|solve|wait\",\"text\":\"自然口语内容\"}。" +
                        "如选择wait，text可为空。不得替其他玩家发言。",
                    // The companion is the SAME person in their original-world
                    // life; only the sealed current-case truth stays unavailable.
                    // Independent fixed hosts remain isolated objective judges.
                    source = "游戏 · 海龟汤同伴推理",
                    title = "海龟汤 · 同伴推理",
                    maxTokens = 220,
                    usage = ModelUsage.Game,
                    contextMode = CompanionContextMode.Full,
                )
                decision.onSuccess { response ->
                    val parsed = parseSoupCompanionDecision(response.text)
                    if (parsed == null) {
                        error = "同伴的本轮决定格式不正确，未写入对局。可以再试。"
                    } else if (room.id == snapshot.id) {
                        if (parsed.type == "question" && !room.canAsk) {
                            error = "同伴想继续提问，但12问挑战已满；本轮未写入对局，可重新决定。"
                            return@onSuccess
                        }
                        when (parsed.type) {
                            "wait" -> Unit
                            "discussion", "question", "solve" -> {
                                val kind = when (parsed.type) {
                                    "question" -> SoupKind.QUESTION
                                    "solve" -> SoupKind.SOLUTION
                                    else -> SoupKind.DISCUSSION
                                }
                                val next = room.copy(lines = room.lines + SoupLine(name(actor), kind, parsed.text))
                                if (commit(next)) {
                                    rememberCompanionLine(actor, next, next.lines.lastIndex)
                                    if (next.host != SoupHost.USER) {
                                        if (kind == SoupKind.QUESTION) judge(next.lines.lastIndex)
                                        if (kind == SoupKind.SOLUTION) judgeSolution(next.lines.lastIndex)
                                    }
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

    LaunchedEffect(room.id, room.pendingSpeech.firstOrNull(), busy, speechFailed, room.revealed) {
        if (!speechFailed) handleSpeech()
    }

    // Once a human utterance arrives, it takes priority over unattended guessing.
    LaunchedEffect(
        room.id, room.started, room.lines.size, busy, autoThink, remainingAutonomousSteps,
        live.companionSpeaking, live.mic.partial, live.mic.active,
    ) {
        if (busy || !room.started || room.revealed || room.guessers.isEmpty() ||
            room.pendingSpeech.isNotEmpty() || room.pendingQuestion >= 0 || room.pendingSolution >= 0 ||
            live.companionSpeaking || lastAutonomousTrigger == room.lines.size) return@LaunchedEffect
        val last = room.lines.lastOrNull()
        val directDiscussion = last?.kind == SoupKind.DISCUSSION && last.speaker == "你"
        val mayContinue = autoThink && remainingAutonomousSteps > 0 &&
            (last == null || last.kind == SoupKind.ANSWER || last.kind == SoupKind.SOLUTION_RESULT)
        if (!directDiscussion && !mayContinue) return@LaunchedEffect
        delay(if (directDiscussion) 0L else 1400L)
        if (live.mic.hasUncommittedSpeech) return@LaunchedEffect
        lastAutonomousTrigger = room.lines.size
        if (mayContinue) remainingAutonomousSteps--
        companionStep()
    }

    LaunchedEffect(room.id, room.lines.size, room.revealed) {
        if (room.started && (room.lines.isNotEmpty() || room.revealed)) {
            val last = room.lines.size - 1 + if (room.revealed) 1 else 0
            if (last >= 0) listState.scrollToItem(last)
        } else {
            listState.scrollToItem(0)
        }
    }

    fun requestHint() {
        if (busy || !room.started || room.revealed) return
        val used = room.lines.count { it.speaker == "系统提示" }
        val nextHint = room.puzzle.hints.getOrNull(used) ?: return
        commit(room.copy(lines = room.lines + SoupLine("系统提示", SoupKind.DISCUSSION, nextHint)))
    }

    fun finish(skipped: Boolean) {
        if (busy || room.pendingSpeech.isNotEmpty() || room.pendingQuestion >= 0 || room.pendingSolution >= 0) return
        if (live.mic.hasUncommittedSpeech) {
            error = "最后一句还在转写，稍等一下。"
            return
        }
        conclude(false, skipped)
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

    fun randomCase(): SoupCase {
        val played = state.records.filter { it.type == LuluGameType.TurtleSoup }
            .map { it.title.removePrefix("海龟汤 · ") }.toSet()
        val other = SoupLibrary.cases.filterNot { it.id == room.caseId }
        val unseen = other.filterNot { it.title in played }
        return (unseen.ifEmpty { other }).random()
    }

    val roundOpen = room.started && !room.revealed
    val roomSettled = !busy && room.pendingSpeech.isEmpty() &&
        room.pendingQuestion < 0 && room.pendingSolution < 0
    val usedHints = room.lines.count { it.speaker == "系统提示" }
    val players = if (room.host == SoupHost.USER) room.guessers.map(::name).joinToString("、")
        else (listOf("你") + room.guessers.map(::name)).joinToString("、")
    val retry: (() -> Unit)? = when {
        busy -> null
        room.host != SoupHost.USER && room.pendingQuestion >= 0 ->
            { { runHostTurn(room.pendingQuestion, SoupKind.QUESTION) } }
        room.host != SoupHost.USER && room.pendingSolution >= 0 ->
            { { runHostTurn(room.pendingSolution, SoupKind.SOLUTION) } }
        speechFailed -> { { speechFailed = false } }
        else -> null
    }

    if (confirmReveal) {
        AlertDialog(
            onDismissRequest = { confirmReveal = false },
            containerColor = SoupTableColors.panel,
            titleContentColor = SoupTableColors.text,
            textContentColor = SoupTableColors.text,
            title = { Text(if (switchCase) "换一碗汤？" else "揭晓汤底？") },
            text = { Text("本局的问答和讨论会存档。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReveal = false
                    val skipped = switchCase
                    finish(skipped)
                    if (skipped && room.revealed) startNew(SoupLibrary.beginnerCase.id)
                }, colors = soupLinkColors()) { Text(if (switchCase) "存档并换题" else "揭晓") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReveal = false }, colors = soupLinkColors()) { Text("继续推理") }
            },
        )
    }
    if (showCase) SoupTextDialog(room.puzzle.title, onDismiss = { showCase = false }) {
        Text(room.puzzle.category + " · " + room.puzzle.difficulty,
            color = SoupTableColors.gold, fontSize = 12.sp)
        Text(room.puzzle.surface, fontSize = 16.sp, lineHeight = 25.sp)
        Text("主持 · " + hostName(), color = SoupTableColors.muted, fontSize = 13.sp)
        if (players.isNotEmpty()) Text("猜题 · $players", color = SoupTableColors.muted, fontSize = 13.sp)
        if (room.host == SoupHost.USER) {
            HorizontalDivider(color = SoupTableColors.border)
            Text("汤底 · 仅你可见", color = SoupTableColors.gold, fontSize = 13.sp)
            Text(room.puzzle.truth, fontSize = 15.sp, lineHeight = 23.sp)
        }
    }
    if (evidenceExpanded) SoupTextDialog("线索板", onDismiss = { evidenceExpanded = false }) {
        if (room.evidence.isEmpty()) Text("向主持人提问后，裁定会记在这里。", color = SoupTableColors.muted)
        room.evidence.forEach { evidence ->
            Text(evidence.verdict + " · " + evidence.speaker, color = SoupTableColors.gold,
                fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(evidence.question, fontSize = 15.sp, lineHeight = 23.sp)
            HorizontalDivider(color = SoupTableColors.border)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(
        listOf(SoupTableColors.panel, SoupTableColors.background, SoupTableColors.background),
    )).imePadding()) {
        val compact = maxHeight < 540.dp
        Column(Modifier.fillMaxSize()) {
            // Keeping the note outside the LazyColumn anchors it while the transcript moves.
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                SoupCaseNote(room.puzzle, compact, onRead = { showCase = true })
                if (room.started) {
                    SoupRoundHeading(room, showRoundOptions, { showRoundOptions = it }, { evidenceExpanded = true }) {
                        DropdownMenuItem(
                            text = { Text("提示 $usedHints / ${room.puzzle.hints.size}", color = SoupTableColors.text) },
                            enabled = roundOpen && !busy && usedHints < room.puzzle.hints.size,
                            onClick = { showRoundOptions = false; requestHint() },
                        )
                        if (room.host == SoupHost.USER) {
                            DropdownMenuItem(
                                text = { Text("查看汤底", color = SoupTableColors.text) },
                                onClick = { showRoundOptions = false; showCase = true },
                            )
                        }
                        if (room.guessers.isNotEmpty() && roundOpen) {
                            DropdownMenuItem(
                                text = { Text("请队友想想", color = SoupTableColors.text) },
                                enabled = roomSettled, onClick = {
                                    showRoundOptions = false
                                    remainingAutonomousSteps = if (live.voiceMode) 1 else 3
                                    companionStep()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (autoThink) "暂停队友主动推理" else "开启队友主动推理",
                                    color = SoupTableColors.text) },
                                onClick = { autoThink = !autoThink; showRoundOptions = false },
                            )
                        }
                        if (roundOpen && room.host != SoupHost.USER) {
                            DropdownMenuItem(
                                text = { Text("提交还原", color = SoupTableColors.text) },
                                onClick = {
                                    inputKind = SoupKind.SOLUTION
                                    keyboardRequest++
                                    showRoundOptions = false
                                },
                            )
                        }
                        if (roundOpen) {
                            DropdownMenuItem(
                                text = { Text("换一碗", color = SoupTableColors.text) },
                                enabled = roomSettled,
                                onClick = { showRoundOptions = false; switchCase = true; confirmReveal = true },
                            )
                            DropdownMenuItem(
                                text = { Text("揭晓汤底", color = SoupTableColors.gold) },
                                enabled = roomSettled,
                                onClick = { showRoundOptions = false; switchCase = false; confirmReveal = true },
                            )
                        }
                    }
                    HorizontalDivider(color = SoupTableColors.border)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = if (compact) 2.dp else 7.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!room.started) {
                    item {
                        SoupSetup(
                            room = room, players = if (players.isEmpty()) "主持 · " + hostName()
                                else "主持 · " + hostName() + "\n猜题 · " + players,
                            showOptions = showSetupOptions, onShowOptions = { showSetupOptions = it },
                            onChange = { next ->
                                val changed = commit(next)
                                if (changed && next.started && next.guessers.isEmpty() && next.host != SoupHost.USER) {
                                    inputKind = SoupKind.QUESTION
                                }
                                changed
                            },
                            onRandom = { commit(room.copy(caseId = randomCase().id)) },
                            error = error,
                        )
                    }
                } else {
                    if (room.lines.isEmpty() && !room.revealed) {
                        item {
                            Text(
                                if (room.host == SoupHost.USER) "等队友开口，按汤底裁定。"
                                else if (room.guessers.isEmpty()) "先问一个能用「是」或「否」回答的问题。"
                                else "先说说你怀疑什么，也可以直接问主持人。",
                                Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                                color = SoupTableColors.muted, fontSize = 14.sp, lineHeight = 22.sp,
                            )
                        }
                    } else {
                        itemsIndexed(room.lines) { _, line ->
                            SoupTranscriptLine(line, room.lines.getOrNull(line.questionIndex))
                        }
                    }
                    if (room.revealed) {
                        item {
                            Surface(color = SoupTableColors.paper, contentColor = SoupTableColors.ink,
                                shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    Text(if (room.solved) "还原成功" else "汤底揭晓", fontSize = 22.sp,
                                        fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
                                    Text(room.puzzle.truth, fontSize = 15.sp, lineHeight = 24.sp)
                                    Text("${room.questionCount} 问 · $usedHints 条提示 · ${room.solutionAttempts} 次还原", fontSize = 12.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = { startNew(randomCase().id) },
                                            colors = ButtonDefaults.textButtonColors(contentColor = SoupTableColors.ink)) {
                                            Text("随机下一碗")
                                        }
                                        TextButton(onClick = { startNew(SoupLibrary.beginnerCase.id) },
                                            colors = ButtonDefaults.textButtonColors(contentColor = SoupTableColors.ink)) {
                                            Text("返回选题")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (roundOpen) {
                SoupComposer(
                    room = room, live = live,
                    kind = if (inputKind == SoupKind.QUESTION && !room.canAsk) SoupKind.DISCUSSION else inputKind,
                    onKind = { inputKind = it },
                    input = input, onInput = { input = it }, onSend = {
                        if (inputKind == SoupKind.QUESTION && !room.canAsk) inputKind = SoupKind.DISCUSSION
                        sendOwner()
                    }, onRetry = retry, onVerdict = ::sendOwnerVerdict, onSolutionVerdict = ::sendOwnerSolutionVerdict,
                    busy = busy, error = error, keyboardRequest = keyboardRequest,
                )
            }
        }
    }
}
