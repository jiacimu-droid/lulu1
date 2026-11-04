package com.jiacimu.lulu.data

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterRelationshipAuthorityTest {
    @Test fun bestFriendBoundariesComeFromThatPersonsAuthoredAgreement() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        CharacterLifeStore.initialize(context)
        val agreement = CharacterSocialRelationship.agreement("lulu", "user")!!
        val prompt = CharacterRuntime.definition("lulu").relationshipPromptSection()
        assertTrue(agreement.details.contains("平等"))
        assertTrue(agreement.details.contains("不把关心演成管家服务或恋人告白"))
        assertTrue(prompt.contains(agreement.title))
        assertTrue(prompt.contains(agreement.details))
        assertFalse(CharacterRuntime.definition(CharacterLifeStore.BUNDLED_JIANG_DU_ID)
            .relationshipPromptSection().contains(agreement.details))
    }

    @Test fun editedMixedRelationshipRemainsCompleteAndDoesNotChangeAnotherPerson() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        CharacterLifeStore.initialize(context)
        val actor = MigratedDomainStores.characters.create("自定义关系人物", "独立、有自己的分寸")
        val other = MigratedDomainStores.characters.create("另一个人", "安静直接")
        val beforeOther = CharacterRuntime.definition(other.characterId)
        val originalPersona = CharacterRuntime.definition(actor.characterId).persona
        val details = "先根据当前语境相处。".repeat(100) + "末尾边界：仅双方明确约定时承担监督。"
        assertTrue(CharacterLifeStore.setRelationshipAgreement(actor.characterId, "user",
            "闺蜜兼恋人", details))
        val mixed = CharacterRuntime.definition(actor.characterId)
        assertTrue(mixed.relationshipPromptSection().contains("闺蜜兼恋人"))
        assertTrue(mixed.relationshipPromptSection().contains(details))
        assertEquals(originalPersona, mixed.persona)
        assertFalse(mixed.relationshipPromptSection().contains("你和对方是平等朋友，不是"))
        assertTrue(CharacterLifeStore.setRelationshipAgreement(actor.characterId, "user",
            "普通朋友", "不继承此前的恋爱约定"))
        val edited = CharacterRuntime.definition(actor.characterId)
        assertFalse(mixed.hasSameConfiguration(edited))
        assertTrue(edited.relationshipPromptSection().contains("不继承此前的恋爱约定"))
        assertFalse(edited.relationshipPromptSection().contains(details))
        assertTrue(beforeOther.hasSameConfiguration(CharacterRuntime.definition(other.characterId)))
        assertFalse(CharacterRuntime.definition(other.characterId).relationshipPromptSection().contains(details))
        assertTrue(CharacterLifeStore.setRelationshipAgreement(actor.characterId, "user", "", ""))
        assertEquals("", CharacterRuntime.definition(actor.characterId).relationshipPromptSection())
    }
}
