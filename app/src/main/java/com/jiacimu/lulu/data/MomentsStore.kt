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
    private var prefs: android.content.SharedPreferences? = null
    private val mutablePosts = MutableStateFlow<List<MomentPost>>(emptyList())
    val posts: StateFlow<List<MomentPost>> = mutablePosts.asStateFlow()

    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mutablePosts.value = decode(prefs?.getString(KEY_POSTS, null))
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
        val name = MigratedDomainStores.characters.get(characterId).displayName
        recordForAllCharacters(post, name)
        return post
    }

    suspend fun letCharactersReact(postId: String) {
        val post = mutablePosts.value.firstOrNull { it.id == postId && it.authorType == MomentAuthorType.User } ?: return
        MigratedDomainStores.characters.settings.value.values.sortedBy { it.displayName }.forEach { character ->
            val result = LuluAiServices.gateway.generate(
                characterId = character.characterId,
                facts = "用户刚刚在朋友圈发布：${post.content}",
                instruction = """
                    你刚刚真实看到了用户发布的这条朋友圈。根据你的人设、你们的关系、连续记忆和当前心情，决定是否点赞、是否评论。
                    不要为了完成任务强行互动；冷淡、疏远或不感兴趣时可以都不做。评论必须像真实朋友圈评论，简短自然，不写角色名标签。
                    只返回 JSON：{"like":true或false,"comment":"评论内容或空字符串"}
                """.trimIndent(),
                source = "朋友圈互动",
                title = "${character.displayName}浏览朋友圈",
                temperature = 0.75,
                maxTokens = 260,
                usage = ModelUsage.Chat,
            ).getOrNull() ?: return@forEach
            val json = parseJsonObject(result.text) ?: return@forEach
            val like = json.optBoolean("like", false)
            val comment = json.optString("comment").trim().take(300)
            mutate { current ->
                current.map { item ->
                    if (item.id != postId) item else item.copy(
                        likedCharacterIds = if (like) item.likedCharacterIds + character.characterId else item.likedCharacterIds,
                        comments = if (comment.isBlank() || item.comments.any { it.characterId == character.characterId }) {
                            item.comments
                        } else {
                            item.comments + MomentComment(characterId = character.characterId, content = comment)
                        },
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

    fun delete(postId: String) {
        val existed = mutablePosts.value.any { it.id == postId }
        if (!existed) return
        mutate { current -> current.filterNot { it.id == postId } }
        MigratedDomainStores.characters.settings.value.keys.forEach { characterId ->
            SharedExperienceTimeline.deleteEvent("moment-$postId-$characterId")
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

    private fun parseJsonObject(raw: String): JSONObject? {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        return if (start >= 0 && end > start) runCatching { JSONObject(clean.substring(start, end + 1)) }.getOrNull() else null
    }
}

private fun String.toMomentInstantOrNow(): Instant = runCatching { Instant.parse(this) }.getOrDefault(Instant.now())
