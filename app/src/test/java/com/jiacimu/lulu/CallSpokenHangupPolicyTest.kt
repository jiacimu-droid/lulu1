package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class CallSpokenHangupPolicyTest {
    @Test fun explicitlyCommittedHangupsAreExecutableOnlyAfterSound() {
        listOf(
            "那我挂了", "那我挂了。", "我先挂了，宝宝晚安！",
            "好，那我挂了。", "我现在挂电话了。", "我先把电话挂了。",
            "那我挂了。明天见。", "[softly] 那我先挂啦！",
            "好的，我去挂电话了。", "好，那我这就去挂电话了。",
            "嗯嗯，我马上去挂电话了，宝宝晚安！",
        ).forEach { text ->
            assertTrue("Should end after audible delivery: $text",
                CallSpokenHangupPolicy.explicitlyEndsOwnCall(text))
        }
    }

    @Test fun ordinaryFarewellsAndHypotheticalsCannotSilentlyHangUp() {
        listOf(
            "拜拜", "晚安，宝贝。", "你先挂吧。",
            "那我挂了吗？", "你说那我挂了是什么意思？",
            "我不挂了。", "我还没挂。", "我想挂电话。",
            "我们不是说好先不挂吗？", "如果我挂了你会怎么样？",
            "他刚才说‘那我挂了’，我还没听懂。",
            "等会我先挂了再打给你。",
            "那我挂了，我们明天再聊吧。你觉得怎么样？",
            "",
        ).forEach { text ->
            assertFalse("Should not end call: $text",
                CallSpokenHangupPolicy.explicitlyEndsOwnCall(text))
        }
    }
}
