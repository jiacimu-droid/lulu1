package com.jiacimu.lulu

import com.jiacimu.lulu.data.CompanionOnlineStore
import org.junit.Assert.*
import org.junit.Test

class GroupChatTurnPlannerTest {
    @Test fun onlyListedMembersCanReceiveAnOpportunity() {
        val ids = listOf("role-a", "role-b", "role-c")
        val raw = """{"speakerIds":["role-b","intruder","role-b","role-a","role-c"]}"""
        assertEquals(listOf("role-b", "role-a"), GroupChatTurnPlanner.decode(raw, ids, 2))
        assertEquals(listOf("role-b", "role-a", "role-c"),
            GroupChatTurnPlanner.decode(raw, ids, 8))
    }

    @Test fun realPauseIsDifferentFromMalformedPlannerOutput() {
        val ids = listOf("a", "b")
        assertEquals(emptyList<String>(), GroupChatTurnPlanner.decode("""{"speakerIds":[]}""", ids, 3))
        assertNull(GroupChatTurnPlanner.decode("no json", ids, 3))
        assertNull(GroupChatTurnPlanner.decode("""{"other":true}""", ids, 3))
        assertEquals(listOf("b"), GroupChatTurnPlanner.decode(
            """```json\n{"speakerIds":["b"]}\n```""", ids, 3))
    }

    @Test fun onlyOwnerGroupInputUsesForegroundCoordinator() {
        assertTrue(CompanionOnlineStore.shouldCoordinateGroupTurn(
            isGroup = true, userMessage = true, appVisible = true, inGroupCall = false))
        assertFalse(CompanionOnlineStore.shouldCoordinateGroupTurn(
            isGroup = false, userMessage = true, appVisible = true, inGroupCall = false))
        assertFalse(CompanionOnlineStore.shouldCoordinateGroupTurn(
            isGroup = true, userMessage = false, appVisible = true, inGroupCall = false))
        assertFalse(CompanionOnlineStore.shouldCoordinateGroupTurn(
            isGroup = true, userMessage = true, appVisible = false, inGroupCall = false))
        assertFalse(CompanionOnlineStore.shouldCoordinateGroupTurn(
            isGroup = true, userMessage = true, appVisible = true, inGroupCall = true))
    }
}
