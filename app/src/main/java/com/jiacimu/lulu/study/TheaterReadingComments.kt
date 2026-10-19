package com.jiacimu.lulu.study

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores

private data class ReaderComment(
    val author: String, val quote: String, val reaction: String, val feeling: String,
    val at: Int, val recordId: String,
)

/**
 * The same verified anchored reading moments from the role's autonomous
 * reading executor, now projected back onto their exact theater chapter.
 * No new review model call and no fake comments from un-read paragraphs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TheaterReadingComments(
    chapter: StarWishTheaterChapter,
    theaterTitle: String,
    focusedRecordId: String? = null,
) {
    val records by ReadingReflectionStore.records.collectAsState()
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val bookId = "theater-chapter:${chapter.id}"
    val revision = remember(chapter.id, chapter.content) {
        readingRevision(BackgroundReadingBook(bookId, chapter.title, chapter.content, "小剧场章节"))
    }
    val matching = remember(records, bookId) { records.filter { it.bookId == bookId } }
    val comments = remember(matching, characters, chapter.content, revision) {
        matching.flatMap { record ->
            record.moments.mapNotNull { moment ->
                // Old offsets are only valid for their original revision. After
                // chapter edits, remap an exact UNIQUE surviving quote, otherwise
                // hide the outdated anchor rather than pointing at a wrong line.
                val newEnd = if (record.revision == revision &&
                    moment.startOffset >= 0 && moment.endOffset <= chapter.content.length &&
                    chapter.content.substring(moment.startOffset, moment.endOffset) == moment.quote
                ) moment.endOffset else {
                    val index = chapter.content.indexOf(moment.quote)
                    if (index >= 0 && index == chapter.content.lastIndexOf(moment.quote))
                        index + moment.quote.length else -1
                }
                if (newEnd < 0) null else ReaderComment(
                    author = characters[record.characterId]?.displayName ?: "角色",
                    quote = moment.quote, reaction = moment.reaction,
                    feeling = moment.feeling, at = newEnd, recordId = record.id,
                )
            }
        }.sortedBy(ReaderComment::at)
    }
    var chosen by remember(chapter.id) { mutableStateOf<List<ReaderComment>>(emptyList()) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (comments.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(7.dp))
                Text("本章 ${comments.size} 处划句评论", fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f))
                val focused = comments.filter { it.recordId == focusedRecordId }
                TextButton(onClick = { chosen = if (focused.isNotEmpty()) focused else comments }) {
                    Text(if (focused.isNotEmpty()) "查看本次评论" else "查看全部评论")
                }
            }
        }
        var paragraphStart = 0
        val paragraphs = chapter.content.split('\n')
        paragraphs.forEachIndexed { idx, paragraph ->
            val start = paragraphStart
            val end = start + paragraph.length
            paragraphStart = end + 1
            if (paragraph.isBlank()) {
                Spacer(Modifier.height(5.dp))
            } else {
                // Sentence-boundary anchors retain exact full-chapter offsets.
                val sentences = remember(chapter.id, idx, paragraph) {
                    val separator = Regex("(?<=[。！？!?])")
                    val parts = paragraph.split(separator).filter(String::isNotEmpty)
                    if (parts.isEmpty()) listOf(paragraph) else parts
                }
                var sentenceStart = start
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    sentences.forEach { sentence ->
                        val sentenceEnd = sentenceStart + sentence.length
                        val anchored = comments.filter {
                            it.at in (sentenceStart + 1)..sentenceEnd
                        }
                        sentenceStart = sentenceEnd
                        Text(
                            sentence,
                            modifier = if (anchored.isNotEmpty()) Modifier.clickable { chosen = anchored } else Modifier,
                            fontSize = 17.sp, lineHeight = 31.sp,
                            fontWeight = if (anchored.isNotEmpty()) FontWeight.SemiBold else FontWeight.Normal,
                            textDecoration = if (anchored.isNotEmpty()) TextDecoration.Underline else TextDecoration.None,
                            color = if (anchored.isNotEmpty()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = .92f),
                        )
                        if (anchored.isNotEmpty()) {
                            Surface(
                                onClick = { chosen = anchored },
                                modifier = Modifier.padding(start = 2.dp, end = 5.dp, top = 4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Icon(Icons.Outlined.ChatBubbleOutline, "查看划句感想",
                                        Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary)
                                    Text("${anchored.size} 条评", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Older successful reads may only have an overall reflection and no
        // source-anchored moments. Preserve their real records without inventing anchors.
        matching.filter { it.revision == revision && it.moments.isEmpty() &&
            it.reflection.isNotBlank() }.forEach { record ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text((characters[record.characterId]?.displayName ?: "角色") + " · 读后感",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(record.reflection, fontSize = 14.sp, lineHeight = 22.sp)
                }
            }
        }
    }

    if (chosen.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { chosen = emptyList() },
            title = { Text("随手划的 · ${chosen.size} 条", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    chosen.forEach { comment ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .65f),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Text(comment.author, fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                                // The highlighted sentence is visible behind the dialog.
                                // Do not quote the same source again inside its comment.
                                Text(comment.reaction, fontSize = 15.sp, lineHeight = 23.sp)
                                if (comment.feeling.isNotBlank())
                                    Text(comment.feeling, fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { chosen = emptyList() }) { Text("关闭") }
            },
        )
    }
}
