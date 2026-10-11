package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class CharacterAddressPreferenceTest {
    @Test fun wakeUpCommandsNeverBecomeDailyNames() {
        listOf("明天早上叫我起床", "叫我起床！", "你要叫我起来",
            "以后记得叫我醒醒", "你可以叫我去吃饭", "明天七点喊我早起").forEach {
            assertNull("Not a form of address: $it", CharacterAddressPreference.extractExplicitAddress(it))
        }
    }

    @Test fun clearUserAddressPreferencesStillWork() {
        assertEquals("宝宝", CharacterAddressPreference.extractExplicitAddress("我更喜欢你叫我宝宝"))
        assertEquals("宝宝", CharacterAddressPreference.extractExplicitAddress("以后就叫我宝宝吧"))
        assertEquals("宝宝", CharacterAddressPreference.extractExplicitAddress("我比较喜欢宝宝这个称呼"))
    }

    @Test fun quotedExamplesAndNegationsAreNotBindingPreferences() {
        assertNull(CharacterAddressPreference.extractExplicitAddress("比如你叫我老婆吧"))
        assertNull(CharacterAddressPreference.extractExplicitAddress("别叫我宝宝"))
        assertNull(CharacterAddressPreference.extractExplicitAddress("我不喜欢你叫我起床"))
    }

    @Test fun taskAndOneOffEmotionDoNotRewriteRelationshipAddress() {
        assertNull(CharacterAddressPreference.extractExplicitAddress("你叫我起床，然后我们再说话"))
        assertNull(CharacterAddressPreference.extractExplicitAddress("明天记得提醒我学习"))
    }
}
