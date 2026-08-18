package com.jiacimu.lulu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material3.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.*
import com.jiacimu.lulu.design.LuluColors
import java.time.Instant
import kotlin.math.absoluteValue

@Composable
internal fun FurnitureSticker(
    item: DigitalWorldItem,
    style: DigitalFurnitureStyle,
    modifier: Modifier = Modifier,
    preview: Boolean = false,
) {
    val width = if (preview) 68.dp else stickerWidth(style.kind)
    val height = if (preview) 58.dp else stickerHeight(style.kind)
    val base = stickerColor(style.colorKey)
    Box(modifier.size(width, height), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) { drawFurnitureSticker(style, base) }
        if (preview) {
            Text(
                item.name,
                fontSize = 7.5.sp,
                color = Color(0xFF4A4743),
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color.White.copy(alpha = .86f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }
    }
}

private fun DrawScope.drawFurnitureSticker(style: DigitalFurnitureStyle, base: Color) {
    val dark = darken(base)
    val darker = darken(dark)
    val white = Color(0xFFFFFEFB)
    val line = Color(0xFF514C46).copy(alpha = .52f)

    when (style.kind) {
        DigitalFurnitureKind.BED -> {
            drawOval(
                Color.Black.copy(alpha = .10f),
                topLeft = Offset(size.width * .08f, size.height * .78f),
                size = Size(size.width * .84f, size.height * .15f),
            )
            drawRoundRect(
                dark,
                topLeft = Offset(size.width * .03f, size.height * .08f),
                size = Size(size.width * .94f, size.height * .34f),
                cornerRadius = CornerRadius(size.height * .11f),
            )
            drawRoundRect(
                darker,
                topLeft = Offset(size.width * .08f, size.height * .34f),
                size = Size(size.width * .84f, size.height * .55f),
                cornerRadius = CornerRadius(size.height * .10f),
            )
            drawRoundRect(
                base,
                topLeft = Offset(size.width * .10f, size.height * .28f),
                size = Size(size.width * .80f, size.height * .52f),
                cornerRadius = CornerRadius(size.height * .10f),
            )
            drawRoundRect(
                white,
                topLeft = Offset(size.width * .14f, size.height * .25f),
                size = Size(size.width * .29f, size.height * .20f),
                cornerRadius = CornerRadius(size.height * .08f),
            )
            drawRoundRect(
                white,
                topLeft = Offset(size.width * .57f, size.height * .25f),
                size = Size(size.width * .29f, size.height * .20f),
                cornerRadius = CornerRadius(size.height * .08f),
            )
            when (style.pattern) {
                "stripe" -> repeat(5) { index ->
                    val y = size.height * (.51f + index * .065f)
                    drawLine(white.copy(alpha = .58f), Offset(size.width * .13f, y), Offset(size.width * .87f, y), strokeWidth = 2.3f)
                }
                "check" -> {
                    repeat(4) { index ->
                        val x = size.width * (.18f + index * .18f)
                        drawLine(white.copy(alpha = .44f), Offset(x, size.height * .48f), Offset(x, size.height * .78f), strokeWidth = 1.8f)
                    }
                    repeat(3) { index ->
                        val y = size.height * (.53f + index * .10f)
                        drawLine(white.copy(alpha = .44f), Offset(size.width * .11f, y), Offset(size.width * .89f, y), strokeWidth = 1.8f)
                    }
                }
            }
            drawLine(line, Offset(size.width * .12f, size.height * .80f), Offset(size.width * .09f, size.height * .94f), strokeWidth = 3f)
            drawLine(line, Offset(size.width * .88f, size.height * .80f), Offset(size.width * .91f, size.height * .94f), strokeWidth = 3f)
        }

        DigitalFurnitureKind.SOFA -> {
            drawOval(Color.Black.copy(alpha = .08f), Offset(size.width * .08f, size.height * .78f), Size(size.width * .84f, size.height * .16f))
            drawRoundRect(dark, Offset(size.width * .06f, size.height * .08f), Size(size.width * .88f, size.height * .46f), CornerRadius(size.height * .15f))
            drawRoundRect(base, Offset(size.width * .02f, size.height * .35f), Size(size.width * .96f, size.height * .50f), CornerRadius(size.height * .17f))
            drawRoundRect(base.copy(alpha = .95f), Offset(0f, size.height * .43f), Size(size.width * .15f, size.height * .34f), CornerRadius(size.height * .10f))
            drawRoundRect(base.copy(alpha = .95f), Offset(size.width * .85f, size.height * .43f), Size(size.width * .15f, size.height * .34f), CornerRadius(size.height * .10f))
            drawLine(white.copy(alpha = .55f), Offset(size.width / 2f, size.height * .45f), Offset(size.width / 2f, size.height * .79f), strokeWidth = 2f)
        }

        DigitalFurnitureKind.COFFEE_TABLE,
        DigitalFurnitureKind.TABLE,
        DigitalFurnitureKind.DESK -> {
            drawOval(Color.Black.copy(alpha = .07f), Offset(size.width * .10f, size.height * .77f), Size(size.width * .80f, size.height * .15f))
            drawRoundRect(base, size = Size(size.width, size.height * .59f), cornerRadius = CornerRadius(size.height * .10f))
            if (style.colorKey == "glass") {
                drawRoundRect(Color.White.copy(alpha = .34f), Offset(size.width * .06f, size.height * .06f), Size(size.width * .88f, size.height * .44f), CornerRadius(size.height * .08f), style = Stroke(2f))
            }
            drawLine(dark, Offset(size.width * .19f, size.height * .54f), Offset(size.width * .14f, size.height), strokeWidth = size.width * .045f)
            drawLine(dark, Offset(size.width * .81f, size.height * .54f), Offset(size.width * .86f, size.height), strokeWidth = size.width * .045f)
        }

        DigitalFurnitureKind.CHAIR -> {
            drawRoundRect(dark, Offset(size.width * .08f, 0f), Size(size.width * .84f, size.height * .46f), CornerRadius(size.height * .10f))
            drawRoundRect(base, Offset(0f, size.height * .36f), Size(size.width, size.height * .34f), CornerRadius(size.height * .09f))
            drawLine(dark, Offset(size.width * .20f, size.height * .62f), Offset(size.width * .14f, size.height), strokeWidth = size.width * .055f)
            drawLine(dark, Offset(size.width * .80f, size.height * .62f), Offset(size.width * .86f, size.height), strokeWidth = size.width * .055f)
        }

        DigitalFurnitureKind.SHELF -> {
            drawRoundRect(dark, size = size, cornerRadius = CornerRadius(size.width * .10f))
            drawRoundRect(base, Offset(size.width * .07f, size.height * .04f), Size(size.width * .86f, size.height * .91f), CornerRadius(size.width * .07f))
            repeat(4) { index ->
                val y = size.height * (index + 1) / 5f
                drawLine(white.copy(alpha = .70f), Offset(size.width * .08f, y), Offset(size.width * .92f, y), strokeWidth = 2f)
            }
            repeat(5) { index ->
                val x = size.width * (.14f + (index % 3) * .22f)
                val y = size.height * (.11f + (index / 3) * .38f)
                drawRoundRect(darker.copy(alpha = .62f), Offset(x, y), Size(size.width * .12f, size.height * .16f), CornerRadius(2f))
            }
        }

        DigitalFurnitureKind.CABINET,
        DigitalFurnitureKind.NIGHTSTAND -> {
            drawOval(Color.Black.copy(alpha = .07f), Offset(size.width * .10f, size.height * .84f), Size(size.width * .80f, size.height * .12f))
            drawRoundRect(base, size = Size(size.width, size.height * .88f), cornerRadius = CornerRadius(size.width * .12f))
            drawLine(white.copy(alpha = .62f), Offset(size.width * .08f, size.height * .45f), Offset(size.width * .92f, size.height * .45f), strokeWidth = 2f)
            drawCircle(darker, radius = size.width * .035f, center = Offset(size.width * .50f, size.height * .30f))
            drawCircle(darker, radius = size.width * .035f, center = Offset(size.width * .50f, size.height * .62f))
            drawLine(dark, Offset(size.width * .17f, size.height * .84f), Offset(size.width * .15f, size.height), strokeWidth = 3f)
            drawLine(dark, Offset(size.width * .83f, size.height * .84f), Offset(size.width * .85f, size.height), strokeWidth = 3f)
        }

        DigitalFurnitureKind.FLOOR_LAMP -> {
            val glow = if (style.colorKey == "warm") Color(0xFFFFD998) else base
            drawCircle(glow.copy(alpha = .16f), radius = size.width * .47f, center = Offset(size.width * .52f, size.height * .24f))
            if (style.id == "lamp_arc") {
                val arc = Path().apply {
                    moveTo(size.width * .32f, size.height * .86f)
                    cubicTo(size.width * .32f, size.height * .28f, size.width * .58f, size.height * .12f, size.width * .72f, size.height * .20f)
                }
                drawPath(arc, dark, style = Stroke(size.width * .055f))
            } else {
                drawLine(dark, Offset(size.width * .50f, size.height * .35f), Offset(size.width * .50f, size.height * .86f), strokeWidth = size.width * .055f)
            }
            val shade = Path().apply {
                moveTo(size.width * .24f, size.height * .10f)
                lineTo(size.width * .76f, size.height * .10f)
                lineTo(size.width * .66f, size.height * .34f)
                lineTo(size.width * .34f, size.height * .34f)
                close()
            }
            drawPath(shade, glow.copy(alpha = .94f))
            drawLine(darker.copy(alpha = .45f), Offset(size.width * .27f, size.height * .31f), Offset(size.width * .73f, size.height * .31f), strokeWidth = 1.5f)
            drawOval(dark, Offset(size.width * .23f, size.height * .84f), Size(size.width * .54f, size.height * .10f))
            if (style.pattern == "sparkle") {
                repeat(5) { i ->
                    val x = size.width * (.30f + (i % 3) * .20f)
                    val y = size.height * (.14f + (i / 3) * .10f)
                    drawCircle(Color.White.copy(alpha = .85f), radius = 1.5f + i % 2, center = Offset(x, y))
                }
            }
        }

        DigitalFurnitureKind.TABLE_LAMP -> {
            val glow = if (style.colorKey == "warm") Color(0xFFFFD998) else base
            drawCircle(glow.copy(alpha = .14f), radius = size.width * .46f, center = Offset(size.width * .50f, size.height * .31f))
            if (style.id == "lamp_mushroom") {
                drawOval(base, Offset(size.width * .15f, size.height * .10f), Size(size.width * .70f, size.height * .40f))
                drawRoundRect(dark, Offset(size.width * .42f, size.height * .45f), Size(size.width * .16f, size.height * .36f), CornerRadius(size.width * .07f))
            } else {
                val shade = Path().apply {
                    moveTo(size.width * .23f, size.height * .12f)
                    lineTo(size.width * .77f, size.height * .12f)
                    lineTo(size.width * .66f, size.height * .47f)
                    lineTo(size.width * .34f, size.height * .47f)
                    close()
                }
                drawPath(shade, if (style.colorKey == "glass") Color.White.copy(alpha = .42f) else glow)
                if (style.colorKey == "glass") drawPath(shade, dark, style = Stroke(1.6f))
                drawLine(dark, Offset(size.width / 2f, size.height * .47f), Offset(size.width / 2f, size.height * .82f), strokeWidth = size.width * .055f)
            }
            drawOval(dark, Offset(size.width * .25f, size.height * .80f), Size(size.width * .50f, size.height * .11f))
        }

        DigitalFurnitureKind.RUG -> {
            val rugColor = base.copy(alpha = .72f)
            if (style.pattern == "round") {
                drawOval(rugColor, size = size)
            } else {
                drawRoundRect(rugColor, size = size, cornerRadius = CornerRadius(size.height * .28f))
            }
            when (style.pattern) {
                "stripe" -> repeat(5) { index ->
                    val y = size.height * (index + 1) / 6f
                    drawLine(white.copy(alpha = .62f), Offset(size.width * .07f, y), Offset(size.width * .93f, y), strokeWidth = 2.5f)
                }
                "check" -> {
                    repeat(4) { index ->
                        val x = size.width * (index + 1) / 5f
                        drawLine(white.copy(alpha = .43f), Offset(x, size.height * .08f), Offset(x, size.height * .92f), strokeWidth = 2f)
                    }
                    repeat(3) { index ->
                        val y = size.height * (index + 1) / 4f
                        drawLine(white.copy(alpha = .43f), Offset(size.width * .06f, y), Offset(size.width * .94f, y), strokeWidth = 2f)
                    }
                }
            }
        }

        DigitalFurnitureKind.PLANT -> {
            drawOval(Color.Black.copy(alpha = .06f), Offset(size.width * .20f, size.height * .87f), Size(size.width * .60f, size.height * .09f))
            val pot = if (style.colorKey == "cactus") Color(0xFFD4B28D) else Color(0xFFC7A17B)
            drawRoundRect(pot, Offset(size.width * .27f, size.height * .63f), Size(size.width * .46f, size.height * .31f), CornerRadius(size.width * .12f))
            val leaf = when (style.colorKey) {
                "olive" -> Color(0xFF788C64)
                "cactus" -> Color(0xFF78A06F)
                else -> Color(0xFF668D68)
            }
            if (style.colorKey == "cactus") {
                drawRoundRect(leaf, Offset(size.width * .40f, size.height * .12f), Size(size.width * .20f, size.height * .58f), CornerRadius(size.width * .10f))
                drawRoundRect(leaf, Offset(size.width * .26f, size.height * .31f), Size(size.width * .18f, size.height * .24f), CornerRadius(size.width * .09f))
                drawRoundRect(leaf, Offset(size.width * .57f, size.height * .25f), Size(size.width * .18f, size.height * .27f), CornerRadius(size.width * .09f))
            } else {
                repeat(6) { index ->
                    val spread = index - 2
                    drawOval(
                        leaf.copy(alpha = .90f - index * .035f),
                        Offset(size.width * (.31f + spread * .075f), size.height * (.08f + spread.absoluteValue * .055f)),
                        Size(size.width * .34f, size.height * .48f),
                    )
                }
            }
        }

        DigitalFurnitureKind.TV -> {
            drawRoundRect(darker, Offset(size.width * .04f, size.height * .06f), Size(size.width * .92f, size.height * .69f), CornerRadius(size.height * .06f))
            drawRoundRect(Color(0xFF25292C), Offset(size.width * .09f, size.height * .11f), Size(size.width * .82f, size.height * .57f), CornerRadius(size.height * .04f))
            drawRoundRect(Color(0xFF6C8796).copy(alpha = .28f), Offset(size.width * .13f, size.height * .15f), Size(size.width * .36f, size.height * .19f), CornerRadius(size.height * .03f))
            drawLine(dark, Offset(size.width * .50f, size.height * .74f), Offset(size.width * .50f, size.height * .90f), strokeWidth = 3f)
            drawRoundRect(dark, Offset(size.width * .27f, size.height * .87f), Size(size.width * .46f, size.height * .08f), CornerRadius(4f))
        }

        DigitalFurnitureKind.MIRROR -> {
            val frame = if (style.colorKey == "glass") Color(0xFFB7AAA0) else dark
            if (style.pattern == "round") {
                drawOval(Color(0xFFEAF1F3), Offset(size.width * .10f, size.height * .08f), Size(size.width * .80f, size.height * .80f))
                drawOval(frame, Offset(size.width * .10f, size.height * .08f), Size(size.width * .80f, size.height * .80f), style = Stroke(3f))
            } else {
                drawRoundRect(Color(0xFFEAF1F3), Offset(size.width * .14f, size.height * .04f), Size(size.width * .72f, size.height * .90f), CornerRadius(size.width * .34f))
                drawRoundRect(frame, Offset(size.width * .14f, size.height * .04f), Size(size.width * .72f, size.height * .90f), CornerRadius(size.width * .34f), style = Stroke(3f))
            }
            drawLine(Color.White.copy(alpha = .78f), Offset(size.width * .31f, size.height * .18f), Offset(size.width * .55f, size.height * .52f), strokeWidth = 2f)
        }

        DigitalFurnitureKind.WALL_ART -> {
            drawRoundRect(darker, size = size, cornerRadius = CornerRadius(4f))
            drawRect(Color(0xFFF6F3EC), Offset(size.width * .07f, size.height * .07f), Size(size.width * .86f, size.height * .86f))
            if (style.id == "art_landscape") {
                val hills = Path().apply {
                    moveTo(size.width * .12f, size.height * .68f)
                    lineTo(size.width * .36f, size.height * .40f)
                    lineTo(size.width * .52f, size.height * .60f)
                    lineTo(size.width * .68f, size.height * .35f)
                    lineTo(size.width * .88f, size.height * .68f)
                }
                drawPath(hills, base, style = Stroke(3f))
            } else {
                drawLine(darker, Offset(size.width * .25f, size.height * .72f), Offset(size.width * .67f, size.height * .26f), strokeWidth = 2f)
                drawCircle(darker, radius = size.minDimension * .11f, center = Offset(size.width * .38f, size.height * .39f), style = Stroke(2f))
            }
        }

        DigitalFurnitureKind.CLOCK -> {
            drawCircle(white, radius = size.minDimension * .42f, center = center)
            drawCircle(dark, radius = size.minDimension * .42f, center = center, style = Stroke(3f))
            drawLine(darker, center, Offset(center.x, center.y - size.height * .20f), strokeWidth = 2.5f)
            drawLine(darker, center, Offset(center.x + size.width * .16f, center.y + size.height * .08f), strokeWidth = 2.5f)
            drawCircle(darker, radius = 3f, center = center)
        }

        DigitalFurnitureKind.CUSHION -> {
            drawRoundRect(base, Offset(size.width * .08f, size.height * .10f), Size(size.width * .84f, size.height * .78f), CornerRadius(size.width * .20f))
            drawCircle(white.copy(alpha = .62f), radius = 2.5f, center = center)
            if (style.pattern == "stripe") repeat(4) { index ->
                val x = size.width * (.24f + index * .16f)
                drawLine(white.copy(alpha = .62f), Offset(x, size.height * .18f), Offset(x, size.height * .82f), strokeWidth = 2f)
            }
        }

        DigitalFurnitureKind.BASKET -> {
            drawRoundRect(base, Offset(size.width * .08f, size.height * .28f), Size(size.width * .84f, size.height * .64f), CornerRadius(size.width * .12f))
            repeat(4) { index ->
                val y = size.height * (.40f + index * .12f)
                drawLine(white.copy(alpha = .38f), Offset(size.width * .13f, y), Offset(size.width * .87f, y), strokeWidth = 1.8f)
            }
            val handle = Path().apply {
                moveTo(size.width * .29f, size.height * .35f)
                cubicTo(size.width * .31f, size.height * .04f, size.width * .69f, size.height * .04f, size.width * .71f, size.height * .35f)
            }
            drawPath(handle, dark, style = Stroke(3f))
        }

        DigitalFurnitureKind.DECOR -> {
            if (style.colorKey == "glass") {
                drawOval(Color(0xFFDAE8EC).copy(alpha = .55f), Offset(size.width * .26f, size.height * .24f), Size(size.width * .48f, size.height * .62f))
                drawOval(Color.White.copy(alpha = .70f), Offset(size.width * .36f, size.height * .29f), Size(size.width * .12f, size.height * .42f))
            } else {
                drawCircle(base, radius = size.minDimension * .35f, center = center)
                drawCircle(white.copy(alpha = .66f), radius = size.minDimension * .14f, center = center)
            }
        }
    }
}

@Composable
internal fun FurnitureCatalogDialog(onDismiss: () -> Unit) {
    val rows = DigitalFurnitureCatalog.styles.chunked(2)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Chair, null) },
        title = { Text("家具城", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { row -> row.joinToString("|") { it.id } }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { style ->
                            Surface(
                                color = Color(0xFFFAF9F7),
                                shape = RoundedCornerShape(16.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE3E0DA)),
                                modifier = Modifier.weight(1f),
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 9.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Box(Modifier.height(68.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        FurnitureCatalogPreview(style)
                                    }
                                    Text(style.displayName, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, maxLines = 1)
                                    Text(
                                        DigitalFurnitureCatalog.kindLabel(style.kind),
                                        color = LuluColors.Muted,
                                        fontSize = 9.sp,
                                    )
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun FurnitureCatalogPreview(style: DigitalFurnitureStyle) {
    val fake = DigitalWorldItem(
        id = "preview-${style.id}",
        ownerCharacterId = "preview",
        type = style.kind.name.lowercase(),
        name = style.displayName,
        appearance = style.displayName,
        position = "预览",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
    FurnitureSticker(fake, style, preview = true)
}

internal fun furniturePlacement(item: DigitalWorldItem, index: Int): Pair<Float, Float> {
    val text = item.position.lowercase()
    val kind = DigitalFurnitureCatalog.resolve(item).kind
    val x = when {
        listOf("最左", "左侧", "靠左", "左边").any(text::contains) -> .06f
        listOf("最右", "右侧", "靠右", "右边").any(text::contains) -> .72f
        listOf("正中", "中央", "中间", "中心").any(text::contains) -> .38f
        else -> ((item.id.hashCode().absoluteValue % 67) / 100f).coerceIn(.04f, .72f)
    }
    val y = when {
        kind in setOf(DigitalFurnitureKind.WALL_ART, DigitalFurnitureKind.CLOCK, DigitalFurnitureKind.MIRROR) -> .10f
        kind == DigitalFurnitureKind.RUG -> .69f
        listOf("上方", "里面", "后方", "靠墙", "墙边", "窗边").any(text::contains) -> .28f
        listOf("下方", "门边", "前方", "入口").any(text::contains) -> .68f
        listOf("正中", "中央", "中间", "中心").any(text::contains) -> .47f
        else -> (((item.id.reversed().hashCode().absoluteValue + index * 19) % 45) / 100f + .28f).coerceIn(.27f, .72f)
    }
    return x to y
}

internal fun stickerWidth(kind: DigitalFurnitureKind): Dp = when (kind) {
    DigitalFurnitureKind.BED -> 126.dp
    DigitalFurnitureKind.SOFA -> 118.dp
    DigitalFurnitureKind.RUG -> 142.dp
    DigitalFurnitureKind.COFFEE_TABLE, DigitalFurnitureKind.TABLE, DigitalFurnitureKind.DESK -> 84.dp
    DigitalFurnitureKind.SHELF, DigitalFurnitureKind.CABINET -> 68.dp
    DigitalFurnitureKind.NIGHTSTAND -> 54.dp
    DigitalFurnitureKind.CHAIR -> 50.dp
    DigitalFurnitureKind.FLOOR_LAMP -> 52.dp
    DigitalFurnitureKind.TABLE_LAMP, DigitalFurnitureKind.PLANT, DigitalFurnitureKind.DECOR -> 50.dp
    DigitalFurnitureKind.TV -> 92.dp
    DigitalFurnitureKind.MIRROR -> 58.dp
    DigitalFurnitureKind.WALL_ART -> 62.dp
    DigitalFurnitureKind.CLOCK -> 46.dp
    DigitalFurnitureKind.CUSHION -> 44.dp
    DigitalFurnitureKind.BASKET -> 52.dp
}

internal fun stickerHeight(kind: DigitalFurnitureKind): Dp = when (kind) {
    DigitalFurnitureKind.BED -> 86.dp
    DigitalFurnitureKind.SOFA -> 74.dp
    DigitalFurnitureKind.RUG -> 84.dp
    DigitalFurnitureKind.COFFEE_TABLE, DigitalFurnitureKind.TABLE, DigitalFurnitureKind.DESK -> 58.dp
    DigitalFurnitureKind.SHELF, DigitalFurnitureKind.CABINET -> 96.dp
    DigitalFurnitureKind.NIGHTSTAND -> 58.dp
    DigitalFurnitureKind.CHAIR -> 60.dp
    DigitalFurnitureKind.FLOOR_LAMP -> 102.dp
    DigitalFurnitureKind.TABLE_LAMP, DigitalFurnitureKind.PLANT, DigitalFurnitureKind.DECOR -> 64.dp
    DigitalFurnitureKind.TV -> 68.dp
    DigitalFurnitureKind.MIRROR -> 96.dp
    DigitalFurnitureKind.WALL_ART -> 76.dp
    DigitalFurnitureKind.CLOCK -> 46.dp
    DigitalFurnitureKind.CUSHION -> 44.dp
    DigitalFurnitureKind.BASKET -> 60.dp
}

private fun stickerColor(key: String): Color = when (key) {
    "sky" -> Color(0xFFABC9D9)
    "sage" -> Color(0xFFA9BEA3)
    "wood" -> Color(0xFFC9A77D)
    "walnut" -> Color(0xFF80654E)
    "charcoal" -> Color(0xFF5C6062)
    "white" -> Color(0xFFF8F8F5)
    "warm" -> Color(0xFFF2C97D)
    "leaf" -> Color(0xFF80A47A)
    "olive" -> Color(0xFF89966E)
    "cactus" -> Color(0xFF76A36F)
    "rose" -> Color(0xFFD7B2AE)
    "latte" -> Color(0xFFC6A88D)
    "navy" -> Color(0xFF5B6B7D)
    "glass" -> Color(0xFFBFD5DC)
    "rattan" -> Color(0xFFC69E70)
    else -> Color(0xFFE8DCC7)
}

private fun darken(color: Color): Color = Color(
    red = (color.red * .78f).coerceIn(0f, 1f),
    green = (color.green * .78f).coerceIn(0f, 1f),
    blue = (color.blue * .78f).coerceIn(0f, 1f),
    alpha = color.alpha,
)
