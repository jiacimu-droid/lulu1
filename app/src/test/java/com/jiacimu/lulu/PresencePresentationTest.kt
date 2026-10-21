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
    @Test fun worldArrivalStatusAndVerifiedActionCannotEchoEachOther() {
        val state = CompanionPresenceState(
            characterId = "jiangdu",
            mood = "松弛、温软",
            statusText = "在浮光咖啡角挑了个靠窗的位置坐下",
            gesture = "江渡换到浮光咖啡角靠窗的位置坐下，看看外面的光景",
        )
        val fields = PresencePresentation.stateFields(state).toMap()
        assertEquals("松弛、温软", fields["心情"])
        assertNull(fields["状态"])
        assertEquals(state.gesture, fields["动作"])
    }

    @Test fun sustainedIndependentConditionSurvivesWorldActionRendering() {
        val state = CompanionPresenceState(
            characterId = "jiangdu",
            mood = "好奇",
            statusText = "在咖啡角等言溪的回复，还想看看他会不会来",
            gesture = "刚坐到窗边",
        )
        val fields = PresencePresentation.stateFields(state).toMap()
        assertEquals(state.statusText, fields["状态"])
        assertEquals(state.gesture, fields["动作"])
    }

    @Test fun partialSharedLocationDoesNotSuppressASeparateGoal() {
        val state = CompanionPresenceState(
            characterId = "jiangdu",
            statusText = "在咖啡角思考昨晚与她讨论的事情",
            gesture = "换到咖啡角靠窗的位置坐下",
        )
        val fields = PresencePresentation.stateFields(state).toMap()
        assertEquals(state.statusText, fields["状态"])
        assertEquals(state.gesture, fields["动作"])
    }

    @Test fun longStatusContainingCompletedActionAndSeparateConcernStaysVisible() {
        val state = CompanionPresenceState(
            characterId = "jiangdu",
            statusText = "在咖啡角靠窗坐下，等着言溪回消息，想当面说清楚",
            gesture = "在咖啡角靠窗坐下",
        )
        val fields = PresencePresentation.stateFields(state).toMap()
        assertEquals("等着言溪回消息，想当面说清楚", fields["状态"])
        assertEquals(state.gesture, fields["动作"])
    }

}
