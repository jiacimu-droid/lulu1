package com.jiacimu.lulu.games

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.LuluProfileAvatar
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.data.CharacterSettings
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private enum class ApocalypsePage { Home, Play, Settings, World, Archive }
private enum class ApocalypseAssetKind { Item, Clue, Map, Core }

private data class ApocalypseAsset(
    val id: String,
    val kind: ApocalypseAssetKind,
    val title: String,
    val detail: String,
)

private data class ApocalypseLocation(
    val id: String,
    val name: String,
    val detail: String,
    val unlocked: Boolean = true,
)

private data class ApocalypseConfig(
    val ability: String = "空间",
    val worldMode: String = "标准异变",
    val autoDelayMillis: Long = 2_800L,
)

private data class SurvivalStats(
    val food: Int = 2,
    val water: Int = 2,
    val medicine: Int = 1,
    val materials: Int = 0,
    val crystalCores: Int = 0,
    val abilityLevel: Int = 1,
    val abilityXp: Int = 0,
    val baseLevel: Int = 0,
    val baseName: String = "尚未建立",
)

private data class ApocalypseDirector(
    val phase: String,
    val location: String,
    val sceneGoal: String,
    val activeThreads: List<String>,
    val hiddenThreads: List<String>,
    val worldFacts: List<String>,
    val locations: List<ApocalypseLocation>,
    val assets: List<ApocalypseAsset>,
    val tension: Int = 2,
)

private data class ApocalypseSave(
    val id: String,
    val scene: Int,
    val partyIds: List<String>,
    val narration: String,
    val director: ApocalypseDirector,
    val stats: SurvivalStats,
    val log: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

private data class ApocalypseBeat(
    val nextDirector: ApocalypseDirector,
    val beatType: String,
    val directive: String,
    val worldDelta: String,
    val foodDelta: Int = 0,
    val waterDelta: Int = 0,
    val medicineDelta: Int = 0,
    val materialsDelta: Int = 0,
    val coresFound: Int = 0,
    val abilityXpGain: Int = 0,
    val baseDelta: Int = 0,
)

private object ApocalypseColors {
    val night = Color(0xFF111519)
    val nightSoft = Color(0xFF1D2328)
    val line = Color(0xFF343B41)
    val paper = Color(0xFFF5F0E7)
    val paperStrong = Color(0xFFFFFCF6)
    val ink = Color(0xFF282825)
    val muted = Color(0xFF77736B)
    val textOnDark = Color(0xFFF5F1E8)
    val textMutedDark = Color(0xFFB3B6B6)
    val amber = Color(0xFFE0BD69)
    val red = Color(0xFFCB7167)
    val green = Color(0xFF759C80)
    val blue = Color(0xFF7D9EB8)
    val purple = Color(0xFF9A84B6)
}

private class ApocalypseSurvivalStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("apocalypse_survival_v2", Context.MODE_PRIVATE)

    fun loadSave(): ApocalypseSave? = prefs.getString("save", null)?.let { raw ->
        runCatching { decodeSave(JSONObject(raw)) }.getOrNull()
    }

    fun save(value: ApocalypseSave) {
        prefs.edit().putString("save", encodeSave(value).toString()).apply()
    }

    fun clearSave() {
        prefs.edit().remove("save").apply()
    }

    fun loadConfig(): ApocalypseConfig = ApocalypseConfig(
        ability = prefs.getString("ability", "空间").orEmpty().ifBlank { "空间" },
        worldMode = prefs.getString("world_mode", "标准异变").orEmpty().ifBlank { "标准异变" },
        autoDelayMillis = prefs.getLong("auto_delay", 2_800L).coerceIn(1_600L, 5_000L),
    )

    fun saveConfig(config: ApocalypseConfig) {
        prefs.edit()
            .putString("ability", config.ability)
            .putString("world_mode", config.worldMode)
            .putLong("auto_delay", config.autoDelayMillis)
            .apply()
    }
}

@Composable
internal fun ApocalypseSurvivalApp(
    gameStore: LuluGameStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val storage = remember(context) { ApocalypseSurvivalStore(context) }
    val gameState by gameStore.state.collectAsState()
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    var page by remember { mutableStateOf(ApocalypsePage.Home) }
    var save by remember { mutableStateOf(storage.loadSave()) }
    var config by remember { mutableStateOf(storage.loadConfig()) }

    fun createSave(): ApocalypseSave {
        val party = gameState.selectedCharacterIds.take(4).ifEmpty {
            characters.keys.firstOrNull()?.let(::listOf).orEmpty()
        }
        return ApocalypseSave(
            id = UUID.randomUUID().toString(),
            scene = 1,
            partyIds = party,
            narration = initialApocalypseScene(party.map { characters[it]?.displayName ?: MigratedDomainStores.characters.get(it).displayName }, config.ability),
            director = initialApocalypseDirector(),
            stats = SurvivalStats(),
        )
    }

    fun enterGame() {
        val current = save ?: createSave().also {
            save = it
            storage.save(it)
        }
        if (current.partyIds.isEmpty() && gameState.selectedCharacterIds.isNotEmpty()) {
            val fixed = current.copy(partyIds = gameState.selectedCharacterIds.take(4))
            save = fixed
            storage.save(fixed)
        }
        page = ApocalypsePage.Play
    }

    fun goBack() {
        if (page == ApocalypsePage.Home) onBack() else page = ApocalypsePage.Home
    }

    BackHandler(onBack = ::goBack)

    when (page) {
        ApocalypsePage.Home -> ApocalypseHomePage(
            save = save,
            config = config,
            onBack = onBack,
            onEnter = ::enterGame,
            onSettings = { page = ApocalypsePage.Settings },
            onWorld = { page = ApocalypsePage.World },
            onArchive = { page = ApocalypsePage.Archive },
        )
        ApocalypsePage.Settings -> ApocalypseSettingsPage(
            config = config,
            selectedPartyIds = gameState.selectedCharacterIds,
            characters = characters.values.toList(),
            onBack = ::goBack,
            onSave = { nextConfig, partyIds ->
                config = nextConfig
                storage.saveConfig(nextConfig)
                if (partyIds.isNotEmpty()) gameStore.selectCharacters(partyIds.take(4))
                page = ApocalypsePage.Home
            },
        )
        ApocalypsePage.World -> ApocalypseWorldPage(config = config, onBack = ::goBack)
        ApocalypsePage.Archive -> ApocalypseArchivePage(
            save = save,
            onBack = ::goBack,
            onClear = {
                storage.clearSave()
                save = null
            },
        )
        ApocalypsePage.Play -> {
            val current = save
            if (current == null) {
                LaunchedEffect(Unit) { enterGame() }
            } else {
                ApocalypsePlayPage(
                    save = current,
                    config = config,
                    gameStore = gameStore,
                    characters = characters,
                    onBack = ::goBack,
                    onSave = { next ->
                        save = next
                        storage.save(next)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApocalypseHomePage(
    save: ApocalypseSave?,
    config: ApocalypseConfig,
    onBack: () -> Unit,
    onEnter: () -> Unit,
    onSettings: () -> Unit,
    onWorld: () -> Unit,
    onArchive: () -> Unit,
) {
    Scaffold(
        containerColor = ApocalypseColors.paper,
        topBar = {
            TopAppBar(
                title = { Text("末世求生", fontWeight = FontWeight.Black) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ApocalypseColors.paper),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = ApocalypseColors.night,
                    contentColor = ApocalypseColors.textOnDark,
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("赤潮纪元", color = ApocalypseColors.amber, fontSize = 12.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                        Text("活下去，然后决定新的世界长什么样", fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.Black)
                        Text(
                            "灾前屯物资 · 建立基地 · 异能成长 · 晶核进阶 · 群像生存",
                            color = ApocalypseColors.textMutedDark,
                            fontSize = 12.sp,
                        )
                        if (save != null) {
                            HorizontalDivider(color = ApocalypseColors.line)
                            Text(
                                "存档 · ${save.director.phase} · 第${save.scene}幕 · ${save.director.location}",
                                color = ApocalypseColors.textMutedDark,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
            item {
                ApocalypseMenuEntry(
                    icon = Icons.Outlined.PlayArrow,
                    title = "进入游戏",
                    subtitle = if (save == null) "从灾前第七日开始" else "继续第 ${save.scene} 幕",
                    onClick = onEnter,
                    emphasis = true,
                )
            }
            item { ApocalypseMenuEntry(Icons.Outlined.Settings, "设定", "角色、异能、世界强度与自动播放", onSettings) }
            item { ApocalypseMenuEntry(Icons.Outlined.Public, "世界档案", "赤潮、生态异化、人类与丧尸进化体系", onWorld) }
            item { ApocalypseMenuEntry(Icons.Outlined.History, "存档与回顾", "查看当前据点、物资、晶核和最近剧情", onArchive) }
            item {
                Surface(color = ApocalypseColors.paperStrong, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Color(0xFFE2DACE))) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = ApocalypseColors.purple)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("你的初始异能 · ${config.ability}", fontWeight = FontWeight.Bold, color = ApocalypseColors.ink)
                            Text(abilityShortDescription(config.ability), color = ApocalypseColors.muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApocalypseMenuEntry(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    emphasis: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        color = if (emphasis) Color(0xFFFFF8E5) else ApocalypseColors.paperStrong,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, if (emphasis) ApocalypseColors.amber else Color(0xFFE2DACE)),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = if (emphasis) ApocalypseColors.amber.copy(alpha = .18f) else Color(0xFFF0ECE5), shape = RoundedCornerShape(14.dp)) {
                Icon(icon, null, tint = ApocalypseColors.ink, modifier = Modifier.padding(10.dp).size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = ApocalypseColors.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = ApocalypseColors.muted, fontSize = 11.sp)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = ApocalypseColors.muted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApocalypseSettingsPage(
    config: ApocalypseConfig,
    selectedPartyIds: List<String>,
    characters: List<CharacterSettings>,
    onBack: () -> Unit,
    onSave: (ApocalypseConfig, List<String>) -> Unit,
) {
    var ability by remember(config.ability) { mutableStateOf(config.ability) }
    var worldMode by remember(config.worldMode) { mutableStateOf(config.worldMode) }
    var speed by remember(config.autoDelayMillis) { mutableLongStateOf(config.autoDelayMillis) }
    var party by remember(selectedPartyIds) { mutableStateOf(selectedPartyIds.take(4).toSet()) }
    val abilities = listOf("空间", "雷电", "火焰", "精神", "生命", "强化")
    val modes = listOf("标准异变", "资源荒年", "高危进化")

    Scaffold(
        containerColor = ApocalypseColors.paper,
        topBar = {
            TopAppBar(
                title = { Text("末世设定", fontWeight = FontWeight.Black) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                actions = {
                    TextButton(onClick = { onSave(ApocalypseConfig(ability, worldMode, speed), party.toList()) }) {
                        Text("保存", fontWeight = FontWeight.Bold, color = ApocalypseColors.ink)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ApocalypseColors.paper),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { ApocalypseSectionTitle("同行角色", "最多4人；使用露露机里已经存在的人设和头像") }
            items(characters.sortedBy { it.displayName }, key = { it.characterId }) { character ->
                val selected = character.characterId in party
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        party = when {
                            selected -> party - character.characterId
                            party.size < 4 -> party + character.characterId
                            else -> party
                        }
                    },
                    color = if (selected) Color(0xFFFFF7E1) else ApocalypseColors.paperStrong,
                    shape = RoundedCornerShape(19.dp),
                    border = BorderStroke(1.dp, if (selected) ApocalypseColors.amber else Color(0xFFE1D9CD)),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        LuluProfileAvatar(character.avatarUri, character.displayName.take(1).ifBlank { "角" }, 46)
                        Spacer(Modifier.width(12.dp))
                        Text(character.displayName.ifBlank { "未命名" }, Modifier.weight(1f), fontWeight = FontWeight.Bold, color = ApocalypseColors.ink)
                        if (selected) Icon(Icons.Outlined.Check, "已选择", tint = ApocalypseColors.ink)
                    }
                }
            }

            item { ApocalypseSectionTitle("玩家异能", "默认空间系；所有异能都必须升级，不会开局无敌") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    abilities.forEach { item ->
                        FilterChip(
                            selected = ability == item,
                            onClick = { ability = item },
                            label = { Text(item) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = ApocalypseColors.paperStrong,
                                selectedContainerColor = Color(0xFFFFF1C8),
                                selectedLabelColor = ApocalypseColors.ink,
                            ),
                        )
                    }
                }
            }
            item {
                Surface(color = ApocalypseColors.paperStrong, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Color(0xFFE1D9CD))) {
                    Text(abilityLongDescription(ability), color = ApocalypseColors.muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(14.dp))
                }
            }

            item { ApocalypseSectionTitle("世界强度", "同一套赤潮世界观，不同资源密度与进化压力") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    modes.forEach { mode ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { worldMode = mode },
                            color = if (worldMode == mode) Color(0xFFFFF7E1) else ApocalypseColors.paperStrong,
                            shape = RoundedCornerShape(17.dp),
                            border = BorderStroke(1.dp, if (worldMode == mode) ApocalypseColors.amber else Color(0xFFE1D9CD)),
                        ) {
                            Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(mode, Modifier.weight(1f), fontWeight = FontWeight.Bold, color = ApocalypseColors.ink)
                                if (worldMode == mode) Icon(Icons.Outlined.Check, null, tint = ApocalypseColors.ink)
                            }
                        }
                    }
                }
            }

            item { ApocalypseSectionTitle("自动播放", "每段文字自动翻到下一段；也可以随时手动点击") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2_000L to "快", 2_800L to "标准", 4_000L to "慢").forEach { (value, label) ->
                        FilterChip(
                            selected = speed == value,
                            onClick = { speed = value },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFFF1C8)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ApocalypseSectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = ApocalypseColors.ink, fontSize = 19.sp, fontWeight = FontWeight.Black)
        Text(subtitle, color = ApocalypseColors.muted, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApocalypseWorldPage(config: ApocalypseConfig, onBack: () -> Unit) {
    Scaffold(
        containerColor = ApocalypseColors.paper,
        topBar = {
            TopAppBar(
                title = { Text("世界档案", fontWeight = FontWeight.Black) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ApocalypseColors.paper),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            item {
                Surface(color = ApocalypseColors.night, shape = RoundedCornerShape(25.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("赤潮不是一场普通瘟疫", color = ApocalypseColors.amber, fontWeight = FontWeight.Bold)
                        Text("它是一场同时改变生态、气候与人类进化方向的行星级灾变。", color = ApocalypseColors.textOnDark, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black)
                        Text("当前模式：${config.worldMode}", color = ApocalypseColors.textMutedDark, fontSize = 11.sp)
                    }
                }
            }
            items(apocalypseWorldLore(), key = { it.first }) { (title, detail) ->
                Surface(color = ApocalypseColors.paperStrong, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Color(0xFFE1D9CD))) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(title, color = ApocalypseColors.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(detail, color = ApocalypseColors.muted, fontSize = 12.sp, lineHeight = 20.sp)
                    }
                }
            }
            item {
                ApocalypseSectionTitle("空间系成长路线", "你的默认异能不是万能仓库，而是一条需要晶核慢慢成长的路线")
            }
            items(spaceAbilityProgression()) { (level, detail) ->
                Surface(color = Color(0xFFF1EDF5), shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.Top) {
                        Text(level, color = ApocalypseColors.purple, fontWeight = FontWeight.Black, modifier = Modifier.width(54.dp))
                        Text(detail, color = ApocalypseColors.ink, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApocalypseArchivePage(
    save: ApocalypseSave?,
    onBack: () -> Unit,
    onClear: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = ApocalypseColors.paper,
        topBar = {
            TopAppBar(
                title = { Text("存档与回顾", fontWeight = FontWeight.Black) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ApocalypseColors.paper),
            )
        },
    ) { padding ->
        if (save == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("还没有存档", color = ApocalypseColors.muted)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { SurvivalStatusPanel(save.stats, save.director.phase, save.director.location) }
                item {
                    ApocalypseSectionTitle("最近剧情", "这里只保存回顾摘要；正文仍在游戏里按段阅读")
                }
                items(save.log.asReversed().take(12)) { log ->
                    Surface(color = ApocalypseColors.paperStrong, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Color(0xFFE1D9CD))) {
                        Text(log, color = ApocalypseColors.ink, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(13.dp), maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                }
                item {
                    OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.RestartAlt, null)
                        Spacer(Modifier.width(6.dp))
                        Text("重新开档")
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空末世存档？") },
            text = { Text("会删除当前末世剧情、物资、基地与异能进度。角色本身不会删除。") },
            confirmButton = {
                TextButton(onClick = { onClear(); confirmClear = false }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ApocalypsePlayPage(
    save: ApocalypseSave,
    config: ApocalypseConfig,
    gameStore: LuluGameStore,
    characters: Map<String, CharacterSettings>,
    onBack: () -> Unit,
    onSave: (ApocalypseSave) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var action by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var paragraphIndex by remember(save.scene, save.narration) { mutableIntStateOf(0) }
    var autoPlay by remember { mutableStateOf(false) }
    var showInventory by remember { mutableStateOf(false) }
    var showMap by remember { mutableStateOf(false) }
    val party = save.partyIds.map { id -> characters[id] ?: MigratedDomainStores.characters.get(id) }
    val paragraphs = remember(save.scene, save.narration) { splitStoryParagraphs(save.narration) }
    val lastParagraph = paragraphIndex >= paragraphs.lastIndex

    fun advance() {
        if (paragraphIndex < paragraphs.lastIndex) paragraphIndex += 1 else autoPlay = false
    }

    LaunchedEffect(autoPlay, paragraphIndex, save.scene) {
        if (!autoPlay || lastParagraph) return@LaunchedEffect
        val readingBonus = (paragraphs.getOrNull(paragraphIndex)?.length ?: 0).coerceAtMost(90) * 12L
        delay(config.autoDelayMillis + readingBonus)
        advance()
    }

    fun submit() {
        val clean = action.trim()
        if (clean.isBlank() || busy || !lastParagraph) return
        scope.launch {
            busy = true
            val beat = planApocalypseBeat(save, config, party, clean)
            val nextStats = applySurvivalBeat(save.stats, beat)
            val nextNarration = writeApocalypseScene(save, config, party, clean, beat, nextStats)
            nextNarration.onSuccess { text ->
                if (text.isBlank()) return@onSuccess
                val next = save.copy(
                    scene = save.scene + 1,
                    narration = text,
                    director = beat.nextDirector,
                    stats = nextStats,
                    log = (save.log + "第${save.scene}幕｜$clean\n${text.take(420)}").takeLast(80),
                    updatedAt = System.currentTimeMillis(),
                )
                onSave(next)
                val recordId = gameStore.recordExternalGame(
                    LuluGameType.RoleplayAdventure,
                    "末世求生 · 第${save.scene}幕",
                    (55 + beat.nextDirector.tension * 4).coerceAtMost(100),
                    0,
                    "${beat.nextDirector.phase}，在${beat.nextDirector.location}执行“$clean”。",
                    JSONObject()
                        .put("scene", save.scene)
                        .put("action", clean)
                        .put("phase", beat.nextDirector.phase)
                        .put("location", beat.nextDirector.location)
                        .put("cores", nextStats.crystalCores)
                        .put("abilityLevel", nextStats.abilityLevel)
                        .toString(),
                )
                gameStore.attachCharacterReply(recordId, text)
                action = ""
                paragraphIndex = 0
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().background(ApocalypseColors.night).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回", tint = ApocalypseColors.textOnDark) }
            Column(Modifier.weight(1f)) {
                Text("末世求生", color = ApocalypseColors.textOnDark, fontSize = 18.sp, fontWeight = FontWeight.Black)
                Text("${save.director.phase} · 第${save.scene}幕", color = ApocalypseColors.amber, fontSize = 10.sp)
            }
            IconButton(onClick = { showMap = true }) { Icon(Icons.Outlined.Map, "地图", tint = ApocalypseColors.textMutedDark) }
            IconButton(onClick = { showInventory = true }) { Icon(Icons.Outlined.Inventory2, "物资", tint = ApocalypseColors.textMutedDark) }
        }

        VisualNovelStage(
            party = party,
            location = save.director.location,
            tension = save.director.tension,
            stats = save.stats,
        )

        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            color = ApocalypseColors.paper,
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clickable(enabled = !busy) { advance() },
                    color = ApocalypseColors.paperStrong,
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, Color(0xFFDDD4C6)),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 17.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${save.director.location}", color = Color(0xFF8A744C), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(
                                paragraphs.getOrElse(paragraphIndex) { "……" },
                                color = ApocalypseColors.ink,
                                fontSize = 17.sp,
                                lineHeight = 28.sp,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (lastParagraph) "这一段结束 · 可以行动" else "点击继续 · ${paragraphIndex + 1}/${paragraphs.size}",
                                color = ApocalypseColors.muted,
                                fontSize = 10.sp,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { autoPlay = !autoPlay }) {
                                Icon(if (autoPlay) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (autoPlay) "暂停自动" else "自动播放", fontSize = 11.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                if (lastParagraph) {
                    OutlinedTextField(
                        value = action,
                        onValueChange = { action = it.take(600) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("你接下来想做什么？可以自由输入……") },
                        minLines = 2,
                        maxLines = 3,
                        shape = RoundedCornerShape(17.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFB79A5B),
                            unfocusedBorderColor = Color(0xFFD8CFC0),
                        ),
                    )
                    Spacer(Modifier.height(7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        QuickActionChip("搜物资") { action = "我先仔细搜集当前区域能长期保存的食物、饮水、药物和工具，不冒不必要的险。" }
                        QuickActionChip("看基地") { action = "我重新评估当前据点的水源、出入口、防御和暴露风险，看看是否值得建设基地。" }
                        QuickActionChip("练异能") { action = "我检查手里的晶核和${config.ability}异能状态，如果条件允许就安全地尝试吸收晶核升级。" }
                    }
                    Spacer(Modifier.height(7.dp))
                    Button(
                        onClick = ::submit,
                        enabled = action.isNotBlank() && !busy,
                        modifier = Modifier.fillMaxWidth().height(49.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ApocalypseColors.amber, contentColor = ApocalypseColors.ink),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(if (busy) "世界正在回应你的行动……" else "行动", fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }

    if (showInventory) {
        ModalBottomSheet(onDismissRequest = { showInventory = false }, containerColor = ApocalypseColors.paper) {
            ApocalypseInventorySheet(save)
        }
    }
    if (showMap) {
        ModalBottomSheet(onDismissRequest = { showMap = false }, containerColor = ApocalypseColors.paper) {
            ApocalypseMapSheet(save.director.locations) { location ->
                action = "我准备前往${location.name}，先观察路线和周边风险，再决定怎么进入。"
                showMap = false
            }
        }
    }
}

@Composable
private fun QuickActionChip(text: String, onClick: () -> Unit) {
    SuggestionChip(onClick = onClick, label = { Text(text, fontSize = 10.sp) })
}

@Composable
private fun VisualNovelStage(
    party: List<CharacterSettings>,
    location: String,
    tension: Int,
    stats: SurvivalStats,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(235.dp)
            .background(Brush.verticalGradient(listOf(Color(0xFF1A1E22), Color(0xFF29272A), Color(0xFF15191C)))),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(location, color = ApocalypseColors.textOnDark, fontSize = 18.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Surface(color = ApocalypseColors.red.copy(alpha = .16f), shape = RoundedCornerShape(9.dp)) {
                    Text("威胁 $tension/10", color = ApocalypseColors.red, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.Bottom,
            ) {
                party.take(4).forEachIndexed { index, character ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.offset(y = if (index % 2 == 0) 0.dp else 8.dp),
                    ) {
                        Surface(
                            modifier = Modifier.size(if (party.size <= 2) 104.dp else 80.dp),
                            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 14.dp, bottomEnd = 14.dp),
                            color = ApocalypseColors.nightSoft,
                            border = BorderStroke(1.dp, Color(0xFF3A4146)),
                        ) {
                            LuluProfileAvatar(
                                character.avatarUri,
                                character.displayName.take(1).ifBlank { "角" },
                                if (party.size <= 2) 104 else 80,
                            )
                        }
                        Text(character.displayName, color = ApocalypseColors.textMutedDark, fontSize = 9.sp, maxLines = 1)
                    }
                    if (index != party.take(4).lastIndex) Spacer(Modifier.width(if (party.size <= 2) 16.dp else 5.dp))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StageTinyStat("食 ${stats.food}")
                StageTinyStat("水 ${stats.water}")
                StageTinyStat("晶核 ${stats.crystalCores}")
                StageTinyStat("异能 Lv.${stats.abilityLevel}")
            }
        }
    }
}

@Composable
private fun StageTinyStat(text: String) {
    Surface(color = Color.Black.copy(alpha = .28f), shape = RoundedCornerShape(8.dp)) {
        Text(text, color = ApocalypseColors.textMutedDark, fontSize = 8.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
    }
}

@Composable
private fun ApocalypseInventorySheet(save: ApocalypseSave) {
    Column(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(.78f).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Text("物资与线索", color = ApocalypseColors.ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
        SurvivalStatusPanel(save.stats, save.director.phase, save.director.location)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(save.director.assets, key = { it.id }) { asset ->
                Surface(color = ApocalypseColors.paperStrong, shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, Color(0xFFE1D9CD))) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(assetIcon(asset.kind), null, tint = assetColor(asset.kind))
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text(asset.title, color = ApocalypseColors.ink, fontWeight = FontWeight.Bold)
                            Text(asset.detail, color = ApocalypseColors.muted, fontSize = 11.sp, lineHeight = 17.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun ApocalypseMapSheet(locations: List<ApocalypseLocation>, onChoose: (ApocalypseLocation) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(.72f).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("区域地图", color = ApocalypseColors.ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text("这里只显示已经确认的地点。地图不替你做选择。", color = ApocalypseColors.muted, fontSize = 11.sp)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(locations, key = { it.id }) { location ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { if (location.unlocked) onChoose(location) },
                    enabled = location.unlocked,
                    color = ApocalypseColors.paperStrong,
                    shape = RoundedCornerShape(17.dp),
                    border = BorderStroke(1.dp, Color(0xFFE1D9CD)),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (location.unlocked) Icons.Outlined.Place else Icons.Outlined.Lock, null, tint = Color(0xFF8A744C))
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text(location.name, color = ApocalypseColors.ink, fontWeight = FontWeight.Bold)
                            Text(location.detail, color = ApocalypseColors.muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun SurvivalStatusPanel(stats: SurvivalStats, phase: String, location: String) {
    Surface(color = ApocalypseColors.night, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("$phase · $location", color = ApocalypseColors.textOnDark, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatusValue("食物", stats.food.toString())
                StatusValue("饮水", stats.water.toString())
                StatusValue("药物", stats.medicine.toString())
                StatusValue("材料", stats.materials.toString())
            }
            HorizontalDivider(color = ApocalypseColors.line)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatusValue("晶核", stats.crystalCores.toString())
                StatusValue("异能", "Lv.${stats.abilityLevel}")
                StatusValue("经验", "${stats.abilityXp}/${abilityXpThreshold(stats.abilityLevel)}")
                StatusValue("基地", if (stats.baseLevel <= 0) "无" else "Lv.${stats.baseLevel}")
            }
            if (stats.baseLevel > 0) Text(stats.baseName, color = ApocalypseColors.textMutedDark, fontSize = 10.sp)
        }
    }
}

@Composable
private fun StatusValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = ApocalypseColors.amber, fontSize = 15.sp, fontWeight = FontWeight.Black)
        Text(label, color = ApocalypseColors.textMutedDark, fontSize = 8.sp)
    }
}

private suspend fun planApocalypseBeat(
    save: ApocalypseSave,
    config: ApocalypseConfig,
    party: List<CharacterSettings>,
    action: String,
): ApocalypseBeat {
    val director = save.director
    val partyPrompt = party.joinToString("\n") { "- ${it.displayName}：${it.persona.ifBlank { "遵循既有人设" }}" }
    val facts = buildString {
        appendLine("互动长篇：《末世求生·赤潮纪元》")
        appendLine("世界模式：${config.worldMode}；玩家异能：${config.ability}")
        appendLine("阶段=${director.phase}；地点=${director.location}；第${save.scene}幕；威胁=${director.tension}/10")
        appendLine("玩家资源：食物${save.stats.food} 水${save.stats.water} 药物${save.stats.medicine} 材料${save.stats.materials} 晶核${save.stats.crystalCores}")
        appendLine("异能等级=${save.stats.abilityLevel} 经验=${save.stats.abilityXp}；基地=${save.stats.baseName}/Lv.${save.stats.baseLevel}")
        appendLine("当前明线：${director.activeThreads.joinToString("｜")}")
        appendLine("隐藏长期线：${director.hiddenThreads.joinToString("｜")}")
        appendLine("已确认世界事实：${director.worldFacts.takeLast(18).joinToString("｜")}")
        appendLine("已知地点：${director.locations.joinToString("｜") { it.name }}")
        appendLine("持有物品线索：${director.assets.joinToString("｜") { it.title }}")
        appendLine("同行角色：\n$partyPrompt")
        appendLine("玩家行动：$action")
        appendLine("上一幕：\n${save.narration.takeLast(2400)}")
    }
    val instruction = """
        你是一个长篇末世互动游戏的隐藏总导演。世界必须长期自洽，玩家拥有真正自由意志。你只返回 JSON，不写正文，不加代码块。

        字段：phase, location, sceneGoal, beatType, tension, activeThreads, hiddenThreads, worldFacts, worldDelta, directive,
        foodDelta, waterDelta, medicineDelta, materialsDelta, coresFound, abilityXpGain, baseDelta,
        unlockLocations:[{id,name,detail}], discoverAssets:[{id,kind,title,detail}]。
        kind只能 item|clue|map|core。所有资源delta范围 -3..3；coresFound 0..3；abilityXpGain 0..5；baseDelta 0..1。

        世界硬规则：
        1. 赤潮是行星生态灾变，不是单纯病毒。灾前七日异常逐渐出现，不能一开场就全民异能、满街巨兽。
        2. 玩家需要真实屯物资、选择运输、找基地、维护水电药品。大量购买、偷抢、噪音、运输能力都会产生后果。
        3. 植物、动物、土地、水体、天气和人类都持续异化；生态不是只为丧尸服务。
        4. 丧尸脑内逐渐形成“源晶核”。低阶行尸多数只有混浊碎核；猎行者、变异体、统御体等越高阶晶核越纯，危险也指数上升。击杀不等于自动拿到晶核，必须剧情上实际取出才给 coresFound。
        5. 异能升级必须依赖晶核/共鸣训练。只有玩家明确吸收、炼化、练习或剧情确实触发突破时，才给 abilityXpGain。不要凭空升级。
        6. 空间系尤其不能开局无限。低级只能保存有限非生命物；以后逐步获得空间标记、短距闪位、折叠庇护、领域能力，并伴随精神负荷和空间失稳风险。
        7. 基地不是按钮升级。只有玩家真正清理、占领、修缮、补水电、防御时才能 baseDelta=1。
        8. 资源模式：标准异变=资源前期正常后期紧缺；资源荒年=补给明显更少；高危进化=高阶变异更早、更聪明，但晶核品质也可能更高。
        9. 角色必须服从既有人设。人物会犹豫、反对、犯错、有自己的关系和利益，不是跟班NPC。
        10. 每幕只推进一个核心事件。高潮之间必须有赶路、整理物资、吃饭、争执、休息、建设、关系变化等低张力段落。
        11. 重大反转必须由旧线索生长出来。不要凭空万能组织、突然失忆、无依据背叛。
        12. 玩家失败可以受伤、损失资源、错过地点、失去关系，但不要用随机秒杀破坏长篇体验。
    """.trimIndent()

    val raw = LuluAiServices.gateway.generate(
        characterId = party.firstOrNull()?.characterId ?: "lulu",
        facts = facts,
        instruction = instruction,
        source = "末世求生导演",
        title = "末世求生导演 · 第${save.scene}幕",
        temperature = 0.36,
        maxTokens = 1400,
        usage = ModelUsage.Game,
        contextMode = CompanionContextMode.PersonaAndScenario,
    ).getOrNull()?.text

    return raw?.let { parseApocalypseBeat(it, director) } ?: fallbackApocalypseBeat(save)
}

private suspend fun writeApocalypseScene(
    save: ApocalypseSave,
    config: ApocalypseConfig,
    party: List<CharacterSettings>,
    action: String,
    beat: ApocalypseBeat,
    nextStats: SurvivalStats,
): Result<String> {
    val partyPrompt = party.joinToString("\n") { "- ${it.displayName}：${it.persona.ifBlank { "遵循既有人设" }}" }
    val facts = buildString {
        appendLine("玩家行动：$action")
        appendLine("阶段：${beat.nextDirector.phase}；地点：${beat.nextDirector.location}；威胁：${beat.nextDirector.tension}/10")
        appendLine("异能：${config.ability} Lv.${nextStats.abilityLevel}；晶核=${nextStats.crystalCores}；经验=${nextStats.abilityXp}")
        appendLine("资源：食${nextStats.food} 水${nextStats.water} 药${nextStats.medicine} 材料${nextStats.materials}；基地=${nextStats.baseName}/Lv.${nextStats.baseLevel}")
        appendLine("导演动作：${beat.beatType}；目标：${beat.nextDirector.sceneGoal}")
        appendLine("世界变化：${beat.worldDelta}")
        appendLine("本幕执行指令：${beat.directive}")
        appendLine("同行者：\n$partyPrompt")
        appendLine("世界规则摘要：${apocalypseWorldLore().joinToString("｜") { "${it.first}:${it.second.take(120)}" }}")
        appendLine("上一幕：\n${save.narration.takeLast(2600)}")
    }
    val instruction = """
        写一幕高质量中文末世互动小说，约900—1500字。只输出正文，不输出选项、数值面板、解释或JSON。

        最重要的显示规则：正文必须天然切成 7—13 个短段落，每段之间用一个空行分隔。每个段落适合视觉小说点击一次读完，通常1—4句；不要把整幕写成一个巨大段落。

        文学与玩法规则：
        - 玩家刚刚输入的行动必须真正发生并有具体后果，不能偷换成编剧想让玩家做的事。
        - 场景要可见：光线、温度、气味、远处声音、建筑材质、天气、身体反应择其适用者写，不要每段机械列感官。
        - 对话要嵌入动作和停顿，人物严格保持既有人设和关系。
        - 生存细节必须有重量：搬运容量、食物保质、水源污染、药物用途、车辆油量、噪音、伤口、睡眠、天气都会影响选择。
        - 赤潮生态要逐步渗透。植物叶脉、动物行为、土壤、水汽、红雨、雾层等都可以成为危险或资源，不要只有丧尸。
        - 丧尸遇敌遵循层级和行为差异。普通行尸靠数量和噪音，高阶种才逐渐出现追踪、伏击、统御等能力。
        - 晶核和异能成长只在上层导演已经给出资源变化时写进正文，不能私自奖励。
        - ${config.ability}异能必须遵守当前等级，不得越级使用未来能力。
        - 本幕不替玩家决定下一步。结尾停在一个自然可行动的节点，不写“你可以选择A/B/C”。
    """.trimIndent()

    return LuluAiServices.gateway.generate(
        characterId = party.firstOrNull()?.characterId ?: "lulu",
        facts = facts,
        instruction = instruction,
        source = "末世求生",
        title = "末世求生 · 第${save.scene}幕",
        temperature = 0.82,
        maxTokens = 2400,
        usage = ModelUsage.Game,
        contextMode = CompanionContextMode.PersonaAndScenario,
    ).map { it.text.trim() }
}

private fun parseApocalypseBeat(raw: String, previous: ApocalypseDirector): ApocalypseBeat? = runCatching {
    val json = JSONObject(extractApocalypseJson(raw))
    val locations = json.optJSONArray("unlockLocations").apocalypseObjects { item ->
        ApocalypseLocation(
            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = item.optString("name").ifBlank { "未知地点" }.take(70),
            detail = item.optString("detail").take(220),
        )
    }
    val assets = json.optJSONArray("discoverAssets").apocalypseObjects { item ->
        ApocalypseAsset(
            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
            kind = when (item.optString("kind").lowercase()) {
                "item" -> ApocalypseAssetKind.Item
                "map" -> ApocalypseAssetKind.Map
                "core" -> ApocalypseAssetKind.Core
                else -> ApocalypseAssetKind.Clue
            },
            title = item.optString("title").ifBlank { "新发现" }.take(70),
            detail = item.optString("detail").take(320),
        )
    }
    val next = previous.copy(
        phase = json.optString("phase").ifBlank { previous.phase }.take(80),
        location = json.optString("location").ifBlank { previous.location }.take(100),
        sceneGoal = json.optString("sceneGoal").ifBlank { previous.sceneGoal }.take(240),
        activeThreads = json.optJSONArray("activeThreads").apocalypseStrings().ifEmpty { previous.activeThreads }.take(6),
        hiddenThreads = json.optJSONArray("hiddenThreads").apocalypseStrings().ifEmpty { previous.hiddenThreads }.take(6),
        worldFacts = json.optJSONArray("worldFacts").apocalypseStrings().ifEmpty { previous.worldFacts }.takeLast(20),
        locations = (previous.locations + locations).distinctBy { it.id }.takeLast(24),
        assets = (previous.assets + assets).distinctBy { it.id }.takeLast(50),
        tension = json.optInt("tension", previous.tension).coerceIn(1, 10),
    )
    ApocalypseBeat(
        nextDirector = next,
        beatType = json.optString("beatType").ifBlank { "choice" }.take(30),
        directive = json.optString("directive").ifBlank { "让玩家行动产生现实后果。" }.take(800),
        worldDelta = json.optString("worldDelta").take(400),
        foodDelta = json.optInt("foodDelta").coerceIn(-3, 3),
        waterDelta = json.optInt("waterDelta").coerceIn(-3, 3),
        medicineDelta = json.optInt("medicineDelta").coerceIn(-3, 3),
        materialsDelta = json.optInt("materialsDelta").coerceIn(-3, 3),
        coresFound = json.optInt("coresFound").coerceIn(0, 3),
        abilityXpGain = json.optInt("abilityXpGain").coerceIn(0, 5),
        baseDelta = json.optInt("baseDelta").coerceIn(0, 1),
    )
}.getOrNull()

private fun fallbackApocalypseBeat(save: ApocalypseSave): ApocalypseBeat = ApocalypseBeat(
    nextDirector = save.director.copy(
        sceneGoal = "让玩家刚刚做的事情改变当前资源、关系或风险，并保留下一步自由空间。",
        tension = if (save.scene % 5 == 0) (save.director.tension + 1).coerceAtMost(7) else save.director.tension,
    ),
    beatType = "choice",
    directive = "延续已有环境与人物，不凭空引入大反转；认真执行玩家行动。",
    worldDelta = "当前局势继续按照玩家行为演化。",
)

private fun applySurvivalBeat(stats: SurvivalStats, beat: ApocalypseBeat): SurvivalStats {
    var next = stats.copy(
        food = (stats.food + beat.foodDelta).coerceIn(0, 99),
        water = (stats.water + beat.waterDelta).coerceIn(0, 99),
        medicine = (stats.medicine + beat.medicineDelta).coerceIn(0, 99),
        materials = (stats.materials + beat.materialsDelta).coerceIn(0, 99),
        crystalCores = (stats.crystalCores + beat.coresFound).coerceIn(0, 999),
        abilityXp = (stats.abilityXp + beat.abilityXpGain).coerceAtLeast(0),
        baseLevel = (stats.baseLevel + beat.baseDelta).coerceIn(0, 5),
        baseName = if (stats.baseLevel == 0 && beat.baseDelta > 0) "临时据点" else stats.baseName,
    )
    while (next.abilityLevel < 5 && next.abilityXp >= abilityXpThreshold(next.abilityLevel)) {
        next = next.copy(
            abilityXp = next.abilityXp - abilityXpThreshold(next.abilityLevel),
            abilityLevel = next.abilityLevel + 1,
        )
    }
    return next
}

private fun abilityXpThreshold(level: Int): Int = when (level) {
    1 -> 4
    2 -> 7
    3 -> 11
    4 -> 16
    else -> 99
}

private fun initialApocalypseDirector(): ApocalypseDirector = ApocalypseDirector(
    phase = "灾前第7日",
    location = "临江市 · 旧城区公寓",
    sceneGoal = "确认七日预警是否值得相信，并在社会秩序仍正常时开始第一轮准备。",
    activeThreads = listOf(
        "七日倒计时：异常正在从网络噪声变成现实迹象",
        "生存准备：钱、运输能力、仓储空间和社会规则仍然有效",
        "据点选择：高层住宅、地下设施、郊区仓库各有代价",
    ),
    hiddenThreads = listOf(
        "赤潮源于失控的全球生态修复载体与异常太阳活动叠加，而非单一实验室病毒",
        "14:17的红色通信雪花来自赤潮载体进入电离层后的共振，不是普通黑客入侵",
        "被删除的B8防灾层保存着第一批赤潮沉降样本和一条旧时代撤离支线",
    ),
    worldFacts = listOf(
        "赤潮将在七天后进入临江市主沉降期，文明秩序会快速失效",
        "赤潮会同时改变植物、动物、微生物、人类和天气系统",
        "少数人会因源质共鸣觉醒异能，但能力存在等级、消耗与失控风险",
        "感染者的大脑会在进化过程中形成源晶核，越高阶越纯净",
        "灾前社会仍然正常：金钱、法律、监控、交通、舆论和人际信任都有现实约束",
    ),
    locations = listOf(
        ApocalypseLocation("home", "旧城区公寓", "当前住所。便于隐藏准备，但储物和防御能力有限。"),
        ApocalypseLocation("market", "城南综合市场", "食品、水、五金和日用品齐全；大量采购会引起注意。"),
        ApocalypseLocation("hospital", "临江二院", "药品与急救资源丰富，也最早出现无法解释的发热和神经病例。"),
        ApocalypseLocation("warehouse", "西郊物流园", "仓储、货车与冷库集中；灾后可能成为多方争夺的补给点。"),
        ApocalypseLocation("metro", "旧城地铁换乘站", "公开图纸只到B4，但旧施工档案里存在被抹去的B8。"),
    ),
    assets = listOf(
        ApocalypseAsset("warning", ApocalypseAssetKind.Clue, "七日预警", "无法追溯来源的消息：七天后赤潮抵达临江市，不要去官方公布的第一避难区。"),
        ApocalypseAsset("b8_map", ApocalypseAssetKind.Map, "B8施工图残片", "旧城地铁公开线路以下还存在一层被红笔圈出的B8防灾层。"),
    ),
    tension = 2,
)

private fun initialApocalypseScene(partyNames: List<String>, ability: String): String {
    val names = partyNames.joinToString("、").ifBlank { "你想保护的人" }
    return """
        下午两点十七分，手机信号从满格瞬间掉到零。不是普通断网——屏幕中央浮出一层细得像血丝的红色雪花，持续三秒，又忽然消失。

        一条没有号码、没有应用图标、无法转发也无法截图的文字压在所有窗口上方：“七日后，赤潮抵达临江市。”

        第二行更短：“不要去官方公布的第一避难区。”

        第三行停留了足足十秒：“如果你还想让${names}活下来，从今天开始准备。”

        消息消失后，相册里多出一张旧城地铁施工图。公开线路只到地下四层，可图纸最底下还有一个被红笔圈起来的“B8”。

        你伸手去放大图片时，指尖前方的空气像水面一样凹了一下。钥匙从桌边滑落，却没有砸到地板——它在离你掌心半寸的位置凭空消失。

        下一秒，一个从未存在过的狭小空间在意识里张开。很小，只有储物柜大小，不能容纳生命，边界还在轻微震颤。你的${ability}异能，似乎比赤潮本身更早醒来了一步。

        窗外仍有人为了停车位按喇叭，楼下便利店循环播放第二件半价。新闻主播在谈周末高温，城市没有任何世界末日的样子。

        七天。秩序还在，钱还能花，车还能开，超市货架还是满的。真正稀缺的不是物资，而是你究竟愿意相信这条消息到什么程度。
    """.trimIndent()
}

private fun splitStoryParagraphs(text: String): List<String> {
    val normalized = text.replace("\r\n", "\n").trim()
    if (normalized.isBlank()) return listOf("……")
    val blocks = normalized.split(Regex("\\n\\s*\\n+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
    if (blocks.size > 1) return blocks
    return normalized.split(Regex("(?<=[。！？!?])\\s*"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .chunked(3)
        .map { it.joinToString("") }
        .ifEmpty { listOf(normalized) }
}

private fun apocalypseWorldLore(): List<Pair<String, String>> = listOf(
    "灾变起源" to "二十年前，各国为了修复沙漠化、海洋缺氧和极端气候，逐步部署一种可在空气、水体和土壤中自复制的生态修复载体。它原本只负责携带微生物群与纳米级营养骨架。一次异常太阳活动改变了全球高层大气电荷环境，分散多年的载体开始同步共振、自我重组。人类后来把这种覆盖天空与降水系统的红褐色沉降带称为“赤潮”。它不是某一个人按下按钮造成的，也无法靠摧毁一座实验室结束。",
    "植物" to "最初只是叶脉泛红、夜间气孔异常开放。随后藤本和根系会追逐热源、电流与富含矿物的建筑裂缝，部分植物产生致幻或神经毒性花粉，也有少数形成能净化低浓度污染水的“净生株”。末世里植物既是食物、药材和过滤材料，也可能把整栋建筑变成捕食结构。",
    "动物" to "动物并非统一变成怪物。犬科与鸦科最先出现群体协同和异常方向感，小型啮齿类繁殖加速，部分大型动物会形成领地性极强的异变种。动物仍然会饥饿、受伤、繁殖和迁徙，因此生态链本身会变化；安全区也可能因为一群迁徙兽被迫搬迁。",
    "土地与水" to "赤潮沉降后，裸露土壤会形成红锈色生物膜，重金属和盐分迁移速度加快。地表水最先失去可靠性，地下深层水源、封闭水塔和经过维护的过滤系统因此成为基地核心资产。某些土壤会长出能吸收源质的结晶菌毯，既可采集，也会吸引感染者。",
    "气候" to "赤雨、逆温红雾、无雨雷暴和骤冷热浪构成灾后的“赤潮天气”。不同天气会改变感染者活跃度、无线电距离、植物孢粉浓度和异能稳定性。天气不是背景贴图，而是一种持续改变路线和基地安全的系统。",
    "人类与异能" to "多数人不会觉醒。少数人在赤潮源质与神经系统发生稳定共鸣后获得能力，主要分空间、自然、精神、生命、强化与极少见的规则型。异能消耗体力与精神稳定度，越级使用会出现头痛、失忆片段、感官错位甚至“鸣蚀”。人类势力会围绕水源、晶核、药品、技术和异能者产生新的秩序。",
    "感染者" to "感染者并非一天内完成进化。早期是行动迟缓、依赖声音和血味的行尸；持续吸收源质后可出现速度更快的猎行者、具有骨甲或感官特化的变异体、能影响低阶感染者的统御体，以及极少数改变整片区域生态的灾厄级个体。越高阶越少，但会学习环境与人类习惯。",
    "源晶核" to "感染者脑内会逐渐形成源质结晶。普通行尸多为混浊碎核，能量少且杂质高；高阶感染者晶核更完整，可用于异能升级、设备供能和研究。晶核不是金币：取核要接近尸体，保存不当会继续释放污染，吸收过量也会造成鸣蚀。",
    "基地" to "真正的基地至少同时解决水、食物、睡眠、医疗、排污、出入口、防火和撤退路线。越大的据点越安全也越显眼，需要更多人口维护。玩家可以从临时房间发展为加固据点、复合基地，再到拥有净水、发电、种植与预警体系的聚居地，但每一步都需要真实材料和人。",
)

private fun spaceAbilityProgression(): List<Pair<String, String>> = listOf(
    "Lv.1" to "小型静态空间：只能收纳有限非生命物体，体积越大精神负荷越明显。活物无法进入。",
    "Lv.2" to "空间标记：可在短距离内对已经触碰过的小型物体进行快速收取/取出，储物边界更稳定。",
    "Lv.3" to "短距闪位：在视线或明确空间标记之间进行极短距离位移；连续使用会眩晕、空间感错乱。",
    "Lv.4" to "折叠庇护：短时间展开可供少数人躲避的折叠空间，但维持成本高，无法永久取代基地。",
    "Lv.5" to "空间领域：在有限范围内干预位置、距离与通道。强度极高，也最容易引发鸣蚀和空间坍缩。",
)

private fun abilityShortDescription(ability: String): String = when (ability) {
    "空间" -> "有限储物起步，逐级成长为空间标记、闪位、折叠庇护与领域。"
    "雷电" -> "从微弱放电与电感知起步，逐渐获得蓄能、导流和范围控制。"
    "火焰" -> "先控制小范围燃烧与耐热，再提升温度、形态和持续输出。"
    "精神" -> "从情绪感知和专注干扰开始，高阶才能触及群体精神领域。"
    "生命" -> "强化恢复和生命感知，治疗越严重的创伤代价越高。"
    else -> "强化肌力、反应和耐受，升级后才逐渐突破正常人体极限。"
}

private fun abilityLongDescription(ability: String): String = when (ability) {
    "空间" -> "空间系默认从“储物柜大小的非生命储藏空间”开始。它解决运输问题，却不能凭空制造物资，也不能一开始装活人或无限囤货。晶核共鸣后逐步解锁空间标记、短距闪位、折叠庇护和领域。"
    "雷电" -> "雷电系初期只能制造有限电流、感知附近电器与导体。升级后才能稳定蓄能、远距离放电、干扰设备和形成电场。潮湿环境既能增强传导，也会提高误伤风险。"
    "火焰" -> "火焰系早期更像可控火源与耐热能力，不是无限火球。氧气、可燃物和封闭空间都会影响战斗与基地安全，高阶才能塑形和形成持续火域。"
    "精神" -> "精神系先从情绪与注意力异常开始。读取、干扰和群体控制都需要更高等级，且面对高阶感染者时可能遭到精神反冲。"
    "生命" -> "生命系能感知生命状态并促进恢复，但无法无代价复活或瞬间治愈重伤。伤势越深，消耗和恢复期越长。"
    else -> "强化系把身体当成成长载体，从肌力、反应、耐受开始逐层突破。它仍然需要食物、睡眠和恢复，过度透支同样会留下伤害。"
}

private fun assetIcon(kind: ApocalypseAssetKind) = when (kind) {
    ApocalypseAssetKind.Item -> Icons.Outlined.Inventory2
    ApocalypseAssetKind.Clue -> Icons.Outlined.Search
    ApocalypseAssetKind.Map -> Icons.Outlined.Map
    ApocalypseAssetKind.Core -> Icons.Outlined.AutoAwesome
}

private fun assetColor(kind: ApocalypseAssetKind): Color = when (kind) {
    ApocalypseAssetKind.Item -> ApocalypseColors.green
    ApocalypseAssetKind.Clue -> ApocalypseColors.blue
    ApocalypseAssetKind.Map -> ApocalypseColors.amber
    ApocalypseAssetKind.Core -> ApocalypseColors.purple
}

private fun encodeSave(value: ApocalypseSave): JSONObject = JSONObject()
    .put("id", value.id)
    .put("scene", value.scene)
    .put("partyIds", JSONArray(value.partyIds))
    .put("narration", value.narration)
    .put("director", encodeDirector(value.director))
    .put("stats", encodeStats(value.stats))
    .put("log", JSONArray(value.log))
    .put("updatedAt", value.updatedAt)

private fun decodeSave(json: JSONObject): ApocalypseSave = ApocalypseSave(
    id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
    scene = json.optInt("scene", 1).coerceAtLeast(1),
    partyIds = json.optJSONArray("partyIds").apocalypseStrings(),
    narration = json.optString("narration").ifBlank { initialApocalypseScene(emptyList(), "空间") },
    director = json.optJSONObject("director")?.let(::decodeDirector) ?: initialApocalypseDirector(),
    stats = json.optJSONObject("stats")?.let(::decodeStats) ?: SurvivalStats(),
    log = json.optJSONArray("log").apocalypseStrings(),
    updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
)

private fun encodeDirector(value: ApocalypseDirector): JSONObject = JSONObject()
    .put("phase", value.phase)
    .put("location", value.location)
    .put("sceneGoal", value.sceneGoal)
    .put("activeThreads", JSONArray(value.activeThreads))
    .put("hiddenThreads", JSONArray(value.hiddenThreads))
    .put("worldFacts", JSONArray(value.worldFacts))
    .put("locations", JSONArray().apply {
        value.locations.forEach { location ->
            put(JSONObject().put("id", location.id).put("name", location.name).put("detail", location.detail).put("unlocked", location.unlocked))
        }
    })
    .put("assets", JSONArray().apply {
        value.assets.forEach { asset ->
            put(JSONObject().put("id", asset.id).put("kind", asset.kind.name).put("title", asset.title).put("detail", asset.detail))
        }
    })
    .put("tension", value.tension)

private fun decodeDirector(json: JSONObject): ApocalypseDirector = ApocalypseDirector(
    phase = json.optString("phase", "灾前第7日"),
    location = json.optString("location", "临江市 · 旧城区公寓"),
    sceneGoal = json.optString("sceneGoal").ifBlank { initialApocalypseDirector().sceneGoal },
    activeThreads = json.optJSONArray("activeThreads").apocalypseStrings().ifEmpty { initialApocalypseDirector().activeThreads },
    hiddenThreads = json.optJSONArray("hiddenThreads").apocalypseStrings().ifEmpty { initialApocalypseDirector().hiddenThreads },
    worldFacts = json.optJSONArray("worldFacts").apocalypseStrings().ifEmpty { initialApocalypseDirector().worldFacts },
    locations = json.optJSONArray("locations").apocalypseObjects { item ->
        ApocalypseLocation(item.optString("id"), item.optString("name"), item.optString("detail"), item.optBoolean("unlocked", true))
    }.ifEmpty { initialApocalypseDirector().locations },
    assets = json.optJSONArray("assets").apocalypseObjects { item ->
        ApocalypseAsset(
            item.optString("id"),
            runCatching { ApocalypseAssetKind.valueOf(item.optString("kind")) }.getOrDefault(ApocalypseAssetKind.Clue),
            item.optString("title"),
            item.optString("detail"),
        )
    }.ifEmpty { initialApocalypseDirector().assets },
    tension = json.optInt("tension", 2).coerceIn(1, 10),
)

private fun encodeStats(value: SurvivalStats): JSONObject = JSONObject()
    .put("food", value.food)
    .put("water", value.water)
    .put("medicine", value.medicine)
    .put("materials", value.materials)
    .put("crystalCores", value.crystalCores)
    .put("abilityLevel", value.abilityLevel)
    .put("abilityXp", value.abilityXp)
    .put("baseLevel", value.baseLevel)
    .put("baseName", value.baseName)

private fun decodeStats(json: JSONObject): SurvivalStats = SurvivalStats(
    food = json.optInt("food", 2).coerceAtLeast(0),
    water = json.optInt("water", 2).coerceAtLeast(0),
    medicine = json.optInt("medicine", 1).coerceAtLeast(0),
    materials = json.optInt("materials", 0).coerceAtLeast(0),
    crystalCores = json.optInt("crystalCores", 0).coerceAtLeast(0),
    abilityLevel = json.optInt("abilityLevel", 1).coerceIn(1, 5),
    abilityXp = json.optInt("abilityXp", 0).coerceAtLeast(0),
    baseLevel = json.optInt("baseLevel", 0).coerceIn(0, 5),
    baseName = json.optString("baseName", "尚未建立"),
)

private fun extractApocalypseJson(raw: String): String {
    val value = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val start = value.indexOf('{')
    val end = value.lastIndexOf('}')
    return if (start >= 0 && end > start) value.substring(start, end + 1) else value
}

private fun JSONArray?.apocalypseStrings(): List<String> = buildList {
    val array = this@apocalypseStrings ?: return@buildList
    for (index in 0 until array.length()) array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
}

private fun <T> JSONArray?.apocalypseObjects(mapper: (JSONObject) -> T): List<T> = buildList {
    val array = this@apocalypseObjects ?: return@buildList
    for (index in 0 until array.length()) {
        array.optJSONObject(index)?.let { item -> runCatching { mapper(item) }.getOrNull()?.let(::add) }
    }
}
