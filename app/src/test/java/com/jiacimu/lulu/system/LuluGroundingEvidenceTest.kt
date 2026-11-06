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
}
