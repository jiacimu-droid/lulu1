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
import androidx.compose.ui.text.style.TextAlign
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
    var activeCall by remember { mutableStateOf<ProactiveIncomingCall?>(null) }
    var permissionTarget by remember { mutableStateOf<ProactiveIncomingCall?>(null) }
    var notice by remember { mutableStateOf("") }

    fun connect(call: ProactiveIncomingCall) {
        val character = MigratedDomainStores.characters.get(call.characterId)
        LuluVoiceCallSession.prepare(
            context = context,
            conversationId = call.conversationId,
            characterId = call.characterId,
            characterName = character.displayName,
        )
        LuluVoiceCallSession.dial()
        ProactiveIncomingCallStore.clear(call)
        activeCall = call
        notice = ""
    }

    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val call = permissionTarget
        permissionTarget = null
        if (granted && call != null) connect(call)
        else notice = "需要麦克风权限才能接听电话"
    }

    val visiblePending = pending?.takeIf { it.active() }
    LaunchedEffect(pending?.expiresAt) {
        pending?.takeIf { !it.active() }?.let(ProactiveIncomingCallStore::clear)
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
                    if (call.reason.isNotBlank()) {
                        Spacer(Modifier.height(14.dp))
                        Surface(
                            color = Color.White.copy(alpha = .62f),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text(
                                call.reason,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                                color = Color(0xFF4B4E55),
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
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
                            ProactiveIncomingCallStore.clear(call)
                            notice = ""
                        }
                        IncomingCallButton(
                            icon = Icons.Outlined.Call,
                            label = "接听",
                            background = Color(0xFF47B978),
                        ) {
                            if (
                                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                connect(call)
                            } else {
                                permissionTarget = call
                                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }
                }
            }
        }
    }

    activeCall?.let { call ->
        val character = MigratedDomainStores.characters.get(call.characterId)
        LuluVoiceCallScreen(
            conversationId = call.conversationId,
            characterId = call.characterId,
            characterName = character.displayName,
        ) {
            activeCall = null
        }
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
