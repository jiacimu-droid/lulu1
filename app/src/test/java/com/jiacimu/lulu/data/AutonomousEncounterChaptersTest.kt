package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AutonomousEncounterChaptersTest {
    @Test fun publicSceneProducesSeparateVerifiedViewpointsInOneResponse() {
        val people = listOf("person-a", "resident:b", "person-c")
        val entries = JSONArray()
        people.forEachIndexed { index, id ->
            entries.put(JSONObject().put("characterId", id)
                .put("title", "三人相遇的第${index + 1}面")
                .put("scene", "大家在同一处庭院里短暂相遇，${id}观察着周围的动静。" +
                    "彼此实际说过的话在午后慢慢交错，三个人感受到的气氛各不相同。"))
        }
        entries.put(JSONObject().put("characterId", "not-present")
            .put("title", "未在场的人").put("scene", "不该保存的虚构故事。".repeat(8)))
        val parsed = AutonomousEncounterChapters.parseChapters(
            JSONObject().put("chapters", entries).toString(), people)
        assertEquals(3, parsed.size)
        assertEquals(people.toSet(), parsed.keys)
        assertTrue(parsed["resident:b"]!!.scene.contains("resident:b"))
        assertFalse(parsed.containsKey("not-present"))
    }

    @Test fun rejectsUnknownViewerAndIncompleteProse() {
        val entries = JSONArray()
            .put(JSONObject().put("characterId", "intruder")
                .put("title", "冒充").put("scene", "好长的无关内容".repeat(10)))
            .put(JSONObject().put("characterId", "a")
                .put("title", "空白").put("scene", "太短"))
        assertTrue(AutonomousEncounterChapters.parseChapters(
            JSONObject().put("chapters", entries).toString(), listOf("a","b")).isEmpty())
    }
}
