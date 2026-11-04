package com.jiacimu.lulu.study

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.jiacimu.lulu.data.MigratedDomainStores
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonalMiniTheaterScreen(characterId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val library = remember(context) { MiniTheaterLibrary.get(context) }
    val all by library.items.collectAsState()
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val character = characters[characterId]
    var opened by rememberSaveable(characterId) { mutableStateOf<String?>(null) }
    var offset by rememberSaveable(characterId) { mutableIntStateOf(
        Math.floorMod(LocalDate.now().toEpochDay(), PersonalMiniTheater.ideas.size.toLong()).toInt()) }
    var guidance by rememberSaveable(characterId) { mutableStateOf("") }
    var background by rememberSaveable(opened) { mutableStateOf(
        all.firstOrNull { it.id == opened }?.sceneContext.orEmpty()) }
    var error by remember { mutableStateOf("") }
    var rewrite by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var showPremise by rememberSaveable(opened) { mutableStateOf(false) }
    val work = all.firstOrNull { it.id == opened && it.characterId == characterId }
    val saved = all.filter { it.characterId == characterId }.asReversed()
    val busy = work?.status in setOf("queued", "running")
    val storyTitle = remember(work?.title, work?.text) {
        PersonalTheaterTypography.sections(work?.text.orEmpty())
            .firstOrNull { it.kind == PersonalTheaterTypography.Kind.Title }
            ?.text?.removePrefix("《")?.removeSuffix("》")
            ?.takeIf(String::isNotBlank) ?: work?.title.orEmpty()
    }
    BackHandler { if (work != null) { opened = null; error = "" } else onBack() }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = { Text("番外", maxLines = 1) },
            navigationIcon = { IconButton(onClick = {
                if (work != null) { opened = null; error = "" } else onBack()
            }) { Icon(Icons.Outlined.ArrowBack, "返回") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                if (work == null) {
                    Text((character?.displayName ?: "已移除角色") + " × 用户",
                        fontWeight = FontWeight.SemiBold)
                    Text("独立幻想番外 · 不计入角色记忆",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(storyTitle, style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center)
                    Text((character?.displayName ?: work.characterName) + " × 你   ·   幻想番外",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
            if (work == null) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("系统给个设定", fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { offset += 1 }) { Text("换一个") }
                    }
                }
                item {
                    val idea = PersonalMiniTheater.suggestions(offset).first()
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(idea.title, fontWeight = FontWeight.SemiBold)
                            Text(idea.premise, style = MaterialTheme.typography.bodyMedium)
                            Button(enabled = character != null, onClick = {
                                runCatching {
                                    val id = library.addPersonal(PersonalMiniTheater.capture(characterId), idea)
                                    opened = id; background = ""; error = ""
                                    MiniTheaterJobs.enqueue(context, id, "")
                                }.onFailure { error = it.message ?: "没有开始创作，请重试" }
                            }) { Text("写这一篇") }
                        }
                    }
                }
                item {
                    Text("或者自己写", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(guidance, { guidance = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("想看的情景或设定") }, minLines = 3,
                        placeholder = { Text("比如：醒来后，我和他交换了身体……") })
                    Button(enabled = character != null && guidance.isNotBlank(), onClick = {
                        runCatching {
                            val idea = PersonalMiniTheater.customIdea(guidance)
                            val id = library.addPersonal(PersonalMiniTheater.capture(characterId), idea)
                            opened = id; background = ""; error = ""
                            MiniTheaterJobs.enqueue(context, id, "")
                        }.onFailure { error = it.message ?: "没有开始创作，请重试" }
                    }) { Text("按这个写") }
                }
                item { Text("已保存 · ${saved.size}", fontWeight = FontWeight.SemiBold) }
                items(saved, key = { it.id }) { item ->
                    OutlinedCard(onClick = {
                        opened = item.id; background = item.sceneContext; error = ""
                    }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(item.title, fontWeight = FontWeight.SemiBold)
                            Text(when (item.status) {
                                "queued", "running" -> "正在创作"
                                "complete" -> "点击阅读"
                                "draft" -> "可继续写完"
                                "failed" -> "生成失败 · 可重试"
                                else -> "尚未开始"
                            }, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    PersonalMiniTheaterProse(work.text)
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
    if (rewrite && work != null) AlertDialog(onDismissRequest = { rewrite = false },
        title = { Text("重新写这篇番外？") },
        text = { Text("沿用本篇人物和幻想设定。新版本完整写完后才替换旧版。") },
        confirmButton = { TextButton(onClick = {
            rewrite = false
            runCatching { MiniTheaterJobs.enqueue(context, work.id, background, rewrite = true) }
                .onFailure { error = it.message ?: "未能重新创作" }
        }) { Text("重新创作") } },
        dismissButton = { TextButton(onClick = { rewrite = false }) { Text("取消") } })
    if (deleting && work != null) AlertDialog(onDismissRequest = { deleting = false },
        title = { Text("删除这篇番外？") }, text = { Text("只删除这篇作品。") },
        confirmButton = { TextButton(onClick = {
            deleting = false
            runCatching { check(library.deletePersonal(work.id, characterId)) }
                .onSuccess { opened = null; error = "" }
                .onFailure { error = it.message ?: "删除失败" }
        }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
}

/** Reader-only presentation. Original generated text stays byte-for-byte in the library. */
@Composable
private fun PersonalMiniTheaterProse(source: String) {
    val sections = remember(source) {
        // The generated chapter title is displayed once in the reader header.
        PersonalTheaterTypography.sections(source).filterNot {
            it.kind == PersonalTheaterTypography.Kind.Title
        }
    }
    val ink = MaterialTheme.colorScheme.onSurface
    SelectionContainer {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(19.dp),
        ) {
            sections.forEach { section ->
                when (section.kind) {
                    PersonalTheaterTypography.Kind.Title -> Text(
                        section.text, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        textAlign = TextAlign.Center, fontSize = 21.sp,
                        lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = ink)
                    PersonalTheaterTypography.Kind.End -> Text(
                        "·  完  ·", modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        textAlign = TextAlign.Center, fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PersonalTheaterTypography.Kind.Dialogue -> Column(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                                RoundedCornerShape(14.dp))
                            .padding(horizontal = 15.dp, vertical = 12.dp),
                    ) {
                        Text(section.text, fontSize = 16.sp, lineHeight = 28.sp,
                            color = ink)
                    }
                    PersonalTheaterTypography.Kind.Narration -> Text(
                        section.text,
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = 16.sp, lineHeight = 29.sp,
                        color = ink)
                }
            }
        }
    }
}


/** Paragraphs and quoted speech are display semantics, never a rewrite of fiction.
 * Dialogue is only a standalone quotation paragraph; mixed prose remains intact.
 * No first-line indentation: the rhythm comes from spacing and restrained contrast.
 */
internal object PersonalTheaterTypography {
    enum class Kind { Title, Narration, Dialogue, End }
    data class Section(val kind: Kind, val text: String)

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
        paragraphs.forEach { paragraph ->
            val narration = StringBuilder()
            fun flushNarration() {
                if (narration.isNotEmpty()) {
                    result += Section(Kind.Narration, narration.toString())
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
                        result += Section(Kind.End, line)
                    }
                    line.startsWith("《") && line.endsWith("》") && line.length < 90 -> {
                        flushNarration()
                        result += Section(Kind.Title, line)
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
                            result += Section(Kind.Dialogue, line.substring(0, end + 1))
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

    /** Generated title belongs to the reader header, not to two stacked headings. */
    fun leadingTitle(sections: List<Section>, fallback: String): String =
        sections.firstOrNull()?.takeIf { it.kind == Kind.Title }
            ?.text?.removePrefix("《")?.removeSuffix("》") ?: fallback
}
