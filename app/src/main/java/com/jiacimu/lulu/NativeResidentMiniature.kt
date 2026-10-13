package com.jiacimu.lulu

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import com.jiacimu.lulu.data.DigitalWorldResident
import com.jiacimu.lulu.games.WorldVector

/** Reuses the same full-body resident model seen inside the world. */
@Composable
internal fun NativeResidentMiniature(resident: DigitalWorldResident, modifier: Modifier) {
    Canvas(modifier) {
        val factor = minOf(size.width / 60f, size.height / 100f)
        withTransform({ scale(factor, factor, pivot = Offset.Zero) }) {
            drawNativeWorldResidents(listOf(DigitalNativeResidentPose(resident,
                WorldVector(30f, 92f), false, 1f)), .5f)
        }
    }
}
