package com.jiacimu.lulu.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class MemoryMatchOpponentFairnessTest {
    @Test fun opponentUsesPreviouslyObservedPairInsteadOfInspectingHiddenDeck() {
        val game = MemoryMatchState(
            cards = listOf("A", "B", "A", "B", "C", "C"),
            observed = mapOf(0 to "A", 2 to "A", 1 to "B"),
            turn = MemoryTurn.Character,
        )
        assertEquals(0, chooseObservedMemoryCard(game, Random(3)))
        assertEquals(2, chooseObservedMemoryCard(game.copy(opened = setOf(0)), Random(3)))
    }

    @Test fun hiddenCardValuesCannotInfluenceOpponentChoice() {
        val first = MemoryMatchState(
            cards = listOf("A", "B", "A", "B", "C", "C"),
            observed = mapOf(0 to "A"),
            opened = setOf(0),
            turn = MemoryTurn.Character,
        )
        val alteredHiddenDeck = first.copy(cards = listOf("A", "C", "C", "B", "A", "B"))
        assertEquals(
            chooseObservedMemoryCard(first, Random(17)),
            chooseObservedMemoryCard(alteredHiddenDeck, Random(17)),
        )
        val selected = chooseObservedMemoryCard(first, Random(17))
        assertFalse(selected in first.opened)
    }

    @Test fun matchedAndOpenedCardsAreNeverChosen() {
        val game = MemoryMatchState(
            cards = listOf("A", "A", "B", "B"),
            observed = mapOf(0 to "A", 1 to "A", 2 to "B", 3 to "B"),
            matched = setOf(0, 1),
            opened = setOf(2),
            turn = MemoryTurn.Character,
        )
        assertEquals(3, chooseObservedMemoryCard(game, Random(4)))
        assertNull(chooseObservedMemoryCard(game.copy(matched = setOf(0, 1, 2, 3))))
    }
}
