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
    val width = if (preview) {
        if (style.kind == DigitalFurnitureKind.RUG) 78.dp else 72.dp
    } else {
        stickerWidth(style.kind)
    }
    val height = if (preview) {
        if (style.kind == DigitalFurnitureKind.RUG) 44.dp else 62.dp
    } else {
        stickerHeight(style.kind)
    }
    val base = stickerColor(style.colorKey)
    Box(modifier.size(width, height), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(width, height)) {
            drawFurnitureSticker(style, base)
        }
    }
}

private data class StickerPalette(
    val base: Color,
    val dark: Color,
    val darker: Color,
    val soft: Color,
    val pale: Color,
    val white: Color = Color(0xFFFFFEFB),
    val ink: Color = Color(0xFF554F49).copy(alpha = .78f),
    val shadow: Color = Color.Black.copy(alpha = .075f),
)

private fun DrawScope.drawFurnitureSticker(style: DigitalFurnitureStyle, base: Color) {
    val palette = StickerPalette(
        base = base,
        dark = darken(base, .76f),
        darker = darken(base, .58f),
        soft = lighten(base, .28f),
        pale = lighten(base, .55f),
    )
    when (style.kind) {
        DigitalFurnitureKind.BED -> drawBed(style, palette)
        DigitalFurnitureKind.SOFA -> drawSofa(style, palette)
        DigitalFurnitureKind.COFFEE_TABLE -> drawTableLike(style, palette, low = true, desk = false)
        DigitalFurnitureKind.TABLE -> drawTableLike(style, palette, low = false, desk = false)
        DigitalFurnitureKind.DESK -> drawTableLike(style, palette, low = false, desk = true)
        DigitalFurnitureKind.CHAIR -> drawChair(style, palette)
        DigitalFurnitureKind.SHELF -> drawShelf(style, palette)
        DigitalFurnitureKind.CABINET -> drawCabinet(style, palette, nightstand = false)
        DigitalFurnitureKind.NIGHTSTAND -> drawCabinet(style, palette, nightstand = true)
        DigitalFurnitureKind.FLOOR_LAMP -> drawFloorLamp(style, palette)
        DigitalFurnitureKind.TABLE_LAMP -> drawTableLamp(style, palette)
        DigitalFurnitureKind.RUG -> drawRug(style, palette)
        DigitalFurnitureKind.PLANT -> drawPlant(style, palette)
        DigitalFurnitureKind.TV -> drawTv(style, palette)
        DigitalFurnitureKind.MIRROR -> drawMirror(style, palette)
        DigitalFurnitureKind.WALL_ART -> drawWallArt(style, palette)
        DigitalFurnitureKind.CLOCK -> drawClock(palette)
        DigitalFurnitureKind.CUSHION -> drawCushion(style, palette)
        DigitalFurnitureKind.BASKET -> drawBasket(style, palette)
        DigitalFurnitureKind.DECOR -> drawDecor(style, palette)
    }
}

private fun DrawScope.drawGroundShadow(p: StickerPalette, left: Float = .10f, top: Float = .86f, width: Float = .80f, height: Float = .08f) {
    drawOval(p.shadow, Offset(size.width * left, size.height * top), Size(size.width * width, size.height * height))
}

private fun DrawScope.drawBed(style: DigitalFurnitureStyle, p: StickerPalette) {
    drawGroundShadow(p, .07f, .86f, .86f, .08f)
    val low = style.pattern == "low"
    val headTop = if (low) .20f else .08f
    val mattressTop = if (low) .36f else .31f
    if (style.pattern == "canopy") {
        val post = size.width * .026f
        listOf(.10f, .90f).forEach { x ->
            drawLine(p.dark, Offset(size.width * x, size.height * .05f), Offset(size.width * x, size.height * .91f), post)
        }
        drawLine(p.dark, Offset(size.width * .10f, size.height * .05f), Offset(size.width * .90f, size.height * .05f), post)
        drawLine(p.white.copy(alpha = .55f), Offset(size.width * .15f, size.height * .08f), Offset(size.width * .20f, size.height * .66f), 1.3.dp.toPx())
        drawLine(p.white.copy(alpha = .45f), Offset(size.width * .85f, size.height * .08f), Offset(size.width * .80f, size.height * .66f), 1.3.dp.toPx())
    }
    drawRoundRect(
        p.dark,
        Offset(size.width * .08f, size.height * headTop),
        Size(size.width * .84f, size.height * (if (low) .28f else .34f)),
        CornerRadius(size.height * .11f),
    )
    if (style.pattern == "cloud") {
        repeat(5) { index ->
            drawCircle(
                p.soft,
                size.width * .105f,
                Offset(size.width * (.23f + index * .135f), size.height * .25f),
            )
        }
    }
    drawRoundRect(
        p.darker,
        Offset(size.width * .06f, size.height * (mattressTop + .12f)),
        Size(size.width * .88f, size.height * .36f),
        CornerRadius(size.height * .075f),
    )
    drawRoundRect(
        p.pale,
        Offset(size.width * .10f, size.height * mattressTop),
        Size(size.width * .80f, size.height * .42f),
        CornerRadius(size.height * .085f),
    )
    drawRoundRect(
        p.white,
        Offset(size.width * .15f, size.height * (mattressTop - .03f)),
        Size(size.width * .29f, size.height * .17f),
        CornerRadius(size.height * .065f),
    )
    drawRoundRect(
        p.white,
        Offset(size.width * .56f, size.height * (mattressTop - .03f)),
        Size(size.width * .29f, size.height * .17f),
        CornerRadius(size.height * .065f),
    )
    drawRoundRect(
        p.soft,
        Offset(size.width * .12f, size.height * (mattressTop + .18f)),
        Size(size.width * .76f, size.height * .22f),
        CornerRadius(size.height * .055f),
    )
    when (style.pattern) {
        "stripe" -> repeat(4) { index ->
            val y = size.height * (mattressTop + .22f + index * .045f)
            drawLine(p.white.copy(alpha = .62f), Offset(size.width * .17f, y), Offset(size.width * .83f, y), 1.15.dp.toPx())
        }
        "check" -> {
            repeat(3) { index ->
                val x = size.width * (.28f + index * .18f)
                drawLine(p.white.copy(alpha = .45f), Offset(x, size.height * (mattressTop + .19f)), Offset(x, size.height * (mattressTop + .39f)), 1.dp.toPx())
            }
            repeat(2) { index ->
                val y = size.height * (mattressTop + .25f + index * .07f)
                drawLine(p.white.copy(alpha = .45f), Offset(size.width * .14f, y), Offset(size.width * .86f, y), 1.dp.toPx())
            }
        }
    }
    if (!low) {
        drawLine(p.ink.copy(alpha = .35f), Offset(size.width * .13f, size.height * .81f), Offset(size.width * .10f, size.height * .93f), 2.dp.toPx())
        drawLine(p.ink.copy(alpha = .35f), Offset(size.width * .87f, size.height * .81f), Offset(size.width * .90f, size.height * .93f), 2.dp.toPx())
    }
}

private fun DrawScope.drawSofa(style: DigitalFurnitureStyle, p: StickerPalette) {
    drawGroundShadow(p, .07f, .86f, .86f, .075f)
    val rounded = style.pattern == "boucle"
    val backRadius = if (rounded) size.height * .24f else size.height * .15f
    drawRoundRect(p.dark, Offset(size.width * .07f, size.height * .11f), Size(size.width * .86f, size.height * .53f), CornerRadius(backRadius))
    val seatCount = if (style.pattern == "modular") 3 else 2
    repeat(seatCount) { index ->
        val gap = .025f
        val usable = .78f
        val seatWidth = (usable - gap * (seatCount - 1)) / seatCount
        val x = .11f + index * (seatWidth + gap)
        drawRoundRect(
            p.soft,
            Offset(size.width * x, size.height * .19f),
            Size(size.width * seatWidth, size.height * .33f),
            CornerRadius(if (rounded) size.height * .14f else size.height * .09f),
        )
    }
    drawRoundRect(p.base, Offset(size.width * .07f, size.height * .51f), Size(size.width * .86f, size.height * .29f), CornerRadius(size.height * .12f))
    drawRoundRect(p.dark, Offset(0f, size.height * .43f), Size(size.width * .16f, size.height * .37f), CornerRadius(size.height * .10f))
    drawRoundRect(p.dark, Offset(size.width * .84f, size.height * .43f), Size(size.width * .16f, size.height * .37f), CornerRadius(size.height * .10f))
    repeat(seatCount - 1) { index ->
        val x = size.width * (.11f + (index + 1) * (.78f / seatCount))
        drawLine(p.white.copy(alpha = .42f), Offset(x, size.height * .54f), Offset(x, size.height * .75f), 1.1.dp.toPx())
    }
    drawLine(p.ink.copy(alpha = .32f), Offset(size.width * .18f, size.height * .79f), Offset(size.width * .17f, size.height * .92f), 2.dp.toPx())
    drawLine(p.ink.copy(alpha = .32f), Offset(size.width * .82f, size.height * .79f), Offset(size.width * .83f, size.height * .92f), 2.dp.toPx())
}

private fun DrawScope.drawTableLike(style: DigitalFurnitureStyle, p: StickerPalette, low: Boolean, desk: Boolean) {
    drawGroundShadow(p, .12f, .86f, .76f, .075f)
    val topY = if (low) .28f else .18f
    val topH = if (low) .27f else .24f
    val topColor = if (style.colorKey == "glass") Color(0xFFD9E8EC).copy(alpha = .72f) else p.soft
    when (style.pattern) {
        "round", "oval", "pebble" -> {
            val left = if (style.pattern == "pebble") .05f else .08f
            val width = if (style.pattern == "pebble") .90f else .84f
            drawOval(p.darker, Offset(size.width * left, size.height * (topY + .06f)), Size(size.width * width, size.height * topH))
            drawOval(topColor, Offset(size.width * left, size.height * topY), Size(size.width * width, size.height * topH))
        }
        else -> {
            // A floor-standing table is seen slightly from above: the back edge is narrower than the front.
            val underside = Path().apply {
                moveTo(size.width * .14f, size.height * (topY + .055f))
                lineTo(size.width * .86f, size.height * (topY + .055f))
                lineTo(size.width * .96f, size.height * (topY + topH + .05f))
                lineTo(size.width * .04f, size.height * (topY + topH + .05f))
                close()
            }
            val top = Path().apply {
                moveTo(size.width * .15f, size.height * topY)
                lineTo(size.width * .85f, size.height * topY)
                lineTo(size.width * .94f, size.height * (topY + topH))
                lineTo(size.width * .06f, size.height * (topY + topH))
                close()
            }
            drawPath(underside, p.darker)
            drawPath(top, topColor)
        }
    }
    if (style.colorKey == "glass") {
        drawLine(Color.White.copy(alpha = .82f), Offset(size.width * .20f, size.height * (topY + .06f)), Offset(size.width * .50f, size.height * (topY + .06f)), 1.2.dp.toPx())
    }
    val legTop = topY + topH
    val legBottom = if (low) .87f else .91f
    drawLine(p.dark, Offset(size.width * .22f, size.height * legTop), Offset(size.width * .17f, size.height * legBottom), size.width * .038f)
    drawLine(p.dark, Offset(size.width * .78f, size.height * legTop), Offset(size.width * .83f, size.height * legBottom), size.width * .038f)
    if (desk) {
        drawRoundRect(p.pale, Offset(size.width * .31f, size.height * (legTop + .05f)), Size(size.width * .38f, size.height * .17f), CornerRadius(4.dp.toPx()))
        drawCircle(p.darker, 1.8.dp.toPx(), Offset(size.width * .50f, size.height * (legTop + .135f)))
    }
}

private fun DrawScope.drawChair(style: DigitalFurnitureStyle, p: StickerPalette) {
    drawGroundShadow(p, .16f, .90f, .68f, .055f)
    if (style.pattern == "bench") {
        drawRoundRect(p.soft, Offset(size.width * .05f, size.height * .38f), Size(size.width * .90f, size.height * .25f), CornerRadius(size.height * .08f))
        drawLine(p.dark, Offset(size.width * .22f, size.height * .60f), Offset(size.width * .18f, size.height * .94f), size.width * .05f)
        drawLine(p.dark, Offset(size.width * .78f, size.height * .60f), Offset(size.width * .82f, size.height * .94f), size.width * .05f)
        return
    }
    val lounge = style.pattern == "lounge"
    val backLeft = if (lounge) .08f else .17f
    val backWidth = if (lounge) .84f else .66f
    drawRoundRect(p.dark, Offset(size.width * backLeft, size.height * .04f), Size(size.width * backWidth, size.height * .48f), CornerRadius(size.width * .18f))
    drawRoundRect(p.pale, Offset(size.width * (backLeft + .08f), size.height * .12f), Size(size.width * (backWidth - .16f), size.height * .29f), CornerRadius(size.width * .14f))
    drawRoundRect(p.base, Offset(size.width * .10f, size.height * .47f), Size(size.width * .80f, size.height * .25f), CornerRadius(size.width * .11f))
    if (lounge) {
        drawRoundRect(p.dark, Offset(size.width * .01f, size.height * .43f), Size(size.width * .18f, size.height * .31f), CornerRadius(size.width * .08f))
        drawRoundRect(p.dark, Offset(size.width * .81f, size.height * .43f), Size(size.width * .18f, size.height * .31f), CornerRadius(size.width * .08f))
    }
    drawLine(p.dark, Offset(size.width * .28f, size.height * .69f), Offset(size.width * .20f, size.height * .95f), size.width * .045f)
    drawLine(p.dark, Offset(size.width * .72f, size.height * .69f), Offset(size.width * .80f, size.height * .95f), size.width * .045f)
    if (style.pattern == "rattan") {
        repeat(3) { index ->
            val y = size.height * (.20f + index * .07f)
            drawLine(p.white.copy(alpha = .48f), Offset(size.width * .30f, y), Offset(size.width * .70f, y), 1.dp.toPx())
        }
    }
}

private fun DrawScope.drawShelf(style: DigitalFurnitureStyle, p: StickerPalette) {
    drawGroundShadow(p, .10f, .94f, .80f, .045f)
    if (style.pattern == "ladder") {
        drawLine(p.darker, Offset(size.width * .20f, size.height * .94f), Offset(size.width * .34f, size.height * .04f), 3.dp.toPx())
        drawLine(p.darker, Offset(size.width * .80f, size.height * .94f), Offset(size.width * .66f, size.height * .04f), 3.dp.toPx())
        repeat(4) { index ->
            val y = .18f + index * .20f
            val inset = .07f + index * .025f
            drawRoundRect(p.soft, Offset(size.width * (.18f + inset), size.height * y), Size(size.width * (.64f - inset * 2f), size.height * .07f), CornerRadius(3.dp.toPx()))
        }
        return
    }
    drawRoundRect(p.darker, size = Size(size.width, size.height * .94f), cornerRadius = CornerRadius(size.width * .08f))
    drawRoundRect(p.soft, Offset(size.width * .06f, size.height * .04f), Size(size.width * .88f, size.height * .85f), CornerRadius(size.width * .055f))
    repeat(3) { index ->
        val y = size.height * (.25f + index * .22f)
        drawLine(p.dark.copy(alpha = .52f), Offset(size.width * .08f, y), Offset(size.width * .92f, y), 1.6.dp.toPx())
    }
    repeat(7) { index ->
        val row = index / 4
        val col = index % 4
        drawRoundRect(
            listOf(p.base, p.pale, p.dark)[index % 3],
            Offset(size.width * (.12f + col * .19f), size.height * (.10f + row * .45f)),
            Size(size.width * .075f, size.height * (.12f + (index % 2) * .025f)),
            CornerRadius(1.5.dp.toPx()),
        )
    }
}

private fun DrawScope.drawCabinet(style: DigitalFurnitureStyle, p: StickerPalette, nightstand: Boolean) {
    drawGroundShadow(p, .12f, .91f, .76f, .05f)
    if (nightstand && style.pattern == "round") {
        drawOval(p.darker, Offset(size.width * .13f, size.height * .09f), Size(size.width * .74f, size.height * .22f))
        drawRoundRect(p.soft, Offset(size.width * .13f, size.height * .18f), Size(size.width * .74f, size.height * .59f), CornerRadius(size.width * .22f))
        drawOval(p.soft, Offset(size.width * .13f, size.height * .64f), Size(size.width * .74f, size.height * .20f))
        drawCircle(p.darker, size.width * .035f, Offset(size.width * .50f, size.height * .45f))
        return
    }
    val top = if (style.pattern == "tall") .02f else .08f
    val height = if (style.pattern == "tall") .88f else .76f
    drawRoundRect(p.darker, Offset(size.width * .04f, size.height * top), Size(size.width * .92f, size.height * height), CornerRadius(size.width * .10f))
    drawRoundRect(p.soft, Offset(size.width * .08f, size.height * (top + .035f)), Size(size.width * .84f, size.height * (height - .07f)), CornerRadius(size.width * .075f))
    if (style.pattern == "glassdoor") {
        drawRoundRect(Color(0xFFDDE9EC).copy(alpha = .55f), Offset(size.width * .14f, size.height * .15f), Size(size.width * .31f, size.height * .56f), CornerRadius(3.dp.toPx()))
        drawRoundRect(Color(0xFFDDE9EC).copy(alpha = .55f), Offset(size.width * .55f, size.height * .15f), Size(size.width * .31f, size.height * .56f), CornerRadius(3.dp.toPx()))
        repeat(2) { index ->
            val y = size.height * (.34f + index * .20f)
            drawLine(p.ink.copy(alpha = .28f), Offset(size.width * .14f, y), Offset(size.width * .86f, y), 1.dp.toPx())
        }
    } else if (style.pattern == "tall") {
        drawLine(p.ink.copy(alpha = .28f), Offset(size.width * .50f, size.height * .10f), Offset(size.width * .50f, size.height * .82f), 1.2.dp.toPx())
        drawCircle(p.darker, size.width * .027f, Offset(size.width * .45f, size.height * .46f))
        drawCircle(p.darker, size.width * .027f, Offset(size.width * .55f, size.height * .46f))
    } else {
        drawLine(p.ink.copy(alpha = .28f), Offset(size.width * .13f, size.height * .43f), Offset(size.width * .87f, size.height * .43f), 1.1.dp.toPx())
        drawCircle(p.darker, size.width * .028f, Offset(size.width * .50f, size.height * .30f))
        drawCircle(p.darker, size.width * .028f, Offset(size.width * .50f, size.height * .62f))
    }
    drawLine(p.dark, Offset(size.width * .20f, size.height * (top + height)), Offset(size.width * .18f, size.height * .95f), 2.dp.toPx())
    drawLine(p.dark, Offset(size.width * .80f, size.height * (top + height)), Offset(size.width * .82f, size.height * .95f), 2.dp.toPx())
}

private fun DrawScope.drawFloorLamp(style: DigitalFurnitureStyle, p: StickerPalette) {
    val glow = if (style.colorKey == "warm") Color(0xFFF4D89C) else p.pale
    drawCircle(glow.copy(alpha = .12f), size.width * .44f, Offset(size.width * .51f, size.height * .24f))
    when (style.pattern) {
        "tripod" -> {
            drawLine(p.ink, Offset(size.width * .50f, size.height * .36f), Offset(size.width * .20f, size.height * .91f), 2.2.dp.toPx())
            drawLine(p.ink, Offset(size.width * .50f, size.height * .36f), Offset(size.width * .80f, size.height * .91f), 2.2.dp.toPx())
            drawLine(p.ink, Offset(size.width * .50f, size.height * .36f), Offset(size.width * .50f, size.height * .91f), 1.8.dp.toPx())
        }
        else -> if (style.id == "lamp_arc") {
            val arc = Path().apply {
                moveTo(size.width * .28f, size.height * .88f)
                cubicTo(size.width * .26f, size.height * .34f, size.width * .49f, size.height * .13f, size.width * .73f, size.height * .19f)
            }
            drawPath(arc, p.ink, style = Stroke(size.width * .042f))
        } else {
            drawLine(p.ink, Offset(size.width * .50f, size.height * .34f), Offset(size.width * .50f, size.height * .88f), size.width * .043f)
        }
    }
    val shade = Path().apply {
        moveTo(size.width * .24f, size.height * .09f)
        lineTo(size.width * .76f, size.height * .09f)
        lineTo(size.width * .65f, size.height * .34f)
        lineTo(size.width * .35f, size.height * .34f)
        close()
    }
    drawPath(shade, glow)
    drawPath(shade, p.ink.copy(alpha = .36f), style = Stroke(1.dp.toPx()))
    if (style.pattern != "tripod") drawOval(p.ink, Offset(size.width * .24f, size.height * .86f), Size(size.width * .52f, size.height * .07f))
}

private fun DrawScope.drawTableLamp(style: DigitalFurnitureStyle, p: StickerPalette) {
    val glow = if (style.colorKey == "warm") Color(0xFFF5DCA6) else p.pale
    drawCircle(glow.copy(alpha = .14f), size.width * .41f, Offset(size.width * .50f, size.height * .31f))
    when {
        style.pattern == "orb" -> {
            drawCircle(glow, size.width * .29f, Offset(size.width * .50f, size.height * .37f))
            drawCircle(p.white.copy(alpha = .42f), size.width * .07f, Offset(size.width * .42f, size.height * .28f))
            drawRoundRect(p.ink, Offset(size.width * .36f, size.height * .67f), Size(size.width * .28f, size.height * .08f), CornerRadius(3.dp.toPx()))
        }
        style.id == "lamp_mushroom" -> {
            drawOval(glow, Offset(size.width * .15f, size.height * .12f), Size(size.width * .70f, size.height * .34f))
            drawRoundRect(p.dark, Offset(size.width * .42f, size.height * .42f), Size(size.width * .16f, size.height * .36f), CornerRadius(size.width * .06f))
            drawOval(p.ink, Offset(size.width * .27f, size.height * .77f), Size(size.width * .46f, size.height * .08f))
        }
        style.id == "lamp_glass" -> {
            drawCircle(Color(0xFFDCE9ED).copy(alpha = .75f), size.width * .27f, Offset(size.width * .50f, size.height * .33f))
            drawCircle(p.white.copy(alpha = .75f), size.width * .08f, Offset(size.width * .42f, size.height * .25f))
            drawLine(p.ink, Offset(size.width * .50f, size.height * .57f), Offset(size.width * .50f, size.height * .79f), 2.dp.toPx())
            drawOval(p.ink, Offset(size.width * .27f, size.height * .78f), Size(size.width * .46f, size.height * .08f))
        }
        else -> {
            val shade = Path().apply {
                moveTo(size.width * .23f, size.height * .13f)
                lineTo(size.width * .77f, size.height * .13f)
                lineTo(size.width * .65f, size.height * .46f)
                lineTo(size.width * .35f, size.height * .46f)
                close()
            }
            drawPath(shade, glow)
            drawPath(shade, p.ink.copy(alpha = .35f), style = Stroke(1.dp.toPx()))
            drawLine(p.ink, Offset(size.width * .50f, size.height * .46f), Offset(size.width * .50f, size.height * .80f), 2.dp.toPx())
            drawOval(p.ink, Offset(size.width * .26f, size.height * .79f), Size(size.width * .48f, size.height * .08f))
        }
    }
}

private fun DrawScope.drawRug(style: DigitalFurnitureStyle, p: StickerPalette) {
    val rug = lighten(p.base, .42f).copy(alpha = .84f)
    val topY = .19f
    val bottomY = .82f
    val topInset = .18f
    val bottomInset = .035f

    // A rug lives on the floor plane. The rear edge is narrower and the whole shape is vertically
    // foreshortened; this avoids the old "upright square sticker" look.
    val shadow = Path().apply {
        moveTo(size.width * (topInset - .015f), size.height * (topY + .04f))
        lineTo(size.width * (1f - topInset + .015f), size.height * (topY + .04f))
        lineTo(size.width * (1f - bottomInset), size.height * (bottomY + .055f))
        lineTo(size.width * bottomInset, size.height * (bottomY + .055f))
        close()
    }
    drawPath(shadow, Color.Black.copy(alpha = .045f))

    when (style.pattern) {
        "round" -> {
            drawOval(
                rug,
                Offset(size.width * .07f, size.height * .25f),
                Size(size.width * .86f, size.height * .50f),
            )
        }
        "cloud" -> {
            drawOval(rug, Offset(size.width * .06f, size.height * .36f), Size(size.width * .42f, size.height * .34f))
            drawOval(rug, Offset(size.width * .29f, size.height * .25f), Size(size.width * .42f, size.height * .43f))
            drawOval(rug, Offset(size.width * .54f, size.height * .37f), Size(size.width * .40f, size.height * .31f))
        }
        "flower" -> {
            repeat(6) { index ->
                val angle = Math.toRadians(index * 60.0)
                val cx = .50f + kotlin.math.cos(angle).toFloat() * .24f
                val cy = .50f + kotlin.math.sin(angle).toFloat() * .17f
                drawOval(
                    rug,
                    Offset(size.width * (cx - .17f), size.height * (cy - .16f)),
                    Size(size.width * .34f, size.height * .32f),
                )
            }
            drawOval(
                Color(0xFFE7C88E),
                Offset(size.width * .38f, size.height * .38f),
                Size(size.width * .24f, size.height * .24f),
            )
        }
        else -> {
            val floorShape = Path().apply {
                moveTo(size.width * topInset, size.height * topY)
                lineTo(size.width * (1f - topInset), size.height * topY)
                lineTo(size.width * (1f - bottomInset), size.height * bottomY)
                lineTo(size.width * bottomInset, size.height * bottomY)
                close()
            }
            drawPath(floorShape, rug)
        }
    }

    if (style.pattern == "stripe") {
        repeat(4) { index ->
            val t = (index + 1) / 5f
            val y = topY + (bottomY - topY) * t
            val inset = topInset + (bottomInset - topInset) * t
            drawLine(
                p.white.copy(alpha = .58f),
                Offset(size.width * (inset + .05f), size.height * y),
                Offset(size.width * (1f - inset - .05f), size.height * y),
                1.15.dp.toPx(),
            )
        }
    } else if (style.pattern == "check") {
        repeat(3) { index ->
            val fraction = .28f + index * .22f
            val topX = topInset + (1f - topInset * 2f) * fraction
            val bottomX = bottomInset + (1f - bottomInset * 2f) * fraction
            drawLine(
                p.white.copy(alpha = .42f),
                Offset(size.width * topX, size.height * (topY + .025f)),
                Offset(size.width * bottomX, size.height * (bottomY - .025f)),
                1.dp.toPx(),
            )
        }
        repeat(3) { index ->
            val t = .26f + index * .20f
            val y = topY + (bottomY - topY) * t
            val inset = topInset + (bottomInset - topInset) * t
            drawLine(
                p.white.copy(alpha = .42f),
                Offset(size.width * (inset + .025f), size.height * y),
                Offset(size.width * (1f - inset - .025f), size.height * y),
                1.dp.toPx(),
            )
        }
    }
}

private fun DrawScope.drawPlant(style: DigitalFurnitureStyle, p: StickerPalette) {
    drawGroundShadow(p, .18f, .90f, .64f, .045f)
    val pot = if (style.colorKey == "cactus") Color(0xFFD6B89A) else Color(0xFFC8A98A)
    drawRoundRect(pot, Offset(size.width * .28f, size.height * .65f), Size(size.width * .44f, size.height * .28f), CornerRadius(size.width * .10f))
    val leaf = when (style.colorKey) {
        "olive" -> Color(0xFF7C8E6B)
        "cactus" -> Color(0xFF78A176)
        else -> Color(0xFF6F9270)
    }
    when {
        style.id == "plant_cactus" -> {
            drawRoundRect(leaf, Offset(size.width * .40f, size.height * .14f), Size(size.width * .20f, size.height * .54f), CornerRadius(size.width * .10f))
            drawRoundRect(leaf, Offset(size.width * .26f, size.height * .34f), Size(size.width * .18f, size.height * .20f), CornerRadius(size.width * .09f))
            drawRoundRect(leaf, Offset(size.width * .56f, size.height * .28f), Size(size.width * .18f, size.height * .24f), CornerRadius(size.width * .09f))
        }
        style.pattern == "snake" -> repeat(5) { index ->
            val x = .28f + index * .11f
            val top = if (index % 2 == 0) .13f else .24f
            val blade = Path().apply {
                moveTo(size.width * x, size.height * .67f)
                lineTo(size.width * (x + .04f), size.height * top)
                lineTo(size.width * (x + .08f), size.height * .67f)
                close()
            }
            drawPath(blade, leaf)
        }
        style.id == "plant_olive" -> {
            drawLine(Color(0xFF756451), Offset(size.width * .50f, size.height * .66f), Offset(size.width * .50f, size.height * .14f), 2.dp.toPx())
            repeat(8) { index ->
                val row = index / 2
                val left = index % 2 == 0
                val cx = if (left) .34f else .54f
                val cy = .20f + row * .105f
                drawOval(leaf.copy(alpha = .88f), Offset(size.width * cx, size.height * cy), Size(size.width * .22f, size.height * .13f))
            }
        }
        else -> {
            val leaves = if (style.pattern == "ficus") {
                listOf(Offset(.18f, .17f), Offset(.34f, .05f), Offset(.48f, .15f), Offset(.56f, .02f), Offset(.63f, .23f), Offset(.28f, .31f))
            } else {
                listOf(Offset(.16f, .20f), Offset(.31f, .08f), Offset(.48f, .16f), Offset(.55f, .04f), Offset(.63f, .25f), Offset(.29f, .31f))
            }
            leaves.forEachIndexed { index, point ->
                drawOval(leaf.copy(alpha = .90f - index * .035f), Offset(size.width * point.x, size.height * point.y), Size(size.width * .28f, size.height * .37f))
            }
        }
    }
}

private fun DrawScope.drawTv(style: DigitalFurnitureStyle, p: StickerPalette) {
    drawGroundShadow(p, .15f, .91f, .70f, .045f)
    val retro = style.pattern == "retro"
    val frameLeft = if (retro) .10f else .05f
    val frameWidth = if (retro) .80f else .90f
    drawRoundRect(p.darker, Offset(size.width * frameLeft, size.height * .09f), Size(size.width * frameWidth, size.height * .63f), CornerRadius(size.height * (if (retro) .09f else .055f)))
    drawRoundRect(Color(0xFF303639), Offset(size.width * (frameLeft + .045f), size.height * .13f), Size(size.width * (frameWidth - .09f), size.height * .51f), CornerRadius(size.height * .04f))
    drawRoundRect(Color(0xFFB5CAD3).copy(alpha = .20f), Offset(size.width * .19f, size.height * .18f), Size(size.width * .30f, size.height * .13f), CornerRadius(size.height * .025f))
    if (retro) {
        drawLine(p.dark, Offset(size.width * .37f, size.height * .08f), Offset(size.width * .28f, size.height * .01f), 1.5.dp.toPx())
        drawLine(p.dark, Offset(size.width * .63f, size.height * .08f), Offset(size.width * .72f, size.height * .01f), 1.5.dp.toPx())
        drawLine(p.dark, Offset(size.width * .26f, size.height * .72f), Offset(size.width * .23f, size.height * .89f), 2.dp.toPx())
        drawLine(p.dark, Offset(size.width * .74f, size.height * .72f), Offset(size.width * .77f, size.height * .89f), 2.dp.toPx())
    } else {
        drawLine(p.dark, Offset(size.width * .50f, size.height * .72f), Offset(size.width * .50f, size.height * .85f), 2.3.dp.toPx())
        drawRoundRect(p.dark, Offset(size.width * .29f, size.height * .84f), Size(size.width * .42f, size.height * .07f), CornerRadius(4.dp.toPx()))
    }
}

private fun DrawScope.drawMirror(style: DigitalFurnitureStyle, p: StickerPalette) {
    val glass = Color(0xFFE7F0F2)
    val frame = Color(0xFFB9AEA5)
    when (style.pattern) {
        "round" -> {
            drawOval(glass, Offset(size.width * .09f, size.height * .08f), Size(size.width * .82f, size.height * .80f))
            drawOval(frame, Offset(size.width * .09f, size.height * .08f), Size(size.width * .82f, size.height * .80f), style = Stroke(2.dp.toPx()))
        }
        "wave" -> {
            val wave = Path().apply {
                moveTo(size.width * .38f, size.height * .04f)
                cubicTo(size.width * .75f, size.height * .01f, size.width * .90f, size.height * .24f, size.width * .76f, size.height * .43f)
                cubicTo(size.width * .92f, size.height * .65f, size.width * .67f, size.height * .96f, size.width * .40f, size.height * .91f)
                cubicTo(size.width * .11f, size.height * .96f, size.width * .05f, size.height * .63f, size.width * .20f, size.height * .46f)
                cubicTo(size.width * .06f, size.height * .25f, size.width * .14f, size.height * .07f, size.width * .38f, size.height * .04f)
                close()
            }
            drawPath(wave, glass)
            drawPath(wave, frame, style = Stroke(2.dp.toPx()))
        }
        else -> {
            drawRoundRect(glass, Offset(size.width * .14f, size.height * .04f), Size(size.width * .72f, size.height * .90f), CornerRadius(size.width * .34f))
            drawRoundRect(frame, Offset(size.width * .14f, size.height * .04f), Size(size.width * .72f, size.height * .90f), CornerRadius(size.width * .34f), style = Stroke(2.dp.toPx()))
        }
    }
    drawLine(Color.White.copy(alpha = .82f), Offset(size.width * .30f, size.height * .18f), Offset(size.width * .55f, size.height * .50f), 1.3.dp.toPx())
}

private fun DrawScope.drawWallArt(style: DigitalFurnitureStyle, p: StickerPalette) {
    drawRoundRect(p.darker, size = size, cornerRadius = CornerRadius(5.dp.toPx()))
    drawRect(Color(0xFFF8F5EF), Offset(size.width * .07f, size.height * .07f), Size(size.width * .86f, size.height * .86f))
    when (style.pattern) {
        "botanical" -> {
            drawLine(Color(0xFF6F9270), Offset(size.width * .49f, size.height * .75f), Offset(size.width * .50f, size.height * .24f), 1.4.dp.toPx())
            repeat(4) { index ->
                val y = .28f + index * .11f
                drawOval(Color(0xFF9EBA93), Offset(size.width * .27f, size.height * y), Size(size.width * .21f, size.height * .10f))
                drawOval(Color(0xFF7FA174), Offset(size.width * .51f, size.height * (y + .035f)), Size(size.width * .20f, size.height * .10f))
            }
        }
        "sunset" -> {
            drawCircle(Color(0xFFE0A487), size.minDimension * .17f, Offset(size.width * .60f, size.height * .38f))
            drawLine(Color(0xFFC9B99F), Offset(size.width * .18f, size.height * .68f), Offset(size.width * .82f, size.height * .68f), 1.3.dp.toPx())
            drawLine(Color(0xFFB99D84), Offset(size.width * .24f, size.height * .57f), Offset(size.width * .48f, size.height * .72f), 1.2.dp.toPx())
        }
        else -> if (style.id == "art_landscape") {
            val hills = Path().apply {
                moveTo(size.width * .14f, size.height * .67f)
                lineTo(size.width * .37f, size.height * .43f)
                lineTo(size.width * .53f, size.height * .59f)
                lineTo(size.width * .69f, size.height * .37f)
                lineTo(size.width * .86f, size.height * .67f)
            }
            drawPath(hills, p.dark.copy(alpha = .55f), style = Stroke(1.5.dp.toPx()))
        } else {
            drawLine(p.ink.copy(alpha = .42f), Offset(size.width * .27f, size.height * .70f), Offset(size.width * .67f, size.height * .28f), 1.4.dp.toPx())
            drawCircle(p.ink.copy(alpha = .40f), size.minDimension * .10f, Offset(size.width * .39f, size.height * .40f), style = Stroke(1.4.dp.toPx()))
        }
    }
}

private fun DrawScope.drawClock(p: StickerPalette) {
    drawCircle(p.white, size.minDimension * .41f, center)
    drawCircle(p.dark, size.minDimension * .41f, center, style = Stroke(2.dp.toPx()))
    repeat(12) { index ->
        val angle = Math.toRadians(index * 30.0 - 90.0)
        val outer = Offset(
            center.x + kotlin.math.cos(angle).toFloat() * size.minDimension * .33f,
            center.y + kotlin.math.sin(angle).toFloat() * size.minDimension * .33f,
        )
        drawCircle(p.dark.copy(alpha = .55f), 1.dp.toPx(), outer)
    }
    drawLine(p.darker, center, Offset(center.x, center.y - size.height * .19f), 1.5.dp.toPx())
    drawLine(p.darker, center, Offset(center.x + size.width * .15f, center.y + size.height * .08f), 1.5.dp.toPx())
    drawCircle(p.darker, 2.dp.toPx(), center)
}

private fun DrawScope.drawCushion(style: DigitalFurnitureStyle, p: StickerPalette) {
    when (style.pattern) {
        "round" -> {
            drawCircle(p.soft, size.minDimension * .42f, center)
            drawCircle(p.ink.copy(alpha = .14f), size.minDimension * .38f, center, style = Stroke(1.dp.toPx()))
            drawCircle(p.white.copy(alpha = .68f), 2.dp.toPx(), center)
        }
        "cloud" -> {
            drawOval(p.soft, Offset(size.width * .05f, size.height * .28f), Size(size.width * .42f, size.height * .51f))
            drawOval(p.soft, Offset(size.width * .29f, size.height * .12f), Size(size.width * .43f, size.height * .64f))
            drawOval(p.soft, Offset(size.width * .54f, size.height * .30f), Size(size.width * .40f, size.height * .48f))
        }
        else -> {
            drawRoundRect(p.soft, Offset(size.width * .08f, size.height * .10f), Size(size.width * .84f, size.height * .78f), CornerRadius(size.width * .20f))
            drawRoundRect(p.ink.copy(alpha = .14f), Offset(size.width * .12f, size.height * .14f), Size(size.width * .76f, size.height * .70f), CornerRadius(size.width * .18f), style = Stroke(1.dp.toPx()))
            if (style.pattern == "stripe") repeat(3) { index ->
                val y = size.height * (.31f + index * .16f)
                drawLine(p.white.copy(alpha = .58f), Offset(size.width * .22f, y), Offset(size.width * .78f, y), 1.dp.toPx())
            }
            drawCircle(p.white.copy(alpha = .68f), 2.dp.toPx(), center)
        }
    }
}

private fun DrawScope.drawBasket(style: DigitalFurnitureStyle, p: StickerPalette) {
    drawGroundShadow(p, .15f, .90f, .70f, .045f)
    drawRoundRect(p.soft, Offset(size.width * .09f, size.height * .31f), Size(size.width * .82f, size.height * .59f), CornerRadius(size.width * .12f))
    if (style.pattern == "wire") {
        repeat(4) { index ->
            val x = size.width * (.20f + index * .20f)
            drawLine(p.dark.copy(alpha = .50f), Offset(x, size.height * .35f), Offset(x, size.height * .86f), 1.dp.toPx())
        }
        repeat(3) { index ->
            val y = size.height * (.47f + index * .14f)
            drawLine(p.dark.copy(alpha = .50f), Offset(size.width * .13f, y), Offset(size.width * .87f, y), 1.dp.toPx())
        }
    } else {
        repeat(3) { index ->
            val y = size.height * (.45f + index * .13f)
            drawLine(p.dark.copy(alpha = .24f), Offset(size.width * .15f, y), Offset(size.width * .85f, y), 1.dp.toPx())
        }
    }
    val handle = Path().apply {
        moveTo(size.width * .29f, size.height * .37f)
        cubicTo(size.width * .31f, size.height * .08f, size.width * .69f, size.height * .08f, size.width * .71f, size.height * .37f)
    }
    drawPath(handle, p.dark.copy(alpha = .66f), style = Stroke(2.dp.toPx()))
}

private fun DrawScope.drawDecor(style: DigitalFurnitureStyle, p: StickerPalette) {
    when (style.pattern) {
        "vase" -> {
            drawOval(Color(0xFFD8E7EA).copy(alpha = .68f), Offset(size.width * .26f, size.height * .25f), Size(size.width * .48f, size.height * .62f))
            drawRoundRect(Color(0xFFD8E7EA).copy(alpha = .72f), Offset(size.width * .39f, size.height * .12f), Size(size.width * .22f, size.height * .27f), CornerRadius(size.width * .08f))
            drawOval(Color.White.copy(alpha = .72f), Offset(size.width * .37f, size.height * .30f), Size(size.width * .10f, size.height * .40f))
        }
        "books" -> {
            drawRoundRect(Color(0xFF9A7865), Offset(size.width * .14f, size.height * .60f), Size(size.width * .72f, size.height * .18f), CornerRadius(3.dp.toPx()))
            drawRoundRect(Color(0xFFB7C4B1), Offset(size.width * .20f, size.height * .43f), Size(size.width * .64f, size.height * .17f), CornerRadius(3.dp.toPx()))
            drawRoundRect(Color(0xFFD1B0AC), Offset(size.width * .12f, size.height * .26f), Size(size.width * .68f, size.height * .17f), CornerRadius(3.dp.toPx()))
            repeat(3) { index ->
                val y = size.height * (.33f + index * .17f)
                drawLine(p.white.copy(alpha = .70f), Offset(size.width * .22f, y), Offset(size.width * .61f, y), 1.dp.toPx())
            }
        }
        "candle" -> {
            val xs = listOf(.25f to .50f, .43f to .35f, .61f to .46f)
            xs.forEach { (x, top) ->
                drawRoundRect(p.pale, Offset(size.width * x, size.height * top), Size(size.width * .18f, size.height * (.82f - top)), CornerRadius(size.width * .06f))
                val flame = Path().apply {
                    moveTo(size.width * (x + .09f), size.height * (top - .18f))
                    cubicTo(size.width * (x - .01f), size.height * (top - .04f), size.width * (x + .19f), size.height * (top - .04f), size.width * (x + .09f), size.height * (top - .18f))
                    close()
                }
                drawPath(flame, Color(0xFFF0B76D))
            }
        }
        "record" -> {
            drawRoundRect(p.dark, Offset(size.width * .08f, size.height * .32f), Size(size.width * .84f, size.height * .50f), CornerRadius(size.width * .08f))
            drawCircle(Color(0xFF303234), size.minDimension * .22f, Offset(size.width * .45f, size.height * .56f))
            drawCircle(Color(0xFFD1B0AC), size.minDimension * .055f, Offset(size.width * .45f, size.height * .56f))
            drawLine(p.white.copy(alpha = .72f), Offset(size.width * .68f, size.height * .42f), Offset(size.width * .57f, size.height * .64f), 1.4.dp.toPx())
            drawCircle(p.white.copy(alpha = .72f), 2.dp.toPx(), Offset(size.width * .69f, size.height * .40f))
        }
        "musicbox" -> {
            drawRoundRect(p.soft, Offset(size.width * .14f, size.height * .42f), Size(size.width * .72f, size.height * .39f), CornerRadius(size.width * .08f))
            drawRoundRect(p.dark, Offset(size.width * .20f, size.height * .31f), Size(size.width * .60f, size.height * .15f), CornerRadius(size.width * .06f))
            drawCircle(p.darker, 2.dp.toPx(), Offset(size.width * .50f, size.height * .61f))
            drawLine(p.darker, Offset(size.width * .86f, size.height * .54f), Offset(size.width * .96f, size.height * .48f), 1.4.dp.toPx())
            drawCircle(p.darker, 2.dp.toPx(), Offset(size.width * .97f, size.height * .47f))
        }
        "sculpture" -> {
            drawOval(p.soft, Offset(size.width * .20f, size.height * .55f), Size(size.width * .60f, size.height * .24f))
            drawCircle(p.dark, size.minDimension * .19f, Offset(size.width * .40f, size.height * .43f))
            drawCircle(p.pale, size.minDimension * .16f, Offset(size.width * .62f, size.height * .36f))
        }
        else -> {
            drawCircle(p.soft, size.minDimension * .34f, center)
            drawCircle(p.white.copy(alpha = .68f), size.minDimension * .13f, center)
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
    val rug = kind == DigitalFurnitureKind.RUG

    val x = when {
        listOf("最左", "左侧", "靠左", "左边").any(text::contains) -> if (rug) .10f else .06f
        listOf("最右", "右侧", "靠右", "右边").any(text::contains) -> if (rug) .58f else .72f
        listOf("正中", "中央", "中间", "中心").any(text::contains) -> if (rug) .28f else .38f
        else -> fallback.first
    }
    val y = when {
        kind in setOf(DigitalFurnitureKind.WALL_ART, DigitalFurnitureKind.CLOCK, DigitalFurnitureKind.MIRROR) -> .10f
        rug -> .69f
        listOf("上方", "里面", "后方", "靠墙", "墙边", "窗边").any(text::contains) -> .30f
        listOf("下方", "门边", "前方", "入口").any(text::contains) -> .67f
        listOf("正中", "中央", "中间", "中心").any(text::contains) -> .48f
        else -> fallback.second
    }
    val maxX = if (rug) .62f else .74f
    val minY = if (rug) .62f else .08f
    return x.coerceIn(.03f, maxX) to y.coerceIn(minY, .72f)
}

private fun defaultFurniturePlacement(kind: DigitalFurnitureKind, index: Int): Pair<Float, Float> {
    val alternate = index % 2 == 1
    return when (kind) {
        DigitalFurnitureKind.BED -> (if (alternate) .48f else .08f) to .34f
        DigitalFurnitureKind.SOFA -> (if (alternate) .49f else .10f) to .40f
        DigitalFurnitureKind.RUG -> .28f to .69f
        DigitalFurnitureKind.COFFEE_TABLE -> .38f to .56f
        DigitalFurnitureKind.TABLE -> (if (alternate) .12f else .53f) to .43f
        DigitalFurnitureKind.DESK -> (if (alternate) .51f else .09f) to .39f
        DigitalFurnitureKind.CHAIR -> (if (alternate) .24f else .66f) to .54f
        DigitalFurnitureKind.SHELF -> (if (alternate) .70f else .04f) to .27f
        DigitalFurnitureKind.CABINET -> (if (alternate) .71f else .05f) to .29f
        DigitalFurnitureKind.NIGHTSTAND -> (if (alternate) .67f else .20f) to .40f
        DigitalFurnitureKind.FLOOR_LAMP -> (if (alternate) .80f else .05f) to .35f
        DigitalFurnitureKind.TABLE_LAMP -> (if (alternate) .66f else .24f) to .40f
        DigitalFurnitureKind.PLANT -> (if (alternate) .78f else .06f) to .49f
        DigitalFurnitureKind.TV -> .57f to .28f
        DigitalFurnitureKind.MIRROR -> (if (alternate) .72f else .12f) to .10f
        DigitalFurnitureKind.WALL_ART -> (if (alternate) .62f else .17f) to .10f
        DigitalFurnitureKind.CLOCK -> (if (alternate) .76f else .28f) to .10f
        DigitalFurnitureKind.CUSHION -> (if (alternate) .58f else .20f) to .55f
        DigitalFurnitureKind.BASKET -> (if (alternate) .69f else .10f) to .58f
        DigitalFurnitureKind.DECOR -> (if (alternate) .63f else .31f) to .48f
    }
}

internal fun stickerWidth(kind: DigitalFurnitureKind): Dp = when (kind) {
    DigitalFurnitureKind.BED -> 118.dp
    DigitalFurnitureKind.SOFA -> 108.dp
    DigitalFurnitureKind.RUG -> 128.dp
    DigitalFurnitureKind.COFFEE_TABLE -> 72.dp
    DigitalFurnitureKind.TABLE, DigitalFurnitureKind.DESK -> 80.dp
    DigitalFurnitureKind.SHELF, DigitalFurnitureKind.CABINET -> 64.dp
    DigitalFurnitureKind.NIGHTSTAND -> 50.dp
    DigitalFurnitureKind.CHAIR -> 52.dp
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
    DigitalFurnitureKind.BED -> 82.dp
    DigitalFurnitureKind.SOFA -> 70.dp
    DigitalFurnitureKind.RUG -> 44.dp
    DigitalFurnitureKind.COFFEE_TABLE -> 48.dp
    DigitalFurnitureKind.TABLE, DigitalFurnitureKind.DESK -> 54.dp
    DigitalFurnitureKind.SHELF, DigitalFurnitureKind.CABINET -> 90.dp
    DigitalFurnitureKind.NIGHTSTAND -> 54.dp
    DigitalFurnitureKind.CHAIR -> 60.dp
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
