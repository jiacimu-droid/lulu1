package com.jiacimu.lulu

import com.jiacimu.lulu.design.LuluAlertDialog as AlertDialog

import android.app.TimePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.CharacterIdentityStore
import com.jiacimu.lulu.data.CharacterLifeForm
import com.jiacimu.lulu.data.CharacterRecordReset
import com.jiacimu.lulu.data.CharacterVoicePreferenceStore
import com.jiacimu.lulu.data.DigitalLifeProfileStore
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.PerceptionIntervalUnit
import com.jiacimu.lulu.data.ProactivePerceptionPolicyStore
import com.jiacimu.lulu.data.UserProfileContext
import com.jiacimu.lulu.design.LuluColors
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterSettingsScreenV2(
    characterId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val pageFocusRequester = remember { FocusRequester() }
    val pageFocusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    remember(context) {
        ProactivePerceptionPolicyStore.initialize(context.applicationContext)
        CharacterVoicePreferenceStore.initialize(context.applicationContext)
        CharacterIdentityStore.initialize(context.applicationContext)
        DigitalLifeProfileStore.initialize(context.applicationContext)
        com.jiacimu.lulu.data.CharacterLifeStore.initialize(context.applicationContext)
        UserProfileContext.initialize(context.applicationContext)
        Unit
    }
    val settings by MigratedDomainStores.characters.settings.collectAsState()
    val identities by CharacterIdentityStore.identities.collectAsState()
    val perceptionPolicies by ProactivePerceptionPolicyStore.policies.collectAsState()
    val wakePlans by com.jiacimu.lulu.data.PerceptionWakePlanStore.plans.collectAsState()
    val voicePreferences by CharacterVoicePreferenceStore.autoPlayReplies.collectAsState()
    val characterVoiceIds by CharacterVoicePreferenceStore.voiceIds.collectAsState()
    val digitalLifeProfiles by DigitalLifeProfileStore.profiles.collectAsState()
    val original = settings[characterId] ?: MigratedDomainStores.characters.get(characterId)
    val digitalLife = digitalLifeProfiles[characterId] ?: DigitalLifeProfileStore.get(characterId)
    val perceptionPolicy = perceptionPolicies[characterId] ?: ProactivePerceptionPolicyStore.get(characterId)
    LaunchedEffect(characterId, perceptionPolicy) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.jiacimu.lulu.data.ProactivePerceptionRuntime.wakePlanFor(context, characterId)
        }
    }
    val autoPlayVoice = voicePreferences[characterId] == true
    val characterVoiceId = characterVoiceIds[characterId].orEmpty()
    val worldBooks by LuluRepositories.worldBook.observeWorldBooks().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var displayName by remember(characterId) { mutableStateOf(original.displayName) }
    var avatarUri by remember(characterId) { mutableStateOf(original.avatarUri) }
    var identity by remember(characterId) { mutableStateOf(identities[characterId].orEmpty()) }
    var persona by remember(characterId) { mutableStateOf(original.persona) }
    var proactiveCalls by remember(characterId) { mutableStateOf(original.contactPolicy.proactiveCallsEnabled) }
    var section by remember(characterId) { mutableIntStateOf(0) }
    LaunchedEffect(characterId, section) {
        pageFocusRequester.requestFocus()
        keyboard?.hide()
    }
    var confirmClearRecords by remember { mutableStateOf(false) }
    var pendingLifeForm by remember { mutableStateOf<CharacterLifeForm?>(null) }
    var clearingRecords by remember { mutableStateOf(false) }
    var recordNotice by remember { mutableStateOf("") }

    // Preset/import changes arrive through the stores too; do not later save stale editor fields.
    LaunchedEffect(original, identities[characterId]) {
        if (displayName.trim().isNotBlank() && displayName.trim() != original.displayName) displayName = original.displayName
        if (avatarUri != original.avatarUri) avatarUri = original.avatarUri
        val savedIdentity = identities[characterId].orEmpty()
        if (identity.trim() != savedIdentity) identity = savedIdentity
        if (persona.trim() != original.persona) persona = original.persona
        proactiveCalls = original.contactPolicy.proactiveCallsEnabled
    }

    fun persistDefinition() {
        val current = MigratedDomainStores.characters.get(characterId)
        CharacterIdentityStore.set(characterId, identity)
        MigratedDomainStores.characters.update(current.copy(
            displayName = displayName.trim().ifBlank { current.displayName },
            avatarUri = avatarUri,
            persona = persona.trim(),
            contactPolicy = current.contactPolicy.copy(proactiveCallsEnabled = proactiveCalls),
        ))
    }

    fun setPerceptionEnabled(enabled: Boolean) {
        ProactivePerceptionPolicyStore.update(characterId) { current ->
            if (!enabled) {
                current.copy(
                    enabled = false,
                    rememberedAdaptiveFrequency = current.adaptiveFrequency,
                    rememberedQuietHoursEnabled = current.quietHoursEnabled,
                    adaptiveFrequency = false,
                    quietHoursEnabled = false,
                )
            } else {
                current.copy(
                    enabled = true,
                    adaptiveFrequency = current.rememberedAdaptiveFrequency,
                    quietHoursEnabled = current.rememberedQuietHoursEnabled,
                )
            }
        }
    }

    Scaffold(
        modifier = Modifier.focusRequester(pageFocusRequester).focusable(),
        containerColor = LuluColors.Paper,
        topBar = {
            TopAppBar(
                title = { Text("${original.displayName}的设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                actions = {
                    IconButton(enabled = !clearingRecords, onClick = { confirmClearRecords = true }) {
                        Icon(Icons.Outlined.DeleteOutline, "清空经历与记忆", tint = MaterialTheme.colorScheme.error)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("资料", "人格", "陪伴", "管理").forEachIndexed { index, label ->
                        FilterChip(section == index, onClick = {
                            pageFocusManager.clearFocus(force = true)
                            keyboard?.hide()
                            section = index
                        }, label = { Text(label, fontSize = 12.sp) }, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (section == 1) item { CharacterV2Card { CharacterLifeSettings(characterId) } }
            if (section == 0) {
            item {
                CharacterV2Card {
                    Text("角色资料", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        LuluAvatarPicker(
                            imageUri = avatarUri,
                            fallback = displayName.take(1).ifBlank { "角" },
                            onSelected = { avatarUri = it; persistDefinition() },
                        )
                        Column(Modifier.weight(1f)) { Text("角色头像", fontWeight = FontWeight.SemiBold) }
                    }
                    OutlinedTextField(value = displayName, onValueChange = { displayName = it; persistDefinition() }, label = { Text("角色名称") }, singleLine = true, modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible())
                    OutlinedTextField(
                        value = identity,
                        onValueChange = { identity = it; persistDefinition() },
                        label = { Text("角色身份") },
                        placeholder = { Text("身份、职业、时代、阵营、背景等世界观信息") },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible(),
                    )
                    OutlinedTextField(
                        value = persona,
                        onValueChange = { persona = it; persistDefinition() },
                        label = { Text("角色设定") },
                        placeholder = { Text("人物的核心设定；具体表达习惯请到「人格」页面编辑") },
                        minLines = 4,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible(),
                    )
                }
            }
            item {
                CharacterV2Card {
                    Text("生命形态", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    if (!digitalLife.isResolved) {
                        Text("这个角色创建时还没有生命形态选项。请确认一次；现有聊天、记忆、辞海和原始时间线都会保留，确认后不可更改。", color = LuluColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
                        OutlinedButton(onClick = { pendingLifeForm = CharacterLifeForm.DIGITAL }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Cloud, null)
                            Spacer(Modifier.width(7.dp))
                            Text("确认为数字生命")
                        }
                        OutlinedButton(onClick = { pendingLifeForm = CharacterLifeForm.REAL_WORLD }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.PersonOutline, null)
                            Spacer(Modifier.width(7.dp))
                            Text("确认为现实角色")
                        }
                    } else if (digitalLife.enabled) {
                        Text("数字生命 · 从生命形态确定起始终如此", fontWeight = FontWeight.SemiBold)
                        Text("生活在数字世界，拥有原生数字身体和持久化家园。数字世界的触觉与见面是真实共同体验，但不冒充物理肉身事件。", color = LuluColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
                        digitalLife.bornAt?.let { bornAt ->
                            val day = (Duration.between(bornAt, Instant.now()).toDays() + 1L).coerceAtLeast(1L)
                            val bornLabel = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(bornAt)
                            Text("生命记录 · 第${day}天 · 可追溯起点 ${bornLabel}", color = LuluColors.Muted, fontSize = 11.sp)
                        }
                    } else {
                        Text("现实角色 · 从创建起始终如此", fontWeight = FontWeight.SemiBold)
                        Text("以现实人物设定与你互动。见面时可以进行现实场景演绎，也可以通过数字投影进入数字世界；不会获得数字生命的原生家园。", color = LuluColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
            }
            if (section == 2) {
            item {
                CharacterV2Card {
                    Text("主动感知", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    CharacterV2Switch(title = "允许主动感知", checked = perceptionPolicy.enabled) { setPerceptionEnabled(it) }
                    CharacterV2Switch(
                        title = "由角色自适应频率",
                        checked = perceptionPolicy.adaptiveFrequency,
                        enabled = perceptionPolicy.enabled,
                    ) { checked ->
                        ProactivePerceptionPolicyStore.update(characterId) { it.copy(adaptiveFrequency = checked, rememberedAdaptiveFrequency = checked) }
                    }
                    Text("开启后会参考未读消息、挂心事项、近期联系来调整感知间隔，并保留少量自然浮动；不是按固定概率主动打电话。是否联系由角色另行决定。", color = LuluColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
                    CharacterV2Switch(
                        title = "夜间勿扰",
                        checked = perceptionPolicy.quietHoursEnabled,
                        enabled = perceptionPolicy.enabled,
                    ) { checked ->
                        ProactivePerceptionPolicyStore.update(characterId) { it.copy(quietHoursEnabled = checked, rememberedQuietHoursEnabled = checked) }
                    }
                    if (perceptionPolicy.enabled) {
                        CharacterV2IntervalRow(
                            value = perceptionPolicy.intervalValue.toString(),
                            unit = perceptionPolicy.intervalUnit,
                            onValueChange = { text ->
                                val value = text.toIntOrNull() ?: return@CharacterV2IntervalRow
                                ProactivePerceptionPolicyStore.update(characterId) { it.copy(intervalValue = value) }
                            },
                            onUnitChange = { unit -> ProactivePerceptionPolicyStore.update(characterId) { it.copy(intervalUnit = unit) } },
                        )
                    }
                    if (perceptionPolicy.enabled) wakePlans[characterId]?.let { plan ->
                        Text("下次预计醒来 · ${plan.dueAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))} · 本轮 ${plan.intervalMinutes} 分钟",
                            color = LuluColors.Muted, fontSize = 12.sp)
                    }
                    if (perceptionPolicy.enabled && perceptionPolicy.quietHoursEnabled) {
                        CharacterV2TimeRow(label = "勿扰开始", minutesOfDay = perceptionPolicy.quietStartMinutesOfDay) { minutes ->
                            ProactivePerceptionPolicyStore.update(characterId) { it.copy(quietStartMinutesOfDay = minutes) }
                        }
                        CharacterV2TimeRow(label = "勿扰结束", minutesOfDay = perceptionPolicy.quietEndMinutesOfDay) { minutes ->
                            ProactivePerceptionPolicyStore.update(characterId) { it.copy(quietEndMinutesOfDay = minutes) }
                        }
                    }
                }
            }
            item {
                CharacterV2Card {
                    Text("主动来电", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    CharacterV2Switch(title = "允许主动来电", checked = proactiveCalls) { proactiveCalls = it; persistDefinition() }
                    Text("角色可以自主选择是否拨号；自己答应的定时来电会尝试通过真实来电执行。关闭主动来电时，未执行的拨号承诺会显示受阻，不能伪装为已拨出。", color = LuluColors.Muted, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
            item {
                CharacterV2Card {
                    Text("语音回复", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    OutlinedTextField(
                        value = characterVoiceId,
                        onValueChange = { CharacterVoicePreferenceStore.setVoiceId(characterId, it) },
                        label = { Text("MiniMax Voice ID") },
                        placeholder = { Text("填写这个角色自己的 Voice ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible(),
                    )
                    var realtimeVoiceId by remember(characterId) { mutableStateOf(CharacterVoicePreferenceStore.realtimeVoiceId(characterId).orEmpty()) }
                    OutlinedTextField(value = realtimeVoiceId, onValueChange = { realtimeVoiceId = it; CharacterVoicePreferenceStore.setRealtimeVoiceId(characterId, it) },
                        label = { Text("ElevenLabs Voice ID") }, singleLine = true, modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible())
                    CharacterV2Switch(title = "自动播放语音", checked = autoPlayVoice) { enabled -> CharacterVoicePreferenceStore.setEnabled(characterId, enabled) }
                    HorizontalDivider()
                    Text("哄睡专用声线", fontWeight = FontWeight.SemiBold)
                    Text("仅 ElevenLabs 哄睡通话使用；关闭或留空时沿用角色平时的声线。",
                        color = LuluColors.Muted, fontSize = 12.sp)
                    var bedtimeVoiceId by remember(characterId) {
                        mutableStateOf(CharacterVoicePreferenceStore.sleepVoiceId(characterId))
                    }
                    var bedtimeVoiceEnabled by remember(characterId) {
                        mutableStateOf(CharacterVoicePreferenceStore.isSleepVoiceEnabled(characterId))
                    }
                    OutlinedTextField(
                        value = bedtimeVoiceId,
                        onValueChange = {
                            bedtimeVoiceId = it
                            CharacterVoicePreferenceStore.setSleepVoiceId(characterId, it)
                        },
                        label = { Text("哄睡 ElevenLabs Voice ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible(),
                    )
                    CharacterV2Switch(
                        title = "哄睡时切换专属声线",
                        checked = bedtimeVoiceEnabled,
                        enabled = bedtimeVoiceId.isNotBlank(),
                    ) {
                        bedtimeVoiceEnabled = it
                        CharacterVoicePreferenceStore.setSleepVoiceEnabled(characterId, it)
                    }
                }
            }
            }
            if (section == 3) {
            item {
                CharacterV2Card {
                    CharacterExecutionSettings(characterId)
                    Text("数据与记录", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    OutlinedButton(
                        onClick = { confirmClearRecords = true },
                        enabled = !clearingRecords,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (clearingRecords) "正在清除…" else "清除所有记录")
                    }
                    if (recordNotice.isNotBlank()) Text(recordNotice, color = LuluColors.Muted, fontSize = 12.sp)
                }
            }
            item { Text("角色世界书", fontWeight = FontWeight.Bold, fontSize = 19.sp) }
            if (worldBooks.isEmpty()) {
                item { CharacterV2Card { Text("还没有世界书", fontWeight = FontWeight.Bold) } }
            } else {
                items(worldBooks, key = { it.id }) { book ->
                    CharacterV2Card {
                        Text(book.title, fontWeight = FontWeight.Bold)
                        Text(if (book.globalEnabled) "全局默认：开启" else "全局默认：关闭", color = LuluColors.Muted, fontSize = 12.sp)
                        val selected = book.characterOverrides[characterId]
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CharacterV2WorldChoice("跟随全局", selected == null, Modifier.weight(1f)) { scope.launch { LuluRepositories.worldBook.setCharacterOverride(book.id, characterId, null) } }
                            CharacterV2WorldChoice("单独开启", selected == true, Modifier.weight(1f)) { scope.launch { LuluRepositories.worldBook.setCharacterOverride(book.id, characterId, true) } }
                            CharacterV2WorldChoice("单独关闭", selected == false, Modifier.weight(1f)) { scope.launch { LuluRepositories.worldBook.setCharacterOverride(book.id, characterId, false) } }
                        }
                    }
                }
            }
            }
        }
    }

    pendingLifeForm?.let { lifeForm ->
        AlertDialog(
            onDismissRequest = { pendingLifeForm = null },
            title = { Text("确认生命形态？") },
            text = { Text(if (lifeForm == CharacterLifeForm.DIGITAL) "确认后，${original.displayName}将永久作为数字生命存在，并获得一处初始为空的数字家园。" else "确认后，${original.displayName}将永久作为现实角色存在。生命形态之后不能切换。") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        DigitalLifeProfileStore.confirmLegacyLifeForm(
                            characterId = characterId,
                            displayName = displayName.trim().ifBlank { original.displayName },
                            creatorName = UserProfileContext.displayLabel(),
                            lifeForm = lifeForm,
                        )
                    }.onSuccess {
                        recordNotice = "生命形态已经确认，之后不可更改"
                    }.onFailure {
                        recordNotice = it.message.orEmpty()
                    }
                    pendingLifeForm = null
                }) { Text("永久确认") }
            },
            dismissButton = { TextButton(onClick = { pendingLifeForm = null }) { Text("再想想") } },
        )
    }

    if (confirmClearRecords) {
        AlertDialog(
            onDismissRequest = { if (!clearingRecords) confirmClearRecords = false },
            title = { Text("清空${original.displayName}的经历与记忆？") },
            text = {
                Text(
                    if (digitalLife.enabled) {
                        "会永久清除这个角色的原始时间线、记忆、辞海、私聊及过往经历痕迹，并以清除当天作为新的出生与可追溯起点。资料、身份、头像、人格、人设、角色设置和设计书全部保留，其他角色的记录不变。此操作无法撤销。"
                    } else {
                        "会永久清除这个角色的私聊消息、辞海、记忆、朋友圈内容与互动、此刻历史，以及原始时间线里的全部事件。角色头像、身份、设定、主动感知等设置会保留。此操作无法撤销。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !clearingRecords,
                    onClick = {
                        clearingRecords = true
                        scope.launch {
                            try {
                                CharacterRecordReset.clearAll(characterId)
                                confirmClearRecords = false
                                recordNotice = if (DigitalLifeProfileStore.isEnabled(characterId))
                                    "${original.displayName}已从今天重新开始，人设与设置已保留"
                                else "${original.displayName}的历史记录已清除，人设与设置已保留"
                            } catch (error: Exception) {
                                recordNotice = "清除未完成：${error.message.orEmpty()}"
                            } finally { clearingRecords = false }
                        }
                    },
                ) { Text("确认清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(enabled = !clearingRecords, onClick = { confirmClearRecords = false }) { Text("取消") } },
        )
    }


}

@Composable
private fun CharacterV2IntervalRow(
    value: String,
    unit: PerceptionIntervalUnit,
    onValueChange: (String) -> Unit,
    onUnitChange: (PerceptionIntervalUnit) -> Unit,
) {
    var draft by remember(value) { mutableStateOf(value) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("感知时间间隔", fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { text ->
                    val clean = text.filter(Char::isDigit).take(3)
                    draft = clean
                    if (clean.isNotBlank()) onValueChange(clean)
                },
                singleLine = true,
                modifier = Modifier.weight(1f).keepFocusedFieldVisible(),
            )
            PerceptionIntervalUnit.entries.forEach { option ->
                FilterChip(selected = unit == option, onClick = { onUnitChange(option) }, label = { Text(option.label) })
            }
        }
    }
}

@Composable
private fun CharacterV2TimeRow(label: String, minutesOfDay: Int, onChange: (Int) -> Unit) {
    val context = LocalContext.current
    val hour = minutesOfDay / 60
    val minute = minutesOfDay % 60
    Surface(
        modifier = Modifier.fillMaxWidth().clickable {
            TimePickerDialog(context, { _, selectedHour, selectedMinute -> onChange(selectedHour * 60 + selectedMinute) }, hour, minute, true).show()
        },
        color = LuluColors.Paper,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, LuluColors.Border),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(String.format(Locale.getDefault(), "%02d:%02d", hour, minute), color = LuluColors.Muted)
        }
    }
}

@Composable
private fun CharacterV2Switch(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.SemiBold, color = if (enabled) LocalContentColor.current else LuluColors.Muted, modifier = Modifier.weight(1f).padding(end = 10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun CharacterV2WorldChoice(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(text, maxLines = 1, fontSize = 11.sp) }, modifier = modifier)
}

@Composable
private fun CharacterV2Card(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = LuluColors.Card),
        border = BorderStroke(1.dp, LuluColors.Border),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
