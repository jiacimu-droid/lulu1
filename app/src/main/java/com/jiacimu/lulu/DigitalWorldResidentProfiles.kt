package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.DigitalWorldExpansionStore
import com.jiacimu.lulu.data.DigitalWorldPublicPlaces
import com.jiacimu.lulu.data.DigitalWorldResident
import com.jiacimu.lulu.data.DigitalWorldStore
import com.jiacimu.lulu.design.LuluColors

/** Native digital residents live alongside, but never inside, the companion character directory. */
@Composable
internal fun DigitalWorldResidentProfiles() {
    val context = LocalContext.current
    remember(context) { DigitalWorldExpansionStore.initialize(context.applicationContext); true }
    val revision by DigitalWorldExpansionStore.revisions.collectAsState()
    val residents = remember(revision) { DigitalWorldExpansionStore.residents() }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider(color = Color(0xFFE1E5E2))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("数字世界居民", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${residents.size} 位", color = LuluColors.Muted, fontSize = 12.sp)
        }
        if (residents.isEmpty()) {
            Text("暂时没有居民", color = LuluColors.Muted, fontSize = 13.sp)
        }
        residents.forEach { resident ->
            val place = when (resident.currentLocationCode) {
                resident.homeLocationCode -> "在家"
                DigitalWorldStore.CLOUD_MEADOW -> "云眠原"
                else -> DigitalWorldPublicPlaces.label(resident.currentLocationCode)
                    ?: DigitalWorldExpansionStore.places().firstOrNull { it.code == resident.currentLocationCode }?.label
                    ?: "外出"
            }
            Surface(
                shape = RoundedCornerShape(19.dp),
                color = LuluColors.Card,
                border = BorderStroke(1.dp, Color(0xFFE1E6E2)),
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(resident.name, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text(resident.identity, color = LuluColors.Muted, fontSize = 12.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(onClick = { editingId = resident.id }) {
                            Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("编辑档案")
                        }
                    }
                    val traits = listOf(resident.temperament, resident.preferences).filter(String::isNotBlank)
                    if (traits.isNotEmpty()) {
                        Text(traits.joinToString(" · "), fontSize = 12.sp, color = Color(0xFF5B6C64),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(place, color = LuluColors.Muted, fontSize = 11.sp)
                }
            }
        }
    }
    residents.firstOrNull { it.id == editingId }?.let { resident ->
        ResidentProfileEditDialog(resident, onDismiss = { editingId = null }, onSaved = { editingId = null })
    }
}

@Composable
private fun ResidentProfileEditDialog(
    resident: DigitalWorldResident,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var name by remember(resident.id) { mutableStateOf(resident.name) }
    var identity by remember(resident.id) { mutableStateOf(resident.identity) }
    var temperament by remember(resident.id) { mutableStateOf(resident.temperament) }
    var preferences by remember(resident.id) { mutableStateOf(resident.preferences) }
    var background by remember(resident.id) { mutableStateOf(resident.background) }
    var error by remember(resident.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${resident.name} · 居民档案") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 450.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(name, { name = it.take(24) }, label = { Text("姓名") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(identity, { identity = it.take(80) }, label = { Text("身份与职业") },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(temperament, { temperament = it.take(400) }, label = { Text("性格") },
                    minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(preferences, { preferences = it.take(400) }, label = { Text("兴趣喜好") },
                    minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(background, { background = it.take(1200) }, label = { Text("背景与经历设定") },
                    minLines = 3, modifier = Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching {
                    DigitalWorldExpansionStore.updateResidentProfile(
                        residentId = resident.id, name = name, identity = identity,
                        temperament = temperament, preferences = preferences, background = background,
                    )
                }.onSuccess { onSaved() }.onFailure { error = it.message ?: "档案保存失败" }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
