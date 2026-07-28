package com.jiacimu.lulu

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll as foundationHorizontalScroll
import androidx.compose.ui.Modifier

internal fun Modifier.horizontalScroll(state: ScrollState): Modifier =
    this.foundationHorizontalScroll(state)
