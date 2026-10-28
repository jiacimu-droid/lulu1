package com.jiacimu.lulu.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/** A real tile world: touch to select, navigate, gather/build/fight/loot and extract. */
@Composable
internal fun ExpeditionScreen(store: LuluGameStore, mode: ExpeditionMode) {
    val context = LocalContext.current
    val game by store.state.collectAsState()
    val prefs = remember(mode) { context.getSharedPreferences("lulu_expedition_${mode.name.lowercase()}_v1", 0) }
    var world by remember(mode) {
        mutableStateOf(ExpeditionState.fromJson(prefs.getString("world", "").orEmpty(), mode)
            ?: ExpeditionRules.start(mode, Random.nextInt(), game.selectedCharacterIds))
    }
    var selected by remember(mode) { mutableStateOf(world.x to world.y) }
    var autoTarget by remember(mode) { mutableStateOf<Pair<Int, Int>?>(null) }
    var showReset by remember(mode) { mutableStateOf(false) }
    val expeditionColor = if (mode == ExpeditionMode.HOMESTEAD) Color(0xFFB2F4AF) else Color(0xFFFFD58D)
    val dark = Color(0xFF101C29)

    fun save(next: ExpeditionState) {
        world = next
        prefs.edit().putString("world", next.toJson().toString()).apply()
    }
    LaunchedEffect(game.selectedCharacterIds, mode) {
        val ids = if (game.playWithCharacter) game.selectedCharacterIds else emptyList()
        if (world.friends.map { it.id } != ids.distinct().take(3)) {
            save(ExpeditionRules.withPartners(world, ids))
        }
    }
    LaunchedEffect(world.finished, world.recorded, mode) {
        if (mode == ExpeditionMode.RELIC_RUN && world.finished && !world.recorded) {
            val record = world
            val description = if (record.escaped) "成功撤离，带回 ${record.relics} 件藏品，价值 ${record.lootValue}"
                else "队伍未能撤离，已搜到的藏品遗失"
            store.recordExternalGame(
                type = LuluGameType.RelicEscape,
                title = mode.title,
                score = if (record.escaped) record.lootValue else 0,
                reward = 0,
                summary = description + "；同行角色 ${record.friends.size} 位，共进行 ${record.turns} 次行动。",
                detailsJson = record.toJson().toString(),
                characterIdOverride = record.friends.firstOrNull()?.id.orEmpty(),
                playedWithCharacterOverride = record.friends.isNotEmpty(),
            )
            save(world.copy(recorded = true))
        }
    }
    LaunchedEffect(autoTarget, world.x, world.y, world.finished) {
        val goal = autoTarget ?: return@LaunchedEffect
        if (world.finished) { autoTarget = null; return@LaunchedEffect }
        if (world.x == goal.first && world.y == goal.second ||
            abs(world.x - goal.first) + abs(world.y - goal.second) == 1 &&
            world.cell(goal.first,goal.second) !in listOf(0,4,6,7)) {
            autoTarget = null
            return@LaunchedEffect
        }
        val next = expeditionNextStep(world, goal.first, goal.second)
        if (next == null) {
            autoTarget = null
            save(world.copy(notice = "没有可通行的路线，可先挖开障碍或换条路"))
            return@LaunchedEffect
        }
        delay(185)
        save(ExpeditionRules.act(world, "move", next.first, next.second))
    }

    Column(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                if (mode == ExpeditionMode.HOMESTEAD)
                    listOf(Color(0xFF102A2C), Color(0xFF0E1C27), dark)
                else listOf(Color(0xFF30202C), Color(0xFF18202F), dark),
            )
        ).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(mode.title, fontSize = 23.sp, color = Color.White, fontWeight = FontWeight.Black)
                Text(
                    if (mode == ExpeditionMode.HOMESTEAD) "自由采集 · 建造家园 · 与伙伴抵御怪物"
                    else "搜寻藏品 · 合作对抗 · 活着撤离",
                    fontSize = 11.sp, color = Color(0xFFB4C7CE),
                )
            }
            Surface(color = expeditionColor.copy(alpha = .15f), shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, expeditionColor.copy(alpha = .3f))) {
                Text(if (world.finished) "本局结束" else "第 ${world.turns} 步",
                    color = expeditionColor, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
            }
        }
        Surface(
            shape = RoundedCornerShape(19.dp), color = Color(0xE01D303C),
            border = BorderStroke(1.dp, Color.White.copy(alpha = .10f)),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusValue("生命", "${world.hp}/12", Color(0xFFFFA7AD))
                if (mode == ExpeditionMode.HOMESTEAD) {
                    StatusValue("木材", "${world.wood}", Color(0xFFC3E99F))
                    StatusValue("石料", "${world.stone}", Color(0xFFC3D0E4))
                    StatusValue("房屋", "${world.tiles.count { it == 3 }}墙", expeditionColor)
                } else {
                    StatusValue("藏品", "${world.relics}件", expeditionColor)
                    StatusValue("估值", "${world.lootValue}", Color(0xFFFFDC95))
                    StatusValue("出口", if (world.tiles.contains(6)) "绿色门" else "未发现", Color(0xFFADDDC0))
                }
            }
        }
        ExpeditionMap(world, selected, onSelect = { x, y ->
            selected = x to y
            autoTarget = null
        })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("目标 (${selected.first}, ${selected.second}) · ${expeditionTileName(world.cell(selected.first, selected.second))}",
                color = Color(0xFFCFD9DF), fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { autoTarget = selected },
                enabled = !world.finished && selected != (world.x to world.y),
            ) { Icon(Icons.Outlined.NearMe, null, Modifier.size(16.dp)); Text("前往") }
        }
        Surface(color = Color(0xDE17303D), shape = RoundedCornerShape(15.dp)) {
            Text(world.notice, color = Color(0xFFDCE9EA), fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(12.dp), lineHeight = 17.sp)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(132.dp)) {
                SquareMoveButton("↑", enabled = !world.finished) {
                    autoTarget = null; save(ExpeditionRules.act(world, "move", world.x, world.y - 1))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    SquareMoveButton("←", enabled = !world.finished) {
                        autoTarget = null; save(ExpeditionRules.act(world, "move", world.x - 1, world.y))
                    }
                    SquareMoveButton("↓", enabled = !world.finished) {
                        autoTarget = null; save(ExpeditionRules.act(world, "move", world.x, world.y + 1))
                    }
                    SquareMoveButton("→", enabled = !world.finished) {
                        autoTarget = null; save(ExpeditionRules.act(world, "move", world.x + 1, world.y))
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val (tx,ty)=selected
                val near = world.adjacent(tx,ty)
                if (mode == ExpeditionMode.HOMESTEAD) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        ActionTile("采集", enabled = near && !world.finished, Modifier.weight(1f)) {
                            save(ExpeditionRules.act(world,"gather",tx,ty))
                        }
                        ActionTile("攻击", enabled = near && !world.finished, Modifier.weight(1f)) {
                            save(ExpeditionRules.act(world,"fight",tx,ty))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        ActionTile("铺地板", enabled = near && !world.finished, Modifier.weight(1f)) {
                            save(ExpeditionRules.act(world,"floor",tx,ty))
                        }
                        ActionTile("筑木墙", enabled = near && !world.finished, Modifier.weight(1f)) {
                            save(ExpeditionRules.act(world,"wall",tx,ty))
                        }
                    }
                    ActionTile("搭建一间小屋 · 6木2石", enabled = !world.finished, Modifier.fillMaxWidth()) {
                        save(ExpeditionRules.act(world, "home"))
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        ActionTile("打开宝箱", enabled = near && !world.finished, Modifier.weight(1f)) {
                            save(ExpeditionRules.act(world,"open",tx,ty))
                        }
                        ActionTile("攻击怪物", enabled = near && !world.finished, Modifier.weight(1f)) {
                            save(ExpeditionRules.act(world,"fight",tx,ty))
                        }
                    }
                    ActionTile("抵达绿色撤离门 · 撤离", enabled = !world.finished,
                        Modifier.fillMaxWidth()) { save(ExpeditionRules.act(world,"exit")) }
                }
            }
        }
        if (world.friends.isNotEmpty()) {
            Text("同行伙伴 · 真实参与本局行动", color = Color(0xFFD4E0E3),
                fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                world.friends.forEach { companion ->
                    val friend = MigratedDomainStores.characters.get(companion.id)
                    Surface(color = Color(0xFF243B47), shape = RoundedCornerShape(13.dp),
                        border = BorderStroke(1.dp, Color(0xFF476774))) {
                        Text("✦ ${friend.displayName} · ${companion.hp}❤",
                            color = Color.White, fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp))
                    }
                }
            }
        }
        if (world.finished) {
            Text(if (world.escaped) "平安归来，宝藏已经入库" else "探索失败，战利品未能带走",
                color = expeditionColor, fontWeight = FontWeight.Black, fontSize = 16.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            if (mode == ExpeditionMode.HOMESTEAD) {
                OutlinedButton(onClick = {
                    store.recordExternalGame(
                        type = LuluGameType.BlockIsland, title = mode.title,
                        score = world.wood + world.stone + world.tiles.count { it==3 }*5,
                        reward = 0,
                        summary = "岛屿探索${world.turns}步，木材${world.wood}、石料${world.stone}、建筑墙面${world.tiles.count { it==3 }}块",
                        detailsJson = world.toJson().toString(),
                        characterIdOverride = world.friends.firstOrNull()?.id.orEmpty(),
                        playedWithCharacterOverride = world.friends.isNotEmpty(),
                    )
                    save(world.copy(notice = "已将这次建造进度保存到游戏记录；岛屿仍会继续存在"))
                }, modifier = Modifier.weight(1f)) { Text("记录本次冒险") }
            }
            OutlinedButton(
                onClick = { showReset = true },
                modifier = Modifier.weight(1f),
            ) { Text(if(mode==ExpeditionMode.HOMESTEAD) "重建岛屿" else "重新出发") }
        }
        Text(
            if (mode == ExpeditionMode.HOMESTEAD) "世界自动存档。资源、怪物与已建造的房屋，退出后仍在。"
            else "冒险自动存档。搜到的藏品只有成功抵达出口撤离才能计入得分。",
            color = Color(0xFF94AEB9), fontSize = 10.sp, lineHeight = 15.sp)
        Spacer(Modifier.height(14.dp))
    }
    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text(if(mode==ExpeditionMode.HOMESTEAD) "重建岛屿？" else "开始新一局？") },
            text = { Text(if(mode==ExpeditionMode.HOMESTEAD)
                "会清除当前岛屿的建筑和采集进度，已保存的历史记录保留。"
                else "会放弃当前探索及尚未撤离的藏品，已完成的对局历史仍保留。") },
            confirmButton = { TextButton(onClick = {
                autoTarget = null
                val partnerIds = if(game.playWithCharacter)game.selectedCharacterIds else emptyList()
                save(ExpeditionRules.start(mode,Random.nextInt(),partnerIds))
                selected = world.x to world.y
                showReset = false
            }) { Text("确认") } },
            dismissButton = { TextButton(onClick = {showReset=false}) {Text("取消")} },
        )
    }
}

@Composable
private fun StatusValue(label: String, value: String, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color=Color(0xFF9AAEB9),fontSize=10.sp)
        Text(value, color=color,fontSize=15.sp,fontWeight=FontWeight.Bold)
    }
}

@Composable
private fun SquareMoveButton(text:String, enabled:Boolean, onClick:()->Unit) {
    FilledTonalButton(onClick=onClick,enabled=enabled,modifier=Modifier.size(43.dp),
        shape=RoundedCornerShape(12.dp),contentPadding=PaddingValues(0.dp),
        colors=ButtonDefaults.filledTonalButtonColors(containerColor=Color(0xFF284352),
            contentColor=Color.White)) {Text(text,fontSize=20.sp,fontWeight=FontWeight.Bold)}
}

@Composable
private fun ActionTile(label:String,enabled:Boolean,modifier:Modifier=Modifier,onClick:()->Unit) {
    Button(onClick=onClick,enabled=enabled,modifier=modifier.height(37.dp),
        shape=RoundedCornerShape(11.dp),contentPadding=PaddingValues(horizontal=4.dp,vertical=1.dp),
        colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFB2DAD1),
            contentColor=Color(0xFF132C38))) {
        Text(label,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}

private fun expeditionTileName(tile:Int)=when(tile){
    0->"空地";1->"树林";2->"矿石";3->"围墙";4->"木地板";5->"宝箱";6->"撤离门";7->"营地";8->"水域";else->"地图边缘"
}

/** Breadth first search only through actually passable terrain; blocked objectives stop adjacent. */
internal fun expeditionNextStep(s:ExpeditionState,goalX:Int,goalY:Int):Pair<Int,Int>? {
    if(!s.inBounds(goalX,goalY))return null
    val starts=s.index(s.x,s.y)
    val queue=java.util.ArrayDeque<Int>()
    val parent=IntArray(s.width*s.height){-1}
    parent[starts]=starts
    queue.add(starts)
    val goals = if(s.cell(goalX,goalY) in listOf(0,4,6,7)) setOf(s.index(goalX,goalY))
        else listOf(goalX+1 to goalY,goalX-1 to goalY,goalX to goalY+1,goalX to goalY-1)
            .filter{(x,y)->s.cell(x,y) in listOf(0,4,6,7)}
            .map{(x,y)->s.index(x,y)}.toSet()
    var found=-1
    while(queue.isNotEmpty()) {
        val at=queue.removeFirst()
        if(at in goals){found=at;break}
        val x=at%s.width;val y=at/s.width
        listOf(x+1 to y,x-1 to y,x to y+1,x to y-1).forEach{(nx,ny)->
            if(!s.inBounds(nx,ny))return@forEach
            val ix=s.index(nx,ny)
            if(parent[ix]!=-1||s.cell(nx,ny) !in listOf(0,4,6,7) ||
                s.mobs.any{it.hp>0 && it.x==nx && it.y==ny})return@forEach
            parent[ix]=at
            queue.add(ix)
        }
    }
    if(found<0||found==starts)return null
    var step=found
    while(parent[step]!=starts && parent[step]!=step) step=parent[step]
    return step%s.width to step/s.width
}

@Composable
private fun ExpeditionMap(
    world:ExpeditionState,selected:Pair<Int,Int>,onSelect:(Int,Int)->Unit,
) {
    val night=world.mode==ExpeditionMode.RELIC_RUN
    Surface(
        shape=RoundedCornerShape(22.dp),
        color=Color(0xFF101E29),
        border=BorderStroke(1.dp,Color.White.copy(alpha=.15f)),
        shadowElevation=9.dp,
    ) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(world.width.toFloat()/world.height)
            .pointerInput(world.width,world.height,onSelect) {
                detectTapGestures { tap ->
                    val tile= min(size.width/world.width.toFloat(),size.height/world.height.toFloat())
                    val offsetX=(size.width-tile*world.width)/2f
                    val offsetY=(size.height-tile*world.height)/2f
                    val tx=((tap.x-offsetX)/tile).toInt()
                    val ty=((tap.y-offsetY)/tile).toInt()
                    if(world.inBounds(tx,ty))onSelect(tx,ty)
                }
            }) {
            val t=min(size.width/world.width.toFloat(),size.height/world.height.toFloat())
            val ox=(size.width-t*world.width)/2f
            val oy=(size.height-t*world.height)/2f
            fun center(x:Int,y:Int)=Offset(ox+(x+.5f)*t,oy+(y+.5f)*t)
            for(y in 0 until world.height)for(x in 0 until world.width) {
                val tile=world.cell(x,y)
                val p=Offset(ox+x*t,oy+y*t)
                val bg=when(tile) {
                    0-> if(night)Color(0xFF334049) else if((x+y)%3==0)Color(0xFF547A59) else Color(0xFF496F51)
                    1->Color(0xFF2A6249);2->Color(0xFF676F77);3-> if(night)Color(0xFF212C39) else Color(0xFF78594D)
                    4->Color(0xFFB3986C);5->Color(0xFF7A5F3A);6->Color(0xFF286A63)
                    7->Color(0xFFB58F6C);else->Color(0xFF24526B)
                }
                drawRect(bg,topLeft=p,size=Size(t+.5f,t+.5f))
                drawRect(Color.Black.copy(alpha=.09f),topLeft=p,size=Size(t,t),style=Stroke(.6f))
                when(tile) {
                    1 -> {
                        drawCircle(Color(0xFF174831),radius=t*.32f,center=center(x,y))
                        drawCircle(Color(0xFF76B279),radius=t*.19f,center=center(x,y)+Offset(-t*.07f,-t*.11f))
                    }
                    2 -> drawRoundRect(Color(0xFFA9B7C2),topLeft=p+Offset(t*.19f,t*.25f),
                        size=Size(t*.63f,t*.52f),cornerRadius=androidx.compose.ui.geometry.CornerRadius(t*.13f))
                    3 -> drawRect(Color(0xFFCCAE8A),topLeft=p+Offset(t*.07f,t*.11f),
                        size=Size(t*.86f,t*.74f))
                    5 -> {
                        drawRoundRect(Color(0xFFF4C866),topLeft=p+Offset(t*.16f,t*.23f),
                            size=Size(t*.68f,t*.57f),cornerRadius=androidx.compose.ui.geometry.CornerRadius(t*.08f))
                        drawRect(Color(0xFF5E3B33),topLeft=p+Offset(t*.44f,t*.22f),size=Size(t*.13f,t*.59f))
                    }
                    6 -> {
                        drawCircle(Color(0xFF8AFFD2).copy(alpha=.3f),radius=t*.43f,center=center(x,y))
                        drawCircle(Color(0xFFADFFE7),radius=t*.24f,center=center(x,y),style=Stroke(3f))
                    }
                    7 -> drawCircle(Color(0xFFFFE1A0),radius=t*.29f,center=center(x,y))
                }
                if(x==selected.first && y==selected.second)drawRect(
                    Color(0xFFFFF3B4),topLeft=p+Offset(1f,1f),size=Size(t-2f,t-2f),style=Stroke(3f))
            }
            world.mobs.filter{it.hp>0}.forEach { mob->
                val p=center(mob.x,mob.y)
                drawCircle(Color(0xFF472B42),radius=t*.35f,center=p)
                drawCircle(Color(0xFFFF92A5),radius=t*.11f,center=p+Offset(-t*.12f,-t*.05f))
                drawCircle(Color(0xFFFF92A5),radius=t*.11f,center=p+Offset(t*.12f,-t*.05f))
            }
            world.friends.filter{it.hp>0}.forEachIndexed { i,friend->
                val p=center(friend.x,friend.y)
                drawCircle(Color(0xFF98DBFF),radius=t*.34f,center=p)
                drawCircle(Color(0xFF35506F),radius=t*.16f,center=p+Offset(0f,t*.03f))
                drawCircle(Color.White,radius=t*.08f,center=p+Offset(t*.11f,-t*.12f))
            }
            val player=center(world.x,world.y)
            drawCircle(Color.Black.copy(alpha=.35f),radius=t*.40f,
                center=player+Offset(2f,3f))
            drawCircle(Color(0xFFFFE7B8),radius=t*.34f,center=player)
            drawCircle(Color(0xFF5E6D9A),radius=t*.16f,center=player+Offset(0f,t*.08f))
            drawCircle(Color.White,radius=t*.07f,center=player+Offset(t*.12f,-t*.09f))
        }
    }
}
