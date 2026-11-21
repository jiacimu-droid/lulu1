package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Projections and validation over the existing timeline/learning ledger; no second store. */
internal object CharacterExperienceLearning {
    private val experienceSchema = """{"topic":"稳定主题","situationEvidenceId":"当时需求/观察的真实ID","actionEvidenceId":"当次tool-router回执或已完成的屏幕观察ID","understanding":"这次发现","methodImpression":"可选，我对这种做法本身的体验","applicableWhen":"适用情境","avoidWhen":"不适用/停止条件","uncertainty":"仍未知之处","feedbackEvidenceId":"可选的后来用户原话ID","feedbackQuote":"该原话中的逐字引用","revisesEvidenceId":"确有修订时旧经验ID"}"""
    // The full experienceSchema belongs to reflectionInstruction/instruction.
    // Expanding it into every paid runtime prompt adds ~300 characters just
    // for example field descriptions and can breach the 3000-char shared
    // character-decision budget even when the caller source appears short.
    val runtimeInstruction = """
        【资源与相处经验】从现有能力挑适合好奇或目标的办法，也可初试。回执后想想这种做法带来了什么、何时还想用或换一种。
        可选 experienceLearning={topic,situationEvidenceId,actionEvidenceId,understanding,methodImpression,applicableWhen,avoidWhen,uncertainty,feedbackEvidenceId,feedbackQuote,revisesEvidenceId}；引用真实回执，本人好恶与用户反馈分开。
        复用时在对应步骤填 learningApplication={sourceEvidenceId,action,howApplied}；有用户逐字反馈才填 learningFeedback={sourceEvidenceId,actionEvidenceId,evaluation,quote,why}；反例用 learningChallenge={sourceEvidenceId,issue}。按回执归因，尊重最新拒绝。
    """.trimIndent()
    val reflectionInstruction = """
        数组中的工具经验项使用 kind=ToolExperience，其余字段为 $experienceSchema。回看当时目标、真实工具和回执，可在 methodImpression 留下自己喜欢或不喜欢这种方式的具体原因，在 applicableWhen 写将来哪个愿望会让你想起它。结合结果与反馈，形成适用和停止条件及下次调整。可从失败学习局限；只有后来用户原话才是用户反馈，不将邻近发言自动当评价。已有同主题经验确被新尝试修订时填 revisesEvidenceId，保留原始来源；单次请求不升级为固定习惯。工具成功不证明对用户有效，用户沉默不证明失败。没有新认识就不重复保存，不需要slot或disposition。
    """.trimIndent()
    val instruction = """
        【主动使用资源与从相处中学习】先判断当前想达成什么、自己还缺什么信息、过去什么做法适用，再选择现有工具；不必等对方点名工具。需要核实的现实信息、时效信息或没有把握的重要建议，应考虑实际检索并核对来源；有充分依据或只是在倾诉时不必为了学习而搜索。工具的能力、权限和当前状态由程序提供，具体用途由你结合现场与经历判断，不按关键词固定派发搜索、音乐、屏幕或弹窗。
        日常相处、失败尝试也能成为学习来源，无须先找一篇网页。亲手尝试后，你可以记下自己对做法的好恶、所得与局限，连同下次适用的目标；主人请你做的事同样可以成为自己的经验。若有值得保留的认识，可选 experienceLearning=$experienceSchema。只引用提供的原始ID；没有真实执行不声称试过。执行失败可以学到局限；执行成功、沉默或谢谢都不证明对方满意。偏好与习惯仍从她的原话和跨次经历认识，一次请求只保留当次条件，不升级为每次必须做。
        实际复用已列出的阅读理解或日常经验时，可在主行动或每个steps项填写 learningApplication={"sourceEvidenceId":"所用理解/经验ID","action":"本项实际工具名或message/phone_reply","howApplied":"这次怎样使用"}；每项只归因自己的行动，副动作的应用放在对应steps项，不能用发言成功代替另一项工具成功。若本轮用户明确评价了以前的应用，可填 learningFeedback={"sourceEvidenceId":"理解/经验ID","actionEvidenceId":"旧应用ID","evaluation":"helped|unhelpful|mixed","quote":"用户本轮逐字原话","why":"对应哪次做法"}；纠正旧理解可用 learningChallenge={"sourceEvidenceId":"理解/经验ID","issue":"具体争议"}。最新拒绝、情境变化与反例优先考虑，不将旧经验当命令或授权，不为收集好评反复追问。字段都可省略，不新增一次反思调用。
    """.trimIndent()

    val receiptSources = setOf("tool-router", "screen-consent", "cloud-service")
    private val groupDeliveryTools = setOf("send_group_message", "send_group_sticker")
    private val receiptMetadata = setOf("success", "status", "evidenceId", "requestId", "conversationId")

    private const val methodFields = "{actionEvidenceId,topic,methodImpression,applicableWhen,avoidWhen}"
    const val methodPrompt = "这次办法本身有没有让你觉得值得再用，或发现它不合适？有切身体会才在 JSON 附 method=$methodFields。写自己的体验与下次适用情境；这些字段只存入自己的经验，不会执行动作。"

    data class Receipt(val tool: String, val status: String, val success: Boolean,
        val summary: String, val causeId: String, val reason: String,
        val requestEvidenceId: String = "", val operation: String = "",
        val backingEvidenceId: String = "", val causeRevision: Long = 0)

    /** A deferred result is linked to the original request; merely queueing a job does not count. */
    fun receipt(event: SharedTimelineEvent): Receipt? {
        if (event.source == "screen-consent" && event.taskId.isNotBlank() &&
            event.id == "screen-observation-${event.characterId}-${event.taskId}") {
            val requestId = "tool-${event.characterId}-${event.taskId}"
            val original = CharacterActionEvidence.resolve(event.characterId, requestId) ?: return null
            if (original.source != "tool-router" || original.evidenceKind != EventEvidenceKind.ToolResult ||
                original.occurredAt.isAfter(event.occurredAt)) return null
            val request = runCatching { JSONObject(original.content) }.getOrNull() ?: return null
            val tool = request.optString("tool")
            if (tool !in setOf("capture_screen", "read_screen") || request.optString("status") != "pending") return null
            val result = runCatching { JSONObject(event.content) }.getOrNull() ?: return null
            val success = result.optBoolean("success")
            if (success && (result.optString("status") != "observed" ||
                    event.evidenceKind != EventEvidenceKind.Observation) ||
                !success && event.evidenceKind != EventEvidenceKind.ToolResult) return null
            return Receipt(tool, result.optString("status").ifBlank { "failed" }, success,
                result.optString("summary").ifBlank { result.optString("error") },
                request.optString("causeEvidenceId"), request.optString("reason"), requestId,
                causeRevision = request.optLong("causeEvidenceRevision"))
        }
        if (event.source == "cloud-service") {
            val task = event.taskId
            if (task.isBlank() || event.id != "cloud-$task-${event.characterId}" ||
                event.evidenceKind != EventEvidenceKind.ToolResult) return null
            val result = runCatching { JSONObject(event.content) }.getOrNull() ?: return null
            val status = result.optString("status")
            if (result.optString("requestId") != task || result.optString("characterId") != event.characterId ||
                status !in setOf("succeeded", "failed", "cancelled")) return null
            val requestId = "tool-${event.characterId}-$task"
            val original = CharacterActionEvidence.resolve(event.characterId, requestId)
                ?.takeIf { it.source == "tool-router" && it.evidenceKind == EventEvidenceKind.ToolResult &&
                    !it.occurredAt.isAfter(event.occurredAt) } ?: return null
            val request = runCatching { JSONObject(original.content) }.getOrNull() ?: return null
            if (request.optString("tool") != "cloud_task" || request.optString("status") != "pending") return null
            val file = result.optJSONObject("result")?.optString("file").orEmpty()
            if (status == "succeeded" && file.isBlank()) return null
            return Receipt("cloud_task", status, status == "succeeded",
                if (status == "succeeded") "云端服务返回已生成文件：$file"
                else result.optString("error").ifBlank { "云端任务状态：$status" },
                request.optString("causeEvidenceId"), request.optString("reason"), requestId,
                operation = result.optString("kind"), causeRevision = request.optLong("causeEvidenceRevision"))
        }
        if (event.source != "tool-router" || event.evidenceKind != EventEvidenceKind.ToolResult) return null
        val root = runCatching { JSONObject(event.content) }.getOrNull() ?: return null
        val result = root.optJSONObject("result") ?: runCatching { JSONObject(root.optString("result")) }.getOrNull()
            ?: return null
        val tool = root.optString("tool")
        if (CapabilityRegistry.find(tool) == null) return null
        val status = root.optString("status").trim().lowercase()
        val success = result.optBoolean("success")
        if (success) {
            // The durable envelope AND the original executor result must confirm completion.
            // Do not let a stale/legacy envelope turn a pending nested result into experience.
            val envelope = JSONObject().put("success", true).put("status", status)
            if (status.isBlank() || !ToolExecutionStatus.completed(envelope) ||
                !ToolExecutionStatus.completed(result)) return null
        } else if (status !in setOf("failed", "denied", "requested_unverified", "unverified", "cancelled")) return null
        val args = runCatching { JSONObject(root.optString("fingerprint").substringAfter(':')) }.getOrNull()
        val operation = args?.let { choice ->
            listOf("op", "action", "command", "worldAction", "area", "gameId", "kind").mapNotNull { key ->
                choice.optString(key).takeIf { it.isNotBlank() }?.let { "$key=${it.take(40)}" }
            }.joinToString("，")
        }.orEmpty()
        val backing = if (!success) "" else result.optString("evidenceId").takeUnless { it == event.id }.orEmpty()
        val scopedBacking = if (tool in groupDeliveryTools && backing.isNotBlank() && !backing.endsWith(":group:${event.characterId}"))
            "$backing:group:${event.characterId}" else backing
        val summary = result.optString("summary").ifBlank { result.optString("error") }
            .ifBlank { result.optString("verification") }.ifBlank { describeReturnedFields(result) }
        return Receipt(tool, status, success, summary, root.optString("causeEvidenceId"), root.optString("reason"),
            operation = operation, backingEvidenceId = scopedBacking,
            causeRevision = root.optLong("causeEvidenceRevision"))
    }

    /** Structured-only device reads still show what became observable. Keep
     * private payloads such as notification text and raw coordinates out of
     * the general prompt; the owning result-bearing scene receives full data. */
    private fun describeReturnedFields(result: JSONObject): String {
        val fields = result.keys().asSequence().filterNot { it in receiptMetadata || result.isNull(it) }
            .take(10).toList()
        val count = (result.opt("count") as? Number)?.toInt()
        return listOfNotNull(
            count?.let { "返回条数=$it" },
            fields.filterNot { it == "count" }.takeIf { it.isNotEmpty() }
                ?.joinToString("、", prefix = "返回字段："),
        ).joinToString("；")
    }

    /** Keep entire episodes in the existing reflection working set, including failures.
     * Neighbouring utterances are context, never automatically attributed feedback. */
    fun episodeEvidence(events: List<SharedTimelineEvent>, limit: Int = 6): List<SharedTimelineEvent> {
        val byId = events.associateBy { it.id }
        return events.filter { receipt(it) != null }.takeLast(limit).flatMap { action ->
            val index = events.indexOf(action)
            val cause = receipt(action)?.causeId?.let(byId::get)
            val before = events.take(index).lastOrNull {
                it.evidenceKind == EventEvidenceKind.UserStatement &&
                    (it.channel == "私聊" || it.channel.contains("电话"))
            }
            val after = events.drop(index + 1).takeWhile { receipt(it) == null }
                .filter { it.evidenceKind == EventEvidenceKind.UserStatement }.take(2)
            listOfNotNull(cause, before, action) + after
        }.distinctBy { it.id }.sortedBy { it.occurredAt }
    }

    fun evidenceText(event: SharedTimelineEvent): String = receipt(event)?.let {
        val operation = it.operation.takeIf(String::isNotBlank)?.let { value -> "（$value）" }.orEmpty()
        "工具=${it.tool}$operation；执行=${it.status}；原始输入ID=${it.causeId}；当时理由=${it.reason.take(150)}；实际结果=${it.summary}"
    } ?: event.evidenceContent

    private fun eligible(characterId: String, events: List<SharedTimelineEvent>,
        excluded: Set<String> = emptySet()): List<SharedTimelineEvent> {
        val attempts = events.mapNotNull { event ->
            if (event.characterId != characterId || event.id in excluded) return@mapNotNull null
            val attempt = receipt(event) ?: return@mapNotNull null
            if (!CapabilityRegistry.canChoose(attempt.tool)) return@mapNotNull null
            if (listOf(attempt.causeId, attempt.requestEvidenceId, attempt.backingEvidenceId).any { it in excluded })
                return@mapNotNull null
            event to attempt
        }
        val required = CharacterPerceptionContext.liveEvidenceIds(characterId,
            attempts.flatMap { (_, attempt) ->
                listOf(attempt.causeId, attempt.requestEvidenceId, attempt.backingEvidenceId)
            })
        val causes = SharedExperienceTimeline.eventsByIds(characterId,
            attempts.map { (_, attempt) -> attempt.causeId }).associateBy { it.id }
        val hidden = SharedExperienceTimeline.foreignEvidenceIds(characterId,
            attempts.map { (_, attempt) -> attempt.backingEvidenceId })
        return attempts.mapNotNull { (event, attempt) ->
            if (attempt.causeId.isNotBlank() && (attempt.causeId !in required ||
                    attempt.causeRevision > 0 && causes[attempt.causeId]?.revision != attempt.causeRevision) ||
                attempt.requestEvidenceId.isNotBlank() && attempt.requestEvidenceId !in required ||
                attempt.backingEvidenceId in hidden || SharedExperienceTimeline.isDeletedEvent(attempt.backingEvidenceId) ||
                attempt.tool in groupDeliveryTools && attempt.backingEvidenceId.isNotBlank() &&
                attempt.backingEvidenceId !in required) null else event
        }
    }

    fun recentContext(characterId: String, excluded: Set<String> = emptySet()): String {
        val events = SharedExperienceTimeline.recentToolResults(characterId, Instant.now(), 64)
        val newest = eligible(characterId, events, excluded)
        val attempts = (newest.distinctBy { receipt(it)?.tool } + newest)
            .distinctBy { it.id }.take(4).asReversed()
        if (attempts.isEmpty()) return ""
        return "【最近工具尝试】回执可供当前行动参考。\n" + renderAttempts(attempts)
    }

    /** Advance only after an accepted decision sees this batch. Old and rare
     * tools keep their turn even when routine actions dominate recent history. */
    fun pendingMethods(context: Context, role: String, now: Instant): List<SharedTimelineEvent> {
        val selected = mutableListOf<SharedTimelineEvent>()
        var after: SharedTimelineEvent? = null
        val limit = 6
        val pageSize = 192
        while (selected.size < limit) {
            val page = SharedExperienceTimeline.perceptionPage(role, now, after?.occurredAt,
                after?.id.orEmpty(), pageSize, sources = receiptSources)
            val unread = PerceptionStimulusLedger.methodsNotReviewed(context, role, page)
            selected += eligible(role, unread).take(limit - selected.size)
            if (page.size < pageSize) break
            after = page.last()
        }
        return selected
    }

    fun reviewContext(events: List<SharedTimelineEvent>): String {
        if (events.isEmpty()) return ""
        return "【亲手试过的办法】对做法本身的体会可写入 JSON 的 toolMethods=[$methodFields]。" +
            " 只写有体会的条目。可以同时觉得有所得也有顾虑，写清什么时候想再用、什么时候换个办法。\n" +
            renderAttempts(events)
    }

    private fun renderAttempts(events: List<SharedTimelineEvent>) = events.joinToString("\n") {
        "- actionEvidenceId=${it.id} at=${it.occurredAt}；${evidenceText(it).take(400)}"
    }

    fun reviewMethods(context: Context, role: String, methods: JSONArray?, offered: List<SharedTimelineEvent>) {
        if (offered.isEmpty()) return
        val revisions = offered.associate { it.id to it.revision }
        val current = eligible(role, SharedExperienceTimeline.eventsByIds(role, revisions.keys))
            .filter { revisions[it.id] == it.revision }
        val ids = current.mapTo(hashSetOf()) { it.id }
        val saved = hashSetOf<String>()
        for (index in 0 until (methods?.length() ?: 0).coerceAtMost(offered.size)) {
            val method = methods?.optJSONObject(index) ?: continue
            val id = method.optString("actionEvidenceId").trim()
            if (id in ids && saved.add(id)) CharacterLearningLedger.recordMethod(role, method, ids)
        }
        current.forEach { PerceptionStimulusLedger.markMethodReviewed(context, it) }
    }

    fun immediateMethods(role: String, requestId: String, tool: String, excluded: Set<String>): List<SharedTimelineEvent> =
        eligible(role, SharedExperienceTimeline.eventsByIds(role,
            listOf("tool-$role-$requestId", "tool-rejected-$role-$requestId")), excluded)
            .filter { receipt(it)?.tool == tool }.takeLast(1)

    /** All dependencies are copied from real, role-owned source revisions. */
    fun prepare(characterId: String, proposal: JSONObject, now: Instant): JSONObject? {
        val action = CharacterActionEvidence.resolve(characterId, proposal.optString("actionEvidenceId")) ?: return null
        val result = receipt(action) ?: return null
        if (action.occurredAt.isAfter(now)) return null
        val cause = if (result.causeId.isBlank()) null
            else CharacterActionEvidence.resolve(characterId, result.causeId) ?: return null
        if (cause != null && (cause.occurredAt.isAfter(action.occurredAt) ||
                result.causeRevision > 0 && result.causeRevision != cause.revision)) return null
        val impression = proposal.optString("methodImpression").trim()
        val situationId = proposal.optString("situationEvidenceId").trim()
        // A personal assessment of a real attempt needs no invented user request.
        // An explicitly cited situation still has to pass every attribution check.
        val situation = if (situationId.isEmpty() && impression.isNotEmpty()) null else
            CharacterActionEvidence.resolve(characterId, situationId) ?: return null
        if (situation != null && (situation.evidenceKind !in setOf(
                EventEvidenceKind.UserStatement, EventEvidenceKind.Observation) ||
                situation.occurredAt.isAfter(action.occurredAt) ||
                cause != null && (situation.occurredAt.isAfter(cause.occurredAt) ||
                    situation.channel != cause.channel ||
                    situation.sessionId.isNotBlank() && cause.sessionId.isNotBlank() &&
                    situation.sessionId != cause.sessionId))) return null
        val topic = proposal.optString("topic").trim()
        val understanding = proposal.optString("understanding").trim()
        val whenToUse = proposal.optString("applicableWhen").trim()
        if (topic.length < 2 || understanding.length < 5 || whenToUse.isBlank()) return null
        val evidence = JSONObject().put(action.id, action.revision)
        situation?.let { evidence.put(it.id, it.revision) }
        cause?.let { evidence.put(it.id, it.revision) }
        if (result.requestEvidenceId.isNotBlank()) {
            val request = CharacterActionEvidence.resolve(characterId, result.requestEvidenceId) ?: return null
            evidence.put(request.id, request.revision)
        }
        if (result.backingEvidenceId.isNotBlank()) {
            if (SharedExperienceTimeline.isDeletedEvent(result.backingEvidenceId) ||
                SharedExperienceTimeline.foreignEvidenceIds(characterId, listOf(result.backingEvidenceId)).isNotEmpty())
                return null
            val backing = CharacterActionEvidence.resolve(characterId, result.backingEvidenceId)
            if (result.tool in groupDeliveryTools &&
                (backing == null || SharedExperienceTimeline.withoutRecalledGroupSpeech(listOf(backing)).isEmpty())) return null
            backing?.let { evidence.put(it.id, it.revision) }
        }
        val feedbackId = proposal.optString("feedbackEvidenceId")
        val quote = proposal.optString("feedbackQuote").trim()
        if (feedbackId.isNotBlank()) {
            val feedback = CharacterActionEvidence.resolve(characterId, feedbackId) ?: return null
            if (feedback.evidenceKind != EventEvidenceKind.UserStatement ||
                !feedback.occurredAt.isAfter(action.occurredAt) || feedback.occurredAt.isAfter(now) ||
                quote.isBlank() || !feedback.content.contains(quote)) return null
            evidence.put(feedback.id, feedback.revision)
        } else if (quote.isNotBlank()) return null
        return JSONObject().put("kind", "experience")
            .put("sourceEvidenceId", action.id).put("sourceRevision", action.revision)
            .put("methodImpression", impression).put("originId", situation?.id ?: cause?.id.orEmpty()).put("evidenceRevisions", evidence)
            .put("topic", topic).put("understanding", understanding)
            .put("applicableWhen", whenToUse).put("avoidWhen", proposal.optString("avoidWhen").trim())
            .put("uncertainty", proposal.optString("uncertainty").trim())
            .put("question", "").put("tool", result.tool).put("executionStatus", result.status)
            .put("feedbackEvidenceId", feedbackId).put("feedbackQuote", quote)
            .put("revisesEvidenceId", proposal.optString("revisesEvidenceId").trim()).put("at", now.toString())
            .put("applications", JSONArray()).put("challenges", JSONArray())
    }

    fun dependenciesValid(characterId: String, item: JSONObject): Boolean {
        val refs = item.optJSONObject("evidenceRevisions") ?: return false
        val ids = refs.keys().asSequence().toList()
        if (ids.isEmpty()) return false
        val live = SharedExperienceTimeline.withoutRecalledGroupSpeech(
            SharedExperienceTimeline.eventsByIds(characterId, ids))
        return live.size == ids.size && live.all { it.revision == refs.optLong(it.id, -1L) }
    }
}
