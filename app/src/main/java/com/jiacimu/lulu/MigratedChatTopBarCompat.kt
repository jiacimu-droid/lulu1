package com.jiacimu.lulu

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.text.font.FontWeight
import com.jiacimu.lulu.design.LuluColors

/**
 * The legacy chat file owns a private function with this name. A callable object
 * gives V2 files the same concise call syntax without creating a conflicting
 * package-level function overload.
 */
internal object MigratedChatTopBar {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    operator fun invoke(
        title: String,
        onBack: () -> Unit,
        actions: @Composable RowScope.() -> Unit = {},
    ) {
        TopAppBar(
            title = { Text(title, fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
            },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
        )
    }
}
