package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class CharacterInitiativeRuntimeTest {
    @Test fun fatigueIsAChanceToTakeInitiativeNotADeviceCommand() {
        val cue = CharacterInitiativeRuntime.detect("我今天学累了，脑子转不动")
        assertEquals(CharacterInitiativeRuntime.NeedKind.FATIGUE, cue?.kind)
        assertFalse(CharacterInitiativeRuntime.isExplicitUserToolRequest("我今天学累了", "create_alarm"))
        assertFalse(CharacterInitiativeRuntime.isExplicitUserToolRequest("我今天学累了", "screen_action"))
    }

    @Test fun directAlarmRequestIsRecognizedAsUserRequested() {
        assertTrue(CharacterInitiativeRuntime.isExplicitUserToolRequest("帮我定一个晚上十点的闹钟", "create_alarm"))
        assertTrue(CharacterInitiativeRuntime.isExplicitUserToolRequest("把刚才那个闹钟取消掉", "cancel_alarm"))
    }

    @Test fun directReadQuestionCountsAsExplicitReadRequest() {
        assertTrue(CharacterInitiativeRuntime.isExplicitUserToolRequest("我现在还有多少电？", "get_battery"))
        assertTrue(CharacterInitiativeRuntime.isExplicitUserToolRequest("我现在在哪里？", "get_location"))
    }

    @Test fun roleOwnedAppActionNeverBorrowsUserRequestedPermissionFlag() {
        assertFalse(CharacterInitiativeRuntime.isExplicitUserToolRequest("陪我玩一会儿", "send_game_invite"))
    }
}
