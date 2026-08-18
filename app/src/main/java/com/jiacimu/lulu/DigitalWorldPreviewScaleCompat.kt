package com.jiacimu.lulu

import androidx.compose.ui.Modifier

/** Local preview helper; room stickers themselves render at their authored 2D size. */
internal fun Modifier.graphicsLayer(scaleX: Float, scaleY: Float): Modifier = this
