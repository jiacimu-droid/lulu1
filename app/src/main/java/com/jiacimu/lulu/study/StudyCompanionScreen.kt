package com.jiacimu.lulu.study

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var sleepText by remember { mutableStateOf("23:30") }
    var wakeText by remember { mutableStateOf("07:30") }
    var durationText by remember { mutableStateOf("7.5") }
    var judgingSleep by remember { mutableStateOf(false) }
    val level = state.profile.level
    val levelStart = StudyLevels.currentLevelStart(level)
    val nextTarget = StudyLevels.nextLevelTarget(level)
    val levelProgress =
        (state.profile.experience - levelStart).toFloat() / (nextTarget - levelStart).coerceAtLeast(1)

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            StudyCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(58.dp).background(StudyDesign.wheat, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            selected.displayName.take(1).ifBlank { "角" },
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            selected.displayName.ifBlank { "未命名角色" },
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "学习陪伴角色会读取真实任务、时长、番茄钟和奖励结果。",
                            color = StudyDesign.muted,
                        )
                    }
                }
                if (characters.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        characters.values.forEach { character ->
                            FilterChip(
                                selected = state.profile.selectedCharacterId == character.characterId,
                                onClick = { store.selectCharacter(character.characterId) },
                                label = { Text(character.displayName.ifBlank { "未命名" }) },
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
                StudyMetric("等级", "Lv.$level", Modifier.weight(1f))
                StudyMetric("连续", "${state.profile.streakDays}天", Modifier.weight(1f))
                StudyMetric("夸夸值", state.profile.praisePoints.toString(), Modifier.weight(1f))
            }
        }
        item {
            StudyCard {
                Text("等级进度", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("经验 ${state.profile.experience} · 下一级 $nextTarget", color = StudyDesign.muted)
                StudyProgress(levelProgress)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (1..level).forEach { claimLevel ->
                        AssistChip(
                            onClick = {
                                message = store.claimLevel(claimLevel)
                                error = false
                            },
                            enabled = claimLevel !in state.profile.claimedLevels,
                            label = {
                                Text(
                                    if (claimLevel in state.profile.claimedLevels) {
                                        "Lv.$claimLevel 已领"
                                    } else {
                                        "领 Lv.$claimLevel"
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
        item {
            StudyCard {
                Text("睡眠习惯奖励", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(
                    "时间只提供参考；是否奖励以及如何回应，由当前角色结合实际情况最终判断。",
                    color = StudyDesign.muted,
                )
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
                            state.profile.sleepRewardDate == LocalDate.now().toString() ->
                                "今天已经判断过"
                            judgingSleep -> "角色正在判断…"
                            else -> "交给角色判断"
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
                        Text(event.detail.ifBlank { event.title })
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
