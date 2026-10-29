package com.jiacimu.lulu.data

/** Projection over the existing Moments post authority, not a parallel notifications database.
 * Historical v1 unread post IDs are retained; new character comments have distinct IDs.
 */
internal object MomentOwnerUnread {
    private const val USER_ID = "__user__"
    private const val COMMENT_PREFIX = "comment:"

    private fun addressedToOwner(post: MomentPost, comment: MomentComment): Boolean =
        comment.characterId != USER_ID &&
            (post.authorType == MomentAuthorType.User ||
                comment.replyToCharacterId == USER_ID)

    private fun commentKey(comment: MomentComment): String = COMMENT_PREFIX + comment.id

    fun reconcile(
        unread: Set<String>,
        posts: List<MomentPost>,
        canView: (MomentPost) -> Boolean,
    ): Set<String> {
        val valid = buildSet {
            posts.filter(canView).forEach { post ->
                if (post.authorType == MomentAuthorType.Character) add(post.id)
                post.comments.filter { addressedToOwner(post, it) }
                    .forEach { add(commentKey(it)) }
            }
        }
        return unread intersect valid
    }

    fun afterMutation(
        before: List<MomentPost>,
        after: List<MomentPost>,
        current: Set<String>,
        canView: (MomentPost) -> Boolean,
    ): Set<String> {
        val beforeById = before.associateBy(MomentPost::id)
        val added = current.toMutableSet()
        after.filter(canView).forEach { post ->
            val old = beforeById[post.id]
            if (old == null && post.authorType == MomentAuthorType.Character) {
                added += post.id
            }
            val priorComments = old?.comments?.mapTo(hashSetOf(), MomentComment::id).orEmpty()
            post.comments.forEach { comment ->
                if (comment.id !in priorComments && addressedToOwner(post, comment)) {
                    added += commentKey(comment)
                }
            }
        }
        return reconcile(added, after, canView)
    }

    /** The given posts are a UI snapshot, not a query for the latest content. */
    fun markViewed(current: Set<String>, observed: List<MomentPost>): Set<String> {
        if (observed.isEmpty()) return current
        val observedKeys = buildSet {
            observed.forEach { post ->
                add(post.id)
                post.comments.forEach { add(commentKey(it)) }
            }
        }
        return current - observedKeys
    }
}
