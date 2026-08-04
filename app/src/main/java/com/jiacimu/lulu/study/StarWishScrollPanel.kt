package com.jiacimu.lulu.study

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun StarWishScrollContent(
    state: StarWishState,
    studyState: StudyState,
    store: StarWishStore,
    context: Context,
) {
    val character = MigratedDomainStores.characters.get(studyState.profile.selectedCharacterId)
    val unlocked = studyState.inventory.unlockedScrolls
    var selectedOutfit by rememberSaveable(unlocked) { mutableStateOf(unlocked.firstOrNull().orEmpty()) }
    var interaction by rememberSaveable { mutableStateOf(false) }
    val defaults = remember(selectedOutfit, character.displayName) {
        StarWishRules.defaultPrompts(selectedOutfit.ifBlank { "星愿画卷" }, character.displayName)
    }
    var soloPrompt by remember(selectedOutfit, state.customPrompts) {
        mutableStateOf(state.customPrompts[selectedOutfit]?.solo ?: defaults.solo)
    }
    var interactionPrompt by remember(selectedOutfit, state.customPrompts) {
        mutableStateOf(state.customPrompts[selectedOutfit]?.interaction ?: defaults.interaction)
    }
    val scope = rememberCoroutineScope()
    val service = remember(context) { StarWishImageService(context.applicationContext) }
    var generating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StudyCard {
                Text("星愿画卷", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("集齐同名蓝色碎片后解锁主题；图片使用当前模型存档的图片接口生成。", color = StudyDesign.muted)
                if (unlocked.isEmpty()) {
                    Text("还没有解锁画卷，先在考研抽卡中收集 10 枚同名碎片。", color = StudyDesign.error)
                } else {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        unlocked.forEach { outfit ->
                            FilterChip(selected = selectedOutfit == outfit, onClick = { selectedOutfit = outfit }, label = { Text(outfit) })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !interaction, onClick = { interaction = false }, label = { Text("角色单人") }, modifier = Modifier.weight(1f))
                        FilterChip(selected = interaction, onClick = { interaction = true }, label = { Text("与用户互动") }, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(
                        value = if (interaction) interactionPrompt else soloPrompt,
                        onValueChange = { value -> if (interaction) interactionPrompt = value else soloPrompt = value },
                        label = { Text("图片提示词") },
                        minLines = 6,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                store.savePrompts(selectedOutfit, StarWishOutfitPrompts(soloPrompt, interactionPrompt))
                                message = "提示词已保存"
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("保存提示词") }
                        Button(
                            enabled = !generating && selectedOutfit.isNotBlank(),
                            onClick = {
                                generating = true
                                message = ""
                                val prompt = if (interaction) interactionPrompt else soloPrompt
                                scope.launch {
                                    service.generate(selectedOutfit, prompt, interaction)
                                        .onSuccess { image ->
                                            store.addImage(image)
                                            message = "画卷已生成并保存在本机"
                                        }
                                        .onFailure { error -> message = error.message ?: "画卷生成失败" }
                                    generating = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            if (generating) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.AutoAwesome, null)
                            Spacer(Modifier.width(5.dp))
                            Text(if (generating) "生成中" else "生成画卷")
                        }
                    }
                }
                if (message.isNotBlank()) StudyMessage(message, error = message.contains("失败"))
            }
        }
        item { Text("生成图库", fontWeight = FontWeight.Bold, fontSize = 19.sp) }
        if (state.imageLaunches.isEmpty()) {
            item { StudyCard { Text("还没有生成图片", color = StudyDesign.muted) } }
        } else {
            items(state.imageLaunches, key = { item -> item.id }) { image ->
                StudyCard {
                    val bitmap = remember(image.filePath) { BitmapFactory.decodeFile(image.filePath)?.asImageBitmap() }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = image.outfit,
                            modifier = Modifier.fillMaxWidth().height(280.dp),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Text("图片文件已不存在", color = StudyDesign.error)
                    }
                    Text(image.outfit, fontWeight = FontWeight.Bold)
                    Text(if (image.interaction) "互动画卷" else "单人画卷", color = StudyDesign.muted, fontSize = 12.sp)
                    Text(
                        Instant.ofEpochMilli(image.createdAtMillis).atZone(ZoneId.systemDefault()).format(StarWishPanelDateFormatter),
                        color = StudyDesign.muted,
                        fontSize = 11.sp,
                    )
                    TextButton(onClick = { store.deleteImage(image.id) }) {
                        Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(5.dp))
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

private val StarWishPanelDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
