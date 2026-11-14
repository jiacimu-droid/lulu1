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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import com.jiacimu.lulu.R
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
    var soundEnabled by remember { mutableStateOf(preferences.getBoolean("island_sound_enabled",true)) }
    var hapticsEnabled by remember { mutableStateOf(preferences.getBoolean("island_haptics_enabled",true)) }
    fun playSound(effect: GameSoundEffect) {
        if(soundEnabled) GameSoundEffects.play(effect)
    }
    var target by remember { mutableStateOf(world.x to world.y) }
    // The D-pad turns the player even when movement is blocked by a tree or monster.
    // Map selection is an optional precise command, not a prerequisite for gathering.
    var facing by remember(world.seed) { mutableStateOf(0 to 1) }
    var joystickHeld by remember(world.seed) { mutableStateOf(false) }
    var mapAim by remember(world.seed) { mutableStateOf(false) }
    var menuSection by rememberSaveable { mutableStateOf("行囊") }
    LaunchedEffect(menuSection) {
        if (menuSection !in setOf("地图","行囊","伙伴","图鉴","设置")) menuSection = "行囊"
    }
    var buildMode by remember(world.seed) { mutableStateOf<String?>(null) }
    var lastBuildMode by remember(world.seed) { mutableStateOf("wall") }
    // A recipe may be tapped again; an integer event always dismisses the tools panel.
    var dismissToolsVersion by remember { mutableIntStateOf(0) }
    var workbenchOpen by remember { mutableStateOf(false) }
    var buildCatalogOpen by remember { mutableStateOf(false) }
    var goalsOpen by remember { mutableStateOf(false) }
    var navigateTo by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var zoomed by remember { mutableStateOf(true) }
    var confirmReset by remember { mutableStateOf(false) }
    var showIslandGuideDialog by remember { mutableStateOf(false) }
    var islandGuideTab by rememberSaveable { mutableStateOf("入门") }
    var companionPing by remember(world.seed) { mutableStateOf<Pair<Int, Int>?>(null) }
    var autonomousCompanions by rememberSaveable { mutableStateOf(true) }
    val autonomousIds = if (autonomousCompanions) world.friends.map { it.id }.toSet() else emptySet()
    var impact by remember { mutableStateOf<BlockIslandImpact?>(null) }
    val impactProgress = remember { Animatable(1f) }
    var previousHp by remember(world.seed) { mutableIntStateOf(world.hp) }
    val hurtProgress = remember(world.seed) { Animatable(1f) }
    LaunchedEffect(world.seed,world.hp) {
        val lost = previousHp-world.hp
        previousHp=world.hp
        if(lost>0) {
            hurtProgress.snapTo(0f)
            hurtProgress.animateTo(1f,tween(520))
        }
    }
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
            val label = when(action) {
                "gather" -> {
                    val resource=if(island.world.cell(x,y)==1)
                        "+${result.world.wood-island.world.wood} 木"
                        else "+${result.world.stone-island.world.stone} 石"
                    val fruitGain=result.fruit-island.fruit
                    val gems=result.crystals-island.crystals
                    resource + (if(gems>0)" · +$gems 晶石" else "") +
                        if(fruitGain>0) " · +$fruitGain 果" else ""
                }
                "fight" -> {
                    val enemy = island.world.mobs.firstOrNull {
                        it.hp>0 && it.x==x && it.y==y
                    }
                    val remaining = result.world.mobs.firstOrNull { it.id==enemy?.id }?.hp ?: 0
                    enemy?.let { "-${(it.hp-remaining).coerceAtLeast(0)} ❤" } ?: ""
                }
                "salvage" -> {
                    val fruitGain=result.fruit-island.fruit
                    "+${result.world.wood-island.world.wood} 木 · +${result.world.stone-island.world.stone} 石" +
                        if(fruitGain>0) " · +$fruitGain 果" else ""
                }
                "revive" -> "队友已救起"
                "recover_pack" -> "背包物资已找回"
                "craft_charm" -> "✧ 救命护符"
                "tame" -> "❤ 新伙伴"
                "care" -> when(island.animals.firstOrNull { it.x==x && it.y==y }?.species) {
                    "hare" -> "+1 果"; "duck" -> "+1 鱼"; "fox" -> "+2 石"; else -> "❤"
                }
                "eat", "eat_fish", "rest" -> {
                    val hpGain=(result.world.hp-island.world.hp).coerceAtLeast(0)
                    if(hpGain>0) "+$hpGain ❤" else "已休息"
                }
                "plant", "wall", "floor", "door", "workbench", "campfire", "bed",
                "bridge", "lantern", "crystal_lantern", "pet_house", "garden", "remove",
                "house_warm", "house_pine" -> "✓"
                "house_step" -> "小屋施工 +1格"
                "sow_berries" -> "种下野果"
                "tend_berries" -> "幼苗已照料"
                "harvest_berries" -> "+${result.fruit-island.fruit} 果"
                else -> ""
            }
            val targetMobId = if(action=="fight") island.world.mobs.firstOrNull {
                it.hp>0 && it.x==x && it.y==y
            }?.id else null
            impact = BlockIslandImpact(x,y,action,result.world.turns,label,targetMobId)
            if(hapticsEnabled) haptics.performHapticFeedback(
                if(action=="fight") HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
            playSound(when (action) {
                "move" -> GameSoundEffect.Move
                "gather", "salvage", "recover_pack", "eat", "eat_fish", "fish", "revive", "tame", "care" -> GameSoundEffect.Collect
                "fight" -> GameSoundEffect.Objective
                else -> GameSoundEffect.Interact
            })
        } else {
            playSound(GameSoundEffect.Bump)
        }
        if (action in setOf("house_warm","house_pine") &&
            result.houseProject?.ownerId=="player" && island.houseProject==null) {
            buildMode=null
            mapAim=false
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
            if (!nearby) playSound(GameSoundEffect.Bump)
            return@LaunchedEffect
        }
        delay(145)
        facing = (step.first-world.x) to (step.second-world.y)
        val next = BlockIslandRules.act(island, "move", step.first, step.second, autonomousIds)
        if (next.world.x == world.x && next.world.y == world.y) {
            navigateTo = null
            playSound(GameSoundEffect.Bump)
        } else {
            playSound(GameSoundEffect.Move)
        }
        save(next)
    }

    val night = world.turns % 48 >= 30
    val ahead = (world.x+facing.first) to (world.y+facing.second)
    val aimedCell = if (mapAim && world.inBounds(target.first,target.second)) target
        else if (world.inBounds(ahead.first,ahead.second)) ahead else world.x to world.y
    val selectedCell = world.cell(aimedCell.first,aimedCell.second)
    val selectedStructure = island.structures[world.index(aimedCell.first,aimedCell.second)]
    val nearTarget = world.adjacent(aimedCell.first,aimedCell.second)
    val isMobSelected = world.mobs.any { it.hp > 0 && it.x == aimedCell.first && it.y == aimedCell.second }
    val primaryAction = islandTargetAction(island,aimedCell)
    val supplySelected = IslandSupplyCaches.available(island,aimedCell.first,aimedCell.second)
    val animalSelected = island.animals.firstOrNull {
        it.x==aimedCell.first && it.y==aimedCell.second
    }
    val targetTitle = when {
        supplySelected -> if (island.world.index(aimedCell.first,aimedCell.second) in island.rareSupplyCaches) "✦ 潮汐秘藏" else "漂流补给箱"
        animalSelected!=null -> IslandWildlife.name(animalSelected.species)
        else -> islandCellTitle(selectedCell,selectedStructure)
    }
    val buildPreview = IslandBuildingControls.preview(island,buildMode,aimedCell.first,aimedCell.second)
    val buildReady = buildPreview?.allowed == true
    val deliberateTarget = target.takeIf { mapAim }
    val nearbyGather = islandNearbyAction(island,facing,setOf("gather","salvage","recover_pack","fish",
        "sow_berries","tend_berries","harvest_berries"),deliberateTarget)
    val nearbyFight = islandNearbyAction(island,facing,setOf("fight"),deliberateTarget)
    val nearbyRescue = islandNearbyAction(island,facing,setOf("revive"),deliberateTarget)
    val nearbyWildlife = islandNearbyAction(island,facing,setOf("tame","care"),deliberateTarget)
    val nearbyPrimary = nearbyFight ?: nearbyRescue ?: nearbyWildlife ?: nearbyGather
    val directionLabel = when(facing) {
        0 to -1 -> "↑"; 1 to 0 -> "→"; -1 to 0 -> "←"; else -> "↓"
    }
    val workbench = island.structures.entries.filter { it.value == "workbench" }
        .minByOrNull { entry ->
            abs(world.x - entry.key % world.width) + abs(world.y - entry.key / world.width)
        }
    val workbenchNear = workbench != null &&
        abs(world.x - workbench.key % world.width) + abs(world.y - workbench.key / world.width) <= 2
    val campRestAvailable = islandCampRestAvailable(island)
    // Later collection objectives are derived from the saved island, not reset by closing UI.
    val survivalGoal = IslandMilestones.next(island)
    val nextLandmark = IslandExpeditionTrail.next(island)
    val activeGuardian = IslandGuardianEncounter.active(island)

    val worldNotice = remember(world.notice,world.friends.map { it.id }) {
        world.friends.fold(world.notice) { line,friend ->
            val name=MigratedDomainStores.characters.get(friend.id).displayName
            line.replace("队友[${friend.id}]",name).replace("行程[${friend.id}]",name)
        }
    }

    GameSceneDeck(
        accent = Color(0xFFA9E5BD),
        healthFraction = world.hp / 12f,
        secondaryFraction = island.hunger / 10f,
        secondaryLabel = "饱食",
        compactStatus = true,
        compactSummary = "❤ ${world.hp}/12 · 饱 ${island.hunger}/10",
        toolsLabel = "☰ 行囊 / 更多",
        closeToolsSignal = dismissToolsVersion,
        notice = if (activeGuardian != null &&
            abs(activeGuardian.x - world.x) + abs(activeGuardian.y - world.y) <= 2 &&
            IslandGuardianEncounter.impendingHeavyAttack(island))
            "⚠ 守卫下一回合重击 · 躲开" else "",
        status = "❤ ${world.hp}/12  饱 ${island.hunger}/10  木 ${world.wood}  石 ${world.stone}  果 ${island.fruit}  " +
            IslandGarden.season(world.turns) + (if (night) "夜" else "昼"),
        scene = {
            BlockIslandCanvas(island, aimedCell, zoomed, night, impact, impactProgress.value,
                navigationTarget = navigateTo,
                onSelect = { x, y -> target = x to y; mapAim=true; navigateTo = null },
                onNavigate = { x, y ->
                    target = x to y
                    mapAim = true
                    // Long-press must only aim while building. It must not walk away
                    // from the work area or spend a turn on an unexpected move.
                    navigateTo = if(buildMode!=null) null
                        else (x to y).takeUnless { it == (world.x to world.y) }
                    if (hapticsEnabled)
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                fullscreen = true, companionPing = companionPing,
                buildPreview = buildPreview, facing = facing,
                hurtProgress = hurtProgress.value)
        },
        companionAvailable = game.playWithCharacter && world.friends.isNotEmpty() &&
            world.friends.map { it.id } == game.selectedCharacterIds.distinct().take(3),
        companion = {
            if (game.playWithCharacter && world.friends.isNotEmpty() &&
                world.friends.map { it.id } == game.selectedCharacterIds.distinct().take(3)) {
                IslandCompanionPanel(island, navigateTo != null || joystickHeld, autonomousCompanions,
                    { enabled ->
                        autonomousCompanions = enabled
                        if (!enabled) save(IslandTravel.stopAll(island))
                    }, ::save, { companionPing = it }, readIsland = { island })
            }
        },
        movement = {
            val center = islandNearbyAction(island, facing, selectedTarget = deliberateTarget)
            // Workbench interaction should remain discoverable near resources.
            val workstationAction = deliberateTarget == null && workbenchNear && center?.action !in setOf(
                "fight", "revive", "tame", "care", "salvage", "recover_pack", "gather", "fish")
            // A map selection is deliberate: a blocked or distant target must
            // not silently turn the center button into resting or crafting.
            val restingAction = deliberateTarget == null && campRestAvailable && !workstationAction && center == null
            GameDirectionalPad(accent = Color(0xFFA9E5BD), holdRepeatMs = 195L,
                stopWhen = world.hp to world.seed,
                interactLabel = if (buildMode != null) "确认建造" else
                    if (workstationAction) "使用工作台制作"
                    else if (restingAction) "在营地休息"
                    else center?.label ?: "附近互动",
                interactEnabled = if (buildMode != null) buildReady
                    else center != null || workstationAction || restingAction,
                onHoldChange = { joystickHeld = it },
                onInteract = {
                    when {
                        buildMode != null -> if (buildReady)
                            buildMode?.let { perform(it, aimedCell.first, aimedCell.second) }
                        workstationAction -> {
                            workbenchOpen = true
                        }
                        restingAction -> perform("rest", world.x, world.y)
                        center != null -> perform(center.action, center.x, center.y)
                    }
                }) { dx, dy ->
                facing = dx to dy
                mapAim = false
                navigateTo = null
                val x = world.x + dx
                val y = world.y + dy
                val front = islandTargetAction(island, x to y)
                // Movement NEVER performs a resource action. The player chooses
                // gathering/opening separately; gear is thus meaningful and
                // a held D-pad cannot accidentally destroy a crystal vein.
                val nextCell=IslandManualMovement.destination(island,dx,dy)
                if(nextCell!=null) {
                    perform("move",nextCell.first,nextCell.second)
                } else if(buildMode==null &&
                    front.action in setOf("gather","salvage","recover_pack")) {
                    // One hint, not repeated disk writes on every held-key tick.
                    val hint=if(front.enabled)
                        "前方是资源 · 点右侧采集键或方向盘中心互动；制作工具能提高产量"
                        else front.label
                    if(world.notice!=hint) save(island.copy(world=world.copy(notice=hint)))
                }
            }
        },
        quickActions = {
            // Show one actionable objective instead of a permanent stack of
            // quest, tracker, direction, protector and construction cards.

            // The quest and help share one short ribbon; never add another map-covering row.
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                Surface(
                    onClick={goalsOpen=true},
                    modifier=Modifier.weight(1f),
                    color=Color(0xBB263E39),shape=RoundedCornerShape(10.dp),
                ) {
                    Text("☑ " + survivalGoal.substringAfter(" · ", survivalGoal) + "  ›",
                        color=Color(0xFFFFE3A7),fontWeight=FontWeight.SemiBold,
                        fontSize=10.sp,maxLines=1,
                        modifier=Modifier.padding(horizontal=9.dp,vertical=6.dp))
                }
                Surface(onClick={islandGuideTab="入门";showIslandGuideDialog=true},
                    color=Color(0xBB263E39),shape=RoundedCornerShape(10.dp)) {
                    Text("？指南",color=Color(0xFFC9F2D9),
                        fontSize=10.sp,fontWeight=FontWeight.SemiBold,
                        modifier=Modifier.padding(horizontal=9.dp,vertical=6.dp))
                }
            }
            island.houseProject?.takeIf { it.ownerId=="player" }?.let { home ->
                val nextPart=IslandHouseProjects.nextPlayerPart(island)
                if (nextPart!=null) {
                    val bx=nextPart.first%world.width
                    val by=nextPart.first/world.width
                    val canWork=IslandHouseProjects.canPlayerBuildNext(island)
                    Surface(onClick={
                        buildMode=null
                        if(canWork) perform("house_step",bx,by)
                        else {
                            val needed=if(nextPart.second=="floor")1 else 2
                            if(world.wood<needed) {
                                save(island.copy(world=world.copy(
                                    notice="施工还需${needed}木材；先砍树，再回来亲手建造")))
                            } else {
                                // Approach an accessible *neighbour*, never walk onto
                                // the future solid wall itself.
                                val standingSpot=listOf(
                                    (bx-1) to by,(bx+1) to by,
                                    bx to (by-1),bx to (by+1),
                                ).filter { (sx,sy) ->
                                    BlockIslandRules.isWalkable(island,sx,sy) &&
                                        world.friends.none { it.x==sx && it.y==sy } &&
                                        world.mobs.none { it.hp>0 && it.x==sx && it.y==sy } &&
                                        island.animals.none { it.x==sx && it.y==sy } &&
                                        ((sx==world.x && sy==world.y) ||
                                            BlockIslandRules.nextStep(island,world.x,world.y,sx,sy)!=null)
                                }.minByOrNull { (sx,sy) -> abs(world.x-sx)+abs(world.y-sy) }
                                if (standingSpot==null) {
                                    save(island.copy(world=world.copy(
                                        notice="暂时走不到施工位，先清理房子旁的障碍")))
                                } else {
                                    target=bx to by
                                    mapAim=false
                                    navigateTo=standingSpot
                                }
                            }
                        }
                    }, color=Color(0xCB544A36),
                        shape=RoundedCornerShape(10.dp)) {
                        Text("⌂ 小屋 ${home.placed.size}/9 · " +
                            (if(canWork) "施工下一块（${bx},${by}）"
                            else "前往施工位（${bx},${by}）"),
                            modifier=Modifier.padding(horizontal=9.dp,vertical=6.dp),
                            color=Color(0xFFFFE2B1),fontSize=10.sp,maxLines=1)
                    }
                } else {
                    Text("⌂ ${IslandHouseProjects.styleName(home.style)}已完工",
                        color=Color(0xFFFFE6B3),fontSize=10.sp,maxLines=1)
                }
            }
            if (buildMode != null) {
                Text(IslandBuildingControls.label(buildMode) + " · " +
                    (buildPreview?.message ?: "选好地点后确认"),
                    color = if (buildReady) Color(0xFFBDF4D3) else Color(0xFFFFD0B6),
                    fontSize = 10.sp, maxLines = 1)
            }
            if (world.notice.isNotBlank() && world.notice != "走到了 (${world.x},${world.y})") {
                Text(worldNotice, color = Color(0xFFE7F4E6),
                    fontSize = 10.sp, maxLines = 1)
            }
        },
        primaryActions = {
            // Keep the world visible while browsing recipes with the right thumb.
            // Browsing never calls the game rules or spends a turn.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if(buildMode!=null) {
                    GameHudQuickAction("◀ 上个",Color(0xFFA9E5BD),
                        modifier=Modifier.weight(1f)) {
                        buildMode=IslandBuildingControls.cycle(buildMode,-1)
                        lastBuildMode=buildMode!!
                    }
                    GameHudQuickAction("下个 ▶",Color(0xFFA9E5BD),
                        modifier=Modifier.weight(1f)) {
                        buildMode=IslandBuildingControls.cycle(buildMode,1)
                        lastBuildMode=buildMode!!
                    }
                    GameHudQuickAction("退出",Color(0xFFE7D59C),
                        modifier=Modifier.weight(1f)) { buildMode=null }
                } else {
                    val gatherButton=nearbyGather?.let { near ->
                        when {
                            near.action=="gather" && world.cell(near.x,near.y)==1 ->
                                if("axe" in island.tools) "伐木+4" else "伐木+2"
                            near.action=="gather" && IslandProgression.crystalVein(
                                world.seed,world.index(near.x,near.y)) -> "晶石+1"
                            near.action=="gather" ->
                                if("pickaxe" in island.tools) "采石+4" else "采石+2"
                            near.action=="recover_pack" -> "取回背包"
                            else -> near.label.take(6)
                        }
                    } ?: "采集"
                    GameHudQuickAction(gatherButton, Color(0xFFA9E5BD),
                        enabled = nearbyGather != null, modifier = Modifier.weight(1f)) {
                        nearbyGather?.let { perform(it.action,it.x,it.y) }
                    }
                    GameHudQuickAction("攻击", Color(0xFFFFC9AE),
                        enabled = nearbyFight != null, modifier = Modifier.weight(1f)) {
                        nearbyFight?.let { perform(it.action,it.x,it.y) }
                    }
                    GameHudQuickAction("建造", Color(0xFFE7D59C),
                        modifier=Modifier.weight(1f)) { buildCatalogOpen = true }
                    GameHudQuickAction("制作", Color(0xFFA9E5BD),
                        enabled=workbenchNear, modifier=Modifier.weight(1f)) { workbenchOpen = true }
                }
            }
        },
        actions = {
            val mainAction = nearbyRescue ?: nearbyPrimary
                ?: if (campRestAvailable) IslandNearbyAction("rest", "在营地休息", world.x, world.y)
                else if (island.fruit > 0 && (world.hp < 12 || island.hunger < 8))
                    IslandNearbyAction("eat", "吃野果", world.x, world.y)
                else null
            Button(onClick = {
                if(navigateTo!=null) navigateTo=null
                else if(buildMode!=null) {
                    if(buildReady) buildMode?.let { perform(it,aimedCell.first,aimedCell.second) }
                } else if(mapAim && primaryAction.enabled && primaryAction.action=="move") {
                    navigateTo=target
                    if(hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                } else if(mapAim && primaryAction.enabled) {
                    perform(primaryAction.action,aimedCell.first,aimedCell.second)
                } else mainAction?.let { perform(it.action,it.x,it.y) }
            }, enabled = navigateTo!=null || if(buildMode!=null) buildReady
                else (if(mapAim) primaryAction.enabled else mainAction!=null),
                modifier=Modifier.weight(1f),
                colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFA9D8B9),
                    contentColor=Color(0xFF17342F))) {
                Text(if(navigateTo!=null) "■ 停止前往"
                    else if(buildMode!=null) "放置 · "+IslandBuildingControls.label(buildMode)
                    else if(mapAim) primaryAction.label+" · "+targetTitle
                    else mainAction?.label ?: "靠近后操作",
                    maxLines=1,fontSize=12.sp)
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
            TextButton(onClick = { showIslandGuideDialog=true }) {
                Text("？玩法", color=Color(0xFFBCEBCC),fontWeight=FontWeight.Bold)
            }
            Surface(shape = RoundedCornerShape(13.dp), color = Color(0xFF2F6063)) {
                Text(if (night) "☾ 夜晚" else "☀ 白天", modifier = Modifier.padding(10.dp),
                    color = Color(0xFFEBFBEC), fontSize = 12.sp)
            }
        }
        Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
            listOf("地图","行囊","伙伴","图鉴","设置").chunked(3).forEach { sections ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                    sections.forEach { section ->
                        FilterChip(selected=menuSection==section,onClick={menuSection=section},
                            label={Text(section,fontSize=11.sp)},
                            colors=FilterChipDefaults.filterChipColors(
                                selectedContainerColor=Color(0xFFA9E5BD),
                                selectedLabelColor=Color(0xFF12342D),
                                labelColor=Color(0xFFD0E8DB)),
                            modifier=Modifier.height(34.dp))
                    }
                }
            }
        }
        if (menuSection=="地图" || menuSection=="行囊") {
        if (menuSection == "地图") Surface(shape = RoundedCornerShape(16.dp),
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
                    else "寻找海岸补给箱；守过整夜后，偶尔会出现泛着海蓝光的潮汐秘藏。真正开箱才能拿到晶石。",
                    color = Color(0xFFB9DDCC), fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IslandStat("❤", "${world.hp}/12", Color(0xFFFFBFC0), Modifier.weight(1f))
            IslandStat("木", "${world.wood}", Color(0xFFBEEAB4), Modifier.weight(1f))
            IslandStat("石", "${world.stone}", Color(0xFFC8D9E9), Modifier.weight(1f))
            IslandStat("果", "${island.fruit}", Color(0xFFF5D79B), Modifier.weight(1f))
            IslandStat("鱼", "${island.fish}", Color(0xFFA4E6F3), Modifier.weight(1f))
            IslandStat("晶", "${island.crystals}", Color(0xFFD1B6F9), Modifier.weight(1f))
        }
        Surface(shape = RoundedCornerShape(13.dp), color = Color(0xFF274C50)) {
            Text(worldNotice, modifier = Modifier.fillMaxWidth().padding(11.dp),
                color = Color(0xFFDDF7ED), fontSize = 12.sp, lineHeight = 17.sp)
        }
        if(menuSection=="行囊") Column(Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("生命归零：回营恢复6/12生命，随身一半木石、野果、晶石掉在原地；到原地点击「找回遗失背包」取回。家园与制作的工具保留。",
                color=Color(0xFFF3D8B5),fontSize=11.sp)
            Text("工具制作后自动生效，无需手动装备。木斧砍树4木/次 · 石镐挖矿4石/次且可采蓝色晶矿 · 石剑伤害4 · 鱼竿钓鱼 · 晶石护符消耗后抵挡一次致命伤",
                color=Color(0xFFC8E4DD),fontSize=11.sp)
            Text("已制作："+(if(island.tools.isEmpty())"暂无" else island.tools.sorted().joinToString("、") { BlockIslandRules.actionName(it) }),
                color=Color(0xFFB5ECD5),fontSize=11.sp)
            Text(IslandProgression.foundCrystalText(island),
                color=Color(0xFFD6C2FA),fontSize=11.sp)
            if(island.lostPacks.isNotEmpty())
                Text("遗失背包：${island.lostPacks.size}处，点击地图定位后靠近拾回",
                    color=Color(0xFFFFCCA1),fontSize=11.sp)
            IslandAction("食用野果 · 回血/饱食", enabled=island.fruit>0 &&
                (world.hp<12 || island.hunger<10),Modifier.fillMaxWidth()) {
                perform("eat",world.x,world.y)
            }
            if(island.fish>0) IslandAction("篝火烤鱼 · 恢复体力与饱食",
                enabled=(world.hp<12 || island.hunger<10) && island.structures.any { it.value=="campfire" },Modifier.fillMaxWidth()) {
                perform("eat_fish",world.x,world.y)
            }
        }
        }
        if(menuSection=="地图") {
            IslandMiniMap(island)
            if(island.lostPacks.isNotEmpty()) {
                val nearest=island.lostPacks.keys.minByOrNull { idx ->
                    abs(world.x-idx%world.width)+abs(world.y-idx/world.width) }
                nearest?.let { key ->
                    val rx=key%world.width
                    val ry=key/world.width
                    IslandAction("遗失背包 · 坐标($rx,$ry) · 点选定位",enabled=true,
                        modifier=Modifier.fillMaxWidth()) {
                        target=rx to ry
                        mapAim=true
                    }
                }
            }
        }
        if(menuSection=="地图") {
        island.houseProject?.let { project ->
            Surface(shape = RoundedCornerShape(15.dp), color = Color(0xFF244950),
                border = BorderStroke(1.dp, Color(0xFF9EE8BF).copy(alpha = .4f))) {
                Column(Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val owner = if(project.ownerId=="player")"你" else
                        MigratedDomainStores.characters.get(project.ownerId).displayName
                    val phase = when(project.phase) {
                        "building" -> "施工中"; "paused" ->
                            if(project.resumePhase=="demolishing")"暂停拆除" else "暂停施工"
                        "complete" -> "已完成"; else -> "拆除中"
                    }
                    Text("⌂ "+owner+"的"+IslandHouseProjects.styleName(project.style)+" · "+phase,
                        color = Color.White,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text((if(project.phase=="demolishing" ||
                        project.phase=="paused" && project.resumePhase=="demolishing")
                        "已拆除 " else "已建造 ")+IslandHouseProjects.progress(project)+"/"+
                        IslandHouseProjects.totalSteps(project)+
                        " · 位置("+
                        project.left+","+project.top+")",
                        color = Color(0xFFBEE8D2), fontSize = 11.sp)
                    if(project.phase=="building" ||
                        (project.phase=="paused" && project.resumePhase=="building")) {
                        val woodStillNeeded=IslandHouseProjects.parts(world,project.left,project.top)
                            .filterNot { it.first in project.placed }
                            .fold(0) { total, part -> total + (if(part.second=="floor") 1 else 2) }
                        Text("施工还需木材 "+woodStillNeeded+" · 共享库存 "+world.wood+
                            if(woodStillNeeded>world.wood)
                                " · 缺口 "+(woodStillNeeded-world.wood) else " · 材料足够",
                            color=if(woodStillNeeded>world.wood)Color(0xFFFFD5AD)
                                else Color(0xFFBEE8D2),fontSize=11.sp)
                    }
                    LinearProgressIndicator(progress = {
                        IslandHouseProjects.progressFraction(project)
                    },
                        modifier = Modifier.fillMaxWidth(), color = Color(0xFFA6E8C3),
                        trackColor = Color(0xFF42636A))
                    Text(if(project.phase=="complete")
                        "房屋已完工，可进入使用；受损后可按实际材料修复。"
                        else if(project.phase=="demolishing" || project.phase=="paused" && project.resumePhase=="demolishing")
                        if(project.phase=="paused") "拆除已暂停，恢复后按实际进度继续。" else "正在逐块拆除，拆除进度以地图真实变化为准。"
                        else if(project.ownerId=="player")
                        if(project.phase=="paused") "施工已暂停；恢复后走到下一块旁边，手动施工。" else "走到下一块旁边手动施工，逐块消耗真实木材。"
                        else if(project.phase=="paused") "伙伴施工已暂停，可在队伍聊天里协商继续或调整。" else "伙伴按自己选择的计划逐步施工；可在队伍聊天里协商调整。",
                        color = Color(0xFFB5D5CD), fontSize = 11.sp)
                }
            }
        }
        }
        if(menuSection=="伙伴") {
        if(world.friends.isEmpty()) Text("当前是单人探索，可在游戏馆选择同行角色。",color=Color(0xFFB9DDCC),fontSize=12.sp)
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
        }
        if(menuSection=="图鉴") {
            Text("小岛生态图鉴",color=Color(0xFFF0FCF4),fontSize=17.sp,
                fontWeight=FontWeight.Black)
            Text("遇见动物后会记入发现记录。结识后它们会陪伴你；修建宠物小屋可以让动物归家休息，小屋附近领取礼物的间隔可由24回合缩短至16回合。",
                color=Color(0xFFBEE3D3),fontSize=12.sp,lineHeight=18.sp)
            Text("已发现 ${island.discoveredSpecies.size}/${IslandWildlife.species.size} · 已结识 ${island.animals.count{it.befriended}}/${island.animals.size}",
                color=Color(0xFFFFE0A2),fontWeight=FontWeight.Bold,fontSize=13.sp)
            island.animals.forEach { animal ->
                val spotted=animal.species in island.discoveredSpecies || animal.befriended
                val near=abs(world.x-animal.x)+abs(world.y-animal.y)<=1
                val ready=if(animal.befriended)IslandWildlife.canGift(island,animal)
                    else IslandWildlife.canFeed(island,animal)
                Surface(color=Color(0xFF244B49),shape=RoundedCornerShape(14.dp),
                    border=BorderStroke(1.dp,if(animal.befriended)
                        Color(0xFFEACB93).copy(alpha=.6f) else Color(0xFF92D6B7).copy(alpha=.25f))) {
                    Row(Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(if(spotted) IslandWildlife.name(animal.species) else "？？？ 未发现",
                                color=Color.White,fontWeight=FontWeight.Bold,fontSize=13.sp)
                            Text(if(!spotted) "探索草地，寻找新的小生命"
                                else if(!animal.befriended) "信任 ${animal.trust}/${IslandWildlife.requiredFeeds(animal.species)} · 喜欢 ${IslandWildlife.feed(animal.species)} · 每次喂食后等待4回合"
                                else if(ready) "已经带回小礼物啦"
                                else if(IslandWildlife.hasNearbyHome(island,animal)) "在宠物小屋旁休息 · 礼物更快"
                                else "跟随中 · 还在准备礼物",
                                color=Color(0xFFB6D8D0),fontSize=11.sp)
                        }
                        if(spotted) {
                            IslandAction(if(animal.befriended)"领礼物" else "喂食 ${animal.trust}/${IslandWildlife.requiredFeeds(animal.species)}",
                                enabled=near && ready) {
                                perform(if(animal.befriended)"care" else "tame",animal.x,animal.y)
                            }
                        }
                    }
                }
            }
        }
        if(menuSection=="设置") {
        Text("自动存档已开启 · 建筑和道具不会在退出时消失",color=Color(0xFFD0E8DB),fontSize=12.sp)
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("游戏音效",color=Color.White,modifier=Modifier.weight(1f))
            Switch(checked=soundEnabled,onCheckedChange={
                soundEnabled=it
                preferences.edit().putBoolean("island_sound_enabled",it).apply()
            })
        }
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("操作震动",color=Color.White,modifier=Modifier.weight(1f))
            Switch(checked=hapticsEnabled,onCheckedChange={
                hapticsEnabled=it
                preferences.edit().putBoolean("island_haptics_enabled",it).apply()
            })
        }
        OutlinedButton(onClick={showIslandGuideDialog=true}) { Text("查看玩法",color=Color.White) }
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
        Text("创建新岛屿会清空当前家园，记录旅程不会清空。",
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            fontSize = 10.sp, color = Color(0xFF9AC0BA))
        }
    }

        },
    )

    // Contextual in-world interaction: the general game menu never owns crafting.
    if (workbenchOpen) AlertDialog(
        onDismissRequest = { workbenchOpen = false },
        title = { Text("工作台 · 制作工具") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(if (workbenchNear) "正在使用附近的工作台 · 制作后立即存入本岛工具栏"
                    else "先靠近已放置的工作台（2格内）",
                    color = if (workbenchNear) Color(0xFFB7F3D2) else Color(0xFFFFD4B5),
                    fontSize = 12.sp)
                IslandWorkbenchRecipes.all.forEach { recipe ->
                    IslandAction(
                        if (recipe.tool in island.tools) "✓ " + recipe.description
                        else "制作 " + recipe.description,
                        enabled = IslandWorkbenchRecipes.canCraft(island, recipe),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        perform(recipe.action, world.x, world.y)
                        workbenchOpen = false
                    }
                }
                Text("材料不足时，到树木与矿石旁采集。制作不会创建新背包或改变原存档规则。",
                    color = Color(0xFFB5CAC3), fontSize = 11.sp)
            }
        },
        containerColor=Color(0xFF17363B), titleContentColor=Color.White,
        textContentColor=Color.White,
        confirmButton={ TextButton(onClick={workbenchOpen=false}) { Text("离开工作台") } },
    )
    if (buildCatalogOpen) AlertDialog(
        onDismissRequest = { buildCatalogOpen = false },
        title = { Text("建造 · 选择建筑") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("选择后在地图上定位，绿色可建、红色不能建；每次放置都真实消耗材料。",
                    color=Color(0xFFC7DDD2),fontSize=11.sp)
                IslandBuildingControls.recipes.forEach { recipe ->
                    IslandAction(recipe.name+" · "+recipe.cost,
                        enabled=true, modifier=Modifier.fillMaxWidth()) {
                        buildMode=recipe.action
                        lastBuildMode=recipe.action
                        mapAim=false
                        navigateTo=null
                        buildCatalogOpen=false
                    }
                }
            }
        },
        containerColor=Color(0xFF17363B), titleContentColor=Color.White,
        textContentColor=Color.White,
        confirmButton={ TextButton(onClick={buildCatalogOpen=false}) { Text("取消") } },
    )
    if (goalsOpen) AlertDialog(
        onDismissRequest = { goalsOpen = false },
        title = { Text("小岛冒险 · 任务手册") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("当前任务：" + survivalGoal,
                    color=Color(0xFFFFE3A7),fontWeight=FontWeight.Bold)
                Text("生存篇  ·  工作台 → 石剑 → 篝火/床铺 → 击退怪物 → 守住3夜",
                    color=Color.White,fontSize=12.sp)
                Text("远航篇  ·  晨光海角 → 南岭古树（地图金色航标，点击右下角可自动寻路）",
                    color=Color(0xFFFFD79F),fontSize=12.sp)
                IslandExpeditionTrail.landmarks.forEach { place ->
                    val claimed=place.id in island.awardedChallenges
                    Text((if(claimed)"✓ " else "◇ ")+place.title+" · "+
                        (if(claimed)"已探索" else "距离${IslandExpeditionTrail.distance(island,place)}格")+
                        " · "+place.rewardText,
                        color=if(claimed)Color(0xFFB1E4CF) else Color(0xFFFFE3AE),
                        fontSize=11.sp)
                }
                Text("家园建造  ·  建造→选择暖灯屋或松林屋→点地图找3×3空地→放蓝图→走到提示施工格，每次真实消耗木材建一格，9格后出现完整屋顶。",
                    color=Color(0xFFF5DCAF),fontSize=12.sp)
                Text("食物篇  ·  砍树找野果 / 制鱼竿钓鱼 / 建菜圃→播种→至少4步后松土→成熟收果。春夏秋冬每96回合轮换，冬季生长较慢。",
                    color=Color(0xFFCBE9D3),fontSize=12.sp)
                Text("装备与晶石  ·  用采集键取木石；木斧/石镐增产，蓝色晶矿必须用石镐；晶石造护夜晶灯（夜间3格内减伤1）或工作台护符（挡一次致命伤）。",
                    color=Color(0xFFDCCBF7),fontSize=12.sp)
                Text("探索篇  ·  寻找补给箱 → 收集4种工具 → 架起第一座桥",
                    color=Color.White,fontSize=12.sp)
                Text("终章篇  ·  双地标 → 石剑 → 守住3夜 → 挑战南岭巨岩守卫",
                    color=Color(0xFFFFD5AF),fontSize=12.sp,fontWeight=FontWeight.Bold)
                Text("守卫状态："+IslandGuardianEncounter.remainingRequirements(island),
                    color=Color(0xFFFFE7CF),fontSize=11.sp)
                Text("家园篇  ·  结识动物 → 建宠物小屋 → 点亮夜灯",
                    color=Color.White,fontSize=12.sp)
                Text("实际进度：守夜 " + island.nightsSurvived + "/3 · 补给 " +
                    island.openedSupplies.size + "/" + island.supplyCaches.size +
                    " · 工具 " + island.tools.intersect(setOf("axe","pickaxe","sword","rod")).size + "/4 · 已结识动物 " +
                    island.animals.count { it.befriended } + "/" + island.animals.size,
                    color=Color(0xFFAEE7CF),fontSize=11.sp)
                Text("✦ 挑战奖励 · 达成后自动放进真实存档",
                    color=Color(0xFFFFE3A7),fontWeight=FontWeight.Bold,fontSize=13.sp)
                IslandChallengeBoard.challenges.forEach { challenge ->
                    val complete = challenge.completed(island)
                    val claimed = challenge.id in island.awardedChallenges
                    Surface(color=if(claimed)Color(0xFF27594E) else Color(0xFF245052),
                        shape=RoundedCornerShape(10.dp)) {
                        Column(Modifier.fillMaxWidth().padding(9.dp)) {
                            Text((if(claimed) "✓ " else if(complete) "✦ " else "○ ") + challenge.title,
                                color=Color.White,fontSize=12.sp,fontWeight=FontWeight.Bold)
                            Text(challenge.detail + " · " + challenge.rewardText +
                                if(claimed) " · 已领取" else if(complete) " · 下一有效行动领取" else "",
                                color=Color(0xFFBCE9D7),fontSize=10.sp,lineHeight=15.sp)
                        }
                    }
                }
                Text("奖励只结算一次，不靠每日打卡；完成与否取决于真实游戏存档。",
                    color=Color(0xFFB5CAC3),fontSize=11.sp)
            }
        },
        containerColor=Color(0xFF17363B), titleContentColor=Color.White,
        textContentColor=Color.White,
        confirmButton={ TextButton(onClick={goalsOpen=false}) { Text("继续冒险") } },
    )


    if (showIslandGuideDialog) AlertDialog(
        onDismissRequest = { showIslandGuideDialog = false },
        title = { Text("方块小岛 · 冒险手册") },
        text = {
            val guideMaxHeight=(LocalConfiguration.current.screenHeightDp*.36f)
                .coerceIn(96f,320f).dp
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                Text("生存 · 探索 · 建造自己的家",
                    color=Color(0xFFBCEBCC),fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    listOf("入门","家园","生存").forEach { tab ->
                        FilterChip(selected=islandGuideTab==tab,
                            onClick={islandGuideTab=tab},
                            label={Text(tab,fontSize=11.sp)},
                            modifier=Modifier.weight(1f))
                    }
                }
                Column(Modifier.fillMaxWidth().heightIn(max=guideMaxHeight)
                    .verticalScroll(rememberScrollState()),
                    verticalArrangement=Arrangement.spacedBy(9.dp)) {
                    when(islandGuideTab) {
                        "家园" -> {
                            Text("建造和生产是长期留在自己存档里的成果。",
                                color=Color.White,fontSize=12.sp,fontWeight=FontWeight.Bold)
                            Text("工作台：收集4木2石，点右侧「建造」选择工作台。绿色位置可放置，红色位置不可放置。",
                                fontSize=12.sp)
                            Text("制作：走到工作台2格内，用方向盘中心互动键或右侧「制作」打开配方；工具制作后自动生效。",
                                fontSize=12.sp)
                            Text("盖屋：在建造中选择暖灯木屋或松林木屋，点地图放置3×3蓝图，再到提示的相邻位置施工9步，共用17木。",
                                fontSize=12.sp)
                            Text("食物：鱼竿靠水钓鱼，篝火烤鱼；菜圃播种野果，至少4回合后松土，成熟后收获，冬天更慢。",
                                fontSize=12.sp)
                            Text("动物：靠近兔、鸭或狐狸多次喂食，建立信任后才能成为家园伙伴。",
                                fontSize=12.sp)
                        }
                        "生存" -> {
                            Text("白天收集资源，夜晚准备食物、营火和武器。",
                                color=Color.White,fontSize=12.sp,fontWeight=FontWeight.Bold)
                            Text("木斧：伐木从2木提升到4木；石镐：采石从2石提升到4石，还能采蓝色晶矿。",
                                fontSize=12.sp)
                            Text("晶石：可以制作护夜晶灯，让附近夜间伤害降低；也可做一次性晶石护符挡住致命伤害。",
                                fontSize=12.sp)
                            Text("倒地：回营只恢复6/12生命，随身约半数木石、野果和晶石留在原地；要找到遗失背包亲手取回。",
                                fontSize=12.sp)
                            Text("进度：房屋、装备、作物与探索自动保存。选择「新岛屿」会清空当前家园，开始新世界。",
                                color=Color(0xFFFFE3AE),fontSize=12.sp)
                        }
                        else -> {
                            Text("初到小岛，先完成这四步就能生存下去。",
                                color=Color.White,fontSize=12.sp,fontWeight=FontWeight.Bold)
                            Text("① 左手按住摇杆行走，拖动改方向。走路不会自动挖掉树或矿石。",
                                fontSize=12.sp)
                            Text("② 靠近树或石头，按右侧「采集」。遇到怪物按「攻击」，地图上点选可指定目标。",
                                fontSize=12.sp)
                            Text("③ 收集4木2石，按「建造」放置工作台；走近工作台再按「制作」打造斧头和石镐。",
                                fontSize=12.sp)
                            Text("④ 做篝火、石剑，准备食物，探索补给箱，再试着守过第一个夜晚。",
                                fontSize=12.sp)
                            Text("右侧「行囊 / 更多」可查看地图、资源、伙伴、图鉴和设置；任务条可打开完整阶段目标。",
                                color=Color(0xFFC9F2D9),fontSize=11.sp)
                        }
                    }
                }
            }
        },
        containerColor=Color(0xFF1A3439),
        titleContentColor=Color.White,textContentColor=Color(0xFFBDD5CC),
        confirmButton={TextButton(onClick={showIslandGuideDialog=false}){
            Text("返回小岛")
        }},
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

/** A lightweight read-only overview of the authoritative, saved world.
 * Unlike another world-map store, every marker reflects the actual current game state.
 * The overview lives inside Exploration, not on top of the moving game scene.
 */
@Composable
private fun IslandMiniMap(island: BlockIslandState) {
    val w=island.world
    Surface(shape=RoundedCornerShape(15.dp),color=Color(0xFF1A3B42),
        border=BorderStroke(1.dp,Color(0xFF719C8D).copy(alpha=.45f))) {
        Column(Modifier.fillMaxWidth().padding(11.dp),
            verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("小岛概览",color=Color.White,fontWeight=FontWeight.Bold,
                    fontSize=13.sp,modifier=Modifier.weight(1f))
                Text("当前位置（${w.x}, ${w.y}）",color=Color(0xFFB8D6CA),
                    fontSize=10.sp)
            }
            Canvas(Modifier.fillMaxWidth().height(144.dp)) {
                val unit=minOf(size.width/w.width.toFloat(),
                    size.height/w.height.toFloat())
                val left=(size.width-unit*w.width)*.5f
                val top=(size.height-unit*w.height)*.5f
                val tiles=w.tiles
                for(y in 0 until w.height) for(x in 0 until w.width) {
                    val cell=tiles[w.index(x,y)]
                    val shade=when(cell) {
                        8 -> Color(0xFF347D9F)
                        1 -> Color(0xFF215A40)
                        2 -> Color(0xFF899B98)
                        3 -> Color(0xFF9C7650)
                        4 -> Color(0xFFBB9B6F)
                        7 -> Color(0xFFCFB279)
                        else -> Color(0xFF669465)
                    }
                    val structure=island.structures[w.index(x,y)]
                    val tint=when(structure) {
                        "wall","door","workbench","campfire","bed","lantern","pet_house" -> Color(0xFFE0BB80)
                        "bridge" -> Color(0xFFC69B74)
                        else -> shade
                    }
                    drawRect(tint,topLeft=Offset(left+x*unit,top+y*unit),
                        size=Size(unit+.3f,unit+.3f))
                }
                fun dot(x:Int,y:Int,color:Color,scale:Float=1f) {
                    if(!w.inBounds(x,y))return
                    drawCircle(Color(0xFF142C32).copy(alpha=.70f),
                        radius=(unit*.82f*scale).coerceAtLeast(2.4f),
                        center=Offset(left+(x+.5f)*unit,top+(y+.5f)*unit))
                    drawCircle(color,radius=(unit*.56f*scale).coerceAtLeast(1.8f),
                        center=Offset(left+(x+.5f)*unit,top+(y+.5f)*unit))
                }
                island.supplyCaches.filter { it !in island.openedSupplies }.forEach {
                    val rare=it in island.rareSupplyCaches
                    dot(it%w.width,it/w.width,
                        if(rare)Color(0xFF9DEAF3)else Color(0xFFFFD992),
                        if(rare)1.65f else 1f)
                }
                IslandExpeditionTrail.landmarks.forEach { place ->
                    val (mx,my)=IslandExpeditionTrail.position(island,place)
                    dot(mx,my,
                        if(place.id in island.awardedChallenges)Color(0xFF71DEC6)
                        else Color(0xFFFFE093),1.75f)
                }
                w.mobs.filter { it.hp>0 }.forEach {
                    dot(it.x,it.y,
                        if(it.id==IslandGuardianEncounter.id)Color(0xFFFFCB7A)
                        else Color(0xFFFF918B),
                        if(it.id==IslandGuardianEncounter.id)2.15f else 1f)
                }
                island.animals.forEach { dot(it.x,it.y,
                    if(it.befriended)Color(0xFFFFD693) else Color(0xFFD6F4C2)) }
                w.friends.filter { it.hp>0 }.forEach { dot(it.x,it.y,Color(0xFF8BDAF3)) }
                dot(w.x,w.y,Color(0xFFFFF0B1),1.35f)
            }
            Text("金色大点：远航地标 / 守卫　海蓝光：潮汐秘藏　黄：你／普通补给　蓝：伙伴　红：怪物",
                color=Color(0xFFC5E0D3),fontSize=10.sp,maxLines=2)
        }
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
private fun IslandBuildChip(
    label: String, cost: String, enabled: Boolean,
    selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    Surface(modifier=modifier, color = if(selected) Color(0xFF345F53) else Color(0xFF284B4E),
        border=BorderStroke(if(selected) 2.dp else 1.dp,
            if(selected) Color(0xFFA9E5BD) else Color(0xFF578277)),
        shape=RoundedCornerShape(13.dp)) {
        Column(Modifier.fillMaxWidth().padding(4.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            TextButton(onClick=onClick,enabled=enabled,contentPadding=PaddingValues(0.dp),
                modifier=Modifier.height(33.dp)) {
                Text(if(selected) "✓ "+label else label,
                    color=if(enabled) Color.White else Color.Gray,fontSize=12.sp)
            }
            Text(cost,color=Color(0xFFC1D8D1),fontSize=10.sp)
        }
    }
}

private fun islandCellTitle(tile: Int, structure: String?): String =
    when (structure) {
        "door" -> "木门"; "workbench" -> "工作台"; "campfire" -> "篝火"; "bed" -> "木床"; "bridge" -> "木桥"
        "lantern" -> "夜灯"; "pet_house" -> "宠物小屋"; "garden" -> "小菜圃"
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
    buildPreview: IslandBuildPreview? = null,
    facing: Pair<Int, Int> = 0 to 1,
    hurtProgress: Float = 1f,
) {
    val w = island.world
    val playerVisual = islandActorPosition(w.seed,"player",w.x,w.y)
    val companionVisuals = w.friends.associate { friend ->
        key(friend.id) { friend.id to islandActorPosition(w.seed,friend.id,friend.x,friend.y) }
    }
    val companionLabels=w.friends.associate { friend ->
        friend.id to MigratedDomainStores.characters.get(friend.id).displayName
    }
    // Monsters use the exact same tile-to-tile interpolation as real players.
    // This is render-only; pathfinding, combat and authoritative positions stay in Rules.
    val mobVisuals = w.mobs.filter { it.hp>0 }.associate { mob ->
        key("mob-${mob.id}") {
            mob.id to islandActorPosition(w.seed,"mob-${mob.id}",mob.x,mob.y)
        }
    }
    // Decode the bundled atlas once at the composable/resource boundary;
    // Canvas then samples only the tiles in the camera's visible viewport.
    val terrainAtlas=ImageBitmap.imageResource(R.drawable.game_island_ground_atlas)
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
    val rows = if (fullscreen) (cols * ((config.screenHeightDp).coerceAtLeast(160).toFloat() /
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
                playerVisual = playerVisual, companionVisuals = companionVisuals,
                mobVisuals = mobVisuals, companionLabels = companionLabels,
                playerFacing = facing, camera = camera,
                navigationStep = navigationStep,
                night = night, phase = phase, impact = impact, impactProgress = impactProgress,
                buildPreview = buildPreview, hurtProgress = hurtProgress,
                terrainAtlas = terrainAtlas,
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
