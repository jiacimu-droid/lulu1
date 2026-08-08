package com.jiacimu.lulu.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
                    it.unlocked && !it.claimed -> 0
                    !it.unlocked -> 1
                    else -> 2
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
                Text("成就", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = StudyDesign.ink)
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
                        Text(achievement.title, fontWeight = FontWeight.Bold, color = StudyDesign.ink)
                        Text(achievement.description, color = StudyDesign.muted)
                    }
                    Text("${achievement.progress.coerceAtMost(achievement.target)}/${achievement.target}", color = StudyDesign.ink)
                }
                StudyProgress(achievement.progress.toFloat() / achievement.target.coerceAtLeast(1))
                when {
                    achievement.unlocked && !achievement.claimed -> Button(
                        onClick = { message = store.claimAchievement(achievement.id) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = StudyDesign.wheat, contentColor = StudyDesign.ink),
                        shape = RoundedCornerShape(15.dp),
                    ) { Text("领取奖励", fontWeight = FontWeight.Bold) }

                    else -> Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.White,
                        shape = RoundedCornerShape(15.dp),
                        border = BorderStroke(1.dp, if (achievement.claimed) StudyDesign.border else StudyDesign.wheat),
                    ) {
                        Text(
                            if (achievement.claimed) "已领取" else "尚未解锁",
                            modifier = Modifier.padding(vertical = 11.dp),
                            color = StudyDesign.muted,
                            fontWeight = FontWeight.Medium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
        item { StudyMessage(message) }
    }
}
