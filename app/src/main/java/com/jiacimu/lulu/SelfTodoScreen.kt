package com.jiacimu.lulu

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.activity.compose.BackHandler
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
    // Keep a just-completed row in composition briefly for its exit animation,
    // while the completion itself has already been committed to storage.
    var exiting by remember { mutableStateOf<Map<String, TodoOccurrence>>(emptyMap()) }

    BackHandler {
        if (showingHistory) showingHistory = false else onBack()
    }

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
    val visiblePending = (pending + exiting.values).distinctBy { it.task.id + "@" + it.date }
        .sortedWith(compareBy<TodoOccurrence> { it.date }
            .thenBy { it.task.minutes ?: Int.MAX_VALUE }
            .thenBy { it.task.createdAt }
            .thenBy { it.task.id })
    val completed = remember(tasks) {
        tasks.flatMap { task -> task.completions.map { task to it } }
            .sortedByDescending { it.second.completedAt }
    }

    Scaffold(
        containerColor = TodoInk.background,
        topBar = {
            Surface(color = TodoInk.background) {
                Row(
                    Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        if (showingHistory) showingHistory = false else onBack()
                    }) {
                        Icon(Icons.Outlined.ArrowBack, "返回", tint = TodoInk.title)
                    }
                    Text(if (showingHistory) "已完成" else "所行",
                        fontWeight = FontWeight.Bold, fontSize = 20.sp,
                        color = TodoInk.title, modifier = Modifier.weight(1f))
                    if (!showingHistory) {
                        IconButton(onClick = { exiting = emptyMap(); showingHistory = true }) {
                            Icon(Icons.Outlined.History, "查看已完成历史", tint = TodoInk.violet)
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!showingHistory) TodoAddButton {
                editing = null
                showEditor = true
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp,
                bottom = if (showingHistory) 35.dp else 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (error.isNotBlank()) {
                item(key = "todo_error") {
                    Text(error, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(8.dp))
                }
            }
            if (!showingHistory) {
                item(key = "todo_hero") {
                    TodoHero(today, pending.size, completed.size,
                        onHistory = { exiting = emptyMap(); showingHistory = true })
                }
                item(key = "todo_heading") {
                    Spacer(Modifier.height(10.dp))
                    TodoListHeading("待办清单", pending.size.toString() + " 件未完成")
                }
                if (visiblePending.isEmpty()) {
                    item(key = "todo_empty") {
                        TodoEmptyState(history = false) {
                            editing = null
                            showEditor = true
                        }
                    }
                } else {
                    items(visiblePending, key = { it.task.id + "@" + it.date }) { occurrence ->
                        TodoPendingRow(
                            occurrence = occurrence, today = today,
                            onEdit = { editing = occurrence.task; showEditor = true },
                            onComplete = {
                                val success = save(tasks.map { current ->
                                    if (current.id == occurrence.task.id) {
                                        SelfTodoCalendar.complete(
                                            current, occurrence.date, System.currentTimeMillis(),
                                        )
                                    } else current
                                })
                                if (success) {
                                    val key = occurrence.task.id + "@" + occurrence.date
                                    exiting = exiting + (key to occurrence)
                                }
                                success
                            },
                            onFaded = {
                                exiting = exiting - (occurrence.task.id + "@" + occurrence.date)
                            },
                        )
                    }
                }
            } else {
                item(key = "history_hero") {
                    Surface(
                        shape = RoundedCornerShape(26.dp),
                        color = TodoInk.sageLight,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(Modifier.fillMaxWidth().height(138.dp)) {
                            TodoBotanical(
                                modifier = Modifier.align(Alignment.CenterEnd)
                                    .size(143.dp, 138.dp),
                                ink = TodoInk.sage.copy(alpha = .36f),
                            )
                            Column(Modifier.align(Alignment.CenterStart)
                                .padding(horizontal = 23.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("THE LITTLE THINGS I'VE DONE", color = TodoInk.sage,
                                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 1.sp)
                                Text("完成的足迹", color = TodoInk.title,
                                    fontSize = 25.sp, fontWeight = FontWeight.Bold)
                                Text("每件事，都算数。", color = TodoInk.secondary,
                                    fontSize = 12.sp)
                            }
                        }
                    }
                }
                item(key = "history_heading") {
                    Spacer(Modifier.height(9.dp))
                    TodoListHeading("完成记录", completed.size.toString() + " 件")
                }
                if (completed.isEmpty()) {
                    item(key = "history_empty") { TodoEmptyState(history = true) }
                } else {
                    items(completed, key = { (task, log) -> task.id + "@" + log.date }) {
                        (task, log) ->
                        TodoHistoryCard(
                            task = task, completion = log,
                            onRestore = {
                                save(tasks.map { current ->
                                    if (current.id == task.id) {
                                        SelfTodoCalendar.undo(current, log.date, today)
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
        TodoEditorDialog(
            original = original,
            onDismiss = { showEditor = false },
            onDelete = { target ->
                showEditor = false
                toDelete = target
            },
            onSave = { title, note, firstDate, minute, repeat ->
                val updated = TodoTask(
                    id = original?.id ?: UUID.randomUUID().toString(),
                    title = title, note = note, firstDate = firstDate,
                    minutes = minute, repeat = repeat,
                    createdAt = original?.createdAt ?: System.currentTimeMillis(),
                    completions = original?.completions.orEmpty(),
                    reopened = original?.reopened.orEmpty(),
                )
                save(tasks.filterNot { it.id == updated.id } + updated)
            },
        )
    }

    toDelete?.let { task ->
        TodoDeleteDialog(
            task = task,
            onDismiss = { toDelete = null },
            onConfirm = {
                if (save(tasks.filterNot { it.id == task.id })) toDelete = null
            },
        )
    }
}

@Composable
private fun TodoPendingRow(
    occurrence: TodoOccurrence,
    today: LocalDate,
    onEdit: () -> Unit,
    onComplete: () -> Boolean,
    onFaded: () -> Unit,
) {
    var hiding by remember(occurrence.task.id, occurrence.date) { mutableStateOf(false) }
    LaunchedEffect(hiding) {
        if (hiding) {
            delay(230L)
            onFaded()
        }
    }
    AnimatedVisibility(
        visible = !hiding,
        exit = fadeOut(tween(205)) + shrinkVertically(tween(205)),
    ) {
        TodoTaskCard(
            occurrence = occurrence, today = today, checked = hiding,
            onCheck = { if (!hiding && onComplete()) hiding = true },
            onEdit = onEdit,
        )
    }
}
