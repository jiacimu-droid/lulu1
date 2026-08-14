package com.jiacimu.lulu.games

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope

/**
 * Specialized overload for the V5 readable-scene list used by StoryHistory.
 *
 * The existing page can keep its scene-card renderer while this layer turns a long flat list into
 * novel-like ten-scene ranges. It intentionally applies only to ApocalypseV5ReadableScene; all
 * other LazyColumn `items(...)` calls continue using the normal Compose overload.
 */
internal fun LazyListScope.items(
    items: List<ApocalypseV5ReadableScene>,
    @Suppress("UNUSED_PARAMETER") key: ((ApocalypseV5ReadableScene) -> Any)? = null,
    itemContent: @Composable LazyItemScope.(ApocalypseV5ReadableScene) -> Unit,
) {
    if (items.isEmpty()) return

    item(key = "apocalypse-v5-history-range-navigator") {
        val minScene = items.minOf { it.sceneNumber }
        val maxScene = items.maxOf { it.sceneNumber }
        val firstBucketStart = ((minScene - 1) / 10) * 10 + 1
        val latestBucketStart = ((maxScene - 1) / 10) * 10 + 1
        val bucketStarts = remember(minScene, maxScene) {
            generateSequence(firstBucketStart) { previous ->
                (previous + 10).takeIf { it <= latestBucketStart }
            }.toList()
        }
        var selectedBucketStart by remember(minScene, maxScene) { mutableIntStateOf(latestBucketStart) }
        if (selectedBucketStart !in bucketStarts) selectedBucketStart = latestBucketStart
        val selectedEnd = selectedBucketStart + 9
        val visibleScenes = items.filter { it.sceneNumber in selectedBucketStart..selectedEnd }

        Column {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 10.dp),
            ) {
                bucketStarts.forEach { start ->
                    val end = (start + 9).coerceAtMost(maxScene)
                    FilterChip(
                        selected = selectedBucketStart == start,
                        onClick = { selectedBucketStart = start },
                        label = { Text("$start–$end") },
                        modifier = Modifier.padding(end = 7.dp),
                    )
                }
            }

            visibleScenes.forEachIndexed { index, scene ->
                itemContent(scene)
                if (index < visibleScenes.lastIndex) Spacer(Modifier.height(10.dp))
            }
        }
    }
}
