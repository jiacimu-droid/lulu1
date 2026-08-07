package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.core.PromiseKind
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.design.LuluColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val LEXICON_PAGE_SIZE = 40

private val LexiconV2Sections = listOf(
    LexiconSection.Life to "生活",
    LexiconSection.Concern to "挂心",
    LexiconSection.Promise to "约定",
    LexiconSection.Diary to "日记",
    LexiconSection.Favorite to "收藏",
)

private val LexiconV2PromiseKinds = listOf(
    PromiseKind.Promise to "承诺",
    PromiseKind.Responsibility to "责任",
    PromiseKind.Reminder to "提醒",
    PromiseKind.LongTermSupervision to "长期监督",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LexiconFeatureScreenV2(
    onBack: () -> Unit,
    initialCharacterId: String? = null,
    initialDiaryTitle: String? = null,
    embedded: Boolean = false,
) {
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val sortedCharacters = remember(characters) { characters.values.sortedBy { it.displayName } }
    var selectedCharacterId by rememberSaveable(initialCharacterId) {
        mutableStateOf(
            initialCharacterId?.takeIf { it in characters }
                ?: characters.keys.firstOrNull()
                ?: "lulu",
        )
    }
    LaunchedEffect(characters.keys, selectedCharacterId, initialCharacterId) {
        if (!initialCharacterId.isNullOrBlank() && initialCharacterId in characters && selectedCharacterId != initialCharacterId) {
            selectedCharacterId = initialCharacterId
        } else if (selectedCharacterId !in characters && characters.isNotEmpty()) {
            selectedCharacterId = characters.keys.first()
        }
    }
    val diarySectionIndex = remember { LexiconV2Sections.indexOfFirst { it.first == LexiconSection.Diary }.coerceAtLeast(0) }
    var sectionIndex by rememberSaveable(initialDiaryTitle) {
        mutableIntStateOf(if (initialDiaryTitle.isNullOrBlank()) 0 else diarySectionIndex)
    }
    val section = LexiconV2Sections[sectionIndex].first
    val entries by LuluRepositories.lexicon
        .observeEntries(selectedCharacterId, section)
        .collectAsState(initial = emptyList())
    var visibleCount by rememberSaveable(selectedCharacterId, section) { mutableIntStateOf(LEXICON_PAGE_SIZE) }
    val visibleEntries = remember(entries, visibleCount) { entries.take(visibleCount) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<LexiconEntry?>(null) }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(section) {
        if (section == LexiconSection.Favorite) {
            creating = false
            editing = null
        }
    }

    LaunchedEffect(initialDiaryTitle, selectedCharacterId, section, entries) {
        val title = initialDiaryTitle?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        if (section != LexiconSection.Diary) return@LaunchedEffect
        val targetIndex = entries.indexOfFirst { it.title == title }
        if (targetIndex >= 0) {
            if (targetIndex >= visibleCount) visibleCount = targetIndex + 1
            listState.scrollToItem(targetIndex)
        }
    }

    Scaffold(
        containerColor = LuluColors.Paper,
        topBar = {
            if (!embedded) {
                TopAppBar(
                    title = { Text("辞海", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
                    },
                    actions = {
                        if (section != LexiconSection.Favorite) {
                            IconButton(onClick = { creating = true }) { Icon(Icons.Outlined.Add, "新增条目") }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (embedded) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("辞海", fontWeight = FontWeight.Bold, fontSize = 23.sp, color = LuluColors.Ink)
                        Text("角色自己的生活、挂心、约定、日记与收藏", color = LuluColors.Muted, fontSize = 11.sp)
                    }
                    if (section != LexiconSection.Favorite) {
                        IconButton(onClick = { creating = true }) { Icon(Icons.Outlined.Add, "新增条目") }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sortedCharacters.forEach { character ->
                    FilterChip(
                        selected = selectedCharacterId == character.characterId,
                        onClick = { selectedCharacterId = character.characterId },
                        label = { Text(character.displayName) },
                        leadingIcon = {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = LuluColors.WheatSoft,
                                modifier = Modifier.size(24.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        character.displayName.take(1).ifBlank { "角" },
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        },
                    )
                }
            }

            ScrollableTabRow(
                selectedTabIndex = sectionIndex,
                containerColor = LuluColors.Card,
                edgePadding = 12.dp,
            ) {
                LexiconV2Sections.forEachIndexed { index, (_, label) ->
                    Tab(
                        selected = sectionIndex == index,
                        onClick = { sectionIndex = index },
                        text = { Text(label) },
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (entries.isEmpty()) {
                    item {
                        LexiconV2Card {
                            Text("还没有${LexiconV2Sections[sectionIndex].second}记录", fontWeight = FontWeight.Bold)
                            if (section == LexiconSection.Favorite) {
                                Text("角色在聊天时真心想留下来的主人消息，会出现在这里。", color = LuluColors.Muted, fontSize = 12.sp)
                            }
                        }
                    }
                } else {
                    items(visibleEntries, key = LexiconEntry::id) { entry ->
                        when (entry.section) {
                            LexiconSection.Diary -> DiaryEntryCard(
                                entry = entry,
                                characterName = characters[entry.characterId]?.displayName ?: "角色",
                                highlighted = !initialDiaryTitle.isNullOrBlank() && entry.title == initialDiaryTitle,
                                onEdit = { editing = entry },
                                onDelete = { scope.launch { LuluRepositories.lexicon.delete(entry.id) } },
                            )
                            LexiconSection.Favorite -> FavoriteEntryCard(
                                entry = entry,
                                characterName = characters[entry.characterId]?.displayName ?: "角色",
                                onDelete = { scope.launch { LuluRepositories.lexicon.delete(entry.id) } },
                            )
                            else -> LexiconV2Card {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                        if (entry.section == LexiconSection.Promise) {
                                            Text(
                                                LexiconV2PromiseKinds
                                                    .firstOrNull { (kind, _) -> kind == entry.promiseKind }
                                                    ?.second
                                                    ?: "承诺",
                                                color = LuluColors.Muted,
                                                fontSize = 11.sp,
                                            )
                                        }
                                        Text(entry.content, color = LuluColors.Muted)
                                    }
                                    IconButton(onClick = { editing = entry }) {
                                        Icon(Icons.Outlined.Edit, "编辑")
                                    }
                                    IconButton(onClick = {
                                        scope.launch { LuluRepositories.lexicon.delete(entry.id) }
                                    }) {
                                        Icon(
                                            Icons.Outlined.DeleteOutline,
                                            "删除",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (visibleEntries.size < entries.size) {
                        item(key = "load-more") {
                            OutlinedButton(
                                onClick = { visibleCount = (visibleCount + LEXICON_PAGE_SIZE).coerceAtMost(entries.size) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("继续加载（${entries.size - visibleEntries.size}）")
                            }
                        }
                    }
                }
            }
        }
    }

    val dialogEntry = editing ?: LexiconEntry(
        id = UUID.randomUUID().toString(),
        characterId = selectedCharacterId,
        section = section,
        title = "",
        content = "",
        promiseKind = if (section == LexiconSection.Promise) PromiseKind.Promise else null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    ).takeIf { creating && section != LexiconSection.Favorite }

    dialogEntry?.let { entry ->
        LexiconEditorDialogV2(
            entry = entry,
            isNew = creating,
            onDismiss = {
                editing = null
                creating = false
            },
            onSave = { saved ->
                scope.launch { LuluRepositories.lexicon.save(saved) }
                editing = null
                creating = false
            },
            onDelete = {
                scope.launch { LuluRepositories.lexicon.delete(entry.id) }
                editing = null
                creating = false
            },
        )
    }
}

@Composable
private fun FavoriteEntryCard(
    entry: LexiconEntry,
    characterName: String,
    onDelete: () -> Unit,
) {
    LexiconV2Card {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(entry.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("$characterName 收藏的主人消息", color = LuluColors.Muted, fontSize = 11.sp)
                Text("“${entry.content}”", color = LuluColors.Ink, fontSize = 15.sp, lineHeight = 22.sp)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteOutline, "删除收藏", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun DiaryEntryCard(
    entry: LexiconEntry,
    characterName: String,
    highlighted: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (highlighted) LuluColors.WheatSoft else Color(0xFFFFFEFA)),
        border = BorderStroke(if (highlighted) 2.dp else 1.dp, if (highlighted) LuluColors.Ink else Color(0xFFE8E0D2)),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = LuluColors.Ink,
                    contentColor = Color.White,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(entry.updatedAt.atZone(ZoneId.systemDefault()).dayOfMonth.toString(), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        "${entry.updatedAt.atZone(ZoneId.systemDefault()).format(DiaryTimeFormatter)} · $characterName",
                        color = LuluColors.Muted,
                        fontSize = 11.sp,
                    )
                }
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "编辑日记") }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "删除日记", tint = MaterialTheme.colorScheme.error) }
            }
            HorizontalDivider(color = Color(0xFFE8E0D2))
            Row(Modifier.padding(horizontal = 18.dp, vertical = 18.dp), verticalAlignment = Alignment.Top) {
                Text("“", color = Color(0xFFC8BCA8), fontSize = 34.sp, lineHeight = 30.sp)
                Spacer(Modifier.width(6.dp))
                Text(entry.content, color = LuluColors.Ink, fontSize = 15.sp, lineHeight = 24.sp)
            }
        }
    }
}

private val DiaryTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm")

@Composable
private fun LexiconEditorDialogV2(
    entry: LexiconEntry,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (LexiconEntry) -> Unit,
    onDelete: () -> Unit,
) {
    var title by remember(entry.id) { mutableStateOf(entry.title) }
    var content by remember(entry.id) { mutableStateOf(entry.content) }
    var promiseKind by remember(entry.id) {
        mutableStateOf(entry.promiseKind ?: PromiseKind.Promise)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新增条目" else "编辑条目") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("内容") },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (entry.section == LexiconSection.Promise) {
                    LexiconV2PromiseKinds.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { (kind, label) ->
                                FilterChip(
                                    selected = promiseKind == kind,
                                    onClick = { promiseKind = kind },
                                    label = { Text(label) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && content.isNotBlank(),
                onClick = {
                    onSave(
                        entry.copy(
                            title = title.trim(),
                            content = content.trim(),
                            promiseKind = if (entry.section == LexiconSection.Promise) promiseKind else null,
                            updatedAt = Instant.now(),
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun LexiconV2Card(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = LuluColors.Card),
        border = BorderStroke(1.dp, LuluColors.Border),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            content = content,
        )
    }
}
