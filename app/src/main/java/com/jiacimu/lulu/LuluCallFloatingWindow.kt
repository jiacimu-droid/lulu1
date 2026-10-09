package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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

/** A mini-window represents a live or connecting call, never a dead or unanswered placeholder. */
internal fun shouldShowFloatingCall(phase: CallPhase, expanded: Boolean, characterId: String): Boolean =
    !expanded && characterId.isNotBlank() &&
        (phase == CallPhase.Dialing || phase == CallPhase.Connected)

/** Drawn above every Lulu app route, including the theater and study pages. */
@Composable
internal fun LuluCallFloatingWindow(modifier: Modifier = Modifier) {
    val call by LuluVoiceCallSession.state.collectAsState()
    val expanded by LuluCallWindowController.expanded.collectAsState()
    // No misleading floating avatar after hanging up or before an actual call begins.
    if (!shouldShowFloatingCall(call.phase, expanded, call.characterId)) return
    val character = remember(call.characterId) { MigratedDomainStores.characters.get(call.characterId) }
    val title = call.characterName.ifBlank { character.displayName }
    // The small window is a floating child of the full app-sized layout, not
    // an immovable TopEnd slot. Coordinates stay normalized across rotation.
    var horizontalFraction by rememberSaveable { mutableFloatStateOf(1f) }
    var verticalFraction by rememberSaveable { mutableFloatStateOf(-1f) }
    var measuredHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.statusBarsPadding().navigationBarsPadding()) {
        val maxXPx = (constraints.maxWidth - with(density) { 116.dp.roundToPx() }).coerceAtLeast(0)
        val heightPx = measuredHeightPx.takeIf { it > 0 } ?: with(density) { 116.dp.roundToPx() }
        val maxYPx = (constraints.maxHeight - heightPx).coerceAtLeast(0)
        val initialYPx = with(density) { 56.dp.toPx() }.coerceAtMost(maxYPx.toFloat())
        val currentY = if (verticalFraction < 0f) initialYPx else verticalFraction * maxYPx
    Surface(
        modifier = Modifier
            .offset { IntOffset((horizontalFraction * maxXPx).roundToInt(), currentY.roundToInt()) }
            .size(116.dp)
            .onSizeChanged { measuredHeightPx = it.height }
            .pointerInput(maxXPx, maxYPx) {
                detectDragGestures(
                    onDragEnd = {
                        // Auto-dock on the closest edge, but never lose the bubble
                        // outside a narrow screen or beneath system navigation.
                        horizontalFraction = if (horizontalFraction < .5f) 0f else 1f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        horizontalFraction = ((horizontalFraction * maxXPx + amount.x) /
                            maxXPx.coerceAtLeast(1)).coerceIn(0f, 1f)
                        // Use the latest fraction on every pointer event.
                        // Capturing currentY here would reuse the first frame's
                        // value and make dragging stall or jump after a few pixels.
                        val previousY = if (verticalFraction < 0f) initialYPx
                            else verticalFraction * maxYPx
                        verticalFraction = ((previousY + amount.y) /
                            maxYPx.coerceAtLeast(1)).coerceIn(0f, 1f)
                    },
                )
            }
            .clickable(onClick = LuluCallWindowController::show),
        shape = RoundedCornerShape(22.dp),
        color = Color(0xF9FFFFFF),
        border = BorderStroke(1.dp, Color(0xFFE4E7ED)),
        shadowElevation = 12.dp,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // One portrait, one time. No instructional copy, name or
            // duplicated microphone label: the mini window stays square.
            Surface(shape = RoundedCornerShape(17.dp), color = Color(0xFFF1F2F5)) {
                LuluProfileAvatar(imageUri = character.avatarUri, fallback = title.take(1), size = 80)
            }
            Spacer(Modifier.height(5.dp))
            Text(
                if (call.phase == CallPhase.Dialing) "呼叫中"
                else "%02d:%02d".format(call.elapsedSeconds / 60, call.elapsedSeconds % 60),
                color = Color(0xFF525F72), fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
    }
}
