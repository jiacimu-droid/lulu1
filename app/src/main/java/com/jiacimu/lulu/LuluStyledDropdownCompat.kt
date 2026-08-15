package com.jiacimu.lulu

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
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
 * every feature inventing another popup style.
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

/**
 * Matching row treatment for the menus above. Destructive callers that explicitly color their
 * Text/Icon still keep that red treatment; ordinary rows get Lulu's quieter ink and roomier rhythm.
 */
@Composable
internal fun DropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    androidx.compose.material3.DropdownMenuItem(
        text = text,
        onClick = onClick,
        modifier = modifier.heightIn(min = 50.dp),
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
        colors = MenuDefaults.itemColors(
            textColor = MaterialTheme.colorScheme.onSurface,
            leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
            disabledLeadingIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .30f),
            disabledTrailingIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .30f),
        ),
    )
}
