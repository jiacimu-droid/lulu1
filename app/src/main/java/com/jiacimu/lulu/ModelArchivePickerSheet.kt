package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.LuluAiServices

/**
 * One consistent archive switcher for chat, calls and game-like apps.
 *
 * The old DropdownMenu inherited whatever Material popup colors happened to be active on the caller,
 * so it looked like a foreign panel on several screens. This sheet owns its complete visual surface
 * and makes the scope of a model choice explicit before the user changes it.
 */
@Composable
internal fun ModelArchivePickerSheet(
    title: String,
    subtitle: String,
    selectedArchiveId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    accent: Color = Color(0xFF526D5E),
    background: Color = Color(0xFFF7F7F6),
    card: Color = Color.White,
    ink: Color = Color(0xFF1D211F),
    muted: Color = Color(0xFF727975),
    border: Color = Color(0xFFE0E4E1),
) {
    val library by LuluAiServices.connectionStore.library.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(.78f)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = accent.copy(alpha = .12f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        Icons.Outlined.Memory,
                        null,
                        tint = accent,
                        modifier = Modifier.padding(10.dp).size(23.dp),
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text(subtitle, color = muted, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }

            HorizontalDivider(color = border)

            if (library.archives.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = card,
                    shape = RoundedCornerShape(19.dp),
                    border = BorderStroke(1.dp, border),
                ) {
                    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CloudOff, null, tint = muted, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("还没有模型存档", color = ink, fontWeight = FontWeight.Bold)
                            Text("先到 API 设置保存配置并加入一个模型存档，再回来选择。", color = muted, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    items(library.archives, key = { it.id }) { archive ->
                        val selected = archive.id == selectedArchiveId
                        val configuration = library.configurations.firstOrNull { it.id == archive.configurationId }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onSelect(archive.id) },
                            color = if (selected) accent.copy(alpha = .10f) else card,
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, if (selected) accent.copy(alpha = .55f) else border),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                    null,
                                    tint = if (selected) accent else muted,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(Modifier.width(11.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        archive.model,
                                        color = ink,
                                        fontSize = 14.sp,
                                        fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        configuration?.name.orEmpty().ifBlank { "未命名 API 配置" },
                                        color = if (selected) accent else muted,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (selected) Text("正在使用", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}
