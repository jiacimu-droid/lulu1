package com.jiacimu.lulu

/** Calls are not inherently important memories. Conversation transcripts
 * remain available independently of automatic long-term memory extraction. */
internal object CallMemoryPolicy {
    const val DUPLICATE_FULL_CALL_AS_STRONG_MEMORY = false
    const val TRANSCRIPT_STILL_AVAILABLE = true
    const val IMPORTANT_DETAILS_STILL_EXTRACTABLE = true
}
