package com.jiacimu.lulu.games

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.LuluProfileAvatar
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.data.CharacterSettings
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private enum class StoryAssetKind { Clue, Map, Item }

private data class StoryAsset(
    val id: String,
    val kind: StoryAssetKind,
    val title: String,
    val detail: String,
    val visualPrompt: String = "",
)

private data class StoryLocation(
    val id: String,
    val name: String,
    val hint: String,
    val unlocked: Boolean = true,
)

private data class DirectorState(
    val phase: String,
    val currentLocation: String,
    val coreQuestion: String,
    val sceneGoal: String,
    val activeThreads: List<String>,
    val hiddenThreads: List<String>,
    val foreshadows: List<String>,
    val worldFacts: List<String>,
    val locations: List<StoryLocation>,
    val assets: List<StoryAsset>,
    val tension: Int = 3,
)

private data class DirectorBeat(
    val nextState: DirectorState,
    val beatType: String,
    val worldDelta: String,
    val narrativeDirective: String,
    val nextPressure: String,
)

private data class CinematicApocalypseSave(
    val id: String,
    val scene: Int,
    val partyIds: List<String>,
    val narration: String,
    val director: DirectorState,
    val log: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

private object ApocalypsePalette {
    val top = Color(0xFF101318)
    val bottom = Color(0xFF20242B)
    val panel = Color(0xFF171B21)
    val panelSoft = Color(0xFF242A32)
    val line = Color(0xFF343B45)
    val text = Color(0xFFF5F3EC)
    val muted = Color(0xFFADB3BC)
    val amber = Color(0xFFF2CF78)
    val red = Color(0xFFD9786B)
    val green = Color(0xFF86B89B)
    val blue = Color(0xFF84A9C8)
}

private class CinematicApocalypseStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cinematic_apocalypse_game", Context.MODE_PRIVATE)

    fun load(): CinematicApocalypseSave? = prefs.getString("save", null)?.let { raw ->
        runCatching { decodeSave(JSONObject(raw)) }.getOrNull()
    }

    fun save(value: CinematicApocalypseSave) {
        prefs.edit().putString("save", encodeSave(value).toString()).apply()
    }

    fun clear() {
        prefs.edit().remove("save").apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CinematicApocalypseGameScreen(
    gameStore: LuluGameStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val storyStore = remember(context) { CinematicApocalypseStore(context) }
    val gameState by gameStore.state.collectAsState()
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    var save by remember { mutableStateOf(storyStore.load()) }
    var action by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showMap by remember { mutableStateOf(false) }
    var showAssets by remember { mutableStateOf(false) }
    var showDirector by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun partyFor(value: CinematicApocalypseSave): List<CharacterSettings> = value.partyIds.map { id ->
        characters[id] ?: MigratedDomainStores.characters.get(id)
    }

    fun createNewSave(): CinematicApocalypseSave {
        val selected = gameState.selectedCharacterIds.take(4).ifEmpty {
            characters.keys.firstOrNull()?.let(::listOf).orEmpty()
        }
        return CinematicApocalypseSave(
            id = UUID.randomUUID().toString(),
            scene = 1,
            partyIds = selected,
            narration = initialApocalypseNarration(selected.map { id -> characters[id]?.displayName ?: MigratedDomainStores.characters.get(id).displayName }),
            director = initialDirectorState(),
        )
    }

    LaunchedEffect(Unit) {
        if (save == null) {
            val created = createNewSave()
            save = created
            storyStore.save(created)
        }
    }

    BackHandler(onBack = onBack)

    fun submitAction() {
        val current = save ?: return
        val cleanAction = action.trim()
        if (cleanAction.isBlank() || busy) return
        val party = partyFor(current)
        scope.launch {
            busy = true
            val beat = planDirectorBeat(current, party, cleanAction)
            val narration = writeCinematicScene(current, party, cleanAction, beat)
            narration.onSuccess { text ->
                if (text.isBlank()) return@onSuccess
                val updated = current.copy(
                    scene = current.scene + 1,
                    narration = text,
                    director = beat.nextState,
                    log = (current.log + "SCENE ${current.scene}｜$cleanAction\n${text.take(650)}").takeLast(60),
                    updatedAt = System.currentTimeMillis(),
                )
                save = updated
                storyStore.save(updated)
                val recordId = gameStore.recordExternalGame(
                    LuluGameType.RoleplayAdventure,
                    "末世剧情游戏 · 第${current.scene}幕",
                    (60 + beat.nextState.tension * 4).coerceAtMost(100),
                    8,
                    "在${beat.nextState.phase}执行“$cleanAction”，剧情导演推进了${beat.beatType}。",
                    JSONObject()
                        .put("scene", current.scene)
                        .put("phase", beat.nextState.phase)
                        .put("location", beat.nextState.currentLocation)
                        .put("beatType", beat.beatType)
                        .put("sceneGoal", beat.nextState.sceneGoal)
                        .put("worldDelta", beat.worldDelta)
                        .put("action", cleanAction)
                        .put("narration", text)
                        .toString(),
                )
                gameStore.attachCharacterReply(recordId, text)
                action = ""
            }
            busy = false
        }
    }

    val current = save
    if (current == null) {
        Box(Modifier.fillMaxSize().background(ApocalypsePalette.top), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = ApocalypsePalette.amber)
        }
        return
    }
    val party = partyFor(current)
    val director = current.director
    val latestAsset = director.assets.lastOrNull()

    Column(Modifier.fillMaxSize().background(ApocalypsePalette.top).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回游戏", tint = ApocalypsePalette.text) }
            Column(Modifier.weight(1f)) {
                Text("末世剧情游戏", color = ApocalypsePalette.text, fontSize = 19.sp, fontWeight = FontWeight.Black)
                Text("${director.phase} · SCENE ${current.scene}", color = ApocalypsePalette.amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = { showDirector = true }) {
                Icon(Icons.Outlined.MovieCreation, "剧情导演", tint = ApocalypsePalette.amber)
            }
            IconButton(onClick = {
                storyStore.clear()
                val created = createNewSave()
                save = created
                storyStore.save(created)
            }) {
                Icon(Icons.Outlined.RestartAlt, "重新开档", tint = ApocalypsePalette.muted)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 245.dp, max = 320.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF1D2026), Color(0xFF29252A), Color(0xFF15191F)),
                    ),
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(director.currentLocation, color = ApocalypsePalette.text, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    Text(director.sceneGoal, color = ApocalypsePalette.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Surface(shape = RoundedCornerShape(9.dp), color = ApocalypsePalette.red.copy(alpha = 0.16f)) {
                            Text("张力 ${director.tension}/10", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = ApocalypsePalette.red, fontSize = 10.sp)
                        }
                        Surface(shape = RoundedCornerShape(9.dp), color = ApocalypsePalette.amber.copy(alpha = 0.14f)) {
                            Text("自由行动", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = ApocalypsePalette.amber, fontSize = 10.sp)
                        }
                    }
                }

                if (latestAsset != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Black.copy(alpha = 0.30f),
                        border = BorderStroke(1.dp, ApocalypsePalette.line),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(Modifier.size(44.dp), RoundedCornerShape(13.dp), assetAccent(latestAsset.kind).copy(alpha = 0.18f)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(assetIcon(latestAsset.kind), null, tint = assetAccent(latestAsset.kind))
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(latestAsset.title, color = ApocalypsePalette.text, fontWeight = FontWeight.Bold)
                                Text(latestAsset.detail, color = ApocalypsePalette.muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy((-7).dp)) {
                        party.take(4).forEach { member ->
                            Surface(
                                modifier = Modifier.size(49.dp),
                                shape = RoundedCornerShape(15.dp),
                                border = BorderStroke(2.dp, ApocalypsePalette.top),
                                color = ApocalypsePalette.panelSoft,
                            ) {
                                LuluProfileAvatar(member.avatarUri, member.displayName.take(1).ifBlank { "角" }, 49)
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { showMap = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ApocalypsePalette.text),
                        border = BorderStroke(1.dp, ApocalypsePalette.line),
                    ) { Icon(Icons.Outlined.Map, null); Spacer(Modifier.width(4.dp)); Text("地图") }
                    OutlinedButton(
                        onClick = { showAssets = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ApocalypsePalette.text),
                        border = BorderStroke(1.dp, ApocalypsePalette.line),
                    ) { Icon(Icons.Outlined.Inventory2, null); Spacer(Modifier.width(4.dp)); Text("线索") }
                }
            }
        }

        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            color = Color(0xFFF5F1E8),
            contentColor = Color(0xFF27282B),
            shape = RoundedCornerShape(topStart = 25.dp, topEnd = 25.dp),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 19.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                item {
                    Text("第 ${current.scene} 幕", color = Color(0xFF8C7650), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(7.dp))
                    Text(current.narration, fontSize = 16.sp, lineHeight = 27.sp)
                }
                if (director.activeThreads.isNotEmpty()) {
                    item {
                        HorizontalDivider(color = Color(0xFFE2DACB))
                        Text("正在发生", fontWeight = FontWeight.Black, fontSize = 13.sp)
                        director.activeThreads.take(3).forEach { thread ->
                            Text("· $thread", color = Color(0xFF69645D), fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Surface(color = ApocalypsePalette.panel, shadowElevation = 10.dp) {
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(11.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                OutlinedTextField(
                    value = action,
                    onValueChange = { action = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("你想做什么都可以……") },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = ApocalypsePalette.amber,
                        focusedBorderColor = ApocalypsePalette.amber,
                        unfocusedBorderColor = ApocalypsePalette.line,
                        focusedPlaceholderColor = ApocalypsePalette.muted,
                        unfocusedPlaceholderColor = ApocalypsePalette.muted,
                    ),
                )
                Button(
                    onClick = ::submitAction,
                    enabled = action.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ApocalypsePalette.amber, contentColor = Color(0xFF25272B)),
                ) {
                    Icon(Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (busy) "导演正在安排下一幕…" else "行动", fontWeight = FontWeight.Black)
                }
            }
        }
    }

    if (showMap) {
        ModalBottomSheet(onDismissRequest = { showMap = false }, containerColor = Color(0xFFF5F1E8)) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text("区域地图", fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text("地图只提供已知地点，不限制你自由行动。点一个地点，会把前往意图放进输入框。", color = Color(0xFF716B62), fontSize = 12.sp)
                director.locations.forEach { location ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = location.unlocked) {
                            action = "我前往${location.name}，到那里后先观察环境，再决定下一步。"
                            showMap = false
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = if (location.unlocked) Color.White else Color(0xFFE6E1D8),
                        border = BorderStroke(1.dp, Color(0xFFD8D0C2)),
                    ) {
                        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (location.unlocked) Icons.Outlined.Place else Icons.Outlined.Lock, null, tint = Color(0xFF8C7650))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(location.name, fontWeight = FontWeight.Bold)
                                Text(location.hint, color = Color(0xFF716B62), fontSize = 11.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }

    if (showAssets) {
        ModalBottomSheet(onDismissRequest = { showAssets = false }, containerColor = Color(0xFFF5F1E8)) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(0.78f).padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text("线索与物品", fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text("这些是导演系统真正保存的剧情资产，不会因为下一轮模型临场发挥而消失。", color = Color(0xFF716B62), fontSize = 12.sp)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(director.assets, key = StoryAsset::id) { asset ->
                        Surface(shape = RoundedCornerShape(17.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFFD8D0C2))) {
                            Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.Top) {
                                Icon(assetIcon(asset.kind), null, tint = assetAccent(asset.kind))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(asset.title, fontWeight = FontWeight.Bold)
                                    Text(asset.detail, color = Color(0xFF716B62), fontSize = 12.sp)
                                    if (asset.visualPrompt.isNotBlank()) {
                                        Text("已保存视觉描述，可直接接入后续生图。", color = Color(0xFF9A8158), fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }

    if (showDirector) {
        ModalBottomSheet(onDismissRequest = { showDirector = false }, containerColor = ApocalypsePalette.panel, contentColor = ApocalypsePalette.text) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(0.78f).padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("剧情导演", fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text("这是编剧层的可公开部分。暗线真相和未回收伏笔不会直接剧透给玩家。", color = ApocalypsePalette.muted, fontSize = 12.sp)
                DirectorPublicCard("核心问题", director.coreQuestion)
                DirectorPublicCard("当前幕目标", director.sceneGoal)
                DirectorPublicCard("明线", director.activeThreads.joinToString("\n") { "· $it" })
                DirectorPublicCard("世界已确认事实", director.worldFacts.takeLast(6).joinToString("\n") { "· $it" })
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun DirectorPublicCard(title: String, content: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = ApocalypsePalette.panelSoft, border = BorderStroke(1.dp, ApocalypsePalette.line)) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = ApocalypsePalette.amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(content.ifBlank { "暂时没有公开信息" }, color = ApocalypsePalette.text, fontSize = 13.sp)
        }
    }
}

private suspend fun planDirectorBeat(
    save: CinematicApocalypseSave,
    party: List<CharacterSettings>,
    action: String,
): DirectorBeat {
    val state = save.director
    val partyPrompt = party.joinToString("\n") { member ->
        "- ${member.displayName}：${member.persona.ifBlank { "遵循既有人设" }}"
    }
    val facts = buildString {
        appendLine("游戏：末日前七日：异能纪元 · 导演式互动小说")
        appendLine("当前第${save.scene}幕；阶段=${state.phase}；地点=${state.currentLocation}；张力=${state.tension}/10")
        appendLine("核心问题：${state.coreQuestion}")
        appendLine("本幕目标：${state.sceneGoal}")
        appendLine("明线：${state.activeThreads.joinToString("｜")}")
        appendLine("隐藏暗线：${state.hiddenThreads.joinToString("｜")}")
        appendLine("已种伏笔：${state.foreshadows.joinToString("｜")}")
        appendLine("世界事实：${state.worldFacts.takeLast(12).joinToString("｜")}")
        appendLine("已知地点：${state.locations.joinToString("｜") { "${it.name}:${if (it.unlocked) "已开放" else "未开放"}" }}")
        appendLine("已有线索/物品：${state.assets.joinToString("｜") { "${it.kind}:${it.title}" }}")
        appendLine("玩家行动：$action")
        appendLine("同行角色：\n$partyPrompt")
        appendLine("最近剧情：\n${save.log.takeLast(6).joinToString("\n")}")
        appendLine("上一幕正文：\n${save.narration.takeLast(2200)}")
    }
    val instruction = """
        你不是正文作者，你是隐藏在游戏背后的电视剧/小说总编剧。玩家拥有高度自由，不能把玩家强行拖回预设轨道；你的任务是在尊重玩家真实行动和已发生事实的前提下，让世界仍然拥有长期结构、伏笔、明暗线、人物弧和有意义的峰回路转。

        只返回一个JSON对象，不要代码块。字段：
        phase: 当前阶段；currentLocation: 本幕结束时最合理的地点；sceneGoal: 下一幕真正要推进的戏剧目标；beatType: setup|pressure|choice|reveal|reversal|payoff|aftermath；tension: 1-10；activeThreads: 2-5条玩家可感知明线；hiddenThreads: 2-5条只有编剧知道的暗线真相；foreshadows: 0-8条仍未完全回收的伏笔；worldFacts: 最多16条已经发生且以后绝不能反悔的世界事实；worldDelta: 本轮导致的世界变化；narrativeDirective: 给正文作者的本幕导演指令；nextPressure: 下一幕可以靠近但不要强制发生的压力；unlockLocations: 新地点数组，每项{id,name,hint}；discoverAssets: 新剧情资产数组，每项{id,kind,title,detail,visualPrompt}，kind只能clue/map/item。

        编剧规则：
        1. 主线不是固定剧本。保留“灾前七日→赤潮降临→据点与群像→势力冲突→天灾真相→文明选择”的大骨架，但玩家可以提前、推迟、绕开、摧毁甚至重建其中的具体路线。
        2. 每次只推进1个主要戏剧动作，最多顺带推进1条暗线。不要每幕都爆炸、背叛、死人。高潮前要有安静、日常、误会、准备和关系沉淀。
        3. 伏笔必须具体可感知，例如异常号码、地图缺口、重复出现的时间、某人的知识漏洞；回收时必须能让玩家意识到“原来之前那个细节是这个意思”。
        4. 峰回路转必须来自已有事实或伏笔，不准凭空新增万能组织、失忆、双胞胎、突然穿越来硬转折。
        5. 角色是人，不是剧情工具。人物可以拒绝编剧希望他做的事；导演应该改剧情，而不是改人设。
        6. 玩家做出离谱但物理上可行的选择时，让世界认真回应并重算线路。玩家彻底破坏某条主线时，把它标记为后果并生成新的替代主线。
        7. 每2-4幕至少让一个之前的细节获得意义；每6-10幕安排一次真正改变局势的回收或反转。
        8. discoverAssets只有玩家这一轮确实可能获得、看到或确认的东西才能新增。地图、照片、纸条、钥匙、样本、武器、药物等都可以成为持久剧情资产。visualPrompt写成以后用于生成插图的客观画面描述，不要写模型参数。
        9. hiddenThreads必须保留长期稳定性，不要每轮全部重写。若必须改变，原因应来自玩家行动造成的世界变化。
        10. 文学目标是让玩家体验一部会因为自己选择而改写的长篇末世剧，而不是一串互不相关的AI段子。
    """.trimIndent()

    val generated = LuluAiServices.gateway.generate(
        characterId = party.firstOrNull()?.characterId ?: "lulu",
        facts = facts,
        instruction = instruction,
        source = "末世游戏导演",
        title = "末世剧情导演 · 第${save.scene}幕",
        temperature = 0.38,
        maxTokens = 1200,
        usage = ModelUsage.Game,
        contextMode = CompanionContextMode.PersonaAndScenario,
    ).getOrNull()?.text

    return generated?.let { parseDirectorBeat(it, state) } ?: fallbackDirectorBeat(save)
}

private suspend fun writeCinematicScene(
    save: CinematicApocalypseSave,
    party: List<CharacterSettings>,
    action: String,
    beat: DirectorBeat,
): Result<String> {
    val state = beat.nextState
    val partyPrompt = party.joinToString("\n") { member ->
        "- ${member.displayName}：${member.persona.ifBlank { "遵循既有人设" }}"
    }
    val facts = buildString {
        appendLine("玩家行动：$action")
        appendLine("阶段：${state.phase}；地点：${state.currentLocation}；张力：${state.tension}/10")
        appendLine("导演指定的戏剧动作：${beat.beatType}")
        appendLine("本幕目标：${state.sceneGoal}")
        appendLine("世界变化：${beat.worldDelta}")
        appendLine("导演指令：${beat.narrativeDirective}")
        appendLine("下一层压力：${beat.nextPressure}")
        appendLine("明线：${state.activeThreads.joinToString("｜")}")
        appendLine("隐藏暗线（只能通过含蓄细节表现，禁止直接剧透）：${state.hiddenThreads.joinToString("｜")}")
        appendLine("未回收伏笔：${state.foreshadows.joinToString("｜")}")
        appendLine("已确认世界事实：${state.worldFacts.takeLast(12).joinToString("｜")}")
        appendLine("同行者：\n$partyPrompt")
        appendLine("上一幕：\n${save.narration.takeLast(2600)}")
    }
    val instruction = """
        你是成熟的长篇小说作者和互动叙事执行导演。你不负责重新设计主线，必须服从上面的隐藏导演规划，但绝不能在正文中暴露“导演、编剧、主线、暗线、伏笔列表”等后台词。

        写900—1500个汉字的完整一幕。玩家的自由行动必须真正发生并产生后果，不能用剧情需要把行动吞掉。文笔要求像质量较高的末世小说/电视剧分场：
        - 不要平铺直叙。必须有具体空间、光线、声音、温度、气味或触感，让场景有可见画面。
        - 对话要有动作、停顿、眼神和潜台词，不要连续排队发言。人物的聪明程度和行为必须符合persona。
        - 一幕至少有一个能被记住的具体画面细节；重要情绪尽量通过动作和环境表现，不直接解释“他很害怕/她很感动”。
        - 句子长短交替。危险段落可以短促，安静段落可以细腻。不要整篇都高强度，也不要整篇流水账。
        - 转折必须来自导演提供的世界变化、既有事实或伏笔。没有依据就不要硬反转。
        - 九死一生只留给真正的重要节点。失败和危险可以造成代价，但每个代价都应带来新的信息、关系变化或路线变化。
        - 这是异能末世，但灾前阶段不要过早满街超能力。异常应逐渐显形。
        - 不写数值面板、任务列表、A/B/C选项，不代替玩家决定下一步。
        - 结尾留下一个自然的悬念、压力或可行动空间即可，不要用“你可以选择……”收尾。

        只输出正文。
    """.trimIndent()
    return LuluAiServices.gateway.generate(
        characterId = party.firstOrNull()?.characterId ?: "lulu",
        facts = facts,
        instruction = instruction,
        source = "末世剧情游戏",
        title = "末世剧情游戏 · 第${save.scene}幕",
        temperature = 0.82,
        maxTokens = 2200,
        usage = ModelUsage.Game,
        contextMode = CompanionContextMode.PersonaAndScenario,
    ).map { it.text.trim() }
}

private fun parseDirectorBeat(raw: String, previous: DirectorState): DirectorBeat? = runCatching {
    val json = JSONObject(extractJsonObject(raw))
    val newLocations = json.optJSONArray("unlockLocations").toObjects { item ->
        StoryLocation(
            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = item.optString("name").ifBlank { "未命名地点" },
            hint = item.optString("hint").take(140),
            unlocked = true,
        )
    }
    val newAssets = json.optJSONArray("discoverAssets").toObjects { item ->
        StoryAsset(
            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
            kind = when (item.optString("kind").lowercase()) {
                "map" -> StoryAssetKind.Map
                "item" -> StoryAssetKind.Item
                else -> StoryAssetKind.Clue
            },
            title = item.optString("title").ifBlank { "新线索" }.take(60),
            detail = item.optString("detail").take(360),
            visualPrompt = item.optString("visualPrompt").take(600),
        )
    }
    val mergedLocations = (previous.locations + newLocations).distinctBy(StoryLocation::id).takeLast(18)
    val mergedAssets = (previous.assets + newAssets).distinctBy(StoryAsset::id).takeLast(30)
    val next = previous.copy(
        phase = json.optString("phase").ifBlank { previous.phase }.take(80),
        currentLocation = json.optString("currentLocation").ifBlank { previous.currentLocation }.take(100),
        sceneGoal = json.optString("sceneGoal").ifBlank { previous.sceneGoal }.take(220),
        activeThreads = json.optJSONArray("activeThreads").stringList().ifEmpty { previous.activeThreads }.take(5),
        hiddenThreads = json.optJSONArray("hiddenThreads").stringList().ifEmpty { previous.hiddenThreads }.take(5),
        foreshadows = json.optJSONArray("foreshadows").stringList().ifEmpty { previous.foreshadows }.take(8),
        worldFacts = json.optJSONArray("worldFacts").stringList().ifEmpty { previous.worldFacts }.takeLast(16),
        locations = mergedLocations,
        assets = mergedAssets,
        tension = json.optInt("tension", previous.tension).coerceIn(1, 10),
    )
    DirectorBeat(
        nextState = next,
        beatType = json.optString("beatType").ifBlank { "pressure" }.take(30),
        worldDelta = json.optString("worldDelta").take(280),
        narrativeDirective = json.optString("narrativeDirective").ifBlank { "让玩家行动产生具体后果，并推进当前明线。" }.take(700),
        nextPressure = json.optString("nextPressure").take(320),
    )
}.getOrNull()

private fun fallbackDirectorBeat(save: CinematicApocalypseSave): DirectorBeat {
    val previous = save.director
    val nextTension = when (save.scene % 6) {
        0 -> (previous.tension + 2).coerceAtMost(8)
        1 -> (previous.tension - 1).coerceAtLeast(2)
        else -> previous.tension
    }
    return DirectorBeat(
        nextState = previous.copy(
            tension = nextTension,
            sceneGoal = "让玩家的行动真实改变当前局势，同时把一条既有细节向前推进。",
        ),
        beatType = if (save.scene % 6 == 0) "reveal" else "choice",
        worldDelta = "世界按玩家的选择继续演化；没有新增未经铺垫的重大设定。",
        narrativeDirective = "优先延续上一幕的具体环境、人物行动和未解决问题。不要凭空制造大反转。",
        nextPressure = "让一个已经出现过的问题逐渐变得更难忽视。",
    )
}

private fun initialDirectorState(): DirectorState = DirectorState(
    phase = "灾前第7日",
    currentLocation = "临江市 · 旧城区公寓",
    coreQuestion = "你为什么会提前知道赤潮会在七天后降临，而这份预警究竟是在救你，还是在把你引向某个结果？",
    sceneGoal = "先让预警变成一个必须认真对待的现实问题，并决定最初要相信谁、准备什么。",
    activeThreads = listOf(
        "七日倒计时：在秩序尚存时确认预警是否真实",
        "生存准备：钱、药品、交通、食物与据点都有限",
    ),
    hiddenThreads = listOf(
        "预警来源与赤潮出现前的一次被掩盖实验有关，但它并不等于幕后黑手",
        "临江市地下防灾网存在一条从公开地图中删除的撤离层",
        "第一座真正安全的据点最终会由玩家早期选择的人际关系决定，而不是由地理位置决定",
    ),
    foreshadows = listOf(
        "每天14:17出现数秒的红色通信雪花",
        "旧城区公共地图上被贴纸盖住的B8层标记",
    ),
    worldFacts = listOf(
        "赤潮将在七天后造成文明级崩塌，但灾前绝大多数人并不知道",
        "异能会随赤潮逐步出现，体系分自然、精神、空间、生命、强化、规则六系",
        "灾前社会秩序仍正常运转，违法、金钱、舆论和人际信任仍有现实代价",
    ),
    locations = listOf(
        StoryLocation("home", "旧城区公寓", "当前落脚点；可以整理情报、联系人和物资。"),
        StoryLocation("market", "城南综合市场", "食物、水、工具和药品都能买到，但大量采购会留下痕迹。"),
        StoryLocation("hospital", "临江二院", "药品、急诊资源和异常病例可能在这里最早出现。"),
        StoryLocation("metro", "旧城地铁换乘站", "公开地图到B4层为止，但你见过一个被遮住的B8标记。"),
    ),
    assets = listOf(
        StoryAsset(
            id = "countdown_message",
            kind = StoryAssetKind.Clue,
            title = "七日预警",
            detail = "手机里出现过一条无法追溯发送源的预警：七天后，赤潮将让城市秩序崩塌。",
            visualPrompt = "夜晚室内，一部手机屏幕显示简短的七日倒计时警告，屏幕边缘有极轻微红色雪花干扰，桌面散着钥匙与未拆封的药盒",
        ),
    ),
    tension = 3,
)

private fun initialApocalypseNarration(partyNames: List<String>): String {
    val companions = partyNames.joinToString("、").ifBlank { "你信任的人" }
    return """
        下午两点十七分，手机信号突然从满格掉到零。

        不是断网。屏幕上所有图标都还在，新闻直播仍停在主持人抬手的那一帧，空调外机也照常在窗外嗡鸣。真正不对劲的是屏幕中央那层极细的红色雪花——像旧电视接触不良，只有三秒，然后一切恢复。

        紧接着，一条没有号码、没有应用图标、也无法截图的文字浮了出来。

        “七日后，赤潮抵达临江市。”

        “不要去官方公布的第一避难区。”

        “如果你还想让${companions}活下来，从今天开始准备。”

        第三句话消失以后，手机相册里多了一张照片：旧城区地铁施工图。公开线路只画到地下四层，可照片最下面还有一个被红笔圈起来的“B8”。

        窗外有人为抢一个停车位按了两声喇叭，楼下便利店正在放促销广播。世界看起来正常得近乎荒谬。

        七天。

        你现在拥有的，只有这七天和一条不知道该不该相信的消息。
    """.trimIndent()
}

private fun encodeSave(value: CinematicApocalypseSave): JSONObject = JSONObject()
    .put("id", value.id)
    .put("scene", value.scene)
    .put("partyIds", JSONArray(value.partyIds))
    .put("narration", value.narration)
    .put("director", encodeDirector(value.director))
    .put("log", JSONArray(value.log))
    .put("updatedAt", value.updatedAt)

private fun decodeSave(json: JSONObject): CinematicApocalypseSave = CinematicApocalypseSave(
    id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
    scene = json.optInt("scene", 1).coerceAtLeast(1),
    partyIds = json.optJSONArray("partyIds").stringList(),
    narration = json.optString("narration").ifBlank { initialApocalypseNarration(emptyList()) },
    director = json.optJSONObject("director")?.let(::decodeDirector) ?: initialDirectorState(),
    log = json.optJSONArray("log").stringList(),
    updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
)

private fun encodeDirector(value: DirectorState): JSONObject = JSONObject()
    .put("phase", value.phase)
    .put("currentLocation", value.currentLocation)
    .put("coreQuestion", value.coreQuestion)
    .put("sceneGoal", value.sceneGoal)
    .put("activeThreads", JSONArray(value.activeThreads))
    .put("hiddenThreads", JSONArray(value.hiddenThreads))
    .put("foreshadows", JSONArray(value.foreshadows))
    .put("worldFacts", JSONArray(value.worldFacts))
    .put("locations", JSONArray().apply {
        value.locations.forEach { location ->
            put(JSONObject().put("id", location.id).put("name", location.name).put("hint", location.hint).put("unlocked", location.unlocked))
        }
    })
    .put("assets", JSONArray().apply {
        value.assets.forEach { asset ->
            put(JSONObject()
                .put("id", asset.id)
                .put("kind", asset.kind.name)
                .put("title", asset.title)
                .put("detail", asset.detail)
                .put("visualPrompt", asset.visualPrompt))
        }
    })
    .put("tension", value.tension)

private fun decodeDirector(json: JSONObject): DirectorState = DirectorState(
    phase = json.optString("phase", "灾前第7日"),
    currentLocation = json.optString("currentLocation", "临江市 · 旧城区公寓"),
    coreQuestion = json.optString("coreQuestion").ifBlank { initialDirectorState().coreQuestion },
    sceneGoal = json.optString("sceneGoal").ifBlank { initialDirectorState().sceneGoal },
    activeThreads = json.optJSONArray("activeThreads").stringList().ifEmpty { initialDirectorState().activeThreads },
    hiddenThreads = json.optJSONArray("hiddenThreads").stringList().ifEmpty { initialDirectorState().hiddenThreads },
    foreshadows = json.optJSONArray("foreshadows").stringList(),
    worldFacts = json.optJSONArray("worldFacts").stringList().ifEmpty { initialDirectorState().worldFacts },
    locations = json.optJSONArray("locations").toObjects { item ->
        StoryLocation(item.optString("id"), item.optString("name"), item.optString("hint"), item.optBoolean("unlocked", true))
    }.ifEmpty { initialDirectorState().locations },
    assets = json.optJSONArray("assets").toObjects { item ->
        StoryAsset(
            item.optString("id"),
            runCatching { StoryAssetKind.valueOf(item.optString("kind")) }.getOrDefault(StoryAssetKind.Clue),
            item.optString("title"),
            item.optString("detail"),
            item.optString("visualPrompt"),
        )
    }.ifEmpty { initialDirectorState().assets },
    tension = json.optInt("tension", 3).coerceIn(1, 10),
)

private fun extractJsonObject(raw: String): String {
    val value = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val start = value.indexOf('{')
    val end = value.lastIndexOf('}')
    return if (start >= 0 && end > start) value.substring(start, end + 1) else value
}

private fun JSONArray?.stringList(): List<String> = buildList {
    val array = this@stringList ?: return@buildList
    for (index in 0 until array.length()) array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
}

private fun <T> JSONArray?.toObjects(mapper: (JSONObject) -> T): List<T> = buildList {
    val array = this@toObjects ?: return@buildList
    for (index in 0 until array.length()) array.optJSONObject(index)?.let { item -> runCatching { mapper(item) }.getOrNull()?.let(::add) }
}

private fun assetIcon(kind: StoryAssetKind) = when (kind) {
    StoryAssetKind.Clue -> Icons.Outlined.Search
    StoryAssetKind.Map -> Icons.Outlined.Map
    StoryAssetKind.Item -> Icons.Outlined.Inventory2
}

private fun assetAccent(kind: StoryAssetKind): Color = when (kind) {
    StoryAssetKind.Clue -> ApocalypsePalette.blue
    StoryAssetKind.Map -> ApocalypsePalette.amber
    StoryAssetKind.Item -> ApocalypsePalette.green
}
