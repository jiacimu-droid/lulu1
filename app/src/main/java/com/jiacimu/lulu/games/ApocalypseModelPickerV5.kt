package com.jiacimu.lulu.games

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.jiacimu.lulu.ModelArchivePickerSheet
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ScopedModelSelections

@Composable
internal fun ApocalypseModelArchiveButtonV5(
    tint: Color = Color(0xFF1B211E),
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val library by LuluAiServices.connectionStore.library.collectAsState()
    var pickerVisible by remember { mutableStateOf(false) }
    var selectedArchiveId by remember(library.archives) {
        mutableStateOf(ScopedModelSelections.selectedArchiveId(ScopedModelSelections.APOCALYPSE, library))
    }

    LaunchedEffect(context) {
        ScopedModelSelections.initialize(context)
        selectedArchiveId = ScopedModelSelections.selectedArchiveId(ScopedModelSelections.APOCALYPSE, library)
    }

    IconButton(onClick = { pickerVisible = true }, enabled = enabled) {
        Icon(Icons.Outlined.Memory, "选择末世求生模型", tint = tint)
    }

    if (pickerVisible) {
        ModelArchivePickerSheet(
            title = "末世求生模型",
            subtitle = "这是末世求生自己的模型存档。修改它不会改变聊天、电话或“游戏”应用正在使用的模型。",
            selectedArchiveId = selectedArchiveId,
            onSelect = { archiveId ->
                ScopedModelSelections.select(ScopedModelSelections.APOCALYPSE, archiveId)
                selectedArchiveId = archiveId
                pickerVisible = false
            },
            onDismiss = { pickerVisible = false },
            accent = Color(0xFF526D5E),
            background = Color(0xFFF5F6F3),
            ink = Color(0xFF1B211E),
            muted = Color(0xFF68726C),
            border = Color(0xFFD9DED9),
        )
    }
}
