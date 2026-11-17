package com.jiacimu.lulu.system

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LuluVirtualNavigationTest {
    @Test fun labelsMustBeExactSingleLineStringsAndLaunchMaySupplyThem() {
        val args = JSONObject().put("appName", "微信")
            .put(LuluVirtualNavigation.LABELS, JSONArray(listOf(" 发现 ", "朋友圈")))
        assertEquals(listOf("发现", "朋友圈"), LuluVirtualNavigation.labels(args))
        assertNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("launch", args))
        assertNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("navigate", args))
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("navigate", JSONObject()))
        args.put(LuluVirtualNavigation.LABELS, JSONArray(listOf("发现", 1)))
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("launch", args))
        args.put(LuluVirtualNavigation.LABELS, JSONArray(listOf("发现\n朋友圈")))
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("navigate", args))
    }

    @Test fun passesVerifiedLocalResultsBetweenEachChosenNavigationStep() = runBlocking {
        val visited = mutableListOf<String>()
        val result = LuluVirtualNavigation.run(
            listOf("发现", "朋友圈"),
            pauseReason = { null },
            click = { label ->
                visited += label
                JSONObject().put("accepted", true).put("actionMethod", "verified_click")
            },
            changed = { it.optBoolean("accepted") },
        )
        assertEquals(listOf("发现", "朋友圈"), visited)
        assertTrue(result.optBoolean("navigationCompleted"))
        assertTrue(result.getJSONArray("navigationSteps").getJSONObject(1).optBoolean("uiChanged"))
    }

    @Test fun anAcceptedButUnverifiedClickStopsBeforeTheNextScreen() = runBlocking {
        val visited = mutableListOf<String>()
        val result = LuluVirtualNavigation.run(
            listOf("发现", "朋友圈"),
            pauseReason = { null },
            click = { label ->
                visited += label
                JSONObject().put("accepted", true)
            },
            changed = { false },
        )
        assertEquals(listOf("发现"), visited)
        assertFalse(result.optBoolean("navigationCompleted"))
        assertEquals("navigation_change_unverified", result.optString("navigationError"))
    }

    @Test fun freshOwnerInputStopsBeforeTheNextTapAndAmbiguousTargetIsNotRetried() = runBlocking {
        val visited = mutableListOf<String>()
        val result = LuluVirtualNavigation.run(
            listOf("发现", "朋友圈"),
            pauseReason = { if (visited.isEmpty()) null else "new_user_input" },
            click = { label ->
                visited += label
                JSONObject().put("accepted", true)
            },
            changed = { true },
        )
        assertEquals(listOf("发现"), visited)
        assertEquals("new_user_input", result.optString("navigationError"))
        assertEquals(LuluVirtualNavigation.Resolution.AMBIGUOUS, LuluVirtualNavigation.resolution(2))
        assertFalse(LuluVirtualNavigation.allowsTouchFallback(false, true, true))
        assertFalse(LuluVirtualNavigation.allowsTouchFallback(true, false, true))
        assertFalse(LuluVirtualNavigation.allowsTouchFallback(true, true, false))
        assertTrue(LuluVirtualNavigation.allowsTouchFallback(true, true, true))
    }
}
