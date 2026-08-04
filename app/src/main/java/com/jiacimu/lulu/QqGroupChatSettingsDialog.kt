package com.jiacimu.lulu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.CharacterSettings
import com.jiacimu.lulu.data.LuluGroupChat
import com.jiacimu.lulu.data.LuluGroupMember
import com.jiacimu.lulu.data.LuluGroupRole

@Composable
internal fun QqGroupChatSettingsDialog(
    group: LuluGroupChat,
    characters: List<CharacterSettings>,
    onDismiss: () -> Unit,
    onSave: (LuluGroupChat) -> Unit,
    onDelete: () -> Unit,
) {
    var editing by remember(group) { mutableStateOf(group) }
    var confirmDelete by remember { mutableStateOf(false) }
    val memberIds = editing.members.mapTo(mutableSetOf(), LuluGroupMember::characterId)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("群聊设置") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 590.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LuluAvatarPicker(
                            imageUri = editing.avatarUri,
                            fallback = editing.name.take(1).ifBlank { "群" },
                            size = 64,
                            onSelected = { editing = editing.copy(avatarUri = it) },
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("群头像", fontWeight = FontWeight.Bold)
                            Text("点击头像可从手机选择图片", fontSize = 11.sp)
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = editing.name,
                        onValueChange = { editing = editing.copy(name = it.take(30)) },
                        label = { Text("群名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = editing.announcement,
                        onValueChange = { editing = editing.copy(announcement = it.take(500)) },
                        label = { Text("群公告") },
                        placeholder = { Text("所有成员进入群聊时都能看到") },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = editing.userGroupNickname,
                        onValueChange = { editing = editing.copy(userGroupNickname = it.take(20)) },
                        label = { Text("我的群昵称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text("群成员（至少保留两个角色）", fontWeight = FontWeight.Bold)
                }
                items(characters, key = CharacterSettings::characterId) { character ->
                    val member = editing.members.firstOrNull { it.characterId == character.characterId }
                    val checked = member != null
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                editing = toggleGroupMember(editing, character.characterId)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { editing = toggleGroupMember(editing, character.characterId) },
                            )
                            LuluProfileAvatar(character.avatarUri, character.displayName.take(1).ifBlank { "角" }, 38)
                            Spacer(Modifier.width(8.dp))
                            Text(character.displayName, Modifier.weight(1f))
                            if (member != null) {
                                Text(
                                    when (member.role) {
                                        LuluGroupRole.Owner -> "群主"
                                        LuluGroupRole.Admin -> "管理员"
                                        LuluGroupRole.Member -> "成员"
                                    },
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        if (member != null) {
                            OutlinedTextField(
                                value = member.groupNickname,
                                onValueChange = { nickname ->
                                    editing = editing.copy(
                                        members = editing.members.map {
                                            if (it.characterId == character.characterId) it.copy(groupNickname = nickname.take(20)) else it
                                        },
                                    )
                                },
                                label = { Text("${character.displayName}的群昵称") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(start = 42.dp),
                            )
                            Row(Modifier.fillMaxWidth().padding(start = 42.dp)) {
                                FilterChip(
                                    selected = member.role == LuluGroupRole.Admin,
                                    enabled = member.role != LuluGroupRole.Owner,
                                    onClick = {
                                        editing = editing.copy(
                                            members = editing.members.map {
                                                if (it.characterId == character.characterId) {
                                                    it.copy(role = if (it.role == LuluGroupRole.Admin) LuluGroupRole.Member else LuluGroupRole.Admin)
                                                } else it
                                            },
                                        )
                                    },
                                    label = { Text("管理员") },
                                )
                            }
                        }
                    }
                }
                item {
                    HorizontalDivider()
                    GroupSettingSwitch("置顶群聊", editing.pinned) { editing = editing.copy(pinned = it) }
                    GroupSettingSwitch("消息免打扰", editing.muted) { editing = editing.copy(muted = it) }
                    GroupSettingSwitch("显示群成员昵称", editing.showMemberNames) { editing = editing.copy(showMemberNames = it) }
                    GroupSettingSwitch("允许角色互相接话", editing.allowCharacterConversation) {
                        editing = editing.copy(allowCharacterConversation = it)
                    }
                }
                item {
                    Text("每次最多自动回复 ${editing.maxAutoReplies} 条", fontSize = 13.sp)
                    Slider(
                        value = editing.maxAutoReplies.toFloat(),
                        onValueChange = { editing = editing.copy(maxAutoReplies = it.toInt().coerceIn(1, 8)) },
                        valueRange = 1f..8f,
                        steps = 6,
                    )
                }
                item {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("退出并删除群聊", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = editing.name.isNotBlank() && memberIds.size >= 2,
                onClick = { onSave(editing.normalized()) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这个群聊？") },
            text = { Text("群设置和本机聊天记录都会被删除，此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

private fun toggleGroupMember(group: LuluGroupChat, characterId: String): LuluGroupChat {
    val existing = group.members.firstOrNull { it.characterId == characterId }
    return if (existing == null) {
        group.copy(members = group.members + LuluGroupMember(characterId))
    } else if (group.members.size > 2 && existing.role != LuluGroupRole.Owner) {
        group.copy(members = group.members.filterNot { it.characterId == characterId })
    } else {
        group
    }
}

@Composable
private fun GroupSettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
