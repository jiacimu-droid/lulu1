package com.jiacimu.lulu.design

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.LuluCssSheet
import com.jiacimu.lulu.data.LuluCssStore

val LocalLuluCssSheet = compositionLocalOf { LuluCssSheet.parse("") }
val LocalLuluCssScope = compositionLocalOf { "Home" }

/** A named native layout hook; works on touch targets without overlaying controls. */
@Composable
fun Modifier.luluCssElement(element: String): Modifier {
    val style = LocalLuluCssSheet.current.element(LocalLuluCssScope.current, element)
    val x = LuluCssSheet.dp(style["offset-x"] ?: style["left"])?.coerceIn(-360f, 360f) ?: 0f
    val y = LuluCssSheet.dp(style["offset-y"] ?: style["top"])?.coerceIn(-360f, 360f) ?: 0f
    val padding = LuluCssSheet.dp(style["padding"])?.coerceIn(0f, 60f) ?: 0f
    return this.offset(x.dp, y.dp).padding(padding.dp)
}

/** Downsampled on-device image behind the real Compose content. */
@Composable
fun LuluCssBackground(modifier: Modifier = Modifier.fillMaxSize()) {
    val token = LocalLuluCssSheet.current.imageToken(LocalLuluCssScope.current)
    val file = LuluCssStore.assetFile(token)
    val image = remember(file?.path, file?.lastModified()) {
        if (file == null) null else runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var factor = 1
            while (bounds.outWidth / factor > 1536 || bounds.outHeight / factor > 1536) {
                factor *= 2
            }
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inSampleSize = factor
            })?.asImageBitmap()
        }.getOrNull()
    }
    if (image != null) Image(
        bitmap = image, contentDescription = null,
        modifier = modifier, contentScale = ContentScale.Crop,
    )
}
