package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class WorldLifeEpisodeTemporalContextTest {
    @Test fun queuedPsychologySurvivesLaterMoodChangeAndWorkerSerialization() {
        val app = RuntimeEnvironment.getApplication() as Context
        CharacterInnerLifeStore.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
            .set(CharacterInnerLifeStore, null)
        CharacterInnerLifeStore.initialize(app)
        CharacterLifeStore.initialize(app)
        val role = "prose-time-" + java.util.UUID.randomUUID()
        val prefs = app.getSharedPreferences("lulu_inner_life_v1", Context.MODE_PRIVATE)
        fun mood(value: String) {
            prefs.edit().putString(role, JSONObject().put("emotion", JSONObject()
                .put("feeling", value).put("cause", "真实相遇")
                .put("startedAt", java.time.Instant.now().minusSeconds(1).toString())
                .put("strength", 3).put("halfLifeMinutes", 60)).toString()).commit()
        }
        mood("旧相遇的欣喜")
        val captured = WorldLifeEpisodeComposer.captureExperiencedContext(role)
        assertTrue(captured.contains("旧相遇的欣喜"))
        val data = androidx.work.Data.Builder().putByteArray("snapshot",
            WorldLifeEpisodeWorker.encodeExperiencedContext(captured)).build()
        mood("后来争执的烦躁")
        val restored = WorldLifeEpisodeWorker.decodeExperiencedContext(data.getByteArray("snapshot"))
        assertEquals(captured, restored)
        assertFalse(restored.contains("后来争执的烦躁"))
        assertEquals("", WorldLifeEpisodeWorker.decodeExperiencedContext(null))
        assertEquals("", WorldLifeEpisodeWorker.decodeExperiencedContext(byteArrayOf(1,2)))
    }
}
