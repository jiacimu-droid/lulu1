package com.jiacimu.lulu

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun LuluProfileAvatar(
    imageUri: String?,
    fallback: String,
    size: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bitmap = remember(imageUri) {
        imageUri?.takeIf(String::isNotBlank)?.let { value ->
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(value))?.use(BitmapFactory::decodeStream)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Surface(
        modifier = modifier.size(size.dp),
        shape = CircleShape,
        color = Color(0xFFF4F4F4),
        border = BorderStroke(1.dp, Color(0xFFE7E7E7)),
    ) {
        if (bitmap != null) {
            Image(bitmap, null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(fallback.take(2).ifBlank { "主" }, fontWeight = FontWeight.Bold, fontSize = (size / 2.8).sp, color = Color(0xFF1D1D1F))
            }
        }
    }
}

@Composable
internal fun LuluAvatarPicker(
    imageUri: String?,
    fallback: String,
    size: Int = 84,
    onSelected: (String) -> Unit,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onSelected(uri.toString())
        }
    }
    Box(contentAlignment = Alignment.BottomEnd) {
        LuluProfileAvatar(
            imageUri = imageUri,
            fallback = fallback,
            size = size,
            modifier = Modifier.clickable {
                launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
        Surface(shape = CircleShape, color = Color(0xFF292929), modifier = Modifier.size(28.dp)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.AddAPhoto, "选择头像图片", tint = Color.White, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
internal fun LuluSelectedPhoto(imageUri: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember(imageUri) {
        imageUri?.takeIf(String::isNotBlank)?.let { value ->
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(value))?.use(BitmapFactory::decodeStream)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Surface(
        modifier = modifier,
        color = Color(0xFFF4F4F4),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0xFFE7E7E7)),
    ) {
        if (bitmap != null) {
            Image(bitmap, null, Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.AddAPhoto, "插入照片", tint = Color(0xFF7A7A7E))
            }
        }
    }
}
