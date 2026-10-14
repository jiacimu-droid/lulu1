package com.jiacimu.lulu

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.VisionModelService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuluVisionSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE) }
    var baseUrl by remember { mutableStateOf(prefs.getString(VisionModelService.KEY_BASE_URL, "").orEmpty()) }
    var apiKey by remember { mutableStateOf(prefs.getString(VisionModelService.KEY_API_KEY, "").orEmpty()) }
    var model by remember { mutableStateOf(prefs.getString(VisionModelService.KEY_MODEL, "").orEmpty()) }
    var customExpanded by remember { mutableStateOf(false) }
    val chatModel = runCatching {
        val store = com.jiacimu.lulu.ai.LuluAiServices.connectionStore
        store.resolveConnection(store.selectedArchiveId(com.jiacimu.lulu.ai.ModelUsage.Chat)).model
    }.getOrDefault("未选择聊天模型")

    LaunchedEffect(baseUrl, apiKey, model) {
        prefs.edit()
            .putString(VisionModelService.KEY_BASE_URL, baseUrl.trim().trimEnd('/'))
            .putString(VisionModelService.KEY_API_KEY, apiKey.trim())
            .putString(VisionModelService.KEY_MODEL, model.trim())
            .apply()
    }

    Scaffold(
        containerColor = Color.White,
        topBar = {
            TopAppBar(
                title = { Text("识图设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFFFCFCFC),
                    border = BorderStroke(1.dp, Color(0xFFE7E7E7)),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Visibility, null, tint = Color(0xFF292929))
                            Spacer(Modifier.width(9.dp))
                            Text("图片理解 · 聊天 / 小红书", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "当前聊天模型：$chatModel。小红书的真实图文阅读会在同一次模型请求中输入正文、已加载评论和最多六张实际下载成功的图片；没有设置独立识图模型时，就使用当前聊天模型。模型和API中转都必须支持图片输入，否则图片不会被标记为已理解。",
                            color = Color(0xFF77777B),
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        )
                    }
                }
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFFFCFCFC),
                    border = BorderStroke(1.dp, Color(0xFFE7E7E7)),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("专用识图模型（可选）", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(if (baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank())
                            "当前小红书图片使用独立模型：$model"
                            else "当前小红书图片跟随聊天模型：$chatModel",
                            color = Color(0xFF3C7667), fontSize = 13.sp)
                        Text(
                            "聊天识图优先用这里的模型，失败或留空时尝试聊天模型。小红书图文阅读默认用私聊模型；这里选好模型后改用它。",
                            color = Color(0xFF77777B),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                        TextButton(onClick = { customExpanded = !customExpanded }) {
                            Text(if (customExpanded) "收起独立模型设置" else "更换识图模型（需要时再配）")
                        }
                        if (customExpanded) {
                        TextButton(onClick = {
                            baseUrl = ""; apiKey = ""; model = ""
                        }) { Text("清除独立设置 · 恢复跟随聊天模型") }
                        SavedConfigurationModelPicker(
                            pickerKey = "vision-understanding",
                            currentBaseUrl = baseUrl,
                            currentModel = model,
                            onConfigurationSelected = {
                                baseUrl = it.baseUrl
                                apiKey = it.apiKey
                            },
                            onModelSelected = { model = it },
                        )
                        }
                    }
                }
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFFF7F7F8),
                    border = BorderStroke(1.dp, Color(0xFFE7E7E7)),
                ) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("什么时候才需要单独配？", fontWeight = FontWeight.Bold, color = Color(0xFF1D1D1F))
                        Text(
                            "私聊模型支持图文输入时，无需另配。若不支持，可在这里选择支持视觉的模型。",
                            color = Color(0xFF77777B),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        }
    }
}
