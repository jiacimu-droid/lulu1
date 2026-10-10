package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class ConversationNaturalnessGateTest {
    @Test fun fatiguePlusWhatShouldIDoIsStronglyAssistantLike() {
        val result = ConversationNaturalnessGate.assess(
            "我学习累了",
            listOf("听起来你今天学习很累。你想让我做什么？"),
        )
        assertTrue(result.needsRerender)
        assertTrue(result.reasons.any { it.contains("甩回") })
    }

    @Test fun characterAskingWhatShouldIDoAlsoGetsRerendered() {
        val result = ConversationNaturalnessGate.assess(
            "我学习累了",
            listOf("那我该做些什么？"),
        )
        assertTrue(result.needsRerender)
        assertTrue(result.reasons.any { it.contains("甩回") })
    }

    @Test fun naturalRoleOwnedResponsePasses() {
        val result = ConversationNaturalnessGate.assess(
            "我学习累了",
            listOf("先别硬撑了。", "我陪你晃会儿脑子。"),
        )
        assertFalse(result.needsRerender)
    }

    @Test fun outlineStyleCasualReplyGetsRerendered() {
        val result = ConversationNaturalnessGate.assess(
            "我今天有点烦",
            listOf("首先你可以休息一下，其次如果你愿意可以和我说说，最后我们再看看怎么办。"),
        )
        assertTrue(result.needsRerender)
        assertTrue(result.score >= 2)
    }

    @Test fun surfaceRewriteCannotInventQuestionsActionsOrRelationshipEscalation() {
        assertFalse(ConversationNaturalnessGate.preservesSurfaceIntent(
            listOf("嗯，知道了。"),
            listOf("嗯，知道了。", "那你现在要不要告诉我为什么？"),
        ))
        assertFalse(ConversationNaturalnessGate.preservesSurfaceIntent(
            listOf("先歇一下。"),
            listOf("先歇一下。", "我马上帮你处理。"),
        ))
        assertFalse(ConversationNaturalnessGate.preservesSurfaceIntent(
            listOf("我在。"),
            listOf("我永远都不会离开你。"),
        ))
        assertTrue(ConversationNaturalnessGate.preservesSurfaceIntent(
            listOf("别硬撑了，先歇会儿。"),
            listOf("先别硬撑，歇会儿。"),
        ))
    }

    @Test fun repeatedMeaningAcrossBubblesTriggersRerender() {
        val result = ConversationNaturalnessGate.assess(
            "我有点累",
            listOf("先歇会儿，别硬撑。", "别再硬撑了，先休息一会儿。"),
        )
        assertTrue(result.needsRerender)
        assertTrue(result.reasons.any { it.contains("换词重复") })
    }

    @Test fun repeatedLongOpeningFromRecentHistoryTriggersRerender() {
        val result = ConversationNaturalnessGate.assess(
            "今天也有点烦",
            listOf("我知道你现在心里不太舒服。"),
            "江渡：我知道你现在心里不太舒服。\n你：昨天就是有点烦",
        )
        assertTrue(result.needsRerender)
        assertTrue(result.reasons.any { it.contains("长起手式") })
    }

    @Test fun oneOrdinaryOfferDoesNotTriggerByItself() {
        val result = ConversationNaturalnessGate.assess(
            "今天还行",
            listOf("要不要出去转转？"),
        )
        assertFalse(result.needsRerender)
    }
}
