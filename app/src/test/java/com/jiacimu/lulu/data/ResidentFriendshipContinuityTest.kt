package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Social continuity must be based on this actor's actual delivered exchanges. */
class ResidentFriendshipContinuityTest {
    private val now = Instant.parse("2026-11-18T12:00:00Z")

    private fun mail(id: String, from: String, to: String, body: String, offset: Long) =
        ResidentDirectMessage(id, from, to, body, now.plusSeconds(offset), "character")

    @Test fun differentFriendsKeepTheirOwnNaturalConversationContext() {
        val letters = listOf(
            mail("b1", "resident:b", "resident:a", "上次你说的那局骰子", -600),
            mail("c1", "resident:c", "resident:a", "咖啡店那件事", -500),
            mail("b2", "resident:a", "resident:b", "我记得！你赢了", -400),
            mail("b3", "resident:b", "resident:a", "改天再来一局？", -300),
            mail("future", "resident:b", "resident:a", "这封还没发生", 10),
            mail("foreign", "resident:b", "resident:c", "只属于另一个人的私信", -200),
        )
        val threads = CharacterEverydaySocialContext.friendThreadLines(
            "resident:a", listOf("resident:b" to "阿北", "resident:c" to "小岑"),
            letters, now)
        assertEquals(2, threads.size)
        assertTrue(threads[0].contains("改天再来一局"))
        assertTrue(threads[0].contains("我记得！你赢了"))
        assertFalse(threads[0].contains("咖啡店那件事"))
        assertFalse(threads.joinToString().contains("这封还没发生"))
        assertFalse(threads.joinToString().contains("只属于另一个人的私信"))
        assertTrue(threads[1].contains("咖啡店那件事"))
        assertFalse(threads[1].contains("改天再来一局"))
    }

    @Test fun neverInventMessagesForExistingButQuietFriends() {
        val result = CharacterEverydaySocialContext.friendThreadLines(
            "resident:a", listOf("resident:b" to "阿北"), emptyList(), now)
        assertEquals(1, result.size)
        assertTrue(result.single().contains("没有彼此已送达的私信"))
    }

    @Test fun longHistoryKeepsTheLastThreeRealBubblesPerFriend() {
        val letters = (1..12).map {
            mail("$it", "resident:b", "resident:a", "第${it}泡", -1000 + it.toLong())
        }
        val result = CharacterEverydaySocialContext.friendThreadLines(
            "resident:a", listOf("resident:b" to "阿北"), letters, now).single()
        assertFalse(result.contains("第1泡"))
        assertFalse(result.contains("第9泡"))
        assertTrue(result.contains("第10泡"))
        assertTrue(result.contains("第11泡"))
        assertTrue(result.contains("第12泡"))
    }
}
