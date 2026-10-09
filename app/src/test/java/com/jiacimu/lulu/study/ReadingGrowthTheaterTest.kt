package com.jiacimu.lulu.study

import android.content.Context
import com.jiacimu.lulu.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [31])
class ReadingGrowthTheaterTest {
    @Test fun languageHabitsRequireTwoSelfExpressionsAndIndependentLifeSources() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.characters.initialize(context)
        CharacterIdentityStore.initialize(context)
        CharacterLifeStore.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        CharacterDevelopmentStore.initialize(context)
        SharedExperienceTimeline.initialize(context)
        val person = MigratedDomainStores.characters.create("学会幽默的数字生命", "成人判断成熟，生活经验尚少")
        val at = Instant.now()
        val experiences = listOf(
            Triple("learning-book", "独自阅读《诗集》", "实际读到了文字表达"),
            Triple("learning-game", "独自游戏", "实际玩过一局游戏"),
            Triple("world-fact-learning-walk", "数字世界活动", "实际去过世界地点"),
        )
        experiences.forEachIndexed { index, (id, channel, detail) ->
            SharedExperienceTimeline.record(id, person.characterId, channel, person.displayName,
                detail, at.plusSeconds(index.toLong()), false)
        }
        val firstExpression = "learning-owned-line-1"
        val secondExpression = "learning-owned-line-2"
        SharedExperienceTimeline.record(firstExpression, person.characterId, "私人日记",
            person.displayName, "今天试着用一个奇怪的停顿自言自语", at.plusSeconds(4), false,
            source = "journal:own", evidenceKind = EventEvidenceKind.CharacterStatement)
        SharedExperienceTimeline.record(secondExpression, person.characterId, "私聊",
            person.displayName, "嗯……算了，我确实挺喜欢这种停顿", at.plusSeconds(5), false,
            source = "message", evidenceKind = EventEvidenceKind.CharacterStatement)
        val persona = CharacterRuntime.personaConstraintSnapshot(person.characterId)
        val exposureIds = experiences.map { it.first }
        fun learn(ids: List<String>) = CharacterDevelopmentStore.applyProposal(
            person.characterId, "person:speech:hesitant_pause", DevelopmentKind.ExpressionHabit,
            "偶尔用长停顿来表现认真思考，亲近时也拿来开玩笑",
            ids, emptyList(), persona)
        assertFalse(learn(exposureIds))
        assertFalse(learn(exposureIds + firstExpression))
        assertTrue(learn(exposureIds + firstExpression + secondExpression))
        assertTrue(CharacterDevelopmentStore.active(person.characterId)
            .any { it.kind == DevelopmentKind.ExpressionHabit })
        SharedExperienceTimeline.deleteEvent(firstExpression)
        assertTrue(CharacterDevelopmentStore.active(person.characterId)
            .none { it.kind == DevelopmentKind.ExpressionHabit })
    }

    @Test fun readingCommitsOnlyOnSuccessGrowthUsesRepeatedEvidenceAndTheaterRejectsStaleResults() {
        val context = RuntimeEnvironment.getApplication() as Context
        StarWishStores.initialize(context)
        ReadingReflectionStore.initialize(context)
        val text = "第一章 开始\n" + "正文".repeat(500) + "\n第二章 后来\n" + "后文".repeat(500)
        context.getSharedPreferences("lulu_reading_library", 0).edit().putString("books_v1",
            JSONArray().put(JSONObject().put("id", "test-book").put("title", "测试书").put("content", text)).toString()).commit()
        val a = ReadingBackgroundBridge.nextSlice(context, "reader-a", "test-book")!!
        assertEquals(readingSections(a.book)[0].end, a.endOffset)
        assertEquals(a, ReadingBackgroundBridge.nextSlice(context, "reader-a", "test-book"))
        val record = ReadingReflectionRecord(characterId = "reader-a", bookId = a.book.id, bookTitle = a.book.title,
            chapterTitle = "第一章 开始", revision = readingRevision(a.book), startOffset = a.startOffset,
            endOffset = a.endOffset, reflection = "我喜欢这段里的安静。", occurredAt = Instant.now())
        val generation = ReadingReflectionStore.generation("reader-a")
        ReadingReflectionStore.completeRead(record, a, generation) {}
        assertEquals(record, ReadingReflectionStore.get(record.id))
        assertEquals(a.endOffset, ReadingBackgroundBridge.nextSlice(context, "reader-a", "test-book")!!.startOffset)
        assertEquals(0, ReadingBackgroundBridge.nextSlice(context, "reader-b", "test-book")!!.startOffset)
        ReadingReflectionStore.clearCharacter("reader-a")
        assertNull(ReadingReflectionStore.get(record.id))
        assertTrue(runCatching { ReadingReflectionStore.completeRead(record, a, generation) {} }.isFailure)
        assertEquals(0, ReadingBackgroundBridge.nextSlice(context, "reader-a", "test-book")!!.startOffset)
        assertTrue(ReadingBackgroundBridge.books(context).any { it.id == "test-book" })

        MigratedDomainStores.characters.initialize(context)
        CharacterIdentityStore.initialize(context)
        CharacterLifeStore.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        CharacterDevelopmentStore.initialize(context)
        SharedExperienceTimeline.initialize(context)
        val role = MigratedDomainStores.characters.create("成长角色", "保持自己的身份")
        val persona = CharacterRuntime.personaConstraintSnapshot(role.characterId)
        val ids = (1..3).map { "reading-growth-$it" }
        val now = Instant.now().plusSeconds(2)
        ids.forEachIndexed { index, id -> SharedExperienceTimeline.record(id, role.characterId, "独自阅读《诗集》",
            role.displayName, "阅读真实原文。阅读感想：我喜欢文字里的安静。", now.plusSeconds(index.toLong()), false,
            source = "reading:poems", sessionId = id) }
        fun propose(evidence: List<String>, content: String = "喜欢安静的诗歌", counter: List<String> = emptyList()) =
            CharacterDevelopmentStore.applyProposal(role.characterId, "poetry", DevelopmentKind.Interest,
                content, evidence, counter, persona)
        assertFalse(propose(ids.take(2)))
        assertTrue(propose(ids))
        assertEquals("喜欢安静的诗歌", CharacterDevelopmentStore.active(role.characterId).single().content)
        assertTrue(propose(ids.drop(1) + ids.first(), "从喜欢意境转向关注节奏", listOf(ids.first())))
        assertEquals(2, CharacterDevelopmentStore.active(role.characterId).single().version)
        SharedExperienceTimeline.record("later-counter", role.characterId, "私聊", role.displayName,
            "现在并不喜欢诗歌", now.plusSeconds(30), false, evidenceKind = EventEvidenceKind.CharacterStatement)
        assertFalse(propose(ids, "突然更喜欢诗歌", listOf("later-counter")))
        CharacterLifeStore.setProfile(role.characterId, "interests", "只喜欢天文")
        // Editing a locked persona invalidates earlier growth snapshots.
        assertTrue(CharacterDevelopmentStore.active(role.characterId).isEmpty())
        // Witnessed later interests are kept separate from the user's fixed personality,
        // not prohibited globally by one manually set 'interests' text field.
        assertTrue(CharacterDevelopmentStore.applyProposal(role.characterId, "poetry", DevelopmentKind.Interest,
            "开始关注诗歌的节奏", ids, emptyList(), CharacterRuntime.personaConstraintSnapshot(role.characterId)))
        assertEquals("只喜欢天文", CharacterLifeStore.state(role.characterId)
            .getJSONObject("profile").getString("interests"))
        assertEquals("开始关注诗歌的节奏",
            CharacterDevelopmentStore.active(role.characterId).single().content)

        val store = StarWishStores.main
        store.setStoryPlan("回归故事", "旧设定", emptyList())
        val old = store.state.value
        store.setTheaterWorldBookIds("回归故事", setOf("new-world-book"))
        val chapter = StarWishTheaterChapter(theater = "回归故事", chapter = 1, title = "开篇", content = "正文", userInfluence = "")
        assertTrue(runCatching { store.appendGeneratedChapter(chapter, old) }.isFailure)
        assertTrue(store.state.value.theaterChapters["回归故事"].isNullOrEmpty())
        val current = store.state.value
        store.appendGeneratedChapter(chapter, current)
        val second = StarWishTheaterChapter(theater = "回归故事", chapter = 2, title = "后续", content = "第二章真实正文", userInfluence = "")
        store.appendGeneratedChapter(second, store.state.value)
        val firstBookId = "theater-chapter:${chapter.id}"
        val secondBookId = "theater-chapter:${second.id}"
        assertTrue(ReadingBackgroundBridge.availableBooks(context, "serial-reader").any { it.id == firstBookId })
        assertFalse(ReadingBackgroundBridge.availableBooks(context, "serial-reader").any { it.id == secondBookId })
        val firstChapter = ReadingBackgroundBridge.nextSlice(context, "serial-reader", firstBookId)!!
        assertTrue(ReadingBackgroundBridge.commitSlice(context, "serial-reader", firstChapter))
        assertTrue(ReadingBackgroundBridge.availableBooks(context, "serial-reader").any { it.id == secondBookId })
        assertTrue(runCatching { store.appendGeneratedChapter(chapter, current) }.isFailure)
        store.deleteTheater("回归故事")
        store.setGeneratedLedger("回归故事", StarWishStoryLedger(summary = "旧结果", updatedThroughChapter = 1), current, chapter)
        assertNull(store.state.value.theaterLedgers["回归故事"])
        val disk = JSONObject(java.io.File(context.filesDir, "starwish/state_v2.json").readText())
        assertFalse(disk.toString().contains("旧结果"))
    }
}
