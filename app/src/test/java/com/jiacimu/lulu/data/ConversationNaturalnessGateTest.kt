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

    @Test fun stylePassCannotTurnUnreadArticleIntoReadArticle() {
        assertFalse(ConversationNaturalnessGate.preservesSurfaceIntent(
            listOf("还没看到这篇小红书的原文，只收到链接。"),
            listOf("已经看过你发来的原文了，挺有意思。"),
        ))
        assertFalse(ConversationNaturalnessGate.preservesSurfaceIntent(
            listOf("图片识别失败了，我没读到图。"),
            listOf("图片我已经看过了，好漂亮。"),
        ))
    }

    @Test fun stylePassMustRetainExactNumbersAndSharedUrls() {
        val url = "https://www.xiaohongshu.com/explore/6777abcdef1234567890abcd"
        assertFalse(ConversationNaturalnessGate.preservesSurfaceIntent(
            listOf("我做了10题，错了3题。"),
            listOf("我做了100题，错了3题。"),
        ))
        assertFalse(ConversationNaturalnessGate.preservesSurfaceIntent(
            listOf("链接是：$url，先看这一篇。"),
            listOf("链接是：https://www.xiaohongshu.com/explore/6777abcdef1234567890abce，先看这一篇。"),
        ))
        assertTrue(ConversationNaturalnessGate.preservesSurfaceIntent(
            listOf("我做了10题，错了3题。"),
            listOf("10题里有3题错了。"),
        ))
    }

    @Test fun repeatedMeaningAcrossBubblesTriggersRerender() {
        val result = ConversationNaturalnessGate.assess(
            "我有点累",
            listOf("先歇一会儿，别再硬撑了。", "别再硬撑了，先歇一会儿吧。"),
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

    @Test fun screenshotStyleRepeatedToolExplanationTriggersRewrite() {
        val result = ConversationNaturalnessGate.assess(
            "这个线路是不是有问题没接通啊？",
            listOf(
                "宝宝，是我刚才在电话里嘴快了。我去后台认真试了一下，确实没有调用到小红书的专用搜索工具，半天都没收到回执。",
                "你别着急，不是你辛苦调的线路出故障了，是我这边目前确实还没真正接入小红书的工具权限。",
                "刚才没确认清楚就一口答应你去搜，害你白等了一阵，还特意挂了电话跑来担心。",
            ),
        )
        assertTrue(result.needsRerender)
        assertTrue(result.reasons.any { it.contains("多段事后说明") })
    }

    @Test fun userExplicitlyRequestsDetailAndCanReceiveSeveralParagraphs() {
        val result = ConversationNaturalnessGate.assess(
            "请详细解释清楚这次连接失败的技术原因",
            listOf(
                "先看入口，执行器实际有没有收到工具请求，跟模型说它可以使用并不是同一回事。这一点需要从真实日志区分。",
                "接下来检查 WebView 是否附着、是否登录、实际搜索是否拿到了对应请求的结果，任何一步没有回执都不能算成功。",
                "最后再检查权限和超时的来源。工具调用失败时要保存失败阶段，而不能从没有文字回复就推断是哪一个接口坏了。",
            ),
        )
        assertFalse(result.reasons.any { it.contains("多段事后说明") })
        assertFalse("A specifically requested technical explanation must not be shortened just because it is detailed", result.needsRerender)
    }

    @Test fun oneShortEmotionalResponseIsStillFine() {
        val result = ConversationNaturalnessGate.assess(
            "刚才吓死我了",
            listOf("啊？怎么了宝宝？", "我在。"),
        )
        assertFalse(result.needsRerender)
    }

    @Test fun oneOrdinaryOfferDoesNotTriggerByItself() {
        val result = ConversationNaturalnessGate.assess(
            "今天还行",
            listOf("要不要出去转转？"),
        )
        assertFalse(result.needsRerender)
    }
}
