package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jiacimu.lulu.data.DigitalFurnitureCatalog
import com.jiacimu.lulu.data.DigitalFurnitureStyle
import com.jiacimu.lulu.data.DigitalWorldItem
import java.time.Instant

@Composable
internal fun StyledFurnitureCatalogDialog(onDismiss: () -> Unit) {
    val groups = DigitalFurnitureCatalog.styles.groupBy { DigitalFurnitureCatalog.kindLabel(it.kind) }.toList()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .heightIn(max = 660.dp),
            color = Color(0xFFFEFEFD),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color(0xFF2B2A28)),
            shadowElevation = 10.dp,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 17.dp, end = 8.dp, top = 10.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(34.dp),
                        shape = RoundedCornerShape(11.dp),
                        color = Color(0xFFF2F1EE),
                        border = BorderStroke(.7.dp, Color(0xFFDEDBD5)),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Chair, null, tint = Color(0xFF353330), modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("家具城", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF22211F))
                        Text("看看数字世界里的家具", fontSize = 9.5.sp, color = Color(0xFF827E78))
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, "关闭", tint = Color(0xFF4B4945))
                    }
                }
                HorizontalDivider(color = Color(0xFFE7E4DE), thickness = .7.dp)

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    groups.forEach { (label, styles) ->
                        item(key = "title-$label") {
                            Text(
                                label,
                                color = Color(0xFF706C66),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 3.dp),
                            )
                        }
                        items(styles.chunked(2), key = { row -> row.joinToString("|") { it.id } }) { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                row.forEach { style ->
                                    FurnitureCatalogCard(style, Modifier.weight(1f))
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FurnitureCatalogCard(style: DigitalFurnitureStyle, modifier: Modifier = Modifier) {
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
    Surface(
        modifier = modifier.height(116.dp),
        color = Color(0xFFFAF9F6),
        shape = RoundedCornerShape(17.dp),
        border = BorderStroke(.8.dp, Color(0xFFDEDAD4)),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.height(70.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                FurnitureSticker(fake, style, preview = true)
            }
            Text(
                style.displayName,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.5.sp,
                color = Color(0xFF34312E),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun DigitalFurnitureDetailDialog(
    item: DigitalWorldItem,
    onDismiss: () -> Unit,
) {
    val style = DigitalFurnitureCatalog.resolve(item)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFFFEFEFD),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, Color(0xFF2B2A28)),
            shadowElevation = 9.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 76.dp, height = 64.dp), contentAlignment = Alignment.Center) {
                        FurnitureSticker(item, style, preview = true)
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF262421))
                        Text(
                            DigitalFurnitureCatalog.kindLabel(style.kind),
                            fontSize = 9.5.sp,
                            color = Color(0xFF807C76),
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, "关闭", tint = Color(0xFF4B4945))
                    }
                }
                HorizontalDivider(color = Color(0xFFE8E5DF), thickness = .7.dp)
                if (item.appearance.isNotBlank()) {
                    Text(item.appearance, color = Color(0xFF3B3935), fontSize = 13.sp, lineHeight = 20.sp)
                }
                Surface(
                    color = Color(0xFFF4F2EE),
                    shape = RoundedCornerShape(11.dp),
                    border = BorderStroke(.7.dp, Color(0xFFE1DED7)),
                ) {
                    Text(
                        "摆放位置 · ${item.position}",
                        color = Color(0xFF716D67),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}
