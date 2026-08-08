package com.jiacimu.lulu.games

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.LuluProfileAvatar
import com.jiacimu.lulu.R
import com.jiacimu.lulu.data.CharacterSettings
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

internal const val APOCALYPSE_PLAYER_SECONDARY_KEY = "__player_secondary__"

private enum class ApocalypseV5Screen { Home, AbilitySettings, SystemSettings, World, Archive, Play }

private data class ApocalypseAbilityTarget(
    val id: String,
    val name: String,
    val playerSecondary: Boolean = false,
)

private object ApocalypseV5Colors {
    val black = Color(0xFF07111F)
    val blackSoft = Color(0xFF0E1B2B)
    val blackLine = Color(0xFF1D3550)
    val white = Color(0xFFFFFFFF)
    val background = Color(0xFFF3F8FD)
    val surfaceBlue = Color(0xFFEAF4FF)
    val surfaceBlueStrong = Color(0xFFD8ECFF)
    val ink = Color(0xFF0A1726)
    val muted = Color(0xFF607287)
    val border = Color(0xFFD3E3F2)
    val blue = Color(0xFF4EA8FF)
    val blueStrong = Color(0xFF2387E8)
    val blueSoft = Color(0xFF93CCFF)
    val textOnDark = Color(0xFFF8FBFF)
    val textMutedDark = Color(0xFFAEC4D9)
}

@Composable
private fun ApocalypseV5PhotoCard(
    drawableRes: Int,
    title: String,
    subtitle: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ApocalypseV5Colors.black,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, ApocalypseV5Colors.blackLine),
    ) {
        Box(Modifier.fillMaxWidth().height(190.dp)) {
            Image(
                painter = painterResource(drawableRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0x6607111F), ApocalypseV5Colors.black.copy(alpha = .96f)),
                    ),
                ),
            )
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(15.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(title, color = ApocalypseV5Colors.white, fontSize = 18.sp, fontWeight = FontWeight.Black)
                Text(subtitle, color = ApocalypseV5Colors.textMutedDark, fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
    }
}

private class ApocalypseReadingProgressStoreV5(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("apocalypse_reading_progress", Context.MODE_PRIVATE)
    private fun key(saveId: String, scene: Int) = "${saveId}_$scene"
    fun load(saveId: String, scene: Int): Int = prefs.getInt(key(saveId, scene), 0).coerceAtLeast(0)
    fun save(saveId: String, scene: Int, page: Int) = prefs.edit().putInt(key(saveId, scene), page.coerceAtLeast(0)).apply()
    fun clear() = prefs.edit().clear().apply()
}

internal fun apocalypseSpaceAbilityDefinitionV5(): ApocalypseAbilityDefinition = ApocalypseAbilityDefinition(
    id = "space",
    name = "空间",
    rarity = ApocalypseAbilityRarity.Exceptional,
    potential = "S+",
    description = "极少见的规则型异能。可从储物与位置控制成长到裂隙、闪位、空间锁与领域；上限极高，但高强度使用仍有明显精神负荷。",
    branches = listOf("空间纳藏", "空间切割", "闪位与折叠", "空间领域"),
)

internal fun apocalypseAbilityCatalogV5(): List<ApocalypseAbilityDefinition> = buildList {
    add(apocalypseSpaceAbilityDefinitionV5())
    addAll(apocalypseCompanionAbilityCatalog().filterNot { it.id == "space" })
}

internal fun apocalypseAbilityDefinitionV5(choice: ApocalypseAbilityChoice): ApocalypseAbilityDefinition =
    if (choice.abilityId == "space") apocalypseSpaceAbilityDefinitionV5() else companionAbilityDefinition(choice)

internal fun apocalypsePlayerSecondaryChoiceV5(config: ApocalypseV3Config): ApocalypseAbilityChoice =
    config.partyAbilities[APOCALYPSE_PLAYER_SECONDARY_KEY] ?: ApocalypseAbilityChoice()

@Composable
internal fun ApocalypseSurvivalAppV5(
    gameStore: LuluGameStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val storage = remember(context) { ApocalypseSurvivalV3Store(context) }
    val progressStore = remember(context) { ApocalypseReadingProgressStoreV5(context) }
    val gameState by gameStore.state.collectAsState()
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    var screen by remember { mutableStateOf(ApocalypseV5Screen.Home) }
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
        screen = ApocalypseV5Screen.Play
    }

    fun goBack() {
        if (screen == ApocalypseV5Screen.Home) onBack() else screen = ApocalypseV5Screen.Home
    }

    BackHandler(onBack = ::goBack)

    when (screen) {
        ApocalypseV5Screen.Home -> ApocalypseV5HomePage(
            save = save,
            config = config,
            onBack = onBack,
            onEnter = ::enterGame,
            onAbilities = { screen = ApocalypseV5Screen.AbilitySettings },
            onSystem = { screen = ApocalypseV5Screen.SystemSettings },
            onWorld = { screen = ApocalypseV5Screen.World },
            onArchive = { screen = ApocalypseV5Screen.Archive },
        )

        ApocalypseV5Screen.AbilitySettings -> ApocalypseV5AbilitySettingsPage(
            config = config,
            selectedPartyIds = gameState.selectedCharacterIds,
            characters = characters.values.toList(),
            onBack = ::goBack,
            onSave = { choices, partyIds ->
                config = config.copy(partyAbilities = choices)
                storage.saveConfig(config)
                if (partyIds.isNotEmpty()) gameStore.selectCharacters(partyIds.take(4))
                val current = save
                if (current != null && partyIds.isNotEmpty() && current.partyIds != partyIds.take(4)) {
                    val updated = current.copy(partyIds = partyIds.take(4), updatedAt = System.currentTimeMillis())
                    save = updated
                    storage.save(updated)
                }
                screen = ApocalypseV5Screen.Home
            },
        )

        ApocalypseV5Screen.SystemSettings -> ApocalypseV5SystemSettingsPage(
            config = config,
            onBack = ::goBack,
            onSave = { worldMode, delayMillis ->
                config = config.copy(worldMode = worldMode, autoDelayMillis = delayMillis)
                storage.saveConfig(config)
                screen = ApocalypseV5Screen.Home
            },
        )

        ApocalypseV5Screen.World -> ApocalypseV5WorldPage(config, ::goBack)

        ApocalypseV5Screen.Archive -> ApocalypseV5ArchivePage(
            save = save,
            onBack = ::goBack,
            onClear = {
                storage.clearSave()
                progressStore.clear()
                save = null
            },
        )

        ApocalypseV5Screen.Play -> {
            val current = save
            if (current == null) {
                LaunchedEffect(Unit) { enterGame() }
            } else {
                ApocalypseV5PlayPage(
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
private fun ApocalypseV5HomePage(
    save: ApocalypseV3Save?,
    config: ApocalypseV3Config,
    onBack: () -> Unit,
    onEnter: () -> Unit,
    onAbilities: () -> Unit,
    onSystem: () -> Unit,
    onWorld: () -> Unit,
    onArchive: () -> Unit,
) {
    val secondary = apocalypseAbilityDefinitionV5(apocalypsePlayerSecondaryChoiceV5(config))
    Scaffold(
        containerColor = ApocalypseV5Colors.background,
        topBar = {
            TopAppBar(
                title = { Text("末世求生", fontWeight = FontWeight.Black, color = ApocalypseV5Colors.ink) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ApocalypseV5Colors.background),
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
                    color = ApocalypseV5Colors.black,
                    contentColor = ApocalypseV5Colors.textOnDark,
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Box(
                        Modifier.background(
                            Brush.linearGradient(
                                listOf(ApocalypseV5Colors.black, Color(0xFF0D2A46), ApocalypseV5Colors.blackSoft),
                            ),
                        ),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("RED TIDE / 赤潮纪元", color = ApocalypseV5Colors.blue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("活下去，也保留选择。", fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.Black)
                            Text("视觉小说式长篇 · 自由行动 · 空间成长 · 基地与群像", color = ApocalypseV5Colors.textMutedDark, fontSize = 11.sp)
                            if (save != null) {
                                HorizontalDivider(color = ApocalypseV5Colors.blackLine)
                                Text("${save.director.phase} · 第${save.scene}幕 · ${save.director.location}", color = ApocalypseV5Colors.textMutedDark, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
            item {
        ApocalypseV5PhotoCard(
            R.drawable.apocalypse_city_night,
            "灾后城市边缘",
            "夜色、工业烟雾与断续灯火会成为赤潮纪元的第一层视觉记忆。",
        )
    }
            if (save != null) { item { ApocalypseSurvivalSnapshotV5(save) } }
            item { ApocalypseV5MenuEntry(Icons.Outlined.PlayArrow, "进入游戏", if (save == null) "从灾前第七日开始" else "继续第 ${save.scene} 幕", onEnter, emphasis = true) }
            item { ApocalypseV5MenuEntry(Icons.Outlined.AutoAwesome, "异能设定", "你与同行角色的异能、分化和队伍配置", onAbilities) }
            item { ApocalypseV5MenuEntry(Icons.Outlined.Tune, "系统设置", "世界强度与自动播放速度", onSystem) }
            item { ApocalypseV5MenuEntry(Icons.Outlined.Public, "世界档案", "赤潮生态、异能社会、丧尸进化与长期剧情骨架", onWorld) }
            item { ApocalypseV5MenuEntry(Icons.Outlined.History, "存档与回顾", "基地、物资、晶核和最近剧情", onArchive) }
            item {
                Surface(
                    color = ApocalypseV5Colors.surfaceBlue,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, ApocalypseV5Colors.border),
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("你的异能槽", fontWeight = FontWeight.Black, color = ApocalypseV5Colors.ink)
                        Text("① 空间 · 固定主异能", color = ApocalypseV5Colors.blueStrong, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(
                            if (secondary.id == "none") "② 第二异能 · 尚未选择" else "② ${secondary.name} · ${secondary.rarity.label}",
                            color = ApocalypseV5Colors.muted,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
            item {
                val configured = config.partyAbilities
                    .filterKeys { it != APOCALYPSE_PLAYER_SECONDARY_KEY }
                    .values.count { it.abilityId != "none" }
                Text("已为 $configured 位同行角色设置异能。灾前稳定觉醒率约 8%；末世淘汰会让幸存者中的异能者比例逐步升高，但异能依然不是人人都有。", color = ApocalypseV5Colors.muted, fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
    }
}

@Composable
private fun ApocalypseV5MenuEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    emphasis: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        color = if (emphasis) ApocalypseV5Colors.surfaceBlueStrong else ApocalypseV5Colors.white,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, if (emphasis) ApocalypseV5Colors.blue else ApocalypseV5Colors.border),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = ApocalypseV5Colors.surfaceBlue, shape = RoundedCornerShape(14.dp)) {
                Icon(icon, null, tint = ApocalypseV5Colors.blueStrong, modifier = Modifier.padding(10.dp).size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = ApocalypseV5Colors.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = ApocalypseV5Colors.muted, fontSize = 11.sp)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = ApocalypseV5Colors.muted)
        }
    }
}

@Composable
private fun ApocalypseV5AbilitySettingsPage(
    config: ApocalypseV3Config,
    selectedPartyIds: List<String>,
    characters: List<CharacterSettings>,
    onBack: () -> Unit,
    onSave: (Map<String, ApocalypseAbilityChoice>, List<String>) -> Unit,
) {
    val context = LocalContext.current
    val userPrefs = remember(context) { context.getSharedPreferences("lulu_user_profile", Context.MODE_PRIVATE) }
    val userName = userPrefs.getString("display_name", "我").orEmpty().ifBlank { "我" }
    val userAvatarUri = userPrefs.getString("avatar_uri", null)
    var party by remember(selectedPartyIds) { mutableStateOf(selectedPartyIds.take(4).toSet()) }
    var choices by remember(config.partyAbilities) { mutableStateOf(config.partyAbilities) }
    var target by remember { mutableStateOf<ApocalypseAbilityTarget?>(null) }

    val editing = target
    if (editing != null) {
        ApocalypseV5AbilityPickerPage(
            target = editing,
            initialChoice = choices[editing.id] ?: ApocalypseAbilityChoice(),
            onBack = { target = null },
            onConfirm = { next ->
                choices = choices + (editing.id to next)
                target = null
            },
        )
        return
    }

    val secondaryChoice = choices[APOCALYPSE_PLAYER_SECONDARY_KEY] ?: ApocalypseAbilityChoice()
    val secondary = apocalypseAbilityDefinitionV5(secondaryChoice)

    Scaffold(
        containerColor = ApocalypseV5Colors.background,
        topBar = {
            TopAppBar(
                title = { Text("异能设定", fontWeight = FontWeight.Black) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                actions = {
                    TextButton(onClick = { onSave(choices, party.toList()) }) {
                        Text("保存", color = ApocalypseV5Colors.blueStrong, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ApocalypseV5Colors.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            item {
                ApocalypseV5SectionTitle("你", "玩家拥有两个异能槽；空间固定占据第一槽")
                Spacer(Modifier.height(7.dp))
                Surface(color = ApocalypseV5Colors.white, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, ApocalypseV5Colors.blue)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LuluProfileAvatar(userAvatarUri, userName.take(1).ifBlank { "我" }, 50)
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(userName, color = ApocalypseV5Colors.ink, fontWeight = FontWeight.Black, fontSize = 18.sp)
                                Text("主角 · 双异能槽", color = ApocalypseV5Colors.blueStrong, fontSize = 11.sp)
                            }
                        }
                        Surface(color = ApocalypseV5Colors.surfaceBlue, shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("第一异能 · 空间", color = ApocalypseV5Colors.blueStrong, fontWeight = FontWeight.Bold)
                                Text("固定主异能。48m³纳藏起步，后续成长为裂隙、闪位、空间锁与领域。", color = ApocalypseV5Colors.muted, fontSize = 11.sp, lineHeight = 16.sp)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("第二异能", color = ApocalypseV5Colors.ink, fontWeight = FontWeight.Bold)
                                Text(if (secondary.id == "none") "尚未选择" else "${secondary.rarity.label} · ${secondary.name} · ${secondaryChoice.branch}", color = ApocalypseV5Colors.muted, fontSize = 11.sp)
                            }
                            TextButton(onClick = { target = ApocalypseAbilityTarget(APOCALYPSE_PLAYER_SECONDARY_KEY, userName, true) }) {
                                Text(if (secondary.id == "none") "选择" else "更改", color = ApocalypseV5Colors.blueStrong)
                            }
                        }
                    }
                }
            }

            item {
                Surface(color = ApocalypseV5Colors.black, shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("异能稀有度规则", color = ApocalypseV5Colors.blue, fontWeight = FontWeight.Black)
                        Text("灾前约 8% 人口形成稳定异能，约 92% 仍是普通人。末世后普通人平均死亡率更高，因此幸存者群体里的异能者比例会逐渐上升；但除特殊据点外，不把异能者写成多数。", color = ApocalypseV5Colors.textMutedDark, fontSize = 11.sp, lineHeight = 17.sp)
                    }
                }
            }

            item { ApocalypseV5SectionTitle("同行角色", "最多4人；普通人依旧可以靠专业技能成为队伍核心") }
            items(characters.sortedBy { it.displayName }, key = { it.characterId }) { character ->
                val selected = character.characterId in party
                val choice = choices[character.characterId] ?: ApocalypseAbilityChoice()
                val ability = apocalypseAbilityDefinitionV5(choice)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = if (selected) ApocalypseV5Colors.surfaceBlue else ApocalypseV5Colors.white,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, if (selected) ApocalypseV5Colors.blue else ApocalypseV5Colors.border),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LuluProfileAvatar(character.avatarUri, character.displayName.take(1).ifBlank { "角" }, 46)
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(character.displayName.ifBlank { "未命名" }, fontWeight = FontWeight.Bold, color = ApocalypseV5Colors.ink)
                                Text(if (selected) "${ability.rarity.label} · ${ability.name} · 潜力${ability.potential}" else "未加入当前同行队伍", color = ApocalypseV5Colors.muted, fontSize = 10.sp)
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
                                colors = CheckboxDefaults.colors(checkedColor = ApocalypseV5Colors.blueStrong),
                            )
                        }
                        if (selected) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("分化：${choice.branch}", color = ApocalypseV5Colors.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { target = ApocalypseAbilityTarget(character.characterId, character.displayName) }) {
                                    Text("设置异能", color = ApocalypseV5Colors.blueStrong)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApocalypseV5AbilityPickerPage(
    target: ApocalypseAbilityTarget,
    initialChoice: ApocalypseAbilityChoice,
    onBack: () -> Unit,
    onConfirm: (ApocalypseAbilityChoice) -> Unit,
) {
    val catalog = remember(target.playerSecondary) {
        if (target.playerSecondary) apocalypseAbilityCatalogV5().filterNot { it.id == "space" }
        else apocalypseAbilityCatalogV5()
    }
    var choice by remember(initialChoice) { mutableStateOf(initialChoice) }
    val current = apocalypseAbilityDefinitionV5(choice)

    Scaffold(
        containerColor = ApocalypseV5Colors.background,
        topBar = {
            TopAppBar(
                title = { Text("${target.name} · 选择异能", fontWeight = FontWeight.Black) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                actions = {
                    TextButton(onClick = { onConfirm(choice) }) {
                        Text("确定", color = ApocalypseV5Colors.blueStrong, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ApocalypseV5Colors.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            item {
                Surface(color = ApocalypseV5Colors.black, shape = RoundedCornerShape(19.dp), border = BorderStroke(1.dp, ApocalypseV5Colors.blackLine)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("${current.rarity.label} · ${current.name} · 潜力${current.potential}", color = ApocalypseV5Colors.blue, fontWeight = FontWeight.Black, fontSize = 17.sp)
                        Text(current.description, color = ApocalypseV5Colors.textMutedDark, fontSize = 11.sp, lineHeight = 17.sp)
                        if (current.branches.size > 1) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                current.branches.forEach { branch ->
                                    FilterChip(
                                        selected = choice.branch == branch,
                                        onClick = { choice = choice.copy(branch = branch) },
                                        label = { Text(branch, fontSize = 10.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = ApocalypseV5Colors.blueStrong,
                                            selectedLabelColor = ApocalypseV5Colors.white,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            items(catalog, key = { it.id }) { ability ->
                val selected = ability.id == choice.abilityId
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { choice = ApocalypseAbilityChoice(ability.id, ability.branches.firstOrNull() ?: ability.name) },
                    color = if (selected) ApocalypseV5Colors.surfaceBlueStrong else ApocalypseV5Colors.white,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, if (selected) ApocalypseV5Colors.blue else ApocalypseV5Colors.border),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = ApocalypseV5Colors.surfaceBlue, shape = RoundedCornerShape(9.dp)) {
                            Text(ability.rarity.label, color = abilityRarityColorV5(ability.rarity), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp))
                        }
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ability.name, color = ApocalypseV5Colors.ink, fontWeight = FontWeight.Bold)
                            Text("潜力${ability.potential} · ${ability.branches.joinToString(" / ")}", color = ApocalypseV5Colors.muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (selected) Icon(Icons.Outlined.Check, null, tint = ApocalypseV5Colors.blueStrong)
                    }
                }
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}

@Composable
private fun ApocalypseV5SystemSettingsPage(
    config: ApocalypseV3Config,
    onBack: () -> Unit,
    onSave: (String, Long) -> Unit,
) {
    var worldMode by remember(config.worldMode) { mutableStateOf(config.worldMode) }
    var speed by remember(config.autoDelayMillis) { mutableLongStateOf(config.autoDelayMillis) }
    Scaffold(
        containerColor = ApocalypseV5Colors.background,
        topBar = {
            TopAppBar(
                title = { Text("系统设置", fontWeight = FontWeight.Black) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                actions = {
                    TextButton(onClick = { onSave(worldMode, speed) }) {
                        Text("保存", color = ApocalypseV5Colors.blueStrong, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ApocalypseV5Colors.background),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            item { ApocalypseV5SectionTitle("世界强度", "只调整资源和进化压力，不改写角色与世界基本规则") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("标准异变", "资源荒年", "高危进化").forEach { mode ->
                        val selected = worldMode == mode
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { worldMode = mode },
                            color = if (selected) ApocalypseV5Colors.surfaceBlueStrong else ApocalypseV5Colors.white,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, if (selected) ApocalypseV5Colors.blue else ApocalypseV5Colors.border),
                        ) {
                            Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(mode, Modifier.weight(1f), color = ApocalypseV5Colors.ink, fontWeight = FontWeight.Bold)
                                if (selected) Icon(Icons.Outlined.Check, null, tint = ApocalypseV5Colors.blueStrong)
                            }
                        }
                    }
                }
            }
            item { ApocalypseV5SectionTitle("自动播放速度", "手动模式仍可随时前后翻看") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2_000L to "快", 2_800L to "标准", 4_000L to "慢").forEach { (value, label) ->
                        FilterChip(
                            selected = speed == value,
                            onClick = { speed = value },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ApocalypseV5Colors.blueStrong,
                                selectedLabelColor = ApocalypseV5Colors.white,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ApocalypseV5WorldPage(config: ApocalypseV3Config, onBack: () -> Unit) {
    val lore = remember {
        apocalypseWorldLoreV3().map { (title, detail) ->
            if (title == "人类与异能生态") {
                title to "绝大多数人仍是普通人。灾前约8%人口形成稳定异能，约92%没有稳定异能；末世后由于普通人生存率更低，幸存者中的异能者比例会逐渐上升，但除异能者聚居地外通常仍不应成为多数。体能与感官强化占异能中的大头，元素与念动力更少，空间、预知等规则型能力极其罕见。"
            } else title to detail
        }
    }
    Scaffold(
        containerColor = ApocalypseV5Colors.background,
        topBar = {
            TopAppBar(
                title = { Text("世界档案", fontWeight = FontWeight.Black) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ApocalypseV5Colors.background),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Surface(color = ApocalypseV5Colors.black, shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("赤潮纪元", color = ApocalypseV5Colors.blue, fontWeight = FontWeight.Bold)
                        Text("这是一个正在重新长出来的世界。", color = ApocalypseV5Colors.textOnDark, fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black)
                        Text("当前模式：${config.worldMode}", color = ApocalypseV5Colors.textMutedDark, fontSize = 11.sp)
                    }
                }
            }
            item {
        ApocalypseV5PhotoCard(
            R.drawable.apocalypse_factory_interior,
            "废弃工业区",
            "基地、物资与人类聚居点都从这样的空壳里重新长出来。",
        )
    }
            item { ApocalypseWorldAtlasSummaryV5() }
            items(lore, key = { it.first }) { (title, detail) ->
                Surface(color = ApocalypseV5Colors.white, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, ApocalypseV5Colors.border)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(title, color = ApocalypseV5Colors.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(detail, color = ApocalypseV5Colors.muted, fontSize = 12.sp, lineHeight = 19.sp)
                    }
                }
            }
            item { ApocalypseV5SectionTitle("空间成长", "主异能同时承担物资与战斗成长") }
            items(playerSpaceProgression()) { (level, detail) ->
                Surface(color = ApocalypseV5Colors.surfaceBlue, shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, ApocalypseV5Colors.border)) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(level, color = ApocalypseV5Colors.blueStrong, fontWeight = FontWeight.Black)
                        Text(detail, color = ApocalypseV5Colors.ink, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
            item { ApocalypseV5SectionTitle("时代走向", "只告诉你这个世界会面对什么，不提前公开导演的未来事件和伏笔") }
            items(apocalypsePublicEraGuideV5(), key = { it.first }) { (title, detail) ->
                Surface(color = ApocalypseV5Colors.white, shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, ApocalypseV5Colors.border)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, color = ApocalypseV5Colors.blueStrong, fontWeight = FontWeight.Bold)
                        Text(detail, color = ApocalypseV5Colors.muted, fontSize = 11.sp, lineHeight = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ApocalypseV5ArchivePage(save: ApocalypseV3Save?, onBack: () -> Unit, onClear: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = ApocalypseV5Colors.background,
        topBar = {
            TopAppBar(
                title = { Text("存档与回顾", fontWeight = FontWeight.Black) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ApocalypseV5Colors.background),
            )
        },
    ) { padding ->
        if (save == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("还没有存档", color = ApocalypseV5Colors.muted) }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
            ApocalypseV5PhotoCard(
                R.drawable.apocalypse_dark_tunnel,
                "地下通路",
                "撤离、探索和未知区域会保留更压迫的黑蓝氛围。",
            )
        }
                item { ApocalypseV5StatusPanel(save.stats, save.director.phase, save.director.location) }
                item { ApocalypseObjectivePanelV5(save.director) }
                item { ApocalypseBaseDashboardV5(save) }
                item { ApocalypseV5SectionTitle("最近剧情", "这里保留行动回顾") }
                items(save.log.asReversed().take(14)) { log ->
                    Surface(color = ApocalypseV5Colors.white, shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, ApocalypseV5Colors.border)) {
                        Text(log, color = ApocalypseV5Colors.ink, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(13.dp), maxLines = 7, overflow = TextOverflow.Ellipsis)
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
private fun ApocalypseV5PlayPage(
    save: ApocalypseV3Save,
    config: ApocalypseV3Config,
    gameStore: LuluGameStore,
    characters: Map<String, CharacterSettings>,
    progressStore: ApocalypseReadingProgressStoreV5,
    onBack: () -> Unit,
    onSave: (ApocalypseV3Save) -> Unit,
) {
    val context = LocalContext.current
    val userPrefs = remember(context) { context.getSharedPreferences("lulu_user_profile", Context.MODE_PRIVATE) }
    val userName = remember { userPrefs.getString("display_name", "我").orEmpty().ifBlank { "我" } }
    val userAvatarUri = remember { userPrefs.getString("avatar_uri", null) }
    val scope = rememberCoroutineScope()
    val party = save.partyIds.map { id -> characters[id] ?: MigratedDomainStores.characters.get(id) }
    val pages = remember(save.scene, save.narration, party) { parseApocalypseStoryPages(save.narration, party) }
    var pageIndex by remember(save.id, save.scene, pages.size) { mutableIntStateOf(progressStore.load(save.id, save.scene).coerceIn(0, pages.lastIndex.coerceAtLeast(0))) }
    var action by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var autoPlay by remember { mutableStateOf(false) }
    var showInventory by remember { mutableStateOf(false) }
    var showMapPage by remember { mutableStateOf(false) }
    val currentPage = pages.getOrElse(pageIndex) { pages.first() }
    val lastPage = pageIndex >= pages.lastIndex

    fun setPage(next: Int) {
        pageIndex = next.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
        progressStore.save(save.id, save.scene, pageIndex)
    }
    fun nextPage() { if (pageIndex < pages.lastIndex) setPage(pageIndex + 1) else autoPlay = false }
    fun previousPage() { if (pageIndex > 0) { autoPlay = false; setPage(pageIndex - 1) } }

    if (showMapPage) {
        ApocalypseWorldMapPageV5(
            save = save,
            currentLocation = save.director.location,
            discoveredLocations = save.director.locations,
            onBack = { showMapPage = false },
            onPlan = { location ->
                action = "我打开地图研究${location.name}，先确认路线、天气、道路、感染者和人类活动，再决定如何前往。"
                showMapPage = false
            },
        )
        return
    }

    LaunchedEffect(autoPlay, pageIndex, save.scene) {
        if (!autoPlay || lastPage) return@LaunchedEffect
        delay(config.autoDelayMillis + currentPage.text.length.coerceAtMost(96) * 16L)
        nextPage()
    }

    fun submit() {
        val clean = action.trim()
        if (clean.isBlank() || busy || !lastPage) return
        scope.launch {
            busy = true
            val beat = planApocalypseV5Beat(save, config, party, clean)
            val nextStats = applyApocalypseV3Beat(save.stats, beat)
            writeApocalypseV5Scene(save, config, party, clean, beat, nextStats)
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
                        JSONObject().put("scene", save.scene).put("action", clean).put("phase", beat.nextDirector.phase).put("location", beat.nextDirector.location).put("cores", nextStats.crystalCores).put("spaceLevel", nextStats.playerAbilityLevel).toString(),
                    )
                    gameStore.attachCharacterReply(recordId, text.replace(Regex("【[^】]+】"), ""))
                    action = ""
                    autoPlay = false
                }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().background(ApocalypseV5Colors.black).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回", tint = ApocalypseV5Colors.textOnDark) }
            Column(Modifier.weight(1f)) {
                Text("末世求生", color = ApocalypseV5Colors.textOnDark, fontSize = 17.sp, fontWeight = FontWeight.Black)
                Text("${apocalypseDayLabelV5(save.director.dayIndex)} ${apocalypseClockLabelV5(save.director.clockMinutes)} · ${save.director.weather} ${save.director.temperatureC}℃ · 第${save.scene}幕", color = ApocalypseV5Colors.blue, fontSize = 9.sp)
            }
        }

        ApocalypseV5SpeakerStage(
            modifier = Modifier.fillMaxWidth().weight(1f),
            page = currentPage,
            party = party,
            config = config,
            location = save.director.location,
            tension = save.director.tension,
            stats = save.stats,
            userName = userName,
            userAvatarUri = userAvatarUri,
            onMap = { showMapPage = true },
            onInventory = { showInventory = true },
            onAdvance = { if (!lastPage && !busy) nextPage() },
        )

        Surface(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            color = ApocalypseV5Colors.background,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(apocalypseV5SpeakerLabel(currentPage, party, userName), color = ApocalypseV5Colors.blueStrong, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${pageIndex + 1}/${pages.size}", color = ApocalypseV5Colors.muted, fontSize = 9.sp)
                }
                Spacer(Modifier.height(5.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp, max = 146.dp).clickable(enabled = !lastPage && !busy) { nextPage() },
                    color = ApocalypseV5Colors.white,
                    shape = RoundedCornerShape(17.dp),
                    border = BorderStroke(1.dp, ApocalypseV5Colors.border),
                ) {
                    Text(currentPage.text, color = ApocalypseV5Colors.ink, fontSize = 16.sp, lineHeight = 24.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
                }
                Row(Modifier.fillMaxWidth().height(39.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = ::previousPage, enabled = pageIndex > 0) { Icon(Icons.Outlined.ChevronLeft, null, Modifier.size(18.dp)); Text("上一段", fontSize = 10.sp) }
                    TextButton(onClick = { autoPlay = !autoPlay }, enabled = !lastPage) { Icon(if (autoPlay) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null, Modifier.size(17.dp)); Spacer(Modifier.width(3.dp)); Text(if (autoPlay) "暂停" else "自动", fontSize = 10.sp) }
                    TextButton(onClick = ::nextPage, enabled = !lastPage) { Text("下一段", fontSize = 10.sp); Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp)) }
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
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ApocalypseV5Colors.blueStrong, unfocusedBorderColor = ApocalypseV5Colors.border),
                    )
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SuggestionChip(onClick = { action = "我仔细搜集能长期保存的食物、饮水、药物、能源和工具，并优先利用空间异能降低搬运风险。" }, label = { Text("搜物资", fontSize = 9.sp) })
                        SuggestionChip(onClick = { action = "我重新评估当前据点的水源、出入口、防御、排污、能源和撤退路线。" }, label = { Text("看基地", fontSize = 9.sp) })
                        SuggestionChip(onClick = { action = "我检查并训练自己的两个异能槽，优先练习当前等级已经允许的能力。" }, label = { Text("练异能", fontSize = 9.sp) })
                    }
                    Spacer(Modifier.height(5.dp))
                    Button(
                        onClick = ::submit,
                        enabled = action.isNotBlank() && !busy,
                        modifier = Modifier.fillMaxWidth().height(43.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ApocalypseV5Colors.blueStrong, contentColor = ApocalypseV5Colors.white),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text(if (busy) "导演正在重排剧情……" else "行动", fontWeight = FontWeight.Black) }
                }
            }
        }
    }

    if (showInventory) {
        ModalBottomSheet(onDismissRequest = { showInventory = false }, containerColor = ApocalypseV5Colors.background) { ApocalypseV5InventorySheet(save) }
    }
}

@Composable
private fun ApocalypseV5SpeakerStage(
    modifier: Modifier,
    page: ApocalypseStoryPage,
    party: List<CharacterSettings>,
    config: ApocalypseV3Config,
    location: String,
    tension: Int,
    stats: ApocalypseV3Stats,
    userName: String,
    userAvatarUri: String?,
    onMap: () -> Unit,
    onInventory: () -> Unit,
    onAdvance: () -> Unit,
) {
    val character = page.characterId?.let { id -> party.firstOrNull { it.characterId == id } }
    val secondary = apocalypseAbilityDefinitionV5(apocalypsePlayerSecondaryChoiceV5(config))
    Box(modifier.background(Brush.verticalGradient(listOf(ApocalypseV5Colors.black, Color(0xFF10283E), ApocalypseV5Colors.blackSoft))).clickable(onClick = onAdvance)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(location, color = ApocalypseV5Colors.textOnDark, fontSize = 17.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("空间 Lv.${stats.playerAbilityLevel} · ${playerSpaceCapacityM3(stats.playerAbilityLevel)}m³", color = ApocalypseV5Colors.textMutedDark, fontSize = 8.sp)
                }
                Surface(color = ApocalypseV5Colors.surfaceBlue.copy(alpha = .12f), shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, ApocalypseV5Colors.blackLine)) {
                    Text("威胁 $tension/10", color = ApocalypseV5Colors.blueSoft, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(
                    onClick = onMap,
                    color = Color.Black.copy(alpha = .28f),
                    shape = RoundedCornerShape(9.dp),
                    border = BorderStroke(1.dp, ApocalypseV5Colors.blackLine),
                ) {
                    Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Map, null, tint = ApocalypseV5Colors.blueSoft, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("地图", color = ApocalypseV5Colors.textOnDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Surface(
                    onClick = onInventory,
                    color = Color.Black.copy(alpha = .28f),
                    shape = RoundedCornerShape(9.dp),
                    border = BorderStroke(1.dp, ApocalypseV5Colors.blackLine),
                ) {
                    Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Inventory2, null, tint = ApocalypseV5Colors.blueSoft, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("物资", color = ApocalypseV5Colors.textOnDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                when (page.speakerKind) {
                    ApocalypseStorySpeakerKind.Narrator -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Icon(Icons.Outlined.Landscape, null, tint = ApocalypseV5Colors.textMutedDark.copy(alpha = .55f), modifier = Modifier.size(58.dp))
                        Text("$location · 场景", color = ApocalypseV5Colors.textMutedDark, fontSize = 10.sp)
                    }
                    ApocalypseStorySpeakerKind.Player -> ApocalypseV5SpeakerPortrait(
                        imageUri = userAvatarUri,
                        fallback = userName.take(1).ifBlank { "我" },
                        name = userName,
                        subtitle = if (secondary.id == "none") "空间系 · 主角" else "空间 + ${secondary.name}",
                    )
                    ApocalypseStorySpeakerKind.Character -> {
                        if (character == null) {
                            Icon(Icons.Outlined.Person, null, tint = ApocalypseV5Colors.textMutedDark, modifier = Modifier.size(54.dp))
                        } else {
                            val choice = companionAbilityChoice(config, character.characterId)
                            val ability = apocalypseAbilityDefinitionV5(choice)
                            ApocalypseV5SpeakerPortrait(
                                imageUri = character.avatarUri,
                                fallback = character.displayName.take(1).ifBlank { "角" },
                                name = character.displayName,
                                subtitle = if (ability.rarity == ApocalypseAbilityRarity.None) "普通人" else "${ability.name} · ${choice.branch}",
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ApocalypseV5TinyStageStat("食 ${stats.food}")
                ApocalypseV5TinyStageStat("水 ${stats.water}")
                ApocalypseV5TinyStageStat("药 ${stats.medicine}")
                ApocalypseV5TinyStageStat("晶 ${stats.crystalCores}")
                if (stats.baseLevel > 0) ApocalypseV5TinyStageStat("基地 Lv.${stats.baseLevel}")
            }
        }
    }
}

@Composable
private fun ApocalypseV5SpeakerPortrait(imageUri: String?, fallback: String, name: String, subtitle: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.size(154.dp),
            shape = RoundedCornerShape(topStart = 46.dp, topEnd = 46.dp, bottomStart = 22.dp, bottomEnd = 22.dp),
            color = ApocalypseV5Colors.blackSoft,
            border = BorderStroke(1.5.dp, ApocalypseV5Colors.blue),
            shadowElevation = 12.dp,
        ) { LuluProfileAvatar(imageUri, fallback, 154) }
        Spacer(Modifier.height(8.dp))
        Text(name, color = ApocalypseV5Colors.textOnDark, fontSize = 14.sp, fontWeight = FontWeight.Black)
        Text(subtitle, color = ApocalypseV5Colors.blue, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ApocalypseV5TinyStageStat(text: String) {
    Surface(color = Color.Black.copy(alpha = .28f), shape = RoundedCornerShape(8.dp)) {
        Text(text, color = ApocalypseV5Colors.textMutedDark, fontSize = 7.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
    }
}

private fun apocalypseV5SpeakerLabel(page: ApocalypseStoryPage, party: List<CharacterSettings>, userName: String): String = when (page.speakerKind) {
    ApocalypseStorySpeakerKind.Narrator -> "旁白"
    ApocalypseStorySpeakerKind.Player -> userName.ifBlank { "我" }
    ApocalypseStorySpeakerKind.Character -> party.firstOrNull { it.characterId == page.characterId }?.displayName ?: "同行角色"
}

@Composable
private fun ApocalypseV5InventorySheet(save: ApocalypseV3Save) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(.78f).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("物资、道具与线索", color = ApocalypseV5Colors.ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
        ApocalypseV5StatusPanel(save.stats, save.director.phase, save.director.location)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(save.director.assets, key = { it.id }) { asset ->
                Surface(color = ApocalypseV5Colors.white, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, ApocalypseV5Colors.border)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(apocalypseAssetIconV5(asset.kind), null, tint = ApocalypseV5Colors.blueStrong, modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(asset.title, color = ApocalypseV5Colors.ink, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (asset.quantity > 1) Text("×${asset.quantity}", color = ApocalypseV5Colors.muted, fontSize = 10.sp)
                        }
                        Text("${asset.kind.label}${asset.tag.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}", color = ApocalypseV5Colors.blueStrong, fontSize = 9.sp)
                        Text(asset.detail, color = ApocalypseV5Colors.muted, fontSize = 11.sp, lineHeight = 17.sp)
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun ApocalypseV5MapSheet(locations: List<ApocalypseV3Location>, onChoose: (ApocalypseV3Location) -> Unit) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(.72f).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("区域地图", color = ApocalypseV5Colors.ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(locations, key = { it.id }) { location ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { if (location.unlocked) onChoose(location) },
                    enabled = location.unlocked,
                    color = if (location.unlocked) ApocalypseV5Colors.white else ApocalypseV5Colors.surfaceBlue,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, ApocalypseV5Colors.border),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (location.unlocked) Icons.Outlined.Place else Icons.Outlined.Lock, null, tint = ApocalypseV5Colors.blueStrong)
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text(location.name, color = ApocalypseV5Colors.ink, fontWeight = FontWeight.Bold)
                            Text(location.detail, color = ApocalypseV5Colors.muted, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun ApocalypseV5StatusPanel(stats: ApocalypseV3Stats, phase: String, location: String) {
    Surface(color = ApocalypseV5Colors.black, shape = RoundedCornerShape(21.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$phase · $location", color = ApocalypseV5Colors.textOnDark, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ApocalypseV5StatusValue("食物", stats.food.toString())
                ApocalypseV5StatusValue("饮水", stats.water.toString())
                ApocalypseV5StatusValue("药物", stats.medicine.toString())
                ApocalypseV5StatusValue("材料", stats.materials.toString())
            }
            HorizontalDivider(color = ApocalypseV5Colors.blackLine)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ApocalypseV5StatusValue("晶核", stats.crystalCores.toString())
                ApocalypseV5StatusValue("空间", "Lv.${stats.playerAbilityLevel}")
                ApocalypseV5StatusValue("容量", "${playerSpaceCapacityM3(stats.playerAbilityLevel)}m³")
                ApocalypseV5StatusValue("基地", if (stats.baseLevel <= 0) "无" else "Lv.${stats.baseLevel}")
            }
            HorizontalDivider(color = ApocalypseV5Colors.blackLine)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ApocalypseV5StatusValue("生命", stats.health.toString())
                ApocalypseV5StatusValue("体力", stats.stamina.toString())
                ApocalypseV5StatusValue("感染", stats.infection.toString())
                ApocalypseV5StatusValue("士气", stats.morale.toString())
            }
        }
    }
}

@Composable
private fun ApocalypseV5StatusValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = ApocalypseV5Colors.blue, fontSize = 14.sp, fontWeight = FontWeight.Black)
        Text(label, color = ApocalypseV5Colors.textMutedDark, fontSize = 8.sp)
    }
}

@Composable
private fun ApocalypseV5SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = ApocalypseV5Colors.ink, fontSize = 19.sp, fontWeight = FontWeight.Black)
        Text(subtitle, color = ApocalypseV5Colors.muted, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

private fun abilityRarityColorV5(rarity: ApocalypseAbilityRarity): Color = when (rarity) {
    ApocalypseAbilityRarity.None -> ApocalypseV5Colors.muted
    ApocalypseAbilityRarity.Common -> Color(0xFF5B8DB9)
    ApocalypseAbilityRarity.Uncommon -> Color(0xFF397FBF)
    ApocalypseAbilityRarity.Rare -> Color(0xFF236EAF)
    ApocalypseAbilityRarity.Exceptional -> ApocalypseV5Colors.blueStrong
}

private fun apocalypseAssetIconV5(kind: ApocalypseV3AssetKind): ImageVector = when (kind) {
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
