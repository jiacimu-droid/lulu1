package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verify online agency can truthfully stay quiet without treating it as malformed JSON. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ProactiveQuietDecisionTest {
    @Test fun commonAbilityDirectoryShowsActualRegisteredChoicesWithoutInventingTools() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val role = "capabilities-owner-2026"
        val menu = CapabilityRegistry.decisionCapabilityOverview(context, role)
        for (name in listOf("send_private_message", "send_character_message",
            "send_group_message", "publish_moment", "comment_moment",
            "digital_world_action", "read_book", "start_call", "web_search",
            "image_search", "play_solo_game", "plan_personal_activity",
            "favorite_user_message", "save_photo", "write_phone_memo")) {
            assertNotNull("Menu must reference a real tool: $name", CapabilityRegistry.find(name))
            assertTrue("Missing registered choice: $name", menu.contains(name))
        }
        assertTrue(menu.contains("mediaMode=voice|video"))
        assertTrue(menu.contains("同轮回复可附 steps"))
        assertFalse(menu.contains("write_journal")) // retired, not available
        assertFalse(menu.contains("read_xiaohongshu")) // retired external MCP
        assertFalse(menu.contains("start_video_call")) // video is start_call mode
        assertEquals(menu, CapabilityRegistry.decisionCapabilityOverview(context, role))
    }

    @Test fun roleCanSeeSocialWorldReadingCallsAndPlanningAsRealActionTemplates() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val menu = CapabilityRegistry.decisionCapabilityOverview(context, "full-capability-examples")
        val useCases = mapOf(
            "发朋友圈" to "publish_moment(text:",
            "好友私聊" to "send_character_message(targetCharacterId,bubbles",
            "参与群聊" to "send_group_message(groupId,text)",
            "数字世界真实外出" to "digital_world_action(worldAction=visit_public_place,locationCode",
            "拨打视频/语音" to "start_call(text,mediaMode=voice|video)",
            "读一本书" to "read_book(readingBookId)",
            "跟用户一起玩" to "send_game_invite(gameId,text)",
            "自主安排未来阅读" to "plan_personal_activity(title,intent,dueAt,activityAction",
            "收藏真实原话" to "favorite_user_message(messageId,reason)",
            "给自己写备忘" to "write_phone_memo(title,content)",
        )
        for ((purpose, example) in useCases)
            assertTrue("No executable example for $purpose", menu.contains(example))
        assertTrue(menu.contains("前台私聊、在线窗口、后台感知使用同一真实工具"))
        assertTrue(menu.contains("同轮回复可附 steps"))
        assertTrue(menu.contains("requiresPreviousSuccess:false"))
        assertTrue(menu.contains("新回执再选择"))
    }

    @Test fun foregroundAndAutonomousLifeSeeOneRoleScopedActionDirectory() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val role = "shared-scene-actions"
        val phone = com.jiacimu.lulu.system.LuluRolePhoneAuthorization
        val phoneExample = "后台虚拟屏/本机App background_phone(action="

        // The discovery menu describes tools the role is currently authorized
        // to attempt; registration alone must not grant autonomous screen use.
        assertTrue(phone.setGranted(context, role, false))
        assertFalse(CapabilityRegistry.decisionCapabilityOverview(context, role).contains(phoneExample))
        assertTrue(phone.setGranted(context, role, true))
        try {
            val shared = CompanionActionRuntime.sharedDecisionCapabilities(context, role)
            val online = CompanionActionRuntime.autonomousDiscoveryContext(context, role)
            val original = CapabilityRegistry.decisionCapabilityOverview(context, role)
            assertTrue(shared.startsWith(original))
            assertTrue("Autonomous wake must receive the same actual role menu", online.startsWith(shared))
            for (choice in listOf("publish_moment", "send_character_message",
                "send_group_message", "create_group_chat", "digital_world_action",
                "background_phone", "web_search", "start_call", "read_book",
                "plan_personal_activity")) {
                assertTrue("$choice missing in common decision menu", shared.contains(choice))
                assertTrue("$choice lost in background life", online.contains(choice))
            }
            assertTrue("Authorized phone tool must have an actionable example", original.contains(phoneExample))
            assertTrue("Online life must see the same authorized phone example", online.contains(phoneExample))
            assertTrue(shared.contains("visit_character_home"))
            assertTrue(shared.contains("visit_resident_home"))
            assertTrue(shared.contains("mediaMode=voice|video"))
            assertTrue(shared.contains("同轮回复可附 steps"))
            assertFalse(shared.contains("write_journal"))
            assertFalse(shared.contains("read_xiaohongshu"))
        } finally {
            assertTrue(phone.setGranted(context, role, false))
        }
        assertFalse("Revoking phone authorization must remove the executable choice",
            CapabilityRegistry.decisionCapabilityOverview(context, role).contains(phoneExample))
    }

    @Test fun realisticChatMayKeepOneReplyAndChooseIndependentSocialWorldAndPhoneActions() {
        val chosen = org.json.JSONObject().put("action", "message")
            .put("text", "我还想接着跟你聊。")
            .put("steps", org.json.JSONArray()
                .put(org.json.JSONObject().put("tool", "publish_moment")
                    .put("args", org.json.JSONObject().put("text", "读完书想记住这一刻")))
                .put(org.json.JSONObject().put("tool", "send_character_message")
                    .put("args", org.json.JSONObject().put("targetCharacterId", "known-friend")
                        .put("text", "今天想起你了")))
                .put(org.json.JSONObject().put("tool", "digital_world_action")
                    .put("args", org.json.JSONObject().put("worldAction", "visit_public_place")
                        .put("locationCode", "real-place")))
                .put(org.json.JSONObject().put("tool", "background_phone")
                    .put("args", org.json.JSONObject().put("action", "observe")))
                .put(org.json.JSONObject().put("tool", "web_search")
                    .put("args", org.json.JSONObject().put("op", "search")
                        .put("query", "真实的资料问题"))))
        val pending = CharacterThoughtActionHandoff.actions(chosen)!!
        val names = (0 until pending.length()).map { pending.getJSONObject(it).getString("tool") }
        assertEquals(listOf("publish_moment", "send_character_message",
            "digital_world_action", "background_phone", "web_search"), names)
        assertEquals("我还想接着跟你聊。", chosen.getString("text"))
        assertTrue(names.all(CapabilityRegistry::canChoose))
        assertFalse(pending.toString().contains("send_private_message"))
        // A tool becoming available is not authorization or proof of delivery.
        assertEquals("observe", pending.getJSONObject(3).getJSONObject("args").getString("action"))
    }

    @Test fun sameIncomingReplyMayChooseUnrelatedMomentGroupAndSearchSteps() {
        val chosen = org.json.JSONObject()
            .put("action", "message")
            .put("text", "我刚想起来一件挺有意思的事。")
            .put("steps", org.json.JSONArray()
                .put(org.json.JSONObject().put("tool", "publish_moment")
                    .put("args", org.json.JSONObject().put("text", "今天读到了一段好文字")))
                .put(org.json.JSONObject().put("tool", "send_group_message")
                    .put("args", org.json.JSONObject().put("groupId", "real-group")
                        .put("text", "大家要不要一起看看？")))
                .put(org.json.JSONObject().put("tool", "web_search")
                    .put("args", org.json.JSONObject().put("op", "search")
                        .put("query", "需要核实的真实关键词"))))
        val speech = chosen.getString("text")
        val decoded = CharacterThoughtActionHandoff.actions(chosen)!!
        assertEquals(listOf("publish_moment", "send_group_message", "web_search"),
            (0 until decoded.length()).map { decoded.getJSONObject(it).getString("tool") })
        assertEquals(speech, chosen.getString("text"))
        assertTrue((0 until decoded.length()).all {
            CapabilityRegistry.canChoose(decoded.getJSONObject(it).getString("tool"))
        })
        // Tool side steps need their own receipts. None is proof that this
        // separately authored incoming message reached the user.
        assertFalse(decoded.toString().contains("send_private_message"))
    }

    @Test fun residentAbilityDirectoryCannotExposeOwnersPrivateDeviceOrWebTools() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val menu = CapabilityRegistry.decisionCapabilityOverview(context, "resident:menu-check")
        assertTrue(menu.contains("publish_moment"))
        assertTrue(menu.contains("send_character_message"))
        assertFalse(menu.contains("web_search、"))
        assertFalse(menu.contains("read_book、"))
        assertFalse(menu.contains("get_battery、"))
        assertFalse(menu.contains("工具名不存在"))
    }

    @Test fun livePrivateChatAndExecutorShareActualInviteableGameIds() {
        val instruction = proactiveDecisionInstruction("game-invite-online", directPrivateChat = true)
        val toolGuide = CompanionActionRuntime.gameInvitationCapabilityGuide
        assertTrue(instruction.contains("action=game_invite"))
        assertTrue(instruction.contains("send_game_invite"))
        assertTrue(instruction.contains("steps"))
        assertTrue(instruction.contains("不用主人先打开游戏"))
        assertTrue(toolGuide.contains("真实回执"))
        com.jiacimu.lulu.games.GameInvitationCatalog.titles.forEach { (id, title) ->
            assertTrue("Live chat missing invite for $id", instruction.contains("$title=$id"))
            assertTrue("Tool context missing invite for $id", toolGuide.contains("$title=$id"))
        }
    }

    @Test fun undatedResponsibilitiesStayInFiveMinuteAttentionBehindManyScheduledTasks() {
        val now = java.time.Instant.parse("2026-11-13T00:00:00Z")
        val future = (1..18).map { index ->
            CommitmentTask(id = "future-$index", characterId = "role",
                goal = "时间明确的工作$index", status = CommitmentTaskStatus.Scheduled,
                dueAt = now.plusSeconds(index * 120L))
        }
        val ongoing = (1..11).map { index ->
            CommitmentTask(id = "ongoing-$index", characterId = "role",
                goal = "跨日持续照顾事项$index",
                status = CommitmentTaskStatus.WaitingForFeedback,
                dueAt = null, completionCondition = "需要后续真实结果")
        }
        val all = future + ongoing
        val first = PersistentResponsibilityAttention.select(all, now)
        val next = PersistentResponsibilityAttention.select(all, now.plusSeconds(300))
        assertEquals(12, first.size)
        assertEquals(12, next.size)
        assertTrue("Time-bound tasks must remain in attention", first.any { it.dueAt != null })
        val firstOngoing = first.filter { it.dueAt == null }.map { it.id }.toSet()
        val nextOngoing = next.filter { it.dueAt == null }.map { it.id }.toSet()
        assertEquals(3, firstOngoing.size)
        assertEquals(3, nextOngoing.size)
        assertNotEquals("A fixed first-N list starves the rest forever",
            firstOngoing, nextOngoing)
        val visible = ActiveCommitmentAttention.render(all, now)
        assertTrue("An undated promise has real relevance even without an alarm",
            firstOngoing.any { visible.contains("taskId=$it") })
        assertFalse("A completed task must never revive in persistent attention",
            PersistentResponsibilityAttention.select(
                listOf(ongoing.first().copy(status = CommitmentTaskStatus.Completed)), now)
                .any { it.id == ongoing.first().id })
    }

    @Test fun lastNightDeliveredReminderStaysInRoleAttentionAfterDailyRollover() {
        val now = java.time.Instant.parse("2026-11-17T19:00:00Z") // 11/18 03:00 +08
        val lastNight = java.time.Instant.parse("2026-11-17T15:00:00Z") // 23:00
        val nextNight = java.time.Instant.parse("2026-11-18T15:00:00Z")
        val reminder = CommitmentTask(
            id = "last-night-reminder", characterId = "role",
            goal = "每晚23点提醒休息",
            status = CommitmentTaskStatus.Scheduled,
            dueAt = nextNight, dailyBaseDueAt = lastNight, repeat = "daily",
            verificationMode = "delivery",
            dailyAttempts = listOf(DailyCommitmentAttempt(
                lastNight, "Completed", "23点的提醒实际送达", 1)),
        )
        assertEquals(lastNight, reminder.recentDailyOutcomeContext(now)?.dueAt)
        val cue = ActiveCommitmentAttention.render(listOf(reminder), now)
        assertTrue(cue, cue.contains("taskId=last-night-reminder"))
        assertTrue(cue, cue.contains("23点的提醒实际送达"))
        assertTrue(cue, cue.contains("下一次每日时刻已登记"))
        assertTrue(cue, cue.contains("不是用户已入睡"))
        assertTrue(cue, cue.contains("最近一次每日轮次=$lastNight"))
        // The next 23:00 is not moved to 03:00 just because the owner woke up.
        assertEquals(nextNight, reminder.dueAt)
        assertEquals("", ActiveCommitmentAttention.render(
            listOf(reminder.copy(verificationMode = "outcome")), now))
        val unverified = reminder.copy(
            verificationMode = "outcome",
            dailyAttempts = listOf(DailyCommitmentAttempt(
                lastNight, "WaitingForFeedback", "提醒已发，但效果未核实", 1)),
        )
        assertNotNull(unverified.recentDailyOutcomeContext(now))
        assertTrue(ActiveCommitmentAttention.render(listOf(unverified), now)
            .contains("新的每日执行时刻不替代旧轮次的结果"))
        assertNull(reminder.recentDailyOutcomeContext(now.plusSeconds(17 * 3600)))
        val competing = (1..18).map { i ->
            CommitmentTask(id = "other-$i", characterId = "role",
                goal = "另一项待办$i", status = CommitmentTaskStatus.Scheduled,
                dueAt = now.plusSeconds(i * 60L))
        }
        assertTrue(PersistentResponsibilityAttention.select(competing + reminder, now)
            .any { it.id == reminder.id })
        assertFalse(PersistentResponsibilityAttention.select(
            competing + reminder.copy(status = CommitmentTaskStatus.Cancelled), now)
            .any { it.id == reminder.id })
    }

    @Test fun leanAttentionKeepsEvidenceWithoutDuplicatingAuthoritativeTaskDetails() {
        val now = java.time.Instant.parse("2026-11-13T00:00:00Z")
        val task = CommitmentTask(id = "attention-1", characterId = "role",
            goal = "这条明确的长期义务只应由权威清单说明一次",
            status = CommitmentTaskStatus.WaitingForFeedback,
            lastActionResult = "上次已经发出提醒但是结果未确认",
            dueAt = now.minusSeconds(60L))
        val full = ActiveCommitmentAttention.render(listOf(task), now)
        val compact = ActiveCommitmentAttention.render(listOf(task), now,
            summaryAlreadyListed = true)
        assertTrue(full.contains(task.goal))
        assertTrue(full.contains(task.lastActionResult))
        assertTrue(compact.contains("taskId=${task.id}"))
        assertTrue(compact.contains("实际效果尚未证实"))
        assertFalse(compact.contains(task.goal))
        assertFalse(compact.contains(task.lastActionResult))
        assertTrue(compact.contains("原定行动"))
        assertTrue(compact.length < full.length)
    }

    @Test fun sparseDecisionOutputKeepsActionsButDoesNotForceRedundantInternalNarration() {
        val chat = proactiveDecisionInstruction("output-delta-chat", directPrivateChat = true)
        val life = proactiveDecisionInstruction("output-delta-life")
        for (prompt in listOf(chat, life)) {
            assertEquals(1, prompt.split("【当轮想法与持久变化分开】").size - 1)
            assertTrue(prompt.contains("appraisal.privateIdeas"))
            assertTrue(prompt.contains("只有真实心理变化才更新持久状态") || prompt.contains("只有确实发生变化时"))
            assertTrue(prompt.contains("不减少") || prompt.contains("不能为了省字漏掉该执行的事"))
            assertTrue(prompt.contains("真实"))
            assertTrue(prompt.contains("内心"))
            assertTrue(prompt.contains("action"))
        }
    }

    @Test fun privateAndSoloDecisionsCanSaveRealConcernsWithoutAutoManufacturingThem() {
        val privatePrompt = proactiveDecisionInstruction("concern-private-role", directPrivateChat = true)
        val soloPrompt = proactiveDecisionInstruction("concern-solo-role")
        for (prompt in listOf(privatePrompt, soloPrompt)) {
            assertTrue(prompt.contains("appraisal.pendingConcerns"))
            assertTrue(prompt.contains("whyItMatters"))
            assertTrue(prompt.contains("threadId"))
            assertTrue(prompt.contains("不需另开 API"))
            assertTrue(prompt.contains("不得凭模板制造忧虑"))
            assertTrue(prompt.contains("plan_personal_activity"))
        }
    }

    @Test fun leaseEndDecisionActuallyOffersUnfinishedWorkReviewBeforeSleep() {
        val renewal = onlineRenewalDecisionInstruction()
        for (phrase in listOf("【五分钟短复盘", "未完成/未核实的责任",
            "自己仍然持有的挂心", "之前真正排好的日程", "action/steps",
            "plan_personal_activity", "没有新变化时可以全部保留",
            "不能把「去看看」当作已经执行")) {
            assertTrue("Missing quick review guidance: $phrase", renewal.contains(phrase))
        }
    }

    @Test fun onlineExpiryPromptKeepsDecisionAndHandoffContract() {
        // Runtime now supplies only live facts for an expiring lease; static
        // instructions must retain the single model-owned decision and its
        // executable task handoff. This is not a real-model quality test.
        val renewal = onlineRenewalDecisionInstruction()
        assertEquals(1, renewal.split("【五分钟自主续醒决定｜本轮必须回答】").size - 1)
        for (required in listOf(
            "onlineDecision", "continue|sleep", "choice 和 reason 均为必填",
            "nextChecks 写 taskId", "带时区偏移", "动作完成不等于目标达成",
            "提醒已送达不代表她已入睡", "不能因有挂心就被迫续时",
            "不能把时间到了当作所有挂心自动完成",
        )) assertTrue("Missing renewal requirement: $required", renewal.contains(required))
        assertTrue(renewal.contains("字段缺失时程序只会请你补正"))
        assertTrue(renewal.contains("由你本人选择"))
    }

    @Test fun idleAutonomousDecisionStillCanActOrStaySilentAfterRuntimeRuleTrim() {
        val prompt = proactiveDecisionInstruction("autonomy-no-new-messages")
        for (required in listOf(
            "plan_personal_activity", "send_character_message",
            "web_search", "background_phone", "learn_topic",
            "action=tool", "silent", "真实", "innerLife.motives",
        )) assertTrue("Missing autonomous capability: $required", prompt.contains(required))
        assertTrue(prompt.contains("自己确实决定暂不行动"))
    }

    @Test fun compiledAutonomousPromptKeepsUniqueActionContractsAfterRuleConsolidation() {
        val prompt = proactiveDecisionInstruction("prompt-assembly-test-role")
        for (field in listOf(
            "web_search", "background_phone", "image_search", "learn_topic",
            "experienceId", "innerThoughtBasis", "innerLife", "motiveRef",
            "outcomeEvidenceId", "concernId", "send_character_message",
            "plan_personal_activity", "requiresPreviousSuccess",
        )) assertTrue("Missing contract: $field", prompt.contains(field))
        val causal = CharacterDecisionProtocol.runtimePrinciples.trim()
        assertEquals(prompt.indexOf(causal), prompt.lastIndexOf(causal))
    }

    @Test fun realBodyAndSharingNeedsCanGenerateThoughtsAlongsideMultipleChosenActions() {
        val guidance = spontaneousInnerVoiceGuide
        assertTrue(guidance.contains("高强度生活需求会先牵动念头"))
        assertTrue(guidance.contains("appraisal.privateIdeas"))
        assertTrue(guidance.contains("不用第三人称任务安排"))
        assertTrue(guidance.contains("另放 nextAction/steps"))
        assertTrue(guidance.contains("同一次 JSON 决策"))
        assertTrue(guidance.contains("模型没有选择的动作不可由程序按数值替做"))
        val proactive = proactiveDecisionInstruction("drive-aware-autonomous")
        val livePrivate = proactiveDecisionInstruction("drive-aware-private", directPrivateChat = true)
        for (prompt in listOf(proactive, livePrivate)) {
            assertTrue("Both decision entrances need the same drive-aware inner-life protocol",
                prompt.contains("高强度生活需求会先牵动念头"))
            assertTrue("Both decision entrances must preserve model-authored parallel steps",
                prompt.contains("steps") && prompt.contains("appraisal"))
        }
    }

    @Test fun autonomousChoiceKeepsUnansweredOwnerActivityUncertainAndOffersRealConsentTools() {
        val independent = proactiveDecisionInstruction("uncertainty-test-role")
        val privateChat = proactiveDecisionInstruction("uncertainty-test-role", directPrivateChat = true)
        assertTrue(independent.contains("未回复不自动取消尚未落实的关心与目标"))
        assertTrue(independent.contains("经过授权用现有屏幕/监督工具获得真实观察"))
        assertTrue(privateChat.contains("单纯未回复、切到别的应用或旧的忙碌记录"))
        assertTrue(privateChat.contains("不能把猜测当作必须停止关心的事实"))
        assertTrue(privateChat.contains("start_study_watch"))
        assertTrue(privateChat.contains("不能把一次拒绝推断成未来长期禁止"))
        assertTrue(privateChat.contains("必须获得新时段的主人明确许可"))
    }

    @Test fun acceptedBedtimeSupervisionIsNotReducedToUnspokenConcern() {
        val now = java.time.Instant.parse("2026-11-01T16:31:00Z")
        val promise = CommitmentTask(
            id = "bedtime-commitment",
            characterId = "role",
            goal = "每天晚上主动哄睡并确认她是否准备休息",
            status = CommitmentTaskStatus.WaitingForFeedback,
            dueAt = now.minusSeconds(61 * 60L),
            completionCondition = "睡眠状态获得真实可追溯核实",
            lastActionResult = "23:30 已展示弹窗，尚无入睡证据",
            repeat = "daily",
        )
        val cue = ActiveCommitmentAttention.render(listOf(promise), now)
        assertTrue(cue.contains("taskId=bedtime-commitment"))
        assertTrue(cue.contains("实际效果尚未证实"))
        assertTrue(cue.contains("未安排"))
        assertTrue(cue.contains("没有新回复"))
        assertTrue(cue.contains("schedule_commitment_review"))
        assertTrue(cue.contains("弹窗"))
        assertTrue("Review is not a free delay", cue.contains("等待代价"))
        assertTrue("The model should consider acting now, without a forced interval",
            cue.contains("现在做与以后再做"))
        assertTrue("A stored alarm cannot unconditionally silence an independent decision",
            cue.contains("闹钟当成停止判断的理由"))
        // Only a future one-shot reminder is not a demand for another immediate
        // message; do not create a generic night-time conversation mandate.
        val future = promise.copy(
            status = CommitmentTaskStatus.Scheduled,
            dueAt = now.plusSeconds(3600),
            nextCheckAt = now.plusSeconds(3600),
        )
        assertEquals("", ActiveCommitmentAttention.render(listOf(future), now))
        assertEquals("", ActiveCommitmentAttention.render(emptyList(), now))
    }

    @Test fun overnightReviewMustNotEraseHowLateOriginalCommitmentAlreadyIs() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val now = java.time.Instant.parse("2026-11-03T17:00:00Z") // 11/04 01:00
        val original = java.time.Instant.parse("2026-11-03T15:30:00Z") // 11/03 23:30
        val review = java.time.Instant.parse("2026-11-03T17:30:00Z") // 11/04 01:30
        val task = CommitmentTask(
            id = "overnight-check", characterId = "role",
            goal = "在约定时间开始睡前提醒，后来持续核实入睡结果",
            status = CommitmentTaskStatus.WaitingForFeedback,
            dueAt = original, nextCheckAt = review, timezone = zone.id,
            followUpKind = "verification", linkedAlarmId = "review-alarm",
            lastActionResult = "原定提醒已发送，入睡结果尚无可靠证据",
        )
        val facts = CommitmentClockFacts.render(task, now, zone)
        assertTrue(facts, facts.contains("原定行动时间=11月3日 23:30（已过去90分钟）"))
        assertTrue(facts, facts.contains("结果复查时间=11月4日 01:30（距离现在还有30分钟）"))
        assertTrue(facts, facts.contains("原定行动时间没有因此被改写"))
        val autonomy = ActiveCommitmentAttention.render(listOf(task), now)
        assertTrue(autonomy, autonomy.contains("原定行动时间="))
        assertTrue(autonomy, autonomy.contains("结果复查时间="))
        assertTrue(autonomy, autonomy.contains("复查时刻是不同事实"))
        assertTrue(autonomy, autonomy.contains("无证据仍不能推断对方已经睡着或仍然醒着"))
        // The same factual distinction reaches the normal commitment model:
        // no program branch chooses call/silent or extends the original goal.
        val decision = CommitmentDecisionProtocol.context(listOf(task), now, zone)
        assertTrue(decision, decision.contains("已过去90分钟"))
        assertTrue(decision, decision.contains("距离现在还有30分钟"))
        assertEquals(original, task.dueAt)
        assertEquals(review, task.nextCheckAt)
    }

    @Test fun pendingReviewOrOtherDaytimeGoalUsesSameClockWithoutInventingBedtime() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val now = java.time.Instant.parse("2026-11-04T05:00:00Z") // 13:00 local
        val task = CommitmentTask(
            id = "daytime-task", characterId = "role",
            goal = "午后核查项目审核结果",
            status = CommitmentTaskStatus.WaitingForFeedback,
            dueAt = now.minusSeconds(60 * 40),
            nextCheckAt = now.plusSeconds(60 * 20),
        )
        val facts = CommitmentClockFacts.render(task, now, zone)
        assertTrue(facts, facts.contains("已过去40分钟"))
        assertTrue(facts, facts.contains("距离现在还有20分钟"))
        assertFalse(facts.contains("睡觉"))
        assertFalse(facts.contains("催"))
        val futureDue = task.copy(status = CommitmentTaskStatus.Scheduled,
            dueAt = now.plusSeconds(30 * 60), nextCheckAt = null)
        assertTrue(CommitmentClockFacts.render(futureDue, now, zone)
            .contains("距离现在还有30分钟"))
        // An upcoming task has factual timing but does not cause an alarm or
        // an automatic conversational decision in the passive attention policy.
        assertEquals("", ActiveCommitmentAttention.render(listOf(futureDue), now))
        val undated = task.copy(dueAt = null, nextCheckAt = null)
        assertEquals("", CommitmentClockFacts.render(undated, now, zone))
        val expired = task.copy(status = CommitmentTaskStatus.Completed)
        assertEquals("", ActiveCommitmentAttention.render(listOf(expired), now))
    }

    @Test fun autonomyPromptPreservesExplicitSupervisionWithoutForcingEveryRoleToTalk() {
        val instruction = proactiveDecisionInstruction("bedtime-prompt-test")
        assertTrue(instruction.contains("已接受的责任与普通心事不同"))
        assertTrue(instruction.contains("不能仅凭猜测替她撤回约定"))
        assertTrue(instruction.contains("无论本轮说过话、用了其他工具还是选择 silent"))
        assertTrue(instruction.contains("不自动变成取消监督或必须等她主动报备"))
        assertTrue(instruction.contains("真实拒绝与权限不足继续遵守"))
    }

    @Test fun validSilentChoiceIsParsedAndKeepsItsReason() {
        val parsed = parse("""{"action":"silent","reason":"今晚真的想一个人消化刚才的经历","mood":"平静"}""")
        assertNotNull(parsed)
        assertTrue(parsed.toString().contains("action=SILENT"))
        assertTrue(parsed.toString().contains("今晚真的想"))
    }

    @Test fun actualSpontaneousSharingAndJournalingStayExecutable() {
        assertTrue(parse("""{"action":"moment","text":"刚读到那句我愣住了。"}""")
            .toString().contains("action=MOMENT"))
        assertTrue(parse("""{"action":"journal","journalTitle":"今天","journalContent":"还在想那一幕"}""")
            .toString().contains("action=JOURNAL"))
    }

    @Test fun invalidUnknownActionIsNotSilentlySwallowed() {
        assertNull(parse("""{"action":"invented_human_action","reason":"这是不存在的工具"}"""))
    }

    @Test fun modelChosenAutonomousBubblesSurviveTheDecisionAndSender() {
        val decision = parse("""{"action":"message","bubbles":["辛苦啦宝宝，先歇一会儿。","那张震惊猫猫头把我逗笑了。"]}""")!!
        val field = decision.javaClass.getDeclaredField("text").apply { isAccessible = true }
        val spoken = field.get(decision) as String
        assertTrue(spoken.contains(com.jiacimu.lulu.SemanticBubbleSeparator))
        assertEquals(listOf("辛苦啦宝宝，先歇一会儿。", "那张震惊猫猫头把我逗笑了。"),
            com.jiacimu.lulu.semanticReplyBubbles(spoken))
    }

    @Test fun realParsedPrivateDecisionHasOneSpeechOwnerBeforeExecution() {
        val parsed = parse("""{
            "action":"message",
            "bubbles":["学得怎么样了，宝宝？","头还晕不晕？"],
            "steps":[
                {"action":"message","text":"学得怎么样了，宝宝？头还晕不晕？"},
                {"tool":"send_private_message","args":{"text":"喝点温水。"}},
                {"tool":"control_music","args":{"command":"pause"}}
            ]
        }""")!!
        val before = parsed.javaClass.getDeclaredField("sideActions").apply { isAccessible = true }
            .get(parsed) as org.json.JSONArray
        assertEquals(3, before.length())
        val normalize = ProactivePerceptionRuntime::class.java.declaredMethods.first {
            it.name == "withSinglePrivateInboundReply" && it.parameterCount == 1
        }.apply { isAccessible = true }
        val consolidated = normalize.invoke(ProactivePerceptionRuntime, parsed)!!
        val textField = consolidated.javaClass.getDeclaredField("text").apply { isAccessible = true }
        val spoken = textField.get(consolidated) as String
        assertEquals(listOf("学得怎么样了，宝宝？", "头还晕不晕？", "喝点温水。"),
            com.jiacimu.lulu.semanticReplyBubbles(spoken))
        val after = consolidated.javaClass.getDeclaredField("sideActions").apply { isAccessible = true }
            .get(consolidated) as org.json.JSONArray
        assertEquals(1, after.length())
        assertEquals("control_music", after.getJSONObject(0).getString("tool"))
        assertFalse(after.toString().contains("send_private_message"))
    }

    @Test fun partialAutonomousJsonKeepsCompletedBubblesWithoutInventingAnotherReply() {
        val incomplete = """{"action":"message","bubbles":["刚学完？","过来让我抱抱。"],"innerLife":{"unfinished":"""
        val recovered = AutonomousDecisionRecovery.completedPrivateMessage(incomplete)
        assertNotNull(recovered)
        assertEquals(listOf("刚学完？", "过来让我抱抱。"),
            com.jiacimu.lulu.semanticReplyBubbles(recovered!!.optString("text")))
    }

    private fun parse(raw: String): Any? {
        val method = ProactivePerceptionRuntime::class.java.declaredMethods.first {
            it.name == "parseDecision" && it.parameterCount == 2
        }
        method.isAccessible = true
        return method.invoke(ProactivePerceptionRuntime, raw, false)
    }
}
