package com.jiacimu.lulu

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.*
import com.jiacimu.lulu.design.LuluColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

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
                    CharacterLearningRetryButton(characterId, goal.optString("id"))
                    if (status != "paused") TextButton(enabled = status != "running", onClick = {
                        CharacterLearningAgenda.update(characterId, goal.optString("id")) {
                            it.put("status", "paused")
                        }
                    }) { Text("暂缓") }
                    TextButton(enabled = status != "running", onClick = {
                        CharacterLearningAgenda.update(characterId, goal.optString("id")) { it.put("status", "dropped") }
                    }) { Text("放下") }
                }
            }
        }
    }
}

/** Both the avatar sheet and life records use the same actual ToolRouter action. */
@Composable
internal fun CharacterLearningRetryButton(characterId: String, goalId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by CharacterLearningAgenda.revisions.collectAsState()
    val goal = remember(characterId, goalId, revision) { CharacterLearningAgenda.get(characterId, goalId) }
    val status = goal?.optString("status").orEmpty()
    var busy by remember(characterId, goalId) { mutableStateOf(false) }
    var error by remember(characterId, goalId) { mutableStateOf("") }
    if (goal == null || status == "dropped") return
    Column {
        TextButton(enabled = !busy && status != "running", onClick = {
            busy = true
            error = ""
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        if (status == "paused") CharacterLearningAgenda.update(characterId, goalId) {
                            require(it.optString("status") == "paused") { "状态已变化，请查看最新进展" }
                            it.put("status", if (it.optString("materialId").isBlank()) "planned"
                                else if (!CharacterLearningLedger.hasInsight(characterId, it.optString("materialId"))) "research"
                                else "practice")
                        }
                        val args = CharacterLearningAgenda.prepareUserRetry(characterId, goalId)
                        JSONObject(ToolRouter.execute(context.applicationContext, characterId, "learn_topic", args,
                            requestId = "manual-learning-${UUID.randomUUID()}", userRequested = true,
                            causeEvidenceId = goal.optString("originId"), reason = "用户点击继续这件正在琢磨的事"))
                    }
                    if (!result.optBoolean("success")) {
                        val latestError = result.optString("error").ifBlank { result.optString("summary") }
                            .ifBlank { "这次没有完成，可以稍后再试" }
                        // The card already displays the persisted execution error.
                        if (CharacterLearningAgenda.get(characterId, goalId)?.optString("error") != latestError)
                            error = latestError
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message ?: "这次没有完成，可以稍后再试"
                } finally {
                    busy = false
                }
            }
        }) {
            Text(when {
                busy || status == "running" -> "正在探索"
                status == "paused" -> "恢复探索"
                status == "failed" -> "再试一次"
                else -> "继续探索"
            })
        }
        if (error.isNotBlank()) Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
    }
}
