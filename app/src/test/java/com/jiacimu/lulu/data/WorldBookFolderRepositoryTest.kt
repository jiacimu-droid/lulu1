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
}
