package com.jiacimu.lulu.games

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ModelArchiveIconButton
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private sealed interface GameRoute {
    data object Home : GameRoute
    data object DeepSea : GameRoute
    data object PerfectMan : GameRoute
    data object Roleplay : GameRoute
    data object TurtleSoup : GameRoute
    data object RapportQuiz : GameRoute
    data object YachtDice : GameRoute
    data object Gomoku : GameRoute
    data object MemoryMatch : GameRoute
    data object Records : GameRoute
    data class Replay(val recordId: String) : GameRoute
}

private data class GameLauncher(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val route: GameRoute,
    val minCharacters: Int = 1,
    val maxCharacters: Int = 1,
    val accent: Color = Color(0xFF64708C),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuluGamesApp(onBack: () -> Unit, initialGameId: String? = null, returnToCaller: Boolean = false) {
    val store = remember { LuluGames.store }
    val state by store.state.collectAsState()
    var route by remember(initialGameId) { mutableStateOf(initialGameId.toGameRouteOrHome()) }
    val bgmEnabled = GameAmbientSoundscape(if (route == GameRoute.DeepSea) GameSoundscape.Ocean else GameSoundscape.Arcade)
    var pendingRoute by remember { mutableStateOf<GameRoute?>(null) }

    fun stepBack() {
        if (route == GameRoute.Home || returnToCaller) onBack() else route = GameRoute.Home
    }

    BackHandler { stepBack() }

    pendingRoute?.let { target ->
        GameParticipantPickerScreen(
            route = target,
            initiallySelected = state.selectedCharacterIds,
            onBack = { pendingRoute = null },
            onConfirm = { selected ->
                store.selectCharacters(selected)
                pendingRoute = null
                route = target
            },
        )
        return
    }

    if (route == GameRoute.Roleplay) {
        FormalRoleplayCampaignScreen(
            store = store,
            onBack = { route = GameRoute.Home },
        )
        return
    }

    val darkChrome = route in setOf(GameRoute.Home, GameRoute.DeepSea, GameRoute.MemoryMatch)

    Scaffold(
        containerColor = if (darkChrome) Color(0xFF0B111B) else GameDesign.paper,
        topBar = {
            TopAppBar(
                title = { Text(route.title(), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = ::stepBack) {
                        Icon(Icons.Outlined.ArrowBack, "返回")
                    }
                },
                actions = {
                    GameAmbientAudioButton(
                        enabled = bgmEnabled,
                        tint = if (darkChrome) Color.White else GameDesign.ink,
                    )
                    if (route == GameRoute.Home) {
                        IconButton(onClick = { route = GameRoute.Records }) {
                            Icon(Icons.Outlined.History, "游戏记录与回放", tint = Color.White)
                        }
                    }
                    ModelArchiveIconButton(
                        usage = ModelUsage.Game,
                        title = "游戏模型",
                        subtitle = "只切换“游戏”应用使用的模型存档；末世求生有自己的独立模型，不会跟着改变。",
                        icon = Icons.Outlined.Memory,
                        contentDescription = "选择游戏模型",
                        tint = if (darkChrome) Color.White else GameDesign.ink,
                        accent = if (darkChrome) Color.White else GameDesign.ink,
                        background = if (darkChrome) Color(0xFF111620) else GameDesign.paper,
                        ink = if (darkChrome) Color.White else GameDesign.ink,
                        muted = if (darkChrome) Color(0xFFB8C2D3) else GameDesign.muted,
                        border = if (darkChrome) Color.White.copy(alpha = .18f) else GameDesign.border,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (darkChrome) Color(0xFF0B111B) else GameDesign.paper,
                    titleContentColor = if (darkChrome) Color.White else GameDesign.ink,
                    navigationIconContentColor = if (darkChrome) Color.White else GameDesign.ink,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            ArcadeAtmosphere(Modifier.matchParentSize())
            when (val current = route) {
                GameRoute.Home -> GameHome(onOpen = { pendingRoute = it })
                GameRoute.DeepSea -> DeepSeaJourneyScreen(store)
                GameRoute.PerfectMan -> PerfectManScreen(store)
                GameRoute.Roleplay -> Unit
                GameRoute.TurtleSoup -> TurtleSoupScreen(store)
                GameRoute.RapportQuiz -> RapportQuizScreen(store)
                GameRoute.YachtDice -> YachtDiceScreen(store)
                GameRoute.Gomoku -> GomokuScreen(store)
                GameRoute.MemoryMatch -> ImmersiveMemoryMatchScreen(store)
                GameRoute.Records -> GameRecordsScreen(state, store, onReplay = { route = GameRoute.Replay(it) })
                is GameRoute.Replay -> GameReplayScreen(
                    record = state.records.firstOrNull { it.id == current.recordId },
                    onDeleteAll = store::clearRecords,
                )
            }
        }
    }
}

private fun GameRoute.title(): String = when (this) {
    GameRoute.Home -> "游戏"
    GameRoute.DeepSea -> "深海回声"
    GameRoute.PerfectMan -> "满分男"
    GameRoute.Roleplay -> "跑团"
    GameRoute.TurtleSoup -> "海龟汤"
    GameRoute.RapportQuiz -> "默契问答"
    GameRoute.YachtDice -> "快艇骰子"
    GameRoute.Gomoku -> "五子棋"
    GameRoute.MemoryMatch -> "记忆配对"
    GameRoute.Records -> "游戏记录"
    is GameRoute.Replay -> "游戏回放"
}

private fun String?.toGameRouteOrHome(): GameRoute = when (this?.trim()?.lowercase()) {
    "deep_sea", "deep_sea_journey" -> GameRoute.DeepSea
    "perfect_man" -> GameRoute.PerfectMan
    "roleplay" -> GameRoute.Roleplay
    "turtle_soup" -> GameRoute.TurtleSoup
    "rapport_quiz" -> GameRoute.RapportQuiz
    "yacht_dice" -> GameRoute.YachtDice
    "gomoku" -> GameRoute.Gomoku
    "memory_match" -> GameRoute.MemoryMatch
    else -> GameRoute.Home
}

@Composable
private fun GameHome(
    onOpen: (GameRoute) -> Unit,
) {
    val deepSea = GameLauncher(
        "deep_sea_journey",
        "深海回声",
        "驾驶潜航器穿越海沟，躲避礁体、收集五段回声并开启归航门。",
        Icons.Outlined.Waves,
        GameRoute.DeepSea,
        minCharacters = 0,
        maxCharacters = 1,
        accent = Color(0xFF66D7CC),
    )
    val featured = listOf(
        GameLauncher("roleplay", "跑团", "长期剧情存档、同行小队与沉浸式小说叙事。", Icons.Outlined.AutoStories, GameRoute.Roleplay, 1, 4, Color(0xFF655F84)),
        GameLauncher("memory_match", "记忆配对", "保留完整翻牌与角色对局，用立体翻转和舞台光效重做。", Icons.Outlined.GridView, GameRoute.MemoryMatch, accent = Color(0xFF75658C)),
    )
    val classics = listOf(
        GameLauncher("perfect_man", "满分男", "轮流描述与猜分，由角色真实判断。", Icons.Outlined.PersonSearch, GameRoute.PerfectMan, accent = Color(0xFF876B7A)),
        GameLauncher("turtle_soup", "海龟汤", "固定汤底、自由提问与共同推理。", Icons.Outlined.HelpOutline, GameRoute.TurtleSoup, 1, 3, Color(0xFF526E86)),
        GameLauncher("rapport_quiz", "默契问答", "角色秘密作答，再比较彼此答案。", Icons.Outlined.QuestionAnswer, GameRoute.RapportQuiz, 1, 3, Color(0xFF8A675E)),
        GameLauncher("yacht_dice", "快艇骰子", "五骰三掷，支持最多四人同局。", Icons.Outlined.Casino, GameRoute.YachtDice, 1, 3, Color(0xFF667653)),
        GameLauncher("gomoku", "五子棋", "双人对弈，角色会进攻、拦截与复盘。", Icons.Outlined.GridOn, GameRoute.Gomoku, accent = Color(0xFF6D6E72)),
    )
    var showClassics by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFF111620)),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { GameHallHero() }
        item { DeepSeaFeaturedEntry(deepSea, onOpen) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("主舞台", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
                Text("完整进度、角色参与和可回放结算", color = Color(0xFF8E99AA), fontSize = 10.sp)
            }
        }
        items(featured, key = { it.id }) { launcher -> GameEntry(launcher, onOpen) }
        item {
            Surface(
                onClick = { showClassics = !showClassics },
                modifier = Modifier.fillMaxWidth(),
                color = Color.White.copy(alpha = .055f),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .12f)),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.SportsEsports, null, tint = Color(0xFFB9C3D5), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("轻量经典 · ${classics.size}", color = Color(0xFFDCE2EE), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Icon(if (showClassics) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = Color(0xFF9CA7BA))
                }
            }
        }
        if (showClassics) {
            items(classics, key = { it.id }) { launcher -> GameEntry(launcher, onOpen) }
        }
    }
}

@Composable
private fun GameHallHero() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF202734),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, Color(0xFF3B4658)),
        shadowElevation = 8.dp,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 154.dp)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF171C27), Color(0xFF293347), Color(0xFF121A26)),
                    ),
                ),
        ) {
            ArcadeAtmosphere(Modifier.matchParentSize())
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Surface(
                    color = Color.White.copy(alpha = .08f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(.7.dp, Color.White.copy(alpha = .15f)),
                ) {
                    Text(
                        "3 个主舞台 · 5 款轻量经典",
                        color = Color(0xFFD9DFED),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    )
                }
                Text("从小游戏，进入游戏世界", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text(
                    "主舞台拥有连续移动、碰撞、镜头、目标与空间音效；角色可加入，但不再强迫双人。",
                    color = Color(0xFFBBC4D8),
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}

@Composable
private fun DeepSeaFeaturedEntry(launcher: GameLauncher, onOpen: (GameRoute) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) .982f else 1f,
        animationSpec = tween(120),
        label = "deep-sea-featured-press",
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(232.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null) { onOpen(launcher.route) },
        color = Color(0xFF082936),
        shape = RoundedCornerShape(30.dp),
        border = BorderStroke(1.dp, Color(0xFF83E4D8).copy(alpha = .38f)),
        shadowElevation = if (pressed) 3.dp else 12.dp,
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(
                    listOf(Color(0xFF0C4655), Color(0xFF08283A), Color(0xFF071824)),
                ),
            ),
        ) {
            Box(
                Modifier
                    .size(210.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 50.dp, y = (-58).dp)
                    .background(
                        Brush.radialGradient(listOf(Color(0xFF8FFFF0).copy(alpha = .28f), Color.Transparent)),
                        CircleShape,
                    ),
            )
            Icon(
                Icons.Outlined.Waves,
                null,
                tint = Color(0xFF9AFFF0).copy(alpha = .15f),
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 20.dp, y = 22.dp).size(174.dp),
            )
            Column(
                Modifier.fillMaxSize().padding(19.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Color(0xFF9AFFF0).copy(alpha = .15f), shape = RoundedCornerShape(99.dp), border = BorderStroke(1.dp, Color(0xFF9AFFF0).copy(alpha = .28f))) {
                        Text("NEW · 沉浸主舞台", color = Color(0xFFB9FFF2), fontSize = 9.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Surface(shape = CircleShape, color = Color.White.copy(alpha = .10f), modifier = Modifier.size(38.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.NorthEast, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(launcher.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    Text(launcher.subtitle, color = Color(0xFFB5D8D5), fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.widthIn(max = 285.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf("自由移动", "真实碰撞", "单人可玩").forEach { label ->
                            Surface(color = Color.Black.copy(alpha = .18f), shape = RoundedCornerShape(8.dp)) {
                                Text(label, color = Color(0xFFAEE9E2), fontSize = 8.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameParticipantPickerScreen(
    route: GameRoute,
    initiallySelected: List<String>,
    onBack: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val limits = route.playerLimits()
    val dark = route == GameRoute.DeepSea
    var selected by remember(route) {
        mutableStateOf(
            if (limits.first == 0) emptySet()
            else initiallySelected.filter { it in characters }.take(limits.second).toSet(),
        )
    }
    LaunchedEffect(characters.keys, route) {
        if (limits.first > 0 && selected.isEmpty() && characters.isNotEmpty()) selected = setOf(characters.keys.first())
    }
    Scaffold(
        containerColor = if (dark) Color(0xFF071824) else GameDesign.paper,
        topBar = {
            TopAppBar(
                title = { Text("选择同行角色", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (dark) Color(0xFF071824) else GameDesign.paper,
                    titleContentColor = if (dark) Color.White else GameDesign.ink,
                    navigationIconContentColor = if (dark) Color.White else GameDesign.ink,
                ),
            )
        },
        bottomBar = {
            Surface(color = if (dark) Color(0xFF09202C) else GameDesign.card, shadowElevation = 8.dp) {
                Button(
                    onClick = { onConfirm(selected.toList()) },
                    enabled = selected.size in limits.first..limits.second,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(52.dp),
                    colors = if (dark) ButtonDefaults.buttonColors(containerColor = Color(0xFF9EFFE9), contentColor = Color(0xFF09242D)) else ButtonDefaults.buttonColors(),
                ) { Text("确认并进入${route.title()}") }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(if (dark) Color(0xFF071824) else GameDesign.paper).padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(
                    color = if (dark) Color(0xFF0B2A37) else GameDesign.card,
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, if (dark) Color(0xFF9EFFE9).copy(alpha = .24f) else GameDesign.border),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(route.title(), color = if (dark) Color.White else GameDesign.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            limits.first == 0 -> "可以独自进入，也可以邀请 1 位角色同行"
                            limits.first == limits.second -> "请选择 ${limits.first} 位角色"
                            else -> "请选择 ${limits.first}—${limits.second} 位角色；加上你共可 ${limits.first + 1}—${limits.second + 1} 人游玩"
                        },
                        color = if (dark) Color(0xFF9FC6C1) else GameDesign.muted,
                    )
                    }
                }
            }
            items(characters.values.sortedBy { it.displayName }, key = { it.characterId }) { character ->
                val checked = character.characterId in selected
                Card(
                    onClick = {
                        selected = when {
                            checked -> selected - character.characterId
                            selected.size < limits.second -> selected + character.characterId
                            else -> selected
                        }
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = if (dark) {
                            if (checked) Color(0xFF174A50) else Color(0xFF0A2430)
                        } else if (checked) GameDesign.wheatSoft else GameDesign.card,
                    ),
                    border = BorderStroke(
                        if (checked) 2.dp else 1.dp,
                        if (dark) {
                            if (checked) Color(0xFF9EFFE9) else Color.White.copy(alpha = .12f)
                        } else if (checked) GameDesign.ink else GameDesign.border,
                    ),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        com.jiacimu.lulu.LuluProfileAvatar(character.avatarUri, character.displayName.take(1).ifBlank { "角" }, 52)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(character.displayName, color = if (dark) Color.White else GameDesign.ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(character.persona.take(70).ifBlank { "按照角色人设参与游戏" }, color = if (dark) Color(0xFF91B8B4) else GameDesign.muted, maxLines = 2)
                        }
                        Icon(if (checked) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, tint = if (dark) Color(0xFF9EFFE9) else GameDesign.ink)
                    }
                }
            }
        }
    }
}

private fun GameRoute.playerLimits(): Pair<Int, Int> = when (this) {
    GameRoute.DeepSea -> 0 to 1
    GameRoute.Roleplay -> 1 to 4
    GameRoute.TurtleSoup, GameRoute.RapportQuiz, GameRoute.YachtDice -> 1 to 3
    GameRoute.PerfectMan, GameRoute.Gomoku, GameRoute.MemoryMatch -> 1 to 1
    else -> 1 to 1
}

@Composable
private fun MemoryMatchScreen(store: LuluGameStore) {
    val state by store.state.collectAsState()
    val game = state.memoryMatch
    val character = MigratedDomainStores.characters.get(state.selectedCharacterId)
    LaunchedEffect(game.opened) {
        if (game.opened.size == 2 && game.opened.any { it !in game.matched }) {
            delay(900)
            store.closeUnmatchedCards()
        }
    }
    LaunchedEffect(game.turn, game.opened, game.matched, game.finished) {
        if (game.turn != MemoryTurn.Character || game.finished || game.opened.size >= 2) return@LaunchedEffect
        delay(550)
        val available = game.cards.indices.filter { it !in game.matched && it !in game.opened }
        if (available.isEmpty()) return@LaunchedEffect
        if (game.opened.isEmpty()) {
            store.openCharacterMemoryCard(available.random())
        } else {
            val first = game.opened.first()
            val pair = available.firstOrNull { game.cards[it] == game.cards[first] }
            val choice = if (pair != null && kotlin.random.Random.nextInt(100) < 68) pair else available.random()
            store.openCharacterMemoryCard(choice)
        }
    }
    GamePageList {
        item {
            GameCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("你 ${game.userPairs} 对", fontWeight = FontWeight.Bold)
                    Text("${character.displayName} ${game.characterPairs} 对", fontWeight = FontWeight.Bold)
                }
                Text(game.lastEvent, color = GameDesign.muted)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                game.cards.indices.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        row.forEach { index ->
                            val visible = index in game.opened || index in game.matched
                            val rotation by androidx.compose.animation.core.animateFloatAsState(
                                targetValue = if (visible) 180f else 0f,
                                animationSpec = androidx.compose.animation.core.spring(
                                    dampingRatio = .72f,
                                    stiffness = 430f,
                                ),
                                label = "memory-card-flip",
                            )
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(0.82f)
                                    .graphicsLayer {
                                        rotationY = rotation
                                        cameraDistance = 12f * density
                                    }
                                    .clickable(
                                        enabled = !visible && !game.finished && game.turn == MemoryTurn.User && game.opened.size < 2,
                                    ) { store.openMemoryCard(index) },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (visible) GameDesign.wheat else GameDesign.card,
                                    contentColor = if (visible) GameDesign.onDark else GameDesign.ink,
                                ),
                                border = BorderStroke(1.dp, GameDesign.border),
                                shape = RoundedCornerShape(17.dp),
                            ) {
                                Box(
                                    Modifier.fillMaxSize().graphicsLayer {
                                        rotationY = if (rotation > 90f) 180f else 0f
                                    },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        if (visible) game.cards[index] else "✦",
                                        color = if (visible) GameDesign.onDark else GameDesign.ink,
                                        fontSize = 27.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (game.finished) item {
            GameCard {
                Text(
                    when {
                        game.userPairs > game.characterPairs -> "你赢啦"
                        game.userPairs < game.characterPairs -> "这局是 ${character.displayName} 赢"
                        else -> "这局平手"
                    },
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text("${game.moves} 轮翻完全部牌", color = GameDesign.muted)
                Button(onClick = store::resetMemoryMatch, modifier = Modifier.fillMaxWidth()) { Text("重新洗牌") }
            }
        }
    }
}

@Composable
private fun MoodGuessScreen(store: LuluGameStore) {
    val state by store.state.collectAsState()
    val round = state.moodRound
    val answered = state.moodAnswered
    GamePageList {
        item { GameCard { Text("判断情境中的情绪", fontSize = 21.sp, fontWeight = FontWeight.Bold); Text(round.clue); Text("这是额外小游戏，情境不自动写成角色真实经历。", color = GameDesign.muted, fontSize = 12.sp) } }
        items(round.options) { option ->
            OutlinedButton(onClick = { store.answerMood(option) }, enabled = answered == null, modifier = Modifier.fillMaxWidth()) { Text(option) }
        }
        if (answered != null) {
            item {
                GameCard {
                    val correct = answered == round.answer
                    Text(if (correct) "判断正确" else "更接近：${round.answer}", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("你的选择：$answered", color = GameDesign.muted)
                    Button(onClick = store::resetMoodGuess, modifier = Modifier.fillMaxWidth()) { Text("下一题") }
                }
            }
        }
    }
}

@Composable
private fun GameRecordsScreen(
    state: LuluGameState,
    store: LuluGameStore,
    onReplay: (String) -> Unit,
) {
    val records = state.records
    GamePageList {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("共 ${records.size} 条记录", fontWeight = FontWeight.Bold)
                TextButton(onClick = store::clearRecords, enabled = records.isNotEmpty()) { Text("清空") }
            }
        }
        if (records.isEmpty()) {
            item { GameCard { Text("还没有游戏记录", fontSize = 20.sp, fontWeight = FontWeight.Bold); Text("完成任意游戏后，这里会保存事实、规则详情和角色回应。", color = GameDesign.muted) } }
        } else {
            items(records, key = { it.id }) { record ->
                GameCard(Modifier.clickable { onReplay(record.id) }) {
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(record.title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(record.summary, color = GameDesign.muted, maxLines = 3)
                            val character = MigratedDomainStores.characters.get(record.characterId)
                            Text(if (record.playedWithCharacter) "参与角色：${character.displayName}" else "单人模式", color = GameDesign.muted, fontSize = 12.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${record.score} 分", fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(record.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")), color = GameDesign.muted, fontSize = 12.sp)
                    Text("查看回放 ›", color = GameDesign.muted)
                }
            }
        }
    }
}

@Composable
private fun GameReplayScreen(record: LuluGameRecord?, onDeleteAll: () -> Unit) {
    if (record == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("这条记录不存在或已被清除") }
        return
    }
    GamePageList {
        item {
            GameCard {
                Text(record.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(record.summary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("得分 ${record.score}")
                }
                Text(record.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), color = GameDesign.muted, fontSize = 12.sp)
            }
        }
        item {
            GameCard {
                Text("规则事实", fontWeight = FontWeight.Bold)
                Text(prettyDetails(record.detailsJson), color = GameDesign.muted)
            }
        }
        if (record.playedWithCharacter) {
            item {
                val character = MigratedDomainStores.characters.get(record.characterId)
                GameCard {
                    Text("${character.displayName}当时的回应", fontWeight = FontWeight.Bold)
                    Text(record.characterReply.ifBlank { "该局已保存，但没有成功生成角色回应。" }, color = if (record.characterReply.isBlank()) GameDesign.muted else GameDesign.ink)
                }
            }
        }
        item { TextButton(onClick = onDeleteAll, modifier = Modifier.fillMaxWidth()) { Text("清空全部游戏记录") } }
    }
}

private fun prettyDetails(raw: String): String = runCatching {
    JSONObject(raw).toString(2)
}.getOrElse { raw.ifBlank { "没有额外规则详情" } }

@Composable
private fun GameEntry(launcher: GameLauncher, onOpen: (GameRoute) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) .975f else 1f,
        animationSpec = androidx.compose.animation.core.tween(120),
        label = "game-card-press",
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = interaction, indication = null) { onOpen(launcher.route) },
        colors = CardDefaults.cardColors(containerColor = GameDesign.card),
        border = BorderStroke(1.dp, launcher.accent.copy(alpha = .28f)),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (pressed) 1.dp else 3.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 92.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(82.dp)
                    .height(92.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(launcher.accent.copy(alpha = .20f), launcher.accent.copy(alpha = .07f)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = launcher.accent,
                    shape = RoundedCornerShape(18.dp),
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(launcher.icon, null, tint = Color.White, modifier = Modifier.size(27.dp))
                    }
                }
            }
            Column(
                Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(launcher.title, fontSize = 18.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.ChevronRight, null, tint = launcher.accent, modifier = Modifier.size(21.dp))
                }
                Text(launcher.subtitle, color = GameDesign.muted, fontSize = 12.sp, lineHeight = 17.sp)
                Text(
                    when {
                        launcher.minCharacters == 0 -> "可单人 · 可邀请 1 位角色"
                        launcher.maxCharacters > 1 -> "最多 " + (launcher.maxCharacters + 1) + " 人"
                        else -> "双人游戏"
                    },
                    color = launcher.accent,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
