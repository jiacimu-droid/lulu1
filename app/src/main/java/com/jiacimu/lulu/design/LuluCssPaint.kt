package com.jiacimu.lulu.design

import android.graphics.Paint
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.LuluCssSheet
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal fun Map<String, String>.hasCssSurface(): Boolean = keys.any {
    it == "background-color" || it == "background-image" || it == "box-shadow" ||
        it == "border-color" || it == "border-width" || it in LuluCssSheet.CORNERS
}

private fun Map<String, String>.cssShape(fallback: Shape?): Shape? {
    val radius = cssDp("border-radius", 512f)?.let(::CornerSize)
    if (radius == null && LuluCssSheet.CORNERS.none { it in this }) return fallback
    val rounded = fallback as? CornerBasedShape
    val defaults = listOf(rounded?.topStart, rounded?.topEnd, rounded?.bottomEnd, rounded?.bottomStart)
    val corners = LuluCssSheet.CORNERS.mapIndexed { index, key ->
        cssDp(key, 512f)?.let(::CornerSize) ?: radius ?: defaults[index] ?: CornerSize(0.dp)
    }
    return AbsoluteRoundedCornerShape(corners[0], corners[1], corners[2], corners[3])
}

@Composable
internal fun Modifier.luluCssPaint(
    style: Map<String, String>,
    defaultShape: Shape? = null,
    defaultBackground: Color? = null,
    defaultBorder: BorderStroke? = null,
    backgroundMaxEdge: Int = 1024,
): Modifier {
    if (style.isEmpty() && defaultShape != null) {
        var plain = this
        if (defaultBackground != null) plain = plain.background(defaultBackground, defaultShape)
        if (defaultBorder != null) plain = plain.border(defaultBorder, defaultShape)
        return plain.clip(defaultShape)
    }
    val before = remember(style) { style.cssLayer("before") }
    val after = remember(style) { style.cssLayer("after") }
    val fill = style.cssColor("background-color") ?: defaultBackground
    val shape = remember(style, defaultShape) { style.cssShape(defaultShape) }
    val image = cssPainter(style, maxEdge = backgroundMaxEdge)
    val gradient = remember(style) { LuluCssSheet.gradient(style["background-image"]) }
    val shadow = remember(style) { LuluCssSheet.shadow(style["box-shadow"]) }
    val borderColor = style.cssColor("border-color")
    val borderWidth = style.cssDp("border-width", 128f) ?: defaultBorder?.width ?: 1.dp
    val border = when {
        borderWidth <= 0.dp -> null
        borderColor != null -> BorderStroke(borderWidth, borderColor)
        defaultBorder != null -> BorderStroke(borderWidth, defaultBorder.brush)
        "border-width" in style -> BorderStroke(borderWidth, style.cssColor("color") ?: Color.Black)
        else -> null
    }
    if (shape == null && fill == null && image == null && gradient == null && shadow == null &&
        before.isEmpty() && after.isEmpty() && border == null) return this

    val beforeImage = cssPainter(before, maxEdge = 512)
    val afterImage = cssPainter(after, maxEdge = 512)
    val outlineShape = shape ?: RectangleShape
    val decoration = drawWithCache {
        val path = if (shape == null && shadow == null) null else outlineShape.createOutline(size, layoutDirection, this).toPath()
        val brush = gradient?.brush(size)
        val drawShadow = if (path != null && shadow != null) cssShadow(path, shadow) else null
        val drawBefore = cssLayerDrawing(before, beforeImage, Alignment.TopStart)
        val drawAfter = cssLayerDrawing(after, afterImage, Alignment.TopEnd)
        onDrawWithContent {
            drawShadow?.invoke(this)
            val drawBackground: DrawScope.() -> Unit = {
                fill?.let { drawRect(it) }
                brush?.let { drawRect(it) }
                image?.let { drawCssImage(it, style, size) }
            }
            if (path == null) clipRect { drawBackground() }
            else clipPath(path) { drawBackground() }
            drawBefore?.invoke(this)
            if (shape == null || path == null) drawContent()
            else clipPath(path) { this@onDrawWithContent.drawContent() }
            drawAfter?.invoke(this)
        }
    }
    return then(decoration).then(if (border == null) Modifier else Modifier.border(border, outlineShape))
}

private fun Map<String, String>.cssLayer(layer: String): Map<String, String> {
    if (get("$layer/content") == "none") return emptyMap()
    return buildMap {
        for ((key, value) in this@cssLayer) {
            if (key.startsWith("$layer/")) put(key.substringAfter('/'), value)
        }
    }
}

@Composable
private fun cssPainter(style: Map<String, String>, maxEdge: Int): BitmapPainter? {
    val token = LuluCssSheet.assetToken(style["background-image"]) ?: return null
    val image = luluCssBitmap(token, maxEdge)
    return remember(image) { image?.let { BitmapPainter(it) } }
}

private fun DrawScope.drawCssImage(
    painter: BitmapPainter,
    style: Map<String, String>,
    bounds: Size,
    alpha: Float = 1f,
    fallbackScale: ContentScale = ContentScale.Crop,
) {
    if (bounds.isEmpty()) return
    val source = painter.intrinsicSize
    val scale = style.cssImageScale(fallbackScale).computeScaleFactor(source, bounds)
    val scaled = Size(source.width * scale.scaleX, source.height * scale.scaleY)
    val offset = style.cssImageAlignment().align(
        IntSize(scaled.width.roundToInt(), scaled.height.roundToInt()),
        IntSize(bounds.width.roundToInt(), bounds.height.roundToInt()),
        LayoutDirection.Ltr,
    )
    translate(offset.x.toFloat(), offset.y.toFloat()) {
        with(painter) { draw(scaled, alpha = alpha) }
    }
}

private fun Outline.toPath(): Path = when (this) {
    is Outline.Generic -> path
    is Outline.Rectangle -> Path().also { it.addRect(rect) }
    is Outline.Rounded -> Path().also { it.addRoundRect(roundRect) }
}

private fun LuluCssSheet.Companion.Gradient.brush(size: Size): Brush {
    val radians = Math.toRadians((angle % 360f).toDouble())
    val x = sin(radians).toFloat()
    val y = -cos(radians).toFloat()
    val half = (abs(x) * size.width + abs(y) * size.height) / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    val extent = Offset(x * half, y * half)
    return Brush.linearGradient(colors.map { Color(it.toInt()) }, center - extent, center + extent)
}

private fun CacheDrawScope.cssLayerDrawing(
    style: Map<String, String>,
    image: BitmapPainter?,
    alignment: Alignment,
): (DrawScope.() -> Unit)? {
    if (style.isEmpty()) return null
    val alpha = style["opacity"]?.toFloatOrNull()?.takeIf { it in 0f..1f } ?: 1f
    if (alpha == 0f) return null
    val fill = style.cssColor("background-color")
    val gradient = LuluCssSheet.gradient(style["background-image"])
    val border = style.cssColor("border-color")
    if (image == null && fill == null && gradient == null && border == null) return null
    fun length(key: String, axis: Float): Float? =
        LuluCssSheet.dp(style[key])?.coerceIn(-4096f, 4096f)?.dp?.toPx()
            ?: LuluCssSheet.percent(style[key])?.coerceIn(-200f, 200f)?.let { it * axis / 100f }

    val left = length("left", size.width)
    val right = length("right", size.width)
    val top = length("top", size.height)
    val bottom = length("bottom", size.height)
    fun dimension(key: String, axis: Float, start: Float?, end: Float?): Float =
        (length(key, axis) ?: if (start != null && end != null) axis - start - end else 28.dp.toPx())
            .coerceIn(0f, 4096.dp.toPx())
    val bounds = Size(dimension("width", size.width, left, right), dimension("height", size.height, top, bottom))
    if (bounds.isEmpty()) return null
    val anchor = style.cssAlignment(alignment).align(
        IntSize(bounds.width.roundToInt(), bounds.height.roundToInt()),
        IntSize(size.width.roundToInt(), size.height.roundToInt()),
        layoutDirection,
    )
    val x = (left ?: right?.let { size.width - bounds.width - it } ?: anchor.x.toFloat()) +
        (length("offset-x", size.width) ?: 0f)
    val y = (top ?: bottom?.let { size.height - bounds.height - it } ?: anchor.y.toFloat()) +
        (length("offset-y", size.height) ?: 0f)
    val angle = (LuluCssSheet.degrees(style["rotate"]) ?: 0f) % 360f
    val outline = (style.cssShape(null) ?: RectangleShape).createOutline(bounds, layoutDirection, this)
    val path = outline.toPath()
    val brush = gradient?.brush(bounds)
    val stroke = (style.cssDp("border-width", 128f) ?: 1.dp).toPx()
    return {
        translate(x, y) {
            rotate(angle, Offset(bounds.width / 2f, bounds.height / 2f)) {
                clipPath(path) {
                    fill?.let { drawRect(it, size = bounds, alpha = alpha) }
                    brush?.let { drawRect(it, size = bounds, alpha = alpha) }
                    image?.let { drawCssImage(it, style, bounds, alpha, ContentScale.Fit) }
                    if (border != null && stroke > 0f) {
                        drawPath(path, border, alpha = alpha, style = Stroke(stroke * 2f))
                    }
                }
            }
        }
    }
}

private fun CacheDrawScope.cssShadow(path: Path, shadow: LuluCssSheet.Companion.Shadow): DrawScope.() -> Unit {
    val x = shadow.x.coerceIn(-128f, 128f).dp.toPx()
    val y = shadow.y.coerceIn(-128f, 128f).dp.toPx()
    val blur = shadow.blur.coerceIn(0f, 64f).dp.toPx()
    val color = Color(shadow.color.toInt())
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(blur, x, y, shadow.color.toInt())
    }
    return {
        clipPath(path, ClipOp.Difference) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                if (blur > 0f && (Build.VERSION.SDK_INT >= 28 || !native.isHardwareAccelerated)) {
                    native.drawPath(path.asAndroidPath(), paint)
                } else {
                    // Hardware on API 26–27 cannot blur an arbitrary path.
                    translate(x, y) { drawPath(path, color) }
                }
            }
        }
    }
}
