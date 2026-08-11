package com.jiacimu.lulu

import android.app.TimePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.CharacterIdentityStore
import com.jiacimu.lulu.data.CharacterRecordReset
import com.jiacimu.lulu.data.CharacterVoicePreferenceStore
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.PerceptionIntervalUnit
import com.jiacimu.lulu.data.ProactivePerceptionPolicyStore
import com.jiacimu.lulu.design.LuluColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterSettingsScreenV2(
    characterId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    remember(context) {
        ProactivePerceptionPolicyStore.initialize(context.applicationContext)
        CharacterVoicePreferenceStore.initialize(context.applicationContext)
        CharacterIdentityStore.initialize(context.applicationContext)
        Unit
    }
    val settings by MigratedDomainStores.characters.settings.collectAsState()
    val identities by CharacterIdentityStore.identities.collectAsState()
    val perceptionPolicies by ProactivePerceptionPolicyStore.policies.collectAsState()
    val voicePreferences by CharacterVoicePreferenceStore.autoPlayReplies.collectAsState()
    val characterVoiceIds by CharacterVoicePreferenceStore.voiceIds.collectAsState()
    val original = settings[characterId] ?: MigratedDomainStores.characters.get(characterId)
    val perceptionPolicy = perceptionPolicies[characterId] ?: ProactivePerceptionPolicyStore.get(characterId)
    val autoPlayVoice = voicePreferences[characterId] == true
    val characterVoiceId = characterVoiceIds[characterId].orEmpty()
    val worldBooks by LuluRepositories.worldBook.observeWorldBooks().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var displayName by remember(characterId) { mutableStateOf(original.displayName) }
    var avatarUri by remember(characterId) { mutableStateOf(original.avatarUri) }
    var identity by remember(characterId) { mutableStateOf(identities[characterId].orEmpty()) }
    var persona by remember(characterId) { mutableStateOf(original.persona) }
    var proactiveCalls by remember(characterId) { mutableStateOf(original.contactPolicy.proactiveCallsEnabled) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmClearRecords by remember { mutableStateOf(false) }
    var clearingRecords by remember { mutableStateOf(false) }
    var recordNotice by remember { mutableStateOf("") }

    LaunchedEffect(displayName, avatarUri, identity, persona, proactiveCalls) {
        if (displayName.isBlank()) return@LaunchedEffect
        delay(350)
        CharacterIdentityStore.set(characterId, identity)
        MigratedDomainStores.characters.update(
            original.copy(
                displayName = displayName.trim(),
                avatarUri = avatarUri,
                persona = persona.trim(),
                contactPolicy = original.contactPolicy.copy(proactiveCallsEnabled = proactiveCalls),
            ),
        )
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
        containerColor = LuluColors.Paper,
        topBar = {
            TopAppBar(
                title = { Text("${original.displayName}的设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                actions = {
                    if (characterId != "lulu") {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Outlined.DeleteOutline, "删除角色", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
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
                            onSelected = { avatarUri = it },
                        )
                        Column(Modifier.weight(1f)) {
                            Text("角色头像", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("角色名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = identity,
                        onValueChange = { identity = it },
                        label = { Text("角色身份") },
                        placeholder = { Text("身份、职业、时代、阵营、背景等世界观信息") },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = persona,
                        onValueChange = { persona = it },
                        label = { Text("角色设定") },
                        placeholder = { Text("性格、说话方式、价值观、关系边界、习惯与处事方式") },
                        minLines = 4,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                CharacterV2Card {
                    Text("主动感知", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    CharacterV2Switch(
                        title = "允许主动感知",
                        checked = perceptionPolicy.enabled,
                    ) { setPerceptionEnabled(it) }
                    CharacterV2Switch(
                        title = "由角色自适应频率",
                        checked = perceptionPolicy.adaptiveFrequency,
                        enabled = perceptionPolicy.enabled,
                    ) { checked ->
                        ProactivePerceptionPolicyStore.update(characterId) {
                            it.copy(adaptiveFrequency = checked, rememberedAdaptiveFrequency = checked)
                        }
                    }
                    CharacterV2Switch(
                        title = "夜间勿扰",
                        checked = perceptionPolicy.quietHoursEnabled,
                        enabled = perceptionPolicy.enabled,
                    ) { checked ->
                        ProactivePerceptionPolicyStore.update(characterId) {
                            it.copy(quietHoursEnabled = checked, rememberedQuietHoursEnabled = checked)
                        }
                    }
                    if (perceptionPolicy.enabled) {
                        CharacterV2IntervalRow(
                            value = perceptionPolicy.intervalValue.toString(),
                            unit = perceptionPolicy.intervalUnit,
                            onValueChange = { text ->
                                val value = text.toIntOrNull() ?: return@CharacterV2IntervalRow
                                ProactivePerceptionPolicyStore.update(characterId) { it.copy(intervalValue = value) }
                            },
                            onUnitChange = { unit ->
                                ProactivePerceptionPolicyStore.update(characterId) { it.copy(intervalUnit = unit) }
                            },
                        )
                    }
                    if (perceptionPolicy.enabled && perceptionPolicy.quietHoursEnabled) {
                        CharacterV2TimeRow(
                            label = "勿扰开始",
                            minutesOfDay = perceptionPolicy.quietStartMinutesOfDay,
                        ) { minutes ->
                            ProactivePerceptionPolicyStore.update(characterId) { it.copy(quietStartMinutesOfDay = minutes) }
                        }
                        CharacterV2TimeRow(
                            label = "勿扰结束",
                            minutesOfDay = perceptionPolicy.quietEndMinutesOfDay,
                        ) { minutes ->
                            ProactivePerceptionPolicyStore.update(characterId) { it.copy(quietEndMinutesOfDay = minutes) }
                        }
                    }
                }
            }
            item {
                CharacterV2Card {
                    Text("主动来电", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    CharacterV2Switch(
                        title = "允许主动来电",
                        checked = proactiveCalls,
                    ) { proactiveCalls = it }
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
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CharacterV2Switch(
                        title = "自动播放语音",
                        checked = autoPlayVoice,
                    ) { enabled -> CharacterVoicePreferenceStore.setEnabled(characterId, enabled) }
                }
            }
            item {
                CharacterV2Card {
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
                    if (recordNotice.isNotBlank()) {
                        Text(recordNotice, color = LuluColors.Muted, fontSize = 12.sp)
                    }
                }
            }
            item {
                Text("角色世界书", fontWeight = FontWeight.Bold, fontSize = 19.sp)
            }
            if (worldBooks.isEmpty()) {
                item {
                    CharacterV2Card {
                        Text("还没有世界书", fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                items(worldBooks, key = { it.id }) { book ->
                    CharacterV2Card {
                        Text(book.title, fontWeight = FontWeight.Bold)
                        Text(if (book.globalEnabled) "全局默认：开启" else "全局默认：关闭", color = LuluColors.Muted, fontSize = 12.sp)
                        val selected = book.characterOverrides[characterId]
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CharacterV2WorldChoice("跟随全局", selected == null, Modifier.weight(1f)) {
                                scope.launch { LuluRepositories.worldBook.setCharacterOverride(book.id, characterId, null) }
                            }
                            CharacterV2WorldChoice("单独开启", selected == true, Modifier.weight(1f)) {
                                scope.launch { LuluRepositories.worldBook.setCharacterOverride(book.id, characterId, true) }
                            }
                            CharacterV2WorldChoice("单独关闭", selected == false, Modifier.weight(1f)) {
                                scope.launch { LuluRepositories.worldBook.setCharacterOverride(book.id, characterId, false) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClearRecords) {
        AlertDialog(
            onDismissRequest = { if (!clearingRecords) confirmClearRecords = false },
            title = { Text("清除${original.displayName}的所有记录？") },
            text = {
                Text(
                    "会永久清除这个角色的私聊消息、辞海、记忆、朋友圈内容与互动、此刻历史，以及原始时间线里的全部事件。角色头像、身份、设定、主动感知等设置会保留。此操作无法撤销。",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !clearingRecords,
                    onClick = {
                        clearingRecords = true
                        scope.launch {
                            CharacterRecordReset.clearAll(characterId)
                            clearingRecords = false
                            confirmClearRecords = false
                            recordNotice = "${original.displayName}的历史记录已清除"
                        }
                    },
                ) { Text("确认清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !clearingRecords, onClick = { confirmClearRecords = false }) { Text("取消") }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除${original.displayName}？") },
            text = { Text("角色资料会删除；已有聊天、记忆和游戏记录不会被静默抹除，仍可在对应页面处理。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (MigratedDomainStores.characters.delete(characterId)) {
                            CharacterIdentityStore.delete(characterId)
                            onDeleted()
                        }
                        confirmDelete = false
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
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
                modifier = Modifier.weight(1f),
            )
            PerceptionIntervalUnit.entries.forEach { option ->
                FilterChip(
                    selected = unit == option,
                    onClick = { onUnitChange(option) },
                    label = { Text(option.label) },
                )
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
            TimePickerDialog(
                context,
                { _, selectedHour, selectedMinute -> onChange(selectedHour * 60 + selectedMinute) },
                hour,
                minute,
                true,
            ).show()
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
        Text(
            title,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) LocalContentColor.current else LuluColors.Muted,
            modifier = Modifier.weight(1f).padding(end = 10.dp),
        )
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
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
