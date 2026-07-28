package com.jiacimu.lulu.study

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.LuluAiServices
import kotlinx.coroutines.launch

@Composable
internal fun StarWishTheaterContent(
    state: StarWishState,
    studyState: StudyState,
    store: StarWishStore,
    studyStore: PostgraduateExamStore,
) {
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf(StarWishRules.theaters.first().title) }
    var influence by rememberSaveable(selected) { mutableStateOf("") }
    var guide by remember(selected, state.theaterGuides) {
        mutableStateOf(
            state.theaterGuides[selected]
                ?: StarWishRules.theaters.first { seed -> seed.title == selected }.prompt,
        )
    }
    var generating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val seed = StarWishRules.theaters.first { item -> item.title == selected }
    val chapters = state.theaterChapters[selected].orEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StudyCard {
                Text("小剧场", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("剧场碎片：${studyState.inventory.theaterFragments}", color = StudyDesign.muted)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StarWishRules.theaters.forEach { item ->
                        FilterChip(
                            selected = selected == item.title,
                            onClick = { selected = item.title },
                            label = { Text(item.title, maxLines = 1) },
                        )
                    }
                }
                OutlinedTextField(
                    value = guide,
                    onValueChange = { guide = it },
                    label = { Text("剧情指南") },
                    minLines = 3,
                    maxLines = 7,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = influence,
                    onValueChange = { influence = it },
                    label = { Text("主人希望下一章发生什么（可空）") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            store.setGuide(selected, guide)
                            message = "剧情指南已保存"
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("保存指南")
                    }
                    Button(
                        enabled = !generating &&
                            studyState.inventory.theaterFragments >= StarWishRules.THEATER_FRAGMENTS_PER_CHAPTER &&
                            chapters.size < StarWishRules.MAX_CHAPTERS_PER_THEATER,
                        onClick = {
                            generating = true
                            message = ""
                            scope.launch {
                                val history = chapters.joinToString("\n\n") { chapter ->
                                    "${chapter.title}\n${chapter.content}"
                                }
                                val chapterNumber = chapters.size + 1
                                LuluAiServices.gateway.generate(
                                    characterId = studyState.profile.selectedCharacterId,
                                    facts = buildString {
                                        appendLine("剧场：${seed.title}")
                                        appendLine("核心设定：$guide")
                                        if (history.isNotBlank()) {
                                            appendLine("已发生章节：")
                                            appendLine(history)
                                        }
                                        if (influence.isNotBlank()) {
                                            appendLine("主人对下一章的影响：$influence")
                                        }
                                    },
                                    instruction = "续写第 $chapterNumber 章完整中文故事，正文约 1800-3000 字。保持人物、时间线和因果连续；主人影响必须自然进入剧情；不要写提纲、解释或系统提示。",
                                    source = "心愿馆",
                                    title = "${seed.title} · 第${chapterNumber}章",
                                    temperature = 0.9,
                                    maxTokens = 4200,
                                ).onSuccess { reply ->
                                    if (StarWishInventoryBridge.consumeTheaterFragment(studyStore)) {
                                        store.addChapter(
                                            StarWishTheaterChapter(
                                                theater = selected,
                                                chapter = chapterNumber,
                                                title = "第 $chapterNumber 章",
                                                content = reply.text,
                                                userInfluence = influence.trim(),
                                            ),
                                        )
                                        message = "第 $chapterNumber 章已生成，剧场碎片 -1"
                                        influence = ""
                                    } else {
                                        message = "剧场碎片不足，本章没有写入存档"
                                    }
                                }.onFailure { error ->
                                    message = error.message ?: "章节生成失败"
                                }
                                generating = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        if (generating) {
                            CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.AutoStories, null)
                        }
                        Spacer(Modifier.width(5.dp))
                        Text(if (generating) "续写中" else "生成下一章")
                    }
                }
                if (chapters.size >= StarWishRules.MAX_CHAPTERS_PER_THEATER) {
                    Text("本剧场已达到 20 章上限", color = StudyDesign.muted)
                }
                if (message.isNotBlank()) {
                    StudyMessage(
                        message,
                        error = message.contains("失败") || message.contains("不足"),
                    )
                }
            }
        }

        if (chapters.isEmpty()) {
            item {
                StudyCard {
                    Text("还没有章节，消耗 1 枚剧场碎片生成第一章。", color = StudyDesign.muted)
                }
            }
        } else {
            items(chapters, key = { chapter -> chapter.id }) { chapter ->
                StudyCard {
                    Text(chapter.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    if (chapter.userInfluence.isNotBlank()) {
                        Text(
                            "主人影响：${chapter.userInfluence}",
                            color = StudyDesign.muted,
                            fontSize = 12.sp,
                        )
                    }
                    Text(chapter.content, lineHeight = 23.sp)
                }
            }
            item {
                TextButton(onClick = { store.deleteTheater(selected) }) {
                    Icon(
                        Icons.Outlined.DeleteSweep,
                        null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("清空《$selected》章节", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
