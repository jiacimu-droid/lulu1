package com.jiacimu.lulu

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.system.LuluSharedWebLinks
import com.jiacimu.lulu.system.LuluShoppingLinkShare
import kotlinx.coroutines.launch

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun QqSharedWebLinkCard(
    url: String,
    modifier: Modifier = Modifier,
    title: String = "",
    caption: String = "",
    verifiedMerchant: String? = null,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opening by remember(url) { mutableStateOf(false) }
    val merchant = verifiedMerchant ?: remember(url) { LuluShoppingLinkShare.merchantForUrl(url) }
    val host = Uri.parse(url).host.orEmpty().removePrefix("www.")
    val xhs = LuluSharedWebLinks.xhsUrl(url) != null
    val heading = title.ifBlank { merchant ?: if (xhs) "小红书笔记" else host }
    val openLabel = when {
        xhs -> "打开小红书"
        merchant != null -> "打开商品"
        else -> "打开链接"
    }
    Surface(
        modifier = modifier.combinedClickable(
            onClickLabel = openLabel,
            onLongClickLabel = "消息操作",
            onClick = {
                if (!opening) {
                    opening = true
                    scope.launch {
                        try {
                            val result = QqShoppingProductLinkOpener.openSharedLink(context, url, merchant)
                            if (result == QqShoppingProductLinkOpener.Result.FAILED)
                                Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
                        } finally {
                            opening = false
                        }
                    }
                }
            },
            onLongClick = onLongClick,
        ),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.widthIn(min = 190.dp, max = 286.dp).padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(40.dp).background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(12.dp),
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (merchant != null) Icons.Outlined.ShoppingBag else Icons.Outlined.Link,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        heading,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        host,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (caption.isNotBlank()) {
                Spacer(Modifier.height(11.dp))
                Text(
                    caption,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
                        RoundedCornerShape(12.dp),
                    )
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (opening) "正在打开…" else openLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Outlined.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
