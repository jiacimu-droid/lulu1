package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.core.*
import com.jiacimu.lulu.data.*
import com.jiacimu.lulu.design.LuluColors
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

internal fun recordTime(at: Instant): String = at.atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

/** Desktop and personal navigation share the same original sources and deletion paths. */
@Composable
fun MemoryFeatureScreen(onBack: () -> Unit, personalCharacterId: String? = null,
    startInCore: Boolean = false, initialCharacterId: String? = null,
    startInTimeline: Boolean = false) {
    val repository = LuluRepositories.memory
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    var characterId by rememberSaveable(personalCharacterId, initialCharacterId) {
        mutableStateOf(personalCharacterId ?: initialCharacterId ?: characters.keys.firstOrNull() ?: "lulu")
    }
    var area by rememberSaveable(startInTimeline, startInCore) {
        mutableIntStateOf(if (startInTimeline) 2 else if (startInCore) 1 else 0)
    }
    var search by rememberSaveable(characterId) { mutableStateOf("") }
    var characterMenu by remember { mutableStateOf(false) }
    var management by remember { mutableStateOf(false) }
    var editingMemory by remember { mutableStateOf<MemoryEntry?>(null) }
    var editingNote by remember { mutableStateOf<LexiconEntry?>(null) }
    var deletingEvent by remember { mutableStateOf<SharedTimelineEvent?>(null) }
    var count by rememberSaveable(characterId, area, search) { mutableIntStateOf(40) }
    val memories by repository.observeMemories(characterId).collectAsState(initial = emptyList())
    val validityRevision by MemoryValidityStore.revisions.collectAsState()
    val life by LuluRepositories.lexicon.observeEntries(characterId, LexiconSection.Life).collectAsState(initial = emptyList())
    val concerns by LuluRepositories.lexicon.observeEntries(characterId, LexiconSection.Concern).collectAsState(initial = emptyList())
    val knowledgeRevision by CharacterLearningLedger.revisions.collectAsState()
    val ownerRevision by CharacterDevelopmentStore.revisions.collectAsState()
    val timelineRevision by SharedExperienceTimeline.revisions.collectAsState()
    val ownerFacts = remember(characterId, ownerRevision, timelineRevision) {
        CharacterDevelopmentStore.active(characterId)
            .filter { it.kind in setOf(DevelopmentKind.UserPreference, DevelopmentKind.UserHabit, DevelopmentKind.VerifiedMethod) }
            .sortedByDescending { it.createdAt }
    }
    val ownerMatches = ownerFacts.filter {
        search.isBlank() || (it.kind.label + it.content).contains(search.trim(), ignoreCase = true)
    }
    val scope = rememberCoroutineScope()
    val notices = remember { SnackbarHostState() }
    LaunchedEffect(characters.keys, personalCharacterId) {
        if (personalCharacterId == null && characterId !in characters && characters.isNotEmpty())
            characterId = characters.keys.first()
    }
    val rememberedState by produceState("" to emptyList<CharacterRememberedItem>(),
        characterId, memories, life, concerns, knowledgeRevision, timelineRevision, validityRevision) {
        value = characterId to withContext(Dispatchers.IO) {
            CharacterRecordProjection.remembered(characterId, memories,
                CharacterLearningLedger.listInsights(characterId), life + concerns)
        }
    }
    val remembered = if (rememberedState.first == characterId) rememberedState.second.filter {
        search.isBlank() || (it.title + it.content).contains(search.trim(), true)
    } else emptyList()
    val coreRemembered = remembered.filter { it.memory?.isCoreMemory() == true }
    val activeCoreCount = memories.count { it.isCoreMemory() &&
        MemoryValidityStore.isActive(it.id) && memoryHasLiveSources(it, characterId) }
    val timelineState by produceState("" to emptyList<SharedTimelineEvent>(), characterId, timelineRevision) {
        value = characterId to withContext(Dispatchers.IO) { SharedExperienceTimeline.all(characterId).asReversed() }
    }
    val timeline = remember(characterId, timelineState, search) {
        timelineState.second.filter { it.characterId == characterId &&
            (search.isBlank() || (it.channel + it.speaker + CharacterRecordProjection.eventText(it))
                .contains(search.trim(), true))
        }
    }
    Scaffold(containerColor = LuluColors.Paper, snackbarHost = { SnackbarHost(notices) }, topBar = {
        TopAppBar(title = { Column {
            Text("记忆", fontWeight = FontWeight.SemiBold)
            if (personalCharacterId != null) Text(characters[characterId]?.displayName.orEmpty(),
                fontSize = 12.sp, color = LuluColors.Muted)
        } }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
            actions = {
                if (personalCharacterId == null) Box {
                    TextButton(onClick = { characterMenu = true }) {
                        Text(characters[characterId]?.displayName ?: "选择角色")
                        Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(17.dp))
                    }
                    DropdownMenu(characterMenu, onDismissRequest = { characterMenu = false }) {
                        characters.values.sortedBy { it.displayName }.forEach { character ->
                            DropdownMenuItem(text = { Text(character.displayName) }, onClick = {
                                characterId = character.characterId; characterMenu = false
                            })
                        }
                    }
                }
                IconButton(onClick = {
                    editingMemory = MemoryEntry(UUID.randomUUID().toString(), characterId, "", MemoryKind.Fact,
                        "手动", null, Instant.now(), 5, false, true)
                }) { Icon(Icons.Outlined.Add, "添加记忆") }
                IconButton(onClick = { management = true }) { Icon(Icons.Outlined.MoreHoriz, "记忆管理") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            CharacterSectionTabs(listOf("记住的事", "核心记忆", "原始时间线", "关于主人"), area, onSelect = { area = it })
            OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
                placeholder = { Text(when (area) { 2 -> "搜索原始记录"; 3 -> "搜索偏好与习惯"; else -> "搜索记忆" }) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                shape = RoundedCornerShape(16.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (area == 3) {
                    if (ownerMatches.isEmpty()) item {
                        Text("还没有发现明确的偏好或相处经验", color = LuluColors.Muted)
                    }
                    items(ownerMatches.take(count), key = { "owner:${it.id}" }) { record ->
                        var expanded by remember(record.id) { mutableStateOf(false) }
                        CharacterRecordCard {
                            Text(record.kind.label, color = LuluColors.Muted, fontSize = 12.sp)
                            Text(record.content, fontSize = 16.sp, lineHeight = 24.sp)
                            Text(if (record.maturity == DevelopmentMaturity.Established) "多次得到支持" else "初步认识",
                                color = LuluColors.Muted, fontSize = 11.sp)
                            TextButton(onClick = { expanded = !expanded }) {
                                Text(if (expanded) "收起依据" else "查看来源")
                            }
                            if (expanded) {
                                CharacterEvidenceDetails(characterId,
                                    record.evidence.keys + record.counterEvidence.keys, showInfluences = false)
                                TextButton(onClick = { CharacterDevelopmentStore.retire(characterId, record.id) }) {
                                    Text("撤回这项认识", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                } else if (area != 2) {
                    val rows = if (area == 1) coreRemembered else remembered
                    if (area == 1) item(key = "core-intro") {
                        Surface(color = Color(0xFFE8F3EF),
                            shape = RoundedCornerShape(17.dp)) {
                            Column(Modifier.fillMaxWidth().padding(15.dp)) {
                                Text("核心记忆 · $activeCoreCount 条常驻",
                                    color = Color(0xFF315C57), fontWeight = FontWeight.SemiBold)
                                Text("直接进入每次角色请求的固定上下文，不依靠话题检索。" +
                                    "点一条记忆的「查看来源与影响」可编辑、取消核心或删除。",
                                    fontSize = 12.sp, lineHeight = 19.sp,
                                    color = Color(0xFF607F77))
                            }
                        }
                    }
                    if (rows.isEmpty()) item {
                        Text(if (area == 1) "还没有核心记忆，点右上角 + 创建或把已有记忆设为核心"
                            else "还没有匹配的记忆", color = LuluColors.Muted)
                    }
                    items(rows.take(count), key = { it.id }) { row ->
                        RememberedItemCard(row, characterId, onEdit = {
                            editingMemory = row.memory; editingNote = row.note
                        }, onDelete = { scope.launch {
                            row.memory?.let { repository.delete(it.id) }
                            row.note?.let { LuluRepositories.lexicon.delete(it.id) }
                            row.insight?.let { CharacterLearningLedger.deleteInsight(characterId, it.sourceEvidenceId) }
                        } }, onPin = { row.memory?.let { scope.launch { repository.togglePinned(it.id) } } },
                            onRecall = { row.memory?.let { scope.launch { repository.toggleRecall(it.id) } } })
                    }
                } else {
                    if (timeline.isEmpty()) item { Text("还没有匹配的原始记录", color = LuluColors.Muted) }
                    items(timeline.take(count), key = { it.id }) { event ->
                        CharacterOriginalEventCard(event, onDelete = { deletingEvent = event })
                    }
                }
                val total = when (area) { 0 -> remembered.size; 1 -> coreRemembered.size; 2 -> timeline.size; else -> ownerMatches.size }
                if (total > count) item { TextButton(onClick = { count += 40 }, modifier = Modifier.fillMaxWidth()) {
                    Text("继续加载 · 还有 ${total - count} 条")
                } }
            }
        }
    }
    if (management) MemoryManagementSheet(characterId, personalCharacterId != null, memories,
        onDismiss = { management = false }, onNotice = { scope.launch { notices.showSnackbar(it) } })
    editingMemory?.let { memory ->
        MemoryEditorDialog(memory, isNew = memory.content.isBlank(), onDismiss = { editingMemory = null },
            onSave = { scope.launch { repository.save(it) }; editingMemory = null },
            onDelete = { scope.launch { repository.delete(memory.id) }; editingMemory = null })
    }
    editingNote?.let { note ->
        LexiconEditorDialogV2(note, isNew = false, onDismiss = { editingNote = null },
            onSave = { scope.launch { LuluRepositories.lexicon.save(it) }; editingNote = null },
            onDelete = { scope.launch { LuluRepositories.lexicon.delete(note.id) }; editingNote = null })
    }
    deletingEvent?.let { event ->
        AlertDialog(onDismissRequest = { deletingEvent = null }, title = { Text("删除这条原始记录？") },
            text = { Text("依赖它的记忆、学习理解和人物变化会同步撤回。") },
            confirmButton = { TextButton(onClick = {
                SharedExperienceTimeline.deleteEvent(event.id); deletingEvent = null
            }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deletingEvent = null }) { Text("取消") } })
    }
}

@Composable
internal fun CharacterRecordCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, LuluColors.Border)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun RememberedItemCard(row: CharacterRememberedItem, characterId: String, onEdit: () -> Unit,
    onDelete: () -> Unit, onPin: () -> Unit, onRecall: () -> Unit) {
    var expanded by rememberSaveable(row.id) { mutableStateOf(false) }
    CharacterRecordCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (row.insight != null) "知识与理解 · ${row.title}" else row.title,
                color = LuluColors.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (row.memory?.isCoreMemory() == true) Icon(Icons.Outlined.PushPin, "置顶记忆", Modifier.size(16.dp))
        }
        Text(row.content, fontSize = 16.sp, lineHeight = 25.sp, color = LuluColors.Ink)
        Text(recordTime(row.at), fontSize = 11.sp, color = LuluColors.Muted)
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
            Text(if (expanded) "收起详情" else "查看来源与影响")
        }
        if (expanded) {
            val knowledgeRevision by CharacterLearningLedger.revisions.collectAsState()
            val trail = remember(characterId, row.id, knowledgeRevision) {
                row.insight?.let { CharacterLearningLedger.growthTrace(characterId,
                    sourceEvidenceId = it.sourceEvidenceId).filter { it.label != "读后形成暂定理解" } }.orEmpty()
            }
            row.insight?.let { insight ->
                listOf("仍不确定" to insight.uncertainty, "还想弄清" to insight.question,
                    "可能适用" to insight.applicableWhen).filter { it.second.isNotBlank() }.forEach {
                    Text("${it.first}：${it.second}", fontSize = 13.sp)
                }
                if (trail.isNotEmpty()) Text("后来的尝试与反馈", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                trail.forEach { step ->
                    Text("${step.label} · ${recordTime(step.at)}", color = LuluColors.Muted, fontSize = 11.sp)
                    Text(step.detail, fontSize = 13.sp, lineHeight = 21.sp)
                }
            }
            CharacterEvidenceDetails(characterId, (row.sourceIds + trail.map { it.evidenceId }).distinct())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (row.memory != null || row.note != null) TextButton(onClick = onEdit) { Text("编辑") }
                row.memory?.let { memory ->
                    TextButton(onClick = onPin) { Text(if (memory.isCoreMemory()) "取消置顶" else "置顶") }
                    TextButton(onClick = onRecall) { Text(if (memory.canRecallProactively) "停止主动回忆" else "允许主动回忆") }
                }
            }
            TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
internal fun CharacterEvidenceDetails(characterId: String, evidenceIds: Collection<String>, showInfluences: Boolean = true) {
    val timelineRevision by SharedExperienceTimeline.revisions.collectAsState()
    val developmentRevision by CharacterDevelopmentStore.revisions.collectAsState()
    val sources = remember(characterId, evidenceIds, timelineRevision) {
        SharedExperienceTimeline.eventsByIds(characterId, evidenceIds)
    }
    val effects = remember(characterId, evidenceIds, timelineRevision, developmentRevision, showInfluences) {
        if (showInfluences) CharacterRecordProjection.influences(characterId, evidenceIds) else emptyList()
    }
    if (sources.isEmpty()) Text(if (evidenceIds.isEmpty()) "手动记录" else "原始依据已撤回",
        color = LuluColors.Muted, fontSize = 12.sp)
    sources.forEach { source ->
        HorizontalDivider(color = LuluColors.Border)
        Text("${source.channel} · ${recordTime(source.occurredAt)}", fontSize = 11.sp, color = LuluColors.Muted)
        Text(CharacterRecordProjection.eventText(source), fontSize = 13.sp, lineHeight = 21.sp)
    }
    if (effects.isNotEmpty()) {
        Text("后来形成的持续影响", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        effects.forEach { Text("${it.kind.label}：${it.content}", fontSize = 13.sp, lineHeight = 21.sp) }
    }
}

@Composable
private fun CharacterOriginalEventCard(event: SharedTimelineEvent, onDelete: () -> Unit) {
    var expanded by rememberSaveable(event.id) { mutableStateOf(false) }
    val developmentRevision by CharacterDevelopmentStore.revisions.collectAsState()
    val effects = remember(event.id, developmentRevision, expanded) {
        if (expanded) CharacterRecordProjection.influences(event.characterId, listOf(event.id)) else emptyList()
    }
    CharacterRecordCard {
        Text("${event.channel} · ${event.speaker}", color = LuluColors.Muted, fontSize = 12.sp)
        Text(CharacterRecordProjection.eventText(event), fontSize = 15.sp, lineHeight = 24.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Text(recordTime(event.occurredAt), fontSize = 11.sp, color = LuluColors.Muted)
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "查看详情") }
        if (expanded) {
            Text(event.evidenceLabel, fontSize = 12.sp, color = LuluColors.Muted)
            effects.forEach { Text("${it.kind.label}：${it.content}", fontSize = 13.sp) }
            TextButton(onClick = onDelete) { Text("删除原始记录", color = MaterialTheme.colorScheme.error) }
        }
    }
}
