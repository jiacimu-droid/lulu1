package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuluOnboardingGuideContentTest {
    @Test fun everyStepHasAResourceIndependentIllustration() {
        // The previous guide bundled corrupted JPEGs and crashed when LazyColumn
        // composed the first screenshot. Illustrations now use Compose text only.
        assertEquals(luluGuideSteps.size, luluGuideSteps.map { it.screen }.distinct().size)
        luluGuideSteps.forEach { step ->
            val rows = guideScreenRows(step.screen)
            assertTrue("missing illustration for ${step.screen}", rows.size >= 3)
            assertTrue(rows.none { it.contains("sk-") || it.contains("https://api.") })
        }
        assertTrue(guideScreenRows("unknown").isEmpty())
    }

    @Test fun onboardingRunsFromApiToOwnRoleToModelToChat() {
        assertEquals(listOf("api", "role", "model", "chat"), luluGuideSteps.map { it.screen })
        assertEquals(
            listOf(MigrationRoute.Settings, MigrationRoute.CharacterCreate, MigrationRoute.Chat, MigrationRoute.Chat),
            luluGuideSteps.map { it.destination },
        )
        assertTrue(luluGuideSteps.all { it.tips.isNotEmpty() && it.location.isNotBlank() })
        assertTrue(luluGuideSteps[1].tips.any { it.contains("保存并创建角色") })
        assertTrue(guideScreenRows("role").any { it.contains("资料") && it.contains("人格") })
        assertFalse(luluGuideSteps[0].tips.any { it.contains("使用第三方 API 可能收费") })
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
