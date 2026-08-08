package com.jiacimu.lulu.study

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun StudyAchievementsScreenV2(state: StudyState, store: PostgraduateExamStore) {
    var message by remember { mutableStateOf("") }
    val unlocked = state.achievements.count { it.unlocked }
    val ordered = remember(state.achievements) {
        state.achievements.sortedWith(
            compareBy<StudyAchievement> {
                when {
                    it.unlocked && !it.claimed -> 0 // 可领取：最上面
                    !it.unlocked -> 1              // 进行中：中间
                    else -> 2                      // 已领取：最下面
                }
            }.thenByDescending { achievement ->
                if (achievement.target <= 0) 0f
                else achievement.progress.toFloat() / achievement.target.toFloat()
            }.thenBy { it.target },
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StudyCard {
                Text("成就", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("已解锁 $unlocked/${state.achievements.size}", color = StudyDesign.muted)
                StudyProgress(if (state.achievements.isEmpty()) 0f else unlocked.toFloat() / state.achievements.size)
            }
        }
        items(ordered, key = StudyAchievement::id) { achievement ->
            StudyCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.EmojiEvents,
                        null,
                        tint = if (achievement.unlocked) StudyDesign.wheat else StudyDesign.muted,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(achievement.title, fontWeight = FontWeight.Bold)
                        Text(achievement.description, color = StudyDesign.muted)
                    }
                    Text("${achievement.progress.coerceAtMost(achievement.target)}/${achievement.target}")
                }
                StudyProgress(achievement.progress.toFloat() / achievement.target.coerceAtLeast(1))
                Button(
                    onClick = { message = store.claimAchievement(achievement.id) },
                    enabled = achievement.unlocked && !achievement.claimed,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudyDesign.dark,
                        contentColor = StudyDesign.wheat,
                        disabledContainerColor = if (achievement.claimed) StudyDesign.darkCard else StudyDesign.wheatSoft,
                        disabledContentColor = if (achievement.claimed) StudyDesign.muted else StudyDesign.ink,
                    ),
                ) {
                    Text(
                        when {
                            achievement.claimed -> "已领取"
                            achievement.unlocked -> "领取奖励"
                            else -> "尚未解锁"
                        },
                    )
                }
            }
        }
        item { StudyMessage(message) }
    }
}
