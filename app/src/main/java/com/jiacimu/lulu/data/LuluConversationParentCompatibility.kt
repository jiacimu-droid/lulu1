package com.jiacimu.lulu.data

/**
 * Lulu1 currently stores only root private/group conversations. Older feature code still checks
 * this field to exclude child conversations, so expose the absent legacy value as null until
 * threaded conversations are reintroduced in the domain model.
 */
internal val LuluConversation.parentConversationId: String?
    get() = null
