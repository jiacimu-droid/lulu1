package com.jiacimu.lulu

import com.jiacimu.lulu.design.LuluAlertDialog as AlertDialog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
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
import com.jiacimu.lulu.data.WorldLifeEpisodeStore
import com.jiacimu.lulu.data.CommitmentTaskStore
import com.jiacimu.lulu.data.CommitmentTaskStatus
import com.jiacimu.lulu.data.isActive
import com.jiacimu.lulu.design.LuluColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val LEXICON_PAGE_SIZE = 40
private const val LIFE_STORY_INDEX = 4
private const val FAVORITE_REASON_SEPARATOR = "\n\n收藏理由："

private val LexiconV2Sections = listOf(
    // New inner concerns are read in 此刻; Lexicon retains life facts and
    // historical compatibility notes instead of a second current concern.
    LexiconSection.Concern to "生活近况",
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
    initialSectionName: String? = null,
    initialFavoriteEntryId: String? = null,
    embedded: Boolean = false,
    personal: Boolean = false,
    onCharacterSelected: (String) -> Unit = {},
    areaNavigation: @Composable () -> Unit = {},
    pageTitle: String = "辞海",
) {
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val sortedCharacters = remember(characters) { characters.values.sortedBy { it.displayName } }
    var selectedCharacterId by rememberSaveable(initialCharacterId) {
        mutableStateOf(
            initialCharacterId?.takeIf { personal || it in characters }
                ?: characters.keys.firstOrNull()
                ?: "lulu",
        )
    }
    LaunchedEffect(characters.keys, selectedCharacterId, initialCharacterId) {
        val requestedCharacterId = initialCharacterId?.takeIf { it.isNotBlank() && (personal || it in characters) }
        if (requestedCharacterId != null && selectedCharacterId != requestedCharacterId) {
            selectedCharacterId = requestedCharacterId
        } else if (!personal && selectedCharacterId !in characters && characters.isNotEmpty()) {
            selectedCharacterId = characters.keys.first()
        }
    }

    val diarySectionIndex = remember {
        LexiconV2Sections.indexOfFirst { it.first == LexiconSection.Diary }.coerceAtLeast(0)
    }
    val requestedSectionIndex = remember(initialSectionName) {
        if (initialSectionName in listOf("小剧场", "story", "scene", "life-story")) LIFE_STORY_INDEX
        else if (initialSectionName in listOf("足迹", "历程", "audit", "life-audit")) 0
        else if (initialSectionName.equals("Life", ignoreCase = true)) 0
        else LexiconV2Sections.indexOfFirst { (section, _) ->
            section.name.equals(initialSectionName.orEmpty(), ignoreCase = true)
        }.takeIf { it >= 0 } ?: 0
    }
    var sectionIndex by rememberSaveable(initialDiaryTitle, initialSectionName) {
        mutableIntStateOf(
            if (!initialDiaryTitle.isNullOrBlank()) diarySectionIndex else requestedSectionIndex,
        )
    }

    LaunchedEffect(initialDiaryTitle, initialSectionName) {
        sectionIndex = if (!initialDiaryTitle.isNullOrBlank()) diarySectionIndex else requestedSectionIndex
    }

    // Legacy section indices and '历程' deep links now fall back to life facts;
    // no historical records are deleted.
    LaunchedEffect(sectionIndex) {
        if (sectionIndex > LIFE_STORY_INDEX) sectionIndex = 0
        if (sectionIndex < 0) sectionIndex = 0
    }
    val isLifeStory = sectionIndex == LIFE_STORY_INDEX
    val context = LocalContext.current
    LaunchedEffect(Unit) { WorldLifeEpisodeStore.initialize(context) }
    val lifeStories by WorldLifeEpisodeStore.state.collectAsState()
    val storyEntries = remember(selectedCharacterId, lifeStories) {
        lifeStories[selectedCharacterId].orEmpty().sortedByDescending { it.occurredAt }
    }
    var selectedEpisodeId by remember { mutableStateOf<String?>(null) }
    var sectionPickerExpanded by remember { mutableStateOf(false) }
    val section = LexiconV2Sections.getOrNull(sectionIndex)?.first ?: LexiconSection.Life
    val currentSectionEntries by LuluRepositories.lexicon
        .observeEntries(selectedCharacterId, section)
        .collectAsState(initial = emptyList())
    val lifeEntries by LuluRepositories.lexicon
        .observeEntries(selectedCharacterId, LexiconSection.Life)
        .collectAsState(initial = emptyList())
    // TaskStore is authoritative: an alarm may already be scheduled even if
    // the async 辞海 materialization has not created a LexiconEntry yet.
    val persistedTasks by CommitmentTaskStore.tasks.collectAsState()
    val scheduledTasks = remember(persistedTasks, selectedCharacterId, section) {
        if (section == LexiconSection.Promise)
            persistedTasks.filter { it.characterId == selectedCharacterId }
                .sortedWith(compareByDescending<com.jiacimu.lulu.data.CommitmentTask> {
                    it.status.isActive()
                }.thenByDescending { it.updatedAt })
        else emptyList()
    }
    val taskLinkedEntryIds = remember(scheduledTasks) {
        scheduledTasks.mapNotNull { it.lexiconEntryId }.toSet()
    }
    val entries = remember(currentSectionEntries, lifeEntries, section, taskLinkedEntryIds) {
        when (section) {
            LexiconSection.Concern -> (lifeEntries + currentSectionEntries)
                .distinctBy { it.id }
                .sortedWith(compareBy<LexiconEntry> { it.section == LexiconSection.Concern }
                    .thenByDescending { it.updatedAt })
            LexiconSection.Promise -> currentSectionEntries
                .filterNot { it.id in taskLinkedEntryIds }
            else -> currentSectionEntries
        }
    }
    var visibleCount by rememberSaveable(selectedCharacterId, section) { mutableIntStateOf(LEXICON_PAGE_SIZE) }
    val visibleEntries = remember(entries, visibleCount) { entries.take(visibleCount) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<LexiconEntry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var characterMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(sectionIndex) {
        if (isLifeStory || section == LexiconSection.Favorite) {
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
    LaunchedEffect(initialFavoriteEntryId, selectedCharacterId, section, entries) {
        val requested = initialFavoriteEntryId?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        if (section != LexiconSection.Favorite) return@LaunchedEffect
        val targetIndex = entries.indexOfFirst { it.id == requested }
        if (targetIndex >= 0) {
            if (targetIndex >= visibleCount) visibleCount = targetIndex + 1
            listState.scrollToItem(targetIndex)
        }
    }

    Scaffold(
        containerColor = LuluColors.Paper,
        topBar = {
            TopAppBar(
                title = { Column {
                    Text(pageTitle, fontWeight = FontWeight.SemiBold)
                    if (personal) Text(characters[selectedCharacterId]?.displayName.orEmpty(),
                        fontSize = 12.sp, color = LuluColors.Muted)
                } },
                navigationIcon = {
                    if (!embedded) {
                        IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
                    }
                },
                actions = {
                    if (!personal) Box {
                        TextButton(onClick = { characterMenuExpanded = true }) {
                            Text(
                                characters[selectedCharacterId]?.displayName.orEmpty().ifBlank { "选择角色" },
                                color = LuluColors.Ink,
                                maxLines = 1,
                            )
                            Icon(Icons.Outlined.KeyboardArrowDown, "展开角色列表", modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(
                            expanded = characterMenuExpanded,
                            onDismissRequest = { characterMenuExpanded = false },
                        ) {
                            sortedCharacters.forEach { character ->
                                DropdownMenuItem(
                                    text = { Text(character.displayName) },
                                    onClick = {
                                        selectedCharacterId = character.characterId
                                        onCharacterSelected(character.characterId)
                                        characterMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    if (!isLifeStory && section != LexiconSection.Favorite) {
                        IconButton(onClick = { creating = true }) { Icon(Icons.Outlined.Add, "新增条目") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            areaNavigation()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("生活记录", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = LuluColors.Ink)
                    Text("独处故事、近况与真正留下的生活痕迹",
                        fontSize = 11.sp, color = LuluColors.Muted)
                }
                Box {
                    OutlinedButton(onClick = { sectionPickerExpanded = true },
                        shape = RoundedCornerShape(13.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(if (isLifeStory) "小剧场" else LexiconV2Sections.getOrNull(sectionIndex)?.second ?: "生活近况",
                            fontSize = 13.sp)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Outlined.KeyboardArrowDown, null, modifier = Modifier.size(17.dp))
                    }
                    DropdownMenu(expanded = sectionPickerExpanded,
                        onDismissRequest = { sectionPickerExpanded = false }) {
                        (LexiconV2Sections.map { it.second } + "小剧场").forEachIndexed { index, label ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                sectionIndex = index
                                sectionPickerExpanded = false
                                editing = null
                                creating = false
                            })
                        }
                    }
                }
            }

            if (isLifeStory) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 42.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item(key = "story-intro") {
                        Column(Modifier.padding(bottom = 9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("一个人的时光", fontSize = 25.sp, fontWeight = FontWeight.SemiBold,
                                color = LuluColors.Ink)
                            Text("从真实行动生长出的私人故事 · ${storyEntries.size} 篇",
                                fontSize = 12.sp, color = LuluColors.Muted)
                        }
                    }
                    if (storyEntries.isEmpty()) {
                        item(key = "no-stories") {
                            LexiconV2Card {
                                Text("还没有独处小剧场", fontWeight = FontWeight.SemiBold)
                                Text("角色完成一次真实的独处行动后，才会产生可阅读的故事；这里不会虚构生成记录。",
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
                                    Text(episode.title, fontSize = 21.sp,
                                        lineHeight = 30.sp, fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF302A28))
                                    Text(WorldLifeEpisodeParagraphs.parse(episode.scene).firstOrNull()
                                        ?.text.orEmpty().take(100),
                                        fontSize = 13.sp, lineHeight = 22.sp,
                                        color = Color(0xFF6A625B), maxLines = 3)
                                    Text("阅读全文  →", fontSize = 12.sp,
                                        color = Color(0xFF947E68), fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            } else LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (section == LexiconSection.Concern) {
                    item(key = "story-library-entrance") {
                        Surface(
                            color = Color(0xFFFCF9F3),
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, Color(0xFFE8E0D6)),
                            modifier = Modifier.fillMaxWidth().clickable { sectionIndex = LIFE_STORY_INDEX },
                        ) {
                            Row(Modifier.padding(horizontal = 20.dp, vertical = 19.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("独处小剧场", fontSize = 19.sp,
                                        fontWeight = FontWeight.SemiBold, color = LuluColors.Ink)
                                    Text("在一整页里读角色自己的故事 · ${storyEntries.size} 篇",
                                        fontSize = 12.sp, color = LuluColors.Muted)
                                }
                                Text("去阅读  →", fontSize = 12.sp, color = Color(0xFF947E68))
                            }
                        }
                    }
                    item(key = "unified-life-note-guidance") {
                        Text("这里记录生活事实。角色当前的私人挂心只在「此刻」展示；旧版关心笔记保留供查阅，不再单独触发主动联系。",
                            fontSize = 12.sp, color = LuluColors.Muted)
                    }
                }
                if (section == LexiconSection.Promise && scheduledTasks.isNotEmpty()) {
                    item(key = "scheduled-commitments-heading") {
                        Text("真实定时约定 · 直接来自执行任务", fontWeight = FontWeight.SemiBold)
                    }
                    items(scheduledTasks, key = { "scheduled-commitment:" + it.id }) { task ->
                        val status = when (task.status) {
                            CommitmentTaskStatus.NeedsClarification -> "待明确"
                            CommitmentTaskStatus.Scheduled -> "已安排"
                            CommitmentTaskStatus.Running -> "执行中"
                            CommitmentTaskStatus.WaitingForFeedback -> if (task.dueAt == null) "持续跟进" else "已触发 · 等待反馈"
                            CommitmentTaskStatus.Completed -> "已完成"
                            CommitmentTaskStatus.Cancelled -> "已取消"
                            CommitmentTaskStatus.Blocked -> "执行受阻"
                            CommitmentTaskStatus.Expired -> "已过期"
                        }
                        val whenText = task.dueAt?.atZone(
                            task.timezone?.let { zone -> runCatching { ZoneId.of(zone) }.getOrNull() }
                                ?: ZoneId.systemDefault(),
                        )?.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
                            ?: if (task.status == CommitmentTaskStatus.WaitingForFeedback) "随实际进展跟进" else "等待确定时间"
                        val delivery = if (task.dueAt == null && task.status == CommitmentTaskStatus.WaitingForFeedback) "后续互动中持续关注"
                            else if (task.deliveryAction == "alarm_only") "到点触发本机闹钟"
                            else if (task.deliveryAction == "start_call") "到点来电；正在通话时在原通话中提醒"
                            else if (task.goal.contains("叫醒") || task.goal.contains("起床"))
                                "到点提醒；正在通话时直接语音叫醒"
                            else "到点发送私聊消息"
                        LexiconV2Card {
                            Text(task.goal, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("$whenText · $status", fontSize = 13.sp,
                                color = LuluColors.Muted)
                            Text(delivery, fontSize = 12.sp, color = LuluColors.Muted)
                            task.lastAlarmReceivedAt?.let { received ->
                                val zone = task.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                                    ?: ZoneId.systemDefault()
                                val time = received.atZone(zone).format(DateTimeFormatter.ofPattern("M月d日 HH:mm:ss"))
                                val delay = task.lastAlarmScheduledAt?.let {
                                    java.time.Duration.between(it, received).seconds.coerceAtLeast(0)
                                } ?: 0L
                                Text("${task.lastAlarmDeliverySource.ifBlank { "系统回调" }} $time" +
                                    if (delay > 1) " · 较计划晚${delay}秒" else "",
                                    fontSize = 12.sp, color = LuluColors.Muted)
                            }
                            if (task.lastActionResult.isNotBlank())
                                Text("执行记录：${task.lastActionResult}", fontSize = 12.sp,
                                    color = LuluColors.Muted)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                if (task.status.isActive()) {
                                    TextButton(onClick = {
                                        CommitmentTaskStore.cancel(task.id, "用户从辞海取消定时约定")
                                    }) { Text("取消约定") }
                                }
                                TextButton(onClick = {
                                    CommitmentTaskStore.remove(task.id)
                                    task.lexiconEntryId?.let { id ->
                                        scope.launch { LuluRepositories.lexicon.delete(id) }
                                    }
                                }) { Text("删除记录") }
                            }
                        }
                    }
                }
                if (entries.isEmpty() && scheduledTasks.isEmpty()) {
                    item {
                        LexiconV2Card {
                            Text("还没有${LexiconV2Sections.getOrNull(sectionIndex)?.second ?: "生活"}记录", fontWeight = FontWeight.Bold)
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
                                highlighted = entry.id == initialFavoriteEntryId,
                                onDelete = { scope.launch { LuluRepositories.lexicon.delete(entry.id) } },
                            )
                            else -> LexiconV2Card {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                        if (entry.section == LexiconSection.Life) {
                                            Text("生活事实整理", color = LuluColors.Muted, fontSize = 11.sp)
                                        }
                                        if (entry.section == LexiconSection.Concern) {
                                            Text("旧版关心笔记 · 历史兼容，不代表角色当前挂心或应主动联系",
                                                color = LuluColors.Muted, fontSize = 11.sp)
                                        }
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
                                        if (entry.section == LexiconSection.Life) {
                                            TextButton(onClick = { scope.launch {
                                                LuluRepositories.lexicon.save(entry.copy(
                                                    status = if (entry.status == com.jiacimu.lulu.core.LexiconStatus.Active) com.jiacimu.lulu.core.LexiconStatus.Resolved else com.jiacimu.lulu.core.LexiconStatus.Active,
                                                    updatedAt = java.time.Instant.now(),
                                                ))
                                            } }) { Text(if (entry.section == LexiconSection.Life)
                                                (if (entry.status == com.jiacimu.lulu.core.LexiconStatus.Active) "记为已过去" else "恢复为当前近况")
                                                else (if (entry.status == com.jiacimu.lulu.core.LexiconStatus.Active) "标记已解决" else "已解决 · 重新挂心")) }
                                        }
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

    selectedEpisodeId?.let { id ->
        WorldLifeEpisodeStore.find(id)?.let { episode ->
            WorldLifeEpisodeFullscreenDialog(episode, onClose = { selectedEpisodeId = null })
        }
    }

    val dialogEntry = editing ?: LexiconEntry(
        id = UUID.randomUUID().toString(),
        characterId = selectedCharacterId,
        section = if (section == LexiconSection.Concern) LexiconSection.Life else section,
        title = "",
        content = "",
        promiseKind = if (section == LexiconSection.Promise) PromiseKind.Promise else null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    ).takeIf { creating && !isLifeStory && section != LexiconSection.Favorite }

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
