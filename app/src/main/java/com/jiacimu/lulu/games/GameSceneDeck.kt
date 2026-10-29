package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The world is the screen; inventory, recipes and history share one optional drawer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GameSceneDeck(
    accent: Color,
    status: String,
    notice: String = "",
    scene: @Composable () -> Unit,
    companion: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    tools: @Composable () -> Unit,
) {
    var drawer by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color(0xFF09151D))) {
        scene()
        // Only the edges are shaded. Real terrain and actors remain visible in the center.
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(
            Color(0xC809151D), Color.Transparent, Color.Transparent, Color(0xF009151D)))))
        Surface(Modifier.align(Alignment.TopStart).padding(12.dp),
            color = Color(0xDD132932), shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, accent.copy(alpha = .35f))) {
            Text(status, color = Color.White, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
        Column(Modifier.align(Alignment.BottomCenter).widthIn(max = 760.dp).fillMaxWidth().navigationBarsPadding().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            companion()
            if(notice.isNotBlank()) Text(notice,color=Color(0xFFE5EEE9),fontSize=11.sp,
                maxLines=2,modifier=Modifier.padding(horizontal=4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions()
                FilledTonalButton(onClick = { drawer = true },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xF02A414B), contentColor = Color.White)) {
                    Text("行囊 / 更多", fontSize = 12.sp)
                }
            }
        }
    }
    if (drawer) ModalBottomSheet(onDismissRequest = { drawer = false },
        containerColor = Color(0xFF132832), contentColor = Color.White,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(.9f)) { tools() }
    }
}
