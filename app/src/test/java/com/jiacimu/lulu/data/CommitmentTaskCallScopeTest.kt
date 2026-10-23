package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class CommitmentTaskCallScopeTest {
    @Test fun combinedMidnightRemindersAndSameCallHangupMustNotDialAgain() {
        val accidental = CommitmentTask(
            characterId = "role",
            goal = "每天晚上十二点主动监督睡觉，今晚02:35主动挂断电话",
            deliveryAction = "start_call",
        )
        assertTrue(accidental.isMisroutedLiveHangup())
    }

    @Test fun genuineTomorrowCallsAndNightlySupervisionRemainSchedulable() {
        assertFalse(CommitmentTask(characterId = "role",
            goal = "每天晚上十二点主动来电提醒休息", deliveryAction = "start_call")
            .isMisroutedLiveHangup())
        assertFalse(CommitmentTask(characterId = "role",
            goal = "明天早晨八点电话叫醒", deliveryAction = "start_call")
            .isMisroutedLiveHangup())
    }
}
