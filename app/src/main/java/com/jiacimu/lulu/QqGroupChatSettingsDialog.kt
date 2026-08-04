package com.jiacimu.lulu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.CharacterSettings
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.LuluGroupChat
import com.jiacimu.lulu.data.LuluGroupMember
import com.jiacimu.lulu.data.LuluGroupRole
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.design.LuluColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QqGroupChatSettingsScreen(
    conversationId: String,
    group: LuluGroupChat,
    characters: List<CharacterSettings>,
    messages: List<LuluChatMessage>,
    onBack: () -> Unit,
    onSave: (LuluGroupChat) -> Unit,
    onDelete: () -> Unit,
) {
    var editing by remember(group) { mutableStateOf(group) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var searchVisible by remember { mutableStateOf(false) }
    var memberPickerVisible by remember { mutableStateOf(false) }
    var memberManagementVisible by remember { mutableStateOf(false) }
    val memberIds = editing.members.mapTo(mutableSetOf(), LuluGroupMember::characterId)

    if (searchVisible) {
        GroupChatRecordSearchScreen(
            messages = messages,
            characterNames = characters.associate { it.characterId to it.displayName },
            userLabel = group.userGroupNickname,
            onBack = { searchVisible = false },
        )
        return
    }
    if (memberPickerVisible) {
        GroupMemberPickerScreen(
            characters = characters,
            existingMemberIds = memberIds,
            onBack = { memberPickerVisible = false },
            onConfirm = { addedIds ->
                val updated = editing.copy(
                    members = editing.members + addedIds.map(::LuluGroupMember),
                ).normalized()
                editing = updated
                onSave(updated)
                memberPickerVisible = false
            },
        )
        return
    }
    if (memberManagementVisible) {
        GroupMemberManagementScreen(
            members = editing.members,
            characters = characters,
            onMembersChange = { members ->
                val updated = editing.copy(members = members).normalized()
                editing = updated
                onSave(updated)
            },
            onAddMember = {
                memberManagementVisible = false
                memberPickerVisible = true
            },
            onBack = { memberManagementVisible = false },
        )
        return
    }

    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = LuluColors.Paper,
        topBar = {
            TopAppBar(
                title = { Text("群聊设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                actions = {
                    TextButton(
                        enabled = editing.name.isNotBlank() && memberIds.size >= 2,
                        onClick = {
                            onSave(editing.normalized())
                            onBack()
                        },
                    ) { Text("保存", fontWeight = FontWeight.Bold) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                GroupSettingsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LuluAvatarPicker(
                            imageUri = editing.avatarUri,
                            fallback = editing.name.take(1).ifBlank { "群" },
                            size = 82,
                            onSelected = { editing = editing.copy(avatarUri = it) },
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(editing.name.ifBlank { "新群聊" }, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text("${editing.members.size + 1} 位成员", color = LuluColors.Muted, fontSize = 12.sp)
                            Text("点击头像可更换群聊图片", color = LuluColors.Muted, fontSize = 12.sp)
                        }
                    }
                    OutlinedTextField(
                        value = editing.name,
                        onValueChange = { editing = editing.copy(name = it.take(30)) },
                        label = { Text("群名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editing.announcement,
                        onValueChange = { editing = editing.copy(announcement = it.take(500)) },
                        label = { Text("群公告") },
                        placeholder = { Text("所有成员进入群聊时都能看到") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editing.userGroupNickname,
                        onValueChange = { editing = editing.copy(userGroupNickname = it.take(20)) },
                        label = { Text("我的群昵称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item {
                GroupSettingsCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("群成员", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Text("${editing.members.size + 1}人", color = LuluColors.Muted, fontSize = 12.sp)
                    }
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(vertical = 2.dp),
                    ) {
                        item(key = "group-user") {
                            GroupMemberAvatarItem(name = editing.userGroupNickname.ifBlank { "我" }, avatarUri = null)
                        }
                        items(editing.members, key = LuluGroupMember::characterId) { member ->
                            val character = characters.firstOrNull { it.characterId == member.characterId }
                            if (character != null) {
                                GroupMemberAvatarItem(
                                    name = member.groupNickname.ifBlank { character.displayName },
                                    avatarUri = character.avatarUri,
                                )
                            }
                        }
                        item(key = "add-group-member") {
                            GroupMemberAddItem(onClick = { memberPickerVisible = true })
                        }
                    }
                    GroupSettingsAction(
                        icon = Icons.Outlined.PersonAdd,
                        title = "添加群成员",
                        subtitle = "从角色列表中选择新的群成员",
                        onClick = { memberPickerVisible = true },
                    )
                    HorizontalDivider(color = LuluColors.Border)
                    GroupSettingsAction(
                        icon = Icons.Outlined.ManageAccounts,
                        title = "管理群成员",
                        subtitle = "修改群昵称、管理员或移出群聊",
                        onClick = { memberManagementVisible = true },
                    )
                }
            }

            item {
                GroupSettingsCard {
                    GroupSettingsAction(
                        icon = Icons.Outlined.Search,
                        title = "查找聊天记录",
                        subtitle = "按关键词查找这个群里的全部消息",
                        onClick = { searchVisible = true },
                    )
                    HorizontalDivider(color = LuluColors.Border)
                    GroupSettingsAction(
                        icon = Icons.Outlined.DeleteSweep,
                        title = "删除群聊的聊天信息",
                        subtitle = "清空消息和原始时间线上下文，但保留这个群",
                        onClick = { confirmClear = true },
                    )
                }
            }

            item {
                GroupSettingsCard {
                    GroupSettingSwitch("置顶群聊", editing.pinned) { editing = editing.copy(pinned = it) }
                    GroupSettingSwitch("消息免打扰", editing.muted) { editing = editing.copy(muted = it) }
                    GroupSettingSwitch("显示群成员昵称", editing.showMemberNames) { editing = editing.copy(showMemberNames = it) }
                    GroupSettingSwitch("允许角色互相接话", editing.allowCharacterConversation) {
                        editing = editing.copy(
                            allowCharacterConversation = it,
                            maxAutoReplies = editing.maxAutoReplies,
                        )
                    }
                    HorizontalDivider(color = LuluColors.Border)
                    Text(
                        if (editing.allowCharacterConversation) {
                            "每轮最多 ${editing.maxAutoReplies} 次自然接话"
                        } else {
                            "每次最多自动回复 ${editing.maxAutoReplies} 条"
                        },
                        fontSize = 13.sp,
                    )
                    Slider(
                        value = editing.maxAutoReplies.toFloat(),
                        onValueChange = {
                            editing = editing.copy(maxAutoReplies = it.toInt().coerceIn(1, 8))
                        },
                        valueRange = 1f..8f,
                        steps = 6,
                    )
                }
            }

            item {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("退出并删除群聊") }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这个群聊？") },
            text = { Text("群设置和本机聊天记录都会被删除，此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空群聊聊天信息？") },
            text = { Text("聊天消息、每位成员的原始时间线副本以及由这些消息提取的记忆都会实质删除。群名称和成员设置会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    MigratedDomainStores.chat.clearConversationMessages(conversationId)
                    confirmClear = false
                }) { Text("确认清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun GroupMemberAvatarItem(name: String, avatarUri: String?) {
    Column(
        modifier = Modifier.width(62.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        LuluProfileAvatar(avatarUri, name.take(1).ifBlank { "群" }, 50)
        Text(
            name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 11.sp,
            color = LuluColors.Muted,
        )
    }
}

@Composable
private fun GroupMemberAddItem(onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(62.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Surface(
            modifier = Modifier.size(50.dp),
            shape = RoundedCornerShape(16.dp),
            color = LuluColors.Paper,
            border = BorderStroke(1.dp, LuluColors.Border),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Add, "添加群成员", tint = LuluColors.Ink, modifier = Modifier.size(24.dp))
            }
        }
        Text("添加", fontSize = 11.sp, color = LuluColors.Muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupMemberPickerScreen(
    characters: List<CharacterSettings>,
    existingMemberIds: Set<String>,
    onBack: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedIds by remember(existingMemberIds) { mutableStateOf(emptySet<String>()) }
    val available = remember(characters, existingMemberIds, query) {
        characters.filter { character ->
            character.characterId !in existingMemberIds &&
                (query.isBlank() || character.displayName.contains(query.trim(), ignoreCase = true))
        }
    }

    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = LuluColors.Paper,
        topBar = {
            TopAppBar(
                title = { Text("添加群成员", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                actions = {
                    TextButton(
                        enabled = selectedIds.isNotEmpty(),
                        onClick = { onConfirm(selectedIds.toList()) },
                    ) {
                        Text(
                            if (selectedIds.isEmpty()) "完成" else "完成(${selectedIds.size})",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索角色") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                )
            }
            if (available.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (query.isBlank()) "现有角色都已经在群里啦" else "没有找到这个角色",
                            color = LuluColors.Muted,
                        )
                    }
                }
            } else {
                items(available, key = CharacterSettings::characterId) { character ->
                    val checked = character.characterId in selectedIds
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            selectedIds = if (checked) selectedIds - character.characterId else selectedIds + character.characterId
                        },
                        colors = CardDefaults.cardColors(containerColor = LuluColors.Card),
                        border = BorderStroke(1.dp, LuluColors.Border),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    selectedIds = if (checked) selectedIds - character.characterId else selectedIds + character.characterId
                                },
                            )
                            LuluProfileAvatar(character.avatarUri, character.displayName.take(1).ifBlank { "角" }, 48)
                            Spacer(Modifier.width(12.dp))
                            Text(character.displayName, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupMemberManagementScreen(
    members: List<LuluGroupMember>,
    characters: List<CharacterSettings>,
    onMembersChange: (List<LuluGroupMember>) -> Unit,
    onAddMember: () -> Unit,
    onBack: () -> Unit,
) {
    var pendingRemoval by remember { mutableStateOf<LuluGroupMember?>(null) }
    val rows = members.mapNotNull { member ->
        characters.firstOrNull { it.characterId == member.characterId }?.let { character -> member to character }
    }
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = LuluColors.Paper,
        topBar = {
            TopAppBar(
                title = { Text("群成员管理", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = onAddMember) { Icon(Icons.Outlined.PersonAdd, "添加群成员") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("${members.size + 1} 位成员（包括你）", color = LuluColors.Muted, fontSize = 12.sp)
            }
            items(rows, key = { it.first.characterId }) { (member, character) ->
                GroupSettingsCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        LuluProfileAvatar(character.avatarUri, character.displayName.take(1).ifBlank { "角" }, 48)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(character.displayName, fontWeight = FontWeight.SemiBold)
                            Text(
                                when (member.role) {
                                    LuluGroupRole.Owner -> "群主"
                                    LuluGroupRole.Admin -> "管理员"
                                    LuluGroupRole.Member -> "群成员"
                                },
                                color = LuluColors.Muted,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = member.groupNickname,
                        onValueChange = { nickname ->
                            onMembersChange(members.map {
                                if (it.characterId == member.characterId) it.copy(groupNickname = nickname.take(20)) else it
                            })
                        },
                        label = { Text("群昵称") },
                        placeholder = { Text(character.displayName) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (member.role != LuluGroupRole.Owner) {
                            FilterChip(
                                selected = member.role == LuluGroupRole.Admin,
                                onClick = {
                                    onMembersChange(members.map {
                                        if (it.characterId == member.characterId) {
                                            it.copy(role = if (it.role == LuluGroupRole.Admin) LuluGroupRole.Member else LuluGroupRole.Admin)
                                        } else it
                                    })
                                },
                                label = { Text(if (member.role == LuluGroupRole.Admin) "管理员" else "设为管理员") },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(
                            enabled = members.size > 2 && member.role != LuluGroupRole.Owner,
                            onClick = { pendingRemoval = member },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(Icons.Outlined.PersonRemove, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("移出群聊")
                        }
                    }
                    if (members.size <= 2) {
                        Text("群聊至少保留两位角色，当前成员不可再移出", color = LuluColors.Muted, fontSize = 11.sp)
                    }
                }
            }
        }
    }

    pendingRemoval?.let { member ->
        val name = characters.firstOrNull { it.characterId == member.characterId }?.displayName ?: "该成员"
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("将 $name 移出群聊？") },
            text = { Text("对方会知道自己已被移出，并且不会再读取这个群之后的新消息。") },
            confirmButton = {
                TextButton(onClick = {
                    onMembersChange(members.filterNot { it.characterId == member.characterId })
                    pendingRemoval = null
                }) { Text("移出", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupChatRecordSearchScreen(
    messages: List<LuluChatMessage>,
    characterNames: Map<String, String>,
    userLabel: String,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val results = remember(messages, query) {
        if (query.isBlank()) emptyList() else messages.filter { it.content.contains(query.trim(), ignoreCase = true) }
    }
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = LuluColors.Paper,
        topBar = {
            TopAppBar(
                title = { Text("查找聊天记录", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("关键词") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(if (query.isBlank()) "输入关键词开始查找" else "找到 ${results.size} 条", color = LuluColors.Muted, fontSize = 12.sp)
            }
            items(results, key = LuluChatMessage::id) { message ->
                val speaker = when (message.sender) {
                    LuluChatMessage.Sender.User -> userLabel
                    LuluChatMessage.Sender.System -> "系统"
                    LuluChatMessage.Sender.Character -> message.authorCharacterId?.let { characterNames[it] } ?: "角色"
                }
                GroupSettingsCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(speaker, fontWeight = FontWeight.SemiBold)
                        Text(
                            message.createdAt.atZone(ZoneId.systemDefault()).format(GroupRecordTime),
                            color = LuluColors.Muted,
                            fontSize = 11.sp,
                        )
                    }
                    Text(message.content, maxLines = 5, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun GroupSettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = LuluColors.Card),
        border = BorderStroke(1.dp, LuluColors.Border),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun GroupSettingsAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = LuluColors.Ink)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = LuluColors.Muted, fontSize = 12.sp)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = LuluColors.Muted)
    }
}

@Composable
private fun GroupSettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private val GroupRecordTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
