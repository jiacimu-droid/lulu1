package com.jiacimu.lulu.games

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import java.util.UUID

internal const val APOCALYPSE_PLAYER_SECONDARY_KEY = "__player_secondary__"

private enum class ApocalypseV5Screen { Home, AbilitySettings, SystemSettings, World, Archive, Play, StoryHistory }

private data class ApocalypseAbilityTarget(
    val id: String,
    val name: String,
    val playerSecondary: Boolean = false,
)

private object ApocalypseV5Colors {
    val black = Color(0xFF101714)
    val blackSoft = Color(0xFF18211D)
    val blackLine = Color(0xFF34423B)
    val white = Color(0xFFFFFFFF)
    val background = Color(0xFFF5F6F3)
    val surfaceBlue = Color(0xFFEDF1ED)
    val surfaceBlueStrong = Color(0xFFDDE7E0)
    val ink = Color(0xFF1B211E)
    val muted = Color(0xFF68726C)
    val border = Color(0xFFD9DED9)
    val blue = Color(0xFFB7CDBF)
    val blueStrong = Color(0xFF526D5E)
    val blueSoft = Color(0xFFC9D9CF)
    val textOnDark = Color(0xFFF7F9F6)
    val textMutedDark = Color(0xFFB8C5BD)
}

private fun apocalypseV5SceneImage(location: String, text: String): Int {
    return apocalypseV5SceneImageMatch(location.lowercase())
        ?: apocalypseV5SceneImageMatch(text.lowercase())
        ?: R.drawable.apocalypse_city_night
}

private fun apocalypseV5SceneImageMatch(scene: String): Int? {
    return when {
        listOf("东江二水厂", "水厂", "净水厂", "取水口", "供水站", "waterworks").any(scene::contains) -> R.drawable.apocalypse_waterworks
        listOf("北岸种源站", "种源站", "种子库", "温室", "育种", "greenhouse", "seed bank").any(scene::contains) -> R.drawable.apocalypse_seed_station
        listOf("学校", "校园", "教学楼", "教室", "图书馆", "中学", "大学", "school", "campus").any(scene::contains) -> R.drawable.apocalypse_school
        listOf("警局", "派出所", "公安", "应急中心", "联席会", "指挥中心", "police", "emergency center").any(scene::contains) -> R.drawable.apocalypse_emergency_center
        listOf("医院", "诊所", "急救", "药房", "病房", "medical", "hospital").any(scene::contains) -> R.drawable.apocalypse_hospital
        listOf("商场", "超市", "便利店", "商店", "市场", "卖场", "store", "mall").any(scene::contains) -> R.drawable.apocalypse_store
        listOf("住宅", "公寓", "宿舍", "卧室", "客厅", "家中", "楼道", "home", "apartment").any(scene::contains) -> R.drawable.apocalypse_home
        listOf("停车场", "车库", "地库", "地下室", "garage", "parking").any(scene::contains) -> R.drawable.apocalypse_parking
        listOf("公路", "高速", "国道", "道路", "桥", "收费站", "车队", "road", "highway").any(scene::contains) -> R.drawable.apocalypse_road
        listOf("郊区", "村", "农场", "荒野", "田野", "山地", "基地", "避难所", "rural", "farm").any(scene::contains) -> R.drawable.apocalypse_rural
        listOf("隧道", "地铁", "地下通道", "下水道", "矿井", "tunnel", "subway").any(scene::contains) -> R.drawable.apocalypse_dark_tunnel
        listOf("工厂", "仓库", "工业", "车间", "厂房", "电站", "factory", "warehouse").any(scene::contains) -> R.drawable.apocalypse_factory_interior
        listOf("火车站", "铁路", "铁轨", "站台", "列车", "railway", "railroad", "platform").any(scene::contains) -> R.drawable.apocalypse_station
        listOf("临江市", "旧城区", "市中心", "街道", "街区", "城区", "城市", "city", "street").any(scene::contains) -> R.drawable.apocalypse_linjiang_street
        else -> null
    }
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

internal class ApocalypseReadingProgressStoreV5(context: Context) {
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
    val historyStore = remember(context) { ApocalypseV5HistoryStore(context) }
    val gameState by gameStore.state.collectAsState()
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val generationStates by ApocalypseGenerationTaskManagerV5.states.collectAsState()
    var screen by remember { mutableStateOf(ApocalypseV5Screen.Home) }
    var save by remember { mutableStateOf(storage.loadSave()) }
    var config by remember { mutableStateOf(storage.loadConfig()) }
    val generationState = save?.id?.let { generationStates[it] }

    LaunchedEffect(Unit) {
        purgeApocalypseMainWorldLeaks(gameStore)
    }

    LaunchedEffect(generationState?.completedScene) {
        val completedScene = generationState?.completedScene ?: return@LaunchedEffect
        if (completedScene > (save?.scene ?: 0)) storage.loadSave()?.let { save = it }
    }

    fun createSave(): ApocalypseV3Save {
        val party = gameState.selectedCharacterIds.take(4).ifEmpty {
            characters.keys.firstOrNull()?.let(::listOf).orEmpty()
        }
        val names = party.map { id -> characters[id]?.displayName ?: MigratedDomainStores.characters.get(id).displayName }
        val partySettings = party.map { id -> characters[id] ?: MigratedDomainStores.characters.get(id) }
        val initialDirector = initialApocalypseV3Director()
        return ApocalypseV3Save(
            id = UUID.randomUUID().toString(),
            scene = 1,
            partyIds = party,
            narration = tagApocalypseNarrationAsNarrator(initialApocalypseV3Scene(names)),
            director = initialDirector.copy(
                presentCharacterIds = party,
                presentCharacterStateKnown = true,
                characterDossiers = ensureApocalypsePartyDossiersV5(
                    previous = initialDirector.characterDossiers,
                    party = partySettings,
                    location = initialDirector.location,
                    scene = 1,
                ),
            ),
            stats = ApocalypseV3Stats(),
        )
    }

    fun enterGame() {
        var current = storage.loadSave() ?: save ?: createSave().also {
            save = it
            storage.save(it)
        }
        save = current
        val currentParty = current.partyIds.map { id -> characters[id] ?: MigratedDomainStores.characters.get(id) }
        val ensuredDossiers = ensureApocalypsePartyDossiersV5(
            previous = current.director.characterDossiers,
            party = currentParty,
            location = current.director.location,
            scene = current.scene,
        )
        val ensuredPresentIds = current.director.presentCharacterIds.mapNotNull { rawId ->
            resolveApocalypseSpeakerTokenV5(
                rawToken = rawId,
                party = currentParty,
                dossiers = ensuredDossiers,
                presentCharacterIds = current.director.presentCharacterIds,
            ).characterId
        }.distinct().take(10)
        if (
            ensuredDossiers != current.director.characterDossiers ||
            ensuredPresentIds != current.director.presentCharacterIds
        ) {
            current = current.copy(
                director = current.director.copy(
                    characterDossiers = ensuredDossiers,
                    presentCharacterIds = ensuredPresentIds,
                ),
            )
            save = current
            storage.save(current)
        }
        if (current.partyIds.isEmpty() && gameState.selectedCharacterIds.isNotEmpty()) {
            val restoredParty = gameState.selectedCharacterIds.take(4)
            val restoredSettings = restoredParty.map { id -> characters[id] ?: MigratedDomainStores.characters.get(id) }
            current = current.copy(
                partyIds = restoredParty,
                director = current.director.copy(
                    presentCharacterIds = current.director.presentCharacterIds.ifEmpty { restoredParty },
                    presentCharacterStateKnown = true,
                    characterDossiers = ensureApocalypsePartyDossiersV5(
                        previous = current.director.characterDossiers,
                        party = restoredSettings,
                        location = current.director.location,
                        scene = current.scene,
                    ),
                ),
            )
            save = current
            storage.save(current)
        }
        screen = ApocalypseV5Screen.Play
    }

    fun goBack() {
        if (screen == ApocalypseV5Screen.Home) onBack() else screen = ApocalypseV5Screen.Home
    }

    fun rollbackStory(entryId: String) {
        val current = save ?: return
        val rollback = historyStore.rollback(current.id, entryId) ?: return
        val restored = rollback.target.restoreOnto(current)
        progressStore.clear()
        ApocalypsePlotMemoryStoreV5(context).trimAfterScene(restored.id, restored.scene)
        save = restored
        storage.save(restored)
    }

    fun clearStoryHistory() {
        val current = save ?: return
        val names = current.partyIds.map { id -> characters[id]?.displayName ?: MigratedDomainStores.characters.get(id).displayName }
        val partySettings = current.partyIds.map { id -> characters[id] ?: MigratedDomainStores.characters.get(id) }
        val initialDirector = initialApocalypseV3Director()
        val reset = current.copy(
            scene = 1,
            narration = tagApocalypseNarrationAsNarrator(initialApocalypseV3Scene(names)),
            director = initialDirector.copy(
                presentCharacterIds = current.partyIds,
                presentCharacterStateKnown = true,
                characterDossiers = ensureApocalypsePartyDossiersV5(
                    previous = initialDirector.characterDossiers,
                    party = partySettings,
                    location = initialDirector.location,
                    scene = 1,
                ),
            ),
            stats = ApocalypseV3Stats(),
            log = emptyList(),
            updatedAt = System.currentTimeMillis(),
        )
        historyStore.clear(current.id)
        ApocalypsePlotMemoryStoreV5(context).trimAfterScene(current.id, reset.scene)
        progressStore.clear()
        save = reset
        storage.save(reset)
    }

    BackHandler(onBack = ::goBack)

    when (screen) {
        ApocalypseV5Screen.Home -> ApocalypseV5HomePage(
            save = save,
            config = config,
            generationState = generationState,
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
            history = save?.let { historyStore.load(it.id) }.orEmpty(),
            onBack = ::goBack,
            onDeleteHistory = ::rollbackStory,
            onClearHistory = ::clearStoryHistory,
            onClearSave = {
                save?.let { historyStore.clear(it.id) }
                storage.clearSave()
                progressStore.clear()
                save = null
            },
        )

        ApocalypseV5Screen.StoryHistory -> {
            val current = save
            if (current == null) {
                LaunchedEffect(Unit) { screen = ApocalypseV5Screen.Home }
            } else {
                ApocalypseV5StoryHistoryPage(
                    save = current,
                    history = historyStore.load(current.id),
                    characters = characters,
                    onBack = { screen = ApocalypseV5Screen.Play },
                    onDeleteScene = { entryId ->
                        rollbackStory(entryId)
                        screen = ApocalypseV5Screen.Play
                    },
                )
            }
        }

        ApocalypseV5Screen.Play -> {
            val current = save
            if (current == null) {
                LaunchedEffect(Unit) { enterGame() }
            } else {
                ApocalypseV5PlayPage(
                    save = current,
                    config = config,
                    characters = characters,
                    progressStore = progressStore,
                    historyStore = historyStore,
                    onBack = ::goBack,
                    onHistory = { screen = ApocalypseV5Screen.StoryHistory },
                    onDeleteCurrent = { entryId -> rollbackStory(entryId) },
                )
            }
        }
    }
}

@Composable
private fun ApocalypseV5HomePage(
    save: ApocalypseV3Save?,
    config: ApocalypseV3Config,
    generationState: ApocalypseGenerationTaskManagerV5.TaskState?,
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
            if (save != null) { item { ApocalypseSurvivalSnapshotV5(save) } }
            item {
                ApocalypseV5MenuEntry(
                    Icons.Outlined.PlayArrow,
                    if (generationState?.running == true) "剧情正在后台生成" else "进入游戏",
                    when {
                        generationState?.running == true -> generationState.phase.ifBlank { "退出页面也不会中断" }
                        save == null -> "从灾前第七日开始"
                        else -> "继续第 ${save.scene} 幕"
                    },
                    onEnter,
                    emphasis = true,
                )
            }
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
                Text("已为 $configured 位同行角色设置灾后潜在分化。同行者灾前不会觉醒；灾后也必须先在剧情中经历完整觉醒事件，才能使用能力。", color = ApocalypseV5Colors.muted, fontSize = 10.sp, lineHeight = 15.sp)
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
                        Text("主沉降后约 8% 人口会形成稳定异能，约 92% 仍是普通人。你为同行选择的是潜在分化；灾前不会生效，灾后必须经过正文中的觉醒事件。", color = ApocalypseV5Colors.textMutedDark, fontSize = 11.sp, lineHeight = 17.sp)
                    }
                }
            }

            item { ApocalypseV5SectionTitle("同行角色与潜在分化", "最多4人；选择的能力不会在灾前直接生效") }
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
                                    Text("设置潜在分化", color = ApocalypseV5Colors.blueStrong)
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
                title to "玩家是目前唯一已确认的灾前提前觉醒者。其他稳定异能从赤潮主沉降后才逐步形成，人口硬基线约8%，约92%没有稳定异能；灾后幸存者中的比例会因淘汰与聚集上升，但通常仍不应成为多数。"
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
private fun ApocalypseV5StoryHistoryPage(
    save: ApocalypseV3Save,
    history: List<ApocalypseV5HistoryEntry>,
    characters: Map<String, CharacterSettings>,
    onBack: () -> Unit,
    onDeleteScene: (String) -> Unit,
) {
    val scenes = remember(save.id, save.scene, save.narration, history) {
        readableApocalypseScenesV5(save, history)
    }
    val party = save.partyIds.map { id -> characters[id] ?: MigratedDomainStores.characters.get(id) }
    var selectedIndex by rememberSaveable(save.id, scenes.size) { mutableStateOf<Int?>(null) }
    var deleteTarget by remember { mutableStateOf<ApocalypseV5ReadableScene?>(null) }
    val selected = selectedIndex?.let(scenes::getOrNull)

    Scaffold(
        containerColor = ApocalypseV5Colors.background,
        topBar = {
            TopAppBar(
                title = { Text(selected?.let { "第${it.sceneNumber}幕" } ?: "剧情历史", fontWeight = FontWeight.Black) },
                navigationIcon = {
                    IconButton(onClick = { if (selectedIndex != null) selectedIndex = null else onBack() }) {
                        Icon(Icons.Outlined.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (selected?.deleteEntryId != null) {
                        IconButton(onClick = { deleteTarget = selected }) {
                            Icon(Icons.Outlined.DeleteOutline, "删除这一幕", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ApocalypseV5Colors.background),
            )
        },
    ) { padding ->
        if (selected == null) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(scenes, key = { it.sceneNumber }) { scene ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { selectedIndex = scenes.indexOf(scene) },
                        color = ApocalypseV5Colors.white,
                        shape = RoundedCornerShape(17.dp),
                        border = BorderStroke(1.dp, ApocalypseV5Colors.border),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("第${scene.sceneNumber}幕", color = ApocalypseV5Colors.ink, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                                if (scene.sceneNumber == save.scene) Text("当前", color = ApocalypseV5Colors.blueStrong, fontSize = 11.sp)
                                Icon(Icons.Outlined.ChevronRight, null, tint = ApocalypseV5Colors.muted)
                            }
                            scene.actionThatLedHere?.takeIf(String::isNotBlank)?.let { action ->
                                Text("你的行动：$action", color = ApocalypseV5Colors.blueStrong, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                scene.narration.replace(Regex("【[^】]+】"), ""),
                                color = ApocalypseV5Colors.muted,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        } else {
            val pages = remember(
                selected.narration,
                party,
                save.director.characterDossiers,
                save.director.presentCharacterIds,
            ) {
                parseApocalypseStoryPages(
                    text = selected.narration,
                    party = party,
                    dossiers = save.director.characterDossiers,
                    presentCharacterIds = save.director.presentCharacterIds,
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                selected.actionThatLedHere?.takeIf(String::isNotBlank)?.let { action ->
                    item {
                        Surface(color = ApocalypseV5Colors.surfaceBlue, shape = RoundedCornerShape(15.dp)) {
                            Text("你的行动：$action", color = ApocalypseV5Colors.blueStrong, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
                items(pages) { page ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            apocalypseV5SpeakerLabel(page, party, save.director.characterDossiers, "我"),
                            color = ApocalypseV5Colors.blueStrong,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                        )
                        Text(page.text, color = ApocalypseV5Colors.ink, fontSize = 16.sp, lineHeight = 27.sp)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(
                            onClick = { selectedIndex = (selectedIndex ?: 0) - 1 },
                            enabled = (selectedIndex ?: 0) > 0,
                        ) { Icon(Icons.Outlined.ChevronLeft, null); Text("上一幕") }
                        TextButton(
                            onClick = { selectedIndex = (selectedIndex ?: 0) + 1 },
                            enabled = (selectedIndex ?: 0) < scenes.lastIndex,
                        ) { Text("下一幕"); Icon(Icons.Outlined.ChevronRight, null) }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除第${target.sceneNumber}幕并重新来？") },
            text = { Text("这一幕以及它之后产生的剧情、伏笔、关系、物资和世界状态都会永久删除。随后会回到上一幕，你可以重新行动生成新的剧情。") },
            confirmButton = {
                TextButton(onClick = {
                    target.deleteEntryId?.let(onDeleteScene)
                    deleteTarget = null
                }) { Text("永久删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ApocalypseV5ArchivePage(
    save: ApocalypseV3Save?,
    history: List<ApocalypseV5HistoryEntry>,
    onBack: () -> Unit,
    onDeleteHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onClearSave: () -> Unit,
) {
    var deleteTarget by remember { mutableStateOf<ApocalypseV5HistoryEntry?>(null) }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmClearSave by remember { mutableStateOf(false) }
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
                item { ApocalypseCharacterStatePanelV5(save.director.characterDossiers) }
                item { ApocalypseBaseDashboardV5(save) }
                item { ApocalypseV5SectionTitle("剧情记录", "新记录可逐幕回滚删除；删除某幕会同步删除它之后依赖该幕的剧情与状态") }

                if (history.isNotEmpty()) {
                    items(history.asReversed(), key = { it.id }) { record ->
                        Surface(color = ApocalypseV5Colors.white, shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, ApocalypseV5Colors.border)) {
                            Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text("第${record.sceneBefore + 1}幕 · ${record.action}", color = ApocalypseV5Colors.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    record.narrationAfter.replace(Regex("【[^】]+】"), ""),
                                    color = ApocalypseV5Colors.muted,
                                    fontSize = 11.sp,
                                    lineHeight = 17.sp,
                                    maxLines = 5,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(onClick = { deleteTarget = record }, modifier = Modifier.align(Alignment.End)) {
                                    Icon(Icons.Outlined.DeleteOutline, null, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("删除这幕", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                } else if (save.log.isNotEmpty()) {
                    item {
                        Surface(color = ApocalypseV5Colors.surfaceBlue, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, ApocalypseV5Colors.border)) {
                            Text(
                                "这些是升级前留下的旧版记录，没有保存可安全回滚的状态快照，所以不提供假删除。可以使用“清空全部剧情记录”彻底重置本局剧情。",
                                color = ApocalypseV5Colors.muted,
                                fontSize = 11.sp,
                                lineHeight = 17.sp,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                    items(save.log.asReversed().take(14)) { log ->
                        Surface(color = ApocalypseV5Colors.white, shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, ApocalypseV5Colors.border)) {
                            Text(log, color = ApocalypseV5Colors.ink, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(13.dp), maxLines = 7, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    item { Text("还没有行动记录。", color = ApocalypseV5Colors.muted, fontSize = 12.sp) }
                }

                if (history.isNotEmpty() || save.log.isNotEmpty() || save.scene > 1) {
                    item {
                        OutlinedButton(onClick = { confirmClearHistory = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.DeleteSweep, null)
                            Spacer(Modifier.width(6.dp))
                            Text("清空全部剧情记录")
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = { confirmClearSave = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.RestartAlt, null)
                        Spacer(Modifier.width(6.dp))
                        Text("重新开档")
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("永久删除第${target.sceneBefore + 1}幕？") },
            text = { Text("为了保证真的删除，这一幕以及它之后依赖这一幕产生的剧情、物资、地点、关系变化和状态都会一起回滚删除；未来模型上下文也不会再读取它们。此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteHistory(target.id)
                    deleteTarget = null
                }) { Text("永久删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("清空全部剧情记录？") },
            text = { Text("会永久删除本局已经发生的剧情及其派生状态，并回到灾前第七日开场。角色本身和异能配置不会删除。") },
            confirmButton = {
                TextButton(onClick = {
                    onClearHistory()
                    confirmClearHistory = false
                }) { Text("永久清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClearHistory = false }) { Text("取消") } },
        )
    }

    if (confirmClearSave) {
        AlertDialog(
            onDismissRequest = { confirmClearSave = false },
            title = { Text("清空末世存档？") },
            text = { Text("会删除当前末世剧情、物资、基地、异能进度和阅读进度。露露机角色本身不会删除。") },
            confirmButton = { TextButton(onClick = { onClearSave(); confirmClearSave = false }) { Text("清空") } },
            dismissButton = { TextButton(onClick = { confirmClearSave = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ApocalypseV5PlayPage(
    save: ApocalypseV3Save,
    config: ApocalypseV3Config,
    characters: Map<String, CharacterSettings>,
    progressStore: ApocalypseReadingProgressStoreV5,
    historyStore: ApocalypseV5HistoryStore,
    onBack: () -> Unit,
    onHistory: () -> Unit,
    onDeleteCurrent: (String) -> Unit,
) {
    val context = LocalContext.current
    val userPrefs = remember(context) { context.getSharedPreferences("lulu_user_profile", Context.MODE_PRIVATE) }
    val userName = remember { userPrefs.getString("display_name", "我").orEmpty().ifBlank { "我" } }
    val userAvatarUri = remember { userPrefs.getString("avatar_uri", null) }
    val party = save.partyIds.map { id -> characters[id] ?: MigratedDomainStores.characters.get(id) }
    val currentHistory = remember(save.id, save.scene) { historyStore.load(save.id) }
    val currentEntry = currentHistory.lastOrNull()?.takeIf { it.sceneBefore + 1 == save.scene }
    val pages = remember(
        save.scene,
        save.narration,
        party,
        currentEntry?.action,
        save.director.characterDossiers,
        save.director.presentCharacterIds,
    ) {
        buildList {
            currentEntry?.action?.takeIf(String::isNotBlank)?.let { playerAction ->
                addAll(parseApocalypseStoryPages("【玩家】$playerAction", party))
            }
            addAll(
                parseApocalypseStoryPages(
                    text = save.narration,
                    party = party,
                    dossiers = save.director.characterDossiers,
                    presentCharacterIds = save.director.presentCharacterIds,
                ),
            )
        }
    }
    var pageIndex by remember(save.id, save.scene, pages.size) { mutableIntStateOf(progressStore.load(save.id, save.scene).coerceIn(0, pages.lastIndex.coerceAtLeast(0))) }
    var action by remember { mutableStateOf("") }
    var autoPlay by remember { mutableStateOf(false) }
    var showInventory by remember { mutableStateOf(false) }
    var showMapPage by remember { mutableStateOf(false) }
    var confirmDeleteCurrent by remember { mutableStateOf(false) }
    var generationSeconds by remember { mutableIntStateOf(0) }
    val generationStates by ApocalypseGenerationTaskManagerV5.states.collectAsState()
    val generationState = generationStates[save.id] ?: ApocalypseGenerationTaskManagerV5.TaskState()
    val busy = generationState.running
    val currentEntryId = currentEntry?.id
    val currentPage = pages.getOrElse(pageIndex) { pages.first() }
    val streamingPages = remember(
        generationState.partialText,
        party,
        save.director.characterDossiers,
        save.director.presentCharacterIds,
    ) {
        generationState.partialText.takeIf(String::isNotBlank)?.let { partial ->
            parseApocalypseStoryPages(
                text = partial,
                party = party,
                dossiers = save.director.characterDossiers,
                presentCharacterIds = save.director.presentCharacterIds,
            )
        }.orEmpty()
    }
    val streamingPage = streamingPages.lastOrNull()
    val displayPage = streamingPage ?: currentPage
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

    LaunchedEffect(busy, generationState.startedAtMillis) {
        generationSeconds = generationState.startedAtMillis?.let { ((System.currentTimeMillis() - it) / 1_000L).toInt() } ?: 0
        while (busy) {
            delay(1_000)
            generationSeconds = generationState.startedAtMillis?.let { ((System.currentTimeMillis() - it) / 1_000L).toInt() } ?: 0
        }
    }

    LaunchedEffect(generationState.lastError, generationState.action) {
        if (generationState.lastError != null && action.isBlank()) action = generationState.action
    }

    fun submit() {
        val clean = action.trim()
        if (clean.isBlank() || busy || !lastPage) return
        val started = ApocalypseGenerationTaskManagerV5.launch(context, save, config, party, clean)
        if (started) {
            action = ""
            autoPlay = false
        }
    }

    Box(Modifier.fillMaxSize().background(ApocalypseV5Colors.black).statusBarsPadding().imePadding()) {
        ApocalypseV5SpeakerStage(
            modifier = Modifier.fillMaxSize(),
            page = displayPage,
            party = party,
            storyDossiers = save.director.characterDossiers,
            config = config,
            location = save.director.location,
            tension = save.director.tension,
            stats = save.stats,
            userName = userName,
            userAvatarUri = userAvatarUri,
            onMap = { if (!busy) showMapPage = true },
            onInventory = { if (!busy) showInventory = true },
            onAdvance = { if (!lastPage && !busy) nextPage() },
        )

        Row(
            Modifier.fillMaxWidth().height(54.dp).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(ApocalypseV5Colors.black.copy(alpha = .44f), Color.Transparent)))
                .padding(horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回", tint = ApocalypseV5Colors.textOnDark) }
            Column(Modifier.weight(1f)) {
                Text("末世求生", color = ApocalypseV5Colors.textOnDark, fontSize = 17.sp, fontWeight = FontWeight.Black)
                Text("${apocalypseDayLabelV5(save.director.dayIndex)} ${apocalypseClockLabelV5(save.director.clockMinutes)} · ${save.director.weather} ${save.director.temperatureC}℃ · 第${save.scene}幕", color = ApocalypseV5Colors.blueSoft, fontSize = 10.sp)
            }
            IconButton(onClick = onHistory, enabled = !busy) {
                Icon(Icons.Outlined.History, "剧情历史", tint = ApocalypseV5Colors.textOnDark)
            }
            if (currentEntryId != null) {
                IconButton(onClick = { confirmDeleteCurrent = true }, enabled = !busy) {
                    Icon(Icons.Outlined.DeleteOutline, "删除当前幕", tint = ApocalypseV5Colors.textOnDark)
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).navigationBarsPadding(),
            color = ApocalypseV5Colors.background.copy(alpha = .98f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (streamingPage != null) "第${save.scene + 1}幕 · 正在生成" else apocalypseV5SpeakerLabel(currentPage, party, save.director.characterDossiers, userName),
                        color = ApocalypseV5Colors.blueStrong,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(if (streamingPage != null) "${streamingPages.size}段" else "${pageIndex + 1}/${pages.size}", color = ApocalypseV5Colors.muted, fontSize = 9.sp)
                }
                Spacer(Modifier.height(5.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 168.dp).clickable(enabled = !lastPage && !busy) { nextPage() },
                    color = ApocalypseV5Colors.white,
                    shape = RoundedCornerShape(17.dp),
                    border = BorderStroke(1.dp, ApocalypseV5Colors.border),
                ) {
                    Text(displayPage.text, color = ApocalypseV5Colors.ink, fontSize = 16.sp, lineHeight = 24.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
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
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
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
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ApocalypseV5Colors.white)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (busy) "${generationState.phase} · ${generationSeconds}s" else "行动", fontWeight = FontWeight.Black)
                    }
                    if (busy) {
                        Spacer(Modifier.height(6.dp))
                        Text("可以返回其他页面，剧情会继续生成。", color = ApocalypseV5Colors.muted, fontSize = 11.sp)
                    }
                    generationState.lastError?.let { message ->
                        Spacer(Modifier.height(6.dp))
                        Text(message, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
    }

    if (showInventory) {
        ModalBottomSheet(onDismissRequest = { showInventory = false }, containerColor = ApocalypseV5Colors.background) { ApocalypseV5InventorySheet(save) }
    }
    if (confirmDeleteCurrent && currentEntryId != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteCurrent = false },
            title = { Text("删除第${save.scene}幕并重新来？") },
            text = { Text("当前幕以及它产生的剧情状态会永久删除，并回到第${save.scene - 1}幕。你可以重新输入行动，生成新的第${save.scene}幕。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteCurrent = false
                    onDeleteCurrent(currentEntryId)
                }) { Text("永久删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteCurrent = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ApocalypseV5SpeakerStage(
    modifier: Modifier,
    page: ApocalypseStoryPage,
    party: List<CharacterSettings>,
    storyDossiers: List<ApocalypseCharacterDossierV5>,
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
    val storyCharacter = page.characterId?.let { id -> storyDossiers.firstOrNull { it.id == id } }
    val secondary = apocalypseAbilityDefinitionV5(apocalypsePlayerSecondaryChoiceV5(config))
    Box(modifier.background(ApocalypseV5Colors.black).clickable(onClick = onAdvance)) {
        Image(
            painter = painterResource(apocalypseV5SceneImage(location, page.text)),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color(0x5C101B18),
                    .30f to Color(0x24101B18),
                    .64f to Color(0x14101B18),
                    1f to Color(0x4D101B18),
                ),
            ),
        )
        Column(
            Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 62.dp, bottom = 190.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(location, color = ApocalypseV5Colors.textOnDark, fontSize = 17.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("空间 Lv.${stats.playerAbilityLevel} · ${playerSpaceCapacityM3(stats.playerAbilityLevel)}m³", color = ApocalypseV5Colors.textMutedDark, fontSize = 10.sp)
                }
                Surface(color = ApocalypseV5Colors.surfaceBlue.copy(alpha = .12f), shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, ApocalypseV5Colors.blackLine)) {
                    Text("威胁 $tension/10", color = ApocalypseV5Colors.blueSoft, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(
                    onClick = onMap,
                    color = Color.Black.copy(alpha = .28f),
                    shape = RoundedCornerShape(9.dp),
                    border = BorderStroke(1.dp, ApocalypseV5Colors.blackLine),
                ) {
                    Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Map, null, tint = ApocalypseV5Colors.blueSoft, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("地图", color = ApocalypseV5Colors.textOnDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Surface(
                    onClick = onInventory,
                    color = Color.Black.copy(alpha = .28f),
                    shape = RoundedCornerShape(9.dp),
                    border = BorderStroke(1.dp, ApocalypseV5Colors.blackLine),
                ) {
                    Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Inventory2, null, tint = ApocalypseV5Colors.blueSoft, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("物资", color = ApocalypseV5Colors.textOnDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ApocalypseV5TinyStageStat("¥${stats.money}")
                ApocalypseV5TinyStageStat("食 ${stats.food}")
                ApocalypseV5TinyStageStat("水 ${stats.water}")
                ApocalypseV5TinyStageStat("药 ${stats.medicine}")
                ApocalypseV5TinyStageStat("晶 ${stats.crystalCores}")
                if (stats.baseLevel > 0) ApocalypseV5TinyStageStat("基地 Lv.${stats.baseLevel}")
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                when (page.speakerKind) {
                    ApocalypseStorySpeakerKind.Narrator -> Unit
                    ApocalypseStorySpeakerKind.Player -> ApocalypseV5SpeakerPortrait(
                        imageUri = userAvatarUri,
                        fallback = userName.take(1).ifBlank { "我" },
                        name = userName,
                        subtitle = if (secondary.id == "none") "空间系 · 主角" else "空间 + ${secondary.name}",
                    )
                    ApocalypseStorySpeakerKind.Character -> {
                        if (character == null) {
                            val npcName = storyCharacter?.let(::apocalypseDossierDisplayNameV5)
                                ?: page.speakerLabel
                                ?: page.characterId?.let(::apocalypseDeterministicNpcNameV5)
                                ?: "说话人未标明"
                            val npcId = storyCharacter?.id ?: page.characterId ?: npcName
                            ApocalypseV5SpeakerPortrait(
                                imageUri = apocalypseNpcAvatarUriV5(npcId),
                                fallback = npcName.take(2).ifBlank { "人" },
                                name = npcName,
                                subtitle = storyCharacter?.storyRole?.ifBlank { "本幕人物" } ?: "本幕人物",
                            )
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
        }
    }
}

@Composable
private fun ApocalypseV5SpeakerPortrait(imageUri: String?, fallback: String, name: String, subtitle: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.size(308.dp),
            shape = RoundedCornerShape(68.dp),
            color = Color.Transparent,
            shadowElevation = 0.dp,
        ) { LuluProfileAvatar(imageUri, fallback, 308) }
        Spacer(Modifier.height(8.dp))
        Text(name, color = ApocalypseV5Colors.textOnDark, fontSize = 14.sp, fontWeight = FontWeight.Black)
        Text(subtitle, color = ApocalypseV5Colors.blue, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ApocalypseV5TinyStageStat(text: String) {
    Surface(color = Color.Black.copy(alpha = .28f), shape = RoundedCornerShape(8.dp)) {
        Text(text, color = ApocalypseV5Colors.textMutedDark, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp))
    }
}

private fun apocalypseV5SpeakerLabel(
    page: ApocalypseStoryPage,
    party: List<CharacterSettings>,
    storyDossiers: List<ApocalypseCharacterDossierV5>,
    userName: String,
): String = when (page.speakerKind) {
    ApocalypseStorySpeakerKind.Narrator -> "旁白"
    ApocalypseStorySpeakerKind.Player -> userName.ifBlank { "我" }
    ApocalypseStorySpeakerKind.Character -> party.firstOrNull { it.characterId == page.characterId }?.displayName
        ?: storyDossiers.firstOrNull { it.id == page.characterId }?.let(::apocalypseDossierDisplayNameV5)
        ?: page.speakerLabel
        ?: page.characterId?.let(::apocalypseDeterministicNpcNameV5)
        ?: "说话人未标明"
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
                ApocalypseV5StatusValue("资金", "¥${stats.money}")
                ApocalypseV5StatusValue("食物", stats.food.toString())
                ApocalypseV5StatusValue("饮水", stats.water.toString())
                ApocalypseV5StatusValue("药物", stats.medicine.toString())
                ApocalypseV5StatusValue("材料", stats.materials.toString())
            }
            HorizontalDivider(color = ApocalypseV5Colors.blackLine)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ApocalypseV5StatusValue("晶核", stats.crystalCores.toString())
                ApocalypseV5StatusValue("空间", "Lv.${stats.playerAbilityLevel}")
                ApocalypseV5StatusValue(
                    "经验",
                    if (stats.playerAbilityLevel >= 5) "MAX" else "${stats.playerAbilityXp}/${abilityXpThresholdV3(stats.playerAbilityLevel)}",
                )
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
