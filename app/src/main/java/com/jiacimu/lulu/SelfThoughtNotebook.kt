package com.jiacimu.lulu

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** One page = one entry, even when several pages have the same calendar date. */
internal data class SelfThoughtPage(
    val id: String,
    val date: LocalDate,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    val mood: String = "",
    val weather: String = "",
)

internal fun thoughtPagesOn(pages: List<SelfThoughtPage>, date: LocalDate) =
    pages.filter { it.date == date }.sortedWith(compareBy<SelfThoughtPage> { it.createdAt }.thenBy { it.id })

internal fun nextThoughtDate(pages: List<SelfThoughtPage>, date: LocalDate, forward: Boolean): LocalDate? =
    pages.asSequence().map { it.date }.filter { if (forward) it.isAfter(date) else it.isBefore(date) }
        .let { if (forward) it.minOrNull() else it.maxOrNull() }

private val BookInk = Color(0xFF4D362A)
private val BookMuted = Color(0xFF8D7463)
private val BookAccent = Color(0xFF866751)
private val BookDate = DateTimeFormatter.ofPattern("M月d日")

/**
 * Real image-backed journal, not a series of grey cards or a screenshot of fake text.
 * Outer page is completely occupied by the book; even the date picker is ON its paper.
 * Only the user's own date, text and page controls are live Compose surfaces.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
internal fun SelfThoughtNotebookScreen(
    pages: List<SelfThoughtPage>,
    onBack: () -> Unit,
    onSave: (SelfThoughtPage) -> Boolean,
    onDelete: (SelfThoughtPage) -> Boolean,
    error: String = "",
) {
    var date by remember { mutableStateOf(LocalDate.now()) }
    var pageNumber by remember { mutableIntStateOf(0) }
    var turningForward by remember { mutableStateOf(true) }
    var chooseDate by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<SelfThoughtPage?>(null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var mood by remember { mutableStateOf("") }
    var weather by remember { mutableStateOf("") }
    var focusId by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<SelfThoughtPage?>(null) }
    val dayPages = remember(pages, date) { thoughtPagesOn(pages, date) }
    LaunchedEffect(date, dayPages) {
        focusId?.let { id ->
            dayPages.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let {
                pageNumber = it
                focusId = null
            }
        }
        pageNumber = pageNumber.coerceIn(0, dayPages.lastIndex.coerceAtLeast(0))
    }
    BackHandler { if (writing) writing = false else onBack() }

    fun day(delta: Long) {
        if (writing) return
        turningForward = delta > 0
        date = date.plusDays(delta)
        pageNumber = 0
    }
    fun turn(forward: Boolean) {
        if (writing) return
        turningForward = forward
        when {
            forward && pageNumber < dayPages.lastIndex -> pageNumber++
            !forward && pageNumber > 0 -> pageNumber--
            else -> {
                val next = nextThoughtDate(pages, date, forward) ?: return
                date = next
                pageNumber = if (forward) 0 else thoughtPagesOn(pages, next).lastIndex.coerceAtLeast(0)
            }
        }
    }
    fun write(page: SelfThoughtPage? = null) {
        editTarget = page
        title = page?.title.orEmpty()
        body = page?.body.orEmpty()
        mood = page?.mood.orEmpty()
        weather = page?.weather.orEmpty()
        writing = true
    }

    var drag by remember { mutableFloatStateOf(0f) }
    SelfThoughtNotebookArtwork(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        // All controls are placed inside the book's paper, rather than a screen-level toolbar.
        IconButton(
            onClick = { if (writing) writing = false else onBack() },
            modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 38.dp).size(40.dp),
        ) { Icon(if (writing) Icons.Outlined.Close else Icons.Outlined.ArrowBack,
            contentDescription = if (writing) "取消编辑" else "返回自序", tint = BookInk) }

        // Image contains an empty washi-taped date label here. This is its functional overlay.
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 58.dp, end = 35.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(date.year.toString(), fontSize = 10.sp, color = BookMuted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { day(-1) }, enabled = !writing, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Outlined.ChevronLeft, "前一天", tint = BookInk, modifier = Modifier.size(20.dp))
                }
                TextButton(onClick = { chooseDate = true }, enabled = !writing,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp)) {
                    Text(date.format(BookDate), fontSize = 12.sp, color = BookInk,
                        fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = { day(1) }, enabled = !writing, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Outlined.ChevronRight, "后一天", tint = BookInk, modifier = Modifier.size(20.dp))
                }
            }
        }

        Column(
            Modifier.fillMaxSize().padding(start = 40.dp, end = 39.dp, top = 178.dp, bottom = 46.dp),
        ) {
            if (writing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("写下这一页", color = BookMuted, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text("${body.length} 字", color = BookMuted, fontSize = 11.sp)
                }
                Spacer(Modifier.height(10.dp))
                BasicTextField(title, { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = BookInk, fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
                    decorationBox = { field ->
                        Box {
                            if (title.isBlank()) Text("这一页的标题", color = BookMuted.copy(alpha = .65f), fontSize = 22.sp)
                            field()
                        }
                    })
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(mood, { mood = it.take(18) }, singleLine = true,
                        placeholder = { Text("心情", fontSize = 12.sp) }, modifier = Modifier.weight(1f),
                        textStyle = TextStyle(fontSize = 12.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BookAccent, unfocusedBorderColor = BookMuted.copy(alpha = .4f)))
                    OutlinedTextField(weather, { weather = it.take(18) }, singleLine = true,
                        placeholder = { Text("天气", fontSize = 12.sp) }, modifier = Modifier.weight(1f),
                        textStyle = TextStyle(fontSize = 12.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BookAccent, unfocusedBorderColor = BookMuted.copy(alpha = .4f)))
                }
                Spacer(Modifier.height(14.dp))
                BasicTextField(body, { body = it }, modifier = Modifier.weight(1f).fillMaxWidth(),
                    textStyle = TextStyle(color = BookInk, fontSize = 16.sp, lineHeight = 29.sp),
                    decorationBox = { field ->
                        Box {
                            if (body.isBlank()) Text("今天想留下些什么……", color = BookMuted, fontSize = 16.sp)
                            field()
                        }
                    })
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { writing = false }) { Text("取消", color = BookMuted) }
                    TextButton(
                        enabled = title.isNotBlank() || body.isNotBlank(),
                        onClick = {
                            val now = System.currentTimeMillis()
                            val entry = SelfThoughtPage(editTarget?.id ?: UUID.randomUUID().toString(),
                                date, title.trim(), body.trim(), editTarget?.createdAt ?: now,
                                now, mood.trim(), weather.trim())
                            if (onSave(entry)) { focusId = entry.id; writing = false }
                        },
                    ) { Text("保存这一页", color = BookAccent, fontWeight = FontWeight.Bold) }
                }
            } else {
                // The book can have multiple pages per day. At the final page,
                // turning goes directly to the next written date, never to an empty invented page.
                val animatedKey = date.toString() + "|" + pageNumber
                AnimatedContent(
                    targetState = animatedKey,
                    modifier = Modifier.weight(1f).fillMaxWidth().pointerInput(pages, date, pageNumber) {
                        detectHorizontalDragGestures(
                            onDragStart = { drag = 0f },
                            onHorizontalDrag = { change, dx -> drag += dx; change.consume() },
                            onDragEnd = {
                                when {
                                    drag < -75f -> turn(true)
                                    drag > 75f -> turn(false)
                                }
                                drag = 0f
                            },
                            onDragCancel = { drag = 0f },
                        )
                    },
                    transitionSpec = {
                        (slideInHorizontally(tween(370)) { if (turningForward) it else -it } +
                            fadeIn(tween(270))) togetherWith
                            (slideOutHorizontally(tween(370)) { if (turningForward) -it else it } +
                                fadeOut(tween(260)))
                    },
                    label = "realNotebookPageTurn",
                ) { target ->
                    val slice = target.split("|")
                    val shownDate = runCatching { LocalDate.parse(slice[0]) }.getOrDefault(date)
                    val shownNumber = slice.getOrNull(1)?.toIntOrNull() ?: 0
                    val page = thoughtPagesOn(pages, shownDate).getOrNull(shownNumber)
                    if (page == null) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center) {
                            Text("✿", color = BookAccent, fontSize = 30.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("这一天，还没有落笔", color = BookInk, fontSize = 17.sp)
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { write() }) { Text("写下第一页", color = BookAccent) }
                        }
                    } else Column(Modifier.fillMaxSize()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(page.title.ifBlank { "没有标题的一页" }, modifier = Modifier.weight(1f),
                                color = BookInk, fontSize = 23.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { write(page) }, modifier = Modifier.size(33.dp)) {
                                Icon(Icons.Outlined.Edit, "编辑本页", tint = BookAccent, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { deleting = page }, modifier = Modifier.size(33.dp)) {
                                Icon(Icons.Outlined.DeleteOutline, "删除本页", tint = BookMuted,
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                        if (page.mood.isNotBlank() || page.weather.isNotBlank()) {
                            Text(listOf(page.weather, page.mood).filter { it.isNotBlank() }.joinToString("  ·  "),
                                fontSize = 12.sp, color = BookMuted)
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(page.body, color = BookInk, fontSize = 16.sp, lineHeight = 29.sp,
                            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { turn(false) },
                        enabled = pageNumber > 0 || pages.any { it.date.isBefore(date) },
                        modifier = Modifier.size(35.dp)) {
                        Icon(Icons.Outlined.ChevronLeft, "上一页", tint = BookInk)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("第 ${if (dayPages.isEmpty()) 0 else pageNumber + 1} / ${dayPages.size} 页",
                            fontSize = 11.sp, color = BookInk)
                        Text("轻扫纸页翻动", fontSize = 10.sp, color = BookMuted)
                    }
                    IconButton(onClick = { turn(true) },
                        enabled = pageNumber < dayPages.lastIndex || pages.any { it.date.isAfter(date) },
                        modifier = Modifier.size(35.dp)) {
                        Icon(Icons.Outlined.ChevronRight, "下一页", tint = BookInk)
                    }
                    IconButton(onClick = { write() }, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Outlined.Create, "写新的一页", tint = BookAccent)
                    }
                }
            }
        }
    }
    if (chooseDate) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { chooseDate = false },
            confirmButton = { TextButton(onClick = {
                picker.selectedDateMillis?.let {
                    date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    pageNumber = 0
                }
                chooseDate = false
            }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { chooseDate = false }) { Text("取消") } },
        ) { DatePicker(state = picker) }
    }
    deleting?.let { selected ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text("删除这一页？") },
            text = { Text("删除后不可恢复。") },
            confirmButton = { TextButton(onClick = {
                if (onDelete(selected)) deleting = null
            }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}
