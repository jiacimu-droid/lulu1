package com.jiacimu.lulu

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

internal data class SelfPageEntry(
    val id: String, val area: String, val title: String, val body: String,
    val done: Boolean, val updated: Long,
    // Compatible with v1 records: newly written pages retain their own date/order on edit.
    val createdAt: Long = updated,
    val entryDate: String = "",
    val mood: String = "",
    val weather: String = "",
    val guide: String = "",
    val nextStep: String = "",
    val state: String = "",
    val starred: Boolean = false,
    val wishFolderId: String = "",
)

/** Private user-authored pages; never mixed into a character's memory or life logs. */
private object SelfPageStore {
    private const val PREFS = "lulu_self_preface_v1"
    private const val KEY = "entries"
    // A raw, immutable pre-upgrade snapshot, kept in the SAME app-private store.
    // Automatic updates and normal overwrite installs preserve these preferences.
    private const val KEY_PRE_WISH_FOLDERS_BACKUP = "entries_before_wish_folders_v1"
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Never treat a corrupted or partly unreadable original as an empty notebook.
     * All prior entries must parse before any save can proceed.
     */
    fun readStrict(context: Context): List<SelfPageEntry>? = runCatching {
        val stored = prefs(context).getString(KEY, null) ?: return@runCatching emptyList()
        val array = JSONArray(stored)
        val rows = (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            val id = obj.getString("id").trim()
            require(id.isNotEmpty()) { "Missing record ID" }
            val updated = obj.optLong("updated")
            SelfPageEntry(
                id, obj.optString("area", "所思"), obj.optString("title"),
                obj.optString("body"), obj.optBoolean("done"), updated,
                obj.optLong("createdAt", updated), obj.optString("entryDate", ""),
                obj.optString("mood", ""), obj.optString("weather", ""),
                obj.optString("guide", ""), obj.optString("nextStep", ""),
                obj.optString("state", ""), obj.optBoolean("starred", false),
                obj.optString("wishFolderId", ""),
            )
        }
        require(rows.map { it.id }.distinct().size == rows.size) { "Duplicate record IDs" }
        rows.sortedByDescending { it.updated }
    }.getOrNull()

    fun persist(context: Context, entries: List<SelfPageEntry>): Boolean = runCatching {
        val stored = prefs(context).getString(KEY, null)
        val originals = mutableMapOf<String, JSONObject>()
        if (stored != null) {
            val array = JSONArray(stored)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.getString("id").trim()
                require(id.isNotEmpty() && !originals.containsKey(id))
                originals[id] = obj
            }
        }
        require(entries.map { it.id }.distinct().size == entries.size)
        val array = JSONArray()
        entries.forEach { item ->
            // Merge instead of rebuilding: preserve all unknown keys from older /
            // future versions (including any extensions another feature wrote).
            val obj = originals[item.id]?.let { JSONObject(it.toString()) } ?: JSONObject()
            array.put(obj.put("id", item.id).put("area", item.area)
                .put("title", item.title).put("body", item.body)
                .put("done", item.done).put("updated", item.updated)
                .put("createdAt", item.createdAt).put("entryDate", item.entryDate)
                .put("mood", item.mood).put("weather", item.weather)
                .put("guide", item.guide).put("nextStep", item.nextStep)
                .put("state", item.state).put("starred", item.starred)
                .put("wishFolderId", item.wishFolderId))
        }
        val editor = prefs(context).edit().putString(KEY, array.toString())
        if (stored != null &&
            !prefs(context).contains(KEY_PRE_WISH_FOLDERS_BACKUP)) {
            // Keep the exact unmodified original JSON before the first folder upgrade.
            editor.putString(KEY_PRE_WISH_FOLDERS_BACKUP, stored)
        }
        editor.commit()
    }.getOrDefault(false)

}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfPrefaceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val initialRead = remember { SelfPageStore.readStrict(context) }
    var records by remember { mutableStateOf(initialRead ?: emptyList()) }
    val dataReadFailed = initialRead == null
    var area by remember { mutableStateOf("所思") }
    var overview by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<SelfPageEntry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SelfPageEntry?>(null) }
    var error by remember { mutableStateOf("") }
    BackHandler(enabled = overview) { onBack() }

    // The five sections have independent screens and no shared switcher.
    // Migrate existing "所行" notes into the dedicated persistent todo store.
    if (!overview && area == "所行") {
        SelfTodoScreen(
            legacy = records.filter { it.area == "所行" }.map {
                LegacySelfTodo(it.id, it.title, it.body, it.done, it.updated)
            },
            onBack = { overview = true },
        )
        return
    }
    val filtered = records.filter { it.area == area }
    fun save(next: List<SelfPageEntry>): Boolean {
        // A stale screen or corrupted storage must never silently overwrite any
        // newer or unreadable user pages.
        val current = SelfPageStore.readStrict(context)
        if (dataReadFailed || current == null ||
            current.associateBy { it.id } != records.associateBy { it.id }) {
            error = "原记录读取异常或已在其他页面更新。已停止写入，请重新打开自序核对。"
            return false
        }
        if (!SelfPageStore.persist(context, next)) {
            error = "保存失败，请重试"
            return false
        }
        records = next.sortedByDescending { it.updated }
        error = ""
        return true
    }

    // 所思 now IS the full-page journal. Reuse the v1 store instead of cloning a second
    // notebook database. Old "日记" and "所思" records are both visible and remain editable.
    if (!overview && area == "所思") {
        val thoughtPages = records.asSequence()
            .filter { it.area == "所思" || it.area == "日记" }
            .map { entry ->
                val originalDate = runCatching { LocalDate.parse(entry.entryDate) }.getOrNull()
                    ?: Instant.ofEpochMilli(entry.updated).atZone(ZoneId.systemDefault()).toLocalDate()
                SelfThoughtPage(entry.id, originalDate, entry.title, entry.body,
                    entry.createdAt, entry.updated, entry.mood, entry.weather)
            }.toList()
        SelfThoughtNotebookScreen(
            pages = thoughtPages,
            onBack = { overview = true },
            onSave = { page ->
                val existing = records.firstOrNull { it.id == page.id }
                save(records.filterNot { it.id == page.id } + (existing ?: SelfPageEntry(
                    id = page.id, area = "所思", title = "", body = "",
                    done = false, updated = page.updatedAt
                )).copy(
                    area = "所思", title = page.title, body = page.body,
                    updated = page.updatedAt, createdAt = page.createdAt,
                    entryDate = page.date.toString(), mood = page.mood,
                    weather = page.weather,
                ))
            },
            onDelete = { page -> save(records.filterNot { it.id == page.id }) },
            error = error,
        )
        return
    }

    // Separate, purpose-built screens. Old user pages remain in the same local store.
    if (!overview && (area == "所重" || area == "所愿")) {
        SelfCompassScreen(
            area = area,
            pages = records.filter { it.area == area },
            onBack = { overview = true },
            onSave = { page -> save(records.filterNot { it.id == page.id } + page) },
            onDelete = { page -> save(records.filterNot { it.id == page.id }) },
            onCreateTodo = { wish ->
                val step = wish.nextStep.trim()
                if (wish.area != "所愿" || step.isBlank()) false
                else {
                    val id = SelfCompassRules.todoId(wish)
                    val existing = SelfTodoStore.read(context)
                    if (existing.any { it.id == id }) true
                    else SelfTodoStore.save(context, existing + TodoTask(
                        id = id,
                        title = step,
                        note = "来自「所愿」：" + wish.title.ifBlank { "我的心愿" },
                        firstDate = LocalDate.now(),
                        minutes = null,
                        repeat = TodoRepeat.ONCE,
                        createdAt = System.currentTimeMillis(),
                    ))
                }
            },
            error = error,
        )
        return
    }

    val homePaper = Color(0xFFFAFAF6)
    Scaffold(
        containerColor = if (overview) homePaper else SelfPrefacePalette.background,
        topBar = {
            TopAppBar(
                title = { Text(if (overview) "自序" else area, fontWeight = FontWeight.SemiBold,
                    color = SelfPrefacePalette.ink) },
                navigationIcon = {
                    IconButton(onClick = { if (overview) onBack() else overview = true }) {
                        Icon(Icons.Outlined.ArrowBack, if (overview) "返回桌面" else "返回自序",
                            tint = SelfPrefacePalette.ink)
                    }
                },
                actions = {
                    if (!overview) IconButton(onClick = { editing = null; creating = true }) {
                        Icon(Icons.Outlined.Add, "新建")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (overview) homePaper else SelfPrefacePalette.background,
                ),
            )
        },
        floatingActionButton = {
            if (!overview) FloatingActionButton(
                onClick = { editing = null; creating = true },
                containerColor = SelfPrefacePalette.sageLight,
            ) { Icon(Icons.Outlined.Edit, "写一点") }
        },
    ) { padding ->
        if (overview) {
            Column(
                Modifier.fillMaxSize().padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                if (dataReadFailed) Text(
                    "自序原始记录读取异常，已保护现有数据并禁止覆盖。请先检查存储。",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 9.dp),
                )
                SelfPrefaceHomeHero(Modifier.padding(top = 4.dp, bottom = 10.dp))
                Row(
                    Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("我的篇章", color = SelfPrefacePalette.ink,
                        fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(Modifier.width(11.dp))
                    Box(Modifier.weight(1f).height(1.dp)
                        .background(SelfPrefacePalette.outline.copy(alpha = .8f)))
                    Spacer(Modifier.width(11.dp))
                    Text("01—04", color = SelfPrefacePalette.muted,
                        fontSize = 10.sp, letterSpacing = 1.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    SelfPrefaceCategory("所思", "日记 · 灵感 · 思考", Icons.Outlined.EditNote,
                        Modifier.weight(1f)) { area = "所思"; overview = false }
                    SelfPrefaceCategory("所重", "价值观 · 人生原则", Icons.Outlined.FavoriteBorder,
                        Modifier.weight(1f)) { area = "所重"; overview = false }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    SelfPrefaceCategory("所愿", "理想 · 心愿 · 长期计划", Icons.Outlined.AutoAwesome,
                        Modifier.weight(1f)) { area = "所愿"; overview = false }
                    SelfPrefaceCategory("所行", "待办 · 目标 · 行动", Icons.Outlined.CheckCircleOutline,
                        Modifier.weight(1f)) { area = "所行"; overview = false }
                }
            }
        } else Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(12.dp))
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp))
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("写下第一笔吧", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 18.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(filtered, key = { it.id }) { entry ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(19.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            onClick = { editing = entry; creating = true },
                        ) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Text(entry.title.ifBlank { "未命名" }, fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (entry.body.isNotBlank()) Text(entry.body, fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 4, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        Instant.ofEpochMilli(entry.updated).atZone(ZoneId.systemDefault())
                                            .format(DateTimeFormatter.ofPattern("yyyy.MM.dd  HH:mm")),
                                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { deleting = entry }) {
                                    Icon(Icons.Outlined.DeleteOutline, "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        val original = editing
        var title by remember(original?.id, area) { mutableStateOf(original?.title.orEmpty()) }
        var body by remember(original?.id, area) { mutableStateOf(original?.body.orEmpty()) }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(if (original == null) "写在「$area」" else "修改记录") },
            text = {
                // Legacy editor (for records not opened through the specialised
                // apps): constrain the form to a keyboard-safe scroll region.
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(title, { title = it }, label = { Text("标题") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().selfPrefaceKeepFieldVisible())
                    OutlinedTextField(body, { body = it }, label = { Text("内容") },
                        minLines = 3, maxLines = 8,
                        modifier = Modifier.fillMaxWidth().selfPrefaceKeepFieldVisible())
                    Spacer(Modifier.height(8.dp))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = title.isNotBlank() || body.isNotBlank(),
                    onClick = {
                        val entry = (original ?: SelfPageEntry(
                            UUID.randomUUID().toString(), area, "", "", false,
                            System.currentTimeMillis(),
                        )).copy(
                            area = area, title = title.trim(), body = body.trim(),
                            updated = System.currentTimeMillis(),
                        )
                        if (save(records.filterNot { it.id == entry.id } + entry)) {
                            overview = false
                            creating = false
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("取消") } },
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除这条记录？") },
            text = { Text(target.title.ifBlank { "未命名" }) },
            confirmButton = {
                TextButton(onClick = {
                    if (save(records.filterNot { it.id == target.id })) deleting = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SelfPrefaceCategory(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // All covers are almost-white paper, like the actual 所思 notebook.
    // Variations are barely visible ink washes, not four colored tiles.
    val paper = SelfPrefacePalette.paperBright
    val sage = SelfPrefacePalette.sageDeep
    val muted = SelfPrefacePalette.muted
    val number = when (title) {
        "所思" -> "01"
        "所重" -> "02"
        "所愿" -> "03"
        else -> "04"
    }
    val wash = when (title) {
        "所思" -> Color(0xFFEAF3EE)
        "所重" -> Color(0xFFF7F2E6)
        "所愿" -> Color(0xFFEAF1EF)
        else -> Color(0xFFF2F3E9)
    }

    Surface(
        modifier = modifier.heightIn(min = 158.dp),
        onClick = onClick,
        shape = RoundedCornerShape(23.dp),
        color = paper,
        border = BorderStroke(1.dp, SelfPrefacePalette.outline.copy(alpha = .85f)),
        shadowElevation = 2.dp,
    ) {
        Box(
            Modifier.fillMaxWidth().background(
                Brush.linearGradient(
                    colors = listOf(paper, Color(0xFFFFFCF7), wash.copy(alpha = .7f)),
                )
            )
        ) {
            Canvas(Modifier.align(Alignment.TopEnd).size(93.dp)) {
                val w = size.width
                val h = size.height
                val center = Offset(w * .87f, h * .13f)
                drawCircle(
                    SelfPrefacePalette.seaGlass.copy(alpha = .13f),
                    radius = w * .43f,
                    center = center,
                    style = Stroke(1.3.dp.toPx()),
                )
                drawCircle(
                    SelfPrefacePalette.sageLight.copy(alpha = .44f),
                    radius = w * .29f,
                    center = center,
                )
                drawLine(
                    SelfPrefacePalette.seaGlass.copy(alpha = .29f),
                    Offset(w * .36f, h * .31f),
                    Offset(w * .71f, h * .68f),
                    1.25.dp.toPx(),
                )
                drawCircle(
                    SelfPrefacePalette.honey.copy(alpha = .68f),
                    radius = 2.3.dp.toPx(),
                    center = Offset(w * .71f, h * .68f),
                )
            }

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = SelfPrefacePalette.sageLight.copy(alpha = .75f),
                        shape = RoundedCornerShape(13.dp),
                    ) {
                        Box(Modifier.size(41.dp), contentAlignment = Alignment.Center) {
                            Icon(icon, contentDescription = null,
                                tint = sage, modifier = Modifier.size(22.dp))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(number, color = muted.copy(alpha = .7f),
                        fontSize = 11.sp, letterSpacing = 1.sp)
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    title, color = SelfPrefacePalette.ink,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp, letterSpacing = 1.1.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        subtitle,
                        color = muted, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(Icons.Outlined.ChevronRight, contentDescription = "进入$title",
                        tint = sage.copy(alpha = .75f),
                        modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
