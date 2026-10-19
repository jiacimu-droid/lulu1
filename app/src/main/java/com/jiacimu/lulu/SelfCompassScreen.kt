package com.jiacimu.lulu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * Independent pages for values (decisions) and wishes (future direction).
 * Both use existing SelfPageStore. A wish only becomes a task by explicit action.
 */
internal object SelfCompassRules {
    const val ACTIVE = "active"
    const val HELD = "held"
    const val FULFILLED = "fulfilled"

    fun state(page: SelfPageEntry): String = when (page.state) {
        ACTIVE, HELD, FULFILLED -> page.state
        else -> if (page.done) FULFILLED else ACTIVE
    }

    fun select(pages: List<SelfPageEntry>, values: Boolean, filter: String): List<SelfPageEntry> =
        pages.filter {
            if (values) filter != "pinned" || it.starred
            else state(it) == filter
        }.sortedWith(compareByDescending<SelfPageEntry> { it.starred }
            .thenByDescending { it.updated }.thenBy { it.id })

    /** Stable ID prevents duplicate imports after rapid taps or screen recreation. */
    fun todoId(page: SelfPageEntry): String = UUID.nameUUIDFromBytes(
        ("self-wish:" + page.id + ":" + page.nextStep.trim()).toByteArray(StandardCharsets.UTF_8)
    ).toString()
}

private data class CompassColors(
    val bg: Color, val ink: Color, val muted: Color,
    val accent: Color, val pale: Color, val hero: Color, val line: Color,
)
private val ValuesInk = CompassColors(
    Color(0xFFFCF8F2), Color(0xFF47362F), Color(0xFF90796D),
    Color(0xFF946A59), Color(0xFFF3E5D8), Color(0xFFECDBCF), Color(0xFFE9DCD1),
)
private val WishesInk = CompassColors(
    Color(0xFFF8FAFE), Color(0xFF354058), Color(0xFF8792AA),
    Color(0xFF617AA4), Color(0xFFE9EFFB), Color(0xFFDCE7F5), Color(0xFFE0E6F0),
)
private val compassDate = DateTimeFormatter.ofPattern("yyyy.MM.dd")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelfCompassScreen(
    area: String,
    pages: List<SelfPageEntry>,
    onBack: () -> Unit,
    onSave: (SelfPageEntry) -> Boolean,
    onDelete: (SelfPageEntry) -> Boolean,
    onCreateTodo: (SelfPageEntry) -> Boolean,
    error: String = "",
) {
    val values = area == "所重"
    val colors = if (values) ValuesInk else WishesInk
    val context = androidx.compose.ui.platform.LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<SelfPageEntry?>(null) }
    var deleting by remember { mutableStateOf<SelfPageEntry?>(null) }
    var filter by remember(area) { mutableStateOf(if (values) "all" else SelfCompassRules.ACTIVE) }
    var taskIds by remember { mutableStateOf(SelfTodoStore.read(context).map { it.id }.toSet()) }
    var localError by remember { mutableStateOf("") }
    fun open(page: SelfPageEntry? = null) { target = page; editing = true }
    BackHandler { if (editing) editing = false else onBack() }

    if (editing) {
        CompassEditor(
            original = target, values = values, colors = colors, error = error,
            onCancel = { editing = false },
            onSave = {
                if (onSave(it)) {
                    editing = false
                    localError = ""
                }
            },
            onDelete = { editing = false; deleting = it },
        )
    } else {
        val visible = SelfCompassRules.select(pages, values, filter)
        val featured = pages.filter {
            it.starred && (values || SelfCompassRules.state(it) == SelfCompassRules.ACTIVE)
        }.maxByOrNull { it.updated }
        val fulfilled = pages.count {
            !values && SelfCompassRules.state(it) == SelfCompassRules.FULFILLED
        }
        Scaffold(
            containerColor = colors.bg,
            topBar = {
                Surface(color = colors.bg) {
                    Row(
                        Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Outlined.ArrowBack, "返回自序", tint = colors.ink)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(area, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = colors.ink)
                            Text(if (values) "MY INNER COMPASS" else "A PLACE FOR DREAMS",
                                fontSize = 9.sp, letterSpacing = 1.25.sp, color = colors.muted)
                        }
                        IconButton(onClick = { open() }) {
                            Icon(Icons.Outlined.Add, "新建", tint = colors.accent)
                        }
                    }
                }
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { open() }, containerColor = colors.accent,
                    contentColor = Color.White,
                    icon = { Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)) },
                    text = { Text(if (values) "写下原则" else "记下心愿") },
                )
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(19.dp, 12.dp, 19.dp, 118.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "hero") { CompassHero(values, pages.size, fulfilled, colors) }
                if (error.isNotBlank() || localError.isNotBlank()) {
                    item(key = "error") {
                        Text((error + "\n" + localError).trim(),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (featured != null) {
                    item(key = "featured") {
                        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            CompassHeading(
                                if (values) "常放在心上的原则" else "眼下的心愿",
                                if (values) "做决定之前，读一读" else "心愿不必着急",
                                colors,
                            )
                            Surface(onClick = { open(featured) },
                                color = colors.pale, shape = RoundedCornerShape(21.dp)) {
                                Column(Modifier.fillMaxWidth().padding(19.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.Bookmark, null, Modifier.size(16.dp),
                                            tint = colors.accent)
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (values) "提醒未来的我" else "一个特别的心愿",
                                            color = colors.accent, fontSize = 10.sp)
                                    }
                                    Text(featured.title.ifBlank { "没有标题的一页" },
                                        fontWeight = FontWeight.SemiBold, fontSize = 20.sp,
                                        color = colors.ink)
                                    val note = if (values) featured.guide else featured.nextStep
                                    if (note.isNotBlank()) Text(
                                        if (values) "遇到选择时 · " + note else "下一步 · " + note,
                                        fontSize = 12.sp, color = colors.muted,
                                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                item(key = "heading") {
                    Spacer(Modifier.height(2.dp))
                    CompassHeading(
                        if (values) "我所珍视的" else "我的心愿册",
                        if (values) pages.size.toString() + " 条人生坐标"
                        else pages.size.toString() + " 个心愿 · " + fulfilled + " 个已实现",
                        colors,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (values) {
                            CompassPill("全部", filter == "all", colors) { filter = "all" }
                            CompassPill("常记心上", filter == "pinned", colors) { filter = "pinned" }
                        } else {
                            CompassPill("在路上", filter == SelfCompassRules.ACTIVE, colors) {
                                filter = SelfCompassRules.ACTIVE
                            }
                            CompassPill("珍藏", filter == SelfCompassRules.HELD, colors) {
                                filter = SelfCompassRules.HELD
                            }
                            CompassPill("已实现", filter == SelfCompassRules.FULFILLED, colors) {
                                filter = SelfCompassRules.FULFILLED
                            }
                        }
                    }
                }
                if (visible.isEmpty()) {
                    item(key = "empty") { CompassEmpty(values, filter, colors) { open() } }
                } else {
                    items(visible, key = { it.id }) { page ->
                        CompassCard(
                            page, values, colors, SelfCompassRules.todoId(page) in taskIds,
                            onEdit = { open(page) },
                            onPin = { onSave(page.copy(starred = !page.starred,
                                updated = System.currentTimeMillis())) },
                            onTodo = {
                                if (onCreateTodo(page)) {
                                    taskIds = SelfTodoStore.read(context).map { it.id }.toSet()
                                    localError = ""
                                } else localError = "加入所行失败，请重试"
                            },
                        )
                    }
                }
                item(key = "footnote") {
                    Text(
                        if (values) "原则也可以随经历改变。你有权重新定义自己。"
                        else "不必把每个愿望都变成待办。它们值得被好好珍藏。",
                        fontSize = 11.sp, color = colors.muted,
                        modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }

    deleting?.let { page ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这条记录？") },
            text = { Text("「" + page.title.ifBlank { "未命名" } + "」删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = { if (onDelete(page)) deleting = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("保留") }
            },
        )
    }
}

@Composable
private fun CompassHero(values: Boolean, count: Int, fulfilled: Int, colors: CompassColors) {
    Box(
        Modifier.fillMaxWidth().height(204.dp).clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(colors.hero, colors.pale))),
    ) {
        CompassArt(values, Modifier.align(Alignment.CenterEnd).size(172.dp, 195.dp),
            colors.accent.copy(alpha = .41f))
        Column(
            Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 21.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(if (values) "THINGS I CHOOSE TO KEEP" else "TO ALL THE THINGS I WISH FOR",
                color = colors.accent, fontSize = 9.sp, letterSpacing = 1.1.sp)
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(if (values) "心有所重，行有所向。" else "慢慢来，愿望会发光。",
                    fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.ink)
                Text(
                    if (values) "把真正重要的事留在这里。\n难以抉择时，记得自己为何出发。"
                    else "想去的地方，想成为的人。\n先安放好心愿，再选择何时启程。",
                    color = colors.ink.copy(alpha = .78f), fontSize = 12.sp, lineHeight = 20.sp,
                )
            }
            Text(
                if (values) "已写下 " + count + " 条人生原则"
                else "珍藏 " + count + " 个心愿   ·   " + fulfilled + " 个已实现",
                color = colors.accent, fontSize = 11.sp,
                fontWeight = FontWeight.Medium)
        }
    }
}

/** Drawn in Compose; no remotely downloaded decorative background. */
@Composable
private fun CompassArt(values: Boolean, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val cx = size.width * .59f
        val cy = size.height * .55f
        val r = size.minDimension * .26f
        if (values) {
            drawCircle(color, r * 1.3f, Offset(cx, cy), style = Stroke(1.2.dp.toPx()))
            repeat(8) { index ->
                val a = 2 * Math.PI * index / 8
                drawCircle(color, r * .51f,
                    Offset(cx + cos(a).toFloat() * r * .66f,
                        cy + sin(a).toFloat() * r * .66f),
                    style = Stroke(1.5.dp.toPx()))
            }
            drawCircle(color, r * .24f, Offset(cx, cy))
        } else {
            drawCircle(color.copy(alpha = .65f), r * 1.55f, Offset(cx, cy),
                style = Stroke(1.2.dp.toPx()))
            val stars = listOf(
                Offset(cx - r * 1.1f, cy - r * .35f),
                Offset(cx - r * .32f, cy - r * 1.11f),
                Offset(cx + r * .42f, cy - r * .40f),
                Offset(cx + r * 1.19f, cy + r * .35f),
                Offset(cx + r * .21f, cy + r * 1.09f),
            )
            stars.zipWithNext().forEach { (a, b) -> drawLine(color, a, b, 1.4.dp.toPx()) }
            stars.forEach { node ->
                drawCircle(color, 3.dp.toPx(), node)
                val d = 8.dp.toPx()
                drawLine(color, Offset(node.x - d, node.y), Offset(node.x + d, node.y),
                    1.2.dp.toPx())
                drawLine(color, Offset(node.x, node.y - d), Offset(node.x, node.y + d),
                    1.2.dp.toPx())
            }
        }
    }
}

@Composable
private fun CompassHeading(title: String, detail: String, colors: CompassColors) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(23.dp)
            .background(colors.accent, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(9.dp))
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold,
            color = colors.ink, modifier = Modifier.weight(1f))
        Text(detail, fontSize = 10.sp, color = colors.muted)
    }
}

@Composable
private fun CompassPill(
    label: String, active: Boolean, colors: CompassColors, onClick: () -> Unit,
) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(30.dp),
        color = if (active) colors.accent else Color.White,
        border = BorderStroke(1.dp, if (active) colors.accent else colors.line),
    ) {
        Text(label, Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
            fontSize = 11.sp, color = if (active) Color.White else colors.muted)
    }
}

@Composable
private fun CompassCard(
    page: SelfPageEntry, values: Boolean, colors: CompassColors, linked: Boolean,
    onEdit: () -> Unit, onPin: () -> Unit, onTodo: () -> Unit,
) {
    val status = SelfCompassRules.state(page)
    Surface(
        modifier = Modifier.fillMaxWidth(), color = Color.White,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, colors.line),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp,
            top = 11.dp, bottom = 11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = colors.pale, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        if (values) "珍视的事" else when (status) {
                            SelfCompassRules.HELD -> "好好珍藏"
                            SelfCompassRules.FULFILLED -> "已经实现"
                            else -> "正在靠近"
                        },
                        Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        fontSize = 10.sp, color = colors.accent)
                }
                Spacer(Modifier.weight(1f))
                Text(Instant.ofEpochMilli(page.updated.coerceAtLeast(0))
                    .atZone(ZoneId.systemDefault()).format(compassDate),
                    fontSize = 10.sp, color = colors.muted)
                IconButton(onClick = onPin, modifier = Modifier.size(40.dp)) {
                    Icon(if (page.starred) Icons.Outlined.Bookmark
                        else Icons.Outlined.BookmarkBorder,
                        if (page.starred) "取消置顶" else "置顶",
                        Modifier.size(20.dp), tint = colors.accent)
                }
            }
            Column(Modifier.fillMaxWidth().clickable(onClick = onEdit),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(page.title.ifBlank { "没有标题的一页" },
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = colors.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (page.body.isNotBlank()) {
                    Text(page.body, fontSize = 13.sp, color = colors.muted,
                        maxLines = 3, lineHeight = 19.sp, overflow = TextOverflow.Ellipsis)
                }
                val hint = if (values) page.guide else page.nextStep
                if (hint.isNotBlank()) {
                    Surface(color = colors.bg, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(if (values) Icons.Outlined.Lightbulb else Icons.Outlined.NorthEast,
                                null, Modifier.size(17.dp), tint = colors.accent)
                            Column(Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(if (values) "提醒未来的我" else "迈出一小步",
                                    fontSize = 10.sp, color = colors.accent)
                                Text(hint, fontSize = 12.sp, lineHeight = 18.sp,
                                    maxLines = 4, overflow = TextOverflow.Ellipsis,
                                    color = colors.ink)
                            }
                        }
                    }
                }
            }
            if (!values && status == SelfCompassRules.ACTIVE && page.nextStep.isNotBlank()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onTodo, enabled = !linked,
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.accent)) {
                        Icon(if (linked) Icons.Outlined.Check else Icons.Outlined.ArrowForward,
                            null, Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (linked) "已加入所行" else "把这一步加入所行", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CompassEmpty(
    values: Boolean, filter: String, colors: CompassColors, onAdd: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
        color = Color.White, shape = RoundedCornerShape(23.dp),
        border = BorderStroke(1.dp, colors.line),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 39.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (values) Icons.Outlined.FavoriteBorder else Icons.Outlined.AutoAwesome,
                null, Modifier.size(30.dp), tint = colors.accent)
            Text(
                if (values && filter == "pinned") "还没有置顶的原则"
                else if (values) "先留下第一条重要的事"
                else if (filter == SelfCompassRules.HELD) "这里还没有珍藏的心愿"
                else if (filter == SelfCompassRules.FULFILLED) "实现的愿望，会好好留在这里"
                else "想要的未来，从一个念头开始",
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
            Text(if (values) "一项底线、一段关系，或者你珍视的生活方式。"
                else "不必宏大，也不必现在就想好怎么完成。",
                fontSize = 11.sp, color = colors.muted)
            TextButton(onClick = onAdd,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.accent)) {
                Text(if (values) "写下我的原则 →" else "写下我的心愿 →")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompassEditor(
    original: SelfPageEntry?, values: Boolean, colors: CompassColors,
    error: String, onCancel: () -> Unit,
    onSave: (SelfPageEntry) -> Unit, onDelete: (SelfPageEntry) -> Unit,
) {
    var title by remember(original?.id, values) { mutableStateOf(original?.title.orEmpty()) }
    var body by remember(original?.id, values) { mutableStateOf(original?.body.orEmpty()) }
    var guidance by remember(original?.id, values) {
        mutableStateOf(if (values) original?.guide.orEmpty() else original?.nextStep.orEmpty())
    }
    var starred by remember(original?.id, values) { mutableStateOf(original?.starred ?: false) }
    var status by remember(original?.id, values) {
        mutableStateOf(if (original == null) SelfCompassRules.ACTIVE else SelfCompassRules.state(original))
    }

    Scaffold(
        containerColor = colors.bg,
        topBar = {
            Row(
                Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Outlined.Close, "取消", tint = colors.ink)
                }
                Text(if (original == null) "新的一页" else "修改记录",
                    color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                if (original != null) {
                    IconButton(onClick = { onDelete(original) }) {
                        Icon(Icons.Outlined.DeleteOutline, "删除", tint = colors.muted)
                    }
                }
            }
        },
        bottomBar = {
            Surface(color = colors.bg) {
                Button(
                    enabled = title.isNotBlank() || body.isNotBlank() || guidance.isNotBlank(),
                    onClick = {
                        val now = System.currentTimeMillis()
                        val page = (original ?: SelfPageEntry(
                            id = UUID.randomUUID().toString(),
                            area = if (values) "所重" else "所愿",
                            title = "", body = "", done = false,
                            updated = now, createdAt = now,
                        )).copy(
                            title = title.trim(), body = body.trim(),
                            guide = if (values) guidance.trim() else original?.guide.orEmpty(),
                            nextStep = if (values) original?.nextStep.orEmpty() else guidance.trim(),
                            starred = starred, state = if (values) SelfCompassRules.ACTIVE else status,
                            done = !values && status == SelfCompassRules.FULFILLED,
                            updated = now,
                        )
                        onSave(page)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent, contentColor = Color.White),
                    shape = RoundedCornerShape(15.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 19.dp, vertical = 12.dp)
                        .height(52.dp),
                ) {
                    Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("保存这页", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 21.dp, vertical = 19.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(if (values) "MY PRINCIPLES" else "MY WISHES",
                fontSize = 10.sp, letterSpacing = 1.3.sp, color = colors.accent)
            Text(if (values) "重要的事，值得认真写下。" else "给心愿一个安放的地方。",
                color = colors.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(if (values) "当你面临选择时，让曾经写下的原则帮你想清楚。"
                else "没有期限也没关系，只有你主动选择，下一步才会去「所行」。",
                color = colors.muted, fontSize = 12.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(7.dp))
            CompassLabel("01", if (values) "什么对我很重要？" else "我想实现什么？", colors)
            OutlinedTextField(title, { title = it },
                placeholder = { Text(if (values) "例如：尊重自己的边界" else "例如：拥有自己的小天地") },
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 3,
                shape = RoundedCornerShape(17.dp))
            CompassLabel("02", if (values) "为什么我珍视它？" else "它对我意味着什么？", colors)
            OutlinedTextField(body, { body = it },
                placeholder = { Text(if (values) "写下背后的经历和理由……"
                    else "描述一下你想要的未来……") },
                modifier = Modifier.fillMaxWidth(), minLines = 5, maxLines = 12,
                shape = RoundedCornerShape(17.dp))
            CompassLabel("03", if (values) "未来遇到选择，我要提醒自己……" else "迈出的第一小步（可选）", colors)
            OutlinedTextField(guidance, { guidance = it },
                placeholder = { Text(if (values) "例如：不舒服时先说明，而不是勉强答应。"
                    else "例如：先收集三个相关的信息。") },
                modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6,
                shape = RoundedCornerShape(17.dp))
            if (!values) {
                CompassLabel("04", "心愿现在的状态", colors)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompassPill("在路上", status == SelfCompassRules.ACTIVE, colors) {
                        status = SelfCompassRules.ACTIVE
                    }
                    CompassPill("珍藏", status == SelfCompassRules.HELD, colors) {
                        status = SelfCompassRules.HELD
                    }
                    CompassPill("已实现", status == SelfCompassRules.FULFILLED, colors) {
                        status = SelfCompassRules.FULFILLED
                    }
                }
            }
            Surface(
                color = Color.White, shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, colors.line),
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable { starred = !starred }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (starred) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                        null, Modifier.size(20.dp), tint = colors.accent)
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (values) "常记心上" else "放在最显眼的位置",
                            fontSize = 13.sp, color = colors.ink)
                        Text(if (values) "在首页提醒自己" else "作为当下最在意的心愿",
                            fontSize = 10.sp, color = colors.muted)
                    }
                    Checkbox(starred, { starred = it },
                        colors = CheckboxDefaults.colors(checkedColor = colors.accent))
                }
            }
            if (error.isNotBlank()) {
                Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun CompassLabel(number: String, text: String, colors: CompassColors) {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(number, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = colors.accent)
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
    }
}
