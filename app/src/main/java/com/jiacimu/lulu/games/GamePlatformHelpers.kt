package com.jiacimu.lulu.games

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale

internal data class GameRoleResponse(
    val loading: Boolean = false,
    val text: String = "",
    val error: String = "",
)

internal fun requestGameRoleResponse(
    scope: CoroutineScope,
    store: LuluGameStore,
    recordId: String?,
    facts: String,
    instruction: String,
    title: String,
    onState: (GameRoleResponse) -> Unit,
    maxTokens: Int = 500,
    characterIdOverride: String? = null,
) {
    val snapshot = store.state.value
    if (!snapshot.playWithCharacter) return
    onState(GameRoleResponse(loading = true))
    scope.launch {
        val connection = runCatching {
            LuluAiServices.connectionStore.resolveConnection(
                LuluAiServices.connectionStore.selectedArchiveId(ModelUsage.Game),
            )
        }.getOrElse { error ->
            onState(GameRoleResponse(error = error.message ?: "请先选择游戏模型"))
            return@launch
        }
        val result = LuluAiServices.gateway.generate(
            characterId = characterIdOverride ?: snapshot.selectedCharacterId,
            facts = facts,
            instruction = instruction,
            source = "游戏",
            title = title,
            maxTokens = maxTokens,
            connectionOverride = connection,
        )
        result.onSuccess { reply ->
            // Round-by-round banter can exist inside a match without becoming a persistent game
            // record. Only callers that pass the final match record attach the reply to history.
            recordId?.let { id -> store.attachCharacterReply(id, reply.text) }
            onState(GameRoleResponse(text = reply.text))
        }.onFailure { error ->
            onState(GameRoleResponse(error = error.message ?: "角色回应生成失败，请重试。"))
        }
    }
}

internal fun saveGameAsSharedMemory(
    scope: CoroutineScope,
    store: LuluGameStore,
    recordId: String,
) {
    val record = store.state.value.records.firstOrNull { it.id == recordId } ?: return
    if (!record.playedWithCharacter) return
    scope.launch {
        LuluRepositories.memory.upsert(
            MemoryEntry(
                id = "game-${record.id}",
                characterId = record.characterId,
                content = "共同游戏《${record.title}》：${record.summary}",
                kind = MemoryKind.Timeline,
                source = "game:${record.type.name}",
                occurredAt = record.createdAt,
                createdAt = Instant.now(),
                strength = 3,
                pinned = false,
                canRecallProactively = true,
            ),
        )
    }
}

@Composable
internal fun rememberGameSpeaker(): GameSpeaker {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    val speaker = remember { GameSpeaker { ready } }
    DisposableEffect(context) {
        val tts = TextToSpeech(context) { status -> ready = status == TextToSpeech.SUCCESS }
        tts.language = Locale.SIMPLIFIED_CHINESE
        speaker.attach(tts)
        onDispose {
            tts.stop()
            tts.shutdown()
            speaker.attach(null)
        }
    }
    return speaker
}

internal class GameSpeaker(private val isReady: () -> Boolean) {
    private var tts: TextToSpeech? = null
    var enabled: Boolean = true

    fun attach(value: TextToSpeech?) { tts = value }

    fun speak(text: String) {
        if (!enabled || !isReady() || text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lulu-game-${System.currentTimeMillis()}")
    }

    fun stop() { tts?.stop() }
}

@Composable
internal fun rememberGameSpeechInput(onText: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latestOnText by rememberUpdatedState(onText)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (text.isNotBlank()) latestOnText(text)
    }
    return remember(context, launcher) {
        {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.SIMPLIFIED_CHINESE.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话")
            }
            runCatching { launcher.launch(intent) }
        }
    }
}
