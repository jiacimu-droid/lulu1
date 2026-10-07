package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterOriginResetTest {
    // Robolectric reuses the SDK sandbox between classes, while each test has a fresh app DB.
    // Release this test's singleton connection so the migration test opens its own seeded DB.
    @Before fun before() { releaseTimelineDatabase() }
    @After fun after() { releaseTimelineDatabase() }

    private fun releaseTimelineDatabase() {
        synchronized(SharedExperienceTimeline) {
            val field = SharedExperienceTimeline::class.java.getDeclaredField("helper")
            field.isAccessible = true
            (field.get(SharedExperienceTimeline) as? android.database.sqlite.SQLiteOpenHelper)?.close()
            field.set(SharedExperienceTimeline, null)
        }
    }

    @Test fun fullResetKeepsCurrentDesignAndOtherRoleButStartsNewLife() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        MigratedDomainStores.characters.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        CharacterIdentityStore.initialize(context)
        CharacterLifeStore.initialize(context)
        val first = MigratedDomainStores.characters.create("新设定角色", "现在的完整人设").copy(
            avatarUri = "saved-avatar", defaultWorldBookIds = setOf("design-book"))
        MigratedDomainStores.characters.update(first)
        val other = MigratedDomainStores.characters.create("另一个角色", "另外的人设")
        CharacterIdentityStore.set(first.characterId, "保留的身份设定")
        CharacterLifeStore.setProfile(first.characterId, "care", "保留的性格设计")
        val old = Instant.parse("2026-08-07T02:00:00Z")
        val reset = Instant.parse("2026-10-07T13:00:00Z")
        DigitalLifeProfileStore.registerNewLife(first.characterId, first.displayName, "佳辞", old)
        DigitalLifeProfileStore.registerNewLife(other.characterId, other.displayName, "佳辞", old)
        SharedExperienceTimeline.record("old-group-a", first.characterId, "群聊", "用户", "旧共同经历", old.plusSeconds(1), false)
        SharedExperienceTimeline.record("old-group-b", other.characterId, "群聊", "用户", "旧共同经历", old.plusSeconds(1), false)
        val oldBackup = SharedExperienceTimeline.exportBackup()
        val otherEvents = SharedExperienceTimeline.all(other.characterId)
        CharacterRecordReset.clearAll(first.characterId, reset)
        assertEquals(first, MigratedDomainStores.characters.get(first.characterId))
        assertEquals("保留的身份设定", CharacterIdentityStore.identities.value[first.characterId])
        assertEquals("保留的性格设计", CharacterLifeStore.state(first.characterId).getJSONObject("profile").getString("care"))
        assertEquals(reset, DigitalLifeProfileStore.birthAt(first.characterId))
        assertEquals("佳辞", DigitalLifeProfileStore.get(first.characterId).creatorName)
        assertEquals(old, DigitalLifeProfileStore.birthAt(other.characterId))
        assertEquals(otherEvents, SharedExperienceTimeline.all(other.characterId))
        val origin = SharedExperienceTimeline.all(first.characterId).single()
        assertEquals(reset, origin.occurredAt)
        assertEquals("生命起点", origin.channel)
        assertTrue(DigitalLifeProfileStore.promptSection(first.characterId, first.displayName, reset).contains("生命第1天"))
        assertFalse(DigitalLifeProfileStore.allowsTimestamp(first.characterId, old.plusSeconds(1)))
        SharedExperienceTimeline.importBackup(oldBackup)
        SharedExperienceTimeline.record("late-old-a", first.characterId, "群聊", "用户", "旧数据不能复活", old.plusSeconds(1), false)
        assertEquals(listOf(origin), SharedExperienceTimeline.all(first.characterId))
        val disk = JSONArray(context.getSharedPreferences("lulu_digital_life_profiles", 0).getString("profiles_v2", "[]"))
        val stored = (0 until disk.length()).map { disk.getJSONObject(it) }.first { it.getString("characterId") == first.characterId }
        assertEquals(reset.toString(), stored.getString("bornAt"))
        val again = reset.plusSeconds(60)
        CharacterRecordReset.clearAll(first.characterId, again)
        assertEquals(again, SharedExperienceTimeline.all(first.characterId).single().occurredAt)
        assertEquals(otherEvents, SharedExperienceTimeline.all(other.characterId))
    }
}
