package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The row is only a view/filter. Stored wishes are NEVER moved or deleted by filtering. */
@Composable
internal fun SelfWishFolderTabs(
    folders: List<SelfWishFolder>,
    pages: List<SelfPageEntry>,
    selected: String,
    onSelect: (String) -> Unit,
    onCreate: (String) -> Boolean,
    onRename: (String, String) -> Boolean,
) {
    var dialog by remember { mutableStateOf<String?>(null) } // "create" or folder ID
    var input by remember(dialog) { mutableStateOf(
        folders.firstOrNull { it.id == dialog }?.name.orEmpty()
    ) }
    var warning by remember(dialog) { mutableStateOf("") }
    val hasLegacy = pages.any {
        SelfCompassRules.state(it) != SelfCompassRules.FULFILLED &&
            SelfWishFolders.categoryId(it) == SelfWishFolders.LEGACY_HELD
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelfWishFolderChip("全部", selected == SelfWishFolders.ALL) {
            onSelect(SelfWishFolders.ALL)
        }
        SelfWishFolderChip("未分类", selected == SelfWishFolders.UNFILED) {
            onSelect(SelfWishFolders.UNFILED)
        }
        if (hasLegacy) {
            SelfWishFolderChip("原珍藏", selected == SelfWishFolders.LEGACY_HELD) {
                onSelect(SelfWishFolders.LEGACY_HELD)
            }
        }
        folders.forEach { folder ->
            SelfWishFolderChip(folder.name, selected == folder.id) {
                onSelect(folder.id)
            }
        }
        SelfWishFolderChip("已实现", selected == SelfWishFolders.COMPLETED, completed = true) {
            onSelect(SelfWishFolders.COMPLETED)
        }
        OutlinedButton(
            onClick = { dialog = "create" },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            shape = RoundedCornerShape(13.dp),
            border = BorderStroke(1.dp, SelfPrefacePalette.outline),
        ) {
            Icon(Icons.Outlined.Add, "新建分类", Modifier.size(17.dp),
                tint = SelfPrefacePalette.sageDeep)
            Spacer(Modifier.width(3.dp))
            Text("文件夹", fontSize = 12.sp, color = SelfPrefacePalette.sageDeep)
        }
        if (folders.isNotEmpty()) {
            IconButton(onClick = { dialog = "manage" }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Outlined.Edit, "重命名文件夹", Modifier.size(18.dp),
                    tint = SelfPrefacePalette.sageDeep)
            }
        }
    }

    when (dialog) {
        "manage" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("管理我的分类") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    folders.forEach { folder ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(folder.name, Modifier.weight(1f),
                                color = SelfPrefacePalette.ink)
                            IconButton(onClick = { dialog = folder.id }) {
                                Icon(Icons.Outlined.Edit, "重命名"+folder.name)
                            }
                        }
                    }
                    Text("重命名只改变文件夹名称，不改变里面的心愿。",
                        color = SelfPrefacePalette.muted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { dialog = null }) { Text("完成") }
            }
        )
        null -> Unit
        else -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (dialog == "create") "新建心愿文件夹" else "重命名文件夹") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        input, { input = it.take(32); warning = "" },
                        label = { Text("文件夹名称") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    if (warning.isNotBlank()) Text(warning,
                        color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = input.isNotBlank(),
                    onClick = {
                        val ok = if (dialog == "create") onCreate(input)
                            else onRename(dialog!!, input)
                        if (ok) dialog = null
                        else warning = "名称重复、记录发生变化或保存失败，请检查后重试。"
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) { Text("取消") }
            },
        )
    }
}

@Composable
internal fun SelfWishFolderChip(
    label: String,
    selected: Boolean,
    completed: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) SelfPrefacePalette.sageDeep else SelfPrefacePalette.paperBright,
        border = BorderStroke(1.dp,
            if (selected) SelfPrefacePalette.sageDeep else SelfPrefacePalette.outline),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(
                if (completed) Icons.Outlined.CheckCircleOutline else Icons.Outlined.FolderOpen,
                null, Modifier.size(15.dp),
                tint = if (selected) Color.White else SelfPrefacePalette.sage,
            )
            Text(label, color = if (selected) Color.White else SelfPrefacePalette.ink,
                fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}
