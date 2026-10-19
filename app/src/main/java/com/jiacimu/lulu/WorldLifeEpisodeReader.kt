package com.jiacimu.lulu

import com.jiacimu.lulu.data.MigratedDomainStores

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jiacimu.lulu.data.WorldLifeEpisode
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Old tagged passages remain readable, but the literary page does not display parser labels. */
internal enum class WorldLifeParagraphKind { NARRATION, THOUGHT, SPEECH }
internal data class WorldLifeParagraph(val kind: WorldLifeParagraphKind, val text: String)

internal object WorldLifeEpisodeParagraphs {
    private val label = Regex("""^(?:【(叙述|环境|动作|心声|心理|内心|对白|台词|说话)】|\[(叙述|环境|动作|心声|心理|内心|对白|台词|说话)\])\s*""")

    internal fun parse(raw: String): List<WorldLifeParagraph> {
        val text = raw.replace("\r\n", "\n").trim()
        if (text.isEmpty()) return emptyList()
        return text.split(Regex("""\n{2,}""")).flatMap { block ->
            val lines = block.split('\n').map(String::trim).filter(String::isNotBlank)
            val chunks = mutableListOf<String>()
            lines.forEach { line ->
                val previous = chunks.lastOrNull()
                if (previous != null && label.containsMatchIn(previous) &&
                    previous.replaceFirst(label, "").isBlank() && !label.containsMatchIn(line)) {
                    chunks[chunks.lastIndex] = previous + line
                } else if (previous != null && !label.containsMatchIn(line) &&
                    !label.containsMatchIn(previous) && previous.last() !in "。！？。”！？\"") {
                    chunks[chunks.lastIndex] = previous + line
                } else chunks.add(line)
            }
            chunks.flatMap { chunk ->
                val marker = label.find(chunk)?.groupValues?.drop(1)?.firstOrNull(String::isNotBlank)
                val clean = chunk.replaceFirst(label, "").trim()
                if (clean.isEmpty()) emptyList() else {
                    val kind = when (marker) {
                        "心声", "心理", "内心" -> WorldLifeParagraphKind.THOUGHT
                        "对白", "台词", "说话" -> WorldLifeParagraphKind.SPEECH
                        else -> if (clean.startsWith('“') && clean.endsWith('”'))
                            WorldLifeParagraphKind.SPEECH else WorldLifeParagraphKind.NARRATION
                    }
                    // Every mode can arrive as an overlong unbroken block. Break only
                    // after complete Chinese/English sentences, preserving every word.
                    val passages = if (clean.length > 155) {
                        val output = mutableListOf<String>()
                        var part = ""
                        Regex("(?<=[。！？!?])").split(clean).forEach { sentence ->
                            if (part.length >= 92 && sentence.isNotBlank()) {
                                output.add(part.trim())
                                part = ""
                            }
                            part += sentence
                        }
                        if (part.isNotBlank()) output.add(part.trim())
                        output
                    } else listOf(clean)
                    passages.filter(String::isNotBlank).map { WorldLifeParagraph(kind, it) }
                }
            }
        }
    }
}

private val StoryInk = Color(0xFF302A28)
private val StoryMuted = Color(0xFF857970)
private val StoryPaper = Color(0xFFFAF8F3)
private val StoryAccent = Color(0xFFA38B74)

@Composable
internal fun WorldLifeEpisodeReader(scene: String) {
    val paragraphs = remember(scene) { WorldLifeEpisodeParagraphs.parse(scene) }
    Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
        paragraphs.forEach { paragraph ->
            // Chinese literary typesetting: real paragraph breaks and first-line
            // indentation. The stored story stays untouched and searchable.
            val displayed = "　　" + paragraph.text.trimStart()
            when (paragraph.kind) {
                WorldLifeParagraphKind.NARRATION ->
                    Text(displayed, color = StoryInk, fontSize = 16.sp, lineHeight = 30.sp,
                        modifier = Modifier.fillMaxWidth())
                WorldLifeParagraphKind.THOUGHT ->
                    Row(Modifier.fillMaxWidth().padding(start = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                        Box(Modifier.width(2.dp).heightIn(min = 44.dp).background(StoryAccent.copy(alpha = .65f)))
                        Text(displayed, color = Color(0xFF716154), fontSize = 16.sp,
                            fontStyle = FontStyle.Italic, lineHeight = 30.sp,
                            modifier = Modifier.weight(1f))
                    }
                WorldLifeParagraphKind.SPEECH ->
                    Text(displayed, color = Color(0xFF56483F), fontSize = 16.sp,
                        lineHeight = 30.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth().padding(start = 3.dp))
            }
        }
    }
}

/** This is a full reading surface, shared by chat, life records and digital-world history. */
@Composable
internal fun WorldLifeEpisodeFullscreenDialog(
    episode: WorldLifeEpisode,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = StoryPaper) {
            WorldLifeEpisodeFullPage(episode, onClose)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorldLifeEpisodeFullPage(
    episode: WorldLifeEpisode,
    onBack: () -> Unit,
) {
    var showSource by remember(episode.id) { mutableStateOf(false) }
    val name = MigratedDomainStores.characters.settings.collectAsState().value[episode.characterId]
        ?.displayName ?: "角色"
    Scaffold(
        containerColor = StoryPaper,
        topBar = {
            TopAppBar(
                title = { Text("独处小剧场", fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    color = StoryInk) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = StoryPaper),
            )
        },
    ) { inset ->
        Column(
            Modifier.fillMaxSize().padding(inset).verticalScroll(rememberScrollState())
                .padding(horizontal = 27.dp, vertical = 25.dp),
        ) {
            Text("LIFE · PRIVATE CHAPTER", fontSize = 10.sp, letterSpacing = 2.sp,
                color = StoryAccent)
            Spacer(Modifier.height(19.dp))
            Text(episode.title, fontSize = 29.sp, lineHeight = 40.sp,
                fontWeight = FontWeight.SemiBold, color = StoryInk)
            Spacer(Modifier.height(15.dp))
            Text("$name  /  " + episode.occurredAt.atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy年M月d日 · HH:mm")),
                fontSize = 12.sp, color = StoryMuted)
            Spacer(Modifier.height(24.dp))
            Box(Modifier.width(38.dp).height(2.dp).background(StoryAccent))
            Spacer(Modifier.height(33.dp))
            WorldLifeEpisodeReader(episode.scene)
            Spacer(Modifier.height(38.dp))
            Text("· 终 ·", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                color = StoryAccent, fontSize = 13.sp, letterSpacing = 6.sp)
            Spacer(Modifier.height(30.dp))
            HorizontalDivider(color = Color(0xFFE6DED4))
            TextButton(onClick = { showSource = !showSource },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 12.dp)) {
                Text(if (showSource) "收起故事依据" else "查看真实行动依据",
                    color = StoryMuted, fontSize = 12.sp)
            }
            if (showSource) {
                Surface(shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFF0ECE5), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(15.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("已执行行动：${episode.actualAction}", fontSize = 12.sp,
                            color = StoryInk, lineHeight = 20.sp)
                        Text("叙事中的心声和想象属于角色的主观体验，不会自动生成额外世界事实。",
                            fontSize = 11.sp, color = StoryMuted, lineHeight = 18.sp)
                    }
                }
            }
            Spacer(Modifier.height(50.dp))
        }
    }
}
