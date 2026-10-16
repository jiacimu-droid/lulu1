package com.jiacimu.lulu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.design.LuluColors

/** Navigation only: original stores, record IDs and editing/deletion remain authoritative. */
@Composable
fun CharacterMemoryFeatureScreen(
    onBack: () -> Unit,
    personalCharacterId: String? = null,
    startInCore: Boolean = false,
    startInRecords: Boolean = false,
    initialDiaryTitle: String? = null,
    initialSectionName: String? = null,
) {
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    var characterId by rememberSaveable(personalCharacterId) {
        mutableStateOf(personalCharacterId ?: characters.keys.firstOrNull() ?: "lulu")
    }
    var area by rememberSaveable(startInRecords, startInCore) { mutableIntStateOf(if (startInRecords) 1 else 0) }
    val navigation: @Composable () -> Unit = {
        CharacterSectionTabs(listOf("记住的事", "生活记录", "学习与成长"), area, onSelect = { area = it })
    }
    when (area) {
        0 -> MemoryFeatureScreen(onBack, personalCharacterId, startInCore,
            initialCharacterId = characterId, onCharacterSelected = { characterId = it }, areaNavigation = navigation)
        1 -> LexiconFeatureScreenV2(onBack, initialCharacterId = characterId,
            initialDiaryTitle = initialDiaryTitle, initialSectionName = initialSectionName,
            personal = personalCharacterId != null, onCharacterSelected = { characterId = it },
            areaNavigation = navigation, pageTitle = "记忆")
        else -> Scaffold(
            containerColor = LuluColors.Paper,
            topBar = {
                TopAppBar(title = { Text("记忆", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                    actions = {
                        if (personalCharacterId == null) {
                            var expanded by remember { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { expanded = true }) { Text(characters[characterId]?.displayName.orEmpty()) }
                                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                                    characters.values.forEach { character ->
                                        DropdownMenuItem(text = { Text(character.displayName) }, onClick = {
                                            characterId = character.characterId; expanded = false
                                        })
                                    }
                                }
                            }
                        }
                    })
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                navigation()
                LazyColumn(contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxSize()) {
                    item(key = characterId) { CharacterLearningSettings(characterId, showConfiguration = false) }
                }
            }
        }
    }
}
