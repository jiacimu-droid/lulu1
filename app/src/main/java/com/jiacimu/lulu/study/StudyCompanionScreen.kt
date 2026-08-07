package com.jiacimu.lulu.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.LuluProfileAvatar
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

@Composable
internal fun StudyCompanionScreen(state: StudyState, store: PostgraduateExamStore) {
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val selected = characters[state.profile.selectedCharacterId]
        ?: characters.values.firstOrNull()
        ?: MigratedDomainStores.characters.get("lulu")
    val scope = rememberCoroutineScope()
    var selectorExpanded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var sleepText by remember { mutableStateOf("23:30") }
    var wakeText by remember { mutableStateOf("07:30") }
    var durationText by remember { mutableStateOf("7.5") }
    var judgingSleep by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            StudyCard {
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectorExpanded = true }
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LuluProfileAvatar(
                            imageUri = selected.avatarUri,
                            fallback = selected.displayName.take(1).ifBlank { "角" },
                            size = 58,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                selected.displayName.ifBlank { "未命名角色" },
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text("点击选择学习陪同角色", color = StudyDesign.muted, fontSize = 12.sp)
                        }
                        Icon(Icons.Outlined.ExpandMore, "选择角色", tint = StudyDesign.muted)
                    }
                    DropdownMenu(
                        expanded = selectorExpanded,
                        onDismissRequest = { selectorExpanded = false },
                        modifier = Modifier.widthIn(min = 220.dp),
                    ) {
                        characters.values.sortedBy { it.displayName }.forEach { character ->
                            DropdownMenuItem(
                                text = { Text(character.displayName.ifBlank { "未命名" }) },
                                leadingIcon = {
                                    LuluProfileAvatar(
                                        imageUri = character.avatarUri,
                                        fallback = character.displayName.take(1).ifBlank { "角" },
                                        size = 36,
                                    )
                                },
                                trailingIcon = {
                                    if (character.characterId == selected.characterId) {
                                        Text("当前", color = StudyDesign.muted, fontSize = 11.sp)
                                    }
                                },
                                onClick = {
                                    store.selectCharacter(character.characterId)
                                    selectorExpanded = false
                                },
                            )
                        }
                    }
                }
                Button(
                    onClick = {
                        message = store.signIn()
                        error = false
                    },
                    enabled = state.profile.lastSignInDate != LocalDate.now().toString(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.profile.lastSignInDate == LocalDate.now().toString()) {
                            "今日已签到"
                        } else {
                            "每日签到"
                        },
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StudyMetric("累计夸夸值", state.profile.totalPraiseEarned.toString(), Modifier.weight(1f))
                StudyMetric("可用夸夸值", state.profile.praisePoints.toString(), Modifier.weight(1f))
                StudyMetric("连续签到", "${state.profile.streakDays}天", Modifier.weight(1f))
            }
        }
        item {
            StudyCard {
                Text("早睡与早起奖励", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("早睡、早起分别通过，各得1张十连券。", color = StudyDesign.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sleepText,
                        onValueChange = { sleepText = it.take(5) },
                        label = { Text("入睡 HH:mm") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = wakeText,
                        onValueChange = { wakeText = it.take(5) },
                        label = { Text("起床 HH:mm") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = durationText,
                    onValueChange = {
                        durationText = it.filter { char -> char.isDigit() || char == '.' }.take(4)
                    },
                    label = { Text("实际睡眠小时") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Button(
                    onClick = {
                        val sleep = runCatching { LocalTime.parse(sleepText) }.getOrNull()
                        val wake = runCatching { LocalTime.parse(wakeText) }.getOrNull()
                        val duration = durationText.toDoubleOrNull()
                        if (sleep == null || wake == null || duration == null) {
                            message = "请按 HH:mm 填写时间，并填写实际睡眠小时"
                            error = true
                        } else {
                            judgingSleep = true
                            scope.launch {
                                store.evaluateSleepReward(sleep, wake, duration)
                                    .onSuccess {
                                        message = it
                                        error = false
                                    }
                                    .onFailure {
                                        message = it.message ?: "角色判断失败"
                                        error = true
                                    }
                                judgingSleep = false
                            }
                        }
                    },
                    enabled = !judgingSleep &&
                        state.profile.sleepRewardDate != LocalDate.now().toString(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            state.profile.sleepRewardDate == LocalDate.now().toString() -> "今日已领取"
                            judgingSleep -> "角色正在判断…"
                            else -> "提交今日作息"
                        },
                    )
                }
            }
        }
        item { StudyMessage(message, error) }
        item { Text("最近学习事件", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        val visibleEvents = state.events.filterNot { it.title.contains("抽") }.take(6)
        if (visibleEvents.isEmpty()) {
            item { StudyCard { Text("还没有事件", color = StudyDesign.muted) } }
        } else {
            item {
                StudyCard {
                    visibleEvents.forEachIndexed { index, event ->
                        Text(studyEventDisplayText(event))
                        if (index != visibleEvents.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 10.dp),
                                color = StudyDesign.border,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun studyEventDisplayText(event: StudyEvent): String {
    val detail = event.detail.trim()
    if (detail.isBlank()) return event.title.ifBlank { "学习记录" }
    val looksInternal = detail.contains("StudyTask(") ||
        listOf("id=", "completed=", "source=", "rewarded=").count(detail::contains) >= 2
    if (!looksInternal) return detail

    val taskTitle = Regex("title=([^,)\\n]+)")
        .find(detail)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf(String::isNotBlank)
    return when {
        !taskTitle.isNullOrBlank() && event.title.isNotBlank() -> "${event.title}：$taskTitle"
        !taskTitle.isNullOrBlank() -> taskTitle
        event.title.isNotBlank() -> event.title
        else -> "学习记录已更新"
    }
}
