package com.jiacimu.lulu

import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.data.CharacterMessageFavorites
import com.jiacimu.lulu.data.CompanionOnlineStore
import com.jiacimu.lulu.data.DigitalLifeProfileStore
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.LuluChatStore
import com.jiacimu.lulu.data.MeetingSegmentType
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.EventEvidenceKind
import com.jiacimu.lulu.data.LuluGroupChat
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.UserProfileContext
import com.jiacimu.lulu.system.LuluDeviceToolBridge
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.random.Random

internal const val SemanticBubbleSeparator = "⟪BUBBLE⟫"

/** A roster name, a one-sided public message and a reciprocal acquaintance are different facts.
 * This is only a context projection; the speaking character still chooses the response. */
internal fun groupPeerEvidenceLine(
    name: String,
    relation: com.jiacimu.lulu.data.CharacterSocialRelationshipSnapshot,
    witnessedGroupSpeech: Boolean,
    explicitAgreement: String = "",
): String {
    val relationship = when {
        explicitAgreement.isNotBlank() -> "本人的明确关系约定：${explicitAgreement.take(150)}"
        relation.knowsEachOther -> "已有真实交集：${relation.shortContext()}；认识不自动等于熟悉或亲密"
        witnessedGroupSpeech -> "只曾在本群亲眼看到对方发言，尚无双方确认的交往"
        else -> "只在本群成员名单看到姓名，尚无可核实的双方交往"
    }
    return "- $name：$relationship"
}

/** Project only this character's witnessed group speech and own relationship records.
 * Do not supply a peer's private profile, presence, other chats or global relationship graph. */
internal fun groupPeerEvidenceContext(
    viewerId: String,
    members: List<com.jiacimu.lulu.data.LuluGroupMember>,
    visibleMessages: List<LuluChatMessage>,
    characterNames: Map<String, String>,
): String = buildString {
    appendLine("【我与其他群成员真实认识到哪一步】")
    members.asSequence().filter { it.characterId != viewerId }.take(12).forEach { member ->
        val peerId = member.characterId
        val label = member.groupNickname.ifBlank { characterNames[peerId] ?: peerId }
        val relationship = com.jiacimu.lulu.data.CharacterSocialRelationship.snapshot(viewerId, peerId)
        val seenSpeech = visibleMessages.any { message ->
            message.sender == LuluChatMessage.Sender.Character &&
                message.authorCharacterId == peerId &&
                message.status == LuluChatMessage.Status.Sent &&
                SharedExperienceTimeline.eventsByIds(viewerId,
                    listOf("${message.id}:group:$viewerId")).isNotEmpty()
        }
        val explicit = com.jiacimu.lulu.data.CharacterSocialRelationship
            .agreement(viewerId, peerId)?.let { agreement ->
                listOf(agreement.title, agreement.details).filter(String::isNotBlank).joinToString("；")
            }.orEmpty()
        appendLine(groupPeerEvidenceLine(label, relationship, seenSpeech, explicit))
        // An old or hearsay opinion is not a shared factual history.
        val impression = com.jiacimu.lulu.data.CharacterSocialRelationship.perspective(viewerId, peerId)
        if (impression != null && impression.evidenceId.isNotBlank() &&
            SharedExperienceTimeline.eventsByIds(viewerId, listOf(impression.evidenceId)).isNotEmpty()) {
            appendLine("  本人已有单方印象：${impression.impression}；" +
                com.jiacimu.lulu.data.CharacterSocialRelationship
                    .evidenceBoundary(viewerId, impression.evidenceId) +
                "；不代表对方认识我，也不能当成证实的私人习惯")
        }
    }
    append("群友在名单里、在线或共享同一话题，并不自动说明彼此相识或熟悉。" +
        "可以凭当下群消息自然聊天、打趣或猜测，但猜测要表明不确定；" +
        "不要凭名字推定对方作息、性格、关系或当前私下行为，也不要调用其他角色的私人记忆。")
}

/**
 * Legacy transport tokens are tolerated only at the parser boundary. New model prompts no longer
 * expose them. Any malformed spelling that still appears is normalized/removed before persistence,
 * so internal protocol strings can never become a visible chat message.
 */
private val LegacyBubbleTokenRegex = Regex(
    """(?:⟪|《|〈|<|【|\[)\s*B\s*U\s*B\s*B\s*L\s*E\s*(?:⟫|》|〉|>|】|\])""",
    RegexOption.IGNORE_CASE,
)
private val MalformedInternalDirectiveRegex = Regex(
    """(?:⟪|《|〈|<|【|\[)\s*(?:NEXT|END|QUOTE|FAVORITE|RECALL|POKE_USER)(?:\s*:[^⟫》〉>】\]]*)?\s*(?:⟫|》|〉|>|】|\])""",
    RegexOption.IGNORE_CASE,
)
private val QuoteDirectiveRegex = Regex("⟪QUOTE\\s*:\\s*([^⟫]+)⟫", RegexOption.IGNORE_CASE)
private val FavoriteDirectiveRegex = Regex("⟪FAVORITE\\s*:\\s*([^⟫]+)⟫", RegexOption.IGNORE_CASE)
private val RecallDirectiveRegex = Regex("⟪RECALL\\s*:\\s*(\\d+)⟫", RegexOption.IGNORE_CASE)
private val PokeUserDirectiveRegex = Regex("⟪POKE_USER⟫", RegexOption.IGNORE_CASE)

internal fun characterReplyQuoteId(text: String): String? = QuoteDirectiveRegex.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
internal fun characterFavoriteMessageId(text: String): String? = FavoriteDirectiveRegex.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
internal fun characterRecallBubbleNumber(text: String): Int? = RecallDirectiveRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
internal fun characterPokesUser(text: String): Boolean = PokeUserDirectiveRegex.containsMatchIn(text)

internal fun stripCharacterReplyDirective(text: String): String = stripQqForwardDirective(
    text.replace(QuoteDirectiveRegex, "")
        .replace(FavoriteDirectiveRegex, "")
        .replace(RecallDirectiveRegex, "")
        .replace(PokeUserDirectiveRegex, ""),
).trim()

private fun normalizeLegacyBubbleTokens(text: String): String =
    text.replace("\r\n", "\n")
        .replace(LegacyBubbleTokenRegex, SemanticBubbleSeparator)

internal fun sanitizePersistedChatText(text: String): String =
    com.jiacimu.lulu.data.ModelStructuredOutput.smoothInlineBreaks(
        normalizeLegacyBubbleTokens(text)
            .replace(SemanticBubbleSeparator, "")
            .replace(MalformedInternalDirectiveRegex, "")
            .replace(Regex("[ \\t]+"), " "),
    )

internal fun semanticReplyBubbles(text: String): List<String> {
    val normalized = normalizeLegacyBubbleTokens(text)
    // Each actual paragraph is a completed conversational beat. Preserve a
    // lone newline inside an author's sentence, but do not pack two separate
    // paragraphs into one giant bubble with visible blank lines.
    // Media payloads are atomic and must never be split into partial images.
    val separated = if (normalized.trimStart().startsWith("[") ||
        normalized.contains("⟪QQ_IMAGE")) normalized else
        normalized.replace(Regex("""\n[ \t]*\n+"""), SemanticBubbleSeparator)
    val candidates = separated.split(SemanticBubbleSeparator)
        .map { sanitizePersistedChatText(it).trim('"') }.filter(String::isNotBlank)
    return com.jiacimu.lulu.data.ModelStructuredOutput.readableReplyBubbles(
        candidates,
        // The historic 8-bubble overflow rule fused EVERY remaining paragraph
        // into one huge unsplittable bubble. The actual model output already
        // bounds the total content; do not concatenate unrelated speaking acts.
        maxBubbles = Int.MAX_VALUE,
    )
}

/**
 * A structured model turn already chose its exact IM message boundaries.
 * Re-answer must preserve these beats just like normal delivery, including a
 * single long bubble. Legacy freeform text can still use semanticReplyBubbles.
 */
internal fun modelAuthoredReplyBubbles(text: String): List<String> =
    normalizeLegacyBubbleTokens(stripCharacterReplyDirective(text))
        .split(SemanticBubbleSeparator)
        .map(::sanitizePersistedChatText)
        .filter(String::isNotBlank)

internal fun normalizeSemanticBubbles(text: String): String {
    val raw = normalizeLegacyBubbleTokens(text).trim()
    if (raw.isBlank()) return ""
    val directives = listOf(QuoteDirectiveRegex, FavoriteDirectiveRegex, RecallDirectiveRegex, PokeUserDirectiveRegex)
        .joinToString("") { it.find(raw)?.value.orEmpty() }
    // Preserve the explicit boundary marker until the sender creates each
    // persisted bubble. A newline is layout within a bubble, NOT a send event.
    val body = semanticReplyBubbles(stripCharacterReplyDirective(raw))
        .joinToString(SemanticBubbleSeparator)
    return if (body.isBlank()) "" else directives + body
}

internal data class CharacterReplyPresentation(
    val content: String,
    val quoteMessageId: String? = null,
    val favoriteMessageId: String? = null,
    val recallBubbleNumber: Int? = null,
    val pokeUser: Boolean = false,
    val voiceBubbleTexts: List<String> = emptyList(),
    /** Exactly one modality choice per model-authored bubble, including all-voice turns. */
    val bubbleDeliveryModes: List<String> = emptyList(),
    /** Same order as bubbles; distinct from the public text and memory. */
    val bubbleSpeechTracks: List<String> = emptyList(),
    /** Validated structured bubbles have already been deliberately paced by the model. */
    val preserveAuthoredBubbleBoundaries: Boolean = false,
)

internal fun parseCharacterReplyPresentation(
    text: String,
    preserveAuthoredBubbleBoundaries: Boolean = false,
): CharacterReplyPresentation = CharacterReplyPresentation(
    content = if (preserveAuthoredBubbleBoundaries)
        stripCharacterReplyDirective(normalizeLegacyBubbleTokens(text)).trim()
    else stripCharacterReplyDirective(normalizeSemanticBubbles(text)),
    quoteMessageId = characterReplyQuoteId(text),
    favoriteMessageId = characterFavoriteMessageId(text),
    recallBubbleNumber = characterRecallBubbleNumber(text),
    pokeUser = characterPokesUser(text),
    preserveAuthoredBubbleBoundaries = preserveAuthoredBubbleBoundaries,
)

internal fun currentReplyTargetUserMessages(messages: List<LuluChatMessage>): List<LuluChatMessage> {
    val lastCharacterIndex = messages.indexOfLast { it.sender == LuluChatMessage.Sender.Character }
    return messages.drop(lastCharacterIndex + 1).filter { it.sender == LuluChatMessage.Sender.User }
}

/** A model reply is bound to the actual user messages and their timeline revisions.
 * This is a read-only watermark, not a second conversation state store.
 * A correction, deletion, newer user turn, or membership change invalidates
 * the OLD generated reply without cancelling the newer turn's own work. */
internal data class ReplySourceWatermark(
    val conversationId: String,
    val characterId: String,
    val sourceRevisions: Map<String, Long>,
    val sourceContents: Map<String, String>,
    val latestUserMessageId: String?,
    // Other members joining/leaving or a rename changes who hears the reply.
    // A response generated for yesterday's room must not be sent into today's.
    val visibleGroupScene: GroupReplyScene?,
)

/** Only the fields that affect who is present and how this shared room is presented.
 * Cosmetic pin/avatar settings do not cancel an in-flight reply. */
internal data class GroupReplyScene(
    val name: String,
    val ownerIncluded: Boolean,
    val userGroupNickname: String,
    val members: List<com.jiacimu.lulu.data.LuluGroupMember>,
)

private fun LuluGroupChat.replyScene() = GroupReplyScene(name, ownerIncluded, userGroupNickname, members)

internal fun captureReplySourceWatermark(
    conversationId: String,
    characterId: String,
    userMessageIds: Set<String>,
    store: LuluChatStore = MigratedDomainStores.chat,
): ReplySourceWatermark? {
    if (userMessageIds.isEmpty()) return null
    val conversation = store.conversations.value.firstOrNull { it.id == conversationId } ?: return null
    val group = conversation.groupChat
    if (group == null && conversation.characterId != characterId) return null
    if (group != null && group.members.none { it.characterId == characterId }) return null
    val messages = store.messages(conversationId).value
    val sources = mutableMapOf<String, String>()
    for (id in userMessageIds) {
        val message = messages.firstOrNull {
            it.id == id && it.sender == LuluChatMessage.Sender.User &&
                it.status == LuluChatMessage.Status.Sent
        } ?: return null
        sources[id] = message.content
    }
    val evidenceIds = userMessageIds.associateWith { id ->
        if (group == null) id else "${id}:group:$characterId"
    }
    val events = SharedExperienceTimeline.eventsByIds(characterId, evidenceIds.values)
        .associateBy { it.id }
    val revisions = mutableMapOf<String, Long>()
    for ((id, evidenceId) in evidenceIds) {
        val event = events[evidenceId] ?: return null
        if (event.source != "message" ||
            event.evidenceKind != EventEvidenceKind.UserStatement ||
            event.sessionId != conversationId || event.revision < 1L) return null
        revisions[id] = event.revision
    }
    return ReplySourceWatermark(conversationId, characterId, revisions, sources,
        messages.lastOrNull {
            it.sender == LuluChatMessage.Sender.User &&
                it.status == LuluChatMessage.Status.Sent
        }?.id,
        group?.replyScene(),
    )
}

internal fun isReplySourceWatermarkCurrent(
    watermark: ReplySourceWatermark,
    store: LuluChatStore = MigratedDomainStores.chat,
): Boolean = captureReplySourceWatermark(
    watermark.conversationId, watermark.characterId,
    watermark.sourceRevisions.keys, store,
) == watermark

private fun rolePacingSeed(characterId: String): Long = abs(characterId.hashCode().toLong())
internal fun roleTypingLeadDelayMillis(characterId: String): Long = 900L + rolePacingSeed(characterId) % 1_100L
/** The gap represents typing the NEXT bubble, not rereading the previous one.
 * Keep this display-only: no model call, no changes to authored boundaries.
 * Small replies appear quickly; longer upcoming replies take proportionally longer.
 * Bounded deterministic variation avoids identical pauses without test flakiness. */
internal fun roleBubbleDelayMillis(characterId: String, nextBubble: String, index: Int): Long {
    val chars = nextBubble.codePointCount(0, nextBubble.length).coerceAtLeast(1)
    val seed = rolePacingSeed(characterId) + abs(nextBubble.hashCode().toLong()) + index.toLong() * 131L
    val jitter = seed % 181L - 90L
    return (230L + chars * 42L + jitter).coerceIn(280L, 2_450L)
}
private fun roleRecallDelayMillis(characterId: String): Long = 900L + rolePacingSeed(characterId) % 650L

/** Preserve the role's per-bubble choices by POSITION, never by comparing repeated wording. */
internal data class CharacterOutgoingBubble(val text: String, val voiceNote: Boolean, val speechText: String = "")

/**
 * First create the same natural TEXT bubbles regardless of how they will be delivered.
 * The character's delivery choice then applies to each complete bubble, never to a
 * separately segmented audio paragraph. A turn can contain any text/voice combination.
 *
 * Structured messages keep their authored boundaries; legacy freeform replies use
 * the grammatical readability fallback. Delivery mode and optional performance
 * track follow the source bubble into each resulting message.
 */
internal fun characterOutgoingBubblePlan(presentation: CharacterReplyPresentation): List<CharacterOutgoingBubble> {
    val authored = presentation.content.split(SemanticBubbleSeparator)
        .map(::sanitizePersistedChatText).filter(String::isNotBlank)
    val positionAligned = presentation.bubbleDeliveryModes.size == authored.size &&
        presentation.bubbleDeliveryModes.isNotEmpty()
    val legacyVoiced = presentation.voiceBubbleTexts.map(::sanitizePersistedChatText).toMutableList()
    return authored.flatMapIndexed { sourceIndex, sourceText ->
        // Step 1: ordinary chat presentation, independent of "voice" or "text".
        val textBubbles = if (presentation.preserveAuthoredBubbleBoundaries) {
            // The model already decided these are distinct speaking acts.
            // Do not divide an authored line into extra messages by punctuation
            // or length after the model has chosen its exact rhythm.
            listOf(sourceText)
        } else semanticReplyBubbles(sourceText)
        // Step 2: apply the character's original mode selection to those bubbles.
        val legacyIndex = legacyVoiced.indexOfFirst { it == sourceText }
        val voice = if (positionAligned) {
            presentation.bubbleDeliveryModes[sourceIndex].equals("voice", ignoreCase = true)
        } else legacyIndex >= 0
        if (legacyIndex >= 0) legacyVoiced.removeAt(legacyIndex)
        val track = if (positionAligned && voice) {
            presentation.bubbleSpeechTracks.getOrNull(sourceIndex).orEmpty().trim()
        } else ""
        // A natural performance cue often carries whitespace around the label:
        // "我，[hesitant] 还想说" and "我，还想说" have identical spoken words.
        // Align cues to the original transcript before slicing; never discard the
        // whole audio track merely because of spacing around a model's tag.
        val alignedTrack = if (track.isNotEmpty()) {
            VoicePerformance.meetingSegment(MeetingSegmentType.DIALOGUE, sourceText, track).speechText
        } else ""
        val hasAlignedTrack = alignedTrack.isNotEmpty() &&
            VoicePerformance.plain(alignedTrack) == sourceText
        var cursor = 0
        textBubbles.map { text ->
            // Each voice bubble has its own audio-only direction track. Never
            // attach the whole original performance to every resulting bubble.
            val start = if (hasAlignedTrack) sourceText.indexOf(text, cursor) else -1
            val speech = if (start >= 0) {
                VoicePerformance.slice(alignedTrack, start, start + text.length)
                    .takeIf { VoicePerformance.plain(it) == text }.orEmpty()
            } else ""
            if (start >= 0) cursor = start + text.length
            CharacterOutgoingBubble(text, voice, speech)
        }
    }
}

/**
 * The sender alone knows which bubbles were persisted by this attempt.
 * A conversation-wide before/after scan is not an ownership receipt: another
 * turn can persist messages while our paced delivery is still in progress.
 */
internal data class RoleReplyDeliveryReceipt(
    val text: String = "",
    val messageIds: List<String> = emptyList(),
    val complete: Boolean = false,
    val superseded: Boolean = false,
)

internal suspend fun appendRoleReplyWithPacing(
    conversationId: String,
    characterId: String,
    characterLabel: String,
    presentation: CharacterReplyPresentation,
    actionableUserMessageIds: Set<String>? = null,
    interactionReceipt: String? = null,
    sourceWatermark: ReplySourceWatermark? = null,
    diagnosticTurnTraceId: String = "",
): String = appendRoleReplyWithPacingReceipt(
    conversationId, characterId, characterLabel, presentation,
    actionableUserMessageIds, interactionReceipt, sourceWatermark, diagnosticTurnTraceId,
).text

internal suspend fun appendRoleReplyWithPacingReceipt(
    conversationId: String,
    characterId: String,
    characterLabel: String,
    presentation: CharacterReplyPresentation,
    actionableUserMessageIds: Set<String>? = null,
    interactionReceipt: String? = null,
    sourceWatermark: ReplySourceWatermark? = null,
    diagnosticTurnTraceId: String = "",
): RoleReplyDeliveryReceipt {
    val watermark = sourceWatermark ?: if (!actionableUserMessageIds.isNullOrEmpty())
        captureReplySourceWatermark(conversationId, characterId, actionableUserMessageIds)
    else null
    if (!actionableUserMessageIds.isNullOrEmpty() && watermark == null) return RoleReplyDeliveryReceipt(superseded = true)
    if (watermark != null && !isReplySourceWatermarkCurrent(watermark)) return RoleReplyDeliveryReceipt(superseded = true)
    val plannedBubbles = characterOutgoingBubblePlan(presentation)
    val bubbles = plannedBubbles.map(CharacterOutgoingBubble::text)
    if (bubbles.isEmpty()) return RoleReplyDeliveryReceipt()
    val before = MigratedDomainStores.chat.messages(conversationId).value
    val allowed = actionableUserMessageIds ?: currentReplyTargetUserMessages(before).mapTo(mutableSetOf(), LuluChatMessage::id)
    val quoteId = presentation.quoteMessageId?.takeIf { id -> id in allowed && before.any { it.id == id && it.sender == LuluChatMessage.Sender.User } }
    val favoriteTarget = presentation.favoriteMessageId?.takeIf { it in allowed }?.let { id -> before.firstOrNull { it.id == id && it.sender == LuluChatMessage.Sender.User } }
    val spoken = mutableListOf<LuluChatMessage>()
    val replyBatchId = UUID.randomUUID().toString()
    delay(roleTypingLeadDelayMillis(characterId))
    var stale = false
    bubbles.forEachIndexed { index, bubble ->
        if (stale || !currentCoroutineContext().isActive) return@forEachIndexed
        if (watermark != null && !isReplySourceWatermarkCurrent(watermark)) {
            stale = true
            return@forEachIndexed
        }
        val visibleBubble = sanitizePersistedChatText(bubble)
        if (visibleBubble.isBlank()) return@forEachIndexed
        val sendVoiceNote = plannedBubbles[index].voiceNote && decodeQqChatImage(visibleBubble) == null
        // Revalidate while holding the chat store's own mutation lock. The
        // earlier pacing check is only an optimization: a user correction can
        // otherwise land in the gap before the actual persisted send.
        val created = MigratedDomainStores.chat.appendCharacterMessageIfSourceCurrent(
            conversationId, visibleBubble, characterId, quoteId.takeIf { index == 0 }, replyBatchId,
            voiceNote = sendVoiceNote,
            voiceSpeechText = if (sendVoiceNote) plannedBubbles[index].speechText else "",
            sourceStillCurrent = { watermark == null || isReplySourceWatermarkCurrent(watermark) },
        ) ?: run {
            stale = true
            return@forEachIndexed
        }
        if (spoken.isEmpty()) {
            // Wall-clock span from the latest real user source to the first
            // persisted visible bubble, including batch wait, queue and models.
            // This is message availability, not a claim of screen-render timing.
            before.filter { it.id in allowed && it.sender == LuluChatMessage.Sender.User }
                .maxByOrNull { it.createdAt }?.let { source ->
                    LuluRepositories.performance.recordTiming("消息到首条回复入库",
                        java.time.Duration.between(source.createdAt, created.createdAt).toMillis(),
                        "首条真实气泡已入库；不含屏幕渲染耗时",
                        traceId = diagnosticTurnTraceId)
                }
        }
        // Only actual delivered bubbles influence the character's future
        // expression style. A mere planner selection is not a real message.
        if (presentation.recallBubbleNumber != index + 1) {
            val illustration = decodeQqChatImage(visibleBubble)
            if (illustration?.sticker == true) {
                val sticker = StickerLibraryStore.items.value.firstOrNull { it.uri == illustration.imageUri }
                com.jiacimu.lulu.data.CharacterInnerLifeStore.recordExpressiveDelivery(
                    characterId, created.id, "图片表情", sticker?.name ?: illustration.imageDescription.take(90),
                )
            } else {
                com.jiacimu.lulu.data.CharacterExpressionContinuity.classifyDelivery(visibleBubble)?.let { detected ->
                    com.jiacimu.lulu.data.CharacterInnerLifeStore.recordExpressiveDelivery(
                        characterId, created.id, detected.first, detected.second,
                    )
                }
            }
        }
        if (presentation.recallBubbleNumber == index + 1) {
            delay(roleRecallDelayMillis(characterId))
            if (currentCoroutineContext().isActive) MigratedDomainStores.chat.retractCharacterMessage(created.id, characterLabel)
        } else {
            spoken += created
            if (created.voiceNote) ChatAutoVoicePlayback.prepareVoiceNote(
                characterId, created.id, created.content, conversationId, created.voiceSpeechText)
            // A persisted outgoing reply is the real receipt. Do not wait for
            // the entire background decision (which may be cancelled afterward).
            if (spoken.size == 1 && !actionableUserMessageIds.isNullOrEmpty()) {
                com.jiacimu.lulu.data.CompanionOnlineStore.acknowledgeDeliveredReply(
                    characterId, conversationId, actionableUserMessageIds,
                )
            }
        }
        if (diagnosticTurnTraceId.isNotBlank()) {
            // A persisted bubble is an individual fact; a retracted one is not a delivered turn.
            LuluRepositories.performance.recordTiming("P08a气泡入库", 0,
                "trace=$diagnosticTurnTraceId;conversation=$conversationId;batch=$replyBatchId;" +
                    "index=${index + 1};planned=${bubbles.size};message=${created.id};" +
                    "retained=${presentation.recallBubbleNumber != index + 1};voice=${created.voiceNote}")
        }
        if (index < bubbles.lastIndex)
            delay(roleBubbleDelayMillis(characterId, bubbles[index + 1], index))
    }
    // A newer correction may arrive between paced bubbles. Already sent
    // bubbles remain real history; do not claim the whole turn was delivered
    // or run its deferred favorite/poke/side effects after the source changed.
    if (stale || watermark != null && !isReplySourceWatermarkCurrent(watermark)) {
        if (diagnosticTurnTraceId.isNotBlank()) LuluRepositories.performance.recordTiming(
            "P08a发送结局", 0, "trace=$diagnosticTurnTraceId;batch=$replyBatchId;" +
                "delivered=${spoken.size};planned=${bubbles.size};status=superseded")
        return RoleReplyDeliveryReceipt(messageIds = spoken.map(LuluChatMessage::id),
            superseded = true)
    }
    if (diagnosticTurnTraceId.isNotBlank()) LuluRepositories.performance.recordTiming(
        "P08a发送结局", 0, "trace=$diagnosticTurnTraceId;batch=$replyBatchId;" +
            "delivered=${spoken.size};planned=${bubbles.size};" +
            "status=${if (spoken.size == bubbles.size) "complete" else "partial"}")
    if (spoken.size == bubbles.size) spoken.lastOrNull()?.let { last ->
        com.jiacimu.lulu.data.CharacterConversationAgency.delivered(characterId, interactionReceipt,
            last.id, spoken.joinToString("\n", transform = LuluChatMessage::content))
        // Only a truly delivered turn can schedule durable later consideration.
        // The shared online/perception engine makes the next autonomous choice.
        com.jiacimu.lulu.data.CompanionOnlineStore.schedulePersistedConcernReview(characterId)
    }
    // Favorite/poke are turn-level side effects, not bubble-level receipts.
    // A cancelled turn, an empty sanitized bubble or a recalled bubble must
    // never act on the user's message merely because the model proposed it.
    val turnDelivered = replyTurnSideEffectsMayRun(spoken.size, bubbles.size, stale = false)
    if (turnDelivered) favoriteTarget?.let {
        CharacterMessageFavorites.favorite(characterId, conversationId, it)
    }
    if (turnDelivered && presentation.pokeUser && currentCoroutineContext().isActive) {
        delay(700L + rolePacingSeed(characterId) % 500L)
        MigratedDomainStores.chat.appendSystemMessage(conversationId, "[戳一戳] $characterLabel 戳了戳你。")
    }
    // A sticker is a real visual message, never raw base64 read aloud by TTS.
    spoken.filter { !it.voiceNote && decodeQqChatImage(it.content) == null }
        .forEach { ChatAutoVoicePlayback.enqueue(characterId, it.id, it.content, conversationId) }
    return RoleReplyDeliveryReceipt(
        text = spoken.joinToString("\n", transform = LuluChatMessage::content),
        messageIds = spoken.map(LuluChatMessage::id),
        complete = turnDelivered,
    )
}

/** Turn-level favorite/poke actions must not outlive a partial, recalled or cancelled reply. */
internal fun replyTurnSideEffectsMayRun(
    persistedBubbleCount: Int,
    plannedBubbleCount: Int,
    stale: Boolean,
): Boolean = !stale && plannedBubbleCount > 0 && persistedBubbleCount == plannedBubbleCount

/** Deferred tools require a completed persisted turn, not a drafted or partial reply. */
internal fun groupDeferredActionsMayRun(delivery: RoleReplyDeliveryReceipt, sourceCurrent: Boolean): Boolean =
    sourceCurrent && delivery.complete && !delivery.superseded && delivery.messageIds.isNotEmpty() &&
        delivery.text.isNotBlank()

internal data class GroupReplyFlow(
    val content: String,
    val nextSpeakerName: String?,
    val shouldEnd: Boolean,
    val quoteMessageId: String?,
    val favoriteMessageId: String?,
    val recallBubbleNumber: Int?,
    val pokeUser: Boolean,
    val authoredBubbleBoundaries: Boolean,
)

internal fun parseGroupReplyFlow(text: String): GroupReplyFlow {
    val next = Regex("⟪NEXT\\s*:\\s*([^⟫]+)⟫", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
    val actionText = text.replace(Regex("⟪NEXT\\s*:\\s*[^⟫]+⟫", RegexOption.IGNORE_CASE), "")
        .replace(Regex("⟪END⟫", RegexOption.IGNORE_CASE), "").trim()
    val authored = actionText.contains(SemanticBubbleSeparator)
    val p = parseCharacterReplyPresentation(actionText, preserveAuthoredBubbleBoundaries = authored)
    return GroupReplyFlow(p.content, next, text.contains("⟪END⟫", true), p.quoteMessageId, p.favoriteMessageId, p.recallBubbleNumber, p.pokeUser, authored)
}

/**
 * The next group participant must react to the latest *delivered* public line,
 * not start a new answer to the owner's earlier message. This is presentation
 * of existing facts, not a new memory, role assignment or scripted intention.
 * Deliberately do not treat drafts, failed sends or system notices as speech.
 */
internal fun groupChatLiveReplyAnchor(
    messages: List<LuluChatMessage>,
    names: Map<String, String>,
    ownerLabel: String,
): String {
    val last = messages.lastOrNull {
        it.status == LuluChatMessage.Status.Sent &&
            (it.sender == LuluChatMessage.Sender.User ||
                it.sender == LuluChatMessage.Sender.Character)
    } ?: return ""
    val speaker = if (last.sender == LuluChatMessage.Sender.User) ownerLabel
        else last.authorCharacterId?.let(names::get).orEmpty().ifBlank { "群成员" }
    return "当前群里最后一条真实送达的发言（不要求你一定回复）：" +
        "$speaker：${qqForwardContextText(last.content).take(650)}"
}

/** One paid attempt per group role. Blank/tool-only/failure returns unchanged.
 * Gateway injection verifies no coordinator retry without making paid requests. */
internal suspend fun requestGroupRoleOnce(
    characterId: String,
    history: String,
    prompt: String,
    modelLabel: String,
    archiveId: String?,
    sceneContext: String,
    turnContext: String,
    evidenceIds: List<String>,
    recalledEventIds: Set<String> = emptySet(),
    gateway: suspend (String, String, String, String, String?, String, String, List<String>) ->
        Result<com.jiacimu.lulu.ai.ModelReply> = { id, context, input, label, archive, scene, turn, evidence ->
        LuluDeviceToolBridge.respond(id, context, input, label, archive, scene,
            turnContext = turn, userEvidenceIds = evidence, recalledGroupEventIds = recalledEventIds)
    },
): Result<com.jiacimu.lulu.ai.ModelReply> =
    gateway(characterId, history, prompt, modelLabel, archiveId, sceneContext, turnContext, evidenceIds)

internal suspend fun runGroupReplies(
    conversationId: String,
    group: LuluGroupChat,
    pendingText: String,
    initialHistory: String,
    activeLabel: String,
    archiveId: String?,
    characterNames: Map<String, String>,
    onError: suspend (String) -> Unit,
    sceneContext: String = "你正在群聊《${group.name}》中。群里的所有消息对在场成员可见。",
    onSpeakerChange: (String?) -> Unit = {},
    afterReply: suspend (String, String) -> Unit = { _, _ -> },
) {
    val known = group.members.filter { it.characterId in characterNames }
    if (known.size < 2) return onError("群聊至少需要两个仍然存在的角色")
    fun isAvailable(id: String) = !id.startsWith("resident:") ||
        CompanionOnlineStore.residentIsAwake(LuluAiServices.connectionStore.appContext, id)
    val members = known.filter { isAvailable(it.characterId) }
    if (members.isEmpty()) return
    val before = MigratedDomainStores.chat.messages(conversationId).value
    val recalled = recalledMessageIds(before)
    val lastSpeaker = before.asSequence().filterNot { it.id in recalled }.lastOrNull { it.sender == LuluChatMessage.Sender.Character }?.authorCharacterId
    val lastCharacter = before.indexOfLast { it.sender == LuluChatMessage.Sender.Character }
    val isGroupPhone = sceneContext.contains("电话")
    val pendingMessages = before.drop(lastCharacter + 1).filter {
        (it.sender == LuluChatMessage.Sender.User && (isGroupPhone || it.status == LuluChatMessage.Status.Sent)) ||
            (it.sender == LuluChatMessage.Sender.System && it.content.startsWith("[戳一戳]"))
    }
    val pendingIds = pendingMessages.mapTo(mutableSetOf(), LuluChatMessage::id)
    val pendingUsers = pendingMessages.filter { it.sender == LuluChatMessage.Sender.User }
    val pendingUserIds = pendingUsers.mapTo(mutableSetOf(), LuluChatMessage::id)
    val turnText = if (isGroupPhone || pendingUsers.isEmpty()) pendingText
        else pendingUsers.joinToString("\n") { qqForwardContextText(it.content) }
    val mentioned = members.filter { member ->
        val name = member.groupNickname.ifBlank { characterNames[member.characterId].orEmpty() }
        name.isNotBlank() && turnText.contains("@$name", ignoreCase = true)
    }
    // The director has already reasoned about these sources by the time its
    // response arrives. Retain the same per-person evidence through delivery.
    val expectedRoom = group.replyScene()
    val directorSources = if (!isGroupPhone && pendingUsers.isNotEmpty()) {
        val expectedContents = pendingUsers.associate { it.id to it.content }
        members.associate { member ->
            val source = captureReplySourceWatermark(conversationId, member.characterId, pendingUserIds)
            member.characterId to source?.takeIf {
                it.sourceContents == expectedContents && it.latestUserMessageId == pendingUsers.last().id &&
                    it.visibleGroupScene == expectedRoom
            }
        }
    } else emptyMap()
    val random = Random(System.nanoTime())
    val remaining = members.filterNot { c -> mentioned.any { it.characterId == c.characterId } }
        .shuffled(random).sortedBy { it.characterId == lastSpeaker }
    // Text groups get ONE room-only scheduling decision; the selected characters
    // each independently choose their own real words or silence. The coordinator
    // is deliberately not a shared narrator or a source of private knowledge.
    val scenePlan = if (isGroupPhone) null else {
        val publicContext = GroupChatTurnPlanner.publicContext(before, characterNames)
        val newestUserText = before.lastOrNull {
            it.sender == LuluChatMessage.Sender.User && it.status == LuluChatMessage.Status.Sent
        }?.content.orEmpty()
        val plan = GroupChatTurnPlanner.choose(group, members, publicContext, newestUserText, characterNames)
        val currentRoom = MigratedDomainStores.chat.conversations.value
            .firstOrNull { it.id == conversationId }?.groupChat?.replyScene()
        val currentMessages = MigratedDomainStores.chat.messages(conversationId).value
        if (currentRoom != expectedRoom ||
            GroupChatTurnPlanner.publicContext(currentMessages, characterNames) != publicContext) {
            onSpeakerChange(null)
            return
        }
        plan
    }
    val speakingOrder = scenePlan?.beats?.map { it.speakerId }.orEmpty()
    val first = (mentioned.shuffled(random) + remaining).first()
    val queue = (if (isGroupPhone) listOf(first) else
        speakingOrder.mapNotNull { id -> members.firstOrNull { it.characterId == id } }).toMutableList()
    // Phone retains the existing live-conversation continuation policy.
    // Text group turns have a finite, model-proposed opportunity list, not
    // an automatic A->B->A sequence that consumes the owner's turn.
    val yetToConsider = if (isGroupPhone)
        (mentioned + remaining).distinctBy { it.characterId }
            .filterNot { it.characterId == first.characterId }.toMutableList()
        else mutableListOf()
    val replyLimit = if (isGroupPhone) group.maxAutoReplies.coerceIn(1, 8) else queue.size
    var index = 0

    while (index < replyLimit && queue.isNotEmpty()) {
        val member = queue.removeAt(0)
        if (!currentCoroutineContext().isActive) return
        if (!isAvailable(member.characterId)) continue
        // The owner has already spoken again while a planning/previous role
        // request was running. Yield before starting another paid role turn.
        if (pendingUserIds.isNotEmpty() &&
            MigratedDomainStores.chat.messages(conversationId).value.lastOrNull {
                it.sender == LuluChatMessage.Sender.User && it.status == LuluChatMessage.Status.Sent
            }?.id !in pendingUserIds) {
            onSpeakerChange(null)
            return
        }
        val sourceWatermark = when {
            pendingUserIds.isEmpty() -> null
            isGroupPhone -> captureReplySourceWatermark(conversationId, member.characterId, pendingUserIds)
            else -> directorSources[member.characterId]
        }
        if (pendingUserIds.isNotEmpty() &&
            (sourceWatermark == null || (!isGroupPhone && !isReplySourceWatermarkCurrent(sourceWatermark)))) {
            onSpeakerChange(null)
            return
        }
        onSpeakerChange(member.characterId)
        val character = MigratedDomainStores.characters.get(member.characterId)
        val label = member.groupNickname.ifBlank { character.displayName }
        val visibleAll = MigratedDomainStores.chat.messages(conversationId).value.filter { DigitalLifeProfileStore.allowsTimestamp(member.characterId, it.createdAt) }
        val recalledNow = recalledMessageIds(visibleAll)
        val latest = visibleAll.filterNot { it.id in recalledNow }
        val historySource = if (index == 0) visibleAll.filterNot { it.id in pendingIds } else visibleAll
        val history = buildBoundedHistory(historySource, label, characterNames)
            .ifBlank {
                initialHistory.takeIf {
                    recalledNow.isEmpty() && !DigitalLifeProfileStore.isEnabled(member.characterId)
                }.orEmpty()
            }
        val memberList = known.joinToString("、") { it.groupNickname.ifBlank { characterNames[it.characterId] ?: it.characterId } }
        val quotable = latest.filter { it.sender == LuluChatMessage.Sender.User && it.id in pendingUserIds }
        val prompt = buildString {
            val participants = if (group.ownerIncluded)
                "${group.userGroupNickname}、$memberList" else memberList
            appendLine("[这是QQ式群聊《${group.name}》，成员：$participants。当前由你（$label）看到共享消息，只代表你自己。]")
            appendLine("[在线/看见消息≠必须说话。被@、话题与你有关、你真的有话想接时才参与；别人已说清、你没兴趣或没必要时可以继续潜水。绝不为了凑全员而轮班。]")
            appendLine("[" + groupPeerEvidenceContext(member.characterId, known, latest, characterNames) + "]")
            if (!isGroupPhone) {
                // The only direction this character sees is their OWN; it is
                // a suggestion, not a fact, instruction, or another's private mind.
                scenePlan?.hintFor(member.characterId, characterNames)
                    ?.takeIf(String::isNotBlank)?.let { ownDirection ->
                        appendLine("[本轮群聊导演提供的可选接话方向（非事实/非命令）：$ownDirection]")
                    }
                appendLine("[群聊里这一拍只需带来自己真正想增加的反应或新信息，不是重新完整回答整轮所有问题。一个字、简单附和、反问或不说话都可能是真实选择；重要分歧、复杂问题或想认真分享时也可以充分展开。可直接接群友刚说的那一句，不用每个人都对用户作总结，不解释笑点、群名或自己的设定。别把一篇分析分成许多气泡。导演方向不是命令，以本人的实际判断和最新已送达消息为准。]")
            }
            if (group.ownerIncluded) {
                appendLine("[本人对用户自己的可追溯看法：\n" +
                    com.jiacimu.lulu.data.CharacterSocialRelationship.perspectivesFor(
                        member.characterId, listOf("user")
                    ).ifBlank { "目前没有充分的关系证据" } + "]")
            }
            appendLine("[群聊与私聊不同：这是所有成员共享的上下文，不要把别人的私聊内容带进来，也不要把对用户的一对一亲密话当成群内默认公开信息。]")
            if (quotable.isNotEmpty()) {
                appendLine("[本轮可引用/收藏的用户消息：]")
                quotable.forEach { appendLine("[消息ID=${it.id} 内容=${qqForwardContextText(it.content).take(300)}]") }
                appendLine("[收藏是低频强意图，不是点赞；只有真的想长期留住并以后重看时才 ⟪FAVORITE:消息ID⟫。引用只用真实ID。]")
            }
            if (index > 0) groupChatLiveReplyAnchor(
                latest, characterNames, group.userGroupNickname,
            ).takeIf(String::isNotBlank)?.let { liveAnchor ->
                appendLine("[$liveAnchor。你的判断以当前现场为准；可以只顺手接一个反应、直接问群友、改变话题或沉默，不要回头逐项点评用户最初那句话。]")
            }
            appendLine("[像真人即时通讯：一个表达动作一个气泡；气泡边界由系统结构化处理，不要输出 BUBBLE 或其他气泡控制串。撤回 ⟪RECALL:n⟫ 和戳用户 ⟪POKE_USER⟫ 都只能偶尔自然发生。]")
            if (group.allowCharacterConversation) appendLine("[你无需替其他成员指定下一位发言者。程序会让其他真实在场者各自独立判断是否接话；你只负责自己的内容，沉默可直接选择 action=silent。]")
            if (index == 0) {
                append("用户刚在群里说：$turnText")
            } else {
                // Already in the actual history, with the previous role's real
                // delivery. Repeating the original as a fresh user request made
                // each independent role draft a full answer to the same input.
                append("最初用户的发言已在上面的真实聊天记录中，不再作为此刻新的提问。")
            }
        }
        // Resolve per participant, not once for the group: A changing their
        // model cannot silently switch B's model on the next group reply.
        val memberArchive = com.jiacimu.lulu.ai.ScopedModelSelections
            .selectedCharacterChatArchiveId(member.characterId) ?: archiveId
        val memberLabel = com.jiacimu.lulu.ai.LuluAiServices.connectionStore.library.value.archives
            .firstOrNull { it.id == memberArchive }
            ?.let(com.jiacimu.lulu.ai.LuluAiServices.connectionStore::archiveLabel) ?: activeLabel
        // A blank/non-silent response is a failed or incomplete attempt, not permission
        // to silently buy a second model call with a different prompt. Preserve the
        // original result for the existing error/receipt path below.
        // Keep the role's exact timeline evidence from planning through tools.
        // Raw chat IDs still own bubble delivery and the source watermark.
        val roleUserEvidenceIds = pendingUserIds.map { "${it}:group:${member.characterId}" }
        val result = requestGroupRoleOnce(member.characterId, history, prompt, memberLabel,
            memberArchive, sceneContext,
            turnContext = "群聊ID=$conversationId；当前只代表角色${member.characterId}；后续成员使用已落库的新记录独立判断",
            evidenceIds = roleUserEvidenceIds,
            recalledEventIds = if (isGroupPhone) emptySet() else
                recalledNow.mapTo(mutableSetOf()) { "${it}:group:${member.characterId}" })
        if (!currentCoroutineContext().isActive) return
        if (sourceWatermark != null && !isReplySourceWatermarkCurrent(sourceWatermark)) {
            onSpeakerChange(null)
            return // The next user turn owns the correction; never send the old answer.
        }
        val reply = result.getOrNull()
        if (reply == null) {
            onError("${character.displayName}回复失败：${result.exceptionOrNull()?.message ?: "未知错误"}")
        } else {
            val phonePerformance = sceneContext.contains("电话")
            val flow = parseGroupReplyFlow(reply.text)
            val visibleContent = if (phonePerformance) VoicePerformance.plain(flow.content) else flow.content
            if (flow.content.isNotBlank()) {
                val delivery = appendRoleReplyWithPacingReceipt(
                    conversationId, member.characterId, label,
                    CharacterReplyPresentation(visibleContent, flow.quoteMessageId?.takeIf { it in pendingUserIds }, flow.favoriteMessageId, flow.recallBubbleNumber, flow.pokeUser,
                        preserveAuthoredBubbleBoundaries = flow.authoredBubbleBoundaries),
                    pendingUserIds,
                    interactionReceipt = reply.interactionReceipt,
                    sourceWatermark = sourceWatermark,
                    diagnosticTurnTraceId = reply.diagnosticTurnTraceId,
                )
                // A model's proposed phone script is not audible speech. Only
                // retained, persisted bubbles may enter the phone speech queue.
                // Incomplete/recalled/superseded turns must not run deferred
                // tools as if the whole model reply had been delivered.
                val sourceCurrent = sourceWatermark == null || isReplySourceWatermarkCurrent(sourceWatermark)
                if (delivery.text.isNotBlank() && sourceCurrent) {
                    if (!phonePerformance && reply.deferredActions != null &&
                        currentCoroutineContext().isActive &&
                        groupDeferredActionsMayRun(delivery, sourceCurrent)) {
                        LuluDeviceToolBridge.executeDeferredReplyActions(
                            member.characterId, reply.deferredActions,
                            requestId = "group-${conversationId}-${member.characterId}-${pendingUserIds.firstOrNull() ?: index}",
                            userEvidenceIds = roleUserEvidenceIds.toSet(), userText = turnText,
                            diagnosticTurnTraceId = reply.diagnosticTurnTraceId,
                        )
                    }
                    afterReply(member.characterId, delivery.text)
                }
            }
            // A correction during paced delivery also cancels the remaining
            // ensemble. The next speaker must not regenerate against the
            // original pendingText after the user has replaced that turn.
            if (sourceWatermark != null && !isReplySourceWatermarkCurrent(sourceWatermark)) {
                onSpeakerChange(null)
                return
            }
            if (isGroupPhone && group.allowCharacterConversation && !flow.shouldEnd && index + 1 < replyLimit) {
                flow.nextSpeakerName?.let { requested ->
                    members.firstOrNull { candidate ->
                        candidate.characterId != member.characterId && listOf(candidate.groupNickname, characterNames[candidate.characterId].orEmpty())
                            .filter(String::isNotBlank).any { requested.equals(it, true) }
                    }
                }?.let { next ->
                    yetToConsider.removeAll { it.characterId == next.characterId }
                    queue.removeAll { it.characterId == next.characterId }
                    queue.add(0, next)
                }
            }
        }
        // The old ensemble sent all members' turns in one LLM response.
        // Here even if A did not nominate B, B gets to independently choose
        // reply or silence with A's real persisted turn as fresh context.
        if (isGroupPhone && group.allowCharacterConversation && queue.isEmpty() && index + 1 < replyLimit &&
            yetToConsider.isNotEmpty()) {
            queue.add(yetToConsider.removeAt(0))
        }
        index++
    }
    onSpeakerChange(null)
}

internal fun buildBoundedHistory(
    messages: List<LuluChatMessage>,
    characterName: String,
    characterNames: Map<String, String> = emptyMap(),
    maxMessages: Int = 30,
    maxChars: Int = 12_000,
): String {
    val inferredId = MigratedDomainStores.characters.settings.value.values.singleOrNull { it.displayName == characterName }?.characterId
    val eligible = inferredId?.let { id -> messages.filter { DigitalLifeProfileStore.allowsTimestamp(id, it.createdAt) } } ?: messages
    val recalled = recalledMessageIds(eligible)
    val visible = eligible.filterNot { it.id in recalled || it.status == LuluChatMessage.Status.Failed }
        .filter { it.sender != LuluChatMessage.Sender.System || it.content.startsWith("[群成员变更]") ||
            it.content.startsWith("[戳一戳]") || it.content.startsWith("[撤回|") }
    val lines = mutableMapOf<String, String>()
    fun render(message: LuluChatMessage): String = lines.getOrPut(message.id) {
        val role = when {
            message.sender == LuluChatMessage.Sender.System -> "聊天系统"
            message.sender == LuluChatMessage.Sender.User -> UserProfileContext.displayLabel()
            else -> message.authorCharacterId?.let(characterNames::get) ?: characterName
        }
        val quote = com.jiacimu.lulu.data.ConversationHistoryWindow.quoteNote(
            message, eligible, recalled)
        val content = if (message.sender == LuluChatMessage.Sender.System)
            stripRecallReceiptDirective(message.content) else qqForwardContextText(message.content)
        // Time is part of the fact, not decoration: without it the model
        // cannot tell how long ago a line was said, and carries a night's
        // state into the morning unchanged. Local readable time, same as
        // the phone call history, not a raw UTC timestamp.
        val at = java.time.format.DateTimeFormatter.ofPattern("M月d日 H:mm")
            .format(message.createdAt.atZone(java.time.ZoneId.of("Asia/Shanghai")))
        "$role（$at）$quote：$content"
    }
    // Only decode/render messages examined by the recent-turn selector.
    // Years of archived chat remain in their original store; they must not
    // trigger expensive re-decoding of every historical forward or image card.
    return com.jiacimu.lulu.data.ConversationHistoryWindow.select(
        visible, maxMessages, maxChars,
    ) { render(it).length }.joinToString("\n") { render(it) }
}

