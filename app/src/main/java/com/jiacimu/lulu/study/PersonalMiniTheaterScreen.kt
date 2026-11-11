package com.jiacimu.lulu.study

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ModelArchivePickerSheet
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ScopedModelSelections
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonalMiniTheaterScreen(characterId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    remember(context) { ScopedModelSelections.initialize(context.applicationContext); Unit }
    val modelLibrary by LuluAiServices.connectionStore.library.collectAsState()
    val modelRevision by ScopedModelSelections.changes.collectAsState()
    val theaterModelId = remember(characterId, modelLibrary, modelRevision) {
        ScopedModelSelections.selectedPersonalTheaterArchiveId(characterId, modelLibrary)
    }
    var showModelPicker by remember { mutableStateOf(false) }
    val library = remember(context) { MiniTheaterLibrary.get(context) }
    val all by library.items.collectAsState()
    val worldBooks by LuluRepositories.worldBook.observeWorldBooks().collectAsState(initial = emptyList())
    val freshIdeas by library.creativeIdeas.collectAsState()
    val availableIdeas = PersonalMiniTheater.ideas + freshIdeas
    val scope = rememberCoroutineScope()
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val character = characters[characterId]
    var opened by rememberSaveable(characterId) { mutableStateOf<String?>(null) }
    var offset by rememberSaveable(characterId) { mutableIntStateOf(
        Math.floorMod(LocalDate.now().toEpochDay(), PersonalMiniTheater.ideas.size.toLong()).toInt()) }
    var guidance by rememberSaveable(characterId) { mutableStateOf("") }
    var showCustom by rememberSaveable(characterId) { mutableStateOf(false) }
    var worldMode by rememberSaveable(characterId) {
        mutableStateOf(availableIdeas[offset.coerceIn(0, availableIdeas.lastIndex)].recommendedWorldMode)
    }
    var chosenWorldBookIds by rememberSaveable(characterId) { mutableStateOf(listOf<String>()) }
    var showWorldBookPicker by remember { mutableStateOf(false) }
    var worldBookSearch by remember { mutableStateOf("") }
    var inspirationLoading by remember { mutableStateOf(false) }
    var background by rememberSaveable(opened) { mutableStateOf(
        all.firstOrNull { it.id == opened }?.sceneContext.orEmpty()) }
    var error by remember { mutableStateOf("") }
    var rewrite by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var showPremise by rememberSaveable(opened) { mutableStateOf(false) }
    val work = all.firstOrNull { it.id == opened && it.characterId == characterId }
    val saved = all.filter { it.characterId == characterId }.asReversed()
    val busy = work?.status in setOf("queued", "running")
    val pageScroll = rememberLazyListState()
    LaunchedEffect(opened) { pageScroll.scrollToItem(0) }
    val readerPrefs = remember(context) {
        context.getSharedPreferences("personal_mini_theater_reader", 0)
    }
    var fontSize by rememberSaveable {
        mutableIntStateOf(readerPrefs.getInt("font_size_sp", 16).coerceIn(14, 20))
    }
    LaunchedEffect(fontSize) { readerPrefs.edit().putInt("font_size_sp", fontSize).apply() }
    val storyTitle = remember(work?.title, work?.text) {
        PersonalTheaterTypography.sections(work?.text.orEmpty())
            .firstOrNull()?.takeIf { it.kind == PersonalTheaterTypography.Kind.Title }
            ?.text?.removePrefix("《")?.removeSuffix("》")
            ?.takeIf(String::isNotBlank) ?: work?.title.orEmpty()
    }
    // Reuse local suggestions until exhausted; then purchase one 10-idea batch.
    fun nextInspiration() {
        if (inspirationLoading) return
        if (offset + 1 < availableIdeas.size) {
            offset++
            worldMode = availableIdeas[offset].recommendedWorldMode
            return
        }
        inspirationLoading = true
        error = ""
        scope.launch {
            try {
                LuluAiServices.initialize(context.applicationContext)
                val ask = PersonalMiniTheater.inspirationRequest(
                    PersonalMiniTheater.ideas + library.creativeIdeas.value, library.items.value)
                val response = LuluAiServices.gateway.generate(
                    characterId = "theater",
                    facts = ask.facts,
                    instruction = ask.instructions,
                    source = "剧场·番外灵感",
                    title = "新一批番外灵感",
                    contextMode = CompanionContextMode.Isolated,
                    connectionOverride = LuluAiServices.connectionStore.resolveConnection(
                        ScopedModelSelections.selectedPersonalTheaterArchiveId(characterId)),
                    maxTokens = 3_800,
                    readTimeoutMillis = 120_000,
                ).getOrThrow()
                val before = PersonalMiniTheater.ideas.size + library.creativeIdeas.value.size
                val added = library.addCreativeIdeas(PersonalMiniTheater.parseGeneratedIdeas(response.text))
                check(added > 0) { "这一批脑洞与旧题材重复啦，可以再试一次" }
                offset = before
                worldMode = library.creativeIdeas.value
                    .getOrNull(before - PersonalMiniTheater.ideas.size)?.recommendedWorldMode ?: worldMode
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                // Provider/JSON errors can contain the entire generated body.
                // Never render that raw payload inside the story reader.
                error = if (e is IllegalArgumentException ||
                    e.message.orEmpty().contains("灵感")) {
                    "这次没取得可用的新灵感，原有灵感已保留；可以再次尝试。"
                } else {
                    "灵感请求未成功，原有灵感已保留；请检查网络或模型设置。"
                }
            } finally {
                inspirationLoading = false
            }
        }
    }

    BackHandler { if (work != null) { opened = null; error = "" } else onBack() }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = { Text("番外", maxLines = 1) },
            navigationIcon = { IconButton(onClick = {
                if (work != null) { opened = null; error = "" } else onBack()
            }) { Icon(Icons.Outlined.ArrowBack, "返回") } },
            actions = {
                IconButton(onClick = { showModelPicker = true }) {
                    Icon(Icons.Outlined.Memory, contentDescription = "选择番外模型")
                }
            })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = pageScroll,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                if (work == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("把想象写成故事",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold)
                        Text("与${character?.displayName ?: "这位角色"}，在另一种可能里相遇",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text(storyTitle, style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center)
                    Text("正文 ${PersonalTheaterTypography.wordCount(work.text)} 字",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("字号", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(14) },
                            enabled = fontSize > 14) { Text("A−") }
                        Text(fontSize.toString(), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(20) },
                            enabled = fontSize < 20) { Text("A＋") }
                    }
                }
                if (error.isNotBlank()) Text(error,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            if (work == null) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("故事灵感", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { nextInspiration() }, enabled = !inspirationLoading) {
                            Text(if (inspirationLoading) "正在构思…" else if (offset + 1 >= availableIdeas.size) "再想 10 个" else "换一个")
                        }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                            RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("故事设定", style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium)
                            TextButton(onClick = { showWorldBookPicker = true }) {
                                Text(if (chosenWorldBookIds.isEmpty()) "世界书 · 可选"
                                    else "世界书 · ${chosenWorldBookIds.size} 条")
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = worldMode == "original",
                                onClick = { worldMode = "original" },
                                label = { Text("原世界") })
                            FilterChip(selected = worldMode == "alternate",
                                onClick = { worldMode = "alternate" },
                                label = { Text("平行世界") })
                        }
                        if (worldMode == "alternate") Text("换身份与关系，只保留人物性格",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (chosenWorldBookIds.isNotEmpty()) Text(
                            worldBooks.filter { it.id in chosenWorldBookIds }
                                .joinToString(" · ") { it.title },
                            style = MaterialTheme.typography.labelSmall, maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (inspirationLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    val idea = availableIdeas[offset.coerceIn(0, availableIdeas.lastIndex)]
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor =
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                            Text("这一篇的灵感", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                            Text(idea.title, style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold)
                            Text(idea.premise, style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(enabled = character != null, onClick = {
                                runCatching {
                                    val id = library.addPersonal(
                                        PersonalMiniTheater.capture(characterId, worldMode), idea,
                                        selectedWorldBookIds = chosenWorldBookIds.toSet(),
                                        availableWorldBooks = worldBooks
                                    )
                                    opened = id; background = ""; error = ""
                                    MiniTheaterJobs.enqueue(context, id, "")
                                }.onFailure { error = it.message ?: "没有开始创作，请重试" }
                            }, shape = RoundedCornerShape(13.dp)) { Text("开始创作") }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("有自己的脑洞？", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { showCustom = !showCustom }) {
                            Text(if (showCustom) "收起" else "自己写设定")
                        }
                    }
                    if (showCustom) {
                        Column(Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
                                RoundedCornerShape(18.dp))
                            .padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(guidance, { guidance = it },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                label = { Text("想看的情景或设定") }, minLines = 3,
                                placeholder = { Text("比如：醒来后，我们交换了身体……") })
                            Button(enabled = character != null && guidance.isNotBlank(), onClick = {
                                runCatching {
                                    val idea = PersonalMiniTheater.customIdea(guidance)
                                    val id = library.addPersonal(
                                        PersonalMiniTheater.capture(characterId, worldMode), idea,
                                        selectedWorldBookIds = chosenWorldBookIds.toSet(),
                                        availableWorldBooks = worldBooks
                                    )
                                    opened = id; background = ""; error = ""
                                    MiniTheaterJobs.enqueue(context, id, "")
                                }.onFailure { error = it.message ?: "没有开始创作，请重试" }
                            }, shape = RoundedCornerShape(13.dp)) { Text("按这个写") }
                        }
                    }
                }
                item { Text("我的番外 · ${saved.size}", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold) }
                items(saved, key = { it.id }) { item ->
                    Card(onClick = {
                        opened = item.id; background = item.sceneContext; error = ""
                    }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor =
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f))) {
                        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Text("正文 ${PersonalTheaterTypography.wordCount(item.text)} 字",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val snippet = remember(item.text) {
                                PersonalTheaterTypography.sections(item.text)
                                    .firstOrNull { it.kind == PersonalTheaterTypography.Kind.Narration }
                                    ?.text.orEmpty()
                            }
                            if (snippet.isNotBlank()) Text(snippet, maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(when (item.status) {
                                "queued", "running" -> "正在创作"
                                "complete" -> "点击阅读"
                                "draft" -> "可继续写完"
                                "failed" -> "生成失败 · 可重试"
                                else -> "尚未开始"
                            }, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                item {
                    TextButton(onClick = { showPremise = !showPremise },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 3.dp)) {
                        Text(if (showPremise) "收起本篇设定" else "查看本篇设定",
                            style = MaterialTheme.typography.labelMedium)
                    }
                    if (showPremise && work.worldBookIds.isNotEmpty()) {
                        Text("本篇选用世界书 · ${work.worldBookIds.size} 条（创作时设定已保存）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (showPremise && work.worldBookContext.isNotBlank()) {
                        Text("本篇世界书设定（创建时保存）：", style = MaterialTheme.typography.labelMedium)
                        Text(work.worldBookContext, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (showPremise) Text(work.prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 21.sp)
                    if (busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                        Text("正在写，离开页面后也会保存")
                    }
                    if (work.error.isNotBlank()) Text(work.error, color = MaterialTheme.colorScheme.error)
                }
                if (!busy && work.status != "complete") item {
                    OutlinedTextField(background, { background = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("想补充的情节（可留空）") }, minLines = 2)
                    Button(onClick = {
                        runCatching { MiniTheaterJobs.enqueue(context, work.id, background) }
                            .onFailure { error = it.message ?: "未能开始创作" }
                    }) { Text(if (work.text.isBlank() || work.rewriting) "重试创作" else "继续写完") }
                }
                if (work.text.isNotBlank()) item {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (work.rewriting) Text("上一版 · 新版本写完前保留",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PersonalMiniTheaterProse(work.text, fontSize)
                }
                if (!busy) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (work.status == "complete") TextButton(enabled = character != null,
                            onClick = { rewrite = true }) { Text("再写一个版本") }
                        TextButton(onClick = { deleting = true }) { Text("删除这篇") }
                    }
                }
            }
        }
    }
    if (showModelPicker) {
        ModelArchivePickerSheet(
            title = "当前角色的番外模型",
            subtitle = "默认跟随这位角色的聊天模型。单独选择后只影响这位角色的番外，不改变私聊或其他人的模型。",
            selectedArchiveId = theaterModelId,
            onSelect = { archiveId ->
                runCatching {
                    ScopedModelSelections.selectPersonalTheaterArchive(characterId, archiveId)
                }.onSuccess { showModelPicker = false }
                    .onFailure { error = it.message ?: "模型选择失败"; showModelPicker = false }
            },
            onFollowDefault = {
                ScopedModelSelections.clearPersonalTheaterArchive(characterId)
                showModelPicker = false
            },
            followDefaultLabel = "跟随这位角色的聊天模型",
            onDismiss = { showModelPicker = false },
        )
    }
    if (showWorldBookPicker) AlertDialog(
        onDismissRequest = { showWorldBookPicker = false },
        shape = RoundedCornerShape(24.dp),
        title = { Text("选择番外世界书") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("只用于接下来新建的番外。可多选；不选则不加入世界书。原有作品保持创建时的设定。",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = worldBookSearch,
                    onValueChange = { worldBookSearch = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索标题或内容") },
                    singleLine = true
                )
                val visibleBooks = worldBooks.filter {
                    worldBookSearch.isBlank() ||
                        it.title.contains(worldBookSearch, ignoreCase = true) ||
                        it.content.contains(worldBookSearch, ignoreCase = true)
                }
                if (visibleBooks.isEmpty()) Text(
                    if (worldBooks.isEmpty()) "世界书里还没有条目，可以先去世界书页面创建。"
                    else "没有匹配的世界书",
                    style = MaterialTheme.typography.bodyMedium
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(visibleBooks, key = { it.id }) { entry ->
                        val checked = entry.id in chosenWorldBookIds
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                chosenWorldBookIds = if (checked) chosenWorldBookIds - entry.id
                                    else chosenWorldBookIds + entry.id
                            }.padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium)
                                Text(entry.content.take(90).replace('\n', ' '),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { showWorldBookPicker = false }) { Text("确定") } },
        dismissButton = {
            TextButton(onClick = { chosenWorldBookIds = emptyList(); showWorldBookPicker = false }) {
                Text("清空选择")
            }
        }
    )
    if (rewrite && work != null) AlertDialog(onDismissRequest = { rewrite = false },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("重新写这篇番外？", style = MaterialTheme.typography.titleLarge) },
        text = { Text("沿用这篇的角色和幻想设定。新版本写完前，旧版会一直保留。",
            style = MaterialTheme.typography.bodyMedium, lineHeight = 23.sp) },
        confirmButton = { Button(onClick = {
            rewrite = false
            runCatching { MiniTheaterJobs.enqueue(context, work.id, background, rewrite = true) }
                .onFailure { error = it.message ?: "未能重新创作" }
        }, shape = RoundedCornerShape(12.dp)) { Text("重新创作") } },
        dismissButton = { TextButton(onClick = { rewrite = false }) { Text("取消") } })
    if (deleting && work != null) AlertDialog(onDismissRequest = { deleting = false },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("删除这篇番外？", style = MaterialTheme.typography.titleLarge) },
        text = { Text("这篇作品删除后无法恢复，其他番外不受影响。",
            style = MaterialTheme.typography.bodyMedium, lineHeight = 23.sp) },
        confirmButton = { TextButton(onClick = {
            deleting = false
            runCatching { check(library.deletePersonal(work.id, characterId)) }
                .onSuccess { opened = null; error = "" }
                .onFailure { error = it.message ?: "删除失败" }
        }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
}

/** Reader-only presentation. Original generated text stays byte-for-byte in the library. */
@Composable
private fun PersonalMiniTheaterProse(source: String, fontSize: Int) {
    val sections = remember(source) {
        // The generated chapter title is displayed once in the reader header.
        PersonalTheaterTypography.readingSections(source).filterIndexed { index, section ->
            !(index == 0 && section.kind == PersonalTheaterTypography.Kind.Title)
        }
    }
    val ink = MaterialTheme.colorScheme.onSurface
    SelectionContainer {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            sections.forEachIndexed { index, section ->
                if (index > 0) {
                    val sameParagraph = sections[index - 1].paragraphIndex == section.paragraphIndex
                    Spacer(Modifier.height(when {
                        section.softBreakBefore -> 13.dp
                        sameParagraph -> 7.dp
                        else -> 19.dp
                    }))
                }
                when (section.kind) {
                    PersonalTheaterTypography.Kind.Title -> Text(
                        section.text, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        textAlign = TextAlign.Center, fontSize = 21.sp,
                        lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = ink)
                    PersonalTheaterTypography.Kind.End -> Text(
                        "·  完  ·", modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        textAlign = TextAlign.Center, fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PersonalTheaterTypography.Kind.Dialogue -> Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.padding(top = 5.dp).width(3.dp).height(21.dp)
                            .background(Color(0xFFB3A3CE), RoundedCornerShape(3.dp)))
                        Text(section.text, modifier = Modifier.weight(1f),
                            fontSize = fontSize.sp, lineHeight = (fontSize + 12).sp,
                            fontWeight = FontWeight.Medium, color = ink)
                    }
                    PersonalTheaterTypography.Kind.Narration -> Text(
                        section.text,
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = fontSize.sp, lineHeight = (fontSize + 13).sp,
                        color = ink)
                }
            }
        }
    }
}


/** Paragraphs and quoted speech are display semantics, never a rewrite of fiction.
 * Paragraph-leading quoted speech has its own style; other quotes remain in prose.
 * No first-line indentation: the rhythm comes from spacing and restrained contrast.
 */
internal object PersonalTheaterTypography {
    enum class Kind { Title, Narration, Dialogue, End }
    data class Section(
        val kind: Kind, val text: String, val paragraphIndex: Int = 0,
        /** A visible pause inside a long source paragraph, without changing saved prose. */
        val softBreakBefore: Boolean = false,
    )

    /**
     * One blank-line-separated paragraph is one reading beat. Ordinary single
     * newlines are preserved INSIDE its text, not inflated into separate 19dp
     * cards. Standalone quoted speech is visually distinct without rewriting
     * or persisting any of the model's original fiction.
     */
    fun sections(raw: String): List<Section> {
        val source = raw.replace("\r\n", "\n").trim()
        if (source.isBlank()) return emptyList()
        val result = mutableListOf<Section>()
        val paragraphs = source.split(Regex("\n[ \t]*\n+"))
        paragraphs.forEachIndexed { paragraphIndex, paragraph ->
            val narration = StringBuilder()
            fun flushNarration() {
                if (narration.isNotEmpty()) {
                    result += Section(Kind.Narration, narration.toString(), paragraphIndex)
                    narration.clear()
                }
            }
            fun addNarration(text: String) {
                if (text.isBlank()) return
                if (narration.isNotEmpty()) narration.append('\n')
                narration.append(text)
            }
            paragraph.lines().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty()) return@forEach
                when {
                    line == "【小剧场已完】" -> {
                        flushNarration()
                        result += Section(Kind.End, line, paragraphIndex)
                    }
                    line.startsWith("《") && line.endsWith("》") && line.length < 90 -> {
                        flushNarration()
                        result += Section(Kind.Title, line, paragraphIndex)
                    }
                    else -> {
                        // Only a leading, closed quote is an independently spoken
                        // line. An embedded quotation stays in the narrator's prose.
                        val closing = when {
                            line.startsWith("“") -> '”'
                            line.startsWith("「") -> '」'
                            else -> null
                        }
                        val end = closing?.let { line.indexOf(it, startIndex = 1) } ?: -1
                        if (end > 0) {
                            flushNarration()
                            result += Section(Kind.Dialogue, line.substring(0, end + 1), paragraphIndex)
                            addNarration(line.substring(end + 1).trimStart())
                        } else {
                            addNarration(line)
                        }
                    }
                }
            }
            flushNarration()
        }
        return result
    }

    /**
     * Reader-only projection: keep the authored/persisted paragraph exactly as is,
     * but give long mobile paragraphs smaller reading beats at natural boundaries.
     * Never split through a word, quotation or arbitrary character count.
     */
    fun readingSections(raw: String): List<Section> = sections(raw).flatMap { section ->
        if (section.kind != Kind.Narration) listOf(section)
        else softParagraphs(section.text).mapIndexed { index, piece ->
            section.copy(text = piece, softBreakBefore = index > 0)
        }
    }

    private fun softParagraphs(text: String): List<String> {
        if (text.length <= 90) return listOf(text)
        val sentences = mutableListOf<Int>()
        val clauses = mutableListOf<Int>()
        var quoteDepth = 0
        for (i in text.indices) {
            when (text[i]) {
                '“', '「', '『' -> quoteDepth++
                '”', '」', '』' -> quoteDepth = (quoteDepth - 1).coerceAtLeast(0)
            }
            if (quoteDepth != 0) continue
            when (text[i]) {
                '。', '！', '？', '!', '?', '；', ';' -> sentences += i + 1
                '，', ',' -> clauses += i + 1
            }
        }
        val pieces = mutableListOf<String>()
        var start = 0
        while (text.length - start > 90) {
            // Prefer complete sentences around 60–70 characters. Fall back to
            // clause breaks only if no safe full sentence boundary is available.
            val candidates = sentences.filter { it - start in 40..110 && text.length - it >= 20 }
            val cut = (candidates.ifEmpty {
                clauses.filter { it - start in 60..110 && text.length - it >= 25 }
            }).minByOrNull { kotlin.math.abs((it - start) - 65) } ?: break
            pieces += text.substring(start, cut)
            start = cut
        }
        pieces += text.substring(start)
        return pieces
    }

    /** Count actual body characters, not the generated title, ending marker, spaces or punctuation.
     * This is a read-only projection; never change the saved edition for a counter.
     */
    fun wordCount(raw: String): Int = sections(raw).asSequence()
        .filter { it.kind == Kind.Narration || it.kind == Kind.Dialogue }
        .sumOf { part -> part.text.count { it.isLetterOrDigit() } }

    /** Generated title belongs to the reader header, not to two stacked headings. */
    fun leadingTitle(sections: List<Section>, fallback: String): String =
        sections.firstOrNull()?.takeIf { it.kind == Kind.Title }
            ?.text?.removePrefix("《")?.removeSuffix("》") ?: fallback
}
