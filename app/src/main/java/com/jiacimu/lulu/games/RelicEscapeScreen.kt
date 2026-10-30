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
    var selected by remember(run?.id){mutableStateOf((run?.hero?.x?:2) to (run?.hero?.y?:2))}
    var confirmAbandon by remember{mutableStateOf(false)}
    var showGuide by remember(run?.id){mutableStateOf(true)}
    var showAdvanced by remember(run?.id){mutableStateOf(false)}
    var showBag by remember(run?.id){mutableStateOf(false)}
    var error by remember{mutableStateOf("")}

    fun change(block:()->RelicSnapshot) {
        runCatching{block()}.onSuccess{snapshot=it;error=""}
            .onFailure{error=it.message?:"存档失败，请重试"}
    }
    fun action(type:String,x:Int=0,y:Int=0,id:String="") {
        val tier=run?.chests?.firstOrNull{it.x==x && it.y==y && !it.opened}?.tier
        val before=run?.turns
        change{ledger.perform(type,x,y,id,run?.allies?.map{it.id}?.toSet() ?: emptySet())}
        if(before!=snapshot.run?.turns) {
            val sound=when(type) {
                "open"->when(tier){
                    RelicTier.COMMON->GameSoundEffect.Interact
                    RelicTier.RARE->GameSoundEffect.Collect
                    RelicTier.PRECIOUS->GameSoundEffect.Objective
                    null->GameSoundEffect.Bump
                }
                "attack","extract","invoke","flare"->GameSoundEffect.Objective
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

    GameSceneDeck(
        accent = relicGold,
        toolsLabel = "背包 / 战术",
        notice = error.ifBlank { if(run?.phase == RelicPhase.SUCCESS)
            "撤离成功 · 带回${run.secured.size}件宝物" else if(run?.phase == RelicPhase.FAILURE)
            "本次探索结束 · 战利品遗失" else run?.notice.orEmpty() },
        status = if (run == null) "暗影遗迹 · 营地" else
            "❤ ${run.hero.hp}/10   ◇ ${run.value}   行囊 ${run.hero.load}/8   第${run.turns}回合",
        scene = {
            if (run != null && run.phase in setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT,RelicPhase.DOWNED))
                RelicEscapeWorldMap(run,selected,{ x,y -> selected=x to y },fullscreen=true)
            else RelicCampBackdrop()
        },
        companionAvailable = run != null && run.allies.isNotEmpty() &&
            run.phase in setOf(RelicPhase.EXPLORE, RelicPhase.EXTRACT, RelicPhase.DOWNED),
        companion = {
            if (run != null && run.allies.isNotEmpty() && run.phase in
                setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT,RelicPhase.DOWNED)) {
                RelicCompanionPanel(run,readRun={snapshot.run ?: run}) { id,choice ->
                    var saved = false
                    change { ledger.companion(run.id,id,choice).also { saved = true } }
                    saved
                }
            }
        },
        movement = {
            val active = run != null && run.phase in setOf(RelicPhase.EXPLORE, RelicPhase.EXTRACT)
            GameDirectionalPad(accent = relicGold, enabled = active, holdRepeatMs = 580L,
                stopWhen = (run?.hero?.hp to run?.phase)) { dx, dy ->
                run?.hero?.let { action("move", it.x + dx, it.y + dy) }
            }
        },
        quickActions = {
            if (run != null && run.phase in setOf(RelicPhase.EXPLORE, RelicPhase.EXTRACT, RelicPhase.DOWNED)) {
                val hero = run.hero
                val sx = selected.first
                val sy = selected.second
                val visible = run.visible(sx, sy)
                val adjacent = RelicEscapeRules.distance(hero.x, hero.y, sx, sy) <= 1
                val enemy = run.mobs.any { it.hp > 0 && it.x == sx && it.y == sy }
                val chest = run.chests.any { !it.opened && it.x == sx && it.y == sy }
                val moveStep = if (visible) RelicEscapeRules.path(run, hero.x, hero.y, sx, sy) else null
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    GameHudQuickAction("攻击", relicGold, run.phase != RelicPhase.DOWNED && visible && adjacent && enemy,
                        Modifier.weight(1f)) { action("attack", sx, sy) }
                    GameHudQuickAction("开箱", relicGold, run.phase != RelicPhase.DOWNED && visible && adjacent && chest,
                        Modifier.weight(1f)) { action("open", sx, sy) }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    GameHudQuickAction("潜行", relicGreen, run.phase != RelicPhase.DOWNED && moveStep != null,
                        Modifier.weight(1f)) { moveStep?.let { action("sneak", it.first, it.second) } }
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
                    change { ledger.begin(if(game.playWithCharacter)game.selectedCharacterIds else emptyList(),kits) }
                } else quick?.let { action(it.action,it.x,it.y,it.id) }
            },enabled = run == null || run.phase in setOf(RelicPhase.SUCCESS,RelicPhase.FAILURE,RelicPhase.SETTLEMENT) || quick != null,
                modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor=relicGold,contentColor=relicInk)) {
                Text(if(run == null) "进入遗迹" else if(run.phase in setOf(RelicPhase.SUCCESS,RelicPhase.FAILURE,RelicPhase.SETTLEMENT))
                    "再次出发" else quick?.label ?: "点选地图目标",fontSize=12.sp,maxLines=1)
            }
            if(run != null && run.phase in setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT,RelicPhase.DOWNED))
                TextButton(onClick={action("wait")}) { Text("等待",color=Color.White) }
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
            if(run!=null)RelicSettlement(run)
            RelicPanel {
                Text("出发准备",color=Color.White,fontWeight=FontWeight.Black,fontSize=19.sp)
                Text("出发自带2枚照明弹、1枚声响诱饵和2套机关工具。祭坛有更贵重的藏品，也会唤醒守卫。成功撤离才将宝物入库；倒地失败只丢失本局战利品。",
                    color=relicMuted,fontSize=12.sp)
                Text("本次同行："+(if(game.playWithCharacter)game.selectedCharacterIds.distinct().take(3).size else 0)+
                    "位伙伴（每人自带1份救援药剂）",color=relicGreen,fontSize=12.sp)
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("药剂 "+kits+" / 库存"+snapshot.kits,color=Color.White,modifier=Modifier.weight(1f))
                    OutlinedButton(onClick={kits=(kits-1).coerceAtLeast(0)}){Text("−")}
                    OutlinedButton(onClick={kits=(kits+1).coerceAtMost(min(3,snapshot.kits))}){Text("+")}
                }
                Text("武器："+if(snapshot.weapon==2)"锋刃长枪（伤害4）" else "旧式短剑（伤害2）",
                    color=relicGold,fontSize=12.sp)
                Button(onClick={
                    change{ledger.begin(if(game.playWithCharacter)game.selectedCharacterIds else emptyList(),kits)}
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
        } else {
            val hero=run.hero
            RelicPanel {
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    RelicValue("生命",hero.hp.toString()+"/10",relicRed)
                    RelicValue("负重",hero.load.toString()+"/"+RelicEscapeRules.CAPACITY,relicGold)
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
                        Text("先找宝物，再返回撤离门", modifier = Modifier.weight(1f),
                            color = relicGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { showGuide = !showGuide }) {
                            Text(if (showGuide) "收起指引" else "怎么玩", color = relicGold, fontSize = 11.sp)
                        }
                    }
                    if (showGuide) {
                        Text("① 轻点地图选择目标  ② 点下方主按钮执行",
                            color = Color.White, fontSize = 12.sp, lineHeight = 19.sp)
                        Text("开箱拿宝物 → 找绿色撤离门 → 守住三回合。每次行动都会推进时间，怪物会反击。",
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
                    mob!=null->"敌人 · 生命"+mob.hp
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
                                    enabled=mob!=null && visible &&
                                        RelicEscapeRules.distance(hero.x,hero.y,sx,sy)<=1) {
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
                Text("遗迹战术",color=relicGold,fontWeight=FontWeight.Bold,fontSize=13.sp)
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
                val latestTreasure=run.events.lastOrNull{it.action=="invoke" || it.action=="open"}
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
                    Text("战利品 · ${hero.bag.size} 件 · ${hero.load}/${RelicEscapeRules.CAPACITY} 格",
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

@Composable private fun RelicSettlement(run:RelicRun) {
    RelicPanel {
        Text(if(run.phase==RelicPhase.SUCCESS)"✦ 平安归来" else "遗迹探险失败",
            fontWeight=FontWeight.Black,fontSize=20.sp,
            color=if(run.phase==RelicPhase.SUCCESS)relicGold else relicRed)
        Text("成功带回 "+run.secured.size+" 件 · 价值 "+run.secured.sumOf{it.value}+
            "    遗失 "+run.lost.size+" 件",color=Color.White)
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

