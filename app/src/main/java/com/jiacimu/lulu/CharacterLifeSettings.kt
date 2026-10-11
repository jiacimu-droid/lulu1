package com.jiacimu.lulu

import com.jiacimu.lulu.design.LuluAlertDialog as AlertDialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.jiacimu.lulu.data.CharacterInnerLifeStore
import com.jiacimu.lulu.data.sameCharacterMotive
import com.jiacimu.lulu.data.CharacterProfileSchema
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.CommitmentTaskStore
import com.jiacimu.lulu.data.CommitmentTaskStatus
import com.jiacimu.lulu.data.isActive
import com.jiacimu.lulu.system.LuluAlarmSystem
import androidx.compose.ui.platform.LocalContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts


@Composable
internal fun CharacterLifeSettings(characterId: String) {
    val states by CharacterLifeStore.states.collectAsState()
    val innerRevision by CharacterInnerLifeStore.revisions.collectAsState()
    val context = LocalContext.current
    val commitments by CommitmentTaskStore.tasks.collectAsState()
    val activeCommitments = commitments.filter { it.characterId == characterId && it.status.isActive() }
    var permissionsRefresh by remember { mutableIntStateOf(0) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionsRefresh += 1 }
    val exactClockAllowed = remember(permissionsRefresh) { LuluAlarmSystem.canScheduleExact() }
    val notificationsAllowed = remember(permissionsRefresh) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

    val innerRoot = remember(characterId, innerRevision) { CharacterInnerLifeStore.snapshot(characterId) }
    val onlineStates by com.jiacimu.lulu.data.CompanionOnlineStore.states.collectAsState()
    val online = onlineStates[characterId]
    val presenceStates by com.jiacimu.lulu.data.CompanionPresenceStore.states.collectAsState()
    val presence = presenceStates[characterId]
    val growthRevision by com.jiacimu.lulu.data.CharacterDevelopmentStore.revisions.collectAsState()
    val root = remember(states, characterId) { CharacterLifeStore.state(characterId) }
    var editing by remember { mutableStateOf<String?>(null) }
    var addressEditing by remember { mutableStateOf(false) }
    var addressDraft by remember { mutableStateOf("") }
    LaunchedEffect(characterId) { CharacterAddressPreference.refresh(characterId) }
    var draft by remember { mutableStateOf("") }
    var showPastChoices by remember(characterId) { mutableStateOf(false) }
    var showInnerDetails by remember(characterId) { mutableStateOf(false) }
    var showAllGrowth by remember(characterId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("人物人格", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold)
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
        HorizontalDivider()
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
        // This screen is for durable personality and changeable relationship
        // preferences only. Transient actions, desires and inner speech now
        // live in the avatar's shared "此刻" surface.
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
