package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A launchable home, not a second system phone or an unverified virtual screen. */
private data class RolePhoneApp(
    val route: String, val label: String, val icon: ImageVector, val tint: Color,
)

private val RolePhoneApps = listOf(
    RolePhoneApp("moments", "朋友圈", Icons.Outlined.PeopleOutline, Color(0xFF527C71)),
    RolePhoneApp("album", "相册", Icons.Outlined.PhotoLibrary, Color(0xFF8D7193)),
    RolePhoneApp("memos", "备忘录", Icons.Outlined.Description, Color(0xFFB08746)),
    RolePhoneApp("calendar", "日程", Icons.Outlined.EventNote, Color(0xFF6083AD)),
    RolePhoneApp("favorites", "收藏", Icons.Outlined.StarOutline, Color(0xFFB47765)),
    RolePhoneApp("activity", "行动记录", Icons.Outlined.History, Color(0xFF69858F)),
    RolePhoneApp("profile", "个人", Icons.Outlined.PersonOutline, Color(0xFF747C8A)),
)
private val RolePhoneDock = listOf(
    RolePhoneApp("messages", "消息", Icons.Outlined.ChatBubbleOutline, Color(0xFF598B76)),
    RolePhoneApp("browser", "浏览器", Icons.Outlined.TravelExplore, Color(0xFF637FAA)),
    RolePhoneApp("screen", "操作现场", Icons.Outlined.PhoneAndroid, Color(0xFF65727F)),
)

@Composable
internal fun RolePhoneDesktop(
    roleName: String,
    avatarUri: String?,
    momentUnread: Int,
    onOpen: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 34.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            LuluProfileAvatar(imageUri = avatarUri,
                fallback = roleName.take(1).ifBlank { "角" }, size = 56)
            Column(Modifier.weight(1f)) {
                Text(roleName, fontSize = 24.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("我的手机", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalArrangement = Arrangement.spacedBy(23.dp),
        ) {
            items(RolePhoneApps, key = { it.route }) { app ->
                RolePhoneAppTile(app, momentUnread.takeIf { app.route == "moments" } ?: 0,
                    onOpen)
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            shape = RoundedCornerShape(27.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 15.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                RolePhoneDock.forEach { app -> RolePhoneAppTile(app, 0, onOpen) }
            }
        }
    }
}

@Composable
private fun RolePhoneAppTile(app: RolePhoneApp, badge: Int, onOpen: (String) -> Unit) {
    Column(
        modifier = Modifier.widthIn(min = 66.dp).clickable { onOpen(app.route) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Surface(
                modifier = Modifier.size(61.dp),
                shape = RoundedCornerShape(19.dp),
                color = app.tint.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, app.tint.copy(alpha = 0.13f)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(app.icon, contentDescription = null, tint = app.tint,
                        modifier = Modifier.size(29.dp))
                }
            }
            if (badge > 0) {
                Surface(
                    shape = RoundedCornerShape(50), color = Color(0xFFB95D58),
                    modifier = Modifier.offset(x = 4.dp, y = (-4).dp),
                ) {
                    Text(badge.coerceAtMost(99).toString(),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontSize = 10.sp, color = Color.White)
                }
            }
        }
        Text(app.label, fontSize = 12.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground)
    }
}
