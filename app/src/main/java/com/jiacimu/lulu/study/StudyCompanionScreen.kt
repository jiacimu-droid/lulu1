package com.jiacimu.lulu.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.LuluProfileAvatar
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.health.GadgetbridgeHealthStore
import com.jiacimu.lulu.health.HealthRolePerception
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StudyCompanionScreen(state: StudyState, store: PostgraduateExamStore) {
    val context = LocalContext.current
    remember(context) {
        HealthRolePerception.initialize(context)
        Unit
    }
    val healthState by GadgetbridgeHealthStore.state.collectAsState()
    val sleepObservation = remember(healthState.days, healthState.lastImportedAt) { HealthRolePerception.latestSleep() }
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val selected = characters[state.profile.selectedCharacterId]
        ?: characters.values.firstOrNull()
        ?: MigratedDomainStores.characters.get("lulu")
    val scope = rememberCoroutineScope()
    var selectorExpanded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var judgingSleep by remember { mutableStateOf(false) }
    var refreshedHealth by remember { mutableStateOf(false) }

    LaunchedEffect(healthState.connected, refreshedHealth) {
        if (healthState.connected && !refreshedHealth) {
            refreshedHealth = true
            GadgetbridgeHealthStore.refresh(context)
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            StudyCard {
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
                            color = StudyDesign.ink,
                        )
                        Text("学习陪同角色", color = StudyDesign.muted, fontSize = 12.sp)
                    }
                    Icon(Icons.Outlined.ExpandMore, "选择角色", tint = StudyDesign.ink)
                }
                Button(
                    onClick = {
                        message = store.signIn()
                        error = false
                    },
                    enabled = state.profile.lastSignInDate != LocalDate.now().toString(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudyDesign.wheat,
                        contentColor = StudyDesign.ink,
                        disabledContainerColor = Color(0xFFF0EEE8),
                        disabledContentColor = StudyDesign.muted,
                    ),
                ) {
                    Text(
                        if (state.profile.lastSignInDate == LocalDate.now().toString()) "今日已签到" else "每日签到",
                        fontWeight = FontWeight.Bold,
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
                Text("早睡与早起奖励", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = StudyDesign.ink)
                Text("早睡、早起分别通过，各得1张十连券。", color = StudyDesign.muted)
                if (sleepObservation == null) {
                    Text(
                        if (healthState.connected) "健康 App 暂时没有可用睡眠记录" else "请先在健康 App 连接并同步手环数据",
                        color = StudyDesign.muted,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StudyMetric("实际入睡", sleepObservation.clock(sleepObservation.sleepStart), Modifier.weight(1f))
                        StudyMetric("实际起床", sleepObservation.clock(sleepObservation.wakeTime), Modifier.weight(1f))
                        StudyMetric("实际睡眠", sleepObservation.durationLabel(), Modifier.weight(1f))
                    }
                    Text("健康 App · ${sleepObservation.date}", color = StudyDesign.muted, fontSize = 12.sp)
                }
                Button(
                    onClick = {
                        val detected = sleepObservation
                        if (detected == null) {
                            message = "健康 App 还没有同步到睡眠记录"
                            error = true
                        } else {
                            judgingSleep = true
                            scope.launch {
                                store.evaluateSleepReward(detected)
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
                    enabled = !judgingSleep && sleepObservation != null &&
                        state.profile.sleepRewardDate != sleepObservation.date.toString(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudyDesign.wheat,
                        contentColor = StudyDesign.ink,
                        disabledContainerColor = Color(0xFFF0EEE8),
                        disabledContentColor = StudyDesign.muted,
                    ),
                ) {
                    Text(
                        when {
                            sleepObservation != null && state.profile.sleepRewardDate == sleepObservation.date.toString() ->
                                "本次已判断 · 可私聊协商"
                            judgingSleep -> "角色正在判断…"
                            else -> "让角色检测本次作息"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        item { StudyMessage(message, error) }
        item { Text("最近学习事件", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = StudyDesign.ink) }
        val visibleEvents = state.events.filterNot { it.title.contains("抽") }.take(6)
        if (visibleEvents.isEmpty()) {
            item { StudyCard { Text("还没有事件", color = StudyDesign.muted) } }
        } else {
            item {
                StudyCard {
                    visibleEvents.forEachIndexed { index, event ->
                        Text(studyEventDisplayText(event), color = StudyDesign.ink)
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

    if (selectorExpanded) {
        ModalBottomSheet(
            onDismissRequest = { selectorExpanded = false },
            containerColor = Color.White,
            contentColor = StudyDesign.ink,
            dragHandle = { BottomSheetDefaults.DragHandle(color = StudyDesign.muted) },
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("选择学习陪同角色", fontSize = 20.sp, fontWeight = FontWeight.Black, color = StudyDesign.ink)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 20.dp),
                ) {
                    items(characters.values.sortedBy { it.displayName }, key = { it.characterId }) { character ->
                        val isSelected = character.characterId == selected.characterId
                        Surface(
                            onClick = {
                                store.selectCharacter(character.characterId)
                                selectorExpanded = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = if (isSelected) StudyDesign.wheatSoft else Color.White,
                            contentColor = StudyDesign.ink,
                            border = BorderStroke(1.5.dp, if (isSelected) StudyDesign.wheat else StudyDesign.border),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LuluProfileAvatar(
                                    imageUri = character.avatarUri,
                                    fallback = character.displayName.take(1).ifBlank { "角" },
                                    size = 42,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    character.displayName.ifBlank { "未命名" },
                                    modifier = Modifier.weight(1f),
                                    fontWeight = FontWeight.Bold,
                                )
                                if (isSelected) Icon(Icons.Outlined.Check, "当前角色", tint = StudyDesign.ink)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun studyOutlinedFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = StudyDesign.wheat,
    unfocusedBorderColor = StudyDesign.border,
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color.White,
)

private fun studyEventDisplayText(event: StudyEvent): String {
    val detail = event.detail.trim()
    if (detail.isBlank()) return event.title.ifBlank { "学习记录" }
    val looksInternal = detail.contains("StudyTask(") ||
        listOf("id=", "completed=", "source=", "rewarded=").count(detail::contains) >= 2
    if (!looksInternal) return if (event.title.isNotBlank()) "${event.title}：$detail" else detail

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
