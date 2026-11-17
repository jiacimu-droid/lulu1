package com.jiacimu.lulu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.CharacterIdentityStore
import com.jiacimu.lulu.data.CharacterLifeStore
import com.jiacimu.lulu.data.CharacterProfileField
import com.jiacimu.lulu.data.CharacterProfileSchema
import com.jiacimu.lulu.data.CharacterVoicePreferenceStore
import com.jiacimu.lulu.data.DigitalLifeProfileStore
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.ProactivePerceptionPolicyStore
import com.jiacimu.lulu.data.UserProfileContext

/** Uses the same profile schema and editor cards as CharacterSettingsScreenV2.
 * All fields are drafts until the owner chooses Save; cancel never creates a role. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CharacterCreateScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val context = LocalContext.current
    var section by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var avatarUri by rememberSaveable { mutableStateOf<String?>(null) }
    var identity by rememberSaveable { mutableStateOf("") }
    var persona by rememberSaveable { mutableStateOf("") }
    var preferredAddress by rememberSaveable { mutableStateOf("") }
    var profile by rememberSaveable { mutableStateOf<Map<String, String>>(emptyMap()) }
    var perceptionEnabled by rememberSaveable { mutableStateOf(true) }
    var adaptiveFrequency by rememberSaveable { mutableStateOf(true) }
    var quietHours by rememberSaveable { mutableStateOf(false) }
    var proactiveCalls by rememberSaveable { mutableStateOf(false) }
    var voiceId by rememberSaveable { mutableStateOf("") }
    var realtimeVoiceId by rememberSaveable { mutableStateOf("") }
    var autoPlayVoice by rememberSaveable { mutableStateOf(false) }
    var editingField by remember { mutableStateOf<CharacterProfileField?>(null) }
    var fieldDraft by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    // An interrupted auxiliary-store write can retry against the same role.
    // Do not create a second character or conversation when Save is tapped again.
    var createdId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(section) { listState.scrollToItem(0) }

    fun saveCharacter() {
        if (name.isBlank() || saving) return
        saving = true
        error = ""
        try {
            val app = context.applicationContext
            CharacterIdentityStore.initialize(app)
            CharacterLifeStore.initialize(app)
            DigitalLifeProfileStore.initialize(app)
            CharacterVoicePreferenceStore.initialize(app)
            ProactivePerceptionPolicyStore.initialize(app)
            val id = createdId ?: MigratedDomainStores.characters.create(name.trim(), persona.trim())
                .characterId.also { createdId = it }
            val current = MigratedDomainStores.characters.get(id)
            MigratedDomainStores.characters.update(current.copy(
                displayName = name.trim(),
                avatarUri = avatarUri,
                persona = persona.trim(),
                contactPolicy = current.contactPolicy.copy(proactiveCallsEnabled = proactiveCalls),
            ))
            CharacterIdentityStore.set(id, identity.trim())
            profile.forEach { (key, value) ->
                if (value.isNotBlank()) CharacterLifeStore.setProfile(id, key, value.trim())
            }
            if (preferredAddress.isNotBlank())
                CharacterLifeStore.setSocialName(id, "preferredAddress", preferredAddress)
            ProactivePerceptionPolicyStore.update(id) { currentPolicy ->
                currentPolicy.copy(
                    enabled = perceptionEnabled,
                    adaptiveFrequency = perceptionEnabled && adaptiveFrequency,
                    rememberedAdaptiveFrequency = adaptiveFrequency,
                    quietHoursEnabled = perceptionEnabled && quietHours,
                    rememberedQuietHoursEnabled = quietHours,
                )
            }
            if (voiceId.isNotBlank()) CharacterVoicePreferenceStore.setVoiceId(id, voiceId)
            if (realtimeVoiceId.isNotBlank())
                CharacterVoicePreferenceStore.setRealtimeVoiceId(id, realtimeVoiceId)
            if (autoPlayVoice) CharacterVoicePreferenceStore.setEnabled(id, true)
            if (!DigitalLifeProfileStore.isResolved(id)) {
                DigitalLifeProfileStore.registerNewLife(
                    characterId = id,
                    displayName = name.trim(),
                    creatorName = UserProfileContext.displayLabel(),
                )
            }
            // Create a blank conversation only after all editable definition data
            // has been written. Never manufacture a welcome message.
            MigratedDomainStores.chat.ensureConversation(id, name.trim())
            onCreated(id)
        } catch (failure: Exception) {
            error = "保存未完成：${failure.message.orEmpty().take(120)}"
        } finally {
            saving = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("新建数字生命", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !saving) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        enabled = name.isNotBlank() && !saving,
                        onClick = ::saveCharacter,
                    ) { Text(if (saving) "保存中" else "保存角色", fontWeight = FontWeight.SemiBold) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = ::saveCharacter,
                        enabled = name.isNotBlank() && !saving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(15.dp),
                    ) { Text(if (saving) "正在保存" else "保存并创建角色") }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                CharacterSectionTabs(
                    listOf("资料", "人格", "陪伴"),
                    selected = section,
                    onSelect = { section = it },
                )
            }
            if (section == 0) {
                item {
                    CharacterV2Card {
                        Text("角色资料", fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge)
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            LuluAvatarPicker(
                                imageUri = avatarUri,
                                fallback = name.take(1).ifBlank { "角" },
                                onSelected = { avatarUri = it },
                            )
                            Text("角色头像", fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedTextField(name, { name = it },
                            label = { Text("角色名称") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible())
                        OutlinedTextField(identity, { identity = it },
                            label = { Text("身份与背景事实") },
                            placeholder = { Text("身份、职业与已知背景") },
                            minLines = 3, maxLines = 8,
                            modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible())
                    }
                }
                item {
                    CharacterV2Card {
                        Text("称呼与社交资料", fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(preferredAddress,
                            { preferredAddress = it.take(24) },
                            label = { Text("角色对你的称呼") },
                            modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible())
                    }
                }
                item {
                    CharacterV2Card {
                        Text("数字生命", fontWeight = FontWeight.SemiBold)
                        Text("角色将生活在露露机的数字世界中。保存后可继续编辑所有设置。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (section == 1) {
                item {
                    CharacterV2Card {
                        Text("人格与表达", fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(persona, { persona = it },
                            label = { Text("人物核心简介") },
                            placeholder = { Text("核心性格与长期特征") },
                            minLines = 4, maxLines = 10,
                            modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible())
                    }
                }
                CharacterProfileSchema.groupedFields().forEach { (group, fields) ->
                    item(group) {
                        CharacterV2Card {
                            Text(group, fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium)
                            fields.forEach { field ->
                                Column(Modifier.fillMaxWidth().clickable {
                                    editingField = field
                                    fieldDraft = profile[field.key].orEmpty()
                                }.padding(vertical = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Row(Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Text(field.label, modifier = Modifier.weight(1f),
                                            fontWeight = FontWeight.Medium)
                                        Text("编辑", color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelMedium)
                                    }
                                    Text(profile[field.key].orEmpty().ifBlank { "未设定" },
                                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            if (section == 2) {
                item {
                    CharacterV2Card {
                        Text("主动感知", fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge)
                        CharacterV2Switch("允许主动感知", perceptionEnabled) {
                            perceptionEnabled = it
                        }
                        CharacterV2Switch("由角色自适应频率", adaptiveFrequency,
                            enabled = perceptionEnabled) { adaptiveFrequency = it }
                        CharacterV2Switch("夜间勿扰", quietHours,
                            enabled = perceptionEnabled) { quietHours = it }
                    }
                }
                item {
                    CharacterV2Card {
                        Text("主动来电", fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge)
                        CharacterV2Switch("允许主动来电", proactiveCalls) {
                            proactiveCalls = it
                        }
                    }
                }
                item {
                    CharacterV2Card {
                        Text("语音回复", fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(voiceId, { voiceId = it },
                            label = { Text("MiniMax Voice ID") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible())
                        OutlinedTextField(realtimeVoiceId, { realtimeVoiceId = it },
                            label = { Text("ElevenLabs Voice ID") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible())
                        CharacterV2Switch("自动播放语音", autoPlayVoice) {
                            autoPlayVoice = it
                        }
                    }
                }
            }
        }
    }
    editingField?.let { field ->
        AlertDialog(
            onDismissRequest = { editingField = null },
            title = { Text(field.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (field.hint.isNotBlank()) Text(field.hint,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(fieldDraft, { fieldDraft = it },
                        minLines = 4, maxLines = 8,
                        modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    profile = profile + (field.key to fieldDraft)
                    editingField = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingField = null }) { Text("取消") }
            },
        )
    }
}
