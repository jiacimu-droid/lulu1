package com.jiacimu.lulu.games

import android.content.Context
import android.content.Intent

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

// Restrained gallery palette: warm ivory metal, cool slate and pale jade.
private val campGold=Color(0xFFE3D1B2)
private val campMint=Color(0xFFB5CECB)
private val campMuted=Color(0xFFBDC6CB)
private val campInk=Color(0xFF151B24)

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
    onFeatureLoot:(String)->Unit,
) {
    val compactLobby = androidx.compose.ui.platform.LocalConfiguration.current.let {
        it.screenWidthDp > it.screenHeightDp
    }
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
    var shopExpanded by remember { mutableStateOf(true) }
    var vaultExpanded by remember { mutableStateOf(true) }
    var selectedTreasureId by remember(snapshot.vault.map { it.id }) {
        mutableStateOf(snapshot.featuredLootId ?: snapshot.vault.lastOrNull()?.id)
    }


    var prepExpanded by remember { mutableStateOf(true) }
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
                        GameLobbyPartySlot(playerAvatarUri,playerAvatarText,"你 · 队长",true,campGold,
                            compact=compactLobby)
                        accepted.forEach { id ->
                            val character=characters[id]
                            GameLobbyPartySlot(character?.avatarUri,
                                character?.displayName?.take(1) ?: "伴",
                                character?.displayName ?: "同行伙伴",false,campMint,
                                allowRemove=!live,onRemove={onRemove(id)},compact=compactLobby)
                        }
                        if(!live && accepted.size<3) GameLobbyInviteSlot(campMint,inviting==null,compact=compactLobby) {
                            picker=true
                        }
                    }
                    if(inviting!=null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth(),color=campMint)
                        Text("正在等待角色本人回应邀请…",color=campMint,fontSize=12.sp)
                    }
                    if(inviteStatus.isNotBlank())Text(inviteStatus,color=campMint,fontSize=12.sp)
                    if(!compactLobby)Text("伙伴由自己决定是否加入；邀请需角色确认。",
                        color=campMuted,fontSize=10.sp)
                }
    }
    val launchButton: @Composable () -> Unit = {
        Button(onClick=onStart,modifier=Modifier.fillMaxWidth().height(62.dp),
            enabled=inviting==null,shape=RoundedCornerShape(15.dp),
            colors=ButtonDefaults.buttonColors(containerColor=campGold,contentColor=campInk)) {
            Text("出发寻宝 · 第"+(snapshot.successes+1).coerceAtMost(5)+"层",
                fontSize=19.sp,fontWeight=FontWeight.Black)
        }
    }

    // The title screen is a fixed game viewport. Long-form controls live in
    // separate sheets, never below the launch button on a scrolling homepage.
    var detailPage by rememberSaveable { mutableStateOf("") }
    BoxWithConstraints(Modifier.fillMaxSize().background(campInk)) {
        val landscape = maxWidth > maxHeight
        GameLobbyBackdrop(island=false,modifier=Modifier.matchParentSize())
        val heading: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("迷雾摸金",color=Color(0xFFF1EAE0),
                        fontSize=if(landscape)26.sp else 30.sp,fontWeight=FontWeight.Black)
                    Text(if(live)"遗迹还有一局未结束" else "寻找值得珍藏的美丽宝物",
                        color=campMuted,fontSize=12.sp,maxLines=1)
                }
                Surface(color=Color(0xD71B2E3B),shape=RoundedCornerShape(12.dp),
                    border=BorderStroke(1.dp,campGold.copy(alpha=.4f))) {
                    Text("◇ "+snapshot.coins+"  ·  ✦ "+snapshot.vault.size,
                        modifier=Modifier.padding(horizontal=10.dp,vertical=8.dp),
                        color=campGold,fontSize=12.sp)
                }
            }
        }
        val tools: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("整备" to "prep","商店" to "shop","藏宝馆" to "vault")
                    .forEach { (label,page) ->
                        OutlinedButton(onClick={detailPage=page},
                            modifier=Modifier.weight(1f),
                            contentPadding=PaddingValues(horizontal=4.dp,vertical=0.dp),
                            border=BorderStroke(1.dp,campGold.copy(alpha=.36f))) {
                            Text(label,color=campGold,fontSize=12.sp,maxLines=1)
                        }
                    }
            }
        }
        val go: @Composable () -> Unit = {
            if(live) {
                Button(onClick=onContinue,modifier=Modifier.fillMaxWidth().height(58.dp),
                    colors=ButtonDefaults.buttonColors(containerColor=campGold,contentColor=campInk)) {
                    Text("▶ 继续本局探险",fontWeight=FontWeight.Black,fontSize=17.sp)
                }
            } else launchButton()
        }
        if(landscape) {
            Row(Modifier.fillMaxSize().padding(horizontal=16.dp,vertical=10.dp),
                horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    heading()
                    Spacer(Modifier.weight(1f))
                    Text(if(live)"返回遗迹，带珍宝安全撤离" else "真正的珍宝，等你亲手带回来",
                        color=Color.White,fontSize=13.sp,fontWeight=FontWeight.Bold)
                    if(live)TextButton(onClick={leaveConfirm=true},
                        contentPadding=PaddingValues(0.dp)) {
                        Text("放弃本局",color=campMuted,fontSize=11.sp)
                    }
                }
                Column(Modifier.weight(1.15f).fillMaxHeight(),
                    verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    partyPanel()
                    Spacer(Modifier.weight(1f))
                    tools()
                    go()
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal=16.dp,vertical=12.dp),
                horizontalAlignment=Alignment.CenterHorizontally,
                verticalArrangement=Arrangement.spacedBy(8.dp)) {
                heading()
                if(error.isNotBlank())Text(error,color=Color(0xFFFFB5B5),
                    fontSize=11.sp,maxLines=2)
                Spacer(Modifier.weight(1f))
                Text(if(live)"探险尚未结束，继续本局或返回整备" else "组建队伍 · 挑选装备 · 出发寻宝",
                    color=Color.White,fontSize=12.sp,fontWeight=FontWeight.Bold)
                partyPanel()
                tools()
                go()
                if(live)TextButton(onClick={leaveConfirm=true},
                    modifier=Modifier.height(30.dp)) {
                    Text("放弃本局",color=campMuted,fontSize=12.sp)
                }
            }
        }
    }
    if(detailPage.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest={detailPage=""},containerColor=campInk) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.78f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal=16.dp,vertical=8.dp),
                verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(when(detailPage) {
                        "prep"->"出发整备"
                        "shop"->"营地商店"
                        else->"我的藏宝馆"
                    },color=campGold,fontSize=21.sp,fontWeight=FontWeight.Black,
                        modifier=Modifier.weight(1f))
                    TextButton(onClick={detailPage=""}) {
                        Text("完成",color=campMint)
                    }
                }
                when(detailPage) {
                    "prep" -> {

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
                    "shop" -> {
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
                    }
                    "vault" -> {
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
                    val exhibit=snapshot.vault.firstOrNull { it.id==selectedTreasureId }
                        ?: snapshot.vault.firstOrNull { it.id==snapshot.featuredLootId }
                        ?: snapshot.vault.lastOrNull()
                    if(exhibit!=null) {
                        RelicTreasureGalleryCard(exhibit,
                            featured=exhibit.id==snapshot.featuredLootId)
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                            OutlinedButton(onClick={onFeatureLoot(exhibit.id)},
                                enabled=exhibit.id!=snapshot.featuredLootId,
                                modifier=Modifier.weight(1f)) {
                                Text(if(exhibit.id==snapshot.featuredLootId)
                                    "✓ 已展出" else "✦ 放到展柜",color=campGold,
                                    fontSize=11.sp)
                            }
                            OutlinedButton(onClick={
                                val intent=Intent(Intent.ACTION_SEND).apply {
                                    type="text/plain"
                                    putExtra(Intent.EXTRA_TEXT,
                                        RelicTreasureGallery.shareText(exhibit))
                                }
                                context.startActivity(Intent.createChooser(intent,"分享我的珍宝"))
                            },modifier=Modifier.weight(1f)) {
                                Text("♡ 炫耀珍宝",color=campMint,fontSize=11.sp)
                            }
                        }
                    }
                    if(vaultExpanded) {
                    if(snapshot.vault.isEmpty())Text(
                        "珍宝馆还空着。去寻找一件让你真正心动的漂亮宝物吧。",
                        color=campMuted,fontSize=12.sp)
                    snapshot.vault.reversed().forEach { item ->
                        Surface(onClick={selectedTreasureId=item.id},
                            color=if(item.id==exhibit?.id) Color(0xFF303A42)
                                else Color(0xFF212B35),
                            shape=RoundedCornerShape(12.dp),
                            border=BorderStroke(1.dp,
                                if(item.id==exhibit?.id) campGold.copy(alpha=.65f)
                                else campMint.copy(alpha=.22f))) {
                            Row(Modifier.fillMaxWidth()
                                .padding(horizontal=8.dp,vertical=5.dp),
                                verticalAlignment=Alignment.CenterVertically,
                                horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text((if(item.id==snapshot.featuredLootId)"✦ " else "✧ ")+
                                        item.name+" · "+item.tier.label,
                                        color=campGold,fontSize=12.sp,
                                        fontWeight=FontWeight.Bold)
                                    Text(RelicTreasureGallery.identity(item.name),
                                        color=campMuted,fontSize=10.sp,maxLines=1)
                                }
                                TextButton(onClick={onSellLoot(item.id)},enabled=!live) {
                                    Text("出售",color=campMint,fontSize=11.sp)
                                }
                            }
                        }
                    }
                    Text("点击藏品可切换大幅鉴赏图。出售实物不会抹掉永久图鉴，但会撤下展柜中的同一件实物。",
                        color=campMuted,fontSize=11.sp)
                    }
                }
                    }
                }
                Spacer(Modifier.height(14.dp))
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
        modifier=Modifier.fillMaxWidth(),color=Color(0xED202934),
        shape=RoundedCornerShape(15.dp),
        border=BorderStroke(1.dp,campGold.copy(alpha=.18f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement=Arrangement.spacedBy(9.dp),content=content)
    }
}
