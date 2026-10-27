package com.jiacimu.lulu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Mailbox fair trimming (batch 17): the old global takeLast(1200) let a
 * single busy pair evict everyone else's mail. Each unordered pair now
 * keeps at most 200 recent messages first, and only then does the
 * global 1200 cap apply. Original order is preserved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ResidentSocialMailboxTrimTest {

    private fun message(pair: String, index: Int, at: Instant): ResidentDirectMessage {
        val (a, b) = pair.split(":")
        // Alternate direction so the pair key must be unordered.
        val (from, to) = if (index % 2 == 0) a to b else b to a
        return ResidentDirectMessage(
            id = "$pair-$index", fromId = from, toId = to,
            content = "$pair-$index", createdAt = at.plusSeconds(index.toLong()), author = "character",
        )
    }

    @Test
    fun busyPairCannotEvictOtherPairsMail() {
        val base = Instant.parse("2026-10-26T12:00:00Z")
        val all = buildList {
            for (i in 1..250) add(message("a:b", i, base))
            for (i in 1..10) add(message("c:d", i, base.plusSeconds(10_000)))
        }
        val trimmed = ResidentSocialMailbox.trimForStorage(all)
        assertEquals(210, trimmed.size)
        val busy = trimmed.filter { it.content.startsWith("a:b-") }
        val quiet = trimmed.filter { it.content.startsWith("c:d-") }
        assertEquals(200, busy.size)
        assertEquals(10, quiet.size)
        // The busy pair keeps its NEWEST 200, not its oldest 200.
        assertEquals("a:b-51", busy.first().content)
        assertEquals("a:b-250", busy.last().content)
        // Order is preserved: quiet pair still comes after the busy pair.
        assertTrue(trimmed.indexOf(quiet.first()) > trimmed.indexOf(busy.last()))
    }

    @Test
    fun globalCapStillAppliesAfterPairCaps() {
        val base = Instant.parse("2026-10-26T12:00:00Z")
        val all = buildList {
            for (pair in 1..7) {
                for (i in 1..200) add(message("p$pair:q$pair", i, base.plusSeconds(pair * 100_000L)))
            }
        }
        assertEquals(1400, all.size)
        val trimmed = ResidentSocialMailbox.trimForStorage(all)
        assertEquals(1200, trimmed.size)
        // The globally oldest pair is evicted by the global cap.
        assertTrue(trimmed.none { it.content.startsWith("p1:q1-") })
        assertTrue(trimmed.any { it.content.startsWith("p7:q7-") })
    }

    @Test
    fun orderIsPreservedUnderCaps() {
        val base = Instant.parse("2026-10-26T12:00:00Z")
        val all = (1..50).map { message("x:y", it, base) }
        val trimmed = ResidentSocialMailbox.trimForStorage(all)
        assertEquals(all.map { it.id }, trimmed.map { it.id })
    }
}
