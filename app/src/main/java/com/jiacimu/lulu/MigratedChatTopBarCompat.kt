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
import androidx.compose.ui.text.font.FontWeight
import com.jiacimu.lulu.design.LuluColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MigratedChatTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
    )
}
