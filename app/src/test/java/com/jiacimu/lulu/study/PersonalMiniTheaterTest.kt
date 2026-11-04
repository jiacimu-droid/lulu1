package com.jiacimu.lulu.study

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PersonalMiniTheaterTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context

    @Before fun setup() {
        LuluRepositories.initialize(context)
        MigratedDomainStores.initialize(context)
        CharacterIdentityStore.initialize(context)
        CharacterLifeStore.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        context.getSharedPreferences("lulu_mini_theaters_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun roleSnapshotAndFictionStayOutsideRealMemoryAndOtherCharactersWorks() {
        val a = MigratedDomainStores.characters.create("江渡番外测试", "独立、有审美、主动可靠")
        val b = MigratedDomainStores.characters.create("女性角色测试", "女性，机灵，语言直接")
        CharacterIdentityStore.set(b.characterId, "一名成年女性店员，有自己的生活")
        SharedExperienceTimeline.record("fiction-private", a.characterId, "私聊", "用户",
            "不应交给番外作者的真实私聊秘密", Instant.now(), triggerExtraction = false,
            source = "message", evidenceKind = EventEvidenceKind.UserStatement)
        CharacterInnerLifeStore.recordInnerVoice(a.characterId, "fiction-private", "不应交给番外作者的实时心声")
        val beforeTimeline = SharedExperienceTimeline.all(a.characterId)
        val beforeMind = CharacterInnerLifeStore.snapshot(a.characterId).toString()
        val beforeLife = CharacterLifeStore.state(a.characterId).toString()
        val beforePersona = MigratedDomainStores.characters.get(a.characterId).persona
        val library = MiniTheaterLibrary(context, emptyList())
        val idA = library.addPersonal(PersonalMiniTheater.capture(a.characterId),
            PersonalMiniTheater.ideas.first { it.id == "swap" }, PersonalMiniTheater.variations.first { it.id == "tender" })
        val idB = library.addPersonal(PersonalMiniTheater.capture(b.characterId),
            PersonalMiniTheater.ideas.first { it.id == "thumb" }, PersonalMiniTheater.variations.first())
        val bound = library.items.value.first { it.id == idA }
        val request = PersonalMiniTheater.request(bound)
        assertTrue(request.facts.contains("独立、有审美"))
        assertTrue(request.facts.contains("交换了身体"))
        assertTrue(request.facts.contains("本篇暂时放大"))
        assertFalse(request.facts.contains("真实私聊秘密"))
        assertFalse(request.facts.contains("实时心声"))
        val femaleRequest = PersonalMiniTheater.request(library.items.value.first { it.id == idB })
        assertTrue(femaleRequest.facts.contains("成年女性店员"))
        assertFalse(femaleRequest.facts.contains("独立、有审美"))
        val token = library.begin(idA, "", false)
        assertTrue(library.finish(idA, token, "complete", text = "这是一篇明确的虚构身体互换作品。"))
        val reloaded = MiniTheaterLibrary(context, emptyList())
        assertEquals(a.characterId, reloaded.items.value.first { it.id == idA }.characterId)
        assertEquals(bound.characterContext, reloaded.items.value.first { it.id == idA }.characterContext)
        assertEquals(b.characterId, reloaded.items.value.first { it.id == idB }.characterId)
        assertEquals(beforeTimeline, SharedExperienceTimeline.all(a.characterId))
        assertEquals(beforeMind, CharacterInnerLifeStore.snapshot(a.characterId).toString())
        assertEquals(beforeLife, CharacterLifeStore.state(a.characterId).toString())
        assertEquals(beforePersona, MigratedDomainStores.characters.get(a.characterId).persona)
    }

    @Test fun failedRewriteRetainsEditionAndRejectsStaleTokenWithoutReusingDraft() {
        val library = MiniTheaterLibrary(context, emptyList())
        val cast = PersonalMiniTheater.Cast("test-role", "角色", "原本人格")
        val id = library.addPersonal(cast, PersonalMiniTheater.ideas.first(), PersonalMiniTheater.variations.first())
        val first = library.begin(id, "", false)
        library.finish(id, first, "complete", text = "已经完成的旧版番外")
        val rewrite = library.begin(id, "这次换一种发展", true)
        assertFalse(library.finish(id, first, "complete", text = "旧请求迟到"))
        val rewriting = library.items.value.first { it.id == id }
        assertFalse(PersonalMiniTheater.request(rewriting).facts.contains("已经完成的旧版番外"))
        assertTrue(library.finish(id, rewrite, "failed", error = "模型请求失败"))
        assertEquals("已经完成的旧版番外", library.items.value.first { it.id == id }.text)
        assertTrue(library.items.value.first { it.id == id }.rewriting)
        val retry = library.begin(id, "", false)
        library.finish(id, retry, "complete", text = "完整的新版本")
        assertEquals("完整的新版本", library.items.value.first { it.id == id }.text)
        assertFalse(library.items.value.first { it.id == id }.rewriting)
    }

    @Test fun legacyUnboundWorksRemainReadableAndNewTemplatesDoNotNeedAPlanner() {
        val raw = JSONArray().put(JSONObject().put("id", "old").put("title", "旧小剧场")
            .put("prompt", "原要求").put("text", "已保存的正文").put("status", "complete"))
        context.getSharedPreferences("lulu_mini_theaters_v1", Context.MODE_PRIVATE).edit()
            .putString("items", raw.toString()).commit()
        val library = MiniTheaterLibrary(context, emptyList())
        val old = library.items.value.single()
        assertEquals("", old.characterId)
        assertEquals("已保存的正文", old.text)
        val seen = (0..PersonalMiniTheater.ideas.size).flatMap {
            PersonalMiniTheater.suggestions(it).map { idea -> idea.id }
        }.toSet()
        assertEquals(PersonalMiniTheater.ideas.map { it.id }.toSet(), seen)
        assertEquals(3, PersonalMiniTheater.suggestions(-1).size)
        assertEquals("原要求", library.items.value.single().prompt)
    }
}
