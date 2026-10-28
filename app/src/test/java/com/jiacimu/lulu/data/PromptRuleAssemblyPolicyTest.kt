package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class PromptRuleAssemblyPolicyTest {
    @Test fun identicalRuleIdIsEmittedOnceAndOrderIsStable() {
        val text = PromptRuleAssemblyPolicy.assemble(listOf(
            PromptRuleAssemblyPolicy.Rule("character-causal", "Identity and receipts"),
            PromptRuleAssemblyPolicy.Rule("voice", "Voice rules"),
            PromptRuleAssemblyPolicy.Rule("character-causal", "Identity and receipts"),
        ))
        assertEquals("Identity and receipts\n\nVoice rules", text)
    }

    @Test fun twoDifferentRuleOwnersWithIdenticalTextStillRenderOnce() {
        val result = PromptRuleAssemblyPolicy.assemble(listOf(
            PromptRuleAssemblyPolicy.Rule("source-a", "Shared action-and-receipt contract"),
            PromptRuleAssemblyPolicy.Rule("source-b", "Shared action-and-receipt contract"),
            PromptRuleAssemblyPolicy.Rule("source-c", "Unique world constraint"),
        ))
        assertEquals("Shared action-and-receipt contract\n\nUnique world constraint", result)
    }

    @Test fun conflictingRuleVersionsCannotSilentlyOverwriteEachOther() {
        val failure = runCatching {
            PromptRuleAssemblyPolicy.assemble(listOf(
                PromptRuleAssemblyPolicy.Rule("causal", "Original rule"),
                PromptRuleAssemblyPolicy.Rule("causal", "Contradictory rule"),
            ))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("causal"))
    }

    @Test fun unrelatedRulesAreNeverTextSimilarityFiltered() {
        val text = PromptRuleAssemblyPolicy.assemble(listOf(
            PromptRuleAssemblyPolicy.Rule("user-said-yes", "I agreed"),
            PromptRuleAssemblyPolicy.Rule("user-said-no", "I did not agree"),
        ))
        assertTrue(text.contains("I agreed"))
        assertTrue(text.contains("I did not agree"))
    }
}
