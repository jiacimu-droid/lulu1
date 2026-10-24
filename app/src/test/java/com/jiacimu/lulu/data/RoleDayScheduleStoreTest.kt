package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RoleDayScheduleStoreTest {
    private val due = Instant.parse("2026-10-20T09:00:00Z")
    private val after = due.plusSeconds(30)
    private val plan = RoleDayPlan(
        id = "plan-1", characterId = "role-a", title = "读剧场",
        intent = "想知道故事会怎么发展", dueAt = due,
        createdAt = due.minusSeconds(3600), status = "due",
        activityAction = "read_book",
    )

    @Test fun oneOffStudyReminderIsOwnedByExactlyOnePromiseAlarm() {
        val time = Instant.parse("2026-10-24T02:30:00Z")
        val study = plan.copy(id = "user-study-plan", dueAt = time,
            title = "十点半来喊用户开始学习", intent = "答应她按时督促开始学习",
            activityAction = "send_private_message", repeat = "once")
        val promise = CommitmentTask(characterId = study.characterId,
            goal = "10:30提醒用户开始今天的学习", dueAt = time,
            deliveryAction = "send_private_message", status = CommitmentTaskStatus.Scheduled,
            linkedAlarmId = "real-alarm-1")
        assertTrue(duplicatesScheduledReminder(study, promise))
        assertTrue(isAuthoritativeScheduledReminder(promise))
        assertFalse(isAuthoritativeScheduledReminder(promise.copy(linkedAlarmId = null)))
        assertFalse(duplicatesScheduledReminder(study.copy(dueAt = time.plusSeconds(86400)), promise),
            "One-off reminders on different dates are independent")
        assertFalse(duplicatesScheduledReminder(study.copy(title = "和居民读书",
            intent = "自己去图书馆读书", activityAction = "read_book"), promise))
        assertFalse(duplicatesScheduledReminder(study.copy(title = "十点半提醒用户吃饭",
            intent = "提醒她吃早饭"), promise))
        assertFalse(duplicatesScheduledReminder(study.copy(characterId = "other-role"), promise))
    }

    @Test fun dailyBedtimeReminderDoesNotCreateACopyForEveryDay() {
        val time = Instant.parse("2026-10-24T15:30:00Z")
        val bedtime = plan.copy(dueAt = time, title = "每天晚上十一点半提醒睡觉",
            intent = "主动监督用户准时休息", activityAction = "send_private_message",
            repeat = "daily")
        val promise = CommitmentTask(characterId = bedtime.characterId,
            goal = "每晚23:30打电话督促睡觉", dueAt = time.plusSeconds(86400),
            deliveryAction = "start_call", repeat = "daily",
            status = CommitmentTaskStatus.Scheduled, linkedAlarmId = "native-call-alarm")
        assertTrue(duplicatesScheduledReminder(bedtime, promise),
            "Same recurring wall-clock belongs to a single alarm task")
        assertFalse(duplicatesScheduledReminder(bedtime.copy(title = "每天复习",
            intent = "监督背书"), promise))
    }

    @Test fun creatingPersonalPlanCannotDuplicateARegisteredUserReminder() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        CommitmentTaskStore.initialize(context)
        val role = "schedule-single-authority-test"
        val base = Instant.now().plusSeconds(7200)
        val task = CommitmentTask(characterId = role, goal = "提醒我开始学习",
            dueAt = base, deliveryAction = "send_private_message",
            status = CommitmentTaskStatus.Scheduled, linkedAlarmId = "test-alarm")
        CommitmentTaskStore.save(task)
        try {
            val blocked = runCatching {
                RoleDayScheduleStore.arrange(context, role, "到点叫用户开始学习",
                    "按用户要求督促她开始学习", base, activityAction = "send_private_message")
            }
            assertTrue(blocked.isFailure)
            assertTrue(blocked.exceptionOrNull()?.message.orEmpty().contains("无需重复"))
            assertTrue(RoleDayScheduleStore.plans(context, role).none {
                it.title.contains("到点叫用户开始学习")
            })
            assertEquals(task.id, CommitmentTaskStore.snapshot(role).first { it.id == task.id }.id)
        } finally {
            CommitmentTaskStore.remove(task.id)
        }
    }

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

    @Test fun explicitlyScheduledDeviceActionsKeepTheSamePermissionAndReceiptBoundary() {
        assertTrue(RoleDayScheduleStore.canSchedule("capture_screen"))
        assertTrue(RoleDayScheduleStore.canSchedule("summon_lulu"))
        assertTrue(RoleDayScheduleStore.canSchedule("send_private_message"))
        assertFalse(RoleDayScheduleStore.canSchedule("plan_personal_activity"))
        assertFalse(RoleDayScheduleStore.canSchedule("write_journal"))
        assertFalse(RoleDayScheduleStore.canSchedule("invented_tool"))
        val scheduled = plan.copy(activityAction = "capture_screen", activityArgs = "{}")
        assertTrue(RoleDayScheduleStore.canComplete(scheduled, "role-a",
            "capture_screen", "real-screenshot-receipt", after))
        assertFalse(RoleDayScheduleStore.canComplete(scheduled, "role-a",
            "summon_lulu", "different-action", after))
        assertFalse(RoleDayScheduleStore.canComplete(scheduled, "role-b",
            "capture_screen", "other-role", after))
        // An unspecified old plan cannot be accidentally satisfied by an unrelated device read.
        assertFalse(RoleDayScheduleStore.canComplete(plan.copy(activityAction = ""),
            "role-a", "capture_screen", "not-an-appointment", after))
    }

    @Test fun undatedOngoingPromiseInvitesItsOwnerToChooseTimeWithoutFabricatingAnAlarm() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        CommitmentTaskStore.initialize(context)
        val role = "self-owned-sleep-check-test"
        val accepted = CommitmentTaskStore.save(CommitmentTask(
            characterId = role,
            goal = "今晚留意她是否准备休息",
            status = CommitmentTaskStatus.WaitingForFeedback,
            createdAt = Instant.parse("2026-10-23T14:00:00Z"),
            dueAt = null,
        ))
        try {
            val pending = RoleDayScheduleStore.undatedCommitmentsToPlan(context, role)
            assertTrue(pending.any { it.id == accepted.id })
            val cue = RoleDayScheduleStore.planningOpportunityContext(
                context, role, Instant.parse("2026-10-23T15:00:00Z"))
            assertTrue(cue.contains("commitmentTaskId=${accepted.id}"))
            assertTrue(cue.contains("plan_personal_activity"))
            assertTrue(cue.contains("自己选"))
            assertNull(accepted.dueAt)
            assertNull(accepted.linkedAlarmId)
        } finally {
            CommitmentTaskStore.remove(accepted.id)
        }
    }

    @Test fun chosenAppointmentLinksAcceptedResponsibilityWithoutInventingAnOutcome() {
        val actual = plan.copy(
            activityAction = "send_private_message",
            commitmentTaskId = "earlier-accepted-promise",
            activityArgs = """{"text":"来看看你准备休息了没有"}""",
        )
        assertEquals("earlier-accepted-promise", actual.commitmentTaskId)
        assertEquals(plan.createdAt, actual.createdAt)
        assertTrue(RoleDayScheduleStore.canComplete(actual, "role-a", "send_private_message",
            "real-message", after))
        assertFalse(RoleDayScheduleStore.canComplete(actual, "role-a", "send_private_message",
            "", after))
        assertFalse(RoleDayScheduleStore.canComplete(actual, "role-a", "send_private_message",
            "premature", due.minusSeconds(60)))
    }

    @Test fun legacyUnrestrictedPlansStillDemandActualEvidenceAndAllowedActions() {
        val legacy = plan.copy(activityAction = "")
        assertTrue(RoleDayScheduleStore.canComplete(legacy, "role-a", "play_solo_game",
            "real-game", after))
        assertFalse(RoleDayScheduleStore.canComplete(legacy, "role-a", "start_call",
            "outgoing-call", after))
    }
}
