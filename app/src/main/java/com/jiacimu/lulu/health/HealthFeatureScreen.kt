package com.jiacimu.lulu.health

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jiacimu.lulu.MigrationActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

private val HealthPaper = Color(0xFFFFFBFC)
private val HealthCard = Color.White
private val HealthSoft = Color(0xFFFFF1F4)
private val HealthSelected = Color(0xFFFFDEE7)
private val HealthPredicted = Color(0xFFF4E8F0)
private val HealthAccent = Color(0xFFB84F6B)
private val HealthAccentDark = Color(0xFF8C344D)
private val HealthInk = Color(0xFF2A2326)
private val HealthMuted = Color(0xFF81777B)
private val HealthLine = Color(0xFFECE1E4)

internal data class PeriodRecord(
    val id: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

internal data class HealthCycleState(
    val records: List<PeriodRecord> = emptyList(),
    val reminderEnabled: Boolean = true,
    val reminderDaysBefore: Int = 3,
)

internal data class PeriodPrediction(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val cycleDays: Int,
    val periodDays: Int,
)

internal object HealthCycleStore {
    private const val PREFS_NAME = "lulu_health_cycle"
    private const val KEY_STATE = "cycle_state_v1"
    private val mutableState = MutableStateFlow(HealthCycleState())
    val state: StateFlow<HealthCycleState> = mutableState.asStateFlow()
    private var context: Context? = null

    @Synchronized
    fun initialize(appContext: Context) {
        if (context != null) return
        context = appContext.applicationContext
        val raw = context!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_STATE, null)
        mutableState.value = decode(raw)
        HealthPeriodReminderScheduler.reschedule(context!!, mutableState.value)
    }

    @Synchronized
    fun savePeriod(first: LocalDate, second: LocalDate) {
        val start = minOf(first, second)
        val end = maxOf(first, second)
        val record = PeriodRecord(UUID.randomUUID().toString(), start, end)
        val records = mutableState.value.records
            .filterNot { it.startDate == start && it.endDate == end }
            .plus(record)
            .sortedByDescending { it.startDate }
            .take(48)
        persist(mutableState.value.copy(records = records))
    }

    @Synchronized
    fun deletePeriod(id: String) {
        persist(mutableState.value.copy(records = mutableState.value.records.filterNot { it.id == id }))
    }

    @Synchronized
    fun setReminderEnabled(enabled: Boolean) {
        persist(mutableState.value.copy(reminderEnabled = enabled))
    }

    @Synchronized
    fun setReminderDaysBefore(days: Int) {
        persist(mutableState.value.copy(reminderDaysBefore = days.coerceIn(0, 14)))
    }

    fun prediction(value: HealthCycleState = mutableState.value): PeriodPrediction? {
        val latest = value.records.maxByOrNull { it.startDate } ?: return null
        val cycleDays = averageCycleDays(value.records)
        val periodDays = averagePeriodDays(value.records)
        val start = latest.startDate.plusDays(cycleDays.toLong())
        return PeriodPrediction(
            startDate = start,
            endDate = start.plusDays((periodDays - 1).toLong()),
            cycleDays = cycleDays,
            periodDays = periodDays,
        )
    }

    private fun averageCycleDays(records: List<PeriodRecord>): Int {
        val starts = records.map { it.startDate }.distinct().sorted()
        val gaps = starts.zipWithNext { first, second ->
            ChronoUnit.DAYS.between(first, second).toInt()
        }.filter { it in 15..60 }
        return if (gaps.isEmpty()) 28 else gaps.average().roundToInt().coerceIn(15, 60)
    }

    private fun averagePeriodDays(records: List<PeriodRecord>): Int {
        val lengths = records.map {
            ChronoUnit.DAYS.between(it.startDate, it.endDate).toInt() + 1
        }.filter { it in 1..15 }
        return if (lengths.isEmpty()) 5 else lengths.average().roundToInt().coerceIn(1, 15)
    }

    private fun persist(next: HealthCycleState) {
        mutableState.value = next
        val appContext = context ?: return
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATE, encode(next).toString())
            .apply()
        HealthPeriodReminderScheduler.reschedule(appContext, next)
    }

    private fun encode(value: HealthCycleState): JSONObject = JSONObject().apply {
        put("reminderEnabled", value.reminderEnabled)
        put("reminderDaysBefore", value.reminderDaysBefore)
        put("records", JSONArray().apply {
            value.records.forEach { record ->
                put(
                    JSONObject()
                        .put("id", record.id)
                        .put("start", record.startDate.toString())
                        .put("end", record.endDate.toString()),
                )
            }
        })
    }

    private fun decode(raw: String?): HealthCycleState = runCatching {
        val root = JSONObject(raw ?: "{}")
        val array = root.optJSONArray("records") ?: JSONArray()
        val records = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val start = runCatching { LocalDate.parse(item.optString("start")) }.getOrNull() ?: continue
                val end = runCatching { LocalDate.parse(item.optString("end")) }.getOrNull() ?: start
                add(
                    PeriodRecord(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        startDate = minOf(start, end),
                        endDate = maxOf(start, end),
                    ),
                )
            }
        }.sortedByDescending { it.startDate }
        HealthCycleState(
            records = records,
            reminderEnabled = root.optBoolean("reminderEnabled", true),
            reminderDaysBefore = root.optInt("reminderDaysBefore", 3).coerceIn(0, 14),
        )
    }.getOrDefault(HealthCycleState())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthFeatureScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    remember(context) {
        HealthCycleStore.initialize(context.applicationContext)
        Unit
    }
    val state by HealthCycleStore.state.collectAsState()
    val prediction = remember(state) { HealthCycleStore.prediction(state) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val month = remember(monthText) { YearMonth.parse(monthText) }
    var startText by rememberSaveable { mutableStateOf<String?>(null) }
    var endText by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedStart = startText?.let(LocalDate::parse)
    val selectedEnd = endText?.let(LocalDate::parse)
    var reminderMenuOpen by remember { mutableStateOf(false) }
    var showWearableNotice by remember { mutableStateOf(false) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            HealthCycleStore.setReminderEnabled(false)
        }
    }

    fun changeReminder(enabled: Boolean) {
        if (
            enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        HealthCycleStore.setReminderEnabled(enabled)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(286.dp).fillMaxHeight(),
                drawerContainerColor = HealthPaper,
            ) {
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = RoundedCornerShape(16.dp), color = HealthSelected) {
                        Icon(Icons.Outlined.FavoriteBorder, null, tint = HealthAccentDark, modifier = Modifier.padding(11.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("健康", color = HealthInk, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text("个人健康记录", color = HealthMuted, fontSize = 11.sp)
                    }
                }
                HorizontalDivider(color = HealthLine)
                NavigationDrawerItem(
                    label = {
                        Column {
                            Text("经期", fontWeight = FontWeight.SemiBold)
                            Text("记录、预测和提醒都在这一页", color = HealthMuted, fontSize = 11.sp)
                        }
                    },
                    selected = true,
                    icon = { Icon(Icons.Outlined.CalendarMonth, null) },
                    onClick = { scope.launch { drawerState.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = HealthSelected,
                        selectedTextColor = HealthInk,
                        selectedIconColor = HealthAccentDark,
                    ),
                )
                NavigationDrawerItem(
                    label = {
                        Column {
                            Text("手环数据", fontWeight = FontWeight.SemiBold)
                            Text("睡眠、心率、步数稍后接入", color = HealthMuted, fontSize = 11.sp)
                        }
                    },
                    selected = false,
                    icon = { Icon(Icons.Outlined.Watch, null) },
                    onClick = {
                        scope.launch { drawerState.close() }
                        showWearableNotice = true
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "经期日期是根据手动记录得到的预测，不替代医疗判断。",
                    color = HealthMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(20.dp),
                )
            }
        },
    ) {
        Scaffold(
            containerColor = HealthPaper,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("健康", color = HealthInk, fontWeight = FontWeight.SemiBold)
                            Text("经期", color = HealthMuted, fontSize = 10.sp, letterSpacing = 1.sp)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Outlined.Menu, "打开菜单", tint = HealthInk)
                        }
                    },
                    actions = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Outlined.Home, "返回桌面", tint = HealthInk)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = HealthPaper),
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { OverviewCard(state, prediction) }
                item {
                    CalendarCard(
                        month = month,
                        records = state.records,
                        prediction = prediction,
                        selectedStart = selectedStart,
                        selectedEnd = selectedEnd,
                        onPrevious = { monthText = month.minusMonths(1).toString() },
                        onNext = { monthText = month.plusMonths(1).toString() },
                        onToday = { monthText = YearMonth.now().toString() },
                        onDate = { date ->
                            if (selectedStart == null || selectedEnd != null) {
                                startText = date.toString()
                                endText = null
                            } else {
                                endText = date.toString()
                            }
                        },
                    )
                }
                item {
                    SelectionCard(
                        start = selectedStart,
                        end = selectedEnd,
                        onClear = {
                            startText = null
                            endText = null
                        },
                        onSave = {
                            val start = selectedStart
                            if (start != null) {
                                HealthCycleStore.savePeriod(start, selectedEnd ?: start)
                                startText = null
                                endText = null
                            }
                        },
                    )
                }
                item {
                    ReminderCard(
                        state = state,
                        prediction = prediction,
                        menuOpen = reminderMenuOpen,
                        onMenuOpen = { reminderMenuOpen = it },
                        onEnabled = ::changeReminder,
                        onDays = HealthCycleStore::setReminderDaysBefore,
                    )
                }
                item { Text("历史记录", color = HealthInk, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                if (state.records.isEmpty()) {
                    item {
                        HealthPanel {
                            Text("还没有经期记录", color = HealthInk, fontWeight = FontWeight.SemiBold)
                            Text("在月历里先点开始日期，再点结束日期。", color = HealthMuted, fontSize = 12.sp)
                        }
                    }
                } else {
                    items(state.records, key = { it.id }) { record ->
                        HistoryRow(record) { HealthCycleStore.deletePeriod(record.id) }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    if (showWearableNotice) {
        AlertDialog(
            onDismissRequest = { showWearableNotice = false },
            icon = { Icon(Icons.Outlined.Watch, null, tint = HealthAccentDark) },
            title = { Text("手环数据已预留") },
            text = { Text("后面会在这里接入睡眠、心率、步数、血氧和活动数据。") },
            confirmButton = {
                TextButton(onClick = { showWearableNotice = false }) { Text("知道啦") }
            },
        )
    }
}

@Composable
private fun OverviewCard(state: HealthCycleState, prediction: PeriodPrediction?) {
    val today = LocalDate.now()
    val distance = prediction?.let { ChronoUnit.DAYS.between(today, it.startDate) }
    val latest = state.records.maxByOrNull { it.startDate }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = HealthAccentDark,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("CYCLE", color = Color.White.copy(alpha = 0.68f), fontSize = 9.sp, letterSpacing = 1.8.sp)
            Text(
                when {
                    prediction == null -> "从第一次记录开始"
                    distance == null -> "下一次预测"
                    distance > 0 -> "预计还有 $distance 天"
                    distance == 0L -> "预计今天开始"
                    else -> "比预测晚了 ${-distance} 天"
                },
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                prediction?.let { "预计 ${formatRange(it.startDate, it.endDate)}" }
                    ?: "在下面的日历选择这次经期的日期。",
                color = Color.White.copy(alpha = 0.84f),
                fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WhiteTag("周期 ${prediction?.cycleDays ?: 28} 天")
                WhiteTag("经期 ${prediction?.periodDays ?: 5} 天")
            }
            latest?.let {
                Text("最近记录：${formatRange(it.startDate, it.endDate)}", color = Color.White.copy(alpha = 0.68f), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun WhiteTag(text: String) {
    Surface(color = Color.White.copy(alpha = 0.12f), shape = RoundedCornerShape(99.dp)) {
        Text(text, color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
    }
}

@Composable
private fun CalendarCard(
    month: YearMonth,
    records: List<PeriodRecord>,
    prediction: PeriodPrediction?,
    selectedStart: LocalDate?,
    selectedEnd: LocalDate?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onDate: (LocalDate) -> Unit,
) {
    val offset = month.atDay(1).dayOfWeek.value - 1
    val cells = buildList<LocalDate?> {
        repeat(offset) { add(null) }
        for (day in 1..month.lengthOfMonth()) add(month.atDay(day))
        while (size % 7 != 0) add(null)
    }
    val selectedFirst = selectedStart?.let { start -> selectedEnd?.let { minOf(start, it) } ?: start }
    val selectedLast = selectedStart?.let { start -> selectedEnd?.let { maxOf(start, it) } ?: start }

    HealthPanel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    month.format(DateTimeFormatter.ofPattern("yyyy年 M月", Locale.SIMPLIFIED_CHINESE)),
                    color = HealthInk,
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                )
                Text("点开始日期，再点结束日期", color = HealthMuted, fontSize = 11.sp)
            }
            TextButton(onClick = onToday) { Text("今天", color = HealthAccentDark) }
            IconButton(onClick = onPrevious) { Icon(Icons.Outlined.ChevronLeft, "上个月") }
            IconButton(onClick = onNext) { Icon(Icons.Outlined.ChevronRight, "下个月") }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
                Text(day, color = HealthMuted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    if (date == null) {
                        Spacer(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val recorded = records.any { date.inside(it.startDate, it.endDate) }
                        val predicted = prediction?.let { date.inside(it.startDate, it.endDate) } == true
                        val selected = selectedFirst != null && selectedLast != null && date.inside(selectedFirst, selectedLast)
                        DayCell(
                            date = date,
                            recorded = recorded,
                            predicted = predicted,
                            selected = selected,
                            today = date == LocalDate.now(),
                            modifier = Modifier.weight(1f),
                            onClick = { onDate(date) },
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(HealthAccent, "已记录")
            Legend(HealthPredicted, "预测")
            Legend(HealthSelected, "选择中")
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    recorded: Boolean,
    predicted: Boolean,
    selected: Boolean,
    today: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val background = when {
        selected -> HealthSelected
        recorded -> HealthAccent
        predicted -> HealthPredicted
        else -> Color.Transparent
    }
    Surface(
        onClick = onClick,
        modifier = modifier.aspectRatio(1f).padding(2.dp),
        color = background,
        shape = RoundedCornerShape(13.dp),
        border = if (today) BorderStroke(1.5.dp, if (recorded) Color.White else HealthAccentDark) else null,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    date.dayOfMonth.toString(),
                    color = if (recorded && !selected) Color.White else HealthInk,
                    fontSize = 13.sp,
                    fontWeight = if (today) FontWeight.Bold else FontWeight.Normal,
                )
                if (predicted && !recorded) {
                    Spacer(Modifier.height(2.dp))
                    Surface(Modifier.size(4.dp), CircleShape, HealthAccent) {}
                }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(8.dp), CircleShape, color) {}
        Spacer(Modifier.width(5.dp))
        Text(text, color = HealthMuted, fontSize = 10.sp)
    }
}

@Composable
private fun SelectionCard(start: LocalDate?, end: LocalDate?, onClear: () -> Unit, onSave: () -> Unit) {
    HealthPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(15.dp), color = HealthSelected) {
                Icon(Icons.Outlined.EditCalendar, null, tint = HealthAccentDark, modifier = Modifier.padding(10.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("本次经期", color = HealthInk, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        start == null -> "还没有选择日期"
                        end == null -> "开始于 ${formatDate(start)}，再点一次选择结束"
                        else -> formatRange(minOf(start, end), maxOf(start, end))
                    },
                    color = HealthMuted,
                    fontSize = 12.sp,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onClear, enabled = start != null, modifier = Modifier.weight(1f)) {
                Text("重新选择")
            }
            Button(
                onClick = onSave,
                enabled = start != null,
                modifier = Modifier.weight(1.3f),
                colors = ButtonDefaults.buttonColors(containerColor = HealthAccentDark),
            ) {
                Text(if (end == null) "保存开始日" else "保存这次经期")
            }
        }
    }
}

@Composable
private fun ReminderCard(
    state: HealthCycleState,
    prediction: PeriodPrediction?,
    menuOpen: Boolean,
    onMenuOpen: (Boolean) -> Unit,
    onEnabled: (Boolean) -> Unit,
    onDays: (Int) -> Unit,
) {
    HealthPanel {
        Text("预测与提醒", color = HealthInk, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            prediction?.let { "下一次预计从 ${formatDate(it.startDate)} 开始，持续约 ${it.periodDays} 天。" }
                ?: "保存第一条记录后，这里会出现下一次预测。",
            color = HealthMuted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        HorizontalDivider(color = HealthLine)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("经期提醒", color = HealthInk, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        !state.reminderEnabled -> "提醒已关闭"
                        state.reminderDaysBefore == 0 -> "预计当天提醒"
                        else -> "预计开始前 ${state.reminderDaysBefore} 天提醒"
                    },
                    color = HealthMuted,
                    fontSize = 11.sp,
                )
            }
            Switch(
                checked = state.reminderEnabled,
                onCheckedChange = onEnabled,
                colors = SwitchDefaults.colors(checkedTrackColor = HealthAccent),
            )
        }
        if (state.reminderEnabled) {
            Box {
                OutlinedButton(onClick = { onMenuOpen(true) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.NotificationsNone, null, tint = HealthAccentDark)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.reminderDaysBefore == 0) "预计当天提醒" else "提前 ${state.reminderDaysBefore} 天提醒")
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Outlined.ExpandMore, null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpen(false) }) {
                    listOf(0, 1, 2, 3, 5, 7).forEach { days ->
                        DropdownMenuItem(
                            text = { Text(if (days == 0) "预计当天提醒" else "提前 $days 天") },
                            leadingIcon = {
                                if (days == state.reminderDaysBefore) Icon(Icons.Outlined.Check, null, tint = HealthAccentDark)
                            },
                            onClick = {
                                onDays(days)
                                onMenuOpen(false)
                            },
                        )
                    }
                }
            }
        }
        Text("预测会随着记录自动调整，周期波动很常见。", color = HealthMuted, fontSize = 10.sp)
    }
}

@Composable
private fun HistoryRow(record: PeriodRecord, onDelete: () -> Unit) {
    HealthPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = HealthSoft) {
                Column(Modifier.padding(horizontal = 13.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(record.startDate.monthValue.toString().padStart(2, '0'), color = HealthAccentDark, fontSize = 10.sp)
                    Text(record.startDate.dayOfMonth.toString(), color = HealthInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(formatRange(record.startDate, record.endDate), color = HealthInk, fontWeight = FontWeight.SemiBold)
                Text("共 ${ChronoUnit.DAYS.between(record.startDate, record.endDate) + 1} 天", color = HealthMuted, fontSize = 11.sp)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "删除记录", tint = HealthMuted) }
        }
    }
}

@Composable
private fun HealthPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = HealthCard,
        shape = RoundedCornerShape(23.dp),
        border = BorderStroke(1.dp, HealthLine),
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp), content = content)
    }
}

private fun LocalDate.inside(first: LocalDate, second: LocalDate): Boolean {
    val start = minOf(first, second)
    val end = maxOf(first, second)
    return !isBefore(start) && !isAfter(end)
}

private fun formatDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("M月d日", Locale.SIMPLIFIED_CHINESE))

private fun formatRange(start: LocalDate, end: LocalDate): String =
    if (start == end) formatDate(start) else "${formatDate(start)}—${formatDate(end)}"

internal object HealthPeriodReminderScheduler {
    private const val REQUEST_CODE = 0x4845

    fun reschedule(context: Context, state: HealthCycleState) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(AlarmManager::class.java)
        val operation = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            Intent(appContext, HealthPeriodReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.cancel(operation)
        if (!state.reminderEnabled) return
        val prediction = HealthCycleStore.prediction(state) ?: return
        val reminderDate = prediction.startDate.minusDays(state.reminderDaysBefore.toLong())
        val trigger = reminderDate.atTime(LocalTime.of(9, 0)).atZone(ZoneId.systemDefault()).toInstant()
        if (!trigger.isAfter(Instant.now())) return
        runCatching {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), operation)
        }.recoverCatching {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toEpochMilli(), operation)
        }
    }
}

class HealthPeriodReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HealthCycleStore.initialize(context.applicationContext)
        val prediction = HealthCycleStore.prediction() ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "经期提醒", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val openApp = PendingIntent.getActivity(
            context,
            0x4846,
            Intent(context, MigrationActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("经期可能快到了")
            .setContentText("按记录预测，下一次大约从 ${formatDate(prediction.startDate)} 开始。")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private companion object {
        const val CHANNEL_ID = "lulu_health_period"
        const val NOTIFICATION_ID = 0x4847
    }
}

class HealthReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            HealthCycleStore.initialize(context.applicationContext)
            HealthPeriodReminderScheduler.reschedule(context.applicationContext, HealthCycleStore.state.value)
        }
    }
}
