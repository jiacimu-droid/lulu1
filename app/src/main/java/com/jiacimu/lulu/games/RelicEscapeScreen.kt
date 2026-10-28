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
    var error by remember{mutableStateOf("")}

    fun change(block:()->RelicSnapshot) {
        runCatching{block()}.onSuccess{snapshot=it;error=""}
            .onFailure{error=it.message?:"存档失败，请重试"}
    }
    fun action(type:String,x:Int=0,y:Int=0,id:String="") {
        val tier=run?.chests?.firstOrNull{it.x==x && it.y==y && !it.opened}?.tier
        val before=run?.turns
        change{ledger.perform(type,x,y,id)}
        if(before!=snapshot.run?.turns) {
            val sound=when(type) {
                "open"->when(tier){
                    RelicTier.COMMON->GameSoundEffect.Interact
                    RelicTier.RARE->GameSoundEffect.Collect
                    RelicTier.PRECIOUS->GameSoundEffect.Objective
                    null->GameSoundEffect.Bump
                }
                "attack","extract"->GameSoundEffect.Objective
                "pickup","heal","revive"->GameSoundEffect.Collect
                "move","sneak"->GameSoundEffect.Move
                else->GameSoundEffect.Interact
            }
            GameSoundEffects.play(sound)
        } else if(type!="wait")GameSoundEffects.play(GameSoundEffect.Bump)
    }
    LaunchedEffect(run?.id,run?.phase,snapshot.awarded) {
        if(run!=null && run.id in snapshot.awarded)ledger.syncExperiences(snapshot,store)
    }

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
        Color(0xFF241D29),Color(0xFF101A25),relicInk)))
        .verticalScroll(rememberScrollState())
        .padding(12.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("迷雾摸金",fontSize=24.sp,fontWeight=FontWeight.Black,color=Color.White)
                Text("合作搜宝 · 怪物巡逻 · 活着离开",fontSize=11.sp,color=relicMuted)
            }
            Text("仓库 "+snapshot.vault.size+"件",color=relicGold,fontWeight=FontWeight.Bold,fontSize=13.sp)
        }
        if(error.isNotBlank())RelicPanel {Text(error,color=relicRed)}
        if(run==null || run.phase in listOf(RelicPhase.SUCCESS,RelicPhase.FAILURE,RelicPhase.SETTLEMENT)) {
            if(run!=null)RelicSettlement(run)
            RelicPanel {
                Text("出发准备",color=Color.White,fontWeight=FontWeight.Black,fontSize=19.sp)
                Text("每次入场都会扣除携带的药剂；宝物仅在安全撤离后入库。失败丢失本局战利品，不扣已有仓库藏品。",
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
            }
            RelicEscapeMap(run,selected){x,y->selected=x to y}
            RelicPanel {
                val sx=selected.first;val sy=selected.second
                val visible=run.visible(sx,sy)
                val chest=run.chests.firstOrNull{it.x==sx && it.y==sy && !it.opened}
                val mob=run.mobs.firstOrNull{it.x==sx && it.y==sy && it.hp>0}
                Text("目标 "+sx+","+sy+" · "+if(!visible)"迷雾未探明" else when {
                    chest!=null->chest.tier.label+"宝箱"
                    mob!=null->"敌人 · 生命"+mob.hp
                    sx==run.exitX && sy==run.exitY->"撤离信标"
                    run.tile(sx,sy)==1->"石墙"
                    run.tile(sx,sy)==2->"腐蚀地面"
                    else->"遗迹通路"
                },fontSize=12.sp,color=relicMuted)
                Text(run.notice,color=Color.White,fontSize=12.sp)
                if(run.phase!=RelicPhase.DOWNED) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        Column(horizontalAlignment=Alignment.CenterHorizontally) {
                            RelicControl("↑"){action("move",hero.x,hero.y-1)}
                            Row(horizontalArrangement=Arrangement.spacedBy(2.dp)) {
                                RelicControl("←"){action("move",hero.x-1,hero.y)}
                                RelicControl("↓"){action("move",hero.x,hero.y+1)}
                                RelicControl("→"){action("move",hero.x+1,hero.y)}
                            }
                        }
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
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
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={action("wait")}) {
                        Text(if(run.phase==RelicPhase.DOWNED)"等待救援" else "等待 / 守住出口")
                    }
                    OutlinedButton(onClick={confirmAbandon=true}){Text("放弃本局")}
                }
            }
            RelicPanel {
                Text("背包 · 仅撤离成功后入库",color=Color.White,fontWeight=FontWeight.Bold)
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
                    Text("· "+it.text,color=if(it.action=="warning" || it.action=="hurt")relicRed else relicMuted,
                        fontSize=11.sp)
                }
            }
        }
    }
    if(confirmAbandon)AlertDialog(onDismissRequest={confirmAbandon=false},
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
@Composable private fun RelicControl(text:String,onClick:()->Unit) {
    FilledTonalButton(onClick=onClick,modifier=Modifier.size(40.dp),
        shape=RoundedCornerShape(10.dp),contentPadding=PaddingValues(0.dp),
        colors=ButtonDefaults.filledTonalButtonColors(containerColor=Color(0xFF435366),
            contentColor=Color.White)){Text(text,fontSize=19.sp)}
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
        Text("回合 "+run.turns+" · 本局宝物的结算已锁定，不可重复领取",
            fontSize=11.sp,color=relicMuted)
    }
}

@Composable private fun RelicEscapeMap(run:RelicRun,selected:Pair<Int,Int>,onSelect:(Int,Int)->Unit) {
    val breathe=rememberInfiniteTransition(label="relic-glow")
        .animateFloat(.45f,1f,infiniteRepeatable(tween(1400),RepeatMode.Reverse),label="pulse").value
    Surface(color=Color(0xFF090F18),shape=RoundedCornerShape(20.dp),
        border=BorderStroke(1.dp,Color(0xFF546376))) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(run.w.toFloat()/run.h)
            .pointerInput(run.id,onSelect) {
                detectTapGestures{tap->
                    val sizePx=min(size.width/run.w.toFloat(),size.height/run.h.toFloat())
                    val tx=(tap.x/sizePx).toInt();val ty=(tap.y/sizePx).toInt()
                    if(run.bounds(tx,ty))onSelect(tx,ty)
                }
            }) {
            val t=min(size.width/run.w.toFloat(),size.height/run.h.toFloat())
            fun at(x:Int,y:Int)=Offset((x+.5f)*t,(y+.5f)*t)
            for(y in 0 until run.h)for(x in 0 until run.w) {
                val pos=Offset(x*t,y*t)
                if(!run.visible(x,y)) {
                    drawRect(Color(0xFF0A101A),pos,Size(t+.5f,t+.5f))
                    if((x*11+y*13)%7==0)drawCircle(Color(0xFF283342).copy(alpha=.30f),
                        t*.22f,at(x,y))
                    continue
                }
                val floor=when(run.tile(x,y)) {
                    1->Color(0xFF293545);2->Color(0xFF42574D)
                    3->Color(0xFF414852)
                    else->if((x*7+y*13)%4==0)Color(0xFF3D3B44)else Color(0xFF33343F)
                }
                drawRect(floor,pos,Size(t+.5f,t+.5f))
                drawLine(Color.White.copy(alpha=.055f),pos+Offset(t*.1f,t*.18f),
                    pos+Offset(t*.86f,t*.12f),strokeWidth=.85f)
                if(run.tile(x,y)==1) {
                    drawRect(Color(0xFF5C6470),pos+Offset(t*.07f,t*.1f),Size(t*.86f,t*.78f))
                    drawLine(Color(0xFF252C3A),pos+Offset(t*.1f,t*.45f),
                        pos+Offset(t*.87f,t*.45f),strokeWidth=t*.06f)
                }
                if(run.tile(x,y)==2)drawCircle(Color(0xFF81BFA6).copy(alpha=.3f),
                    t*.33f,at(x,y))
                if(run.tile(x,y)==3)drawRoundRect(Color(0xFF74747D),
                    pos+Offset(t*.17f,t*.27f),Size(t*.64f,t*.53f),
                    cornerRadius=CornerRadius(t*.12f))
                val chest=run.chests.firstOrNull{it.x==x && it.y==y && !it.opened}
                if(chest!=null) {
                    val glow=when(chest.tier){RelicTier.COMMON->Color(0xFFD4AF69)
                        RelicTier.RARE->Color(0xFF70CBE0);RelicTier.PRECIOUS->Color(0xFFFFDD8A)}
                    drawCircle(glow.copy(alpha=.15f*breathe),t*.52f,at(x,y))
                    drawRoundRect(Color(0xFF8C623C),pos+Offset(t*.13f,t*.29f),
                        Size(t*.74f,t*.48f),CornerRadius(t*.08f))
                    drawRect(glow,pos+Offset(t*.19f,t*.31f),Size(t*.64f,t*.11f))
                    drawCircle(glow,t*.10f,at(x,y))
                }
                if(x==run.exitX && y==run.exitY) {
                    drawCircle(relicGreen.copy(alpha=.19f*breathe),t*.55f,at(x,y))
                    drawCircle(relicGreen,t*.30f,at(x,y),style=Stroke(t*.10f))
                    drawCircle(Color.White.copy(alpha=.65f),t*.08f,at(x,y))
                }
                run.drops.filter{it.x==x && it.y==y}.forEach {
                    drawCircle(relicGold,t*.10f,at(x,y)+Offset(t*.27f,t*.23f))
                }
                if(x==selected.first && y==selected.second)
                    drawRect(relicGold,pos+Offset(1f,1f),Size(t-2f,t-2f),style=Stroke(2.2f))
            }
            run.mobs.filter{it.hp>0 && run.visible(it.x,it.y)}.forEach{m->
                val p=at(m.x,m.y)
                if(m.windup)drawCircle(relicRed.copy(alpha=.43f*breathe),t*.55f,p)
                drawCircle(if(m.kind==2)Color(0xFF762F49)else Color(0xFF594058),t*.34f,p)
                drawCircle(relicRed,t*.08f,p+Offset(-t*.13f,-t*.04f))
                drawCircle(relicRed,t*.08f,p+Offset(t*.13f,-t*.04f))
            }
            run.allies.forEachIndexed{index,a->
                if(!run.visible(a.x,a.y))return@forEachIndexed
                val p=at(a.x,a.y)
                drawCircle(if(a.hp>0)Color(0xFF83C7EC)else Color(0xFF536375),t*.30f,p)
                drawCircle(Color(0xFF223947),t*.13f,p)
                if(a.hp<=0)drawLine(relicRed,p+Offset(-t*.17f,-t*.17f),
                    p+Offset(t*.17f,t*.17f),strokeWidth=t*.10f)
            }
            val player=at(run.hero.x,run.hero.y)
            drawCircle(relicGold.copy(alpha=.24f),t*.46f,player)
            drawCircle(if(run.hero.hp>0)relicGold else relicRed,t*.33f,player)
            drawCircle(Color(0xFF4A5268),t*.15f,player+Offset(0f,t*.06f))
            drawCircle(Color.White,t*.06f,player+Offset(t*.1f,-t*.11f))
        }
    }
}
