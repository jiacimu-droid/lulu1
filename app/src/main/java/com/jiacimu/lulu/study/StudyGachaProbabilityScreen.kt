package com.jiacimu.lulu.study

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

private data class EditableGachaRule(
    val id: String,
    val title: String,
    val rarity: StudyRarity,
    val probability: String,
    val amount: String,
    val type: StudyGachaRewardType,
) {
    val custom: Boolean get() = type == StudyGachaRewardType.Custom
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StudyGachaProbabilityScreen(
    state: StudyState,
    store: PostgraduateExamStore,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var rows by remember(state.gachaRules) {
        mutableStateOf(
            repairGachaRules(state.gachaRules).map { rule ->
                EditableGachaRule(
                    id = rule.id,
                    title = rule.title,
                    rarity = rule.rarity,
                    probability = probabilityEditorText(rule.probabilityPercent),
                    amount = rule.amountPerDraw.toString(),
                    type = rule.type,
                )
            },
        )
    }
    var message by remember { mutableStateOf("") }

    val parsedProbabilities = rows.map { it.probability.replace(',', '.').toDoubleOrNull() }
    val invalidProbability = parsedProbabilities.any { it == null || !it.isFinite() || it < 0.0 || it > 100.0 }
    val invalidAmount = rows.any { it.amount.toIntOrNull() !in 1..999 }
    val invalidTitle = rows.any { it.custom && it.title.trim().isBlank() }
    val nonBlueTotal = parsedProbabilities.filterNotNull().sum()
    val blueProbability = (100.0 - nonBlueTotal).coerceAtLeast(0.0)
    val totalTooHigh = nonBlueTotal > 100.000001

    fun updateRow(id: String, transform: (EditableGachaRule) -> EditableGachaRule) {
        rows = rows.map { if (it.id == id) transform(it) else it }
        message = ""
    }

    fun addCustom(rarity: StudyRarity) {
        rows = rows + EditableGachaRule(
            id = "custom_${UUID.randomUUID()}",
            title = "",
            rarity = rarity,
            probability = "0.1",
            amount = "1",
            type = StudyGachaRewardType.Custom,
        )
        message = ""
    }

    fun save() {
        if (invalidTitle) {
            message = "还有自定义项目没有填写名称"
            return
        }
        if (invalidProbability || totalTooHigh) {
            message = if (totalTooHigh) "紫色、金色、彩色合计概率不能超过100%" else "请检查概率输入"
            return
        }
        if (invalidAmount) {
            message = "单次获得数量需要填写1—999的整数"
            return
        }
        val rules = rows.map { row ->
            StudyGachaRule(
                id = row.id,
                title = row.title.trim(),
                rarity = row.rarity,
                probabilityPercent = row.probability.replace(',', '.').toDouble(),
                amountPerDraw = row.amount.toInt(),
                type = row.type,
            )
        }
        message = store.saveGachaRules(rules)
    }

    Scaffold(
        containerColor = StudyDesign.paper,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("抽卡概率设计", fontWeight = FontWeight.Bold)
                        Text("紫色 / 金色 / 彩色", color = StudyDesign.muted, fontSize = 11.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = StudyDesign.paper),
            )
        },
        bottomBar = {
            Surface(color = StudyDesign.paper, tonalElevation = 3.dp, shadowElevation = 8.dp) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (message.isNotBlank()) {
                        Text(
                            message,
                            color = if (message.startsWith("已保存")) StudyDesign.success else StudyDesign.error,
                            fontSize = 12.sp,
                        )
                    }
                    Button(
                        onClick = ::save,
                        enabled = !invalidProbability && !invalidAmount && !invalidTitle && !totalTooHigh,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = StudyDesign.dark, contentColor = Color.White),
                    ) {
                        Text("保存概率设计", fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                StudyCard {
                    Text("概率总览", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProbabilityMetric("蓝色剩余", blueProbability, Modifier.weight(1f))
                        ProbabilityMetric("非蓝色", nonBlueTotal, Modifier.weight(1f))
                    }
                    Text(
                        "蓝色画卷不单独编辑，会自动使用100%减去紫色、金色、彩色后的剩余概率。默认：抖音2.5%、游戏2%、小剧场1%（一次3张）、电影0.8%、彩色0.4%。",
                        color = StudyDesign.muted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                    if (totalTooHigh) {
                        Text("当前合计超过100%，请先降低非蓝色概率。", color = StudyDesign.error, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            listOf(StudyRarity.Rare, StudyRarity.Epic, StudyRarity.Rainbow).forEach { rarity ->
                item(key = "header-${rarity.name}") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${rarity.label}项目", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "合计 ${probabilityEditorText(rows.filter { it.rarity == rarity }.sumOf { it.probability.replace(',', '.').toDoubleOrNull() ?: 0.0 })}%",
                                color = StudyDesign.muted,
                                fontSize = 12.sp,
                            )
                        }
                        TextButton(onClick = { addCustom(rarity) }) {
                            Icon(Icons.Outlined.Add, null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("添加项目")
                        }
                    }
                }

                val rarityRows = rows.filter { it.rarity == rarity }
                if (rarityRows.isEmpty()) {
                    item(key = "empty-${rarity.name}") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = rarityTint(rarity),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                        ) {
                            Text("还没有${rarity.label}项目，可以点右上角添加。", Modifier.padding(14.dp), color = StudyDesign.muted)
                        }
                    }
                }
                rarityRows.forEach { row ->
                    item(key = row.id) {
                        GachaRuleEditorCard(
                            row = row,
                            onTitleChange = { value -> updateRow(row.id) { it.copy(title = value.take(60)) } },
                            onProbabilityChange = { value ->
                                if (value.length <= 8 && value.all { ch -> ch.isDigit() || ch == '.' || ch == ',' }) {
                                    updateRow(row.id) { it.copy(probability = value) }
                                }
                            },
                            onAmountChange = { value ->
                                if (value.length <= 3 && value.all(Char::isDigit)) updateRow(row.id) { it.copy(amount = value) }
                            },
                            onDelete = if (row.custom) {
                                { rows = rows.filterNot { it.id == row.id }; message = "" }
                            } else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProbabilityMetric(label: String, value: Double, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = StudyDesign.wheatSoft,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, StudyDesign.border),
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${probabilityEditorText(value)}%", fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text(label, color = StudyDesign.muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun GachaRuleEditorCard(
    row: EditableGachaRule,
    onTitleChange: (String) -> Unit,
    onProbabilityChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = StudyDesign.card,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, rarityTint(row.rarity)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = rarityTint(row.rarity), shape = androidx.compose.foundation.shape.RoundedCornerShape(99.dp)) {
                    Text(row.rarity.label, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
                Text(if (row.custom) "自定义项目" else "内置项目", color = StudyDesign.muted, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                onDelete?.let {
                    IconButton(onClick = it, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Outlined.DeleteOutline, "删除项目", tint = StudyDesign.error)
                    }
                }
            }

            if (row.custom) {
                OutlinedTextField(
                    value = row.title,
                    onValueChange = onTitleChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("项目名称") },
                    placeholder = { Text("例如：旅行券、奶茶券……") },
                    singleLine = true,
                )
            } else {
                Text(row.title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = row.probability,
                    onValueChange = onProbabilityChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("概率 %") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    suffix = { Text("%") },
                )
                OutlinedTextField(
                    value = row.amount,
                    onValueChange = onAmountChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("单次获得") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    suffix = { Text("份") },
                )
            }
            if (row.type == StudyGachaRewardType.Theater) {
                Text("当前默认一次抽中直接发3张小剧场券。", color = StudyDesign.muted, fontSize = 11.sp)
            }
        }
    }
}

private fun rarityTint(rarity: StudyRarity): Color = when (rarity) {
    StudyRarity.Normal -> Color(0xFFDCEAF4)
    StudyRarity.Rare -> Color(0xFFE8DDF2)
    StudyRarity.Epic -> Color(0xFFFFEDB8)
    StudyRarity.Rainbow -> Color(0xFFD8F3EF)
}

private fun probabilityEditorText(value: Double): String {
    val rounded = kotlin.math.round(value * 1000.0) / 1000.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString().trimEnd('0').trimEnd('.')
}