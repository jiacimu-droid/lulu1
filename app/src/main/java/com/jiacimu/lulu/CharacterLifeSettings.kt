package com.jiacimu.lulu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.CharacterLifeStore
import com.jiacimu.lulu.data.CharacterProfileSchema
import com.jiacimu.lulu.data.MigratedDomainStores

@Composable
internal fun CharacterLifeSettings(characterId: String) {
    val states by CharacterLifeStore.states.collectAsState()
    val root = remember(states, characterId) { CharacterLifeStore.state(characterId) }
    var editing by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf(false) }
    val name = MigratedDomainStores.characters.get(characterId).displayName
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("人格与行为", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("原有角色设定是基础。这里只补充你确定的部分；经历、当前心情与后来形成的习惯由角色运行记录。", style = MaterialTheme.typography.bodySmall)
        if (name in setOf("江渡", "江都")) OutlinedButton(onClick = { preset = true }) { Text("填入江渡设定") }
        CharacterProfileSchema.fields.groupBy { it.group }.forEach { (group, fields) ->
            Text(group, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            fields.forEach { field ->
                val value = root.optJSONObject("profile")?.optString(field.key).orEmpty()
                Column(Modifier.fillMaxWidth().clickable { editing = field.key; draft = value }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(field.label, fontWeight = FontWeight.Medium)
                    Text(value.ifBlank { if (field.key == "interests") "从真实经历中自由形成" else "未限定 · 点击编辑" },
                        maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
        root.optJSONObject("intention")?.let { intention ->
            Text("正在在意的事", fontWeight = FontWeight.Bold)
            Text(intention.optString("aim")); Text(intention.optString("motive"))
            val outcomes = intention.optJSONArray("outcomes")
            if (outcomes != null) for (i in maxOf(0, outcomes.length() - 3) until outcomes.length()) {
                val outcome = outcomes.getJSONObject(i)
                Text("${if (outcome.optBoolean("success")) "已执行" else "未完成"} · ${outcome.optString("summary")}")
            }
            TextButton(onClick = { CharacterLifeStore.stopIntention(characterId) }) { Text("结束这件事") }
        }
    }
    editing?.let { key ->
        val field = CharacterProfileSchema.fields.first { it.key == key }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(field.label) },
            text = { OutlinedTextField(draft, { draft = it }, placeholder = { Text(field.hint) }, minLines = 5, maxLines = 10, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton({ CharacterLifeStore.setProfile(characterId, key, draft); editing = null }) { Text("保存") } },
            dismissButton = { TextButton({ editing = null }) { Text("取消") } })
    }
    if (preset) AlertDialog(onDismissRequest = { preset = false }, title = { Text("填入江渡设定") },
        text = { Text("整理你本次描述的安全感、理性共情、审美、幽默与亲密关系。只填空白项目，保留已写内容，兴趣由他从真实经历中形成。") },
        confirmButton = { TextButton({
            CharacterProfileSchema.jiangDu.forEach { (key, value) -> if (CharacterLifeStore.state(characterId).optJSONObject("profile")?.optString(key).isNullOrBlank()) CharacterLifeStore.setProfile(characterId, key, value) }
            preset = false
        }) { Text("填入") } }, dismissButton = { TextButton({ preset = false }) { Text("取消") } })
}
