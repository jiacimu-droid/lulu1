package com.jiacimu.lulu.data

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CompanionPresenceFreshnessTest {
    @Test fun endedMorningCallDoesNotRemainLiveInLaterChatMoment() {
        val context = RuntimeEnvironment.getApplication() as Context
        CompanionPresenceStore.initialize(context)
        val role = "morning-call-moment-freshness"
        CompanionPresenceStore.clearCharacter(role)
        val morning = Instant.parse("2026-11-16T00:00:00Z")

        CompanionPresenceStore.update(role, "看书中", "坐着", "刚刚还想聊聊", "轻松",
            "聊天", morning)
        CompanionPresenceStore.beginCall(role)
        CompanionPresenceStore.update(
            role, "电话接通中，等着刚开麦的她出声", "举着手机听她说话",
            null, null, "通话", morning.plusSeconds(90),
        )
        assertTrue(CompanionPresenceStore.isInCall(role))
        CompanionPresenceStore.finishCall(role)
        assertFalse(CompanionPresenceStore.isInCall(role))
        assertEquals("通话已结束", CompanionPresenceStore.current(role)!!.statusText)

        val newMessage = morning.plusSeconds(3 * 3600)
        // The character may reply normally without producing another heart voice.
        CompanionPresenceStore.update(role, null, null, "", "轻松", "聊天回复", newMessage)
        val latest = CompanionPresenceStore.current(role)!!
        assertEquals("", latest.statusText)
        assertEquals("", latest.gesture)
        assertEquals(newMessage, latest.updatedAt)
        assertNull(PresenceHistoryPresentation.latestVoice(
            latest, CompanionPresenceStore.histories.value[role].orEmpty(), false))
        assertTrue(CompanionPresenceStore.histories.value[role].orEmpty().any {
            it.innerThought == "刚刚还想聊聊"
        })
        CompanionPresenceStore.clearCharacter(role)
    }

    @Test fun oldGameCompletionReceiptIsHistoricalNotTheNextChatAction() {
        val context = RuntimeEnvironment.getApplication() as Context
        CompanionPresenceStore.initialize(context)
        val role = "game-completion-moment-freshness"
        CompanionPresenceStore.clearCharacter(role)
        val playedAt = Instant.parse("2026-11-16T00:02:00Z")
        val receipt = "独自在《方块小岛》玩了13回合，总回合81"
        CompanionPresenceStore.update(role, "", receipt, "", "轻松", "后台主动感知",
            playedAt, gestureVerified = true)
        CompanionPresenceStore.update(role, null, null, "", null, "聊天回复",
            playedAt.plusSeconds(180))
        assertEquals("", CompanionPresenceStore.current(role)!!.gesture)
        assertFalse(CompanionPresenceStore.current(role)!!.gestureVerified)
        assertTrue(CompanionPresenceStore.histories.value[role].orEmpty().any {
            it.gesture == receipt && it.gestureVerified
        })
        CompanionPresenceStore.clearCharacter(role)
    }

    @Test fun explicitlyBlankStatusClearsStaleStateWithoutInventingNewMind() {
        val context = RuntimeEnvironment.getApplication() as Context
        CompanionPresenceStore.initialize(context)
        val role = "explicit-clear-presence"
        CompanionPresenceStore.clearCharacter(role)
        val at = Instant.parse("2026-11-16T00:03:00Z")
        CompanionPresenceStore.update(role, "正在翻书", null, null, null, "聊天", at)
        CompanionPresenceStore.update(role, "", null, "", null, "聊天", at.plusSeconds(60))
        assertEquals("", CompanionPresenceStore.current(role)!!.statusText)
        assertEquals(at.plusSeconds(60), CompanionPresenceStore.current(role)!!.updatedAt)
        CompanionPresenceStore.clearCharacter(role)
    }
}
