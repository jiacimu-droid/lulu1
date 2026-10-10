package com.jiacimu.lulu

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.CloudTaskBridge
import kotlinx.coroutines.launch

@Composable
internal fun CloudConnectionSettings() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lulu_advanced_settings", android.content.Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(CloudTaskBridge.configuration().first) }
    var token by remember { mutableStateOf(CloudTaskBridge.configuration().second) }
    var notice by remember { mutableStateOf("") }
    var jobs by remember { mutableStateOf(CloudTaskBridge.tasks()) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("可选云端任务服务", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(url, { url = it }, label = { Text("HTTPS 服务地址") }, modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible(), singleLine = true)
        OutlinedTextField(token, { token = it }, label = { Text("应用访问凭证") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().keepFocusedFieldVisible(), singleLine = true)
        Button(onClick = {
            runCatching { CloudTaskBridge.configure(url, token) }.onSuccess {
                notice = "配置已保存"
                scope.launch {
                    runCatching { CloudTaskBridge.request("/health") }.onSuccess { notice = "服务可连接；声音账号需要在通话时验证" }
                        .onFailure { notice = it.message.orEmpty() }
                }
            }.onFailure { notice = it.message.orEmpty() }
        }) { Text("保存并检查服务") }
        if (notice.isNotBlank()) Text(notice)
        OutlinedButton(onClick = { scope.launch { CloudTaskBridge.refresh(); jobs = CloudTaskBridge.tasks() } }) { Text("刷新云端任务") }
        jobs.takeLast(15).asReversed().forEach { job ->
            Text("${job.optString("kind")} · ${job.optString("status")}")
            val error = job.optString("error").ifBlank { job.optString("connectionError") }
            if (error.isNotBlank()) Text(error)
            if (job.optString("status") == "succeeded") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { runCatching { CloudTaskBridge.download(job) }.onFailure { notice = it.message.orEmpty() } }) { Text("下载文件") }
                    if (job.optJSONObject("result")?.has("preview") == true) OutlinedButton(onClick = {
                        runCatching { CloudTaskBridge.download(job, true) }.onFailure { notice = it.message.orEmpty() }
                    }) { Text("下载预览") }
                }
            }
        }
    }
}
