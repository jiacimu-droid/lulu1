package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AutonomousActionTraceTest {
    @Test fun sameActionDifferentPlaceIsNotTheSameBehavior() {
        val a = AutonomousActionTrace.signature("digital_world", worldAction = "visit_public_place",
            destination = "shared:courtyard")
        val b = AutonomousActionTrace.signature("digital_world", worldAction = "visit_public_place",
            destination = "shared:reading_lounge")
        assertNotEquals(a, b)
        assertEquals(a, AutonomousActionTrace.signature("digital_world",
            worldAction = "visit_public_place", destination = "shared:courtyard"))
    }

    @Test fun feedbackShowsRepetitionButDoesNotForbidLegitimateExploration() {
        val at = Instant.parse("2026-10-11T02:00:00Z")
        val decision = JSONObject().put("at", at.toString())
            .put("selected", "digital_world").put("signature", "digital_world/visit_cloud_meadow")
            .put("succeeded", true).put("outcome", "真的到了云眠原")
        val history = JSONArray().put(decision).put(JSONObject(decision.toString()))
        val rendered = AutonomousActionTrace.render(history, at.plusSeconds(300))
        assertTrue(rendered.contains("同一具体动作近期出现过多次"))
        assertTrue(rendered.contains("可以继续"))
    }

    @Test fun oldHistoryDoesNotCreateArtificialPressureToAct() {
        val history = JSONArray().put(JSONObject().put("at", "2026-09-01T00:00:00Z")
            .put("selected", "reading").put("signature", "reading/book-1")
            .put("succeeded", true))
        assertEquals("", AutonomousActionTrace.render(history,
            Instant.parse("2026-10-11T02:00:00Z")))
    }
}
