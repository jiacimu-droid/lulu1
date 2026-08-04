package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.ApiConfiguration
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelArchive
import kotlinx.coroutines.launch

private val SettingsPaper = Color(0xFFFFFFFF)
private val SettingsCard = Color(0xFFFCFCFC)
private val SettingsBorder = Color(0xFFE7E7E7)
private val SettingsMuted = Color(0xFF7A7A7E)
private val SettingsInk = Color(0xFF1D1D1F)
private val SettingsAccent = Color(0xFFF4F4F4)
private val SettingsAccentStrong = Color(0xFF292929)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuluSettingsScreen(onBack: () -> Unit) {
    ApiConfigurationEditor(onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiConfigurationEditor(onBack: () -> Unit) {
    val store = LuluAiServices.connectionStore
    val library by store.library.collectAsState()
    val scope = rememberCoroutineScope()

    val initialConfiguration = remember(library.configurations, library.activeArchiveId) {
        val activeConfigurationId = library.archives
            .firstOrNull { it.id == library.activeArchiveId }
            ?.configurationId
        library.configurations.firstOrNull { it.id == activeConfigurationId }
            ?: library.configurations.firstOrNull()
    }

    var editingId by remember { mutableStateOf(initialConfiguration?.id) }
    var configurationName by remember { mutableStateOf(initialConfiguration?.name.orEmpty()) }
    var baseUrl by remember { mutableStateOf(initialConfiguration?.baseUrl.orEmpty()) }
    var apiKey by remember { mutableStateOf(initialConfiguration?.apiKey.orEmpty()) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedModels by remember { mutableStateOf<Set<String>>(emptySet()) }
    var modelQuery by remember { mutableStateOf("") }
    var loadingModels by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var noticeIsError by remember { mutableStateOf(false) }

    val visibleModels = remember(models, modelQuery) {
        val query = modelQuery.trim()
        if (query.isBlank()) models else models.filter { it.contains(query, ignoreCase = true) }
    }

    fun loadConfiguration(configuration: ApiConfiguration) {
        editingId = configuration.id
        configurationName = configuration.name
        baseUrl = configuration.baseUrl
        apiKey = configuration.apiKey
        models = emptyList()
        selectedModels = emptySet()
        modelQuery = ""
        notice = "已载入“${configuration.name}”"
        noticeIsError = false
    }

    fun clearEditor() {
        editingId = null
        configurationName = ""
        baseUrl = ""
        apiKey = ""
        models = emptyList()
        selectedModels = emptySet()
        modelQuery = ""
        notice = "已新建空白配置"
        noticeIsError = false
    }

    fun saveCurrent(): ApiConfiguration? = runCatching {
        store.saveConfiguration(
            id = editingId,
            name = configurationName,
            baseUrl = baseUrl,
            apiKey = apiKey,
        )
    }.onSuccess { configuration ->
        editingId = configuration.id
        configurationName = configuration.name
        baseUrl = configuration.baseUrl
        apiKey = configuration.apiKey
        notice = "配置“${configuration.name}”已保存"
        noticeIsError = false
    }.onFailure { error ->
        notice = error.message ?: "保存配置失败"
        noticeIsError = true
    }.getOrNull()

    Scaffold(
        containerColor = SettingsPaper,
        topBar = {
            TopAppBar(
                title = { Text("API 设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SettingsPaper),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsCardBox {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (editingId == null) "新配置" else "编辑配置", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = SettingsInk)
                            Text("配置名称、地址和密钥会作为一组保存", color = SettingsMuted, fontSize = 12.sp)
                        }
                        TextButton(onClick = ::clearEditor) {
                            Icon(Icons.Outlined.Add, null)
                            Spacer(Modifier.width(4.dp))
                            Text("新建")
                        }
                    }
                    OutlinedTextField(value = configurationName, onValueChange = { configurationName = it }, label = { Text("配置名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("API 地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API 密钥") }, leadingIcon = { Icon(Icons.Outlined.Key, null) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(onClick = { saveCurrent() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = SettingsAccent, contentColor = SettingsInk)) {
                        Icon(Icons.Outlined.Save, null)
                        Spacer(Modifier.width(7.dp))
                        Text("保存配置", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            loadingModels = true
                            notice = ""
                            scope.launch {
                                LuluAiServices.gateway.fetchModels(baseUrl, apiKey)
                                    .onSuccess { fetched ->
                                        models = fetched.distinct()
                                        selectedModels = emptySet()
                                        modelQuery = ""
                                        notice = "已获取 ${fetched.size} 个模型"
                                        noticeIsError = false
                                    }
                                    .onFailure { error ->
                                        models = emptyList()
                                        selectedModels = emptySet()
                                        notice = error.message ?: "获取模型失败"
                                        noticeIsError = true
                                    }
                                loadingModels = false
                            }
                        },
                        enabled = !loadingModels && baseUrl.isNotBlank() && apiKey.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (loadingModels) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.CloudDownload, null)
                        Spacer(Modifier.width(7.dp))
                        Text(if (loadingModels) "正在获取模型…" else "获取模型")
                    }
                }
            }
            if (models.isNotEmpty()) {
                item {
                    SettingsCardBox {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("选择模型", fontWeight = FontWeight.Bold, color = SettingsInk)
                                Text("已选择 ${selectedModels.size} 个", color = SettingsMuted, fontSize = 12.sp)
                            }
                            TextButton(onClick = {
                                selectedModels = if (selectedModels.size == visibleModels.size && visibleModels.isNotEmpty()) selectedModels - visibleModels.toSet() else selectedModels + visibleModels.toSet()
                            }) { Text(if (visibleModels.isNotEmpty() && visibleModels.all { it in selectedModels }) "取消当前结果" else "全选当前结果") }
                        }
                        OutlinedTextField(
                            value = modelQuery,
                            onValueChange = { modelQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Outlined.Search, null) },
                            trailingIcon = { if (modelQuery.isNotBlank()) IconButton(onClick = { modelQuery = "" }) { Icon(Icons.Outlined.Close, "清空搜索") } },
                            label = { Text("搜索模型") },
                            placeholder = { Text("输入模型名称的一部分") },
                        )
                        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color.White.copy(alpha = 0.7f), border = BorderStroke(1.dp, SettingsBorder)) {
                            if (visibleModels.isEmpty()) {
                                Text("没有匹配的模型", modifier = Modifier.padding(16.dp), color = SettingsMuted)
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp), contentPadding = PaddingValues(vertical = 5.dp)) {
                                    items(visibleModels, key = { it }) { model ->
                                        val checked = model in selectedModels
                                        Row(
                                            modifier = Modifier.fillMaxWidth().clickable { selectedModels = if (checked) selectedModels - model else selectedModels + model }.padding(horizontal = 12.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Checkbox(checked = checked, onCheckedChange = { selectedModels = if (checked) selectedModels - model else selectedModels + model })
                                            Spacer(Modifier.width(8.dp))
                                            Text(model, modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                        Button(
                            onClick = {
                                val configuration = saveCurrent() ?: return@Button
                                val existing = library.archives.filter { it.configurationId == configuration.id }.mapTo(mutableSetOf()) { it.model }
                                val toAdd = selectedModels.filterNot { it in existing }
                                runCatching { toAdd.forEach { model -> store.addArchive(configurationId = configuration.id, model = model) } }
                                    .onSuccess {
                                        val skipped = selectedModels.size - toAdd.size
                                        notice = buildString { append("已加入 ${toAdd.size} 个模型存档"); if (skipped > 0) append("，跳过 $skipped 个重复项") }
                                        noticeIsError = false
                                    }
                                    .onFailure { error -> notice = error.message ?: "批量加入存档失败"; noticeIsError = true }
                            },
                            enabled = selectedModels.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = SettingsAccentStrong, contentColor = Color.White),
                        ) {
                            Icon(Icons.Outlined.Inventory2, null)
                            Spacer(Modifier.width(7.dp))
                            Text("加入存档（${selectedModels.size}）", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            if (library.configurations.isNotEmpty()) {
                item { Text("已配置模型", color = SettingsInk, fontWeight = FontWeight.Bold, fontSize = 17.sp) }
                items(library.configurations, key = { it.id }) { configuration ->
                    SavedConfigurationRow(
                        configuration = configuration,
                        selected = configuration.id == editingId,
                        onClick = { loadConfiguration(configuration) },
                        onDelete = { store.deleteConfiguration(configuration.id); if (editingId == configuration.id) clearEditor() },
                    )
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("模型存档", color = SettingsInk, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text("点按存档即可切换当前聊天模型", color = SettingsMuted, fontSize = 12.sp)
                }
            }
            if (library.archives.isEmpty()) {
                item {
                    SettingsCardBox {
                        Text("还没有模型存档", fontWeight = FontWeight.Bold)
                        Text("在上方获取模型并加入存档后，会显示在这里。", color = SettingsMuted)
                    }
                }
            } else {
                items(library.archives, key = { "archive-${it.id}" }) { archive ->
                    ModelArchiveRow(
                        archive = archive,
                        label = store.archiveLabel(archive),
                        selected = archive.id == library.activeArchiveId,
                        onSelect = { store.selectArchive(archive.id) },
                        onDelete = { store.removeArchive(archive.id) },
                    )
                }
            }
            if (notice.isNotBlank()) {
                item {
                    Surface(color = if (noticeIsError) Color(0xFFF6E7E4) else Color(0xFFE7F0ED), shape = RoundedCornerShape(14.dp)) {
                        Text(notice, modifier = Modifier.fillMaxWidth().padding(12.dp), color = SettingsInk)
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedConfigurationRow(configuration: ApiConfiguration, selected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) SettingsAccent else SettingsCard,
        border = BorderStroke(1.dp, if (selected) SettingsAccentStrong.copy(alpha = 0.45f) else SettingsBorder),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 15.dp, top = 12.dp, bottom = 12.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Api, null, tint = SettingsAccentStrong)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(configuration.name, fontWeight = FontWeight.Bold, color = SettingsInk)
                Text(configuration.baseUrl, color = SettingsMuted, fontSize = 12.sp, maxLines = 1)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "删除配置", tint = SettingsMuted) }
        }
    }
}

@Composable
private fun ModelArchiveRow(archive: ModelArchive, label: String, selected: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) SettingsAccent else SettingsCard,
        border = BorderStroke(1.dp, if (selected) SettingsAccentStrong.copy(alpha = 0.55f) else SettingsBorder),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect)
            Spacer(Modifier.width(5.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.Bold, color = SettingsInk)
                Text(if (selected) "当前聊天模型" else "点按设为当前模型", color = SettingsMuted, fontSize = 12.sp)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Close, "移出存档", tint = SettingsMuted) }
        }
    }
}

@Composable
private fun SettingsCardBox(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SettingsCard), border = BorderStroke(1.dp, SettingsBorder), shape = RoundedCornerShape(22.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
