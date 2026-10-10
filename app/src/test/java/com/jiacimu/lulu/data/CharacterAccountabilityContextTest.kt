package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class CharacterAccountabilityContextTest {
    @Test fun jiangDuV6RetainsAllPreviouslyDefinedSemanticPillars() {
        val whole = buildString {
            appendLine(CharacterProfileSchema.jiangDuIdentity)
            appendLine(CharacterProfileSchema.jiangDuPersona)
            CharacterProfileSchema.jiangDu.values.forEach(::appendLine)
        }
        listOf(
            "数字生命", "没有现实肉身", "现实职业", "数字投影",
            "恋人身份", "共同回忆", "感情基础", "真实相处",
            "温柔", "明朗", "幽默", "少年意气", "独立个性", "内在逻辑",
            "细腻共情", "独立而细腻的审美", "内在美", "可靠", "有原则",
            "陌生", "好奇", "责任感", "迟疑", "吸引", "安全感", "真实行动", "可兑现承诺",
            "不自以为看穿", "嘴硬", "管教", "轻蔑", "冷暴力",
            "俏皮", "暧昧", "适度强势", "有城府", "害羞",
            "主人", "公主殿下", "妻主大人", "冷笑话", "谐音",
            "不预设", "兴趣", "核心恐惧", "创伤", "占有欲",
        ).forEach { marker ->
            assertTrue("江渡 v6 丢失旧设定语义：$marker", whole.contains(marker))
        }
    }

    @Test fun disputedMissedWakeUpIsNotRecastAsUserFault() {
        assertTrue(CharacterAccountabilityContext.isUnmetPromiseChallenge("那你也没十点叫我啊"))
        assertTrue(CharacterAccountabilityContext.isUnmetPromiseChallenge("明天你答应叫醒我，怎么没有闹钟"))
        assertFalse(CharacterAccountabilityContext.isUnmetPromiseChallenge("今天我吃了披萨"))
        assertFalse(CharacterAccountabilityContext.isUnmetPromiseChallenge("明天10点叫我"))
        val guide = CharacterAccountabilityContext.challengeGuidance("你都没有叫醒我")
        assertTrue(guide.contains("先确认谁承担叫醒或提醒责任"))
        assertTrue(guide.contains("倒打一耙"))
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

    @Test fun obviousRoleBlameIsStoppedOnlyInMissedCommitmentContext() {
        val unfair = "憋了四个小时，就憋出这一句倒打一耙？"
        val repaired = CharacterAccountabilityContext.guardUnfairBlame("那你也没十点叫我啊", unfair)
        assertFalse(repaired.contains("倒打一耙"))
        assertTrue(repaired.contains("是我答应"))
        assertTrue(repaired.contains("对不起"))
        assertEquals(unfair, CharacterAccountabilityContext.guardUnfairBlame("今天吃了一个披萨", unfair))
        val fair = "是我没做到，我不该怪你。对不起。"
        assertEquals(fair, CharacterAccountabilityContext.guardUnfairBlame("你没叫我起床", fair))
    }

    @Test fun languageQuirksHaveTheirOwnPerRolePersonaSlotNotGlobalWorldbook() {
        val field = CharacterProfileSchema.fields.single { it.key == "speechHabits" }
        assertEquals("语言习惯与小癖好", field.label)
        assertEquals("表达", field.group)
        assertTrue(field.hint.contains("倒装"))
        assertTrue(field.hint.contains("标点"))
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
}
