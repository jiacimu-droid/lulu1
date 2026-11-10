package com.jiacimu.lulu.games

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import kotlinx.coroutines.launch
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
import kotlin.math.abs
import kotlin.math.min

private val relicInk=Color(0xFF0A111A)
private val relicGold=Color(0xFFFFD894)
private val relicMuted=Color(0xFFB6C4D4)
private val relicGreen=Color(0xFF81E8C5)
private val relicRed=Color(0xFFFFA2AD)

@Composable
internal fun RelicEscapeScreen(store:LuluGameStore) {
    val context=LocalContext.current
    val ledger=remember(context){RelicEscapeLedger(context)}
    val game by store.state.collectAsState()
    var snapshot by remember{mutableStateOf(ledger.read())}
    val run=snapshot.run
    var kits by remember{mutableIntStateOf(1)}
    var lobbyOpen by rememberSaveable { mutableStateOf(true) }
    var acceptedInvites by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var difficulty by rememberSaveable { mutableStateOf(RelicDifficulty.NORMAL) }
    var startingWeapon by rememberSaveable { mutableStateOf("blade") }
    var chosenContract by rememberSaveable { mutableStateOf(RelicContract.FIRST_HAUL) }
    var invitingId by remember { mutableStateOf<String?>(null) }
    var invitationStatus by remember { mutableStateOf("") }
    val invitationScope=rememberCoroutineScope()
    var selected by remember(run?.id){mutableStateOf((run?.hero?.x?:2) to (run?.hero?.y?:2))}
    var facing by remember(run?.id){mutableStateOf(0 to 1)}
    var joystickHeld by remember(run?.id){mutableStateOf(false)}
    // Movement returns the camera to the player; looking at teammates spends no turn.
    var cameraFocus by remember(run?.id, run?.hero?.x, run?.hero?.y) {
        mutableStateOf<Pair<Int, Int>?>(null)
    }
    var confirmAbandon by remember{mutableStateOf(false)}
    var confirmRestart by remember{mutableStateOf(false)}
    var showRulesDialog by remember{mutableStateOf(false)}
    var showGuide by remember(run?.id){mutableStateOf(true)}
    var showAdvanced by remember(run?.id){mutableStateOf(false)}
    var showBag by remember(run?.id){mutableStateOf(false)}
    var showAlbum by remember{mutableStateOf(false)}
    var chestHud by remember(run?.id){mutableStateOf(RelicChestHudState())}
    val openingReceipt = chestHud.opening
    val lastChestReceipt = chestHud.lastCommitted
    val openingClock = remember(run?.id) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(openingReceipt?.runId, openingReceipt?.chestId) {
        if (openingReceipt == null) { openingClock.snapTo(0f); return@LaunchedEffect }
        val receipt = openingReceipt ?: return@LaunchedEffect
        openingClock.snapTo(0f)
        openingClock.animateTo(1f, androidx.compose.animation.core.tween(1750,
            easing = androidx.compose.animation.core.FastOutSlowInEasing))
        kotlinx.coroutines.delay(1150)
        chestHud = chestHud.dismiss(receipt)
    }
    var error by remember{mutableStateOf("")}

    fun change(block:()->RelicSnapshot): Boolean {
        return runCatching { block() }.fold(
            onSuccess = { snapshot = it; error = ""; true },
            onFailure = { error = it.message ?: "存档失败，请重试"; false },
        )
    }
    fun action(type:String,x:Int=0,y:Int=0,id:String="") {
        val unopened=run?.chests?.firstOrNull{it.x==x && it.y==y && !it.opened}
        val tier=unopened?.tier
        val before=run?.turns
        val openingRun=run
        // A rejected action must not erase the last committed physical loot receipt.
        if (!change{ledger.perform(type,x,y,id,run?.allies?.map{it.id}?.toSet() ?: emptySet())}) return
        // A persisted no-op (for example, an invalid tap) is not a committed world action.
        chestHud=chestHud.afterWorldTurn(before, snapshot.run?.turns)
        if(type=="open" && openingRun!=null && unopened!=null) {
            // Draw no win animation unless the actual saved world has an opened chest
            // and an exact corresponding item in the player's bag or on the ground.
            val receipt=snapshot.run?.let {
                RelicChestPresentation.committed(openingRun,it,unopened.id)
            }
            if(receipt!=null){
                chestHud=chestHud.committed(receipt)
            }
        }
        if(before!=snapshot.run?.turns) {
            val sound=when(type) {
                "open"->when(tier){
                    RelicTier.COMMON->GameSoundEffect.Interact
                    RelicTier.RARE->GameSoundEffect.Collect
                    RelicTier.PRECIOUS->GameSoundEffect.Objective
                    null->GameSoundEffect.Bump
                }
                "attack","extract","invoke","channel","flare"->GameSoundEffect.Objective
                "pickup","heal","revive","disarm"->GameSoundEffect.Collect
                "move","sneak"->GameSoundEffect.Move
                "lure"->GameSoundEffect.Interact
                else->GameSoundEffect.Interact
            }
            GameSoundEffects.play(sound)
        } else if(type!="wait")GameSoundEffects.play(GameSoundEffect.Bump)
    }
    LaunchedEffect(run?.id,run?.phase,snapshot.awarded) {
        if(run!=null && run.id in snapshot.awarded)ledger.syncExperiences(snapshot,store)
    }

    if(lobbyOpen || run==null || run.phase in setOf(
        RelicPhase.SUCCESS,RelicPhase.FAILURE,RelicPhase.SETTLEMENT,RelicPhase.PREPARE)) {
        RelicEscapeLobby(
            snapshot=snapshot, accepted=acceptedInvites, inviting=invitingId,
            inviteStatus=invitationStatus,error=error,kits=kits,difficulty=difficulty,
            startingWeapon=startingWeapon,contract=chosenContract,
            onKits={kits=it},onDifficulty={difficulty=it},onStartingWeapon={startingWeapon=it},
            onContract={chosenContract=it},
            onInvite={ id ->
                if(invitingId==null && id !in acceptedInvites && acceptedInvites.size<3) {
                    invitingId=id
                    invitationStatus=""
                    val party=acceptedInvites.toList()
                    invitationScope.launch {
                        val result=runCatching { RelicLobbyInvitation.ask(id,party) }
                            .getOrElse { Result.failure(it) }
                        if(invitingId==id) {
                            invitingId=null
                            val choice=result.getOrNull()
                            val name=MigratedDomainStores.characters.get(id).displayName
                            invitationStatus=when {
                                choice==null -> name+"的邀请尚未完成："+(result.exceptionOrNull()?.message ?: "请重试")
                                choice.accepted -> {
                                    acceptedInvites=(acceptedInvites+id).distinct().take(3)
                                    name+"接受了邀请"+if(choice.speech.isBlank())"" else " · "+choice.speech
                                }
                                else -> name+"这次没有加入"+if(choice.speech.isBlank())"" else " · "+choice.speech
                            }
                        }
                    }
                }
            },
            onRemove={id->acceptedInvites=acceptedInvites.filterNot{it==id}},
            onStart={
                store.selectCharacters(acceptedInvites)
                if(change { ledger.begin(acceptedInvites,kits.coerceIn(0,snapshot.kits),difficulty,startingWeapon,chosenContract) }) {
                    if(snapshot.run?.phase in setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT)) lobbyOpen=false
                }
            },
            onContinue={lobbyOpen=false},
            onAbandon={
                if(change { ledger.perform("abandon") }) ledger.syncExperiences(snapshot,store)
                lobbyOpen=true
            },
            onBuyKit={change{ledger.buyKit()}},
            onUpgradeWeapon={change{ledger.upgradeWeapon()}},
            onBuyBow={change{ledger.buyBow()}},
            onUpgradeSatchel={change{ledger.upgradeSatchel()}},
            onSellLoot={id->change{ledger.sellLoot(id)}},
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
    GameSceneDeck(
        accent = relicGold,
        healthFraction = run?.hero?.hp?.div(10f),
        secondaryFraction = run?.let { state ->
            state.chests.count { it.opened }.toFloat() /
                state.chests.size.coerceAtLeast(1)
        },
        secondaryLabel = "开箱",
        toolsLabel = "背包 / 战术",
        notice = error.ifBlank { if(run?.let { current ->
            current.mobs.any { mob ->
                mob.hp>0 && mob.windup && current.visible(mob.x,mob.y) &&
                    RelicEscapeRules.distance(
                        current.hero.x,current.hero.y,mob.x,mob.y)<=3
            }
        }==true) "⚠ 遗迹敌人正在蓄力 · 格挡或贴近打断！"
            else if(run?.phase == RelicPhase.SUCCESS)
            "撤离成功 · 带回${run.secured.size}件宝物" else if(run?.phase == RelicPhase.FAILURE)
            "本次探索结束 · 战利品遗失" else run?.notice.orEmpty() },
        status = if (run == null) "暗影遗迹 · 营地" else
            "❤ ${run.hero.hp}/10   ◇ ${run.value}   第${run.turns}回合\n" +
                if (run.allies.isEmpty()) "本局单人探索" else "同行 ${run.allies.size} 人 · 金圈是你",
        scene = {
            Box(Modifier.fillMaxSize()) {
                if (run != null && run.phase in setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT,RelicPhase.DOWNED))
                    RelicEscapeWorldMap(run,selected,{ x,y -> selected=x to y },fullscreen=true,
                        focus = cameraFocus ?: (run.hero.x to run.hero.y),
                         openingReceipt = openingReceipt, openingProgress = openingClock.value)
                else RelicCampBackdrop()
                openingReceipt?.let { receipt ->
                    RelicChestCeremony(receipt, openingClock.value) {
                        chestHud=chestHud.dismiss(receipt)
                    }
                }
            }
        },
        companionAvailable = run != null && run.allies.isNotEmpty() &&
            run.phase in setOf(RelicPhase.EXPLORE, RelicPhase.EXTRACT, RelicPhase.DOWNED),
        companion = {
            if (run != null && run.allies.isNotEmpty() && run.phase in
                setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT,RelicPhase.DOWNED)) {
                RelicCompanionPanel(run,continuousTravel=joystickHeld,
                    readRun={snapshot.run ?: run}) { id,choice ->
                    var saved = false
                    change { ledger.companion(run.id,id,choice).also { saved = true } }
                    saved
                }
            }
        },
        movement = {
            val active = run != null && run.phase in setOf(RelicPhase.EXPLORE, RelicPhase.EXTRACT)
            val adjacentAction = run?.let { relicNearbyAction(it, facing) }
            GameDirectionalPad(accent = relicGold, enabled = active, holdRepeatMs = 340L,
                stopWhen = run?.hero?.hp to run?.phase,
                interactLabel = adjacentAction?.label ?: "附近互动",
                interactEnabled = adjacentAction != null,
                onHoldChange = { joystickHeld = it },
                onInteract = { adjacentAction?.let { action(it.action, it.x, it.y, it.id) } }) { dx, dy ->
                facing = dx to dy
                run?.hero?.let { hero ->
                    val x = hero.x + dx
                    val y = hero.y + dy
                    val nearby = run?.let { relicQuickAction(it, x to y) }
                    // Stepping toward usable objects interacts; fighting requires attack.
                    if (nearby?.action in setOf("open", "pickup", "resupply",
                            "fountain", "disarm", "invoke", "revive", "extract")) {
                        action(nearby!!.action, nearby.x, nearby.y, nearby.id)
                    } else {
                        action("move", x, y)
                    }
                }
            }
        },
        quickActions = {
            if (run != null && run.phase in setOf(RelicPhase.EXPLORE, RelicPhase.EXTRACT, RelicPhase.DOWNED)) {
                val hero = run.hero
                lastChestReceipt?.let { receipt ->
                    val loot = receipt.loot
                    Surface(color=if(RelicCollectibles.isCrownJewel(loot.name))
                        Color(0xFF60441F) else Color(0xFF24374B),
                        shape=RoundedCornerShape(14.dp),
                        border=BorderStroke(1.dp,if(RelicCollectibles.isCrownJewel(loot.name))
                            Color(0xFFFFE3A0) else relicGold.copy(alpha=.45f))) {
                        Text((if(RelicCollectibles.isCrownJewel(loot.name)) "✦ 隐世奇珍！ " else "✧ 宝箱出货 · ")+
                            loot.tier.label+"「"+loot.name+"」 · 估值"+loot.value+
                            (if(receipt.inBag) " · 已入包，撤离后收录"
                            else " · 背包已满，拾起并撤离后收录"),
                            modifier=Modifier.fillMaxWidth().padding(10.dp),
                            color=Color.White,fontSize=12.sp,fontWeight=FontWeight.Bold)
                    }
                }
                Text("已探明 ${run.seen.size * 100 / (run.w * run.h)}% · 宝箱 ${run.chests.count{it.opened}}/${run.chests.size} · 动作会推进回合",
                    color = Color.White, fontSize = 10.sp, maxLines = 1)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { cameraFocus = null; selected = hero.x to hero.y }) {
                        Text("定位你", color = relicGold, fontSize = 11.sp)
                    }
                    TextButton(onClick = { showRulesDialog = true }) {
                        Text("玩法", color = relicGreen, fontSize = 11.sp)
                    }
                    TextButton(onClick = { lobbyOpen = true }) {
                        Text("回营地", color = relicGold, fontSize = 11.sp)
                    }
                    TextButton(onClick = { action("survey") },
                        enabled = RelicEscapeRules.canSurvey(run)) {
                        Text("侦察", color = relicGreen, fontSize = 11.sp)
                    }
                    run.allies.forEach { ally ->
                        val name = MigratedDomainStores.characters.get(ally.id).displayName
                        TextButton(onClick = {
                            cameraFocus = ally.x to ally.y
                            selected = ally.x to ally.y
                        }) { Text("找$name", color = relicGreen, fontSize = 11.sp) }
                    }
                }
                run.coopPlans.forEach { (id,plan) ->
                    val name=MigratedDomainStores.characters.get(id).displayName
                    val work=when(plan.kind) {
                        "support" -> "正在赶去支援"
                        "chest" -> "正在寻找宝箱"
                        "deliver" -> {
                            val item=run.allies.firstOrNull { it.id==id }?.bag
                                ?.firstOrNull { it.id==plan.itemId }
                            if(item==null)"正在核对送达物品"
                            else "正带着「"+item.name+"」来送给你"
                        }
                        else -> "正在与你集合"
                    }
                    Text(name+" · "+work,color=relicGreen,fontSize=11.sp,maxLines=1)
                }
                if(run.guardedBy.isNotBlank()) {
                    run.allies.firstOrNull { it.id==run.guardedBy && it.hp>0 }?.let { teammate ->
                        Text("♡ "+MigratedDomainStores.characters.get(teammate.id).displayName+
                            " 正在保护你",color=relicGreen,fontSize=11.sp,maxLines=1)
                    }
                }
                val quickHint = relicQuickAction(run, selected)?.hint
                val nearbyLoot = run.chests.firstOrNull { !it.opened &&
                    RelicEscapeRules.distance(hero.x, hero.y, it.x, it.y) <= 1 &&
                    run.visible(it.x, it.y) }
                val objective = when (run.phase) {
                    RelicPhase.EXTRACT -> "撤离中：守住剩余${(3-run.extraction).coerceAtLeast(0)}回合"
                    else -> if (run.carried.isEmpty()) "目标：找到一件宝物，再去绿色撤离门"
                        else "已带${run.carried.size}件宝物（估值${run.value}）→ 去绿色撤离门"
                }
                val chestTarget=RelicObjectiveGuide.visibleChest(run)
                val exitTarget=RelicObjectiveGuide.visibleExit(run)
                Surface(
                    color=Color(0xE31B3340),shape=RoundedCornerShape(12.dp),
                    border=BorderStroke(1.dp,relicGold.copy(alpha=.48f)),
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=8.dp),
                        verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text(objective,color=relicGold,fontWeight=FontWeight.Bold,
                            fontSize=11.sp,maxLines=2)
                        if(run.contract!=RelicContract.NONE) {
                            val progress=RelicContractBoard.bagProgress(run)
                            Text("✦ "+run.contract.label+" · "+
                                (progress?.first?:0)+"/"+(progress?.second?:1)+
                                " · 成功撤离额外 ◇"+run.contract.bonus,
                                color=relicGreen,fontSize=10.sp,
                                fontWeight=FontWeight.Bold,maxLines=2)
                        }
                        // Present both real options simultaneously. A player with
                        // loot may keep searching; the system must not force
                        // extraction just because the bag has one item.
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                            OutlinedButton(
                                onClick={
                                    chestTarget?.let {
                                        cameraFocus=it.x to it.y
                                        selected=it.x to it.y
                                    }
                                },
                                enabled=chestTarget!=null,
                                modifier=Modifier.weight(1f),
                                contentPadding=PaddingValues(horizontal=6.dp,vertical=4.dp),
                                border=BorderStroke(1.dp,relicGold.copy(alpha=.55f)),
                            ) { Text("◇ 再搜一箱",fontSize=10.sp,maxLines=1,color=relicGold) }
                            OutlinedButton(
                                onClick={
                                    exitTarget?.let {
                                        cameraFocus=it.x to it.y
                                        selected=it.x to it.y
                                    }
                                },
                                enabled=exitTarget!=null,
                                modifier=Modifier.weight(1f),
                                contentPadding=PaddingValues(horizontal=6.dp,vertical=4.dp),
                                border=BorderStroke(1.dp,relicGreen.copy(alpha=.55f)),
                            ) { Text("↗ 去撤离门",fontSize=10.sp,maxLines=1,color=relicGreen) }
                        }
                        Text(when {
                            chestTarget!=null && exitTarget!=null ->
                                "宝箱："+RelicObjectiveGuide.optionLabel(run,chestTarget)+
                                    " · 撤离："+RelicObjectiveGuide.optionLabel(run,exitTarget)
                            chestTarget!=null ->
                                "宝箱："+RelicObjectiveGuide.optionLabel(run,chestTarget)+
                                    " · 撤离门尚未发现"
                            exitTarget!=null ->
                                "撤离："+RelicObjectiveGuide.optionLabel(run,exitTarget)+
                                    " · 尚无已知未开宝箱"
                            else -> RelicObjectiveGuide.prompt(run)
                        },color=relicMuted,fontSize=10.sp,maxLines=2)
                    }
                }
                val attackTarget = relicAttackTarget(run, selected)
                val visibleThreat = run.mobs.filter { it.hp > 0 && run.visible(it.x, it.y) &&
                    RelicEscapeRules.distance(hero.x, hero.y, it.x, it.y) <= 3 }
                    .minByOrNull { RelicEscapeRules.distance(hero.x, hero.y, it.x, it.y) }
                if (visibleThreat != null) {
                    val name = RelicArsenal.monsterName(visibleThreat.kind)
                    Text(if (visibleThreat.windup)
                        "⚠ $name 正在蓄力！及时格挡，或用短剑贴身打断"
                        else "$name · 生命${visibleThreat.hp} · 预计造成${RelicArsenal.damage(run, visibleThreat)}伤害",
                        color = if (visibleThreat.windup) relicRed else relicGreen,
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                }
                Text(quickHint ?: "探索迷雾找宝箱 → 绿门撤离",
                    color = relicGold, fontSize = 10.sp, maxLines = 2)
                if (attackTarget == null) Text(relicAttackHint(run, selected),
                    color = relicMuted, fontSize = 10.sp, maxLines = 2)
            }
        },
        primaryActions = {
            if (run != null && run.phase in setOf(
                RelicPhase.EXPLORE, RelicPhase.EXTRACT, RelicPhase.DOWNED)) {
                val hero = run.hero
                val attackTarget = relicAttackTarget(run, selected)
                val nearbyLoot = run.chests.firstOrNull { !it.opened &&
                    RelicEscapeRules.distance(hero.x, hero.y, it.x, it.y) <= 1 &&
                    run.visible(it.x, it.y) }
                val sx = selected.first
                val sy = selected.second
                val visible = run.visible(sx, sy)
                val adjacent = RelicEscapeRules.distance(hero.x, hero.y, sx, sy) <= 1
                val chest = run.chests.any { !it.opened && it.x == sx && it.y == sy }
                val chestAtHand = if (chest && visible && adjacent) sx to sy
                    else nearbyLoot?.let { it.x to it.y }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    GameHudQuickAction(if (attackTarget != null && selected != (attackTarget.x to attackTarget.y))
                            "攻击近敌" else RelicArsenal.label(run.equipped)+"攻击", relicGold,
                        attackTarget != null, Modifier.weight(1f)) {
                        attackTarget?.let { action("attack", it.x, it.y) }
                    }
                    GameHudQuickAction("开箱", relicGold, run.phase != RelicPhase.DOWNED && chestAtHand != null,
                        Modifier.weight(1f)) { chestAtHand?.let { action("open", it.first, it.second) } }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    GameHudQuickAction("格挡", relicGreen, run.phase != RelicPhase.DOWNED,
                        Modifier.weight(1f)) { action("defend") }
                    GameHudQuickAction("治疗", relicGreen, run.phase != RelicPhase.DOWNED &&
                        hero.kits > 0 && hero.hp < 10,
                        Modifier.weight(1f)) { action("heal") }
                }
            }
        },
        actions = {
            val quick = run?.let { relicQuickAction(it,selected) }
            Button(onClick = {
                if (run == null || run.phase in setOf(RelicPhase.SUCCESS,RelicPhase.FAILURE,RelicPhase.SETTLEMENT)) {
                    change { ledger.begin(if(game.playWithCharacter)game.selectedCharacterIds else emptyList(),kits.coerceAtMost(snapshot.kits)) }
                } else quick?.let { action(it.action,it.x,it.y,it.id) }
            },enabled = run == null || run.phase in setOf(RelicPhase.SUCCESS,RelicPhase.FAILURE,RelicPhase.SETTLEMENT) || quick != null,
                modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor=relicGold,contentColor=relicInk)) {
                Text(if(run == null) "进入遗迹" else if(run.phase in setOf(RelicPhase.SUCCESS,RelicPhase.FAILURE,RelicPhase.SETTLEMENT))
                    "再次出发" else quick?.label ?: "点选地图目标",fontSize=12.sp,maxLines=1)
            }
            if (run != null && run.phase in setOf(RelicPhase.EXPLORE, RelicPhase.EXTRACT, RelicPhase.DOWNED)) {
                TextButton(onClick = { confirmRestart = true }) {
                    Text("↻ 重开", color = relicGold, fontSize = 11.sp)
                }
            }
        },
        tools = {
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
        Color(0xFF241D29),Color(0xFF101A25),relicInk)))
        .verticalScroll(rememberScrollState())
        .padding(12.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("迷雾摸金",fontSize=24.sp,fontWeight=FontWeight.Black,color=Color.White)
                Text("暗影遗迹 · 随机宝藏 · 战术撤离",fontSize=11.sp,color=relicMuted)
            }
            Surface(color=relicGold.copy(alpha=.12f),shape=RoundedCornerShape(14.dp),
                border=BorderStroke(1.dp,relicGold.copy(alpha=.38f))) {
                Text("◇  仓库 "+snapshot.vault.size+" 件",color=relicGold,
                    fontWeight=FontWeight.Bold,fontSize=12.sp,
                    modifier=Modifier.padding(horizontal=12.dp,vertical=9.dp))
            }
        }
        if(error.isNotBlank())RelicPanel {Text(error,color=relicRed)}
        if(run==null || run.phase in listOf(RelicPhase.SUCCESS,RelicPhase.FAILURE,RelicPhase.SETTLEMENT)) {
            if(run!=null)RelicSettlement(run,snapshot.newDiscoveries)
            RelicPanel {
                Text("出发准备",color=Color.White,fontWeight=FontWeight.Black,fontSize=19.sp)
                Text("出发自带2枚照明弹、1枚声响诱饵和2套机关工具。祭坛有更贵重的藏品，也会唤醒守卫。成功撤离才将宝物入库；倒地失败只丢失本局战利品。",
                    color=relicMuted,fontSize=12.sp)
                Text("本层目标：摸宝撤离。重复珍宝可换铜币，准备更多药剂、升级武器或扩大永久探险背包。",
                    color=relicGold,fontSize=12.sp)
                Text("本次同行："+(if(game.playWithCharacter)game.selectedCharacterIds.distinct().take(3).size else 0)+
                    "位伙伴（每人自带1份救援药剂）",color=relicGreen,fontSize=12.sp)
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("药剂 "+kits.coerceAtMost(snapshot.kits)+" / 库存"+snapshot.kits,color=Color.White,modifier=Modifier.weight(1f))
                    OutlinedButton(onClick={kits=(kits-1).coerceAtLeast(0)}){Text("−")}
                    OutlinedButton(onClick={kits=(kits+1).coerceAtMost(min(3,snapshot.kits))}){Text("+")}
                }
                Text("武器："+if(snapshot.weapon==2)"锋刃长枪（伤害4）" else "旧式短剑（伤害2）",
                    color=relicGold,fontSize=12.sp)
                Text("探险背包："+(8+snapshot.satchelLevel*2)+"格 · 升级后每局都能多带出战利品",
                    color=relicGreen,fontSize=12.sp)
                Button(onClick={
                    change{ledger.begin(if(game.playWithCharacter)game.selectedCharacterIds else emptyList(),kits.coerceAtMost(snapshot.kits))}
                },modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=relicGold,
                    contentColor=relicInk)) {Text("进入遗迹 · 第"+(snapshot.successes+1).coerceAtMost(5)+"层")}
            }
            RelicPanel {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("补给与永久仓库",color=Color.White,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                    Text("铜币 "+snapshot.coins,color=relicGold)
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={change{ledger.buyKit()}},enabled=snapshot.coins>=70){
                        Text("购入药剂 · 70")
                    }
                    OutlinedButton(onClick={change{ledger.upgradeWeapon()}},
                        enabled=snapshot.weapon==1 && snapshot.coins>=350) {
                        Text("升级武器 · 350")
                    }
                }
                RelicCampUpgrades.satchelCost(snapshot.satchelLevel)?.let { price ->
                    val slots=8+(snapshot.satchelLevel+1)*2
                    OutlinedButton(onClick={change{ledger.upgradeSatchel()}},
                        enabled=snapshot.coins>=price,
                        modifier=Modifier.fillMaxWidth()) {
                        Text("扩容背包至${slots}格 · ${price}铜币",
                            color=if(snapshot.coins>=price)relicGold else relicMuted)
                    }
                    Text("背包升级属于永久装备，但只对下一次进入遗迹的探险生效。",
                        color=relicMuted,fontSize=11.sp)
                }
                if(snapshot.vault.isEmpty())Text("尚未有成功带回的实物",color=relicMuted)
                snapshot.vault.takeLast(30).reversed().forEach{item->
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(item.tier.label+" · "+item.name+" · "+item.value,
                            color=when(item.tier){RelicTier.PRECIOUS->relicGold;RelicTier.RARE->relicGreen;else->Color.White},
                            fontSize=12.sp,modifier=Modifier.weight(1f))
                        TextButton(onClick={change{ledger.sellLoot(item.id)}}){Text("兑换铜币")}
                    }
                }
            }
            RelicPanel {
                val all=RelicCollectibles.names
                val found=all.count{it in snapshot.discovered}
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("✦ 永久珍宝图鉴",color=Color.White,fontWeight=FontWeight.Bold,
                        modifier=Modifier.weight(1f))
                    Text("$found / ${all.size}",color=relicGold,fontWeight=FontWeight.Bold)
                }
                LinearProgressIndicator(
                    progress={found.toFloat()/all.size.coerceAtLeast(1)},
                    modifier=Modifier.fillMaxWidth(),color=relicGold,
                    trackColor=Color(0xFF45404B))
                Text("只有成功撤离才算收录；卖掉实物也保留图鉴记录。同名宝物重复出货可卖掉换补给。",
                    color=relicMuted,fontSize=11.sp,lineHeight=16.sp)
                val featured=snapshot.discovered.sortedWith(
                    compareByDescending<String> { name ->
                        when {
                            RelicCollectibles.isCrownJewel(name)->4
                            name=="封印之心"->3
                            name in RelicCollectibles.namesFor(RelicTier.PRECIOUS)->3
                            name in RelicCollectibles.namesFor(RelicTier.RARE)->2
                            else->1
                        }
                    }.thenBy { it }
                ).take(4)
                if(featured.isNotEmpty()) {
                    Text("✧ 我的珍宝陈列",color=relicGold,fontSize=13.sp,
                        fontWeight=FontWeight.Bold)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                        featured.forEach { RelicMuseumPiece(it) }
                    }
                    Text("陈列是永久的发现纪念，不等于仍持有仓库中的实物。",
                        color=relicMuted,fontSize=10.sp)
                }
                TextButton(onClick={showAlbum=!showAlbum}) {
                    Text(if(showAlbum)"收起完整图鉴" else "查看未收集藏品",color=relicGreen)
                }
                if(showAlbum) {
                    RelicTier.values().forEach{tier->
                        val entries=RelicCollectibles.namesFor(tier)+
                            if(tier==RelicTier.PRECIOUS)
                                listOf("封印之心",RelicCollectibles.CROWN_JEWEL) else emptyList()
                        Text(tier.label+" · "+entries.count{it in snapshot.discovered}+"/"+entries.size,
                            color=if(tier==RelicTier.PRECIOUS)relicGold else relicGreen,
                            fontWeight=FontWeight.Bold,fontSize=12.sp)
                        Text(entries.joinToString("  ·  "){name->
                            if(name in snapshot.discovered)name else "？？？"
                        },color=relicMuted,fontSize=11.sp,lineHeight=19.sp)
                    }
                }
            }
        } else {
            val hero=run.hero
            RelicPanel {
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    RelicValue("生命",hero.hp.toString()+"/10",relicRed)
                    RelicValue("负重",hero.load.toString()+"/"+RelicEscapeRules.heroCapacity(run),relicGold)
                    RelicValue("药剂",hero.kits.toString(),relicGreen)
                    RelicValue("估值",run.value.toString(),relicGold)
                    RelicValue("回合",run.turns.toString(),Color.White)
                }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(when(run.phase){
                        RelicPhase.EXTRACT->"撤离倒数 "+(3-run.extraction).coerceAtLeast(0)+" 回合"
                        RelicPhase.DOWNED->"倒地，剩余救援机会 "+(4-run.downTurns).coerceAtLeast(0)
                        else->"出口方向："+relicDirection(run.exitX-hero.x,run.exitY-hero.y)
                    },color=if(run.phase==RelicPhase.EXTRACT)relicGold else relicGreen,
                        fontWeight=FontWeight.Bold,fontSize=13.sp,modifier=Modifier.weight(1f))
                    Text("层级 "+run.level,color=relicMuted,fontSize=12.sp)
                }
                Text("已探明 ${run.seen.size * 100 / (run.w * run.h)}%   ·   宝箱 ${run.chests.count { it.opened }}/${run.chests.size}",
                    color = relicGreen, fontSize = 11.sp)
                LinearProgressIndicator(progress={run.seen.size.toFloat()/(run.w*run.h)},
                    modifier=Modifier.fillMaxWidth(), color=relicGreen,
                    trackColor=Color(0xFF3D4654))
                if(run.phase==RelicPhase.EXTRACT)
                    LinearProgressIndicator(progress={run.extraction/3f},
                        modifier=Modifier.fillMaxWidth(),color=relicGold)
                HorizontalDivider(color=Color.White.copy(alpha=.10f))
                Text("✧ 照明弹 "+run.flares+"   ◈ 诱饵 "+run.lures+
                    "   ⚒ 拆解工具 "+run.tools+"     ·     "+run.sites.count{!it.spent && it.kind=="altar"}+" 处未知秘藏",
                    color=relicMuted,fontSize=11.sp)
            }
            Surface(color = Color(0xFF192738), shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, relicGold.copy(alpha = .21f))) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("本局目标 · 搜宝 → 生还 → 带出 → 升级", modifier = Modifier.weight(1f),
                            color = relicGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { showGuide = !showGuide }) {
                            Text(if (showGuide) "收起指引" else "怎么玩", color = relicGold, fontSize = 11.sp)
                        }
                    }
                    if (showGuide) {
                        Text("① 方向键探索  ② 靠近宝箱按方向或✦  ③ 带宝物回出口",
                            color = Color.White, fontSize = 12.sp, lineHeight = 19.sp)
                        Text("怪物用攻击键打，格挡可以防御蓄力攻击。靠近稀有箱可选择稳妥开箱或危险破封。宝物必须成功撤离才会进入永久图鉴。",
                            color = relicMuted, fontSize = 11.sp, lineHeight = 17.sp)
                    }
                }
            }
            RelicPanel {
                val sx=selected.first;val sy=selected.second
                val visible=run.visible(sx,sy)
                val chest=run.chests.firstOrNull{it.x==sx && it.y==sy && !it.opened}
                val mob=run.mobs.firstOrNull{it.x==sx && it.y==sy && it.hp>0}
                val site=run.sites.firstOrNull{it.x==sx && it.y==sy && visible && !it.spent}
                Text("目标 "+sx+","+sy+" · "+if(!visible)"迷雾未探明" else when {
                    chest!=null->chest.tier.label+"宝箱"
                    site?.kind=="altar"->"古老祭坛 · 唤醒后会有守卫"
                    site?.kind=="trap"->"古代陷阱 · 可提前解除"
                    site?.kind=="supply"->"战地补给站 · 药剂与弩箭"
                    site?.kind=="fountain"->"恢复灵泉"
                    mob!=null->RelicArsenal.monsterName(mob.kind)+" · 生命"+mob.hp
                    sx==run.exitX && sy==run.exitY->"撤离信标"
                    run.tile(sx,sy)==1->"石墙"
                    run.tile(sx,sy)==2->"腐蚀地面"
                    else->"遗迹通路"
                },fontSize=12.sp,color=relicMuted)
                Text(run.notice,color=Color.White,fontSize=12.sp)
                val quick = relicQuickAction(run, selected)
                Button(
                    onClick = { quick?.let { action(it.action, it.x, it.y, it.id) } },
                    enabled = quick != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = relicGold, contentColor = relicInk,
                        disabledContainerColor = Color(0xFF465262),
                        disabledContentColor = Color(0xFFB4BECA),
                    ),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(quick?.label ?: when {
                        !visible -> "迷雾未探明 · 先探索附近"
                        selected == (hero.x to hero.y) -> "轻点地图选择宝箱、怪物或出口"
                        else -> "目标暂不可行动 · 换个目标"
                    }, fontSize = 13.sp, fontWeight = FontWeight.Black)
                }
                if (quick != null) {
                    Text(quick.hint, color = relicMuted, fontSize = 11.sp, lineHeight = 16.sp)
                }
                if(chest?.tier==RelicTier.RARE && visible) {
                    Surface(color=Color(0xFF33273A),shape=RoundedCornerShape(15.dp),
                        border=BorderStroke(1.dp,Color(0xFFD7AEED).copy(alpha=.47f))) {
                        Column(Modifier.fillMaxWidth().padding(10.dp),
                            verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text("✦ 稀有箱 · 要不要赌一把？",color=Color(0xFFF6D7FE),
                                fontWeight=FontWeight.Bold,fontSize=13.sp)
                            Text("稳妥：直接开箱拿稀有宝物。冒险：消耗1套拆解工具，必升珍贵箱，但会惊醒额外守卫。开了封也可以放弃，是否撤离由你决定。",
                                color=relicMuted,fontSize=11.sp,lineHeight=16.sp)
                            OutlinedButton(onClick={action("channel",sx,sy)},
                                modifier=Modifier.fillMaxWidth(),
                                enabled=run.tools>0 &&
                                    RelicEscapeRules.distance(hero.x,hero.y,sx,sy)<=1) {
                                Text(if(run.tools<=0)"缺少拆解工具 · 不能破封"
                                    else if(RelicEscapeRules.distance(hero.x,hero.y,sx,sy)>1)
                                        "先靠近宝箱再破封"
                                    else "冒险破封 · 升级珍贵箱 / 唤醒守卫",
                                    color=Color(0xFFF6D7FE),fontSize=12.sp)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("高级操作", color = relicGold, fontWeight = FontWeight.Bold,
                        fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showAdvanced = !showAdvanced }) {
                        Text(if (showAdvanced) "收起操作" else "战术 / 道具",
                            color = relicGold, fontSize = 12.sp)
                    }
                }
                if (showAdvanced) {
                if(run.phase!=RelicPhase.DOWNED) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                                RelicCommand("向目标一步",Modifier.weight(1f),enabled=visible) {
                                    val step=RelicEscapeRules.path(run,hero.x,hero.y,sx,sy)
                                    if(step!=null)action("move",step.first,step.second)
                                }
                                RelicCommand("潜行一步",Modifier.weight(1f),enabled=visible) {
                                    val step=RelicEscapeRules.path(run,hero.x,hero.y,sx,sy)
                                    if(step!=null)action("sneak",step.first,step.second)
                                }
                            }
                            Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                                RelicCommand("开箱",Modifier.weight(1f),
                                    enabled=chest!=null && visible &&
                                        RelicEscapeRules.distance(hero.x,hero.y,sx,sy)<=1) {
                                    action("open",sx,sy)
                                }
                                RelicCommand("攻击",Modifier.weight(1f),
                                    enabled=mob!=null && visible && RelicArsenal.canHit(run,sx,sy)) {
                                    action("attack",sx,sy)
                                }
                            }
                            Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                                RelicCommand("治疗",Modifier.weight(1f),
                                    enabled=hero.kits>0 && hero.hp<10){action("heal")}
                                RelicCommand("撤离",Modifier.weight(1f),
                                    enabled=RelicEscapeRules.distance(hero.x,hero.y,run.exitX,run.exitY)<=1){
                                    action("extract")
                                }
                            }
                    }
                }
                Text("切换武器（无回合消耗）",color=relicGold,fontWeight=FontWeight.Bold,fontSize=13.sp)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    for(weaponId in listOf("blade","spear","bow")) {
                        RelicCommand((if(weaponId==run.equipped)"✓ " else "")+
                            RelicArsenal.label(weaponId)+
                            (if(weaponId=="bow")" · "+run.bolts+"箭" else ""),
                            enabled=weaponId in run.weapons && weaponId!=run.equipped &&
                                run.phase!=RelicPhase.DOWNED) {
                            action("equip",id=weaponId)
                        }
                    }
                }
                Text("遗迹战术",color=relicGold,fontWeight=FontWeight.Bold,fontSize=13.sp)
                RelicCommand("侦察迷雾 · 消耗1回合", Modifier.fillMaxWidth(),
                    enabled=RelicEscapeRules.canSurvey(run)) { action("survey") }
                Text("探索附近两圈迷雾，无需消耗道具，但声响会吸引守卫。",
                    color=relicMuted,fontSize=11.sp)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    RelicCommand("照明弹 · "+run.flares,Modifier.weight(1f),enabled=run.flares>0) {
                        action("flare")
                    }
                    RelicCommand("诱饵 · "+run.lures,Modifier.weight(1f),
                        enabled=run.lures>0 && visible && run.walk(sx,sy) &&
                            RelicEscapeRules.distance(hero.x,hero.y,sx,sy)<=4) {
                        action("lure",sx,sy)
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    RelicCommand("唤醒祭坛",Modifier.weight(1f),
                        enabled=site?.kind=="altar" &&
                            RelicEscapeRules.distance(hero.x,hero.y,sx,sy)<=1) {
                        action("invoke",sx,sy)
                    }
                    RelicCommand("拆解机关 · "+run.tools,Modifier.weight(1f),
                        enabled=site?.kind=="trap" && run.tools>0 &&
                            RelicEscapeRules.distance(hero.x,hero.y,sx,sy)<=1) {
                        action("disarm",sx,sy)
                    }
                }
                }
                val latestTreasure=run.events.lastOrNull{it.action in setOf("invoke","open","resupply","fountain")}
                if(latestTreasure!=null && run.turns-latestTreasure.turn<=1) {
                    Surface(shape=RoundedCornerShape(12.dp),
                        color=Color(0xFF604B39),border=BorderStroke(1.dp,relicGold.copy(alpha=.5f))) {
                        Text("✦ "+latestTreasure.text,color=relicGold,fontSize=12.sp,
                            fontWeight=FontWeight.Bold,modifier=Modifier.padding(11.dp))
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={action("wait")}) {
                        Text(if(run.phase==RelicPhase.DOWNED)"等待救援" else "等待 / 守住出口")
                    }
                    OutlinedButton(onClick={confirmAbandon=true}){Text("放弃本局")}
                }
            }
            RelicPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("战利品 · ${hero.bag.size} 件 · ${hero.load}/${RelicEscapeRules.heroCapacity(run)} 格",
                        modifier = Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { showBag = !showBag }) {
                        Text(if (showBag) "收起背包" else "打开背包", color = relicGold)
                    }
                }
                Text("只有成功撤离后，宝物才会进入永久仓库。",
                    color = relicMuted, fontSize = 11.sp)
                if (showBag) {
                if(hero.bag.isEmpty())Text("背包空空的",color=relicMuted)
                hero.bag.forEach{item->
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(item.tier.label+" "+item.name+" · "+item.value+" · "+item.weight+"格",
                            color=relicGold,fontSize=12.sp,modifier=Modifier.weight(1f))
                        TextButton(onClick={action("drop",id=item.id)}){Text("丢下")}
                    }
                }
                run.drops.filter{RelicEscapeRules.distance(hero.x,hero.y,it.x,it.y)<=1}
                    .forEach{drop->
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("地面："+drop.loot.name+" · "+drop.loot.weight+"格",
                                color=relicGreen,fontSize=12.sp,modifier=Modifier.weight(1f))
                            TextButton(onClick={action("pickup",id=drop.loot.id)}){Text("拾取")}
                        }
                    }
                }
            }
            if(run.allies.isNotEmpty())RelicPanel {
                Text("同行伙伴 · 现场实际状态",color=Color.White,fontWeight=FontWeight.Bold)
                run.allies.forEach{ally->
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        val name=remember(ally.id){MigratedDomainStores.characters.get(ally.id).displayName}
                        Column(Modifier.weight(1f)) {
                            Text(name+" · "+ally.hp+"❤ · 背包"+ally.load+"/8",color=Color.White,fontSize=12.sp)
                            Text("药剂"+ally.kits+" · 救援"+ally.rescues+"次 · 倒地"+ally.downs+"次",
                                color=relicMuted,fontSize=11.sp)
                        }
                        if(ally.hp<=0)TextButton(onClick={action("revive",id=ally.id)},
                            enabled=hero.kits>0 &&
                                RelicEscapeRules.distance(hero.x,hero.y,ally.x,ally.y)<=1){
                            Text("救援")
                        }
                    }
                }
            }
            RelicPanel {
                Text("现场动静",color=Color.White,fontWeight=FontWeight.Bold)
                run.events.takeLast(5).reversed().forEach{
                    Text("· "+it.text,color=when(it.action){
                        "warning","hurt"->relicRed
                        "invoke","open","escape"->relicGold
                        else->relicMuted
                    },
                        fontSize=11.sp)
                }
            }
        }
    }
        },
    )
    if(run.phase==RelicPhase.DOWNED) {
        Box(Modifier.fillMaxSize().background(Color(0x9C07111A)),
            contentAlignment=Alignment.Center) {
            Surface(
                modifier=Modifier.widthIn(max=390.dp).padding(horizontal=16.dp),
                color=Color(0xFF172738),
                shape=RoundedCornerShape(22.dp),
                border=BorderStroke(1.dp,relicRed.copy(alpha=.7f)),
                shadowElevation=14.dp,
            ) {
                Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(9.dp),
                    horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("❤ 你倒地了",color=relicRed,fontSize=23.sp,
                        fontWeight=FontWeight.Black)
                    Text(
                        if(run.allies.any{it.hp>0 && it.kits>0})
                            "还能等待队友来救你 · 剩余"+
                                (4-run.downTurns).coerceAtLeast(0)+"回合"
                        else "目前没有能救援的队友。不用卡在这里，可以立即重开或返回营地。",
                        color=Color.White,fontSize=12.sp,lineHeight=18.sp)
                    if(run.allies.any{it.hp>0 && it.kits>0}) {
                        OutlinedButton(onClick={action("wait")},modifier=Modifier.fillMaxWidth()) {
                            Text("等待队友救援",color=relicGreen)
                        }
                    }
                    Button(onClick={confirmRestart=true},modifier=Modifier.fillMaxWidth(),
                        colors=ButtonDefaults.buttonColors(
                            containerColor=relicGold,contentColor=relicInk)) {
                        Text("↻ 放弃并重新开局",fontWeight=FontWeight.Bold)
                    }
                    TextButton(onClick={lobbyOpen=true},modifier=Modifier.fillMaxWidth()) {
                        Text("返回营地 / 退出本局画面",color=Color.White)
                    }
                    Text("回营地不会偷偷扣除战利品；可在营地选择继续或明确放弃。",
                        color=relicMuted,fontSize=10.sp)
                }
            }
        }
    }
    }
    if(showRulesDialog)AlertDialog(
        onDismissRequest={showRulesDialog=false}, containerColor=relicInk,
        titleContentColor=Color.White, textContentColor=relicMuted,
        title={Text("迷雾摸金 · 怎么玩")},
        text={Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Text("移动：左侧方向键每次走一格，点地图可选目标；点「定位你」回到自己。")
            Text("战斗：附近有可攻击的敌人会自动显示「攻击近敌」，无需反复点怪。短剑只打上下左右1格；长矛直线2格，弩弓直线4格并消耗弩箭。")
            Text("探索：靠近宝箱开箱，侦察会消耗一回合；敌人也会随你的行动移动或攻击。")
            Text("撤离：到绿门附近启动撤离，守住3回合才能带走战利品；没赶到门附近的伙伴可能掉队。")
            Text("重开：点画面右下「重开」并确认；会失去当前未撤离的宝物和已消耗的补给，永久仓库不清空。")
        }},
        confirmButton={TextButton(onClick={showRulesDialog=false}) { Text("开始探索",color=relicGold) }},
    )
    if(confirmRestart)AlertDialog(
        onDismissRequest={confirmRestart=false}, containerColor=relicInk,
        titleContentColor=Color.White, textContentColor=relicMuted,
        title={Text("重新开始这局摸金？")},
        text={Text("当前局将按中途撤离失败结算，失去未带出的战利品和已消耗补给；永久仓库与铜币保留。新局将随机生成地图。")},
        confirmButton={TextButton(onClick={
            val previous=run
            val restarted=change {
                val ended=ledger.perform("abandon")
                ledger.syncExperiences(ended,store)
                ledger.begin(previous.allies.map{it.id},kits.coerceAtMost(ended.kits),
                    previous.difficulty,startingWeapon,previous.contract)
            }
            if(restarted && snapshot.run?.id!=previous.id) lobbyOpen=false
            confirmRestart=false
        }){Text("放弃并重开",color=relicGold)}},
        dismissButton={TextButton(onClick={confirmRestart=false}){Text("继续本局",color=relicMuted)}},
    )
    if(confirmAbandon)AlertDialog(onDismissRequest={confirmAbandon=false},
        containerColor=relicInk,titleContentColor=Color.White,textContentColor=relicMuted,
        title={Text("放弃本局？")},
        text={Text("放弃将失去本局所有未撤离宝物和已消耗补给，永久仓库不会被扣除。")},
        confirmButton={TextButton(onClick={action("abandon");confirmAbandon=false}){Text("确认放弃")}},
        dismissButton={TextButton(onClick={confirmAbandon=false}){Text("继续探索")}})
}

@Composable private fun RelicPanel(content:@Composable ColumnScope.()->Unit) {
    Surface(shape=RoundedCornerShape(18.dp),color=Color(0xE525303E),
        border=BorderStroke(1.dp,Color.White.copy(alpha=.12f))) {
        Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp),
            content=content)
    }
}
/** Simple original gemstone illustration; it visualizes only verified album entries. */
@Composable private fun RelicMuseumPiece(name:String) {
    val precious=name=="封印之心" || RelicCollectibles.isCrownJewel(name) ||
        name in RelicCollectibles.namesFor(RelicTier.PRECIOUS)
    val rare=name in RelicCollectibles.namesFor(RelicTier.RARE)
    val color=if(precious)Color(0xFFFFD58B) else if(rare)
        Color(0xFF90DDF8) else Color(0xFFDCB99C)
    Surface(modifier=Modifier.width(144.dp),
        color=Color(0xFF192837),shape=RoundedCornerShape(16.dp),
        border=BorderStroke(1.dp,color.copy(alpha=.55f))) {
        Column(Modifier.padding(10.dp),
            horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Canvas(Modifier.fillMaxWidth().height(65.dp)) {
                val center=Offset(size.width*.5f,size.height*.5f)
                val scale=size.height
                drawCircle(color.copy(alpha=.10f),scale*.43f,center)
                drawCircle(color.copy(alpha=.24f),scale*.30f,center,
                    style=Stroke(scale*.035f))
                with(RelicTreasureArt) {
                    drawTreasure(name,center,scale*.78f,color)
                }
                drawCircle(Color.White.copy(alpha=.73f),scale*.045f,
                    center+Offset(scale*.35f,-scale*.26f))
            }
            Text(name,color=Color.White,fontSize=12.sp,
                fontWeight=FontWeight.Bold,maxLines=1)
            Text(if(RelicCollectibles.isCrownJewel(name))"✦ 隐世奇珍"
                else if(precious)"珍贵藏品" else if(rare)"稀有藏品" else "普通藏品",
                color=color,fontSize=10.sp)
        }
    }
}

@Composable private fun RelicValue(title:String,detail:String,color:Color) {
    Column {
        Text(title,fontSize=10.sp,color=relicMuted)
        Text(detail,fontSize=14.sp,fontWeight=FontWeight.Bold,color=color)
    }
}
@Composable private fun RelicCommand(text:String,modifier:Modifier=Modifier,enabled:Boolean=true,onClick:()->Unit) {
    Button(onClick=onClick,enabled=enabled,modifier=modifier.height(39.dp),
        shape=RoundedCornerShape(10.dp),contentPadding=PaddingValues(horizontal=2.dp),
        colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFC5AC76),contentColor=relicInk)){
        Text(text,fontSize=10.sp,maxLines=1)
    }
}
private fun relicDirection(dx:Int,dy:Int):String=
    (if(dy<0)"北" else if(dy>0)"南" else "")+
        (if(dx<0)"西" else if(dx>0)"东" else "")+
        if(dx==0 && dy==0)"此处" else ""

@Composable private fun RelicSettlement(run:RelicRun,newDiscoveries:Set<String>) {
    RelicPanel {
        Text(if(run.phase==RelicPhase.SUCCESS)"✦ 平安归来" else "遗迹探险失败",
            fontWeight=FontWeight.Black,fontSize=20.sp,
            color=if(run.phase==RelicPhase.SUCCESS)relicGold else relicRed)
        Text("成功带回 "+run.secured.size+" 件 · 价值 "+run.secured.sumOf{it.value}+
            "    遗失 "+run.lost.size+" 件",color=Color.White)
        if(run.phase==RelicPhase.SUCCESS && newDiscoveries.isNotEmpty()) {
            Surface(shape=RoundedCornerShape(15.dp),
                color=Color(0xFF463823),border=BorderStroke(1.dp,relicGold.copy(alpha=.65f))) {
                Column(Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text("✦ 首次收录 · 珍宝图鉴新增 ${newDiscoveries.size} 件",
                        fontSize=15.sp,fontWeight=FontWeight.Black,color=Color(0xFFFFE4AA))
                    Text(newDiscoveries.sorted().joinToString(" · "),
                        fontSize=12.sp,color=Color.White,lineHeight=19.sp)
                    Text("这份发现永远属于你。实物出售后，图鉴也不会遗忘。",
                        fontSize=11.sp,color=relicGold)
                }
            }
        }
        if(run.contract!=RelicContract.NONE) {
            val earned=RelicContractBoard.bonus(run)
            Text("探险委托 · "+run.contract.label+" · "+
                (if(earned>0) "达成！铜币 +"+earned else "本局未完成（无额外奖励）"),
                color=if(earned>0)relicGreen else relicMuted,fontWeight=FontWeight.Bold)
        }
        run.secured.forEach{Text("带回 · "+it.tier.label+" "+it.name+" · "+it.value,color=relicGreen)}
        run.lost.forEach{Text("遗失 · "+it.tier.label+" "+it.name+" · "+it.value,color=relicRed)}
        if(run.secured.isEmpty() && run.lost.isEmpty())Text("本局没有带出宝物",color=relicMuted)
        Text("成员战况",color=Color.White,fontWeight=FontWeight.Bold)
        Text("玩家 · 生命"+run.hero.hp+" · 救援"+run.hero.rescues+"次 · 受伤"+
            run.events.count{it.who=="player" && it.action=="hurt"}+"次",color=relicMuted)
        run.allies.forEach{ally->
            Text(ally.id+" · 生命"+ally.hp+" · 救援"+ally.rescues+" · 倒地"+ally.downs+
                " · 受伤"+run.events.count{it.who==ally.id && it.action=="hurt"}+"次 · "+
                if(run.events.any{it.who==ally.id && it.action=="escape"})"撤离" else "未撤离",
                color=relicMuted,fontSize=12.sp)
        }
        Text("行动回顾",color=Color.White,fontWeight=FontWeight.Bold)
        val actorEvents=run.events.filter{it.action in setOf(
            "open","attack","heal","rescue","down","revived","escape","left","hurt","failure","pickup")}
        if(actorEvents.isEmpty())Text("本局没有特殊行动",color=relicMuted)
        actorEvents.takeLast(24).forEach{event->
            val actor=if(event.who=="player")"玩家" else if(event.who=="world")"遗迹"
                else run.allies.firstOrNull{it.id==event.who}?.let{
                    MigratedDomainStores.characters.get(it.id).displayName
                }?:event.who
            Text("第"+event.turn+"回合 · "+actor+"："+event.text,
                color=if(event.action=="hurt" || event.action=="failure")relicRed else relicMuted,
                fontSize=11.sp)
        }
        Text("回合 "+run.turns+" · 本局宝物的结算已锁定，不可重复领取",
            fontSize=11.sp,color=relicMuted)
    }
}

