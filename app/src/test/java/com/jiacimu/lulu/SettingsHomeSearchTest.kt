package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsHomeSearchTest {

    @Test fun everySettingsDestinationIsStillReachableAndGrouped() {
        assertEquals(
            setOf("api", "integrations", "voice", "memory", "residents",
                "vision", "image", "capabilities", "backup"),
            settingsHomeEntries.map { it.id }.toSet(),
        )
        assertEquals(settingsHomeEntries.size, settingsHomeEntries.map { it.id }.distinct().size)
        assertTrue(settingsHomeEntries.all { it.section in settingsHomeSections })
        assertEquals(settingsHomeEntries, filterSettingsHomeEntries(settingsHomeEntries, ""))
        assertEquals(settingsHomeEntries, filterSettingsHomeEntries(settingsHomeEntries, "  "))
    }

    @Test fun everydayQueriesFindTheActualSettingWithoutASecondNavigationTree() {
        assertEquals(listOf("api"), ids("兜底模型"))
        assertEquals(listOf("voice"), ids("音色"))
        assertEquals(listOf("capabilities"), ids("通知"))
        assertEquals(listOf("backup"), ids("云备份"))
        assertEquals(listOf("memory"), ids("重排序"))
        assertEquals(listOf("residents"), ids("NPC"))
        assertEquals(listOf("vision"), ids("图片理解"))
        assertEquals(listOf("image"), ids("图像生成"))
        assertEquals(listOf("integrations"), ids("小红书"))
    }

    @Test fun multiTermCaseInsensitiveSearchAndNoMatchesStayPredictable() {
        assertEquals(listOf("api"), ids("API  模型"))
        assertEquals(listOf("voice"), ids("elevenlabs 语速"))
        assertEquals(listOf("memory"), ids("embedding 记忆"))
        assertTrue(ids("根本不存在的选项").isEmpty())
        assertFalse(ids("角色").isEmpty()) // Section names remain discoverable
    }

    private fun ids(query: String) =
        filterSettingsHomeEntries(settingsHomeEntries, query).map { it.id }
}
