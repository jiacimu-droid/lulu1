package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class TurtleSoupRoomTest {
    @Test fun longRoomKeepsAllQuestionsNegativeEvidenceAndDiscussionsAcrossReload() {
        val context = RuntimeEnvironment.getApplication() as android.content.Context
        context.getSharedPreferences("lulu_turtle_soup_room_v1", 0).edit().clear().commit()
        val archive = TurtleSoupRoomArchive(context)
        val lines = buildList {
            repeat(55) { index ->
                add(SoupLine(if (index % 2 == 0) "你" else "江渡", SoupKind.QUESTION,
                    "第" + index + "个假设：这与时间有关吗？"))
                add(SoupLine("固定主持人", SoupKind.ANSWER,
                    if (index % 2 == 0) "否" else "部分是", size - 1))
                add(SoupLine("你", SoupKind.DISCUSSION, "第" + index + "次讨论，保留早期推断"))
            }
        }
        val original = SoupRoom(caseId = "upward_rain", host = SoupHost.AI,
            companions = listOf("jiangdu"), lines = lines)
        archive.save(original)
        val reopened = TurtleSoupRoomArchive(context).read()!!
        assertEquals(original, reopened)
        assertEquals(55, reopened.questionCount)
        assertEquals(-1, reopened.pendingQuestion)
        val modelContext = reopened.publicContext()
        assertTrue(modelContext.contains("第0次讨论"))
        assertTrue(modelContext.contains("第54次讨论"))
        assertTrue(modelContext.contains("第0个假设"))
        assertTrue(modelContext.contains("第54个假设"))
        assertTrue(modelContext.contains("部分是"))
        assertTrue(modelContext.contains(reopened.puzzle.surface))
        assertFalse(modelContext.contains(reopened.puzzle.truth))
    }

    @Test fun userHostCanAnswerExactPendingQuestionWithoutLeakingTruthToCompanions() {
        val room = SoupRoom(caseId = "silent_subtitle", host = SoupHost.USER,
            companions = listOf("river", "cloud"),
            lines = listOf(
                SoupLine("river", SoupKind.QUESTION, "这是真实字幕吗？"),
                SoupLine("cloud", SoupKind.DISCUSSION, "先核对字幕的来源"),
                SoupLine("你 · 主持", SoupKind.ANSWER, "否", 0),
                SoupLine("cloud", SoupKind.QUESTION, "房间里还有其他声音吗？"),
            ))
        assertEquals(3, room.pendingQuestion)
        assertEquals(listOf("river", "cloud"), room.guessers)
        assertTrue(room.publicContext().contains("先核对字幕的来源"))
        assertFalse(room.publicContext().contains(room.puzzle.truth))
        val answered = room.copy(lines = room.lines + SoupLine("你 · 主持", SoupKind.ANSWER, "是", 3))
        assertEquals(-1, answered.pendingQuestion)
        assertEquals(answered, SoupRoom.fromJson(answered.toJson()))
    }

    @Test fun roleAssignmentKeepsDesignatedHostOutOfGuesserRoster() {
        val companions = listOf("alpha", "beta", "gamma")
        val ai = SoupRoom(companions = companions, host = SoupHost.AI)
        val character = ai.copy(host = SoupHost.CHARACTER)
        val user = ai.copy(host = SoupHost.USER)
        assertNull(ai.hostId)
        assertEquals(companions, ai.guessers)
        assertEquals("alpha", character.hostId)
        assertEquals(listOf("beta", "gamma"), character.guessers)
        assertEquals(companions, user.guessers)
    }

    @Test fun verdictAndCompanionDecisionsHaveClosedOutputContracts() {
        assertEquals("是", normalizeSoupVerdict("是。再想想。"))
        assertEquals("否", normalizeSoupVerdict("不是"))
        assertEquals("部分是", normalizeSoupVerdict("是也不是"))
        assertEquals("无关", normalizeSoupVerdict("与此无关"))
        assertNull(normalizeSoupVerdict("我觉得也许有可能"))
        assertEquals(SoupCompanionDecision("question", "与时间有关吗？"),
            parseSoupCompanionDecision("""{"type":"question","text":"与时间有关吗？"}"""))
        assertEquals(SoupCompanionDecision("wait", ""),
            parseSoupCompanionDecision("""{"type":"wait","text":""}"""))
        assertNull(parseSoupCompanionDecision("""{"type":"reveal","text":"泄底"}"""))
    }

    @Test fun originalCasesHaveLockedDistinctExplanationsAndHints() {
        assertTrue(SoupLibrary.cases.size >= 8)
        assertEquals(SoupLibrary.cases.size, SoupLibrary.cases.map { it.id }.distinct().size)
        SoupLibrary.cases.forEach { puzzle ->
            assertTrue(puzzle.surface.length >= 25)
            assertTrue(puzzle.truth.length >= 50)
            assertTrue(puzzle.hints.size >= 3)
            assertFalse(puzzle.surface == puzzle.truth)
        }
    }
    @Test fun caseSelectionAndExplicitStartSurviveReloadWithoutLosingLegacyPlay() {
        val lobby = SoupRoom(caseId = "caption_sos", host = SoupHost.AI,
            companions = listOf("jiangdu"))
        assertFalse(lobby.started)
        val reopenedLobby = SoupRoom.fromJson(lobby.toJson())!!
        assertEquals(lobby, reopenedLobby)
        assertEquals("caption_sos", reopenedLobby.caseId)
        assertFalse(reopenedLobby.started)
        assertTrue(SoupRoom.fromJson(lobby.copy(started = true).toJson())!!.started)

        // Old rooms created before the lobby upgrade had no started flag.
        // Their public turns mean they must resume as active instead of locking players out.
        val old = org.json.JSONObject(lobby.copy(
            lines = listOf(SoupLine("你", SoupKind.QUESTION, "有其他人在场吗？"))
        ).toJson())
        old.remove("started")
        val restored = SoupRoom.fromJson(old.toString())!!
        assertTrue(restored.started)
        assertEquals(1, restored.questionCount)
        assertEquals(0, restored.pendingQuestion)
        assertFalse(restored.publicContext().contains(restored.puzzle.truth))
    }

    @Test fun fullSolutionMayFailWithoutSpoilingTruthAndThenSucceedAfterReopen() {
        val initial = SoupRoom(caseId = "caption_sos", started = true,
            companions = listOf("jiangdu"), host = SoupHost.AI)
        val submitted = initial.copy(lines = listOf(
            SoupLine("你", SoupKind.QUESTION, "画面是在实时拍摄吗？"),
            SoupLine("固定主持人", SoupKind.ANSWER, "否", 0),
            SoupLine("江渡", SoupKind.DISCUSSION, "也许是一段录制"),
            SoupLine("你", SoupKind.SOLUTION, "这段直播可能是录好的"),
        ))
        assertEquals(3, submitted.pendingSolution)
        assertEquals(-1, submitted.pendingQuestion)
        assertEquals(1, submitted.solutionAttempts)
        assertFalse(submitted.publicContext().contains(submitted.puzzle.truth))

        val failed = submitted.copy(lines = submitted.lines +
            SoupLine("固定主持人", SoupKind.SOLUTION_RESULT, "还差一点 · 核心因果没解释完整", 3))
        assertEquals(-1, failed.pendingSolution)
        assertFalse(failed.revealed)
        assertFalse(failed.solved)

        val retry = failed.copy(lines = failed.lines + SoupLine("你", SoupKind.SOLUTION,
            "这其实是预录的画面，主播通过录制时留下的硬字幕求救"))
        assertEquals(5, retry.pendingSolution)
        val victory = retry.copy(lines = retry.lines + SoupLine(
            "固定主持人", SoupKind.SOLUTION_RESULT, "还原成功", 5
        ), revealed = true, solved = true)
        val restored = SoupRoom.fromJson(victory.toJson())!!
        assertEquals(victory, restored)
        assertTrue(restored.solved)
        assertEquals(2, restored.solutionAttempts)
        assertEquals(-1, restored.pendingSolution)
        assertTrue(restored.publicContext().contains("核心因果没解释完整"))
    }

    @Test fun strictFinalJudgeJsonRejectsAmbiguousOrNonBooleanDecisions() {
        assertEquals(SoupSolutionJudgment(true, "你说清楚了字幕求救与预录直播"),
            parseSoupSolutionJudgment("""{"correct":true,"feedback":"你说清楚了字幕求救与预录直播"}"""))
        assertEquals(SoupSolutionJudgment(false, "你还缺少核心原因"),
            parseSoupSolutionJudgment("""{"correct":false,"feedback":"你还缺少核心原因"}"""))
        assertNull(parseSoupSolutionJudgment("可能猜对了"))
        assertNull(parseSoupSolutionJudgment("""{"correct":"true","feedback":"猜对"}"""))
        assertNull(parseSoupSolutionJudgment("""{"correct":true}"""))
        assertEquals(SoupCompanionDecision("solve", "这不是实时画面"),
            parseSoupCompanionDecision("""{"type":"solve","text":"这不是实时画面"}"""))
    }

    @Test fun expandedOriginalCasesAreStableUniqueAndWellFormed() {
        assertEquals(28, SoupLibrary.cases.size)
        val ids = SoupLibrary.cases.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(SoupLibrary.cases.count { it.category.contains("惊悚") || it.category.contains("恐怖") } >= 3)
        SoupLibrary.cases.forEach { puzzle ->
            assertTrue("短汤面: " + puzzle.id, puzzle.surface.length >= 25)
            assertTrue("解释过短: " + puzzle.id, puzzle.truth.length >= 50)
            assertTrue("缺渐进提示: " + puzzle.id, puzzle.hints.size >= 3)
            assertTrue("有空提示: " + puzzle.id, puzzle.hints.none { it.isBlank() })
            assertEquals(puzzle, SoupLibrary.find(puzzle.id))
        }
    }

}
