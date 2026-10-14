package com.jiacimu.lulu.data

/**
 * The realtime voice provider may not have the client tool installed.
 * A character's explicit, actually HEARD decision can still be enacted by
 * Lulu on the same phone call against the last verified user message.
 *
 * This is deliberately much stricter than semantic "wants to remember":
 * hypothetical, later, quoted and conditional intentions never run tools.
 */
internal object RealtimePhoneFavoritePolicy {
    private val decidedNow = Regex(
        """(?:我|这句|这条)(?:现在|这就|马上|立刻|要|想)?(?:把|将)?(?:你(?:刚才)?(?:说的|发的|那句|这句|的话)|这句(?:话)?|这条(?:消息)?).{0,7}(?:收藏起来|收藏了|存进辞海|收进辞海)|我(?:现在|这就|马上|立刻)(?:把)?(?:这句|这条|你(?:刚才)?那句).{0,7}(?:收藏|收起来)"""
    )
    private val excluded = Regex(
        """(?:等|待会|回头|过会|以后|改天|挂(?:断|了)|下次|先不|不打算|不想|不能|没法|还没|如果|假如|要是|或许|可能|试试|考虑|可以|能不能|要不要).{0,22}(?:收藏|收起来)|(?:收藏|收起来).{0,14}(?:以后|再说|回头|等会|吗|？|\\?)"""
    )

    fun decidedToSaveNow(spoken: String): Boolean {
        val normalized = spoken.replace(Regex("""\\s+"""), "")
        if (normalized.length !in 5..260 || excluded.containsMatchIn(normalized)) return false
        return decidedNow.containsMatchIn(normalized)
    }
}
