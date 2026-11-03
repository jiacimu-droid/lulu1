package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.core.WorldBookEntry
import com.jiacimu.lulu.core.WorldBookPromptPosition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class WorldBookFolderRepositoryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun resetWorldBook() {
        context.getSharedPreferences("lulu_world_book", Context.MODE_PRIVATE).edit()
            .clear().putBoolean("human_chat_world_book_seeded_v1", true).commit()
    }

    @Test fun modernChatBookSeedsOnceAndRespectsOwnerDisableAndDeletion() = runBlocking {
        val storage = context.getSharedPreferences("lulu_world_book", Context.MODE_PRIVATE)
        storage.edit().remove("human_chat_world_book_seeded_v1").commit()
        val original = InMemoryWorldBookRepository().also { it.initialize(context) }
        val seeded = original.snapshot().single()
        assertEquals("现代聊天与自然表达", seeded.title)
        assertTrue(seeded.globalEnabled)
        assertEquals(WorldBookPromptPosition.BOTTOM, seeded.promptPosition)
        assertTrue(seeded.content.contains("感叹号适合真有惊讶"))
        original.setGlobalEnabled(seeded.id, false)
        assertFalse(InMemoryWorldBookRepository().also { it.initialize(context) }
            .snapshot().single().globalEnabled)
        original.delete(seeded.id)
        assertTrue(InMemoryWorldBookRepository().also { it.initialize(context) }.snapshot().isEmpty())
    }

    @Test fun modernChatBookNeverOverwritesExistingOwnerEntry() {
        val storage = context.getSharedPreferences("lulu_world_book", Context.MODE_PRIVATE)
        storage.edit().putString("entries_v1",
            """[{"id":"my-entry","title":"现代聊天与自然表达","content":"主人自己的版本","globalEnabled":false}]"""
        ).remove("human_chat_world_book_seeded_v1").commit()
        val saved = InMemoryWorldBookRepository().also { it.initialize(context) }.snapshot()
        assertEquals(1, saved.size)
        assertEquals("主人自己的版本", saved.single().content)
        assertFalse(saved.single().globalEnabled)
    }

    private fun entry(id: String) = WorldBookEntry(
        id = id, title = "标题$id", content = "正文$id",
        globalEnabled = true,
        characterOverrides = mapOf("role-a" to false, "role-b" to true),
    )

    @Test fun bulkMoveSurvivesReloadWithoutChangingOriginalOrderOrSwitches() = runBlocking {
        val repo = InMemoryWorldBookRepository()
        repo.initialize(context)
        (1..120).forEach { repo.save(entry("b$it")) }
        val before = repo.snapshot()
        val folderId = repo.saveFolder("人物")
        repo.moveEntriesToFolder((50..100).map { "b$it" }.toSet(), folderId)
        assertEquals(before.map { it.id }, repo.snapshot().map { it.id })
        assertEquals(before.map { it.globalEnabled to it.characterOverrides },
            repo.snapshot().map { it.globalEnabled to it.characterOverrides })
        val newRepo = InMemoryWorldBookRepository()
        newRepo.initialize(context)
        assertEquals(1, newRepo.observeFolders().first().size)
        assertEquals((50..100).map { "b$it" }.toSet(),
            newRepo.snapshot().filter { it.folderId == folderId }.map { it.id }.toSet())
        assertEquals(before.map { it.id }, newRepo.snapshot().map { it.id })
    }

    @Test fun deleteFolderOnlyUnclassifiesEntriesAndAllowsRename() = runBlocking {
        val repo = InMemoryWorldBookRepository()
        repo.initialize(context)
        repo.save(entry("a"))
        val id = repo.saveFolder("初始分类")
        repo.setEntryFolder("a", id)
        assertEquals(id, repo.snapshot().single().folderId)
        repo.saveFolder("重命名", id)
        assertEquals("重命名", repo.observeFolders().first().single().name)
        repo.deleteFolder(id)
        assertTrue(repo.observeFolders().first().isEmpty())
        assertNull(repo.snapshot().single().folderId)
        assertEquals(false, repo.snapshot().single().characterOverrides["role-a"])
        assertEquals(true, repo.snapshot().single().globalEnabled)
    }

    @Test fun rejectsNonexistentFolderWithoutAnyPartialMove() = runBlocking {
        val repo = InMemoryWorldBookRepository()
        repo.initialize(context)
        repo.save(entry("first"))
        repo.save(entry("second"))
        try {
            repo.moveEntriesToFolder(setOf("first", "second"), "does-not-exist")
            fail("expected validation failure")
        } catch (_: IllegalArgumentException) {
            assertTrue(repo.snapshot().all { it.folderId == null })
        }
    }

    @Test fun deleteMultipleUndoPreservesOrderContentOverridesAndFoldersAcrossRestart() = runBlocking {
        val repo = InMemoryWorldBookRepository()
        repo.initialize(context)
        val folderId = repo.saveFolder("需要整理")
        (1..8).forEach { repo.save(entry("e$it").copy(folderId = folderId)) }
        val original = repo.snapshot()
        val removed = repo.deleteEntries(setOf("e2", "e5", "e8"))
        assertEquals(listOf("e2", "e5", "e8"), removed.entries.map { it.id })
        assertEquals(listOf("e1", "e3", "e4", "e6", "e7"), repo.snapshot().map { it.id })
        val reloadedAfterDelete = InMemoryWorldBookRepository().also { it.initialize(context) }
        assertEquals(repo.snapshot(), reloadedAfterDelete.snapshot())

        // New edits after removal must survive undo: only the removed IDs come back.
        repo.save(entry("new"))
        repo.setGlobalEnabled("e3", false)
        repo.restoreDeletedEntries(removed)
        assertEquals(listOf("e1", "e2", "e3", "e4", "e5", "e6", "e7", "e8", "new"),
            repo.snapshot().map { it.id })
        assertEquals(original.filter { it.id in setOf("e2", "e5", "e8") },
            repo.snapshot().filter { it.id in setOf("e2", "e5", "e8") })
        assertFalse(repo.snapshot().first { it.id == "e3" }.globalEnabled)
        repo.restoreDeletedEntries(removed) // Double-clicking undo must not duplicate records.
        assertEquals(9, repo.snapshot().size)
        val reloadedAfterUndo = InMemoryWorldBookRepository().also { it.initialize(context) }
        assertEquals(repo.snapshot(), reloadedAfterUndo.snapshot())
    }

    @Test fun undoAfterFolderWasRemovedRestoresRecordsWithoutInvalidFolderLink() = runBlocking {
        val repo = InMemoryWorldBookRepository()
        repo.initialize(context)
        val folderId = repo.saveFolder("可删除文件夹")
        repo.save(entry("x").copy(folderId = folderId))
        val deleted = repo.deleteEntries(setOf("x"))
        repo.deleteFolder(folderId)
        repo.restoreDeletedEntries(deleted)
        assertEquals("x", repo.snapshot().single().id)
        assertNull(repo.snapshot().single().folderId)
        assertEquals(false, repo.snapshot().single().characterOverrides["role-a"])
    }

    @Test fun searchMatchesFolderTitleAndBodyButKeepsOriginalOrder() {
        val folders = listOf(WorldBookFolder("f1", "古代历史"), WorldBookFolder("f2", "人物背景"))
        val books = listOf(
            entry("a").copy(title = "骑士", content = "忠诚", folderId = "f2"),
            entry("b").copy(title = "国王", content = "城堡", folderId = "f1"),
            entry("c").copy(title = "平民", content = "古代生活", folderId = "f2"),
            entry("d").copy(title = "工匠", content = "制造武器"),
        )
        assertEquals(listOf("b"), groupWorldBookEntries(books, folders, "历史")
            .flatMap { it.third }.map { it.id })
        assertEquals(listOf("a"), groupWorldBookEntries(books, folders, "骑士")
            .flatMap { it.third }.map { it.id })
        assertEquals(listOf("c"), groupWorldBookEntries(books, folders, "生活")
            .flatMap { it.third }.map { it.id })
        assertEquals(listOf("d"), groupWorldBookEntries(books, folders, "武器")
            .flatMap { it.third }.map { it.id })
        assertEquals(listOf("a", "c"), groupWorldBookEntries(books, folders, "人物背景")
            .flatMap { it.third }.map { it.id })
        val all = groupWorldBookEntries(books, folders, "")
        assertEquals(listOf("d", "b", "a", "c"), all.flatMap { it.third }.map { it.id })
        // Grouped viewing is presentation-only; prompt ordering stays a,b,c,d.
        assertEquals(listOf("a", "b", "c", "d"), books.map { it.id })
    }

    @Test fun worldBookPromptPositionPersistsWithoutMovingOrReenablingEntries() = runBlocking {
        val repository = InMemoryWorldBookRepository().also { it.initialize(context) }
        repository.save(entry("top"))
        repository.save(entry("middle"))
        repository.save(entry("bottom"))
        assertTrue(repository.snapshot().all { it.promptPosition == WorldBookPromptPosition.NORMAL })
        repository.setPromptPosition("top", WorldBookPromptPosition.TOP)
        repository.setPromptPosition("bottom", WorldBookPromptPosition.BOTTOM)
        assertEquals(listOf("top", "middle", "bottom"), repository.snapshot().map { it.id })
        assertEquals(listOf(WorldBookPromptPosition.TOP, WorldBookPromptPosition.NORMAL,
            WorldBookPromptPosition.BOTTOM), repository.snapshot().map { it.promptPosition })
        assertEquals(mapOf("role-a" to false, "role-b" to true),
            repository.snapshot().first().characterOverrides)
        assertTrue(repository.snapshot().all { it.globalEnabled })
        val restored = InMemoryWorldBookRepository().also { it.initialize(context) }
        assertEquals(repository.snapshot(), restored.snapshot())

        restored.setPromptPosition("top", WorldBookPromptPosition.NORMAL)
        assertEquals(WorldBookPromptPosition.NORMAL, restored.snapshot().first().promptPosition)
        assertEquals(listOf("top", "middle", "bottom"), restored.snapshot().map { it.id })
        val raw = context.getSharedPreferences("lulu_world_book", Context.MODE_PRIVATE)
            .getString("entries_v1", "")!!
        assertTrue(raw.contains("\"promptPosition\":\"BOTTOM\""))
    }

    @Test fun oldWorldBookRecordsWithoutPositionKeepTheirOriginalPlacement() {
        val oldJson = """[{"id":"legacy","title":"旧条目","content":"不应移动","globalEnabled":true,
            "characterOverrides":{"role-a":false},"createdAt":"2026-01-01T00:00:00Z"}]"""
        context.getSharedPreferences("lulu_world_book", Context.MODE_PRIVATE)
            .edit().putString("entries_v1", oldJson).commit()
        val repository = InMemoryWorldBookRepository().also { it.initialize(context) }
        val saved = repository.snapshot().single()
        assertEquals(WorldBookPromptPosition.NORMAL, saved.promptPosition)
        assertEquals("不应移动", saved.content)
        assertEquals(false, saved.characterOverrides["role-a"])
    }

    @Test fun shippedModernChatV1UpgradesOnlyExactDefaultAndPreservesSwitches() = runBlocking {
        val storage = context.getSharedPreferences("lulu_world_book", Context.MODE_PRIVATE)
        // Real shipped v1 text is an immutable upgrade fixture.
        val previousText = """
            【现代日常聊天的语用参考｜不是角色身份或新任务】
            现代人跟熟人发消息，不是在逐项答题。一个人可以了解事情的全部经过，却只说此刻最想让对方听见的那一小部分；对方有兴趣再往下聊。知道得多不等于每次要讲得多。随口聊天常先给直觉、态度或一个具体发现，不必把原因、背景、后果和建议一次说尽；对方认真询问原因、技术细节或寻求完整帮助时，则可以讲清。
            
            【情绪怎样进入语言】
            真正的感受会改变词语、语序、节奏、标点和停顿：惊讶时可能先蹦出短短一声；着急时可能重复或说话打结；得意时可能故意卖关子；害羞时可能忽然改口，认真时则干净明白。比如“等等等等！”和“等我一下，好不好”传达的是不同的情绪。重复、口头禅、拟声、半句话、突然的自我修正都可能自然出现，但出自这一次真实心情，不当成每轮必演的套路；平淡地说句“嗯，我知道了”也完全正常。
            
            【即时通讯的气泡与文字动作】
            气泡是一次次自然的说话动作，不是把同一篇说明书切成多段。可以一个短反应就独立成泡；下一泡若没有新反应、新意思或情绪转折，可以不写。连续气泡可以依次惊讶、改口、笑、补上一句，彼此有推进；也可以仅有一个气泡。分句依照本人会在哪里停、哪里换气、哪里想让对方先看到一句，不按字数或标点机械切割。
            熟人聊天偶尔用“（假装镇定）”“（把脸埋进枕头）”等括号来做文字里的玩笑或虚拟表演，也会发独立表情包。这只是文本里的表达，不证明真实身体或设备动作；不需要每次加括号。
            
            【每个人有自己的声音】
            本书描述可用的现代口语习惯，不要求所有人物说流行语、卖萌、重复、撒娇或使用同一个节奏。人物自己的性格、语言偏好、当下关系、共同经历和情绪决定要选哪一种。清冷而风趣的人可以嘴角带笑地简短回应，热烈的人可以连发几句；认真或有重要事项时可以详细。不要把知道的事实藏起来，但也不用把知道的一切主动汇报。
        """.trimIndent()
        val original = JSONObject()
            .put("id", "built-in-modern-chat-expression-v1")
            .put("title", "现代聊天与自然表达")
            .put("content", previousText)
            .put("globalEnabled", false)
            .put("promptPosition", "TOP")
            .put("characterOverrides", JSONObject().put("role-a", true))
        storage.edit().putString("entries_v1", JSONArray().put(original).toString())
            .putBoolean("human_chat_world_book_seeded_v1", true)
            .remove("human_chat_world_book_revised_v2").commit()

        val upgraded = InMemoryWorldBookRepository().also { it.initialize(context) }
        val book = upgraded.snapshot().single()
        assertNotEquals(previousText, book.content)
        assertTrue(book.content.contains("连续几个气泡不意味着每个都要显得兴奋"))
        assertFalse(book.globalEnabled)
        assertEquals(true, book.characterOverrides["role-a"])
        assertEquals(WorldBookPromptPosition.TOP, book.promptPosition)
        assertTrue(storage.getBoolean("human_chat_world_book_revised_v2", false))
        assertEquals(book, InMemoryWorldBookRepository().also { it.initialize(context) }.snapshot().single())

        // Owner edits are never silently upgraded, even on a later boot.
        upgraded.save(book.copy(content = "主人自己写的聊天指导"))
        storage.edit().remove("human_chat_world_book_revised_v2").commit()
        val kept = InMemoryWorldBookRepository().also { it.initialize(context) }.snapshot().single()
        assertEquals("主人自己写的聊天指导", kept.content)
        assertFalse(kept.globalEnabled)
    }

    @Test fun modernChatRevisionNeverResurrectsDeletedOrRetitledBook() {
        val storage = context.getSharedPreferences("lulu_world_book", Context.MODE_PRIVATE)
        storage.edit().putBoolean("human_chat_world_book_seeded_v1", true)
            .remove("human_chat_world_book_revised_v2").commit()
        assertTrue(InMemoryWorldBookRepository().also { it.initialize(context) }.snapshot().isEmpty())

        storage.edit().putString("entries_v1", JSONArray().put(JSONObject()
            .put("id", "built-in-modern-chat-expression-v1")
            .put("title", "主人重命名的聊天书")
            .put("content", "主人自己写的内容")
            .put("globalEnabled", false)).toString())
            .remove("human_chat_world_book_revised_v2").commit()
        val custom = InMemoryWorldBookRepository().also { it.initialize(context) }.snapshot().single()
        assertEquals("主人重命名的聊天书", custom.title)
        assertEquals("主人自己写的内容", custom.content)
        assertFalse(custom.globalEnabled)
    }

}
