package com.jiacimu.lulu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The "自序" cover has the large date artwork removed from 所行.
 * Everything is vector-drawn and offline; the clock uses local device time
 * and refreshes while this screen is on display.
 */
@Composable
internal fun SelfPrefaceHomeHero(modifier: Modifier = Modifier) {
    var clock by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = LocalDateTime.now()
            delay(60_000L)
        }
    }
    val dark = Color(0xFF314451)
    val muted = Color(0xFF746B89)
    Box(modifier.fillMaxWidth().height(184.dp)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(27.dp),
            shadowElevation = 3.dp,
            color = Color(0xFFF2E9F4),
        ) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        listOf(Color(0xFFEAE7FA), Color(0xFFF8EBED), Color(0xFFF0F6F1)),
                    )
                )
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val plum = Color(0xFF9F8CB6)
                    val rose = Color(0xFFD49D9D)
                    // A broad orbital line and restrained stars, not a downloaded banner.
                    val orbit = Path().apply {
                        moveTo(w * .65f, h * 1.04f)
                        cubicTo(w * .77f, h * .68f, w * .74f, h * .20f, w * 1.01f, h * .08f)
                    }
                    drawPath(orbit, plum.copy(alpha = .44f),
                        style = Stroke(2.1.dp.toPx()))
                    drawCircle(rose.copy(alpha = .13f), w * .24f,
                        Offset(w * .99f, h * .88f))
                    drawCircle(plum.copy(alpha = .11f), w * .16f,
                        Offset(w * .81f, h * .10f))
                    listOf(
                        Offset(w * .69f, h * .28f),
                        Offset(w * .86f, h * .47f),
                        Offset(w * .76f, h * .78f),
                        Offset(w * .95f, h * .24f),
                    ).forEachIndexed { index, o ->
                        val d = (if (index % 2 == 0) 6 else 4).dp.toPx()
                        drawLine(if (index % 2 == 0) plum else rose,
                            Offset(o.x - d, o.y), Offset(o.x + d, o.y), 1.5.dp.toPx())
                        drawLine(if (index % 2 == 0) plum else rose,
                            Offset(o.x, o.y - d), Offset(o.x, o.y + d), 1.5.dp.toPx())
                    }
                }
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 21.dp, vertical = 19.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("A PAGE OF MY LIFE", color = muted,
                            fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.4.sp)
                        Spacer(Modifier.weight(1f))
                        Text(clock.format(DateTimeFormatter.ofPattern("HH:mm")),
                            color = dark, fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(clock.format(DateTimeFormatter.ofPattern("yyyy.MM")),
                            color = muted, fontSize = 12.sp, letterSpacing = 1.sp)
                        Text(
                            clock.dayOfMonth.toString() + "  /  " +
                                clock.format(DateTimeFormatter.ofPattern("EEEE", Locale.SIMPLIFIED_CHINESE)),
                            color = dark, fontWeight = FontWeight.Bold, fontSize = 29.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("人生由我落笔，故事尚在途中。",
                            color = dark.copy(alpha = .78f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
