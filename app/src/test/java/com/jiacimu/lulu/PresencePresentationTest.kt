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

    @Test fun duplicatedMoodIsShownOnlyOnceAndDoesNotBecomeAnotherGesture() {
        val state = CompanionPresenceState(
            characterId = "jiangdu",
            mood = "温柔 怜爱 忍俊不禁 · 温柔 怜爱 忍俊不禁",
            statusText = "温柔、怜爱、忍俊不禁",
            gesture = "指尖轻敲桌面",
            innerThought = "我突然想收藏这句话",
        )
        assertEquals("温柔 怜爱 忍俊不禁", PresencePresentation.uniqueMood(state.mood))
        val fields = PresencePresentation.stateFields(state)
        assertEquals(2, fields.size)
        assertEquals("心情", fields[0].first)
        assertEquals("动作", fields[1].first)
        assertEquals("指尖轻敲桌面", fields[1].second)
    }

    @Test fun independentLongTermConditionRemainsVisible() {
        val state = CompanionPresenceState(characterId = "jiangdu",
            mood = "困倦", statusText = "准备休息",
            gesture = "揉了揉眼睛")
        assertEquals("困倦 · 准备休息", PresencePresentation.status(state))
    }
}
