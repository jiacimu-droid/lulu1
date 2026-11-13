package com.jiacimu.lulu.data

import com.jiacimu.lulu.core.WorldBookEntry
import com.jiacimu.lulu.core.WorldBookPromptPosition
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
    @Test fun nativeJsonHonorsPlacementButForeignImportNeverGuessesItsMeaning() {
        val native = WorldBookJsonImporter.parse(
            """[{"title":"顶部","content":"A","globalEnabled":true,"promptPosition":"TOP"},
                 {"title":"底部","content":"B","globalEnabled":true,"promptPosition":"BOTTOM"},
                 {"title":"旧项","content":"C","globalEnabled":true},
                 {"title":"未知","content":"D","globalEnabled":true,"promptPosition":"invalid"}]""",
            "person",
        )
        assertEquals(listOf(WorldBookPromptPosition.TOP, WorldBookPromptPosition.BOTTOM,
            WorldBookPromptPosition.NORMAL, WorldBookPromptPosition.NORMAL),
            native.entries.map { it.promptPosition })
        val tavern = WorldBookJsonImporter.parse(
            """{"entries":[{"name":"异构条目","content":"E","constant":true,
                 "position":"bottom","promptPosition":"BOTTOM"}]}""", "person")
        assertEquals(WorldBookPromptPosition.NORMAL, tavern.entries.single().promptPosition)
    }

    @Test fun bundledMeowssiahSummonIsVerbatimAndOptIn() {
        val context = RuntimeEnvironment.getApplication()
        val raw = context.assets.open("worldbook/meowssiah_verbatim.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val source = org.json.JSONObject(raw)
        val original = source.getJSONArray("books").getJSONObject(0).getString("content")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(original.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        // SHA-256 of prompts[9].content in the uploaded Meowssiah-2.1.0.json.
        assertEquals("3fd6146e7b9376342553799a39747c487484823fb1a79b00e823b67831be973c", digest)
        val parsed = WorldBookJsonImporter.parse(raw, "selected-character")
        assertEquals(1, parsed.entries.size)
        assertEquals("（不要关）召唤术", parsed.entries.single().title)
        assertEquals(original, parsed.entries.single().content)
        assertFalse(parsed.entries.single().globalEnabled)
        assertTrue(parsed.entries.single().characterOverrides.isEmpty())
    }

    @Test fun importAndPersistKeepLiteralWhitespace() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val original = "\n  <{{user}}> 原文不应裁剪 \n"
        val preview = WorldBookJsonImporter.parse(
            org.json.JSONArray().put(org.json.JSONObject()
                .put("title", "空白保留")
                .put("content", original)
                .put("globalEnabled", false)).toString(),
            "character-a",
        )
        assertEquals(original, preview.entries.single().content)
        val repository = InMemoryWorldBookRepository()
        repository.initialize(context)
        val testId = "verbatim-" + UUID.randomUUID().toString()
        repository.save(preview.entries.single().copy(id = testId))
        val reloaded = InMemoryWorldBookRepository()
        reloaded.initialize(context)
        assertEquals(original, reloaded.snapshot().first { it.id == testId }.content)
    }

    @Test fun completeMeowssiahPresetPreservesSourceAndImportsEveryNonEmptyPrompt() {
        val context = RuntimeEnvironment.getApplication()
        val raw = context.assets.open("worldbook/Meowssiah-2.1.0.original.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertEquals("4b6f0737d854e8fb11c18e84b360e9bccb864367760f7f84dbd2ddc4ee67b59d", digest)

        val source = org.json.JSONObject(raw)
        assertEquals(56, source.getJSONArray("prompts").length())
        assertNotNull(source.getJSONObject("extensions").getJSONObject("SPreset")
            .getJSONObject("FinalRequestProcessing").getString("script"))
        val preview = WorldBookJsonImporter.parse(raw, "character-meow")
        assertTrue(preview.originalPresetFormat)
        assertFalse(preview.foreignFormat)
        assertEquals(48, preview.entries.size)
        // Eight original host marker slots have no body, and remain in the full source asset.
        assertEquals(8, preview.skipped)
        assertEquals(29, preview.entries.count {
            it.characterOverrides["character-meow"] == true
        })
        assertTrue(preview.entries.all { !it.globalEnabled })
        assertTrue(preview.entries.all { it.promptPosition == WorldBookPromptPosition.NORMAL })

        val prompts = source.getJSONArray("prompts")
        val sourceById = LinkedHashMap<String, org.json.JSONObject>()
        for (i in 0 until prompts.length()) {
            val prompt = prompts.getJSONObject(i)
            val id = prompt.optString("identifier").ifBlank { "prompt-$i" }
            sourceById[id] = prompt
        }
        val mainOrder = source.getJSONArray("prompt_order").getJSONObject(1).getJSONArray("order")
        val orderedIds = LinkedHashSet<String>()
        for (i in 0 until mainOrder.length()) {
            orderedIds.add(mainOrder.getJSONObject(i).getString("identifier"))
        }
        orderedIds.addAll(sourceById.keys)
        val expected = orderedIds.mapNotNull { id ->
            val item = sourceById[id] ?: return@mapNotNull null
            val body = item.opt("content") as? String ?: ""
            if (body.isBlank()) null else item.optString("name").ifBlank { id } to body
        }
        assertEquals(expected, preview.entries.map { it.title to it.content })
        assertEquals(sourceById.getValue("enhanceDefinitions").getString("content"),
            preview.entries.first { it.title == "（不要关）召唤术" }.content)
    }

}
