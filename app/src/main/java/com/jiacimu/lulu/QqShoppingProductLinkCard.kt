package com.jiacimu.lulu

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Explicit user tap opens the merchant's actual HTTPS share link via Android App Links. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun QqShoppingProductLinkCard(
    product: QqShoppingProductLink,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    Surface(
        modifier = modifier.combinedClickable(
            onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(product.url))
                        .addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: Exception) {
                    Toast.makeText(context, "无法打开商品链接，请检查是否安装商家应用或浏览器", Toast.LENGTH_LONG).show()
                }
            },
            onLongClick = onLongClick,
        ),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.widthIn(min = 190.dp, max = 290.dp).padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.ShoppingBag, contentDescription = null, modifier = Modifier.size(21.dp))
                Text(product.merchant + " · 商品链接", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Outlined.OpenInNew, contentDescription = "打开商品", modifier = Modifier.size(18.dp))
            }
            if (product.caption.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(product.caption, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            Text("点开查看商品 · 下单和付款由你确认",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
