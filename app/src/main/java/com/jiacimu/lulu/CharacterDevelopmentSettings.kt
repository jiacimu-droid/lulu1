package com.jiacimu.lulu

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.*
import com.jiacimu.lulu.design.LuluColors

/** Shows the exact effective adaptations read by CharacterRuntime, grouped with personality. */
@Composable
internal fun CharacterDevelopmentSettings(characterId: String) {
    val revision by CharacterDevelopmentStore.revisions.collectAsState()
    val reflectionRevision by CharacterDevelopmentRuntime.revisions.collectAsState()
    val evidenceRevision by SharedExperienceTimeline.revisions.collectAsState()
    val settings by MigratedDomainStores.characters.settings.collectAsState()
    val profiles by CharacterLifeStore.states.collectAsState()
    val current = remember(characterId, revision, evidenceRevision, settings, profiles) {
        CharacterDevelopmentStore.active(characterId)
    }
    val history = remember(characterId, revision) { CharacterDevelopmentStore.history(characterId) }
    var historyOpen by remember(characterId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("经历形成的倾向", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        val lastReflection = remember(characterId, reflectionRevision) {
            CharacterDevelopmentRuntime.lastStatus(characterId)
        }
        if (lastReflection.isNotBlank())
            Text(lastReflection, color = LuluColors.Muted, fontSize = 12.sp)
        if (current.isEmpty()) Text("还没有形成持续的变化", color = LuluColors.Muted, fontSize = 13.sp)
        current.groupBy { it.kind }.forEach { (kind, records) ->
            Text(kind.label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            records.forEach { record ->
                var expanded by remember(record.id) { mutableStateOf(false) }
                Text(record.content, fontSize = 14.sp, lineHeight = 23.sp)
                Text(if (record.maturity == DevelopmentMaturity.Established) "较稳定" else "逐渐形成中",
                    color = LuluColors.Muted, fontSize = 11.sp)
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起变化依据" else "查看变化依据") }
                if (expanded) {
                    CharacterEvidenceDetails(characterId, record.evidence.keys, showInfluences = false)
                    history.filter { it.kind == record.kind && it.slot == record.slot && it.id != record.id }
                        .sortedByDescending { it.version }.forEach { previous ->
                            Text("${recordTime(previous.createdAt)} · 过去的理解：${previous.content}",
                                fontSize = 12.sp, color = LuluColors.Muted)
                        }
                    TextButton(onClick = { CharacterDevelopmentStore.retire(characterId, record.id) }) {
                        Text("撤回这项变化", color = MaterialTheme.colorScheme.error)
                    }
                }
                HorizontalDivider(color = LuluColors.Border)
            }
        }
        val inactive = history.filter { row -> current.none { it.id == row.id } }
        if (inactive.isNotEmpty()) {
            TextButton(onClick = { historyOpen = !historyOpen }) { Text(if (historyOpen) "收起历史变化" else "历史变化") }
            if (historyOpen) inactive.sortedByDescending { it.createdAt }.forEach { record ->
                Text("${record.kind.label} · ${recordTime(record.createdAt)}", fontSize = 11.sp, color = LuluColors.Muted)
                Text(record.content, fontSize = 13.sp)
            }
        }
    }
}
