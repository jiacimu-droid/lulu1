package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RoleDayScheduleStoreTest {
    private val due = Instant.parse("2026-10-20T09:00:00Z")
    private val after = due.plusSeconds(30)
    private val plan = RoleDayPlan(
        id = "plan-1", characterId = "role-a", title = "读剧场",
        intent = "想知道故事会怎么发展", dueAt = due,
        createdAt = due.minusSeconds(3600), status = "due",
        activityAction = "read_book",
    )

    @Test fun onlyVerifiedMatchingActivityCanCloseTheOriginalRolesPlan() {
        assertTrue(RoleDayScheduleStore.canComplete(plan, "role-a", "read_book", "reading-evidence", after))
        assertFalse(RoleDayScheduleStore.canComplete(plan, "role-b", "read_book", "reading-evidence", after))
        assertFalse(RoleDayScheduleStore.canComplete(plan, "role-a", "send_private_message", "message-id", after))
        assertFalse(RoleDayScheduleStore.canComplete(plan, "role-a", "play_solo_game", "game-evidence", after))
        assertFalse(RoleDayScheduleStore.canComplete(plan, "role-a", "read_book", "", after))
        assertFalse(RoleDayScheduleStore.canComplete(plan.copy(status = "completed"),
            "role-a", "read_book", "reading-evidence", after))
        assertFalse(RoleDayScheduleStore.canComplete(plan.copy(status = "cancelled"),
            "role-a", "read_book", "reading-evidence", after))
    }

    @Test fun osWakeDelayDoesNotBlockARealActionThatOccurredAfterDueTime() {
        val stillScheduled = plan.copy(status = "planned")
        assertFalse(RoleDayScheduleStore.canComplete(stillScheduled, "role-a", "read_book",
            "actual-read", due.minusSeconds(1)))
        assertTrue(RoleDayScheduleStore.canComplete(stillScheduled, "role-a", "read_book",
            "actual-read", after))
    }

    @Test fun scheduledSocialActionMustMatchTheRealRecipient() {
        val friend = plan.copy(activityAction = "send_character_message",
            targetCharacterId = "role-friend")
        assertTrue(RoleDayScheduleStore.canComplete(friend, "role-a", "send_character_message",
            "actual-mail", after, org.json.JSONObject().put("targetCharacterId", "role-friend")))
        assertFalse(RoleDayScheduleStore.canComplete(friend, "role-a", "send_character_message",
            "actual-mail", after, org.json.JSONObject().put("targetCharacterId", "stranger")))
        assertFalse(RoleDayScheduleStore.canComplete(friend, "role-a", "send_character_message",
            "actual-mail", after))
        assertFalse(RoleDayScheduleStore.canComplete(friend.copy(activityAction = ""),
            "role-a", "send_private_message", "unrelated-private-message", after))
        val meeting = plan.copy(activityAction = "digital_world_action",
            targetResidentId = "cafe-worker")
        assertFalse(RoleDayScheduleStore.canComplete(
            plan.copy(activityAction = "", targetResidentId = "cafe-worker"),
            "role-a", "send_private_message", "unrelated-message", after))
        assertFalse(RoleDayScheduleStore.canComplete(meeting, "role-a", "digital_world_action",
            "only-arrival", after, org.json.JSONObject().put("worldAction", "visit_public_place")))
        assertTrue(RoleDayScheduleStore.canComplete(meeting, "role-a", "digital_world_action",
            "real-encounter", after, org.json.JSONObject()
                .put("worldAction", "interact_resident").put("itemId", "cafe-worker")))
    }

    @Test fun futureDailyRemindersKeepOriginalSlotAndSkipMissedDays() {
        val dayLater = RoleDayScheduleStore.nextDailyDue(due, due.plusSeconds(120))
        assertEquals(due.plusSeconds(86400), dayLater)
        val threeDaysLater = RoleDayScheduleStore.nextDailyDue(due, due.plusSeconds(3 * 86400L + 80))
        assertEquals(due.plusSeconds(4 * 86400L), threeDaysLater)
    }

    @Test fun onlyRealDeliveredMessageCanCloseScheduledStudyPrompt() {
        val reminder = plan.copy(activityAction = "send_private_message", repeat = "daily")
        assertTrue(RoleDayScheduleStore.canComplete(reminder, "role-a", "send_private_message",
            "delivered-message-id", after))
        assertFalse(RoleDayScheduleStore.canComplete(reminder, "role-a", "send_private_message",
            "", after))
        assertFalse(RoleDayScheduleStore.canComplete(reminder, "role-a", "start_call",
            "outgoing-call", after))
    }

    @Test fun legacyUnrestrictedPlansStillDemandActualEvidenceAndAllowedActions() {
        val legacy = plan.copy(activityAction = "")
        assertTrue(RoleDayScheduleStore.canComplete(legacy, "role-a", "play_solo_game",
            "real-game", after))
        assertFalse(RoleDayScheduleStore.canComplete(legacy, "role-a", "start_call",
            "outgoing-call", after))
    }
}
