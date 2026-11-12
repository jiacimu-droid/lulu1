package com.jiacimu.lulu.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGenerationActivityPhaseTest {
    @Test fun completingModelPhaseStopsTypingWithoutDiscardingLiveToolLease() = runBlocking {
        val conversation = "lease-phase-test"
        ChatGenerationActivity.duringWithLease("lease-role", setOf(conversation)) { leaseId ->
            assertTrue(ChatGenerationActivity.activities.value.getValue(leaseId).awaitingModel)
            assertTrue(ChatGenerationActivity.isRunning(conversation))
            ChatGenerationActivity.markModelCompleted(leaseId)
            assertFalse(ChatGenerationActivity.activities.value.getValue(leaseId).awaitingModel)
            assertTrue(ChatGenerationActivity.isRunning(conversation))
        }
        assertFalse(ChatGenerationActivity.isRunning(conversation))
    }

    @Test fun exceptionReleasesBothTypingAndWorkOwnership() = runBlocking {
        val conversation = "lease-failure-test"
        runCatching {
            ChatGenerationActivity.duringWithLease("lease-role", setOf(conversation)) { _ ->
                throw IllegalStateException("request failed")
            }
        }
        assertFalse(ChatGenerationActivity.isRunning(conversation))
    }
}
