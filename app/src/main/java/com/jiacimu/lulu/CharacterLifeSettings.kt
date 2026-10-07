package com.jiacimu.lulu

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.CharacterLifeStore
import org.json.JSONObject

@Composable
internal fun CharacterLifeSettings(characterId: String) {
    val states by CharacterLifeStore.states.collectAsState()
    val root = remember(states, characterId) { CharacterLifeStore.state(characterId) }
    val fields = listOf(
        Triple("values", "在意与底线", "他最珍惜什么？什么事不会为了讨好而答应？"),
        Triple("care", "怎样表达关心", "直接表达、做具体小事、默默记挂；选适合他的方式"),
        Triple("conflict", "分歧与受挫", "会坦率争论、先退开整理，还是用玩笑缓和？"),
        Triple("interests", "自己的兴趣", "希望他持续阅读、探索或参与什么？"),
        Triple("expression", "语言与情绪", "简短含蓄、幽默直白、细腻敏感；避免只填性格标签"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("性格如何体现在行为里", fontWeight = FontWeight.Bold)
        fields.forEach { (key, label, hint) ->
            OutlinedTextField(value = root.optJSONObject("profile")?.optString(key).orEmpty(),
                onValueChange = { CharacterLifeStore.setProfile(characterId, key, it) },
                label = { Text(label) }, placeholder = { Text(hint) }, modifier = Modifier.fillMaxWidth())
        }
        val intention = root.optJSONObject("intention")
        if (intention != null) {
            Text("正在在意的事", fontWeight = FontWeight.Bold)
            Text(intention.optString("aim"))
            Text(intention.optString("motive"))
            val outcomes = intention.optJSONArray("outcomes")
            if (outcomes != null) for (i in maxOf(0, outcomes.length() - 3) until outcomes.length()) {
                val outcome: JSONObject = outcomes.getJSONObject(i)
                Text("${if (outcome.optBoolean("success")) "已执行" else "未完成"} · ${outcome.optString("summary")}")
            }
            TextButton(onClick = { CharacterLifeStore.stopIntention(characterId) }) { Text("结束这件事") }
        }
    }
}
