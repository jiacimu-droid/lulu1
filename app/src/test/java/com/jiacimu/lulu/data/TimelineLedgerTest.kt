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
    }
}
