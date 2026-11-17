package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuluOnboardingGuideContentTest {
    @Test fun onboardingRunsFromApiToOwnRoleToModelToChat() {
        assertEquals(listOf("api", "role", "model", "chat"), luluGuideSteps.map { it.screen })
        assertEquals(
            listOf(MigrationRoute.Settings, MigrationRoute.Chat, MigrationRoute.Chat, MigrationRoute.Chat),
            luluGuideSteps.map { it.destination },
        )
        assertTrue(luluGuideSteps.all { it.tips.isNotEmpty() && it.location.isNotBlank() })
    }

    @Test fun everyFeatureHasUsefulContentAndARealDestination() {
        assertEquals(luluGuideFeatures.size, luluGuideFeatures.map { it.key }.distinct().size)
        assertTrue(luluGuideFeatures.size >= 10)
        assertTrue(luluGuideFeatures.all {
            it.where.isNotBlank() && it.instructions.isNotEmpty() && it.warning.isNotBlank()
        })
    }

    @Test fun publicGuideDoesNotShipOwnersPrivateNamesOrKeys() {
        val publicWords = luluGuideSteps.flatMap { listOf(it.name, it.location) + it.tips } +
            luluGuideFeatures.flatMap { listOf(it.name, it.description, it.where, it.warning) + it.instructions }
        val text = publicWords.joinToString("\n")
        assertFalse(text.contains("江渡"))
        assertFalse(text.contains("星禾"))
        assertFalse(text.contains("https://api."))
        assertFalse(text.contains("sk-"))
    }
}
