package com.jiacimu.lulu.health

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val HealthPaper = Color(0xFFFFFAFB)
private val HealthCard = Color.White
private val HealthSoft = Color(0xFFFFF0F3)
private val HealthSelected = Color(0xFFFFDDE6)
private val HealthPredicted = Color(0xFFF4E5EB)
private val HealthAccent = Color(0xFFBF4D6D)
private val HealthAccentDark = Color(0xFF91344F)
private val HealthAccentDeep = Color(0xFF74253F)
private val HealthInk = Color(0xFF292225)
private val HealthMuted = Color(0xFF81767A)
private val HealthLine = Color(0xFFECE0E4)

private enum class HealthSection(val label: String) {
    Cycle("经期"),
    Wearable("手环数据"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthFeatureScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    remember(context) {
        HealthCycleStore.initialize(context.applicationContext)
        GadgetbridgeHealthStore.initialize(context.applicationContext)
        Unit
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var section by rememberSaveable { mutableStateOf(HealthSection.Cycle) }

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
                    Text("健康", color = HealthInk, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                }
                HorizontalDivider(color = HealthLine)
                NavigationDrawerItem(
                    label = { Text("经期", fontWeight = FontWeight.SemiBold) },
                    selected = section == HealthSection.Cycle,
                    icon = { Icon(Icons.Outlined.CalendarMonth, null) },
                    onClick = {
                        section = HealthSection.Cycle
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    colors = drawerItemColors(),
                )
                NavigationDrawerItem(
                    label = { Text("手环数据", fontWeight = FontWeight.SemiBold) },
                    selected = section == HealthSection.Wearable,
                    icon = { Icon(Icons.Outlined.Watch, null) },
                    onClick = {
                        section = HealthSection.Wearable
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = drawerItemColors(),
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
                            Text(section.label, color = HealthMuted, fontSize = 10.sp, letterSpacing = 1.sp)
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
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (section) {
                    HealthSection.Cycle -> CycleScreen()
                    HealthSection.Wearable -> GadgetbridgeHealthScreen()
                }
            }
        }
    }
}

@Composable
private fun drawerItemColors() = NavigationDrawerItemDefaults.colors(
    selectedContainerColor = HealthSelected,
    selectedTextColor = HealthInk,
    selectedIconColor = HealthAccentDark,
)

@Composable
private fun CycleScreen() {
    val context = LocalContext.current
    val state by HealthCycleStore.state.collectAsState()
    val prediction = remember(state) { HealthCycleStore.prediction(state) }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val month = remember(monthText) { YearMonth.parse(monthText) }
    var startText by rememberSaveable { mutableStateOf<String?>(null) }
    var endText by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedStart = startText?.let(LocalDate::parse)
    val selectedEnd = endText?.let(LocalDate::parse)

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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
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
        if (selectedStart != null) {
            item {
                SelectionCard(
                    start = selectedStart,
                    end = selectedEnd,
                    onClear = {
                        startText = null
                        endText = null
                    },
                    onSave = {
                        HealthCycleStore.savePeriod(selectedStart, selectedEnd ?: selectedStart)
                        startText = null
                        endText = null
                    },
                )
            }
        }
        item {
            PredictionCard(
                state = state,
                prediction = prediction,
                onEnabled = ::changeReminder,
                onDays = HealthCycleStore::setReminderDaysBefore,
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("历史记录", color = HealthInk, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("${state.records.size}", color = HealthMuted, fontSize = 12.sp)
            }
        }
        if (state.records.isEmpty()) {
            item {
                HealthPanel {
                    Text(
                        "暂无记录，点击日历选择开始与结束日期。",
                        color = HealthMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 18.dp),
                    )
                }
            }
        } else {
            items(state.records, key = { it.id }) { record ->
                HistoryRow(record) { HealthCycleStore.deletePeriod(record.id) }
            }
        }
        item { Spacer(Modifier.height(18.dp)) }
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

    HealthPanel(shape = 30) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(month.year.toString(), color = HealthMuted, fontSize = 10.sp)
                Text("${month.monthValue}月", color = HealthInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onToday) { Text("今天", color = HealthAccentDark) }
            IconButton(onClick = onPrevious) { Icon(Icons.Outlined.ChevronLeft, "上个月") }
            IconButton(onClick = onNext) { Icon(Icons.Outlined.ChevronRight, "下个月") }
        }
        HorizontalDivider(color = HealthLine)
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
                            modifier = Modifier.weight(1f),
                            onClick = { onDate(date) },
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Legend(HealthAccent, "已记录")
            Spacer(Modifier.width(18.dp))
            Legend(HealthPredicted, "预测")
            Spacer(Modifier.width(18.dp))
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
        shape = RoundedCornerShape(14.dp),
        border = if (date == LocalDate.now()) BorderStroke(1.5.dp, if (recorded) Color.White else HealthAccentDark) else null,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                date.dayOfMonth.toString(),
                color = if (recorded && !selected) Color.White else HealthInk,
                fontSize = 13.sp,
                fontWeight = if (date == LocalDate.now() || recorded) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = color) {}
        Spacer(Modifier.width(5.dp))
        Text(text, color = HealthMuted, fontSize = 10.sp)
    }
}

@Composable
private fun SelectionCard(start: LocalDate, end: LocalDate?, onClear: () -> Unit, onSave: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = HealthSoft,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, HealthSelected),
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text("本次记录", color = HealthInk, fontWeight = FontWeight.Bold)
            Text(
                end?.let { formatRange(minOf(start, it), maxOf(start, it)) } ?: formatHealthDate(start),
                color = HealthAccentDark,
                fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1.35f),
                    colors = ButtonDefaults.buttonColors(containerColor = HealthAccentDark),
                ) { Text("保存") }
            }
        }
    }
}

@Composable
private fun PredictionCard(
    state: HealthCycleState,
    prediction: PeriodPrediction?,
    onEnabled: (Boolean) -> Unit,
    onDays: (Int) -> Unit,
) {
    val distance = prediction?.let { ChronoUnit.DAYS.between(LocalDate.now(), it.startDate) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = HealthAccentDark,
        shape = RoundedCornerShape(30.dp),
        shadowElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .background(Brush.linearGradient(listOf(HealthAccentDark, HealthAccentDeep)))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("预测与提醒", color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp, letterSpacing = 1.5.sp)
            Text(
                when {
                    prediction == null -> "等待首次记录"
                    distance == null -> "下一次预测"
                    distance > 0 -> "预计还有 $distance 天"
                    distance == 0L -> "预计今天开始"
                    else -> "比预测晚了 ${-distance} 天"
                },
                color = Color.White,
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold,
            )
            prediction?.let {
                Text(formatRange(it.startDate, it.endDate), color = Color.White.copy(alpha = 0.86f), fontSize = 14.sp)
                Text("平均周期 ${it.cycleDays} 天 · 经期 ${it.periodDays} 天", color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.16f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("经期提醒", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (!state.reminderEnabled) "已关闭" else if (state.reminderDaysBefore == 0) "预计当天" else "提前 ${state.reminderDaysBefore} 天",
                        color = Color.White.copy(alpha = 0.68f),
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = state.reminderEnabled,
                    onCheckedChange = onEnabled,
                    colors = SwitchDefaults.colors(checkedThumbColor = HealthAccentDark, checkedTrackColor = Color.White),
                )
            }
            if (state.reminderEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 1, 3, 5, 7).forEach { days ->
                        FilterChip(
                            selected = state.reminderDaysBefore == days,
                            onClick = { onDays(days) },
                            label = { Text(if (days == 0) "当天" else "$days 天") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color.White,
                                selectedLabelColor = HealthAccentDark,
                                containerColor = Color.White.copy(alpha = 0.10f),
                                labelColor = Color.White,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = state.reminderDaysBefore == days,
                                borderColor = Color.White.copy(alpha = 0.24f),
                                selectedBorderColor = Color.Transparent,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(record: PeriodRecord, onDelete: () -> Unit) {
    HealthPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(16.dp), color = HealthSoft) {
                Column(
                    Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(record.startDate.monthValue.toString().padStart(2, '0'), color = HealthAccentDark, fontSize = 10.sp)
                    Text(record.startDate.dayOfMonth.toString(), color = HealthInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(formatRange(record.startDate, record.endDate), color = HealthInk, fontWeight = FontWeight.SemiBold)
                Text("${ChronoUnit.DAYS.between(record.startDate, record.endDate) + 1} 天", color = HealthMuted, fontSize = 11.sp)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteOutline, "删除记录", tint = HealthMuted)
            }
        }
    }
}

@Composable
private fun HealthPanel(shape: Int = 23, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = HealthCard,
        shape = RoundedCornerShape(shape.dp),
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

private fun formatRange(start: LocalDate, end: LocalDate): String =
    if (start == end) formatHealthDate(start) else "${formatHealthDate(start)}—${formatHealthDate(end)}"
