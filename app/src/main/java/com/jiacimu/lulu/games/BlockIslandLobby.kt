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

@Composable
private fun BlockIslandLobby(onStart: (List<String>) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
    val mint = Color(0xFFB7F2CE)
    val dim = Color(0xFFB6CDBF)
    val panel = Color(0xED183C3E)


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
                    GameLobbyPartySlot(playerAvatar,playerName,"你 · 岛主",true,mint)
                    selected.forEach { id ->
                        characters[id]?.let { c ->
                            GameLobbyPartySlot(c.avatarUri,c.displayName.take(1),c.displayName,
                                false,mint,allowRemove=inviting==null,
                                onRemove={selected=selected-id})
                        }
                    }
                    if(selected.size<3) GameLobbyInviteSlot(mint,inviting==null) {
                        choosing=!choosing
                    }
                }
                Text("＋ 邀请由角色本人决定是否接受（需要一次模型调用）；游戏内可语音交谈、一起行动。",
                    color = dim, fontSize = 11.sp, lineHeight = 16.sp)
                if (choosing) {
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
                if (invitationResult.isNotBlank()) {
                    Text(invitationResult, color = mint, fontSize = 11.sp, lineHeight = 16.sp)
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
                Text("✦ 本次冒险简报",fontSize=20.sp,fontWeight=FontWeight.Black,color=mint)
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
                val nextChallenges=IslandChallengeBoard.challenges.filter {
                    savedIsland==null || it.id !in savedIsland.awardedChallenges
                }.take(3)
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
                Text("生存 · 探索 · 建造家园，完成挑战会在世界里获得真实物资。",
                    color=dim,fontSize=11.sp,lineHeight=16.sp)
            }
        }
    }
    val launchButton: @Composable () -> Unit = {
        Button(onClick={onStart(selected.take(3))},enabled=inviting==null,
            modifier=Modifier.fillMaxWidth().height(62.dp),
            shape=RoundedCornerShape(15.dp),
            colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFF7BF65),
                contentColor=Color(0xFF3A2B18))) {
            Text(if(existingIsland)"▶ 继续我的海岛冒险" else "▶ 开始第一天的探险",
                fontWeight=FontWeight.Black,fontSize=19.sp)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        GameLobbyBackdrop(island=true,modifier=Modifier.matchParentSize())
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal=18.dp,vertical=14.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("方块小岛",fontSize=31.sp,fontWeight=FontWeight.Black,color=Color.White)
                Text("海岛生存 · 自由建造 · 与喜欢的人一起冒险",
                    fontSize=13.sp,color=dim)
            }
            Surface(color=Color(0xD328534A),shape=RoundedCornerShape(12.dp),
                border=BorderStroke(1.dp,mint.copy(alpha=.35f))) {
                Text(if(existingIsland)"✦ 家园已保存" else "✦ 全新旅程",
                    modifier=Modifier.padding(horizontal=12.dp,vertical=8.dp),
                    color=mint,fontSize=11.sp)
            }
        }
            if(landscape) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp),
                    verticalAlignment=Alignment.Top) {
                    Column(Modifier.weight(1.48f),
                        verticalArrangement=Arrangement.spacedBy(9.dp)) {
                        Spacer(Modifier.height(110.dp))
                        rosterPanel()
                    }
                    Column(Modifier.weight(1f),
                        verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        missionPanel()
                        launchButton()
                        Text("可独自出发 · 已接受邀请的伙伴会随你一起进入真实世界",
                            color=dim,fontSize=11.sp)
                    }
                }
            } else {
                GameLobbyStage(island=true,
                    eyebrow=if(existingIsland)"CONTINUE YOUR WORLD" else "YOUR FIRST ADVENTURE",
                    title=if(existingIsland)"欢迎回家，岛主" else "从一座小岛，建起我们的世界",
                    subtitle="探索未知海岸 · 盖一间小屋 · 认识新的伙伴",
                    modifier=Modifier.fillMaxWidth().height(202.dp))
                launchButton()
                rosterPanel()
                missionPanel()
            }
        }
    }
}
