package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Only device connectivity lives in settings. Inspection, preview and owner
 * controls live in 查手机; credentials never enter the model's prompt. */
@Composable
internal fun CharacterWirelessDebuggingSettings() {
    val context = LocalContext.current
    val debug = com.jiacimu.lulu.system.LuluWirelessDebugging
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(debug.suggestedWifiHost(context).orEmpty()) }
    var pairPort by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
        ) {
            Column(Modifier.fillMaxWidth().padding(17.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Text("无线调试", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(notice.ifBlank {
                    if (debug.hasPriorPairing(context)) "已保存配对 · 待检查连接" else "未配对"
                }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy && debug.hasPriorPairing(context),
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    notice = debug.test(context, connectPort.toIntOrNull(), host)
                                } catch (error: Exception) {
                                    notice = "连接失败：" + error.message.orEmpty().take(110)
                                } finally { busy = false }
                            }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text(if (busy) "检查中…" else "检查连接") }
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                            }.onFailure { notice = "请手动打开开发者选项" }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text("系统设置") }
                }
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
        ) {
            Column(Modifier.fillMaxWidth().padding(17.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("设备配对", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = host, onValueChange = { host = it.trim().take(45) },
                    label = { Text("本机 Wi-Fi IP") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = pairPort,
                        onValueChange = { pairPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("配对端口") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                        label = { Text("6 位配对码") },
                        visualTransformation =
                            androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = connectPort,
                    onValueChange = { connectPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("连接端口（可留空）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = !busy && (pairPort.toIntOrNull()?.let { it in 1..65535 } == true)
                        && pairingCode.length == 6,
                    onClick = {
                        val port = pairPort.toIntOrNull() ?: return@Button
                        val code = pairingCode
                        pairingCode = ""
                        busy = true
                        scope.launch {
                            try {
                                notice = debug.pair(context, port, code, host)
                            } catch (error: Exception) {
                                notice = "配对失败：" + error.message.orEmpty().take(110)
                            } finally { busy = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("配对手机") }
                Text("两种端口不同；配对码仅在本机使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(
                    enabled = !busy && debug.hasPriorPairing(context),
                    onClick = {
                        runCatching { debug.forget(context) }
                            .onSuccess { notice = "本机密钥已清除，请到系统里撤销配对" }
                            .onFailure { notice = "撤销失败：" + it.message.orEmpty().take(90) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("清除配对密钥") }
            }
        }
    }
}
