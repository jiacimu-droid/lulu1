package com.jiacimu.lulu.games

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jiacimu.lulu.CallVoiceConfiguration
import com.jiacimu.lulu.ProviderCallInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Opt-in, game-scoped hands-free microphone. Reuses the telephone's *existing*
 * VAD/Scribe/provider capture pipeline, not a new speech API or a frame-based
 * game LLM. Only committed human utterances are delivered to the game model.
 * It closes on leaving the game; speaking NPC audio mutes capture to avoid echo.
 */
internal class IslandGameOpenMic(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var active by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    var partial by mutableStateOf("")
        private set

    private var playerSpeaking = false
    private var input: ProviderCallInput? = null
    private var onUtterance: (String) -> Unit = {}

    fun setListener(listener: (String) -> Unit) { onUtterance = listener }
    fun permissionDenied() { status = "需要麦克风权限才能游戏连麦" }

    fun start() {
        if (active) return
        CallVoiceConfiguration.sttPreflight(context)?.let { status = it; return }
        val prefs = context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
        val engine = CallVoiceConfiguration.sttEngine(context)
        val model = CallVoiceConfiguration.elevenSttModel(prefs.getString("eleven_asr_model", null))
        val mode = CallVoiceConfiguration.elevenInputPath(engine, model)
        val session = ProviderCallInput(
            context = context, scope = scope, sttEngine = engine,
            elevenPath = mode, elevenModel = model,
            accept = { active && !playerSpeaking },
            onReady = { scope.launch { if (active) status = "连麦中 · 直接说话" } },
            onLevel = {},
            onSpeech = { scope.launch { if (active) status = "正在听你说话…" } },
            onCaptureFinished = {},
            onPartial = { text -> scope.launch { if (active) partial = text } },
            onText = { text, _ ->
                scope.launch {
                    if (active && text.isNotBlank()) {
                        partial = ""
                        status = "已转写，伙伴正在听"
                        onUtterance(text)
                    }
                }
            },
            onStatus = { text -> scope.launch { if (active) status = text } },
            onError = { text -> scope.launch { if (active) status = text } },
        )
        input = session
        active = true
        status = "正在打开麦克风…"
        partial = ""
        session.start(startMuted = playerSpeaking)
    }

    fun setCompanionSpeaking(speaking: Boolean) {
        if (playerSpeaking == speaking) return
        playerSpeaking = speaking
        if (active) {
            if (speaking) input?.pauseCapture()
            else input?.resumeCapture()
        }
    }

    fun stop() {
        active = false
        input?.stop()
        input = null
        partial = ""
        status = "麦克风已关闭"
    }
}

@Composable
internal fun rememberIslandGameOpenMic(
    onUtterance: (String) -> Unit,
): Pair<IslandGameOpenMic, () -> Unit> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    val mic = remember(context) { IslandGameOpenMic(context.applicationContext, scope) }
    SideEffect { mic.setListener(onUtterance) }
    DisposableEffect(mic, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) mic.stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); mic.stop() }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) mic.start() else mic.permissionDenied()
    }
    val toggle: () -> Unit = {
        if (mic.active) mic.stop()
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) mic.start()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    return mic to toggle
}
