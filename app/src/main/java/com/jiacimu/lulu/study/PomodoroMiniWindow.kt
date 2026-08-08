package com.jiacimu.lulu.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * App-level draggable timer window. It is deliberately Compose-owned instead of a system overlay:
 * no overlay permission is required, and it remains visible while navigating around Lulu. Returning
 * from this window reopens the same active timer rather than starting another Pomodoro session.
 */
@Composable
internal fun PomodoroMiniWindow(
    studyState: StudyState,
    companion: PomodoroCompanionPreferences,
    onOpen: () -> Unit,
    onPauseResume: () -> Unit,
    onEnd: () -> Unit,
) {
    val density = LocalDensity.current
    var offsetX by rememberSaveable { mutableStateOf(0f) }
    var offsetY by rememberSaveable { mutableStateOf(0f) }
    val palette = remember(companion.skin) { miniPalette(companion.skin) }
    val running = if (companion.timerMode == PomodoroTimerMode.CountUp) {
        companion.countUpRunning
    } else {
        studyState.pomodoro.running
    }
    val displaySeconds = if (companion.timerMode == PomodoroTimerMode.CountUp) {
        companion.countUpElapsedSeconds.coerceAtLeast(0)
    } else {
        studyState.pomodoro.remainingSeconds.coerceAtLeast(0)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val horizontalLimit = with(density) { ((maxWidth - 80.dp) / 2).toPx().coerceAtLeast(0f) }
        val verticalLimit = with(density) { (maxHeight - 120.dp).toPx().coerceAtLeast(0f) }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 14.dp, vertical = 24.dp)
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .shadow(12.dp, RoundedCornerShape(22.dp))
                .pointerInput(horizontalLimit, verticalLimit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount.x).coerceIn(-horizontalLimit, horizontalLimit)
                        offsetY = (offsetY + dragAmount.y).coerceIn(-verticalLimit, 0f)
                    }
                },
            shape = RoundedCornerShape(22.dp),
            color = palette.surface,
            contentColor = palette.text,
            border = BorderStroke(1.dp, palette.border),
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Outlined.DragIndicator, "拖动", tint = palette.muted, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        companion.activeTask.ifBlank { companion.task }.ifBlank { "番茄钟专注中" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (companion.timerMode == PomodoroTimerMode.CountUp) "正计时" else "倒计时",
                        color = palette.muted,
                        fontSize = 10.sp,
                    )
                }
                Text(
                    formatPomodoroClock(displaySeconds),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = palette.accent,
                )
                IconButton(onClick = onPauseResume, modifier = Modifier.size(36.dp)) {
                    Icon(if (running) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (running) "暂停" else "继续", tint = palette.text)
                }
                IconButton(onClick = onEnd, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.StopCircle, "结束", tint = palette.stop)
                }
                TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("返回", color = palette.accent, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
            }
        }
    }
}

private data class MiniPalette(
    val surface: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val border: Color,
    val stop: Color,
)

private fun miniPalette(skin: PomodoroSkin): MiniPalette = when (skin) {
    PomodoroSkin.Light -> MiniPalette(
        surface = Color(0xFFF7F8FA).copy(alpha = 0.97f),
        text = Color(0xFF22252B),
        muted = Color(0xFF7A8089),
        accent = Color(0xFF667D92),
        border = Color(0xFFE1E4E8),
        stop = Color(0xFFA25E65),
    )
    PomodoroSkin.Dark -> MiniPalette(
        surface = Color(0xFF171B22).copy(alpha = 0.98f),
        text = Color(0xFFF2F4F7),
        muted = Color(0xFF9BA5B1),
        accent = Color(0xFF9DB6CB),
        border = Color(0xFF303844),
        stop = Color(0xFFD88B91),
    )
}

internal fun formatPomodoroClock(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val rest = safe % 60
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, rest) else "%02d:%02d".format(minutes, rest)
}

internal fun formatPomodoroElapsed(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val rest = safe % 60
    return when {
        hours > 0 -> "${hours}小时${minutes}分${rest}秒"
        minutes > 0 -> "${minutes}分${rest}秒"
        else -> "${rest}秒"
    }
}
