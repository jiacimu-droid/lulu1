package com.jiacimu.lulu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import com.jiacimu.lulu.design.LuluColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

private sealed interface MomentAttachmentState {
    data object Loading : MomentAttachmentState
    data class Ready(val bitmap: Bitmap) : MomentAttachmentState
    data object Failed : MomentAttachmentState
}

@Composable
internal fun MomentImageAttachment(
    imageUri: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by produceState<MomentAttachmentState>(MomentAttachmentState.Loading, imageUri) {
        value = MomentAttachmentState.Loading
        val bitmap = withContext(Dispatchers.IO) { loadMomentAttachmentBitmap(context, imageUri) }
        value = bitmap?.let(MomentAttachmentState::Ready) ?: MomentAttachmentState.Failed
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = LuluColors.CardStrong,
    ) {
        when (val current = state) {
            MomentAttachmentState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            is MomentAttachmentState.Ready -> Image(
                bitmap = current.bitmap.asImageBitmap(),
                contentDescription = "朋友圈图片",
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop,
            )
            MomentAttachmentState.Failed -> Column(
                modifier = Modifier.fillMaxSize().padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Outlined.BrokenImage,
                    contentDescription = null,
                    tint = LuluColors.Muted,
                    modifier = Modifier.size(30.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text("图片附件加载失败", color = LuluColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("原动态仍保留，不会伪装成空白图片。", color = LuluColors.Muted, fontSize = 10.sp)
            }
        }
    }
}

private fun loadMomentAttachmentBitmap(context: Context, value: String): Bitmap? {
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
    return when (uri.scheme?.lowercase()) {
        "https" -> loadMomentRemoteBitmap(value)
        "http" -> null
        else -> decodeMomentContentUri(context, uri)
    }
}

private fun decodeMomentContentUri(context: Context, uri: Uri): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { input -> BitmapFactory.decodeStream(input, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val options = BitmapFactory.Options().apply {
        inSampleSize = momentSampleSize(bounds.outWidth, bounds.outHeight)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    context.contentResolver.openInputStream(uri)?.use { input -> BitmapFactory.decodeStream(input, null, options) }
}.getOrNull()

private fun loadMomentRemoteBitmap(value: String): Bitmap? {
    val connection = runCatching { URL(value).openConnection() as? HttpsURLConnection }.getOrNull() ?: return null
    return try {
        connection.connectTimeout = 7_000
        connection.readTimeout = 12_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Lulu-Android/1.0")
        if (connection.responseCode !in 200..299) return null
        if (connection.contentLengthLong > MOMENT_IMAGE_MAX_BYTES) return null
        val bytes = connection.inputStream.use(::readMomentBytes) ?: return null
        decodeMomentBytes(bytes)
    } catch (_: Throwable) {
        null
    } finally {
        connection.disconnect()
    }
}

private fun decodeMomentBytes(bytes: ByteArray): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply {
            inSampleSize = momentSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )
}.getOrNull()

private fun momentSampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (width / sample > MOMENT_IMAGE_MAX_EDGE * 2 || height / sample > MOMENT_IMAGE_MAX_EDGE * 2) sample *= 2
    return sample.coerceAtLeast(1)
}

private fun readMomentBytes(input: InputStream): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > MOMENT_IMAGE_MAX_BYTES) return null
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private const val MOMENT_IMAGE_MAX_BYTES = 8 * 1024 * 1024
private const val MOMENT_IMAGE_MAX_EDGE = 1_600
