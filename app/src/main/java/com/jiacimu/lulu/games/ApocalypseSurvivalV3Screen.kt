package com.jiacimu.lulu.games

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
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private object ApocalypseV3Colors {
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

@Composable
internal fun ApocalypseSurvivalAppV3(
    gameStore: LuluGameStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val storage = remember(context) { ApocalypseSurvivalV3Store(context) }
    val gameState by gameStore.state.collectAsState()
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    var page by remember { mutableStateOf(ApocalypseV3Page.Home) }
    var save by remember { mutableStateOf(storage.loadSave()) }
    var config by remember { mutableStateOf(storage.loadConfig()) }

    fun createSave(): ApocalypseV3Save {
        val party = gameState.selectedCharacterIds.take(4).ifEmpty {
            characters.keys.firstOrNull()?.let(::listOf).orEmpty()
        }
        return ApocalypseV3Save(
            id = UUID.randomUUID().toString(),
            scene = 1,
            partyIds = party,
            narration = initialApocalypseV3Scene(
                party.map { id -> characters[id]?.displayName ?: MigratedDomainStores.characters.get(id).displayName },
            ),
            director = initialApocalypseV3Director(),
            stats = ApocalypseV3Stats(),
        )
    }

    fun enterGame() {
        var current = save ?: createSave().also {
            save = it
            storage.save(it)
        }
        if (current.partyIds.isEmpty() && gameState.selectedCharacterIds.isNotEmpty()) {
            current = current.copy(partyIds = gameState.selectedCharacterIds.take(4))
            save = current
            storage.save(current)
        }
        page = ApocalypseV3Page.Play
    }

    fun goBack() {
        if (page == ApocalypseV3Page.Home) onBack() else page = ApocalypseV3Page.Home
    }

    BackHandler(onBack = ::goBack)

    when (page) {
        ApocalypseV3Page.Home -> ApocalypseV3HomePage(
            save = save,
            config = config,
            onBack = onBack,
            onEnter = ::enterGame,
            onSettings = { page = ApocalypseV3Page.Settings },
            onWorld = { page = ApocalypseV3Page.World },
            onArchive = { page = ApocalypseV3Page.Archive },
        )

        ApocalypseV3Page.Settings -> ApocalypseV3SettingsPage(
            config = config,
            selectedPartyIds = gameState.selectedCharacterIds,
            characters = characters.values.toList(),
            onBack = ::goBack,
            onSave = { nextConfig, partyIds ->
                config = nextConfig
                storage.saveConfig(nextConfig)
                gameStore.selectCharacters(partyIds.take(4))
                val current = save
                if (current != null && current.partyIds != partyIds.take(4)) {
                    val updated = current.copy(partyIds = partyIds.take(4), updatedAt = System.currentTimeMillis())
                    save = updated
                    storage.save(updated)
                }
                page = ApocalypseV3Page.Home
            },
        )

        ApocalypseV3Page.World -> ApocalypseV3WorldPage(config, ::goBack)

        ApocalypseV3Page.Archive -> ApocalypseV3ArchivePage(
            save = save,
            onBack = ::goBack,
            onClear = {
                storage.clearSave()
                save = null
            },
        )

        ApocalypseV3Page.Play -> {
            val current = save
            if (current == null) {
                LaunchedEffect(Unit) { enterGame() }
            } else {
                ApocalypseV3PlayPage(
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

@Composable
private fun ApocalypseV3HomePage(
    save: ApocalypseV3Save?,
    config: ApocalypseV3Config,
    onBack: () -> Unit,
    onEnter: () -> Unit,
    onSettings: () -> Unit,
    onWorld: () -> Unit,
    onArchive: () -> Unit,
) {
    Scaffold(
        containerColor = ApocalypseV3Colors.paper,
        topBar = {
            TopAppBar(
                title = { Text("末世求生", fontWeight = FontWeight.Black) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ApocalypseV3Colors.paper),
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
                    color = ApocalypseV3Colors.night,
                    contentColor = ApocalypseV3Colors.textOnDark,
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("赤潮纪元", color = ApocalypseV3Colors.amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("你不是最强壮的人，但你必须活成这个世界的主角。", fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Black)
                        Text("灾前屯物资 · 空间金手指 · 同行异能 · 建立基地 · 晶核进阶 · 长篇导演", color = ApocalypseV3Colors.textMutedDark, fontSize = 11.sp)
                        if (save != null) {
                            HorizontalDivider(color = ApocalypseV3Colors.line)
                            Text("存档 · ${save.director.phase} · 第${save.scene}幕 · ${save.director.location}", color = ApocalypseV3Colors.textMutedDark, fontSize = 11.sp)
                        }
                    }
                }
            }
            item { ApocalypseV3MenuEntry(Icons.Outlined.PlayArrow, "进入游戏", if (save == null) "从灾前第七日开始" else "继续第 ${save.scene} 幕", onEnter, true) }
            item { ApocalypseV3MenuEntry(Icons.Outlined.Settings, "设定", "选择同行角色、同行异能与世界强度", onSettings) }
            item { ApocalypseV3MenuEntry(Icons.Outlined.Public, "世界档案", "赤潮生态、异能社会、丧尸进化与空间成长", onWorld) }
            item { ApocalypseV3MenuEntry(Icons.Outlined.History, "存档与回顾", "查看基地、物资、晶核与最近剧情", onArchive) }
            item {
                Surface(color = Color(0xFFF1EDF5), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Color(0xFFDCD2E7))) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("你的固定异能 · 空间", fontWeight = FontWeight.Black, color = ApocalypseV3Colors.ink)
                        Text("开局48m³近停滞储物；后续会长出裂隙刃、闪位、空间锁和领域。你的体能偏弱，所以这份异能必须同时承担生存优势和后期攻击能力。", color = ApocalypseV3Colors.muted, fontSize = 11.sp, lineHeight = 17.sp)
                    }
                }
            }
            item {
                val configured = config.partyAbilities.values.count { it.abilityId != "none" }
                Text("同行异能已配置 $configured 个角色", color = ApocalypseV3Colors.muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable
private fun ApocalypseV3MenuEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    emphasis: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        color = if (emphasis) Color(0xFFFFF8E5) else ApocalypseV3Colors.paperStrong,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, if (emphasis) ApocalypseV3Colors.amber else Color(0xFFE2DACE)),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = if (emphasis) ApocalypseV3Colors.amber.copy(alpha = .18f) else Color(0xFFF0ECE5), shape = RoundedCornerShape(14.dp)) {
                Icon(icon, null, tint = ApocalypseV3Colors.ink, modifier = Modifier.padding(10.dp).size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = ApocalypseV3Colors.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = ApocalypseV3Colors.muted, fontSize = 11.sp)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = ApocalypseV3Colors.muted)
        }
    }
}

@Composable
private fun ApocalypseV3SettingsPage(
    config: ApocalypseV3Config,
    selectedPartyIds: List<String>,
    characters: List<CharacterSettings>,
    onBack: () -> Unit,
    onSave: (ApocalypseV3Config, List<String>) -> Unit,
) {
    var worldMode by remember(config.worldMode) { mutableStateOf(config.worldMode) }
    var speed by remember(config.autoDelayMillis) { mutableLongStateOf(config.autoDelayMillis) }
    var party by remember(selectedPartyIds) { mutableStateOf(selectedPartyIds.take(4).toSet()) }
    var choices by remember(config.partyAbilities) { mutableStateOf(config.partyAbilities) }
    var editingCharacterId by remember { mutableStateOf<String?>(null) }
    val modes = listOf("标准异变", "资源荒年", "高危进化")

    Scaffold(
        containerColor = ApocalypseV3Colors.paper,
        topBar = {
            TopAppBar(
                title = { Text("末世设定", fontWeight = FontWeight.Black) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                actions = {
                    TextButton(
                        onClick = { onSave(ApocalypseV3Config(worldMode, speed, choices), party.toList()) },
                    ) { Text("保存", color = ApocalypseV3Colors.ink, fontWeight = FontWeight.Bold) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ApocalypseV3Colors.paper),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                ApocalypseV3SectionTitle("你的异能", "固定为空间系，不在这里修改")
                Spacer(Modifier.height(8.dp))
                Surface(color = Color(0xFFF1EDF5), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Color(0xFFD9CEE5))) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("空间 · 主角级高稳定共鸣", color = ApocalypseV3Colors.purple, fontWeight = FontWeight.Black, fontSize = 17.sp)
                        Text("开局48m³近停滞储物，后期必定演化攻击路线。你体能偏弱，因此空间系会更强调快速取物、距离控制、闪位和空间切割。", color = ApocalypseV3Colors.ink, fontSize = 12.sp, lineHeight = 19.sp)
                    }
                }
            }

            item { ApocalypseV3SectionTitle("同行角色与异能", "最多4人；普通人也是真实且重要的选择，每个同行者都可以单独设定异能和分化方向") }
            items(characters.sortedBy { it.displayName }, key = { it.characterId }) { character ->
                val selected = character.characterId in party
                val choice = choices[character.characterId] ?: ApocalypseAbilityChoice()
                val definition = companionAbilityDefinition(choice)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = if (selected) Color(0xFFFFF7E1) else ApocalypseV3Colors.paperStrong,
                    shape = RoundedCornerShape(19.dp),
                    border = BorderStroke(1.dp, if (selected) ApocalypseV3Colors.amber else Color(0xFFE1D9CD)),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LuluProfileAvatar(character.avatarUri, character.displayName.take(1).ifBlank { "角" }, 46)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(character.displayName.ifBlank { "未命名" }, fontWeight = FontWeight.Bold, color = ApocalypseV3Colors.ink)
                                if (selected) {
                                    Text("${definition.rarity.label} · ${definition.name} · 潜力${definition.potential}", color = abilityRarityColor(definition.rarity), fontSize = 10.sp)
                                }
                            }
                            Checkbox(
                                checked = selected,
                                onCheckedChange = { checked ->
                                    party = when {
                                        !checked -> party - character.characterId
                                        party.size < 4 -> party + character.characterId
                                        else -> party
                                    }
                                },
                            )
                        }
                        if (selected) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("分化：${choice.branch}", color = ApocalypseV3Colors.ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    Text(definition.description, color = ApocalypseV3Colors.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton(onClick = { editingCharacterId = character.characterId }) {
                                    Text("设置异能", color = ApocalypseV3Colors.ink, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            item { ApocalypseV3SectionTitle("世界强度", "不改变世界观，只改变资源压力和高阶变异出现节奏") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    modes.forEach { mode ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { worldMode = mode },
                            color = if (worldMode == mode) Color(0xFFFFF7E1) else ApocalypseV3Colors.paperStrong,
                            shape = RoundedCornerShape(17.dp),
                            border = BorderStroke(1.dp, if (worldMode == mode) ApocalypseV3Colors.amber else Color(0xFFE1D9CD)),
                        ) {
                            Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(mode, Modifier.weight(1f), fontWeight = FontWeight.Bold, color = ApocalypseV3Colors.ink)
                                if (worldMode == mode) Icon(Icons.Outlined.Check, null, tint = ApocalypseV3Colors.ink)
                            }
                        }
                    }
                }
            }

            item { ApocalypseV3SectionTitle("自动播放", "自动翻到下一段；手动时仍然可以前后查看") }
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

    val editingId = editingCharacterId
    if (editingId != null) {
        val character = characters.firstOrNull { it.characterId == editingId }
        val initialChoice = choices[editingId] ?: ApocalypseAbilityChoice()
        ApocalypseAbilityPickerSheet(
            characterName = character?.displayName.orEmpty().ifBlank { "同行角色" },
            initialChoice = initialChoice,
            onDismiss = { editingCharacterId = null },
            onConfirm = { nextChoice ->
                choices = choices + (editingId to nextChoice)
                editingCharacterId = null
            },
        )
    }
}

@Composable
private fun ApocalypseAbilityPickerSheet(
    characterName: String,
    initialChoice: ApocalypseAbilityChoice,
    onDismiss: () -> Unit,
    onConfirm: (ApocalypseAbilityChoice) -> Unit,
) {
    val catalog = remember { apocalypseCompanionAbilityCatalog() }
    var choice by remember(initialChoice) { mutableStateOf(initialChoice) }
    val current = companionAbilityDefinition(choice)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = ApocalypseV3Colors.paper) {
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(.88f).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("$characterName · 异能", color = ApocalypseV3Colors.ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Surface(color = Color(0xFFFFF8E5), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, ApocalypseV3Colors.amber)) {
                Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("${current.rarity.label} · ${current.name} · 潜力${current.potential}", color = ApocalypseV3Colors.ink, fontWeight = FontWeight.Bold)
                    Text(current.description, color = ApocalypseV3Colors.muted, fontSize = 11.sp, lineHeight = 17.sp)
                    if (current.branches.size > 1) {
                        Text("分化方向", color = ApocalypseV3Colors.muted, fontSize = 10.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            current.branches.forEach { branch ->
                                FilterChip(
                                    selected = choice.branch == branch,
                                    onClick = { choice = choice.copy(branch = branch) },
                                    label = { Text(branch, fontSize = 10.sp) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFFE9A9)),
                                )
                            }
                        }
                    }
                }
            }
            Text("能力库", color = ApocalypseV3Colors.ink, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(catalog, key = { it.id }) { ability ->
                    val selected = ability.id == choice.abilityId
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            choice = ApocalypseAbilityChoice(
                                abilityId = ability.id,
                                branch = ability.branches.firstOrNull() ?: ability.name,
                            )
                        },
                        color = if (selected) Color(0xFFFFF7E1) else ApocalypseV3Colors.paperStrong,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, if (selected) ApocalypseV3Colors.amber else Color(0xFFE1D9CD)),
                    ) {
                        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = abilityRarityColor(ability.rarity).copy(alpha = .13f), shape = RoundedCornerShape(10.dp)) {
                                Text(ability.rarity.label, color = abilityRarityColor(ability.rarity), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp))
                            }
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(ability.name, color = ApocalypseV3Colors.ink, fontWeight = FontWeight.Bold)
                                Text("潜力${ability.potential} · ${ability.branches.joinToString(" / ")}", color = ApocalypseV3Colors.muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (selected) Icon(Icons.Outlined.Check, null, tint = ApocalypseV3Colors.ink)
                        }
                    }
                }
            }
            Button(
                onClick = { onConfirm(choice) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ApocalypseV3Colors.night, contentColor = Color.White),
            ) { Text("确定", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun ApocalypseV3WorldPage(config: ApocalypseV3Config, onBack: () -> Unit) {
    Scaffold(
        containerColor = ApocalypseV3Colors.paper,
        topBar = {
            TopAppBar(
                title = { Text("世界档案", fontWeight = FontWeight.Black) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ApocalypseV3Colors.paper),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            item {
                Surface(color = ApocalypseV3Colors.night, shape = RoundedCornerShape(25.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("赤潮不是一场普通瘟疫", color = ApocalypseV3Colors.amber, fontWeight = FontWeight.Bold)
                        Text("它会重写生态、气候、感染者、人类异能与文明秩序。", color = ApocalypseV3Colors.textOnDark, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black)
                        Text("当前模式：${config.worldMode}", color = ApocalypseV3Colors.textMutedDark, fontSize = 11.sp)
                    }
                }
            }
            items(apocalypseWorldLoreV3(), key = { it.first }) { (title, detail) ->
                Surface(color = ApocalypseV3Colors.paperStrong, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Color(0xFFE1D9CD))) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(title, color = ApocalypseV3Colors.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(detail, color = ApocalypseV3Colors.muted, fontSize = 12.sp, lineHeight = 20.sp)
                    }
                }
            }
            item { ApocalypseV3SectionTitle("你的空间成长", "这是主角金手指，不是仓库工具人路线") }
            items(playerSpaceProgression()) { (level, detail) ->
                Surface(color = Color(0xFFF1EDF5), shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.Top) {
                        Text(level, color = ApocalypseV3Colors.purple, fontWeight = FontWeight.Black, modifier = Modifier.width(96.dp))
                        Text(detail, color = ApocalypseV3Colors.ink, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
            item { ApocalypseV3SectionTitle("宏观剧情骨架", "导演提前维护大方向，但玩家可以真实改变、绕开甚至摧毁原计划") }
            items(defaultApocalypseLongTermPlan()) { item ->
                Surface(color = ApocalypseV3Colors.paperStrong, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color(0xFFE1D9CD))) {
                    Text(item, color = ApocalypseV3Colors.ink, fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
}

@Composable
private fun ApocalypseV3ArchivePage(
    save: ApocalypseV3Save?,
    onBack: () -> Unit,
    onClear: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = ApocalypseV3Colors.paper,
        topBar = {
            TopAppBar(
                title = { Text("存档与回顾", fontWeight = FontWeight.Black) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ApocalypseV3Colors.paper),
            )
        },
    ) { padding ->
        if (save == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("还没有存档", color = ApocalypseV3Colors.muted)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { ApocalypseV3StatusPanel(save.stats, save.director.phase, save.director.location) }
                item { ApocalypseV3SectionTitle("最近剧情", "保留回顾摘要，正文仍在游戏里按段查看") }
                items(save.log.asReversed().take(14)) { log ->
                    Surface(color = ApocalypseV3Colors.paperStrong, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Color(0xFFE1D9CD))) {
                        Text(log, color = ApocalypseV3Colors.ink, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(13.dp), maxLines = 7, overflow = TextOverflow.Ellipsis)
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
            text = { Text("会删除当前末世剧情、物资、基地与空间异能进度。露露机角色本身不会删除。") },
            confirmButton = { TextButton(onClick = { onClear(); confirmClear = false }) { Text("清空") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ApocalypseV3PlayPage(
    save: ApocalypseV3Save,
    config: ApocalypseV3Config,
    gameStore: LuluGameStore,
    characters: Map<String, CharacterSettings>,
    onBack: () -> Unit,
    onSave: (ApocalypseV3Save) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var action by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pageIndex by remember(save.scene, save.narration) { mutableIntStateOf(0) }
    var autoPlay by remember { mutableStateOf(false) }
    var showInventory by remember { mutableStateOf(false) }
    var showMap by remember { mutableStateOf(false) }
    val party = save.partyIds.map { id -> characters[id] ?: MigratedDomainStores.characters.get(id) }
    val pages = remember(save.scene, save.narration) { splitApocalypseStoryPages(save.narration) }
    val lastPage = pageIndex >= pages.lastIndex

    fun nextPage() {
        if (pageIndex < pages.lastIndex) pageIndex += 1 else autoPlay = false
    }

    fun previousPage() {
        if (pageIndex > 0) {
            autoPlay = false
            pageIndex -= 1
        }
    }

    LaunchedEffect(autoPlay, pageIndex, save.scene) {
        if (!autoPlay || lastPage) return@LaunchedEffect
        val readingBonus = (pages.getOrNull(pageIndex)?.length ?: 0).coerceAtMost(112) * 14L
        delay(config.autoDelayMillis + readingBonus)
        nextPage()
    }

    fun submit() {
        val clean = action.trim()
        if (clean.isBlank() || busy || !lastPage) return
        scope.launch {
            busy = true
            val beat = planApocalypseV3Beat(save, config, party, clean)
            val nextStats = applyApocalypseV3Beat(save.stats, beat)
            writeApocalypseV3Scene(save, config, party, clean, beat, nextStats)
                .onSuccess { text ->
                    if (text.isBlank()) return@onSuccess
                    val next = save.copy(
                        scene = save.scene + 1,
                        narration = text,
                        director = beat.nextDirector,
                        stats = nextStats,
                        log = (save.log + "第${save.scene}幕｜$clean\n${text.take(460)}").takeLast(100),
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
                            .put("spaceLevel", nextStats.playerAbilityLevel)
                            .toString(),
                    )
                    gameStore.attachCharacterReply(recordId, text)
                    action = ""
                    pageIndex = 0
                    autoPlay = false
                }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().background(ApocalypseV3Colors.night).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回", tint = ApocalypseV3Colors.textOnDark) }
            Column(Modifier.weight(1f)) {
                Text("末世求生", color = ApocalypseV3Colors.textOnDark, fontSize = 18.sp, fontWeight = FontWeight.Black)
                Text("${save.director.phase} · 第${save.scene}幕", color = ApocalypseV3Colors.amber, fontSize = 10.sp)
            }
            IconButton(onClick = { showMap = true }) { Icon(Icons.Outlined.Map, "地图", tint = ApocalypseV3Colors.textMutedDark) }
            IconButton(onClick = { showInventory = true }) { Icon(Icons.Outlined.Inventory2, "物资", tint = ApocalypseV3Colors.textMutedDark) }
        }

        ApocalypseV3NovelStage(
            party = party,
            config = config,
            location = save.director.location,
            tension = save.director.tension,
            stats = save.stats,
        )

        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            color = ApocalypseV3Colors.paper,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(188.dp).clickable(enabled = !busy && !lastPage) { nextPage() },
                    color = ApocalypseV3Colors.paperStrong,
                    shape = RoundedCornerShape(19.dp),
                    border = BorderStroke(1.dp, Color(0xFFDDD4C6)),
                ) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 13.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(save.director.location, color = Color(0xFF8A744C), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(
                                pages.getOrElse(pageIndex) { "……" },
                                color = ApocalypseV3Colors.ink,
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                            )
                        }
                        Text("${pageIndex + 1} / ${pages.size}", color = ApocalypseV3Colors.muted, fontSize = 9.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().height(43.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = ::previousPage, enabled = pageIndex > 0) {
                        Icon(Icons.Outlined.ChevronLeft, null, modifier = Modifier.size(18.dp))
                        Text("上一段", fontSize = 11.sp)
                    }
                    TextButton(onClick = { autoPlay = !autoPlay }, enabled = !lastPage) {
                        Icon(if (autoPlay) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(if (autoPlay) "暂停自动" else "自动播放", fontSize = 11.sp)
                    }
                    TextButton(onClick = ::nextPage, enabled = !lastPage) {
                        Text("下一段", fontSize = 11.sp)
                        Icon(Icons.Outlined.ChevronRight, null, modifier = Modifier.size(18.dp))
                    }
                }

                if (lastPage) {
                    OutlinedTextField(
                        value = action,
                        onValueChange = { action = it.take(600) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("你接下来想做什么？可以自由输入……") },
                        minLines = 1,
                        maxLines = 2,
                        shape = RoundedCornerShape(15.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFB79A5B),
                            unfocusedBorderColor = Color(0xFFD8CFC0),
                        ),
                    )
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ApocalypseV3QuickAction("搜物资") { action = "我仔细搜集能长期保存的食物、饮水、药物、能源和工具，并优先利用空间异能降低搬运风险。" }
                        ApocalypseV3QuickAction("看基地") { action = "我重新评估当前据点的水源、出入口、防御、排污、能源和撤退路线。" }
                        ApocalypseV3QuickAction("练空间") { action = "我检查晶核和空间异能状态，优先训练当前等级已经允许的储物、控制或攻击能力。" }
                    }
                    Spacer(Modifier.height(5.dp))
                    Button(
                        onClick = ::submit,
                        enabled = action.isNotBlank() && !busy,
                        modifier = Modifier.fillMaxWidth().height(45.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ApocalypseV3Colors.amber, contentColor = ApocalypseV3Colors.ink),
                        shape = RoundedCornerShape(15.dp),
                    ) {
                        Text(if (busy) "导演正在重排这一幕……" else "行动", fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }

    if (showInventory) {
        ModalBottomSheet(onDismissRequest = { showInventory = false }, containerColor = ApocalypseV3Colors.paper) {
            ApocalypseV3InventorySheet(save)
        }
    }
    if (showMap) {
        ModalBottomSheet(onDismissRequest = { showMap = false }, containerColor = ApocalypseV3Colors.paper) {
            ApocalypseV3MapSheet(save.director.locations) { location ->
                action = "我准备前往${location.name}，先观察路线、天气、感染者和人类活动，再决定怎么进入。"
                showMap = false
            }
        }
    }
}

@Composable
private fun ApocalypseV3NovelStage(
    party: List<CharacterSettings>,
    config: ApocalypseV3Config,
    location: String,
    tension: Int,
    stats: ApocalypseV3Stats,
) {
    Box(
        modifier = Modifier.fillMaxWidth().height(218.dp)
            .background(Brush.verticalGradient(listOf(Color(0xFF1A1E22), Color(0xFF29272A), Color(0xFF15191C)))),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 9.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(location, color = ApocalypseV3Colors.textOnDark, fontSize = 17.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Surface(color = ApocalypseV3Colors.red.copy(alpha = .16f), shape = RoundedCornerShape(9.dp)) {
                    Text("威胁 $tension/10", color = ApocalypseV3Colors.red, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                party.take(4).forEachIndexed { index, character ->
                    val choice = companionAbilityChoice(config, character.characterId)
                    val ability = companionAbilityDefinition(choice)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(
                            modifier = Modifier.size(if (party.size <= 2) 88.dp else 70.dp),
                            shape = RoundedCornerShape(topStart = 25.dp, topEnd = 25.dp, bottomStart = 13.dp, bottomEnd = 13.dp),
                            color = ApocalypseV3Colors.nightSoft,
                            border = BorderStroke(1.dp, Color(0xFF3A4146)),
                        ) {
                            LuluProfileAvatar(character.avatarUri, character.displayName.take(1).ifBlank { "角" }, if (party.size <= 2) 88 else 70)
                        }
                        Text(character.displayName, color = ApocalypseV3Colors.textOnDark, fontSize = 8.sp, maxLines = 1)
                        Text(if (ability.rarity == ApocalypseAbilityRarity.None) "普通人" else ability.name, color = abilityRarityColor(ability.rarity), fontSize = 7.sp, maxLines = 1)
                    }
                    if (index != party.take(4).lastIndex) Spacer(Modifier.width(if (party.size <= 2) 14.dp else 5.dp))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ApocalypseV3TinyStat("食 ${stats.food}")
                ApocalypseV3TinyStat("水 ${stats.water}")
                ApocalypseV3TinyStat("晶 ${stats.crystalCores}")
                ApocalypseV3TinyStat("空间 Lv.${stats.playerAbilityLevel} · ${playerSpaceCapacityM3(stats.playerAbilityLevel)}m³")
            }
        }
    }
}

@Composable
private fun ApocalypseV3QuickAction(text: String, onClick: () -> Unit) {
    SuggestionChip(onClick = onClick, label = { Text(text, fontSize = 9.sp) })
}

@Composable
private fun ApocalypseV3TinyStat(text: String) {
    Surface(color = Color.Black.copy(alpha = .28f), shape = RoundedCornerShape(8.dp)) {
        Text(text, color = ApocalypseV3Colors.textMutedDark, fontSize = 7.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
    }
}

@Composable
private fun ApocalypseV3InventorySheet(save: ApocalypseV3Save) {
    Column(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(.80f).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("物资、道具与线索", color = ApocalypseV3Colors.ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
        ApocalypseV3StatusPanel(save.stats, save.director.phase, save.director.location)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(save.director.assets, key = { it.id }) { asset ->
                Surface(color = ApocalypseV3Colors.paperStrong, shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, Color(0xFFE1D9CD))) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(apocalypseV3AssetIcon(asset.kind), null, tint = apocalypseV3AssetColor(asset.kind))
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(asset.title, color = ApocalypseV3Colors.ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                if (asset.quantity > 1) Text("×${asset.quantity}", color = ApocalypseV3Colors.muted, fontSize = 10.sp)
                            }
                            Text("${asset.kind.label}${asset.tag.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}", color = apocalypseV3AssetColor(asset.kind), fontSize = 9.sp)
                            Text(asset.detail, color = ApocalypseV3Colors.muted, fontSize = 11.sp, lineHeight = 17.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun ApocalypseV3MapSheet(locations: List<ApocalypseV3Location>, onChoose: (ApocalypseV3Location) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(.76f).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("区域地图", color = ApocalypseV3Colors.ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text("地点会随着调查、势力变化和剧情推进逐渐扩展。地图提供信息，但不会替你决定路线。", color = ApocalypseV3Colors.muted, fontSize = 11.sp)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(locations, key = { it.id }) { location ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { if (location.unlocked) onChoose(location) },
                    enabled = location.unlocked,
                    color = if (location.unlocked) ApocalypseV3Colors.paperStrong else Color(0xFFEAE6DE),
                    shape = RoundedCornerShape(17.dp),
                    border = BorderStroke(1.dp, Color(0xFFE1D9CD)),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (location.unlocked) Icons.Outlined.Place else Icons.Outlined.Lock, null, tint = Color(0xFF8A744C))
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text(location.name, color = ApocalypseV3Colors.ink, fontWeight = FontWeight.Bold)
                            Text(location.detail, color = ApocalypseV3Colors.muted, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun ApocalypseV3StatusPanel(stats: ApocalypseV3Stats, phase: String, location: String) {
    Surface(color = ApocalypseV3Colors.night, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("$phase · $location", color = ApocalypseV3Colors.textOnDark, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ApocalypseV3StatusValue("食物", stats.food.toString())
                ApocalypseV3StatusValue("饮水", stats.water.toString())
                ApocalypseV3StatusValue("药物", stats.medicine.toString())
                ApocalypseV3StatusValue("材料", stats.materials.toString())
            }
            HorizontalDivider(color = ApocalypseV3Colors.line)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ApocalypseV3StatusValue("晶核", stats.crystalCores.toString())
                ApocalypseV3StatusValue("空间", "Lv.${stats.playerAbilityLevel}")
                ApocalypseV3StatusValue("容量", "${playerSpaceCapacityM3(stats.playerAbilityLevel)}m³")
                ApocalypseV3StatusValue("基地", if (stats.baseLevel <= 0) "无" else "Lv.${stats.baseLevel}")
            }
            Text(playerSpaceAttack(stats.playerAbilityLevel), color = ApocalypseV3Colors.textMutedDark, fontSize = 9.sp)
        }
    }
}

@Composable
private fun ApocalypseV3StatusValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = ApocalypseV3Colors.amber, fontSize = 14.sp, fontWeight = FontWeight.Black)
        Text(label, color = ApocalypseV3Colors.textMutedDark, fontSize = 8.sp)
    }
}

@Composable
private fun ApocalypseV3SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = ApocalypseV3Colors.ink, fontSize = 19.sp, fontWeight = FontWeight.Black)
        Text(subtitle, color = ApocalypseV3Colors.muted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

private suspend fun planApocalypseV3Beat(
    save: ApocalypseV3Save,
    config: ApocalypseV3Config,
    party: List<CharacterSettings>,
    action: String,
): ApocalypseV3Beat {
    val director = save.director
    val partyPrompt = party.joinToString("\n") { character ->
        val choice = companionAbilityChoice(config, character.characterId)
        val ability = companionAbilityDefinition(choice)
        "- ${character.displayName}：人设=${character.persona.ifBlank { "遵循既有人设" }}；能力=${ability.name}；稀有度=${ability.rarity.label}；潜力=${ability.potential}；分化=${choice.branch}"
    }
    val facts = buildString {
        appendLine("互动长篇：《末世求生·赤潮纪元》")
        appendLine("世界模式：${config.worldMode}")
        appendLine(playerSpacePrompt(save.stats))
        appendLine("阶段=${director.phase}；地点=${director.location}；第${save.scene}幕；威胁=${director.tension}/10")
        appendLine("资源：食物${save.stats.food} 水${save.stats.water} 药物${save.stats.medicine} 材料${save.stats.materials} 晶核${save.stats.crystalCores}")
        appendLine("基地=${save.stats.baseName}/Lv.${save.stats.baseLevel}")
        appendLine("当前明线：${director.activeThreads.joinToString("｜")}")
        appendLine("隐藏长期线：${director.hiddenThreads.joinToString("｜")}")
        appendLine("长期剧情蓝图：${director.longTermPlan.joinToString("｜")}")
        appendLine("势力状态：${director.factionStates.joinToString("｜")}")
        appendLine("人物长期弧：${director.characterArcs.joinToString("｜")}")
        appendLine("伏笔回收计划：${director.foreshadowPlan.joinToString("｜")}")
        appendLine("已确认世界事实：${director.worldFacts.takeLast(24).joinToString("｜")}")
        appendLine("已知地点：${director.locations.joinToString("｜") { it.name }}")
        appendLine("已获得资产：${director.assets.joinToString("｜") { "${it.kind.label}:${it.title}" }}")
        appendLine("同行者硬设定：\n$partyPrompt")
        appendLine("玩家行动：$action")
        appendLine("上一幕：\n${save.narration.takeLast(2600)}")
    }
    val instruction = """
        你是长篇互动游戏《末世求生》的隐藏总导演。你不直接写正文，只维护世界、长线剧情和下一幕导演意图。只返回 JSON，不加代码块。

        返回字段：phase, location, sceneGoal, beatType, tension, activeThreads, hiddenThreads, worldFacts,
        longTermPlan, factionStates, characterArcs, foreshadowPlan, worldDelta, directive,
        foodDelta, waterDelta, medicineDelta, materialsDelta, coresFound, playerAbilityXpGain, baseDelta,
        unlockLocations:[{id,name,detail,unlocked}],
        discoverAssets:[{id,kind,title,detail,quantity,tag}]。
        kind只能 food|water|medicine|material|tool|weapon|vehicle|key|document|clue|map|core。

        导演原则：
        1. 你必须提前有中长期故事，不是每幕现编。longTermPlan 是数月到数年的宏观骨架；factionStates 维护势力利益和变化；characterArcs 维护人物长期成长；foreshadowPlan 维护伏笔出现、加深和回收窗口。
        2. 蓝图不是铁路。玩家可以拒绝任务、绕路、救下本该死的人、毁掉势力、提前接触后期地点。发生这种情况时承认事实并重排蓝图，不得强行把玩家拖回原剧情。
        3. 每幕只推进一个核心戏剧动作，最多顺带推进一条暗线。2—4幕让旧细节产生一次新意义；6—10幕才安排真正改变局面的回收或反转。
        4. 重大反转必须能回看见依据。禁止万能组织、无缘由背叛、突然失忆、凭空出现救世主来强救剧情。
        5. 大多数人是普通人。世界里约四分之三人口没有稳定异能；常见异能以感官/体能强化为主，元素和念动力更少，规则型极少。不要把路人都写成超能力大战。
        6. 同行角色的能力名称、稀有度、潜力和分化方向是硬设定。普通人不能为了方便突然觉醒；同一异能不同分化要真正影响解决问题方式。
        7. 玩家固定空间系，而且是主角级高稳定共鸣。玩家体能偏弱，不能靠突然跑得比强化系还快或纯肉搏解决危机；空间的运输、保存、取物和后期攻击应该成为玩家明显的主角优势。
        8. 空间系当前等级必须严格生效。Lv1没有稳定空间刃；Lv2才出现裂隙刃雏形；Lv3闪位和稳定空间刃；Lv4空间锁/陷阱；Lv5领域。不能提前偷用未来技能。
        9. 赤潮是行星生态灾变：植物、动物、土壤、水体、天气、人类和感染者都持续变化，剧情不能只剩打丧尸。
        10. 感染者进化要有时间尺度。早期主要普通行尸，随后才出现猎行者、特化变异体、统御体和灾厄级。高阶越少、越危险、晶核越纯。
        11. 晶核不是自动掉落金币。只有玩家实际击杀/取得并处理尸体才给 coresFound；只有明确吸收、训练、共鸣或突破情节才给 playerAbilityXpGain。
        12. 末世资源要真实：水比很多食物更急，药、燃料、净水、运输、电力、工具、卫生、睡眠和撤退路线都可能比枪更重要。空间异能能极大缓解搬运和保存，但不能凭空创造物资。
        13. 基地升级必须对应真实清理、修缮、水电、防御和人力投入，不能点按钮升级。
        14. 角色不是跟班NPC。必须保留他们既有人设、利益、恐惧、意见和关系；可以反对玩家，但不要为了制造冲突而无理由唱反调。
        15. JSON 数值：资源delta -4..4；coresFound 0..4；playerAbilityXpGain 0..5；baseDelta 0..1；tension 1..10。
    """.trimIndent()

    val raw = LuluAiServices.gateway.generate(
        characterId = party.firstOrNull()?.characterId ?: "lulu",
        facts = facts,
        instruction = instruction,
        source = "末世求生导演V3",
        title = "末世求生导演 · 第${save.scene}幕",
        temperature = 0.34,
        maxTokens = 1900,
        usage = ModelUsage.Game,
        contextMode = CompanionContextMode.PersonaAndScenario,
    ).getOrNull()?.text

    return raw?.let { parseApocalypseV3Beat(it, director) } ?: fallbackApocalypseV3Beat(save)
}

private suspend fun writeApocalypseV3Scene(
    save: ApocalypseV3Save,
    config: ApocalypseV3Config,
    party: List<CharacterSettings>,
    action: String,
    beat: ApocalypseV3Beat,
    nextStats: ApocalypseV3Stats,
): Result<String> {
    val partyPrompt = party.joinToString("\n") { character ->
        val choice = companionAbilityChoice(config, character.characterId)
        val ability = companionAbilityDefinition(choice)
        "- ${character.displayName}：${character.persona.ifBlank { "遵循既有人设" }}；${ability.name}/${choice.branch}/${ability.rarity.label}"
    }
    val facts = buildString {
        appendLine("玩家行动：$action")
        appendLine("阶段：${beat.nextDirector.phase}；地点：${beat.nextDirector.location}；威胁：${beat.nextDirector.tension}/10")
        appendLine(playerSpacePrompt(nextStats))
        appendLine("资源：食${nextStats.food} 水${nextStats.water} 药${nextStats.medicine} 材料${nextStats.materials} 晶核${nextStats.crystalCores}；基地=${nextStats.baseName}/Lv.${nextStats.baseLevel}")
        appendLine("导演动作：${beat.beatType}；本幕目标：${beat.nextDirector.sceneGoal}")
        appendLine("世界变化：${beat.worldDelta}")
        appendLine("导演执行指令：${beat.directive}")
        appendLine("同行者：\n$partyPrompt")
        appendLine("当前明线：${beat.nextDirector.activeThreads.joinToString("｜")}")
        appendLine("世界规则摘要：${apocalypseWorldLoreV3().joinToString("｜") { "${it.first}:${it.second.take(110)}" }}")
        appendLine("上一幕：\n${save.narration.takeLast(2800)}")
    }
    val instruction = """
        写一幕高质量中文末世互动小说，约800—1300字。只输出正文，不输出选项、数值面板、解释或JSON。

        显示规则：正文自然分成10—18个短段落，每段通常1—3句。即使你偶尔写长，客户端也会再次强制切分，所以不要依赖一个巨段落承载全部信息。

        文学与玩法规则：
        - 玩家刚才的行动必须真的发生并产生现实后果，不能偷换成编剧想让玩家做的事。
        - 重要场景要有可感知的画面：光线、温度、气味、远处声音、材质、天气、身体反应选择性出现，不要机械堆感官词。
        - 对话嵌入动作、停顿、眼神和潜台词；人物严格保持露露机既有人设和关系。
        - 同行者异能严格按设定和分化方向使用；普通人依靠专业技能、装备和判断力，不能突然有能力。
        - 玩家体能偏弱，不要让玩家突然靠超常跑跳肉搏取胜。玩家的主角感来自空间异能、准备能力、判断和逐渐成长的空间攻击。
        - 空间当前等级不可越级；但当前等级已经拥有的优势要大胆、聪明地让玩家体验，不要故意把金手指写得像没有。
        - 末世细节有重量：水、搬运、保质、药物、燃料、噪音、伤口、睡眠、天气、车辆、卫生和电力都会改变选择。
        - 赤潮生态逐步渗透植物、动物、土壤、水和天气，不要只写丧尸。
        - 高潮之间必须允许整理物资、做饭、赶路、争执、建设、休息和关系变化。九死一生要靠积累，不要每一幕都爆炸反转。
        - 晶核、物资、线索、地图和新地点只有导演给出时才正式获得；正文要把‘怎么得到的’写清楚。
        - 结尾停在自然可行动节点，不替玩家决定下一步，也不列A/B/C选项。
    """.trimIndent()

    return LuluAiServices.gateway.generate(
        characterId = party.firstOrNull()?.characterId ?: "lulu",
        facts = facts,
        instruction = instruction,
        source = "末世求生V3",
        title = "末世求生 · 第${save.scene}幕",
        temperature = 0.80,
        maxTokens = 2400,
        usage = ModelUsage.Game,
        contextMode = CompanionContextMode.PersonaAndScenario,
    ).map { it.text.trim() }
}

private fun parseApocalypseV3Beat(raw: String, previous: ApocalypseV3Director): ApocalypseV3Beat? = runCatching {
    val json = JSONObject(extractApocalypseV3Json(raw))
    val locations = json.optJSONArray("unlockLocations").v3ScreenObjects { item ->
        ApocalypseV3Location(
            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = item.optString("name").ifBlank { "未知地点" }.take(70),
            detail = item.optString("detail").take(260),
            unlocked = item.optBoolean("unlocked", true),
        )
    }
    val assets = json.optJSONArray("discoverAssets").v3ScreenObjects { item ->
        ApocalypseV3Asset(
            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
            kind = parseApocalypseV3AssetKindForScreen(item.optString("kind")),
            title = item.optString("title").ifBlank { "新发现" }.take(70),
            detail = item.optString("detail").take(360),
            quantity = item.optInt("quantity", 1).coerceIn(1, 999),
            tag = item.optString("tag").take(40),
        )
    }
    val next = previous.copy(
        phase = json.optString("phase").ifBlank { previous.phase }.take(80),
        location = json.optString("location").ifBlank { previous.location }.take(100),
        sceneGoal = json.optString("sceneGoal").ifBlank { previous.sceneGoal }.take(260),
        activeThreads = json.optJSONArray("activeThreads").v3ScreenStrings().ifEmpty { previous.activeThreads }.take(8),
        hiddenThreads = json.optJSONArray("hiddenThreads").v3ScreenStrings().ifEmpty { previous.hiddenThreads }.take(8),
        worldFacts = json.optJSONArray("worldFacts").v3ScreenStrings().ifEmpty { previous.worldFacts }.takeLast(28),
        longTermPlan = json.optJSONArray("longTermPlan").v3ScreenStrings().ifEmpty { previous.longTermPlan }.take(12),
        factionStates = json.optJSONArray("factionStates").v3ScreenStrings().ifEmpty { previous.factionStates }.take(14),
        characterArcs = json.optJSONArray("characterArcs").v3ScreenStrings().ifEmpty { previous.characterArcs }.take(14),
        foreshadowPlan = json.optJSONArray("foreshadowPlan").v3ScreenStrings().ifEmpty { previous.foreshadowPlan }.take(14),
        locations = (previous.locations + locations).distinctBy { it.id }.takeLast(36),
        assets = (previous.assets + assets).distinctBy { it.id }.takeLast(90),
        tension = json.optInt("tension", previous.tension).coerceIn(1, 10),
    )
    ApocalypseV3Beat(
        nextDirector = next,
        beatType = json.optString("beatType").ifBlank { "choice" }.take(40),
        directive = json.optString("directive").ifBlank { "让玩家行动产生现实后果。" }.take(900),
        worldDelta = json.optString("worldDelta").take(500),
        foodDelta = json.optInt("foodDelta").coerceIn(-4, 4),
        waterDelta = json.optInt("waterDelta").coerceIn(-4, 4),
        medicineDelta = json.optInt("medicineDelta").coerceIn(-4, 4),
        materialsDelta = json.optInt("materialsDelta").coerceIn(-4, 4),
        coresFound = json.optInt("coresFound").coerceIn(0, 4),
        playerAbilityXpGain = json.optInt("playerAbilityXpGain").coerceIn(0, 5),
        baseDelta = json.optInt("baseDelta").coerceIn(0, 1),
    )
}.getOrNull()

private fun fallbackApocalypseV3Beat(save: ApocalypseV3Save): ApocalypseV3Beat = ApocalypseV3Beat(
    nextDirector = save.director.copy(
        sceneGoal = "承认玩家刚才的自由行动，让它改变资源、关系、风险或信息，并继续沿长期世界状态自然演化。",
        tension = if (save.scene % 6 == 0) (save.director.tension + 1).coerceAtMost(7) else save.director.tension,
    ),
    beatType = "continuation",
    directive = "延续已有环境、角色与伏笔，不凭空反转；认真执行玩家行动。",
    worldDelta = "局势按照玩家行为继续变化。",
)

private fun extractApocalypseV3Json(raw: String): String {
    val value = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val start = value.indexOf('{')
    val end = value.lastIndexOf('}')
    return if (start >= 0 && end > start) value.substring(start, end + 1) else value
}

private fun JSONArray?.v3ScreenStrings(): List<String> = buildList {
    val array = this@v3ScreenStrings ?: return@buildList
    for (index in 0 until array.length()) array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
}

private fun <T> JSONArray?.v3ScreenObjects(mapper: (JSONObject) -> T): List<T> = buildList {
    val array = this@v3ScreenObjects ?: return@buildList
    for (index in 0 until array.length()) {
        array.optJSONObject(index)?.let { item -> runCatching { mapper(item) }.getOrNull()?.let(::add) }
    }
}

private fun parseApocalypseV3AssetKindForScreen(raw: String): ApocalypseV3AssetKind = when (raw.lowercase()) {
    "food" -> ApocalypseV3AssetKind.Food
    "water" -> ApocalypseV3AssetKind.Water
    "medicine" -> ApocalypseV3AssetKind.Medicine
    "material" -> ApocalypseV3AssetKind.Material
    "tool", "item" -> ApocalypseV3AssetKind.Tool
    "weapon" -> ApocalypseV3AssetKind.Weapon
    "vehicle" -> ApocalypseV3AssetKind.Vehicle
    "key" -> ApocalypseV3AssetKind.Key
    "document" -> ApocalypseV3AssetKind.Document
    "map" -> ApocalypseV3AssetKind.Map
    "core" -> ApocalypseV3AssetKind.Core
    else -> ApocalypseV3AssetKind.Clue
}

private fun apocalypseV3AssetIcon(kind: ApocalypseV3AssetKind): ImageVector = when (kind) {
    ApocalypseV3AssetKind.Food -> Icons.Outlined.Restaurant
    ApocalypseV3AssetKind.Water -> Icons.Outlined.WaterDrop
    ApocalypseV3AssetKind.Medicine -> Icons.Outlined.MedicalServices
    ApocalypseV3AssetKind.Material -> Icons.Outlined.Construction
    ApocalypseV3AssetKind.Tool -> Icons.Outlined.Handyman
    ApocalypseV3AssetKind.Weapon -> Icons.Outlined.GpsFixed
    ApocalypseV3AssetKind.Vehicle -> Icons.Outlined.DirectionsCar
    ApocalypseV3AssetKind.Key -> Icons.Outlined.Key
    ApocalypseV3AssetKind.Document -> Icons.Outlined.Description
    ApocalypseV3AssetKind.Clue -> Icons.Outlined.Search
    ApocalypseV3AssetKind.Map -> Icons.Outlined.Map
    ApocalypseV3AssetKind.Core -> Icons.Outlined.AutoAwesome
}

private fun apocalypseV3AssetColor(kind: ApocalypseV3AssetKind): Color = when (kind) {
    ApocalypseV3AssetKind.Food, ApocalypseV3AssetKind.Water -> ApocalypseV3Colors.blue
    ApocalypseV3AssetKind.Medicine -> ApocalypseV3Colors.red
    ApocalypseV3AssetKind.Material, ApocalypseV3AssetKind.Tool -> ApocalypseV3Colors.green
    ApocalypseV3AssetKind.Weapon, ApocalypseV3AssetKind.Vehicle -> Color(0xFF8A744C)
    ApocalypseV3AssetKind.Key, ApocalypseV3AssetKind.Document, ApocalypseV3AssetKind.Map -> ApocalypseV3Colors.amber
    ApocalypseV3AssetKind.Clue -> ApocalypseV3Colors.blue
    ApocalypseV3AssetKind.Core -> ApocalypseV3Colors.purple
}

private fun abilityRarityColor(rarity: ApocalypseAbilityRarity): Color = when (rarity) {
    ApocalypseAbilityRarity.None -> Color(0xFF7D7B76)
    ApocalypseAbilityRarity.Common -> Color(0xFF6B8D72)
    ApocalypseAbilityRarity.Uncommon -> Color(0xFF708BA8)
    ApocalypseAbilityRarity.Rare -> Color(0xFF9474AD)
    ApocalypseAbilityRarity.Exceptional -> Color(0xFFB78545)
}
