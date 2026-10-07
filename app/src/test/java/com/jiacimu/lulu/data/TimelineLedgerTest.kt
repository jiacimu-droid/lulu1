package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class TimelineLedgerTest {
    @Test fun migrationDedupIsolationCorrectionAndDeletionAreDurable() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.openOrCreateDatabase("shared_experience_timeline.db", 0, null).use { db ->
            db.execSQL("CREATE TABLE timeline_events(id TEXT PRIMARY KEY NOT NULL,character_id TEXT NOT NULL,channel TEXT NOT NULL,speaker TEXT NOT NULL,content TEXT NOT NULL,occurred_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE deleted_timeline_events(event_id TEXT PRIMARY KEY NOT NULL,deleted_at INTEGER NOT NULL)")
            db.execSQL("INSERT INTO timeline_events VALUES('old','character-a','私聊','用户','旧数据',1)")
            db.version = 2
        }
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterDevelopmentStore.initialize(context)
        assertEquals("旧数据", SharedExperienceTimeline.all("character-a").single().content)
        val time = Instant.now()
        fun record(character: String, content: String, revision: Long? = null) = SharedExperienceTimeline.record(
            "canonical", character, "私聊", "用户", content, time, triggerExtraction = false,
            sessionId = "session", source = "message", evidenceKind = EventEvidenceKind.UserStatement, expectedRevision = revision)
        record("character-a", "初始记录")
        record("character-a", "初始记录")
        assertEquals(1L, SharedExperienceTimeline.eventsByIds("character-a", listOf("canonical")).single().revision)
        record("character-b", "不能覆盖其他人的经历")
        assertTrue(SharedExperienceTimeline.all("character-b").isEmpty())
        record("character-a", "迟到的旧更正", 0)
        assertEquals("初始记录", SharedExperienceTimeline.eventsByIds("character-a", listOf("canonical")).single().content)
        record("character-a", "已核准更正", 1)
        assertEquals(2L, SharedExperienceTimeline.eventsByIds("character-a", listOf("canonical")).single().revision)
        val backup = SharedExperienceTimeline.exportBackup()
        SharedExperienceTimeline.importBackup(backup)
        assertEquals(2, SharedExperienceTimeline.all("character-a").size)
        SharedExperienceTimeline.deleteEvent("canonical")
        SharedExperienceTimeline.importBackup(backup)
        record("character-a", "不能复活已删除的经历")
        assertTrue(SharedExperienceTimeline.eventsByIds("character-a", listOf("canonical")).isEmpty())

        MigratedDomainStores.characters.initialize(context)
        val persona = MigratedDomainStores.characters.get("character-a").persona
        val evidence = (0..2).map { "growth-$it" }
        evidence.forEach { id -> SharedExperienceTimeline.record(id, "character-a", "私聊", "用户", "以后回答简短一些", time,
            triggerExtraction = false, evidenceKind = EventEvidenceKind.UserStatement) }
        fun propose(character: String = "character-a", snapshot: String = persona, kind: DevelopmentKind = DevelopmentKind.Habit) =
            CharacterDevelopmentStore.applyProposal(character, "concise", kind, "回答更简短", evidence, emptyList(), snapshot)
        assertFalse(propose("character-b"))
        assertFalse(propose(snapshot = persona + "不允许替换人设"))
        assertFalse(propose(kind = DevelopmentKind.VerifiedMethod)) // User wishes do not prove a verified tool method.
        assertTrue(propose())
        assertEquals(1, CharacterDevelopmentStore.active("character-a").size)
        SharedExperienceTimeline.record(evidence[0], "character-a", "私聊", "用户", "更正后仍希望简短", time,
            triggerExtraction = false, evidenceKind = EventEvidenceKind.UserStatement, expectedRevision = 1)
        assertTrue(CharacterDevelopmentStore.active("character-a").isEmpty())
        assertTrue(propose()) // New evidence revision permits a new, traceable version.
        val updated = CharacterDevelopmentStore.active("character-a").single()
        assertEquals(2, updated.version)
        CharacterLifeStore.initialize(context)
        CharacterLifeStore.setProfile("character-a", "care", "通过具体行动表达")
        assertTrue(CharacterDevelopmentStore.active("character-a").isEmpty())
        assertFalse(propose()) // Old locked-profile snapshots cannot add or reactivate growth.
        CharacterLifeStore.setProfile("character-a", "care", "")
        CharacterDevelopmentStore.retire("character-a", updated.id)
        assertFalse(propose()) // The same evidence cannot resurrect a manually retired record.
        val repository = LocalMemoryRepository().apply { initialize(context) }
        repeat(150) { index ->
            SharedExperienceTimeline.record("backlog-$index", "backlog-role", "私聊", "用户", "消息$index",
                time.plusSeconds(index.toLong()), triggerExtraction = false, evidenceKind = EventEvidenceKind.UserStatement)
        }
        val recentContext = repository.contextTimelineEvents("backlog-role")
        assertEquals(com.jiacimu.lulu.core.MemoryPolicy().rawContextMessageCount, recentContext.size)
        assertEquals("backlog-149", recentContext.last().id)
        assertTrue(repository.pendingTimelineEvents("backlog-role").size > recentContext.size)
    }
}
