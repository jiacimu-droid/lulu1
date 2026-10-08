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
    var partnerId by rememberSaveable(gameId) { mutableStateOf(residents.firstOrNull()?.characterId.orEmpty()) }
    var errorText by remember { mutableStateOf("") }
    val memoryMatch = gameId == "memory_match"
    val partner = residents.firstOrNull { it.characterId == partnerId }
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
            Text(if (memoryMatch) "记忆配对" else "深海回声", style = MaterialTheme.typography.headlineMedium)
            Text("一起玩", style = MaterialTheme.typography.titleMedium)
            if (!memoryMatch) {
                FilterChip(selected = partnerId.isBlank(), onClick = { partnerId = "" }, label = { Text("自己玩") })
            }
            residents.forEach { character ->
                FilterChip(
                    selected = character.characterId == partnerId,
                    onClick = { partnerId = character.characterId },
                    label = { Text(character.displayName) },
                )
            }
            if (memoryMatch && residents.isEmpty()) Text("这里暂时没有角色。记忆配对需要一位对手。")
            val hasUnfinished = memoryMatch && gameState.memoryMatch.moves > 0 && !gameState.memoryMatch.finished
            if (!memoryMatch && divePrefs.getLong("started_at", 0L) > 0L && !divePrefs.getBoolean("completed", false)) {
                Text("已有潜航进度；开始新潜航会替换它。已完成的游戏记录会保留。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (errorText.isNotBlank()) Text(errorText, color = MaterialTheme.colorScheme.error)
            if (hasUnfinished) Text("已有一局未完成的配对；开始新局会替换它。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (hasUnfinished && partner?.characterId == gameState.selectedCharacterId) {
                OutlinedButton(onClick = { partner?.let { com.jiacimu.lulu.data.CompanionOnlineStore.recordActivity(it.characterId) }; started = true }, shape = RoundedCornerShape(16.dp)) { Text("继续当前对局") }
            }
            Button(
                enabled = !memoryMatch || partner != null,
                onClick = {
                    if (memoryMatch || divePrefs.edit().clear().putLong("started_at", System.currentTimeMillis()).commit()) {
                        store.selectCharacters(partner?.let { listOf(it.characterId) }.orEmpty())
                        if (memoryMatch) store.resetMemoryMatch()
                        partner?.let { com.jiacimu.lulu.data.CompanionOnlineStore.recordActivity(it.characterId) }
                        started = true
                    } else errorText = "潜航进度保存失败，请重试"
                },
                shape = RoundedCornerShape(16.dp),
            ) { Text(if (memoryMatch) "开始新局" else "开始新潜航") }
        }
    }
}
