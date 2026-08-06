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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

private val HealthPaper = Color(0xFFFFFBFC)
private val HealthCard = Color(0xFFFFFFFF)
private val HealthSoft = Color(0xFFFFF2F5)
private val HealthSoftStrong = Color(0xFFFFE3EA)
private val HealthAccent = Color(0xFFB94E6A)
private val HealthAccentDark = Color(0xFF8E354E)
private val HealthInk = Color(0xFF2A2326)
private val HealthMuted = Color(0xFF81777B)
private val HealthLine = Color(0xFFEDE3E6)
private val HealthPrediction = Color(0xFFF4E9F1)

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
    val averageCycleDays: Int,
    val averagePeriodDays: Int,
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
        val prefs = context!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mutableState.value = decode(prefs.getString(KEY_STATE, null))
        HealthPeriodReminderScheduler.reschedule(context!!, mutableState.value)
    }

    @Synchronized
    fun savePeriod(first: LocalDate, second: LocalDate) {
        val start = minOf(first, second)
        val end = maxOf(first, second)
        val normalized = PeriodRecord(UUID.randomUUID().toString(), start, end)
        val withoutExactDuplicate = mutableState.value.records.filterNot {
            it.startDate == normalized.startDate && it.endDate == normalized.endDate
        }
        mutate(
            mutableState.value.copy(
                records = (withoutExactDuplicate + normalized)
                    .sortedByDescending(PeriodRecord::startDate)
                    .take(48),
            ),
        )
    }

    @Synchronized
    fun deletePeriod(id: String) {
        mutate(mutableState.value.copy(records = mutableState.value.records.filterNot { it.id == id }))
    }

    @Synchronized
    fun setReminderEnabled(enabled: Boolean) {
        mutate(mutableState.value.copy(reminderEnabled = enabled))
    }

    @Synchronized
    fun setReminderDaysBefore(days: Int) {
        mutate(mutableState.value.copy(reminderDaysBefore = days.coerceIn(0, 14)))
    }

    fun prediction(current: HealthCycleState = mutableState.value): PeriodPrediction? {
        val latest = current.records.maxByOrNull(PeriodRecord::startDate) ?: return null
        val cycleDays = averageCycleDays(current.records)
        val periodDays = averagePeriodDays(current.records)
        val predictedStart = latest.startDate.plusDays(cycleDays.toLong())
        return PeriodPrediction(
            startDate = predictedStart,
            endDate = predictedStart.plusDays((periodDays - 1).toLong()),
            averageCycleDays = cycleDays,
            averagePeriodDays = periodDays,
        )
    }

    fun averageCycleDays(records: List<PeriodRecord>): Int {
        val starts = records.map(PeriodRecord::startDate).distinct().sorted()
        val gaps = starts.zipWithNext { first, second -> ChronoUnit.DAYS.between(first, second).toInt() }
            .filter { it in 15..60 }
        return gaps.takeIf(List<Int>::isNotEmpty)?.average()?.roundToInt()?.coerceIn(15, 60) ?: 28
    }

    fun averagePeriodDays(records: List<PeriodRecord>): Int {
        val lengths = records.map {
            ChronoUnit.DAYS.between(it.startDate, it.endDate).toInt() + 1
        }.filter { it in 1..15 }
        return lengths.takeIf(List<Int>::isNotEmpty)?.average()?.roundToInt()?.coerceIn(1, 15) ?: 5
    }

    private fun mutate(next: HealthCycleState) {
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
                        .put("startDate", record.startDate.toString())
                        .put("endDate", record.endDate.toString()),
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
                val start = runCatching { LocalDate.parse(item.optString("startDate")) }.getOrNull() ?: continue
                val end = runCatching { LocalDate.parse(item.optString("endDate")) }.getOrNull() ?: start
                add(
                    PeriodRecord(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        startDate = minOf(start, end),
                        endDate = maxOf(start, end),
                    ),
                )
            }
        }.sortedByDescending(PeriodRecord::startDate)
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
        true
    }
    val state by HealthCycleStore.state.collectAsState()
    val prediction = remember(state) { HealthCycleStore.prediction(state) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val visibleMonth = remember(month) { YearMonth.parse(month) }
    var selectedStart by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedEnd by rememberSaveable { mutableStateOf<String?>(null) }
    var showWearableDialog by remember { mutableStateOf(false) }
    var reminderMenuOpen by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            HealthCycleStore.setReminderEnabled(false)
        }
    }

    fun updateReminder(enabled: Boolean) {
        if (
            enabled &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        HealthCycleStore.setReminderEnabled(enabled)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(286.dp),
                drawerContainerColor = HealthPaper,
            ) {
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = RoundedCornerShape(16.dp), color = HealthSoftStrong) {
                        Icon(
                            Icons.Outlined.FavoriteBorder,
                            contentDescription = null,
                            tint = HealthAccentDark,
                            modifier = Modifier.padding(11.dp).size(24.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("健康", color = HealthInk, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                        Text("只记录你主动保存的数据", color = HealthMuted, fontSize = 11.sp)
                    }
                }
                HorizontalDivider(color = HealthLine)
                NavigationDrawerItem(
                    label = {
                        Column {
                            Text("经期", fontWeight = FontWeight.SemiBold)
                            Text("记录、预测和提醒都在这里", fontSize = 11.sp, color = HealthMuted)
                        }
                    },
                    selected = true,
                    icon = { Icon(Icons.Outlined.CalendarMonth, null) },
                    onClick = { scope.launch { drawerState.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = HealthSoftStrong,
                        selectedIconColor = HealthAccentDark,
                        selectedTextColor = HealthInk,
                    ),
                )
                NavigationDrawerItem(
                    label = {
                        Column {
                            Text("手环数据", fontWeight = FontWeight.SemiBold)
                            Text("睡眠、心率、步数稍后接入", fontSize = 11.sp, color = HealthMuted)
                        }
                    },
                    selected = false,
                    icon = { Icon(Icons.Outlined.Watch, null) },
                    onClick = {
                        scope.launch { drawerState.close() }
                        showWearableDialog = true
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "经期预测会随着记录增加而调整，不替代医疗判断。",
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
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    PeriodOverviewCard(state = state, prediction = prediction)
                }
                item {
                    PeriodCalendarCard(
                        month = visibleMonth,
                        records = state.records,
                        prediction = prediction,
                        selectedStart = selectedStart?.let(LocalDate::parse),
                        selectedEnd = selectedEnd?.let(LocalDate::parse),
                        onPreviousMonth = { month = visibleMonth.minusMonths(1).toString() },
                        onNextMonth = { month = visibleMonth.plusMonths(1).toString() },
                        onToday = { month = YearMonth.now().toString() },
                        onDateClick = { date ->
                            val currentStart = selectedStart?.let(LocalDate::parse)
                            val currentEnd = selectedEnd?.let(LocalDate::parse)
                            if (currentStart == null || currentEnd != null) {
                                selectedStart = date.toString()
                                selectedEnd = null
                            } else {
                                selectedEnd = date.toString()
                            }
                        },
                    )
                }
                item {
                    SelectedPeriodCard(
                        start = selectedStart?.let(LocalDate::parse),
                        end = selectedEnd?.let(LocalDate::parse),
                        onSave = {
                            val start = selectedStart?.let(LocalDate::parse) ?: return@SelectedPeriodCard
                            val end = selectedEnd?.let(LocalDate::parse) ?: start
                            HealthCycleStore.savePeriod(start, end)
                            selectedStart = null
                            selectedEnd = null
                        },
                        onClear = {
                            selectedStart = null
                            selectedEnd = null
                        },
                    )
                }
                item {
                    PredictionAndReminderCard(
                        state = state,
                        prediction = prediction,
                        reminderMenuOpen = reminderMenuOpen,
                        onReminderMenuChange = { reminderMenuOpen = it },
                        onReminderEnabledChange = ::updateReminder,
                        onReminderDaysChange = HealthCycleStore::setReminderDaysBefore,
                    )
                }
                item {
                    Text(
                        "历史记录",
                        color = HealthInk,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (state.records.isEmpty()) {
                    item {
                        HealthCard {
                            Text("还没有经期记录", color = HealthInk, fontWeight = FontWeight.SemiBold)
                            Text("在上面的月历点开始日期，再点结束日期，然后保存。", color = HealthMuted, fontSize = 12.sp)
                        }
                    }
                } else {
                    items(state.records, key = PeriodRecord::id) { record ->
                        PeriodHistoryRow(record = record, onDelete = { HealthCycleStore.deletePeriod(record.id) })
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }

    if (showWearableDialog) {
        AlertDialog(
            onDismissRequest = { showWearableDialog = false },
            icon = { Icon(Icons.Outlined.Watch, null, tint = HealthAccentDark) },
            title = { Text("手环数据已预留") },
            text = {
                Text("下一阶段可以接入睡眠、心率、步数、血氧和活动数据。现在先把经期记录、预测和提醒做稳定。")
            },
            confirmButton = {
                TextButton(onClick = { showWearableDialog = false }) { Text("知道啦") }
            },
        )
    }
}

@Composable
private fun PeriodOverviewCard(state: HealthCycleState, prediction: PeriodPrediction?) {
    val today = LocalDate.now()
    val latest = state.records.maxByOrNull(PeriodRecord::startDate)
    val distance = prediction?.let { ChronoUnit.DAYS.between(today, it.startDate) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = HealthAccentDark,
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("CYCLE", color = Color.White.copy(alpha = 0.68f), fontSize = 9.sp, letterSpacing = 1.8.sp)
            Text(
                when {
                    prediction == null -> "从第一次记录开始"
                    distance == null -> "下一次预测"
                    distance > 0 -> "预计还有 $distance 天"
                    distance == 0L -> "预计今天开始"
                    else -> "比预测日期晚了 ${-distance} 天"
                },
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                prediction?.let {
                    "预计 ${it.startDate.monthValue}月${it.startDate.dayOfMonth}日—${it.endDate.monthValue}月${it.endDate.dayOfMonth}日"
                } ?: "在月历里点选本次经期的开始与结束日期。",
                color = Color.White.copy(alpha = 0.84f),
                fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OverviewTag("平均周期 ${prediction?.averageCycleDays ?: 28} 天")
                OverviewTag("平均经期 ${prediction?.averagePeriodDays ?: 5} 天")
            }
            latest?.let {
                Text(
                    "最近记录：${formatDateRange(it.startDate, it.endDate)}",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun OverviewTag(text: String) {
    Surface(color = Color.White.copy(alpha = 0.12f), shape = RoundedCornerShape(99.dp)) {
        Text(text, color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
    }
}

@Composable
private fun PeriodCalendarCard(
    month: YearMonth,
    records: List<PeriodRecord>,
    prediction: PeriodPrediction?,
    selectedStart: LocalDate?,
    selectedEnd: LocalDate?,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
    onDateClick: (LocalDate) -> Unit,
) {
    val first = month.atDay(1)
    val offset = first.dayOfWeek.value - 1
    val cells = buildList<LocalDate?> {
        repeat(offset) { add(null) }
        for (day in 1..month.lengthOfMonth()) add(month.atDay(day))
        while (size % 7 != 0) add(null)
    }
    val selectionFirst = selectedStart?.let { start -> selectedEnd?.let { minOf(start, it) } ?: start }
    val selectionLast = selectedStart?.let { start -> selectedEnd?.let { maxOf(start, it) } ?: start }

    HealthCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    month.format(DateTimeFormatter.ofPattern("yyyy年 M月", Locale.SIMPLIFIED_CHINESE)),
                    color = HealthInk,
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                )
                Text("点一次选开始，再点一次选结束", color = HealthMuted, fontSize = 11.sp)
            }
            TextButton(onClick = onToday) { Text("今天", color = HealthAccentDark) }
            IconButton(onClick = onPreviousMonth) { Icon(Icons.Outlined.ChevronLeft, "上个月") }
            IconButton(onClick = onNextMonth) { Icon(Icons.Outlined.ChevronRight, "下个月") }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { weekday ->
                Text(
                    weekday,
                    color = HealthMuted,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                )
            }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    if (date == null) {
                        Spacer(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val recorded = records.any { date in it.startDate..it.endDate }
                        val predicted = prediction?.let { date in it.startDate..it.endDate } == true
                        val selected = selectionFirst != null && selectionLast != null && date in selectionFirst..selectionLast
                        val today = date == LocalDate.now()
                        CalendarDay(
                            date = date,
                            recorded = recorded,
                            predicted = predicted,
                            selected = selected,
                            today = today,
                            modifier = Modifier.weight(1f),
                            onClick = { onDateClick(date) },
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CalendarLegend(HealthAccent, "已记录")
            CalendarLegend(HealthPrediction, "预测")
            CalendarLegend(HealthSoftStrong, "正在选择")
        }
    }
}

@Composable
private fun CalendarDay(
    date: LocalDate,
    recorded: Boolean,
    predicted: Boolean,
    selected: Boolean,
    today: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val fill = when {
        selected -> HealthSoftStrong
        recorded -> HealthAccent
        predicted -> HealthPrediction
        else -> Color.Transparent
    }
    val textColor = when {
        recorded && !selected -> Color.White
        else -> HealthInk
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(fill)
            .then(if (today) Modifier.background(Color.Transparent).clip(RoundedCornerShape(13.dp)) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (today) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Transparent,
                shape = RoundedCornerShape(13.dp),
                border = BorderStroke(1.5.dp, if (recorded) Color.White else HealthAccentDark),
            ) {}
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(date.dayOfMonth.toString(), color = textColor, fontSize = 13.sp, fontWeight = if (today) FontWeight.Bold else FontWeight.Normal)
            if (predicted && !recorded) {
                Spacer(Modifier.height(2.dp))
                Surface(Modifier.size(4.dp), CircleShape, HealthAccent) {}
            }
        }
    }
}

@Composable
private fun CalendarLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(8.dp), CircleShape, color) {}
        Spacer(Modifier.width(5.dp))
        Text(label, color = HealthMuted, fontSize = 10.sp)
    }
}

@Composable
private fun SelectedPeriodCard(
    start: LocalDate?,
    end: LocalDate?,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    HealthCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(15.dp), color = HealthSoftStrong) {
                Icon(Icons.Outlined.EditCalendar, null, tint = HealthAccentDark, modifier = Modifier.padding(10.dp).size(23.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("本次经期", color = HealthInk, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        start == null -> "还没有选择日期"
                        end == null -> "开始于 ${formatDate(start)} · 再点日期选择结束"
                        else -> formatDateRange(minOf(start, end), maxOf(start, end))
                    },
                    color = HealthMuted,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onClear,
                enabled = start != null,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, HealthLine),
            ) { Text("重新选择", color = HealthInk) }
            Button(
                onClick = onSave,
                enabled = start != null,
                modifier = Modifier.weight(1.35f),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = HealthAccentDark),
            ) { Text(if (end == null) "保存开始日" else "保存这次经期") }
        }
    }
}

@Composable
private fun PredictionAndReminderCard(
    state: HealthCycleState,
    prediction: PeriodPrediction?,
    reminderMenuOpen: Boolean,
    onReminderMenuChange: (Boolean) -> Unit,
    onReminderEnabledChange: (Boolean) -> Unit,
    onReminderDaysChange: (Int) -> Unit,
) {
    HealthCard {
        Text("预测与提醒", color = HealthInk, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            prediction?.let {
                "按现有记录，下一次预计从 ${formatDate(it.startDate)} 开始，持续约 ${it.averagePeriodDays} 天。"
            } ?: "保存第一条经期记录后，这里会显示下一次预测。",
            color = HealthMuted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        HorizontalDivider(color = HealthLine)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("经期提醒", color = HealthInk, fontWeight = FontWeight.SemiBold)
                Text(
                    if (state.reminderEnabled) "预计开始前 ${state.reminderDaysBefore} 天提醒" else "提醒已关闭",
                    color = HealthMuted,
                    fontSize = 11.sp,
                )
            }
            Switch(
                checked = state.reminderEnabled,
                onCheckedChange = onReminderEnabledChange,
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = HealthAccent),
            )
        }
        if (state.reminderEnabled) {
            Box {
                OutlinedButton(
                    onClick = { onReminderMenuChange(true) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, HealthLine),
                ) {
                    Icon(Icons.Outlined.NotificationsNone, null, tint = HealthAccentDark, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("提前 ${state.reminderDaysBefore} 天提醒", color = HealthInk)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Outlined.ExpandMore, null, tint = HealthMuted)
                }
                DropdownMenu(
                    expanded = reminderMenuOpen,
                    onDismissRequest = { onReminderMenuChange(false) },
                ) {
                    listOf(0, 1, 2, 3, 5, 7).forEach { days ->
                        DropdownMenuItem(
                            text = { Text(if (days == 0) "预计当天提醒" else "提前 $days 天") },
                            onClick = {
                                onReminderDaysChange(days)
                                onReminderMenuChange(false)
                            },
                            leadingIcon = {
                                if (days == state.reminderDaysBefore) Icon(Icons.Outlined.Check, null, tint = HealthAccentDark)
                            },
                        )
                    }
                }
            }
        }
        Text(
            "预测会随着每次记录自动更新；周期波动很常见，页面不会把预测当成确定日期。",
            color = HealthMuted,
            fontSize = 10.sp,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun PeriodHistoryRow(record: PeriodRecord, onDelete: () -> Unit) {
    HealthCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = HealthSoft) {
                Column(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(record.startDate.monthValue.toString().padStart(2, '0'), color = HealthAccentDark, fontSize = 10.sp)
                    Text(record.startDate.dayOfMonth.toString(), color = HealthInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(formatDateRange(record.startDate, record.endDate), color = HealthInk, fontWeight = FontWeight.SemiBold)
                val length = ChronoUnit.DAYS.between(record.startDate, record.endDate) + 1
                Text("共 $length 天", color = HealthMuted, fontSize = 11.sp)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteOutline, "删除记录", tint = HealthMuted)
            }
        }
    }
}

@Composable
private fun HealthCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(23.dp),
        color = HealthCard,
        border = BorderStroke(1.dp, HealthLine),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
            content = content,
        )
    }
}

private fun formatDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("M月d日", Locale.SIMPLIFIED_CHINESE))

private fun formatDateRange(start: LocalDate, end: LocalDate): String =
    if (start == end) formatDate(start)
    else "${formatDate(start)}—${formatDate(end)}"

internal object HealthPeriodReminderScheduler {
    private const val ACTION = "app.lulu.health.PERIOD_REMINDER"
    private const val REQUEST_CODE = 0x4845

    fun reschedule(context: Context, state: HealthCycleState) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        val operation = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            Intent(appContext, HealthPeriodReminderReceiver::class.java).apply { action = ACTION },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(operation)
        if (!state.reminderEnabled) return
        val prediction = HealthCycleStore.prediction(state) ?: return
        val reminderDate = prediction.startDate.minusDays(state.reminderDaysBefore.toLong())
        val triggerTime = reminderDate.atTime(LocalTime.of(9, 0)).atZone(ZoneId.systemDefault()).toInstant()
        if (!triggerTime.isAfter(Instant.now())) return
        runCatching {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime.toEpochMilli(), operation)
        }.recoverCatching {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime.toEpochMilli(), operation)
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
                NotificationChannel(
                    CHANNEL_ID,
                    "经期提醒",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "根据你手动记录的经期日期发送预测提醒"
                },
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
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "按你在健康 App 中保存的记录，下一次经期大约从 ${formatDate(prediction.startDate)} 开始。预测可能有波动，记得按实际情况更新。",
                ),
            )
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
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
