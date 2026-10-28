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
    var zoomed by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var showRecipes by remember { mutableStateOf(true) }

    fun save(next: BlockIslandState) {
        island = next
        preferences.edit().putString("world", next.toJson()).apply()
    }
    fun perform(action: String, x: Int = target.first, y: Int = target.second) {
        navigateTo = null
        save(BlockIslandRules.act(island, action, x, y))
    }

    LaunchedEffect(game.selectedCharacterIds) {
        val wanted = game.selectedCharacterIds.distinct().take(3)
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
            save(island.copy(world = world.copy(notice = "已抵达目标附近，或路线被树林、矿石挡住")))
            return@LaunchedEffect
        }
        delay(145)
        val next = BlockIslandRules.act(island, "move", step.first, step.second)
        if (next.world.x == world.x && next.world.y == world.y) navigateTo = null
        save(next)
    }

    val night = world.turns % 48 >= 30
    val selectedCell = world.cell(target.first, target.second)
    val selectedStructure = island.structures[world.index(target.first, target.second)]
    val nearTarget = world.adjacent(target.first, target.second)
    val isMobSelected = world.mobs.any { it.hp > 0 && it.x == target.first && it.y == target.second }

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
                Text("种树采矿 · 自由盖房 · 和伙伴一起冒险", color = Color(0xFFBCE2D8), fontSize = 11.sp)
            }
            Surface(shape = RoundedCornerShape(13.dp), color = Color(0xFF2F6063)) {
                Text(if (night) "☾ 夜晚" else "☀ 白天", modifier = Modifier.padding(10.dp),
                    color = Color(0xFFEBFBEC), fontSize = 12.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            IslandStat("❤", "${world.hp}/12", Color(0xFFFFBFC0), Modifier.weight(1f))
            IslandStat("木", "${world.wood}", Color(0xFFBEEAB4), Modifier.weight(1f))
            IslandStat("石", "${world.stone}", Color(0xFFC8D9E9), Modifier.weight(1f))
            IslandStat("果", "${island.fruit}", Color(0xFFF5D79B), Modifier.weight(1f))
        }
        Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF142D33),
            border = BorderStroke(1.dp, Color(0xFF75A39D).copy(alpha = .45f))) {
            Column(Modifier.padding(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text("我的小岛 · 第${world.turns}步", color = Color(0xFFE1F8EB),
                        fontWeight = FontWeight.SemiBold, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { zoomed = !zoomed }) {
                        Text(if (zoomed) "缩小地图" else "放大地图", fontSize = 11.sp,
                            color = Color(0xFFA8E7C7))
                    }
                }
                BlockIslandCanvas(island, target, zoomed, night) { x, y ->
                    target = x to y
                    navigateTo = null
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp)) {
                    Text("(${target.first}, ${target.second}) · ${islandCellTitle(selectedCell, selectedStructure)}${if (isMobSelected) " · 野怪" else ""}",
                        modifier = Modifier.weight(1f), color = Color(0xFFC4E3D5), fontSize = 11.sp)
                    TextButton(onClick = { navigateTo = target },
                        enabled = target != (world.x to world.y)) { Text("前往", color = Color(0xFFB2F0CF)) }
                }
            }
        }
        Surface(shape = RoundedCornerShape(13.dp), color = Color(0xFF274C50)) {
            Text(world.notice, modifier = Modifier.fillMaxWidth().padding(11.dp),
                color = Color(0xFFDDF7ED), fontSize = 12.sp, lineHeight = 17.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(130.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                IslandDirection("↑") { perform("move", world.x, world.y - 1) }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IslandDirection("←") { perform("move", world.x - 1, world.y) }
                    IslandDirection("↓") { perform("move", world.x, world.y + 1) }
                    IslandDirection("→") { perform("move", world.x + 1, world.y) }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                IslandAction("采集资源", enabled = nearTarget && selectedCell in listOf(1, 2),
                    Modifier.fillMaxWidth()) { perform("gather") }
                IslandAction("攻击野怪", enabled = nearTarget && isMobSelected,
                    Modifier.fillMaxWidth()) { perform("fight") }
                IslandAction("吃野果 · 回血", enabled = island.fruit > 0 && world.hp < 12,
                    Modifier.fillMaxWidth()) { perform("eat", world.x, world.y) }
            }
        }
        Text("自由建造", color = Color(0xFFF0FCF4), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("选中身边的空地，再选择建筑。拆除会返还部分材料。",
            color = Color(0xFFAAC7C5), fontSize = 11.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            IslandBuildChip("♣ 种树", "1木", nearTarget) { perform("plant") }
            IslandBuildChip("▤ 地板", "1木", nearTarget) { perform("floor") }
            IslandBuildChip("▥ 木墙", "2木", nearTarget) { perform("wall") }
            IslandBuildChip("▣ 木门", "2木", nearTarget) { perform("door") }
            IslandBuildChip("⚒ 工作台", "4木2石", nearTarget) { perform("workbench") }
            IslandBuildChip("♨ 篝火", "2木1石", nearTarget) { perform("campfire") }
            IslandBuildChip("↶ 拆除", "退部分材料", nearTarget) { perform("remove") }
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
                    }
                    Text("木斧 2木1石 · 石镐 2木2石 · 石剑 1木3石",
                        color = Color(0xFFB8D0C9), fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("篝火旁可以恢复体力", modifier = Modifier.weight(1f),
                            color = Color(0xFFB8D0C9), fontSize = 11.sp)
                        TextButton(onClick = { perform("rest", world.x, world.y) }) {
                            Text("篝火休息", color = Color(0xFFBCE9CB), fontSize = 12.sp)
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
                        listOf("follow" to "跟随", "gather" to "采集", "guard" to "守卫").forEach { (mode, label) ->
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
                store.recordExternalGame(
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

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("重新生成小岛？") },
            text = { Text("将清除当前家园、工具和背包，但不会删除已经保存的游戏历史。") },
            confirmButton = {
                TextButton(onClick = {
                    save(BlockIslandRules.newWorld(Random.nextInt(), game.selectedCharacterIds))
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
private fun IslandDirection(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick,
        modifier = Modifier.size(42.dp), shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = Color(0xFF396367), contentColor = Color.White,
        )) { Text(label, fontWeight = FontWeight.Bold, fontSize = 19.sp) }
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
        "door" -> "木门"; "workbench" -> "工作台"; "campfire" -> "篝火"
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
    onSelect: (Int, Int) -> Unit,
) {
    val w = island.world
    val cols = if (zoomed) 14 else 22
    val rows = if (zoomed) 11 else 16
    val left = (w.x - cols / 2).coerceIn(0, (w.width - cols).coerceAtLeast(0))
    val top = (w.y - rows / 2).coerceIn(0, (w.height - rows).coerceAtLeast(0))
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(cols.toFloat() / rows)
            .pointerInput(left, top, cols, rows, onSelect) {
                detectTapGestures { tap ->
                    val tileSize = size.width / cols
                    val x = left + (tap.x / tileSize).toInt()
                    val y = top + (tap.y / tileSize).toInt()
                    if (w.inBounds(x, y) && x < left + cols && y < top + rows) onSelect(x, y)
                }
            },
    ) {
        val tileSize = size.width / cols
        fun centre(x: Int, y: Int) = Offset(
            (x - left + .5f) * tileSize, (y - top + .5f) * tileSize,
        )
        for (y in top until (top + rows).coerceAtMost(w.height)) {
            for (x in left until (left + cols).coerceAtMost(w.width)) {
                val cell = w.cell(x, y)
                val pos = Offset((x - left) * tileSize, (y - top) * tileSize)
                val grass = if ((x + y) % 3 == 0) Color(0xFF63916A) else Color(0xFF53835D)
                val base = when (cell) {
                    1 -> Color(0xFF447A53)
                    2 -> Color(0xFF767E77)
                    3 -> Color(0xFF785D4A)
                    4 -> Color(0xFFBD9D73)
                    7 -> Color(0xFFE3C38A)
                    8 -> Color(0xFF397A93)
                    else -> grass
                }
                drawRect(base, topLeft = pos, size = Size(tileSize + .5f, tileSize + .5f))
                drawRect(Color.Black.copy(alpha = .08f), topLeft = pos,
                    size = Size(tileSize, tileSize), style = Stroke(.7f))
                when (cell) {
                    1 -> {
                        drawCircle(Color(0xFF205C40), tileSize * .33f, centre(x, y))
                        drawCircle(Color(0xFF9CD28B), tileSize * .15f,
                            centre(x, y) + Offset(-tileSize * .09f, -tileSize * .11f))
                    }
                    2 -> {
                        drawRoundRect(Color(0xFFCFD6C8),
                            topLeft = pos + Offset(tileSize * .13f, tileSize * .23f),
                            size = Size(tileSize * .72f, tileSize * .59f),
                            cornerRadius = CornerRadius(tileSize * .14f))
                        drawCircle(Color(0xFF828F99), tileSize * .13f, centre(x, y))
                    }
                    3 -> {
                        drawRect(Color(0xFFDCBB90), topLeft = pos + Offset(2f, 2f),
                            size = Size(tileSize - 4f, tileSize - 4f))
                        drawRect(Color(0xFF987554), topLeft = pos + Offset(3f, tileSize * .48f),
                            size = Size(tileSize - 6f, tileSize * .08f))
                    }
                    7 -> drawCircle(Color(0xFFFFE2A2), tileSize * .36f, centre(x, y))
                }
                when (island.structures[w.index(x, y)]) {
                    "door" -> {
                        drawRoundRect(Color(0xFF754F42),
                            topLeft = pos + Offset(tileSize * .25f, tileSize * .14f),
                            size = Size(tileSize * .50f, tileSize * .75f),
                            cornerRadius = CornerRadius(3f))
                        drawCircle(Color(0xFFF9E29A), tileSize * .04f,
                            centre(x, y) + Offset(tileSize * .13f, 0f))
                    }
                    "workbench" -> {
                        drawRoundRect(Color(0xFF694833),
                            topLeft = pos + Offset(tileSize * .12f, tileSize * .20f),
                            size = Size(tileSize * .76f, tileSize * .64f),
                            cornerRadius = CornerRadius(3f))
                        drawRect(Color(0xFFD5B479),
                            topLeft = pos + Offset(tileSize * .08f, tileSize * .26f),
                            size = Size(tileSize * .84f, tileSize * .18f))
                    }
                    "campfire" -> {
                        drawCircle(Color(0xFFFFD58D).copy(alpha = .25f),
                            tileSize * .53f, centre(x, y))
                        drawCircle(Color(0xFFFF9F52), tileSize * .27f, centre(x, y))
                        drawCircle(Color(0xFFFFEA99), tileSize * .15f,
                            centre(x, y) + Offset(0f, -tileSize * .11f))
                    }
                }
                if (night) drawRect(Color(0xFF15214B).copy(alpha = .29f),
                    topLeft = pos, size = Size(tileSize, tileSize))
                if (x == target.first && y == target.second) drawRect(Color(0xFFFFECAC),
                    topLeft = pos + Offset(1f, 1f),
                    size = Size(tileSize - 2f, tileSize - 2f), style = Stroke(2.5f))
            }
        }
        w.mobs.filter { it.hp > 0 && it.x in left until left + cols && it.y in top until top + rows }
            .forEach { mob ->
                drawCircle(Color(0xFF493048), tileSize * .39f, centre(mob.x, mob.y))
                drawCircle(Color(0xFFFF9BA1), tileSize * .09f,
                    centre(mob.x, mob.y) + Offset(-tileSize * .12f, -tileSize * .05f))
                drawCircle(Color(0xFFFF9BA1), tileSize * .09f,
                    centre(mob.x, mob.y) + Offset(tileSize * .12f, -tileSize * .05f))
            }
        w.friends.filter { it.hp > 0 && it.x in left until left + cols && it.y in top until top + rows }
            .forEach { friend ->
                drawCircle(Color(0xFF8BD7ED), tileSize * .37f, centre(friend.x, friend.y))
                drawCircle(Color(0xFF36576F), tileSize * .17f,
                    centre(friend.x, friend.y) + Offset(0f, tileSize * .09f))
            }
        drawCircle(Color(0xFF1D403D).copy(alpha = .45f),
            tileSize * .46f, centre(w.x, w.y) + Offset(2f, 3f))
        drawCircle(Color(0xFFFFE9C8), tileSize * .39f, centre(w.x, w.y))
        drawCircle(Color(0xFF658B82), tileSize * .19f,
            centre(w.x, w.y) + Offset(0f, tileSize * .1f))
        drawCircle(Color.White, tileSize * .07f,
            centre(w.x, w.y) + Offset(tileSize * .12f, -tileSize * .10f))
    }
}
