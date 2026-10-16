package com.jiacimu.lulu.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.LuluAppearance
import com.jiacimu.lulu.data.LuluAppearanceCatalog
import com.jiacimu.lulu.data.LuluAppearanceFont
import com.jiacimu.lulu.data.LuluAppearanceMotif
import kotlin.math.floor

val LocalLuluAppearance = compositionLocalOf { LuluAppearanceCatalog.builtIns.first() }

@Composable
fun LuluAppearanceTheme(profile: LuluAppearance, content: @Composable () -> Unit) {
    val p = profile.palette
    val background = Color(p.paper.toInt())
    val card = Color(p.card.toInt())
    val cardStrong = Color(p.cardStrong.toInt())
    val ink = Color(p.ink.toInt())
    val muted = Color(p.muted.toInt())
    val border = Color(p.border.toInt())
    val accent = Color(p.accent.toInt())
    val onAccent = Color(p.onAccent.toInt())
    val soft = Color(p.soft.toInt())
    val scheme = if (profile.dark) darkColorScheme(
        primary = accent, onPrimary = onAccent,
        primaryContainer = soft, onPrimaryContainer = ink,
        secondary = muted, onSecondary = background,
        background = background, onBackground = ink,
        surface = card, onSurface = ink,
        surfaceVariant = cardStrong, onSurfaceVariant = muted,
        outline = border, outlineVariant = border,
        surfaceTint = Color.Transparent,
    ) else lightColorScheme(
        primary = accent, onPrimary = onAccent,
        primaryContainer = soft, onPrimaryContainer = ink,
        secondary = muted, onSecondary = Color.White,
        background = background, onBackground = ink,
        surface = card, onSurface = ink,
        surfaceVariant = cardStrong, onSurfaceVariant = muted,
        outline = border, outlineVariant = border,
        surfaceTint = Color.Transparent,
    )
    val family = when (profile.font) {
        LuluAppearanceFont.SANS -> FontFamily.SansSerif
        LuluAppearanceFont.SERIF -> FontFamily.Serif
        LuluAppearanceFont.MONO -> FontFamily.Monospace
    }
    val styled = Typography(
        headlineLarge = LuluTypography.headlineLarge.copy(fontFamily = family),
        headlineMedium = LuluTypography.headlineMedium.copy(fontFamily = family),
        titleLarge = LuluTypography.titleLarge.copy(fontFamily = family),
        titleMedium = LuluTypography.titleMedium.copy(fontFamily = family),
        bodyLarge = LuluTypography.bodyLarge.copy(fontFamily = family),
        bodyMedium = LuluTypography.bodyMedium.copy(fontFamily = family),
        bodySmall = LuluTypography.bodySmall.copy(fontFamily = family),
        labelLarge = LuluTypography.labelLarge.copy(fontFamily = family),
        labelMedium = LuluTypography.labelMedium.copy(fontFamily = family),
    )
    val radius = profile.corner.coerceIn(4, 32)
    val shapes = if (profile.isJournal()) Shapes(
        small = RoundedCornerShape(radius.dp, (radius / 2).dp, radius.dp, (radius / 2).dp),
        medium = RoundedCornerShape(radius.dp, (radius / 2).dp, radius.dp, (radius / 2).dp),
        large = RoundedCornerShape((radius + 6).dp, radius.dp, radius.dp, (radius + 6).dp),
    ) else Shapes(
        small = RoundedCornerShape((radius / 2).dp),
        medium = RoundedCornerShape(radius.dp),
        large = RoundedCornerShape((radius + 6).coerceAtMost(40).dp),
    )
    CompositionLocalProvider(LocalLuluAppearance provides profile) {
        MaterialTheme(colorScheme = scheme, typography = styled, shapes = shapes, content = content)
    }
}

/**
 * Lightweight vector marks. These add texture without image download,
 * bitmap memory pressure or interfering with touch/scroll gestures.
 */
@Composable
fun LuluAppearancePattern(
    modifier: Modifier = Modifier,
    profile: LuluAppearance = LocalLuluAppearance.current,
) {
    val accent = Color(profile.palette.accent.toInt()).copy(alpha = 0.13f)
    val ink = Color(profile.palette.ink.toInt()).copy(alpha = 0.085f)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w < 10f || h < 10f) return@Canvas
        when (profile.motif) {
            LuluAppearanceMotif.CLEAN -> Unit
            LuluAppearanceMotif.STITCH -> {
                val step = 25.dp.toPx()
                val count = floor(w / step).toInt()
                for (i in 0..count) {
                    val x = i * step
                    drawLine(ink, Offset(x, 0f), Offset(x, h), strokeWidth = 0.6.dp.toPx())
                }
                val rowCount = floor(h / step).toInt()
                for (j in 0..rowCount) {
                    val y = j * step
                    drawLine(ink, Offset(0f, y), Offset(w, y), strokeWidth = 0.6.dp.toPx())
                }
                for (j in 1..rowCount) {
                    drawCircle(accent, 2.dp.toPx(), Offset(10.dp.toPx(), j * step))
                }
            }
            LuluAppearanceMotif.SLASH -> {
                val gap = 60.dp.toPx()
                for (i in 0..floor((w + h) / gap).toInt()) {
                    val start = i * gap
                    drawLine(accent, Offset(start, 0f), Offset(start - h * 0.58f, h),
                        strokeWidth = 12.dp.toPx())
                }
            }
            LuluAppearanceMotif.INK -> {
                val gap = 44.dp.toPx()
                for (i in 0..floor(h / gap).toInt()) {
                    val y = i * gap
                    drawLine(ink, Offset(0f, y), Offset(w, y), strokeWidth = 0.6.dp.toPx())
                }
                drawCircle(accent, w * 0.28f, Offset(w * 0.95f, h * 0.05f))
            }
            LuluAppearanceMotif.GOTHIC -> {
                val gap = 42.dp.toPx()
                for (i in 0..floor(w / gap).toInt()) {
                    drawCircle(accent, 9.dp.toPx(), Offset(i * gap, 0f))
                    drawCircle(accent, 3.dp.toPx(), Offset(i * gap + gap / 2f, 15.dp.toPx()))
                }
            }
            LuluAppearanceMotif.COMIC -> {
                val gap = 17.dp.toPx()
                for (i in 0..floor(w / gap).toInt()) {
                    for (j in 0..floor(h / gap).toInt()) {
                        drawCircle(ink, 1.15.dp.toPx(), Offset(i * gap, j * gap))
                    }
                }
            }
            LuluAppearanceMotif.BOTANICAL -> {
                val gap = 90.dp.toPx()
                for (i in 0..floor(h / gap).toInt()) {
                    val y = i * gap
                    drawCircle(accent, 13.dp.toPx(), Offset(w - 18.dp.toPx(), y))
                    drawCircle(ink, 7.dp.toPx(), Offset(w - 37.dp.toPx(), y + 12.dp.toPx()))
                }
            }
            LuluAppearanceMotif.GLASS -> {
                drawCircle(accent, w * 0.5f, Offset(w, 0f))
                drawCircle(ink, w * 0.32f, Offset(0f, h))
            }
            LuluAppearanceMotif.STARS -> {
                val gap = 68.dp.toPx()
                for (i in 0..floor(w / gap).toInt()) {
                    for (j in 0..floor(h / gap).toInt()) {
                        val center = Offset(i * gap + 10.dp.toPx(), j * gap + 18.dp.toPx())
                        drawLine(accent, Offset(center.x - 4.dp.toPx(), center.y),
                            Offset(center.x + 4.dp.toPx(), center.y), strokeWidth = 1.5.dp.toPx())
                        drawLine(accent, Offset(center.x, center.y - 4.dp.toPx()),
                            Offset(center.x, center.y + 4.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                    }
                }
            }
        }
    }
}
