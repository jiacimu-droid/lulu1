package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

enum class MomentAuthorType { User, Character }

data class MomentComment(
    val id: String = UUID.randomUUID().toString(),
    val characterId: String,
    val content: String,
    val createdAt: Instant = Instant.now(),
    val replyToCommentId: String? = null,
    val replyToCharacterId: String? = null,
)

data class MomentPost(
    val id: String = UUID.randomUUID().toString(),
    val authorType: MomentAuthorType,
    val authorCharacterId: String? = null,
    val content: String,
    val createdAt: Instant = Instant.now(),
    val likedCharacterIds: Set<String> = emptySet(),
    val comments: List<MomentComment> = emptyList(),
    val imageUri: String? = null,
    val imageDescription: String = "",
)

object MomentsStore {
    private const val PREFS_NAME = "lulu_moments"
    private const val KEY_POSTS = "posts_v1"
    private const val KEY_UNREAD_CHARACTER_IDS = "unread_character_post_ids_v1"
    private const val USER_COMMENTER_ID = "__user__"
    private data class ReactionPlan(
        val wantsLike: Boolean,
        val comment: String,
    )

    private var prefs: android.content.SharedPreferences? = null
    private val mutablePosts = MutableStateFlow<List<MomentPost>>(emptyList())
    val posts: StateFlow<List<MomentPost>> = mutablePosts.asStateFlow()
    private val mutableUnreadCharacterPosts = MutableStateFlow(0)
    val unreadCharacterPosts: StateFlow<Int> = mutableUnreadCharacterPosts.asStateFlow()
    private val socialScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mutablePosts.value = decode(prefs?.getString(KEY_POSTS, null))
        refreshUnreadCount()
    }

    fun markCharacterPostsSeen() {
        prefs?.edit()?.putStringSet(KEY_UNREAD_CHARACTER_IDS, emptySet())?.apply()
        mutableUnreadCharacterPosts.value = 0
    }

    fun publishUser(
        content: String,
        imageUri: String? = null,
        imageDescription: String = "",
    ): MomentPost? {
        val clean = content.trim()
        val cleanImage = imageUri?.trim()?.takeIf(String::isNotBlank)
        if (clean.isBlank() && cleanImage == null) return null
        val post = MomentPost(
            authorType = MomentAuthorType.User,
            content = clean.take(2_000),
            imageUri = cleanImage,
            imageDescription = imageDescription.trim().take(1_800),
        )
        savePost(post)
        recordForAllCharacters(post, UserProfileContext.displayLabel())
        socialScope.launch { letOnlineCharactersReact(post.id) }
        return post
    }

    fun publishCharacter(
        characterId: String,
        content: String,
        imageUri: String? = null,
        imageDescription: String = "",
    ): MomentPost? {
        val clean = content.trim()
        val cleanImage = imageUri?.trim()?.takeIf(String::isNotBlank)
        if (characterId.isBlank() || (clean.isBlank() && cleanImage == null)) return null
        val post = MomentPost(
            authorType = MomentAuthorType.Character,
            authorCharacterId = characterId,
            content = clean.take(2_000),
            imageUri = cleanImage,
            imageDescription = imageDescription.trim().take(1_800),
        )
        savePost(post)
        addUnreadCharacterPost(post.id)
        val name = MigratedDomainStores.characters.get(characterId).displayName
        recordForAllCharacters(post, name)
        socialScope.launch { letOtherCharactersReact(post.id) }
        return post
    }

    /** A user post is an @all-style wake-up; every role perceives it independently and may stay silent. */
    fun requestCharactersReact(postId: String) {
        socialScope.launch { letCharactersReact(postId) }
    }

    suspend fun letCharactersReact(postId: String) {
        val post = mutablePosts.value.firstOrNull { it.id == postId && it.authorType == MomentAuthorType.User } ?: return
        val candidates = MigratedDomainStores.characters.settings.value.values.sortedBy(CharacterSettings::displayName)
        candidates.forEach { character ->
            CompanionOnlineStore.wakeCharacter(
                characterId = character.characterId,
                reason = CompanionOnlineReason.MomentsWake,
                trigger = "用户发布朋友圈并呼唤角色来看",
                perceiveNow = false,
            )
        }
        candidates.forEach { character ->
            runIndependentReaction(post, character, UserProfileContext.displayLabel())
        }
    }

    private suspend fun letOnlineCharactersReact(postId: String) {
        val post = mutablePosts.value.firstOrNull { it.id == postId && it.authorType == MomentAuthorType.User } ?: return
        val candidates = MigratedDomainStores.characters.settings.value.values
            .filter { CompanionOnlineStore.isOnline(it.characterId) }
            .sortedBy(CharacterSettings::displayName)
        candidates.forEach { character ->
            runIndependentReaction(post, character, UserProfileContext.displayLabel())
        }
    }

    private suspend fun letOtherCharactersReact(postId: String) {
        val post = mutablePosts.value.firstOrNull {
            it.id == postId && it.authorType == MomentAuthorType.Character && !it.authorCharacterId.isNullOrBlank()
        } ?: return
        val authorId = post.authorCharacterId ?: return
        val author = MigratedDomainStores.characters.get(authorId)
        val candidates = MigratedDomainStores.characters.settings.value.values
            .filter { it.characterId != authorId && CompanionOnlineStore.isOnline(it.characterId) }
            .sortedBy(CharacterSettings::displayName)
        candidates.forEach { character -> runIndependentReaction(post, character, author.displayName) }
    }

    private suspend fun runIndependentReaction(
        post: MomentPost,
        character: CharacterSettings,
        authorName: String,
    ) {
        val socialMemoryRequest = UnifiedMemoryRequest(
            currentInput = momentContext(post),
            sceneContext = "朋友圈原帖，作者：$authorName",
            taskIntent = "当前角色独立判断是否点赞、评论，或者只看不回应",
        )
        val memoryContext = UnifiedMemoryOrchestrator.assemble(
            characterId = character.characterId,
            request = socialMemoryRequest,
            recallLimit = 6,
            evidenceLimit = 4,
            evidenceCharacterBudget = 2_200,
            recentCharacterBudget = 2_600,
        )
        val identity = CharacterIdentityStore.get(character.characterId)
        val presence = CompanionPresenceStore.current(character.characterId)

        val raw = LuluAiServices.gateway.generate(
            characterId = character.characterId,
            facts = buildString {
                appendLine("【朋友圈原帖】")
                appendLine("作者：$authorName")
                appendLine("作者类型：${if (post.authorType == MomentAuthorType.User) "用户" else "角色"}")
                appendLine(momentContext(post))
                appendLine()
                appendLine("【当前独立判断的角色】")
                appendLine("characterId=${character.characterId}")
                appendLine("显示名=${character.displayName}")
                if (identity.isNotBlank()) appendLine("身份与关系=${identity.take(760)}")
                appendLine("人设=${character.persona.ifBlank { "按该角色现有人设自然行动。" }.take(980)}")
                memoryContext.compactPromptSection(characterBudget = 3_600)
                    .takeIf(String::isNotBlank)
                    ?.let { appendLine(it) }
                presence?.let {
                    appendLine(
                        "上一刻状态=${it.statusText.take(120)}；动作=${it.gesture.take(120)}；心情=${it.mood.take(80)}；没说出口=${it.innerThought.take(180)}",
                    )
                }
            },
            instruction = """
                你就是当前这个角色。你刚上线看见了这条朋友圈，请按本人性格、关系、经历和此刻心情独立决定怎么做。看见不等于必须互动。

                只返回一个 JSON 对象，不要代码块、解释或旁白：
                {"action":"none|like|comment|like_comment","comment":"评论正文；不评论时留空"}

                规则：
                1. none、只点赞、只评论、点赞并评论都是真实有效的选择。关系不好、不想搭理或觉得没必要时可以 none。
                2. 不要因为用户呼唤了你，就机械地表示“看到了”；行动必须符合这个角色本人。
                3. 如果评论，应像真实社交软件里的自然短评论，可以接梗、关心、吐槽，也可以只写很短一句；不写角色名标签或规则说明。
                4. 如果有配图描述，那是实际可见的信息，可以回应画面细节；不要编造描述之外的画面。
                5. 只根据原帖、本人资料和真实经历判断，不要虚构用户未提供的身体、环境或私密设备状态。
            """.trimIndent(),
            source = "朋友圈独立感知",
            title = "${character.displayName}查看朋友圈",
            temperature = 0.9,
            maxTokens = 600,
            usage = ModelUsage.Chat,
            contextMode = CompanionContextMode.PersonaAndScenario,
            memoryRequest = socialMemoryRequest,
        ).getOrNull()?.text.orEmpty()

        val plan = parseReactionPlan(raw) ?: return
        if (plan.wantsLike) addCharacterLike(post, character, authorName)
        if (plan.comment.isNotBlank()) addCharacterTopLevelComment(post, character, authorName, plan.comment)
    }

    private fun parseReactionPlan(raw: String): ReactionPlan? = runCatching {
        val clean = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
            .let { value ->
                val start = value.indexOf('{')
                val end = value.lastIndexOf('}')
                if (start >= 0 && end > start) value.substring(start, end + 1) else value
            }
        val item = JSONObject(clean)
        val action = item.optString("action").trim().lowercase()
        if (action !in setOf("none", "like", "comment", "like_comment", "comment_like")) return@runCatching null
        val wantsLike = action == "like" || action == "like_comment" || action == "comment_like"
        val wantsComment = action == "comment" || action == "like_comment" || action == "comment_like"
        val comment = if (wantsComment) {
            item.optString("comment").trim().removePrefix("评论：").removeSurrounding("\"").take(300)
        } else {
            ""
        }
        ReactionPlan(wantsLike = wantsLike, comment = comment)
    }.getOrNull()

    private fun addCharacterLike(post: MomentPost, character: CharacterSettings, authorName: String) {
        val currentPost = mutablePosts.value.firstOrNull { it.id == post.id } ?: return
        if (character.characterId in currentPost.likedCharacterIds) return
        mutate { current ->
            current.map { item ->
                if (item.id == post.id) item.copy(likedCharacterIds = item.likedCharacterIds + character.characterId) else item
            }
        }
        SharedExperienceTimeline.record(
            eventId = "moment-like-${post.id}-${character.characterId}",
            characterId = character.characterId,
            channel = "朋友圈",
            speaker = character.displayName,
            content = "给${authorName}的朋友圈点了赞：${momentContext(post).take(420)}",
            occurredAt = Instant.now(),
        )
    }

    private fun addCharacterTopLevelComment(
        post: MomentPost,
        character: CharacterSettings,
        authorName: String,
        content: String,
    ): Boolean {
        val clean = content.trim().take(300)
        if (clean.isBlank()) return false
        val currentPost = mutablePosts.value.firstOrNull { it.id == post.id } ?: return false
        if (currentPost.comments.any { it.characterId == character.characterId && it.replyToCommentId == null }) return false
        val comment = MomentComment(characterId = character.characterId, content = clean)
        mutate { current ->
            current.map { item ->
                if (item.id != post.id || item.comments.any { it.id == comment.id }) item
                else item.copy(comments = item.comments + comment)
            }
        }
        recordPublicCommentForAllCharacters(
            comment = comment,
            speakerName = character.displayName,
            timelineText = "【公开评论】评论了${authorName}的朋友圈：${comment.content}",
        )
        return true
    }

    fun toggleUserLike(postId: String, characterId: String = USER_COMMENTER_ID) {
        mutate { current ->
            current.map { post ->
                if (post.id != postId) post else post.copy(
                    likedCharacterIds = if (characterId in post.likedCharacterIds) {
                        post.likedCharacterIds - characterId
                    } else {
                        post.likedCharacterIds + characterId
                    },
                )
            }
        }
    }

    fun addUserComment(postId: String, content: String): MomentComment? {
        val clean = content.trim().take(500)
        if (clean.isBlank()) return null
        val post = mutablePosts.value.firstOrNull { it.id == postId } ?: return null
        val comment = MomentComment(characterId = USER_COMMENTER_ID, content = clean)
        mutate { current ->
            current.map { item -> if (item.id == postId) item.copy(comments = item.comments + comment) else item }
        }
        val authorName = postAuthorName(post)
        recordPublicCommentForAllCharacters(
            comment = comment,
            speakerName = UserProfileContext.displayLabel(),
            timelineText = "【公开评论】评论了${authorName}的朋友圈：$clean",
        )
        post.authorCharacterId?.let { authorId ->
            socialScope.launch { replyToUserComment(postId, comment.id, authorId) }
        }
        return comment
    }

    /** User taps a concrete character comment/reply, creating a directed reply visible in UI but private in raw timelines. */
    fun addUserReply(postId: String, replyToCommentId: String, content: String): MomentComment? {
        val clean = content.trim().take(500)
        if (clean.isBlank()) return null
        val post = mutablePosts.value.firstOrNull { it.id == postId } ?: return null
        val target = post.comments.firstOrNull { it.id == replyToCommentId } ?: return null
        val targetCharacterId = target.characterId.takeIf { it != USER_COMMENTER_ID } ?: return null
        val comment = MomentComment(
            characterId = USER_COMMENTER_ID,
            content = clean,
            replyToCommentId = target.id,
            replyToCharacterId = targetCharacterId,
        )
        mutate { current ->
            current.map { item -> if (item.id == postId) item.copy(comments = item.comments + comment) else item }
        }
        recordUserCommentForCharacter(
            comment = comment,
            characterId = targetCharacterId,
            timelineText = "【定向回复】回复了你在朋友圈的评论“${target.content.take(180)}”：$clean",
        )
        socialScope.launch { replyToUserComment(postId, comment.id, targetCharacterId) }
        return comment
    }

    private fun recordUserCommentForCharacter(
        comment: MomentComment,
        characterId: String,
        timelineText: String,
    ) {
        SharedExperienceTimeline.record(
            eventId = "moment-user-comment-${comment.id}-$characterId",
            characterId = characterId,
            channel = "朋友圈",
            speaker = UserProfileContext.displayLabel(),
            content = timelineText,
            occurredAt = comment.createdAt,
        )
    }

    private fun recordPublicCommentForAllCharacters(
        comment: MomentComment,
        speakerName: String,
        timelineText: String,
    ) {
        MigratedDomainStores.characters.settings.value.keys.forEach { characterId ->
            SharedExperienceTimeline.record(
                eventId = "moment-public-comment-${comment.id}-$characterId",
                characterId = characterId,
                channel = "朋友圈",
                speaker = speakerName,
                content = timelineText,
                occurredAt = comment.createdAt,
            )
        }
    }

    private suspend fun replyToUserComment(postId: String, commentId: String, responderId: String) {
        val post = mutablePosts.value.firstOrNull { it.id == postId } ?: return
        val userComment = post.comments.firstOrNull {
            it.id == commentId && it.characterId == USER_COMMENTER_ID
        } ?: return
        if (post.comments.any { it.characterId == responderId && it.replyToCommentId == commentId }) return

        CompanionOnlineStore.wakeCharacter(
            characterId = responderId,
            reason = CompanionOnlineReason.MomentsWake,
            trigger = "用户在朋友圈评论区呼唤角色",
            perceiveNow = false,
        )

        val responder = MigratedDomainStores.characters.get(responderId)
        val repliedComment = userComment.replyToCommentId?.let { id -> post.comments.firstOrNull { it.id == id } }
        val postAuthorName = postAuthorName(post)
        val directPrivateThread = userComment.replyToCharacterId == responderId
        val replyText = LuluAiServices.gateway.generate(
            characterId = responderId,
            facts = buildString {
                appendLine("朋友圈原帖作者：$postAuthorName")
                appendLine("朋友圈原帖：${momentContext(post)}")
                when {
                    repliedComment?.characterId == responderId -> {
                        appendLine("你先前在这条朋友圈下面写过：${repliedComment.content}")
                        appendLine("${UserProfileContext.displayLabel()}刚刚直接回复你这条评论：${userComment.content}")
                    }
                    post.authorCharacterId == responderId -> {
                        appendLine("这是你自己发布的朋友圈。")
                        appendLine("${UserProfileContext.displayLabel()}刚刚在你的朋友圈下面评论：${userComment.content}")
                    }
                    else -> {
                        appendLine("${UserProfileContext.displayLabel()}刚刚在这条朋友圈评论区里直接回复你：${userComment.content}")
                    }
                }
                if (post.comments.isNotEmpty()) {
                    appendLine("最近评论区上下文：")
                    post.comments.takeLast(10).forEach { item ->
                        val speaker = when (item.characterId) {
                            USER_COMMENTER_ID -> UserProfileContext.displayLabel()
                            else -> MigratedDomainStores.characters.get(item.characterId).displayName
                        }
                        val target = when (item.replyToCharacterId) {
                            USER_COMMENTER_ID -> UserProfileContext.displayLabel()
                            null -> null
                            else -> MigratedDomainStores.characters.get(item.replyToCharacterId).displayName
                        }
                        appendLine(
                            if (target.isNullOrBlank()) "- $speaker：${item.content.take(260)}"
                            else "- $speaker 回复 $target：${item.content.take(260)}",
                        )
                    }
                }
            },
            instruction = """
                这是朋友圈评论区里正在发生的真实一对一互动。你已经上线并看见用户刚刚对你的评论或回复，但看见不等于必须回应。
                如果原帖不是你发的，也不要把原帖说成自己的；你只是正在那个评论区里继续和用户说话。
                只返回 JSON：{"action":"reply|silent","text":"决定回复时的正文，否则留空"}
                是否回复由你的人设、关系、情绪和这条内容决定。回复要短、自然，可以接梗、回答、反问或只回一句，不扩写成聊天长文。
                不替其他角色说话，不写角色名，不写“回复：”标签，不解释规则。
            """.trimIndent(),
            source = "朋友圈单独回复",
            title = "${responder.displayName}回复朋友圈评论",
            temperature = 0.86,
            maxTokens = 180,
            usage = ModelUsage.Chat,
        ).getOrNull()?.text.orEmpty().let { raw ->
            runCatching {
                val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val start = clean.indexOf('{')
                val end = clean.lastIndexOf('}')
                val json = JSONObject(if (start >= 0 && end > start) clean.substring(start, end + 1) else clean)
                if (json.optString("action").trim().lowercase() == "reply") {
                    json.optString("text").trim().removeSurrounding("\"").take(300)
                } else {
                    ""
                }
            }.getOrDefault("")
        }
        if (replyText.isBlank()) return
        val reply = MomentComment(
            characterId = responderId,
            content = replyText,
            replyToCommentId = commentId,
            replyToCharacterId = USER_COMMENTER_ID,
        )
        mutate { current ->
            current.map { item ->
                if (item.id != postId || item.comments.any { it.characterId == responderId && it.replyToCommentId == commentId }) item
                else item.copy(comments = item.comments + reply)
            }
        }
        if (directPrivateThread) {
            SharedExperienceTimeline.record(
                eventId = "moment-comment-${reply.id}-$responderId",
                characterId = responderId,
                channel = "朋友圈",
                speaker = responder.displayName,
                content = "【定向回复】回复了${UserProfileContext.displayLabel()}：${reply.content}",
                occurredAt = reply.createdAt,
            )
        } else {
            recordPublicCommentForAllCharacters(
                comment = reply,
                speakerName = responder.displayName,
                timelineText = "【公开评论】回复了${UserProfileContext.displayLabel()}在${postAuthorName}朋友圈下的评论：${reply.content}",
            )
        }
    }

    fun delete(postId: String) {
        val post = mutablePosts.value.firstOrNull { it.id == postId } ?: return
        mutate { current -> current.filterNot { it.id == postId } }
        removeUnreadCharacterPost(postId)
        val characterIds = MigratedDomainStores.characters.settings.value.keys
        characterIds.forEach { characterId ->
            SharedExperienceTimeline.deleteEvent("moment-$postId-$characterId")
            SharedExperienceTimeline.deleteEvent("moment-like-$postId-$characterId")
        }
        post.comments.forEach { comment ->
            deleteTimelineEventsForComment(post, comment, characterIds)
        }
    }

    /** Clears authored posts plus this character's likes/comments and user replies aimed at them. */
    fun clearCharacterData(characterId: String) {
        if (characterId.isBlank()) return
        mutablePosts.value.filter { it.authorCharacterId == characterId }.map(MomentPost::id).forEach(::delete)
        val authoredComments = mutablePosts.value.flatMap { post ->
            post.comments.filter { it.characterId == characterId }.map { comment -> post to comment }
        }
        val userRepliesToCharacter = mutablePosts.value.flatMap { post ->
            post.comments.filter {
                it.characterId == USER_COMMENTER_ID && it.replyToCharacterId == characterId
            }.map { comment -> post to comment }
        }
        mutate { current ->
            current.map { post ->
                post.copy(
                    likedCharacterIds = post.likedCharacterIds - characterId,
                    comments = post.comments.filterNot { comment ->
                        comment.characterId == characterId ||
                            (comment.characterId == USER_COMMENTER_ID && comment.replyToCharacterId == characterId)
                    },
                )
            }
        }
        mutablePosts.value.forEach { post -> SharedExperienceTimeline.deleteEvent("moment-like-${post.id}-$characterId") }
        val allCharacterIds = MigratedDomainStores.characters.settings.value.keys
        authoredComments.forEach { (post, comment) ->
            deleteTimelineEventsForComment(post, comment, allCharacterIds)
        }
        userRepliesToCharacter.forEach { (_, comment) ->
            SharedExperienceTimeline.deleteEvent("moment-user-comment-${comment.id}-$characterId")
        }
    }

    private fun deleteTimelineEventsForComment(
        post: MomentPost,
        comment: MomentComment,
        characterIds: Set<String>,
    ) {
        if (isPrivateDirectedComment(post, comment)) {
            val targetId = when {
                comment.characterId == USER_COMMENTER_ID -> comment.replyToCharacterId
                else -> comment.characterId
            }
            targetId?.let { characterId ->
                if (comment.characterId == USER_COMMENTER_ID) {
                    SharedExperienceTimeline.deleteEvent("moment-user-comment-${comment.id}-$characterId")
                } else {
                    SharedExperienceTimeline.deleteEvent("moment-comment-${comment.id}-$characterId")
                }
            }
        } else {
            characterIds.forEach { characterId ->
                SharedExperienceTimeline.deleteEvent("moment-public-comment-${comment.id}-$characterId")
            }
        }

        // Backward-compatible cleanup for comments created before public-comment fan-out existed.
        if (comment.characterId == USER_COMMENTER_ID) {
            userCommentTargetCharacterId(post, comment)?.let { characterId ->
                SharedExperienceTimeline.deleteEvent("moment-user-comment-${comment.id}-$characterId")
            }
        } else {
            SharedExperienceTimeline.deleteEvent("moment-comment-${comment.id}-${comment.characterId}")
        }
    }

    private fun isPrivateDirectedComment(post: MomentPost, comment: MomentComment): Boolean {
        if (comment.characterId == USER_COMMENTER_ID) return !comment.replyToCharacterId.isNullOrBlank()
        val parentId = comment.replyToCommentId ?: return false
        val parent = post.comments.firstOrNull { it.id == parentId } ?: return false
        return parent.characterId == USER_COMMENTER_ID && parent.replyToCharacterId == comment.characterId
    }

    private fun userCommentTargetCharacterId(post: MomentPost, comment: MomentComment): String? =
        comment.replyToCharacterId?.takeIf { it != USER_COMMENTER_ID } ?: post.authorCharacterId

    private fun postAuthorName(post: MomentPost): String =
        if (post.authorType == MomentAuthorType.User) {
            UserProfileContext.displayLabel()
        } else {
            post.authorCharacterId
                ?.let { MigratedDomainStores.characters.get(it).displayName }
                .orEmpty()
                .ifBlank { "角色" }
        }

    private fun savePost(post: MomentPost) = mutate { current -> listOf(post) + current }

    private fun recordForAllCharacters(post: MomentPost, authorName: String) {
        val context = momentContext(post)
        MigratedDomainStores.characters.settings.value.keys.forEach { characterId ->
            SharedExperienceTimeline.record(
                eventId = "moment-${post.id}-$characterId",
                characterId = characterId,
                channel = "朋友圈",
                speaker = authorName,
                content = context,
                occurredAt = post.createdAt,
            )
        }
    }

    private fun momentContext(post: MomentPost): String = buildString {
        if (post.content.isNotBlank()) append(post.content)
        if (post.imageDescription.isNotBlank()) {
            if (isNotEmpty()) append("\n")
            append("[配图：${post.imageDescription}]")
        } else if (!post.imageUri.isNullOrBlank()) {
            if (isNotEmpty()) append("\n")
            append("[这条朋友圈带有一张图片，但当前没有可用的识图描述]")
        }
    }.ifBlank { "[朋友圈图片]" }

    private fun addUnreadCharacterPost(postId: String) {
        val current = prefs?.getStringSet(KEY_UNREAD_CHARACTER_IDS, emptySet()).orEmpty().toMutableSet()
        current += postId
        prefs?.edit()?.putStringSet(KEY_UNREAD_CHARACTER_IDS, current)?.apply()
        mutableUnreadCharacterPosts.value = current.size
    }

    private fun removeUnreadCharacterPost(postId: String) {
        val current = prefs?.getStringSet(KEY_UNREAD_CHARACTER_IDS, emptySet()).orEmpty().toMutableSet()
        if (current.remove(postId)) prefs?.edit()?.putStringSet(KEY_UNREAD_CHARACTER_IDS, current)?.apply()
        mutableUnreadCharacterPosts.value = current.size
    }

    private fun refreshUnreadCount() {
        val existingIds = mutablePosts.value.asSequence()
            .filter { it.authorType == MomentAuthorType.Character }
            .map(MomentPost::id)
            .toSet()
        val current = prefs?.getStringSet(KEY_UNREAD_CHARACTER_IDS, emptySet()).orEmpty().filterTo(mutableSetOf()) { it in existingIds }
        prefs?.edit()?.putStringSet(KEY_UNREAD_CHARACTER_IDS, current)?.apply()
        mutableUnreadCharacterPosts.value = current.size
    }

    private fun mutate(transform: (List<MomentPost>) -> List<MomentPost>) {
        val next = transform(mutablePosts.value).sortedByDescending(MomentPost::createdAt)
        mutablePosts.value = next
        prefs?.edit()?.putString(KEY_POSTS, encode(next).toString())?.apply()
    }

    private fun encode(values: List<MomentPost>): JSONArray = JSONArray().apply {
        values.forEach { post ->
            put(JSONObject().apply {
                put("id", post.id)
                put("authorType", post.authorType.name)
                put("authorCharacterId", post.authorCharacterId ?: JSONObject.NULL)
                put("content", post.content)
                put("createdAt", post.createdAt.toString())
                put("imageUri", post.imageUri ?: JSONObject.NULL)
                put("imageDescription", post.imageDescription)
                put("likedCharacterIds", JSONArray(post.likedCharacterIds.toList()))
                put("comments", JSONArray().apply {
                    post.comments.forEach { comment ->
                        put(
                            JSONObject()
                                .put("id", comment.id)
                                .put("characterId", comment.characterId)
                                .put("content", comment.content)
                                .put("createdAt", comment.createdAt.toString())
                                .put("replyToCommentId", comment.replyToCommentId ?: JSONObject.NULL)
                                .put("replyToCharacterId", comment.replyToCharacterId ?: JSONObject.NULL),
                        )
                    }
                })
            })
        }
    }

    private fun decode(raw: String?): List<MomentPost> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val content = item.optString("content").trim()
                    val imageUri = item.optString("imageUri").takeIf(String::isNotBlank)
                    if (content.isBlank() && imageUri == null) continue
                    val likes = item.optJSONArray("likedCharacterIds")?.let { values ->
                        buildSet { for (i in 0 until values.length()) add(values.optString(i)) }
                    }.orEmpty()
                    val comments = item.optJSONArray("comments")?.let { values ->
                        buildList {
                            for (i in 0 until values.length()) {
                                val value = values.optJSONObject(i) ?: continue
                                val text = value.optString("content").trim()
                                if (text.isNotBlank()) add(
                                    MomentComment(
                                        id = value.optString("id").ifBlank { UUID.randomUUID().toString() },
                                        characterId = value.optString("characterId"),
                                        content = text,
                                        createdAt = value.optString("createdAt").toMomentInstantOrNow(),
                                        replyToCommentId = value.optString("replyToCommentId").takeIf(String::isNotBlank),
                                        replyToCharacterId = value.optString("replyToCharacterId").takeIf(String::isNotBlank),
                                    ),
                                )
                            }
                        }
                    }.orEmpty()
                    add(
                        MomentPost(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            authorType = runCatching { MomentAuthorType.valueOf(item.optString("authorType")) }.getOrDefault(MomentAuthorType.User),
                            authorCharacterId = item.optString("authorCharacterId").takeIf(String::isNotBlank),
                            content = content,
                            createdAt = item.optString("createdAt").toMomentInstantOrNow(),
                            likedCharacterIds = likes,
                            comments = comments,
                            imageUri = imageUri,
                            imageDescription = item.optString("imageDescription").trim(),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}

private fun String.toMomentInstantOrNow(): Instant = runCatching { Instant.parse(this) }.getOrDefault(Instant.now())
