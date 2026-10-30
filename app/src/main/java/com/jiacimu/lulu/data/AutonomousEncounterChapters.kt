package com.jiacimu.lulu.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ScopedModelSelections
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * One grounded literary request for the entire witnessed encounter (N roles,
 * N readable viewpoints). Public scene facts are shared, but NO role's
 * private memory, innerThought, motive, diary or appraisal crosses this call.
 *
 * Literary text is a non-authoritative artifact, never an event or receipt.
 */
internal object AutonomousEncounterChapters {
    /** Exactly ONE queued literary job for a completed shared meeting, not
     * one work item per witness. World/social execution never waits for prose. */
    fun enqueue(
        context: Context,
        meeting: MeetingSession,
        locationCode: String,
        worldTick: DigitalWorldLifeTick?,
    ) {
        val input = Data.Builder()
            .putString("meetingId", meeting.id)
            .putString("locationCode", locationCode)
        worldTick?.let { tick ->
            input.putString("tickId", tick.incidentId)
                .putString("tickLocation", tick.locationCode)
                .putString("tickName", tick.locationName)
                .putString("tickKind", tick.kind)
                .putString("tickItemId", tick.anchorItemId)
                .putString("tickItemName", tick.anchorItemName)
                .putString("tickStatus", tick.status)
                .putInt("tickStage", tick.stage)
                .putString("tickSummary", tick.summary.take(700))
                .putLong("tickOccurredAt", tick.occurredAt.toEpochMilli())
        }
        val request = OneTimeWorkRequestBuilder<AutonomousEncounterChaptersWorker>()
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(input.build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "lulu-world-one-chapter-request-${meeting.id}",
            ExistingWorkPolicy.KEEP, request)
    }

    fun parseChapters(raw: String, participantIds: List<String>): Map<String, WorldLifeSceneDraft> {
        val json = ModelStructuredOutput.objectOrNull(raw) ?: return emptyMap()
        val entries = json.optJSONArray("chapters") ?: return emptyMap()
        val allowed = participantIds.toSet()
        val result = linkedMapOf<String, WorldLifeSceneDraft>()
        for (index in 0 until minOf(entries.length(), allowed.size * 3)) {
            val entry = entries.optJSONObject(index) ?: continue
            val id = entry.optString("characterId")
            if (id !in allowed || id in result) continue
            val title = entry.optString("title").trim().take(48)
            val scene = entry.optString("scene").trim()
            // A literary draft may describe ONLY confirmed public actions.
            val parsed = WorldLifeEpisodeComposer.parseModelScene(
                JSONObject().put("title", title).put("scene", scene).toString(), title)
                ?: continue
            result[id] = parsed
        }
        return result
    }

    suspend fun composeAndRecord(
        context: Context,
        meeting: MeetingSession,
        characters: Map<String, CharacterSettings>,
        locationCode: String,
        worldTick: DigitalWorldLifeTick?,
        at: Instant,
    ) {
        if (meeting.endedAt == null || (meeting.turns.isEmpty() && worldTick == null)) return
        val ids = meeting.participantIds.distinct().take(AutonomousEncounterScript.MAX_PARTICIPANTS)
        if (ids.isEmpty()) return
        // The final model sees ONLY the common, witnessed conversation and
        // objects. There is deliberately no CharacterAndScenario context.
        val publicFacts = buildString {
            appendLine("真实地点：${meeting.location}（$locationCode）；时间=$at")
            appendLine("共同在场：${ids.joinToString("；") { id ->
                "$id=${characters.getValue(id).displayName}"
            }}")
            worldTick?.let { appendLine("公开现场事件：${it.summary}；状态=${it.status}") }
            appendLine("【人物已设定的外在气质与说话风格，仅供文学表现，不是新事件或任何人讲过的话】")
            ids.forEach { id ->
                appendLine("${characters.getValue(id).displayName}：${characters.getValue(id).persona.take(350)}")
            }
            appendLine("【当事人共同可见的现有关系事实】")
            appendLine(CharacterSocialRelationship.contextForGroup(ids).ifBlank { "尚无全部见证者共同确认的既往互动" })
            appendLine("以下为同一场会面已保存的原始时间线，不能添加未记载的事或台词：")
            meeting.turns.forEachIndexed { i, turn ->
                appendLine("${i + 1}. ${turn.speakerName}（${turn.speakerId}）")
                if (turn.sceneText.isNotBlank()) appendLine("  可见动作：${turn.sceneText}")
                if (turn.dialogue.isNotBlank()) appendLine("  确实说过：${turn.dialogue}")
            }
        }
        ScopedModelSelections.initialize(context.applicationContext)
        val connection = runCatching {
            ScopedModelSelections.worldLifeConnection(ids.first())
        }.getOrNull()
        if (connection == null) {
            com.jiacimu.lulu.LuluRepositories.performance.recordError(
                source = "生活小剧场", title = "合场文学模型未配置",
                message = "请在 API 设置中选择生活小剧场文学正文模型")
            return
        }
        val result = try {
            LuluAiServices.gateway.generate(
                characterId = ids.first(),
                facts = publicFacts,
                instruction = """
                    请依据已实际发生的共同时间线，写一次完整的生活短篇，
                    并分别呈现每位在场者从公开行为角度能看见的不同侧面。
                    输出一个 JSON 对象：
                    {"chapters":[{"characterId":"真实参与者ID","title":"鲜活具体的篇名","scene":"围绕本次真实事件充分展开、有完整发展与收束的短篇"}]}
                    chapters 必须包含名单里的每人各一篇，不能重复 ID，不要 JSON 外说明。
                    同一场戏可以有很多真实对话轮次，也可以在意外小事后出现格外尴尬的沉默：
                    关系生疏的人可能无从接话，熟悉的人可能自然打趣，甚至关系紧张的人可能更难堪。
                    只依据真实角色外在设定和已证实的行为写出趣味、节奏、态度、细微波折，
                    而不是把事实压缩成一段风景或一句寒暄。人物可以不同意、冷淡、尴尬、说笑，
                    不强制亲密或煽情。每篇自然围绕自己可见的行为选择组织，不要求同一开头。
                    绝不能加入未记载的台词、动作、意外、物品、活动完成、关系变化或未来事件；
                    不能猜成事实，不能写出别人没有表达的私人念头，也不能冒充当事人。
                    文学化结果只供阅读，不用于更新地图、关系、记忆、计划或行动事实。
                    ${WorldLifeNarrativeGuide.instruction}
                """.trimIndent(),
                source = "数字世界·合场文学",
                title = "共同经历的不同视角",
                maxTokens = 1_700 + ids.size * 2_400,
                contextMode = CompanionContextMode.Isolated,
                connectionOverride = connection,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            com.jiacimu.lulu.LuluRepositories.performance.recordError(
                source = "生活小剧场", title = "合场文学生成异常",
                message = error.message.orEmpty().take(150))
            return
        }
        val raw = result.getOrNull()?.text.orEmpty()
        if (WorldLifeNarrativeGuide.skipReason(raw) != null) return
        val chapters = parseChapters(raw, ids)
        if (chapters.isEmpty()) {
            com.jiacimu.lulu.LuluRepositories.performance.recordError(
                source = "生活小剧场", title = "合场文学格式不可用",
                message = result.exceptionOrNull()?.message?.take(150)
                    ?: "公共事实仍保存；文学正文没有生成有效的每人章节")
            return
        }
        WorldLifeEpisodeStore.initialize(context)
        ids.forEach { id ->
            val draft = chapters[id] ?: return@forEach
            val sourceId = "meeting-${meeting.id}-autonomous-summary-viewer-$id"
            val source = SharedExperienceTimeline.eventsByIds(id, listOf(sourceId))
                .firstOrNull() ?: return@forEach
            // Keep only verified, current, viewer-scoped evidence IDs.
            val candidateIds = AutonomousSocialRuntime.witnessedEpisodeSources(
                meeting, id, worldTick, meeting.startedAt)
            val verified = SharedExperienceTimeline.eventsByIds(id, candidateIds)
            val present = verified.mapTo(hashSetOf()) { it.id }
            val links = (listOf(sourceId) + candidateIds.filter { it in present })
                .distinct().take(32)
            val episode = WorldLifeEpisodeStore.record(
                characterId = id, location = locationCode,
                title = draft.title, prose = draft.scene,
                actualAction = source.content, evidenceId = sourceId,
                now = source.occurredAt,
                expectedSource = source, sourceEvidenceIds = links,
                unresolvedThread = draft.unresolvedThread, category = "encounter",
            )
            if (episode != null) {
                MigratedDomainStores.chat.appendPrivateActivityNotice(
                    id, "[独处片段|${episode.id}] ${characters.getValue(id).displayName}的《${episode.title}》 · 查看生活小剧场")
            }
        }
    }
}

/** WorkManager owns the single optional literary model request. On failure
 * leave the already verified public conversation untouched; no paid retry. */
class AutonomousEncounterChaptersWorker(
    context: Context, params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            initializeBackgroundRuntime(applicationContext)
            val meetingId = inputData.getString("meetingId") ?: return Result.success()
            val location = inputData.getString("locationCode") ?: return Result.success()
            val meeting = DigitalWorldStore.state.value.meetings.firstOrNull {
                it.id == meetingId && it.endedAt != null
            } ?: return Result.success()
            // A verified incident with no dialogue can still be a meaningful quiet scene.
            val characters = meeting.participantIds.associateWith {
                MigratedDomainStores.characters.get(it)
            }
            val tick = inputData.getString("tickId")?.let { incidentId ->
                DigitalWorldLifeTick(
                    incidentId = incidentId,
                    locationCode = inputData.getString("tickLocation").orEmpty(),
                    locationName = inputData.getString("tickName").orEmpty(),
                    kind = inputData.getString("tickKind").orEmpty(),
                    anchorItemId = inputData.getString("tickItemId").orEmpty(),
                    anchorItemName = inputData.getString("tickItemName").orEmpty(),
                    status = inputData.getString("tickStatus").orEmpty(),
                    stage = inputData.getInt("tickStage", 0),
                    summary = inputData.getString("tickSummary").orEmpty(),
                    occurredAt = Instant.ofEpochMilli(
                        inputData.getLong("tickOccurredAt", meeting.startedAt.toEpochMilli())),
                )
            }
            AutonomousEncounterChapters.composeAndRecord(
                applicationContext, meeting, characters, location, tick,
                meeting.endedAt ?: return Result.success())
            return Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            com.jiacimu.lulu.LuluRepositories.performance.recordError(
                source = "生活小剧场", title = "合场文学任务失败",
                message = error.message.orEmpty().take(160))
            return Result.success()
        }
    }
}
