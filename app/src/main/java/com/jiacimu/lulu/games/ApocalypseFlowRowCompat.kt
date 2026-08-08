package com.jiacimu.lulu.games

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Stable local replacement for Compose's experimental FlowRow.
 *
 * Apocalypse V2/V3 settings use compact horizontal selectors for abilities and branches. Keeping
 * this helper in the game package avoids opting the whole app into ExperimentalLayoutApi. On narrow
 * screens the chips scroll horizontally instead of overflowing.
 */
@Composable
internal fun FlowRow(
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable RowScope.() -> Unit,
) {
    Column(verticalArrangement = verticalArrangement) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = horizontalArrangement,
            content = content,
        )
    }
}
