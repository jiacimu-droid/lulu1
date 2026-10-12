package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.study.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ReadingReactionIntegrationTest {
    private lateinit var context: Context
    private lateinit var role: String
    private val text = "第一章 门后\n门突然开了。原来是猫！他忍不住笑了。"
    @Before fun before() {
        MemoryTestEnvironment.release()
        context = RuntimeEnvironment.getApplication()
        LuluRepositories.initialize(context)
        MigratedDomainStores.initialize(context)
        CharacterLifeStore.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        CompanionPresenceStore.initialize(context)
        SharedExperienceTimeline.initialize(context)
        StarWishStores.initialize(context)
        LuluAiServices.initialize(context)
        role = MigratedDomainStores.characters.create("读者", "说话随性，有自己的感受").characterId
        context.getSharedPreferences("lulu_reading_library", 0).edit().putString("books_v1",
            JSONArray().put(JSONObject().put("id", "reaction-book").put("title", "门后")
                .put("content", text)).toString()).commit()
    }
    @After fun after() { MemoryTestEnvironment.release() }
    private suspend fun read(response: JSONObject): ReadingReflectionRecord {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
                JSONObject().put("content", response.toString())).put("finish_reason", "stop"))).toString()))
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null, "reading-test",
                server.url("/").toString(), "test-only")
            LuluAiServices.connectionStore.addArchive(config.id, "test")
            val result = CompanionActionRuntime.execute(context, role, "read_book",
                JSONObject().put("readingBookId", "reaction-book"))
            assertTrue(result.summary, result.success)
            val body = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
            assertTrue(body.toString().contains("阅读不是交书评作业"))
            assertEquals(1, server.requestCount)
            return ReadingReflectionStore.get(result.evidenceId)!!
        } finally { server.shutdown() }
    }
    @Test fun emptyFeelingStillCommitsActualReadingAndDeletingItRewindsProgress() = runBlocking {
        val record = read(JSONObject())
        assertEquals("", record.reflection)
        assertTrue(record.moments.isEmpty())
        assertEquals(text, record.sourceText)
        assertNull(ReadingBackgroundBridge.nextSlice(context, role, "reaction-book"))
        assertTrue(SharedExperienceTimeline.eventsByIds(role, listOf(record.id)).isNotEmpty())
        SharedExperienceTimeline.deleteEvent(record.id)
        assertNull(ReadingReflectionStore.get(record.id))
        assertFalse(MigratedDomainStores.chat.conversations.value.filter { it.characterId == role }
            .flatMap { MigratedDomainStores.chat.messages(it.id).value }
            .any { it.content.contains("[阅读记录|${record.id}]") })
        assertEquals(0, ReadingBackgroundBridge.nextSlice(context, role, "reaction-book")!!.startOffset)
    }
    @Test fun anchoredReactionAndExplicitLearningWishShareOneRealReadingSource() = runBlocking {
        val record = read(JSONObject()
            .put("moments", JSONArray().put(JSONObject().put("quote", "原来是猫！").put("reaction", "卧槽，被它骗了。")))
            .put("learning", JSONObject().put("topic", "悬念表达").put("understanding", "短铺垫可以先制造一个预期，再给出意外解释"))
            .put("learningIntent", JSONObject().put("topic", "悬念表达").put("question", "怎么让自己的短笑话也有自然的反转")))
        assertEquals("卧槽，被它骗了。", record.moments.single().reaction)
        assertTrue(CharacterLearningLedger.context(role, "笑话").contains("短铺垫"))
        val goal = CharacterLearningAgenda.goals(role).single()
        assertEquals(record.id, goal.optString("originId"))
        assertEquals(record.id, goal.optString("materialId"))
        // Reopen the actual durable preferences; a one-word reaction and its source survive.
        ReadingReflectionStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(null, null)
        ReadingReflectionStore.initialize(context)
        assertEquals(record, ReadingReflectionStore.get(record.id))
        SharedExperienceTimeline.deleteEvent(record.id)
        assertEquals("", CharacterLearningLedger.context(role, "笑话"))
        assertTrue(CharacterLearningAgenda.goals(role).isEmpty())
    }
    @Test fun failedRawSourceWriteDoesNotAdvanceCursorOrLeaveAPhantomReflection() {
        val slice = ReadingBackgroundBridge.nextSlice(context, role, "reaction-book")!!
        val record = ReadingReflectionRecord(characterId = role, bookId = slice.book.id,
            bookTitle = slice.book.title, chapterTitle = "第一章", revision = readingRevision(slice.book),
            startOffset = slice.startOffset, endOffset = slice.endOffset, reflection = "", occurredAt = Instant.now())
        assertTrue(runCatching { ReadingReflectionStore.completeRead(record, slice,
            ReadingReflectionStore.generation(role)) { error("来源保存失败") } }.isFailure)
        assertNull(ReadingReflectionStore.get(record.id))
        assertEquals(0, ReadingBackgroundBridge.nextSlice(context, role, "reaction-book")!!.startOffset)
    }
}
