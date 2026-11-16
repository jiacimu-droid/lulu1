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

/** A meeting shares private chat's owner-statement, causal provenance and rewind rules. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MeetingCausalEvidenceTest {
    @Before fun before() = releaseTimelineDatabase()
    @After fun after() = releaseTimelineDatabase()

    private fun releaseTimelineDatabase() {
        synchronized(SharedExperienceTimeline) {
            val field = SharedExperienceTimeline::class.java.getDeclaredField("helper")
            field.isAccessible = true
            (field.get(SharedExperienceTimeline) as? android.database.sqlite.SQLiteOpenHelper)?.close()
            field.set(SharedExperienceTimeline, null)
        }
    }

    @Test fun ownerDraftIsAnExactUserStatementAndGeneratedSceneIsNot() {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        MigratedDomainStores.characters.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        DigitalWorldStore.initialize(context)
        MeetingExperienceStore.initialize(context)
        CompanionPresenceStore.initialize(context)
        val first = MigratedDomainStores.characters.create("原话见证甲", "温柔")
        val second = MigratedDomainStores.characters.create("原话见证乙", "好奇")
        val now = Instant.now()
        listOf(first, second).forEach {
            DigitalLifeProfileStore.registerNewLife(
                it.characterId, it.displayName, "用户", now.minusSeconds(180))
        }
        val session = DigitalWorldStore.startMeeting(
            listOf(first.characterId, second.characterId), "云眠原", now = now.minusSeconds(90))
        val exchange = MeetingExperienceStore.beginExchange(session, "我今天有一点不开心", now = now.minusSeconds(20))
        val raw = "我今天有一点不开心"
        DigitalWorldStore.recordMeetingOriginalInput(session, exchange.id, raw, now.minusSeconds(10))
        // A replay of a resumed generator must never duplicate or revise the owner input.
        DigitalWorldStore.recordMeetingOriginalInput(session, exchange.id, raw, now.minusSeconds(10))

        for (role in listOf(first, second)) {
            val id = DigitalWorldStore.meetingInputEvidenceId(session.id, exchange.id, role.characterId)
            val original = SharedExperienceTimeline.eventsByIds(role.characterId, listOf(id)).single()
            assertEquals(raw, original.content)
            assertEquals(EventEvidenceKind.UserStatement, original.evidenceKind)
            assertEquals("meeting", original.source)
            assertEquals(session.id, original.sessionId)
            assertEquals(1L, original.revision)
        }
        val sceneId = "meeting-${session.id}-turn-expanded-viewer-${first.characterId}"
        DigitalWorldStore.recordMeetingTimeline(
            session, first.characterId, "turn-expanded", "主人",
            "她可能在笑（模型扩写，并非主人原话）", now, false,
            evidenceKind = EventEvidenceKind.Observation)
        val projected = SharedExperienceTimeline.eventsByIds(first.characterId, listOf(sceneId)).single()
        assertEquals(EventEvidenceKind.Observation, projected.evidenceKind)
        assertFalse(projected.isUserMemoryStatement())
    }

    @Test fun rewindingOneExchangeWithdrawsItsEvidenceAndPrivateVoiceButKeepsLaterHistory() {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        MigratedDomainStores.characters.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        DigitalWorldStore.initialize(context)
        MeetingExperienceStore.initialize(context)
        CompanionPresenceStore.initialize(context)
        val role = MigratedDomainStores.characters.create("原话撤回见证者", "可靠")
        val now = Instant.now()
        DigitalLifeProfileStore.registerNewLife(
            role.characterId, role.displayName, "用户", now.minusSeconds(200))
        val session = DigitalWorldStore.startMeeting(
            listOf(role.characterId), "云眠原", now = now.minusSeconds(100))
        val old = MeetingExperienceStore.beginExchange(session, "刚才那句话", now = now.minusSeconds(25))
        DigitalWorldStore.recordMeetingOriginalInput(session, old.id, old.rawDraft, now.minusSeconds(20))
        val evidenceId = DigitalWorldStore.meetingInputEvidenceId(session.id, old.id, role.characterId)
        CharacterInnerLifeStore.recordCausalTransition(
            characterId = role.characterId, evidenceId = evidenceId,
            appraisal = null, innerLife = null, innerThoughtBasis = null,
            selectedAction = "reply", innerThought = "我想认真回答", reason = "在意主人",
            now = now.minusSeconds(19))
        CharacterInnerLifeStore.recordInnerVoice(
            role.characterId, evidenceId, "我想认真回答", now.minusSeconds(19))
        assertTrue(
            CharacterInnerLifeStore.historySnapshot(role.characterId)
                .optJSONArray("causalTransitions")!!.length() > 0)

        val later = MeetingExperienceStore.beginExchange(session, "后来新的原话", now = now.minusSeconds(5))
        DigitalWorldStore.recordMeetingOriginalInput(session, later.id, later.rawDraft, now.minusSeconds(4))
        val laterId = DigitalWorldStore.meetingInputEvidenceId(session.id, later.id, role.characterId)
        assertEquals(1, SharedExperienceTimeline.eventsByIds(role.characterId, listOf(laterId)).size)

        DigitalWorldStore.rewindMeetingExchanges(session.id, listOf(old), old.beforeScene)
        assertTrue(SharedExperienceTimeline.eventsByIds(role.characterId, listOf(evidenceId)).isEmpty())
        assertEquals(1, SharedExperienceTimeline.eventsByIds(role.characterId, listOf(laterId)).size)
        val mental = CharacterInnerLifeStore.historySnapshot(role.characterId)
        val traces = mental.optJSONArray("causalTransitions")
        assertTrue(traces == null || (0 until traces.length()).none {
            traces.optJSONObject(it)?.optString("evidenceId") == evidenceId
        })
        val voices = mental.optJSONArray("innerVoices")
        assertTrue(voices == null || (0 until voices.length()).none {
            voices.optJSONObject(it)?.optString("evidenceId") == evidenceId
        })

        // Deletion creates a tombstone. The resumed generator must use a fresh exchange ID.
        val replacement = MeetingExperienceStore.beginExchange(session, old.rawDraft, now = now)
        DigitalWorldStore.recordMeetingOriginalInput(session, replacement.id, replacement.rawDraft, now)
        val fresh = DigitalWorldStore.meetingInputEvidenceId(session.id, replacement.id, role.characterId)
        assertNotEquals(evidenceId, fresh)
        assertEquals(1, SharedExperienceTimeline.eventsByIds(role.characterId, listOf(fresh)).size)
    }

    @Test fun aNonUserMomentHasItsOwnObservationAnchorAndRewindsPrecisely() {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        MigratedDomainStores.characters.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        DigitalWorldStore.initialize(context)
        MeetingExperienceStore.initialize(context)
        CompanionPresenceStore.initialize(context)
        val role = MigratedDomainStores.characters.create("身体见证者", "平静")
        val now = Instant.now()
        DigitalLifeProfileStore.registerNewLife(role.characterId, role.displayName, "用户", now.minusSeconds(100))
        val session = DigitalWorldStore.startMeeting(
            listOf(role.characterId), "云眠原", now = now.minusSeconds(60))
        val exchange = MeetingExperienceStore.beginExchange(session, "现场行为", now = now.minusSeconds(5))
        val id = DigitalWorldStore.recordMeetingExchangeObservation(
            session, exchange.id, role.characterId, "主人刚刚松开了手", now)
        val event = SharedExperienceTimeline.eventsByIds(role.characterId, listOf(id)).single()
        assertEquals(EventEvidenceKind.Observation, event.evidenceKind)
        assertEquals("主人刚刚松开了手", event.content)
        DigitalWorldStore.rewindMeetingExchanges(session.id, listOf(exchange), exchange.beforeScene)
        assertTrue(SharedExperienceTimeline.eventsByIds(role.characterId, listOf(id)).isEmpty())
    }
}
