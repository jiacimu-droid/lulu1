package com.jiacimu.lulu.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Imitation is triggered only by one actual short-bubble burst, not a fixed personality. */
class CharacterSpeechIdentityTest {
    @Test fun consecutiveTinyUserBubblesCanBeNoticedAsOneJoke() {
        val burst = listOf("那", "个", "人", "没", "有", "话").mapIndexed { i, text ->
            text to (1_000L + i * 900L)
        }
        assertEquals(6, CharacterSpeechIdentity.shortBubbleBurst(burst))
    }

    @Test fun ordinaryConversationOrGapMustNotTriggerMirroring() {
        assertEquals(0, CharacterSpeechIdentity.shortBubbleBurst(
            listOf("你好", "我在", "你呢").mapIndexed { i, text -> text to (i * 1000L) }))
        assertEquals(0, CharacterSpeechIdentity.shortBubbleBurst(
            listOf("今", "天", "天", "气").mapIndexed { i, text -> text to (i * 15_000L) }))
        assertEquals(0, CharacterSpeechIdentity.shortBubbleBurst(
            listOf("我", "真", "的", "很有意思的一段话").mapIndexed { i, text -> text to (i * 1000L) }))
    }

    @Test fun nearbyShortBubblesBeforeLongAnswerAreNotMislabeledAsCurrentMimicry() {
        assertEquals(0, CharacterSpeechIdentity.shortBubbleBurst(
            listOf("那", "个", "人", "没", "我刚才在说什么呢").mapIndexed { i, text ->
                text to (1_000L + i * 900L)
            }))
    }
}
