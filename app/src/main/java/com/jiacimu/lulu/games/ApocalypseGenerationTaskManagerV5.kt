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

        // The first scene explicitly promises answers about its opening mysteries. Migrate older saves
        // before any new model call so those promises cannot silently disappear from a long-running
        // director ledger. This does not reveal anything to the player; it only repairs hidden state.
        val continuitySave = ensureApocalypseCoreMysteryContinuityV5(save)
        if (continuitySave != save) {
            storage.save(continuitySave)
            return launch(context, continuitySave, config, party, cleanAction)
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
            val npcEcologyWake = shouldWakeApocalypseNpcEcologyDirectorV5(save, cleanAction)
            val needsDirector = shouldPlanApocalypseV5Beat(save, cleanAction) || backstageWake || npcEcologyWake
            updateState(save.id) {
                TaskState(
                    running = true,
                    phase = if (save.scene >= 10 && save.scene % 10 == 0) {
                        "正在整理阶段剧情"
                    } else if (npcEcologyWake) {
                        "正在恢复世界人物生态"
                    } else if (needsDirector) {
                        "正在召回相关旧剧情"
                    } else {
                        "正在检索本局旧剧情"
                    },
                    action = cleanAction,
                    startedAtMillis = System.currentTimeMillis(),
                    usedDirector = needsDirector,
                )
            }
            val job = appScope.launch(start = CoroutineStart.LAZY) {
                try {
                    // A ten-scene boundary is a real editorial checkpoint. If an upgraded long save is
                    // already sitting on scene 60, build the current 41-60 bundle before planning 61;
                    // older missing bundles are filled gradually in the background below.
                    if (save.scene >= 10 && save.scene % 10 == 0) {
                        updateState(save.id) {
                            it.copy(
                                phase = if (save.scene % 20 == 0) {
                                    "正在整理第${save.scene - 19}—${save.scene}幕阶段总结"
                                } else {
                                    "正在整理第${save.scene - 9}—${save.scene}幕小结"
                                },
                            )
                        }
                        runCatching {
                            ApocalypseChapterSummaryRuntimeV5.ensureCurrentMilestone(appContext, save)
                        }
                    }

                    val chapterSummaryContext = runCatching {
                        ApocalypseChapterSummaryStoreV5(appContext).promptForDirector(save)
                    }.getOrDefault("")

                    val plotMemoryDeferred = async {
                        runCatching {
                            recallApocalypsePlotMemoryChronologicallyV5(
                                context = appContext,
                                save = save,
                                action = cleanAction,
                            )
                        }.getOrDefault("")
                    }

                    val livingWorldContext = runCatching {
                        livingWorldStore.promptForDirector(save)
                    }.getOrDefault("")

                    // Relevance chooses which old scenes deserve a closer look; chronology decides how
                    // the director reads them. The director waits for this ordered supplement because
                    // long-form causal quality is more important here than shaving off a few seconds.
                    val directorPlotMemoryContext = if (needsDirector) {
                        plotMemoryDeferred.await()
                    } else {
                        ""
                    }
                    val planResult = if (needsDirector) {
                        updateState(save.id) { it.copy(phase = "导演规划 · 按时间轴核对旧剧情") }
                        planApocalypseV5Beat(
                            save = save,
                            config = config,
                            party = party,
                            action = cleanAction,
                            plotMemoryContext = apocalypseDirectorSupplementContextV5(
                                chronologicalPlotRecall = directorPlotMemoryContext,
                                chapterSummaryContext = chapterSummaryContext,
                                livingWorldContext = livingWorldContext,
                            ),
                        )
                    } else {
                        ApocalypsePlanResultV5(
                            beat = continueApocalypseV5Beat(save, cleanAction),
                            directorApplied = false,
                        )
                    }

                    updateState(save.id) { it.copy(phase = "正在整理相关旧剧情") }
                    val plotMemoryContext = if (needsDirector) {
                        directorPlotMemoryContext
                    } else {
                        plotMemoryDeferred.await()
                    }
                    val plannedBeat = sanitizeApocalypseAbilityProgressionV5(save, cleanAction, planResult.beat)
                    val usedDirector = planResult.directorApplied
                    updateState(save.id) { it.copy(usedDirector = usedDirector) }
                    val projectedStats = applyApocalypseV3Beat(save.stats, plannedBeat)

                    suspend fun writeSceneAttempt(phaseLabel: String): ApocalypseSceneOutcomeV5 {
                        updateState(save.id) { it.copy(phase = phaseLabel) }
                        return writeApocalypseV5Scene(
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
                    }

                    var outcome = writeSceneAttempt("正在写第${save.scene + 1}幕")
                    var quality = inspectApocalypseNarrativeV5(outcome.text)
                    if (!quality.complete) {
                        // A provider can finish the JSON receipt and then hit its output cap in the prose.
                        // Never canonize that half sentence. Re-run only the writer with the already planned
                        // beat; the expensive director and plot retrieval are deliberately not repeated.
                        updateState(save.id) {
                            it.copy(phase = "检测到正文未写完 · 正在自动重写")
                        }
                        outcome = writeSceneAttempt("正文尾部不完整 · 正在重新写完整这一幕")
                        quality = inspectApocalypseNarrativeV5(outcome.text)
                    }
                    check(quality.complete) {
                        "游戏模型连续两次返回了未写完的正文（${quality.reason}）。这次没有写入存档，你的行动已经保留，可以直接重试。"
                    }

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
                    val finalQuality = inspectApocalypseNarrativeV5(text)
                    check(finalQuality.complete) {
                        "这一幕在最终整理时发现正文不完整（${finalQuality.reason}），因此没有写入存档。你的行动已经保留，可以直接重试。"
                    }
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

                    // Build milestone summaries as soon as the milestone scene lands. On old upgraded
                    // saves, backfill one missing 20-scene bundle per new scene so history catches up
                    // naturally without blocking the player with many one-time model calls.
                    if (next.scene >= 10) {
                        appScope.launch {
                            runCatching {
                                if (next.scene % 10 == 0) {
                                    ApocalypseChapterSummaryRuntimeV5.ensureCurrentMilestone(appContext, next)
                                }
                                ApocalypseChapterSummaryRuntimeV5.backfillOneOlderBundle(appContext, next)
                            }
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
