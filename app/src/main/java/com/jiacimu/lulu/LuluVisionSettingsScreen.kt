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
                            Text("朋友圈图片理解", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "发朋友圈图片时先由这个模型看图，识别结果只作为角色理解图片的内部上下文；不会把识图描述直接显示在朋友圈正文里。",
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
                        Text("识图模型", fontSize = 17.sp, fontWeight = FontWeight.Bold)
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
                        Text("推荐：Gemini 3.6 Flash", fontWeight = FontWeight.Bold, color = Color(0xFF1D1D1F))
                        Text(
                            "优先推荐稳定版 gemini-3.6-flash：多模态与空间理解强，适合照片、截图、文字、场景和复杂画面的统一识别。想尝试更重推理时也可以选 Gemini 3.1 Pro Preview。",
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
