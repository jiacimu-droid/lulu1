package com.jiacimu.lulu.data

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WakeActionPlannerTest {
    @Test fun malformedModelAnswerStillUsesAudibleFallback() {
        assertEquals(WakeActionPlanner.Cue.RING,
            WakeActionPlanner.parseChoice("not json", true, true).cue)
    }

    @Test fun musicRequiresPermissionEvenIfModelWantsIt() {
        val raw = """{"cue":"music","reason":"想让她精神一点","volumePercent":42}"""
        val noPermission = WakeActionPlanner.parseChoice(raw, false, true)
        assertEquals(WakeActionPlanner.Cue.MUSIC, noPermission.cue)
        assertTrue(noPermission.executionIssue.contains("权限"))
        assertFalse(noPermission.systemFallback)
        assertEquals(WakeActionPlanner.Cue.MUSIC,
            WakeActionPlanner.parseChoice(raw, true, true).cue)
    }

    @Test fun speechWithoutLiveCallMustNotSilenceTheAlarm() {
        val raw = """{"cue":"speak","openingLine":"该起床啦"}"""
        val unconnected = WakeActionPlanner.parseChoice(raw, true, false)
        assertEquals(WakeActionPlanner.Cue.SPEAK, unconnected.cue)
        assertTrue(unconnected.executionIssue.contains("尚未连接"))
        assertEquals(WakeActionPlanner.Cue.SPEAK,
            WakeActionPlanner.parseChoice(raw, true, true).cue)
    }

    @Test fun eachNewDecisionChoosesOneCueNotAPrearrangedBatch() {
        val ring = WakeActionPlanner.parseChoice(
            """{"cue":"ring","ringSeconds":99,"reason":"已经说过话仍没确认醒来，想再响铃"}""", true, true)
        assertEquals(WakeActionPlanner.Cue.RING, ring.cue)
        assertEquals(10, ring.ringSeconds)
        val talk = WakeActionPlanner.parseChoice(
            """{"cue":"speak","openingLine":"再叫你一下，好不好？"}""", true, true)
        assertEquals(WakeActionPlanner.Cue.SPEAK, talk.cue)
        assertEquals("再叫你一下，好不好？", talk.openingLine)
        val music = WakeActionPlanner.parseChoice("""{"cue":"music"}""", true, true)
        assertEquals(WakeActionPlanner.Cue.MUSIC, music.cue)
        assertEquals(6, WakeActionPlanner.parseChoice("invalid", true, true).ringSeconds)
        assertEquals(WakeActionPlanner.Cue.MUSIC, WakeActionPlanner.parseChoice(
            """{"cue":"music"}""", false, true).cue)
    }

    @Test fun modelChosenWakeMusicSurvivesOnlyTheMatchingAnsweredCall() {
        val context = RuntimeEnvironment.getApplication()
        val characterId = "wake-music-handoff-test"
        val taskId = "morning-t123"
        val permissions = context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
        permissions.edit().putBoolean("$characterId:media", true)
            .putBoolean("$characterId:wake_music_enabled", true).commit()
        WakeMusicCoordinator.cancelPending(context, characterId)
        try {
            val staged = WakeMusicCoordinator.onScheduledWake(
                context, characterId, taskId, defer = true, volumePercent = 42)
            assertEquals(true, staged.contains("接通后"))
            assertNull(WakeMusicCoordinator.takeAnsweredMusicPlan(context, characterId, "wrong-task"))
            val selected = WakeMusicCoordinator.takeAnsweredMusicPlan(context, characterId, taskId)
            assertNotNull(selected)
            assertEquals(42, selected?.volumePercent)
            // An answered task consumes the explicit choice once.
            assertNull(WakeMusicCoordinator.takeAnsweredMusicPlan(context, characterId, taskId))
            WakeMusicCoordinator.onScheduledWake(context, characterId, "missed", defer = true)
            WakeMusicCoordinator.onMissedCall(context, characterId, "missed")
            assertNull(WakeMusicCoordinator.takeAnsweredMusicPlan(context, characterId, "missed"))
        } finally {
            WakeMusicCoordinator.cancelPending(context, characterId)
        }
    }

    @Test fun eachFollowUpKeepsModelsContactChoiceAndRequestedReviewTime() {
        val model = WakeActionPlanner.parseChoice(
            """{"cue":"wait","contact":"wait","reviewAfterSeconds":420,"reason":"再观察一下"}""",
            true, false)
        assertEquals(WakeActionPlanner.Cue.WAIT, model.cue)
        assertEquals(WakeActionPlanner.Contact.WAIT, model.contact)
        assertEquals(420L, model.reviewAfterSeconds)
        assertFalse(model.systemFallback)
        val call = WakeActionPlanner.parseChoice(
            """{"cue":"ring","contact":"call","reviewAfterSeconds":1400}""",
            true, false)
        assertEquals(WakeActionPlanner.Contact.CALL, call.contact)
        assertNull(call.reviewAfterSeconds)
        val message = WakeActionPlanner.parseChoice(
            """{"cue":"speak","contact":"message","openingLine":"起床啦"}""",
            false, false)
        assertEquals(WakeActionPlanner.Contact.MESSAGE, message.contact)
        val fallback = WakeActionPlanner.parseChoice("not json", true, true)
        assertTrue(fallback.systemFallback)
        assertEquals(WakeActionPlanner.Cue.RING, fallback.cue)
    }

    @Test fun firstPromisedRingUsesAnExplicitSystemFallbackForConflictingModelChoice() {
        val conflicting = WakeActionPlanner.parseChoice(
            """{"cue":"wait","contact":"call","reason":"想先观察"}""", true, true)
        // Follow-up decisions remain exactly what the character chose.
        assertEquals(WakeActionPlanner.Cue.WAIT,
            WakeActionPlanner.honorFirstWakePromise(conflicting, 1).cue)
        assertFalse(WakeActionPlanner.honorFirstWakePromise(conflicting, 1).systemFallback)
        val initial = WakeActionPlanner.honorFirstWakePromise(conflicting, 0)
        assertEquals(WakeActionPlanner.Cue.RING, initial.cue)
        assertTrue(initial.systemFallback)
        assertTrue(initial.executionIssue.contains("原选择=WAIT"))
        assertEquals(WakeActionPlanner.Cue.RING,
            WakeActionPlanner.honorFirstWakePromise(
                WakeActionPlanner.parseChoice("""{"cue":"ring"}""", true, true), 0).cue)
    }

    @Test fun invalidVolumeIsNotCarriedIntoExecutor() {
        val raw = """{"cue":"music","volumePercent":200}"""
        assertNull(WakeActionPlanner.parseChoice(raw, true, true).volumePercent)
    }
}
