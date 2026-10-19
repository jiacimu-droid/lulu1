package com.jiacimu.lulu

import android.content.Context
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private data class SelfPageEntry(
    val id: String, val area: String, val title: String, val body: String,
    val done: Boolean, val updated: Long,
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
            SelfPageEntry(id, obj.optString("area", "所思"), obj.optString("title"),
                obj.optString("body"), obj.optBoolean("done"), obj.optLong("updated"))
        }.sortedByDescending { it.updated }
    }.getOrDefault(emptyList())

    fun persist(context: Context, entries: List<SelfPageEntry>): Boolean {
        val array = JSONArray()
        entries.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("area", item.area)
                .put("title", item.title).put("body", item.body)
                .put("done", item.done).put("updated", item.updated))
        }
        return prefs(context).edit().putString(KEY, array.toString()).commit()
    }
}

private val SelfAreas = listOf("所思", "所重", "所愿", "所行", "日记")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfPrefaceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var records by remember { mutableStateOf(SelfPageStore.read(context)) }
    var area by remember { mutableStateOf("所思") }
    var editing by remember { mutableStateOf<SelfPageEntry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SelfPageEntry?>(null) }
    var error by remember { mutableStateOf("") }
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("自序", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回桌面") }
                },
                actions = {
                    IconButton(onClick = { editing = null; creating = true }) {
                        Icon(Icons.Outlined.Add, "新建")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { editing = null; creating = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) { Icon(Icons.Outlined.Edit, "写一点") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            Text("人生由我落笔，故事尚在途中。", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 13.dp))
            ScrollableTabRow(
                selectedTabIndex = SelfAreas.indexOf(area), edgePadding = 0.dp,
                divider = {}, containerColor = MaterialTheme.colorScheme.background,
            ) {
                SelfAreas.forEach { tab ->
                    Tab(selected = area == tab, onClick = { area = tab },
                        text = { Text(tab, fontWeight = if (area == tab) FontWeight.SemiBold else FontWeight.Normal) })
                }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp))
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (area == "所行") "还有什么想要完成？" else "写下第一笔吧",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                if (area == "所行") {
                                    Checkbox(
                                        checked = entry.done,
                                        onCheckedChange = { checked ->
                                            save(records.map {
                                                if (it.id == entry.id) it.copy(done = checked, updated = System.currentTimeMillis()) else it
                                            })
                                        },
                                    )
                                }
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
        var chosenArea by remember(original?.id, area) { mutableStateOf(original?.area ?: area) }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(if (original == null) "写在「$chosenArea」" else "修改记录") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ScrollableTabRow(
                        selectedTabIndex = SelfAreas.indexOf(chosenArea).coerceAtLeast(0),
                        edgePadding = 0.dp, divider = {},
                    ) {
                        SelfAreas.forEach { tab ->
                            Tab(selected = chosenArea == tab, onClick = { chosenArea = tab },
                                text = { Text(tab, fontSize = 12.sp) })
                        }
                    }
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
                            chosenArea, title.trim(), body.trim(), original?.done ?: false,
                            System.currentTimeMillis(),
                        )
                        if (save(records.filterNot { it.id == entry.id } + entry)) {
                            area = chosenArea
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
