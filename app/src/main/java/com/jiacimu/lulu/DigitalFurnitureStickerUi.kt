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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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
    val width = if (preview) 56.dp else stickerWidth(style.kind)
    val height = if (preview) 48.dp else stickerHeight(style.kind)
    val base = stickerColor(style.colorKey)
    Box(modifier.size(width, height), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) { drawFurnitureSticker(style, base) }
        if (!preview) {
            Text(
                item.name,
                fontSize = 8.5.sp,
                color = Color(0xFF4E4A45),
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color.White.copy(alpha = .74f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

private fun DrawScope.drawFurnitureSticker(style: DigitalFurnitureStyle, base: Color) {
    val dark = base.copy(alpha = .92f)
    val white = Color.White.copy(alpha = .78f)
    when (style.kind) {
        DigitalFurnitureKind.BED -> {
            drawRoundRect(base, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * .16f))
            drawRoundRect(white, Offset(size.width * .07f, size.height * .08f), Size(size.width * .34f, size.height * .25f), androidx.compose.ui.geometry.CornerRadius(size.height * .08f))
            drawRoundRect(white, Offset(size.width * .56f, size.height * .08f), Size(size.width * .34f, size.height * .25f), androidx.compose.ui.geometry.CornerRadius(size.height * .08f))
            if (style.pattern == "stripe") {
                repeat(6) { index ->
                    val x = size.width * (.12f + index * .14f)
                    drawLine(Color.White.copy(alpha = .42f), Offset(x, size.height * .38f), Offset(x, size.height * .92f), strokeWidth = (size.width * .035f).coerceAtLeast(2f))
                }
            }
        }
        DigitalFurnitureKind.SOFA -> {
            drawRoundRect(dark, Offset(size.width * .05f, size.height * .05f), Size(size.width * .9f, size.height * .48f), androidx.compose.ui.geometry.CornerRadius(size.height * .16f))
            drawRoundRect(base, Offset(0f, size.height * .34f), Size(size.width, size.height * .58f), androidx.compose.ui.geometry.CornerRadius(size.height * .18f))
            drawLine(white, Offset(size.width / 2f, size.height * .45f), Offset(size.width / 2f, size.height * .82f), strokeWidth = 2f)
        }
        DigitalFurnitureKind.COFFEE_TABLE,
        DigitalFurnitureKind.TABLE,
        DigitalFurnitureKind.DESK -> {
            drawRoundRect(base, size = Size(size.width, size.height * .62f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * .12f))
            drawLine(dark, Offset(size.width * .18f, size.height * .56f), Offset(size.width * .13f, size.height), strokeWidth = size.width * .045f)
            drawLine(dark, Offset(size.width * .82f, size.height * .56f), Offset(size.width * .87f, size.height), strokeWidth = size.width * .045f)
        }
        DigitalFurnitureKind.CHAIR -> {
            drawRoundRect(base, size = Size(size.width, size.height * .55f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * .12f))
            drawLine(dark, Offset(size.width * .2f, size.height * .5f), Offset(size.width * .16f, size.height), strokeWidth = size.width * .06f)
            drawLine(dark, Offset(size.width * .8f, size.height * .5f), Offset(size.width * .84f, size.height), strokeWidth = size.width * .06f)
        }
        DigitalFurnitureKind.SHELF,
        DigitalFurnitureKind.CABINET -> {
            drawRoundRect(base, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * .14f))
            repeat(3) { index ->
                val y = size.height * (index + 1) / 4f
                drawLine(white, Offset(size.width * .08f, y), Offset(size.width * .92f, y), strokeWidth = 2f)
            }
        }
        DigitalFurnitureKind.FLOOR_LAMP -> {
            drawLine(dark, Offset(size.width / 2f, size.height * .28f), Offset(size.width / 2f, size.height * .86f), strokeWidth = size.width * .08f)
            drawCircle(base, radius = size.width * .34f, center = Offset(size.width / 2f, size.height * .22f))
            drawOval(dark, Offset(size.width * .18f, size.height * .84f), Size(size.width * .64f, size.height * .12f))
        }
        DigitalFurnitureKind.TABLE_LAMP -> {
            drawCircle(base, radius = size.width * .35f, center = Offset(size.width / 2f, size.height * .32f))
            drawLine(dark, Offset(size.width / 2f, size.height * .55f), Offset(size.width / 2f, size.height * .8f), strokeWidth = size.width * .07f)
            drawOval(dark, Offset(size.width * .22f, size.height * .78f), Size(size.width * .56f, size.height * .14f))
        }
        DigitalFurnitureKind.RUG -> {
            drawRoundRect(base.copy(alpha = .62f), size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * .28f))
            if (style.pattern == "stripe") repeat(5) { index ->
                val y = size.height * (index + 1) / 6f
                drawLine(white, Offset(size.width * .06f, y), Offset(size.width * .94f, y), strokeWidth = 3f)
            }
        }
        DigitalFurnitureKind.PLANT -> {
            drawRoundRect(Color(0xFFC9A780), Offset(size.width * .28f, size.height * .62f), Size(size.width * .44f, size.height * .34f), androidx.compose.ui.geometry.CornerRadius(size.width * .12f))
            repeat(5) { index ->
                val spread = index - 2
                drawOval(
                    Color(0xFF6E936C),
                    Offset(size.width * (.34f + spread * .08f), size.height * (.08f + spread.absoluteValue * .07f)),
                    Size(size.width * .34f, size.height * .5f),
                )
            }
        }
        DigitalFurnitureKind.DECOR -> {
            drawCircle(base, radius = size.minDimension * .38f, center = center)
            drawCircle(white, radius = size.minDimension * .17f, center = center)
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
            LazyColumn(modifier = Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DigitalFurnitureCatalog.styles, key = DigitalFurnitureStyle::id) { style ->
                    Surface(color = Color(0xFFF7F7F5), shape = RoundedCornerShape(13.dp)) {
                        Row(Modifier.fillMaxWidth().padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(62.dp), contentAlignment = Alignment.Center) { FurnitureCatalogPreview(style) }
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
    DigitalFurnitureKind.COFFEE_TABLE, DigitalFurnitureKind.TABLE, DigitalFurnitureKind.DESK -> 82.dp
    DigitalFurnitureKind.SHELF, DigitalFurnitureKind.CABINET -> 66.dp
    DigitalFurnitureKind.CHAIR -> 48.dp
    DigitalFurnitureKind.FLOOR_LAMP -> 46.dp
    DigitalFurnitureKind.TABLE_LAMP, DigitalFurnitureKind.PLANT, DigitalFurnitureKind.DECOR -> 48.dp
}

internal fun stickerHeight(kind: DigitalFurnitureKind): Dp = when (kind) {
    DigitalFurnitureKind.BED -> 82.dp
    DigitalFurnitureKind.SOFA -> 72.dp
    DigitalFurnitureKind.RUG -> 82.dp
    DigitalFurnitureKind.COFFEE_TABLE, DigitalFurnitureKind.TABLE, DigitalFurnitureKind.DESK -> 56.dp
    DigitalFurnitureKind.SHELF, DigitalFurnitureKind.CABINET -> 94.dp
    DigitalFurnitureKind.CHAIR -> 58.dp
    DigitalFurnitureKind.FLOOR_LAMP -> 96.dp
    DigitalFurnitureKind.TABLE_LAMP, DigitalFurnitureKind.PLANT, DigitalFurnitureKind.DECOR -> 62.dp
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
