package com.jiacimu.lulu

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Actual offline stationery artwork, not a screenshot with fake controls.
 * The image contains only the book, its binding, paper texture and botanical collage.
 * Clickable dates, handwritten content, editing and page turns render as separate layers.
 * A single downsampled WebP is decoded once per composition and never fetched remotely.
 */
@Composable
internal fun SelfThoughtNotebookArtwork(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val image = remember(context.applicationContext) {
        runCatching {
            val encoded = context.assets.open("selfpreface/thought-notebook.webp.b64")
                .bufferedReader().use { it.readText() }
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            require(bytes.size in 1..200_000) { "Notebook artwork is missing or oversized" }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    // The offline artwork is pre-cropped to a real 420:603 book aspect ratio.
    // No device-sized stretching and no repainting bitmap on every page turn.
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFF4E9D8),
        shadowElevation = 8.dp,
        border = BorderStroke(0.6.dp, Color(0xFFD5BBA0).copy(alpha = .45f)),
    ) {
        Box(Modifier.fillMaxSize()) {
            image?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
            }
            content()
        }
    }
}
