package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import java.time.Instant
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RelationshipPreferencePersistenceTest {
    @Before fun before() { MemoryTestEnvironment.release(); CharacterRelationshipPreferences.invalidate() }
    @After fun after() { MemoryTestEnvironment.release(); CharacterRelationshipPreferences.invalidate() }
    private fun initialize() {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
    }
    private fun record(id: String, text: String, seconds: Long = 0) {
        SharedExperienceTimeline.record(id, "preference-role", "私聊", "用户", text,
            Instant.parse("2026-01-01T00:00:00Z").plusSeconds(seconds), triggerExtraction = false,
            evidenceKind = EventEvidenceKind.UserStatement)
    }

    @Test fun sourceEditsDeletionAndRestartRebuildTheSamePreferences() {
        initialize()
        record("old", "以后每次抛飞吻的时候都要发mwah")
        assertTrue(CharacterRelationshipPreferences.promptSection("preference-role").contains("mwah"))
        record("new", "以后每次抛飞吻的时候都要发mua", 1)
        assertEquals(listOf("mua"), CharacterRelationshipPreferences.snapshot("preference-role").preferences.single().requiredForms)
        SharedExperienceTimeline.deleteEvent("new")
        assertEquals(listOf("mwah"), CharacterRelationshipPreferences.snapshot("preference-role").preferences.single().requiredForms)
        record("old", "这次没有长期相处要求", 2)
        assertTrue(CharacterRelationshipPreferences.snapshot("preference-role").preferences.isEmpty())
        record("survives", "以后你安慰我不要再给我一堆计划", 3)
        MemoryTestEnvironment.release()
        initialize()
        assertEquals("survives", CharacterRelationshipPreferences.snapshot("preference-role").preferences.single().sourceId)
        SharedExperienceTimeline.deleteCharacterEvents("preference-role")
        assertTrue(CharacterRelationshipPreferences.snapshot("preference-role").preferences.isEmpty())
    }

    @Test fun importedDeletionCannotLeaveAnOldCachedContractActive() {
        initialize()
        record("contract", "以后每次抛飞吻的时候都要发mwah")
        assertFalse(CharacterRelationshipPreferences.snapshot("preference-role").preferences.isEmpty())
        val backup = SharedExperienceTimeline.exportBackup()
        backup.put("deleted", org.json.JSONArray().put(org.json.JSONObject()
            .put("event_id", "contract").put("deleted_at", System.currentTimeMillis())))
        SharedExperienceTimeline.importBackup(backup)
        assertTrue(CharacterRelationshipPreferences.snapshot("preference-role").preferences.isEmpty())
    }
}
