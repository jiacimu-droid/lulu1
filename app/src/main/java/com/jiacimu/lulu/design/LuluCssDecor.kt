package com.jiacimu.lulu.design

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.jiacimu.lulu.data.LuluCssSheet
import com.jiacimu.lulu.data.LuluCssStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

val LocalLuluCssSheet = compositionLocalOf { LuluCssSheet.parse("") }
val LocalLuluCssScope = compositionLocalOf { "Home" }

@Composable
fun luluCssStyle(vararg elements: String): Map<String, String> =
    LocalLuluCssSheet.current.element(LocalLuluCssScope.current, *elements)

internal fun Map<String, String>.cssColor(key: String): Color? =
    LuluCssSheet.parseColor(get(key))?.let { Color(it.toInt()) }

internal fun Map<String, String>.cssDp(key: String, max: Float = 4096f): Dp? =
    LuluCssSheet.dp(get(key))?.coerceIn(0f, max)?.dp

internal fun Map<String, String>.cssFontSize(): TextUnit? =
    get("font-size")?.removeSuffix("sp")?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
        ?.coerceIn(1f, 160f)?.sp

private fun Map<String, String>.cssFontFamily(): FontFamily? = when (get("font-family")) {
    "serif", "songti" -> FontFamily.Serif
    "mono", "monospace" -> FontFamily.Monospace
    "sans", "sans-serif" -> FontFamily.SansSerif
    else -> null
}

private fun Map<String, String>.cssImageScale(): ContentScale = when (get("background-size")) {
    "contain" -> ContentScale.Fit
    "stretch" -> ContentScale.FillBounds
    else -> ContentScale.Crop
}

internal fun Map<String, String>.cssAlignment(fallback: Alignment): Alignment = when (get("align")) {
    "start", "top-start" -> Alignment.TopStart
    "top-center" -> Alignment.TopCenter
    "end", "top-end" -> Alignment.TopEnd
    "center-start" -> Alignment.CenterStart
    "center" -> Alignment.Center
    "center-end" -> Alignment.CenterEnd
    "bottom-start" -> Alignment.BottomStart
    "bottom-center" -> Alignment.BottomCenter
    "bottom-end" -> Alignment.BottomEnd
    else -> fallback
}

@Composable
private fun luluCssBitmap(token: String?, maxEdge: Int): ImageBitmap? {
    val file = LuluCssStore.assetFile(token)
    // Key the result with the file: a previous image must never appear on a new page/tile.
    val decoded by produceState<Pair<String, ImageBitmap?>?>(null, file?.path, maxEdge) {
        val path = file?.path ?: return@produceState
        value = path to withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var factor = 1
                while (bounds.outWidth / factor > maxEdge || bounds.outHeight / factor > maxEdge) {
                    factor *= 2
                }
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
                    inSampleSize = factor
                })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return decoded?.takeIf { it.first == file?.path }?.second
}

@Composable
fun Modifier.luluCssElement(vararg elements: String): Modifier = luluCssDecor(luluCssStyle(*elements))

@Composable
internal fun Modifier.luluCssDecor(
    style: Map<String, String>,
    includeArtwork: Boolean = true,
): Modifier {
    if (style.isEmpty()) return this
    val radius = style.cssDp("border-radius", 512f)
    val shape = RoundedCornerShape(radius ?: 0.dp)
    val margin = style.cssDp("margin")
    val padding = style.cssDp("padding")
    val x = style["offset-x"]
    val y = style["offset-y"]

    val minWidth = style.cssDp("min-width")
    val minHeight = style.cssDp("min-height")
    val maxWidth = style.cssDp("max-width")
    val maxHeight = style.cssDp("max-height")
    var result = this
    if (x != null || y != null) result = result.layout { measurable, constraints ->
        val child = measurable.measure(constraints)
        val viewportWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else child.width
        val viewportHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else child.height
        fun offset(value: String?, available: Int): Int =
            LuluCssSheet.dp(value)?.coerceIn(-4096f, 4096f)?.dp?.roundToPx()
                ?: ((LuluCssSheet.percent(value) ?: 0f).coerceIn(-200f, 200f) * available / 100).roundToInt()
        layout(child.width, child.height) {
            child.placeRelative(offset(x, viewportWidth), offset(y, viewportHeight))
        }
    }
    style["z-index"]?.toIntOrNull()?.let { result = result.zIndex(it.toFloat()) }
    result = result.padding(
        horizontal = style.cssDp("margin-inline") ?: margin ?: 0.dp,
        vertical = style.cssDp("margin-block") ?: margin ?: 0.dp,
    ).sizeIn(
        minWidth = minWidth ?: Dp.Unspecified,
        minHeight = minHeight ?: Dp.Unspecified,
        maxWidth = maxWidth?.coerceAtLeast(minWidth ?: 0.dp) ?: Dp.Unspecified,
        maxHeight = maxHeight?.coerceAtLeast(minHeight ?: 0.dp) ?: Dp.Unspecified,
    )
    style.cssDp("width")?.let { result = result.width(it) }
    style.cssDp("height")?.let { result = result.height(it) }
    LuluCssSheet.percent(style["width"])?.let { result = result.fillMaxWidth(it.coerceIn(0f, 100f) / 100) }
    LuluCssSheet.percent(style["height"])?.let { result = result.fillMaxHeight(it.coerceIn(0f, 100f) / 100) }
    if (includeArtwork) {
        if (radius != null) result = result.clip(shape)
        style.cssColor("background-color")?.let { result = result.background(it, shape) }
        val border = style.cssColor("border-color")
        if (border != null) {
            val borderWidth = style.cssDp("border-width") ?: 1.dp
            if (borderWidth > 0.dp) result = result.border(borderWidth, border, shape)
        }
        val token = LuluCssSheet.assetToken(style["background-image"])
        if (token != null) {
            val image = luluCssBitmap(token, maxEdge = 1024)
            if (image != null) result = result.paint(BitmapPainter(image), sizeToIntrinsics = false,
                contentScale = style.cssImageScale())
        }
    }
    return result.padding(
        horizontal = style.cssDp("padding-inline") ?: padding ?: 0.dp,
        vertical = style.cssDp("padding-block") ?: padding ?: 0.dp,
    )
}

/** Wrap existing content so its drawing, focus and pointer target travel together. */
@Composable
internal fun LuluCssRegion(
    style: Map<String, String>,
    modifier: Modifier = Modifier,
    fallbackAlignment: Alignment = Alignment.TopStart,
    propagateMinConstraints: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.luluCssDecor(style),
        contentAlignment = style.cssAlignment(fallbackAlignment),
        propagateMinConstraints = propagateMinConstraints,
    ) {
        val color = style.cssColor("color") ?: LocalContentColor.current
        val text = LocalTextStyle.current
        CompositionLocalProvider(LocalContentColor provides color) {
            ProvideTextStyle(text.copy(
                color = style.cssColor("color") ?: text.color,
                fontSize = style.cssFontSize() ?: text.fontSize,
                fontFamily = style.cssFontFamily() ?: text.fontFamily,
            )) { content() }
        }
    }
}

@Composable
fun LuluCssArtwork(
    token: String?,
    modifier: Modifier = Modifier,
    style: Map<String, String> = emptyMap(),
    maxEdge: Int = 1536,
    fallback: @Composable () -> Unit = {},
) {
    val image = if (token != null) luluCssBitmap(token, maxEdge) else null
    if (image == null) fallback()
    else Image(image, contentDescription = null, modifier = modifier, contentScale = style.cssImageScale())
}

@Composable
fun LuluCssBackground(modifier: Modifier = Modifier.fillMaxSize()) {
    val scope = LocalLuluCssScope.current
    val sheet = LocalLuluCssSheet.current
    LuluCssArtwork(sheet.imageToken(scope), modifier, sheet.page(scope))
}
