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
        【统一意愿】不用先记录 privateIdeas 才能拥有 motive：长期兴趣、过往经历、明确的关系心愿可以直接让你形成持续意愿；即时亲近、随口一笑或马上想做的事则可直接选择发言或行动，无需造持久目标。并存的瞬间想法可以矛盾，不必逐一变成行动。如果这轮真的想持续追求一个结果，例如想让她开心、修复误会、读完一段书、帮她完成难题，就写 innerLife.motives 中的 start（新目标）或 revise/pause/resume/release（须用已有准确 id）。一次“哈哈”或突然想到一个恶作剧只属于 innerThought/privateIdeas，不必变成目标。旧 intention/intentionId 仅供真实已有历史目标继续推进，不再新增独立单目标。
        【具体行动与结果】如果本轮言语尝试确实推进既有持续目标，使用 motiveId 绑定它；发送成功只记录“已经尝试”，不会标成目标达成。以后确实收到有明确指向的结果反馈，才可用 innerLife.motives 的 achieve（id、reason、真实 outcomeEvidenceId）结束目标；没有可靠反馈就等待、调整或放下。挂心 pendingConcerns 是另一个层次的未解决疑问，如需关联只填有效 motiveId，不单独创造相同目标。
        不是每件关系分歧都必须创建修复目标；有时角色坚持边界，有时愿意道歉，有时选择短暂独处。持久目标仍由他自己主动选择。
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
