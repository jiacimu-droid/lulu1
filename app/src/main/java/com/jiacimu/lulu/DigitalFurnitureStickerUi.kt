package com.jiacimu.lulu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.DigitalFurnitureCatalog
import com.jiacimu.lulu.data.DigitalFurnitureKind
import com.jiacimu.lulu.data.DigitalFurnitureStyle
import com.jiacimu.lulu.data.DigitalWorldItem

@Composable
internal fun FurnitureSticker(
    item: DigitalWorldItem,
    style: DigitalFurnitureStyle,
    modifier: Modifier = Modifier,
    preview: Boolean = false,
) {
    val width = if (preview) 72.dp else stickerWidth(style.kind)
    val height = if (preview) 62.dp else stickerHeight(style.kind)
    val base = stickerColor(style.colorKey)
    Box(modifier.size(width, height), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(width, height)) {
            drawFurnitureSticker(style, base)
        }
    }
}

private fun DrawScope.drawFurnitureSticker(style: DigitalFurnitureStyle, base: Color) {
    val dark = darken(base, .76f)
    val darker = darken(base, .58f)
    val soft = lighten(base, .28f)
    val pale = lighten(base, .55f)
    val white = Color(0xFFFFFEFB)
    val ink = Color(0xFF554F49).copy(alpha = .72f)
    val softInk = ink.copy(alpha = .36f)
    val shadow = Color.Black.copy(alpha = .075f)

    when (style.kind) {
        DigitalFurnitureKind.BED -> {
            drawOval(shadow, Offset(size.width * .08f, size.height * .82f), Size(size.width * .84f, size.height * .12f))
            drawRoundRect(
                darker,
                Offset(size.width * .07f, size.height * .18f),
                Size(size.width * .86f, size.height * .67f),
                CornerRadius(size.height * .10f),
            )
            drawRoundRect(
                dark,
                Offset(size.width * .05f, size.height * .06f),
                Size(size.width * .90f, size.height * .31f),
                CornerRadius(size.height * .13f),
            )
            drawRoundRect(
                soft,
                Offset(size.width * .10f, size.height * .27f),
                Size(size.width * .80f, size.height * .52f),
                CornerRadius(size.height * .08f),
            )
            drawRoundRect(
                pale,
                Offset(size.width * .12f, size.height * .49f),
                Size(size.width * .76f, size.height * .28f),
                CornerRadius(size.height * .07f),
            )
            drawRoundRect(white, Offset(size.width * .15f, size.height * .25f), Size(size.width * .28f, size.height * .19f), CornerRadius(size.height * .07f))
            drawRoundRect(white, Offset(size.width * .57f, size.height * .25f), Size(size.width * .28f, size.height * .19f), CornerRadius(size.height * .07f))
            drawLine(softInk, Offset(size.width * .13f, size.height * .81f), Offset(size.width * .10f, size.height * .93f), 2.2.dp.toPx())
            drawLine(softInk, Offset(size.width * .87f, size.height * .81f), Offset(size.width * .90f, size.height * .93f), 2.2.dp.toPx())
            if (style.pattern == "stripe") {
                repeat(4) { index ->
                    val y = size.height * (.55f + index * .055f)
                    drawLine(white.copy(alpha = .65f), Offset(size.width * .17f, y), Offset(size.width * .83f, y), 1.2.dp.toPx())
                }
            }
        }

        DigitalFurnitureKind.SOFA -> {
            drawOval(shadow, Offset(size.width * .09f, size.height * .82f), Size(size.width * .82f, size.height * .11f))
            drawRoundRect(
                dark,
                Offset(size.width * .08f, size.height * .10f),
                Size(size.width * .84f, size.height * .52f),
                CornerRadius(size.height * .16f),
            )
            drawRoundRect(
                soft,
                Offset(size.width * .12f, size.height * .17f),
                Size(size.width * .35f, size.height * .36f),
                CornerRadius(size.height * .11f),
            )
            drawRoundRect(
                soft,
                Offset(size.width * .53f, size.height * .17f),
                Size(size.width * .35f, size.height * .36f),
                CornerRadius(size.height * .11f),
            )
            drawRoundRect(
                base,
                Offset(size.width * .07f, size.height * .49f),
                Size(size.width * .86f, size.height * .31f),
                CornerRadius(size.height * .11f),
            )
            drawRoundRect(dark, Offset(0f, size.height * .42f), Size(size.width * .16f, size.height * .36f), CornerRadius(size.height * .10f))
            drawRoundRect(dark, Offset(size.width * .84f, size.height * .42f), Size(size.width * .16f, size.height * .36f), CornerRadius(size.height * .10f))
            drawLine(softInk, Offset(size.width * .18f, size.height * .78f), Offset(size.width * .17f, size.height * .91f), 2.dp.toPx())
            drawLine(softInk, Offset(size.width * .82f, size.height * .78f), Offset(size.width * .83f, size.height * .91f), 2.dp.toPx())
            drawLine(white.copy(alpha = .52f), Offset(size.width * .50f, size.height * .52f), Offset(size.width * .50f, size.height * .75f), 1.2.dp.toPx())
        }

        DigitalFurnitureKind.COFFEE_TABLE,
        DigitalFurnitureKind.TABLE,
        DigitalFurnitureKind.DESK -> {
            drawOval(shadow, Offset(size.width * .13f, size.height * .83f), Size(size.width * .74f, size.height * .10f))
            val topColor = if (style.colorKey == "glass") Color(0xFFDCE9ED).copy(alpha = .82f) else soft
            drawRoundRect(
                darker,
                Offset(size.width * .05f, size.height * .16f),
                Size(size.width * .90f, size.height * .34f),
                CornerRadius(size.height * .09f),
            )
            drawRoundRect(
                topColor,
                Offset(size.width * .07f, size.height * .10f),
                Size(size.width * .86f, size.height * .31f),
                CornerRadius(size.height * .09f),
            )
            if (style.colorKey == "glass") {
                drawLine(Color.White.copy(alpha = .78f), Offset(size.width * .17f, size.height * .16f), Offset(size.width * .53f, size.height * .16f), 1.2.dp.toPx())
            }
            drawLine(dark, Offset(size.width * .20f, size.height * .43f), Offset(size.width * .15f, size.height * .88f), size.width * .035f)
            drawLine(dark, Offset(size.width * .80f, size.height * .43f), Offset(size.width * .85f, size.height * .88f), size.width * .035f)
            if (style.kind == DigitalFurnitureKind.DESK) {
                drawRoundRect(pale, Offset(size.width * .31f, size.height * .47f), Size(size.width * .38f, size.height * .17f), CornerRadius(4.dp.toPx()))
            }
        }

        DigitalFurnitureKind.CHAIR -> {
            drawOval(shadow, Offset(size.width * .18f, size.height * .87f), Size(size.width * .64f, size.height * .08f))
            drawRoundRect(
                dark,
                Offset(size.width * .14f, size.height * .03f),
                Size(size.width * .72f, size.height * .48f),
                CornerRadius(size.width * .18f),
            )
            drawRoundRect(
                pale,
                Offset(size.width * .22f, size.height * .11f),
                Size(size.width * .56f, size.height * .28f),
                CornerRadius(size.width * .15f),
            )
            drawRoundRect(
                base,
                Offset(size.width * .08f, size.height * .43f),
                Size(size.width * .84f, size.height * .28f),
                CornerRadius(size.width * .12f),
            )
            drawLine(dark, Offset(size.width * .26f, size.height * .67f), Offset(size.width * .20f, size.height * .94f), size.width * .045f)
            drawLine(dark, Offset(size.width * .74f, size.height * .67f), Offset(size.width * .80f, size.height * .94f), size.width * .045f)
        }

        DigitalFurnitureKind.SHELF -> {
            drawOval(shadow, Offset(size.width * .12f, size.height * .92f), Size(size.width * .76f, size.height * .06f))
            drawRoundRect(darker, size = Size(size.width, size.height * .94f), cornerRadius = CornerRadius(size.width * .09f))
            drawRoundRect(soft, Offset(size.width * .06f, size.height * .035f), Size(size.width * .88f, size.height * .86f), CornerRadius(size.width * .065f))
            repeat(3) { index ->
                val y = size.height * (.26f + index * .22f)
                drawLine(dark.copy(alpha = .50f), Offset(size.width * .08f, y), Offset(size.width * .92f, y), 1.6.dp.toPx())
            }
            val bookColors = listOf(base, pale, dark)
            repeat(6) { index ->
                val row = index / 3
                val col = index % 3
                drawRoundRect(
                    bookColors[index % bookColors.size],
                    Offset(size.width * (.14f + col * .24f), size.height * (.10f + row * .45f)),
                    Size(size.width * .10f, size.height * .14f),
                    CornerRadius(2.dp.toPx()),
                )
            }
        }

        DigitalFurnitureKind.CABINET,
        DigitalFurnitureKind.NIGHTSTAND -> {
            drawOval(shadow, Offset(size.width * .13f, size.height * .89f), Size(size.width * .74f, size.height * .07f))
            drawRoundRect(darker, Offset(size.width * .04f, size.height * .05f), Size(size.width * .92f, size.height * .82f), CornerRadius(size.width * .12f))
            drawRoundRect(soft, Offset(size.width * .08f, size.height * .08f), Size(size.width * .84f, size.height * .75f), CornerRadius(size.width * .10f))
            drawLine(softInk, Offset(size.width * .13f, size.height * .43f), Offset(size.width * .87f, size.height * .43f), 1.2.dp.toPx())
            drawCircle(darker, size.width * .030f, Offset(size.width * .50f, size.height * .29f))
            drawCircle(darker, size.width * .030f, Offset(size.width * .50f, size.height * .62f))
            drawLine(dark, Offset(size.width * .20f, size.height * .84f), Offset(size.width * .18f, size.height * .95f), 2.dp.toPx())
            drawLine(dark, Offset(size.width * .80f, size.height * .84f), Offset(size.width * .82f, size.height * .95f), 2.dp.toPx())
        }

        DigitalFurnitureKind.FLOOR_LAMP -> {
            val glow = if (style.colorKey == "warm") Color(0xFFF4D89C) else pale
            drawCircle(glow.copy(alpha = .11f), size.width * .43f, Offset(size.width * .52f, size.height * .23f))
            if (style.id == "lamp_arc") {
                val arc = Path().apply {
                    moveTo(size.width * .30f, size.height * .87f)
                    cubicTo(size.width * .28f, size.height * .34f, size.width * .50f, size.height * .14f, size.width * .72f, size.height * .19f)
                }
                drawPath(arc, ink, style = Stroke(size.width * .045f))
            } else {
                drawLine(ink, Offset(size.width * .50f, size.height * .34f), Offset(size.width * .50f, size.height * .87f), size.width * .045f)
            }
            val shade = Path().apply {
                moveTo(size.width * .25f, size.height * .09f)
                lineTo(size.width * .75f, size.height * .09f)
                lineTo(size.width * .65f, size.height * .34f)
                lineTo(size.width * .35f, size.height * .34f)
                close()
            }
            drawPath(shade, glow)
            drawPath(shade, softInk, style = Stroke(1.dp.toPx()))
            drawOval(ink, Offset(size.width * .24f, size.height * .85f), Size(size.width * .52f, size.height * .08f))
        }

        DigitalFurnitureKind.TABLE_LAMP -> {
            val glow = if (style.colorKey == "warm") Color(0xFFF4D89C) else pale
            drawCircle(glow.copy(alpha = .10f), size.width * .40f, Offset(size.width * .50f, size.height * .31f))
            if (style.id == "lamp_mushroom") {
                drawOval(glow, Offset(size.width * .16f, size.height * .12f), Size(size.width * .68f, size.height * .34f))
                drawRoundRect(dark, Offset(size.width * .43f, size.height * .43f), Size(size.width * .14f, size.height * .36f), CornerRadius(size.width * .06f))
            } else {
                val shade = Path().apply {
                    moveTo(size.width * .24f, size.height * .12f)
                    lineTo(size.width * .76f, size.height * .12f)
                    lineTo(size.width * .65f, size.height * .46f)
                    lineTo(size.width * .35f, size.height * .46f)
                    close()
                }
                drawPath(shade, glow)
                drawPath(shade, softInk, style = Stroke(1.dp.toPx()))
                drawLine(ink, Offset(size.width * .50f, size.height * .46f), Offset(size.width * .50f, size.height * .80f), size.width * .045f)
            }
            drawOval(ink, Offset(size.width * .26f, size.height * .79f), Size(size.width * .48f, size.height * .08f))
        }

        DigitalFurnitureKind.RUG -> {
            val rug = lighten(base, .48f).copy(alpha = .72f)
            drawOval(Color.Black.copy(alpha = .035f), Offset(size.width * .03f, size.height * .09f), Size(size.width * .94f, size.height * .84f))
            if (style.pattern == "round") {
                drawOval(rug, Offset(size.width * .02f, size.height * .03f), Size(size.width * .96f, size.height * .88f))
                drawOval(softInk.copy(alpha = .18f), Offset(size.width * .08f, size.height * .10f), Size(size.width * .84f, size.height * .74f), style = Stroke(1.dp.toPx()))
            } else {
                drawRoundRect(rug, Offset(size.width * .02f, size.height * .04f), Size(size.width * .96f, size.height * .86f), CornerRadius(size.height * .24f))
                drawRoundRect(softInk.copy(alpha = .18f), Offset(size.width * .08f, size.height * .11f), Size(size.width * .84f, size.height * .72f), CornerRadius(size.height * .19f), style = Stroke(1.dp.toPx()))
            }
            if (style.pattern == "stripe") {
                repeat(3) { index ->
                    val y = size.height * (.30f + index * .17f)
                    drawLine(white.copy(alpha = .58f), Offset(size.width * .17f, y), Offset(size.width * .83f, y), 1.2.dp.toPx())
                }
            }
        }

        DigitalFurnitureKind.PLANT -> {
            drawOval(shadow, Offset(size.width * .20f, size.height * .88f), Size(size.width * .60f, size.height * .07f))
            val pot = if (style.colorKey == "cactus") Color(0xFFD6B89A) else Color(0xFFC8A98A)
            drawRoundRect(pot, Offset(size.width * .28f, size.height * .64f), Size(size.width * .44f, size.height * .28f), CornerRadius(size.width * .11f))
            val leaf = when (style.colorKey) {
                "olive" -> Color(0xFF7C8E6B)
                "cactus" -> Color(0xFF78A176)
                else -> Color(0xFF6F9270)
            }
            if (style.colorKey == "cactus") {
                drawRoundRect(leaf, Offset(size.width * .40f, size.height * .13f), Size(size.width * .20f, size.height * .56f), CornerRadius(size.width * .10f))
                drawRoundRect(leaf, Offset(size.width * .27f, size.height * .33f), Size(size.width * .18f, size.height * .22f), CornerRadius(size.width * .09f))
                drawRoundRect(leaf, Offset(size.width * .56f, size.height * .27f), Size(size.width * .18f, size.height * .25f), CornerRadius(size.width * .09f))
            } else {
                val leaves = listOf(
                    Offset(.18f, .20f), Offset(.33f, .08f), Offset(.49f, .16f),
                    Offset(.55f, .04f), Offset(.63f, .24f), Offset(.30f, .30f),
                )
                leaves.forEachIndexed { index, p ->
                    drawOval(
                        leaf.copy(alpha = .88f - index * .035f),
                        Offset(size.width * p.x, size.height * p.y),
                        Size(size.width * .28f, size.height * .40f),
                    )
                }
            }
        }

        DigitalFurnitureKind.TV -> {
            drawOval(shadow, Offset(size.width * .17f, size.height * .90f), Size(size.width * .66f, size.height * .06f))
            drawRoundRect(darker, Offset(size.width * .05f, size.height * .06f), Size(size.width * .90f, size.height * .66f), CornerRadius(size.height * .06f))
            drawRoundRect(Color(0xFF303639), Offset(size.width * .09f, size.height * .10f), Size(size.width * .82f, size.height * .57f), CornerRadius(size.height * .04f))
            drawRoundRect(Color(0xFFB5CAD3).copy(alpha = .20f), Offset(size.width * .15f, size.height * .16f), Size(size.width * .32f, size.height * .15f), CornerRadius(size.height * .03f))
            drawLine(dark, Offset(size.width * .50f, size.height * .72f), Offset(size.width * .50f, size.height * .86f), 2.3.dp.toPx())
            drawRoundRect(dark, Offset(size.width * .29f, size.height * .85f), Size(size.width * .42f, size.height * .07f), CornerRadius(4.dp.toPx()))
        }

        DigitalFurnitureKind.MIRROR -> {
            val frame = if (style.colorKey == "glass") Color(0xFFB9AEA5) else dark
            if (style.pattern == "round") {
                drawOval(Color(0xFFEAF1F3), Offset(size.width * .10f, size.height * .08f), Size(size.width * .80f, size.height * .80f))
                drawOval(frame, Offset(size.width * .10f, size.height * .08f), Size(size.width * .80f, size.height * .80f), style = Stroke(2.dp.toPx()))
            } else {
                drawRoundRect(Color(0xFFEAF1F3), Offset(size.width * .14f, size.height * .04f), Size(size.width * .72f, size.height * .90f), CornerRadius(size.width * .34f))
                drawRoundRect(frame, Offset(size.width * .14f, size.height * .04f), Size(size.width * .72f, size.height * .90f), CornerRadius(size.width * .34f), style = Stroke(2.dp.toPx()))
            }
            drawLine(Color.White.copy(alpha = .76f), Offset(size.width * .31f, size.height * .18f), Offset(size.width * .55f, size.height * .52f), 1.3.dp.toPx())
        }

        DigitalFurnitureKind.WALL_ART -> {
            drawRoundRect(darker, size = size, cornerRadius = CornerRadius(5.dp.toPx()))
            drawRect(Color(0xFFF8F5EF), Offset(size.width * .07f, size.height * .07f), Size(size.width * .86f, size.height * .86f))
            if (style.id == "art_landscape") {
                val hills = Path().apply {
                    moveTo(size.width * .14f, size.height * .67f)
                    lineTo(size.width * .37f, size.height * .43f)
                    lineTo(size.width * .53f, size.height * .59f)
                    lineTo(size.width * .69f, size.height * .37f)
                    lineTo(size.width * .86f, size.height * .67f)
                }
                drawPath(hills, dark.copy(alpha = .55f), style = Stroke(1.5.dp.toPx()))
            } else {
                drawLine(softInk, Offset(size.width * .27f, size.height * .70f), Offset(size.width * .67f, size.height * .28f), 1.4.dp.toPx())
                drawCircle(softInk, size.minDimension * .10f, Offset(size.width * .39f, size.height * .40f), style = Stroke(1.4.dp.toPx()))
            }
        }

        DigitalFurnitureKind.CLOCK -> {
            drawCircle(white, size.minDimension * .41f, center)
            drawCircle(dark, size.minDimension * .41f, center, style = Stroke(2.dp.toPx()))
            drawLine(darker, center, Offset(center.x, center.y - size.height * .19f), 1.5.dp.toPx())
            drawLine(darker, center, Offset(center.x + size.width * .15f, center.y + size.height * .08f), 1.5.dp.toPx())
            drawCircle(darker, 2.dp.toPx(), center)
        }

        DigitalFurnitureKind.CUSHION -> {
            drawRoundRect(soft, Offset(size.width * .08f, size.height * .10f), Size(size.width * .84f, size.height * .78f), CornerRadius(size.width * .20f))
            drawRoundRect(softInk.copy(alpha = .16f), Offset(size.width * .12f, size.height * .14f), Size(size.width * .76f, size.height * .70f), CornerRadius(size.width * .18f), style = Stroke(1.dp.toPx()))
            drawCircle(white.copy(alpha = .68f), 2.dp.toPx(), center)
        }

        DigitalFurnitureKind.BASKET -> {
            drawOval(shadow, Offset(size.width * .16f, size.height * .88f), Size(size.width * .68f, size.height * .07f))
            drawRoundRect(soft, Offset(size.width * .09f, size.height * .31f), Size(size.width * .82f, size.height * .59f), CornerRadius(size.width * .12f))
            repeat(3) { index ->
                val y = size.height * (.45f + index * .13f)
                drawLine(dark.copy(alpha = .24f), Offset(size.width * .15f, y), Offset(size.width * .85f, y), 1.dp.toPx())
            }
            val handle = Path().apply {
                moveTo(size.width * .29f, size.height * .37f)
                cubicTo(size.width * .31f, size.height * .08f, size.width * .69f, size.height * .08f, size.width * .71f, size.height * .37f)
            }
            drawPath(handle, dark.copy(alpha = .66f), style = Stroke(2.dp.toPx()))
        }

        DigitalFurnitureKind.DECOR -> {
            if (style.colorKey == "glass") {
                drawOval(Color(0xFFD8E7EA).copy(alpha = .62f), Offset(size.width * .27f, size.height * .25f), Size(size.width * .46f, size.height * .60f))
                drawOval(Color.White.copy(alpha = .72f), Offset(size.width * .37f, size.height * .30f), Size(size.width * .10f, size.height * .40f))
            } else {
                drawCircle(soft, size.minDimension * .34f, center)
                drawCircle(white.copy(alpha = .68f), size.minDimension * .13f, center)
            }
        }
    }
}

/** Kept for old callers; the visible catalog is now the custom Lulu-styled dialog. */
@Composable
internal fun FurnitureCatalogDialog(onDismiss: () -> Unit) {
    StyledFurnitureCatalogDialog(onDismiss)
}

internal fun furniturePlacement(item: DigitalWorldItem, index: Int): Pair<Float, Float> {
    val text = item.position.lowercase()
    val kind = DigitalFurnitureCatalog.resolve(item).kind
    val fallback = defaultFurniturePlacement(kind, index)

    val x = when {
        listOf("最左", "左侧", "靠左", "左边").any(text::contains) -> .06f
        listOf("最右", "右侧", "靠右", "右边").any(text::contains) -> .72f
        listOf("正中", "中央", "中间", "中心").any(text::contains) -> .38f
        else -> fallback.first
    }
    val y = when {
        kind in setOf(DigitalFurnitureKind.WALL_ART, DigitalFurnitureKind.CLOCK, DigitalFurnitureKind.MIRROR) -> .10f
        kind == DigitalFurnitureKind.RUG -> .67f
        listOf("上方", "里面", "后方", "靠墙", "墙边", "窗边").any(text::contains) -> .30f
        listOf("下方", "门边", "前方", "入口").any(text::contains) -> .67f
        listOf("正中", "中央", "中间", "中心").any(text::contains) -> .48f
        else -> fallback.second
    }
    return x.coerceIn(.03f, .74f) to y.coerceIn(.08f, .72f)
}

private fun defaultFurniturePlacement(kind: DigitalFurnitureKind, index: Int): Pair<Float, Float> {
    val alternate = index % 2 == 1
    return when (kind) {
        DigitalFurnitureKind.BED -> (if (alternate) .48f else .08f) to .34f
        DigitalFurnitureKind.SOFA -> (if (alternate) .49f else .10f) to .40f
        DigitalFurnitureKind.RUG -> .27f to .67f
        DigitalFurnitureKind.COFFEE_TABLE -> .38f to .56f
        DigitalFurnitureKind.TABLE -> (if (alternate) .12f else .53f) to .43f
        DigitalFurnitureKind.DESK -> (if (alternate) .51f else .09f) to .39f
        DigitalFurnitureKind.CHAIR -> (if (alternate) .24f else .66f) to .54f
        DigitalFurnitureKind.SHELF -> (if (alternate) .70f else .04f) to .27f
        DigitalFurnitureKind.CABINET -> (if (alternate) .71f else .05f) to .29f
        DigitalFurnitureKind.NIGHTSTAND -> (if (alternate) .67f else .20f) to .40f
        DigitalFurnitureKind.FLOOR_LAMP -> (if (alternate) .80f else .05f) to .35f
        DigitalFurnitureKind.TABLE_LAMP -> (if (alternate) .66f else .24f) to .42f
        DigitalFurnitureKind.PLANT -> (if (alternate) .78f else .06f) to .49f
        DigitalFurnitureKind.TV -> .57f to .28f
        DigitalFurnitureKind.MIRROR -> (if (alternate) .72f else .12f) to .10f
        DigitalFurnitureKind.WALL_ART -> (if (alternate) .62f else .17f) to .10f
        DigitalFurnitureKind.CLOCK -> (if (alternate) .76f else .28f) to .10f
        DigitalFurnitureKind.CUSHION -> (if (alternate) .58f else .20f) to .57f
        DigitalFurnitureKind.BASKET -> (if (alternate) .69f else .10f) to .58f
        DigitalFurnitureKind.DECOR -> (if (alternate) .63f else .31f) to .50f
    }
}

internal fun stickerWidth(kind: DigitalFurnitureKind): Dp = when (kind) {
    DigitalFurnitureKind.BED -> 118.dp
    DigitalFurnitureKind.SOFA -> 108.dp
    DigitalFurnitureKind.RUG -> 124.dp
    DigitalFurnitureKind.COFFEE_TABLE -> 72.dp
    DigitalFurnitureKind.TABLE, DigitalFurnitureKind.DESK -> 80.dp
    DigitalFurnitureKind.SHELF, DigitalFurnitureKind.CABINET -> 64.dp
    DigitalFurnitureKind.NIGHTSTAND -> 50.dp
    DigitalFurnitureKind.CHAIR -> 48.dp
    DigitalFurnitureKind.FLOOR_LAMP -> 48.dp
    DigitalFurnitureKind.TABLE_LAMP, DigitalFurnitureKind.PLANT, DigitalFurnitureKind.DECOR -> 47.dp
    DigitalFurnitureKind.TV -> 86.dp
    DigitalFurnitureKind.MIRROR -> 54.dp
    DigitalFurnitureKind.WALL_ART -> 58.dp
    DigitalFurnitureKind.CLOCK -> 43.dp
    DigitalFurnitureKind.CUSHION -> 40.dp
    DigitalFurnitureKind.BASKET -> 48.dp
}

internal fun stickerHeight(kind: DigitalFurnitureKind): Dp = when (kind) {
    DigitalFurnitureKind.BED -> 80.dp
    DigitalFurnitureKind.SOFA -> 68.dp
    DigitalFurnitureKind.RUG -> 64.dp
    DigitalFurnitureKind.COFFEE_TABLE -> 48.dp
    DigitalFurnitureKind.TABLE, DigitalFurnitureKind.DESK -> 54.dp
    DigitalFurnitureKind.SHELF, DigitalFurnitureKind.CABINET -> 90.dp
    DigitalFurnitureKind.NIGHTSTAND -> 54.dp
    DigitalFurnitureKind.CHAIR -> 58.dp
    DigitalFurnitureKind.FLOOR_LAMP -> 96.dp
    DigitalFurnitureKind.TABLE_LAMP, DigitalFurnitureKind.PLANT, DigitalFurnitureKind.DECOR -> 60.dp
    DigitalFurnitureKind.TV -> 64.dp
    DigitalFurnitureKind.MIRROR -> 90.dp
    DigitalFurnitureKind.WALL_ART -> 70.dp
    DigitalFurnitureKind.CLOCK -> 43.dp
    DigitalFurnitureKind.CUSHION -> 40.dp
    DigitalFurnitureKind.BASKET -> 56.dp
}

private fun stickerColor(key: String): Color = when (key) {
    "sky" -> Color(0xFFB7CBD4)
    "sage" -> Color(0xFFB7C4B1)
    "wood" -> Color(0xFFC7AD8D)
    "walnut" -> Color(0xFF8A715F)
    "charcoal" -> Color(0xFF696B6B)
    "white" -> Color(0xFFF3F2EE)
    "warm" -> Color(0xFFE7C88E)
    "leaf" -> Color(0xFF8EAA89)
    "olive" -> Color(0xFF969E7E)
    "cactus" -> Color(0xFF82A67B)
    "rose" -> Color(0xFFD1B0AC)
    "latte" -> Color(0xFFC5AD98)
    "navy" -> Color(0xFF68788A)
    "glass" -> Color(0xFFC6D9DE)
    "rattan" -> Color(0xFFC5A47E)
    else -> Color(0xFFDED3C3)
}

private fun darken(color: Color, factor: Float): Color = Color(
    red = (color.red * factor).coerceIn(0f, 1f),
    green = (color.green * factor).coerceIn(0f, 1f),
    blue = (color.blue * factor).coerceIn(0f, 1f),
    alpha = color.alpha,
)

private fun lighten(color: Color, amount: Float): Color = Color(
    red = (color.red + (1f - color.red) * amount).coerceIn(0f, 1f),
    green = (color.green + (1f - color.green) * amount).coerceIn(0f, 1f),
    blue = (color.blue + (1f - color.blue) * amount).coerceIn(0f, 1f),
    alpha = color.alpha,
)
