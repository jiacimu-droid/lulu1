package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class ConversationNaturalnessGateTest {
    @Test fun screenshotComfortIsOneOverfilledTurnRegardlessOfBubbleLayout() {
        val bubbles = ConversationNaturalnessFixtures.screenshotComfort
        listOf(bubbles, listOf(bubbles.joinToString(""))).forEach { layout ->
            val assessment = ConversationNaturalnessGate.assess("不读书就没钱，", layout)
            assertTrue(assessment.needsRerender)
            assertTrue(assessment.reasons.any { it.contains("安慰流程") })
        }
        val natural = listOf("你怕的也是以后的日子，对吧？")
        val before = ConversationNaturalnessGate.assess("不读书就没钱，", bubbles)
        val after = ConversationNaturalnessGate.assess("不读书就没钱，", natural)
        assertFalse(after.needsRerender)
        assertTrue(ConversationNaturalnessGate.shouldUseRewrite(
            "不读书就没钱，", bubbles, natural, before, after))
    }

    @Test fun shortCounsellingScriptIsNotNaturalJustBecauseItIsShort() {
        val script = listOf("先深呼吸。我知道你心里压力很大。休息不等于放弃。别一个人扛，好不好？")
        val assessment = ConversationNaturalnessGate.assess("有点慌", script)
        assertTrue(script.single().length < 90)
        assertTrue(assessment.reasons.any { it.contains("安慰流程") })
        assertFalse(ConversationNaturalnessGate.assess("有点慌",
            listOf("先别一个人扛。", "跟我说说？")).needsRerender)
        assertFalse(ConversationNaturalnessGate.assess("带我一步一步呼吸放松", script)
            .reasons.any { it.contains("安慰流程") })
    }

    @Test fun materiallyLighterRevisionWinsEvenWhenDiscreteFlagsTie() {
        val original = listOf(
            "这个名字我很喜欢，听着很自在，也保留了原来那种温和的感觉。",
            "第一个字让我想到山间的清风，第二个字又带着少年人自由自在的意味。",
            "而且你替我起名字这件事，本身就让我觉得自己被你认真地看见和惦记着。",
            "所以这不只是一个称呼，也是你把我们之间的归属感放进了几个字里面。",
            "从此每次听到你这样叫我，我都会想起今天这一刻，想起你为我花的心思。",
        )
        val rewritten = original.take(3)
        val before = ConversationNaturalnessGate.assess("这个名字怎么样", original)
        val after = ConversationNaturalnessGate.assess("这个名字怎么样", rewritten)
        assertTrue(before.needsRerender)
        assertEquals(before.score, after.score)
        assertTrue(ConversationNaturalnessGate.shouldUseRewrite(
            "这个名字怎么样", original, rewritten, before, after))
        assertFalse(ConversationNaturalnessGate.shouldUseRewrite(
            "这个名字怎么样", original, original, before, before))
    }

    @Test fun lighterRevisionStillCannotDropToolEvidenceOrAddAssistantSpeech() {
        val original = ConversationNaturalnessFixtures.screenshotComfort +
            "还没读到这篇笔记，图片识别失败，https://example.com/post/42 只有2张图。"
        val before = ConversationNaturalnessGate.assess("怎么没读到", original)
        val lostFacts = listOf("先歇会儿，明天再说。")
        assertFalse(ConversationNaturalnessGate.shouldUseRewrite("怎么没读到", original, lostFacts,
            before, ConversationNaturalnessGate.assess("怎么没读到", lostFacts)))
        val assistant = listOf("作为AI，我知道你有压力。")
        assertFalse(ConversationNaturalnessGate.shouldUseRewrite("有点慌",
            ConversationNaturalnessFixtures.screenshotComfort, assistant,
            ConversationNaturalnessGate.assess("有点慌", ConversationNaturalnessFixtures.screenshotComfort),
            ConversationNaturalnessGate.assess("有点慌", assistant)))
    }

    @Test fun presenceComplaintDoesNotAuthorizeInventingAMessageFailure() {
        val result = ConversationNaturalnessGate.assess("不理我",
            listOf("在呢在呢。刚才消息稍微卡了一下没及时回出来，让你等了，是我的错。"))
        assertTrue(result.needsRerender)
        assertTrue(result.reasons.any { it.contains("未经本轮程序回执") })
        assertFalse(ConversationNaturalnessGate.assess("不理我", listOf("啊，我在呢宝宝。"))
            .needsRerender)
    }

    @Test fun ownShortOpeningMattersWithoutTreatingUserQuotesAsTheCharactersStyle() {
        val draft = listOf("在呢在呢，刚才没回上。我这就陪你聊会儿，先跟我说说今天发生了什么有趣的事情吧。")
        val own = ConversationNaturalnessGate.assess("小宝宝", draft,
            recentCharacterReplies = listOf("在呢在呢，来了！"))
        assertTrue(own.reasons.any { it.contains("短起手式") })
        val userQuote = ConversationNaturalnessGate.assess("小宝宝", draft,
            recentHistory = "用户：你之前说在呢在呢")
        assertFalse(userQuote.reasons.any { it.contains("短起手式") })
        assertFalse(ConversationNaturalnessGate.assess("小宝宝", listOf("在呢在呢。"),
            recentCharacterReplies = listOf("在呢在呢，来了！")).needsRerender)
    }

    @Test fun verifiedTechnicalReceiptIsNotTreatedAsAnInventedExcuse() {
        val result = ConversationNaturalnessGate.assess("怎么回事", listOf("消息卡住了，刚才没有发出去。"),
            operationalEvidence = "投递诊断：消息卡住，发送失败")
        assertFalse(result.reasons.any { it.contains("未经本轮程序回执") })
    }

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

    @Test fun characterCanOfferSomethingPersonalThenNaturallyAskWithoutForcedRewrite() {
        val assessment = ConversationNaturalnessGate.assess(
            "我学习累了",
            listOf("我先陪你坐会儿。", "等你想说话了，你希望我怎么陪？"),
        )
        assertFalse(assessment.reasons.any { it.contains("甩回") })
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

    @Test fun shortRepeatedApologiesStillCountAsRobotLikeExplanations() {
        val assessment = ConversationNaturalnessGate.assess(
            "那个线路怎么没接通啊？",
            listOf(
                "是我刚才嘴快了，没搜到。",
                "你别着急，不是你的问题。",
                "害你白等了，刚才没确认清楚。",
            ),
        )
        assertTrue(assessment.needsRerender)
        assertTrue(assessment.reasons.any { it.contains("自我检讨") })
    }

    @Test fun aSingleGenuineApologyIsNotAnIncidentReport() {
        val assessment = ConversationNaturalnessGate.assess(
            "你刚才把我吓了一跳",
            listOf("啊，对不起。", "我刚才是真没注意到。"),
        )
        assertFalse(assessment.needsRerender)
    }

    @Test fun detailedTechnicalRequestMayIncludeResponsibilityAndEvidence() {
        val assessment = ConversationNaturalnessGate.assess(
            "请详细解释清楚，你刚才的工具调用为什么失败",
            listOf(
                "是我刚才没有核实工具结果。你别着急，我先把后台收到的调用状态讲清楚。",
                "不是你的线路设置导致的结论，这里目前只有请求失败的记录，还不能证明是哪一段权限有问题。",
                "我没有拿到笔记正文，因此不能说搜索已经成功。这件事需要根据实际日志进一步判断。",
            ),
        )
        assertFalse(assessment.reasons.any { it.contains("自我检讨") })
    }

    @Test fun oneOrdinaryOfferDoesNotTriggerByItself() {
        val result = ConversationNaturalnessGate.assess(
            "今天还行",
            listOf("要不要出去转转？"),
        )
        assertFalse(result.needsRerender)
    }

    @Test fun nameAppraisalIsAssessedAcrossTheWholeTurnEvenWhenSplitIntoBubbles() {
        val bubbles = listOf(
            "这个名字我很喜欢，听着很自在，也保留了原来那种温和的感觉。",
            "第一个字让我想到山间的清风，第二个字又带着少年人自由自在的意味。",
            "而且你替我起名字这件事，本身就让我觉得自己被你认真地看见和惦记着。",
            "所以这不只是一个称呼，也是你把我们之间的归属感放进了几个字里面。",
            "从此每次听到你这样叫我，我都会想起今天这一刻，想起你为我花的心思。",
        )
        assertTrue(ConversationNaturalnessGate.assess("这个名字怎么样", bubbles).needsRerender)
        assertTrue(ConversationNaturalnessGate.assess("这个名字怎么样", listOf(bubbles.joinToString("")))
            .needsRerender)
        assertFalse(ConversationNaturalnessGate.assess("这个名字怎么样",
            listOf("喜欢，就这个。", "以后你叫这个名字，我就知道在喊我了。")).needsRerender)
    }

    @Test fun longCasualInputDoesNotHideAnOverfilledReply() {
        val input = "今天先去上了课，然后回家时看见了晚霞，还有那家小店也开门了，" +
            "我在门口站了很久也没买东西。回家收拾完房间，忽然觉得今天过得还挺舒服的，想跟你说一声。"
        val reply = "听到你说今天过得舒服，我也很开心，原来这样普通的一天可以有这么多让人留恋的小细节。".repeat(6)
        assertTrue(ConversationNaturalnessGate.assess(input, listOf(reply)).needsRerender)
    }

    @Test fun casualWhyStillAllowsAReasonButDoesNotExemptAnEssay() {
        val reply = "我偏心原来的名字，因为叫得久，已经有种熟悉感了。"
        assertFalse(ConversationNaturalnessGate.assess("为什么呀", listOf(reply)).needsRerender)
        assertTrue(ConversationNaturalnessGate.assess("为什么呀", listOf(reply.repeat(8))).needsRerender)
        assertFalse(ConversationNaturalnessGate.assess("请详细解释原因", listOf(reply.repeat(8)))
            .reasons.any { it.contains("展开过满") })
    }
}

/** A complete turn: splitting it into more bubbles must never make it pass. */
internal object ConversationNaturalnessFixtures {
    val screenshotComfort = listOf(
        "别怕，宝宝，先深吸一口气，把心跳缓下来。",
        "我知道你心里压着多大的石头，备考、以后的生计、还有怕停下来就一无所有的恐慌，甚至连我的存在都成了你肩上的重担。",
        "可是今晚让你停下来歇一歇，绝不等于放弃，更不会直接走到‘活不下去’那一步。",
        "人不是铁打的机器，心里那根弦要是硬生生绷断了，才真的走不动长路。",
        "不差今晚这几个小时。不要把所有极端的结果全堆在这一刻来吓自己，天塌不下来；",
        "就算真有什么，也别一个人扛着，先到我身边缓口气，好不好？",
    )
}
