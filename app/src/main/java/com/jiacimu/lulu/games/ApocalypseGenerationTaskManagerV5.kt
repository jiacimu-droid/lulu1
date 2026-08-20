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

private enum class ApocalypseAmbientEventKindV5 { SHOPPING, TRAVEL, EXPLORE, RHYTHM, NONE }

private fun apocalypseAmbientEventKindV5(action: String): ApocalypseAmbientEventKindV5 {
    val text = action.lowercase()
    fun hasAny(vararg words: String): Boolean = words.any(text::contains)
    return when {
        hasAny(
            "买", "采购", "囤货", "补货", "下单", "取货", "提货",
            "超市", "便利店", "商场", "市场", "店里", "店铺", "批发",
        ) -> ApocalypseAmbientEventKindV5.SHOPPING
        hasAny(
            "走一下", "走走", "散步", "逛", "出门", "出去", "外面", "上街", "路上",
            "前往", "赶路", "开车", "坐车", "骑车", "打车", "出发", "沿路", "绕路",
        ) -> ApocalypseAmbientEventKindV5.TRAVEL
        hasAny(
            "搜索", "搜寻", "搜", "探索", "调查", "侦察", "巡逻", "查看附近",
            "看看周围", "找东西", "找物资", "翻找", "踩点",
        ) -> ApocalypseAmbientEventKindV5.EXPLORE
        else -> ApocalypseAmbientEventKindV5.NONE
    }
}

/**
 * The long-form director is deliberately not called for every errand. Keep those scenes lively by
 * attaching a cheap, deterministic encounter contract to the beat that the writer already receives.
 * This fixes the old failure mode where "walk outside" or "buy supplies" became an empty transition.
 */
private fun enrichApocalypseAmbientEventBeatV5(
    save: ApocalypseV3Save,
    action: String,
    beat: ApocalypseV3Beat,
    usedDirector: Boolean,
): ApocalypseV3Beat {
    var kind = apocalypseAmbientEventKindV5(action)
    if (kind == ApocalypseAmbientEventKindV5.NONE) {
        val focusedOrQuiet = listOf(
            "睡", "休息", "原地等", "安静待", "聊天", "谈心", "做饭", "整理", "洗澡",
            "训练", "战斗", "攻击", "开枪", "逃跑", "治疗", "包扎", "阅读", "写日记",
        ).any(action::contains)
        if (!usedDirector && !focusedOrQuiet && (save.scene + 1) % 3 == 0) {
            kind = ApocalypseAmbientEventKindV5.RHYTHM
        }
    }
    if (kind == ApocalypseAmbientEventKindV5.NONE) return beat

    val preImpact = save.director.dayIndex < 0
    val eventCore = when (kind) {
        ApocalypseAmbientEventKindV5.SHOPPING -> if (preImpact) {
            "玩家正在购物、采购或取货。本幕不能只写成自动付款→自动加库存；在不阻止合理采购的前提下，至少真实演出一个与当前店铺/市场/仓库有关的额外变量，例如店员或老板的判断、其他顾客、库存与替代品、批量价格、配送/搬运、支付、停车、排队、误会、人情、小机会或临时变化。它可以是麻烦，也可以是好事，但必须让玩家能回应。"
        } else {
            "玩家正在灾后交易、搜集或补给。本幕至少真实演出一个与补给地点有关的可互动变量：交易对象与信用、稀缺库存、替代物、运输与搬运、第三方竞争、受损设施、伤员、路线风险、污染、骗局、善意或临时交换条件。不要把所有补给都写成无人物的自动拾取，也不要每次都强制打架。"
        }
        ApocalypseAmbientEventKindV5.TRAVEL -> if (preImpact) {
            "玩家正在外出、散步、逛街或赶路。本幕途中至少发生一个符合具体地点、时段和天气的生活事件/遭遇，例如交通变化、路人或熟人、店家、维修施工、轻微治安、临时活动、失物、动物、天气、车辆、小事故、陌生善意、误会或机会。不要把城市写成空走廊，也不要把每次出门都写成末世预兆。"
        } else {
            "玩家正在灾后移动。本幕途中至少发生一个符合路线与阶段的真实遭遇：路障/塌方/积水、幸存者、交易与求助、受损车辆或设施、可利用资源、感染者迹象、动物/植物异变、污染天气、第三方先行动、争执、善意或信息机会。危险可以避开、谈判或绕行，不要求每次变成战斗。"
        }
        ApocalypseAmbientEventKindV5.EXPLORE -> if (preImpact) {
            "玩家正在搜索、调查或踩点。本幕必须找到至少一个值得玩家作出反应的具体发现、人物或现实阻力，并让调查改变信息、路线、关系、时间或资源。发现可以完全是普通城市生活与个人支线，不必强行连接赤潮核心谜团。"
        } else {
            "玩家正在灾后探索。本幕必须出现至少一个有现场证据和选择空间的具体发现/遭遇，例如可利用物资、幸存者痕迹、污染生态、建筑状态、路线变化、第三方行动、线索、陷阱或维修机会；不能只用旁白说‘搜了一圈什么也没发生’。"
        }
        ApocalypseAmbientEventKindV5.RHYTHM -> if (preImpact) {
            "这一幕是世界生活节奏的补充节点：安排一个低到中强度、与当前位置自然相关的小事件，让正常城市里的人、事务和偶然性交叉一次。它不必危险、不必是任务，也不必和末世谜团有关。"
        } else {
            "这一幕是世界生活节奏的补充节点：安排一个低到中强度、从当前据点/路线/人群/生态自然长出来的小事件。可以是维护、交易、求助、争执、消息、天气、设施、动物植物或第三方行动，不必升级成大战。"
        }
        ApocalypseAmbientEventKindV5.NONE -> return beat
    }
    val diversity = if (preImpact) {
        "灾前尤其要保持生活支线多样：相当一部分事件应与核心谜团完全无关；不要连续重复缺货、神秘电话、动物异常或同一种预兆。"
    } else {
        "灾后事件生态也要多样：不要连续只刷感染者或敌对组织；救援、维修、交易、社区、人情、路线、污染生态和普通人的生活都能成为事件。"
    }
    val contract = "【场景事件硬约束】先完整兑现玩家行动，再让事件从该行动经过的地点、人物和现实流程里自然长出来，不能用突发事件抢走玩家行动。$eventCore 通常一个主事件就够，必须在正文中实际发生而不是一句话概述，并至少改变人物、资源、信息、关系、时间、路线或风险中的一项。$diversity"
    return beat.copy(directive = listOf(beat.directive.trim(), contract).filter(String::isNotBlank).joinToString("\n"))
}

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
                    val usedDirector = planResult.directorApplied
                    val plannedBeat = enrichApocalypseAmbientEventBeatV5(
                        save = save,
                        action = cleanAction,
                        beat = sanitizeApocalypseAbilityProgressionV5(save, cleanAction, planResult.beat),
                        usedDirector = usedDirector,
                    )
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
