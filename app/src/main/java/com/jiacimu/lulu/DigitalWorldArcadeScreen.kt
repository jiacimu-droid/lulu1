package com.jiacimu.lulu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    val divePrefs = remember(context) { context.getSharedPreferences("lulu_deep_sea_journey_v1", 0) }
    val store = LuluGames.store
    val gameState by store.state.collectAsState()
    var started by rememberSaveable(gameId) { mutableStateOf(false) }
    val maxParty = if (gameId in setOf("block_island", "relic_escape", "yacht_dice", "turtle_soup")) 3 else 1
    var selectedIds by rememberSaveable(gameId) {
        mutableStateOf(residents.firstOrNull()?.characterId?.let(::listOf).orEmpty())
    }
    var errorText by remember { mutableStateOf("") }
    val memoryMatch = gameId == "memory_match"
    val deepSea = gameId == "deep_sea_journey"
    val soloAllowed = deepSea || gameId in setOf("yacht_dice", "block_island", "relic_escape")
    val gameTitle = when (gameId) {
        "memory_match" -> "记忆配对"
        "yacht_dice" -> "快艇骰子"
        "block_island" -> "方块小岛"
        "relic_escape" -> "迷雾摸金"
        "turtle_soup" -> "海龟汤"
        else -> "深海回声"
    }
    val partner = residents.firstOrNull { it.characterId == selectedIds.firstOrNull() }
    BackHandler(onBack = onBack)
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
            Text("选择同伴", style = MaterialTheme.typography.titleMedium)
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
            if (memoryMatch && residents.isEmpty()) Text("这里暂时没有在场角色，记忆配对需要一位对手。")
            val hasUnfinished = memoryMatch && gameState.memoryMatch.moves > 0 && !gameState.memoryMatch.finished
            if (deepSea && divePrefs.getLong("started_at", 0L) > 0L && !divePrefs.getBoolean("completed", false)) {
                Text("已有潜航进度；开始新潜航会替换它。已完成的游戏记录会保留。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (errorText.isNotBlank()) Text(errorText, color = MaterialTheme.colorScheme.error)
            if (hasUnfinished) Text("已有一局未完成的配对；开始新局会替换它。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hasUnfinished && partner?.characterId == gameState.selectedCharacterId) {
                OutlinedButton(onClick = { partner?.let { com.jiacimu.lulu.data.CompanionOnlineStore.recordActivity(it.characterId) }; started = true }, shape = RoundedCornerShape(16.dp)) { Text("继续当前对局") }
            }
            Button(
                enabled = soloAllowed || partner != null,
                onClick = {
                    if (!deepSea || divePrefs.edit().clear().putLong("started_at", System.currentTimeMillis()).commit()) {
                        store.selectCharacters(selectedIds)
                        if (memoryMatch) store.resetMemoryMatch()
                        selectedIds.forEach { com.jiacimu.lulu.data.CompanionOnlineStore.recordActivity(it) }
                        started = true
                    } else errorText = "潜航进度保存失败，请重试"
                },
                shape = RoundedCornerShape(16.dp),
            ) { Text(if (deepSea) "开始新潜航" else "开始新局") }
        }
    }
}
