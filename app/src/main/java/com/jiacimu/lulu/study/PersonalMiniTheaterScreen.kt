package com.jiacimu.lulu.study

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
    var variationId by rememberSaveable(characterId) { mutableStateOf("original") }
    var background by rememberSaveable(opened) { mutableStateOf(
        all.firstOrNull { it.id == opened }?.sceneContext.orEmpty()) }
    var error by remember { mutableStateOf("") }
    var rewrite by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val work = all.firstOrNull { it.id == opened && it.characterId == characterId }
    val saved = all.filter { it.characterId == characterId }.asReversed()
    val variation = PersonalMiniTheater.variations.first { it.id == variationId }
    val busy = work?.status in setOf("queued", "running")
    BackHandler { if (work != null) { opened = null; error = "" } else onBack() }

    Scaffold(topBar = {
        TopAppBar(title = { Text(if (work == null) "和我的小剧场" else work.title, maxLines = 1) },
            navigationIcon = { IconButton(onClick = {
                if (work != null) { opened = null; error = "" } else onBack()
            }) { Icon(Icons.Outlined.ArrowBack, "返回") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text((character?.displayName ?: work?.characterName ?: "已移除角色") + " × 用户",
                    fontWeight = FontWeight.SemiBold)
                Text("独立幻想番外 · 不计入角色记忆",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
            if (work == null) {
                item {
                    Text("今天想看哪一面", fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PersonalMiniTheater.variations.forEach { choice ->
                            FilterChip(selected = choice.id == variationId,
                                onClick = { variationId = choice.id }, label = { Text(choice.label) })
                        }
                    }
                    TextButton(onClick = { offset += 3 }) { Text("换一批幻想题材") }
                }
                items(PersonalMiniTheater.suggestions(offset), key = { "idea-" + it.id }) { idea ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(idea.title, fontWeight = FontWeight.SemiBold)
                            Text(idea.premise, style = MaterialTheme.typography.bodyMedium)
                            Button(enabled = character != null, onClick = {
                                runCatching {
                                    val cast = PersonalMiniTheater.capture(characterId)
                                    val id = library.addPersonal(cast, idea, variation)
                                    opened = id; background = ""; error = ""
                                    MiniTheaterJobs.enqueue(context, id, "")
                                }.onFailure { error = it.message ?: "没有开始创作，请重试" }
                            }) { Text("就看这篇") }
                        }
                    }
                }
                item { Text("我的番外 · ${saved.size}", fontWeight = FontWeight.SemiBold) }
                items(saved, key = { it.id }) { item ->
                    OutlinedCard(onClick = {
                        opened = item.id; background = item.sceneContext; error = ""
                    }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(item.title, fontWeight = FontWeight.SemiBold)
                            Text(item.variationContext.substringBefore("："),
                                style = MaterialTheme.typography.bodySmall)
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
                    Text(work.variationContext.substringBefore("："), fontWeight = FontWeight.Medium)
                    Text(work.prompt, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    HorizontalDivider()
                    if (work.rewriting) Text("上一版 · 新版本写完前保留")
                    SelectionContainer { Text(work.text, style = MaterialTheme.typography.bodyLarge) }
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
