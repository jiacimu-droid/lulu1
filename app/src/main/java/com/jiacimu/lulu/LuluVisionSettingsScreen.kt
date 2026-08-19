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
                            Text("图片理解", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "聊天和朋友圈发图时，露露机会先理解图片，再把结果作为角色内部上下文。这里的专用识图模型是可选的：不单独配置时，会自动尝试当前聊天模型；只要聊天模型本身支持图片输入，就不需要再配第二个模型。",
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
                        Text(
                            "如果这里选了模型，会优先使用它；它失败时还会再尝试当前聊天模型。留空则直接跟随聊天模型。",
                            color = Color(0xFF77777B),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
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
                            "只有当前聊天模型不支持视觉输入、兼容接口不接受图片，或者你想用一个更便宜/更擅长看图的模型时，才需要在这里单独选择支持图片理解的模型。",
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
