package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Chat and phone use the same curiosity/sharing loop as autonomous life.
 * Planning is private state. Only the sender or completed audio playback acknowledges expression.
 */
internal object CharacterConversationAgency {
    val instruction = """
        【聊天与电话中的自己的注意和表达欲】
        不只判断该怎么答：你是否真对她刚说的某个细节感到好奇，是否有自己真实经历想让她知道？
        先理解她这次想交流的重点，再依自己的兴趣、感受和关系选择接话、分享、逗弄、表达不同意见或局部询问。好奇是想了解一个具体细节，不是每轮结尾追加一个问题；分享是自己有内容想说，不是逐条向她汇报生活。
        能回应后顺着共同话题带出自己的发现；她在认真倾诉或纠正误解时先接住，不抢话题。想知道的事可以暂存，下轮随她实际回答推进或放下，不反复问已经答过的事。
        如果产生了值得延续的好奇，可填 curiosity={"topic":"具体主题","question":"自己真正想知道的事","why":"这次实际输入怎样触动自己","nextStep":"以后仍感兴趣时可以怎样继续","status":"exploring|dropped"}。只是问出口还不能写 satisfied，更不能声称已经知道答案。
        如确实在分享下方一件真实经历，填写 experienceId=准确ID；暂时想分享但未说可用 appraisal.shareWish={"experienceId":"准确ID","status":"held|released","why":"自己的理由"}。只在实际文字送达或语音播放完成后才算表达过，生成草稿不算；表达过也不证明对方喜欢、接受或被感动。
        如果你正自然地把已读材料的一个理解应用在这次回复，可选 learningApplication={"sourceEvidenceId":"确实存在的阅读/外部材料证据ID","howApplied":"这次怎样用在实际发出的文字/口语里"}。只有完成文字送达/语音播放，才能记为实践；不是验证成功，也不需要每轮填。
        若本次用户原话清楚评价了你**此前已经执行**的一次学习应用（如喜欢、不喜欢、有效、没用），可选 learningFeedback={"sourceEvidenceId":"阅读证据ID","actionEvidenceId":"以前实践行动的证据ID","evaluation":"helped|unhelpful|mixed","why":"她原话针对那次做法的理由"}。仅依据本轮亲耳听到的用户原话，且必须有那次真实行动ID；没有明确相关评价就不填，不要把闲聊或感谢自动变成学习效果证明。
        如果用户这次明确纠正你对某份已读材料的理解，可选 learningChallenge={"sourceEvidenceId":"那次阅读的证据ID","issue":"对方原话让原理解哪一点存疑"}。必须是用户本人这一轮实际说的反例，不是你凭空想到的不同意见；记录争议后要审慎核对，既不能装作自己从未有过旧想法，也不能自动判定用户或书中说法绝对正确。没有精确阅读证据ID就省略。
        如果这次说出口的话确实是在推进上下文里的某条持续愿望，可填 intentionId=那条愿望的准确ID；只在文字送达或整段语音实际播放后记录尝试，没填就不自动关联。愿望较抽象时自己选择具体下一步，可用 intention.nextStep 更新计划，不能把用户回复视为完成长期愿望。
        有时一次对话留下了真正重要但未解决的个人目标，例如自己说错话后想把误解解释清楚、想挽回关系、仍希望对方理解自己的担忧。若确实产生这种具体且想继续承担的愿望，可以写 intention={"aim":"自己真正在乎的结果","motive":"来自实际经历的个人原因","nextStep":"愿意尝试的具体行动或观察"}。这会保留目标并提供一次短时复盘机会，不要求立刻再发消息。不是吵架就一定道歉、一定追着哄；也可能坚持自己的边界，或尊重对方想独处的选择。
        重要：发出道歉只意味着道歉已送达；和好、被原谅与否，要依据对方真实反馈，不能自行宣布成功。五分钟复盘可以选择不再说话、改做实际生活中的事、改变表达方法或暂停，不得按计时器重复“对不起”。
        情绪推动这次表达时可以给 motiveId=已有真实动机ID 或 concernId=已有心事ID，只有这次交流确实与它有关才填。表达是一次尝试，后续根据用户的真实回应调整；不靠发出一句话就自动宣布长期矛盾已解决。
    """.trimIndent()

    fun rememberInquiry(context: Context, characterId: String, proposal: JSONObject?, evidenceId: String, now: Instant) {
        if (proposal == null || evidenceId.isBlank()) return
        val event = SharedExperienceTimeline.eventsByIds(characterId, listOf(evidenceId)).firstOrNull()
            ?.takeIf { it.evidenceKind in setOf(EventEvidenceKind.UserStatement, EventEvidenceKind.Observation) }
            ?: return
        CharacterCuriosityRuntime.initialize(context)
        if (proposal.optString("status") == "dropped") CharacterCuriosityRuntime.releaseInterest(characterId, proposal, now)
        else CharacterCuriosityRuntime.recordInquiry(characterId, proposal, event.id, event.content, now)
    }

    fun receipt(evidenceId: String, reason: String, motiveId: String, concernId: String,
                curiosity: JSONObject?, experienceId: String,
                learningApplication: JSONObject? = null, intentionId: String = ""): String = JSONObject()
        .put("id", "conversation-choice-${UUID.randomUUID()}").put("evidenceId", evidenceId)
        .put("reason", reason.take(240)).put("motiveId", motiveId.take(80))
        .put("concernId", concernId.take(100)).put("curiosity", curiosity)
        .put("experienceId", experienceId.take(180))
        .put("learningApplication", learningApplication).put("intentionId", intentionId.take(80)).toString()

    /** A complete sender receipt, never a model-generated claim of having spoken. */
    @Synchronized fun delivered(characterId: String, raw: String?, messageId: String, text: String, phone: Boolean = false) {
        val metadata = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        if (text.isBlank() || messageId.isBlank()) return
        val id = metadata.optString("id")
        if (id.isBlank()) return
        val source = metadata.optString("evidenceId")
        // A deleted original input cannot resurrect derived motives/curiosity through a delayed TTS callback.
        if (source.isNotBlank() && SharedExperienceTimeline.eventsByIds(characterId, listOf(source)).isEmpty()) return
        val prior = CharacterInnerLifeStore.snapshot(characterId).optJSONArray("decisions")
        if ((0 until (prior?.length() ?: 0)).any { prior?.optJSONObject(it)?.optString("id") == id }) return
        // Group history stores one role-specific evidence row per participant.
        // Use the event's actual ID for every derived state so removing a group
        // message removes its psychological effects too.
        val action = if (phone) "phone_reply" else "message"
        val deliveredEvidenceId = CharacterActionEvidence.resolve(characterId, messageId)
            ?.takeIf { CharacterActionEvidence.supports(it, action) }?.id ?: return
        val summary = if (phone) "这段口语已实际播放完毕：${text.take(180)}" else "文字已实际送达：${text.take(180)}"
        val intentionId = metadata.optString("intentionId")
        if (intentionId.isNotBlank() && metadata.optString("reason").isNotBlank() &&
            CharacterLifeStore.markExecuting(characterId, intentionId, deliveredEvidenceId, action)) {
            CharacterLifeStore.recordLinkedOutcome(characterId, intentionId, deliveredEvidenceId, action, true, summary)
        }
        CharacterInnerLifeStore.recordDecision(characterId, id, action, metadata.optString("reason"),
            metadata.optString("motiveId"), null, summary, true, actionSignature = deliveredEvidenceId, causalEvidenceId = source)
        CharacterInnerLifeStore.recordActionResult(characterId, metadata.optString("motiveId"), deliveredEvidenceId, action, true, summary)
        CharacterInnerLifeStore.recordConcernOutcome(characterId, metadata.optString("concernId"), deliveredEvidenceId, action, true, summary)
        CharacterLifeFollowThroughStore.recordAction(characterId, metadata.optString("experienceId"),
            "message", true, summary, deliveredEvidenceId)
        CharacterLearningLedger.recordApplication(
            characterId, metadata.optJSONObject("learningApplication"), deliveredEvidenceId,
            action, true, summary,
        )
        val inquiry = metadata.optJSONObject("curiosity")?.takeUnless { it.optString("status") == "dropped" }
            ?.let { JSONObject(it.toString()).put("status", "exploring").put("sourceEvidenceId", source) }
        CharacterCuriosityRuntime.recordOutcome(characterId, inquiry, action, true, summary, deliveredEvidenceId)
    }
}
