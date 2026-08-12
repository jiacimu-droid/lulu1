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
        val storage = ApocalypseSurvivalV3Store(appContext)

        // Inventory can be manually corrected from its bottom sheet without recreating the Play
        // composable. Always prefer the newest persisted save before spending any model request, so a
        // deleted bogus row cannot be silently reintroduced by a stale in-memory save.
        val latestStored = storage.loadSave()
        if (
            latestStored != null &&
            latestStored.id == save.id &&
            (latestStored.updatedAt != save.updatedAt || latestStored.scene != save.scene)
        ) {
            return launch(context, latestStored, config, party, cleanAction)
        }

        val repairedSave = repairApocalypseCurrentSceneInventoryV5(
            appContext,
            sanitizeApocalypseLoadedAbilityStateV5(save),
        )
        if (repairedSave != save) {
            storage.save(repairedSave)
            return launch(context, repairedSave, config, party, cleanAction)
        }

        val livingWorldStore = ApocalypseLivingWorldStoreV5(appContext)
        synchronized(lock) {
            if (jobs[save.id]?.isActive == true) return false
            val backstageWake = runCatching { livingWorldStore.shouldWakeDirector(save) }.getOrDefault(false) &&
                save.scene % 2 == 0
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
                    val plotMemoryDeferred = async {
                        runCatching {
                            ApocalypsePlotMemoryRuntimeV5.recall(
                                context = appContext,
                                save = save,
                                action = cleanAction,
                            )
                        }.getOrDefault("")
                    }

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
                    val plannedBeat = sanitizeApocalypseAbilityProgressionV5(save, cleanAction, planResult.beat)
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
                        plotMemoryContext = plotMemoryContext,
                        onPartialText = { _ ->
                            updateState(save.id) {
                                it.copy(phase = "正文正在生成", partialText = "")
                            }
                        },
                    ).getOrElse { error -> throw error }

                    val narratedOutcome = recoverApocalypseNarratedInventoryV5(outcome)
                    val inventoryOutcome = reconcileApocalypseInventoryOutcomeV5(save, narratedOutcome)
                    val resolvedBeat = applyApocalypseSceneOutcomeV5(
                        save = save,
                        plannedBeat = plannedBeat,
                        outcome = inventoryOutcome,
                        usedDirector = usedDirector,
                        party = party,
                    )
                    val rawBeat = if (needsDirector && !usedDirector) {
                        resolvedBeat.copy(
                            nextDirector = resolvedBeat.nextDirector.copy(directorRefreshNeeded = false),
                        )
                    } else {
                        resolvedBeat
                    }
                    val sanitizedBeat = sanitizeApocalypseAbilityProgressionV5(save, cleanAction, rawBeat)
                    val beat = preserveApocalypseInventoryLedgerV5(save, inventoryOutcome, sanitizedBeat)
                    val nextStats = applyApocalypseV3Beat(save.stats, beat)
                    val normalizedText = normalizeApocalypseStorySpeakerTagsV5(
                        text = inventoryOutcome.text,
                        party = party,
                        dossiers = beat.nextDirector.characterDossiers,
                        presentCharacterIds = beat.nextDirector.presentCharacterIds,
                    ).trim()
                    val text = ensureApocalypseAbilityUpgradeNarrationV5(
                        text = normalizedText,
                        before = save.stats,
                        after = nextStats,
                    )
                    check(text.isNotBlank()) { "这一幕没有生成出正文，请再试一次。" }
                    val persistedOutcome = withApocalypseAbilityUpgradeCanonV5(
                        outcome = inventoryOutcome,
                        before = save.stats,
                        after = nextStats,
                        visibleText = text,
                    )

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
                            append("｜结果=${persistedOutcome.actionOutcome.ifBlank { compactApocalypseSceneExcerptV5(text, 100) }.take(220)}")
                            append("｜正史=${persistedOutcome.continuitySummary.ifBlank { compactApocalypseSceneExcerptV5(text) }.take(360)}")
                        }).takeLast(100),
                        updatedAt = System.currentTimeMillis(),
                    )
                    storage.save(next)

                    runCatching {
                        livingWorldStore.recordScene(
                            saveBefore = save,
                            saveAfter = next,
                            action = cleanAction,
                            outcome = persistedOutcome,
                            beat = beat,
                        )
                    }

                    runCatching {
                        ApocalypsePlotMemoryStoreV5(appContext).recordScene(
                            saveBefore = save,
                            saveAfter = next,
                            action = cleanAction,
                            outcome = persistedOutcome,
                        )
                    }.onSuccess {
                        appScope.launch {
                            ApocalypsePlotMemoryRuntimeV5.refreshEmbeddings(appContext, next.id)
                        }
                    }

                    if (ApocalypseLivingWorldRuntimeV5.shouldRefreshBackstage(next, beat, persistedOutcome, usedDirector)) {
                        appScope.launch {
                            runCatching {
                                ApocalypseLivingWorldRuntimeV5.refreshAfterScene(
                                    context = appContext,
                                    saveBefore = save,
                                    saveAfter = next,
                                    action = cleanAction,
                                    outcome = persistedOutcome,
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
