package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Projections and validation over the existing timeline/learning ledger; no second store. */
internal object CharacterExperienceLearning {
    private val experienceSchema = """{"topic":"稳定主题","situationEvidenceId":"当时需求/观察的真实ID","actionEvidenceId":"当次tool-router回执或已完成的屏幕观察ID","understanding":"这次发现及下次怎样改","applicableWhen":"适用情境","avoidWhen":"不适用/停止条件","uncertainty":"仍未知之处","feedbackEvidenceId":"可选的后来用户原话ID","feedbackQuote":"该原话中的逐字引用","revisesEvidenceId":"确有修订时旧经验ID"}"""
    val reflectionInstruction = """
        数组中的工具经验项使用 kind=ToolExperience，其余字段为 $experienceSchema。回看当时目标、已有信息、真正用了什么、结果与反馈，形成适用和停止条件及下次调整。可从失败学习局限；只有后来用户原话才是用户反馈，不将邻近发言自动当评价。已有同主题经验确被新尝试修订时填 revisesEvidenceId，保留原始来源；单次请求不升级为固定习惯。工具成功不证明对用户有效，用户沉默不证明失败。没有新认识就不重复保存，不需要slot或disposition。
    """.trimIndent()
    val instruction = """
        【主动使用资源与从相处中学习】先判断当前想达成什么、自己还缺什么信息、过去什么做法适用，再选择现有工具；不必等对方点名工具。需要核实的现实信息、时效信息或没有把握的重要建议，应考虑实际检索并核对来源；有充分依据或只是在倾诉时不必为了学习而搜索。工具的能力、权限和当前状态由程序提供，具体用途由你结合现场与经历判断，不按关键词固定派发搜索、音乐、屏幕或弹窗。
        日常相处、失败尝试也能成为学习来源，无须先找一篇网页。若真实旧工具回执与本轮反馈使你发现值得保留的具体经验，可选 experienceLearning=$experienceSchema。只引用提供的原始ID；没有真实执行不声称试过。执行失败可以学到局限；执行成功、沉默或谢谢都不证明对方满意。偏好与习惯仍从她的原话和跨次经历认识，一次请求只保留当次条件，不升级为每次必须做。
        实际复用已列出的阅读理解或日常经验时，可在主行动或每个steps项填写 learningApplication={"sourceEvidenceId":"所用理解/经验ID","action":"本项实际工具名或message/phone_reply","howApplied":"这次怎样使用"}；每项只归因自己的行动，副动作的应用放在对应steps项，不能用发言成功代替另一项工具成功。若本轮用户明确评价了以前的应用，可填 learningFeedback={"sourceEvidenceId":"理解/经验ID","actionEvidenceId":"旧应用ID","evaluation":"helped|unhelpful|mixed","quote":"用户本轮逐字原话","why":"对应哪次做法"}；纠正旧理解可用 learningChallenge={"sourceEvidenceId":"理解/经验ID","issue":"具体争议"}。最新拒绝、情境变化与反例优先考虑，不将旧经验当命令或授权，不为收集好评反复追问。字段都可省略，不新增一次反思调用。
    """.trimIndent()

    data class Receipt(val tool: String, val status: String, val success: Boolean,
        val summary: String, val causeId: String, val reason: String,
        val requestEvidenceId: String = "")

    /** Pending permission, cloud jobs and model claims are not completed attempts. */
    fun receipt(event: SharedTimelineEvent): Receipt? {
        if (event.source == "screen-consent" && event.taskId.isNotBlank() &&
            event.id == "screen-observation-${event.characterId}-${event.taskId}") {
            val requestId = "tool-${event.characterId}-${event.taskId}"
            val original = CharacterActionEvidence.resolve(event.characterId, requestId) ?: return null
            if (original.source != "tool-router" || original.evidenceKind != EventEvidenceKind.ToolResult ||
                original.occurredAt.isAfter(event.occurredAt)) return null
            val request = runCatching { JSONObject(original.content) }.getOrNull() ?: return null
            if (request.optString("tool") != "capture_screen" || request.optString("status") != "pending") return null
            val result = runCatching { JSONObject(event.content) }.getOrNull() ?: return null
            val success = result.optBoolean("success")
            if (success && (result.optString("status") != "observed" ||
                    event.evidenceKind != EventEvidenceKind.Observation) ||
                !success && event.evidenceKind != EventEvidenceKind.ToolResult) return null
            return Receipt("capture_screen", result.optString("status").ifBlank { "failed" }, success,
                result.optString("summary").ifBlank { result.optString("error") },
                request.optString("causeEvidenceId"), request.optString("reason"), requestId)
        }
        if (event.source != "tool-router" || event.evidenceKind != EventEvidenceKind.ToolResult) return null
        val root = runCatching { JSONObject(event.content) }.getOrNull() ?: return null
        val result = root.optJSONObject("result") ?: runCatching { JSONObject(root.optString("result")) }.getOrNull()
            ?: return null
        val tool = root.optString("tool")
        if (CapabilityRegistry.find(tool) == null) return null
        val status = root.optString("status")
        val confirmed = setOf("succeeded", "observed", "notification_posted", "popup_requested",
            "verified", "already_at_target", "unchanged", "capped", "at_safe_limit")
        val unconfirmed = setOf("failed", "denied", "requested_unverified")
        if (status !in confirmed && status !in unconfirmed) return null
        val success = result.optBoolean("success")
        if ((status in confirmed) != success) return null
        return Receipt(tool, status, success,
            result.optString("summary").ifBlank { result.optString("error").ifBlank { result.optString("verification") } },
            root.optString("causeEvidenceId"), root.optString("reason"))
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
        "工具=${it.tool}；执行=${it.status}；原始输入ID=${it.causeId}；当时理由=${it.reason}；实际结果=${it.summary}"
    } ?: event.evidenceContent

    fun recentContext(characterId: String): String {
        val events = SharedExperienceTimeline.recentEvents(characterId, 60)
        val attempts = events.filter { receipt(it) != null }.takeLast(4)
        if (attempts.isEmpty()) return ""
        return buildString {
            appendLine("【最近工具尝试｜供本轮归因和改进，不自动代表有效】")
            attempts.forEach { event ->
                appendLine("- actionEvidenceId=${event.id} at=${event.occurredAt}；${evidenceText(event).take(500)}")
            }
        }.trim()
    }

    /** All dependencies are copied from real, role-owned source revisions. */
    fun prepare(characterId: String, proposal: JSONObject, now: Instant): JSONObject? {
        val action = CharacterActionEvidence.resolve(characterId, proposal.optString("actionEvidenceId")) ?: return null
        val result = receipt(action) ?: return null
        val situation = CharacterActionEvidence.resolve(characterId, proposal.optString("situationEvidenceId")) ?: return null
        if (situation.evidenceKind !in setOf(EventEvidenceKind.UserStatement, EventEvidenceKind.Observation) ||
            situation.occurredAt.isAfter(action.occurredAt) || action.occurredAt.isAfter(now)) return null
        val cause = if (result.causeId.isBlank()) null
            else CharacterActionEvidence.resolve(characterId, result.causeId) ?: return null
        if (cause != null && (cause.occurredAt.isAfter(action.occurredAt) ||
                situation.occurredAt.isAfter(cause.occurredAt) ||
                situation.channel != cause.channel ||
                situation.sessionId.isNotBlank() && cause.sessionId.isNotBlank() && situation.sessionId != cause.sessionId)) return null
        val topic = proposal.optString("topic").trim()
        val understanding = proposal.optString("understanding").trim()
        val whenToUse = proposal.optString("applicableWhen").trim()
        if (topic.length < 2 || understanding.length < 5 || whenToUse.isBlank()) return null
        val evidence = JSONObject().put(action.id, action.revision).put(situation.id, situation.revision)
        cause?.let { evidence.put(it.id, it.revision) }
        if (result.requestEvidenceId.isNotBlank()) {
            val request = CharacterActionEvidence.resolve(characterId, result.requestEvidenceId) ?: return null
            evidence.put(request.id, request.revision)
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
            .put("originId", situation.id).put("evidenceRevisions", evidence)
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
        return ids.all { id -> CharacterActionEvidence.resolve(characterId, id)?.revision == refs.optLong(id, -1L) }
    }
}
