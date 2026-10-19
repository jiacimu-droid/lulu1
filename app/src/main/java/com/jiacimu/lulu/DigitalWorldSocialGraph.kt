package com.jiacimu.lulu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private data class RelationshipNode(val id: String, val label: String, val connected: Boolean, val kind: String)

@Composable
internal fun DigitalWorldSocialGraphDialog(
    initialCharacterId: String? = null,
    onDismiss: () -> Unit,
) {
    val settings by MigratedDomainStores.characters.settings.collectAsState()
    val world by DigitalWorldStore.state.collectAsState()
    val revision by CharacterInnerLifeStore.revisions.collectAsState()
    val relationData by CharacterLifeStore.states.collectAsState()
    val participants = settings.values.toList().sortedBy { it.displayName }
    var focusId by remember(initialCharacterId, participants) {
        mutableStateOf(initialCharacterId?.takeIf { it in settings } ?: participants.firstOrNull()?.characterId.orEmpty())
    }
    var selectedId by remember(focusId) { mutableStateOf<String?>(null) }
    var editingRelation by remember(focusId, selectedId) { mutableStateOf(false) }
    var relationshipTitle by remember(focusId, selectedId) { mutableStateOf("") }
    var relationshipDetails by remember(focusId, selectedId) { mutableStateOf("") }
    // Read the existing evidence-backed bonds and genuine encounters; no extra
    // social score or fabricated "friendship" database is introduced.
    val nodes = remember(focusId, settings, world.meetings, revision, relationData) {
        if (focusId.isBlank()) emptyList() else buildList {
            val known = participants.filter { it.characterId != focusId }
            add(RelationshipNode("user", "你", true, "用户"))
            known.forEach { role ->
                val relation = CharacterSocialRelationship.snapshot(focusId, role.characterId)
                val directed = CharacterSocialRelationship.perspective(focusId, role.characterId) != null ||
                    CharacterSocialRelationship.perspective(role.characterId, focusId) != null
                val agreed = CharacterSocialRelationship.agreement(focusId, role.characterId) != null ||
                    CharacterSocialRelationship.agreement(role.characterId, focusId) != null
                add(RelationshipNode(role.characterId, role.displayName.take(7),
                    relation.knowsEachOther || directed || agreed, "角色"))
            }
            val nativeIds = world.meetings.filter { focusId in it.participantIds }
                .flatMap { it.turns.mapNotNull { turn ->
                    turn.speakerId?.takeIf { id -> id.startsWith("resident:") }
                } }.distinct()
            nativeIds.forEach { id ->
                val resident = DigitalWorldExpansionStore.residents()
                    .firstOrNull { "resident:${it.id}" == id }
                add(RelationshipNode(id, resident?.name?.take(7) ?: "世界居民", true, "原住民"))
            }
        }
    }
    val selectedNode = nodes.firstOrNull { it.id == selectedId }
    val agreement = selectedNode?.let { CharacterSocialRelationship.agreement(focusId, it.id) }
    // Reading this state ensures explicit relationship edits refresh this dialog.
    val relationStateKey = relationData[focusId].orEmpty()
    val focalName = settings[focusId]?.displayName ?: "角色"
    val forward = selectedNode?.let { CharacterSocialRelationship.perspective(focusId, it.id) }
    val backward = selectedNode?.takeIf { it.kind == "角色" }
        ?.let { CharacterSocialRelationship.perspective(it.id, focusId) }
    val shared = selectedNode?.takeIf { it.kind == "角色" }
        ?.let { CharacterSocialRelationship.snapshot(focusId, it.id) }
    val displayed = nodes.take(7)

    if (editingRelation && selectedNode != null) {
        AlertDialog(
            onDismissRequest = { editingRelation = false },
            title = { Text("编辑与${selectedNode.label}的关系") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = relationshipTitle,
                        onValueChange = { relationshipTitle = it.take(70) },
                        label = { Text("身份与关系（如：朋友、恋人、主人）") },
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = relationshipDetails,
                        onValueChange = { relationshipDetails = it.take(1600) },
                        label = { Text("共同约定与已确认的关系事实") },
                        modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 9)
                    Text("清空并保存可撤销原约定；角色的主观感受仍由实际经历变化。",
                        style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    CharacterSocialRelationship.setAgreement(focusId, selectedNode.id,
                        relationshipTitle, relationshipDetails)
                    editingRelation = false
                }) { Text("保存关系") }
            },
            dismissButton = { TextButton(onClick = { editingRelation = false }) { Text("取消") } },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("人物关系网络", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("每条线代表真实的认识或相处线索；每个人对另一个人的印象独立保存，不是共享好感值。",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    participants.forEach { character ->
                        FilterChip(
                            selected = focusId == character.characterId,
                            onClick = { focusId = character.characterId; selectedId = null },
                            label = { Text(character.displayName.take(7), maxLines = 1, fontSize = 11.sp) },
                        )
                    }
                }
                if (participants.isEmpty()) {
                    Text("暂时没有创建数字生命。")
                } else {
                    BoxWithConstraints(
                        Modifier.fillMaxWidth().height(256.dp)
                            .background(Color(0xFFF4F8F6), RoundedCornerShape(20.dp)),
                    ) {
                        val r = minOf(92.dp, (maxWidth - 86.dp) / 2)
                        Canvas(Modifier.matchParentSize()) {
                            val middle = Offset(size.width / 2f, size.height / 2f)
                            displayed.forEachIndexed { index, node ->
                                val rad = -PI / 2 + 2 * PI * index / displayed.size.coerceAtLeast(1)
                                val point = Offset(
                                    middle.x + cos(rad).toFloat() * r.toPx(),
                                    middle.y + sin(rad).toFloat() * r.toPx(),
                                )
                                drawLine(
                                    if (node.connected) Color(0xFF478F79) else Color(0xFFC6CFCC),
                                    middle, point, strokeWidth = if (node.connected) 2.4.dp.toPx() else 1.dp.toPx(),
                                )
                            }
                        }
                        Box(
                            Modifier.align(Alignment.Center).size(74.dp)
                                .background(Color(0xFF25473F), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(focalName.take(5), color = Color.White, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, maxLines = 2)
                        }
                        displayed.forEachIndexed { index, node ->
                            val rad = -PI / 2 + 2 * PI * index / displayed.size.coerceAtLeast(1)
                            Box(
                                Modifier.align(Alignment.Center)
                                    .offset(x = (cos(rad) * r.value).toFloat().dp,
                                        y = (sin(rad) * r.value).toFloat().dp)
                                    .size(66.dp)
                                    .background(
                                        if (selectedId == node.id) Color(0xFF90D8BF)
                                        else if (node.connected) Color(0xFFD8EDE3) else Color(0xFFE9ECEA),
                                        CircleShape,
                                    )
                                    .clickable { selectedId = node.id },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(node.label, fontSize = 10.sp, color = Color(0xFF294039),
                                    fontWeight = FontWeight.SemiBold, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 5.dp))
                            }
                        }
                    }
                    if (nodes.size > displayed.size) {
                        Text("另有 ${nodes.size - displayed.size} 位关系对象，可从下方列表查看",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    nodes.forEach { node ->
                        Row(
                            Modifier.fillMaxWidth().clickable { selectedId = node.id }
                                .padding(horizontal = 5.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (node.connected) "●" else "○",
                                color = if (node.connected) Color(0xFF478F79) else Color.Gray,
                                modifier = Modifier.padding(end = 8.dp))
                            Text(node.label, modifier = Modifier.weight(1f), fontSize = 13.sp)
                            Text(if (selectedId == node.id) "查看中" else "查看双方印象",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                        }
                    }
                    if (selectedNode != null) {
                        HorizontalDivider()
                        Text("${focalName} 与 ${selectedNode.label}",
                            fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        if (shared != null) Text(shared.shortContext(), fontSize = 11.sp)
                        Text("当前约定（可随双方确认修改）", fontWeight = FontWeight.Medium,
                            fontSize = 12.sp)
                        Text(agreement?.title?.takeIf(String::isNotBlank) ?: "尚未设定身份约定",
                            fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        agreement?.details?.takeIf(String::isNotBlank)?.let {
                            Text(it, fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            relationshipTitle = agreement?.title.orEmpty()
                            relationshipDetails = agreement?.details.orEmpty()
                            editingRelation = true
                        }) { Text("编辑这段关系的身份与约定") }
                        if (selectedNode.kind == "原住民") {
                            val count = world.meetings.count { session ->
                                focusId in session.participantIds &&
                                    session.turns.any { it.speakerId == selectedNode.id }
                            }
                            Text("真实交流 ${count} 次", fontSize = 11.sp)
                        }
                        Text("${focalName} → ${selectedNode.label}", fontWeight = FontWeight.Medium,
                            color = Color(0xFF357A67), fontSize = 12.sp)
                        Text(forward?.impression ?: "还没有形成可追溯的主观看法",
                            fontSize = 12.sp)
                        forward?.reason?.takeIf(String::isNotBlank)?.let {
                            Text("依据：$it", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        forward?.trend?.takeIf(String::isNotBlank)?.let {
                            Text("近期变化：$it", fontSize = 11.sp)
                        }
                        if (selectedNode.kind == "角色") {
                            Text("${selectedNode.label} → $focalName", fontWeight = FontWeight.Medium,
                                color = Color(0xFF357A67), fontSize = 12.sp)
                            Text(backward?.impression ?: "对方尚没有形成可追溯的主观看法", fontSize = 12.sp)
                            backward?.reason?.takeIf(String::isNotBlank)?.let {
                                Text("依据：$it", fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            backward?.trend?.takeIf(String::isNotBlank)?.let {
                                Text("近期变化：$it", fontSize = 11.sp)
                            }
                        } else if (selectedNode.kind == "用户") {
                            Text("用户的真实态度以你在聊天中明确表达过的内容为准；角色不能替你定义感受。",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        containerColor = Color(0xFFFEFEFD),
    )
}
