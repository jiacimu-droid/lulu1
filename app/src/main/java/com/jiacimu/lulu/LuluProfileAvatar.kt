package com.jiacimu.lulu

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlin.math.max
import kotlin.math.roundToInt

private const val LULU_REMOTE_AVATAR_MAX_BYTES = 3 * 1024 * 1024
private const val LULU_AVATAR_MAX_EDGE = 768
private const val LULU_AVATAR_PREVIEW_MAX_EDGE = 192
private const val LULU_AVATAR_PREVIEW_DISPLAY_MAX_DP = 112

// Keep bounded full-size portraits for settings/detail pages.
private val luluAvatarBitmapCache = object : LruCache<String, Bitmap>(48 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
}

// Chat lists only need tiny portraits. Keeping a second small cache prevents the visible avatar from
// dropping back to initials while a larger source bitmap is reloaded or evicted.
private val luluAvatarPreviewCache = object : LruCache<String, Bitmap>(8 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
}

private fun cachedLuluAvatarFullBitmap(value: String?): Bitmap? {
    val key = value?.takeIf(String::isNotBlank) ?: return null
    return synchronized(luluAvatarBitmapCache) { luluAvatarBitmapCache.get(key) }
}

private fun cachedLuluAvatarPreviewBitmap(value: String?): Bitmap? {
    val key = value?.takeIf(String::isNotBlank) ?: return null
    return synchronized(luluAvatarPreviewCache) { luluAvatarPreviewCache.get(key) }
}

private fun cachedLuluAvatarBitmap(value: String?): Bitmap? =
    cachedLuluAvatarFullBitmap(value) ?: cachedLuluAvatarPreviewBitmap(value)

@Composable
internal fun LuluProfileAvatar(
    imageUri: String?,
    fallback: String,
    size: Int,
    modifier: Modifier = Modifier,
) {
    val avatarShape = RoundedCornerShape((size * 0.22f).dp)
    val context = LocalContext.current
    val smallDisplay = size <= LULU_AVATAR_PREVIEW_DISPLAY_MAX_DP
    // Small chat/list avatars can stay on the persistent 192px preview. Large cards/portraits may
    // show that preview immediately to avoid a blank flash, but must continue loading the full image.
    val initialBitmap = remember(imageUri, smallDisplay) {
        cachedLuluAvatarFullBitmap(imageUri)
            ?: cachedLuluAvatarPreviewBitmap(imageUri)
            ?: loadStoredLuluAvatarPreview(context, imageUri)
    }
    val bitmap by produceState<Bitmap?>(initialValue = initialBitmap, imageUri, smallDisplay) {
        val uri = imageUri?.takeIf(String::isNotBlank)
        if (uri == null) {
            value = null
            return@produceState
        }

        cachedLuluAvatarFullBitmap(uri)?.let {
            value = it
            return@produceState
        }

        if (smallDisplay) {
            cachedLuluAvatarPreviewBitmap(uri)?.let {
                value = it
                return@produceState
            }
            loadStoredLuluAvatarPreview(context, uri)?.let {
                value = it
                return@produceState
            }
            value = withContext(Dispatchers.IO) { loadLuluAvatarBitmap(context, uri, preferPreview = true) }
            return@produceState
        }

        // A large portrait is never allowed to settle on the 192px preview. Keep the preview as the
        // temporary visible placeholder, then replace it with the bounded full-resolution bitmap.
        if (value == null) {
            value = cachedLuluAvatarPreviewBitmap(uri) ?: loadStoredLuluAvatarPreview(context, uri)
        }
        val full = withContext(Dispatchers.IO) { loadLuluAvatarBitmap(context, uri, preferPreview = false) }
        if (full != null) value = full
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

private fun loadLuluAvatarBitmap(context: Context, value: String, preferPreview: Boolean): Bitmap? {
    cachedLuluAvatarFullBitmap(value)?.let { return it }
    if (preferPreview) {
        cachedLuluAvatarPreviewBitmap(value)?.let { return it }
        loadStoredLuluAvatarPreview(context, value)?.let { return it }
    }
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
    val bitmap = when (uri.scheme?.lowercase()) {
        "https" -> loadLuluRemoteAvatarBitmap(context, value)
        "http" -> null
        else -> decodeContentUriBounded(context, uri)
    } ?: return null
    cacheLuluAvatarBitmap(context, value, bitmap)
    return bitmap
}

private fun cacheLuluAvatarBitmap(context: Context, value: String, bitmap: Bitmap) {
    synchronized(luluAvatarBitmapCache) { luluAvatarBitmapCache.put(value, bitmap) }
    val preview = createLuluAvatarPreview(bitmap)
    synchronized(luluAvatarPreviewCache) { luluAvatarPreviewCache.put(value, preview) }
    persistLuluAvatarPreview(context, value, preview)
}

private fun createLuluAvatarPreview(bitmap: Bitmap): Bitmap {
    val longest = max(bitmap.width, bitmap.height)
    if (longest <= LULU_AVATAR_PREVIEW_MAX_EDGE) return bitmap
    val scale = LULU_AVATAR_PREVIEW_MAX_EDGE.toFloat() / longest.toFloat()
    val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}

private fun luluAvatarPreviewFile(context: Context, value: String): File {
    val directory = File(context.filesDir, "lulu-avatar-previews").apply { mkdirs() }
    return File(directory, "${luluAvatarCacheKey(value)}.jpg")
}

private fun loadStoredLuluAvatarPreview(context: Context, value: String?): Bitmap? {
    val key = value?.takeIf(String::isNotBlank) ?: return null
    cachedLuluAvatarPreviewBitmap(key)?.let { return it }
    val file = luluAvatarPreviewFile(context, key)
    if (!file.isFile || file.length() <= 0L) return null
    val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    if (bitmap == null) {
        file.delete()
        return null
    }
    synchronized(luluAvatarPreviewCache) { luluAvatarPreviewCache.put(key, bitmap) }
    return bitmap
}

private fun persistLuluAvatarPreview(context: Context, value: String, bitmap: Bitmap) {
    val file = luluAvatarPreviewFile(context, value)
    if (file.isFile && file.length() > 0L) return
    runCatching {
        file.outputStream().buffered().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output))
        }
    }.onFailure { file.delete() }
}

private fun decodeContentUriBounded(context: Context, uri: Uri): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { input -> BitmapFactory.decodeStream(input, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateAvatarSample(bounds.outWidth, bounds.outHeight)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, options)
    }
}.getOrNull()

private fun loadLuluRemoteAvatarBitmap(context: Context, value: String): Bitmap? {
    val directory = File(context.cacheDir, "lulu-avatar-images")
    if (!directory.exists() && !directory.mkdirs()) return null
    val cacheFile = File(directory, "${luluAvatarCacheKey(value)}.img")
    if (cacheFile.isFile) {
        decodeFileBounded(cacheFile)?.let { bitmap ->
            cacheLuluAvatarBitmap(context, value, bitmap)
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
        val bitmap = decodeBytesBounded(bytes) ?: return null
        runCatching { cacheFile.writeBytes(bytes) }
        cacheLuluAvatarBitmap(context, value, bitmap)
        bitmap
    } catch (_: Throwable) {
        null
    } finally {
        connection.disconnect()
    }
}

private fun decodeFileBounded(file: File): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = calculateAvatarSample(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )
}.getOrNull()

private fun decodeBytesBounded(bytes: ByteArray): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply {
            inSampleSize = calculateAvatarSample(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )
}.getOrNull()

private fun calculateAvatarSample(width: Int, height: Int): Int {
    var sample = 1
    var sampledWidth = width
    var sampledHeight = height
    while (sampledWidth > LULU_AVATAR_MAX_EDGE * 2 || sampledHeight > LULU_AVATAR_MAX_EDGE * 2) {
        sample *= 2
        sampledWidth = width / sample
        sampledHeight = height / sample
    }
    return sample.coerceAtLeast(1)
}

private fun readLuluAvatarBytes(input: InputStream): ByteArray? {
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
    val bitmap by produceState<Bitmap?>(initialValue = cachedLuluAvatarFullBitmap(imageUri), imageUri) {
        val source = imageUri?.takeIf(String::isNotBlank)
        value = if (source == null) null else withContext(Dispatchers.IO) {
            loadLuluAvatarBitmap(context, source, preferPreview = false)
        }
    }
    Surface(
        modifier = modifier,
        color = Color(0xFFF4F4F4),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0xFFE7E7E7)),
    ) {
        if (bitmap != null) {
            Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.AddAPhoto, "插入照片", tint = Color(0xFF7A7A7E))
            }
        }
    }
}
