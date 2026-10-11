package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Instant

/**
 * One exceptional relationship episode may leave a private *imprint* immediately.
 *
 * A persistent concern is not a stable personality mutation. Repeated experience
 * remains the exclusive domain of CharacterDevelopmentRuntime/DevelopmentPolicy.
 * This process NEVER sends messages, writes a diary, or declares the other person
 * appeased; the existing independent decision executor remains responsible.
 */
internal object CharacterEmotionalImprintRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /**
     * Older, already recorded disagreements also deserve a chance to be
     * reflected on after installing an update. Never invent or replay a chat:
     * both the feedback and the subsequent reply must be real stored messages.
     */
    fun recoverRecent(context: Context, now: Instant = Instant.now()) {
        MigratedDomainStores.chat.conversations.value
            .asSequence().filter { it.groupChat == null }
            .forEach { conversation ->
                val all = MigratedDomainStores.chat.messages(conversation.id).value
                    .filter { it.status == LuluChatMessage.Status.Sent }
                    .sortedBy { it.createdAt }
                val recentFeedback = all.lastOrNull {
                    it.sender == LuluChatMessage.Sender.User &&
                        !it.createdAt.isAfter(now) &&
                        java.time.Duration.between(it.createdAt, now) <= java.time.Duration.ofHours(72) &&
                        CharacterGrowthTrajectory.feedbackTopic(it.content) != null
                } ?: return@forEach
                val reply = all.lastOrNull {
                    it.sender == LuluChatMessage.Sender.Character &&
                        !it.createdAt.isBefore(recentFeedback.createdAt) &&
                        java.time.Duration.between(recentFeedback.createdAt, it.createdAt) <= java.time.Duration.ofMinutes(90)
                } ?: return@forEach
                val role = reply.authorCharacterId?.takeIf(String::isNotBlank)
                    ?: conversation.characterId
                request(context, role, reply.id, listOf(recentFeedback.id))
            }
    }

    fun request(context: Context, characterId: String, replyEventId: String, userEventIds: List<String>) {
        if (characterId.isBlank() || replyEventId.isBlank() || userEventIds.isEmpty()) return
        val app = context.applicationContext
        scope.launch {
            mutex.withLock {
                reflect(app, characterId, replyEventId, userEventIds.distinct().takeLast(12))
            }
        }
    }

    private suspend fun reflect(context: Context, characterId: String, replyEventId: String,
                                userEventIds: List<String>) {
        val prefs = context.getSharedPreferences("lulu_emotional_imprint_v1", Context.MODE_PRIVATE)
        val key = "$characterId:$replyEventId"
        if (prefs.getBoolean(key, false)) return
        val spoken = SharedExperienceTimeline.eventsByIds(characterId, listOf(replyEventId))
            .singleOrNull()?.takeIf { it.evidenceKind == EventEvidenceKind.CharacterStatement }
            ?: return
        val heard = SharedExperienceTimeline.eventsByIds(characterId, userEventIds)
            .filter { it.evidenceKind == EventEvidenceKind.UserStatement && !it.occurredAt.isAfter(spoken.occurredAt) }
            .sortedBy { it.occurredAt }
        if (heard.isEmpty()) return
        // The user-reported concern is the controlling evidence. Deleting that
        // message must retract any psychological interpretation derived from it.
        val feedbackEvidenceId = heard.last().id
        val originalPersona = CharacterRuntime.personaConstraintSnapshot(characterId)
        val facts = buildString {
            appendLine("真实已发送的角色回复，证据ID=${spoken.id}：${spoken.content.take(450)}")
            heard.takeLast(8).forEach {
                appendLine("之前用户真正说过，证据ID=${it.id}：${it.content.take(260)}")
            }
            appendLine("角色已有的心理变化和旧心事（主观，不是用户事实）：")
            appendLine(CharacterInnerLifeStore.compactContext(characterId).take(1_500))
        }
        val result = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = facts,
            instruction = """
                这是一次实际发生过的关系冲突及其回应。你已经说过上面的回复，
                现在独自思考它到底是否真的触动了你，而不是继续扮演服务用户的助手。
                注意：用户被安抚好了吗、是否原谅了你，目前都不能凭你的台词认定。
                依据稳定人设与真正的相处关系，允许出现不安、松口气、自省、嘴硬、
                不认同对方、仍想弄清楚什么，也允许根本没有特别的长期影响。
                单次明显的关系经历足以成为「未想通的私人挂心」，但不是长期性格变化，
                不是已经兑现的誓言，不等于必须道歉、写日记或给用户发消息。
                若确实有自己的挂心，使用 appraisal.pendingConcern，status=open，
                写明 focus、whyItMatters，可有 hesitation、possibleNextStep。
                如果已有相关心事，使用 threadId 且 status=revise 来推进，不要新建重复事项。
                如只是短时感受，可以返回 innerLife.emotion；
                真正想长期做的一件事可选 innerLife.motives（要有目标和实际缘由），
                但不要保证已经执行。没有变化时输出 {}。
                若有需要写在“此刻”的私人内心话，可选 innerThought，不是向用户说出口。
                如果只是想写日记，不是直接写过；此处只能形成愿望，
                后续由原有主动行动系统根据角色自己决定是否真的写。
                仅返回 JSON 对象：{
                  "appraisal":{"meaning":"本次经历对自己的意义",
                    "pendingConcern":{"status":"open|revise","threadId":"仅修订旧心事时填写",
                    "focus":"这件事让自己放在心上的问题","whyItMatters":"缘由",
                    "hesitation":"犹豫","possibleNextStep":"可选想做的事"}},
                  "innerLife":{"emotion":{"feeling":"感受","cause":"确实发生的依据",
                    "strength":1,"halfLifeMinutes":360},
                    "motives":[{"op":"start","aim":"可选想做的事","why":"缘由"}]},
                  "innerThought":"可选，不能假装已说出口"
                }
                没有必要的字段全部省略。不要为填字段制造感情或行动。
            """.trimIndent(),
            source = "关系冲突后的一次真实经历回望",
            title = "角色私人印记",
            maxTokens = 700,
            contextMode = CompanionContextMode.Full,
        ).getOrNull() ?: return
        val parsed = ModelStructuredOutput.objectOrNull(result.text) ?: return
        // A deleted/corrected reply or a changed stable persona invalidates the
        // reflection before any derived psychological state is committed.
        if (CharacterRuntime.personaConstraintSnapshot(characterId) != originalPersona ||
            SharedExperienceTimeline.eventsByIds(characterId, listOf(replyEventId))
                .singleOrNull()?.revision != spoken.revision ||
            heard.any { event ->
                SharedExperienceTimeline.eventsByIds(characterId, listOf(event.id))
                    .singleOrNull()?.revision != event.revision
            }) return
        val appraisal = parsed.optJSONObject("appraisal")
        val inner = parsed.optJSONObject("innerLife")
        val thought = parsed.optString("innerThought").trim().take(400)
        // A model-authored, grounded interpretation is itself a private
        // reflection even when it did not create an unresolved concern.
        // Distinguish it in the trace from a stable trait or real action.
        val changed = appraisal?.optString("meaning").orEmpty().isNotBlank() ||
            appraisal?.optJSONObject("pendingConcern") != null ||
            inner != null || thought.isNotBlank()
        val now = Instant.now()
        if (changed) {
            if (inner != null) CharacterInnerLifeStore.observe(
                characterId, feedbackEvidenceId, "真实反馈及随后回应：${spoken.content.take(150)}",
                inner, now = now,
            )
            CharacterInnerLifeStore.recordCausalTransition(
                characterId = characterId, evidenceId = feedbackEvidenceId,
                appraisal = appraisal, innerLife = inner, innerThoughtBasis = null,
                selectedAction = "emotional_imprint",
                innerThought = thought,
                reason = appraisal?.optString("meaning").orEmpty().ifBlank { "重新理解自己的刚才那次回应" },
                now = now,
            )
            if (thought.isNotBlank()) CharacterInnerLifeStore.recordInnerVoice(characterId, feedbackEvidenceId, thought, now)
        }
        prefs.edit().putBoolean(key, true).commit()
    }
}
