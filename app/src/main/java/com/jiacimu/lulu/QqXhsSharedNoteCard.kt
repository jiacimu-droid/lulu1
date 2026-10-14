package com.jiacimu.lulu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.XhsImageLoader

private val xhsRed = Color(0xFFE65059)
private val darkInk = Color(0xFF292A30)
private val secondaryInk = Color(0xFF929199)

@Composable
private fun QqXhsCover(url: String, modifier: Modifier = Modifier) {
    val image by produceState<Bitmap?>(initialValue = null, key1 = url) {
        value = if (url.isBlank()) null else runCatching { XhsImageLoader.bitmap(url) }.getOrNull()
    }
    Surface(modifier, shape = RoundedCornerShape(13.dp), color = Color(0xFFFFE9E8)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (image != null && image!!.width > 0 && image!!.height > 0) {
                Image(image!!.asImageBitmap(), contentDescription = "笔记实际封面",
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
                    Box(Modifier.fillMaxSize().padding(1.dp)) {
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                            drawRect(Brush.linearGradient(
                                listOf(Color(0xFFFFE4E6), Color(0xFFFFF5EB))))
                        }
                        Column(Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("小", fontSize = 37.sp, fontWeight = FontWeight.Black,
                                color = xhsRed.copy(alpha = 0.78f))
                            Text("RED NOTE", fontSize = 8.sp, color = xhsRed.copy(alpha = 0.76f),
                                letterSpacing = 1.sp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QqXhsSharedNoteCard(
    note: QqXhsSharedNote,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = Color.White,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0xFFF0E9E9)),
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QqXhsCover(note.coverUrl, Modifier.size(width = 92.dp, height = 106.dp))
                Column(Modifier.weight(1f).heightIn(min = 106.dp),
                    verticalArrangement = Arrangement.SpaceBetween) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("● 小红书笔记", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            color = xhsRed)
                        Text(note.title, color = darkInk, fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold, lineHeight = 21.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Text(note.author.ifBlank { if (note.verified) "图文分享" else "来自小红书" },
                        fontSize = 11.sp, color = secondaryInk, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            val teaser = if (note.verified) note.body.replace(Regex("""\s+"""), " ").take(110)
                .ifBlank { note.visualReading.replace(Regex("""\s+"""), " ").take(100) }
                else "已分享笔记链接，点击查看分享信息"
            if (teaser.isNotBlank()) Text(teaser, color = Color(0xFF77757D),
                fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            HorizontalDivider(color = Color(0xFFF2EEEE))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (note.verified) "已附正文 · 点击查看详情" else "分享链接 · 点击查看",
                    modifier = Modifier.weight(1f), fontSize = 11.sp, color = secondaryInk)
                Text("查看  ›", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = xhsRed)
            }
        }
    }
}

@Composable
internal fun QqXhsSharedNoteDetail(note: QqXhsSharedNote, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("小红书分享", fontSize = 12.sp, color = xhsRed)
                Text(note.title, fontWeight = FontWeight.SemiBold, fontSize = 19.sp,
                    lineHeight = 26.sp, color = darkInk)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp)
                .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (note.coverUrl.isNotBlank()) {
                    QqXhsCover(note.coverUrl, Modifier.fillMaxWidth().height(170.dp))
                }
                if (note.author.isNotBlank()) Text("作者 · ${note.author}",
                    fontSize = 12.sp, color = secondaryInk)
                if (!note.verified) {
                    Text("这是分享链接，尚未读取笔记正文及图片。角色不能仅凭链接声称已经看过。",
                        color = secondaryInk, fontSize = 13.sp, lineHeight = 20.sp)
                    if (note.shareText.isNotBlank()) Text(note.shareText.take(900),
                        color = darkInk, fontSize = 13.sp, lineHeight = 20.sp)
                } else {
                    if (note.body.isNotBlank()) {
                        Text("笔记正文", fontWeight = FontWeight.SemiBold, color = darkInk)
                        Text(note.body, color = darkInk, fontSize = 14.sp, lineHeight = 22.sp)
                    }
                    if (note.visualReading.isNotBlank()) {
                        HorizontalDivider(color = Color(0xFFF1EDED))
                        Text("图片理解 · AI 识别", fontWeight = FontWeight.SemiBold, color = darkInk)
                        Text(note.visualReading, color = darkInk,
                            fontSize = 13.sp, lineHeight = 21.sp)
                    } else {
                        Text("图片内容未经过可确认的识别。", color = secondaryInk, fontSize = 12.sp)
                    }
                    if (note.comments.isNotEmpty()) {
                        HorizontalDivider(color = Color(0xFFF1EDED))
                        Text("已加载的评论（非完整评论区）", fontWeight = FontWeight.SemiBold)
                        note.comments.forEach { comment ->
                            Text("· $comment", fontSize = 13.sp, lineHeight = 20.sp, color = darkInk)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("小红书笔记链接", note.url))
                onDismiss()
            }) { Text("复制链接", color = xhsRed) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭", color = secondaryInk) } },
    )
}
