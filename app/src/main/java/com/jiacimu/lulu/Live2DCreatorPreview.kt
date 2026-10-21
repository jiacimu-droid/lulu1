package com.jiacimu.lulu

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shows the creator's complete portrait, not the sliced texture atlas.
 * Decode downsampled so even a big PNG does not exhaust Android RAM.
 */
@Composable
internal fun Live2DCreatorPreview(file: File?, modifier: Modifier = Modifier, fallback: @Composable () -> Unit) {
    val preview by produceState<Bitmap?>(initialValue = null, file?.absolutePath) {
        value = withContext(Dispatchers.IO) {
            val target = file?.takeIf(File::isFile) ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            val largest = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (largest / sample > 1440) sample *= 2
            runCatching {
                BitmapFactory.decodeFile(target.absolutePath, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            }.getOrNull()
        }
    }
    if (preview == null) {
        fallback()
    } else {
        Image(
            bitmap = preview!!.asImageBitmap(),
            contentDescription = "Live2D 模型作者提供的完整形象预览",
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            alignment = Alignment.Center,
        )
    }
}
