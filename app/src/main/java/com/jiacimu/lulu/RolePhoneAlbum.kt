package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.design.LuluColors

/** Per-character durable media, not a global/shared memory gallery. */
@Composable
internal fun RolePhoneAlbum(characterId: String) {
    val context = LocalContext.current
    val revision by RolePhotoAlbumStore.revisions.collectAsState()
    val photos = remember(context, characterId, revision) {
        RolePhotoAlbumStore.list(context, characterId)
    }
    var selected by remember(characterId) { mutableStateOf<RoleAlbumPhoto?>(null) }
    if (photos.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(26.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有保存照片", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Text("角色可以自行保存收到的聊天图片、网络图片，" +
                "也可以在已经授权的识图过程中选择保留截图。",
                color = LuluColors.Muted, fontSize = 13.sp)
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Text("共保存 ${photos.size} 张 · 这个角色的私人相册",
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 8.dp),
                color = LuluColors.Muted, fontSize = 12.sp)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(108.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(photos, key = RoleAlbumPhoto::id) { photo ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { selected = photo },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            LuluProfileAvatar(photo.uri, "图", 100)
                            Text(photo.source, maxLines = 1, fontSize = 11.sp,
                                color = LuluColors.Muted,
                                modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                }
            }
        }
    }
    selected?.let { photo ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("相册 · ${photo.source}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    LuluProfileAvatar(photo.uri, "照片", 240)
                    if (photo.caption.isNotBlank())
                        Text(photo.caption, style = MaterialTheme.typography.bodyMedium)
                    Text(photo.savedAt.take(16).replace('T', ' '),
                        fontSize = 12.sp, color = LuluColors.Muted)
                    if (photo.sourceUrl.isNotBlank())
                        Text("来源：${photo.sourceUrl.take(160)}",
                            fontSize = 11.sp, color = LuluColors.Muted)
                }
            },
            confirmButton = {
                TextButton(onClick = { selected = null }) { Text("返回") }
            },
            dismissButton = {
                TextButton(onClick = {
                    RolePhotoAlbumStore.delete(context, characterId, photo.id)
                    selected = null
                }) { Text("删除照片") }
            },
        )
    }
}
