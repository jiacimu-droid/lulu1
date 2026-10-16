package com.jiacimu.lulu.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.LuluAppearance
import com.jiacimu.lulu.data.LuluAppearanceMotif
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Original 2D fabric + paper artwork for the journal theme. Intentionally drawable,
 * density-independent and offline: no remote asset, oversized wallpaper or runtime decoding.
 * Other appearance recipes remain completely unaffected.
 */
fun LuluAppearance.isJournal(): Boolean = motif == LuluAppearanceMotif.STITCH

private val LinenRose = Color(0xFFBE837B)
private val LinenDarker = Color(0xFF9E6964)
private val LinenLeaf = Color(0xFF779080)

/** Fabric texture is deterministic and cached until the viewport or palette changes. */
fun Modifier.journalLinen(appearance: LuluAppearance): Modifier = this.drawWithCache {
    val base = Color(appearance.palette.paper.toInt())
    val thread = Color(appearance.palette.ink.toInt())
    val yarn = Color(appearance.palette.accent.toInt())
    val gap = 8.dp.toPx()
    val pale = Brush.verticalGradient(
        colors = listOf(base.copy(alpha = 0.98f), base, Color(0xFFE8D5C4).copy(alpha = .9f)),
        startY = 0f, endY = size.height,
    )
    onDrawBehind {
        drawRect(pale)
        var x = 0f
        var column = 0
        while (x < size.width) {
            drawLine(thread.copy(alpha = .027f), Offset(x, 0f), Offset(x, size.height), .7.dp.toPx())
            if (column % 3 == 0)
                drawLine(yarn.copy(alpha = .023f), Offset(x + 2f, 0f), Offset(x + 2f, size.height), .45.dp.toPx())
            column++; x += gap
        }
        var y = 0f
        var row = 0
        while (y < size.height) {
            drawLine(thread.copy(alpha = .034f), Offset(0f, y), Offset(size.width, y), .65.dp.toPx())
            if (row % 3 == 0)
                drawLine(Color.White.copy(alpha = .25f), Offset(0f, y + 2f), Offset(size.width, y + 2f), .6.dp.toPx())
            row++; y += gap
        }
        // Sparse fibres, not random noise: stable across recompositions/scrolling.
        for (index in 0 until 60) {
            val px = ((index * 89 + 23) % 509) / 509f * size.width
            val py = ((index * 157 + 31) % 613) / 613f * size.height
            drawLine(thread.copy(alpha = .07f), Offset(px, py), Offset(px + 1.8.dp.toPx(), py - .5.dp.toPx()),
                .5.dp.toPx())
        }
    }
}

/** Paper has warm edge wash, faint laid-paper fibres and inset sewing-thread details. */
fun Modifier.journalPaper(appearance: LuluAppearance, stitches: Boolean = true): Modifier = this.drawWithCache {
    val paper = Color(appearance.palette.card.toInt())
    val ink = Color(appearance.palette.ink.toInt())
    val accent = Color(appearance.palette.accent.toInt())
    val gradient = Brush.linearGradient(
        listOf(Color.White.copy(alpha = .48f), paper.copy(alpha = .02f), accent.copy(alpha = .045f))
    )
    val w = size.width
    val h = size.height
    val margin = 6.dp.toPx()
    onDrawBehind {
        drawRect(gradient)
        for (i in 0 until 16) {
            val y = ((i * 37 + 11) % 97) / 97f * h
            val x = ((i * 71 + 3) % 101) / 101f * w
            drawLine(ink.copy(alpha = .035f), Offset(x, y), Offset((x + 25.dp.toPx()).coerceAtMost(w), y),
                .5.dp.toPx())
        }
        if (stitches && w > margin * 2 && h > margin * 2) {
            val dash = 7.dp.toPx()
            var pos = margin
            while (pos < w - margin) {
                drawLine(accent.copy(alpha = .34f), Offset(pos, margin), Offset((pos + 3.dp.toPx()).coerceAtMost(w-margin), margin),
                    .85.dp.toPx())
                drawLine(accent.copy(alpha = .34f), Offset(pos, h-margin), Offset((pos + 3.dp.toPx()).coerceAtMost(w-margin), h-margin),
                    .85.dp.toPx())
                pos += dash
            }
            pos = margin
            while (pos < h - margin) {
                drawLine(accent.copy(alpha = .34f), Offset(margin, pos), Offset(margin, (pos + 3.dp.toPx()).coerceAtMost(h-margin)),
                    .85.dp.toPx())
                drawLine(accent.copy(alpha = .34f), Offset(w-margin, pos), Offset(w-margin, (pos + 3.dp.toPx()).coerceAtMost(h-margin)),
                    .85.dp.toPx())
                pos += dash
            }
        }
    }
}

/** Paper tape: diagonal translucency and thin cross-fibres mimic washi adhesive. */
@Composable
fun JournalWashiTape(modifier: Modifier = Modifier, color: Color = Color(0xFFDFC1AD)) {
    Canvas(modifier) {
        val wash = Brush.linearGradient(
            listOf(color.copy(alpha = .66f), Color.White.copy(alpha = .70f), color.copy(alpha = .70f))
        )
        drawRoundRect(wash, cornerRadius = CornerRadius(2.dp.toPx()))
        val edge = Color(0xFF8B7165).copy(alpha = .20f)
        for (i in 1..8) {
            val x = i * size.width / 9f
            drawLine(edge, Offset(x, 1.dp.toPx()), Offset(x - 3.dp.toPx(), size.height-1.dp.toPx()), .65.dp.toPx())
        }
    }
}

/** Tiny pressed flower with deliberate imperfect petals and embroidery-centre. */
@Composable
fun JournalPressedFlower(modifier: Modifier = Modifier, petal: Color = LinenRose) {
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height * .46f)
        val radius = size.minDimension * .20f
        for (i in 0 until 5) {
            val a = (i * 2.0 * PI / 5.0 - PI / 2.0)
            val cx = center.x + cos(a).toFloat() * radius
            val cy = center.y + sin(a).toFloat() * radius
            drawCircle(petal.copy(alpha = if (i % 2 == 0) .8f else .66f), radius = radius * .84f, center = Offset(cx, cy))
        }
        drawCircle(Color(0xFFF7DC9B), radius * .45f, center)
        drawCircle(LinenDarker.copy(alpha = .65f), radius * .12f, Offset(center.x + radius * .08f, center.y - radius * .08f))
        val stemY = size.height * .83f
        drawLine(LinenLeaf, Offset(center.x, center.y + radius), Offset(center.x + radius * .10f, stemY),
            strokeWidth = 1.dp.toPx())
        drawOval(LinenLeaf.copy(alpha = .66f),
            topLeft = Offset(center.x - radius * .7f, stemY - radius * .4f),
            size = androidx.compose.ui.geometry.Size(radius * .85f, radius * .45f))
    }
}

/**
 * Real composables for collage: layered ticket, washi tape, edge stitching and pinned flower.
 * The content is still real text, accessible and clickable — not a flattened screenshot.
 */
@Composable
fun JournalPaperCard(
    modifier: Modifier = Modifier,
    appearance: LuluAppearance = LocalLuluAppearance.current,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(10.dp, 20.dp, 11.dp, 18.dp)
    Box(modifier) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = shape,
            color = Color(appearance.palette.card.toInt()),
            border = BorderStroke(1.dp, Color(appearance.palette.border.toInt())),
            shadowElevation = 3.dp,
            onClick = onClick ?: {},
        ) {
            Box(Modifier.fillMaxSize().journalPaper(appearance)) {
                content()
            }
        }
        JournalWashiTape(
            modifier = Modifier.align(Alignment.TopCenter)
                .offset(y = (-6).dp).width(67.dp).height(17.dp),
        )
    }
}

/** A tactile individual app icon, for the journal skin only. */
@Composable
fun JournalPatchIcon(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val profile = LocalLuluAppearance.current
    val bg = Color(profile.palette.cardStrong.toInt())
    val stroke = Color(profile.palette.border.toInt())
    val accent = Color(profile.palette.accent.toInt())
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.width(61.dp).height(63.dp).clickable(onClick = onClick),
            shape = RoundedCornerShape(16.dp, 9.dp, 15.dp, 9.dp),
            color = bg,
            border = BorderStroke(1.dp, stroke),
            shadowElevation = 3.dp,
        ) {
            Box(Modifier.fillMaxSize().journalPaper(profile), contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    color = Color(profile.palette.card.toInt()).copy(alpha = .72f),
                    shape = RoundedCornerShape(13.dp, 8.dp, 13.dp, 8.dp),
                    border = BorderStroke(1.dp, accent.copy(alpha = .25f)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, title, modifier = Modifier.size(26.dp), tint = accent)
                    }
                }
                JournalPressedFlower(
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-6).dp).size(21.dp),
                    petal = Color(0xFFBE8D80),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(title, color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Tactile stitched background behind normal chat text, not a new speech or time model. */
@Composable
fun JournalChatBubble(
    mine: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val profile = LocalLuluAppearance.current
    val surface = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val border = MaterialTheme.colorScheme.outline.copy(alpha = .73f)
    val shape = if (mine) RoundedCornerShape(17.dp, 6.dp, 16.dp, 16.dp)
                else RoundedCornerShape(6.dp, 17.dp, 16.dp, 16.dp)
    Surface(modifier = modifier,
        color = surface,
        shape = shape,
        border = BorderStroke(1.dp, border),
        shadowElevation = 1.dp,
    ) {
        Box(Modifier.journalPaper(profile, stitches = false)) {
            // Offset left seam leaves long messages readable on small phones.
            Box(Modifier.align(Alignment.CenterStart).width(3.dp).fillMaxHeight()
                .background(if (mine) MaterialTheme.colorScheme.primary.copy(alpha=.72f)
                            else LinenLeaf.copy(alpha=.72f)))
            Box(Modifier.padding(start = 4.dp)) { content() }
        }
    }
}

@Composable
fun JournalStudioHero(modifier: Modifier = Modifier) {
    val profile = LocalLuluAppearance.current
    Box(modifier.journalLinen(profile)) {
        Box(
            Modifier.align(Alignment.CenterStart).padding(start = 20.dp)
                .width(198.dp).height(156.dp),
        ) {
            JournalPaperCard(Modifier.fillMaxSize(), appearance = profile) {
                Column(Modifier.fillMaxSize().padding(start = 18.dp, top = 18.dp, end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("HANDMADE  /  VOL. 01", color = MaterialTheme.colorScheme.primary,
                        fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
                    Text("日子的边角料", color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("把喜欢的东西，一片片缝进生活。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp, lineHeight = 16.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf(0xFFEAC8C5, 0xFFC6D3BA, 0xFFE6D5B3).forEach {
                            Box(Modifier.size(17.dp).clip(CircleShape).background(Color(it)))
                        }
                    }
                }
            }
        }
        JournalPressedFlower(
            Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 15.dp).size(86.dp),
            petal = Color(0xFFBF8280),
        )
        Surface(
            Modifier.align(Alignment.BottomEnd).padding(bottom = 18.dp, end = 12.dp),
            color = Color(0xFFF2DFCB), shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, Color(0xFFD8BDA8)),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("a little", color = Color(0xFF876D62), fontSize = 9.sp)
                Text("scrapbook", color = Color(0xFF876D62), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}
