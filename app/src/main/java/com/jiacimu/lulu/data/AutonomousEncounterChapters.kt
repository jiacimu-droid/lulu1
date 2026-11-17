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
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * One grounded literary request for the entire witnessed encounter (N roles,
 * N readable viewpoints). Public scene facts are shared, but NO role's
 * private chats, diaries or unrelated memories cross this call. Exact role-owned
 * thoughts/choices linked to this encounter may be supplied to the narrator,
 * partitioned by owner; never make them another role's knowledge or scene facts.
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

    /**
     * A model may exhaust its output budget after finishing some chapters but
     * before closing the containing JSON array/object. Preserve only complete,
     * individually valid chapter objects; never invent unfinished prose.
     */
    private fun completedChaptersFromPartialEnvelope(raw: String): JSONArray? {
        val marker = Regex(""""chapters"\s*:\s*\[""").find(raw) ?: return null
        val arrayStart = raw.indexOf('[', marker.range.first)
        val completed = JSONArray()
        var objectStart = -1
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in arrayStart + 1 until raw.length) {
            val char = raw[index]
            if (quoted) {
                if (escaped) escaped = false
                else when (char) {
                    '\\' -> escaped = true
                    '"' -> quoted = false
                }
                continue
            }
            when (char) {
                '"' -> quoted = true
                '{' -> { if (depth == 0) objectStart = index; depth++ }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && objectStart >= 0) {
                        runCatching { JSONObject(raw.substring(objectStart, index + 1)) }
                            .getOrNull()?.let(completed::put)
                        objectStart = -1
                    }
                }
                ']' -> if (depth == 0) break
            }
        }
        return completed.takeIf { it.length() > 0 }
    }

    fun parseChapters(raw: String, participantIds: List<String>): Map<String, WorldLifeSceneDraft> {
        val entries = ModelStructuredOutput.objectOrNull(raw)?.optJSONArray("chapters")
            ?: completedChaptersFromPartialEnvelope(raw) ?: return emptyMap()
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


    /** Source-matched personal thought/decision excerpts for ONE person's literary POV.
     * Never treat these as visible actions or facts known to other participants. */
    internal fun roleEvidence(
        mind: JSONObject,
        ownedEvidenceIds: Set<String>,
        startedAt: Instant,
        endedAt: Instant,
    ): String {
        val thoughts = mind.optJSONArray("innerVoices")
        val voices = (0 until (thoughts?.length() ?: 0))
            .mapNotNull { thoughts?.optJSONObject(it) }
            .filter { entry ->
                entry.optString("evidenceId") in ownedEvidenceIds &&
                    runCatching { Instant.parse(entry.optString("occurredAt")) }
                        .getOrNull()?.let { !it.isAfter(endedAt) } == true
            }.takeLast(4)
        val decisions = mind.optJSONArray("decisions")
        val choices = (0 until (decisions?.length() ?: 0))
            .mapNotNull { decisions?.optJSONObject(it) }
            .filter { entry ->
                val time = runCatching { Instant.parse(entry.optString("at")) }.getOrNull()
                    ?: return@filter false
                if (time.isAfter(endedAt)) return@filter false
                val linked = entry.optString("causalEvidenceId") in ownedEvidenceIds
                val preceding = !time.isAfter(startedAt) &&
                    !time.isBefore(startedAt.minusSeconds(900)) &&
                    entry.optString("selected") != "silent"
                linked || preceding
            }.takeLast(4)
        return buildString {
            if (voices.isEmpty() && choices.isEmpty()) {
                append("没有与本场证据相关的已保存私人心理；不要补造当时心声。")
                return@buildString
            }
            voices.forEach { entry ->
                appendLine("本人当时真正记录的心声：${entry.optString("thought").take(240)}")
            }
            choices.forEach { entry ->
                appendLine("本人做过的选择（不等于执行成功）：${entry.optString("selected")}；原因=${entry.optString("reason").take(220)}；回执=${entry.optString("outcome").take(180)}；成功=${entry.optBoolean("succeeded")}")
            }
        }.trim()
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
        // Public actions remain shared. Arrival notifications and source-linked
        // private thoughts are tagged by their actual owner for the matching POV
        // only; the literary writer does not update any character's knowledge.
        val publicFacts = buildString {
            appendLine("真实地点：${meeting.location}（$locationCode）；时间=$at")
            appendLine("共同在场：${ids.joinToString("；") { id ->
                "$id=${characters.getValue(id).displayName}"
            }}")
            worldTick?.let { appendLine("公开现场事件：${it.summary}；状态=${it.status}") }
            appendLine("【人物已设定的外在气质与说话风格，仅供文学表现，不是新事件或任何人讲过的话】")
            appendLine(WorldSceneParticipantProfile.context(ids, forNarrator = true))
            appendLine(WorldSceneParticipantProfile.instruction)
            appendLine("【当事人共同可见的现有关系事实】")
            appendLine(CharacterSocialRelationship.contextForGroup(ids).ifBlank { "尚无全部见证者共同确认的既往互动" })
            appendLine("【本场主人到访与离场｜按各角色实际收到的原始通知隔离；不能从只有到访的通知推断主人仍在】")
            ids.forEach { id ->
                val received = WorldVisitorSceneContext.eventsFor(id, locationCode, at)
                appendLine("${characters.getValue(id).displayName}（$id）获知：")
                appendLine(WorldVisitorSceneContext.describe(received))
            }
            appendLine("【按角色隔离的、与本场及其前因相关的私人心声和已作选择】")
            ids.forEach { id ->
                val witnessed = AutonomousSocialRuntime.witnessedEpisodeSources(
                    meeting, id, worldTick, meeting.startedAt)
                val ownedSources = SharedExperienceTimeline.eventsByIds(id, witnessed)
                    .mapTo(hashSetOf()) { it.id }
                ownedSources += WorldVisitorSceneContext.eventsFor(id, locationCode, at)
                    .map(SharedTimelineEvent::id)
                appendLine("${characters.getValue(id).displayName}（$id）的私有心境，只准用于 $id 的章节：")
                appendLine(roleEvidence(
                    CharacterInnerLifeStore.historySnapshot(id, 160),
                    ownedSources, meeting.startedAt, at))
            }
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
                    请按角色自己的到访通知与当时已保存的心声，承接其为何来到这里、是否要找主人及随后如何改变想法。
                    不能凭角色收到到访通知就假设主人现在仍在场，也不能把主人写成已参与本场角色对话；若确有离场通知，要让其作为可知的事实进入对应视角。
                    私人心境只用于所属 characterId 的章节，绝不能写成其他角色知道的内容，或引用成已说出口的台词；没有证据时留白，不为剧情补造动机。
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
        // A failed transport / HTTP request is NOT a malformed literary reply.
        // The shared gateway already records the detailed, private-safe transport
        // diagnostics; do not replace them with a misleading JSON error.
        val reply = result.getOrElse { error ->
            com.jiacimu.lulu.LuluRepositories.performance.recordError(
                source = "生活小剧场", title = "合场文学模型请求失败",
                message = WorldLifeEpisodeComposer.failureSummary(error) +
                    "；公共事实已保存，未自动重发付费请求")
            return
        }
        val raw = reply.text
        if (WorldLifeNarrativeGuide.skipReason(raw) != null) return
        val chapters = parseChapters(raw, ids)
        if (chapters.isEmpty()) {
            com.jiacimu.lulu.LuluRepositories.performance.recordError(
                source = "生活小剧场", title = "合场文学格式不可用",
                message = "模型返回 " + raw.length + " 字；结束原因=" +
                    (reply.finishReason ?: "未提供") +
                    "；未找到完整有效的角色章节，公共事实已保存")
            return
        }
        if (chapters.size < ids.size) {
            com.jiacimu.lulu.LuluRepositories.performance.recordError(
                source = "生活小剧场", title = "合场文学部分章节未完成",
                message = "可用章节 " + chapters.size + "/" + ids.size +
                    "；仅保留完整的真实视角，未重新付费生成")
        }
        WorldLifeEpisodeStore.initialize(context)
        ids.forEach { id ->
            val draft = chapters[id] ?: return@forEach
            val sourceId = "meeting-${meeting.id}-autonomous-summary-viewer-$id"
            val source = SharedExperienceTimeline.eventsByIds(id, listOf(sourceId))
                .firstOrNull() ?: return@forEach
            // Keep only verified, current, viewer-scoped evidence IDs.
            val candidateIds = AutonomousSocialRuntime.witnessedEpisodeSources(
                meeting, id, worldTick, meeting.startedAt) +
                WorldVisitorSceneContext.eventsFor(id, locationCode, at).map { it.id }
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
