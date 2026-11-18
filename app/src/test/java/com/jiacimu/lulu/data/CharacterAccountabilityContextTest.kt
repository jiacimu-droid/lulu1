package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class CharacterAccountabilityContextTest {
    @Test fun publicProfileSchemaDefinesFieldsWithoutBundledPersonalValues() {
        assertTrue(CharacterProfileSchema.fields.any { it.key == "values" })
        assertTrue(CharacterProfileSchema.fields.any { it.key == "dialogueExamples" })
        assertEquals(CharacterProfileSchema.fields.map { it.key }.toSet().size,
            CharacterProfileSchema.fields.size)
    }

    @Test fun modelReceivesOpenEndedAccountabilityReflection() {
        val guide = CharacterAccountabilityContext.reflectionGuidance("no-such-role-id-193822")
        assertTrue(guide.contains("稳定价值观"))
        assertTrue(guide.contains("即使她没有逐字说出任务名称"))
        assertTrue(guide.contains("一条睡前提醒"))
        assertTrue(guide.contains("实际采取的动作"))
        assertTrue(guide.contains("同一次思考"))
        assertTrue(guide.contains("不规定台词"))
        assertTrue(guide.contains("不强制道歉"))
    }

    @Test fun missingReceiptsCannotBeTreatedAsSuccessfulDelivery() {
        val context = CharacterAccountabilityContext.prompt("no-such-role-id-193822")
        assertTrue(context.contains("没有可验证的结构化任务回执") ||
            context.contains("未证实按约履行"))
        assertTrue(context.contains("不能") || context.contains("不能声称"))
    }

    @Test fun reflectionDoesNotUseKeywordSpecificVetoOrDemandOneEmotion() {
        val guide = CharacterAccountabilityContext.reflectionGuidance("no-such-role-id-193822")
        assertTrue(guide.contains("根据确切证据和自己的人格"))
        assertTrue(guide.contains("表达分歧"))
        assertTrue(guide.contains("现在行动"))
        assertFalse(guide.contains("倒打一耙"))
        assertFalse(guide.contains("甩锅"))
        // Model-authored inner thoughts are no longer rejected by phrase matching.
        assertFalse(CharacterAccountabilityContext::class.java.declaredMethods
            .any { it.name in setOf("guardUnfairBlame", "guardUnfoundedInnerBlame",
                "containsUnfoundedBlame", "challengeGuidance", "isUnmetPromiseChallenge") })
    }

    @Test fun languageQuirksHaveTheirOwnPerRolePersonaSlotNotGlobalWorldbook() {
        val field = CharacterProfileSchema.fields.single { it.key == "speechHabits" }
        assertEquals("说话的味道", field.label)
        assertEquals("呈现与表达", field.group)
        assertTrue(CharacterProfileSchema.groupedFields()
            .flatMap { it.second }.any { it.key == "speechHabits" })
        assertTrue(CharacterProfileSchema.groupedFields()
            .flatMap { it.second }.none { it.key == "typing" })
        assertTrue(CharacterProfileSchema.fields.any { it.key == "expression" })
        assertEquals(CharacterProfileSchema.fields.size,
            CharacterProfileSchema.fields.map { it.key }.distinct().size)
    }

    @Test fun expressionGuidePreservesIndependenceWithoutUnjustifiedCondescension() {
        val guide = CharacterExpressionGuide.promptSection()
        assertTrue(guide.contains("本人的稳定性格、价值、兴趣"))
        assertTrue(guide.contains("承诺和行为要承接真实记录"))
        assertTrue(guide.contains("执行成功必须有真正回执"))
        assertTrue(guide.contains("句长随谈话而变"))
        assertTrue(guide.contains("让情绪自然落在选词、语气、停顿"))
        assertTrue(CharacterExpressionGuide.channelNote(CharacterSpeechChannel.AUTONOMOUS).contains("朋友圈面向熟人分享"))
        assertTrue(CharacterExpressionGuide.channelNote(CharacterSpeechChannel.AUTONOMOUS).contains("日记是自我整理"))
        assertTrue(guide.contains("过去相处形成的阅读默契，自然体现在此刻的措辞与发送停顿里"))
    }

}
