package com.jiacimu.lulu

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.CharacterExpressionPreferences
import com.jiacimu.lulu.data.CharacterLifeStore

@Composable
internal fun CharacterExpressionPreferencesSettings(characterId: String) {
    val states by CharacterLifeStore.states.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("kaomoji" to "颜文字", "stickers" to "表情包").forEach { (key, title) ->
            val selected = remember(states, characterId, key) {
                CharacterExpressionPreferences.selected(characterId, key)
            }
            Text(title, style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CharacterExpressionPreferences.choices.forEach { (value, label) ->
                    FilterChip(selected = selected == value,
                        onClick = { CharacterLifeStore.setExpressionPreference(characterId, key, value) },
                        label = { Text(label) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
