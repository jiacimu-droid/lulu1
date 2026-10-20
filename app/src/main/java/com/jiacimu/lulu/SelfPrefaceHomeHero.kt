package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
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
 * Original on-device ink illustration for 自序's cover.
 * Its primary color is the same almost-white warm paper as the 所思 notebook;
 * green is for tiny line details, NOT a saturated green/yellow gradient.
 *
 * This header reads only the device clock. No notes, wishes, todo lists or
 * character records are written or migrated during a theme change.
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

    val ink = SelfPrefacePalette.ink
    val muted = SelfPrefacePalette.muted
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(min = 196.dp),
        shape = RoundedCornerShape(25.dp),
        color = SelfPrefacePalette.paperBright,
        border = BorderStroke(1.dp, SelfPrefacePalette.outline.copy(alpha = .78f)),
        shadowElevation = 3.dp,
    ) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 196.dp)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFFFFDF8), // notebook paper: warm white, barely yellow
                            Color(0xFFFFFCF6),
                            Color(0xFFF1F7F3), // subtle pale green only at the far edge
                        ),
                        start = Offset.Zero,
                        end = Offset(1100f, 650f),
                    )
                )
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val sage = Color(0xFF82A7A0)
                val honey = Color(0xFFC9AC7A)
                val stroke = 1.35.dp.toPx()

                // Quiet paper seam and lightly sketched inset margin.
                drawLine(sage.copy(alpha = .20f),
                    Offset(w * .044f, h * .13f),
                    Offset(w * .044f, h * .87f), stroke)
                drawLine(sage.copy(alpha = .17f),
                    Offset(w * .065f, h * .89f),
                    Offset(w * .91f, h * .89f), stroke)

                // Same hand-drawn geometry as the notebook: orbit + small sun
                // rather than a large colored plant or gradient illustration.
                val orbit = Path().apply {
                    moveTo(w * .66f, h * .72f)
                    cubicTo(w * .79f, h * .12f, w * .92f, h * .19f, w * .985f, h * .35f)
                }
                drawPath(orbit, sage.copy(alpha = .55f),
                    style = Stroke(width = 1.65.dp.toPx()))
                drawCircle(
                    Color(0xFFFFF8E9),
                    radius = w * .065f,
                    center = Offset(w * .825f, h * .41f),
                )
                drawCircle(
                    honey.copy(alpha = .47f),
                    radius = w * .065f,
                    center = Offset(w * .825f, h * .41f),
                    style = Stroke(width = stroke),
                )
                // A few points of light, with restrained gold instead of yellow fill.
                listOf(
                    Offset(w * .70f, h * .32f),
                    Offset(w * .955f, h * .65f),
                    Offset(w * .76f, h * .78f),
                ).forEachIndexed { i, p ->
                    val d = (if (i == 1) 6 else 4).dp.toPx()
                    val color = if (i == 1) honey else sage
                    drawLine(color.copy(alpha = .7f),
                        Offset(p.x - d, p.y), Offset(p.x + d, p.y), stroke)
                    drawLine(color.copy(alpha = .7f),
                        Offset(p.x, p.y - d), Offset(p.x, p.y + d), stroke)
                }
                drawCircle(sage.copy(alpha = .35f), radius = 2.dp.toPx(),
                    center = Offset(w * .97f, h * .19f))
            }

            Column(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = 196.dp)
                    .padding(start = 25.dp, end = 23.dp, top = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "MY LIFE JOURNAL",
                        color = muted,
                        fontSize = 10.sp,
                        letterSpacing = 1.8.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        clock.format(DateTimeFormatter.ofPattern("HH:mm")),
                        color = ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(.75f),
                ) {
                    Text(
                        clock.format(DateTimeFormatter.ofPattern("yyyy  /  MM")),
                        color = muted,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            clock.dayOfMonth.toString().padStart(2, '0'),
                            color = ink,
                            fontSize = 38.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            clock.format(DateTimeFormatter.ofPattern("EEEE", Locale.SIMPLIFIED_CHINESE)),
                            color = ink,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(bottom = 7.dp),
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "人生由我落笔，故事尚在途中。",
                        color = muted,
                        fontSize = 12.sp,
                        maxLines = 2,
                        lineHeight = 19.sp,
                    )
                }
            }
        }
    }
}
