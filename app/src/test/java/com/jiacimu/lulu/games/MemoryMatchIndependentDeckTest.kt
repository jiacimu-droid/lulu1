package com.jiacimu.lulu.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MemoryMatchIndependentDeckTest {
    @Test fun ownerAndOpponentHaveIdenticalButIndependentPairProgress() {
        val store = LuluGameStore(RuntimeEnvironment.getApplication())
        store.startMemoryMatch("test-opponent")
        val initial = store.state.value.memoryMatch
        val sameValueIndex = initial.cards.indices.first { it != 0 && initial.cards[it] == initial.cards[0] }

        store.openMemoryCard(0)
        store.openMemoryCard(sameValueIndex)
        val afterOwner = store.state.value.memoryMatch
        assertEquals(1, afterOwner.moves)
        assertEquals(1, afterOwner.userPairs)
        assertEquals(0, afterOwner.characterPairs)
        assertEquals(MemoryTurn.Character, afterOwner.turn)
        assertTrue(0 in afterOwner.matched)
        assertFalse(0 in afterOwner.characterMatched)

        // Both can solve this SAME pair on their own boards, without peeking at each other's turns.
        store.openCharacterMemoryCard(0)
        store.openCharacterMemoryCard(sameValueIndex)
        val afterRole = store.state.value.memoryMatch
        assertEquals(1, afterRole.characterMoves)
        assertEquals(1, afterRole.characterPairs)
        assertEquals(1, afterRole.moves)
        assertTrue(0 in afterRole.characterMatched)
        assertTrue(0 in afterRole.matched)
        assertEquals(MemoryTurn.User, afterRole.turn)
        assertFalse(afterRole.finished)
    }

    @Test fun soloCompletesAPairWithoutWaitingForAnyRole() {
        val store = LuluGameStore(RuntimeEnvironment.getApplication())
        store.startMemoryMatch(null)
        val initial = store.state.value.memoryMatch
        val sameValueIndex = initial.cards.indices.first { it != 0 && initial.cards[it] == initial.cards[0] }
        store.openMemoryCard(0)
        store.openMemoryCard(sameValueIndex)
        val after = store.state.value.memoryMatch
        assertEquals(MemoryTurn.User, after.turn)
        assertEquals(1, after.moves)
        assertEquals(0, after.characterMoves)
        assertEquals(1, after.userPairs)
        assertFalse(after.finished)
    }
}
