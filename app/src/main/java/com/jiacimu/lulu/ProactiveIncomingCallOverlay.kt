package com.jiacimu.lulu

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.ProactiveIncomingCall
import com.jiacimu.lulu.data.ProactiveIncomingCallStore

/** Global incoming-call layer so a role can really call while the user is anywhere in Lulu. */
@Composable
internal fun ProactiveIncomingCallOverlay() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { ProactiveIncomingCallStore.initialize(context) }
    val pending by ProactiveIncomingCallStore.pending.collectAsState()
    var permissionTarget by remember { mutableStateOf<ProactiveIncomingCall?>(null) }
    var notice by remember { mutableStateOf("") }
    var connectingCall by remember { mutableStateOf<ProactiveIncomingCall?>(null) }
    val session by LuluVoiceCallSession.state.collectAsState()

    fun connect(call: ProactiveIncomingCall) {
        if (connectingCall != null) return
        val before = LuluVoiceCallSession.state.value
        val retrySameReady = before.phase == CallPhase.Ready &&
            before.conversationId == call.conversationId &&
            before.characterId == call.characterId
        if (before.phase in setOf(CallPhase.Dialing, CallPhase.Connected)) {
            notice = "另一通电话正在准备或进行，请先结束那通电话"
            return
        }
        val character = MigratedDomainStores.characters.get(call.characterId)
        if (!retrySameReady) LuluVoiceCallSession.prepare(
            context = context,
            conversationId = call.conversationId,
            characterId = call.characterId,
            characterName = character.displayName,
            incomingReason = call.reason,
            incomingCommitmentTaskId = call.commitmentTaskId,
        )
        LuluVoiceCallSession.dial()
        val started = LuluVoiceCallSession.state.value
        if (started.phase !in setOf(CallPhase.Dialing, CallPhase.Connected)) {
            notice = started.errorMessage.ifBlank { started.statusMessage.ifBlank { "电话尚未接通，请检查设置后重试" } }
            return
        }
        connectingCall = call
        notice = "正在连接电话…"
    }

    // Dialing is asynchronous (especially cloud STT). Clicking Answer is not
    // evidence of a connected call. Preserve the ringing invitation on failure.
    LaunchedEffect(connectingCall, session.phase, session.callExperienceId, session.errorMessage) {
        val call = connectingCall ?: return@LaunchedEffect
        val matching = session.conversationId == call.conversationId &&
            session.characterId == call.characterId
        when {
            matching && IncomingCallAnswerPolicy.genuinelyConnected(
                call, ProactiveIncomingCallStore.pending.value, session) -> {
                ProactiveIncomingCallStore.respond(call, answered = true)
                connectingCall = null
                notice = ""
                LuluCallWindowController.show()
            }
            !matching || session.phase in setOf(CallPhase.Idle, CallPhase.Ended) ||
                session.phase == CallPhase.Ready || !call.active() -> {
                connectingCall = null
                notice = session.errorMessage.ifBlank { "这次接通失败，来电仍可重试" }
            }
        }
    }
    LaunchedEffect(connectingCall) {
        val call = connectingCall ?: return@LaunchedEffect
        kotlinx.coroutines.delay(30_000L)
        if (connectingCall == call && LuluVoiceCallSession.state.value.phase == CallPhase.Dialing) {
            LuluVoiceCallSession.cancelDial()
            connectingCall = null
            notice = "连接超时，未记为已接听；请检查收音线路后重试"
        }
    }

    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val call = permissionTarget
        permissionTarget = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && call != null) connect(call)
        else notice = "需要麦克风权限才能接听电话"
    }

    val visiblePending = pending?.takeIf { it.active() }
    DisposableEffect(visiblePending != null) {
        val activity = context as? android.app.Activity
        activity?.setIncomingCallWindow(visiblePending != null)
        onDispose { activity?.setIncomingCallWindow(false) }
    }
    LaunchedEffect(pending?.createdAt, pending?.expiresAt) {
        val active = pending ?: return@LaunchedEffect
        val millis = java.time.Duration.between(java.time.Instant.now(), active.expiresAt)
            .toMillis().coerceAtLeast(0L)
        kotlinx.coroutines.delay(millis)
        ProactiveIncomingCallStore.reconcileExpired()
    }

    visiblePending?.let { call ->
        val character = MigratedDomainStores.characters.get(call.characterId)
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color(0xFFF8F5FF), Color(0xFFEAF2FF), Color(0xFFFFF8F3)),
                    ),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 34.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(
                        modifier = Modifier.size(132.dp),
                        shape = RoundedCornerShape(36.dp),
                        color = Color.White,
                        border = BorderStroke(4.dp, Color.White),
                        shadowElevation = 14.dp,
                    ) {
                        LuluProfileAvatar(
                            imageUri = character.avatarUri,
                            fallback = character.displayName.take(1).ifBlank { "露" },
                            size = 132,
                        )
                    }
                    Spacer(Modifier.height(22.dp))
                    Text(character.displayName, fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF25262A))
                    Spacer(Modifier.height(7.dp))
                    Text("语音来电", color = Color(0xFF72757C), fontSize = 14.sp)
                    if (notice.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(notice, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(44.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        IncomingCallButton(
                            icon = Icons.Outlined.CallEnd,
                            label = "拒绝",
                            background = Color(0xFFE35E68),
                        ) {
                            ProactiveIncomingCallStore.respond(call, answered = false)
                            notice = ""
                        }
                        IncomingCallButton(
                            icon = Icons.Outlined.Call,
                            label = "接听",
                            background = Color(0xFF47B978),
                        ) {
                            val permissions = buildList {
                                add(Manifest.permission.RECORD_AUDIO)
                                if (android.os.Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
                            }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                            if (permissions.isEmpty()) {
                                connect(call)
                            } else {
                                permissionTarget = call
                                microphonePermission.launch(permissions.toTypedArray())
                            }
                        }
                    }
                }
            }
        }
    }


}

/** The app still supports Android 8.0 (API 26), before Activity's lock-screen methods existed. */
internal fun android.app.Activity.setIncomingCallWindow(enabled: Boolean) {
    if (android.os.Build.VERSION.SDK_INT >= 27) {
        setShowWhenLocked(enabled)
        setTurnScreenOn(enabled)
    } else {
        @Suppress("DEPRECATION")
        val flags = android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        if (enabled) window.addFlags(flags) else window.clearFlags(flags)
    }
}

@Composable
private fun IncomingCallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    background: Color,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = background,
                contentColor = Color.White,
            ),
        ) {
            Icon(icon, label, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color(0xFF565960), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
