package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.ApiConfiguration
import com.jiacimu.lulu.ai.LuluAiServices
import kotlinx.coroutines.launch

private val PickerBorder = Color(0xFFE7E7E7)
private val PickerField = Color(0xFFF4F4F4)
private val PickerInk = Color(0xFF1D1D1F)
private val PickerMuted = Color(0xFF7A7A7E)

@Composable
internal fun SavedConfigurationModelPicker(
    pickerKey: String,
    currentBaseUrl: String,
    currentModel: String,
    onConfigurationSelected: (ApiConfiguration) -> Unit,
    onModelSelected: (String) -> Unit,
) {
    val store = LuluAiServices.connectionStore
    val library by store.library.collectAsState()
    val scope = rememberCoroutineScope()
    val initialId = remember(library.configurations, currentBaseUrl) {
        library.configurations.firstOrNull { it.baseUrl.trimEnd('/') == currentBaseUrl.trimEnd('/') }?.id
            ?: library.configurations.firstOrNull()?.id
    }
    var configurationId by rememberSaveable(pickerKey) { mutableStateOf(initialId) }
    var configurationMenu by remember { mutableStateOf(false) }
    var models by remember(pickerKey) { mutableStateOf(emptyList<String>()) }
    var query by rememberSaveable("$pickerKey-query") { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }

    val configuration = library.configurations.firstOrNull { it.id == configurationId }
    val visibleModels = remember(models, query) {
        val clean = query.trim()
        (if (clean.isBlank()) models else models.filter { it.contains(clean, ignoreCase = true) }).take(120)
    }
    val recommendations = remember(pickerKey) {
        when {
            pickerKey.contains("embedding") -> listOf(
                "BAAI/bge-m3" to "首选 · 中文记忆够用",
                "Qwen/Qwen3-Embedding-0.6B" to "轻量省钱",
                "Qwen/Qwen3-Embedding-4B" to "效果更强",
            )
            pickerKey.contains("rerank") -> listOf(
                "BAAI/bge-reranker-v2-m3" to "首选 · 稳定省心",
                "Qwen/Qwen3-Reranker-0.6B" to "轻量省钱",
                "Qwen/Qwen3-Reranker-4B" to "效果更强",
            )
            else -> emptyList()
        }
    }

    LaunchedEffect(initialId, library.archives) {
        if (configurationId == null || library.configurations.none { it.id == configurationId }) {
            configurationId = initialId
        }
        val selected = library.configurations.firstOrNull { it.id == configurationId } ?: return@LaunchedEffect
        onConfigurationSelected(selected)
        val archived = library.archives.filter { it.configurationId == selected.id }.map { it.model }
        models = (listOf(currentModel) + archived).filter(String::isNotBlank).distinct().sorted()
    }

    if (library.configurations.isEmpty()) {
        Text("请先到 API 设置保存一个配置", color = PickerMuted, fontSize = 13.sp)
        return
    }

    Text("已保存配置", color = PickerMuted, fontSize = 12.sp)
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { configurationMenu = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 13.dp),
        ) {
            Text(configuration?.name ?: "选择配置", Modifier.weight(1f), color = PickerInk)
            Icon(Icons.Outlined.ArrowDropDown, null)
        }
        DropdownMenu(
            expanded = configurationMenu,
            onDismissRequest = { configurationMenu = false },
        ) {
            library.configurations.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(item.name, fontWeight = FontWeight.SemiBold)
                            Text(item.baseUrl, color = PickerMuted, fontSize = 11.sp, maxLines = 1)
                        }
                    },
                    onClick = {
                        configurationId = item.id
                        configurationMenu = false
                        query = ""
                        notice = ""
                        onConfigurationSelected(item)
                        val archived = library.archives.filter { it.configurationId == item.id }.map { it.model }
                        models = archived.distinct().sorted()
                        if (currentModel !in models) onModelSelected("")
                    },
                )
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(
        onClick = {
            val selected = configuration ?: return@OutlinedButton
            loading = true
            notice = ""
            scope.launch {
                LuluAiServices.gateway.fetchModels(selected.baseUrl, selected.apiKey)
                    .onSuccess {
                        models = it.distinct().sorted()
                        notice = "已获取 ${models.size} 个模型"
                    }
                    .onFailure { notice = it.message ?: "获取模型失败" }
                loading = false
            }
        },
        enabled = configuration != null && !loading,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
        else Icon(Icons.Outlined.CloudDownload, null, Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(if (loading) "正在获取模型…" else "获取模型列表")
    }
    if (recommendations.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text("露露推荐", color = PickerMuted, fontSize = 12.sp)
        recommendations.forEach { (model, description) ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable {
                    query = model
                    onModelSelected(model)
                }.padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = currentModel == model, onClick = { query = model; onModelSelected(model) })
                Column(Modifier.weight(1f)) {
                    Text(model, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(description, color = PickerMuted, fontSize = 11.sp)
                }
            }
        }
    }
    if (models.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("搜索模型") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
            color = PickerField,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, PickerBorder),
        ) {
            if (visibleModels.isEmpty()) {
                Text("没有匹配的模型", Modifier.padding(14.dp), color = PickerMuted)
            } else {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                    visibleModels.forEach { model ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onModelSelected(model) }.padding(horizontal = 9.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = model == currentModel, onClick = { onModelSelected(model) })
                            Text(model, Modifier.weight(1f), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    } else {
        Spacer(Modifier.height(8.dp))
        Text("这个配置还没有模型列表，点上方按钮获取。", color = PickerMuted, fontSize = 12.sp)
    }
    if (notice.isNotBlank()) {
        Spacer(Modifier.height(7.dp))
        Text(notice, color = if (notice.contains("失败")) MaterialTheme.colorScheme.error else PickerMuted, fontSize = 12.sp)
    }
    if (currentModel.isNotBlank()) {
        Spacer(Modifier.height(7.dp))
        Surface(color = Color.White, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, PickerBorder)) {
            Text("已选择：$currentModel", Modifier.fillMaxWidth().padding(11.dp), color = PickerInk, fontSize = 12.sp)
        }
    }
}
