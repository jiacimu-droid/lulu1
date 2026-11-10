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

    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0D2730), Color(0xFF163D3B), Color(0xFF101F2C))))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("方块小岛", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White)
                Text("你的小岛，不止是采集材料", fontSize = 12.sp, color = dim)
            }
            Surface(color = Color(0xFF28564D), shape = RoundedCornerShape(12.dp)) {
                Text(if (existingIsland) "家园已保存" else "新的旅程",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = mint, fontSize = 11.sp)
            }
        }

        // The world itself sells the fantasy before the player reads any settings.
        GameLobbyStage(
            island = true,
            title = if (existingIsland) "欢迎回家，岛主" else "从一座小岛，建起我们的世界",
            eyebrow = if (existingIsland) "CONTINUE YOUR WORLD" else "YOUR FIRST ADVENTURE",
            subtitle = "探索未知海岸 · 盖一间小屋 · 认识新的伙伴",
            modifier = Modifier.fillMaxWidth().height(224.dp),
        )
        Button(
            onClick = { onStart(selected.take(3)) },
            enabled = inviting == null,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(15.dp),
            colors = ButtonDefaults.buttonColors(containerColor = mint, contentColor = Color(0xFF183B32)),
        ) {
            Text(if (existingIsland) "▶ 继续我的岛屿冒险" else "▶ 开始第一天的冒险",
                fontWeight = FontWeight.Black, fontSize = 17.sp)
        }
        Text("不想邀请伙伴？一个人也能出发。进度会自动保存。",
            color = dim, fontSize = 11.sp)

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
        Surface(shape = RoundedCornerShape(16.dp), color = panel) {
            Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("✦ 冒险手册 · 三条成长路线", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = mint)
                Text("01  生存挑战   ·   做工具、搭营地、守住三夜", color = Color.White, fontSize = 12.sp)
                Text("02  探险发现   ·   寻宝、解锁工具、搭桥", color = Color.White, fontSize = 12.sp)
                Text("03  温暖家园   ·   结识动物、盖家、点亮夜灯", color = Color.White, fontSize = 12.sp)
                Text("这些目标都读取真实岛屿存档，完成后还能自由创造。", color = dim, fontSize = 10.sp)
            }
        }
    }
}
