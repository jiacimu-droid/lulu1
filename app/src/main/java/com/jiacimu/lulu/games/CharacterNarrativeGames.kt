package com.jiacimu.lulu.games

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.random.Random

private enum class PerfectManPhase { UserGuesses, CharacterGuesses }

@Composable
internal fun PerfectManScreen(store: LuluGameStore) {
    val state by store.state.collectAsState()
    val character = MigratedDomainStores.characters.get(state.selectedCharacterId)
    val scope = rememberCoroutineScope()
    val speaker = rememberGameSpeaker()
    var voiceEnabled by remember { mutableStateOf(true) }
    var round by remember { mutableIntStateOf(1) }
    var phase by remember { mutableStateOf(PerfectManPhase.UserGuesses) }
    var hiddenScore by remember { mutableIntStateOf(Random.nextInt(0, 11)) }
    var generatedDescription by remember { mutableStateOf("") }
    var userDescription by remember { mutableStateOf("") }
    var guessText by remember { mutableStateOf("") }
    var resultText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var roleResponse by remember { mutableStateOf(GameRoleResponse()) }

    speaker.enabled = voiceEnabled
    val voiceDescription = rememberGameSpeechInput { userDescription = it }
    val voiceGuess = rememberGameSpeechInput { transcript -> guessText = transcript.filter(Char::isDigit).take(2) }

    fun nextRound() {
        speaker.stop()
        round += 1
        phase = if (phase == PerfectManPhase.UserGuesses) PerfectManPhase.CharacterGuesses else PerfectManPhase.UserGuesses
        hiddenScore = Random.nextInt(0, 11)
        generatedDescription = ""
        userDescription = ""
        guessText = ""
        resultText = ""
        roleResponse = GameRoleResponse()
        busy = false
    }

    fun generateCharacterDescription() {
        if (busy) return
        busy = true
        roleResponse = GameRoleResponse(loading = true)
        scope.launch {
            LuluAiServices.gateway.generate(
                characterId = state.selectedCharacterId,
                facts = "满分男第 $round 轮。隐藏分数为 $hiddenScore/10；绝对不能向用户暴露分数。",
                instruction = "你负责出题。按照自己的人设描述这个人，让用户猜实际分数。只输出角色台词，2-4句，不得写出隐藏分数。",
                source = "游戏",
                title = "满分男出题",
                maxTokens = 420,
                usage = ModelUsage.Game,
            ).onSuccess { reply ->
                generatedDescription = reply.text
                roleResponse = GameRoleResponse(text = reply.text)
                speaker.speak(reply.text)
            }.onFailure { error ->
                roleResponse = GameRoleResponse(error = error.message ?: "角色出题失败，请检查模型设置后重试。")
            }
            busy = false
        }
    }

    fun submitUserGuess() {
        val guess = guessText.toIntOrNull()?.coerceIn(0, 10) ?: return
        if (generatedDescription.isBlank() || busy || resultText.isNotBlank()) return
        val diff = abs(guess - hiddenScore)
        val success = diff <= 1
        resultText = "真实分 $hiddenScore · 你的猜分 $guess · 差值 $diff"
        val summary = "第 $round 轮由角色出题；真实分 $hiddenScore，用户猜 $guess，差值 $diff，${if (success) "默契" else "未命中"}。"
        val recordId = store.recordExternalGame(
            LuluGameType.PerfectMan,
            "满分男",
            (100 - diff * 10).coerceAtLeast(10),
            if (success) 10 else 4,
            summary,
            JSONObject()
                .put("phase", "user_guesses")
                .put("round", round)
                .put("score", hiddenScore)
                .put("guess", guess)
                .put("description", generatedDescription)
                .toString(),
        )
        saveGameAsSharedMemory(scope, store, recordId)
        requestGameRoleResponse(
            scope = scope,
            store = store,
            recordId = recordId,
            facts = "$summary\n角色刚才的描述：$generatedDescription",
            instruction = "以角色自己的方式回应，并明确说出真实分和差值。不得默认夸奖、吐槽或亲密。只说1-3句角色台词。",
            title = "满分男结算",
            onState = {
                roleResponse = it
                if (it.text.isNotBlank()) speaker.speak(it.text)
            },
        )
    }

    fun submitDescriptionForCharacterGuess() {
        val description = userDescription.trim()
        if (description.isBlank() || busy || resultText.isNotBlank()) return
        busy = true
        roleResponse = GameRoleResponse(loading = true)
        scope.launch {
            LuluAiServices.gateway.generate(
                characterId = state.selectedCharacterId,
                facts = "满分男第 $round 轮。真实分数为 $hiddenScore/10。用户的描述：$description",
                instruction = "你负责猜分。按角色自己的判断回应，最后必须明确写‘我猜：X分’，X是0到10的整数；不要泄露后台提示。",
                source = "游戏",
                title = "满分男猜分",
                maxTokens = 420,
                usage = ModelUsage.Game,
            ).onSuccess { reply ->
                val guess = Regex("""(?:我猜[：:]?\s*)?(\d{1,2})\s*分""")
                    .findAll(reply.text)
                    .lastOrNull()
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.coerceIn(0, 10)
                if (guess == null) {
                    roleResponse = GameRoleResponse(error = "角色回复里没有可读取的0-10分，请重试这一轮。")
                } else {
                    val diff = abs(guess - hiddenScore)
                    val success = diff <= 1
                    resultText = "真实分 $hiddenScore · ${character.displayName}猜 $guess · 差值 $diff"
                    val summary = "第 $round 轮由用户描述；真实分 $hiddenScore，角色猜 $guess，差值 $diff，${if (success) "默契" else "未命中"}。"
                    val recordId = store.recordExternalGame(
                        LuluGameType.PerfectMan,
                        "满分男",
                        (100 - diff * 10).coerceAtLeast(10),
                        if (success) 10 else 4,
                        summary,
                        JSONObject()
                            .put("phase", "character_guesses")
                            .put("round", round)
                            .put("score", hiddenScore)
                            .put("guess", guess)
                            .put("description", description)
                            .toString(),
                    )
                    store.attachCharacterReply(recordId, reply.text)
                    saveGameAsSharedMemory(scope, store, recordId)
                    roleResponse = GameRoleResponse(text = reply.text)
                    speaker.speak(reply.text)
                }
            }.onFailure { error ->
                roleResponse = GameRoleResponse(error = error.message ?: "角色猜分失败，请检查模型设置后重试。")
            }
            busy = false
        }
    }

    GamePageList {
        item {
            GameCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("第 $round 轮", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(if (phase == PerfectManPhase.UserGuesses) "角色描述，你来猜分" else "你来描述，角色猜分", color = GameDesign.muted)
                    }
                    IconButton(onClick = ::nextRound) { Icon(Icons.Outlined.Refresh, "下一轮") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("角色语音", color = GameDesign.muted)
                    Switch(
                        checked = voiceEnabled,
                        onCheckedChange = {
                            voiceEnabled = it
                            speaker.enabled = it
                            if (!it) speaker.stop()
                        },
                    )
                }
            }
        }
        item { GameRolePanel(character.displayName, roleResponse) }
        item {
            GameCard {
                if (phase == PerfectManPhase.UserGuesses) {
                    if (generatedDescription.isBlank()) {
                        Text("让${character.displayName}按人设出题", fontWeight = FontWeight.Bold)
                        Button(onClick = ::generateCharacterDescription, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (busy) "正在出题…" else "开始这一轮")
                        }
                    } else {
                        Text(generatedDescription)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = guessText,
                                onValueChange = { guessText = it.filter(Char::isDigit).take(2) },
                                label = { Text("0-10分") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = voiceGuess) { Icon(Icons.Outlined.Mic, "语音猜分") }
                        }
                        Button(onClick = ::submitUserGuess, enabled = resultText.isBlank(), modifier = Modifier.fillMaxWidth()) { Text("提交猜分") }
                    }
                } else {
                    Text("本轮真实分：$hiddenScore / 10", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = userDescription,
                        onValueChange = { userDescription = it },
                        label = { Text("这是一个满分男，但是……") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = voiceDescription, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Mic, null)
                            Spacer(Modifier.width(6.dp))
                            Text("语音输入")
                        }
                        OutlinedButton(onClick = { userDescription = perfectManExamples.random() }, modifier = Modifier.weight(1f)) { Text("随机缺点") }
                    }
                    Button(onClick = ::submitDescriptionForCharacterGuess, enabled = !busy && resultText.isBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "正在猜分…" else "发送给角色")
                    }
                }
                if (resultText.isNotBlank()) {
                    GameResultBanner(resultText, success = resultText.substringAfterLast("差值 ").toIntOrNull()?.let { it <= 1 } == true)
                    Button(onClick = ::nextRound, modifier = Modifier.fillMaxWidth()) { Text("下一轮") }
                }
            }
        }
    }
}

private val perfectManExamples = listOf(
    "10天不洗脚，也不洗澡。",
    "每次约会都先讲半小时自己的梦。",
    "回复很快，但每句话都带工作总结格式。",
    "很会做饭，但所有菜都坚持放薄荷。",
    "记得所有纪念日，但礼物永远买同款保温杯。",
    "声音特别好听，但睡前故事只讲刑法案例。",
)

private data class RoleplayTurn(val action: String, val roll: Int, val result: String, val roleText: String)

@Composable
internal fun RoleplayAdventureScreen(store: LuluGameStore) {
    val state by store.state.collectAsState()
    val character = MigratedDomainStores.characters.get(state.selectedCharacterId)
    val scope = rememberCoroutineScope()
    var action by remember { mutableStateOf("") }
    var turns by remember { mutableStateOf(emptyList<RoleplayTurn>()) }
    var roleResponse by remember { mutableStateOf(GameRoleResponse()) }
    var busy by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    fun act() {
        val clean = action.trim()
        if (clean.isBlank() || busy || finished) return
        val roll = Random.nextInt(1, 21)
        val result = when {
            roll == 20 -> "大成功"
            roll >= 12 -> "成功"
            roll == 1 -> "大失败"
            else -> "失败并出现新麻烦"
        }
        val chapter = when (turns.size) {
            0 -> "倒走旅馆的前厅"
            1 -> "没有住客却亮着灯的307房"
            2 -> "地下钟室"
            else -> "倒走主钟之前"
        }
        val history = turns.takeLast(6).joinToString("\n") { "用户：${it.action}\nd20=${it.roll} ${it.result}\n角色：${it.roleText}" }
        busy = true
        roleResponse = GameRoleResponse(loading = true)
        scope.launch {
            LuluAiServices.gateway.generate(
                characterId = state.selectedCharacterId,
                facts = "轻量跑团《倒走的钟》。当前地点：$chapter。\n已有记录：\n$history\n用户本轮行动：$clean\n程序判定：d20=$roll，$result。",
                instruction = "你既是同伴也是剧情主持。必须接受程序的d20结果，不得改骰；根据结果推进场景并给用户新的可行动线索。保持角色人设，输出2-5句。",
                source = "游戏",
                title = "轻量跑团",
                maxTokens = 620,
                usage = ModelUsage.Game,
            ).onSuccess { reply ->
                val turn = RoleplayTurn(clean, roll, result, reply.text)
                val nextTurns = turns + turn
                turns = nextTurns
                val recordId = store.recordExternalGame(
                    LuluGameType.RoleplayAdventure,
                    "轻量跑团 · 第${nextTurns.size}幕",
                    roll * 5,
                    if (roll >= 12) 6 else 3,
                    "在${chapter}执行‘$clean’，d20=$roll，$result。",
                    JSONObject()
                        .put("scenario", "倒走的钟")
                        .put("chapter", chapter)
                        .put("action", clean)
                        .put("roll", roll)
                        .put("result", result)
                        .put("role_text", reply.text)
                        .toString(),
                )
                store.attachCharacterReply(recordId, reply.text)
                saveGameAsSharedMemory(scope, store, recordId)
                roleResponse = GameRoleResponse(text = reply.text)
                action = ""
            }.onFailure { error ->
                roleResponse = GameRoleResponse(error = error.message ?: "本轮角色回应生成失败；骰点尚未写入记录，请重试。")
            }
            busy = false
        }
    }

    fun finishAdventure() {
        if (turns.isEmpty() || finished) return
        finished = true
        val successes = turns.count { it.roll >= 12 }
        val summary = "完成《倒走的钟》共${turns.size}幕，成功${successes}次，最后行动是${turns.last().action}。"
        val recordId = store.recordExternalGame(
            LuluGameType.RoleplayAdventure,
            "轻量跑团 · 完结",
            (successes * 20 + turns.size * 5).coerceAtMost(100),
            18,
            summary,
            JSONObject().put("scenario", "倒走的钟").put("completed", true).put("turns", turns.size).toString(),
        )
        saveGameAsSharedMemory(scope, store, recordId)
        requestGameRoleResponse(
            scope = scope,
            store = store,
            recordId = recordId,
            facts = summary,
            instruction = "以角色自己的立场为这次共同冒险收尾，1-4句；不得虚构没有发生的行动。",
            title = "轻量跑团完结",
            onState = { roleResponse = it },
            maxTokens = 460,
        )
    }

    GamePageList {
        item {
            GameCard {
                Text("倒走的钟", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("自由行动 · d20判定 · ${character.displayName}作为同伴和主持人参与", color = GameDesign.muted)
                if (turns.isEmpty()) Text("场景：你和角色刚进入所有钟表都在倒走的旅馆。", color = GameDesign.muted)
            }
        }
        item { GameRolePanel(character.displayName, roleResponse) }
        items(turns) { turn ->
            GameCard {
                Text("你：${turn.action}", fontWeight = FontWeight.SemiBold)
                Text("d20=${turn.roll} · ${turn.result}", color = GameDesign.muted)
                Text("${character.displayName}：${turn.roleText}")
            }
        }
        item {
            GameCard {
                OutlinedTextField(
                    value = action,
                    onValueChange = { action = it },
                    label = { Text("描述你的自由行动") },
                    minLines = 3,
                    enabled = !finished,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = ::act, enabled = action.isNotBlank() && !busy && !finished, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "正在判定并生成…" else "掷d20并行动")
                }
                OutlinedButton(onClick = ::finishAdventure, enabled = turns.isNotEmpty() && !finished, modifier = Modifier.fillMaxWidth()) { Text("结束并保存本次冒险") }
                if (finished) {
                    Button(
                        onClick = {
                            turns = emptyList()
                            action = ""
                            finished = false
                            roleResponse = GameRoleResponse()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("重新开团") }
                }
            }
        }
    }
}

private data class TurtleCase(val title: String, val surface: String, val truth: String)

private val turtleCases = listOf(
    TurtleCase(
        "空房间的电话",
        "一个男人每天都给空房间打电话。某天电话突然被接通，他却哭了。为什么？",
        "那是去世恋人的旧号码。号码一直空置，所以他每天拨打怀念她；某天号码被重新分配并被陌生人接起，他意识到最后的联系也消失了。",
    ),
    TurtleCase(
        "没有雨的雨伞",
        "女人每天晴天都带一把湿雨伞上班，却从不在雨天带伞。为什么？",
        "她在游泳馆工作，晴天步行时把刚清洗的遮阳伞带去晾干；雨天由家人开车接送，雨伞留在车里。",
    ),
    TurtleCase(
        "最后一班电梯",
        "他每天坐电梯到十层，再走楼梯到十五层；只有一个人的时候才这样。为什么？",
        "他身高较矮，只能按到十层按钮；有人同行时会请对方按十五层。",
    ),
)

@Composable
internal fun TurtleSoupScreen(store: LuluGameStore) {
    val state by store.state.collectAsState()
    val character = MigratedDomainStores.characters.get(state.selectedCharacterId)
    val scope = rememberCoroutineScope()
    var caseIndex by remember { mutableIntStateOf(0) }
    val case = turtleCases[caseIndex]
    var question by remember(caseIndex) { mutableStateOf("") }
    var history by remember(caseIndex) { mutableStateOf(emptyList<Pair<String, String>>()) }
    var roleResponse by remember(caseIndex) { mutableStateOf(GameRoleResponse()) }
    var busy by remember { mutableStateOf(false) }
    var revealed by remember(caseIndex) { mutableStateOf(false) }

    fun ask() {
        val clean = question.trim()
        if (clean.isBlank() || busy || revealed) return
        busy = true
        roleResponse = GameRoleResponse(loading = true)
        scope.launch {
            val prior = history.joinToString("\n") { "问：${it.first}\n答：${it.second}" }
            LuluAiServices.gateway.generate(
                characterId = state.selectedCharacterId,
                facts = "海龟汤题面：${case.surface}\n锁定汤底：${case.truth}\n此前问答：\n$prior\n用户新问题：$clean",
                instruction = "你是主持人。严格依据锁定汤底判断。回复必须以‘是’、‘否’或‘无关’之一开头，可再补一句不泄底的短提示；不得修改汤底。",
                source = "游戏",
                title = "海龟汤主持",
                maxTokens = 220,
                usage = ModelUsage.Game,
            ).onSuccess { reply ->
                val normalized = reply.text.trim()
                history = history + (clean to normalized)
                roleResponse = GameRoleResponse(text = normalized)
                question = ""
            }.onFailure { error ->
                roleResponse = GameRoleResponse(error = error.message ?: "主持回应失败，请检查模型设置后重试。")
            }
            busy = false
        }
    }

    fun reveal() {
        if (revealed) return
        revealed = true
        val summary = "海龟汤《${case.title}》共提问${history.size}次后揭晓汤底。"
        val recordId = store.recordExternalGame(
            LuluGameType.TurtleSoup,
            "海龟汤 · ${case.title}",
            (100 - history.size * 4).coerceAtLeast(25),
            10,
            summary,
            JSONObject()
                .put("surface", case.surface)
                .put("truth", case.truth)
                .put("questions", JSONArray().apply { history.forEach { put(JSONObject().put("q", it.first).put("a", it.second)) } })
                .toString(),
        )
        saveGameAsSharedMemory(scope, store, recordId)
    }

    GamePageList {
        item { GameCard { Text(case.title, fontSize = 22.sp, fontWeight = FontWeight.Bold); Text(case.surface) } }
        item { GameRolePanel(character.displayName, roleResponse) }
        items(history) { item -> GameCard { Text("你：${item.first}"); Text("${character.displayName}：${item.second}", color = GameDesign.muted) } }
        item {
            GameCard {
                if (!revealed) {
                    OutlinedTextField(question, { question = it }, label = { Text("自由提问") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = ::ask, enabled = question.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "主持正在判断…" else "提问") }
                    TextButton(onClick = ::reveal, modifier = Modifier.fillMaxWidth()) { Text("揭晓汤底") }
                } else {
                    Text("汤底", fontWeight = FontWeight.Bold)
                    Text(case.truth)
                    Button(onClick = { caseIndex = (caseIndex + 1) % turtleCases.size }, modifier = Modifier.fillMaxWidth()) { Text("下一碗汤") }
                }
            }
        }
    }
}

private data class RapportQuestion(val text: String, val options: List<String>)

private val rapportQuestions = listOf(
    RapportQuestion("你压力很大时，更希望对方怎么做？", listOf("先安静陪着", "直接安慰", "帮忙拆计划", "暂时离开")),
    RapportQuestion("完成一次艰难学习后，你更想得到什么？", listOf("具体夸奖", "小礼物", "一起玩", "安静休息")),
    RapportQuestion("计划被打乱时，你更在意什么？", listOf("马上开始", "重新排完整", "有人监督", "先缓一缓")),
    RapportQuestion("你不回消息时，角色应该怎么处理？", listOf("等待", "提醒一次", "连续追问", "按承诺执行监督")),
)

@Composable
internal fun RapportQuizScreen(store: LuluGameStore) {
    val state by store.state.collectAsState()
    val character = MigratedDomainStores.characters.get(state.selectedCharacterId)
    val scope = rememberCoroutineScope()
    var index by remember { mutableIntStateOf(0) }
    var score by remember { mutableIntStateOf(0) }
    var secret by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var roleResponse by remember { mutableStateOf(GameRoleResponse()) }
    var busy by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }

    fun prepare() {
        if (busy || completed) return
        val question = rapportQuestions[index]
        busy = true
        roleResponse = GameRoleResponse(loading = true)
        scope.launch {
            LuluAiServices.gateway.generate(
                characterId = state.selectedCharacterId,
                facts = "默契问答题目：${question.text}\n可选项：${question.options.joinToString(" / ")}",
                instruction = "请结合角色真实人设、连续记忆和辞海中对用户的了解，秘密选择最符合用户的一项。只输出一个可选项原文，不要解释。",
                source = "游戏",
                title = "默契问答秘密作答",
                maxTokens = 80,
                usage = ModelUsage.Game,
            ).onSuccess { reply ->
                secret = question.options.firstOrNull { reply.text.contains(it) }
                roleResponse = if (secret == null) {
                    GameRoleResponse(error = "角色没有返回有效选项，请重新生成。")
                } else {
                    GameRoleResponse()
                }
            }.onFailure { error ->
                roleResponse = GameRoleResponse(error = error.message ?: "角色秘密作答失败，请检查模型设置。")
            }
            busy = false
        }
    }

    fun answer(option: String) {
        val roleAnswer = secret ?: return
        if (selected != null) return
        selected = option
        val matched = option == roleAnswer
        if (matched) score += 1
        roleResponse = GameRoleResponse(text = "${character.displayName}选择：$roleAnswer。${if (matched) "这一题一致。" else "这一题没有选中同一项。"}")
    }

    fun next() {
        if (selected == null) return
        if (index == rapportQuestions.lastIndex) {
            completed = true
            val summary = "默契问答完成${rapportQuestions.size}题，匹配${score}题。"
            val recordId = store.recordExternalGame(
                LuluGameType.RapportQuiz,
                "默契问答",
                score * 25,
                6 + score * 3,
                summary,
                JSONObject().put("matched", score).put("total", rapportQuestions.size).toString(),
            )
            saveGameAsSharedMemory(scope, store, recordId)
        } else {
            index += 1
            secret = null
            selected = null
            roleResponse = GameRoleResponse()
        }
    }

    GamePageList {
        item { GameCard { Text("默契问答", fontSize = 22.sp, fontWeight = FontWeight.Bold); Text("角色会读取人设、记忆和辞海后秘密作答。", color = GameDesign.muted); Text("当前得分：$score") } }
        item { GameRolePanel(character.displayName, roleResponse) }
        if (!completed) {
            item {
                GameCard {
                    Text("第${index + 1}/${rapportQuestions.size}题", color = GameDesign.muted)
                    Text(rapportQuestions[index].text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    if (secret == null) {
                        Button(onClick = ::prepare, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在秘密作答…" else "让角色先秘密作答") }
                    } else {
                        Text("秘密答案已锁定。", color = GameDesign.muted)
                    }
                }
            }
            if (secret != null) {
                items(rapportQuestions[index].options) { option ->
                    OutlinedButton(onClick = { answer(option) }, enabled = selected == null, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text(option) }
                }
                if (selected != null) {
                    item { Button(onClick = ::next, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text(if (index == rapportQuestions.lastIndex) "查看结果" else "下一题") } }
                }
            }
        } else {
            item {
                GameCard {
                    Text("默契得分：$score / ${rapportQuestions.size}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = {
                            index = 0
                            score = 0
                            secret = null
                            selected = null
                            completed = false
                            roleResponse = GameRoleResponse()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("重新作答") }
                }
            }
        }
    }
}

@Composable
internal fun RockPaperScissorsScreen(store: LuluGameStore) {
    val state by store.state.collectAsState()
    val character = MigratedDomainStores.characters.get(state.selectedCharacterId)
    val scope = rememberCoroutineScope()
    val choices = listOf("石头", "剪刀", "布")
    var result by remember { mutableStateOf("") }
    var roleResponse by remember { mutableStateOf(GameRoleResponse()) }

    fun play(user: String) {
        val role = choices.random()
        val outcome = compareRps(user, role)
        result = "你出$user · ${character.displayName}出$role · $outcome"
        val recordId = store.recordExternalGame(
            LuluGameType.RockPaperScissors,
            "一起猜拳",
            when (outcome) { "用户胜" -> 100; "平局" -> 60; else -> 30 },
            if (outcome == "用户胜") 6 else 2,
            result,
            JSONObject().put("user_move", user).put("role_move", role).put("outcome", outcome).toString(),
        )
        saveGameAsSharedMemory(scope, store, recordId)
        requestGameRoleResponse(
            scope = scope,
            store = store,
            recordId = recordId,
            facts = result,
            instruction = "只根据真实猜拳结果，以角色自己的语气回应1-3句，不得修改出拳和胜负。",
            title = "一起猜拳",
            onState = { roleResponse = it },
            maxTokens = 220,
        )
    }

    GamePageList {
        item { GameCard { Text("一起猜拳", fontSize = 22.sp, fontWeight = FontWeight.Bold); Text("真实结果先由游戏引擎生成，再交给角色按人设回应。", color = GameDesign.muted) } }
        item { GameRolePanel(character.displayName, roleResponse) }
        item {
            GameCard {
                choices.forEach { choice -> Button(onClick = { play(choice) }, modifier = Modifier.fillMaxWidth()) { Text(choice) } }
                if (result.isNotBlank()) GameResultBanner(result, success = result.endsWith("用户胜") || result.endsWith("平局"))
            }
        }
    }
}

private fun compareRps(user: String, role: String): String = when {
    user == role -> "平局"
    (user == "石头" && role == "剪刀") ||
        (user == "剪刀" && role == "布") ||
        (user == "布" && role == "石头") -> "用户胜"
    else -> "角色胜"
}
