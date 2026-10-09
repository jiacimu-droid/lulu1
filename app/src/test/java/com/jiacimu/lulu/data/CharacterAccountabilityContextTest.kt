package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class CharacterAccountabilityContextTest {
    @Test fun disputedMissedWakeUpIsNotRecastAsUserFault() {
        assertTrue(CharacterAccountabilityContext.isUnmetPromiseChallenge("那你也没十点叫我啊"))
        assertTrue(CharacterAccountabilityContext.isUnmetPromiseChallenge("明天你答应叫醒我，怎么没有闹钟"))
        assertFalse(CharacterAccountabilityContext.isUnmetPromiseChallenge("今天我吃了披萨"))
        assertFalse(CharacterAccountabilityContext.isUnmetPromiseChallenge("明天10点叫我"))
        val guide = CharacterAccountabilityContext.challengeGuidance("你都没有叫醒我")
        assertTrue(guide.contains("先确认谁承担叫醒或提醒责任"))
        assertTrue(guide.contains("倒打一耙"))
    }

    @Test fun missingReceiptsCannotBeTreatedAsSuccessfulDelivery() {
        val context = CharacterAccountabilityContext.prompt("no-such-role-id-193822")
        assertTrue(context.contains("没有可验证的结构化任务回执") ||
            context.contains("未证实按约履行"))
        assertTrue(context.contains("不能") || context.contains("不能声称"))
    }

    @Test fun expressionGuidePreservesIndependenceWithoutUnjustifiedCondescension() {
        val guide = CharacterExpressionGuide.promptSection()
        assertTrue(guide.contains("独立人格不是居高临下"))
        assertTrue(guide.contains("如果自己承诺过却没做到"))
        assertTrue(guide.contains("角色在意别人也不等于已发出通知"))
    }
}
