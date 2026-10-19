package com.jiacimu.lulu

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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

private data class SelfPageEntry(
    val id: String, val area: String, val title: String, val body: String,
    val done: Boolean, val updated: Long,
    // Compatible with v1 records: newly written pages retain their own date/order on edit.
    val createdAt: Long = updated,
    val entryDate: String = "",
    val mood: String = "",
    val weather: String = "",
)

/** Private user-authored pages; never mixed into a character's memory or life logs. */
private object SelfPageStore {
    private const val PREFS = "lulu_self_preface_v1"
    private const val KEY = "entries"
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(context: Context): List<SelfPageEntry> = runCatching {
        val array = JSONArray(prefs(context).getString(KEY, "[]"))
        (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val updated = obj.optLong("updated")
            SelfPageEntry(id, obj.optString("area", "所思"), obj.optString("title"),
                obj.optString("body"), obj.optBoolean("done"), updated,
                obj.optLong("createdAt", updated), obj.optString("entryDate", ""),
                obj.optString("mood", ""), obj.optString("weather", ""))
        }.sortedByDescending { it.updated }
    }.getOrDefault(emptyList())

    fun persist(context: Context, entries: List<SelfPageEntry>): Boolean {
        val array = JSONArray()
        entries.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("area", item.area)
                .put("title", item.title).put("body", item.body)
                .put("done", item.done).put("updated", item.updated)
                .put("createdAt", item.createdAt).put("entryDate", item.entryDate)
                .put("mood", item.mood).put("weather", item.weather))
        }
        return prefs(context).edit().putString(KEY, array.toString()).commit()
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfPrefaceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var records by remember { mutableStateOf(SelfPageStore.read(context)) }
    var area by remember { mutableStateOf("所思") }
    var overview by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<SelfPageEntry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SelfPageEntry?>(null) }
    var error by remember { mutableStateOf("") }
    BackHandler(enabled = !overview && area != "所行" && area != "所思") { overview = true }

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
                save(records.filterNot { it.id == page.id } + SelfPageEntry(
                    id = page.id,
                    area = "所思",
                    title = page.title,
                    body = page.body,
                    done = false,
                    updated = page.updatedAt,
                    createdAt = page.createdAt,
                    entryDate = page.date.toString(),
                    mood = page.mood,
                    weather = page.weather,
                ))
            },
            onDelete = { page -> save(records.filterNot { it.id == page.id }) },
            error = error,
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (overview) "自序" else area, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { if (overview) onBack() else overview = true }) {
                        Icon(Icons.Outlined.ArrowBack, if (overview) "返回桌面" else "返回自序")
                    }
                },
                actions = {
                    if (!overview) IconButton(onClick = { editing = null; creating = true }) {
                        Icon(Icons.Outlined.Add, "新建")
                    }
                },
            )
        },
        floatingActionButton = {
            if (!overview) FloatingActionButton(
                onClick = { editing = null; creating = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) { Icon(Icons.Outlined.Edit, "写一点") }
        },
    ) { padding ->
        if (overview) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(23.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 9.dp),
                ) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("MY LIFE JOURNAL", letterSpacing = 1.2.sp, fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("自序", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                        Text("人生由我落笔，故事尚在途中。", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SelfPrefaceCategory("所思", "日记 · 灵感 · 思考", Icons.Outlined.EditNote,
                        Modifier.weight(1f)) { area = "所思"; overview = false }
                    SelfPrefaceCategory("所重", "价值观 · 人生原则", Icons.Outlined.FavoriteBorder,
                        Modifier.weight(1f)) { area = "所重"; overview = false }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("标题") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(body, { body = it }, label = { Text("内容") },
                        minLines = 5, maxLines = 12, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(
                    enabled = title.isNotBlank() || body.isNotBlank(),
                    onClick = {
                        val entry = SelfPageEntry(
                            original?.id ?: UUID.randomUUID().toString(),
                            area, title.trim(), body.trim(), original?.done ?: false,
                            System.currentTimeMillis(),
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
    Surface(
        onClick = onClick,
        modifier = modifier.height(124.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, fontSize = 10.sp, maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
