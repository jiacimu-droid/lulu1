package com.jiacimu.lulu

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.jiacimu.lulu.data.ResidentDailyDecision
import com.jiacimu.lulu.design.LuluAlertDialog
import kotlinx.coroutines.launch

/** Recovery is attached to the existing resident schedule, not another task system. */
@Composable
internal fun ResidentDecisionRecoveryControl(nativeId: String, onUpdated: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember(nativeId) { mutableIntStateOf(0) }
    val recovery = remember(nativeId, refresh) { ResidentDailyDecision.dayRecovery(context, nativeId) }
    val savedRecovery = remember(nativeId, refresh) {
        ResidentDailyDecision.savedDayRecovery(context, nativeId)
    }
    val hasPartialActions = remember(nativeId, refresh) {
        ResidentDailyDecision.hasPartialDayActions(context, nativeId)
    }
    var confirmingSaved by remember(nativeId) { mutableStateOf(false) }
    var confirming by remember(nativeId) { mutableStateOf(false) }
    var busy by remember(nativeId) { mutableStateOf(false) }
    var result by remember(nativeId) { mutableStateOf("") }
    if (recovery != null) {
        Text(recovery.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { confirming = true }, enabled = !busy) {
            Text(if (busy) "正在处理" else "重新请求今日规划")
        }
    }
    if (hasPartialActions) {
        Text("今日规划有行动未完成，详情见原始时间线", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (savedRecovery != null) {
        TextButton(onClick = { confirmingSaved = true }, enabled = !busy) {
            Text(if (busy) "正在处理" else "续接今日已保存的行动")
        }
    }
    if (result.isNotBlank()) Text(result, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (confirmingSaved && savedRecovery != null) LuluAlertDialog(
        onDismissRequest = { confirmingSaved = false },
        title = { Text("续接今日未完成的行动？") },
        text = { Text("不再请求模型，只按角色今天已保存的决定核对未完成动作。已成功动作沿原执行 ID 去重；权限拒绝或结果未知的动作仍可能无法完成。每个决定最多人工续接一次。") },
        confirmButton = { TextButton(onClick = {
            confirmingSaved = false; busy = true
            scope.launch {
                result = try {
                    if (ResidentDailyDecision.resumeSavedDayDecision(context, savedRecovery))
                        "今日行动已续接完成"
                    else "仍有行动未完成，请查看原始时间线的执行回执"
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { "续接未完成，请查看执行回执" }
                finally { busy = false; refresh++; onUpdated() }
            }
        }) { Text("续接一次") } },
        dismissButton = { TextButton(onClick = { confirmingSaved = false }) { Text("取消") } },
    )
    if (confirming && recovery != null) LuluAlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("重新请求今日规划？") },
        text = { Text("上次请求可能已经计费。重新请求可能再次计费；将按当前情况重新规划，不补做昨天的活动。") },
        confirmButton = { TextButton(onClick = {
            confirming = false
            busy = true
            scope.launch {
                result = try {
                    if (ResidentDailyDecision.retryDayDecision(context, nativeId, recovery))
                        "今日规划已恢复"
                    else "状态已变化或请求仍未完成，请查看请求记录"
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { "恢复未完成，请查看请求记录" }
                finally { busy = false; refresh++; onUpdated() }
            }
        }) { Text("重新请求") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("取消") } },
    )
}

@Composable
internal fun ResidentPlanRecoveryControl(plan: com.jiacimu.lulu.data.RoleDayPlan, onUpdated: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember(plan.id, plan.dueAt) { mutableIntStateOf(0) }
    val recovery = remember(plan.id, plan.dueAt, refresh) { ResidentDailyDecision.dueRecovery(context, plan) }
    val savedRecovery = remember(plan.id, plan.dueAt, refresh) {
        ResidentDailyDecision.savedDueRecovery(context, plan)
    }
    var confirmingSaved by remember(plan.id, plan.dueAt) { mutableStateOf(false) }
    var confirming by remember(plan.id, plan.dueAt) { mutableStateOf(false) }
    var busy by remember(plan.id) { mutableStateOf(false) }
    var result by remember(plan.id) { mutableStateOf("") }
    if (recovery != null) TextButton(onClick = { confirming = true }, enabled = !busy) {
        Text(if (busy) "正在处理" else "恢复这项活动的决定")
    }
    if (savedRecovery != null) TextButton(
        onClick = { confirmingSaved = true }, enabled = !busy,
    ) { Text(if (busy) "正在处理" else "续接已保存的行动") }
    if (result.isNotBlank()) Text(result, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (confirmingSaved && savedRecovery != null) LuluAlertDialog(
        onDismissRequest = { confirmingSaved = false },
        title = { Text("续接未完成的行动？") },
        text = { Text("不重新请求模型，只续接角色已保存的决定。已完成动作沿原请求 ID 去重；失败步骤仍可能无法执行。每项旧决定最多人工续接一次。") },
        confirmButton = { TextButton(onClick = {
            confirmingSaved = false; busy = true
            scope.launch {
                result = try {
                    if (ResidentDailyDecision.resumeSavedDueDecision(context, savedRecovery))
                        "已续接保存的行动"
                    else "行动仍未完成，或日程已改变；请查看执行回执"
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { "续接未完成，请查看执行回执" }
                finally { busy = false; refresh++; onUpdated() }
            }
        }) { Text("续接一次") } },
        dismissButton = { TextButton(onClick = { confirmingSaved = false }) { Text("取消") } },
    )
    if (confirming && recovery != null) LuluAlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("重新考虑这项活动？") },
        text = { Text("上次模型请求可能已经计费，再次请求可能重复计费。角色将按现在的情况重新决定，不会直接补做旧活动。") },
        confirmButton = { TextButton(onClick = {
            confirming = false; busy = true
            scope.launch {
                result = try {
                    if (ResidentDailyDecision.retryDueDecision(context, recovery)) "已处理这项活动的决定"
                    else "状态已变化或请求仍未完成，请查看请求记录"
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { "恢复未完成，请查看请求记录" }
                finally { busy = false; refresh++; onUpdated() }
            }
        }) { Text("重新请求") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("取消") } },
    )
}
