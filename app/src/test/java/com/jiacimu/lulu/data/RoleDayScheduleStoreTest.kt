package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.json.JSONArray
import org.json.JSONObject
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

    @Test fun missedAndCrossMidnightOwnerPlansRecoverWithoutInventingACompletedAction() {
        val midnight = Instant.parse("2026-10-31T00:42:00Z")
        val missed = plan.copy(id = "bedtime-missed", title = "23:30 关心主人睡觉",
            dueAt = midnight.minusSeconds(72 * 60), status = "planned")
        val oldDue = missed.copy(status = "due")
        assertTrue(RoleDayScheduleStore.needsWakeRestoration(missed, midnight))
        assertTrue(RoleDayScheduleStore.needsWakeRestoration(oldDue, midnight))
        assertFalse(RoleDayScheduleStore.needsWakeRestoration(oldDue.copy(
            lastWakeHandoffAt = midnight.minusSeconds(60)), midnight))
        assertFalse(RoleDayScheduleStore.needsWakeRestoration(oldDue.copy(
            status = "completed"), midnight))
        assertFalse(RoleDayScheduleStore.needsWakeRestoration(oldDue.copy(
            status = "cancelled"), midnight))
        assertFalse(RoleDayScheduleStore.needsWakeRestoration(oldDue.copy(
            characterId = "resident:someone"), midnight))
        assertTrue(RoleDayScheduleStore.needsWakeRestoration(missed.copy(
            dueAt = midnight.plusSeconds(3600)), midnight))
    }

    @Test fun handoffIsDurableIdempotentAndNotAnActionReceipt() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val role = "missed-bedtime-handoff-test"
        val scheduledAt = Instant.now().plusSeconds(3600)
        val created = RoleDayScheduleStore.arrange(context, role, "监督休息",
            "答应关心她是否准备睡觉", scheduledAt, activityAction = "send_private_message")
        try {
            val duePlan = requireNotNull(RoleDayScheduleStore.markDue(context, role, created.id,
                expectedDueAt = scheduledAt))
            assertEquals("due", duePlan.status)
            assertTrue(RoleDayScheduleStore.recordWakeHandoff(context, role, created.id,
                scheduledAt, scheduledAt.plusSeconds(20)))
            assertTrue(RoleDayScheduleStore.recordWakeHandoff(context, role, created.id,
                scheduledAt, scheduledAt.plusSeconds(30)))
            val stored = RoleDayScheduleStore.plans(context, role).first { it.id == created.id }
            assertEquals(scheduledAt.plusSeconds(20), stored.lastWakeHandoffAt)
            assertEquals("due", stored.status)
            assertTrue(stored.resultEvidenceId.isBlank())
            assertFalse(RoleDayScheduleStore.needsWakeRestoration(stored, scheduledAt.plusSeconds(60)))
            assertFalse(RoleDayScheduleStore.recordWakeHandoff(context, role, created.id,
                scheduledAt.plusSeconds(3600), scheduledAt.plusSeconds(40)))
        } finally {
            RoleDayScheduleStore.remove(context, role, created.id)
        }
    }

    @Test fun oneAutonomousDecisionCanKeepSeveralDifferentPlansWithoutDuplicates() {
        val a = plan.copy(status = "planned", id = "first",
            activityAction = "learn_topic",
            activityArgs = """{"goalId":"study-one","op":"study"}""",
            dueAt = due.plusSeconds(7200))
        val repeated = a.copy(id = "another-attempt", title = "改了写法但仍同一项目")
        val differentGoal = a.copy(id = "second",
            activityArgs = """{"goalId":"study-two","op":"study"}""")
        val tomorrow = a.copy(id = "tomorrow", dueAt = a.dueAt.plusSeconds(86400))
        val otherOwner = a.copy(id = "someone-else", characterId = "role-b")
        assertTrue(RoleDayScheduleStore.duplicateOwnPlan(a, repeated))
        assertFalse(RoleDayScheduleStore.duplicateOwnPlan(a, differentGoal))
        assertFalse(RoleDayScheduleStore.duplicateOwnPlan(a, tomorrow))
        assertFalse(RoleDayScheduleStore.duplicateOwnPlan(a, otherOwner))
        assertFalse(RoleDayScheduleStore.duplicateOwnPlan(a.copy(status = "completed"), a))
    }

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
        assertFalse("One-off reminders on different dates are independent",
            duplicatesScheduledReminder(study.copy(dueAt = time.plusSeconds(86400)), promise))
        assertFalse(duplicatesScheduledReminder(study.copy(title = "和居民读书",
            intent = "自己去图书馆读书", activityAction = "read_book"), promise))
        assertFalse(duplicatesScheduledReminder(study.copy(title = "十点半提醒用户吃饭",
            intent = "提醒她吃早饭"), promise))
        assertFalse(duplicatesScheduledReminder(study.copy(characterId = "other-role"), promise))
    }

    @Test fun screenCheckAndOpenEndedFollowUpShareTheExistingStudyCommitment() {
        val time = Instant.parse("2026-10-25T09:00:00Z")
        val reminder = CommitmentTask(characterId = "role-a",
            goal = "五点整检查法制史背诵情况",
            completionCondition = "",
            dueAt = time, deliveryAction = "send_private_message",
            status = CommitmentTaskStatus.Scheduled, linkedAlarmId = "study-reminder")
        val screenCheck = plan.copy(dueAt = time,
            title = "抽查佳辞下一轮法制史学习进度",
            intent = "五点整检查她有没有专心看书，法制史背了多少",
            activityAction = "capture_screen", status = "planned")
        assertTrue(duplicatesScheduledReminder(screenCheck, reminder))
        assertTrue(duplicatesScheduledReminder(screenCheck.copy(activityAction = ""), reminder))
        assertFalse(duplicatesScheduledReminder(screenCheck.copy(activityAction = "read_book"), reminder))
        assertFalse(duplicatesScheduledReminder(screenCheck.copy(motiveId = "own-reading"), reminder))
        assertFalse(duplicatesScheduledReminder(screenCheck.copy(targetCharacterId = "friend"), reminder))
        assertFalse(duplicatesScheduledReminder(screenCheck.copy(locationCode = "library"), reminder))
        assertFalse(duplicatesScheduledReminder(screenCheck.copy(characterId = "other"), reminder))
        assertFalse(duplicatesScheduledReminder(screenCheck.copy(dueAt = time.plusSeconds(86400)), reminder))
        assertFalse(duplicatesScheduledReminder(screenCheck.copy(title = "提醒吃晚饭",
            intent = "五点时提醒她先做饭"), reminder))
    }

    @Test fun aTimedStudyPromiseBlocksASecondScreenCheckWorker() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        CommitmentTaskStore.initialize(context)
        val role = "screen-check-single-authority-test"
        val time = Instant.now().plusSeconds(7200)
        val promise = CommitmentTaskStore.save(CommitmentTask(characterId = role,
            goal = "五点整检查法制史背诵情况",
            completionCondition = "", dueAt = time,
            deliveryAction = "send_private_message",
            status = CommitmentTaskStatus.Scheduled, linkedAlarmId = "study-alarm"))
        try {
            val attempt = runCatching {
                RoleDayScheduleStore.arrange(context, role,
                    "抽查佳辞下一轮法制史学习进度",
                    "督促她专心看书，检查法制史背了多少", time,
                    activityAction = "capture_screen")
            }
            assertTrue(attempt.isFailure)
            assertTrue(attempt.exceptionOrNull()?.message.orEmpty().contains("无需重复"))
            assertTrue(RoleDayScheduleStore.plans(context, role).isEmpty())
            assertEquals(promise.id, CommitmentTaskStore.snapshot(role).first { it.id == promise.id }.id)
        } finally {
            CommitmentTaskStore.remove(promise.id)
        }
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
        assertTrue("Same recurring wall-clock belongs to a single alarm task",
            duplicatesScheduledReminder(bedtime, promise))
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

    @Test fun agreedResidentActivityCannotBeCompletedByAnArrivalGreetingOrAnotherEpisode() {
        val selected = org.json.JSONObject().put("worldAction", "interact_resident")
            .put("activityId", "continue_episode").put("incidentId", "actual-shared-writing")
            .put("itemId", "cafe-worker")
        val appointment = plan.copy(activityAction = "digital_world_action",
            targetResidentId = "cafe-worker", activityArgs = selected.toString())
        fun completed(args: org.json.JSONObject) = RoleDayScheduleStore.canComplete(
            appointment, "role-a", "digital_world_action", "verified-activity", after, args)
        assertFalse(completed(org.json.JSONObject().put("worldAction", "visit_public_place")))
        assertFalse(completed(org.json.JSONObject().put("worldAction", "interact_resident")
            .put("itemId", "cafe-worker").put("activityId", "greet")))
        assertFalse(completed(org.json.JSONObject(selected.toString()).put("incidentId", "another-episode")))
        assertTrue(completed(org.json.JSONObject(selected.toString()).put("text", "本次真实交出的新文字")))
        assertFalse(RoleDayScheduleStore.canComplete(appointment, "role-b", "digital_world_action",
            "other-persons-result", after, selected))
        // Empty legacy activityArgs retains the original target-only contract.
        assertTrue(RoleDayScheduleStore.canComplete(appointment.copy(activityArgs = "{}"),
            "role-a", "digital_world_action", "legacy-real-encounter", after,
            org.json.JSONObject().put("worldAction", "interact_resident").put("itemId", "cafe-worker")))
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

    @Test fun immediateActionLabelsResolveToRegisteredScheduledTools() {
        assertEquals("read_book", CapabilityRegistry.scheduledActionName("reading"))
        assertEquals("play_solo_game", CapabilityRegistry.scheduledActionName("solo_game"))
        assertEquals("digital_world_action", CapabilityRegistry.scheduledActionName("digital_world"))
        assertEquals("publish_moment", CapabilityRegistry.scheduledActionName("moment"))
        assertEquals("send_private_message", CapabilityRegistry.scheduledActionName("send_private_message"))
        assertTrue(RoleDayScheduleStore.canSchedule("reading"))
        assertTrue(RoleDayScheduleStore.canSchedule("solo_game"))
        assertTrue(RoleDayScheduleStore.canSchedule("digital_world"))
        assertFalse(RoleDayScheduleStore.canSchedule("write_journal"))
        assertFalse(RoleDayScheduleStore.canSchedule("unknown_world_action"))
        assertFalse(RoleDayScheduleStore.canSchedule("go_offline"))
        assertFalse(RoleDayScheduleStore.canSchedule("plan_personal_activity"))
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

    @Test fun undatedUnclearSupervisionIsNotLostBeforeItsOwnerPlansARealFollowUp() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        CommitmentTaskStore.initialize(context)
        val role = "undated-clarification-follow-through-test"
        val task = CommitmentTaskStore.save(CommitmentTask(
            characterId = role,
            goal = "监督学习、起床、吃饭和日常",
            status = CommitmentTaskStatus.NeedsClarification,
            dueAt = null,
            lastActionResult = "缺少必要条件，需要后续自然确认",
        ))
        try {
            assertTrue(task.needsUndatedActionReview())
            val pending = RoleDayScheduleStore.undatedCommitmentsToPlan(context, role)
            assertTrue(pending.any { it.id == task.id })
            val cue = RoleDayScheduleStore.planningOpportunityContext(context, role)
            assertTrue(cue.contains("commitmentTaskId=${task.id}"))
            assertTrue(cue.contains("状态=NeedsClarification"))
            assertTrue(cue.contains("自己选下一步"))
            assertNull(task.dueAt)
            assertNull(task.linkedAlarmId)
            assertEquals(CommitmentTaskStatus.NeedsClarification,
                CommitmentTaskStore.snapshot(role).first { it.id == task.id }.status)
            assertFalse(task.copy(dueAt = Instant.now().plusSeconds(600)).needsUndatedActionReview())
            assertFalse(task.copy(status = CommitmentTaskStatus.Completed).needsUndatedActionReview())
            assertFalse(task.copy(status = CommitmentTaskStatus.Cancelled).needsUndatedActionReview())
        } finally {
            CommitmentTaskStore.remove(task.id)
        }
    }

    @Test fun sameLongTermSupervisionKeepsSeveralIndependentPlannedStepsVisible() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        CommitmentTaskStore.initialize(context)
        val role = "multi-child-supervision-test"
        val task = CommitmentTaskStore.save(CommitmentTask(characterId = role,
            goal = "监督学习、起床、吃饭和休息",
            status = CommitmentTaskStatus.NeedsClarification, dueAt = null))
        val prefs = context.getSharedPreferences("lulu_role_day_plans_v1", android.content.Context.MODE_PRIVATE)
        fun savedPlan(id: String, title: String, dueAt: String) = JSONObject()
            .put("id", id).put("characterId", role).put("title", title)
            .put("intent", "自主选择一次具体监督").put("dueAt", dueAt)
            .put("createdAt", "2026-10-29T00:00:00Z")
            .put("status", "planned").put("activityAction", "send_private_message")
            .put("commitmentTaskId", task.id)
        val planned = JSONArray()
            .put(savedPlan("study-follow-up", "下午询问学习情况", "2026-10-29T06:00:00Z"))
            .put(savedPlan("sleep-follow-up", "夜里关注休息", "2026-10-29T15:00:00Z"))
        try {
            assertTrue(prefs.edit().putString("plans", planned.toString()).commit())
            assertTrue(RoleDayScheduleStore.undatedCommitmentsToPlan(context, role).any { it.id == task.id })
            val cue = RoleDayScheduleStore.planningOpportunityContext(
                context, role, Instant.parse("2026-10-29T01:00:00Z"))
            assertTrue(cue.contains("study-follow-up"))
            assertTrue(cue.contains("sleep-follow-up"))
            assertTrue(cue.contains("多项行动"))
            val first = plan.copy(characterId = role, commitmentTaskId = task.id,
                dueAt = Instant.parse("2026-10-29T06:00:00Z"), status = "planned",
                activityAction = "send_private_message")
            assertTrue(RoleDayScheduleStore.duplicateOwnPlan(first, first.copy(id = "replayed")))
            assertFalse(RoleDayScheduleStore.duplicateOwnPlan(first,
                first.copy(id = "another-hour", dueAt = first.dueAt.plusSeconds(7200))))
            assertFalse(RoleDayScheduleStore.duplicateOwnPlan(first,
                first.copy(id = "another-role", characterId = "other-role")))
        } finally {
            prefs.edit().remove("plans").commit()
            CommitmentTaskStore.remove(task.id)
        }
    }

    @Test fun oneStoreTransitionRevokesOnlyTrulyObsoleteUndatedSteps() {
        val original = CommitmentTask(characterId = "role-a",
            status = CommitmentTaskStatus.NeedsClarification,
            goal = "监督学习与饮食", dueAt = null,
            completionCondition = "需要真实学习反馈")
        assertFalse(original.invalidatesUndatedFollowUps(original.copy(
            lastActionResult = "刚查看过安排但还没执行", revision = 4)))
        assertFalse(original.invalidatesUndatedFollowUps(original.copy(
            status = CommitmentTaskStatus.WaitingForFeedback)))
        assertTrue(original.invalidatesUndatedFollowUps(original.copy(
            status = CommitmentTaskStatus.Cancelled)))
        assertTrue(original.invalidatesUndatedFollowUps(original.copy(
            status = CommitmentTaskStatus.Completed)))
        assertTrue(original.invalidatesUndatedFollowUps(original.copy(
            dueAt = Instant.parse("2026-10-30T02:00:00Z"),
            status = CommitmentTaskStatus.Scheduled)))
        assertTrue(original.invalidatesUndatedFollowUps(original.copy(
            goal = "只监督睡觉")))
        assertTrue(original.invalidatesUndatedFollowUps(original.copy(
            completionCondition = "由本人确认已经就寝")))
        assertFalse(original.copy(status = CommitmentTaskStatus.Cancelled)
            .invalidatesUndatedFollowUps(original))
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
