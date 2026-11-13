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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterPhoneMemoTest {
    private fun rebind() {
        CharacterLifeStore.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
            .set(CharacterLifeStore, null)
    }

    @Before fun before() = rebind()
    @After fun after() = rebind()

    @Test fun aMemoBelongsToItsCharacterAndSurvivesReload() {
        val context = RuntimeEnvironment.getApplication() as Context
        CharacterLifeStore.initialize(context)
        val a = "phone-memo-a-${UUID.randomUUID()}"
        val b = "phone-memo-b-${UUID.randomUUID()}"
        val first = CharacterLifeStore.writePhoneMemo(a, "想研究的事", "从真实看到的内容继续学习", "memo")
        CharacterLifeStore.writePhoneMemo(b, "自己的生活", "今天见到了朋友", "life")
        assertEquals(1, CharacterLifeStore.phoneMemos(a).size)
        assertEquals(1, CharacterLifeStore.phoneMemos(b).size)
        assertEquals("想研究的事", CharacterLifeStore.phoneMemos(a).single().getString("title"))
        assertFalse(CharacterLifeStore.phoneMemos(b).any { it.optString("id") == first.getString("id") })
        rebind()
        CharacterLifeStore.initialize(context)
        assertEquals(first.getString("id"), CharacterLifeStore.phoneMemos(a).single().getString("id"))
        assertEquals("从真实看到的内容继续学习",
            CharacterLifeStore.phoneMemos(a).single().getString("content"))
    }

    @Test fun editsTargetExistingMemoWithoutInventingACompletedTask() {
        val context = RuntimeEnvironment.getApplication() as Context
        CharacterLifeStore.initialize(context)
        val role = "phone-memo-update-${UUID.randomUUID()}"
        val first = CharacterLifeStore.writePhoneMemo(role,
            "记得跟进", "只是暂时的提醒", "responsibility")
        val updated = CharacterLifeStore.writePhoneMemo(role, "还要核实",
            "尚未证明目标完成", "responsibility", first.getString("id"))
        assertEquals(first.getString("id"), updated.getString("id"))
        assertEquals(first.getString("createdAt"), updated.getString("createdAt"))
        assertEquals("尚未证明目标完成", CharacterLifeStore.phoneMemos(role).single().getString("content"))
        assertEquals(1, CharacterLifeStore.phoneMemos(role).size)
        assertFalse(CharacterLifeStore.state(role).has("completedTasks"))
    }

    @Test fun invalidOrForeignMemoCannotBeRewritten() {
        val context = RuntimeEnvironment.getApplication() as Context
        CharacterLifeStore.initialize(context)
        val a = "phone-memo-source-${UUID.randomUUID()}"
        val b = "phone-memo-target-${UUID.randomUUID()}"
        val created = CharacterLifeStore.writePhoneMemo(a, "事实", "真实自己的记录")
        assertTrue(runCatching {
            CharacterLifeStore.writePhoneMemo(b, "冒用", "跨角色覆盖", "memo", created.getString("id"))
        }.isFailure)
        assertTrue(runCatching {
            CharacterLifeStore.writePhoneMemo(a, "", "空标题")
        }.isFailure)
        assertTrue(runCatching {
            CharacterLifeStore.writePhoneMemo(a, "格式错误", "正文", "unknown")
        }.isFailure)
        assertEquals(1, CharacterLifeStore.phoneMemos(a).size)
        assertTrue(CharacterLifeStore.phoneMemos(b).isEmpty())
    }

    @Test fun memoIsARealCharacterToolIncludingForResidents() {
        val ctx = RuntimeEnvironment.getApplication() as Context
        val capability = CapabilityRegistry.find("write_phone_memo")
        assertNotNull(capability)
        assertTrue(CapabilityRegistry.canChoose("write_phone_memo"))
        assertTrue(CapabilityRegistry.allows(ctx, "resident:memo-test", capability!!, false))
    }
}
