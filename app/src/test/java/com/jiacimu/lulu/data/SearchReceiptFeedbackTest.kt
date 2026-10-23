package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Search must be completed by ToolRouter before a role can report results. */
class SearchReceiptFeedbackTest {
    @Test fun webSearchAndReadPreserveRealExecutorEvidence() {
        val args = JSONObject().put("op", "search_read").put("query", "第五人格活动")
        val actual = JSONObject().put("success", true)
            .put("summary", "官网：新活动详情 https://example.org/event")
            .put("evidenceId", "tool-real-execution")
        val receipt = SearchReceiptFeedback.from("web_search", args, actual)
        assertNotNull(receipt)
        assertTrue(receipt!!.success)
        assertEquals("tool-real-execution", receipt.evidenceId)
        assertTrue(receipt.details.contains("https://example.org/event"))
        assertEquals("search_read", receipt.operation)
    }

    @Test fun failuresAndMissingProviderAreNotRewrittenIntoSuccess() {
        val failure = JSONObject().put("success", false).put("error", "搜索渠道尚未配置")
        val actual = SearchReceiptFeedback.from("web_search", JSONObject(), failure)!!
        assertFalse(actual.success)
        assertEquals("搜索渠道尚未配置", actual.details)
        assertNull(SearchReceiptFeedback.from("control_volume", JSONObject(), failure))
        assertNull(SearchReceiptFeedback.from("image_search",
            JSONObject().put("op", "search_send"), failure))
    }

    @Test fun imageCandidateDiscoveryNeedsFollowupButSendingImageAlreadyDeliveredIt() {
        val args = JSONObject().put("op", "search")
        val result = JSONObject().put("success", true)
            .put("summary", "找到候选 searchId=real-id ; index=0")
        assertEquals("image_search", SearchReceiptFeedback.from("image_search", args, result)!!.tool)
        assertNull(SearchReceiptFeedback.from("image_search",
            JSONObject().put("op", "send"), result))
        assertNull(SearchReceiptFeedback.from("image_search",
            JSONObject().put("op", "search_send"), result))
    }
}
