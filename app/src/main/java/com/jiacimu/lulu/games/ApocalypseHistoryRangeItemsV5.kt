package androidx.compose.foundation.lazy

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.games.ApocalypseV5ReadableScene

private val apocalypseHistoryBucketStartV5 = mutableIntStateOf(1)
private var apocalypseHistoryLastMaxSceneV5: Int = -1

/**
 * Specialized overload intentionally lives beside Compose's explicitly imported lazy `items`.
 * ApocalypseSurvivalV5App imports androidx.compose.foundation.lazy.items, so keeping this overload
 * in that same package guarantees the V5 story-history List<ApocalypseV5ReadableScene> call selects
 * this more-specific overload instead of silently falling back to the generic Compose list renderer.
 */
internal fun LazyListScope.items(
    scenes: List<ApocalypseV5ReadableScene>,
    key: ((ApocalypseV5ReadableScene) -> Any)? = null,
    itemContent: @Composable LazyItemScope.(ApocalypseV5ReadableScene) -> Unit,
) {
    if (scenes.isEmpty()) return

    val minScene = scenes.minOf(ApocalypseV5ReadableScene::sceneNumber)
    val maxScene = scenes.maxOf(ApocalypseV5ReadableScene::sceneNumber)
    val firstBucket = ((minScene - 1).coerceAtLeast(0) / 10) * 10 + 1
    val lastBucket = ((maxScene - 1).coerceAtLeast(0) / 10) * 10 + 1
    val buckets = generateSequence(firstBucket) { current ->
        (current + 10).takeIf { it <= lastBucket }
    }.toList()

    if (apocalypseHistoryLastMaxSceneV5 != maxScene || apocalypseHistoryBucketStartV5.intValue !in buckets) {
        apocalypseHistoryLastMaxSceneV5 = maxScene
        apocalypseHistoryBucketStartV5.intValue = lastBucket
    }

    val selectedStart = apocalypseHistoryBucketStartV5.intValue
    val selectedEnd = minOf(selectedStart + 9, maxScene)
    val visibleScenes = scenes.filter { it.sceneNumber in selectedStart..selectedEnd }

    item(key = "apocalypse-history-bucket-selector") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            buckets.forEach { start ->
                val end = minOf(start + 9, maxScene)
                FilterChip(
                    selected = selectedStart == start,
                    onClick = { apocalypseHistoryBucketStartV5.intValue = start },
                    label = { Text("$start–$end", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = androidx.compose.ui.graphics.Color(0xFFDDE7E0),
                        selectedLabelColor = androidx.compose.ui.graphics.Color(0xFF526D5E),
                    ),
                )
            }
        }
    }

    items(
        count = visibleScenes.size,
        key = { index -> key?.invoke(visibleScenes[index]) ?: visibleScenes[index].sceneNumber },
    ) { index ->
        itemContent(visibleScenes[index])
    }
}
