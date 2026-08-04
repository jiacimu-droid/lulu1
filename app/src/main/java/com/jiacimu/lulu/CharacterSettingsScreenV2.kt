package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.CharacterContactPolicy
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.design.LuluColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterSettingsScreenV2(
    characterId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val settings by MigratedDomainStores.characters.settings.collectAsState()
    val original = settings[characterId] ?: MigratedDomainStores.characters.get(characterId)
    val worldBooks by LuluRepositories.worldBook.observeWorldBooks().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var displayName by remember(characterId) { mutableStateOf(original.displayName) }
    var avatarUri by remember(characterId) { mutableStateOf(original.avatarUri) }
    var persona by remember(characterId) { mutableStateOf(original.persona) }
    var contactEnabled by remember(characterId) { mutableStateOf(original.contactPolicy.enabled) }
    var adaptiveFrequency by remember(characterId) { mutableStateOf(original.contactPolicy.adaptiveFrequency) }
    var quietHours by remember(characterId) { mutableStateOf(original.contactPolicy.quietHoursEnabled) }
    var quietStart by remember(characterId) { mutableStateOf(original.contactPolicy.quietStartHour.toString()) }
    var quietEnd by remember(characterId) { mutableStateOf(original.contactPolicy.quietEndHour.toString()) }
    var proactiveCalls by remember(characterId) { mutableStateOf(original.contactPolicy.proactiveCallsEnabled) }
    var callStart by remember(characterId) { mutableStateOf(original.contactPolicy.callWindowStartHour.toString()) }
    var callEnd by remember(characterId) { mutableStateOf(original.contactPolicy.callWindowEndHour.toString()) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(
        displayName, avatarUri, persona, contactEnabled, adaptiveFrequency, quietHours,
        quietStart, quietEnd, proactiveCalls, callStart, callEnd,
    ) {
        if (displayName.isBlank()) return@LaunchedEffect
        delay(350)
        MigratedDomainStores.characters.update(
            original.copy(
                displayName = displayName.trim(),
                avatarUri = avatarUri,
                persona = persona.trim(),
                contactPolicy = CharacterContactPolicy(
                    enabled = contactEnabled,
                    adaptiveFrequency = adaptiveFrequency,
                    quietHoursEnabled = quietHours,
                    quietStartHour = quietStart.toIntOrNull()?.coerceIn(0, 23) ?: 23,
                    quietEndHour = quietEnd.toIntOrNull()?.coerceIn(0, 23) ?: 7,
                    proactiveCallsEnabled = proactiveCalls,
                    callWindowStartHour = callStart.toIntOrNull()?.coerceIn(0, 23) ?: 9,
                    callWindowEndHour = callEnd.toIntOrNull()?.coerceIn(0, 23) ?: 22,
                ),
            ),
        )
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
                            Text("点击头像，从手机相册选择图片", color = LuluColors.Muted, fontSize = 12.sp)
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
                        value = persona,
                        onValueChange = { persona = it },
                        label = { Text("角色核心设定") },
                        minLines = 4,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                CharacterV2Card {
                    Text("主动联系", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    CharacterV2Switch("允许主动联系", "角色可以根据关系和情境主动发消息。", contactEnabled) { contactEnabled = it }
                    CharacterV2Switch("由角色自适应频率", "不写死每日次数；根据主人状态降低或增加。", adaptiveFrequency) { adaptiveFrequency = it }
                    CharacterV2Switch("夜间勿扰", "只限制角色主动联系，不影响主人主动打开聊天。", quietHours) { quietHours = it }
                    if (quietHours) {
                        CharacterV2HourRow("勿扰开始", quietStart) { quietStart = it }
                        CharacterV2HourRow("勿扰结束", quietEnd) { quietEnd = it }
                    }
                }
            }
            item {
                CharacterV2Card {
                    Text("主动来电", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    CharacterV2Switch("允许主动来电", "角色只有在全局通知许可和自己的时间窗内才能主动来电。", proactiveCalls) { proactiveCalls = it }
                    if (proactiveCalls) {
                        CharacterV2HourRow("来电开始", callStart) { callStart = it }
                        CharacterV2HourRow("来电结束", callEnd) { callEnd = it }
                    }
                }
            }
            item {
                Text("角色世界书", fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text("每本可以跟随全局，或为${original.displayName}单独开启、关闭。", color = LuluColors.Muted)
            }
            if (worldBooks.isEmpty()) {
                item {
                    CharacterV2Card {
                        Text("还没有世界书", fontWeight = FontWeight.Bold)
                        Text("先在桌面的世界书 App 中创建。", color = LuluColors.Muted)
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

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除${original.displayName}？") },
            text = { Text("角色资料会删除；已有聊天、记忆和游戏记录不会被静默抹除，仍可在对应页面处理。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (MigratedDomainStores.characters.delete(characterId)) onDeleted()
                        confirmDelete = false
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun CharacterV2HourRow(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(2)) },
        label = { Text("$label（0—23）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CharacterV2Switch(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = LuluColors.Muted, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
