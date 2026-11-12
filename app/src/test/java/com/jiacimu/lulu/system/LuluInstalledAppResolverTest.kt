package com.jiacimu.lulu.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LuluInstalledAppResolverTest {
    private val apps = listOf(
        LuluInstalledAppResolver.LaunchableApp("抖音", "com.example.aweme"),
        LuluInstalledAppResolver.LaunchableApp("小红书", "com.example.red"),
        // Two launcher activities for the SAME app are not ambiguous.
        LuluInstalledAppResolver.LaunchableApp("抖音", "com.example.aweme"),
    )

    @Test fun exactVisibleNameSelectsInstalledPackage() {
        assertEquals("com.example.aweme",
            LuluInstalledAppResolver.exactPackage(" 抖音 ", apps))
        assertEquals("com.example.red",
            LuluInstalledAppResolver.exactPackage("小红书", apps))
    }

    @Test fun doesNotSelectAnUnrelatedPartialName() {
        try {
            LuluInstalledAppResolver.exactPackage("抖", apps)
            fail("must not infer which installed app was intended")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("没有找到"))
        }
    }

    @Test fun doesNotGuessAmongTwoPackagesWithSameLabel() {
        val duplicates = apps + LuluInstalledAppResolver.LaunchableApp(
            "抖音", "com.example.fake")
        try {
            LuluInstalledAppResolver.exactPackage("抖音", duplicates)
            fail("must not choose arbitrarily among apps with the same label")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("多个同名"))
        }
    }

    @Test fun rejectsMissingAppNameRatherThanOpeningFirstLauncher() {
        try {
            LuluInstalledAppResolver.exactPackage(" ", apps)
            fail("empty app name is not a device instruction")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("appName"))
        }
    }
}
