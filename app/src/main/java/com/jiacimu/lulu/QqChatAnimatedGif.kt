package com.jiacimu.lulu

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/** Display the actual GIF frames; generic avatar bitmap loaders only decode frame one. */
@Composable
internal fun QqChatAnimatedGif(imageUri: String) {
    AndroidView(
        modifier = Modifier.size(154.dp),
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
            }
        },
        update = { view ->
            if (view.tag != imageUri) {
                (view.drawable as? AnimatedImageDrawable)?.stop()
                val file = Uri.parse(imageUri).path?.let(::File)
                // This path is provided by the app's existing persisted chat
                // image URI and is never passed to an external process.
                val animation = runCatching {
                    require(file?.isFile == true)
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
                }.getOrNull()
                view.setImageDrawable(animation)
                (animation as? AnimatedImageDrawable)?.start()
                view.tag = imageUri
            }
        },
    )
}
