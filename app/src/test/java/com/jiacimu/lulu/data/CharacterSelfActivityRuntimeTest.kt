package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CharacterSelfActivityRuntimeTest {
    private val available = setOf("real-book-7")

    @Test fun realReceiptConsumesOnlyTheMatchingConsideration() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("lulu_own_activity_followthrough_v1", 0)
        val idea = JSONObject().put("stage", "considering").put("action", "reading")
            .put("whyNow", "想看下一章节").put("createdAt", System.currentTimeMillis())
        prefs.edit().putString("considering:receipt-role", idea.toString()).commit()
        CharacterSelfActivityRuntime.resolveConsideration(context, "receipt-role", "play_solo_game")
        assertNotNull(CharacterSelfActivityRuntime.latestConsideration(context, "receipt-role"))
        CharacterSelfActivityRuntime.resolveConsideration(context, "receipt-role", "read_book")
        assertNull(CharacterSelfActivityRuntime.latestConsideration(context, "receipt-role"))
    }

    @Test fun mereHeartVoiceAndUnchosenIdeasNeverExecuteAnything() {
        assertNull(CharacterSelfActivityRuntime.parse(null, available, digitalLife = true))
        assertNull(CharacterSelfActivityRuntime.parse(JSONObject()
            .put("action", "reading").put("readingBookId", "real-book-7"),
            available, digitalLife = true))
        assertNull(CharacterSelfActivityRuntime.parse(JSONObject()
            .put("stage", "thinking").put("action", "reading")
            .put("readingBookId", "real-book-7"), available, true))
        assertNull(CharacterSelfActivityRuntime.parse(JSONObject()
            .put("stage", "decided").put("action", "reading")
            .put("readingBookId", "a-book-i-made-up"), available, true))
    }

    @Test fun onlyKnownExecutableActivityMayBeScheduled() {
        val reading = CharacterSelfActivityRuntime.parse(JSONObject()
            .put("stage", "decided").put("action", "reading")
            .put("readingBookId", "real-book-7"), available, true)
        assertEquals("read_book", reading!!.tool)
        assertEquals("real-book-7", reading.args.getString("readingBookId"))
        assertEquals("play_solo_game", CharacterSelfActivityRuntime.parse(JSONObject()
            .put("stage", "decided").put("action", "solo_game")
            .put("gameId", "memory_match"), available, true)!!.tool)
        assertNull(CharacterSelfActivityRuntime.parse(JSONObject()
            .put("stage", "decided").put("action", "solo_game")
            .put("gameId", "unavailable-game"), available, true))
    }

    @Test fun digitalWorldCannotPretendToReadWithoutARealBookReceipt() {
        assertNull(CharacterSelfActivityRuntime.parse(JSONObject()
            .put("stage", "decided").put("action", "digital_world")
            .put("worldAction", "use_location").put("activityId", "quiet_read"),
            available, true))
        assertNull(CharacterSelfActivityRuntime.parse(JSONObject()
            .put("stage", "decided").put("action", "digital_world")
            .put("worldAction", "go_home"), available, digitalLife = false))
        val world = CharacterSelfActivityRuntime.parse(JSONObject()
            .put("stage", "decided").put("action", "digital_world")
            .put("worldAction", "visit_public_place").put("location", "cloud_meadow"),
            available, true)
        assertEquals("digital_world_action", world!!.tool)
        assertEquals("cloud_meadow", world.args.getString("location"))
    }

    @Test fun actualPersistentIncidentNeedsLiveIdLocationAndAllowedApproach() {
        val idea = JSONObject().put("stage", "decided")
            .put("action", "digital_world").put("worldAction", "handle_incident")
            .put("incidentId", "persisted-roach-id").put("approach", "search")
        // The model cannot invent that an incident still exists.
        assertNull(CharacterSelfActivityRuntime.parse(idea, emptySet(), true))
        assertNull(CharacterSelfActivityRuntime.parse(idea, emptySet(), true) { id, approach ->
            id == "persisted-roach-id" && approach == "drive_out"
        })
        val validated = CharacterSelfActivityRuntime.parse(idea, emptySet(), true) { id, approach ->
            id == "persisted-roach-id" && approach == "search"
        }
        assertEquals("digital_world_action", validated?.tool)
        assertEquals("persisted-roach-id", validated?.args?.optString("incidentId"))
        assertEquals("search", validated?.args?.optString("approach"))
    }

    @Test fun situationalPlayfulnessAndUnexecutedIntentionStaySeparateFromReceipts() {
        val appraisal = JSONObject()
            .put("spontaneousBid", JSONObject()
                .put("spark", "听见她突然哼歌")
                .put("wantedMove", "想给她起个临时小绰号逗她"))
            .put("ownActivity", JSONObject()
                .put("stage", "decided").put("action", "reading")
                .put("readingBookId", "real-book-7"))
        val present = CharacterMomentSnapshot.reflection(null, appraisal = appraisal)
        assertTrue(present.contains("想给她起个临时小绰号"))
        // The UI shows her spontaneous desire, not a narrated reason why.
        assertFalse(present.contains("听见她突然哼歌"))
        assertTrue(present.contains("想翻翻书"))
        assertFalse(present.contains("已经读完"))
    }

    @Test fun aRealSelfActivityReceiptUpdatesOnlyItsExplicitMotiveAndNeverTwice() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        CharacterInnerLifeStore.initialize(context)
        val role = "self-activity-motive-receipt"
        CharacterInnerLifeStore.clear(role)
        CharacterInnerLifeStore.observe(role, "role-idea", "读过的小说留下疑问",
            JSONObject().put("motives", org.json.JSONArray().put(JSONObject()
                .put("op", "start").put("aim", "探索小说结局")
                .put("why", "觉得人物的抉择有意思"))))
        val motive = CharacterInnerLifeStore.snapshot(role)
            .getJSONArray("motives").getJSONObject(0)
        val id = motive.getString("id")
        val prefs = context.getSharedPreferences("lulu_own_activity_followthrough_v1", 0)
        val action = JSONObject().put("id", "planned-reading-1").put("status", "running")
            .put("motiveId", id).put("tool", "read_book").put("whyNow", "真想看下去")
        prefs.edit().putString("activity:$role", action.toString()).commit()
        CharacterSelfActivityRuntime.markResult(context, role, "planned-reading-1", true, "确实读到了第二章")
        val result = CharacterInnerLifeStore.snapshot(role).getJSONArray("motives")
            .getJSONObject(0).getJSONArray("outcomes")
        assertEquals(1, result.length())
        assertEquals("self-activity-planned-reading-1", result.getJSONObject(0).getString("id"))
        assertTrue(result.getJSONObject(0).getBoolean("success"))
        CharacterSelfActivityRuntime.markResult(context, role, "planned-reading-1", true, "重复回执")
        assertEquals(1, CharacterInnerLifeStore.snapshot(role).getJSONArray("motives")
            .getJSONObject(0).getJSONArray("outcomes").length())
        CharacterInnerLifeStore.clear(role)
    }

    @Test fun cancelledUnexecutedActivityNeverBecomesAFailedToolAttempt() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        CharacterInnerLifeStore.initialize(context)
        val role = "self-activity-cancelled"
        CharacterInnerLifeStore.clear(role)
        CharacterInnerLifeStore.observe(role, "idea-for-cancellation", "打算稍后再读书",
            JSONObject().put("motives", org.json.JSONArray().put(JSONObject()
                .put("op", "start").put("aim", "读完整本书").put("why", "自己想看完"))))
        val motive = CharacterInnerLifeStore.snapshot(role).getJSONArray("motives").getJSONObject(0)
        val prefs = context.getSharedPreferences("lulu_own_activity_followthrough_v1", 0)
        val planned = JSONObject().put("id", "cancel-1").put("status", "pending")
            .put("motiveId", motive.getString("id")).put("tool", "read_book")
        prefs.edit().putString("activity:$role", planned.toString()).commit()
        CharacterSelfActivityRuntime.markResult(context, role, "cancel-1",
            false, "愿望暂停，未实际执行", attempted = false)
        assertEquals(0, CharacterInnerLifeStore.snapshot(role).getJSONArray("motives")
            .getJSONObject(0).getJSONArray("outcomes").length())
        CharacterInnerLifeStore.clear(role)
    }

}
