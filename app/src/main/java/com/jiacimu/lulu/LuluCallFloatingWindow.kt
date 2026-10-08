package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-app phone presentation is independent from any chat page.
 * Minimizing hides only the full-screen dialog. The live call itself keeps running.
 */
internal object LuluCallWindowController {
    private val mutableExpanded = MutableStateFlow(false)
    val expanded = mutableExpanded.asStateFlow()

    fun show() { mutableExpanded.value = true }
    fun minimize() { mutableExpanded.value = false }
}

/** Drawn above every Lulu app route, including the theater and study pages. */
@Composable
internal fun LuluCallFloatingWindow(modifier: Modifier = Modifier) {
    val call by LuluVoiceCallSession.state.collectAsState()
    val expanded by LuluCallWindowController.expanded.collectAsState()
    // No misleading floating avatar after hanging up or before an actual call begins.
    val active = call.phase == CallPhase.Dialing || call.phase == CallPhase.Connected
    if (!active || expanded || call.characterId.isBlank()) return
    val character = remember(call.characterId) { MigratedDomainStores.characters.get(call.characterId) }
    val title = call.characterName.ifBlank { character.displayName }
    Surface(
        modifier = modifier
            .width(112.dp)
            .clickable(onClick = LuluCallWindowController::show),
        shape = RoundedCornerShape(22.dp),
        color = Color(0xF9FFFFFF),
        border = BorderStroke(1.dp, Color(0xFFE4E7ED)),
        shadowElevation = 12.dp,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Surface(shape = RoundedCornerShape(15.dp), color = Color(0xFFF1F2F5)) {
                LuluProfileAvatar(imageUri = character.avatarUri, fallback = title.take(1), size = 58)
            }
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = Color(0xFF2B3040), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    when {
                        call.microphoneMuted -> Icons.Outlined.MicOff
                        call.speaking -> Icons.Outlined.GraphicEq
                        else -> Icons.Outlined.Call
                    }, contentDescription = null, modifier = Modifier.size(13.dp),
                    tint = Color(0xFF4E78AB),
                )
                Text(
                    if (call.phase == CallPhase.Dialing) "正在连接" else "%02d:%02d".format(call.elapsedSeconds / 60, call.elapsedSeconds % 60),
                    fontSize = 10.sp, color = Color(0xFF6B7382),
                )
            }
            Text("点按返回通话", fontSize = 9.sp, color = Color(0xFF8B909B), maxLines = 1)
        }
    }
}
