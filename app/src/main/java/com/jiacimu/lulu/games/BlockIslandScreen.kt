package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.random.Random

/**
 * 方块小岛专属屏幕。与摸金的 ExpeditionScreen 解耦；老版存档读取后自动迁移。
 * Canvas 是可玩的地图，不是图片：所有按钮均发往 BlockIslandRules.act。
 */
@Composable
internal fun BlockIslandScreen(store: LuluGameStore) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val game by store.state.collectAsState()
    val preferences = remember(context) {
        context.getSharedPreferences("lulu_block_island_v2", 0)
    }
    val oldPreferences = remember(context) {
        context.getSharedPreferences("lulu_expedition_homestead_v1", 0)
    }
    var island by remember {
        mutableStateOf(
            BlockIslandState.fromJson(preferences.getString("world", "").orEmpty())
                ?: ExpeditionState.fromJson(
                    oldPreferences.getString("world", "").orEmpty(), ExpeditionMode.HOMESTEAD,
                )?.let(BlockIslandState::fromLegacy)
                ?: BlockIslandRules.newWorld(Random.nextInt(), game.selectedCharacterIds),
        )
    }
    val world = island.world
    var target by remember { mutableStateOf(world.x to world.y) }
    var navigateTo by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var zoomed by remember { mutableStateOf(true) }
    var confirmReset by remember { mutableStateOf(false) }
    var showRecipes by remember { mutableStateOf(true) }
    var showHelp by remember { mutableStateOf(false) }
    var companionPing by remember(world.seed) { mutableStateOf<Pair<Int, Int>?>(null) }
    var autonomousCompanions by rememberSaveable { mutableStateOf(true) }
    val autonomousIds = if (autonomousCompanions) world.friends.map { it.id }.toSet() else emptySet()
    var impact by remember { mutableStateOf<BlockIslandImpact?>(null) }
    val impactProgress = remember { Animatable(1f) }
    LaunchedEffect(impact) {
        if (impact != null) {
            impactProgress.snapTo(0f)
            impactProgress.animateTo(1f, tween(550))
            impact = null
        }
    }

    fun save(next: BlockIslandState) {
        island = next
        preferences.edit().putString("world", next.toJson()).apply()
    }
    fun perform(action: String, x: Int = target.first, y: Int = target.second) {
        navigateTo = null
        val result = BlockIslandRules.act(island, action, x, y, autonomousIds)
        if (result.world.turns > island.world.turns) {
            impact = BlockIslandImpact(x, y, action, result.world.turns)
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            GameSoundEffects.play(when (action) {
                "move" -> GameSoundEffect.Move
                "gather", "salvage", "eat", "eat_fish", "fish", "revive" -> GameSoundEffect.Collect
                "fight" -> GameSoundEffect.Objective
                else -> GameSoundEffect.Interact
            })
        } else {
            GameSoundEffects.play(GameSoundEffect.Bump)
        }
        save(result)
    }

    LaunchedEffect(game.selectedCharacterIds, game.playWithCharacter) {
        val wanted = if (game.playWithCharacter) game.selectedCharacterIds.distinct().take(3) else emptyList()
        if (island.world.friends.map { it.id } != wanted) {
            save(with(BlockIslandRules) { island.withPartners(wanted) })
        }
    }
    LaunchedEffect(navigateTo, world.x, world.y) {
        val destination = navigateTo ?: return@LaunchedEffect
        if (destination == (world.x to world.y)) {
            navigateTo = null
            return@LaunchedEffect
        }
        val step = BlockIslandRules.nextStep(island, world.x, world.y, destination.first, destination.second)
        if (step == null) {
            navigateTo = null
            val nearby = world.adjacent(destination.first, destination.second)
            save(island.copy(world = world.copy(notice =
                if (nearby) "已靠近目标，可以进行采集、战斗或建造"
                else "前往失败：路线被阻挡，换个目标或清理障碍试试")))
            if (!nearby) GameSoundEffects.play(GameSoundEffect.Bump)
            return@LaunchedEffect
        }
        delay(145)
        val next = BlockIslandRules.act(island, "move", step.first, step.second, autonomousIds)
        if (next.world.x == world.x && next.world.y == world.y) {
            navigateTo = null
            GameSoundEffects.play(GameSoundEffect.Bump)
        } else {
            GameSoundEffects.play(GameSoundEffect.Move)
        }
        save(next)
    }

    val night = world.turns % 48 >= 30
    val selectedCell = world.cell(target.first, target.second)
    val selectedStructure = island.structures[world.index(target.first, target.second)]
    val nearTarget = world.adjacent(target.first, target.second)
    val isMobSelected = world.mobs.any { it.hp > 0 && it.x == target.first && it.y == target.second }
    val primaryAction = islandTargetAction(island, target)
    val supplySelected = IslandSupplyCaches.available(island, target.first, target.second)
    val targetTitle = if (supplySelected) "漂流补给箱" else islandCellTitle(selectedCell, selectedStructure)

    val worldNotice = remember(world.notice,world.friends.map { it.id }) {
        world.friends.fold(world.notice) { line,friend ->
            val name=MigratedDomainStores.characters.get(friend.id).displayName
            line.replace("队友[${friend.id}]",name).replace("行程[${friend.id}]",name)
        }
    }

    GameSceneDeck(
        accent = Color(0xFFA9E5BD),
        toolsLabel = "背包 / 探险",
        notice = worldNotice,
        status = "❤ ${world.hp}/12   木 ${world.wood}   石 ${world.stone}   " +
            if (night) "☾ 夜晚" else "☀ 白天" +
                " · 补给 ${island.openedSupplies.size}/${island.supplyCaches.size}",
        scene = {
            BlockIslandCanvas(island, target, zoomed, night, impact, impactProgress.value,
                navigationTarget = navigateTo,
                onSelect = { x, y -> target = x to y; navigateTo = null },
                onNavigate = { x, y ->
                    target = x to y
                    navigateTo = (x to y).takeUnless { it == (world.x to world.y) }
                    if (navigateTo != null) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                fullscreen = true, companionPing = companionPing)
        },
        companionAvailable = game.playWithCharacter && world.friends.isNotEmpty() &&
            world.friends.map { it.id } == game.selectedCharacterIds.distinct().take(3),
        companion = {
            if (game.playWithCharacter && world.friends.isNotEmpty() &&
                world.friends.map { it.id } == game.selectedCharacterIds.distinct().take(3)) {
                IslandCompanionPanel(island, navigateTo != null, autonomousCompanions,
                    { enabled ->
                        autonomousCompanions = enabled
                        if (!enabled) save(IslandTravel.stopAll(island))
                    }, ::save, { companionPing = it }, readIsland = { island })
            }
        },
        movement = {
            GameDirectionalPad(accent = Color(0xFFA9E5BD), holdRepeatMs = 420L,
                stopWhen = world.hp to world.seed) { dx, dy ->
                perform("move", world.x + dx, world.y + dy)
            }
        },
        quickActions = {
            Text(
                if (navigateTo != null) "正在前往 · ${targetTitle} · 距离${primaryAction.distance}格"
                else "目标 · ${targetTitle} · ${if (primaryAction.distance <= 1) "身边" else "距离${primaryAction.distance}格"}",
                color = Color(0xFFF0FFE9), fontSize = 10.sp, maxLines = 1,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                GameHudQuickAction("采集", Color(0xFFA9E5BD),
                    enabled = nearTarget && selectedCell in listOf(1, 2),
                    modifier = Modifier.weight(1f)) { perform("gather") }
                GameHudQuickAction("攻击", Color(0xFFFFC9AE),
                    enabled = nearTarget && isMobSelected,
                    modifier = Modifier.weight(1f)) { perform("fight") }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                GameHudQuickAction(if (supplySelected) "打开补给" else "造墙", Color(0xFFA9E5BD),
                    enabled = if (supplySelected) nearTarget else
                        BlockIslandRules.canBuild(island, "wall", target.first, target.second),
                    modifier = Modifier.weight(1f)) {
                    if (supplySelected) perform("salvage") else perform("wall")
                }
                GameHudQuickAction("吃果", Color(0xFFA9E5BD),
                    enabled = island.fruit > 0 && world.hp < 12,
                    modifier = Modifier.weight(1f)) { perform("eat", world.x, world.y) }
            }
        },
        actions = {
            Button(onClick = {
                if (navigateTo != null) navigateTo = null
                else if (primaryAction.action == "move") {
                    navigateTo = target
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                } else perform(primaryAction.action)
            }, enabled = navigateTo != null || primaryAction.enabled,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA9D8B9),
                    contentColor = Color(0xFF17342F))) {
                Text(if (navigateTo != null) "■ 停止前往" else primaryAction.label +
                    " · " + targetTitle,
                    maxLines = 1, fontSize = 12.sp)
            }
            TextButton(onClick = { zoomed = !zoomed }) {
                Text(if (zoomed) "远景" else "近景", color = Color.White)
            }
        },
        tools = {
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF123C43), Color(0xFF102931), Color(0xFF101C26))))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("方块小岛", fontSize = 24.sp, color = Color.White, fontWeight = FontWeight.Black)
                Text("砍树挖矿 · 建房搭桥 · 和伙伴一起冒险", color = Color(0xFFBCE2D8), fontSize = 11.sp)
            }
            TextButton(onClick = { showHelp = !showHelp }) {
                Text(if (showHelp) "收起指南" else "？怎么玩", color = Color(0xFFBCEBCC), fontWeight = FontWeight.Bold)
            }
            Surface(shape = RoundedCornerShape(13.dp), color = Color(0xFF2F6063)) {
                Text(if (night) "☾ 夜晚" else "☀ 白天", modifier = Modifier.padding(10.dp),
                    color = Color(0xFFEBFBEC), fontSize = 12.sp)
            }
        }
        if (showHelp) {
            Surface(color = Color(0xFF21474B), shape = RoundedCornerShape(17.dp),
                border = BorderStroke(1.dp, Color(0xFF9EE8BF).copy(alpha = .3f))) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("在岛上活下去，慢慢建造自己的家", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("① 点地图上的树或石头，选中附近目标后点「采集」。", color = Color(0xFFD1F0DF), fontSize = 12.sp)
                    Text("② 长按或双击地图自动前往；途中按「停止前往」即可取消。", color = Color(0xFFD1F0DF), fontSize = 12.sp)
                    Text("③ 沿路找到漂流补给箱，靠近后领取一次性物资；再建造家园。", color = Color(0xFFD1F0DF), fontSize = 12.sp)
                }
            }
        }
        Surface(shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1C4445),
            border = BorderStroke(1.dp, Color(0xFF8FD9BE).copy(alpha = .35f))) {
            Column(Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✦ 野外补给探索", color = Color(0xFFF0FFF3),
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${island.openedSupplies.size} / ${island.supplyCaches.size}",
                        color = Color(0xFFB1F2D4), fontWeight = FontWeight.Bold)
                }
                LinearProgressIndicator(
                    progress = { if (island.supplyCaches.isEmpty()) 1f
                        else island.openedSupplies.size.toFloat() / island.supplyCaches.size },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF95E8C8),
                    trackColor = Color(0xFF355C57),
                )
                Text(if (IslandSupplyCaches.remaining(island) == 0)
                    "全部找齐啦！补给已经放入背包，可以继续建设家园。"
                    else "沿着小岛探索发光的补给箱，走近后点「打开补给」领取木材、石料，有时还有野果。",
                    color = Color(0xFFB9DDCC), fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IslandStat("❤", "${world.hp}/12", Color(0xFFFFBFC0), Modifier.weight(1f))
            IslandStat("木", "${world.wood}", Color(0xFFBEEAB4), Modifier.weight(1f))
            IslandStat("石", "${world.stone}", Color(0xFFC8D9E9), Modifier.weight(1f))
            IslandStat("果", "${island.fruit}", Color(0xFFF5D79B), Modifier.weight(1f))
            IslandStat("鱼", "${island.fish}", Color(0xFFA4E6F3), Modifier.weight(1f))
        }
        Surface(shape = RoundedCornerShape(13.dp), color = Color(0xFF274C50)) {
            Text(worldNotice, modifier = Modifier.fillMaxWidth().padding(11.dp),
                color = Color(0xFFDDF7ED), fontSize = 12.sp, lineHeight = 17.sp)
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                IslandAction("采集资源", enabled = nearTarget && selectedCell in listOf(1, 2),
                    Modifier.fillMaxWidth()) { perform("gather") }
                if (selectedCell == 8) IslandAction("水边钓鱼", enabled = nearTarget && "rod" in island.tools,
                    Modifier.fillMaxWidth()) { perform("fish") }
                IslandAction("攻击野怪", enabled = nearTarget && isMobSelected,
                    Modifier.fillMaxWidth()) { perform("fight") }
                val downed = world.friends.any { it.hp <= 0 && it.x == target.first && it.y == target.second }
                if (downed) IslandAction("救援队友 · 2果", enabled = nearTarget && island.fruit >= 2,
                    Modifier.fillMaxWidth()) { perform("revive") }
                IslandAction("吃野果 · 回血", enabled = island.fruit > 0 && world.hp < 12,
                    Modifier.fillMaxWidth()) { perform("eat", world.x, world.y) }
                if (island.fish > 0) IslandAction("篝火烤鱼 · +5❤",
                    enabled = world.hp < 12, Modifier.fillMaxWidth()) {
                    perform("eat_fish", world.x, world.y)
                }
        }
        Text("自由建造", color = Color(0xFFF0FCF4), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("选中身边的空地，再选择建筑。拆除会返还部分材料。",
            color = Color(0xFFAAC7C5), fontSize = 11.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            IslandBuildChip("♣ 种树", "1木", world.wood > 0 &&
                BlockIslandRules.canPlantAt(island, target.first, target.second)) { perform("plant") }
            IslandBuildChip("▤ 地板", "1木", BlockIslandRules.canBuild(island, "floor", target.first, target.second)) { perform("floor") }
            IslandBuildChip("▥ 木墙", "2木", BlockIslandRules.canBuild(island, "wall", target.first, target.second)) { perform("wall") }
            IslandBuildChip("▣ 木门", "2木", BlockIslandRules.canBuild(island, "door", target.first, target.second)) { perform("door") }
            IslandBuildChip("⚒ 工作台", "4木2石", BlockIslandRules.canBuild(island, "workbench", target.first, target.second)) { perform("workbench") }
            IslandBuildChip("♨ 篝火", "2木1石", BlockIslandRules.canBuild(island, "campfire", target.first, target.second)) { perform("campfire") }
            IslandBuildChip("▱ 木床", "3木1石", BlockIslandRules.canBuild(island, "bed", target.first, target.second)) { perform("bed") }
            IslandBuildChip("▦ 木桥", "3木", BlockIslandRules.canBuild(island, "bridge", target.first, target.second)) { perform("bridge") }
            IslandBuildChip("↶ 拆除", "退部分材料",
                BlockIslandRules.canRemoveAt(island, target.first, target.second)) { perform("remove") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("背包与工具", color = Color(0xFFF0FCF4), fontWeight = FontWeight.Bold,
                fontSize = 16.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { showRecipes = !showRecipes }) {
                Text(if (showRecipes) "收起配方" else "查看配方", color = Color(0xFFB9EACB), fontSize = 12.sp)
            }
        }
        if (showRecipes) {
            Surface(color = Color(0xFF213D41), shape = RoundedCornerShape(17.dp)) {
                Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("靠近工作台制作工具 · 制作后永久放在背包", fontSize = 11.sp,
                        color = Color(0xFFBFDCD0))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IslandAction(if ("axe" in island.tools) "✓ 木斧" else "制作木斧", enabled = "axe" !in island.tools,
                            Modifier.weight(1f)) { perform("craft_axe", world.x, world.y) }
                        IslandAction(if ("pickaxe" in island.tools) "✓ 石镐" else "制作石镐", enabled = "pickaxe" !in island.tools,
                            Modifier.weight(1f)) { perform("craft_pickaxe", world.x, world.y) }
                        IslandAction(if ("sword" in island.tools) "✓ 石剑" else "制作石剑", enabled = "sword" !in island.tools,
                            Modifier.weight(1f)) { perform("craft_sword", world.x, world.y) }
                        IslandAction(if ("rod" in island.tools) "✓ 鱼竿" else "制作鱼竿", enabled = "rod" !in island.tools,
                            Modifier.weight(1f)) { perform("craft_rod", world.x, world.y) }
                    }
                    Text("木斧 2木1石 · 石镐 2木2石 · 石剑 1木3石 · 鱼竿 2木1石",
                        color = Color(0xFFB8D0C9), fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("篝火或木床旁可以恢复体力", modifier = Modifier.weight(1f),
                            color = Color(0xFFB8D0C9), fontSize = 11.sp)
                        TextButton(onClick = { perform("rest", world.x, world.y) }) {
                            Text("就地休息", color = Color(0xFFBCE9CB), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        if (world.friends.isNotEmpty()) {
            Text("同行伙伴", color = Color(0xFFF0FCF4), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            world.friends.forEach { friend ->
                Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF233E43)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        val character = MigratedDomainStores.characters.get(friend.id)
                        Column(Modifier.weight(1f)) {
                            Text(character.displayName, color = Color.White,
                                fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                            Text("生命${friend.hp}/8", color = Color(0xFFB6D0CC), fontSize = 10.sp)
                        }
                        if (!autonomousCompanions) listOf("follow" to "跟随", "gather" to "采集", "guard" to "守卫").forEach { (mode, label) ->
                            FilterChip(
                                selected = friend.mode == mode,
                                onClick = { save(BlockIslandRules.setCompanionMode(island, friend.id, mode)) },
                                label = { Text(label, fontSize = 10.sp) },
                                modifier = Modifier.height(34.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF9DCFB8),
                                    selectedLabelColor = Color(0xFF152D2F),
                                    labelColor = Color(0xFFE2F5E6),
                                ),
                            )
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                val details = island.toJson()
                val recordId = store.recordExternalGame(
                    type = LuluGameType.BlockIsland,
                    title = "方块小岛",
                    score = world.wood + world.stone + island.tools.size * 10 +
                        world.tiles.count { it == 3 } * 3 + island.structures.size * 8,
                    reward = 0,
                    summary = "探索${world.turns}步，采集木材${world.wood}、石料${world.stone}；" +
                        "制作工具${island.tools.size}件，建造设施${island.structures.size}处。",
                    detailsJson = details,
                    characterIdOverride = world.friends.firstOrNull()?.id.orEmpty(),
                    playedWithCharacterOverride = world.friends.isNotEmpty(),
                )
                // The generic recorder handles the primary companion. Each other participant
                // gets only the firsthand game events explicitly attributed to that identity.
                world.friends.drop(1).forEach { friend ->
                    val ownActions = world.events.filter { it.contains("队友[${friend.id}]") }
                    com.jiacimu.lulu.data.SharedExperienceTimeline.record(
                        eventId = "block-island-participant-$recordId-${friend.id}",
                        characterId = friend.id,
                        channel = "共同游戏《方块小岛》",
                        speaker = "游戏记录",
                        content = "实际参与方块小岛探索；本人的行动记录：" +
                            if (ownActions.isEmpty()) "本轮没有独立操作" else ownActions.takeLast(12).joinToString("；"),
                        occurredAt = java.time.Instant.now(),
                    )
                }
                save(island.copy(world = world.copy(notice = "本次旅程已记入游戏记录，家园会继续保留")))
            }, modifier = Modifier.weight(1f)) { Text("记录旅程", color = Color(0xFFE8F8EB)) }
            OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.weight(1f)) {
                Text("新建岛屿", color = Color(0xFFE8F8EB))
            }
        }
        Text("家园自动存档 · 建筑与背包会随下次进入恢复",
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            fontSize = 10.sp, color = Color(0xFF9AC0BA))
    }

        },
    )

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("重新生成小岛？") },
            text = { Text("将清除当前家园、工具和背包，但不会删除已经保存的游戏历史。") },
            containerColor = Color(0xFF1A3439),
            titleContentColor = Color(0xFFEDF8EF),
            textContentColor = Color(0xFFBDD5CC),
            confirmButton = {
                TextButton(onClick = {
                    save(BlockIslandRules.newWorld(Random.nextInt(), if(game.playWithCharacter)game.selectedCharacterIds else emptyList()))
                    target = island.world.x to island.world.y
                    navigateTo = null
                    confirmReset = false
                }) { Text("重新开始") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun IslandStat(label: String, value: String, tint: Color, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(13.dp),
        color = Color(0xFF254A4A), border = BorderStroke(1.dp, tint.copy(alpha = .2f))) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            Text(label, color = tint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.width(5.dp))
            Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun IslandAction(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.height(38.dp), enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 3.dp, vertical = 1.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFA9D8B9), contentColor = Color(0xFF17342F),
        )) { Text(label, fontSize = 10.sp, maxLines = 1) }
}

@Composable
private fun IslandBuildChip(label: String, cost: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(color = Color(0xFF284B4E), border = BorderStroke(1.dp, Color(0xFF578277)),
        shape = RoundedCornerShape(13.dp)) {
        Column(Modifier.width(94.dp).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(31.dp)) {
                Text(label, color = if (enabled) Color.White else Color.Gray, fontSize = 12.sp)
            }
            Text(cost, color = Color(0xFFC1D8D1), fontSize = 9.sp)
        }
    }
}

private fun islandCellTitle(tile: Int, structure: String?): String =
    when (structure) {
        "door" -> "木门"; "workbench" -> "工作台"; "campfire" -> "篝火"; "bed" -> "木床"; "bridge" -> "木桥"
        else -> when (tile) {
            0 -> "草地"; 1 -> "树木"; 2 -> "石矿"; 3 -> "木墙"
            4 -> "地板"; 7 -> "营地"; 8 -> "水域"; else -> "空地"
        }
    }

@Composable
private fun BlockIslandCanvas(
    island: BlockIslandState,
    target: Pair<Int, Int>,
    zoomed: Boolean,
    night: Boolean,
    impact: BlockIslandImpact?,
    impactProgress: Float,
    navigationTarget: Pair<Int, Int>? = null,
    onSelect: (Int, Int) -> Unit,
    onNavigate: (Int, Int) -> Unit,
    fullscreen: Boolean = false,
    companionPing: Pair<Int, Int>? = null,
) {
    val w = island.world
    val playerVisual = islandActorPosition(w.seed,"player",w.x,w.y)
    val companionVisuals = w.friends.associate { friend ->
        key(friend.id) { friend.id to islandActorPosition(w.seed,friend.id,friend.x,friend.y) }
    }
    // Only the Canvas recomposes for breeze/water/fire; controls & saved state stay stable.
    val scenery = rememberInfiniteTransition(label = "island-weather")
    val phase by scenery.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5400, easing = LinearEasing), RepeatMode.Restart),
        label = "island-breeze",
    )
    // In landscape a broad, shallower viewport keeps the world above the controls
    // instead of creating a 600dp-tall square that needs several scroll gestures.
    val config = LocalConfiguration.current
    val landscape = config.screenWidthDp > config.screenHeightDp
    val cols = if (landscape) { if (zoomed) 18 else 28 } else { if (zoomed) 14 else 22 }
    val rows = if (fullscreen) (cols * ((config.screenHeightDp - 54).coerceAtLeast(160).toFloat() /
        config.screenWidthDp.coerceAtLeast(1))).toInt().coerceIn(6, 32)
        else if (landscape) { if (zoomed) 7 else 10 } else { if (zoomed) 11 else 16 }
    // The player and camera share the same interpolated position. Even at the world edge,
    // keep the actor in the clear center instead of sliding beneath the thumb controls.
    val latestPlayerVisual by rememberUpdatedState(playerVisual)
    val navigationStep = remember(island, navigationTarget) {
        navigationTarget?.let {
            BlockIslandRules.nextStep(island, w.x, w.y, it.first, it.second)
        }
    }
    val latestSelect by rememberUpdatedState(onSelect)
    val latestNavigate by rememberUpdatedState(onNavigate)
    Canvas(
        (if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(cols.toFloat() / rows))
            .pointerInput(cols, rows, w.width, w.height, w.seed) {
                fun targetFor(tap: Offset): Pair<Int, Int>? =
                    islandFollowCamera(
                        size.width.toFloat(), size.height.toFloat(), cols, rows,
                        latestPlayerVisual.x, latestPlayerVisual.y, w.width, w.height,
                    ).tileAt(tap.x, tap.y, w.width, w.height)
                detectTapGestures(
                    onTap = { point -> targetFor(point)?.let { (x, y) -> latestSelect(x, y) } },
                    onDoubleTap = { point -> targetFor(point)?.let { (x, y) -> latestNavigate(x, y) } },
                    onLongPress = { point -> targetFor(point)?.let { (x, y) -> latestNavigate(x, y) } },
                )
            },
    ) {
        val camera = islandFollowCamera(
            size.width, size.height, cols, rows,
            playerVisual.x, playerVisual.y, w.width, w.height,
        )
        with(BlockIslandArt) {
            paint(
                island = island, selected = target, companionPing = companionPing,
                playerVisual = playerVisual, companionVisuals = companionVisuals, camera = camera,
                navigationStep = navigationStep,
                night = night, phase = phase, impact = impact, impactProgress = impactProgress,
            )
        }
    }
}

/** Interpolate real adjacent moves only. Scene resets and respawns snap, never fly across the map. */
@Composable
private fun islandActorPosition(seed: Int,id: String,x: Int,y: Int): Offset {
    val target=Offset(x.toFloat(),y.toFloat())
    val position=remember(seed,id) { Animatable(target,Offset.VectorConverter) }
    val previous=remember(seed,id) { arrayOf(target) }
    LaunchedEffect(seed,id,x,y) {
        val distance=abs(previous[0].x-target.x)+abs(previous[0].y-target.y)
        previous[0]=target
        if(distance<=1f)position.animateTo(target,tween(145,easing=LinearEasing))
        else position.snapTo(target)
    }
    return position.value
}
