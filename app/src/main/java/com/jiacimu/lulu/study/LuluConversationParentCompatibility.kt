package com.jiacimu.lulu.study

import com.jiacimu.lulu.data.LuluConversation

/** Lulu1 does not currently persist child conversations; every stored conversation is a root. */
internal val LuluConversation.parentConversationId: String?
    get() = null
