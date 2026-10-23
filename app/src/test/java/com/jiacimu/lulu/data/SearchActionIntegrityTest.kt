package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SearchActionIntegrityTest {
    @Test fun aPromisedSearchWithoutASelectedToolNeedsModelOwnedRepair() {
        assertTrue(SearchActionIntegrity.needsFollowThrough(
            "那你随便搜吧", "我去网上搜一下《第五人格》万圣节活动。",
            "message", "", null))
        assertTrue(SearchActionIntegrity.needsFollowThrough(
            "帮我搜索一下新活动", "我这就查查。",
            "message", "", JSONArray()))
    }

    @Test fun neverForceSearchOnRefusalOrOnARealExistingStep() {
        val steps = JSONArray().put(JSONObject().put("tool", "web_search")
            .put("args", JSONObject().put("query", "新活动")))
        assertFalse(SearchActionIntegrity.needsFollowThrough(
            "请搜一下", "我去搜吧", "message", "", steps))
        assertFalse(SearchActionIntegrity.needsFollowThrough(
            "请搜一下", "不搜这个，我可以解释原因", "message", "", null))
        assertFalse(SearchActionIntegrity.needsFollowThrough(
            "你搜了吗", "没有搜，现在还没查到", "message", "", null))
        assertFalse(SearchActionIntegrity.needsFollowThrough(
            "请搜一下", "我觉得没必要查", "silent", "", null))
    }

    @Test fun acceptOnlyRealSearchToolAndBoundedModelSelectedParameters() {
        val yes = SearchActionIntegrity.validatedAction(
            """{"tool":"web_search","args":{"op":"search","query":"第五人格万圣节活动官网"}}""")
        assertEquals("web_search", yes?.optString("tool"))
        assertEquals("第五人格万圣节活动官网",
            yes?.optJSONObject("args")?.optString("query"))
        assertTrue(yes?.optBoolean("requiresPreviousSuccess") == true)
        assertNull(SearchActionIntegrity.validatedAction(
            """{"tool":"screen_action","args":{"query":"秘密"}}"""))
        assertNull(SearchActionIntegrity.validatedAction(
            """{"tool":"web_search","args":{"op":"send","query":"第五人格"}}"""))
        assertNull(SearchActionIntegrity.validatedAction(
            """{"tool":"image_search","args":{"op":"send","query":"表情包"}}"""))
        assertNull(SearchActionIntegrity.validatedAction(
            """{"tool":"web_search","args":{"op":"search","query":""}}"""))
    }
}
