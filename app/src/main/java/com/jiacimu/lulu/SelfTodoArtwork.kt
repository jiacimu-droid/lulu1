package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** App-scoped palette. No changes to the desktop or character screen themes. */
internal object TodoInk {
    val background = SelfPrefacePalette.background
    val paper = SelfPrefacePalette.paperBright
    val title = SelfPrefacePalette.ink
    val secondary = SelfPrefacePalette.muted
    val line = SelfPrefacePalette.outline
    // Legacy names remain internal API aliases for the existing UI components.
    val violet = SelfPrefacePalette.seaGlass
    val violetLight = SelfPrefacePalette.sageLight
    val lavender = SelfPrefacePalette.outline
    val sage = SelfPrefacePalette.sage
    val sageLight = SelfPrefacePalette.sageMist
    val coral = SelfPrefacePalette.honey
    val coralLight = SelfPrefacePalette.creamLight
    val overdue = SelfPrefacePalette.overdue
}


internal val todoVisualDate = DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE)

@Composable
internal fun TodoBotanical(modifier: Modifier, ink: Color = TodoInk.violet.copy(alpha = .36f)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val main = Path().apply {
            moveTo(w * .35f, h * .96f)
            cubicTo(w * .28f, h * .65f, w * .68f, h * .40f, w * .60f, h * .06f)
        }
        drawPath(main, ink, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        listOf(
            Triple(.36f, .80f, -.19f), Triple(.43f, .66f, .20f),
            Triple(.52f, .53f, -.18f), Triple(.60f, .40f, .15f),
            Triple(.62f, .23f, -.13f),
        ).forEach { (x, y, side) ->
            val leaf = Path().apply {
                moveTo(w * x, h * y)
                cubicTo(w * (x + side * .25f), h * (y - .14f),
                    w * (x + side * .50f), h * (y - .11f),
                    w * (x + side * .43f), h * (y - .20f))
                cubicTo(w * (x + side * .09f), h * (y - .15f),
                    w * (x - side * .01f), h * (y - .04f), w * x, h * y)
            }
            drawPath(leaf, ink, style = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round))
        }
        listOf(Offset(w * .80f, h * .11f), Offset(w * .88f, h * .36f),
            Offset(w * .15f, h * .43f)).forEach { center ->
            val d = 5.dp.toPx()
            drawLine(ink, Offset(center.x - d, center.y),
                Offset(center.x + d, center.y), 1.3.dp.toPx())
            drawLine(ink, Offset(center.x, center.y - d),
                Offset(center.x, center.y + d), 1.3.dp.toPx())
        }
    }
}

@Composable
internal fun TodoHero(date: LocalDate, count: Int, completedCount: Int, onHistory: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(193.dp)
            .shadow(5.dp, RoundedCornerShape(26.dp))
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(
                colors = listOf(SelfPrefacePalette.creamLight, SelfPrefacePalette.sageLight, SelfPrefacePalette.paper),
                start = Offset.Zero, end = Offset(1200f, 900f),
            )),
    ) {
        TodoBotanical(Modifier.align(Alignment.CenterEnd).size(166.dp, 190.dp),
            SelfPrefacePalette.sage.copy(alpha = .34f))
        Column(
            Modifier.fillMaxSize().padding(start = 23.dp, top = 22.dp, bottom = 19.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("A LITTLE PLAN FOR TODAY", fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 1.35.sp,
                color = TodoInk.violet)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(date.format(DateTimeFormatter.ofPattern("yyyy.MM", Locale.SIMPLIFIED_CHINESE)),
                    fontSize = 12.sp, color = TodoInk.violet, letterSpacing = 1.sp)
                Text(date.dayOfMonth.toString().padStart(2, '0') + "  /  " +
                    date.format(DateTimeFormatter.ofPattern("EEEE", Locale.SIMPLIFIED_CHINESE)),
                    fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TodoInk.title)
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.size(7.dp).background(TodoInk.coral, CircleShape))
                Text(if (count == 0) "今天也有好好前进" else "还有 $count 件事，慢慢完成",
                    fontSize = 12.sp, color = TodoInk.title, fontWeight = FontWeight.Medium)
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 15.dp, end = 15.dp),
            shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = .76f),
            onClick = onHistory,
        ) {
            Row(Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.History, null, Modifier.size(17.dp), tint = TodoInk.violet)
                Text("$completedCount", fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, color = TodoInk.violet)
            }
        }
    }
}

@Composable
internal fun TodoListHeading(title: String, caption: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(3.dp).height(24.dp)
            .background(TodoInk.coral, RoundedCornerShape(4.dp)))
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold,
            color = TodoInk.title, modifier = Modifier.weight(1f))
        Text(caption, fontSize = 12.sp, color = TodoInk.secondary)
    }
}

/**
 * Dense task row: one compact title line + contextual metadata and optional short note.
 * The full title, long note and recurrence editor are still available on tap.
 */
@Composable
internal fun TodoTaskCard(
    occurrence: TodoOccurrence, today: LocalDate, checked: Boolean,
    onCheck: () -> Unit, onEdit: () -> Unit,
) {
    val task = occurrence.task
    val overdue = occurrence.date.isBefore(today)
    val tint = when {
        overdue -> TodoInk.overdue
        occurrence.date == today.plusDays(1) -> TodoInk.sage
        else -> TodoInk.violet
    }
    val dayLabel = when {
        overdue -> "逾期 · " + occurrence.date.format(todoVisualDate)
        occurrence.date == today -> "今天"
        occurrence.date == today.plusDays(1) -> "明天"
        else -> occurrence.date.format(todoVisualDate)
    }
    val timeText = task.minutes?.let {
        String.format(Locale.getDefault(), "%02d:%02d", it / 60, it % 60)
    }
    val recurrence = when (task.repeat) {
        TodoRepeat.DAILY -> "每天"
        TodoRepeat.WEEKLY -> "每周"
        TodoRepeat.ONCE -> ""
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = TodoInk.paper,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, TodoInk.line),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp,
                top = 13.dp, bottom = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 36dp mark inside a comfortable 44dp hit target.
            Box(Modifier.size(44.dp).clickable(onClick = onCheck),
                contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.size(28.dp),
                    color = if (checked) TodoInk.sage else Color.White,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.5.dp,
                        if (checked) TodoInk.sage else tint.copy(alpha = .6f)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (checked) Icon(Icons.Outlined.Check, "完成",
                            modifier = Modifier.size(20.dp), tint = Color.White)
                    }
                }
            }
            Spacer(Modifier.width(9.dp))
            Column(
                Modifier.weight(1f).clickable(onClick = onEdit).padding(vertical = 1.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    task.title, color = TodoInk.title, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2,
                    lineHeight = 19.sp, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (checked) TextDecoration.LineThrough else null,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(dayLabel, fontSize = 10.sp, color = tint,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (timeText != null) Text(timeText, fontSize = 10.sp,
                        color = TodoInk.secondary)
                    if (recurrence.isNotEmpty()) Text("↻ $recurrence",
                        fontSize = 10.sp, color = TodoInk.secondary)
                }
                if (task.note.isNotBlank()) Text(
                    task.note, fontSize = 11.sp, color = TodoInk.secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(onClick = onEdit, modifier = Modifier.size(40.dp),
                shape = CircleShape, color = Color.Transparent) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.ArrowForward, "查看待办",
                        modifier = Modifier.size(16.dp), tint = TodoInk.secondary)
                }
            }
        }
    }
}

@Composable
internal fun TodoHistoryCard(task: TodoTask, completion: TodoCompletion, onRestore: () -> Unit) {
    val whenDone = Instant.ofEpochMilli(completion.completedAt)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE))
    Surface(Modifier.fillMaxWidth(), color = TodoInk.paper,
        border = BorderStroke(1.dp, TodoInk.line), shape = RoundedCornerShape(19.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 17.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Surface(onClick = onRestore, modifier = Modifier.size(44.dp),
                color = TodoInk.sageLight, shape = RoundedCornerShape(13.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Check, "撤销完成", Modifier.size(23.dp),
                        tint = TodoInk.sage)
                }
            }
            Column(Modifier.weight(1f).padding(start = 13.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(task.title, color = TodoInk.secondary, fontSize = 15.sp,
                    fontWeight = FontWeight.Medium, textDecoration = TextDecoration.LineThrough,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("完成于 $whenDone", color = TodoInk.secondary, fontSize = 11.sp)
            }
            TextButton(onClick = onRestore, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text("恢复", color = TodoInk.violet, fontSize = 12.sp)
            }
        }
    }
}

@Composable
internal fun TodoEmptyState(history: Boolean, onAdd: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(144.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.size(132.dp), color = TodoInk.violetLight,
                shape = RoundedCornerShape(40.dp)) {}
            TodoBotanical(Modifier.size(104.dp), ink = TodoInk.violet.copy(alpha = .58f))
            Icon(if (history) Icons.Outlined.History else Icons.Outlined.CheckCircleOutline,
                null, tint = TodoInk.violet,
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 10.dp).size(33.dp))
        }
        Spacer(Modifier.height(19.dp))
        Text(if (history) "还没有完成记录" else "清单空空，心情轻轻",
            fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TodoInk.title)
        Spacer(Modifier.height(8.dp))
        Text(if (history) "每一件做成的小事，都会留在这里"
            else "所有未完成的事，会安静排在这里",
            fontSize = 12.sp, color = TodoInk.secondary)
        if (!history) {
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = onAdd,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TodoInk.violet),
                border = BorderStroke(1.dp, TodoInk.lavender),
                shape = RoundedCornerShape(15.dp)) {
                Icon(Icons.Outlined.Add, null, Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("记下一件事")
            }
        }
    }
}

@Composable
internal fun TodoAddButton(onClick: () -> Unit) {
    Button(onClick = onClick, shape = RoundedCornerShape(19.dp),
        colors = ButtonDefaults.buttonColors(containerColor = TodoInk.title),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 15.dp),
        modifier = Modifier.heightIn(min = 56.dp)) {
        Icon(Icons.Outlined.Add, "添加待办", Modifier.size(23.dp))
        Spacer(Modifier.width(8.dp))
        Text("写一件事", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}
