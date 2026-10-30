package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class TimelinePrefixDeletionTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context

    @Before fun before() {
        MemoryTestEnvironment.release()
        LuluRepositories.initialize(context)
        LuluRepositories.lexicon.initialize(context)
        SharedExperienceTimeline.initialize(context)
    }

    @After fun after() { MemoryTestEnvironment.release() }

    @Test fun deletingFavoriteWithWildcardIdKeepsOtherReceiptsAndOtherCharacters() = runBlocking {
        val owner = "prefix_favorite_owner"
        val another = "prefix_favorite_other"
        val favoriteId = "fav_%"
        val prefix = "lexicon-favorite-$favoriteId"
        val own = listOf("$prefix-receipt-one", "$prefix-receipt-two")
        val unrelated = listOf(
            "lexicon-favorite-fav_a-receipt", // '_' was a LIKE wildcard
            "lexicon-favorite-favZZ-receipt", // '%' matched the remainder
            "lexicon-favorite-FAV_%-receipt", // LIKE folded ASCII case
        )
        val foreign = "$prefix-foreign"
        val at = Instant.parse("2026-10-30T01:00:00Z")
        (own + unrelated).forEach { id ->
            SharedExperienceTimeline.record(id, owner, "收藏", "执行器", id,
                occurredAt = at, triggerExtraction = false)
        }
        SharedExperienceTimeline.record(foreign, another, "收藏", "执行器", foreign,
            occurredAt = at, triggerExtraction = false)
        assertEquals(own.toSet(), SharedExperienceTimeline.eventsByIdPrefix(owner, prefix)
            .map { it.id }.toSet())

        LuluRepositories.lexicon.save(LexiconEntry(
            id = favoriteId, characterId = owner, section = LexiconSection.Favorite,
            title = "收藏一", content = "只删除这条收藏的真实回执",
            createdAt = at, updatedAt = at,
        ))
        assertTrue(LuluRepositories.lexicon.snapshot(owner).any { it.id == favoriteId })
        LuluRepositories.lexicon.delete(favoriteId)

        assertTrue(SharedExperienceTimeline.eventsByIds(owner, own).isEmpty())
        assertEquals(unrelated.toSet(), SharedExperienceTimeline.eventsByIds(owner, unrelated)
            .map { it.id }.toSet())
        assertEquals(listOf(foreign), SharedExperienceTimeline.eventsByIds(another, listOf(foreign))
            .map { it.id })
        assertFalse(LuluRepositories.lexicon.snapshot(owner).any { it.id == favoriteId })

        // The deleted native evidence cannot be revived by a late result.
        SharedExperienceTimeline.record(own.first(), owner, "收藏", "执行器", "迟到结果",
            occurredAt = at.plusSeconds(1), triggerExtraction = false)
        assertTrue(SharedExperienceTimeline.eventsByIds(owner, listOf(own.first())).isEmpty())
        assertTrue(SharedExperienceTimeline.isDeletedEvent(own.first()))
    }

    @Test fun deletingMeetingPrefixUsesExactCaseAndDoesNotEraseSimilarSessionIds() {
        val owner = "prefix_meeting_owner"
        val prefix = "meeting-room_%"
        val own = "$prefix-turn-1"
        val otherSession = "meeting-room_a-turn-1"
        val otherCase = "meeting-ROOM_%-turn-1"
        listOf(own, otherSession, otherCase).forEach { id ->
            SharedExperienceTimeline.record(id, owner, "见面", "系统", id,
                triggerExtraction = false)
        }
        SharedExperienceTimeline.deleteEventsByIdPrefix(owner, prefix)
        assertTrue(SharedExperienceTimeline.eventsByIds(owner, listOf(own)).isEmpty())
        assertEquals(setOf(otherSession, otherCase),
            SharedExperienceTimeline.eventsByIds(owner, listOf(otherSession, otherCase))
                .map { it.id }.toSet())
    }
}
