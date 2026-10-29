package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * QQ-like Moments social ACL. Being acquainted, in the same group or at the
 * same location is NOT the same as being friends.
 *
 * The owner explicitly owns newly created companion chat contacts. Existing
 * non-resident roles therefore remain the owner's friends by default for
 * backward compatibility; removing one persists a negative override.
 * Native residents and character-to-character pairs require an explicit
 * confirmed link. A link grants both parties view/like/comment access.
 */
object MomentFriendAccess {
    const val OWNER = "user"
    private const val PREFS = "lulu_moment_friend_access_v1"
    private const val LINKS = "confirmed_v1"
    private const val OWNER_REMOVED = "owner_removed_v1"
    private var prefs: android.content.SharedPreferences? = null
    private val changed = MutableStateFlow(0L)
    val revisions = changed.asStateFlow()

    @Synchronized fun initialize(context: Context) {
        if (prefs == null) prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun pair(a: String, b: String): String =
        listOf(a, b).sorted().joinToString("\u001f")

    /** Tests and other policy callers can validate rules without Android. */
    internal fun allowed(a: String, b: String, links: Set<String>,
        removedOwnerContacts: Set<String>, companionIds: Set<String>): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true
        if (pair(a, b) in links) return true
        if (a == OWNER && b in companionIds && b !in removedOwnerContacts) return true
        if (b == OWNER && a in companionIds && a !in removedOwnerContacts) return true
        return false
    }

    private fun ownerCompanions(): Set<String> =
        MigratedDomainStores.characters.settings.value.keys
            .filterNot { it.startsWith("resident:") }.toSet()

    @Synchronized fun areFriends(a: String, b: String): Boolean =
        allowed(a, b, prefs?.getStringSet(LINKS, emptySet()).orEmpty(),
            prefs?.getStringSet(OWNER_REMOVED, emptySet()).orEmpty(), ownerCompanions())

    fun setFriends(a: String, b: String, friend: Boolean): Boolean {
        val committed = synchronized(this) {
            if (prefs == null || a.isBlank() || b.isBlank() || a == b ||
                (a != OWNER && a !in MigratedDomainStores.characters.settings.value) ||
                (b != OWNER && b !in MigratedDomainStores.characters.settings.value)) {
                return@synchronized false
            }
            val links = prefs?.getStringSet(LINKS, emptySet()).orEmpty().toMutableSet()
            val blocked = prefs?.getStringSet(OWNER_REMOVED, emptySet()).orEmpty().toMutableSet()
            if (friend) links += pair(a, b) else links -= pair(a, b)
            val companion = when {
                a == OWNER && b in ownerCompanions() -> b
                b == OWNER && a in ownerCompanions() -> a
                else -> null
            }
            if (companion != null) {
                if (friend) blocked -= companion else blocked += companion
            }
            val saved = prefs?.edit()?.putStringSet(LINKS, links)
                ?.putStringSet(OWNER_REMOVED, blocked)?.commit() == true
            if (saved) changed.value += 1
            saved
        }
        if (!committed) return false
        // Never acquire MomentsStore while holding the friendship monitor:
        // Moments mutation checks visibility in the reverse lock order.
        MomentsStore.refreshOwnerUnreadVisibility()
        if (!friend) {
            if (a != OWNER) MomentsStore.scrubHiddenTimeline(a)
            if (b != OWNER) MomentsStore.scrubHiddenTimeline(b)
        }
        return true
    }

    /** Facts for the role to weigh when deciding if it wants to share.
     * Audience affects choices, never commands an automatic publication.
     */
    fun audienceContext(characterId: String): String {
        val friends = MigratedDomainStores.characters.settings.value.values
            .filter { it.characterId != characterId && areFriends(characterId, it.characterId) }
            .map { it.displayName }.take(12)
        val owner = if (areFriends(characterId, OWNER)) listOf("主人") else emptyList()
        return buildString {
            append("【真实朋友圈好友可见范围】")
            append((owner + friends).joinToString("、").ifBlank { "目前没有已确认的好友" })
            append("。只有以上确认好友能阅读、点赞和评论；能看到对作者所有的点赞评论。")
            append("知道谁会看到是选择分享的参考，不强制你发布，也不保证有人互动。")
        }
    }

    fun canView(post: MomentPost, viewer: String): Boolean {
        val author = if (post.authorType == MomentAuthorType.User)
            OWNER else post.authorCharacterId.orEmpty()
        return areFriends(author, viewer)
    }

    /** With author access, all likes/comments are visible, not just mutuals. */
    fun visiblePosts(viewer: String, posts: List<MomentPost>): List<MomentPost> =
        posts.filter { canView(it, viewer) }
}
