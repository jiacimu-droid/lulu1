package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.CharacterContactPolicy
import com.jiacimu.lulu.data.MigratedDomainStores

private val CharacterPaper = Color(0xFFFFFDF7)
private val CharacterCard = Color(0xFFFFFBF1)
private val CharacterBorder = Color(0xFFEAE0CC)
private val CharacterMuted = Color(0xFF6D7888)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterSettingsScreen(onBack: () -> Unit) {
    val original = remember { MigratedDomainStores.characters.get("lulu") }
    var displayName by remember { mutableStateOf(original.displayName) }
    var persona by remember { mutableStateOf(original.persona) }
    var contactEnabled by remember { mutableStateOf(original.contactPolicy.enabled) }
    var adaptiveFrequency by remember { mutableStateOf(original.contactPolicy.adaptiveFrequency) }
    var quietHours by remember { mutableStateOf(original.contactPolicy.quietHoursEnabled) }
    var proactiveCalls by remember { mutableStateOf(original.contactPolicy.proactiveCallsEnabled) }

    Scaffold(
        containerColor = CharacterPaper,
        topBar = {
            TopAppBar(
                title = { Text("角色设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CharacterPaper),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                CharacterSettingCard {
                    Text("露露", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text("角色资料只影响角色本身，不与页面样式耦合。", color = CharacterMuted)
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("角色名称") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = persona,
                        onValueChange = { persona = it },
                        label = { Text("角色核心设定") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                }
            }

            item {
                CharacterSettingCard {
                    Text("主动联系", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    SettingSwitch("允许主动联系", "角色可以根据关系与情境主动发消息。", contactEnabled) {
                        contactEnabled = it
                    }
                    SettingSwitch("由角色自适应频率", "不设置固定每日上限；主人忙时自动降低。", adaptiveFrequency) {
                        adaptiveFrequency = it
                    }
                    SettingSwitch("夜间勿扰", "默认参考 23:00—07:00，角色可结合实际情况判断。", quietHours) {
                        quietHours = it
                    }
                }
            }

            item {
                CharacterSettingCard {
                    Text("主动来电", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    SettingSwitch("允许主动来电", "来电时间段与铃声会在通话设置中继续完善。", proactiveCalls) {
                        proactiveCalls = it
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        MigratedDomainStores.characters.update(
                            original.copy(
                                displayName = displayName.trim().ifBlank { "露露" },
                                persona = persona.trim(),
                                contactPolicy = CharacterContactPolicy(
                                    enabled = contactEnabled,
                                    adaptiveFrequency = adaptiveFrequency,
                                    quietHoursEnabled = quietHours,
                                    proactiveCallsEnabled = proactiveCalls,
                                ),
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text("保存角色设置", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CharacterSettingCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CharacterCard),
        border = BorderStroke(1.dp, CharacterBorder),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = CharacterMuted, fontSize = 13.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
