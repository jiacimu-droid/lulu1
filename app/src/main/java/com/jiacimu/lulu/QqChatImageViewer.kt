package com.jiacimu.lulu

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fullscreen media viewer shared by outgoing and incoming chat photo bubbles. */
@Composable
internal fun QqChatImageViewer(imageUri: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, imageUri) {
        value = withContext(Dispatchers.IO) {
            loadLuluAvatarBitmap(context, imageUri, preferPreview = false)
        }
    }
    var transform by remember(imageUri) { mutableStateOf(QqPhotoTransform()) }
    var viewport by remember(imageUri) { mutableStateOf(IntSize.Zero) }
    var showClose by remember(imageUri) { mutableStateOf(true) }
    val isLocalGif = android.os.Build.VERSION.SDK_INT >= 28 &&
        imageUri.substringBefore('?').endsWith(".gif", ignoreCase = true) &&
        imageUri.startsWith("file://")
    val imageWidth = bitmap?.width ?: 0
    val imageHeight = bitmap?.height ?: 0

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { viewport = it }
                .pointerInput(imageUri, viewport, imageWidth, imageHeight) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        transform = nextQqPhotoTransform(
                            transform, zoom, centroid.x, centroid.y, pan.x, pan.y,
                            viewport.width, viewport.height, imageWidth, imageHeight,
                        )
                    }
                }
                .pointerInput(imageUri, viewport, imageWidth, imageHeight) {
                    detectTapGestures(
                        onTap = { showClose = !showClose },
                        onDoubleTap = { focal ->
                            val target = if (transform.scale > 1.01f) 1f else 2.5f
                            transform = nextQqPhotoTransform(
                                transform, target / transform.scale,
                                focal.x, focal.y, 0f, 0f,
                                viewport.width, viewport.height, imageWidth, imageHeight,
                            )
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val imageModifier = Modifier.fillMaxSize().graphicsLayer(
                scaleX = transform.scale,
                scaleY = transform.scale,
                translationX = transform.x,
                translationY = transform.y,
            )
            when {
                isLocalGif -> QqChatAnimatedGif(imageUri, imageModifier)
                bitmap != null -> Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "聊天图片",
                    modifier = imageModifier,
                    contentScale = ContentScale.Fit,
                )
                else -> Text("图片暂时无法加载", color = Color.White)
            }
            if (showClose) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(8.dp)
                        .size(48.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "关闭图片", tint = Color.White)
                }
            }
        }
    }
}
