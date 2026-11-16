package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PromptContextPolicyTest {
    @Test fun quietAutonomousPulseAvoidsPaidRerankWithoutDisablingNewMessageRecall() {
        assertFalse(PromptContextPolicy.paidRankingFor("后台主动感知", UnifiedMemoryRequest(
            sceneContext = "本轮实际世界状态", taskIntent = "观察并自主决定")))
        assertTrue(PromptContextPolicy.paidRankingFor("后台主动感知", UnifiedMemoryRequest(
            currentInput = "你还记得昨天的事吗？")))
        assertTrue(PromptContextPolicy.paidRankingFor("电话回复", UnifiedMemoryRequest()))
        assertTrue(PromptContextPolicy.paidRankingFor("聊天工具规划",
            UnifiedMemoryRequest(currentInput = "随便聊聊")))
    }

    @Test fun normalChatKeepsASmallBoundedEvidenceWindowWithoutBecomingHeavyweight() {
        val b = PromptContextPolicy.forRequest("聊天工具规划",
            UnifiedMemoryRequest(currentInput = "今天看见小猫啦", sceneContext = "私聊"))
        assertEquals(2_000, b.evidenceCharacters)
        assertTrue(b.memoryCharacters >= 6_000)
        assertTrue(b.workingSetCharacters >= 18_000)
        assertTrue(b.evidenceCharacters < b.memoryCharacters)
        assertTrue(b.memoryCharacters > 0)
        assertTrue(b.recentCharacters > 0)
        assertFalse(b.worldDetails)
        assertFalse(b.richInnerLife)
    }

    @Test fun promisesAndExactQuotesGainOriginalEvidence() {
        val b = PromptContextPolicy.forRequest("聊天工具规划",
            UnifiedMemoryRequest(currentInput = "你上次说过要叫醒我，实际执行了吗？"))
        assertTrue(b.precise)
        assertTrue(b.evidenceCharacters >= 4_000)
        assertTrue(b.richInnerLife)
    }

    @Test fun anOldTopicDoesNotForceDetailedWorldOnEveryNewTurn() {
        val b = PromptContextPolicy.forRequest("聊天工具规划",
            UnifiedMemoryRequest(currentInput = "嗨", recentContext = "昨天我们聊了家具和约定"))
        assertFalse(b.precise)
        assertFalse(b.worldDetails)
    }

    @Test fun longTopMemoryStillGetsPromptSpaceWithoutMutatingOriginal() {
        val hugeText = "一段确实发生的共同经历".repeat(500)
        val long = com.jiacimu.lulu.core.MemoryEntry(
            id = "top", characterId = "role", content = hugeText,
            kind = com.jiacimu.lulu.core.MemoryKind.Fact, source = "test",
            occurredAt = null, createdAt = java.time.Instant.EPOCH,
            strength = 3, pinned = false, canRecallProactively = true,
        )
        val short = long.copy(id = "other", content = "另一条应当保留的真实记忆")
        val result = PromptMemoryRenderer.render(listOf(long, short), 1_400)
        assertTrue(result.contains("一段确实发生"))
        assertTrue(result.contains("另一条应当保留"))
        assertTrue(result.length <= 1_400)
        assertEquals(hugeText, long.content)
    }

    @Test fun worldNeedsExpandOnlyWhenSceneReallyRequiresIt() {
        val meeting = PromptContextPolicy.forRequest("数字世界见面",
            UnifiedMemoryRequest(currentInput = "这个桌子能搬吗"))
        val background = PromptContextPolicy.forRequest("后台主动感知", UnifiedMemoryRequest())
        assertTrue(meeting.worldDetails)
        assertFalse("A quiet autonomous reflection should not copy the full world registry", background.worldDetails)
        assertTrue(PromptContextPolicy.forRequest("后台主动感知",
            UnifiedMemoryRequest(currentInput = "想看看家里的书架能不能移动")).worldDetails)
        assertTrue(meeting.richInnerLife)
        assertTrue(background.evidenceCharacters >= 5_000)
        assertTrue(background.workingSetCharacters >= 27_000)
        assertTrue(meeting.memoryCharacters >= 7_800)
        assertTrue(background.recallLimit >= 40)
    }
    @Test fun privateAndSoloLivesShareOneRichMemoryTierAndPrioritizeOwnerDialogue() {
        val input = UnifiedMemoryRequest(
            currentInput = "我想再聊聊今天做的事",
            sceneContext = "普通生活中的当前聊天与自主选择",
        )
        val independent = PromptContextPolicy.forRequest("后台主动感知", input)
        val chatting = PromptContextPolicy.forRequest(
            "后台主动感知", input, hasLiveConversation = true)
        val ordinaryChat = PromptContextPolicy.forRequest("私聊", input)
        assertTrue(independent.richInnerLife)
        assertTrue(chatting.richInnerLife)
        assertTrue(ordinaryChat.richInnerLife)
        assertEquals(independent.recallLimit, chatting.recallLimit)
        assertEquals(independent.recentCharacters, chatting.recentCharacters)
        assertEquals(independent.lexiconCharacters, chatting.lexiconCharacters)
        assertEquals(independent.worldDetails, chatting.worldDetails)
        assertFalse(independent.worldDetails)
        // Speaking with the owner must not reduce how much the person knows.
        assertTrue(chatting.memoryCharacters >= independent.memoryCharacters)
        assertTrue(chatting.evidenceCharacters >= independent.evidenceCharacters)
        assertTrue(chatting.workingSetCharacters >= independent.workingSetCharacters)
        assertTrue(chatting.workingSetCharacters - independent.workingSetCharacters <= 1_000)
        assertEquals(ordinaryChat.memoryCharacters, chatting.memoryCharacters)
        assertEquals(ordinaryChat.workingSetCharacters, chatting.workingSetCharacters)
        val game = PromptContextPolicy.forRequest("游戏 · 方块小岛自主同行", input)
        assertTrue(game.richInnerLife)
        assertEquals(independent.workingSetCharacters, game.workingSetCharacters)
    }

    @Test fun actualWorldOrPromiseEvidenceCanExpandWithoutChangingTheSharedPerson() {
        val source = "后台主动感知"
        val world = UnifiedMemoryRequest(currentInput = "去数字世界的庭院用真实物品")
        val precise = UnifiedMemoryRequest(currentInput = "你上次答应我的叫醒真正做到了吗？")
        for (conversation in listOf(false, true)) {
            val situated = PromptContextPolicy.forRequest(source, world,
                hasLiveConversation = conversation)
            val verifying = PromptContextPolicy.forRequest(source, precise,
                hasLiveConversation = conversation)
            assertTrue(situated.worldDetails)
            assertTrue(situated.richInnerLife)
            assertTrue(verifying.precise)
            assertTrue(verifying.evidenceCharacters >= 7_000)
        }
    }

    @Test fun fullCharacterDecisionsUseIdenticalMemoryTierAcrossEveryRealScene() {
        val request = UnifiedMemoryRequest(
            currentInput = "我在想今天那些真实经历",
            sceneContext = "这个角色本人的当前场景",
            taskIntent = "按已有真实经历独立做决定",
        )
        // Not a heuristic source list in production: Full is the authoritative
        // actor decision flag. The scene label must never deprive this person
        // of its other memories, commitments, feelings or desired activities.
        val realScenes = listOf(
            "后台主动感知",
            "私聊",
            "聊天工具规划",
            "电话回复",
            "群聊本人发言",
            "朋友圈独立感知",
            "数字世界见面",
            "数字世界居民交流",
            "游戏 · 方块小岛自主同行",
            "游戏 · 迷雾摸金自主同行",
            "游戏",
            "一起看",
            "原住民个人日程到点",
        )
        val budgets = realScenes.map { scene ->
            PromptContextPolicy.forRequest(
                scene, request, fullCharacterDecision = true,
            )
        }
        val first = budgets.first()
        budgets.forEachIndexed { index, actual ->
            assertEquals(realScenes[index], first.recallLimit, actual.recallLimit)
            assertEquals(realScenes[index], first.memoryCharacters, actual.memoryCharacters)
            assertEquals(realScenes[index], first.evidenceCharacters, actual.evidenceCharacters)
            assertEquals(realScenes[index], first.recentCharacters, actual.recentCharacters)
            assertEquals(realScenes[index], first.lexiconCharacters, actual.lexiconCharacters)
            assertEquals(realScenes[index], first.workingSetCharacters, actual.workingSetCharacters)
            assertTrue(realScenes[index], actual.richInnerLife)
            assertEquals(28_000, actual.workingSetCharacters)
        }
        // Distinct actual scenes can require different authoritative world
        // inventories; this is not a second personality or a different memory tier.
        assertFalse(budgets.first().worldDetails)
        assertTrue(budgets[6].worldDetails)
        // A real new owner message cannot lower the person's memory.
        assertEquals(first, PromptContextPolicy.forRequest(
            "后台主动感知", request, hasLiveConversation = true,
            fullCharacterDecision = true))
    }

    @Test fun sharedActorStillExpandsSourceEvidenceWhenActualPromiseMustBeChecked() {
        val request = UnifiedMemoryRequest(
            currentInput = "你答应过的那次电话，实际完成了吗？")
        listOf("后台主动感知", "电话回复", "游戏 · 方块小岛自主同行").forEach { scene ->
            val budget = PromptContextPolicy.forRequest(
                scene, request, fullCharacterDecision = true)
            assertTrue(budget.precise)
            assertTrue(budget.richInnerLife)
            assertEquals(7_000, budget.evidenceCharacters)
            assertEquals(31_000, budget.workingSetCharacters)
        }
    }

    @Test fun alreadySentSceneEvidenceDoesNotPolluteRecallQuery() {
        val normal = UnifiedMemoryRequest(
            currentInput = "还记得我们约好的事吗？",
            recentContext = "昨天说过明天提醒",
            taskIntent = "按实际经历决定",
        )
        val alreadySent = normal.copy(
            alreadyPresentedEvidence = "这里是已经作为本轮场景发送的另一份原始记录")
        assertEquals(normal.retrievalQuery(), alreadySent.retrievalQuery())
    }

}
