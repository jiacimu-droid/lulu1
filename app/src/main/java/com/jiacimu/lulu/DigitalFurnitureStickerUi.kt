package com.jiacimu.lulu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
    val base = stickerColor(style.colorKey)
    val dark = base.copy(alpha = .92f)
    val width = if (preview) 56.dp else stickerWidth(style.kind)
    val height = if (preview) 48.dp else stickerHeight(style.kind)
    Box(modifier.size(width, height), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            when (style.kind) {
                DigitalFurnitureKind.BED -> {
                    drawRoundRect(
                        base,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),
                        size = size,
                    )
                    drawRoundRect(
                        Color.White.copy(alpha = .86f),
                        topLeft = Offset(size.width * .07f, size.height * .08f),
                        size = Size(size.width * .34f, size.height * .26f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()),
                    )
                    drawRoundRect(
                        Color.White.copy(alpha = .78f),
                        topLeft = Offset(size.width * .55f, size.height * .08f),
                        size = Size(size.width * .34f, size.height * .26f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()),
                    )
                    if (style.pattern == "stripe") {
                        var x = size.width * .09f
                        while (x < size.width) {
                            drawLine(
                                Color.White.copy(alpha = .38f),
                                Offset(x, size.height * .36f),
                                Offset(x, size.height * .94f),
                                strokeWidth = (size.width * .04f).coerceAtLeast(2f),
                            )
                            x += size.width * .13f
                        }
                    }
                }
                DigitalFurnitureKind.SOFA -> {
                    drawRoundRect(
                        dark,
                        topLeft = Offset(size.width * .04f, size.height * .05f),
                        size = Size(size.width * .92f, size.height * .48f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(13.dp.toPx()),
                    )
                    drawRoundRect(
                        base,
                        topLeft = Offset(0f, size.height * .34f),
                        size = Size(size.width, size.height * .58f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(15.dp.toPx()),
                    )
                    drawLine(
                        Color.White.copy(alpha = .45f),
                        Offset(size.width / 2f, size.height * .45f),
                        Offset(size.width / 2f, size.height * .82f),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }
                DigitalFurnitureKind.COFFEE_TABLE,
                DigitalFurnitureKind.TABLE,
                DigitalFurnitureKind.DESK -> {
                    drawRoundRect(
                        base,
                        size = Size(size.width, size.height * .62f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(11.dp.toPx()),
                    )
                    drawLine(
                        dark,
                        Offset(size.width * .16f, size.height * .55f),
                        Offset(size.width * .12f, size.height),
                        strokeWidth = 4.dp.toPx(),
                    )
                    drawLine(
                        dark,
                        Offset(size.width * .84f, size.height * .55f),
                        Offset(size.width * .88f, size.height),
                        strokeWidth = 4.dp.toPx(),
                    )
                }
                DigitalFurnitureKind.CHAIR -> {
                    drawRoundRect(
                        base,
                        size = Size(size.width, size.height * .55f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()),
                    )
                    drawLine(dark, Offset(size.width * .2f, size.height * .5f), Offset(size.width * .16f, size.height), strokeWidth = 3.dp.toPx())
                    drawLine(dark, Offset(size.width * .8f, size.height * .5f), Offset(size.width * .84f, size.height), strokeWidth = 3.dp.toPx())
                }
                DigitalFurnitureKind.SHELF,
                DigitalFurnitureKind.CABINET -> {
                    drawRoundRect(base, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()))
                    repeat(3) { i ->
                        val y = size.height * (i + 1) / 4f
                        drawLine(
                            Color.White.copy(alpha = .5f),
                            Offset(size.width * .08f, y),
                            Offset(size.width * .92f, y),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                    }
                }
                DigitalFurnitureKind.FLOOR_LAMP -> {
                    drawLine(dark, Offset(size.width / 2f, size.height * .28f), Offset(size.width / 2f, size.height * .86f), strokeWidth = 4.dp.toPx())
                    drawCircle(base, radius = size.width * .34f, center = Offset(size.width / 2f, size.height * .22f))
                    drawOval(dark, topLeft = Offset(size.width * .18f, size.height * .84f), size = Size(size.width * .64f, size.height * .12f))
                }
                DigitalFurnitureKind.TABLE_LAMP -> {
                    drawCircle(base, radius = size.width * .36f, center = Offset(size.width / 2f, size.height * .32f))
                    drawLine(dark, Offset(size.width / 2f, size.height * .55f), Offset(size.width / 2f, size.height * .8f), strokeWidth = 3.dp.toPx())
                    drawOval(dark, topLeft = Offset(size.width * .22f, size.height * .78f), size = Size(size.width * .56f, size.height * .14f))
                }
                DigitalFurnitureKind.RUG -> {
                    drawRoundRect(
                        base.copy(alpha = .62f),
                        size = size,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()),
                    )
                    if (style.pattern == "stripe") {
                        repeat(5) { i ->
                            val y = size.height * (i + 1) / 6f
                            drawLine(
                                Color.White.copy(alpha = .56f),
                                Offset(size.width * .06f, y),
                                Offset(size.width * .94f, y),
                                strokeWidth = 3.dp.toPx(),
                            )
                        }
                    }
                }
                DigitalFurnitureKind.PLANT -> {
                    drawRoundRect(
                        Color(0xFFC9A780),
                        topLeft = Offset(size.width * .28f, size.height * .62f),
                        size = Size(size.width * .44f, size.height * .34f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()),
                    )
                    repeat(5) { i ->
                        val angle = i - 2
                        drawOval(
                            Color(0xFF6E936C),
                            topLeft = Offset(
                                size.width * (.34f + angle * .08f),
                                size.height * (.08f + angle.absoluteValue * .07f),
                            ),
                            size = Size(size.width * .34f, size.height * .5f),
                        )
                    }
                }
                DigitalFurnitureKind.DECOR -> {
                    drawCircle(base, radius = size.minDimension * .38f, center = center)
                    drawCircle(Color.White.copy(alpha = .5f), radius = size.minDimension * .17f, center = center)
                }
            }
        }
        if (!preview) {
            Text(
                item.name,
                fontSize = 8.5.sp,
                color = Color(0xFF4E4A45),
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color.White.copy(alpha = .72f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
internal fun FurnitureCatalogDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Chair, null) },
        title = { Text("家具城", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(DigitalFurnitureCatalog.styles, key = DigitalFurnitureStyle::id) { style ->
                    Surface(color = Color(0xFFF7F7F5), shape = RoundedCornerShape(13.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(62.dp), contentAlignment = Alignment.Center) {
                                FurnitureCatalogPreview(style)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(style.displayName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(
                                    "${style.kind.name.lowercase()} · ${style.colorKey}${if (style.pattern != "plain") " · ${style.pattern}" else ""}",
                                    color = LuluColors.Muted,
                                    fontSize = 9.5.sp,
                                )
                            }
                        }
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
    val x = when {
        listOf("最左", "左侧", "靠左", "左边").any(text::contains) -> .06f
        listOf("最右", "右侧", "靠右", "右边").any(text::contains) -> .72f
        listOf("正中", "中央", "中间", "中心").any(text::contains) -> .38f
        else -> ((item.id.hashCode().absoluteValue % 67) / 100f).coerceIn(.04f, .72f)
    }
    val y = when {
        listOf("上方", "里面", "后方", "靠墙", "墙边", "窗边").any(text::contains) -> .14f
        listOf("下方", "门边", "前方", "入口").any(text::contains) -> .69f
        listOf("正中", "中央", "中间", "中心").any(text::contains) -> .42f
        else -> (((item.id.reversed().hashCode().absoluteValue + index * 19) % 64) / 100f).coerceIn(.12f, .72f)
    }
    return x to y
}

internal fun stickerWidth(kind: DigitalFurnitureKind): Dp = when (kind) {
    DigitalFurnitureKind.BED -> 122.dp
    DigitalFurnitureKind.SOFA -> 116.dp
    DigitalFurnitureKind.RUG -> 138.dp
    DigitalFurnitureKind.COFFEE_TABLE,
    DigitalFurnitureKind.TABLE,
    DigitalFurnitureKind.DESK -> 82.dp
    DigitalFurnitureKind.SHELF,
    DigitalFurnitureKind.CABINET -> 66.dp
    DigitalFurnitureKind.CHAIR -> 48.dp
    DigitalFurnitureKind.FLOOR_LAMP -> 46.dp
    DigitalFurnitureKind.TABLE_LAMP,
    DigitalFurnitureKind.PLANT,
    DigitalFurnitureKind.DECOR -> 48.dp
}

internal fun stickerHeight(kind: DigitalFurnitureKind): Dp = when (kind) {
    DigitalFurnitureKind.BED -> 82.dp
    DigitalFurnitureKind.SOFA -> 72.dp
    DigitalFurnitureKind.RUG -> 82.dp
    DigitalFurnitureKind.COFFEE_TABLE,
    DigitalFurnitureKind.TABLE,
    DigitalFurnitureKind.DESK -> 56.dp
    DigitalFurnitureKind.SHELF,
    DigitalFurnitureKind.CABINET -> 94.dp
    DigitalFurnitureKind.CHAIR -> 58.dp
    DigitalFurnitureKind.FLOOR_LAMP -> 96.dp
    DigitalFurnitureKind.TABLE_LAMP,
    DigitalFurnitureKind.PLANT,
    DigitalFurnitureKind.DECOR -> 62.dp
}

private fun stickerColor(key: String): Color = when (key) {
    "sky" -> Color(0xFFABC9D9)
    "sage" -> Color(0xFFA9BEA3)
    "wood" -> Color(0xFFC9A77D)
    "charcoal" -> Color(0xFF666A6B)
    "white" -> Color(0xFFF8F8F5)
    "warm" -> Color(0xFFF2C97D)
    "leaf" -> Color(0xFF80A47A)
    else -> Color(0xFFE8DCC7)
}
