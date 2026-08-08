package com.jiacimu.lulu.data

import android.content.Context
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

    /** User posts are social events: every existing character likes and leaves one in-character comment. */
    suspend fun letCharactersReact(postId: String) {
        val post = mutablePosts.value.firstOrNull { it.id == postId && it.authorType == MomentAuthorType.User } ?: return
        MigratedDomainStores.characters.settings.value.values.sortedBy { it.displayName }.forEach { character ->
            mutate { current ->
                current.map { item ->
                    if (item.id != postId) item else item.copy(
                        likedCharacterIds = item.likedCharacterIds + character.characterId,
                    )
                }
            }
            SharedExperienceTimeline.record(
                eventId = "moment-like-$postId-${character.characterId}",
                characterId = character.characterId,
                channel = "朋友圈",
                speaker = character.displayName,
                content = "给${UserProfileContext.displayLabel()}的朋友圈点了赞：${momentContext(post).take(420)}",
                occurredAt = Instant.now(),
            )

            val generated = LuluAiServices.gateway.generate(
                characterId = character.characterId,
                facts = "用户刚刚在朋友圈发布：${momentContext(post)}",
                instruction = """
                    你刚刚真实看到了用户发布的这条朋友圈。你必须留下一条符合你人设、关系、连续记忆和当前心情的朋友圈评论。
                    如果动态带图片，图片描述就是你实际看到的画面信息，可以自然针对画面细节评论。
                    评论要像真实社交软件里的短评论，自然、有区分度，不写角色名标签，不解释规则，不输出 JSON，只输出评论正文。
                """.trimIndent(),
                source = "朋友圈互动",
                title = "${character.displayName}评论朋友圈",
                temperature = 0.86,
                maxTokens = 180,
                usage = ModelUsage.Chat,
            ).getOrNull()?.text
                ?.trim()
                ?.removeSurrounding("\"")
                ?.take(300)
                .orEmpty()
                .ifBlank { "看到了。" }

            val comment = MomentComment(characterId = character.characterId, content = generated)
            mutate { current ->
                current.map { item ->
                    if (item.id != postId || item.comments.any { it.characterId == character.characterId }) item
                    else item.copy(comments = item.comments + comment)
                }
            }
            SharedExperienceTimeline.record(
                eventId = "moment-comment-${comment.id}-${character.characterId}",
                characterId = character.characterId,
                channel = "朋友圈",
                speaker = character.displayName,
                content = "评论了${UserProfileContext.displayLabel()}的朋友圈：${comment.content}",
                occurredAt = comment.createdAt,
            )
        }
    }

    private suspend fun letOtherCharactersReact(postId: String) {
        val post = mutablePosts.value.firstOrNull {
            it.id == postId && it.authorType == MomentAuthorType.Character && !it.authorCharacterId.isNullOrBlank()
        } ?: return
        val authorId = post.authorCharacterId ?: return
        val author = MigratedDomainStores.characters.get(authorId)
        val candidates = MigratedDomainStores.characters.settings.value.values
            .filterNot { it.characterId == authorId }
            .sortedBy { "$postId:${it.characterId}".hashCode() }
            .take(5)

        candidates.forEach { character ->
            val raw = LuluAiServices.gateway.generate(
                characterId = character.characterId,
                facts = buildString {
                    appendLine("${author.displayName}刚刚发了一条朋友圈：${post.content}")
                    if (post.imageDescription.isNotBlank()) appendLine("配图内容：${post.imageDescription}")
                },
                instruction = """
                    你正在刷朋友圈，刚看见另一个真实角色发的动态。先按你们的关系、你的性格和此刻心情决定是否互动，不需要为了礼貌每条都互动。
                    第一行只能写 ACTION=SKIP、ACTION=LIKE、ACTION=COMMENT 或 ACTION=LIKE_COMMENT 之一。
                    如果包含 COMMENT，第二行起只写一条你真正会留下的短评论；不要解释规则，不要替对方说话。
                """.trimIndent(),
                source = "朋友圈角色社交",
                title = "${character.displayName}看到${author.displayName}的朋友圈",
                temperature = 0.82,
                maxTokens = 220,
                usage = ModelUsage.Chat,
            ).getOrNull()?.text.orEmpty().trim()
            if (raw.isBlank()) return@forEach
            val lines = raw.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
            val action = lines.firstOrNull().orEmpty().uppercase()
            if (action.contains("SKIP")) return@forEach
            val wantsLike = action.contains("LIKE")
            val wantsComment = action.contains("COMMENT")
            if (wantsLike) {
                mutate { current ->
                    current.map { item ->
                        if (item.id == postId) item.copy(likedCharacterIds = item.likedCharacterIds + character.characterId) else item
                    }
                }
                SharedExperienceTimeline.record(
                    eventId = "moment-like-$postId-${character.characterId}",
                    characterId = character.characterId,
                    channel = "朋友圈",
                    speaker = character.displayName,
                    content = "给${author.displayName}的朋友圈点了赞：${momentContext(post).take(420)}",
                    occurredAt = Instant.now(),
                )
            }
            if (wantsComment) {
                val text = lines.drop(1).joinToString("\n")
                    .removePrefix("COMMENT=")
                    .removePrefix("评论：")
                    .trim()
                    .removeSurrounding("\"")
                    .take(300)
                if (text.isNotBlank()) {
                    val comment = MomentComment(characterId = character.characterId, content = text)
                    mutate { current ->
                        current.map { item ->
                            if (item.id != postId || item.comments.any { it.characterId == character.characterId }) item
                            else item.copy(comments = item.comments + comment)
                        }
                    }
                    SharedExperienceTimeline.record(
                        eventId = "moment-comment-${comment.id}-${character.characterId}",
                        characterId = character.characterId,
                        channel = "朋友圈",
                        speaker = character.displayName,
                        content = "评论了${author.displayName}的朋友圈：${comment.content}",
                        occurredAt = comment.createdAt,
                    )
                }
            }
        }
    }

    fun toggleUserLike(postId: String, characterId: String = "__user__") {
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
        val comment = MomentComment(characterId = "__user__", content = clean)
        mutate { current ->
            current.map { item -> if (item.id == postId) item.copy(comments = item.comments + comment) else item }
        }
        post.authorCharacterId?.let { authorId ->
            SharedExperienceTimeline.record(
                eventId = "moment-user-comment-${comment.id}-$authorId",
                characterId = authorId,
                channel = "朋友圈",
                speaker = UserProfileContext.displayLabel(),
                content = "评论了你的朋友圈：$clean",
                occurredAt = comment.createdAt,
            )
            socialScope.launch { replyToUserComment(postId, comment.id, authorId) }
        }
        return comment
    }

    private suspend fun replyToUserComment(postId: String, commentId: String, authorId: String) {
        val post = mutablePosts.value.firstOrNull { it.id == postId } ?: return
        val userComment = post.comments.firstOrNull { it.id == commentId && it.characterId == "__user__" } ?: return
        if (post.comments.any { it.characterId == authorId && it.replyToCommentId == commentId }) return
        val author = MigratedDomainStores.characters.get(authorId)
        val replyText = LuluAiServices.gateway.generate(
            characterId = authorId,
            facts = buildString {
                appendLine("你刚刚发的朋友圈：${post.content}")
                if (post.imageDescription.isNotBlank()) appendLine("你朋友圈里的配图内容：${post.imageDescription}")
                appendLine("${UserProfileContext.displayLabel()}刚刚在这条朋友圈下面评论：${userComment.content}")
            },
            instruction = """
                这是发生在朋友圈评论区里的真实互动。请以你自己的口吻回复用户刚刚对你这条朋友圈的评论。
                回复要像真实朋友圈评论区：短、自然、符合你和用户的关系；可以接梗、回答、反问或只回一句，不要写成聊天长文。
                不写角色名，不写“回复：”标签，不解释规则，只输出你要发出的回复正文。
            """.trimIndent(),
            source = "朋友圈回复",
            title = "${author.displayName}回复朋友圈评论",
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
            characterId = authorId,
            content = replyText,
            replyToCommentId = commentId,
            replyToCharacterId = "__user__",
        )
        mutate { current ->
            current.map { item ->
                if (item.id != postId || item.comments.any { it.characterId == authorId && it.replyToCommentId == commentId }) item
                else item.copy(comments = item.comments + reply)
            }
        }
        SharedExperienceTimeline.record(
            eventId = "moment-comment-${reply.id}-$authorId",
            characterId = authorId,
            channel = "朋友圈",
            speaker = author.displayName,
            content = "回复了${UserProfileContext.displayLabel()}的朋友圈评论：${reply.content}",
            occurredAt = reply.createdAt,
        )
    }

    fun delete(postId: String) {
        val post = mutablePosts.value.firstOrNull { it.id == postId } ?: return
        mutate { current -> current.filterNot { it.id == postId } }
        removeUnreadCharacterPost(postId)
        MigratedDomainStores.characters.settings.value.keys.forEach { characterId ->
            SharedExperienceTimeline.deleteEvent("moment-$postId-$characterId")
            SharedExperienceTimeline.deleteEvent("moment-like-$postId-$characterId")
        }
        post.comments.forEach { comment ->
            if (comment.characterId == "__user__") {
                post.authorCharacterId?.let { authorId ->
                    SharedExperienceTimeline.deleteEvent("moment-user-comment-${comment.id}-$authorId")
                }
            } else {
                SharedExperienceTimeline.deleteEvent("moment-comment-${comment.id}-${comment.characterId}")
            }
        }
    }

    /** Clears authored posts plus this character's likes/comments from other posts. */
    fun clearCharacterData(characterId: String) {
        if (characterId.isBlank()) return
        mutablePosts.value.filter { it.authorCharacterId == characterId }.map(MomentPost::id).forEach(::delete)
        val reactions = mutablePosts.value.flatMap { post ->
            post.comments.filter { it.characterId == characterId }.map { comment -> post.id to comment }
        }
        mutate { current ->
            current.map { post ->
                post.copy(
                    likedCharacterIds = post.likedCharacterIds - characterId,
                    comments = post.comments.filterNot { it.characterId == characterId },
                )
            }
        }
        mutablePosts.value.forEach { post -> SharedExperienceTimeline.deleteEvent("moment-like-${post.id}-$characterId") }
        reactions.forEach { (_, comment) ->
            SharedExperienceTimeline.deleteEvent("moment-comment-${comment.id}-$characterId")
        }
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
