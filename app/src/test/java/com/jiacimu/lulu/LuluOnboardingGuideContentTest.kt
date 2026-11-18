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

    @Test fun onboardingPreparesVoiceMemoryAndPermissionsBeforeChat() {
        assertEquals(listOf("api", "role", "model", "voice", "memory", "permissions", "chat"), luluGuideSteps.map { it.screen })
        assertEquals(
            listOf(MigrationRoute.Settings, MigrationRoute.CharacterCreate, MigrationRoute.Chat,
                MigrationRoute.Settings, MigrationRoute.Settings, MigrationRoute.Settings, MigrationRoute.Chat),
            luluGuideSteps.map { it.destination },
        )
        assertTrue(luluGuideSteps.all { it.tips.isNotEmpty() && it.location.isNotBlank() })
        assertTrue(luluGuideSteps[1].tips.any { it.contains("保存并创建角色") })
        assertTrue(guideScreenRows("role").any { it.contains("资料") && it.contains("人格") })
        assertTrue(luluGuideSteps[3].tips.any { it.contains("语音合成") && it.contains("语音识别") })
        assertTrue(luluGuideSteps[4].tips.any { it.contains("记忆抽取模型") })
        assertTrue(luluGuideSteps[5].tips.any { it.contains("虚拟屏幕授权") })
        assertTrue(luluGuideSteps.last().tips.any { it.contains("开始") || it.contains("第一条消息") })
        assertFalse(luluGuideSteps[0].tips.any { it.contains("使用第三方 API 可能收费") })
    }

    @Test fun heroFocusesOnFourDistinctStrengths() {
        assertEquals(4, luluGuideHeroHighlights.size)
        assertEquals(
            listOf("memory", "continuity", "games", "phone"),
            luluGuideHeroHighlights.map { it.featureKey },
        )
        assertTrue(luluGuideHeroHighlights.all { point ->
            point.title.isNotBlank() && point.subtitle.isNotBlank() &&
                luluGuideFeatures.any { it.key == point.featureKey }
        })
        assertTrue(luluGuideHeroHighlights.first().title.contains("自动记忆"))
    }

    @Test fun everyFeatureHasUsefulContentAndARealDestination() {
        assertEquals(luluGuideFeatures.size, luluGuideFeatures.map { it.key }.distinct().size)
        assertTrue(luluGuideFeatures.size >= 22)
        assertTrue(luluGuideFeatures.all { it.highlights.isNotEmpty() })
        val byKey = luluGuideFeatures.associateBy { it.key }
        assertTrue(byKey.getValue("call").instructions.any { it.contains("30 分钟") })
        assertTrue(byKey.getValue("call").highlights.any { it.contains("铃声") })
        assertTrue(byKey.getValue("live2d").instructions.any { it.contains("切换") })
        assertTrue(byKey.getValue("memory").instructions.any { it.contains("轮") && it.contains("15") })
        assertTrue(byKey.getValue("memory").instructions.any { it.contains("气泡") && it.contains("一轮") })
        assertTrue(byKey.getValue("phone").instructions.any { it.contains("小红书") && it.contains("微信") })
        assertTrue(byKey.getValue("phone").warning.contains("发送"))
        assertTrue(byKey.getValue("mind").instructions.any { it.contains("顶部") || it.contains("最上方") })
        assertTrue(byKey.getValue("mind").instructions.any { it.contains("左侧") && it.contains("头像") })
        // Shopping and the role-phone browser now have separate guide steps.
        // Both must still be documented; do not require them in one sentence.
        assertTrue(byKey.getValue("phone").instructions.any { it.contains("拼多多") })
        assertTrue(byKey.getValue("phone").instructions.any { it.contains("浏览器") })
        assertTrue(byKey.getValue("appearance").instructions.any { it.contains(".css") })
        assertTrue(byKey.getValue("widgets").instructions.any { it.contains("星座") })
        assertTrue(byKey.getValue("meet").warning.contains("大世界"))
        assertTrue(byKey.getValue("games").description.contains("连麦"))
        assertTrue(byKey.getValue("continuity").instructions.any { it.contains("私聊") && it.contains("电话") })
        assertTrue(byKey.getValue("live2d").warning.contains("不兼容"))
        assertTrue(byKey.getValue("memory").description.contains("自动"))
        assertTrue(luluGuideFeatures.none { it.warning.contains("内测提醒") })
        assertTrue(luluGuideFeatures.all {
            it.where.isNotBlank() && it.instructions.isNotEmpty() && it.warning.isNotBlank()
        })
    }

    @Test fun publicGuideDoesNotShipOwnersPrivateNamesOrKeys() {
        val publicWords = luluGuideSteps.flatMap { listOf(it.name, it.location) + it.tips } +
            luluGuideFeatures.flatMap { listOf(it.name, it.description, it.where, it.warning) + it.instructions + it.highlights }
        val text = publicWords.joinToString("\n")
        assertFalse(text.contains("江渡"))
        assertFalse(text.contains("星禾"))
        assertFalse(text.contains("https://api."))
        assertFalse(text.contains("sk-"))
    }
}
