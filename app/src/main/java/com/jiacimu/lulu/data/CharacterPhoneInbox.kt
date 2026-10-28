package com.jiacimu.lulu.data

import android.content.Context
import java.time.Instant

/**
 * Read-only virtual QQ inbox projection for each digital character.
 * The posts themselves remain owned by MomentsStore, friends by
 * MomentFriendAccess, applications by CharacterFriendRequests.
 * Only the character's READ CURSOR lives here. A new post is not an
 * instruction to wake everybody or buy a separate model call.
 */
internal object CharacterPhoneInbox {
    private const val PREFS = "lulu_character_phone_cursors_v1"
    private var prefs: android.content.SharedPreferences? = null

    @Synchronized fun initialize(context: Context) {
        if (prefs == null) prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        MomentsStore.initialize(context)
        CharacterFriendRequests.initialize(context)
    }

    private fun cursorKey(characterId: String) = "moment-seen:$characterId"
    private fun cursorIdKey(characterId: String) = "moment-seen-id:$characterId"

    /**
     * A timestamp alone skips unpresented posts when a capped inbox batch ends
     * in several updates sharing the same Instant. The stable post ID is the
     * tie breaker. Legacy timestamp-only cursors reread their boundary once
     * rather than silently losing a friend's activity.
     */
    internal fun afterCursor(activityAt: Instant, postId: String,
                             seenAt: Instant, seenId: String): Boolean =
        activityAt.isAfter(seenAt) ||
            (activityAt == seenAt && postId > seenId)

    @Synchronized fun unreadMoments(characterId: String, max: Int = 8): List<MomentPost> {
        if (characterId.isBlank()) return emptyList()
        val cursor = prefs?.getString(cursorKey(characterId), null)
            ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH
        val cursorId = prefs?.getString(cursorIdKey(characterId), "").orEmpty()
        return MomentFriendAccess.visiblePosts(characterId, MomentsStore.posts.value)
            .asSequence().filter {
                (it.authorCharacterId != characterId ||
                    it.lastActivityAt.isAfter(it.createdAt)) &&
                    (it.authorType != MomentAuthorType.User || characterId != MomentFriendAccess.OWNER) &&
                    afterCursor(it.lastActivityAt, it.id, cursor, cursorId)
            }.sortedWith(compareBy<MomentPost> { it.lastActivityAt }.thenBy { it.id })
            .take(max.coerceIn(1, 20)).toList()
    }

    @Synchronized fun unreadCount(characterId: String): Int {
        val cursor = prefs?.getString(cursorKey(characterId), null)
            ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH
        val cursorId = prefs?.getString(cursorIdKey(characterId), "").orEmpty()
        return MomentFriendAccess.visiblePosts(characterId, MomentsStore.posts.value).count {
            (it.authorCharacterId != characterId ||
                it.lastActivityAt.isAfter(it.createdAt)) &&
                (it.authorType != MomentAuthorType.User ||
                    characterId != MomentFriendAccess.OWNER) &&
                afterCursor(it.lastActivityAt, it.id, cursor, cursorId)
        }
    }

    /**
     * Validate the exact viewer of a supplied snapshot. Passing another
     * character's saved posts (or a stale/withdrawn post) must not become a
     * shortcut around the friendship ACL when composing model context.
     * New likes/comments may arrive during model thinking; older unchanged
     * fields remain a valid historical view without marking new activity read.
     */
    private fun authorizedSnapshots(characterId: String, posts: List<MomentPost>): List<MomentPost> {
        if (characterId.isBlank() || posts.isEmpty()) return emptyList()
        val latest = MomentsStore.posts.value.associateBy(MomentPost::id)
        return posts.filter { snapshot ->
            val live = latest[snapshot.id] ?: return@filter false
            MomentFriendAccess.canView(live, characterId) &&
                live.authorType == snapshot.authorType &&
                live.authorCharacterId == snapshot.authorCharacterId &&
                live.createdAt == snapshot.createdAt &&
                live.content == snapshot.content &&
                !snapshot.lastActivityAt.isAfter(live.lastActivityAt) &&
                snapshot.comments.all(live.comments::contains) &&
                live.likedCharacterIds.containsAll(snapshot.likedCharacterIds) &&
                (snapshot.imageDescription.isBlank() ||
                    snapshot.imageDescription == live.imageDescription)
        }.distinctBy(MomentPost::id)
    }

    /** Advance only up to posts that were actually presented in a completed
     * model decision. Failed or interrupted perception leaves a red dot.
     * A late callback can never roll the cursor back.
     */
    @Synchronized fun acknowledge(characterId: String, postIds: Collection<String>): Boolean =
        acknowledgeObserved(characterId,
            MomentFriendAccess.visiblePosts(characterId, MomentsStore.posts.value)
                .filter { it.id in postIds })

    /** The actual immutable snapshot shown to the model owns the read receipt.
     * If somebody comments during an API call, that NEW activity remains unread.
     */
    @Synchronized fun acknowledgeObserved(characterId: String, observedPosts: List<MomentPost>): Boolean {
        if (observedPosts.isEmpty()) return true
        val presented = authorizedSnapshots(characterId, observedPosts)
        val observed = presented.maxWithOrNull(
            compareBy<MomentPost> { it.lastActivityAt }.thenBy { it.id },
        ) ?: return true
        val current = prefs?.getString(cursorKey(characterId), null)
            ?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH
        val currentId = prefs?.getString(cursorIdKey(characterId), "").orEmpty()
        if (!afterCursor(observed.lastActivityAt, observed.id, current, currentId)) return true
        val delivered = presented.filter { afterCursor(it.lastActivityAt, it.id, current, currentId) }
        // Only AFTER the successful model decision has looked at this inbox
        // do these posts enter the viewer's actual lived observations.
        delivered.forEach { post ->
            val author = post.authorCharacterId?.let {
                MigratedDomainStores.characters.get(it).displayName
            } ?: UserProfileContext.displayLabel()
            val commentDigest = post.comments.takeLast(5).joinToString("；") { comment ->
                val name = if (comment.characterId == "__user__") UserProfileContext.displayLabel()
                    else MigratedDomainStores.characters.get(comment.characterId).displayName
                "$name：${comment.content.take(120)}"
            }
            SharedExperienceTimeline.record(
                eventId = if (post.authorCharacterId == characterId)
                    "moment-own-activity-${post.id}-$characterId"
                else "moment-${post.id}-$characterId",
                characterId = characterId,
                channel = "手机·已查看朋友圈",
                source = "moment:phone-read",
                speaker = author,
                content = "醒来查看手机，postId=${post.id}；动态：${post.content.take(400)}" +
                    if (commentDigest.isBlank()) "" else "；看到评论：$commentDigest",
                evidenceKind = EventEvidenceKind.Observation,
                triggerExtraction = false,
            )
        }
        return prefs?.edit()
            ?.putString(cursorKey(characterId), observed.lastActivityAt.toString())
            ?.putString(cursorIdKey(characterId), observed.id)
            ?.commit() == true
    }

    fun modelContext(characterId: String, posts: List<MomentPost>): String = buildString {
        appendLine("【自己手机的聊天软件 · 朋友圈小红点】")
        appendLine("本次醒来尚未读过的好友动态=${unreadCount(characterId)}条；下列是真实有权限查看的帖子。")
        authorizedSnapshots(characterId, posts).forEach { post ->
            val authorId = post.authorCharacterId
            val author = authorId?.let { MigratedDomainStores.characters.get(it).displayName }
                ?: UserProfileContext.displayLabel()
            appendLine("postId=${post.id}；作者=${author}；发表于=${post.createdAt}；最新活动=${post.lastActivityAt}")
            appendLine("  正文：${post.content.take(300).ifBlank { "[图片动态]" }}")
            if (post.imageDescription.isNotBlank()) appendLine("  实际配图描述：${post.imageDescription.take(200)}")
            appendLine("  已有点赞=${post.likedCharacterIds.joinToString(",").take(200).ifBlank { "无" }}")
            post.comments.takeLast(6).forEach { c ->
                val name = if (c.characterId == "__user__") UserProfileContext.displayLabel()
                    else MigratedDomainStores.characters.get(c.characterId).displayName
                appendLine("  ${name}：${c.content.take(150)}")
            }
        }
        appendLine("这些是你查看手机时可感知的事实，不代表你已经点赞评论。可以继续原计划并顺手做别的事；选择由你本人决定。")
    }

    fun requestContext(characterId: String): String = buildString {
        val pending = CharacterFriendRequests.pendingFor(characterId)
        appendLine("【聊天软件 · 好友申请】")
        if (pending.isEmpty()) appendLine("没有待处理好友申请")
        else pending.forEach { request ->
            val from = MigratedDomainStores.characters.get(request.fromId).displayName
            appendLine("requestId=${request.id}；${from} (${request.fromId}) 向你申请好友；" +
                "招呼=${request.greeting.ifBlank { "无" }}；是否同意只由你决定。")
        }
    }

    fun summary(characterId: String): String = buildString {
        append("朋友圈未读=${unreadCount(characterId)}；")
        append("好友申请=${CharacterFriendRequests.pendingFor(characterId).size}；")
        append("真正好友=${MigratedDomainStores.characters.settings.value.keys.count {
            it != characterId && MomentFriendAccess.areFriends(characterId, it)
        }}")
    }
}
