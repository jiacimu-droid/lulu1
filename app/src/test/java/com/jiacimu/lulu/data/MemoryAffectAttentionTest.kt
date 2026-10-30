package com.jiacimu.lulu.data

import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import com.jiacimu.lulu.core.MemoryTier
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MemoryAffectAttentionTest {
    private val now = Instant.parse("2026-10-30T00:00:00Z")
    private fun memory(source: String, owner: String = "role") =
        MemoryEntry("m", owner, "曾经真实谈过的一件事", MemoryKind.Timeline,
            source, now, now, 6, false, true, MemoryTier.Stable)

    private val emotion = JSONObject().put("feeling", "此刻有点惦记")
        .put("evidenceId", "source-1")
    private val fresh = CharacterInnerLifeStore.EmotionInfluence(
        elapsedMinutes = 0, halfLifeMinutes = 120,
        initialStrength = 3, remainingStrength = 3.0,
        valence = null, arousal = null,
    )

    @Test fun onlyTheExactSourceOfTheSameCharactersActiveFeelingGetsAttention() {
        assertEquals(0.12, MemoryAffectAttention.bonus(
            memory("timeline-events:source-1"), "role", emotion, fresh), 0.00001)
        assertEquals(0.0, MemoryAffectAttention.bonus(
            memory("timeline-events:source-10"), "role", emotion, fresh), 0.0)
        assertEquals(0.0, MemoryAffectAttention.bonus(
            memory("timeline-batch:source-1|unrelated"), "role", emotion, fresh), 0.0)
        assertEquals(0.0, MemoryAffectAttention.bonus(
            memory("timeline-events:source-1", owner = "other"), "role", emotion, fresh), 0.0)
    }

    @Test fun fadingOrMissingEmotionNeverInventsAttentionOrEditsTheMemory() {
        val item = memory("timeline-events:source-1")
        val oldSource = item.source
        val fading = fresh.copy(elapsedMinutes = 120, remainingStrength = 1.5)
        assertEquals(0.06, MemoryAffectAttention.bonus(item, "role", emotion, fading), 0.00001)
        assertEquals(0.0, MemoryAffectAttention.bonus(item, "role", emotion, null), 0.0)
        assertEquals(0.0, MemoryAffectAttention.bonus(item, "role",
            JSONObject().put("feeling", "难以归因"), fresh), 0.0)
        assertEquals(oldSource, item.source)
    }
}
