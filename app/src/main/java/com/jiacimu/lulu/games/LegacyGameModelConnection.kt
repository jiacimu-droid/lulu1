package com.jiacimu.lulu.games

import com.jiacimu.lulu.ai.ModelConnectionStore
import com.jiacimu.lulu.ai.ModelLibraryState
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import java.util.WeakHashMap

/** Keeps the existing game screen compatible with the new API profile/model archive library. */
data class GameModelConnectionStatus(
    val enabled: Boolean,
    val apiKey: String,
    val model: String,
)

private class GameModelConnectionStateFlow(
    private val source: StateFlow<ModelLibraryState>,
) : StateFlow<GameModelConnectionStatus> {
    override val value: GameModelConnectionStatus
        get() = source.value.toGameStatus()

    override val replayCache: List<GameModelConnectionStatus>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<GameModelConnectionStatus>): Nothing {
        source.map { it.toGameStatus() }.collect(collector)
        error("StateFlow collection completed unexpectedly")
    }
}

private fun ModelLibraryState.toGameStatus(): GameModelConnectionStatus {
    val archive = archives.firstOrNull { it.id == activeArchiveId }
    val configuration = archive?.let { selected ->
        configurations.firstOrNull { it.id == selected.configurationId }
    }
    return GameModelConnectionStatus(
        enabled = archive != null && configuration != null,
        apiKey = configuration?.apiKey.orEmpty(),
        model = archive?.model.orEmpty(),
    )
}

private val gameConnectionFlows = WeakHashMap<ModelConnectionStore, StateFlow<GameModelConnectionStatus>>()

val ModelConnectionStore.state: StateFlow<GameModelConnectionStatus>
    get() = synchronized(gameConnectionFlows) {
        gameConnectionFlows.getOrPut(this) { GameModelConnectionStateFlow(library) }
    }
