package com.jiacimu.lulu.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared visual vocabulary for the warm-paper rebuild. */
object LuluColors {
    val Paper = Color(0xFFFFFCF5)
    val Card = Color(0xFFFFFBF3)
    val CardStrong = Color(0xFFFFF4DD)
    val Wheat = Color(0xFFF2CF70)
    val WheatSoft = Color(0xFFFFE8A8)
    val Ink = Color(0xFF302C2B)
    val Muted = Color(0xFF747887)
    val Border = Color(0xFFE7DDC8)
    val BlueGray = Color(0xFF6F8090)
    val SoftBlue = Color(0xFFE7EFF2)
    val SoftRose = Color(0xFFFFE8E2)
    val Success = Color(0xFF5F7659)
    val Danger = Color(0xFF9B4A45)
}

object LuluSpacing {
    val Tiny: Dp = 4.dp
    val Small: Dp = 8.dp
    val Medium: Dp = 12.dp
    val Large: Dp = 16.dp
    val XL: Dp = 20.dp
    val XXL: Dp = 28.dp
}

object LuluRadii {
    val Small: Dp = 12.dp
    val Medium: Dp = 16.dp
    val Large: Dp = 22.dp
    val Hero: Dp = 28.dp
}

object LuluSizes {
    val IconSmall: Dp = 18.dp
    val Icon: Dp = 24.dp
    val AvatarSmall: Dp = 34.dp
    val Avatar: Dp = 52.dp
    val TouchTarget: Dp = 48.dp
}

@Immutable
data class LuluMotionTokens(
    val quickMillis: Int = 140,
    val normalMillis: Int = 220,
    val slowMillis: Int = 360,
)

val LuluTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
)

val LuluLightColorScheme: ColorScheme = lightColorScheme(
    primary = LuluColors.Wheat,
    onPrimary = LuluColors.Ink,
    primaryContainer = LuluColors.WheatSoft,
    onPrimaryContainer = LuluColors.Ink,
    secondary = LuluColors.BlueGray,
    onSecondary = Color.White,
    secondaryContainer = LuluColors.SoftBlue,
    onSecondaryContainer = LuluColors.Ink,
    background = LuluColors.Paper,
    onBackground = LuluColors.Ink,
    surface = LuluColors.Card,
    onSurface = LuluColors.Ink,
    surfaceVariant = LuluColors.CardStrong,
    onSurfaceVariant = LuluColors.Muted,
    outline = LuluColors.Border,
    error = LuluColors.Danger,
)
