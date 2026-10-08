package com.jiacimu.lulu.ai

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ModelResponsePayloadTest {
    @Test fun nonStreamingRequestCanReadActualSseWithoutConvertingDataIntoAnObject() {
        val raw = """
            : keepalive
            data: {"choices":[{"delta":{"content":"她说“"}}]}

            data: {"choices":[{"delta":{"content":"别走”。"}}]}

            data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":7}}

            data: [DONE]
        """.trimIndent()
        val parsed = modelResponseObject(raw)
        assertEquals("她说“别走”。", parsed.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
        assertEquals(12, parsed.getJSONObject("usage").getInt("prompt_tokens"))
    }
    @Test fun encodedObjectsAndArrayApisRemainSupported() {
        val json = JSONObject().put("data", "正文不是流式标头 data:")
        val encoded = JSONObject.quote(JSONObject.quote(json.toString()))
        assertEquals(json.getString("data"), modelResponseObject(encoded).getString("data"))
        assertEquals(2, modelResponseObject("[1,2]").getJSONArray("data").length())
    }
    @Test fun claudeStreamPreservesItsContentBlocksAndUsage() {
        val raw = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":10}}}

            data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"正文"}}

            data: {"type":"message_delta","usage":{"output_tokens":3}}

        """.trimIndent()
        val parsed = modelResponseObject(raw)
        assertEquals("正文", parsed.getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals(10, parsed.getJSONObject("usage").getInt("input_tokens"))
        assertEquals(3, parsed.getJSONObject("usage").getInt("output_tokens"))
    }
    @Test fun malformedResponsesAndStreamErrorsAreNeverReturnedAsNovelProse() {
        assertTrue(runCatching { modelResponseObject("data: incomplete") }.isFailure)
        assertTrue(runCatching { modelResponseObject("data: {\"error\":{\"message\":\"限流\"}}") }.exceptionOrNull()?.message?.contains("限流") == true)
        assertTrue(runCatching { modelResponseObject("普通错误页面") }.isFailure)
    }
}
