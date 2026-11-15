package com.jiacimu.lulu.system

import org.junit.Assert.*
import org.junit.Test

class PhoneSleepPromptModeTest {
    @Test fun aBedtimeResponsibilityCannotTurnOrdinarySilenceIntoBedtimePrompt() {
        // The previous sceneContext.contains("哄睡") accidentally matched this.
        val ordinaryCallContext = "一对一电话中的沉默观察：原任务是哄睡主人"
        assertTrue(ordinaryCallContext.contains("哄睡"))
        assertFalse(PhoneSleepPromptMode.active("real-call-id", modeEnabled = false))
    }

    @Test fun onlyAnActualConnectedCallWithEnabledModeUsesSleepPrompt() {
        assertTrue(PhoneSleepPromptMode.active("real-call-id", modeEnabled = true))
        assertFalse(PhoneSleepPromptMode.active("", modeEnabled = true))
        assertFalse(PhoneSleepPromptMode.active("real-call-id", modeEnabled = false))
    }
}
