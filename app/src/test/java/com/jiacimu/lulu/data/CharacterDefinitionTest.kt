package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class CharacterDefinitionTest {
    @Test fun changingApprovedDialogueExamplesRefreshesRoleWithoutTurningSampleIntoPersonaFact() {
        val app = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.characters.initialize(app)
        CharacterIdentityStore.initialize(app)
        CharacterLifeStore.initialize(app)
        DigitalLifeProfileStore.initialize(app)
        val actor = MigratedDomainStores.characters.create("对话示例角色", "直接但不刻薄")
        val original = CharacterRuntime.definition(actor.characterId)
        CharacterLifeStore.setProfile(actor.characterId, "dialogueExamples",
            "对方：今天累了\\n角色：先歇会儿，等你缓过来再聊")
        val modified = CharacterRuntime.definition(actor.characterId)
        assertFalse(original.hasSameConfiguration(modified))
        assertFalse(modified.promptSection().contains("对方：今天累了"))
        assertTrue(CharacterSpeechIdentity.promptSection(actor.characterId)
            .contains("对方：今天累了"))
        CharacterLifeStore.setProfile(actor.characterId, "dialogueExamples", "")
        assertFalse(modified.hasSameConfiguration(CharacterRuntime.definition(actor.characterId)))
    }

    @Test fun everyDefinitionReadUsesLatestIdentityPersonaAndCompleteBehaviorFields() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.characters.initialize(context)
        CharacterIdentityStore.initialize(context)
        CharacterLifeStore.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        val role = MigratedDomainStores.characters.create("一致性角色", "旧人设")
        CharacterIdentityStore.set(role.characterId, "旧身份")
        CharacterLifeStore.setProfile(role.characterId, "expression", "旧表达方式")
        val old = CharacterRuntime.definition(role.characterId)
        val persona = "完整人设".repeat(500) + "人设末尾关键约束"
        val identity = "完整身份".repeat(500) + "身份末尾关键约束"
        val expression = "完整表达方式".repeat(300) + "行为末尾关键约束"
        MigratedDomainStores.characters.update(role.copy(persona = persona))
        CharacterIdentityStore.set(role.characterId, identity)
        CharacterLifeStore.setProfile(role.characterId, "expression", expression)
        val current = CharacterRuntime.definition(role.characterId)
        val prompt = current.promptSection()
        assertTrue(prompt.contains(persona))
        assertTrue(prompt.contains(identity))
        assertTrue(prompt.contains(expression))
        assertFalse(prompt.contains("旧人设"))
        assertFalse(prompt.contains("旧身份"))
        assertFalse(prompt.contains("旧表达方式"))
        assertFalse(current.hasSameConfiguration(old))
        assertTrue(current.hasSameConfiguration(current.copy(identity = current.identity + "生活节奏更新")))
        val disk = org.json.JSONObject(context.getSharedPreferences("lulu_character_life", 0)
            .getString(role.characterId, "{}")!!)
        assertEquals(expression, disk.getJSONObject("profile").getString("expression"))
        val other = MigratedDomainStores.characters.create("另一个角色", "另一份人设")
        assertFalse(CharacterRuntime.definition(other.characterId).promptSection().contains("行为末尾关键约束"))
        CharacterLifeStore.setProfile(role.characterId, "expression", "修改后的口语习惯")
        assertTrue(CharacterRuntime.definition(role.characterId).persona.contains("修改后的口语习惯"))
        CharacterLifeStore.setProfile(role.characterId, "expression", "")
        assertFalse(CharacterRuntime.definition(role.characterId).persona.contains("修改后的口语习惯"))
        runBlocking {
            val changes = mutableListOf<CharacterDefinitionSnapshot>()
            val collector = launch { CharacterRuntime.definitionChanges(role.characterId).take(2).toList(changes) }
            withTimeout(5000) { while (changes.isEmpty()) yield() }
            CharacterLifeStore.setProfile(other.characterId, "expression", "另一人的表达")
            yield()
            assertEquals(1, changes.size)
            CharacterLifeStore.setProfile(role.characterId, "expression", "通话中刚更新的表达")
            withTimeout(5000) { collector.join() }
            assertTrue(changes.last().persona.contains("通话中刚更新的表达"))
        }
    }
}
