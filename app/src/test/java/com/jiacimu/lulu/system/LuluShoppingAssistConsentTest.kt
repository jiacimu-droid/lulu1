package com.jiacimu.lulu.system

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Consent is stored by real owner UI, scoped to one character and default-off. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LuluShoppingAssistConsentTest {
    @Test fun ownerConsentIsDefaultOffRoleScopedAndRevocable() {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = ctx.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
        val a = "shopping-test-role-a"
        val b = "shopping-test-role-b"
        prefs.edit().remove("$a:screen").remove("$b:screen").commit()
        try {
            assertFalse(LuluShoppingAssistPolicy.isEnabled(ctx, a))
            assertFalse(LuluShoppingAssistPolicy.isEnabled(ctx, b))
            assertTrue(LuluRolePhoneAuthorization.setGranted(ctx, a, true))
            assertTrue(LuluShoppingAssistPolicy.isEnabled(ctx, a))
            assertFalse(LuluShoppingAssistPolicy.isEnabled(ctx, b))
            assertFalse(LuluShoppingAssistPolicy.isEnabled(ctx, "resident:someone"))
            assertFalse(LuluRolePhoneAuthorization.setGranted(ctx, "resident:someone", true))
            assertTrue(LuluRolePhoneAuthorization.setGranted(ctx, a, false))
            assertFalse(LuluShoppingAssistPolicy.isEnabled(ctx, a))
        } finally { prefs.edit().remove("$a:screen").remove("$b:screen").commit() }
    }
}
