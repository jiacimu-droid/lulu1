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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.LuluProfileAvatar
import com.jiacimu.lulu.data.CharacterSettings
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

private object ApocalypseV4Colors {
    val night = Color(0xFF101418)
    val nightSoft = Color(0xFF1B2126)
    val line = Color(0xFF343B41)
    val paper = Color(0xFFF5F0E7)
    val paperStrong = Color(0xFFFFFCF6)
    val ink = Color(0xFF282825)
    val muted = Color(0xFF77736B)
    val textOnDark = Color(0xFFF6F2EA)
    val textMutedDark = Color(0xFFB5B7B6)
    val amber = Color(0xFFE1BD69)
    val red = Color(0xFFCB7167)
    val green = Color(0xFF759C80)
    val blue = Color(0xFF7D9EB8)
    val purple = Color(0xFF9A84B6)
}

private class ApocalypseReadingProgressStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("apocalypse_reading_progress", Context.MODE_PRIVATE)

    private fun key(saveId: String, scene: Int) = "${saveId}_$scene"

    fun load(saveId: String, scene: Int): Int = prefs.getInt(key(saveId, scene), 0).coerceAtLeast(0)

    fun save(saveId: String, scene: Int, page: Int) {
        prefs.edit().putInt(key(saveId, scene), page.coerceAtLeast(0)).apply()
    }

    fun clear() = prefs.edit().clear().apply()
}

@Composable
internal fun ApocalypseSurvivalAppV4(
    gameStore: LuluGameStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val storage = remember(context) { ApocalypseSurvivalV3Store(context) }
    val progressStore = remember(context) { ApocalypseReadingProgressStore(context) }
    val gameState by gameStore.state.collectAsState()
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    var page by remember { mutableStateOf(ApocalypseV3Page.Home) }
    var save by remember { mutableStateOf(storage.loadSave()) }
    var config by remember { mutableStateOf(storage.loadConfig()) }

    fun createSave(): ApocalypseV3Save {
        val party = gameState.selectedCharacterIds.take(4).ifEmpty {
            characters.keys.firstOrNull()?.let(::listOf).orEmpty()
        }
        val names = party.map { id -> characters[id]?.displayName ?: MigratedDomainStores.characters.get(id).displayName }
        return ApocalypseV3Save(
            id = UUID.randomUUID().toString(),
            scene = 1,
            partyIds = party,
            narration = tagApocalypseNarrationAsNarrator(initialApocalypseV3Scene(names)),
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
        ApocalypseV3Page.Home -> ApocalypseV4HomePage(
            save = save,
            config = config,
            onBack = onBack,
            onEnter = ::enterGame,
            onSettings = { page = ApocalypseV3Page.Settings },
            onWorld = { page = ApocalypseV3Page.World },
            onArchive = { page = ApocalypseV3Page.Archive },
        )

        ApocalypseV3Page.Settings -> ApocalypseV4SettingsPage(
            config = config,
            selectedPartyIds = gameState.selectedCharacterIds,
            characters = characters.values.toList(),
            onBack = ::goBack,
            onSave = { nextConfig, partyIds ->
                config = nextConfig
                storage.saveConfig(nextConfig)
                if (partyIds.isNotEmpty()) gameStore.selectCharacters(partyIds.take(4))
                val current = save
                if (current != null && partyIds.isNotEmpty() && current.partyIds != partyIds.take(4)) {
                    val updated = current.copy(partyIds = partyIds.take(4), updatedAt = System.currentTimeMillis())
                    save = updated
                    storage.save(updated)
                }
                page = ApocalypseV3Page.Home
            },
        )

        ApocalypseV3Page.World -> ApocalypseV4WorldPage(config, ::goBack)

        ApocalypseV3Page.Archive -> ApocalypseV4ArchivePage(
            save = save,
            onBack = ::goBack,
            onClear = {
                storage.clearSave()
                progressStore.clear()
                save = null
            },
        )

        ApocalypseV3Page.Play -> {
            val current = save
            if (current == null) {
                LaunchedEffect(Unit) { enterGame() }
            } else {
                ApocalypseV4PlayPage(
                    save = current,
                    config = config,
                    gameStore = gameStore,
                    characters = characters,
                    progressStore = progressStore,
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
private fun ApocalypseV4HomePage(
    save: ApocalypseV3Save?,
    config: ApocalypseV3Config,
    onBack: () -> Unit,
    onEnter: () -> Unit,
    onSettings: () -> Unit,
    onWorld: () -> Unit,
    onArchive: () -> Unit,
) {
    Scaffold(
        containerColor = ApocalypseV4Colors.paper,
        topBar = {
            TopAppBar(
                title = { Text("末世求生", fontWeight = FontWeight.Black) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ApocalypseV4Colors.paper),
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
                    color = ApocalypseV4Colors.night,
                    contentColor = ApocalypseV4Colors.textOnDark,
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("赤潮纪元", color = ApocalypseV4Colors.amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("活下去，但别把自己活成一个没有选择的人。", fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.Black)
                        Text("视觉小说式长篇 · 自由行动 · 导演长线 · 空间成长 · 基地与群像", color = ApocalypseV4Colors.textMutedDark, fontSize = 11.sp)
                        if (save != null) {
                            HorizontalDivider(color = ApocalypseV4Colors.line)
                            Text("${save.director.phase} · 第${save.scene}幕 · ${save.director.location}", color = ApocalypseV4Colors.textMutedDark, fontSize = 11.sp)
                        }
                    }
                }
            }
            item { ApocalypseV4MenuEntry(Icons.Outlined.PlayArrow, "进入游戏", if (save == null) "从灾前第七日开始" else "继续第 ${save.scene} 幕", onEnter, true) }
            item { ApocalypseV4MenuEntry(Icons.Outlined.Settings, "设定", "同行角色、同行异能、世界强度与自动播放", onSettings) }
            item { ApocalypseV4MenuEntry(Icons.Outlined.Public, "世界档案", "赤潮生态、异能社会、丧尸进化与长期剧情骨架", onWorld) }
            item { ApocalypseV4MenuEntry(Icons.Outlined.History, "存档与回顾", "基地、物资、晶核和最近剧情", onArchive) }
            item {
                Surface(color = Color(0xFFF1EDF5), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Color(0xFFDCD2E7))) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("主角固定异能 · 空间", fontWeight = FontWeight.Black, color = ApocalypseV4Colors.ink)
                        Text("48m³近停滞储物起步；后续裂隙刃、闪位、空间锁和领域。体能弱不等于战斗废，路线本身就是主角的生存与攻击金手指。", color = ApocalypseV4Colors.muted, fontSize = 11.sp, lineHeight = 17.sp)
                    }
                }
            }
            item {
                val configured = config.partyAbilities.values.count { it.abilityId != "none" }
                Text("已为 $configured 位同行角色设置异能；没有设置的人默认是普通人。", color = ApocalypseV4Colors.muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun ApocalypseV4MenuEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    emphasis: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        color = if (emphasis) Color(0xFFFFF8E5) else ApocalypseV4Colors.paperStrong,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, if (emphasis) ApocalypseV4Colors.amber else Color(0xFFE2DACE)),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = if (emphasis) ApocalypseV4Colors.amber.copy(alpha = .18f) else Color(0xFFF0ECE5), shape = RoundedCornerShape(14.dp)) {
                Icon(icon, null, tint = ApocalypseV4Colors.ink, modifier = Modifier.padding(10.dp).size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = ApocalypseV4Colors.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = ApocalypseV4Colors.muted, fontSize = 11.sp)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = ApocalypseV4Colors.muted)
        }
    }
}

@Composable
private fun ApocalypseV4SettingsPage(
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

    Scaffold(
        containerColor = ApocalypseV4Colors.paper,
        topBar = {
            TopAppBar(
                title = { Text("末世设定", fontWeight = FontWeight.Black) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                actions = {
                    TextButton(onClick = { onSave(ApocalypseV3Config(worldMode, speed, choices), party.toList()) }) {
                        Text("保存", color = ApocalypseV4Colors.ink, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ApocalypseV4Colors.paper),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            item {
                ApocalypseV4SectionTitle("你的异能", "固定为空间系")
                Spacer(Modifier.height(7.dp))
                Surface(color = Color(0xFFF1EDF5), shape = RoundedCornerShape(19.dp), border = BorderStroke(1.dp, Color(0xFFD9CEE5))) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("空间 · 主角级高稳定共鸣", color = ApocalypseV4Colors.purple, fontWeight = FontWeight.Black, fontSize = 17.sp)
                        Text("开局48m³近停滞储物，后续必定长出攻击路线。体能偏弱，所以你的战斗更依赖取物、距离、闪位与空间切割。", color = ApocalypseV4Colors.ink, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
            item { ApocalypseV4SectionTitle("同行角色与异能", "最多4人；普通人也可以成为队伍核心") }
            items(characters.sortedBy { it.displayName }, key = { it.characterId }) { character ->
                val selected = character.characterId in party
                val choice = choices[character.characterId] ?: ApocalypseAbilityChoice()
                val ability = companionAbilityDefinition(choice)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = if (selected) Color(0xFFFFF7E1) else ApocalypseV4Colors.paperStrong,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, if (selected) ApocalypseV4Colors.amber else Color(0xFFE1D9CD)),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LuluProfileAvatar(character.avatarUri, character.displayName.take(1).ifBlank { "角" }, 46)
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(character.displayName.ifBlank { "未命名" }, fontWeight = FontWeight.Bold, color = ApocalypseV4Colors.ink)
                                if (selected) Text("${ability.rarity.label} · ${ability.name} · 潜力${ability.potential}", color = abilityRarityColorV4(ability.rarity), fontSize = 10.sp)
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
                                Text("分化：${choice.branch}", color = ApocalypseV4Colors.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { editingCharacterId = character.characterId }) { Text("设置异能", color = ApocalypseV4Colors.ink) }
                            }
                        }
                    }
                }
            }
            item { ApocalypseV4SectionTitle("世界强度", "世界观不变，只调整资源和进化压力") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("标准异变", "资源荒年", "高危进化").forEach { mode ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { worldMode = mode },
                            color = if (worldMode == mode) Color(0xFFFFF7E1) else ApocalypseV4Colors.paperStrong,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, if (worldMode == mode) ApocalypseV4Colors.amber else Color(0xFFE1D9CD)),
                        ) {
                            Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(mode, Modifier.weight(1f), color = ApocalypseV4Colors.ink, fontWeight = FontWeight.Bold)
                                if (worldMode == mode) Icon(Icons.Outlined.Check, null)
                            }
                        }
                    }
                }
            }
            item { ApocalypseV4SectionTitle("自动播放速度", "手动模式仍可前后翻看") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2_000L to "快", 2_800L to "标准", 4_000L to "慢").forEach { (value, label) ->
                        FilterChip(
                            selected = speed == value,
                            onClick = { speed = value },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFFEFC0)),
                        )
                    }
                }
            }
        }
    }

    val editingId = editingCharacterId
    if (editingId != null) {
        val character = characters.firstOrNull { it.characterId == editingId }
        ApocalypseV4AbilityPicker(
            characterName = character?.displayName.orEmpty().ifBlank { "同行角色" },
            initialChoice = choices[editingId] ?: ApocalypseAbilityChoice(),
            onDismiss = { editingCharacterId = null },
            onConfirm = { next ->
                choices = choices + (editingId to next)
                editingCharacterId = null
            },
        )
    }
}

@Composable
private fun ApocalypseV4AbilityPicker(
    characterName: String,
    initialChoice: ApocalypseAbilityChoice,
    onDismiss: () -> Unit,
    onConfirm: (ApocalypseAbilityChoice) -> Unit,
) {
    val catalog = remember { apocalypseCompanionAbilityCatalog() }
    var choice by remember(initialChoice) { mutableStateOf(initialChoice) }
    val current = companionAbilityDefinition(choice)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = ApocalypseV4Colors.paper) {
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(.86f).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text("$characterName · 异能", color = ApocalypseV4Colors.ink, fontSize = 21.sp, fontWeight = FontWeight.Black)
            Surface(color = Color(0xFFFFF8E5), shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, ApocalypseV4Colors.amber)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("${current.rarity.label} · ${current.name} · 潜力${current.potential}", fontWeight = FontWeight.Bold, color = ApocalypseV4Colors.ink)
                    Text(current.description, color = ApocalypseV4Colors.muted, fontSize = 11.sp, lineHeight = 17.sp)
                    if (current.branches.size > 1) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            current.branches.forEach { branch ->
                                FilterChip(
                                    selected = choice.branch == branch,
                                    onClick = { choice = choice.copy(branch = branch) },
                                    label = { Text(branch, fontSize = 10.sp) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFFE7A0)),
                                )
                            }
                        }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(catalog, key = { it.id }) { ability ->
                    val selected = ability.id == choice.abilityId
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            choice = ApocalypseAbilityChoice(ability.id, ability.branches.firstOrNull() ?: ability.name)
                        },
                        color = if (selected) Color(0xFFFFF7E1) else ApocalypseV4Colors.paperStrong,
                        shape = RoundedCornerShape(15.dp),
                        border = BorderStroke(1.dp, if (selected) ApocalypseV4Colors.amber else Color(0xFFE1D9CD)),
                    ) {
                        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = abilityRarityColorV4(ability.rarity).copy(alpha = .13f), shape = RoundedCornerShape(9.dp)) {
                                Text(ability.rarity.label, color = abilityRarityColorV4(ability.rarity), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp))
                            }
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(ability.name, color = ApocalypseV4Colors.ink, fontWeight = FontWeight.Bold)
                                Text("潜力${ability.potential} · ${ability.branches.joinToString(" / ")}", color = ApocalypseV4Colors.muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (selected) Icon(Icons.Outlined.Check, null)
                        }
                    }
                }
            }
            Button(
                onClick = { onConfirm(choice) },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ApocalypseV4Colors.night, contentColor = Color.White),
            ) { Text("确定", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun ApocalypseV4WorldPage(config: ApocalypseV3Config, onBack: () -> Unit) {
    Scaffold(
        containerColor = ApocalypseV4Colors.paper,
        topBar = {
            TopAppBar(
                title = { Text("世界档案", fontWeight = FontWeight.Black) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ApocalypseV4Colors.paper),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Surface(color = ApocalypseV4Colors.night, shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("赤潮纪元", color = ApocalypseV4Colors.amber, fontWeight = FontWeight.Bold)
                        Text("这不是一场等着被打通的病毒副本，而是一整个正在重新长出来的世界。", color = ApocalypseV4Colors.textOnDark, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black)
                        Text("当前模式：${config.worldMode}", color = ApocalypseV4Colors.textMutedDark, fontSize = 11.sp)
                    }
                }
            }
            items(apocalypseWorldLoreV3(), key = { it.first }) { (title, detail) ->
                Surface(color = ApocalypseV4Colors.paperStrong, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Color(0xFFE1D9CD))) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(title, color = ApocalypseV4Colors.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(detail, color = ApocalypseV4Colors.muted, fontSize = 12.sp, lineHeight = 19.sp)
                    }
                }
            }
            item { ApocalypseV4SectionTitle("空间成长", "你的金手指既解决物资，也会长出战斗路线") }
            items(playerSpaceProgression()) { (level, detail) ->
                Surface(color = Color(0xFFF1EDF5), shape = RoundedCornerShape(17.dp)) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(level, color = ApocalypseV4Colors.purple, fontWeight = FontWeight.Black)
                        Text(detail, color = ApocalypseV4Colors.ink, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
            item { ApocalypseV4SectionTitle("导演长期蓝图", "这些是方向，不是强制剧情") }
            items(defaultApocalypseLongTermPlan()) { item ->
                Surface(color = ApocalypseV4Colors.paperStrong, shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, Color(0xFFE1D9CD))) {
                    Text(item, color = ApocalypseV4Colors.ink, fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }
}

@Composable
private fun ApocalypseV4ArchivePage(save: ApocalypseV3Save?, onBack: () -> Unit, onClear: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = ApocalypseV4Colors.paper,
        topBar = {
            TopAppBar(
                title = { Text("存档与回顾", fontWeight = FontWeight.Black) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ApocalypseV4Colors.paper),
            )
        },
    ) { padding ->
        if (save == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("还没有存档", color = ApocalypseV4Colors.muted) }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { ApocalypseV4StatusPanel(save.stats, save.director.phase, save.director.location) }
                item { ApocalypseV4SectionTitle("最近剧情", "阅读位置会单独保存；这里保留行动回顾") }
                items(save.log.asReversed().take(14)) { log ->
                    Surface(color = ApocalypseV4Colors.paperStrong, shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, Color(0xFFE1D9CD))) {
                        Text(log, color = ApocalypseV4Colors.ink, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(13.dp), maxLines = 7, overflow = TextOverflow.Ellipsis)
                    }
                }
                item {
                    OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.RestartAlt, null)
                        Spacer(Modifier.width(6.dp))
                        Text("重新开档")
                    }
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("清空末世存档？") },
            text = { Text("会删除当前末世剧情、物资、基地、异能进度和阅读进度。露露机角色本身不会删除。") },
            confirmButton = { TextButton(onClick = { onClear(); confirm = false }) { Text("清空") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ApocalypseV4PlayPage(
    save: ApocalypseV3Save,
    config: ApocalypseV3Config,
    gameStore: LuluGameStore,
    characters: Map<String, CharacterSettings>,
    progressStore: ApocalypseReadingProgressStore,
    onBack: () -> Unit,
    onSave: (ApocalypseV3Save) -> Unit,
) {
    val context = LocalContext.current
    val userPrefs = remember(context) { context.getSharedPreferences("lulu_user_profile", Context.MODE_PRIVATE) }
    val userName = remember { userPrefs.getString("display_name", "我").orEmpty().ifBlank { "我" } }
    val userAvatarText = remember { userPrefs.getString("avatar_text", "我").orEmpty().ifBlank { "我" }.take(2) }
    val userAvatarUri = remember { userPrefs.getString("avatar_uri", null) }
    val scope = rememberCoroutineScope()
    val party = save.partyIds.map { id -> characters[id] ?: MigratedDomainStores.characters.get(id) }
    val pages = remember(save.scene, save.narration, party) { parseApocalypseStoryPages(save.narration, party) }
    var pageIndex by remember(save.id, save.scene, pages.size) {
        mutableIntStateOf(progressStore.load(save.id, save.scene).coerceIn(0, pages.lastIndex.coerceAtLeast(0)))
    }
    var action by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var autoPlay by remember { mutableStateOf(false) }
    var showInventory by remember { mutableStateOf(false) }
    var showMap by remember { mutableStateOf(false) }
    val currentPage = pages.getOrElse(pageIndex) { pages.first() }
    val lastPage = pageIndex >= pages.lastIndex

    fun setPage(next: Int) {
        pageIndex = next.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
        progressStore.save(save.id, save.scene, pageIndex)
    }

    fun nextPage() {
        if (pageIndex < pages.lastIndex) setPage(pageIndex + 1) else autoPlay = false
    }

    fun previousPage() {
        if (pageIndex > 0) {
            autoPlay = false
            setPage(pageIndex - 1)
        }
    }

    LaunchedEffect(autoPlay, pageIndex, save.scene) {
        if (!autoPlay || lastPage) return@LaunchedEffect
        val readingBonus = currentPage.text.length.coerceAtMost(96) * 16L
        delay(config.autoDelayMillis + readingBonus)
        nextPage()
    }

    fun submit() {
        val clean = action.trim()
        if (clean.isBlank() || busy || !lastPage) return
        scope.launch {
            busy = true
            val beat = planApocalypseV4Beat(save, config, party, clean)
            val nextStats = applyApocalypseV3Beat(save.stats, beat)
            writeApocalypseV4Scene(save, config, party, clean, beat, nextStats)
                .onSuccess { text ->
                    if (text.isBlank()) return@onSuccess
                    val next = save.copy(
                        scene = save.scene + 1,
                        narration = text,
                        director = beat.nextDirector,
                        stats = nextStats,
                        log = (save.log + "第${save.scene}幕｜$clean\n${text.replace(Regex("【[^】]+】"), "").take(460)}").takeLast(100),
                        updatedAt = System.currentTimeMillis(),
                    )
                    onSave(next)
                    progressStore.save(next.id, next.scene, 0)
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
                    gameStore.attachCharacterReply(recordId, text.replace(Regex("【[^】]+】"), ""))
                    action = ""
                    autoPlay = false
                }
            busy = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(ApocalypseV4Colors.night).statusBarsPadding().imePadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回", tint = ApocalypseV4Colors.textOnDark) }
            Column(Modifier.weight(1f)) {
                Text("末世求生", color = ApocalypseV4Colors.textOnDark, fontSize = 17.sp, fontWeight = FontWeight.Black)
                Text("${save.director.phase} · 第${save.scene}幕", color = ApocalypseV4Colors.amber, fontSize = 9.sp)
            }
            IconButton(onClick = { showMap = true }) { Icon(Icons.Outlined.Map, "地图", tint = ApocalypseV4Colors.textMutedDark) }
            IconButton(onClick = { showInventory = true }) { Icon(Icons.Outlined.Inventory2, "物资", tint = ApocalypseV4Colors.textMutedDark) }
        }

        ApocalypseV4SpeakerStage(
            modifier = Modifier.fillMaxWidth().weight(1f),
            page = currentPage,
            party = party,
            config = config,
            location = save.director.location,
            tension = save.director.tension,
            stats = save.stats,
            userName = userName,
            userAvatarText = userAvatarText,
            userAvatarUri = userAvatarUri,
            onAdvance = { if (!lastPage && !busy) nextPage() },
        )

        Surface(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            color = ApocalypseV4Colors.paper,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        speakerLabel(currentPage, party, userName),
                        color = when (currentPage.speakerKind) {
                            ApocalypseStorySpeakerKind.Narrator -> ApocalypseV4Colors.muted
                            ApocalypseStorySpeakerKind.Player -> ApocalypseV4Colors.purple
                            ApocalypseStorySpeakerKind.Character -> ApocalypseV4Colors.ink
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${pageIndex + 1}/${pages.size}", color = ApocalypseV4Colors.muted, fontSize = 9.sp)
                }
                Spacer(Modifier.height(5.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp, max = 146.dp)
                        .clickable(enabled = !lastPage && !busy) { nextPage() },
                    color = ApocalypseV4Colors.paperStrong,
                    shape = RoundedCornerShape(17.dp),
                    border = BorderStroke(1.dp, Color(0xFFDDD4C6)),
                ) {
                    Text(
                        currentPage.text,
                        color = ApocalypseV4Colors.ink,
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height(39.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = ::previousPage, enabled = pageIndex > 0) {
                        Icon(Icons.Outlined.ChevronLeft, null, modifier = Modifier.size(18.dp))
                        Text("上一段", fontSize = 10.sp)
                    }
                    TextButton(onClick = { autoPlay = !autoPlay }, enabled = !lastPage) {
                        Icon(if (autoPlay) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(if (autoPlay) "暂停" else "自动", fontSize = 10.sp)
                    }
                    TextButton(onClick = ::nextPage, enabled = !lastPage) {
                        Text("下一段", fontSize = 10.sp)
                        Icon(Icons.Outlined.ChevronRight, null, modifier = Modifier.size(18.dp))
                    }
                }

                if (lastPage) {
                    OutlinedTextField(
                        value = action,
                        onValueChange = { action = it.take(600) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("你接下来想做什么？") },
                        minLines = 1,
                        maxLines = 2,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFB79A5B),
                            unfocusedBorderColor = Color(0xFFD8CFC0),
                        ),
                    )
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SuggestionChip(onClick = { action = "我仔细搜集能长期保存的食物、饮水、药物、能源和工具，并优先利用空间异能降低搬运风险。" }, label = { Text("搜物资", fontSize = 9.sp) })
                        SuggestionChip(onClick = { action = "我重新评估当前据点的水源、出入口、防御、排污、能源和撤退路线。" }, label = { Text("看基地", fontSize = 9.sp) })
                        SuggestionChip(onClick = { action = "我检查晶核和空间异能状态，训练当前等级已经允许的储物、控制或攻击能力。" }, label = { Text("练空间", fontSize = 9.sp) })
                    }
                    Spacer(Modifier.height(5.dp))
                    Button(
                        onClick = ::submit,
                        enabled = action.isNotBlank() && !busy,
                        modifier = Modifier.fillMaxWidth().height(43.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ApocalypseV4Colors.amber, contentColor = ApocalypseV4Colors.ink),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text(if (busy) "导演正在重排剧情……" else "行动", fontWeight = FontWeight.Black) }
                }
            }
        }
    }

    if (showInventory) {
        ModalBottomSheet(onDismissRequest = { showInventory = false }, containerColor = ApocalypseV4Colors.paper) {
            ApocalypseV4InventorySheet(save)
        }
    }
    if (showMap) {
        ModalBottomSheet(onDismissRequest = { showMap = false }, containerColor = ApocalypseV4Colors.paper) {
            ApocalypseV4MapSheet(save.director.locations) { location ->
                action = "我准备前往${location.name}，先观察路线、天气、感染者和人类活动，再决定怎么进入。"
                showMap = false
            }
        }
    }
}

@Composable
private fun ApocalypseV4SpeakerStage(
    modifier: Modifier,
    page: ApocalypseStoryPage,
    party: List<CharacterSettings>,
    config: ApocalypseV3Config,
    location: String,
    tension: Int,
    stats: ApocalypseV3Stats,
    userName: String,
    userAvatarText: String,
    userAvatarUri: String?,
    onAdvance: () -> Unit,
) {
    val character = page.characterId?.let { id -> party.firstOrNull { it.characterId == id } }
    Box(
        modifier = modifier
            .background(Brush.verticalGradient(listOf(Color(0xFF171B20), Color(0xFF29282A), Color(0xFF111519))))
            .clickable(onClick = onAdvance),
    ) {
        // Scene remains visible even when no one is speaking, so narration has an intentional background rather than an empty portrait slot.
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(location, color = ApocalypseV4Colors.textOnDark, fontSize = 17.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("空间 Lv.${stats.playerAbilityLevel} · ${playerSpaceCapacityM3(stats.playerAbilityLevel)}m³", color = ApocalypseV4Colors.textMutedDark, fontSize = 8.sp)
                }
                Surface(color = ApocalypseV4Colors.red.copy(alpha = .16f), shape = RoundedCornerShape(9.dp)) {
                    Text("威胁 $tension/10", color = ApocalypseV4Colors.red, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
                }
            }

            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                when (page.speakerKind) {
                    ApocalypseStorySpeakerKind.Narrator -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Icon(Icons.Outlined.Landscape, null, tint = ApocalypseV4Colors.textMutedDark.copy(alpha = .52f), modifier = Modifier.size(58.dp))
                            Text("$location · 场景", color = ApocalypseV4Colors.textMutedDark, fontSize = 10.sp)
                        }
                    }
                    ApocalypseStorySpeakerKind.Player -> {
                        SpeakerPortrait(
                            imageUri = userAvatarUri,
                            fallback = userAvatarText.ifBlank { userName.take(1).ifBlank { "我" } },
                            name = userName,
                            subtitle = "空间系 · 主角",
                            accent = ApocalypseV4Colors.purple,
                        )
                    }
                    ApocalypseStorySpeakerKind.Character -> {
                        if (character == null) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Outlined.Person, null, tint = ApocalypseV4Colors.textMutedDark, modifier = Modifier.size(54.dp))
                                Text("同行角色", color = ApocalypseV4Colors.textMutedDark, fontSize = 10.sp)
                            }
                        } else {
                            val choice = companionAbilityChoice(config, character.characterId)
                            val ability = companionAbilityDefinition(choice)
                            SpeakerPortrait(
                                imageUri = character.avatarUri,
                                fallback = character.displayName.take(1).ifBlank { "角" },
                                name = character.displayName,
                                subtitle = if (ability.rarity == ApocalypseAbilityRarity.None) "普通人" else "${ability.name} · ${choice.branch}",
                                accent = abilityRarityColorV4(ability.rarity),
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                TinyStageStat("食 ${stats.food}")
                TinyStageStat("水 ${stats.water}")
                TinyStageStat("药 ${stats.medicine}")
                TinyStageStat("晶 ${stats.crystalCores}")
                if (stats.baseLevel > 0) TinyStageStat("基地 Lv.${stats.baseLevel}")
            }
        }
    }
}

@Composable
private fun SpeakerPortrait(
    imageUri: String?,
    fallback: String,
    name: String,
    subtitle: String,
    accent: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.size(154.dp),
            shape = RoundedCornerShape(topStart = 46.dp, topEnd = 46.dp, bottomStart = 22.dp, bottomEnd = 22.dp),
            color = ApocalypseV4Colors.nightSoft,
            border = BorderStroke(1.5.dp, accent.copy(alpha = .72f)),
            shadowElevation = 12.dp,
        ) {
            LuluProfileAvatar(imageUri, fallback, 154)
        }
        Spacer(Modifier.height(8.dp))
        Text(name, color = ApocalypseV4Colors.textOnDark, fontSize = 14.sp, fontWeight = FontWeight.Black)
        Text(subtitle, color = accent, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TinyStageStat(text: String) {
    Surface(color = Color.Black.copy(alpha = .28f), shape = RoundedCornerShape(8.dp)) {
        Text(text, color = ApocalypseV4Colors.textMutedDark, fontSize = 7.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
    }
}

private fun speakerLabel(page: ApocalypseStoryPage, party: List<CharacterSettings>, userName: String): String = when (page.speakerKind) {
    ApocalypseStorySpeakerKind.Narrator -> "旁白"
    ApocalypseStorySpeakerKind.Player -> userName.ifBlank { "我" }
    ApocalypseStorySpeakerKind.Character -> party.firstOrNull { it.characterId == page.characterId }?.displayName ?: "同行角色"
}

@Composable
private fun ApocalypseV4InventorySheet(save: ApocalypseV3Save) {
    Column(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(.78f).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("物资、道具与线索", color = ApocalypseV4Colors.ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
        ApocalypseV4StatusPanel(save.stats, save.director.phase, save.director.location)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(save.director.assets, key = { it.id }) { asset ->
                Surface(color = ApocalypseV4Colors.paperStrong, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color(0xFFE1D9CD))) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(assetIconV4(asset.kind), null, tint = assetColorV4(asset.kind), modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(asset.title, color = ApocalypseV4Colors.ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (asset.quantity > 1) Text("×${asset.quantity}", color = ApocalypseV4Colors.muted, fontSize = 10.sp)
                        }
                        Text("${asset.kind.label}${asset.tag.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}", color = assetColorV4(asset.kind), fontSize = 9.sp)
                        Text(asset.detail, color = ApocalypseV4Colors.muted, fontSize = 11.sp, lineHeight = 17.sp)
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun ApocalypseV4MapSheet(locations: List<ApocalypseV3Location>, onChoose: (ApocalypseV3Location) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(.72f).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("区域地图", color = ApocalypseV4Colors.ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(locations, key = { it.id }) { location ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { if (location.unlocked) onChoose(location) },
                    enabled = location.unlocked,
                    color = if (location.unlocked) ApocalypseV4Colors.paperStrong else Color(0xFFEAE6DE),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFFE1D9CD)),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (location.unlocked) Icons.Outlined.Place else Icons.Outlined.Lock, null, tint = Color(0xFF8A744C))
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text(location.name, color = ApocalypseV4Colors.ink, fontWeight = FontWeight.Bold)
                            Text(location.detail, color = ApocalypseV4Colors.muted, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun ApocalypseV4StatusPanel(stats: ApocalypseV3Stats, phase: String, location: String) {
    Surface(color = ApocalypseV4Colors.night, shape = RoundedCornerShape(21.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$phase · $location", color = ApocalypseV4Colors.textOnDark, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatusValueV4("食物", stats.food.toString())
                StatusValueV4("饮水", stats.water.toString())
                StatusValueV4("药物", stats.medicine.toString())
                StatusValueV4("材料", stats.materials.toString())
            }
            HorizontalDivider(color = ApocalypseV4Colors.line)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatusValueV4("晶核", stats.crystalCores.toString())
                StatusValueV4("空间", "Lv.${stats.playerAbilityLevel}")
                StatusValueV4("容量", "${playerSpaceCapacityM3(stats.playerAbilityLevel)}m³")
                StatusValueV4("基地", if (stats.baseLevel <= 0) "无" else "Lv.${stats.baseLevel}")
            }
        }
    }
}

@Composable
private fun StatusValueV4(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = ApocalypseV4Colors.amber, fontSize = 14.sp, fontWeight = FontWeight.Black)
        Text(label, color = ApocalypseV4Colors.textMutedDark, fontSize = 8.sp)
    }
}

@Composable
private fun ApocalypseV4SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = ApocalypseV4Colors.ink, fontSize = 19.sp, fontWeight = FontWeight.Black)
        Text(subtitle, color = ApocalypseV4Colors.muted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

private fun abilityRarityColorV4(rarity: ApocalypseAbilityRarity): Color = when (rarity) {
    ApocalypseAbilityRarity.None -> Color(0xFF7D7B76)
    ApocalypseAbilityRarity.Common -> Color(0xFF6B8D72)
    ApocalypseAbilityRarity.Uncommon -> Color(0xFF708BA8)
    ApocalypseAbilityRarity.Rare -> Color(0xFF9474AD)
    ApocalypseAbilityRarity.Exceptional -> Color(0xFFB78545)
}

private fun assetIconV4(kind: ApocalypseV3AssetKind): ImageVector = when (kind) {
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

private fun assetColorV4(kind: ApocalypseV3AssetKind): Color = when (kind) {
    ApocalypseV3AssetKind.Food, ApocalypseV3AssetKind.Water -> ApocalypseV4Colors.blue
    ApocalypseV3AssetKind.Medicine -> ApocalypseV4Colors.red
    ApocalypseV3AssetKind.Material, ApocalypseV3AssetKind.Tool -> ApocalypseV4Colors.green
    ApocalypseV3AssetKind.Weapon, ApocalypseV3AssetKind.Vehicle -> Color(0xFF8A744C)
    ApocalypseV3AssetKind.Key, ApocalypseV3AssetKind.Document, ApocalypseV3AssetKind.Map -> ApocalypseV4Colors.amber
    ApocalypseV3AssetKind.Clue -> ApocalypseV4Colors.blue
    ApocalypseV3AssetKind.Core -> ApocalypseV4Colors.purple
}
