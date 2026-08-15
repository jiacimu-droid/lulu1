package androidx.compose.foundation.lazy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 *
 * The story-history page is grouped by ten scenes. The selector is rendered at the upper-right of
 * the history list; choosing a range keeps only those ten scene cards visible. Scene reading itself
 * remains in ApocalypseSurvivalV5App, where previous/next scene navigation can continue across ranges.
 */
internal fun LazyListScope.items(
    scenes: List<ApocalypseV5ReadableScene>,
    key: ((ApocalypseV5ReadableScene) -> Any)? = null,
    itemContent: @Composable LazyItemScope.(ApocalypseV5ReadableScene) -> Unit,
) {
    if (scenes.isEmpty()) return

    val maxScene = scenes.maxOf(ApocalypseV5ReadableScene::sceneNumber).coerceAtLeast(1)
    val lastBucket = ((maxScene - 1) / 10) * 10 + 1
    val buckets = generateSequence(1) { current ->
        (current + 10).takeIf { it <= lastBucket }
    }.toList()

    if (apocalypseHistoryLastMaxSceneV5 != maxScene || apocalypseHistoryBucketStartV5.intValue !in buckets) {
        apocalypseHistoryLastMaxSceneV5 = maxScene
        apocalypseHistoryBucketStartV5.intValue = lastBucket
    }

    val selectedStart = apocalypseHistoryBucketStartV5.intValue
    val selectedEnd = selectedStart + 9
    val visibleScenes = scenes.filter { it.sceneNumber in selectedStart..selectedEnd }

    item(key = "apocalypse-history-bucket-selector") {
        var expanded by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Box {
                TextButton(onClick = { expanded = true }) {
                    Text("$selectedStart–$selectedEnd ▾", fontSize = 12.sp)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    buckets.forEach { start ->
                        val end = start + 9
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (start == selectedStart) "✓ $start–$end" else "$start–$end",
                                    fontSize = 13.sp,
                                )
                            },
                            onClick = {
                                apocalypseHistoryBucketStartV5.intValue = start
                                expanded = false
                            },
                        )
                    }
                }
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
