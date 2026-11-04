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

    @Test fun readerSeparatesQuotedSpeechFromNarrationWithoutChangingSavedStory() {
        val story = """
            《耳尖藏不住》
            
            空气里有微暖的光。

            “江渡？”你走近两步。

            少年微微一僵，帽子掉了下来。

            “你来了。”

            【小剧场已完】
        """.trimIndent()
        val parts = PersonalTheaterTypography.sections(story)
        assertEquals(listOf(
            PersonalTheaterTypography.Kind.Title,
            PersonalTheaterTypography.Kind.Narration,
            PersonalTheaterTypography.Kind.Dialogue,
            PersonalTheaterTypography.Kind.Narration,
            PersonalTheaterTypography.Kind.Narration,
            PersonalTheaterTypography.Kind.Dialogue,
            PersonalTheaterTypography.Kind.End,
        ), parts.map { it.kind })
        assertEquals("“江渡？”", parts[2].text)
        assertEquals("你走近两步。", parts[3].text)
        assertEquals("《耳尖藏不住》", parts[0].text)
        assertEquals("少年微微一僵，帽子掉了下来。", parts[4].text)
        assertEquals("“你来了。”", parts[5].text)
        assertEquals("【小剧场已完】", parts[6].text)

        val library = MiniTheaterLibrary(context, emptyList())
        val id = library.addPersonal(PersonalMiniTheater.Cast("reader-role", "角色", "独立人物"),
            PersonalMiniTheater.ideas.first())
        val token = library.begin(id, "", rewrite = false)
        assertTrue(library.finish(id, token, "complete", text = story))
        // Rendering a story never edits the canonical stored text or its evidence.
        PersonalTheaterTypography.sections(library.items.value.single().text)
        assertEquals(story, library.items.value.single().text)
    }

    @Test fun readerSupportsSingleLineQuotePlainParagraphsAndOldUnstructuredText() {
        val parts = PersonalTheaterTypography.sections(
            "“只说一句。”\n旁白没有空行。\n\n我没有加首行缩进。")
        assertEquals(listOf(
            PersonalTheaterTypography.Kind.Dialogue,
            PersonalTheaterTypography.Kind.Narration,
            PersonalTheaterTypography.Kind.Narration,
        ), parts.map { it.kind })
        assertEquals("只说一句。", parts.first().text.removeSurrounding("“", "”"))
        assertTrue(PersonalTheaterTypography.sections(" \n \n ").isEmpty())
        assertEquals(listOf(PersonalTheaterTypography.Kind.Narration),
            PersonalTheaterTypography.sections("旧版保存的普通正文").map { it.kind })
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
    @Test fun aSingleSystemOrCustomPremiseNeedsNoPersonalitySelection() {
        val library = MiniTheaterLibrary(context, emptyList())
        val cast = PersonalMiniTheater.Cast("single-premise-role", "角色", "保留本人的判断方式")
        val guidance = "身体互换之后，双方要一起完成一件事。\n结尾让角色坦白一个小小的心愿。"
        val custom = PersonalMiniTheater.customIdea(guidance)
        val customId = library.addPersonal(cast, custom)
        val work = library.items.value.first { it.id == customId }
        assertEquals(guidance, work.prompt)
        assertEquals("", work.variationContext)
        val request = PersonalMiniTheater.request(work)
        assertTrue(request.facts.contains(guidance))
        assertFalse(request.facts.contains("旧版作品保留的补充设定"))
        val systemId = library.addPersonal(cast,
            PersonalMiniTheater.ideas.first { it.id == "tender-spell" })
        val system = library.items.value.first { it.id == systemId }
        assertTrue(system.prompt.contains("温柔体贴"))
        assertEquals("", system.variationContext)
        assertThrows(IllegalArgumentException::class.java) { PersonalMiniTheater.customIdea("  ") }
    }


    @Test fun readerKeepsParagraphsAndSeparatesActualSpeechWithoutLosingFiction() {
        val original = """
            《偷偷冒出来的耳朵》

            你走过去。
            他把帽檐压低，耳朵却抖了抖。

            “江渡？”
            他耳尖一下竖起来。

            “你来啦。”他笑着偏过头。

            你说“别装”，他一下没绷住。

            【小剧场已完】
        """.trimIndent()
        val sections = PersonalTheaterTypography.sections(original)
        assertEquals(listOf(
            PersonalTheaterTypography.Kind.Title,
            PersonalTheaterTypography.Kind.Narration,
            PersonalTheaterTypography.Kind.Dialogue,
            PersonalTheaterTypography.Kind.Narration,
            PersonalTheaterTypography.Kind.Dialogue,
            PersonalTheaterTypography.Kind.Narration,
            PersonalTheaterTypography.Kind.Narration,
            PersonalTheaterTypography.Kind.End,
        ), sections.map { it.kind })
        assertEquals("你走过去。\n他把帽檐压低，耳朵却抖了抖。", sections[1].text)
        assertEquals("“江渡？”", sections[2].text)
        assertEquals("他耳尖一下竖起来。", sections[3].text)
        assertEquals("“你来啦。”", sections[4].text)
        assertEquals("他笑着偏过头。", sections[5].text)
        // The quoted line and its gesture belong to the same paragraph,
        // so the reader should use a small gap rather than full paragraph spacing.
        assertEquals(sections[2].paragraphIndex, sections[3].paragraphIndex)
        assertNotEquals(sections[3].paragraphIndex, sections[4].paragraphIndex)
        assertEquals(sections[4].paragraphIndex, sections[5].paragraphIndex)
        assertEquals("你说“别装”，他一下没绷住。", sections[6].text)
        assertEquals("偷偷冒出来的耳朵", PersonalTheaterTypography.leadingTitle(sections, "备用标题"))
        assertEquals("备用标题", PersonalTheaterTypography.leadingTitle(
            PersonalTheaterTypography.sections("先有旁白\n\n《中途的小标题》"),
            "备用标题"))
        // Reading is only a projection. This test never rewrites an original work.
        assertEquals(original, original.toString())
    }

    @Test fun readerSupportsLegacyUntitledAndEmptyWorks() {
        assertTrue(PersonalTheaterTypography.sections(" \n  ").isEmpty())
        val untitled = PersonalTheaterTypography.sections("天亮了。\r\n他转头望过来。")
        assertEquals(1, untitled.size)
        assertEquals(PersonalTheaterTypography.Kind.Narration, untitled.single().kind)
        assertEquals("天亮了。\n他转头望过来。", untitled.single().text)
        assertEquals("旧作品", PersonalTheaterTypography.leadingTitle(untitled, "旧作品"))
        val standalone = PersonalTheaterTypography.sections("「我没事。」")
        assertEquals(PersonalTheaterTypography.Kind.Dialogue, standalone.single().kind)
    }

    @Test fun originalWorldAndAlternateWorldUseDifferentIdentityBoundariesAndPersist() {
        val character = MigratedDomainStores.characters.create(
            "可穿越角色", "观察敏锐、嘴上冷静、行动有点冒险精神")
        CharacterIdentityStore.set(character.characterId, "住在露露机数字世界，职业是虚拟咖啡师")
        CharacterLifeStore.setProfile(character.characterId, "care",
            "亲密相处时可以自信地主导，也愿意把主导交给亲近的人；原世界称呼主人。")
        val original = PersonalMiniTheater.capture(character.characterId)
        val alternate = PersonalMiniTheater.capture(character.characterId, "alternate")
        assertTrue(original.material.contains("虚拟咖啡师"))
        assertTrue(alternate.material.contains("观察敏锐"))
        assertTrue(alternate.material.contains("自信地主导"))
        assertTrue(alternate.material.contains("身份、世界观或既定关系"))
        assertFalse(alternate.material.contains("虚拟咖啡师"))
        assertFalse(alternate.material.contains("原设身份与背景"))
        val library = MiniTheaterLibrary(context, emptyList())
        val originalId = library.addPersonal(original, PersonalMiniTheater.ideas.first())
        val alternateId = library.addPersonal(alternate, PersonalMiniTheater.customIdea(
            "这次双方在陌生城市第一次见面。"))
        val originalWork = library.items.value.first { it.id == originalId }
        val alternateWork = library.items.value.first { it.id == alternateId }
        assertEquals("original", originalWork.worldMode)
        assertEquals("alternate", alternateWork.worldMode)
        assertTrue(PersonalMiniTheater.request(originalWork).instructions.contains("原世界番外"))
        assertTrue(PersonalMiniTheater.request(alternateWork).instructions.contains("彻底架空"))
        assertFalse(PersonalMiniTheater.request(alternateWork).facts.contains("虚拟咖啡师"))
        val restored = MiniTheaterLibrary(context, emptyList())
        assertEquals("alternate", restored.items.value.first { it.id == alternateId }.worldMode)
        assertEquals("original", restored.items.value.first { it.id == originalId }.worldMode)
    }

    @Test fun animalTemperamentMetaphorsStayCharacterTraitsNotFixedStoryLabels() {
        val fictionalPersona = "有时像小狐狸般机灵地主动接近，也可以像小狗一样自愿柔软、信任别人。"
        val cast = PersonalMiniTheater.Cast("metaphor-role", "角色", fictionalPersona)
        val library = MiniTheaterLibrary(context, emptyList())
        val originalId = library.addPersonal(cast, PersonalMiniTheater.customIdea("两人一起处理一场误会。"))
        val original = PersonalMiniTheater.request(library.items.value.first { it.id == originalId })
        assertTrue(original.facts.contains(fictionalPersona))
        assertTrue(original.instructions.contains("行为侧面"))
        assertTrue(original.instructions.contains("不需要解释他此刻切换了哪一种人格"))
        assertTrue(original.instructions.contains("不要把人格比喻反复写成旁白的"))
        val alternateId = library.addPersonal(
            cast.copy(worldMode = "alternate"), PersonalMiniTheater.customIdea("陌生城市里第一次合作。"))
        val alternate = PersonalMiniTheater.request(library.items.value.first { it.id == alternateId })
        assertTrue(alternate.instructions.contains("彻底架空"))
        assertTrue(alternate.instructions.contains("这些是可能的行为侧面"))
        val earsId = library.addPersonal(cast, PersonalMiniTheater.ideas.first { it.id == "cat" })
        val ears = PersonalMiniTheater.request(library.items.value.first { it.id == earsId })
        assertTrue(ears.facts.contains("耳朵和尾巴"))
        assertTrue(ears.instructions.contains("如果本篇明确设有兽耳、尾巴"))
        assertFalse(ears.instructions.contains("所有动物元素都不许出现"))
    }

    @Test fun selectedWorldBookRulesAreIsolatedPersistedAndFrozenPerWork() {
        val library = MiniTheaterLibrary(context, emptyList())
        val cast = PersonalMiniTheater.Cast("story-role", "角色", "稳重但爱开玩笑")
        val books = listOf(
            WorldBookEntry("sea", "水下城市", "海底的所有灯塔每天只在黎明鸣响。", false, emptyMap()),
            WorldBookEntry("forest", "月亮森林", "这里的每棵树都能记录声音。", true, emptyMap()),
        )
        val id = library.addPersonal(cast, PersonalMiniTheater.ideas.first(),
            selectedWorldBookIds = setOf("sea"), availableWorldBooks = books)
        val noBookId = library.addPersonal(cast.copy(worldMode = "alternate"),
            PersonalMiniTheater.customIdea("平行世界里第一次相遇"))
        val work = library.items.value.first { it.id == id }
        assertEquals(listOf("sea"), work.worldBookIds)
        assertTrue(work.worldBookContext.contains("海底的所有灯塔"))
        assertFalse(work.worldBookContext.contains("每棵树"))
        val request = PersonalMiniTheater.request(work)
        assertTrue(request.facts.contains("水下城市"))
        assertFalse(request.facts.contains("月亮森林"))
        assertTrue(request.instructions.contains("只根据本篇明确选中的条目"))
        assertEquals("", library.items.value.first { it.id == noBookId }.worldBookContext)
        val reopened = MiniTheaterLibrary(context, emptyList())
        val frozen = reopened.items.value.first { it.id == id }
        assertEquals(work.worldBookContext, frozen.worldBookContext)
        // Rewrites use the original selected snapshot even if the source entry changes.
        val token = reopened.begin(id, "", rewrite = false)
        assertTrue(reopened.finish(id, token, "draft", text = "已有开篇"))
        val rewrite = reopened.begin(id, "", rewrite = true)
        val duringRewrite = reopened.items.value.first { it.id == id }
        assertEquals(work.worldBookContext, duringRewrite.worldBookContext)
        assertTrue(PersonalMiniTheater.request(duringRewrite).facts.contains("海底的所有灯塔"))
        assertTrue(reopened.finish(id, rewrite, "complete", text = "新版完结"))
        assertThrows(IllegalArgumentException::class.java) {
            reopened.addPersonal(cast, PersonalMiniTheater.ideas.first(),
                selectedWorldBookIds = setOf("deleted"), availableWorldBooks = books)
        }
    }

    @Test fun creativeIdeaBatchesAreSavedReusedAndFilteredAgainstExistingPremises() {
        val library = MiniTheaterLibrary(context, emptyList())
        val generated = PersonalMiniTheater.parseGeneratedIdeas(
            """{"ideas":[
                {"title":"海上收音机","premise":"两人在风暴夜被困不同船舱，只能通过短波广播协作解开谜题、求得救援。"},
                {"title":"反向直播","premise":"一场游戏直播中双方身份被全网误认，必须在不暴露真相的情况下共同完成公开挑战。"}
            ]}""")
        assertEquals(2, generated.size)
        assertEquals(2, library.addCreativeIdeas(generated))
        assertEquals(0, library.addCreativeIdeas(generated))
        val reopened = MiniTheaterLibrary(context, emptyList())
        assertEquals(2, reopened.creativeIdeas.value.size)
        val facts = PersonalMiniTheater.inspirationRequest(
            PersonalMiniTheater.ideas + reopened.creativeIdeas.value, reopened.items.value).facts
        assertTrue(facts.contains("反向直播"))
        assertTrue(facts.contains("醒来竟然交换了身体"))
        assertThrows(IllegalArgumentException::class.java) {
            PersonalMiniTheater.parseGeneratedIdeas("not a JSON result")
        }
    }

}
