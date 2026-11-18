package com.jiacimu.lulu.data

import com.jiacimu.lulu.ai.ModelReply
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CharacterObservedMomentTest {
    @Test fun aSilentGameDecisionCarriesPrivateStateWithoutExposingItToTheSceneParser() {
        val input = ModelReply(
            """{"type":"wait","text":"","privateMoment":{"innerThought":"我想再看看线索。","innerThoughtBasis":{"focus":"新线索","change":"刚才的猜测站不住了"}}}""",
        )
        val output = CharacterObservedMoment.detach("actor-A", input)
        assertEquals("wait", JSONObject(output.text).getString("type"))
        assertFalse(output.text.contains("我想再看看线索"))
        assertEquals("我想再看看线索。", output.privateMoment?.getString("innerThought"))
        assertEquals("actor-A", output.privateMomentOwner)
    }

    @Test fun invalidOrTruncatedPrivateOutputCannotBecomeSpeechOrPrivateState() {
        val invalid = ModelReply("""{"text":"公开","privateMoment":{"innerThought":"秘密" """)
        val output = CharacterObservedMoment.detach("actor-A", invalid)
        assertEquals("", output.text)
        assertNull(output.privateMoment)

        val complete = ModelReply(
            """{"text":"公开","privateMoment":{"innerThought":"私有"}}""", finishReason = "length",
        )
        val truncated = CharacterObservedMoment.detach("actor-A", complete)
        assertEquals("公开", JSONObject(truncated.text).getString("text"))
        assertNull(truncated.privateMoment)
    }

    @Test fun aCompleteSceneResponseDoesNotNeedToInventAMoodOrThought() {
        val input = ModelReply("""{"action":"keep","indices":[0,2],"say":""}""")
        assertEquals(input, CharacterObservedMoment.detach("actor-B", input))
    }
}
