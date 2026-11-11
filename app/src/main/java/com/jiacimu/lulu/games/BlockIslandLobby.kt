package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.LuluProfileAvatar
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.launch

/**
 * Entry room belongs to the game, not to the generic arcade character picker.
 * Selecting companions changes only the party; it never resets an existing island.
 * New party members join only after their own model explicitly accepts the invitation.
 */
@Composable
internal fun BlockIslandEntry(store: LuluGameStore) {
    var playing by rememberSaveable { mutableStateOf(false) }
    if (playing) {
        BlockIslandScreen(store)
    } else {
        BlockIslandLobby(onStart = { companions ->
            store.selectCharacters(companions)
            playing = true
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockIslandLobby(onStart: (List<String>) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val compactLobby = androidx.compose.ui.platform.LocalConfiguration.current.let {
        it.screenWidthDp > it.screenHeightDp
    }
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val candidates = characters.values.sortedBy { it.displayName }
    val savedIsland = remember(context) {
        BlockIslandState.fromJson(
            context.getSharedPreferences("lulu_block_island_v2", 0)
                .getString("world", "").orEmpty()
        ) ?: ExpeditionState.fromJson(
            context.getSharedPreferences("lulu_expedition_homestead_v1", 0)
                .getString("world", "").orEmpty(),
            ExpeditionMode.HOMESTEAD,
        )?.let(BlockIslandState::fromLegacy)
    }
    // Returning to a saved home should not silently dismiss its previous companions.
    // Compose/Kotlin needs a single invariant state type: mixed inferred List implementations
    // made the delegate a projected MutableState and broke all subsequent list operations.
    var selected: List<String> by rememberSaveable {
        mutableStateOf<List<String>>(
            savedIsland?.world?.friends?.map { it.id } ?: emptyList()
        )
    }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var inviting by remember { mutableStateOf<String?>(null) }
    var invitationResult by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val existingIsland = savedIsland != null
    val playerProfile = remember(context) { context.getSharedPreferences("lulu_user_profile", 0) }
    val playerAvatar = remember(playerProfile) { playerProfile.getString("avatar_uri", null) }
    val playerName = remember(playerProfile) {
        playerProfile.getString("avatar_text", "我").orEmpty().ifBlank { "我" }.take(2)
    }
    // A soft coastal palette: sage, warm ivory and quiet deep foliage.
    // Keep the panels readable over any part of the actual island artwork.
    val mint = Color(0xFFD6E7CB)
    val dim = Color(0xFFCCD8CC)
    val panel = Color(0xE525403B)


    // Wide phones get a complete game screen: the island is the backdrop, the
    // selected players stand in the foreground and the real objective is on the right.
    // Portrait uses the same interactions with a readable stacked fallback.
    val rosterPanel: @Composable () -> Unit = {
        Surface(shape = RoundedCornerShape(18.dp), color = panel,
            border = BorderStroke(1.dp, mint.copy(alpha = .28f))) {
            Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("一起去冒险", color = Color.White, fontWeight = FontWeight.Black,
                        fontSize = 17.sp, modifier = Modifier.weight(1f))
                    Text("1 + ${selected.size} / 3", color = mint, fontSize = 11.sp)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement=Arrangement.spacedBy(10.dp),
                    verticalAlignment=Alignment.CenterVertically) {
                    GameLobbyPartySlot(playerAvatar,playerName,"你 · 岛主",true,mint,
                        compact=compactLobby)
                    selected.forEach { id ->
                        characters[id]?.let { c ->
                            GameLobbyPartySlot(c.avatarUri,c.displayName.take(1),c.displayName,
                                false,mint,allowRemove=inviting==null,
                                onRemove={selected=selected-id},compact=compactLobby)
                        }
                    }
                    if(selected.size<3) GameLobbyInviteSlot(mint,inviting==null,compact=compactLobby) {
                        choosing=!choosing
                    }
                }
                if(!compactLobby)Text("伙伴可自由决定是否同行；入岛后能共同冒险。",
                    color = dim, fontSize = 11.sp, lineHeight = 16.sp)
                
                if (invitationResult.isNotBlank()) {
                    Text(invitationResult, color = mint, fontSize = 11.sp, lineHeight = 16.sp,
                        maxLines=2)
                }
            }
        }
    }
    val missionPanel: @Composable () -> Unit = {
        Surface(shape = RoundedCornerShape(18.dp), color = panel,
            border = BorderStroke(1.dp, mint.copy(alpha=.42f))) {
            Column(Modifier.fillMaxWidth().padding(15.dp),
                verticalArrangement=Arrangement.spacedBy(9.dp)) {
                val total=IslandChallengeBoard.challenges.size
                val completed=savedIsland?.let { state ->
                    IslandChallengeBoard.challenges.count { it.id in state.awardedChallenges }
                } ?: 0
                Text("今天去哪里",fontSize=20.sp,fontWeight=FontWeight.Bold,color=Color(0xFFF2EED9))
                Text(if(existingIsland)"主线 · "+IslandMilestones.next(savedIsland!!)
                    else "主线 · 收集木石，搭建你的第一座工作台",
                    color=Color.White,fontWeight=FontWeight.Bold,fontSize=12.sp,
                    lineHeight=16.sp,maxLines=2)
                Text("冒险进度  $completed / $total",color=mint,fontSize=11.sp)
                Box(Modifier.fillMaxWidth().height(6.dp)
                    .background(Color(0x88466362),RoundedCornerShape(99.dp))) {
                    Box(Modifier.fillMaxWidth(completed.toFloat()/total.coerceAtLeast(1))
                        .height(6.dp)
                        .background(Color(0xFFF2C777),RoundedCornerShape(99.dp)))
                }
                // Two visible goals at once: enough direction, no checklist
                // wall competing with the landscape. The full quest journal
                // remains available inside the game.
                val nextChallenges=IslandChallengeBoard.challenges.filter {
                    savedIsland==null || it.id !in savedIsland.awardedChallenges
                }.take(2)
                if(nextChallenges.isEmpty()) {
                    Text("✦ 本岛全部挑战完成！继续自由建造，收集与伙伴的故事",
                        color=Color(0xFFFFE3A9),fontSize=12.sp)
                }
                nextChallenges.forEach { challenge ->
                    // Migrated saves may satisfy a challenge before its next
                    // valid turn awards it. Show truth, not a forced empty box.
                    val achieved=savedIsland?.let(challenge.completed) ?: false
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(if(achieved)"✓" else "□",color=if(achieved)mint else dim,
                            fontSize=17.sp,modifier=Modifier.width(26.dp))
                        Column(Modifier.weight(1f)) {
                            Text(challenge.title,color=Color.White,fontWeight=FontWeight.Bold,
                                fontSize=13.sp)
                            Text(challenge.rewardText,color=dim,fontSize=11.sp)
                        }
                    }
                }
                Text("生存、探险、建起喜欢的家。",
                    color=dim,fontSize=11.sp,lineHeight=16.sp)
            }
        }
    }
    val launchButton: @Composable () -> Unit = {
        Button(onClick={onStart(selected.take(3))},enabled=inviting==null,
            modifier=Modifier.fillMaxWidth().height(65.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(Brush.horizontalGradient(listOf(
                    Color(0xFFF3E7C8),Color(0xFFE2CFA9),Color(0xFFEBDFC0)))),
            shape=RoundedCornerShape(17.dp),
            elevation=ButtonDefaults.buttonElevation(defaultElevation=6.dp,
                pressedElevation=2.dp),
            colors=ButtonDefaults.buttonColors(containerColor=Color.Transparent,
                contentColor=Color(0xFF38483E),
                disabledContainerColor=Color(0xAA69625A))) {
            Text(if(existingIsland)"▶  继续海岛冒险" else "▶  开始第一天的探险",
                fontWeight=FontWeight.Black,fontSize=19.sp)
        }
    }
    // A single-screen game lobby in both orientations; additional detail is
    // shown on demand instead of turning the title screen into a vertical form.
    var goalsOpen by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        GameLobbyBackdrop(island=true,modifier=Modifier.matchParentSize())
        val header: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("方块小岛",fontSize=if(landscape)26.sp else 30.sp,
                        fontWeight=FontWeight.Black,color=Color(0xFFF6F2E6))
                    Text("一起探索，把这片小岛建成家",fontSize=12.sp,
                        color=dim,maxLines=1)
                }
                Surface(color=Color(0xC52D4D43),shape=RoundedCornerShape(12.dp),
                    border=BorderStroke(1.dp,mint.copy(alpha=.24f))) {
                    Text(if(existingIsland)"✦ 已保存" else "✦ 新旅程",
                        modifier=Modifier.padding(horizontal=10.dp,vertical=8.dp),
                        color=mint,fontSize=11.sp)
                }
            }
        }
        val goalsButton: @Composable () -> Unit = {
            OutlinedButton(onClick={goalsOpen=true},modifier=Modifier.fillMaxWidth(),
                border=BorderStroke(1.dp,mint.copy(alpha=.55f)),
                colors=ButtonDefaults.outlinedButtonColors(
                    containerColor=Color(0xB820403D))) {
                Text("▣ 查看冒险目标",color=Color.White,fontSize=13.sp)
            }
        }
        if(landscape) {
            Row(Modifier.fillMaxSize().padding(horizontal=16.dp,vertical=10.dp),
                horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    header()
                    Spacer(Modifier.weight(1f))
                    Text(if(existingIsland)"欢迎回家，岛主" else "从这片海岸，开始我们的故事",
                        color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Bold)
                }
                Column(Modifier.weight(1.15f).fillMaxHeight(),
                    verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    rosterPanel()
                    Spacer(Modifier.weight(1f))
                    goalsButton()
                    launchButton()
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal=16.dp,vertical=12.dp),
                verticalArrangement=Arrangement.spacedBy(8.dp)) {
                header()
                Spacer(Modifier.weight(1f))
                Text(if(existingIsland)"欢迎回家，岛主" else "从这片海岸，开始我们的故事",
                    color=Color.White,fontSize=16.sp,fontWeight=FontWeight.Black)
                rosterPanel()
                goalsButton()
                launchButton()
            }
        }
    }
    if(goalsOpen) {
        ModalBottomSheet(onDismissRequest={goalsOpen=false},
            containerColor=Color(0xFF1D3834)) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.7f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal=16.dp,vertical=8.dp)) {
                missionPanel()
                TextButton(onClick={goalsOpen=false},modifier=Modifier.align(Alignment.End)) {
                    Text("返回大厅",color=mint)
                }
            }
        }
    }
    if(choosing)AlertDialog(
        onDismissRequest={choosing=false},
        containerColor=Color(0xFF1D3834),
        title={Text("邀请伙伴一起登岛",color=Color.White)},
        text={
            Column(Modifier.heightIn(max=310.dp).verticalScroll(rememberScrollState()),
                verticalArrangement=Arrangement.spacedBy(7.dp)) {

                    if (candidates.isEmpty()) {
                        Text("还没有可选择的角色", color = dim)
                    } else {
                        candidates.forEach { c ->
                            val checked = c.characterId in selected
                            Surface(onClick = {
                                when {
                                    inviting != null -> Unit
                                    checked -> selected = selected - c.characterId
                                    selected.size >= 3 -> invitationResult = "队伍最多3位同行角色"
                                    else -> {
                                        val id = c.characterId
                                        inviting = id
                                        invitationResult = "正在邀请 " + c.displayName + "…"
                                        scope.launch {
                                            val result = IslandInvitation.ask(id, selected)
                                            if (inviting == id) {
                                                result.onSuccess { answer ->
                                                    invitationResult = c.displayName + "：" +
                                                        answer.speech.ifBlank {
                                                            if (answer.accepted) "我来啦" else "这次先不去了"
                                                        }
                                                    if (answer.accepted && id !in selected && selected.size < 3)
                                                        selected = selected + id
                                                }.onFailure { error ->
                                                    invitationResult = c.displayName + "还没有给出有效答复：" +
                                                        (error.message ?: "请检查游戏模型后重试")
                                                }
                                                inviting = null
                                            }
                                        }
                                    }
                                }
                            }, color = if (checked) Color(0xFF315E51) else Color(0xFF204346),
                                shape = RoundedCornerShape(11.dp)) {
                                Row(Modifier.fillMaxWidth().padding(9.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    LuluProfileAvatar(c.avatarUri, c.displayName.take(1), 34)
                                    Spacer(Modifier.width(10.dp))
                                    Text(c.displayName, color = Color.White,
                                        modifier = Modifier.weight(1f), fontSize = 12.sp)
                                    Text(if (checked) "✓ 同行" else if (inviting == c.characterId) "邀请中…" else if (selected.size >= 3) "队伍已满" else "＋ 邀请",
                                        color = mint, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                
            }
        },
        confirmButton={TextButton(onClick={choosing=false}) {
            Text("完成",color=mint)
        }},
    )
}
