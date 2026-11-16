package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.design.LuluColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Per-character durable media, not a global/shared memory gallery. */
@Composable
internal fun RolePhoneAlbum(characterId: String, observationsOnly: Boolean = false) {
    val context = LocalContext.current
    val revision by RolePhotoAlbumStore.revisions.collectAsState()
    val photos = remember(context, characterId, observationsOnly, revision) {
        RolePhotoAlbumStore.list(context, characterId).filter {
            (it.source == RolePhotoAlbumStore.SCREEN_OBSERVATION) == observationsOnly
        }
    }
    var selected by remember(characterId, observationsOnly) {
        mutableStateOf<RoleAlbumPhoto?>(null)
    }
    if (photos.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(26.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (observationsOnly) "还没有真实截图" else "还没有保存照片",
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Text(if (observationsOnly)
                "角色获授权查看主人主屏或操作后台虚拟屏后，真实且已验证的截图会出现在这里。没有记录不代表角色没有使用手机。"
                else "这里是角色自己保存的聊天图片、网络图片与照片。",
                color = LuluColors.Muted, fontSize = 13.sp)
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Text(if (observationsOnly) "实际截取 ${photos.size} 张 · 仅供主人查看"
                else "共保存 ${photos.size} 张 · 这个角色的私人相册",
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 8.dp),
                color = LuluColors.Muted, fontSize = 12.sp)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(108.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(photos, key = RoleAlbumPhoto::id) { photo ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { selected = photo },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (observationsOnly) {
                                MomentImageAttachment(photo.uri,
                                    modifier = Modifier.fillMaxWidth().height(150.dp),
                                    contentScale = ContentScale.Fit,
                                    contentDescription = "历史屏幕截图",
                                    previewMaxEdge = 320)
                            } else {
                                LuluProfileAvatar(photo.uri, "图", 100)
                            }
                            Text(if (observationsOnly) screenRecordLocalTime(photo.savedAt)
                                else photo.source.substringBefore(" · "),
                                maxLines = 1, fontSize = 11.sp,
                                color = LuluColors.Muted,
                                modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                }
            }
        }
    }
    selected?.let { photo ->
        if (observationsOnly) {
            ScreenRecordViewer(photo,
                onClose = { selected = null },
                onDelete = {
                    RolePhotoAlbumStore.delete(context, characterId, photo.id)
                    selected = null
                })
        } else AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("相册 · ${photo.source.substringBefore(" · ")}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    LuluProfileAvatar(photo.uri, "照片", 240)
                    if (photo.caption.isNotBlank())
                        Text(photo.caption, style = MaterialTheme.typography.bodyMedium)
                    Text(photo.savedAt.take(16).replace('T', ' '),
                        fontSize = 12.sp, color = LuluColors.Muted)
                    if (photo.sourceUrl.isNotBlank())
                        Text("来源：${photo.sourceUrl.take(160)}",
                            fontSize = 11.sp, color = LuluColors.Muted)
                }
            },
            confirmButton = {
                TextButton(onClick = { selected = null }) { Text("返回") }
            },
            dismissButton = {
                TextButton(onClick = {
                    RolePhotoAlbumStore.delete(context, characterId, photo.id)
                    selected = null
                }) { Text("删除照片") }
            },
        )
    }
}

/** Never crop or reformat an archived screenshot. Keep its original pixel ratio;
 * only the presentation changes to a full-screen, edge-to-edge photo viewer. */
@Composable
private fun ScreenRecordViewer(
    photo: RoleAlbumPhoto,
    onClose: () -> Unit,
    onDelete: () -> Unit,
) {
    var scale by remember(photo.id) { mutableFloatStateOf(1f) }
    var shiftX by remember(photo.id) { mutableFloatStateOf(0f) }
    var shiftY by remember(photo.id) { mutableFloatStateOf(0f) }
    var viewport by remember(photo.id) { mutableStateOf(IntSize.Zero) }
    var showControls by remember(photo.id) { mutableStateOf(true) }
    val resetZoom = {
        scale = 1f
        shiftX = 0f
        shiftY = 0f
    }
    val zoom = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = nextScale
        shiftX = clampScreenRecordPan(shiftX + panChange.x, viewport.width, nextScale)
        shiftY = clampScreenRecordPan(shiftY + panChange.y, viewport.height, nextScale)
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // No 94%-height card, inset frame or image-sized header/footer: a phone
        // screenshot needs the same portrait viewport as the phone itself.
        Box(Modifier.fillMaxSize().background(Color(0xFF101012))) {
            Box(
                Modifier.fillMaxSize()
                    .onSizeChanged { viewport = it }
                    .transformable(zoom)
                    .pointerInput(photo.id) {
                        detectTapGestures(
                            onTap = { showControls = !showControls },
                            onDoubleTap = {
                                if (scale > 1f) resetZoom() else {
                                    scale = 2.5f
                                    shiftX = 0f
                                    shiftY = 0f
                                }
                            },
                        )
                    },
            ) {
                MomentImageAttachment(
                    photo.uri,
                    modifier = Modifier.fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = shiftX
                            translationY = shiftY
                        },
                    contentScale = ContentScale.Fit,
                    contentDescription = "完整原始手机截屏，双指缩放，轻点隐藏工具栏",
                    cornerRadius = 0.dp,
                    backgroundColor = Color(0xFF101012),
                    foregroundColor = Color.White,
                )
            }
            if (showControls) {
                // Controls float OVER the image instead of shrinking it.
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter),
                    color = Color.Black.copy(alpha = 0.74f),
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(onClick = onClose) { Text("‹ 返回", color = Color.White) }
                        Text("屏幕记录", fontWeight = FontWeight.SemiBold,
                            color = Color.White, fontSize = 15.sp)
                        TextButton(onClick = { showControls = false }) { Text("全屏", color = Color.White) }
                    }
                }
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    color = Color.Black.copy(alpha = 0.78f),
                ) {
                    Column(
                        Modifier.fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        if (photo.caption.isNotBlank()) Text(
                            screenRecordLocalTime(photo.savedAt) + " · " + photo.caption,
                            maxLines = 2, fontSize = 11.sp, color = Color.White,
                        )
                        Text("历史真实截屏（非实时） · 双指缩放 · 轻点隐藏工具栏",
                            fontSize = 11.sp, color = Color.White.copy(alpha = 0.70f))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = onDelete) {
                                Text("删除这张", color = Color.White)
                            }
                            TextButton(onClick = resetZoom) {
                                Text("复原大小", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Keep a pinch-zoomed capture within its viewport. At 1x it is always centred. */
internal fun clampScreenRecordPan(offsetPx: Float, viewportPx: Int, scale: Float): Float {
    if (scale <= 1f || viewportPx <= 0) return 0f
    val maxOffset = viewportPx * (scale - 1f) / 2f
    return offsetPx.coerceIn(-maxOffset, maxOffset)
}

private val ScreenRecordTimeFormat = DateTimeFormatter.ofPattern("M/d HH:mm")
private fun screenRecordLocalTime(iso: String): String = runCatching {
    Instant.parse(iso).atZone(ZoneId.systemDefault()).format(ScreenRecordTimeFormat)
}.getOrDefault(iso.take(16).replace('T', ' '))
