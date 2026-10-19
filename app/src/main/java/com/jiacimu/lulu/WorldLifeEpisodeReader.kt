package com.jiacimu.lulu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Reading styles are presentation only; labels do not establish world facts. */
internal enum class WorldLifeParagraphKind { NARRATION, THOUGHT, SPEECH }
internal data class WorldLifeParagraph(val kind: WorldLifeParagraphKind, val text: String)

internal object WorldLifeEpisodeParagraphs {
    private val label = Regex("""^(?:【(叙述|环境|动作|心声|心理|内心|对白|台词|说话)】|\[(叙述|环境|动作|心声|心理|内心|对白|台词|说话)\])\s*""")
    internal fun parse(raw: String): List<WorldLifeParagraph> {
        val normalized = raw.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return emptyList()
        val sections = normalized.split(Regex("""\n{2,}"""))
            .flatMap { segment ->
                val lines = segment.split('\n').map(String::trim).filter(String::isNotEmpty)
                // Explicit single-line labels are their own paragraphs; old
                // hard-wrapped Chinese text is joined back into normal prose.
                val result = mutableListOf<String>()
                lines.forEach { line ->
                    if (result.isNotEmpty() && !label.containsMatchIn(line) &&
                        !label.containsMatchIn(result.last()) &&
                        !result.last().endsWith('。') && !result.last().endsWith('！') &&
                        !result.last().endsWith('？') && !result.last().endsWith('”')) {
                        result[result.lastIndex] = result.last() + line
                    } else result.add(line)
                }
                result
            }
        return sections.flatMap { section ->
            val type = label.find(section)?.groupValues?.drop(1)?.firstOrNull(String::isNotBlank)
            val text = section.replaceFirst(label, "").trim()
            val kind = when (type) {
                "心声", "心理", "内心" -> WorldLifeParagraphKind.THOUGHT
                "对白", "台词", "说话" -> WorldLifeParagraphKind.SPEECH
                else -> if (text.startsWith("“") && text.endsWith("”"))
                    WorldLifeParagraphKind.SPEECH else WorldLifeParagraphKind.NARRATION
            }
            // Split legacy single-block novels at complete sentence boundaries.
            // Nothing is discarded; tags and original dialogue text are preserved.
            val parts = if (text.length > 270 && kind == WorldLifeParagraphKind.NARRATION) {
                val split = mutableListOf<String>()
                var start = 0
                text.forEachIndexed { i, ch ->
                    if (ch in "。！？" && i - start >= 85) {
                        split.add(text.substring(start, i + 1).trim())
                        start = i + 1
                    }
                }
                text.substring(start).trim().takeIf(String::isNotEmpty)?.let(split::add)
                split
            } else listOf(text)
            parts.filter(String::isNotBlank).map { WorldLifeParagraph(kind, it) }
        }
    }
}

@Composable
internal fun WorldLifeEpisodeReader(scene: String) {
    val paragraphs = androidx.compose.runtime.remember(scene) {
        WorldLifeEpisodeParagraphs.parse(scene)
    }
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        paragraphs.forEach { paragraph ->
            when (paragraph.kind) {
                WorldLifeParagraphKind.NARRATION ->
                    Text(paragraph.text, color = Color(0xFF26282D),
                        fontSize = 15.sp, lineHeight = 27.sp, modifier = Modifier.fillMaxWidth())
                WorldLifeParagraphKind.THOUGHT ->
                    Surface(color = Color(0xFFF4F1F7),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                            Text("心声", fontSize = 11.sp, color = Color(0xFF7C687F),
                                fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(5.dp))
                            Text(paragraph.text, fontSize = 15.sp, lineHeight = 25.sp,
                                fontStyle = FontStyle.Italic, color = Color(0xFF44394A))
                        }
                    }
                WorldLifeParagraphKind.SPEECH ->
                    Surface(color = Color(0xFFF0F3F6),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                            Text("对白", fontSize = 11.sp, color = Color(0xFF596B7A),
                                fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            Text(paragraph.text, fontSize = 15.sp, lineHeight = 25.sp,
                                color = Color(0xFF263D50))
                        }
                    }
            }
        }
    }
}
