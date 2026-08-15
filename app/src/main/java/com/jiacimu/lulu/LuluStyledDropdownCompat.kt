package com.jiacimu.lulu

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

/**
 * App-wide replacement for Material's bare popup menu inside the main Lulu package.
 *
 * Most Lulu screens import material3 with a wildcard. A declaration in the screen's own package
 * wins over that wildcard import, so old call sites automatically inherit this visual shell without
 * every feature inventing another popup style. The action rows themselves remain normal
 * DropdownMenuItems, preserving their icons, disabled states and destructive colors.
 */
@Composable
internal fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    containerColor: Color? = null,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val background = containerColor ?: MaterialTheme.colorScheme.surface
    val border = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)

    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
            .clip(shape)
            .border(1.dp, border, shape),
        offset = offset,
        scrollState = scrollState,
        properties = properties,
        containerColor = background,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        content = content,
    )
}
