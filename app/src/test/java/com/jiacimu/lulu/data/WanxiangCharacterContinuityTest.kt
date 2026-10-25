package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class WanxiangCharacterContinuityTest {
    @Test fun residentAgendaKeepsExplicitContactAndPlaceInExecutorArgs() {
        val proposal = JSONObject()
            .put("targetCharacterId", "resident:friend")
            .put("activityArgs", JSONObject().put("text", "上次那本书看完啦")
                .put("planId", "injected"))
        val args = ResidentDailyDecision.agendaArgs("send_character_message", proposal)
        assertEquals("resident:friend", args.optString("targetCharacterId"))
        assertEquals("上次那本书看完啦", args.optString("text"))
        assertFalse(args.has("planId"))
        assertEquals("injected", proposal.getJSONObject("activityArgs").optString("planId"))

        val visit = JSONObject().put("locationCode", "world-courtyard")
            .put("activityArgs", JSONObject().put("worldAction", "visit_public_place")
                .put("locationCode", "old-location"))
        val normalized = ResidentDailyDecision.agendaArgs("digital_world_action", visit)
        assertEquals("world-courtyard", normalized.optString("locationCode"))
        val unrelated = ResidentDailyDecision.agendaArgs("play_solo_game",
            JSONObject().put("targetCharacterId", "resident:friend")
                .put("activityArgs", JSONObject().put("gameId", "game1")))
        assertFalse(unrelated.has("targetCharacterId"))
        assertEquals("game1", unrelated.optString("gameId"))
    }

    @Test fun residentLifeContextExcludesOtherPeopleDraftPlansAndUnfinishedTools() {
        val now = Instant.parse("2026-10-25T06:00:00Z")
        fun event(id: String, person: String, source: String,
            kind: EventEvidenceKind, body: String = "曾真实经过广场") =
            SharedTimelineEvent(id, person, "数字世界", person, body,
                now, source = source, evidenceKind = kind)
        val ok = JSONObject().put("tool", "digital_world_action")
            .put("status", "succeeded")
            .put("result", JSONObject().put("summary", "到了旧桥").toString()).toString()
        val failed = JSONObject().put("tool", "send_character_message")
            .put("status", "failed")
            .put("result", JSONObject().put("error", "对方已不在联系人内").toString()).toString()
        val actual = ResidentDailyDecision.ownLifeEvidence("resident:one", listOf(
            event("planned", "resident:one", "resident-daily", EventEvidenceKind.CharacterStatement),
            event("pending", "resident:one", "tool-router", EventEvidenceKind.ToolResult,
                JSONObject().put("status", "running").toString()),
            event("foreign", "resident:two", "tool-router", EventEvidenceKind.ToolResult, ok),
            event("real", "resident:one", "tool-router", EventEvidenceKind.ToolResult, ok),
            event("failed", "resident:one", "tool-router", EventEvidenceKind.ToolResult, failed),
            event("invented", "resident:one", "tool-router", EventEvidenceKind.CharacterStatement, ok),
            event("routine", "resident:one", "resident-duty", EventEvidenceKind.Observation),
        ))
        assertEquals(listOf("real", "failed", "routine"), actual.map { it.id })
        assertTrue(ResidentDailyDecision.lifeEvidenceLine(actual.first()).contains("到了旧桥"))
        assertTrue(ResidentDailyDecision.lifeEvidenceLine(actual[1]).contains("对方已不在联系人内"))
    }


    @Test fun deletingOnePastMomentAlsoRetractsThePriorRelationshipImpression() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        SharedExperienceTimeline.initialize(app)
        CharacterInnerLifeStore.initialize(app)
        val person = "wanxiang-bond-" + java.util.UUID.randomUUID().toString()
        val first = "wanxiang-first-" + java.util.UUID.randomUUID().toString()
        val second = "wanxiang-second-" + java.util.UUID.randomUUID().toString()
        fun record(id: String, text: String) = SharedExperienceTimeline.record(
            id, person, "私聊", "用户", text,
            source = "message", evidenceKind = EventEvidenceKind.UserStatement,
            triggerExtraction = false,
        )
        fun impression(value: String, reason: String): JSONObject =
            JSONObject().put("social", JSONObject().put("targetId", "user")
                .put("interpretation", value).put("reason", reason))
        try {
            record(first, "我以后再跟你说")
            CharacterInnerLifeStore.observe(person, first, "真实收到暂缓答复",
                impression("我以为她不想谈", "她说以后再说"),
                allowedSocialIds = setOf("user"))
            record(second, "我不是不想谈，只是当时赶时间")
            CharacterInnerLifeStore.observe(person, second, "真实收到新的解释",
                impression("原来只是赶时间", "她明确澄清了原因"),
                allowedSocialIds = setOf("user"))
            val before = CharacterSocialRelationship.perspectivesFor(person, listOf("user"))
            assertTrue(before.contains("之前曾认为=我以为她不想谈"))
            assertTrue(before.contains("具体依据=她明确澄清了原因"))
            assertTrue(CharacterInnerLifeStore.compactContext(person)
                .contains("以前曾这样理解，现已调整：我以为她不想谈"))
            SharedExperienceTimeline.deleteEvent(first)
            val after = CharacterSocialRelationship.perspectivesFor(person, listOf("user"))
            assertTrue(after.contains("原来只是赶时间"))
            assertFalse(after.contains("我以为她不想谈"))
            assertFalse(CharacterInnerLifeStore.compactContext(person).contains("我以为她不想谈"))
            assertEquals("", CharacterSocialRelationship.perspective(person, "user")?.priorImpression)
        } finally {
            SharedExperienceTimeline.deleteEvent(first)
            SharedExperienceTimeline.deleteEvent(second)
            CharacterInnerLifeStore.clear(person)
        }
    }


    @Test fun coldStartRoutineReadsItsPersistedResidentLeisureChoice() {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as android.content.Context
        val local = java.time.LocalDate.of(2026, 10, 25)
        val at = local.atTime(18, 30).atZone(java.time.ZoneId.systemDefault()).toInstant()
        val person = DigitalWorldResident(
            name = "临时值班居民", identity = "店员",
            homeLocationCode = DigitalWorldPublicPlaces.CAFE,
            currentLocationCode = DigitalWorldPublicPlaces.CAFE,
            createdAt = at,
        )
        val prefs = app.getSharedPreferences("lulu_resident_daily_decisions_v1",
            android.content.Context.MODE_PRIVATE)
        val key = "plan:${person.id}"
        try {
            assertTrue(prefs.edit().putString(key, JSONObject()
                .put("date", ResidentDailyDecision.date(at))
                .put("evening", "games")
                .put("eveningActivity", "想去游戏厅听听新歌")
                .toString()).commit())
            val planned = ResidentDailyRoutine.plan(person, at, context = app)
            assertEquals("evening", planned.slot)
            assertEquals(DigitalWorldPublicPlaces.GAME_HALL, planned.location)
            assertEquals("想去游戏厅听听新歌", planned.intendedActivity)
            assertFalse(planned.activity.contains("听听新歌")) // choice is not execution
        } finally {
            prefs.edit().remove(key).commit()
        }
    }

    @Test fun sharedPrivateAndVoiceGuidanceAllowsSpontaneousSpeechWithoutInventedMisunderstanding() {
        val private = CharacterExpressionGuide.channelNote(CharacterSpeechChannel.PRIVATE_CHAT)
        val phone = CharacterExpressionGuide.channelNote(CharacterSpeechChannel.PHONE)
        assertTrue(private.contains("不为了活人感故意制造误会"))
        assertTrue(private.contains("不在每轮末尾附一段总结"))
        assertTrue(phone.contains("没有听清便承认没听清"))
        assertTrue(phone.contains("实时通话优先低延迟"))
        assertFalse(phone.contains("每次必须"))
    }
}
