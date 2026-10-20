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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
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

private val BookInk = SelfPrefacePalette.ink
private val BookMuted = SelfPrefacePalette.muted
private val BookAccent = SelfPrefacePalette.honey
private val BookDate = DateTimeFormatter.ofPattern("M月d日")

/** Drawn notebook with a date navigator outside the paper and full-width editable pages. */
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
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val keyboardController = LocalSoftwareKeyboardController.current
    BackHandler {
        when {
            writing && keyboardVisible -> keyboardController?.hide()
            writing -> writing = false
            else -> onBack()
        }
    }

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
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(
                listOf(SelfPrefacePalette.background, SelfPrefacePalette.sageLight)))
            .statusBarsPadding().navigationBarsPadding().imePadding()
            .padding(horizontal = 12.dp),
    ) {
        if (!writing || !keyboardVisible) {
        Row(
            Modifier.fillMaxWidth().padding(top = 23.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (writing) writing = false else onBack() },
                modifier = Modifier.size(44.dp)) {
                Icon(if (writing) Icons.Outlined.Close else Icons.Outlined.ArrowBack,
                    contentDescription = if (writing) "取消写作" else "返回自序",
                    tint = BookInk, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text("NOTES TO MYSELF", color = BookMuted, fontSize = 9.sp,
                    letterSpacing = 1.3.sp)
                Spacer(Modifier.height(3.dp))
                Text("所思", color = BookInk, fontSize = 27.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
            }
            Surface(
                onClick = {
                    editTarget = null
                    title = ""
                    body = ""
                    mood = ""
                    weather = ""
                    writing = true
                },
                enabled = !writing,
                color = SelfPrefacePalette.sageLight,
                shape = RoundedCornerShape(99.dp),
                border = BorderStroke(1.dp, SelfPrefacePalette.outline),
            ) {
                Row(Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.Edit, null, tint = BookInk,
                        modifier = Modifier.size(16.dp))
                    Text("新的一页", color = BookInk, fontSize = 12.sp)
                }
            }
        }
        // Navigation is OUTSIDE the paper so its top can be used for actual writing.
        Surface(
            color = SelfPrefacePalette.sageLight,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, SelfPrefacePalette.outline),
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 7.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().height(49.dp).padding(horizontal = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { day(-1) }, enabled = !writing,
                    modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Outlined.ChevronLeft, "前一天", tint = BookInk)
                }
                TextButton(onClick = { chooseDate = true }, enabled = !writing,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(date.format(DateTimeFormatter.ofPattern("yyyy.MM.dd")),
                            color = BookInk, fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold)
                        Text(date.dayOfWeek.getDisplayName(JavaTextStyle.FULL, Locale.SIMPLIFIED_CHINESE),
                            color = BookMuted, fontSize = 10.sp)
                    }
                }
                IconButton(onClick = { day(1) }, enabled = !writing,
                    modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Outlined.ChevronRight, "后一天", tint = BookInk)
                }
            }
        }
        } else {
            // A small writing toolbar leaves most of the available height for
            // editable paper while the phone keyboard is on-screen.
            Row(Modifier.fillMaxWidth().height(50.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { keyboardController?.hide() }) {
                    Icon(Icons.Outlined.KeyboardArrowDown, "收起键盘", tint = BookInk)
                }
                Text("所思 · ${date.format(BookDate)}",
                    modifier = Modifier.weight(1f), color = BookInk,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { keyboardController?.hide() }) {
                    Text("收起", color = BookAccent)
                }
            }
        }
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            // A book follows paper proportions, not the 9:16 device proportions.
            val compactWriting = writing && keyboardVisible
            val bookWidth = if (compactWriting) maxWidth
                else minOf(maxWidth, (maxHeight - 4.dp) * .615f)
            val bookHeight = if (compactWriting) maxHeight else bookWidth / .615f
            SelfThoughtNotebookArtwork(Modifier.width(bookWidth).height(bookHeight)) {
        Column(
            Modifier.fillMaxSize().padding(
                start = bookWidth * .135f,
                end = bookWidth * .102f,
                top = if (compactWriting) 25.dp else bookHeight * .192f,
                bottom = if (compactWriting) 15.dp else bookHeight * .077f,
            ),
        ) {
            if (writing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("写在纸上的心事", color = BookMuted, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text("${body.length} 字", color = BookMuted, fontSize = 11.sp)
                }
                Spacer(Modifier.height(10.dp))
                BasicTextField(title, { title = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().selfPrefaceKeepFieldVisible(),
                    textStyle = TextStyle(color = BookInk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                    decorationBox = { field ->
                        Box {
                            if (title.isBlank()) Text("这一页的标题", color = BookMuted.copy(alpha = .65f), fontSize = 20.sp)
                            field()
                        }
                    })
                Spacer(Modifier.height(16.dp))
                BasicTextField(body, { body = it }, modifier = Modifier.weight(1f).fillMaxWidth()
                    .selfPrefaceKeepFieldVisible(),
                    textStyle = TextStyle(color = BookInk, fontSize = 15.sp, lineHeight = 26.sp),
                    decorationBox = { field ->
                        Box {
                            if (body.isBlank()) Text("今天想留下些什么……", color = BookMuted, fontSize = 15.sp)
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
                        Column(Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center) {
                            Canvas(Modifier.size(width = 125.dp, height = 62.dp)) {
                                val inkPath = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(size.width * .08f, size.height * .75f)
                                    cubicTo(size.width * .25f, size.height * .22f,
                                        size.width * .34f, size.height * .95f,
                                        size.width * .49f, size.height * .55f)
                                    cubicTo(size.width * .63f, size.height * .19f,
                                        size.width * .68f, size.height * .83f,
                                        size.width * .88f, size.height * .22f)
                                }
                                drawPath(inkPath, BookMuted,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(2.3.dp.toPx(),
                                        cap = androidx.compose.ui.graphics.StrokeCap.Round))
                                drawCircle(BookAccent, radius = 3.dp.toPx(),
                                    center = androidx.compose.ui.geometry.Offset(
                                        size.width * .92f, size.height * .16f))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("今天，想记点什么？", color = BookInk, fontSize = 19.sp,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(11.dp))
                            Text("写给自己的几句话，也值得被珍藏。",
                                color = BookMuted, fontSize = 12.sp, lineHeight = 21.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            Spacer(Modifier.height(19.dp))
                            Surface(
                                onClick = { write() },
                                color = SelfPrefacePalette.sageLight,
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(1.dp, SelfPrefacePalette.outline),
                            ) {
                                Row(Modifier.padding(horizontal = 19.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Edit, null, tint = BookInk,
                                        modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("写下第一笔", color = BookInk, fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    } else Column(Modifier.fillMaxSize()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(page.title.ifBlank { "没有标题的一页" }, modifier = Modifier.weight(1f),
                                color = BookInk, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { write(page) }, modifier = Modifier.size(33.dp)) {
                                Icon(Icons.Outlined.Edit, "编辑本页", tint = BookAccent, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { deleting = page }, modifier = Modifier.size(33.dp)) {
                                Icon(Icons.Outlined.DeleteOutline, "删除本页", tint = BookMuted,
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(page.body, color = BookInk, fontSize = 15.sp, lineHeight = 26.sp,
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
                        Text(if (dayPages.isEmpty()) "空白页"
                            else "${pageNumber + 1} / ${dayPages.size}",
                            fontSize = 12.sp, color = BookInk)
                    }
                    IconButton(onClick = { turn(true) },
                        enabled = pageNumber < dayPages.lastIndex || pages.any { it.date.isAfter(date) },
                        modifier = Modifier.size(35.dp)) {
                        Icon(Icons.Outlined.ChevronRight, "下一页", tint = BookInk)
                    }
                }
            }
        }
            }
        }
        Spacer(Modifier.height(7.dp))
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
