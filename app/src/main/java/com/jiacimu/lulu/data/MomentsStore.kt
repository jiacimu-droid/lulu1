package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

data class MomentPost(
    val id: String = UUID.randomUUID().toString(),
    val authorType: MomentAuthorType,
    val authorCharacterId: String? = null,
    val content: String,
    val createdAt: Instant = Instant.now(),
    val likedCharacterIds: Set<String> = emptySet(),
    val comments: List<MomentComment> = emptyList(),
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

    fun publishUser(content: String): MomentPost? {
        val clean = content.trim()
        if (clean.isBlank()) return null
        val post = MomentPost(authorType = MomentAuthorType.User, content = clean.take(2_000))
        savePost(post)
        recordForAllCharacters(post, UserProfileContext.displayLabel())
        return post
    }

    fun publishCharacter(characterId: String, content: String): MomentPost? {
        val clean = content.trim()
        if (characterId.isBlank() || clean.isBlank()) return null
        val post = MomentPost(
            authorType = MomentAuthorType.Character,
            authorCharacterId = characterId,
            content = clean.take(2_000),
        )
        savePost(post)
        addUnreadCharacterPost(post.id)
        val name = MigratedDomainStores.characters.get(characterId).displayName
        recordForAllCharacters(post, name)
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
                content = "给${UserProfileContext.displayLabel()}的朋友圈点了赞：${post.content.take(240)}",
                occurredAt = Instant.now(),
            )

            val generated = LuluAiServices.gateway.generate(
                characterId = character.characterId,
                facts = "用户刚刚在朋友圈发布：${post.content}",
                instruction = """
                    你刚刚真实看到了用户发布的这条朋友圈。你必须留下一条符合你人设、关系、连续记忆和当前心情的朋友圈评论。
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
        }
        return comment
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
        MigratedDomainStores.characters.settings.value.keys.forEach { characterId ->
            SharedExperienceTimeline.record(
                eventId = "moment-${post.id}-$characterId",
                characterId = characterId,
                channel = "朋友圈",
                speaker = authorName,
                content = post.content,
                occurredAt = post.createdAt,
            )
        }
    }

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
                put("likedCharacterIds", JSONArray(post.likedCharacterIds.toList()))
                put("comments", JSONArray().apply {
                    post.comments.forEach { comment ->
                        put(JSONObject().put("id", comment.id).put("characterId", comment.characterId)
                            .put("content", comment.content).put("createdAt", comment.createdAt.toString()))
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
                    if (content.isBlank()) continue
                    val likes = item.optJSONArray("likedCharacterIds")?.let { values ->
                        buildSet { for (i in 0 until values.length()) add(values.optString(i)) }
                    }.orEmpty()
                    val comments = item.optJSONArray("comments")?.let { values ->
                        buildList {
                            for (i in 0 until values.length()) {
                                val value = values.optJSONObject(i) ?: continue
                                val text = value.optString("content").trim()
                                if (text.isNotBlank()) add(MomentComment(
                                    id = value.optString("id").ifBlank { UUID.randomUUID().toString() },
                                    characterId = value.optString("characterId"),
                                    content = text,
                                    createdAt = value.optString("createdAt").toMomentInstantOrNow(),
                                ))
                            }
                        }
                    }.orEmpty()
                    add(MomentPost(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        authorType = runCatching { MomentAuthorType.valueOf(item.optString("authorType")) }.getOrDefault(MomentAuthorType.User),
                        authorCharacterId = item.optString("authorCharacterId").takeIf(String::isNotBlank),
                        content = content,
                        createdAt = item.optString("createdAt").toMomentInstantOrNow(),
                        likedCharacterIds = likes,
                        comments = comments,
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }
}

private fun String.toMomentInstantOrNow(): Instant = runCatching { Instant.parse(this) }.getOrDefault(Instant.now())
