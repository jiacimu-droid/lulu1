package com.jiacimu.lulu

import com.jiacimu.lulu.design.LuluAlertDialog as AlertDialog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.core.PromiseKind
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.CharacterLifeVisibility
import com.jiacimu.lulu.data.CharacterInnerLifeStore
import com.jiacimu.lulu.data.CharacterCuriosityRuntime
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.CharacterRecordDestination
import com.jiacimu.lulu.data.WorldLifeEpisodeStore
import com.jiacimu.lulu.data.CommitmentTaskStore
import com.jiacimu.lulu.data.CommitmentTaskStatus
import com.jiacimu.lulu.data.isActive
import com.jiacimu.lulu.design.LuluColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val LEXICON_PAGE_SIZE = 40
private const val LIFE_STORY_INDEX = 2
private const val FAVORITE_REASON_SEPARATOR = "\n\n收藏理由："
private val LexiconV2PromiseKinds = listOf(
    PromiseKind.Promise to "承诺", PromiseKind.Responsibility to "责任",
    PromiseKind.Reminder to "提醒", PromiseKind.LongTermSupervision to "长期监督",
)

/** Personal expressions live here; factual notes and learning belong to memory. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LexiconFeatureScreenV2(
    onBack: () -> Unit,
    initialCharacterId: String? = null,
    initialDiaryTitle: String? = null,
    initialSectionName: String? = null,
    initialFavoriteEntryId: String? = null,
    embedded: Boolean = false,
    personal: Boolean = false,
    onCharacterSelected: (String) -> Unit = {},
    pageTitle: String = "生活记录",
) {
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    var characterId by rememberSaveable(initialCharacterId) {
        mutableStateOf(initialCharacterId ?: characters.keys.firstOrNull() ?: "lulu")
    }
    val requested = remember(initialSectionName, initialDiaryTitle, initialFavoriteEntryId) {
        when (CharacterRecordDestination.fromLegacy(initialSectionName, initialDiaryTitle, initialFavoriteEntryId)) {
            CharacterRecordDestination.Diary -> -1 // old diary deep-links open the safe life-record home
            CharacterRecordDestination.Favorites -> 1
            CharacterRecordDestination.Stories -> 2
            CharacterRecordDestination.Moments -> 3
            else -> -1
        }
    }
    var sectionIndex by rememberSaveable(initialSectionName, initialDiaryTitle, initialFavoriteEntryId) { mutableIntStateOf(requested) }
    val isHome = sectionIndex < 0
    val isLifeStory = sectionIndex == LIFE_STORY_INDEX
    val section = if (sectionIndex == 1) LexiconSection.Favorite else LexiconSection.Diary
    val context = LocalContext.current
    LaunchedEffect(Unit) { WorldLifeEpisodeStore.initialize(context) }
    LaunchedEffect(characters.keys, personal) {
        if (!personal && characterId !in characters && characters.isNotEmpty()) characterId = characters.keys.first()
    }
    val lifeStories by WorldLifeEpisodeStore.state.collectAsState()
    val selectedCharacterId = characterId
    val storyEntries = remember(selectedCharacterId, lifeStories) {
        lifeStories[selectedCharacterId].orEmpty().sortedByDescending { it.occurredAt }
    }
    val collectedEntries by LuluRepositories.lexicon.observeEntries(characterId, section).collectAsState(initial = emptyList())
    val collectedFavorites by LuluRepositories.lexicon.observeEntries(characterId, LexiconSection.Favorite).collectAsState(initial = emptyList())
    val entries = collectedEntries.filter { it.characterId == characterId && it.section == section }
    val favorites = collectedFavorites.filter { it.characterId == characterId }
    var visibleCount by rememberSaveable(characterId, section) { mutableIntStateOf(LEXICON_PAGE_SIZE) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var selectedEpisodeId by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<LexiconEntry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var characterMenu by remember { mutableStateOf(false) }
    val title = when (sectionIndex) { 0 -> "日记"; 1 -> "收藏"; 2 -> "小剧场"; 3 -> "朋友圈"; 4 -> "心事"; else -> pageTitle }
    val back = { if (isHome) onBack() else { sectionIndex = -1; Unit } }
    androidx.activity.compose.BackHandler(enabled = !isHome) { sectionIndex = -1 }
    LaunchedEffect(characterId, section, entries, initialDiaryTitle, initialFavoriteEntryId) {
        val index = entries.indexOfFirst {
            (!initialDiaryTitle.isNullOrBlank() && it.title == initialDiaryTitle) ||
                (!initialFavoriteEntryId.isNullOrBlank() && it.id == initialFavoriteEntryId)
        }
        if (index >= 0) { visibleCount = maxOf(visibleCount, index + 1); listState.scrollToItem(index) }
    }
    Scaffold(containerColor = LuluColors.Paper, topBar = {
        TopAppBar(title = { Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (personal) Text(characters[characterId]?.displayName.orEmpty(), fontSize = 12.sp, color = LuluColors.Muted)
        } }, navigationIcon = { if (!embedded) IconButton(onClick = back) { Icon(Icons.Outlined.ArrowBack, "返回") } },
            actions = {
                if (!personal) Box {
                    TextButton(onClick = { characterMenu = true }) {
                        Text(characters[characterId]?.displayName ?: "选择角色")
                        Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(17.dp))
                    }
                    DropdownMenu(characterMenu, onDismissRequest = { characterMenu = false }) {
                        characters.values.sortedBy { it.displayName }.forEach { character ->
                            DropdownMenuItem(text = { Text(character.displayName) }, onClick = {
                                characterId = character.characterId; onCharacterSelected(characterId); characterMenu = false
                            })
                        }
                    }
                }
                // Diary entry creation is retired; existing private entries stay in local storage.
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (isHome) {
                LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(listOf("心事" to 4, "小剧场" to 2, "收藏" to 1, "朋友圈" to 3), key = { it.second }) { (label, index) ->
                        Surface(color = Color.White, shape = RoundedCornerShape(19.dp),
                            border = BorderStroke(1.dp, LuluColors.Border),
                            modifier = Modifier.fillMaxWidth().clickable { sectionIndex = index }) {
                            Row(Modifier.padding(horizontal = 21.dp, vertical = 25.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                val number = when (index) { 2 -> storyEntries.size; 0 -> entries.size; 1 -> favorites.size; else -> null }
                                if (number != null) Text(number.toString(), fontSize = 12.sp, color = LuluColors.Muted)
                                Spacer(Modifier.width(12.dp))
                                Icon(Icons.Outlined.ChevronRight, "打开$label", tint = LuluColors.Muted)
                            }
                        }
                    }
                }
            } else if (sectionIndex == 3) {
                MomentsScreen(characterId = characterId)
            } else if (sectionIndex == 4) {
                CharacterLifeMattersPage(characterId, Modifier.weight(1f))
            } else if (isLifeStory) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 42.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item(key = "story-intro") {
                        Column(Modifier.padding(bottom = 9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("生活小剧场", fontSize = 25.sp, fontWeight = FontWeight.SemiBold,
                                color = LuluColors.Ink)
                            Text("有意思的独处、相遇与生活事件 · ${storyEntries.size} 篇",
                                fontSize = 12.sp, color = LuluColors.Muted)
                        }
                    }
                    if (storyEntries.isEmpty()) {
                        item(key = "no-stories") {
                            LexiconV2Card {
                                Text("还没有生活小剧场", fontWeight = FontWeight.SemiBold)
                                Text("角色实际经历了值得回看的生活片段，才会写成故事；相遇、交流与生活事件也会收进这里。",
                                    fontSize = 12.sp, color = LuluColors.Muted)
                            }
                        }
                    } else {
                        items(storyEntries, key = { "story:" + it.id }) { episode ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable { selectedEpisodeId = episode.id },
                                color = Color(0xFFFCF9F3),
                                shape = RoundedCornerShape(19.dp),
                                border = BorderStroke(1.dp, Color(0xFFE8E0D6)),
                            ) {
                                Column(Modifier.padding(horizontal = 21.dp, vertical = 22.dp),
                                    verticalArrangement = Arrangement.spacedBy(11.dp)) {
                                    Text(episode.occurredAt.atZone(ZoneId.systemDefault())
                                        .format(DateTimeFormatter.ofPattern("yyyy / MM / dd    HH:mm")),
                                        fontSize = 11.sp, color = Color(0xFF968575))
                                    Text(episode.category.let { if (it == "encounter") "相遇 · 交谈" else if (it == "incident") "生活事件" else "个人片刻" },
                                         fontSize = 11.sp, color = Color(0xFF947E68))
                                     Text(episode.title, fontSize = 21.sp,
                                        lineHeight = 30.sp, fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF302A28))
                                    Text(WorldLifeEpisodeParagraphs.parse(episode.scene).firstOrNull()
                                        ?.text.orEmpty().take(100),
                                        fontSize = 13.sp, lineHeight = 22.sp,
                                        color = Color(0xFF6A625B), maxLines = 3)
                                    Text("阅读全文  ↗", fontSize = 13.sp,
                                        color = Color(0xFF745138), fontWeight = FontWeight.SemiBold,
                                        textDecoration = TextDecoration.Underline)
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (entries.isEmpty()) item { Text("还没有$title", color = LuluColors.Muted) }
                    items(entries.take(visibleCount), key = LexiconEntry::id) { entry ->
                        if (section == LexiconSection.Diary) DiaryEntryCard(entry,
                            characters[characterId]?.displayName ?: "角色",
                            highlighted = entry.title == initialDiaryTitle,
                            onEdit = { editing = entry }, onDelete = { scope.launch { LuluRepositories.lexicon.delete(entry.id) } })
                        else FavoriteEntryCard(entry, characters[characterId]?.displayName ?: "角色",
                            highlighted = entry.id == initialFavoriteEntryId,
                            onDelete = { scope.launch { LuluRepositories.lexicon.delete(entry.id) } })
                    }
                    if (visibleCount < entries.size) item { TextButton(onClick = { visibleCount += LEXICON_PAGE_SIZE },
                        modifier = Modifier.fillMaxWidth()) { Text("继续加载") } }
                }
            }
        }
    }
    selectedEpisodeId?.let { id -> WorldLifeEpisodeStore.find(id)?.let { episode ->
        WorldLifeEpisodeFullscreenDialog(episode, onClose = { selectedEpisodeId = null })
    } }
    val dialogEntry = editing ?: LexiconEntry(UUID.randomUUID().toString(), characterId, LexiconSection.Diary,
        "", "", createdAt = Instant.now(), updatedAt = Instant.now()).takeIf { creating }
    dialogEntry?.let { entry ->
        LexiconEditorDialogV2(entry, isNew = creating,
            onDismiss = { editing = null; creating = false },
            onSave = { scope.launch { LuluRepositories.lexicon.save(it) }; editing = null; creating = false },
            onDelete = { scope.launch { LuluRepositories.lexicon.delete(entry.id) }; editing = null; creating = false })
    }
}


/** The personal-life library projects existing thoughts; it never mutates motives or schedules. */
@Composable
private fun CharacterLifeMattersPage(characterId: String, modifier: Modifier = Modifier) {
    val mindRevision by CharacterInnerLifeStore.revisions.collectAsState()
    val curiosityRevision by CharacterCuriosityRuntime.revisions.collectAsState()
    val evidenceRevision by SharedExperienceTimeline.revisions.collectAsState()
    val entries by produceState(
        initialValue = emptyList<com.jiacimu.lulu.data.CharacterLifeVisible>(),
        characterId, mindRevision, curiosityRevision, evidenceRevision,
    ) {
        value = withContext(Dispatchers.Default) {
            CharacterLifeVisibility.matters(characterId)
        }
    }
    val sections = listOf("持续意愿", "挂心", "正在琢磨", "心声", "心路历程")
    LazyColumn(modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (entries.isEmpty()) {
            item(key = "empty-matters") {
                LexiconV2Card {
                    Text("暂时没有留下心事", fontWeight = FontWeight.Medium)
                }
            }
        }
        sections.forEach { section ->
            val group = entries.filter { it.category == section }
            if (group.isNotEmpty()) {
                item(key = "matter-title:$section") {
                    Text(section, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = LuluColors.Ink, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                }
                itemsIndexed(group, key = { index, entry ->
                    "${entry.category}:${entry.evidenceId}:${entry.at}:$index"
                }) { _, entry ->
                    Surface(modifier = Modifier.fillMaxWidth(), color = Color.White,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, LuluColors.Border)) {
                        Column(Modifier.fillMaxWidth().padding(17.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text(entry.at.atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("MM月dd日 HH:mm")),
                                fontSize = 11.sp, color = LuluColors.Muted)
                            Text(entry.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                color = LuluColors.Ink)
                            Text(entry.detail, fontSize = 13.sp, lineHeight = 22.sp,
                                color = LuluColors.Ink)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteEntryCard(
    entry: LexiconEntry,
    characterName: String,
    highlighted: Boolean = false,
    onDelete: () -> Unit,
) {
    val parts = remember(entry.id, entry.content) {
        val index = entry.content.indexOf(FAVORITE_REASON_SEPARATOR)
        if (index < 0) {
            entry.content.trim() to ""
        } else {
            entry.content.substring(0, index).trim() to
                entry.content.substring(index + FAVORITE_REASON_SEPARATOR.length).trim()
        }
    }
    LexiconV2Card(highlighted = highlighted) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(entry.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("$characterName 收藏的主人消息", color = LuluColors.Muted, fontSize = 11.sp)
                Text("“${parts.first}”", color = LuluColors.Ink, fontSize = 15.sp, lineHeight = 22.sp)
                if (parts.second.isNotBlank()) {
                    Surface(
                        color = LuluColors.CardStrong,
                        shape = RoundedCornerShape(13.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    ) {
                        Column(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("为什么想收藏", color = LuluColors.Muted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                            Text(parts.second, color = LuluColors.Ink, fontSize = 13.sp, lineHeight = 19.sp)
                        }
                    }
                }
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
        border = BorderStroke(
            if (highlighted) 2.dp else 1.dp,
            if (highlighted) LuluColors.Ink else Color(0xFFE8E0D2),
        ),
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
                        Text(
                            entry.updatedAt.atZone(ZoneId.systemDefault()).dayOfMonth.toString(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
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
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.DeleteOutline, "删除日记", tint = MaterialTheme.colorScheme.error)
                }
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
internal fun LexiconEditorDialogV2(
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
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("内容") },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible(),
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
private fun LexiconV2Card(
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (highlighted) LuluColors.CardStrong else LuluColors.Card),
        border = BorderStroke(if (highlighted) 2.dp else 1.dp,
            if (highlighted) LuluColors.Ink else LuluColors.Border),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            content = content,
        )
    }
}
