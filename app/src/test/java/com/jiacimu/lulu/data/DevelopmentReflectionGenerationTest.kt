package com.jiacimu.lulu.data

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DevelopmentReflectionGenerationTest {
    @Test fun olderReflectionCannotEraseNewWorkArrivingDuringItsModelCall() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("development-generation-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val characterId = "growth-race"
        DevelopmentReflectionGeneration.request(prefs, characterId)
        val started = DevelopmentReflectionGeneration.snapshot(prefs, characterId)
        DevelopmentReflectionGeneration.request(prefs, characterId)
        assertFalse(DevelopmentReflectionGeneration.acknowledge(prefs, characterId, started))
        assertTrue(prefs.getBoolean("pending:$characterId", false))
        val newer = DevelopmentReflectionGeneration.snapshot(prefs, characterId)
        assertTrue(DevelopmentReflectionGeneration.acknowledge(prefs, characterId, newer))
        assertFalse(prefs.getBoolean("pending:$characterId", true))
    }

    @Test fun pendingGenerationSurvivesProcessPreferenceReopen() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("development-generation-restart-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val characterId = "growth-restart"
        val version = DevelopmentReflectionGeneration.request(prefs, characterId)
        val reopened = context.getSharedPreferences("development-generation-restart-test", Context.MODE_PRIVATE)
        assertEquals(version, DevelopmentReflectionGeneration.snapshot(reopened, characterId))
        assertTrue(reopened.getBoolean("pending:$characterId", false))
        assertTrue(DevelopmentReflectionGeneration.acknowledge(reopened, characterId, version))
    }
}
