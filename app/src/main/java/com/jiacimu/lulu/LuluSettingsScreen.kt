package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelConnection
import com.jiacimu.lulu.ai.ModelConnectionStore
import com.jiacimu.lulu.ai.ModelProviderKind
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.launch

private val SettingsPaper = Color(0xFFFFFDF7)
private val SettingsCard = Color(0xFFFFFBF1)
private val SettingsBorder = Color(0xFFEAE0CC)
private val SettingsMuted = Color(0xFF6D7888)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuluSettingsScreen(onBack: () -> Unit) {
    val saved by LuluAiServices.connectionStore.state.collectAsState()
    val scope = rememberCoroutineScope()
    var provider by remember(saved.provider) { mutableStateOf(saved.provider) }
    var baseUrl by remember(saved.baseUrl) { mutableStateOf(saved.baseUrl) }
    var model by remember(saved.model) { mutableStateOf(saved.model) }
    var apiKey by remember(saved.apiKey) { mutableStateOf(saved.apiKey) }
    var enabled by remember(saved.enabled) { mutableStateOf(saved.enabled) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }

    fun save() {
        LuluAiServices.connectionStore.save(
            ModelConnection(
                provider = provider,
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                enabled = enabled,
            ),
        )
        message = "模型配置已保存在本机"
        error = false
    }

    Scaffold(
        containerColor = SettingsPaper,
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SettingsPaper),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            item {
                SettingsCardBox {
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("模型与 API", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                            Text("供聊天、角色游戏、学习计划和角色判断共用。", color = SettingsMuted)
                        }
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ModelProviderKind.entries.forEach { item ->
                            FilterChip(
                                selected = provider == item,
                                onClick = {
                                    provider = item
                                    baseUrl = ModelConnectionStore.defaultBaseUrl(item)
                                },
                                label = { Text(item.label()) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("API 地址") },
                        supportingText = { Text("兼容中转站和自定义服务地址") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("模型名称") },
                        placeholder = { Text("例如 gpt-5.6-terra") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API 密钥") },
                        leadingIcon = { Icon(Icons.Outlined.Key, null) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Button(onClick = ::save, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.CheckCircleOutline, null)
                        Spacer(Modifier.width(7.dp))
                        Text("保存配置")
                    }
                    OutlinedButton(
                        onClick = {
                            save()
                            testing = true
                            message = ""
                            scope.launch {
                                val characterId = MigratedDomainStores.characters.settings.value.keys.firstOrNull() ?: "lulu"
                                LuluAiServices.gateway.generate(
                                    characterId = characterId,
                                    facts = "这是一次连接测试，不代表任何真实事件。",
                                    instruction = "只回复‘连接成功’，不要添加别的内容。",
                                    source = "设置",
                                    title = "模型连接测试",
                                    temperature = 0.0,
                                    maxTokens = 30,
                                ).onSuccess {
                                    message = "连接成功：${it.text}"
                                    error = false
                                }.onFailure {
                                    message = it.message ?: "连接测试失败"
                                    error = true
                                }
                                testing = false
                            }
                        },
                        enabled = !testing && enabled && apiKey.isNotBlank() && model.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (testing) "正在测试…" else "测试连接") }
                    if (message.isNotBlank()) {
                        Surface(
                            color = if (error) Color(0xFFF7E6E2) else Color(0xFFE7F0E6),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(message, Modifier.fillMaxWidth().padding(12.dp))
                        }
                    }
                }
            }
            item {
                SettingsCardBox {
                    Row {
                        Icon(Icons.Outlined.Security, null, tint = SettingsMuted)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("密钥与隐私", fontWeight = FontWeight.Bold)
                            Text("API 密钥仅保存在本机 SharedPreferences，不写入仓库、日志、游戏回放或记忆内容。", color = SettingsMuted)
                        }
                    }
                }
            }
            item {
                SettingsCardBox {
                    Text("聊天", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("自动生成对话建议：关闭。相关入口和生成线路不会在新项目中恢复。", color = SettingsMuted)
                }
            }
            item {
                SettingsCardBox {
                    Text("记忆", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("总结阈值与最近消息排除数量在记忆 App 中设置；角色游戏和考研陪伴会读取已启用的记忆、辞海和世界书。", color = SettingsMuted)
                }
            }
            item {
                SettingsCardBox {
                    Text("通知与主动联系", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("主动联系、夜间勿扰、主动来电和来电时间段在角色设置中管理，不设每天最多三次的系统硬上限。", color = SettingsMuted)
                }
            }
            item {
                SettingsCardBox {
                    Text("数据与性能", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("模型 Token、报错、学习时长、聊天时长和通话时长统一进入性能监测。学习与游戏状态使用独立本地存档。", color = SettingsMuted)
                }
            }
        }
    }
}

private fun ModelProviderKind.label(): String = when (this) {
    ModelProviderKind.OpenAICompatible -> "OpenAI 兼容"
    ModelProviderKind.Anthropic -> "Anthropic"
    ModelProviderKind.Gemini -> "Gemini"
}

@Composable
private fun SettingsCardBox(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SettingsCard),
        border = BorderStroke(1.dp, SettingsBorder),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            content = content,
        )
    }
}
