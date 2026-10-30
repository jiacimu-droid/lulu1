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
    var editing by remember(characterId) { mutableStateOf<String?>(null) }
    var draft by remember(characterId) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var dialogueGenerating by remember(characterId) { mutableStateOf(false) }
    var dialogueGenerationError by remember(characterId) { mutableStateOf("") }
    // A one-time, evidence-constrained fill of *only* speech habits and interests.
    // Existing user-authored fields are never replaced. Unlike the former
    // ten-field button, this is tied to stable setting evidence, not guesses.
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val autofillPrefs = remember(app) {
        app.getSharedPreferences("lulu_persona_user_requested_autofill_v1", android.content.Context.MODE_PRIVATE)
    }
    var autofillPending by remember(characterId) { mutableStateOf(false) }
    var autofillResult by remember(characterId) { mutableStateOf("") }
    LaunchedEffect(characterId, seedPersona, seedIdentity) {
        val profile = CharacterLifeStore.state(characterId).optJSONObject("profile")
        val missingFields = listOf("speechHabits", "interests")
            .filter { profile?.optString(it).isNullOrBlank() }
        val key = "review:$characterId:${seedPersona.hashCode()}:${seedIdentity.hashCode()}"
        if (missingFields.isEmpty() || seedPersona.isBlank() || autofillPrefs.getBoolean(key, false)) return@LaunchedEffect
        // Mark BEFORE dispatch so navigating back never starts another paid request.
        autofillPrefs.edit().putBoolean(key, true).apply()
        autofillPending = true
        try {
            // Growth is adaptive and evidence-backed, not part of user-editable
            // original persona. Never silently copy learned habits into seed data.
            val response = LuluAiServices.gateway.generate(
                characterId = characterId,
                facts = """用户原始人格：${seedPersona.take(2300)}
身份事实：${seedIdentity.take(900)}
要整理的字段：${missingFields.joinToString("、")}""",
                instruction = """
                    仅从用户原始角色设定提取明确的初始偏好；后天成长留在适应层，不得回写原始资料。只输出 {"speechHabits":"...","interests":"..."}。
                    speechHabits 用自然的个人语言习惯与小癖好描述，不生成固定台词；颜文字和图片表情包允许按已有角色风格自然使用，不要求每回合卖萌或发图。没有证据就不要发明口头禅。
                    interests 只写角色确有的兴趣／倾向，区分曾经接触和确实喜欢；若没有明确兴趣依据，保持空字符串，不编造书名、爱好、往事。
                    不修改原始人格，不给角色添加虚构职业、成长史或经历，不写心理报告。每项至多 170 字。
                """.trimIndent(),
                source = "角色稳定表达与兴趣的首次资料整理",
                title = "人格字段一次性整理",
                maxTokens = 360,
                contextMode = com.jiacimu.lulu.ai.CompanionContextMode.Isolated,
            ).getOrNull()?.text?.let(ModelStructuredOutput::objectOrNull)
            var saved = 0
            missingFields.forEach { field ->
                val proposed = response?.optString(field).orEmpty().trim().take(200)
                if (proposed.isNotBlank() &&
                    CharacterLifeStore.state(characterId).optJSONObject("profile")?.optString(field).isNullOrBlank()) {
                    CharacterLifeStore.setProfile(characterId, field, proposed)
                    saved++
                }
            }
            autofillResult = if (saved > 0) "已根据原有设定整理 $saved 项，可随时修改" else "没有可靠依据的兴趣保持未设定"
        } finally {
            autofillPending = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("稳定倾向", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold)
        if (autofillPending) Text("正在整理已知语言习惯与兴趣…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (autofillResult.isNotBlank()) Text(autofillResult,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        CharacterProfileSchema.groupedFields().forEach { (group, fields) ->
            var expanded by remember(characterId, group) { mutableStateOf(group == "人物内核" || group == "呈现与表达") }
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(group, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) fields.forEach { field ->
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
                        if (field.key == "interests") "尚未形成"
                        else "未设定"
                    }, maxLines = 3,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (field.key == "dialogueExamples") {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !dialogueGenerating, onClick = {
                            scope.launch {
                                dialogueGenerating = true
                                dialogueGenerationError = ""
                                try {
                                    val profile = CharacterLifeStore.state(characterId)
                                        .optJSONObject("profile")
                                    val currentPersona = com.jiacimu.lulu.data.MigratedDomainStores
                                        .characters.get(characterId).persona
                                    val material = buildString {
                                        appendLine("角色本人设定：")
                                        appendLine(currentPersona.ifBlank { seedPersona }.take(2300))
                                        val roleIdentity = com.jiacimu.lulu.data.CharacterIdentityStore
                                            .get(characterId).ifBlank { seedIdentity }
                                        appendLine("明确身份设定：${roleIdentity.take(600)}")
                                        val userAgreement = com.jiacimu.lulu.data.CharacterSocialRelationship
                                            .agreement(characterId, "user")?.title.orEmpty()
                                        if (userAgreement.isNotBlank())
                                            appendLine("已设定的当前关系：${userAgreement.take(150)}")
                                        listOf("expression", "speechHabits", "social", "conflict", "care", "respect")
                                            .forEach { key ->
                                                profile?.optString(key)?.trim()
                                                    ?.takeIf(String::isNotBlank)
                                                    ?.let { appendLine("$key：${it.take(250)}") }
                                            }
                                    }
                                    val generated = LuluAiServices.gateway.generate(
                                        characterId = characterId,
                                        facts = material,
                                        instruction = """
                                            你是角色的对话样例编辑。只根据明确提供的个人设定，写三段短小的虚构即时通讯对话，供用户预览并决定是否保存。
                                            每段用【场景】一行标识不同情境（闲聊、真正有观点的回应、遇到小分歧或需要关心），每段包含“对方：”与“角色：”的自然往返；允许角色连续发两句或只回一句，也允许迟疑和不赞同。
                                            **真正区别**应出现在这个人会注意什么、如何接话、语气、分寸、幽默感与潜台词上；不要把所有人写成温柔客服、说明书、文学旁白或撒娇机器。
                                            选用很普通的小事做假设素材（例如随手聊一个见闻），让人物性格影响其自然反应。没有给出的经历、爱好、关系或称呼不可凭空设定；可以使用不含真实姓名的普通情景。
                                            不要分析人格，不要总结技巧，不要给角色写固定必用台词，不要假装这些对话真的发生过。不要输出 JSON/Markdown 代码块，只输出三段可编辑的示例文本，总体尽量精简。
                                        """.trimIndent(),
                                        source = "角色本人对话示例草稿",
                                        title = "按人设生成短对话草稿",
                                        maxTokens = 760,
                                        contextMode = com.jiacimu.lulu.ai.CompanionContextMode.Isolated,
                                    )
                                    val candidate = generated.getOrNull()?.text.orEmpty()
                                        .trim().take(1_500)
                                    if (candidate.isBlank()) {
                                        dialogueGenerationError = generated.exceptionOrNull()?.message
                                            ?.take(100) ?: "没有生成有效的示例，请重试或直接编辑"
                                    } else {
                                        draft = candidate
                                        editing = "dialogueExamples" // The user must explicitly save.
                                    }
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    dialogueGenerationError = error.message?.take(100)
                                        ?: "生成失败，可直接编辑对话示例"
                                } finally {
                                    dialogueGenerating = false
                                }
                            }
                        }) { Text(if (dialogueGenerating) "正在生成示例…" else "按本人设定生成三段对话") }
                    }
                    if (dialogueGenerationError.isNotBlank()) Text(dialogueGenerationError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    Text("示例只是可编辑的假设对话；审核保存后才影响这个角色，不会变成聊天历史。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (field.key == "speechHabits") {
                    Text("表情偏好",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CharacterExpressionPreferencesSettings(characterId)
                }
                HorizontalDivider()
            }
        }
        // Evidence-backed adaptive traits are rendered once by
        // CharacterDevelopmentSettings on the parent personality page.
        // CharacterLifeSettings owns only the editable stable profile.
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
    var showRelationships by remember { mutableStateOf(false) }
    var addressDraft by remember { mutableStateOf("") }
    LaunchedEffect(characterId) { CharacterAddressPreference.refresh(characterId) }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("称呼与社交资料", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = { showRelationships = true }) { Text("查看人物关系网络 →") }
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
        val relationship = com.jiacimu.lulu.data.CharacterRelationshipPreferences.snapshot(characterId)
        if (relationship.rejectedAddresses.isNotEmpty()) Text(
            "避免这样称呼你：" + relationship.rejectedAddresses.joinToString("、"),
            style = MaterialTheme.typography.bodyMedium)
        val userRemark = socialNames?.optString("userRemark").orEmpty()
        val selfNickname = socialNames?.optString("selfNickname").orEmpty()
        Text("给你的私人备注：" + userRemark.ifBlank { "还没有设置" }, style = MaterialTheme.typography.bodyMedium)
        if (userRemark.isNotBlank()) TextButton(onClick = { CharacterLifeStore.setSocialName(characterId, "userRemark", "") }) { Text("清除这条备注") }
        Text("自己的聊天网名：" + selfNickname.ifBlank { "沿用角色原名" }, style = MaterialTheme.typography.bodyMedium)
        if (selfNickname.isNotBlank()) TextButton(onClick = { CharacterLifeStore.setSocialName(characterId, "selfNickname", "") }) { Text("恢复原网名") }
    }
    if (showRelationships) DigitalWorldSocialGraphDialog(initialCharacterId = characterId, onDismiss = { showRelationships = false })
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
