package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * Entry room belongs to the game, not to the generic arcade character picker.
 * Selecting companions changes only the party; it never resets an existing island.
 * This screen does not impersonate an AI invitation/acceptance decision.
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
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    val existingIsland = remember(context) {
        context.getSharedPreferences("lulu_block_island_v2", 0)
            .getString("world", "").orEmpty().isNotBlank()
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

        Box(Modifier.fillMaxWidth().height(128.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val cell = size.height / 6f
                for (row in 0..5) for (col in 0..19) {
                    val shade = if ((row * 7 + col * 3) % 6 == 0) Color(0xFF3D8065) else Color(0xFF548C6F)
                    drawRect(shade, Offset(col * cell, row * cell), Size(cell - 1f, cell - 1f))
                }
                // Pixel-art island: local drawing only, no new game state.
                drawRect(Color(0xFF8DC2A1), Offset(size.width * .49f, size.height * .25f),
                    Size(size.width * .16f, size.height * .48f))
                drawRect(Color(0xFF775A41), Offset(size.width * .54f, size.height * .35f),
                    Size(size.width * .07f, size.height * .32f))
                drawRect(Color(0xFFB5A27B), Offset(size.width * .45f, size.height * .23f),
                    Size(size.width * .24f, size.height * .12f))
                drawCircle(Color(0xFFFFE3A7), radius = cell * .55f,
                    center = Offset(size.width * .26f, size.height * .35f))
            }
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text("岛屿生活 · 共建家园", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text("建营地、守夜、探索宝箱，与伙伴真正分工", color = Color.White, fontSize = 10.sp)
            }
        }

        Surface(shape = RoundedCornerShape(18.dp), color = panel,
            border = BorderStroke(1.dp, mint.copy(alpha = .28f))) {
            Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("出发队伍", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(13.dp), color = Color(0xFF32644F)) {
                        Column(Modifier.width(86.dp).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🧑", fontSize = 30.sp)
                            Text("你 · 岛主", color = Color.White, fontSize = 11.sp)
                        }
                    }
                    selected.forEach { id ->
                        characters[id]?.let { c ->
                            Column(Modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                LuluProfileAvatar(c.avatarUri, c.displayName.take(1), 45)
                                Text(c.displayName, color = mint, fontSize = 11.sp, maxLines = 1)
                                TextButton(onClick = { selected = selected - id },
                                    contentPadding = PaddingValues(0.dp), modifier = Modifier.height(26.dp)) {
                                    Text("移出", color = dim, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = { choosing = !choosing },
                        modifier = Modifier.height(56.dp),
                        border = BorderStroke(1.dp, mint)) {
                        Text("＋ 邀请", color = mint)
                    }
                }
                Text("可独自玩，也可选择最多3位角色一起进入。角色在队伍频道中自主决定说什么、做什么。",
                    color = dim, fontSize = 11.sp, lineHeight = 16.sp)
                if (choosing) {
                    if (candidates.isEmpty()) {
                        Text("还没有可选择的角色", color = dim)
                    } else {
                        candidates.forEach { c ->
                            val checked = c.characterId in selected
                            Surface(onClick = {
                                selected = if (checked) selected - c.characterId
                                    else if (selected.size < 3) selected + c.characterId else selected
                            }, color = if (checked) Color(0xFF315E51) else Color(0xFF204346),
                                shape = RoundedCornerShape(11.dp)) {
                                Row(Modifier.fillMaxWidth().padding(9.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    LuluProfileAvatar(c.avatarUri, c.displayName.take(1), 34)
                                    Spacer(Modifier.width(10.dp))
                                    Text(c.displayName, color = Color.White,
                                        modifier = Modifier.weight(1f), fontSize = 12.sp)
                                    Text(if (checked) "✓ 同行" else if (selected.size >= 3) "队伍已满" else "＋",
                                        color = mint, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        Surface(shape = RoundedCornerShape(16.dp), color = panel) {
            Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("本次冒险目标", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = mint)
                Text("① 收集木石并建立工作台    ② 制造工具与营地\n③ 抵御夜间怪物    ④ 探索补给与建设自己的家",
                    color = Color.White, lineHeight = 20.sp, fontSize = 12.sp)
                Text("小岛会自动存档；再次进入可继续，不必重做家园。", color = dim, fontSize = 10.sp)
            }
        }
        Button(onClick = { onStart(selected.filter { it in characters }.take(3)) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = mint, contentColor = Color(0xFF183B32))) {
            Text(if (existingIsland) "继续我的小岛" else "开始建岛",
                fontWeight = FontWeight.Black, fontSize = 16.sp)
        }
    }
}
