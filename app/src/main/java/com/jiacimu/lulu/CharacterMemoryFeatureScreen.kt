package com.jiacimu.lulu

import androidx.compose.runtime.Composable

/** One memory page; personal navigation only changes its owner scope. */
@Composable
fun CharacterMemoryFeatureScreen(onBack: () -> Unit, personalCharacterId: String? = null,
    startInCore: Boolean = false, startInTimeline: Boolean = false) {
    MemoryFeatureScreen(onBack, personalCharacterId, startInCore, startInTimeline = startInTimeline)
}
