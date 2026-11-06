package com.jiacimu.lulu.system

import org.junit.Assert.assertEquals
import org.junit.Test

class LuluGroundingEvidenceTest {
    @Test fun missingVerifiedEventNeverBorrowsAnOlderIdenticalMessage() {
        // Two distinct user turns may have exactly the same words. A transport
        // that supplies no verified event ID must not guess the older turn.
        val olderMessageId = "user-turn-001"
        val newerMessageId = "user-turn-002"
        val identicalText = "我现在想读这篇笔记"
        assertEquals("我现在想读这篇笔记", identicalText)
        assertEquals("", LuluDeviceToolBridge.selectGroundingEvidenceId(
            "", false, "", "",
        ))
        assertEquals(newerMessageId, LuluDeviceToolBridge.selectGroundingEvidenceId(
            "", false, "", newerMessageId,
        ))
        assertEquals(olderMessageId, LuluDeviceToolBridge.selectGroundingEvidenceId(
            "", false, "", olderMessageId,
        ))
    }

    @Test fun explicitReconsiderationAndSilenceObservationKeepTheirOwnCause() {
        assertEquals("revision-request", LuluDeviceToolBridge.selectGroundingEvidenceId(
            "revision-request", false, "", "user-turn",
        ))
        assertEquals("silence-observation", LuluDeviceToolBridge.selectGroundingEvidenceId(
            "", true, "silence-observation", "user-turn",
        ))
        assertEquals("", LuluDeviceToolBridge.selectGroundingEvidenceId(
            "", true, "", "user-turn",
        ))
    }
    @Test fun directXhsIdempotencySeparatesIdenticalTextAcrossUnverifiedTurns() {
        // Two calls with identical text but no verified event must not share
        // a cached read. Retrying the same invocation keeps its trace identity.
        val first = LuluDeviceToolBridge.directXhsRequestId("", "trace-first")
        val second = LuluDeviceToolBridge.directXhsRequestId("", "trace-second")
        assertEquals("direct-xhs-trace-first", first)
        org.junit.Assert.assertNotEquals(first, second)
        assertEquals(first, LuluDeviceToolBridge.directXhsRequestId("", "trace-first"))
        // Once the transport provides a real event, retries with a different
        // diagnostic trace still belong to the same user request.
        assertEquals(
            LuluDeviceToolBridge.directXhsRequestId("event-42", "trace-first"),
            LuluDeviceToolBridge.directXhsRequestId("event-42", "trace-second"),
        )
        org.junit.Assert.assertNotEquals(
            LuluDeviceToolBridge.directXhsRequestId("event-42", "trace-first"),
            LuluDeviceToolBridge.directXhsRequestId("event-43", "trace-first"),
        )
    }

}
