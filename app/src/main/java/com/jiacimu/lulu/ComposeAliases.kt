package com.jiacimu.lulu

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** Package-local helper for call sites that use a solid-color border. */
fun BorderStroke(width: Dp, color: Color): androidx.compose.foundation.BorderStroke =
    androidx.compose.foundation.BorderStroke(width, color)
