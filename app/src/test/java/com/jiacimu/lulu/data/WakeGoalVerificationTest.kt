package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class WakeGoalVerificationTest {
    @Test fun noReplyOrRingtoneNeverCountsAsGoalSuccess() {
        assertFalse(WakeGoalVerification.explicitlyAwake(""))
        assertFalse(WakeGoalVerification.explicitlyAwake("嗯"))
        assertFalse(WakeGoalVerification.explicitlyAwake("怎么啦"))
        assertFalse(WakeGoalVerification.explicitlyAwake("我还没醒"))
        assertFalse(WakeGoalVerification.explicitlyAwake("我醒了，但是又睡过去了，我没醒"))
    }

    @Test fun clearAffirmationIncludesNoMusicPreference() {
        assertTrue(WakeGoalVerification.explicitlyAwake("我醒了"))
        assertTrue(WakeGoalVerification.explicitlyAwake("我醒了，不过别放歌"))
        assertTrue(WakeGoalVerification.explicitlyAwake("我起来啦"))
    }
}
