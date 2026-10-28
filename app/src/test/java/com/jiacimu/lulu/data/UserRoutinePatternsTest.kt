package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class UserRoutinePatternsTest {
    @Before fun before() { MemoryTestEnvironment.release() }
    @After fun after() { MemoryTestEnvironment.release() }

    @Test fun separateDaytimeAndNighttimeActivitiesWithoutInventingSleep() {
        val now = Instant.parse("2026-10-26T15:00:00Z")
        fun event(id: String, time: String, source: String = "user-activity:sleep-guidance:start",
            role: String = "one"): SharedTimelineEvent = SharedTimelineEvent(
            id = id, characterId = role, channel = "电话互动",
            speaker = "实际通话操作", content = "电话实际启用了一种活动模式",
            occurredAt = Instant.parse(time), sessionId = id,
            source = source, evidenceKind = EventEvidenceKind.Observation,
        )
        val data = (0..2).flatMap { d ->
            val date = "2026-10-${20 + d}"
            listOf(event("noon-$d", "${date}T12:30:00Z"),
                event("evening-$d", "${date}T22:30:00Z"))
        } + listOf(
            event("one-off", "2026-10-23T04:00:00Z"),
            event("end", "2026-10-22T12:40:00Z", "user-activity:sleep-guidance:end"),
            event("untrusted", "2026-10-22T12:35:00Z", "interaction"),
        )
        val found = UserRoutinePatterns.detect(data, now, ZoneId.of("UTC"))
        assertEquals(2, found.size)
        assertEquals(setOf(750, 1350), found.map { it.approximateMinute }.toSet())
        assertTrue(found.all { it.days == 3 && it.sourceIds.size == 3 })
        assertFalse(found.any { "end" in it.sourceIds || "untrusted" in it.sourceIds })
    }

    @Test fun sameTypeOfActivityInTwoCharactersDoesNotCreateAThirdSharedHabit() {
        val now = Instant.parse("2026-10-28T16:00:00Z")
        fun observed(role: String, day: Int): SharedTimelineEvent =
            SharedTimelineEvent(
                id = "activity-$role-$day", characterId = role, channel = "电话互动",
                speaker = "实际操作", content = "本角色通话里实际开启睡前语音模式",
                occurredAt = Instant.parse("2026-10-${20 + day}T12:30:00Z"),
                sessionId = "call-$role-$day",
                source = "user-activity:sleep-guidance:start",
                evidenceKind = EventEvidenceKind.Observation,
            )
        val mixed = listOf(observed("xinghe", 0), observed("jiangdu", 1),
            observed("jiangdu", 2), observed("stranger", 3))
        // The old code grouped only by source, incorrectly inferring a
        // four-day shared "user routine" from four separate perspectives.
        val patterns = UserRoutinePatterns.detect(mixed, now, ZoneId.of("UTC"))
        assertEquals(1, patterns.size)
        assertEquals(2, patterns.single().days)
        assertTrue(patterns.single().sourceIds.all { it.contains("jiangdu") })
        assertFalse(patterns.any { it.sourceIds.any { id -> id.contains("xinghe") } })
        val onlyDifferentPeople = listOf(observed("xinghe", 0),
            observed("jiangdu", 1), observed("stranger", 2))
        assertTrue(UserRoutinePatterns.detect(
            onlyDifferentPeople, now, ZoneId.of("UTC")).isEmpty())
    }

    @Test fun habitNeedsThreeIndependentDaysAndCanBeRevokedBySourceDeletion() {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterDevelopmentStore.initialize(context)
        MigratedDomainStores.characters.initialize(context)
        val role = "routine-evidence-role"
        val persona = CharacterRuntime.personaConstraintSnapshot(role)
        val base = Instant.parse("2026-10-20T12:35:00Z")
        val ids = (0..2).map { day ->
            val id = "routine-noon-$day"
            SharedExperienceTimeline.record(
                eventId = id, characterId = role,
                channel = "电话互动", speaker = "实际通话操作",
                content = "本通电话实际开启哄睡模式，未证实主人入睡",
                occurredAt = base.plusSeconds(day * 86_400L),
                triggerExtraction = false, sessionId = "call-noon-$day",
                source = "user-activity:sleep-guidance:start",
                evidenceKind = EventEvidenceKind.Observation,
            )
            id
        }
        assertFalse(CharacterDevelopmentStore.applyProposal(role, "user:noon-sleep",
            DevelopmentKind.UserHabit, "主人每天都会在中午睡着",
            ids.take(2), emptyList(), persona))
        assertTrue(CharacterDevelopmentStore.applyProposal(role, "user:noon-sleep",
            DevelopmentKind.UserHabit, "最近几个不同日子的午间，我们都启用过哄睡模式",
            ids, emptyList(), persona))
        assertEquals(1, CharacterDevelopmentStore.active(role).count { it.kind == DevelopmentKind.UserHabit })
        assertTrue(CharacterDevelopmentStore.active("someone-else").isEmpty())

        val pattern = UserRoutinePatterns.detect(SharedExperienceTimeline.recentUserActivities(role),
            Instant.parse("2026-10-26T00:00:00Z"), ZoneId.of("UTC"))
        assertTrue(pattern.any { it.days == 3 && it.sourceIds.size == 3 })
        SharedExperienceTimeline.deleteEvent(ids.first())
        assertFalse(CharacterDevelopmentStore.active(role).any { it.slot == "user:noon-sleep" })
        assertTrue(UserRoutinePatterns.detect(SharedExperienceTimeline.recentUserActivities(role),
            Instant.parse("2026-10-26T00:00:00Z"), ZoneId.of("UTC"))
            .all { it.days < 3 })
    }

    @Test fun connectedCallAndSleepModeAreSingleRealEventsPerSession() {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        val role = "routine-call-role"
        val callId = "call-single-event"
        val time = Instant.parse("2026-10-25T12:45:00Z")

        InteractionSignalBridge.recordCallStart(role, callId, "", time)
        InteractionSignalBridge.recordCallStart(role, callId, "", time)
        InteractionSignalBridge.recordUserActivity(role, callId, "sleep-guidance", "start", time)
        InteractionSignalBridge.recordUserActivity(role, callId, "sleep-guidance", "start", time.plusSeconds(60))
        InteractionSignalBridge.recordUserActivity(role, callId, "sleep-guidance", "end", time.plusSeconds(600))

        val activities = SharedExperienceTimeline.all(role).filter {
            it.source.startsWith("user-activity:")
        }
        assertEquals(3, activities.size)
        assertEquals(2, activities.count(UserRoutinePatterns::isStart))
        assertEquals(1, activities.count { it.source == "user-activity:sleep-guidance:start" })
        assertEquals(1, activities.count { it.source == "user-activity:phone-call:start" })
        assertFalse(activities.any { it.content.contains("主人已经睡着") })

        InteractionSignalBridge.recordUserActivity(role, "listening-visit-one",
            "listen-together", "start", time.plusSeconds(720), channel = "一起听")
        val shared = SharedExperienceTimeline.all(role).single {
            it.source == "user-activity:listen-together:start"
        }
        assertEquals("一起听", shared.channel)
        assertTrue(UserRoutinePatterns.isStart(shared))
        assertTrue(shared.content.contains("是否听到声波") && shared.content.contains("独立感知回执"))
    }

    @Test fun unrelatedOrSameDayActionsCannotCreateObservedHabit() {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterDevelopmentStore.initialize(context)
        MigratedDomainStores.characters.initialize(context)
        val role = "routine-negative-role"
        val persona = CharacterRuntime.personaConstraintSnapshot(role)
        val base = Instant.parse("2026-10-20T12:00:00Z")
        val ids = (0..2).map { day ->
            val id = "negative-$day"
            SharedExperienceTimeline.record(id, role, "电话互动", "实际操作", "真实活动",
                base.plusSeconds(day * 86_400L), triggerExtraction = false,
                sessionId = "different-$day",
                source = "user-activity:activity-$day:start",
                evidenceKind = EventEvidenceKind.Observation)
            id
        }
        assertFalse(CharacterDevelopmentStore.applyProposal(role, "user:unrelated",
            DevelopmentKind.UserHabit, "三个不相干活动被误认成日常习惯",
            ids, emptyList(), persona))

        val sameDay = (0..2).map { index ->
            val id = "same-day-$index"
            SharedExperienceTimeline.record(id, role, "电话互动", "实际操作", "开启活动",
                base.plusSeconds(index * 1800L), triggerExtraction = false,
                sessionId = "same-day-call-$index",
                source = "user-activity:sleep-guidance:start",
                evidenceKind = EventEvidenceKind.Observation)
            id
        }
        assertFalse(CharacterDevelopmentStore.applyProposal(role, "user:same-day",
            DevelopmentKind.UserHabit, "同一天的三通电话被误认成长期习惯",
            sameDay, emptyList(), persona))
    }
}
