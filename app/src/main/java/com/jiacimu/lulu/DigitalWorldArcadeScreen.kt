package com.jiacimu.lulu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.CharacterSettings
import com.jiacimu.lulu.games.LuluGames
import com.jiacimu.lulu.games.LuluGamesApp

/** Real game entry from a physical arcade, with only residents actually present as partners. */
@Composable
internal fun DigitalWorldArcadeScreen(
    gameId: String,
    residents: List<CharacterSettings>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val store = LuluGames.store
    val gameState by store.state.collectAsState()
    var started by rememberSaveable(gameId) { mutableStateOf(false) }
    val maxParty = if (gameId in setOf("block_island", "relic_escape", "yacht_dice", "turtle_soup")) 3 else 1
    var selectedIds by rememberSaveable(gameId) {
        mutableStateOf(residents.firstOrNull()?.characterId?.let(::listOf).orEmpty())
    }
    val memoryMatch = gameId == "memory_match"
    val soloAllowed = gameId in setOf("memory_match", "yacht_dice", "block_island", "relic_escape")
    val gameTitle = when (gameId) {
        "memory_match" -> "记忆配对"
        "yacht_dice" -> "快艇骰子"
        "block_island" -> "方块小岛"
        "relic_escape" -> "迷雾摸金"
        "turtle_soup" -> "海龟汤"
        else -> "游戏"
    }
    val partner = residents.firstOrNull { it.characterId == selectedIds.firstOrNull() }
    BackHandler(onBack = onBack)
    if (com.jiacimu.lulu.games.GameInvitationCatalog.title(gameId) == null) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("这个版本未开放该游戏")
            TextButton(onClick = onBack) { Text("返回游戏馆") }
        }
        return
    }
    if (started) {
        Box(modifier.fillMaxSize()) {
            LuluGamesApp(onBack = onBack, initialGameId = gameId, returnToCaller = true)
        }
        return
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                TextButton(onClick = onBack) { Text("返回机台") }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(gameTitle, style = MaterialTheme.typography.headlineMedium)
            Text(if (memoryMatch) "选择对手，或独自挑战最少轮次" else "选择同伴", style = MaterialTheme.typography.titleMedium)
            if (soloAllowed) {
                FilterChip(selected = selectedIds.isEmpty(),
                    onClick = { selectedIds = emptyList() }, label = { Text("自己玩") })
            }
            residents.forEach { character ->
                val checked = character.characterId in selectedIds
                FilterChip(
                    selected = checked,
                    onClick = {
                        selectedIds = if (checked) selectedIds - character.characterId
                            else if (selectedIds.size < maxParty) selectedIds + character.characterId
                            else selectedIds
                    },
                    label = { Text(character.displayName) },
                )
            }
            if (maxParty > 1) Text("最多邀请${maxParty}位在场伙伴，同行角色会真实采集、战斗和搜索。",
                style = MaterialTheme.typography.bodySmall)
            if (memoryMatch && residents.isEmpty()) Text("当前没有在场角色，也可以独自挑战自己的最佳轮次。")
            val hasUnfinished = memoryMatch && gameState.memoryMatch.moves > 0 && !gameState.memoryMatch.finished
            if (hasUnfinished) Text("已有一局未完成的配对；开始新局会替换它。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hasUnfinished && gameState.memoryMatch.opponentId == selectedIds.firstOrNull()) {
                OutlinedButton(onClick = { partner?.let { com.jiacimu.lulu.data.CompanionOnlineStore.recordActivity(it.characterId) }; started = true }, shape = RoundedCornerShape(16.dp)) { Text("继续当前对局") }
            }
            Button(
                enabled = soloAllowed || partner != null,
                onClick = {
                    if (memoryMatch) store.startMemoryMatch(selectedIds.firstOrNull())
                    else store.selectCharacters(selectedIds)
                    selectedIds.forEach { com.jiacimu.lulu.data.CompanionOnlineStore.recordActivity(it) }
                    started = true
                },
                shape = RoundedCornerShape(16.dp),
            ) { Text("开始新局") }
        }
    }
}
