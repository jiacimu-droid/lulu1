package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class VoiceDeliveryLedgerTest {
    @Test fun interruptionDoesNotCommitUnplayedContent() {
        val ledger = VoiceDeliveryLedger()
        val turn = ledger.reset()
        ledger.generated(turn, 1, "完整回复尚未播放")
        ledger.interrupt(turn, 1)
        assertTrue(ledger.played(turn).isEmpty())
        assertEquals("只听到了前半句", ledger.corrected(turn, 1, "只听到了前半句"))
    }
    @Test fun lateResultCannotEnterNewSession() {
        val ledger = VoiceDeliveryLedger()
        val old = ledger.reset()
        val current = ledger.reset()
        ledger.generated(old, 1, "迟到的旧回复")
        assertTrue(ledger.played(current).isEmpty())
        assertNull(ledger.corrected(old, 1, "迟到修正"))
    }
    @Test fun completedSpeechCommitsExactlyOnce() {
        val ledger = VoiceDeliveryLedger()
        val turn = ledger.reset()
        ledger.generated(turn, 8, "真正播放完成")
        assertEquals(mapOf(8 to "真正播放完成"), ledger.played(turn))
        assertTrue(ledger.played(turn).isEmpty())
    }
    @Test fun cancelledResponseCannotBeRevivedByLateGeneration() {
        val ledger = VoiceDeliveryLedger()
        val turn = ledger.reset()
        ledger.generated(turn, 3, "旧回复")
        ledger.interrupt(turn, 3)
        ledger.generated(turn, 3, "迟到的完整旧回复")
        assertTrue(ledger.played(turn).isEmpty())
    }
}
