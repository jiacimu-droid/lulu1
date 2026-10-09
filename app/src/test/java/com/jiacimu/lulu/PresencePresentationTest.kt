package com.jiacimu.lulu

import com.jiacimu.lulu.data.CompanionPresenceState
import org.junit.Assert.*
import org.junit.Test

class PresencePresentationTest {
    @Test fun stateDoesNotRepeatAnAlreadyVisibleAction() {
        val state = CompanionPresenceState(
            characterId = "jiangdu", mood = "挂念",
            statusText = "挂念 · 江渡在藤椅上休息",
            gesture = "江渡在藤椅上休息",
        )
        assertEquals("挂念", PresencePresentation.status(state))
    }

    @Test fun independentLongTermConditionRemainsVisible() {
        val state = CompanionPresenceState(characterId = "jiangdu",
            mood = "困倦", statusText = "准备休息",
            gesture = "揉了揉眼睛")
        assertEquals("困倦 · 准备休息", PresencePresentation.status(state))
    }
}
