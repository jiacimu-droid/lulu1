package com.jiacimu.lulu.data

import android.content.Context
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class WorldLifeEpisodeStoreTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val role = "unit-test-solo-life"

    @Test fun followupActionsAtSamePlaceShareOneEpisode() {
        WorldLifeEpisodeStore.initialize(context)
        WorldLifeEpisodeStore.clear(role)
        val start = Instant.parse("2026-10-14T09:00:00Z")
        val prose = "他在自己实际到达的地点度过一段时间，想着没说出口的话。".repeat(5)
        try {
            assertNull(WorldLifeEpisodeStore.recentAt(role, "shared:cloud_meadow", start))
            val first = WorldLifeEpisodeStore.record(
                role, "shared:cloud_meadow", "一个午后", prose,
                "真实探索完成", "world-evidence-42", start,
            )
            assertNotNull(first)
            assertEquals(first?.id, WorldLifeEpisodeStore.recentAt(
                role, "shared:cloud_meadow", start.plusSeconds(6 * 60))?.id)
            assertNull(WorldLifeEpisodeStore.recentAt(
                role, "shared:arrival", start.plusSeconds(6 * 60)))
            assertNull(WorldLifeEpisodeStore.recentAt(
                role, "shared:cloud_meadow", start.plusSeconds(37 * 60)))
        } finally {
            WorldLifeEpisodeStore.clear(role)
        }
    }

    @Test fun verifiedWorldActionCanBecomeOneDurableClickThroughButTextAloneCannot() {
        WorldLifeEpisodeStore.initialize(context)
        WorldLifeEpisodeStore.clear(role)
        val prose = "他踏上真正已经存在的长椅旁的路，慢慢留意午后光影。" .repeat(6)
        try {
            assertNull(WorldLifeEpisodeStore.record(role, "garden", "午后", prose,
                "成功移动", "", Instant.parse("2026-10-14T06:00:00Z")))
            val first = WorldLifeEpisodeStore.record(role, "garden", "午后", prose,
                "真实世界已移动", "real-world-evidence-id", Instant.parse("2026-10-14T06:00:00Z"))
            assertNotNull(first)
            assertEquals(1, WorldLifeEpisodeStore.episodes(role).size)
            val repeated = WorldLifeEpisodeStore.record(role, "garden", "午后", prose,
                "真实世界已移动", "real-world-evidence-id", Instant.parse("2026-10-14T06:05:00Z"))
            assertEquals(first?.id, repeated?.id)
            assertEquals(1, WorldLifeEpisodeStore.episodes(role).size)
            assertEquals(first, WorldLifeEpisodeStore.find(first!!.id))
            WorldLifeEpisodeStore.invalidateEvidence("real-world-evidence-id")
            assertNull(WorldLifeEpisodeStore.find(first.id))
        } finally {
            WorldLifeEpisodeStore.clear(role)
        }
    }
}
