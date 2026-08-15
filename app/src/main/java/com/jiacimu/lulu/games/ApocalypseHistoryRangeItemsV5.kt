package androidx.compose.foundation.lazy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.games.ApocalypseV5ReadableScene

private val apocalypseHistoryBucketStartV5 = mutableIntStateOf(1)
private var apocalypseHistoryLastMaxSceneV5: Int = -1

private object ApocalypseHistoryRangeColorsV5 {
    val background = Color(0xFFF5F6F3)
    val surface = Color(0xFFFFFFFF)
    val selectedSurface = Color(0xFFEDF1ED)
    val selectedStrong = Color(0xFFDDE7E0)
    val ink = Color(0xFF1B211E)
    val muted = Color(0xFF68726C)
    val border = Color(0xFFD9DED9)
    val accent = Color(0xFF526D5E)
}

/**
 * Specialized overload intentionally lives beside Compose's explicitly imported lazy `items`.
 * ApocalypseSurvivalV5App imports androidx.compose.foundation.lazy.items, so keeping this overload
 * in that same package guarantees the V5 story-history List<ApocalypseV5ReadableScene> call selects
 * this more-specific overload instead of silently falling back to the generic Compose list renderer.
 *
 * The story-history page is grouped by ten scenes. Its range selector is deliberately rendered as
 * part of the page instead of a platform DropdownMenu, so the selector, expanded range choices and
 * scene cards share the same visual language. Scene reading itself remains in ApocalypseSurvivalV5App,
 * where previous/next scene navigation continues across ranges.
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

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = ApocalypseHistoryRangeColorsV5.surface,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, ApocalypseHistoryRangeColorsV5.border),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "章节浏览",
                            color = ApocalypseHistoryRangeColorsV5.ink,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "每 10 幕一组，选择后只显示这一段历史",
                            color = ApocalypseHistoryRangeColorsV5.muted,
                            fontSize = 10.sp,
                        )
                    }

                    Surface(
                        modifier = Modifier.clickable { expanded = !expanded },
                        color = ApocalypseHistoryRangeColorsV5.selectedSurface,
                        shape = RoundedCornerShape(13.dp),
                        border = BorderStroke(1.dp, ApocalypseHistoryRangeColorsV5.border),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "$selectedStart–$selectedEnd",
                                color = ApocalypseHistoryRangeColorsV5.accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = if (expanded) "⌃" else "⌄",
                                color = ApocalypseHistoryRangeColorsV5.accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                if (expanded) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = ApocalypseHistoryRangeColorsV5.border)
                    Spacer(Modifier.height(11.dp))
                    Text(
                        text = "选择章节范围",
                        color = ApocalypseHistoryRangeColorsV5.muted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(8.dp))

                    buckets.chunked(3).forEachIndexed { rowIndex, rowBuckets ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowBuckets.forEach { start ->
                                val end = start + 9
                                val selected = start == selectedStart
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            apocalypseHistoryBucketStartV5.intValue = start
                                            expanded = false
                                        },
                                    color = if (selected) ApocalypseHistoryRangeColorsV5.selectedStrong else ApocalypseHistoryRangeColorsV5.background,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) ApocalypseHistoryRangeColorsV5.accent.copy(alpha = .36f)
                                        else ApocalypseHistoryRangeColorsV5.border,
                                    ),
                                ) {
                                    Text(
                                        text = if (selected) "✓  $start–$end" else "$start–$end",
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 10.dp),
                                        color = if (selected) ApocalypseHistoryRangeColorsV5.accent else ApocalypseHistoryRangeColorsV5.ink,
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    )
                                }
                            }
                            repeat(3 - rowBuckets.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                        if (rowIndex != buckets.chunked(3).lastIndex) {
                            Spacer(Modifier.height(8.dp))
                        }
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
