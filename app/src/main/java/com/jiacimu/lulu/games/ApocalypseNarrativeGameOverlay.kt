package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.LuluProfileAvatar
import com.jiacimu.lulu.data.CharacterSettings

@Composable
internal fun ApocalypseNarrativeGameOverlay(
    page: ApocalypseStoryPage,
    pageIndex: Int,
    pageCount: Int,
    party: List<CharacterSettings>,
    dossiers: List<ApocalypseCharacterDossierV5>,
    userName: String,
    userAvatarUri: String?,
    lastPage: Boolean,
    autoPlay: Boolean,
    busy: Boolean,
    generationState: ApocalypseGenerationTaskManagerV5.TaskState,
    generationSeconds: Int,
    action: String,
    onActionChanged: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleAuto: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 5.dp),
        color = Color(0xEE0B1412),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .16f)),
        shadowElevation = 16.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ApocalypseSpeakerBadge(page, party, dossiers, userName, userAvatarUri)
                Spacer(Modifier.weight(1f))
                if (busy) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.8.dp, color = Color(0xFFE7D08B))
                    Spacer(Modifier.width(6.dp))
                    Text("${generationState.phase} · ${generationSeconds}s", color = Color(0xFFE7D08B), fontSize = 8.5.sp)
                } else {
                    Text("${pageIndex + 1}/${pageCount.coerceAtLeast(1)}", color = Color(0xFF91AAA2), fontSize = 8.5.sp)
                }
            }
            Spacer(Modifier.height(7.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 70.dp, max = 116.dp)
                    .clickable(enabled = !lastPage && !busy, onClick = onNext),
                color = Color.White.copy(alpha = .055f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .08f)),
            ) {
                Text(
                    page.text,
                    color = Color(0xFFF1F4F1),
                    fontSize = 13.5.sp,
                    lineHeight = 20.5.sp,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp).verticalScroll(rememberScrollState()),
                )
            }
            Row(
                Modifier.fillMaxWidth().height(34.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onPrevious, enabled = pageIndex > 0) {
                    Icon(Icons.Outlined.ChevronLeft, null, Modifier.size(16.dp))
                    Text("上一段", fontSize = 9.5.sp)
                }
                TextButton(onClick = onToggleAuto, enabled = !lastPage) {
                    Icon(if (autoPlay) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(if (autoPlay) "暂停" else "自动", fontSize = 9.5.sp)
                }
                TextButton(onClick = onNext, enabled = !lastPage) {
                    Text("下一段", fontSize = 9.5.sp)
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp))
                }
            }

            if (lastPage) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    OutlinedTextField(
                        value = action,
                        onValueChange = { onActionChanged(it.take(600)) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("自由行动，或在场景里调查目标……", fontSize = 10.5.sp) },
                        minLines = 1,
                        maxLines = 2,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFE7D08B),
                            unfocusedBorderColor = Color.White.copy(alpha = .18f),
                            cursorColor = Color(0xFFE7D08B),
                            focusedContainerColor = Color.Black.copy(alpha = .13f),
                            unfocusedContainerColor = Color.Black.copy(alpha = .10f),
                            focusedPlaceholderColor = Color(0xFF879B94),
                            unfocusedPlaceholderColor = Color(0xFF879B94),
                        ),
                    )
                    FilledIconButton(
                        onClick = onSubmit,
                        enabled = action.isNotBlank() && !busy,
                        modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFFE7D08B),
                            contentColor = Color(0xFF18201D),
                            disabledContainerColor = Color.White.copy(alpha = .10f),
                            disabledContentColor = Color.White.copy(alpha = .28f),
                        ),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.NorthEast, "执行行动", Modifier.size(20.dp))
                    }
                }
            }
            generationState.lastError?.let { error ->
                Spacer(Modifier.height(4.dp))
                Text(error, color = Color(0xFFFF938E), fontSize = 9.5.sp, lineHeight = 13.sp, maxLines = 2)
            }
        }
    }
}

@Composable
private fun ApocalypseSpeakerBadge(
    page: ApocalypseStoryPage,
    party: List<CharacterSettings>,
    dossiers: List<ApocalypseCharacterDossierV5>,
    userName: String,
    userAvatarUri: String?,
) {
    val character = page.characterId?.let { id -> party.firstOrNull { it.characterId == id } }
    val dossier = page.characterId?.let { id -> dossiers.firstOrNull { it.id == id } }
    val name = when (page.speakerKind) {
        ApocalypseStorySpeakerKind.Narrator -> "场景"
        ApocalypseStorySpeakerKind.Player -> userName.ifBlank { "我" }
        ApocalypseStorySpeakerKind.Character -> character?.displayName
            ?: dossier?.let(::apocalypseDossierDisplayNameV5)
            ?: page.speakerLabel
            ?: "未知人物"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (page.speakerKind) {
            ApocalypseStorySpeakerKind.Narrator -> {
                Surface(modifier = Modifier.size(31.dp), shape = RoundedCornerShape(10.dp), color = Color(0xFFE7D08B).copy(alpha = .14f)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.AutoStories, null, tint = Color(0xFFE7D08B), modifier = Modifier.size(17.dp)) }
                }
            }
            ApocalypseStorySpeakerKind.Player -> LuluProfileAvatar(userAvatarUri, name.take(1), 31)
            ApocalypseStorySpeakerKind.Character -> LuluProfileAvatar(character?.avatarUri ?: apocalypseNpcAvatarUriV5(page.characterId.orEmpty()), name.take(1), 31)
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(name, color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Black)
            Text(
                if (page.speakerKind == ApocalypseStorySpeakerKind.Narrator) "环境叙事" else "正在说话",
                color = Color(0xFF8CA39B),
                fontSize = 7.5.sp,
            )
        }
    }
}
