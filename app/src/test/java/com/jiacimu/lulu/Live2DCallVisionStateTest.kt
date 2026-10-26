package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Live2DCallVisionStateTest {
    @Before fun reset() { Live2DCallVisionState.enable("role", "call") }

    @Test fun frameAgeIncludesTimeSpentWaitingForVision() {
        val frame = Live2DCallVisionState.beginFrame("role", "call", 1_000)!!
        assertTrue(Live2DCallVisionState.accept(frame, "桌上有杯子", 11_000))
        assertTrue(Live2DCallVisionState.turnContext("role", "call", 12_000).contains("约11秒前"))
        assertTrue(Live2DCallVisionState.turnContext("role", "call", 91_001).contains("已过期"))
    }

    @Test fun expiredResponseCannotBecomeFreshEvidence() {
        val frame = Live2DCallVisionState.beginFrame("role", "call", 1_000)!!
        assertFalse(Live2DCallVisionState.accept(frame, "旧画面", 91_001))
        assertFalse(Live2DCallVisionState.turnContext("role", "call", 91_001).contains("旧画面"))
        assertTrue(Live2DCallVisionState.accept(frame, "边界画面", 91_000))
    }

    @Test fun invalidClockCannotProduceObservation() {
        assertNull(Live2DCallVisionState.beginFrame("role", "call", -1))
        val frame = Live2DCallVisionState.beginFrame("role", "call", 1_000)!!
        assertFalse(Live2DCallVisionState.accept(frame, "未来画面", 999))
        assertFalse(Live2DCallVisionState.accept(frame, " ", 1_001))
    }

    @Test fun reshareSameCallCannotAcceptOldRequest() {
        val old = Live2DCallVisionState.beginFrame("role", "call", 1_000)!!
        Live2DCallVisionState.disable("role", "call")
        Live2DCallVisionState.enable("role", "call")
        assertFalse(Live2DCallVisionState.accept(old, "上次共享的画面", 2_000))
        val fresh = Live2DCallVisionState.beginFrame("role", "call", 2_000)!!
        assertTrue(Live2DCallVisionState.accept(fresh, "新共享", 3_000))
    }

    @Test fun olderAndDuplicateCompletionsCannotOverwriteLatestFrame() {
        val old = Live2DCallVisionState.beginFrame("role", "call", 1_000)!!
        val fresh = Live2DCallVisionState.beginFrame("role", "call", 2_000)!!
        assertTrue(Live2DCallVisionState.accept(fresh, "新杯子", 3_000))
        assertFalse(Live2DCallVisionState.accept(old, "旧杯子", 4_000))
        assertFalse(Live2DCallVisionState.accept(fresh, "重复覆盖", 4_000))
        val context = Live2DCallVisionState.turnContext("role", "call", 4_000)
        assertTrue(context.contains("新杯子"))
        assertFalse(context.contains("旧杯子"))
        assertFalse(context.contains("重复覆盖"))
    }

    @Test fun oldCallCannotWriteOrDisableDifferentRoleCall() {
        val old = Live2DCallVisionState.beginFrame("role", "call", 1_000)!!
        Live2DCallVisionState.enable("other", "other-call")
        Live2DCallVisionState.disable("role", "call")
        assertTrue(Live2DCallVisionState.isEnabled("other", "other-call"))
        assertFalse(Live2DCallVisionState.accept(old, "串线", 2_000))
        assertNull(Live2DCallVisionState.beginFrame("role", "call", 2_000))
        assertEquals("", Live2DCallVisionState.turnContext("role", "call", 2_000))
    }
}
