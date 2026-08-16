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
    private const val SOCIAL_BATCH_CHARACTER_ID = "__moments_social_batch__"

    private data class ReactionPlan(
        val characterId: String,
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
        socialScope.launch { letCharactersReact(post.id) }
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

    /**
     * Public Moments use one ensemble model request, but every candidate character must leave one
     * in-character comment. Likes remain optional. This keeps the user's requested full participation
     * without paying for one model request per character.
     */
    suspend fun letCharactersReact(postId: String) {
        val post = mutablePosts.value.firstOrNull { it.id == postId && it.authorType == MomentAuthorType.User } ?: return
        val candidates = MigratedDomainStores.characters.settings.value.values.sortedBy(CharacterSettings::displayName)
        runBatchReactions(
            post = post,
            candidates = candidates,
            authorName = UserProfileContext.displayLabel(),
        )
    }

    private suspend fun letOtherCharactersReact(postId: String) {
        val post = mutablePosts.value.firstOrNull {
            it.id == postId && it.authorType == MomentAuthorType.Character && !it.authorCharacterId.isNullOrBlank()
        } ?: return
        val authorId = post.authorCharacterId ?: return
        val author = MigratedDomainStores.characters.get(authorId)
        val candidates = MigratedDomainStores.characters.settings.value.values
            .filterNot { it.characterId == authorId }
            .sortedBy(CharacterSettings::displayName)
        runBatchReactions(post = post, candidates = candidates, authorName = author.displayName)
    }

    private suspend fun runBatchReactions(
        post: MomentPost,
        candidates: List<CharacterSettings>,
        authorName: String,
    ) {
        if (candidates.isEmpty()) return
        val validIds = candidates.mapTo(linkedSetOf(), CharacterSettings::characterId)
        val detailScale = if (candidates.size <= 8) 1f else 0.58f
        fun scaled(value: Int): Int = (value * detailScale).toInt().coerceAtLeast(160)
        val socialRecallQuery = buildString {
            appendLine(authorName)
            appendLine(momentContext(post))
        }
        val candidateMemoryContexts = buildMap {
            candidates.forEach { character ->
                put(
                    character.characterId,
                    UnifiedMemoryOrchestrator.assemble(
                        characterId = character.characterId,
                        query = socialRecallQuery,
                        recallLimit = 6,
                        evidenceLimit = 4,
                        evidenceCharacterBudget = scaled(1_000),
                        recentLimit = 6,
                        recentCharacterBudget = scaled(1_100),
                    ),
                )
            }
        }

        val raw = LuluAiServices.gateway.generate(
            characterId = SOCIAL_BATCH_CHARACTER_ID,
            facts = buildString {
                appendLine("【朋友圈原帖】")
                appendLine("作者：$authorName")
                appendLine("作者类型：${if (post.authorType == MomentAuthorType.User) "用户" else "角色"}")
                appendLine(momentContext(post))
                appendLine()
                appendLine("【本轮必须全部评论的角色】")
                candidates.forEach { character ->
                    val identity = CharacterIdentityStore.get(character.characterId)
                    val memoryContext = candidateMemoryContexts[character.characterId]
                    val presence = CompanionPresenceStore.current(character.characterId)
                    appendLine("---")
                    appendLine("characterId=${character.characterId}")
                    appendLine("显示名=${character.displayName}")
                    if (identity.isNotBlank()) appendLine("身份与关系=${identity.take(scaled(760))}")
                    appendLine("人设=${character.persona.ifBlank { "按该角色现有人设自然行动。" }.take(scaled(980))}")
                    memoryContext?.compactPromptSection(characterBudget = scaled(1_900))
                        ?.takeIf(String::isNotBlank)
                        ?.let { appendLine(it) }
                    presence?.let {
                        appendLine(
                            "上一刻状态=${it.statusText.take(120)}；动作=${it.gesture.take(120)}；心情=${it.mood.take(80)}；没说出口=${it.innerThought.take(180)}",
                        )
                    }
                }
            },
            instruction = """
                你是朋友圈的一次性整轮社交编排器。必须在这一次请求里替清单中的每一个角色分别写出他本人会留下的评论，不允许跳过任何人，也不要拆成多次模型调用。

                只返回一个 JSON 对象，不要代码块、解释或旁白：
                {"reactions":[{"characterId":"真实角色ID","action":"comment|like_comment","comment":"该角色自己的评论正文"}]}

                规则：
                1. reactions 必须完整覆盖候选清单中的每一个 characterId，每个人恰好出现一次；任何角色都不能 skip，也不能只点赞不评论。
                2. 每个人都必须有非空 comment。点赞可以有也可以没有：想点赞就用 like_comment，不点赞就用 comment。
                3. 虽然所有人都要评论，但绝不能写成整齐报到、统一句式或同一种语气。每条评论都要服从对应角色的身份、关系、人设、近期真实经历和此刻心情。
                4. 角色之间可以对同一条动态关注完全不同的点：有人接梗、有人关心、有人吐槽、有人只写很短的一句。全员评论不等于机械轮班。
                5. 如果有配图描述，那是角色在这条朋友圈中实际可见的图片信息，可以自然回应画面细节；不要编造描述之外的画面。
                6. 评论保持真实社交软件长度，通常一句或几句短话；不写角色名标签，不写 ACTION 标签，不解释规则。
                7. 只根据原帖、候选角色资料和真实经历判断，不要虚构用户此刻未提供的身体、环境或私密设备状态。
            """.trimIndent(),
            source = "朋友圈全员整轮互动",
            title = "朋友圈一次性全员评论",
            temperature = 0.9,
            maxTokens = (700 + candidates.size * 180).coerceIn(900, 4_000),
            usage = ModelUsage.Chat,
            contextMode = CompanionContextMode.PersonaAndScenario,
        ).getOrNull()?.text.orEmpty()

        val plans = parseReactionPlans(raw, validIds).associateBy(ReactionPlan::characterId)
        candidates.forEach { character ->
            val plan = plans[character.characterId]
            if (plan?.wantsLike == true) addCharacterLike(post, character, authorName)
            val comment = plan?.comment.orEmpty().ifBlank { fallbackMomentComment(character) }
            addCharacterTopLevelComment(post, character, authorName, comment)
        }
    }

    private fun parseReactionPlans(raw: String, validIds: Set<String>): List<ReactionPlan> = runCatching {
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
        val reactions = JSONObject(clean).optJSONArray("reactions") ?: return@runCatching emptyList()
        buildList {
            val seen = mutableSetOf<String>()
            for (index in 0 until reactions.length()) {
                val item = reactions.optJSONObject(index) ?: continue
                val characterId = item.optString("characterId").trim()
                if (characterId !in validIds || !seen.add(characterId)) continue
                val action = item.optString("action").trim().lowercase()
                val wantsLike = action == "like_comment" || action == "comment_like" || item.optBoolean("like", false)
                val comment = item.optString("comment")
                    .trim()
                    .removePrefix("评论：")
                    .removeSurrounding("\"")
                    .take(300)
                if (comment.isNotBlank()) add(ReactionPlan(characterId, wantsLike, comment))
            }
        }
    }.getOrDefault(emptyList())

    private fun fallbackMomentComment(character: CharacterSettings): String {
        val persona = character.persona
        return when {
            listOf("寡言", "冷淡", "克制", "内敛").any(persona::contains) -> "嗯，看到了。"
            listOf("活泼", "开朗", "元气", "爱闹").any(persona::contains) -> "我看到啦。"
            else -> "看到了。"
        }
    }

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
                这是朋友圈评论区里正在发生的真实一对一回复。请只以你自己的口吻接住用户刚刚对你的评论或回复。
                如果原帖不是你发的，也不要把原帖说成自己的；你只是正在那个评论区里继续和用户说话。
                回复要像真实朋友圈评论区：短、自然、符合你和用户的关系，可以接梗、回答、反问或只回一句，不要扩写成聊天长文。
                不替其他角色说话，不写角色名，不写“回复：”标签，不解释规则，只输出你真正要发出的回复正文。
            """.trimIndent(),
            source = "朋友圈单独回复",
            title = "${responder.displayName}回复朋友圈评论",
            temperature = 0.86,
            maxTokens = 180,
            usage = ModelUsage.Chat,
        ).getOrNull()?.text
            ?.trim()
            ?.removeSurrounding("\"")
            ?.take(300)
            .orEmpty()
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
