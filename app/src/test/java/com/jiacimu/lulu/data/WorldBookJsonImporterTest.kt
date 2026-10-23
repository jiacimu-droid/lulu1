package com.jiacimu.lulu.data

import com.jiacimu.lulu.core.WorldBookEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class WorldBookJsonImporterTest {

    @Test fun luluJsonPreservesGlobalAndPerCharacterOverrides() {
        val preview = WorldBookJsonImporter.parse(
            """[{"title":"城市设定","content":"海边的城市","globalEnabled":true,"characterOverrides":{"role-a":false,"role-b":true}}]""",
            "role-a",
        )
        assertEquals(1, preview.entries.size)
        assertEquals(0, preview.skipped)
        assertFalse(preview.foreignFormat)
        val entry = preview.entries.single()
        assertTrue(entry.globalEnabled)
        assertEquals(false, entry.characterOverrides["role-a"])
        assertEquals(true, entry.characterOverrides["role-b"])
    }

    @Test fun tavernEntriesKeepKeywordsAsTextButDoNotSilentlyEnableConditionalLore() {
        val preview = WorldBookJsonImporter.parse(
            """{"entries":{"0":{"comment":"学院","content":"位于北方","key":["学院"],"constant":false},
               "1":{"comment":"世界法则","content":"没有传送术","constant":true},
               "2":{"comment":"空白条目","content":""}}}""",
            "role-a",
        )
        assertEquals(2, preview.entries.size)
        assertEquals(1, preview.skipped)
        assertEquals(1, preview.conditionalEntries)
        assertTrue(preview.foreignFormat)
        val conditional = preview.entries[0]
        assertEquals("学院", conditional.title)
        assertTrue(conditional.content.contains("原世界书关键词：学院"))
        assertFalse(conditional.globalEnabled)
        assertTrue(conditional.characterOverrides.isEmpty())
        val constant = preview.entries[1]
        assertFalse(constant.globalEnabled)
        assertEquals(true, constant.characterOverrides["role-a"])
        assertNull(constant.characterOverrides["role-b"])
    }

    @Test fun nestedCharacterV2WorldBookCanBeImported() {
        val preview = WorldBookJsonImporter.parse(
            """{"data":{"character_book":{"entries":[
              {"name":"常驻人设","content":"历史沿革","constant":true,"enabled":true},
              {"name":"停用条目","content":"暂时不用","constant":true,"disable":true}
            ]}}}""", "role-b",
        )
        assertEquals(2, preview.entries.size)
        assertEquals(true, preview.entries[0].characterOverrides["role-b"])
        assertTrue(preview.entries[1].characterOverrides.isEmpty())
        assertFalse(preview.entries[0].globalEnabled)
    }

    @Test fun unknownJsonOrAllEmptyEntriesAreRejectedWithoutImportingAnything() {
        assertThrows(IllegalArgumentException::class.java) {
            WorldBookJsonImporter.parse("""{"random":"value"}""", "role")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorldBookJsonImporter.parse("""[{"title":"A","content":""}]""", "role")
        }
    }

    @Test fun atomicImportKeepsExistingRecordsAndSkipsReimports() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repository = InMemoryWorldBookRepository()
        repository.initialize(context)
        val unique = UUID.randomUUID().toString()
        val existing = WorldBookEntry(
            id = "existing-$unique", title = "已有-$unique", content = "不能被覆盖",
            globalEnabled = true, characterOverrides = mapOf("role-z" to false),
        )
        repository.save(existing)
        val original = repository.snapshot().first { it.id == existing.id }
        val candidate = WorldBookEntry(
            id = existing.id, title = "导入-$unique", content = "新条目",
            globalEnabled = false, characterOverrides = mapOf("role-a" to true),
        )
        val first = repository.importEntries(listOf(candidate, candidate))
        assertEquals(1, first.imported)
        assertEquals(1, first.duplicates)
        val second = repository.importEntries(listOf(candidate))
        assertEquals(0, second.imported)
        assertEquals(1, second.duplicates)
        assertEquals(original, repository.snapshot().first { it.id == existing.id })
        val imported = repository.snapshot().filter { it.title == candidate.title }
        assertEquals(1, imported.size)
        assertNotEquals(existing.id, imported.single().id)
        assertEquals(true, imported.single().characterOverrides["role-a"])
    }
}
