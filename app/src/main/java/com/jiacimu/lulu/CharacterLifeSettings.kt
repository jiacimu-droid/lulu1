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
import com.jiacimu.lulu.data.ModelStructuredOutput
import com.jiacimu.lulu.ai.LuluAiServices
import kotlinx.coroutines.launch


@Composable
internal fun CharacterLifeSettings(
    characterId: String,
    seedPersona: String = "",
    seedIdentity: String = "",
) {
    val states by CharacterLifeStore.states.collectAsState()
    val root = remember(states, characterId) { CharacterLifeStore.state(characterId) }
    var editing by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var filling by remember(characterId) { mutableStateOf(false) }
    var fillFeedback by remember(characterId) { mutableStateOf("") }
    val fillable = CharacterProfileSchema.fields.filter { it.group != "兼容存档" }
    val missing = fillable.filter { root.optJSONObject("profile")?.optString(it.key).isNullOrBlank() }
    val grownRevision by CharacterDevelopmentStore.revisions.collectAsState()
    val grown = remember(characterId, states, grownRevision) {
        CharacterDevelopmentStore.active(characterId).takeLast(5)
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("人物人格", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold)
        if (missing.isNotEmpty() && seedPersona.isNotBlank()) {
            OutlinedButton(
                enabled = !filling,
                onClick = {
                    filling = true
                    fillFeedback = ""
                    scope.launch {
                        val questions = missing.joinToString("、") { "${it.key}=${it.label}" }
                        val answer = LuluAiServices.gateway.generate(
                            characterId = characterId,
                            facts = "这是用户保存的角色稳定核心简介：${seedPersona.take(1500)}\n身份背景：${seedIdentity.take(900)}\n仅需补全：$questions",
                            instruction = """
                                按已有用户人设，以简洁中文仅输出一个 JSON 对象，key 为指定字段英文键，每值 20～90 字。
                                只能提炼核心简介与身份已经明确表达的稳定倾向，不能编造新职业、履历、童年、喜爱过的作品、真实经历、已形成的关系、恐惧和未发生的事。
                                特别是 interests：没有明确提到具体爱好就留空；不把“有好奇心”当作已形成一个具体兴趣。
                                表达气质、语言习惯、边界和判断是原则，不写固定口头禅、强制行动脚本或跨角色通用客服腔。
                                仅输出键值 JSON，不要解释，不修改现有字段。
                            """.trimIndent(),
                            source = "基于已有核心人设的空白人格补齐",
                            title = "角色稳定人格整理",
                            maxTokens = 1_400,
                        ).getOrNull()
                        val proposal = answer?.text?.let(ModelStructuredOutput::objectOrNull)
                        var saved = 0
                        if (proposal != null) {
                            missing.forEach { field ->
                                val suggested = proposal.optString(field.key).trim().take(260)
                                if (suggested.isNotBlank() &&
                                    CharacterLifeStore.state(characterId).optJSONObject("profile")
                                        ?.optString(field.key).isNullOrBlank()) {
                                    CharacterLifeStore.setProfile(characterId, field.key, suggested)
                                    saved++
                                }
                            }
                        }
                        fillFeedback = if (saved > 0) "按已有设定补齐了 $saved 项；未明确的兴趣仍留待真实经历形成"
                            else "这次未生成可核实的补充，原有人格未被改动"
                        filling = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (filling) "正在依据已有设定整理" else "依据已有核心简介补齐空白（不覆盖已填项）") }
        }
        if (fillFeedback.isNotBlank()) Text(fillFeedback,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
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
                    Text(value.ifBlank {
                        if (field.key == "interests") "尚未从真实经历中形成具体的稳定兴趣"
                        else "未设定 · 可按已有简介整理"
                    }, maxLines = 3,
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
