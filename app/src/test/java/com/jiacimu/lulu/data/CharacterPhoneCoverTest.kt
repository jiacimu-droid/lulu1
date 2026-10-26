package com.jiacimu.lulu.data

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterPhoneCoverTest {
    @Before fun before() { resetStorage() }
    @After fun after() { resetStorage() }

    private fun resetStorage() {
        CharacterLifeStore.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
            .set(CharacterLifeStore, null)
    }

    @Test fun twoResidentsHaveIndependentPersistentPhoneCovers() {
        val ctx = RuntimeEnvironment.getApplication() as Context
        CharacterLifeStore.initialize(ctx)
        val a = "resident:phone-cover-a"
        val b = "resident:phone-cover-b"
        assertTrue(CharacterLifeStore.setPhoneMomentsCover(a, "rose"))
        assertTrue(CharacterLifeStore.setPhoneMomentsCover(b, "forest"))
        assertEquals("rose", CharacterLifeStore.state(a)
            .getJSONObject("phoneAppearance").getString("momentsCover"))
        assertEquals("forest", CharacterLifeStore.state(b)
            .getJSONObject("phoneAppearance").getString("momentsCover"))
        assertFalse(CharacterLifeStore.setPhoneMomentsCover(a, "rose"))
        assertFalse(CharacterLifeStore.setPhoneMomentsCover(a, "made-up-photo"))
    }

    @Test fun coverChangeIsARealRegisteredDigitalResidentAction() {
        val ctx = RuntimeEnvironment.getApplication() as Context
        val capability = CapabilityRegistry.find("set_phone_moments_cover")
        assertNotNull(capability)
        assertTrue(CapabilityRegistry.canChoose("set_phone_moments_cover"))
        assertTrue(CapabilityRegistry.allows(ctx, "resident:phone-cover-a", capability!!, false))
    }

    @Test fun photoPresetNeedsSourceAndCanRestorePresetAfterPhoto() {
        val ctx = RuntimeEnvironment.getApplication() as Context
        CharacterLifeStore.initialize(ctx)
        val role = "resident:phone-cover-photo"
        assertFalse(CharacterLifeStore.setPhoneMomentsCover(role, "photo"))
        assertTrue(CharacterLifeStore.setPhoneMomentsCover(role, "photo", "existing-post-id"))
        assertTrue(CharacterLifeStore.setPhoneMomentsCover(role, "sky"))
        val own = CharacterLifeStore.state(role).getJSONObject("phoneAppearance")
        assertEquals("sky", own.getString("momentsCover"))
        assertEquals("", own.getString("momentsPhotoPostId"))
    }
}
