package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.core.WorldBookEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
        context.getSharedPreferences("lulu_world_book", Context.MODE_PRIVATE).edit().clear().commit()
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

}
