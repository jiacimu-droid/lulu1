package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class CharacterAccountabilityContextTest {
    @Test fun jiangDuRewriteKeepsWorldIdentityAndUsesEveryExistingProfileField() {
        assertEquals(LegacyJiangDuProfileSchema.jiangDuIdentity, CharacterProfileSchema.jiangDuIdentity)
        assertEquals(CharacterProfileSchema.fields.map { it.key }.toSet(), CharacterProfileSchema.jiangDu.keys)
        assertTrue(CharacterProfileSchema.jiangDu.filterKeys { it != "typing" }.values.all(String::isNotBlank))
        assertEquals("", CharacterProfileSchema.jiangDu["typing"])
    }

    @Test fun disputedMissedWakeUpIsNotRecastAsUserFault() {
        assertTrue(CharacterAccountabilityContext.isUnmetPromiseChallenge("那你也没十点叫我啊"))
        assertTrue(CharacterAccountabilityContext.isUnmetPromiseChallenge("明天你答应叫醒我，怎么没有闹钟"))
        assertFalse(CharacterAccountabilityContext.isUnmetPromiseChallenge("今天我吃了披萨"))
        assertFalse(CharacterAccountabilityContext.isUnmetPromiseChallenge("明天10点叫我"))
        val guide = CharacterAccountabilityContext.challengeGuidance("你都没有叫醒我")
        assertTrue(guide.contains("当时是谁答应执行"))
        assertTrue(guide.contains("不预设道歉句式"))
    }

    @Test fun missingReceiptsCannotBeTreatedAsSuccessfulDelivery() {
        val context = CharacterAccountabilityContext.prompt("no-such-role-id-193822")
        assertTrue(context.contains("没有可验证的结构化任务回执") ||
            context.contains("未证实按约履行"))
        assertTrue(context.contains("不能") || context.contains("不能声称"))
    }

    @Test fun privateThoughtCannotBlameUserForRoleResponsibilityWithoutEvidence() {
        val incoming = "两点五十一分要给我打电话"
        assertEquals("", CharacterAccountabilityContext.guardUnfoundedInnerBlame(
            incoming, "准时打过去，不给她抓把柄挑刺的机会。"))
        assertEquals("我得核对时间，别再弄错。",
            CharacterAccountabilityContext.guardUnfoundedInnerBlame(incoming, "我得核对时间，别再弄错。"))
        assertEquals("她不是在挑刺，是我自己没按约定做完。",
            CharacterAccountabilityContext.guardUnfoundedInnerBlame(incoming, "她不是在挑刺，是我自己没按约定做完。"))
        assertEquals("她说这道题不对，我真的不同意。",
            CharacterAccountabilityContext.guardUnfoundedInnerBlame("今天的法律题很难", "她说这道题不对，我真的不同意。"))
    }

    @Test fun neutralWakeFeedbackIsNotTreatedAsUserBlame() {
        val feedback = "你声音比较温柔，所以叫不醒我"
        assertTrue(CharacterAccountabilityContext.isWakeExplanation(feedback))
        val response = CharacterAccountabilityContext.guardUnfairBlame(
            feedback, "这甩锅的理由找得挺齐全呀，怎么还嘴硬。")
        assertFalse(response.contains("甩锅的理由"))
        assertEquals("", response)
        assertFalse(CharacterDecisionProtocol.runtimePrinciples.contains("主人与仆人"))
    }

    @Test fun obviousRoleBlameIsStoppedOnlyInMissedCommitmentContext() {
        val unfair = "憋了四个小时，就憋出这一句倒打一耙？"
        val repaired = CharacterAccountabilityContext.guardUnfairBlame("那你也没十点叫我啊", unfair)
        assertFalse(repaired.contains("倒打一耙"))
        assertEquals("", repaired)
        assertTrue(CharacterAccountabilityContext.containsUnfoundedBlame("那你也没十点叫我啊", unfair))
        assertEquals(unfair, CharacterAccountabilityContext.guardUnfairBlame("今天吃了一个披萨", unfair))
        val fair = "是我没做到，我不该怪你。对不起。"
        assertEquals(fair, CharacterAccountabilityContext.guardUnfairBlame("你没叫我起床", fair))
        assertFalse(CharacterAccountabilityContext.containsUnfoundedBlame(
            "今天这题我没懂", "我有证据表明这题答案应该是B"))
        assertEquals("", CharacterAccountabilityContext.guardUnfairBlame(
            "你没有叫醒我", "我看你就是嘴硬，明明是你自己懒"))
        // No canned apology is returned for any companion.
        assertFalse(CharacterAccountabilityContext::class.java.declaredMethods
            .any { it.name.contains("fixedApology") })
    }

    @Test fun languageQuirksHaveTheirOwnPerRolePersonaSlotNotGlobalWorldbook() {
        val field = CharacterProfileSchema.fields.single { it.key == "speechHabits" }
        assertEquals("说话的味道", field.label)
        assertEquals("呈现与表达", field.group)
        assertTrue(CharacterProfileSchema.groupedFields()
            .flatMap { it.second }.any { it.key == "speechHabits" })
        assertTrue(CharacterProfileSchema.groupedFields()
            .flatMap { it.second }.none { it.key == "typing" })
        assertEquals(CharacterProfileSchema.jiangDuSpeechHabits, CharacterProfileSchema.jiangDu["speechHabits"])
        assertEquals(listOf("expression", "speechHabits"), CharacterProfileSchema.featuredFields.map { it.key })
        assertEquals(CharacterProfileSchema.fields.size, CharacterProfileSchema.featuredFields.size + CharacterProfileSchema.otherFields.size)
    }

    @Test fun expressionGuidePreservesIndependenceWithoutUnjustifiedCondescension() {
        val guide = CharacterExpressionGuide.promptSection()
        assertTrue(guide.contains("独立人格"))
        assertTrue(guide.contains("承诺和行为要承接真实记录"))
        assertTrue(guide.contains("执行成功必须有真正回执"))
        assertTrue(guide.contains("语气、停顿、标点和消息节奏"))
        assertTrue(guide.contains("朋友圈面向熟人分享"))
        assertTrue(guide.contains("日记是自我整理"))
        assertTrue(guide.contains("个人语言会成长，但不自我复制"))
    }

    @Test fun sleepingSupervisionDisputeIsNotMisreadAsUserAttackingTheRole() {
        val user = "但是你在12点之后一点反应都没有，那我说你食言了，有问题吗？"
        assertTrue(CharacterAccountabilityContext.isUnmetPromiseChallenge(user))
        assertTrue(CharacterAccountabilityContext.challengeGuidance(user).contains("核查"))
        assertTrue(CharacterAccountabilityContext.containsUnfoundedBlame(
            user, "你这不是倒打一耙是什么"))
        assertEquals("", CharacterAccountabilityContext.guardUnfoundedInnerBlame(
            user, "她居然倒打一耙，还敢质问我。"))
        assertEquals("她没有倒打一耙，我得去核对原先的约定和实际回执。",
            CharacterAccountabilityContext.guardUnfoundedInnerBlame(
                user, "她没有倒打一耙，我得去核对原先的约定和实际回执。"))
        // Honest evidence-based disagreement on other matters remains possible.
        assertEquals("这题的法条我确实有不同理解。",
            CharacterAccountabilityContext.guardUnfoundedInnerBlame(
                "我们讨论刑法的法条", "这题的法条我确实有不同理解。"))
    }
}
