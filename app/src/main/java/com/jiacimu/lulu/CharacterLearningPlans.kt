package com.jiacimu.lulu

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.*
import com.jiacimu.lulu.design.LuluColors

/** Active questions are current intentions; their eventual understanding is already in memory. */
@Composable
internal fun CharacterLearningPlans(characterId: String) {
    val revision by CharacterLearningAgenda.revisions.collectAsState()
    val goals = remember(characterId, revision) {
        CharacterLearningAgenda.goals(characterId).filter { it.optString("status") != "dropped" }
    }
    if (goals.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("正在琢磨", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        goals.forEach { goal ->
            val status = goal.optString("status")
            val label = when (status) {
                "planned" -> "准备了解"; "running" -> "正在探索"; "failed" -> "遇到阻碍"
                "paused" -> "暂缓"; "research" -> "待核实"; else -> "想继续尝试"
            }
            CharacterRecordCard {
                Text(goal.optString("topic"), fontSize = 14.sp)
                Text(label, fontSize = 11.sp, color = LuluColors.Muted)
                goal.optString("error").takeIf(String::isNotBlank)?.let { Text(it, fontSize = 12.sp) }
                Row {
                    TextButton(enabled = status != "running", onClick = {
                        CharacterLearningAgenda.update(characterId, goal.optString("id")) {
                            it.put("status", if (status == "paused")
                                if (it.optString("materialId").isBlank()) "planned"
                                else if (!CharacterLearningLedger.hasInsight(characterId, it.optString("materialId"))) "research"
                                else "practice" else "paused")
                        }
                    }) { Text(if (status == "paused") "恢复" else "暂缓") }
                    TextButton(enabled = status != "running", onClick = {
                        CharacterLearningAgenda.update(characterId, goal.optString("id")) { it.put("status", "dropped") }
                    }) { Text("放下") }
                }
            }
        }
    }
}
