package com.jiacimu.lulu.ai

/** Actual dialogue turns, distinct from instructions, private state and tool receipts. */
data class ModelConversationMessage(val role: Role, val content: String) {
    enum class Role(val wire: String) { USER("user"), ASSISTANT("assistant") }
}
