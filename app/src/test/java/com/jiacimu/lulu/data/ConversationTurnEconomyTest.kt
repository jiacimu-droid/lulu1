package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class ConversationTurnEconomyTest {
    @Test fun delayDoesNotEstablishADeliveryFailure() {
        assertTrue(ConversationTurnEconomy.hasUnsupportedDeliveryCause(
            "刚才消息稍微卡了一下没及时回出来。", "用户三分钟前发送了消息；本轮现在开始回复"))
        assertFalse(ConversationTurnEconomy.hasUnsupportedDeliveryCause(
            "刚才没回上，我在呢。", ""))
        assertFalse(ConversationTurnEconomy.hasUnsupportedDeliveryCause(
            "不知道是不是消息卡住了。", ""))
        assertFalse(ConversationTurnEconomy.hasUnsupportedDeliveryCause(
            "不是消息卡住了，是我没回上。", ""))
    }

    @Test fun actualDiagnosticAllowsReportingTheVerifiedFailure() {
        assertFalse(ConversationTurnEconomy.hasUnsupportedDeliveryCause(
            "刚才消息卡住了。", "执行器诊断：消息卡住，投递失败"))
        assertTrue(ConversationTurnEconomy.hasUnsupportedDeliveryCause(
            "刚才我这边断网了。", "只是发现了尚未回复的用户消息"))
        assertFalse(ConversationTurnEconomy.hasUnsupportedDeliveryCause(
            "刚才我这边断网了。", "程序实际检测到：刚才我这边断网"))
    }

    @Test fun repeatedShortOpeningIsACueButPresenceAndRequestedRepetitionAreAllowed() {
        assertTrue(ConversationTurnEconomy.repeatsOpening(
            "在呢在呢，刚才没回上。", listOf("在呢在呢，来了！"), "不理我"))
        assertFalse(ConversationTurnEconomy.repeatsOpening(
            "我在。", listOf("我在。"), "宝宝"))
        assertFalse(ConversationTurnEconomy.repeatsOpening(
            "在呢在呢，来了！", listOf("在呢在呢，来了！"), "再说一遍刚才的话"))
        assertFalse(ConversationTurnEconomy.repeatsOpening(
            "在呢在呢，来了！", emptyList(), "小宝宝"))
    }

    @Test fun onlyOverloadedUnrequestedClockPivotsAreFlagged() {
        val speech = "这么晚了还没睡呀？我看现在都一点半过了，我们宝宝这是熬夜忙完刚闲下来，" +
            "还是今晚还不困，想抓我出来陪你聊会儿天？刚才没能及时回上，让你干等这三分钟，是我的错。"
        assertTrue(ConversationTurnEconomy.overloadedClockPivot("不理我", speech))
        assertFalse(ConversationTurnEconomy.overloadedClockPivot("我睡不着", speech))
        assertFalse(ConversationTurnEconomy.overloadedClockPivot("不理我", "这么晚还没睡呀？"))
    }

    @Test fun openingCuesContainNeitherFullOldRepliesNorOtherKindsOfState() {
        val old = "在呢在呢，今天实际读完了一段书，有个细节想告诉你。"
        val context = ConversationTurnEconomy.context("宝宝", listOf(old))
        assertTrue(context.contains("在呢在呢"))
        assertFalse(context.contains("读完了一段书"))
        assertEquals("", ConversationTurnEconomy.context("宝宝"))
        assertEquals("", ConversationTurnEconomy.context("原样重复", listOf(old)))
    }
}
