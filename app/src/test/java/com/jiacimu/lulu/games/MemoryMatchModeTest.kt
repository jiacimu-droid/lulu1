package com.jiacimu.lulu.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryMatchModeTest {
    @Test fun soloNeverHandsOffTurnAfterAMiss() {
        val fresh = MemoryMatchState(opponentId = null)
        assertNull(fresh.opponentId)
        assertEquals(MemoryTurn.User, nextMemoryTurn(fresh))
        assertEquals(MemoryTurn.User, nextMemoryTurn(fresh.copy(turn = MemoryTurn.Character)))
    }

    @Test fun duelAlternatesTurnsOnAnUnmatchedPair() {
        val room = MemoryMatchState(opponentId = "test-character")
        assertEquals(MemoryTurn.Character, nextMemoryTurn(room))
        assertEquals(MemoryTurn.User, nextMemoryTurn(room.copy(turn = MemoryTurn.Character)))
    }

    @Test fun shuffledRoomPreservesActualOpponentAcrossUpdates() {
        val room = MemoryMatchState(opponentId = "chosen-person")
        val afterFlip = room.copy(opened = setOf(0), moves = 1)
        assertEquals("chosen-person", afterFlip.opponentId)
        assertEquals(MemoryTurn.Character, nextMemoryTurn(afterFlip))
    }
}
