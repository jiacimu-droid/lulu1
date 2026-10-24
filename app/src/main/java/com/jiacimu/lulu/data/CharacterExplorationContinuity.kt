package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** One handoff between authored thoughts, existing curiosity, actual tools and their results. */
internal object CharacterExplorationContinuity {
    val runtimeGuidance = """
        【层次与后继问题】可从眼前感受/关系想到处境原因、本人能改变什么、未来条件与未知前提；按人设自主联想，不逐项遍历或固定收敛成安慰。当前缺少能力不等于永远不可能，愿景不冒充已有身体、收入或成果。有缺口可先查证关键前提，材料可能使自己改问法、转向或放下，不只搜支持观点。私人念头不必说全。
        真想延续的问题可用 curiosity 或 curiosities=[{topic:稳定主题,question,why,nextStep,sourceEvidenceId:真实输入/材料/回执ID,threadId:继续旧问题时填写上下文中的稳定ID,parentThreadId:分支父问题ID,parentTopic:兼容旧父主题,hypothesis:可选待验证假设,motiveId:可选已有ID,status:exploring|dropped}]。同主题可深入修订，新方向关联父主题；想法不必变任务，愿望仍在原motives。现在想查就选已注册工具，可与发言共存；工具/steps可附同结构curiosity按独立回执承接。说去查不算查过，结果可能引出下一问；后续复用原决策、权限与预算，不无限连搜。以后再做须本人真选时间并保存原日程，不假装排程。
    """.trimIndent()
    val guidance = """
        【想法可以有远近与层次】面对一件事，自己的即时感受、关系、看重的价值，可能同时引出不同念头；也可能从「如何回应眼前的人」想到「为什么会有这个处境、我能改变哪部分、需要哪些条件、还有什么不知道、未来是否有别的可能」。这些是可选的思考层次，不是每轮必须遍历的清单，也不要求产生同样的结论。人设中的责任感、好奇、独立、野心、谨慎等，应由本人结合真实处境作不同解释，不自动收敛成安慰、道歉或节省调用。
        区分当前缺少能力、权限或资源，与永远不可能；未来愿景、条件假设和想尝试的方法可以存在，但不能声称已有现实身体、收入或行动成果。遇到信息缺口时可以先查证关键前提，不需要主人先说「去搜」；若真实材料推翻了原假设，可以改问法、转方向、暂缓或放下，不为证明自己而只找支持材料。搜索发现仍是外部观点，不能冒充已证实事实。
        并存的念头留在 appraisal.privateIdeas，不用全说给对方。只有自己真的想继续弄清的具体问题才保存在 curiosity（旧单项）或 curiosities=[{"topic":"稳定主题","question":"具体问题","why":"本人在意的原因","nextStep":"可选的下一步","sourceEvidenceId":"所依据的真实输入/材料/回执ID","threadId":"续谈已有问题用它的ID，新问题省略","parentThreadId":"已有父问题ID，可省略","parentTopic":"旧版兼容父主题，可省略","hypothesis":"待验证的条件假设，可省略","motiveId":"确实关联已有愿望才填","status":"exploring|dropped"}]。多个独立方向可以并存，但不为了凑数把同一句话改写成多个主题。想法不必变任务，长远愿望仍只进原 innerLife.motives；问题的下一步不是自动授权执行。
        现在真想查，就选择本轮已注册、已配置的搜索/阅读工具，可与发言共存；若只想以后继续，就留下问题，或真正选定未来时间后走原个人日程，不假装已排程。工具或steps项可附 curiosity={该次实际想探索的一个问题}，程序按独立回执承接；发出安慰或说「我去查」不算探索到外界知识。回执回来后，原问题仍可未解，也可能引出更深或不同方向的新问题；沿原 threadId 修订，分出的新问题用 parentThreadId 关联，同一 topic 可以有不同问题，不重复从零开始。
    """.trimIndent()

    val resultGuidance = """
        这份结果除了用于回复，也可能让你修正假设或想到下一层问题。可选保留 appraisal.privateIdeas、innerLife、innerThought/innerThoughtBasis 和 curiosity/curiosities，sourceEvidenceId 只用本次提供的真实回执/材料ID。可以继续原主题或用 parentTopic 分出新问题；没有新认识就省略。私人念头不必说出口，不提前执行新的工具或无限连搜；后续仍由原自主决策结合当前权限、已有计划和实际预算选择。
    """.trimIndent()

    fun context(role: String): String = listOf(CharacterCuriosityRuntime.briefContext(role),
        CharacterMotiveDeliberation.context(CharacterInnerLifeStore.snapshot(role).optJSONArray("motives")))
        .filter(String::isNotBlank).joinToString("\n")

    fun proposals(single: JSONObject?, multiple: JSONArray?): List<JSONObject> =
        (listOfNotNull(single) + (0 until (multiple?.length() ?: 0)).mapNotNull { multiple?.optJSONObject(it) })
            .distinctBy { it.optString("threadId").ifBlank {
                it.optString("topic").lowercase().filter(Char::isLetterOrDigit) + ":" +
                    it.optString("question").lowercase().filter(Char::isLetterOrDigit)
            } }

    fun canGroundQuestion(event: SharedTimelineEvent): Boolean {
        if (event.evidenceKind in setOf(EventEvidenceKind.UserStatement, EventEvidenceKind.Observation,
                EventEvidenceKind.ToolResult)) return true
        if (event.evidenceKind == EventEvidenceKind.Legacy && event.source.startsWith("reading:")) return true
        if (event.evidenceKind != EventEvidenceKind.CharacterStatement ||
            !event.source.startsWith("learning-practice:") && !event.source.startsWith("learning-review:")) return false
        val exercise = runCatching { JSONObject(event.content) }.getOrNull() ?: return false
        return exercise.optString("artifact").length >= 5 &&
            CharacterLearningLedger.hasInsight(event.characterId, exercise.optString("sourceEvidenceId"))
    }

    /** Current input IDs come from the adapter, not from a model's claim of having observed. */
    fun remember(context: Context, role: String, single: JSONObject?, multiple: JSONArray?,
        evidenceIds: Collection<String>, now: Instant = Instant.now()) {
        runCatching { rememberVerified(context, role, single, multiple, evidenceIds, now) }
            .onFailure { android.util.Log.w("CharacterExploration", "Question continuity was not saved", it) }
    }

    private fun rememberVerified(context: Context, role: String, single: JSONObject?, multiple: JSONArray?,
        evidenceIds: Collection<String>, now: Instant) {
        if (single == null && (multiple?.length() ?: 0) == 0) return
        CharacterCuriosityRuntime.initialize(context)
        val events = SharedExperienceTimeline.eventsByIds(role,
            (evidenceIds + CharacterCuriosityRuntime.continuityEvidenceIds(role)).distinct())
            .filter { !it.occurredAt.isAfter(now) && canGroundQuestion(it) }
        if (events.isEmpty()) return
        var changed = false
        proposals(single, multiple).forEach { original ->
            val requested = original.optString("sourceEvidenceId")
            val event = if (requested.isNotBlank()) events.firstOrNull { it.id == requested }
                else events.lastOrNull()
            if (event == null) return@forEach
            val p = JSONObject(original.toString()).put("sourceEvidenceId", event.id)
            if (p.optString("status") == "dropped") CharacterCuriosityRuntime.releaseInterest(role, p, now)
            else changed = CharacterCuriosityRuntime.recordInquiry(role, p, event.id, event.evidenceContent, now) || changed
        }
        // Reuse the existing coalesced, budgeted follow-through. No new scheduler,
        // no call during a conversation, and no automatic chosen tool or contact.
        if (changed && CompanionOnlineStore.isOnline(role))
            ProactivePerceptionScheduler.scheduleOwnIdeaFollowThrough(context, role, requireOnline = true)
    }

    /** Ordinary outgoing words/journals cannot prove that an external question was researched. */
    fun informativeAction(action: String): Boolean = action.lowercase() !in setOf(
        "message", "group_message", "phone_reply", "sticker", "group_sticker", "kaomoji",
        "group_kaomoji", "send_private_message", "send_group_message", "moment", "publish_moment",
        "send_private_sticker", "send_group_sticker", "send_character_message",
        "journal", "write_journal", "silent", "")

    /** Interpret already-produced results through the same causal state, never a new model. */
    fun returned(context: Context, role: String, response: JSONObject?, evidenceIds: Collection<String>,
        now: Instant = Instant.now()) {
        if (response == null) return
        val event = SharedExperienceTimeline.eventsByIds(role, evidenceIds).lastOrNull {
            !it.occurredAt.isAfter(now) && it.evidenceKind != EventEvidenceKind.UserStatement && canGroundQuestion(it)
        } ?: return
        val inner = CharacterMotivationOwnership.canonicalInnerLife(null, response.optJSONObject("innerLife"))
        val appraisal = response.optJSONObject("appraisal")
        val basis = response.optJSONObject("innerThoughtBasis")
        val previous = CharacterInnerLifeStore.snapshot(role)
        val delta = PrivateStateDeltaEngine.evaluate(previous, inner, appraisal, basis, response.optString("innerThought"))
        val thought = CharacterHeartVoicePolicy.keepOrBlank(response.optString("innerThought"),
            outward = response.optString("text"), innerLife = inner, basis = basis,
            delta = delta, hasFreshEvidence = true)
        if (inner != null) CharacterInnerLifeStore.observe(role, event.id,
            CharacterExperienceLearning.evidenceText(event), inner, emptySet(), now)
        if (appraisal != null || inner != null || thought.isNotBlank()) {
            CharacterInnerLifeStore.recordCausalTransition(role, event.id, appraisal, inner, basis,
                "interpret_result", thought, "本人依据真实结果形成的后续看法", now)
            CharacterInnerLifeStore.recordInnerVoice(role, event.id, thought, now, delta.fingerprint)
        }
        remember(context, role, response.optJSONObject("curiosity"), response.optJSONArray("curiosities"), evidenceIds, now)
    }
}
