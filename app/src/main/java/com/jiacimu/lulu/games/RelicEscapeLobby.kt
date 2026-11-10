package com.jiacimu.lulu.games

import android.content.Context

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
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
    startingWeapon:String,
    contract:RelicContract,
    onContract:(RelicContract)->Unit,
    onKits:(Int)->Unit,
    onStartingWeapon:(String)->Unit,
    onDifficulty:(RelicDifficulty)->Unit,
    onInvite:(String)->Unit,
    onRemove:(String)->Unit,
    onStart:()->Unit,
    onContinue:()->Unit,
    onAbandon:()->Unit,
    onBuyKit:()->Unit,
    onUpgradeWeapon:()->Unit,
    onBuyBow:()->Unit,
    onUpgradeSatchel:()->Unit,
    onSellLoot:(String)->Unit,
) {
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    // Use the same avatar profile as chat and Moments rather than a placeholder pawn.
    val context=LocalContext.current
    val playerProfile=remember(context) {
        context.getSharedPreferences("lulu_user_profile",Context.MODE_PRIVATE)
    }
    val playerAvatarUri=remember(playerProfile) { playerProfile.getString("avatar_uri",null) }
    val playerAvatarText=remember(playerProfile) {
        playerProfile.getString("avatar_text","我").orEmpty().ifBlank{"我"}.take(2)
    }
    val live=snapshot.run?.phase in setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT,RelicPhase.DOWNED)
    var picker by remember { mutableStateOf(false) }
    var leaveConfirm by remember { mutableStateOf(false) }
    var shopExpanded by remember { mutableStateOf(false) }
    var vaultExpanded by remember { mutableStateOf(false) }


    var prepExpanded by remember { mutableStateOf(false) }
    val partyPanel: @Composable () -> Unit = {
        RelicLobbyPanel {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("冒险者集结",color=Color.White,modifier=Modifier.weight(1f),
                            fontSize=18.sp,fontWeight=FontWeight.Black)
                        Text("你 + "+accepted.size+"/3 位伙伴",color=campMint,fontSize=12.sp)
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        GameLobbyPartySlot(playerAvatarUri,playerAvatarText,"你 · 队长",true,campGold)
                        accepted.forEach { id ->
                            val character=characters[id]
                            GameLobbyPartySlot(character?.avatarUri,
                                character?.displayName?.take(1) ?: "伴",
                                character?.displayName ?: "同行伙伴",false,campMint,
                                allowRemove=!live,onRemove={onRemove(id)})
                        }
                        if(!live && accepted.size<3) GameLobbyInviteSlot(campMint,inviting==null) {
                            picker=true
                        }
                    }
                    if(inviting!=null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth(),color=campMint)
                        Text("正在等待角色本人回应邀请…",color=campMint,fontSize=12.sp)
                    }
                    if(inviteStatus.isNotBlank())Text(inviteStatus,color=campMint,fontSize=12.sp)
                    Text("队友有自己的选择与行动。邀请需要一次模型调用，拒绝不会强行入队。",
                        color=campMuted,fontSize=10.sp)
                }
    }
    val launchButton: @Composable () -> Unit = {
        Button(onClick=onStart,modifier=Modifier.fillMaxWidth().height(62.dp),
            enabled=inviting==null,shape=RoundedCornerShape(15.dp),
            colors=ButtonDefaults.buttonColors(containerColor=campGold,contentColor=campInk)) {
            Text("⚔ 出发摸金 · 第"+(snapshot.successes+1).coerceAtMost(5)+"层",
                fontSize=19.sp,fontWeight=FontWeight.Black)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(campInk)) {
        val landscape=maxWidth>maxHeight
        GameLobbyBackdrop(island=false,modifier=Modifier.matchParentSize())
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal=18.dp,vertical=14.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max=1040.dp).fillMaxWidth(),
                verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("✦ 迷雾摸金",color=Color.White,fontSize=31.sp,
                            fontWeight=FontWeight.Black)
                        Text("遗迹探索 · 寻回珍宝 · 带着队友安全撤离",
                            color=campMuted,fontSize=13.sp)
                    }
                    Surface(color=Color(0xD71B2E3B),shape=RoundedCornerShape(14.dp),
                        border=BorderStroke(1.dp,campGold.copy(alpha=.42f))) {
                        Column(Modifier.padding(horizontal=14.dp,vertical=9.dp)) {
                            Text("◇ "+snapshot.coins+" 铜币",color=campGold,
                                fontSize=15.sp,fontWeight=FontWeight.Bold)
                            Text("永久珍藏 "+snapshot.vault.size+" 件",
                                color=campMuted,fontSize=11.sp)
                        }
                    }
                }
                if(error.isNotBlank())Text(error,color=Color(0xFFFFB5B5),fontSize=12.sp)
                if(landscape) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp),
                        verticalAlignment=Alignment.Top) {
                        Column(Modifier.weight(1.40f),
                            verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Spacer(Modifier.height(108.dp))
                            partyPanel()
                        }
                        Column(Modifier.weight(1f),
                            verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            RelicLobbyPanel {
                                Text("✦ 本次出发简报",color=campGold,fontSize=20.sp,
                                    fontWeight=FontWeight.Black)
                                Text(if(live)"尚有未结束的探索，可返回营地续玩"
                                    else "前往第"+(snapshot.successes+1).coerceAtMost(5)+"层遗迹",
                                    color=Color.White,fontSize=13.sp)
                                Text("难度 · "+difficulty.label+"    治疗药剂 · "+
                                    kits.coerceIn(0,snapshot.kits),
                                    color=campMuted,fontSize=12.sp)
                                Text("本次委托 · "+contract.label,
                                    color=campGold,fontSize=12.sp,fontWeight=FontWeight.Bold)
                                Text(contract.condition,color=campMuted,fontSize=11.sp)
                                Text("武器 · "+RelicArsenal.label(startingWeapon),
                                    color=campMuted,fontSize=11.sp)
                                if(!live) TextButton(onClick={prepExpanded=!prepExpanded}) {
                                    Text(if(prepExpanded)"收起战前整备 −" else "配置装备 / 委托 / 难度 ＋",
                                        color=campMint,fontSize=12.sp)
                                }
                            }
                            if(!live) launchButton()
                            else Button(onClick=onContinue,modifier=Modifier.fillMaxWidth().height(55.dp),
                                colors=ButtonDefaults.buttonColors(containerColor=campGold,
                                    contentColor=campInk)) { Text("▶ 继续上次探险",fontWeight=FontWeight.Bold) }
                        }
                    }
                } else {
                    GameLobbyStage(island=false,eyebrow="EXPEDITION BASE CAMP",
                        title=if(live)"遗迹正在等你归队" else "下一件传说珍宝，就在迷雾深处",
                        subtitle="组建队伍 · 战术探索 · 带着真正的宝藏归来",
                        modifier=Modifier.fillMaxWidth().height(202.dp))
                    partyPanel()
                    if(!live)launchButton()
                    if(!live)TextButton(onClick={prepExpanded=!prepExpanded}) {
                        Text(if(prepExpanded)"收起战前整备 −" else "装备 / 难度 / 委托整备 ＋",
                            color=campMint,fontSize=12.sp)
                    }
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
                    if(prepExpanded) {
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
                        Text("✦ 选择本局探险委托",color=Color.White,fontSize=17.sp,
                            fontWeight=FontWeight.Black)
                        Text("带着目标出发。完成委托并成功撤离，才会结算额外铜币。",
                            color=campMuted,fontSize=11.sp)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                            RelicContract.values().forEach { choice ->
                                FilterChip(selected=contract==choice,onClick={onContract(choice)},
                                    label={Text(choice.label)},
                                    colors=FilterChipDefaults.filterChipColors(
                                        selectedContainerColor=campGold,selectedLabelColor=campInk,
                                        labelColor=campMuted))
                            }
                        }
                        Text(contract.condition+" · "+
                            (if(contract.bonus>0)"额外获得 ◇"+contract.bonus+" 铜币"
                             else "不设奖励，可自由探索"),
                            color=campGold,fontSize=12.sp,fontWeight=FontWeight.Bold)
                    }
                    RelicLobbyPanel {
                        Text("我的出发装备",color=Color.White,fontSize=17.sp,
                            fontWeight=FontWeight.Black)
                        Text("武器强化 · "+(if(snapshot.weapon==2)"已强化（短剑伤害4）" else "未强化（短剑伤害2）"),
                            color=campGold,fontSize=12.sp)
                        Text("选择本局出发武器",color=campMuted,fontSize=12.sp)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            snapshot.purchasedWeapons.sorted().forEach { id ->
                                FilterChip(
                                    selected=startingWeapon==id,
                                    onClick={onStartingWeapon(id)},
                                    label={Text(RelicArsenal.label(id)+" · "+RelicArsenal.description(id))},
                                    colors=FilterChipDefaults.filterChipColors(
                                        selectedContainerColor=campGold,selectedLabelColor=campInk,
                                        labelColor=campMuted),
                                )
                            }
                        }
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
                    }
                    }
                }

                RelicLobbyPanel {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("营地商店",color=Color.White,fontWeight=FontWeight.Black,
                            fontSize=17.sp,modifier=Modifier.weight(1f))
                        TextButton(onClick={shopExpanded=!shopExpanded}) {
                            Text(if(shopExpanded)"收起 −" else "打开商店 ＋",color=campGold,fontSize=11.sp)
                        }
                    }
                    if(shopExpanded) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                        OutlinedButton(onClick=onBuyKit,enabled=!live && snapshot.coins>=70) {
                            Text("药剂 · 70",color=campGold)
                        }
                        OutlinedButton(onClick=onUpgradeWeapon,
                            enabled=!live && snapshot.weapon==1 && snapshot.coins>=350) {
                            Text("武器强化 + 长矛 · 350",color=campGold)
                        }
                        OutlinedButton(onClick=onBuyBow,
                            enabled=!live && "bow" !in snapshot.purchasedWeapons &&
                                snapshot.coins>=650) {
                            Text("购买弩弓 · 650",color=campGold)
                        }
                        RelicCampUpgrades.satchelCost(snapshot.satchelLevel)?.let { cost ->
                            OutlinedButton(onClick=onUpgradeSatchel,
                                enabled=!live && snapshot.coins>=cost) {
                                Text("背包扩容 · "+cost,color=campGold)
                            }
                        }
                    }
                    Text("带出的宝物可换铜币，商店只出售确定的装备和补给。",
                        color=campMuted,fontSize=11.sp)
                    }
                }

                RelicLobbyPanel {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("永久藏宝库 · "+snapshot.vault.size+"件",
                            color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Black,
                            modifier=Modifier.weight(1f))
                        TextButton(onClick={vaultExpanded=!vaultExpanded}) {
                            Text(if(vaultExpanded)"收起 −" else "查看珍藏 ＋",
                                color=campGold,fontSize=11.sp)
                        }
                    }
                    Text("图鉴已收录 "+snapshot.discovered.size+"/"+RelicCollectibles.names.size+" 件",
                        color=campMint,fontSize=11.sp)
                    if(vaultExpanded) {
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
                    Text("出售实物不会抹掉永久图鉴记录。",color=campMuted,fontSize=11.sp)
                    }
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
