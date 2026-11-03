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
        assertTrue(seeded.content.contains("背景知识，不是人物设定"))
        assertTrue(storage.getBoolean("human_chat_world_book_revised_v3", false))
        assertTrue(storage.getBoolean("human_chat_world_book_revised_v4", false))
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
            .remove("human_chat_world_book_revised_v2")
            .remove("human_chat_world_book_revised_v3")
            .remove("human_chat_world_book_revised_v4").commit()

        val upgraded = InMemoryWorldBookRepository().also { it.initialize(context) }
        val book = upgraded.snapshot().single()
        assertNotEquals(previousText, book.content)
        assertTrue(book.content.contains("接词、猜谜、共同游戏可能只需一个词"))
        assertFalse(book.globalEnabled)
        assertEquals(true, book.characterOverrides["role-a"])
        assertEquals(WorldBookPromptPosition.TOP, book.promptPosition)
        assertTrue(storage.getBoolean("human_chat_world_book_revised_v2", false))
        assertTrue(storage.getBoolean("human_chat_world_book_revised_v3", false))
        assertTrue(storage.getBoolean("human_chat_world_book_revised_v4", false))
        assertEquals(book, InMemoryWorldBookRepository().also { it.initialize(context) }.snapshot().single())

        // Owner edits are never silently upgraded, even on a later boot.
        upgraded.save(book.copy(content = "主人自己写的聊天指导"))
        storage.edit().remove("human_chat_world_book_revised_v2")
            .remove("human_chat_world_book_revised_v4").commit()
        val kept = InMemoryWorldBookRepository().also { it.initialize(context) }.snapshot().single()
        assertEquals("主人自己写的聊天指导", kept.content)
        assertFalse(kept.globalEnabled)
    }

    @Test fun modernChatRevisionNeverResurrectsDeletedOrRetitledBook() {
        val storage = context.getSharedPreferences("lulu_world_book", Context.MODE_PRIVATE)
        storage.edit().putBoolean("human_chat_world_book_seeded_v1", true)
            .remove("human_chat_world_book_revised_v2")
            .remove("human_chat_world_book_revised_v4").commit()
        assertTrue(InMemoryWorldBookRepository().also { it.initialize(context) }.snapshot().isEmpty())

        storage.edit().putString("entries_v1", JSONArray().put(JSONObject()
            .put("id", "built-in-modern-chat-expression-v1")
            .put("title", "主人重命名的聊天书")
            .put("content", "主人自己写的内容")
            .put("globalEnabled", false)).toString())
            .remove("human_chat_world_book_revised_v2")
            .remove("human_chat_world_book_revised_v4").commit()
        val custom = InMemoryWorldBookRepository().also { it.initialize(context) }.snapshot().single()
        assertEquals("主人重命名的聊天书", custom.title)
        assertEquals("主人自己写的内容", custom.content)
        assertFalse(custom.globalEnabled)
    }


    @Test fun shippedModernChatV3UpgradesToJointConversationAndRespectsOwnerChanges() = runBlocking {
        val storage = context.getSharedPreferences("lulu_world_book", Context.MODE_PRIVATE)
        val previousText = """
            【现代即时通讯的社会观察｜文化背景，不是人物性格】
            现代人通过即时消息交流时，既会说完整句子，也会发短词、表情、停顿、图片；对同一件事可以冷静、兴奋、含蓄或幽默。社会交流方式没有统一的人格或口吻，具体说法取决于说话者本人、对象、场合及所处关系。

            【轮流交流与共同活动】
            日常交谈通常由参与者共同推进。一个人有时只回答眼前的问题，有时补充细节，有时把话轮交给对方。轮流猜词、接龙或其他小游戏中，一次参与可能只需要一个词、一个选择或者一句话；规则解释、纠正和策略讨论则是另外的互动。交接话轮并不意味着冷淡，也不是所有对话只能短答。

            【语气、标点与含蓄】
            句号、问号、感叹号、省略号、断句和不写句末标点都见于当代聊天；标点和语气通常与当时表达的意思及情绪程度相呼应。真实惊喜与平常打趣有时用不同力度，普通推测、低声吐槽和认真倾听也属于自然交流。说话者常会留下一些尚未展开的想法，让下一轮的真实回应决定是否继续。

            【资料范围】
            本书只提供社会交流习惯的观察，帮助角色理解别人如何说话、游戏怎样轮流推进；它不是角色专属的说话示例、个性设定、情绪命令或篇幅配额。具体角色的价值、性格不同侧面、关系与个人语言习惯仍来自其本人设定及真实经历。
        """.trimIndent()
        val old = JSONObject()
            .put("id", "built-in-modern-chat-expression-v1")
            .put("title", "现代聊天与自然表达")
            .put("content", previousText)
            .put("globalEnabled", false)
            .put("promptPosition", "TOP")
            .put("characterOverrides", JSONObject().put("role-a", true).put("role-b", false))
        storage.edit().putString("entries_v1", JSONArray().put(old).toString())
            .putBoolean("human_chat_world_book_seeded_v1", true)
            .putBoolean("human_chat_world_book_revised_v3", true)
            .remove("human_chat_world_book_revised_v4").commit()
        val repo = InMemoryWorldBookRepository().also { it.initialize(context) }
        val updated = repo.snapshot().single()
        assertNotEquals(previousText, updated.content)
        assertTrue(updated.content.contains("下一轮若出现误会、兴趣或新信息"))
        assertFalse(updated.globalEnabled)
        assertEquals(WorldBookPromptPosition.TOP, updated.promptPosition)
        assertEquals(true, updated.characterOverrides["role-a"])
        assertEquals(false, updated.characterOverrides["role-b"])
        assertTrue(storage.getBoolean("human_chat_world_book_revised_v4", false))

        // An owner-edited body does not become replaceable just because its
        // title and ID still match the bundled copy.
        repo.save(updated.copy(content = "主人自己调整的社会习惯介绍"))
        storage.edit().remove("human_chat_world_book_revised_v4").commit()
        val saved = InMemoryWorldBookRepository().also { it.initialize(context) }.snapshot().single()
        assertEquals("主人自己调整的社会习惯介绍", saved.content)
        assertFalse(saved.globalEnabled)
    }

    @Test fun shippedModernChatV2UpgradesToCultureReferenceWithoutOverridingAnOwnersScope() {
        val storage = context.getSharedPreferences("lulu_world_book", Context.MODE_PRIVATE)
        val previousText = """
            【现代聊天与自然表达｜供选择的语用参考】
            熟悉的人聊天通常是在延续眼前的一次互动，而不是完整陈述所有联想。先抓住真正想回应的那一点；一句话说到了就可以停，话题有意思时再自然展开。被认真问到细节、解释或求助时，仍把必要内容说清楚。

            【情绪与语气来自这一次谈话】
            同一个人可以平静、好笑、迟疑、激动，也可以认真。语气来自正在表达的意思和当下感受，不靠持续提高音量证明亲近或活泼。比如普通判断可以说“可能就是味道不对。”，好奇时可以说“你怎么想到这个的？”，真被逗笑时可以说“哈哈，原来你说的是这个”。这些是不同情境的例子，不是要逐句照搬的台词。感叹号适合真有惊讶、喊话或强烈情绪的瞬间；平常玩笑、推测、解释、轻轻吐槽也完全可以用句号、问号、省略或无句末标点。连续几个气泡不意味着每个都要显得兴奋。

            【即时通讯里的气泡】
            气泡是一次次真实说话动作，不是把文章切小。可以先给短反应，再补一句新的观察或玩笑；没有新意思就让对方接话。气泡边界跟随真实停顿和话意变化，不按固定字数、句数或标点切。括号动作、表情包、颜文字和流行语也只是个人可选的表达手段，不是鲜活感的必备装饰。

            【每个人保留自己的声音】
            这是关于现代聊天习惯的参考资料，不是统一人物性格。具体选择由角色自己的设定、现实关系、情绪、历史互动及当前媒介决定。沉静的人可以轻轻一笑，机灵的人可以随口打趣，热情的人也可以安静地认真听。自然感来自具体的理解、反应和变化，不来自重复同一套句式。
        """.trimIndent()
        val custom = JSONObject()
            .put("id", "built-in-modern-chat-expression-v1")
            .put("title", "现代聊天与自然表达")
            .put("content", previousText)
            .put("globalEnabled", false)
            .put("promptPosition", "BOTTOM")
            .put("characterOverrides", JSONObject().put("jiangdu", true))
        storage.edit().putString("entries_v1", JSONArray().put(custom).toString())
            .putBoolean("human_chat_world_book_seeded_v1", true)
            .putBoolean("human_chat_world_book_revised_v2", true)
            .remove("human_chat_world_book_revised_v3")
            .remove("human_chat_world_book_revised_v4").commit()
        val book = InMemoryWorldBookRepository().also { it.initialize(context) }.snapshot().single()
        assertNotEquals(previousText, book.content)
        assertTrue(book.content.contains("参与者共同完成的互动"))
        assertFalse(book.globalEnabled)
        assertEquals(true, book.characterOverrides["jiangdu"])
        assertEquals(WorldBookPromptPosition.BOTTOM, book.promptPosition)
        assertTrue(storage.getBoolean("human_chat_world_book_revised_v3", false))
        assertTrue(storage.getBoolean("human_chat_world_book_revised_v4", false))
    }

}
