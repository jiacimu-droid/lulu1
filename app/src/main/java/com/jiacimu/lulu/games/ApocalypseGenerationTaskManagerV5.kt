package com.jiacimu.lulu.games

import android.content.Context
import com.jiacimu.lulu.data.CharacterSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException

/** Keeps apocalypse scene generation alive when its Compose page leaves the screen. */
internal object ApocalypseGenerationTaskManagerV5 {
    data class TaskState(
        val running: Boolean = false,
        val phase: String = "",
        val action: String = "",
        val startedAtMillis: Long? = null,
        val lastError: String? = null,
        val completedScene: Int? = null,
        val usedDirector: Boolean = false,
        val partialText: String = "",
    )

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()
    private val mutableStates = MutableStateFlow<Map<String, TaskState>>(emptyMap())

    val states: StateFlow<Map<String, TaskState>> = mutableStates.asStateFlow()

    fun state(saveId: String): TaskState = mutableStates.value[saveId] ?: TaskState()

    fun launch(
        context: Context,
        save: ApocalypseV3Save,
        config: ApocalypseV3Config,
        party: List<CharacterSettings>,
        action: String,
    ): Boolean {
        val cleanAction = action.trim()
        if (save.id.isBlank() || cleanAction.isBlank()) return false
        val appContext = context.applicationContext
        val livingWorldStore = ApocalypseLivingWorldStoreV5(appContext)
        synchronized(lock) {
            if (jobs[save.id]?.isActive == true) return false
            val backstageWake = runCatching { livingWorldStore.shouldWakeDirector(save) }.getOrDefault(false)
            val needsDirector = shouldPlanApocalypseV5Beat(save, cleanAction) || backstageWake
            updateState(save.id) {
                TaskState(
                    running = true,
                    phase = if (needsDirector) "导演规划 · 同时检索旧剧情" else "正在检索本局旧剧情",
                    action = cleanAction,
                    startedAtMillis = System.currentTimeMillis(),
                    usedDirector = needsDirector,
                )
            }
            val job = appScope.launch(start = CoroutineStart.LAZY) {
                try {
                    // Semantic plot recall is independent from the long-form director's structural
                    // planning. Run it in parallel with a director pass when one is needed.
                    val plotMemoryDeferred = async {
                        runCatching {
                            ApocalypsePlotMemoryRuntimeV5.recall(
                                context = appContext,
                                save = save,
                                action = cleanAction,
                            )
                        }.getOrDefault("")
                    }

                    // The living-world ledger is local and private. It contains offscreen clocks and
                    // prepared-but-skippable setups, so only the director receives its hidden layer.
                    val livingWorldContext = runCatching {
                        livingWorldStore.promptForDirector(save)
                    }.getOrDefault("")

                    val planResult = if (needsDirector) {
                        updateState(save.id) { it.copy(phase = "导演规划 · 世界继续在镜头外运行") }
                        planApocalypseV5Beat(
                            save = save,
                            config = config,
                            party = party,
                            action = cleanAction,
                            plotMemoryContext = livingWorldContext,
                        )
                    } else {
                        ApocalypsePlanResultV5(
                            beat = continueApocalypseV5Beat(save, cleanAction),
                            directorApplied = false,
                        )
                    }

                    updateState(save.id) { it.copy(phase = "正在整理相关旧剧情") }
                    val plotMemoryContext = plotMemoryDeferred.await()
                    val plannedBeat = planResult.beat
                    val usedDirector = planResult.directorApplied
                    updateState(save.id) { it.copy(usedDirector = usedDirector) }
                    val projectedStats = applyApocalypseV3Beat(save.stats, plannedBeat)
                    updateState(save.id) { it.copy(phase = "正在写第${save.scene + 1}幕") }
                    val outcome = writeApocalypseV5Scene(
                        save = save,
                        config = config,
                        party = party,
                        action = cleanAction,
                        beat = plannedBeat,
                        nextStats = projectedStats,
                        usedDirector = usedDirector,
                        // The prose writer gets only canonically recalled old scenes, not hidden
                        // backstage truths. Anything secret must first be transformed by the director
                        // into an observable directive/foreshadow move.
                        plotMemoryContext = plotMemoryContext,
                        onPartialText = { partial ->
                            updateState(save.id) {
                                it.copy(phase = "正文正在生成", partialText = partial)
                            }
                        },
                    )
                        .getOrElse { error -> throw error }
                    val resolvedBeat = applyApocalypseSceneOutcomeV5(
                        save = save,
                        plannedBeat = plannedBeat,
                        outcome = outcome,
                        usedDirector = usedDirector,
                        party = party,
                    )
                    val beat = if (needsDirector && !usedDirector) {
                        // The director already had its chance this scene. A timeout or malformed JSON
                        // must not force another expensive director call on every following action;
                        // the normal cadence or the player's next structural choice can wake it again.
                        resolvedBeat.copy(
                            nextDirector = resolvedBeat.nextDirector.copy(directorRefreshNeeded = false),
                        )
                    } else {
                        resolvedBeat
                    }
                    val nextStats = applyApocalypseV3Beat(save.stats, beat)
                    val text = normalizeApocalypseStorySpeakerTagsV5(
                        text = outcome.text,
                        party = party,
                        dossiers = beat.nextDirector.characterDossiers,
                        presentCharacterIds = beat.nextDirector.presentCharacterIds,
                    ).trim()
                    check(text.isNotBlank()) { "这一幕没有生成出正文，请再试一次。" }

                    val storage = ApocalypseSurvivalV3Store(appContext)
                    val latest = storage.loadSave()
                    check(
                        latest != null &&
                            latest.id == save.id &&
                            latest.scene == save.scene &&
                            latest.updatedAt == save.updatedAt,
                    ) { "生成期间存档已经被修改，这次结果没有覆盖你的新进度。" }

                    ApocalypseV5HistoryStore(appContext).append(
                        saveBefore = save,
                        action = cleanAction,
                        narrationAfter = text,
                    )
                    val next = save.copy(
                        scene = save.scene + 1,
                        narration = text,
                        director = beat.nextDirector,
                        stats = nextStats,
                        log = (save.log + buildString {
                            append("第${save.scene + 1}幕｜行动=${cleanAction.take(180)}")
                            append("｜结果=${outcome.actionOutcome.ifBlank { compactApocalypseSceneExcerptV5(text, 100) }.take(220)}")
                            append("｜正史=${outcome.continuitySummary.ifBlank { compactApocalypseSceneExcerptV5(text) }.take(360)}")
                        })
                            .takeLast(100),
                        updatedAt = System.currentTimeMillis(),
                    )
                    storage.save(next)

                    // First advance the hidden world locally so even an offline/failed backstage model
                    // cannot freeze the rest of the world. This state is plot-only and never becomes
                    // player-visible canon by itself.
                    runCatching {
                        livingWorldStore.recordScene(
                            saveBefore = save,
                            saveAfter = next,
                            action = cleanAction,
                            outcome = outcome,
                            beat = beat,
                        )
                    }

                    runCatching {
                        ApocalypsePlotMemoryStoreV5(appContext).recordScene(
                            saveBefore = save,
                            saveAfter = next,
                            action = cleanAction,
                            outcome = outcome,
                        )
                    }.onSuccess {
                        // Vectorization and model-change reindexing never hold the visible generation
                        // chain open. A missing/slow Embedding endpoint leaves the local plot card usable.
                        appScope.launch {
                            ApocalypsePlotMemoryRuntimeV5.refreshEmbeddings(appContext, next.id)
                        }
                    }

                    // A compact backstage pass periodically updates offscreen actors, world clocks and
                    // fair surprise setups. It runs only after the scene is already saved, so it never
                    // blocks the player's visible chapter. If the player has already advanced or rolled
                    // back by the time it returns, the stale backstage result is discarded.
                    if (ApocalypseLivingWorldRuntimeV5.shouldRefreshBackstage(next, beat, outcome, usedDirector)) {
                        appScope.launch {
                            runCatching {
                                ApocalypseLivingWorldRuntimeV5.refreshAfterScene(
                                    context = appContext,
                                    saveBefore = save,
                                    saveAfter = next,
                                    action = cleanAction,
                                    outcome = outcome,
                                    beat = beat,
                                )
                            }
                        }
                    }

                    ApocalypseReadingProgressStoreV5(appContext).save(next.id, next.scene, 0)
                    updateState(save.id) {
                        it.copy(
                            phase = "",
                            lastError = null,
                            completedScene = next.scene,
                            partialText = "",
                        )
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    updateState(save.id) {
                        it.copy(
                            phase = "",
                            lastError = when (error) {
                                is SocketTimeoutException -> "游戏模型太久没有继续返回内容，本次没有写入存档；你的行动已经保留，可以直接重试。"
                                else -> error.message?.trim().orEmpty().ifBlank {
                                    "生成失败了，请检查游戏模型或网络后重试。"
                                }
                            },
                            partialText = "",
                        )
                    }
                } finally {
                    val completedJob = currentCoroutineContext()[Job]
                    val stillCurrent = synchronized(lock) {
                        if (jobs[save.id] === completedJob) {
                            jobs.remove(save.id)
                            true
                        } else {
                            false
                        }
                    }
                    if (stillCurrent) updateState(save.id) { it.copy(running = false, phase = "") }
                }
            }
            jobs[save.id] = job
            job.start()
            return true
        }
    }

    private fun updateState(saveId: String, transform: (TaskState) -> TaskState) {
        mutableStates.update { all -> all + (saveId to transform(all[saveId] ?: TaskState())) }
    }
}
