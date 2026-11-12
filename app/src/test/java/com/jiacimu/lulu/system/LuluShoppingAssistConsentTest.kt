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
        val prefs = ctx.getSharedPreferences("lulu_virtual_shopping_owner_v1", Context.MODE_PRIVATE)
        val a = "shopping-test-role-a"
        val b = "shopping-test-role-b"
        prefs.edit().remove(a).remove(b).commit()
        try {
            assertFalse(LuluShoppingAssistPolicy.isEnabled(ctx, a))
            assertFalse(LuluShoppingAssistPolicy.isEnabled(ctx, b))
            assertTrue(LuluShoppingAssistPolicy.setEnabled(ctx, a, true))
            assertTrue(LuluShoppingAssistPolicy.isEnabled(ctx, a))
            assertFalse(LuluShoppingAssistPolicy.isEnabled(ctx, b))
            assertFalse(LuluShoppingAssistPolicy.isEnabled(ctx, "resident:someone"))
            assertFalse(LuluShoppingAssistPolicy.setEnabled(ctx, "resident:someone", true))
            assertTrue(LuluShoppingAssistPolicy.setEnabled(ctx, a, false))
            assertFalse(LuluShoppingAssistPolicy.isEnabled(ctx, a))
        } finally { prefs.edit().remove(a).remove(b).commit() }
    }
}
