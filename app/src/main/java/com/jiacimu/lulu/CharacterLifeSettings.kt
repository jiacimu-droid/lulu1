package com.jiacimu.lulu

import com.jiacimu.lulu.design.LuluAlertDialog as AlertDialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.CharacterLifeStore
import com.jiacimu.lulu.data.CharacterAddressPreference
import com.jiacimu.lulu.data.CharacterProfileSchema
import com.jiacimu.lulu.data.CharacterDevelopmentStore
import com.jiacimu.lulu.data.DevelopmentMaturity


@Composable
internal fun CharacterLifeSettings(characterId: String) {
    val states by CharacterLifeStore.states.collectAsState()
    val root = remember(states, characterId) { CharacterLifeStore.state(characterId) }
    var editing by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    val grownRevision by CharacterDevelopmentStore.revisions.collectAsState()
    val grown = remember(characterId, states, grownRevision) {
        CharacterDevelopmentStore.active(characterId).takeLast(5)
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("人物人格", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold)
        CharacterProfileSchema.groupedFields().forEach { (group, fields) ->
            Text(group, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            fields.forEach { field ->
                val value = root.optJSONObject("profile")?.optString(field.key).orEmpty()
                Column(Modifier.fillMaxWidth().clickable { editing = field.key; draft = value }
                    .padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(field.label, fontWeight = FontWeight.Medium)
                        Text("修改", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text(value.ifBlank { "未设定" }, maxLines = 3,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
        if (grown.isNotEmpty()) {
            Text("相处中逐渐形成", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            grown.forEach { record ->
                val maturity = if (record.maturity == DevelopmentMaturity.Established) "较稳定" else "形成中"
                Text(record.kind.label + " · " + maturity,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(record.content, style = MaterialTheme.typography.bodyMedium)
            }
        }
        // Short-lived mental states belong to 此刻, not the stable persona.
    }
    editing?.let { key ->
        val field = CharacterProfileSchema.fields.first { it.key == key }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(field.label) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(draft, { draft = it }, placeholder = { Text("写下这个角色的设定") },
                        minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible())
                }
            },
            confirmButton = { TextButton({ CharacterLifeStore.setProfile(characterId, key, draft); editing = null }) { Text("保存") } },
            dismissButton = { TextButton({ editing = null }) { Text("取消") } })
    }

}

/** Editable social names live in 资料, not in the character's stable人格. */
@Composable
internal fun CharacterSocialNamesSettings(characterId: String) {
    val states by CharacterLifeStore.states.collectAsState()
    val root = remember(states, characterId) { CharacterLifeStore.state(characterId) }
    var addressEditing by remember { mutableStateOf(false) }
    var addressDraft by remember { mutableStateOf("") }
    LaunchedEffect(characterId) { CharacterAddressPreference.refresh(characterId) }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("称呼与社交资料", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        val socialNames = root.optJSONObject("socialNames")
        val preferredAddress = socialNames?.optString("preferredAddress").orEmpty()
        val addressManuallySet = socialNames?.optBoolean("preferredAddressManual", false) == true
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("平时怎么称呼你：" + preferredAddress.ifBlank { "尚未确认" },
                    style = MaterialTheme.typography.bodyMedium)
 
            }
            TextButton(onClick = { addressDraft = preferredAddress; addressEditing = true }) {
                Text("编辑")
            }
        }
        if (addressManuallySet) TextButton(onClick = {
            CharacterLifeStore.followObservedPreferredAddress(characterId)
            CharacterAddressPreference.refresh(characterId)
        }) { Text("恢复自动学习") }
        val userRemark = socialNames?.optString("userRemark").orEmpty()
        val selfNickname = socialNames?.optString("selfNickname").orEmpty()
        Text("给你的私人备注：" + userRemark.ifBlank { "还没有设置" }, style = MaterialTheme.typography.bodyMedium)
        if (userRemark.isNotBlank()) TextButton(onClick = { CharacterLifeStore.setSocialName(characterId, "userRemark", "") }) { Text("清除这条备注") }
        Text("自己的聊天网名：" + selfNickname.ifBlank { "沿用角色原名" }, style = MaterialTheme.typography.bodyMedium)
        if (selfNickname.isNotBlank()) TextButton(onClick = { CharacterLifeStore.setSocialName(characterId, "selfNickname", "") }) { Text("恢复原网名") }
    }
    if (addressEditing) {
        AlertDialog(onDismissRequest = { addressEditing = false },
            title = { Text("角色平时怎样称呼你") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = addressDraft,
                        onValueChange = { addressDraft = it.take(24) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("常用称呼") })
                }
            },
            confirmButton = { TextButton(onClick = {
                CharacterLifeStore.setSocialName(characterId, "preferredAddress", addressDraft)
                addressEditing = false
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { addressEditing = false }) { Text("取消") } })
    }

}
