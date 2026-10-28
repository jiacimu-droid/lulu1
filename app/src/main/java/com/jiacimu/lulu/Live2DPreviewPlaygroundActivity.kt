package com.jiacimu.lulu

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.MigratedDomainStores
import java.io.File
import kotlinx.coroutines.launch

/**
 * The Live2D model library, opened from the desktop without a video call.
 * Models are managed here on their own, with no character involved: import
 * or delete model ZIPs in this list. Which model a character wears is
 * chosen on the video-call screen from this same pool. Opening a model
 * binds it to the reserved library host so the stage, motions, expressions
 * and touch calibration can be tried without placing a call.
 */
class Live2DPreviewPlaygroundActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Live2DModelLibraryScreen { finish() }
            }
        }
    }
}

@Composable
private fun Live2DModelLibraryScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by Live2DAssets.revisions.collectAsState()
    val models = remember(revision) { Live2DAssets.listAvailableModels(context) }
    val modeled = remember(revision) { Live2DAssets.listModeledCharacters(context) }
    val characterSettings by MigratedDomainStores.characters.settings.collectAsState()
    var previewing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Live2DAssets.ArchiveEntry?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            notice = runCatching { Live2DAssets.importModelToLibrary(context, uri) }
                .getOrElse { it.message ?: "导入失败" }
            importing = false
        }
    }
    if (previewing) {
        Live2DPreviewPlaygroundContent(Live2DAssets.LIBRARY_OWNER_ID) { previewing = false }
        return
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("Live2D 模型库", fontSize = 18.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("关闭") }
        }
        Text(
            "模型在这里统一管理，跟角色分开：上传、删除都在这页。" +
                "打视频时，在通话页面从这个库里选这次用哪个模型。",
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = !importing,
            onClick = { importLauncher.launch("application/zip") },
        ) {
            Text(if (importing) "正在导入…" else "导入新模型（ZIP）")
        }
        if (notice.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(notice, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        if (models.isEmpty()) {
            Text("模型库还是空的，先导入一个模型 ZIP。", fontSize = 13.sp)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(models) { entry ->
                    val users = modeled.filter { (id, _) ->
                        Live2DAssets.currentModelSettings(context, id) == entry.settings
                    }.map { (id, fallback) ->
                        characterSettings[id]?.displayName ?: fallback
                    }
                    val health = remember(entry.settings, revision) {
                        Live2DAssets.libraryModelHealth(
                            context, File(Live2DAssets.base(context), entry.settings))
                    }
                    val portraitBitmap = remember(health.portrait?.absolutePath,
                        health.portrait?.lastModified()) {
                        health.portrait?.let { file ->
                            runCatching { BitmapFactory.decodeFile(file.absolutePath) }
                                .getOrNull()
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row {
                                if (portraitBitmap != null) {
                                    Image(
                                        portraitBitmap.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.size(64.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                }
                                Column {
                                    Text(entry.label, fontSize = 15.sp)
                                    Text(
                                        if (users.isEmpty()) "还没有角色在用"
                                        else "正在使用：" + users.joinToString("、"),
                                        fontSize = 12.sp,
                                    )
                                    Text(
                                        buildList {
                                            add("表情 ${health.expressions} 个")
                                            add("动作 ${health.motions} 个")
                                            if (health.wolfPackEligible) add("含小狼专属动作包")
                                            if (health.touchCalibrated) add("触摸已校准")
                                        }.joinToString(" · "),
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                            Row {
                                TextButton(onClick = {
                                    if (Live2DAssets.useExistingModel(
                                            context, Live2DAssets.LIBRARY_OWNER_ID, entry.settings,
                                        )
                                    ) {
                                        previewing = true
                                    } else {
                                        notice = "打开失败，模型文件可能不完整"
                                    }
                                }) { Text("打开预览 · 试动作") }
                                TextButton(onClick = { pendingDelete = entry }) {
                                    Text("删除")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除模型") },
            text = {
                Text(
                    "把「" + entry.label + "」从模型库里彻底删除？" +
                        "正在用它的角色会自动改用库里剩下的模型；没有剩余模型时，那个角色就没有模型可用。" +
                        "模型文件会一起删掉。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    notice = if (Live2DAssets.deleteFromLibrary(context, entry.settings)) {
                        "已删除 " + entry.label
                    } else {
                        "删除失败"
                    }
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}
