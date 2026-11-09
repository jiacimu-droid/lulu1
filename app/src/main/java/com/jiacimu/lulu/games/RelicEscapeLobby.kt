package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores

private val campGold=Color(0xFFFFD894)
private val campMint=Color(0xFF91F1D3)
private val campMuted=Color(0xFFC6CEDD)
private val campInk=Color(0xFF0C1522)

@Composable
internal fun RelicEscapeLobby(
    snapshot:RelicSnapshot,
    accepted:List<String>,
    inviting:String?,
    inviteStatus:String,
    error:String,
    kits:Int,
    difficulty:RelicDifficulty,
    onKits:(Int)->Unit,
    onDifficulty:(RelicDifficulty)->Unit,
    onInvite:(String)->Unit,
    onRemove:(String)->Unit,
    onStart:()->Unit,
    onContinue:()->Unit,
    onAbandon:()->Unit,
    onBuyKit:()->Unit,
    onUpgradeWeapon:()->Unit,
    onUpgradeSatchel:()->Unit,
    onSellLoot:(String)->Unit,
) {
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val live=snapshot.run?.phase in setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT,RelicPhase.DOWNED)
    var picker by remember { mutableStateOf(false) }
    var leaveConfirm by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(campInk)) {
        RelicCampBackdrop()
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(
            Color(0xE609101C),Color(0xDF111925),Color(0xF109111D),
        ))))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal=18.dp, vertical=14.dp),
            horizontalAlignment=Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max=1040.dp).fillMaxWidth(),
                verticalArrangement=Arrangement.spacedBy(13.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("✦ 迷雾摸金",color=Color.White,fontSize=27.sp,
                            fontWeight=FontWeight.Black)
                        Text("探险营地 · 找伙伴、整备装备，再一起出发",
                            color=campMuted,fontSize=12.sp)
                    }
                    Surface(color=Color(0xCE1B2E3B),
                        shape=RoundedCornerShape(14.dp),
                        border=BorderStroke(1.dp,campGold.copy(alpha=.36f))) {
                        Column(Modifier.padding(horizontal=13.dp,vertical=8.dp)) {
                            Text("◇ "+snapshot.coins+" 铜币",color=campGold,fontWeight=FontWeight.Bold)
                            Text("珍藏 "+snapshot.vault.size+" 件",color=campMuted,fontSize=11.sp)
                        }
                    }
                }
                if(error.isNotBlank())Text(error,color=Color(0xFFFFB5B5),fontSize=12.sp)
                RelicLobbyPanel {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("我的探险小队",color=Color.White,modifier=Modifier.weight(1f),
                            fontSize=18.sp,fontWeight=FontWeight.Black)
                        Text("你 + "+accepted.size+"/3 位伙伴",color=campMint,fontSize=12.sp)
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(15.dp)) {
                        Column(horizontalAlignment=Alignment.CenterHorizontally) {
                            GameCharacterPawn(avatarUri=null,fallback="你",moving=false,facingX=1f,
                                player=true,coat=Color(0xFF5E7D91))
                            Text("你 · 队长",color=campGold,fontSize=11.sp,fontWeight=FontWeight.Bold)
                        }
                        accepted.forEach { id ->
                            val character=characters[id]
                            Column(horizontalAlignment=Alignment.CenterHorizontally) {
                                GameCharacterPawn(
                                    avatarUri=character?.avatarUri,fallback=character?.displayName?.take(1) ?: "伴",
                                    moving=false,facingX=1f,coat=Color(0xFF5E7D64))
                                Text(character?.displayName ?: "同行伙伴",color=Color.White,fontSize=11.sp)
                                TextButton(onClick={onRemove(id)},enabled=!live,
                                    contentPadding=PaddingValues(horizontal=4.dp,vertical=0.dp),
                                    modifier=Modifier.height(28.dp)) {
                                    Text("移出",color=campMuted,fontSize=10.sp)
                                }
                            }
                        }
                        if(!live && accepted.size<3) {
                            OutlinedButton(onClick={picker=true},
                                enabled=inviting==null,
                                modifier=Modifier.height(64.dp),
                                border=BorderStroke(1.dp,campMint.copy(alpha=.7f))) {
                                Text("+ 邀请伙伴",color=campMint,fontSize=12.sp)
                            }
                        }
                    }
                    if(inviting!=null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth(),color=campMint)
                        Text("正在等待角色本人回应邀请…",color=campMint,fontSize=12.sp)
                    }
                    if(inviteStatus.isNotBlank())Text(inviteStatus,color=campMint,fontSize=12.sp)
                    Text("邀请会交给角色本人决定；未答应的角色不会被自动加入。",
                        color=campMuted,fontSize=10.sp)
                }

                if(live) {
                    RelicLobbyPanel {
                        Text("有一局探险尚未结束",color=campGold,fontSize=18.sp,
                            fontWeight=FontWeight.Bold)
                        val run=snapshot.run
                        if(run!=null)Text(
                            "第"+run.level+"层 · "+run.difficulty.label+" · "+run.turns+"回合 · "+
                                (if(run.phase==RelicPhase.DOWNED)"你已倒地，可继续等待救援或结束本局" else "存档完整，可继续探索"),
                            color=campMuted,fontSize=12.sp)
                        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Button(onClick=onContinue,
                                colors=ButtonDefaults.buttonColors(containerColor=campGold,
                                    contentColor=campInk)) { Text("继续本局") }
                            OutlinedButton(onClick={leaveConfirm=true}) {
                                Text("放弃本局",color=campMuted)
                            }
                        }
                    }
                } else {
                    snapshot.run?.let { previous ->
                        if(previous.phase in setOf(RelicPhase.SUCCESS,RelicPhase.FAILURE)) {
                            RelicLobbyPanel {
                                Text(if(previous.phase==RelicPhase.SUCCESS)"上局撤离成功！" else "上局探险失败",
                                    color=campGold,fontWeight=FontWeight.Bold)
                                Text("宝物带出 "+previous.secured.size+" 件 · 遗失 "+
                                    previous.lost.size+" 件。永久仓库和升级仍然保留。",
                                    color=campMuted,fontSize=12.sp)
                            }
                        }
                    }
                    RelicLobbyPanel {
                        Text("选择难度",color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Black)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            RelicDifficulty.values().forEach { choice ->
                                FilterChip(selected=difficulty==choice,
                                    onClick={onDifficulty(choice)},
                                    label={Text(choice.label)},
                                    colors=FilterChipDefaults.filterChipColors(
                                        selectedContainerColor=campGold,
                                        selectedLabelColor=campInk,
                                        labelColor=campMuted))
                            }
                        }
                        Text(when(difficulty) {
                            RelicDifficulty.EASY->"适合熟悉操作 · 守卫更少、血量与伤害更低"
                            RelicDifficulty.NORMAL->"标准探索 · 正常数量与强度的守卫"
                            RelicDifficulty.HARD->"更危险的遗迹 · 守卫更多、更强，也更容易倒地"
                        },color=campMuted,fontSize=11.sp)
                    }
                    RelicLobbyPanel {
                        Text("我的出发装备",color=Color.White,fontSize=17.sp,
                            fontWeight=FontWeight.Black)
                        Text("武器 · "+(if(snapshot.weapon==2)"强化长枪（伤害4）" else "旧式短剑（伤害2）"),
                            color=campGold,fontSize=12.sp)
                        Text("背包 · "+(8+snapshot.satchelLevel*2)+"格（永久升级）",
                            color=campMint,fontSize=12.sp)
                        Row(verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                            Text("治疗药剂 "+kits.coerceIn(0,snapshot.kits)+" / 库存"+snapshot.kits,
                                color=Color.White,modifier=Modifier.weight(1f),fontSize=12.sp)
                            OutlinedButton(onClick={onKits((kits-1).coerceAtLeast(0))}) {
                                Text("−",color=campGold)
                            }
                            OutlinedButton(onClick={onKits((kits+1).coerceAtMost(minOf(3,snapshot.kits)))}) {
                                Text("+",color=campGold)
                            }
                        }
                        Text("每位接受邀请的伙伴自带1份救援药剂。背包装备与药剂会在出发时锁定。",
                            color=campMuted,fontSize=11.sp)
                        Button(onClick=onStart,modifier=Modifier.fillMaxWidth().height(48.dp),
                            enabled=inviting==null,
                            colors=ButtonDefaults.buttonColors(containerColor=campGold,
                                contentColor=campInk)) {
                            Text("✦ 开始探险 · 第"+(snapshot.successes+1).coerceAtMost(5)+"层",
                                fontWeight=FontWeight.Black)
                        }
                    }
                }

                RelicLobbyPanel {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("营地商店",color=Color.White,fontWeight=FontWeight.Black,
                            fontSize=17.sp,modifier=Modifier.weight(1f))
                        Text("只用游戏内铜币",color=campMuted,fontSize=11.sp)
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                        OutlinedButton(onClick=onBuyKit,enabled=!live && snapshot.coins>=70) {
                            Text("药剂 · 70",color=campGold)
                        }
                        OutlinedButton(onClick=onUpgradeWeapon,
                            enabled=!live && snapshot.weapon==1 && snapshot.coins>=350) {
                            Text("强化武器 · 350",color=campGold)
                        }
                        RelicCampUpgrades.satchelCost(snapshot.satchelLevel)?.let { cost ->
                            OutlinedButton(onClick=onUpgradeSatchel,
                                enabled=!live && snapshot.coins>=cost) {
                                Text("背包扩容 · "+cost,color=campGold)
                            }
                        }
                    }
                    Text("带出的宝物可卖换铜币。购买的是确定的装备和补给，不卖随机抽奖。",
                        color=campMuted,fontSize=11.sp)
                }

                RelicLobbyPanel {
                    Text("永久仓库 · "+snapshot.vault.size+"件",
                        color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Black)
                    if(snapshot.vault.isEmpty())Text("成功撤离后，这里会展示你带出的宝物。",
                        color=campMuted,fontSize=12.sp)
                    snapshot.vault.takeLast(12).reversed().forEach { item ->
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(item.tier.label+" · "+item.name+" · ◇"+item.value,
                                color=campGold,fontSize=11.sp,modifier=Modifier.weight(1f))
                            TextButton(onClick={onSellLoot(item.id)},enabled=!live) {
                                Text("出售",color=campMint,fontSize=11.sp)
                            }
                        }
                    }
                    Text("图鉴已发现 "+snapshot.discovered.size+"/"+RelicCollectibles.names.size+
                        " 件 · 卖出后图鉴记录仍在",color=campMuted,fontSize=11.sp)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if(picker)AlertDialog(
        onDismissRequest={picker=false},containerColor=Color(0xFF182938),
        title={Text("邀请谁一起摸金？",color=Color.White)},
        text={
            Column(Modifier.heightIn(max=290.dp).verticalScroll(rememberScrollState()),
                verticalArrangement=Arrangement.spacedBy(7.dp)) {
                if(characters.isEmpty())Text("还没有可邀请的角色",color=campMuted)
                characters.values.sortedBy{it.displayName}.filter{it.characterId !in accepted}
                    .forEach { character ->
                        TextButton(onClick={
                            picker=false
                            onInvite(character.characterId)
                        },modifier=Modifier.fillMaxWidth()) {
                            com.jiacimu.lulu.LuluProfileAvatar(
                                character.avatarUri,character.displayName.take(1),36)
                            Spacer(Modifier.width(10.dp))
                            Text(character.displayName,color=Color.White,
                                modifier=Modifier.weight(1f))
                            Text("邀请",color=campMint)
                        }
                    }
            }
        },
        confirmButton={TextButton(onClick={picker=false}) {Text("返回",color=campGold)}},
    )
    if(leaveConfirm)AlertDialog(
        onDismissRequest={leaveConfirm=false},containerColor=Color(0xFF182938),
        title={Text("确定放弃这局探险？",color=Color.White)},
        text={Text("本局未撤离的宝物会遗失，永久仓库、铜币和已购买升级不受影响。",
            color=campMuted)},
        confirmButton={TextButton(onClick={leaveConfirm=false;onAbandon()}) {
            Text("放弃并返回营地",color=Color(0xFFFFB0B5))
        }},
        dismissButton={TextButton(onClick={leaveConfirm=false}) {Text("继续保留",color=campMint)}},
    )
}

@Composable
private fun RelicLobbyPanel(content:@Composable ColumnScope.()->Unit) {
    Surface(
        modifier=Modifier.fillMaxWidth(),color=Color(0xED172836),
        shape=RoundedCornerShape(18.dp),
        border=BorderStroke(1.dp,campGold.copy(alpha=.26f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement=Arrangement.spacedBy(9.dp),content=content)
    }
}
