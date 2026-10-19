package com.jiacimu.lulu.study

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores

private data class ReaderComment(
    val author: String, val quote: String, val reaction: String, val feeling: String,
    val at: Int,
)

/**
 * The same verified anchored reading moments from the role's autonomous
 * reading executor, now projected back onto their exact theater chapter.
 * No new review model call and no fake comments from un-read paragraphs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TheaterReadingComments(chapter: StarWishTheaterChapter, theaterTitle: String) {
    val records by ReadingReflectionStore.records.collectAsState()
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val bookId = "theater-chapter:${chapter.id}"
    val revision = remember(chapter.id, chapter.content) {
        readingRevision(BackgroundReadingBook(bookId, chapter.title, chapter.content, "小剧场章节"))
    }
    val matching = remember(records, bookId, revision) {
        records.filter { it.bookId == bookId && it.revision == revision }
    }
    val comments = remember(matching, characters) {
        matching.flatMap { record ->
            record.moments.map { moment ->
                ReaderComment(
                    author = characters[record.characterId]?.displayName ?: "角色",
                    quote = moment.quote,
                    reaction = moment.reaction,
                    feeling = moment.feeling,
                    at = moment.endOffset,
                )
            }
        }.sortedBy(ReaderComment::at)
    }
    var chosen by remember(chapter.id) { mutableStateOf<List<ReaderComment>>(emptyList()) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                            sentence, fontSize = 17.sp, lineHeight = 31.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .92f),
                        )
                        if (anchored.isNotEmpty()) {
                            Surface(
                                onClick = { chosen = anchored },
                                modifier = Modifier.padding(start = 2.dp, end = 5.dp, top = 4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(9.dp),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Icon(Icons.Outlined.ChatBubbleOutline, "查看划句感想",
                                        Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(anchored.size.toString(), fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Older successful reads may only have an overall reflection and no
        // source-anchored moments. Preserve their real records without inventing anchors.
        matching.filter { it.moments.isEmpty() && it.reflection.isNotBlank() }.forEach { record ->
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
            title = { Text("划句感想", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    chosen.forEach { comment ->
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(comment.author, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("“${comment.quote}”", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(comment.reaction, fontSize = 15.sp, lineHeight = 23.sp)
                            if (comment.feeling.isNotBlank()) Text(comment.feeling, fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
