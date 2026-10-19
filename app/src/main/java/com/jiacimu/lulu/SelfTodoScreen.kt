package com.jiacimu.lulu

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/** Migration input from the former undated "所行" notes. */
internal data class LegacySelfTodo(
    val id: String,
    val title: String,
    val note: String,
    val done: Boolean,
    val updatedAt: Long,
)

internal enum class TodoRepeat { ONCE, DAILY, WEEKLY }
internal data class TodoCompletion(val date: LocalDate, val completedAt: Long)
internal data class TodoTask(
    val id: String,
    val title: String,
    val note: String,
    val firstDate: LocalDate,
    val minutes: Int?,
    val repeat: TodoRepeat,
    val createdAt: Long,
    val completions: List<TodoCompletion> = emptyList(),
    val reopened: Set<LocalDate> = emptySet(),
)
internal data class TodoOccurrence(val task: TodoTask, val date: LocalDate)

/**
 * A recurring task is one definition with dated completion records, not a task
 * deleted and recreated at midnight. A deliberately restored old occurrence
 * is kept as an overdue item until completed again.
 */
internal object SelfTodoCalendar {
    fun pending(task: TodoTask, today: LocalDate): List<TodoOccurrence> {
        // A one-off item remains completed even when its date is edited later.
        if (task.repeat == TodoRepeat.ONCE && task.completions.isNotEmpty()) return emptyList()
        val completed = task.completions.map { it.date }.toSet()
        val due = when (task.repeat) {
            TodoRepeat.ONCE -> task.firstDate
            TodoRepeat.DAILY -> if (today.isBefore(task.firstDate)) task.firstDate else today
            TodoRepeat.WEEKLY -> {
                if (today.isBefore(task.firstDate)) task.firstDate
                else task.firstDate.plusDays((ChronoUnit.DAYS.between(task.firstDate, today) / 7) * 7)
            }
        }
        val extra = if (task.repeat == TodoRepeat.ONCE) emptySet() else task.reopened
        return (extra + due).asSequence()
            .filter { it !in completed }
            .distinct()
            .map { TodoOccurrence(task, it) }
            .toList()
    }

    fun allPending(tasks: List<TodoTask>, today: LocalDate): List<TodoOccurrence> =
        tasks.flatMap { pending(it, today) }
            .sortedWith(
                compareBy<TodoOccurrence> { it.date }
                    .thenBy { it.task.minutes ?: Int.MAX_VALUE }
                    .thenBy { it.task.createdAt }
                    .thenBy { it.task.id },
            )

    fun complete(task: TodoTask, date: LocalDate, at: Long): TodoTask =
        if (task.completions.any { it.date == date }) task
        else task.copy(
            completions = task.completions + TodoCompletion(date, at),
            reopened = task.reopened - date,
        )

    fun undo(task: TodoTask, date: LocalDate, today: LocalDate): TodoTask {
        val remaining = task.completions.filterNot { it.date == date }
        // Restoring yesterday's recurring completion must also show up in the
        // pending list, not vanish behind today's independently completed item.
        val needsExplicitOccurrence = task.repeat != TodoRepeat.ONCE &&
            date != pending(task.copy(completions = emptyList()), today).first().date
        return task.copy(
            completions = remaining,
            reopened = if (needsExplicitOccurrence) task.reopened + date else task.reopened - date,
        )
    }
}

internal object SelfTodoStore {
    private const val PREF = "lulu_self_todo_v2"
    private const val TASKS = "tasks"
    private const val MIGRATED = "legacy_imported"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun read(context: Context): List<TodoTask> = runCatching {
        val array = JSONArray(preferences(context).getString(TASKS, "[]"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val firstDate = runCatching { LocalDate.parse(item.getString("firstDate")) }
                .getOrElse { LocalDate.now() }
            val repeat = runCatching { TodoRepeat.valueOf(item.optString("repeat")) }
                .getOrDefault(TodoRepeat.ONCE)
            val log = item.optJSONArray("completions") ?: JSONArray()
            val completed = (0 until log.length()).mapNotNull { i ->
                val obj = log.optJSONObject(i) ?: return@mapNotNull null
                val date = runCatching { LocalDate.parse(obj.getString("date")) }.getOrNull()
                    ?: return@mapNotNull null
                TodoCompletion(date, obj.optLong("at"))
            }
            val reopened = item.optJSONArray("reopened") ?: JSONArray()
            TodoTask(
                id = id,
                title = item.optString("title"),
                note = item.optString("note"),
                firstDate = firstDate,
                minutes = if (item.isNull("minutes")) null else item.optInt("minutes").coerceIn(0, 1439),
                repeat = repeat,
                createdAt = item.optLong("createdAt"),
                completions = completed,
                reopened = (0 until reopened.length()).mapNotNull { i ->
                    runCatching { LocalDate.parse(reopened.getString(i)) }.getOrNull()
                }.toSet(),
            )
        }
    }.getOrDefault(emptyList())

    private fun encode(tasks: List<TodoTask>): String {
        val array = JSONArray()
        tasks.forEach { item ->
            val logs = JSONArray()
            item.completions.forEach { log ->
                logs.put(JSONObject().put("date", log.date.toString()).put("at", log.completedAt))
            }
            val reopened = JSONArray()
            item.reopened.forEach { reopened.put(it.toString()) }
            array.put(
                JSONObject().put("id", item.id).put("title", item.title)
                    .put("note", item.note).put("firstDate", item.firstDate.toString())
                    .put("minutes", item.minutes ?: JSONObject.NULL)
                    .put("repeat", item.repeat.name).put("createdAt", item.createdAt)
                    .put("completions", logs).put("reopened", reopened),
            )
        }
        return array.toString()
    }

    fun save(context: Context, tasks: List<TodoTask>): Boolean =
        preferences(context).edit().putString(TASKS, encode(tasks)).commit()

    fun loadWithLegacy(context: Context, legacy: List<LegacySelfTodo>): List<TodoTask> {
        val stored = read(context)
        val prefs = preferences(context)
        if (prefs.getBoolean(MIGRATED, false)) return stored
        val now = LocalDate.now()
        val additions = legacy.filterNot { old -> stored.any { it.id == old.id } }.map { old ->
            val oldDate = Instant.ofEpochMilli(old.updatedAt.coerceAtLeast(0L))
                .atZone(ZoneId.systemDefault()).toLocalDate()
            TodoTask(
                id = old.id, title = old.title.ifBlank { old.note }, note = if (old.title.isBlank()) "" else old.note,
                firstDate = if (old.done) oldDate else now,
                minutes = null, repeat = TodoRepeat.ONCE, createdAt = old.updatedAt,
                completions = if (old.done) listOf(TodoCompletion(oldDate, old.updatedAt)) else emptyList(),
            )
        }
        val merged = stored + additions
        // Mark the migration only together with its data. Failed commits can retry.
        prefs.edit().putString(TASKS, encode(merged)).putBoolean(MIGRATED, true).commit()
        return merged
    }
}

private val todoDateFormatter = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)

private fun todoDateLabel(date: LocalDate, today: LocalDate, minutes: Int?): String {
    val day = when {
        date.isBefore(today) -> "逾期 · " + date.format(todoDateFormatter)
        date == today -> "今天"
        date == today.plusDays(1) -> "明天"
        else -> date.format(todoDateFormatter)
    }
    val time = if (minutes == null) "不设时间" else
        String.format(Locale.getDefault(), "%02d:%02d", minutes / 60, minutes % 60)
    return day + "  ·  " + time
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelfTodoScreen(
    legacy: List<LegacySelfTodo>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var tasks by remember { mutableStateOf(SelfTodoStore.loadWithLegacy(context, legacy)) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    var showingHistory by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TodoTask?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<TodoTask?>(null) }
    var error by remember { mutableStateOf("") }

    // The screen can stay open across midnight; process-restored screens also read today's date.
    LaunchedEffect(Unit) {
        while (true) {
            today = LocalDate.now()
            delay(60_000L)
        }
    }

    fun save(next: List<TodoTask>): Boolean {
        if (!SelfTodoStore.save(context, next)) {
            error = "保存失败，请重试"
            return false
        }
        tasks = next
        error = ""
        return true
    }

    val pending = remember(tasks, today) { SelfTodoCalendar.allPending(tasks, today) }
    val completed = remember(tasks) {
        tasks.flatMap { task -> task.completions.map { task to it } }
            .sortedByDescending { it.second.completedAt }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (showingHistory) "已完成" else "所行", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (showingHistory) showingHistory = false else onBack()
                    }) { Icon(Icons.Outlined.ArrowBack, "返回") }
                },
                actions = {
                    if (!showingHistory) {
                        IconButton(onClick = { showingHistory = true }) {
                            Icon(Icons.Outlined.History, "查看已完成历史")
                        }
                        IconButton(onClick = { editing = null; showEditor = true }) {
                            Icon(Icons.Outlined.Add, "添加待办")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!showingHistory) FloatingActionButton(onClick = {
                editing = null
                showEditor = true
            }, containerColor = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Outlined.Add, "添加待办")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 18.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(if (showingHistory) "完成记录" else "待办清单",
                    fontWeight = FontWeight.SemiBold, fontSize = 23.sp,
                    modifier = Modifier.weight(1f))
                Text(
                    (if (showingHistory) completed.size else pending.size).toString() + " 项",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (error.isNotBlank()) Text(error,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
            if (showingHistory) {
                if (completed.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("还没有完成记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else LazyColumn(
                    contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
                ) {
                    items(completed, key = { (task, log) -> task.id + "@" + log.date }) { (task, log) ->
                        TodoHistoryRow(
                            task = task,
                            completion = log,
                            onRestore = {
                                save(tasks.map { current ->
                                    if (current.id == task.id) SelfTodoCalendar.undo(current, log.date, today)
                                    else current
                                })
                            },
                        )
                    }
                }
            } else {
                if (pending.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Icon(Icons.Outlined.CheckCircleOutline, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(35.dp))
                            Text("眼前的事项都完成了",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { editing = null; showEditor = true }) {
                                Text("添加待办")
                            }
                        }
                    }
                } else LazyColumn(
                    contentPadding = PaddingValues(top = 8.dp, bottom = 110.dp),
                ) {
                    items(pending, key = { it.task.id + "@" + it.date }) { occurrence ->
                        TodoPendingRow(
                            occurrence = occurrence, today = today,
                            onEdit = { editing = occurrence.task; showEditor = true },
                            onComplete = {
                                save(tasks.map { current ->
                                    if (current.id == occurrence.task.id) {
                                        SelfTodoCalendar.complete(current, occurrence.date, System.currentTimeMillis())
                                    } else current
                                })
                            },
                        )
                    }
                }
            }
        }
    }

    if (showEditor) {
        val original = editing
        var title by remember(showEditor, original?.id) { mutableStateOf(original?.title.orEmpty()) }
        var note by remember(showEditor, original?.id) { mutableStateOf(original?.note.orEmpty()) }
        var firstDate by remember(showEditor, original?.id) {
            mutableStateOf(original?.firstDate ?: LocalDate.now())
        }
        var minute by remember(showEditor, original?.id) { mutableStateOf(original?.minutes) }
        var repeat by remember(showEditor, original?.id) {
            mutableStateOf(original?.repeat ?: TodoRepeat.ONCE)
        }
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text(if (original == null) "新建待办" else "编辑待办") },
            text = {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    OutlinedTextField(
                        value = title, onValueChange = { title = it },
                        label = { Text("要做什么？") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = note, onValueChange = { note = it },
                        label = { Text("备注（选填）") }, minLines = 2, maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("重复", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            TodoRepeat.ONCE to "仅一次",
                            TodoRepeat.DAILY to "每天",
                            TodoRepeat.WEEKLY to "每周",
                        ).forEach { (option, label) ->
                            FilterChip(selected = repeat == option, onClick = { repeat = option },
                                label = { Text(label, fontSize = 12.sp) })
                        }
                    }
                    Text(
                        if (repeat == TodoRepeat.ONCE) "日期 / 截止日期"
                        else if (repeat == TodoRepeat.DAILY) "开始日期" else "每周执行日（首次日期）",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            DatePickerDialog(
                                context,
                                { _, year, month, day -> firstDate = LocalDate.of(year, month + 1, day) },
                                firstDate.year, firstDate.monthValue - 1, firstDate.dayOfMonth,
                            ).show()
                        },
                    ) { Text(firstDate.format(todoDateFormatter)) }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val initial = minute ?: 9 * 60
                            TimePickerDialog(
                                context,
                                { _, hour, minutes -> minute = hour * 60 + minutes },
                                initial / 60, initial % 60, true,
                            ).show()
                        },
                    ) {
                        Text(if (minute == null) "不设具体时间  ·  选择时间" else
                            String.format(Locale.getDefault(), "%02d:%02d", minute!! / 60, minute!! % 60))
                    }
                    if (minute != null) {
                        TextButton(onClick = { minute = null }) { Text("清除时间") }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = title.isNotBlank(),
                    onClick = {
                        val updated = TodoTask(
                            id = original?.id ?: UUID.randomUUID().toString(),
                            title = title.trim(), note = note.trim(), firstDate = firstDate,
                            minutes = minute, repeat = repeat,
                            createdAt = original?.createdAt ?: System.currentTimeMillis(),
                            completions = original?.completions.orEmpty(),
                            reopened = original?.reopened.orEmpty(),
                        )
                        if (save(tasks.filterNot { it.id == updated.id } + updated)) showEditor = false
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                Row {
                    if (original != null) TextButton(onClick = {
                        showEditor = false
                        toDelete = original
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { showEditor = false }) { Text("取消") }
                }
            },
        )
    }

    toDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("删除待办？") },
            text = { Text("将一并删除这项待办的完成历史，且无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    if (save(tasks.filterNot { it.id == task.id })) toDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun TodoPendingRow(
    occurrence: TodoOccurrence,
    today: LocalDate,
    onEdit: () -> Unit,
    onComplete: () -> Boolean,
) {
    var hiding by remember(occurrence.task.id, occurrence.date) { mutableStateOf(false) }
    LaunchedEffect(hiding) {
        if (hiding) {
            delay(210L)
            if (!onComplete()) hiding = false
        }
    }
    AnimatedVisibility(
        visible = !hiding,
        exit = fadeOut(tween(190)) + shrinkVertically(tween(190)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = hiding,
                onCheckedChange = { if (!hiding) hiding = true },
                modifier = Modifier.padding(end = 7.dp),
            )
            Surface(
                onClick = onEdit,
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.weight(1f),
            ) {
                Column(Modifier.padding(top = 9.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(occurrence.task.title, fontSize = 16.sp,
                        fontWeight = FontWeight.Medium, maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (hiding) TextDecoration.LineThrough else null)
                    if (occurrence.task.note.isNotBlank()) {
                        Text(occurrence.task.note,
                            fontSize = 12.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        todoDateLabel(occurrence.date, today, occurrence.task.minutes) +
                            when (occurrence.task.repeat) {
                                TodoRepeat.ONCE -> ""
                                TodoRepeat.DAILY -> "  ·  每天"
                                TodoRepeat.WEEKLY -> "  ·  每周"
                            },
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Outlined.Edit, "编辑待办",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun TodoHistoryRow(
    task: TodoTask,
    completion: TodoCompletion,
    onRestore: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = true, onCheckedChange = { if (!it) onRestore() })
        Column(Modifier.weight(1f).padding(start = 7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(task.title, fontSize = 15.sp, textDecoration = TextDecoration.LineThrough,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val dateLabel = completion.date.format(todoDateFormatter)
            val doneTime = Instant.ofEpochMilli(completion.completedAt).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE))
            Text(dateLabel + " · 完成于 " + doneTime,
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
