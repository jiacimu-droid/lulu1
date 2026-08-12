package com.jiacimu.lulu

import android.content.Intent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

private const val LULU_REMOTE_AVATAR_MAX_BYTES = 3 * 1024 * 1024

// Keep enough portraits hot for long visual-novel scenes. A 512px ARGB bitmap is roughly 1 MiB;
// 32 MiB avoids repeatedly evicting the small cast and re-showing placeholders between dialogue beats.
private val luluAvatarBitmapCache = object : LruCache<String, Bitmap>(32 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
}

private fun cachedLuluAvatarBitmap(value: String?): Bitmap? {
    val key = value?.takeIf(String::isNotBlank) ?: return null
    return synchronized(luluAvatarBitmapCache) { luluAvatarBitmapCache.get(key) }
}

@Composable
internal fun LuluProfileAvatar(
    imageUri: String?,
    fallback: String,
    size: Int,
    modifier: Modifier = Modifier,
) {
    val avatarShape = RoundedCornerShape((size * 0.22f).dp)
    val context = LocalContext.current
    // Important for the visual-novel stage: do not reset an already-cached portrait to null for one
    // composition frame merely because the speaker changed. That null frame was the visible "flash"
    // when advancing from the player's line to another speaker.
    val initialBitmap = remember(imageUri) { cachedLuluAvatarBitmap(imageUri) }
    val bitmap by produceState<Bitmap?>(initialValue = initialBitmap, imageUri) {
        val uri = imageUri?.takeIf(String::isNotBlank)
        if (uri == null) {
            value = null
            return@produceState
        }
        cachedLuluAvatarBitmap(uri)?.let {
            value = it
            return@produceState
        }
        value = withContext(Dispatchers.IO) { loadLuluAvatarBitmap(context, uri) }
    }
    Surface(
        modifier = modifier.size(size.dp),
        shape = avatarShape,
        color = Color(0xFFF4F4F4),
        border = BorderStroke(1.dp, Color(0xFFE7E7E7)),
    ) {
        if (bitmap != null) {
            Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize().clip(avatarShape), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(fallback.take(2).ifBlank { "主" }, fontWeight = FontWeight.Bold, fontSize = (size / 2.8).sp, color = Color(0xFF1D1D1F))
            }
        }
    }
}

private fun loadLuluAvatarBitmap(context: Context, value: String): Bitmap? {
    synchronized(luluAvatarBitmapCache) { luluAvatarBitmapCache.get(value) }?.let { return it }
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
    val bitmap = when (uri.scheme?.lowercase()) {
        "https" -> loadLuluRemoteAvatarBitmap(context, value)
        "http" -> null
        else -> runCatching {
            context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
        }.getOrNull()
    } ?: return null
    synchronized(luluAvatarBitmapCache) { luluAvatarBitmapCache.put(value, bitmap) }
    return bitmap
}

private fun loadLuluRemoteAvatarBitmap(context: Context, value: String): Bitmap? {
    val directory = File(context.cacheDir, "lulu-avatar-images")
    if (!directory.exists() && !directory.mkdirs()) return null
    val cacheFile = File(directory, "${luluAvatarCacheKey(value)}.img")
    if (cacheFile.isFile) {
        BitmapFactory.decodeFile(cacheFile.absolutePath)?.let { bitmap ->
            synchronized(luluAvatarBitmapCache) { luluAvatarBitmapCache.put(value, bitmap) }
            return bitmap
        }
        cacheFile.delete()
    }

    val connection = runCatching { URL(value).openConnection() as? HttpsURLConnection }.getOrNull() ?: return null
    return try {
        connection.connectTimeout = 5_000
        connection.readTimeout = 8_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Lulu-Android/1.0")
        if (connection.responseCode !in 200..299) return null
        val declaredLength = connection.contentLengthLong
        if (declaredLength > LULU_REMOTE_AVATAR_MAX_BYTES) return null
        val bytes = connection.inputStream.use(::readLuluAvatarBytes) ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        runCatching { cacheFile.writeBytes(bytes) }
        synchronized(luluAvatarBitmapCache) { luluAvatarBitmapCache.put(value, bitmap) }
        bitmap
    } catch (_: Throwable) {
        null
    } finally {
        connection.disconnect()
    }
}

private fun readLuluAvatarBytes(input: java.io.InputStream): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > LULU_REMOTE_AVATAR_MAX_BYTES) return null
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun luluAvatarCacheKey(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

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
        Surface(
            shape = CircleShape,
            color = Color(0xFF292929),
            contentColor = Color.White,
            modifier = Modifier.size(28.dp),
        ) {
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
