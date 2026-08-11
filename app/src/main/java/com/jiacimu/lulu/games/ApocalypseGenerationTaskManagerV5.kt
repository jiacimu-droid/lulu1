package com.jiacimu.lulu.games

import android.content.Context
import com.jiacimu.lulu.data.CharacterSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
        synchronized(lock) {
            if (jobs[save.id]?.isActive == true) return false
            val needsDirector = shouldPlanApocalypseV5Beat(save, cleanAction)
            updateState(save.id) {
                TaskState(
                    running = true,
                    phase = if (needsDirector) "导演规划关键节点" else "沿用剧情蓝图",
                    action = cleanAction,
                    startedAtMillis = System.currentTimeMillis(),
                    usedDirector = needsDirector,
                )
            }
            val appContext = context.applicationContext
            val job = appScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val planResult = if (needsDirector) {
                        planApocalypseV5Beat(save, config, party, cleanAction)
                    } else {
                        ApocalypsePlanResultV5(
                            beat = continueApocalypseV5Beat(save, cleanAction),
                            directorApplied = false,
                        )
                    }
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
                        resolvedBeat.copy(
                            nextDirector = resolvedBeat.nextDirector.copy(directorRefreshNeeded = true),
                        )
                    } else {
                        resolvedBeat
                    }
                    val nextStats = applyApocalypseV3Beat(save.stats, beat)
                    val text = outcome.text.trim()
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
                    ApocalypseReadingProgressStoreV5(appContext).save(next.id, next.scene, 0)
                    updateState(save.id) {
                        it.copy(
                            phase = "",
                            lastError = null,
                            completedScene = next.scene,
                        )
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    updateState(save.id) {
                        it.copy(
                            phase = "",
                            lastError = error.message?.trim().orEmpty().ifBlank {
                                "生成失败了，请检查游戏模型或网络后重试。"
                            },
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
